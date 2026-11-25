package com.livephotolink.app.sender

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections

data class CapturedPhotoCandidate(
    val mediaId: Long,
    val contentUri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val dateTakenMs: Long
)

/**
 * Monitors MediaStore for newly captured Samsung Camera photos in DCIM/Camera.
 * Ensures each photo has finished writing (IS_PENDING == 0, stable non-zero size, readable stream)
 * before emitting it to the transfer queue.
 */
class CameraPhotoMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPhotoReady: (CapturedPhotoCandidate) -> Unit
) {
    private val resolver = context.contentResolver
    private val trackedIds = Collections.synchronizedSet(mutableSetOf<Long>())
    private val verifyingIds = Collections.synchronizedSet(mutableSetOf<Long>())

    private var sessionStartSec: Long = System.currentTimeMillis() / 1000L
    private var observer: ContentObserver? = null
    private var pollJob: Job? = null

    fun start() {
        stop()
        sessionStartSec = (System.currentTimeMillis() / 1000L) - 2L
        trackedIds.clear()
        verifyingIds.clear()

        // Snapshot existing recent IDs so historical photos are never synced
        snapshotExistingIds()

        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                scope.launch(Dispatchers.IO) {
                    scanForNewCameraPhotos()
                }
            }
        }
        observer = contentObserver
        runCatching {
            resolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                contentObserver
            )
        }.onFailure {
            Log.e(TAG, "Failed to register MediaStore ContentObserver", it)
        }

        // Gentle fallback poll every 2.5s in case Samsung Camera batches MediaStore updates
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2_500L)
                scanForNewCameraPhotos()
            }
        }
        Log.i(TAG, "CameraPhotoMonitor started (sessionStartSec=$sessionStartSec)")
    }

    fun stop() {
        observer?.let { runCatching { resolver.unregisterContentObserver(it) } }
        observer = null
        pollJob?.cancel()
        pollJob = null
    }

    private fun snapshotExistingIds() {
        runCatching {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
            val args = arrayOf((sessionStartSec - 300L).toString())
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    trackedIds.add(cursor.getLong(idCol))
                }
            }
        }
    }

    private fun scanForNewCameraPhotos() {
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.MIME_TYPE)
            add(MediaStore.Images.Media.RELATIVE_PATH)
            add(MediaStore.Images.Media.SIZE)
            add(MediaStore.Images.Media.WIDTH)
            add(MediaStore.Images.Media.HEIGHT)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.DATE_MODIFIED)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.Images.Media.IS_PENDING)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Images.Media.IS_TRASHED)
            }
        }.toTypedArray()

        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ? OR ${MediaStore.Images.Media.DATE_MODIFIED} >= ?"
        val args = arrayOf(sessionStartSec.toString(), sessionStartSec.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC"

        runCatching {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val relPathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val modCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val pendingCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING)
                val trashedCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    cursor.getColumnIndex(MediaStore.Images.Media.IS_TRASHED)
                } else -1

                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idCol)
                    if (trackedIds.contains(mediaId) || verifyingIds.contains(mediaId)) continue

                    val displayName = cursor.getString(nameCol)
                    val mimeType = cursor.getString(mimeCol) ?: "image/jpeg"
                    val relativePath = cursor.getString(relPathCol)
                    val dateAdded = cursor.getLong(addedCol)
                    val dateModified = cursor.getLong(modCol)
                    val isPending = cursor.getInt(pendingCol)
                    val isTrashed = if (trashedCol >= 0) cursor.getInt(trashedCol) else 0

                    if (!CameraPhotoFilter.isAfterSessionStart(dateAdded, dateModified, sessionStartSec)) {
                        trackedIds.add(mediaId)
                        continue
                    }

                    // If still pending write, skip for now (will re-check on next onChange/poll)
                    if (isPending != 0) continue

                    if (!CameraPhotoFilter.isCameraPhoto(relativePath, displayName, mimeType, isPending, isTrashed)) {
                        trackedIds.add(mediaId)
                        continue
                    }

                    val size = cursor.getLong(sizeCol)
                    val width = cursor.getInt(widthCol)
                    val height = cursor.getInt(heightCol)
                    val dateTaken = cursor.getLong(takenCol).takeIf { it > 0L } ?: System.currentTimeMillis()
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)

                    verifyingIds.add(mediaId)
                    scope.launch(Dispatchers.IO) {
                        verifyAndEmit(
                            mediaId = mediaId,
                            uri = uri,
                            displayName = displayName ?: "IMG_$mediaId.jpg",
                            mimeType = mimeType,
                            initialSize = size,
                            width = width,
                            height = height,
                            dateTakenMs = dateTaken
                        )
                    }
                }
            }
        }.onFailure {
            Log.e(TAG, "Error querying MediaStore", it)
        }
    }

    /**
     * Waits until the photo file is completely written (size > 0, stable across consecutive checks,
     * IS_PENDING == 0, and readable via ContentResolver). Handles Samsung 50MP/200MP HDR processing delay.
     */
    private suspend fun verifyAndEmit(
        mediaId: Long,
        uri: Uri,
        displayName: String,
        mimeType: String,
        initialSize: Long,
        width: Int,
        height: Int,
        dateTakenMs: Long
    ) {
        try {
            var lastSize = initialSize
            var stableChecks = 0

            for (attempt in 0 until MAX_STABILITY_ATTEMPTS) {
                delay(STABILITY_CHECK_INTERVAL_MS)
                val info = queryCurrentFileState(uri) ?: return
                if (info.isPending != 0) {
                    stableChecks = 0
                    continue
                }
                val actualSize = info.sizeBytes
                if (actualSize > 0L && actualSize == lastSize) {
                    stableChecks++
                    if (stableChecks >= REQUIRED_STABLE_CHECKS && isReadable(uri)) {
                        val orientation = readExifOrientation(uri)
                        if (trackedIds.add(mediaId)) {
                            onPhotoReady(
                                CapturedPhotoCandidate(
                                    mediaId = mediaId,
                                    contentUri = uri,
                                    displayName = info.displayName.ifBlank { displayName },
                                    mimeType = mimeType,
                                    sizeBytes = actualSize,
                                    width = if (info.width > 0) info.width else width,
                                    height = if (info.height > 0) info.height else height,
                                    orientation = orientation,
                                    dateTakenMs = dateTakenMs
                                )
                            )
                        }
                        return
                    }
                } else {
                    lastSize = actualSize
                    stableChecks = 0
                }
            }
            Log.w(TAG, "Photo $displayName did not stabilize in time; skipping")
        } finally {
            verifyingIds.remove(mediaId)
        }
    }

    private data class FileState(
        val displayName: String,
        val sizeBytes: Long,
        val width: Int,
        val height: Int,
        val isPending: Int
    )

    private fun queryCurrentFileState(uri: Uri): FileState? {
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.IS_PENDING
        )
        return runCatching {
            resolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                FileState(
                    displayName = c.getString(0).orEmpty(),
                    sizeBytes = c.getLong(1),
                    width = c.getInt(2),
                    height = c.getInt(3),
                    isPending = c.getInt(4)
                )
            }
        }.getOrNull()
    }

    private fun isReadable(uri: Uri): Boolean {
        return runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(16)
                stream.read(buf) > 0
            } ?: false
        }.getOrDefault(false)
    }

    private fun readExifOrientation(uri: Uri): Int {
        return runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
    }

    companion object {
        private const val TAG = "CameraPhotoMonitor"
        private const val STABILITY_CHECK_INTERVAL_MS = 300L
        private const val REQUIRED_STABLE_CHECKS = 2
        private const val MAX_STABILITY_ATTEMPTS = 40 // up to 12 seconds for 200MP HDR
    }
}
