package com.livephotolink.app.receiver

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.livephotolink.app.model.ReceivedPhoto
import com.livephotolink.app.model.StorageMode
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Manages received photo files on the tablet across APP_PRIVATE, SYSTEM_GALLERY, and DISPLAY_ONLY modes.
 * Also handles safe metadata extraction (without decoding full 200MP bitmaps) and DNG preview rendering.
 */
class PhotoStorageManager(private val context: Context) {

    private val privateDir: File
        get() = File(context.filesDir, "received_photos").apply { if (!exists()) mkdirs() }

    private val displayOnlyDir: File
        get() = File(context.cacheDir, "live_display").apply { if (!exists()) mkdirs() }

    private val dngPreviewCacheDir: File
        get() = File(context.cacheDir, "dng_previews").apply { if (!exists()) mkdirs() }

    fun getTargetDir(mode: StorageMode): File {
        return when (mode) {
            StorageMode.DISPLAY_ONLY -> displayOnlyDir
            StorageMode.APP_PRIVATE, StorageMode.SYSTEM_GALLERY -> privateDir
        }
    }

    fun readImageMetadata(file: File, fallbackWidth: Int, fallbackHeight: Int, fallbackOrientation: Int): Triple<Int, Int, Int> {
        var width = fallbackWidth
        var height = fallbackHeight
        var orientation = fallbackOrientation

        runCatching {
            val exif = ExifInterface(file.absolutePath)
            val exifOrient = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED
            )
            if (exifOrient != ExifInterface.ORIENTATION_UNDEFINED) {
                orientation = exifOrient
            }
            if (width <= 0 || height <= 0) {
                val exifW = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                val exifH = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                if (exifW > 0 && exifH > 0) {
                    width = exifW
                    height = exifH
                }
            }
        }

        if (width <= 0 || height <= 0) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    width = opts.outWidth
                    height = opts.outHeight
                }
            }
        }
        return Triple(width, height, orientation)
    }

    /**
     * For DNG files, extracts an embedded preview or downsampled JPEG into cache so Coil renders it reliably.
     * For JPEG/PNG/HEIC, returns the original file directly (Coil handles subsampling and EXIF rotation).
     */
    fun resolveDisplayFile(originalFile: File, mimeType: String): File {
        val isDng = originalFile.extension.equals("dng", ignoreCase = true) ||
            mimeType.contains("dng", ignoreCase = true)
        if (!isDng) return originalFile

        val cached = File(dngPreviewCacheDir, "${originalFile.nameWithoutExtension}_preview.jpg")
        if (cached.exists() && cached.length() > 0L) return cached

        // 1. Try embedded EXIF thumbnail if decent size
        runCatching {
            val exif = ExifInterface(originalFile.absolutePath)
            val thumb = exif.thumbnailBytes
            if (thumb != null && thumb.size > 16_000) {
                cached.writeBytes(thumb)
                return cached
            }
        }

        // 2. Fallback: subsampled BitmapFactory decode (supported on Android 10+ for many DNGs)
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(originalFile.absolutePath, bounds)
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (maxDim / (sample * 2) >= 2048) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(originalFile.absolutePath, opts)
            if (bmp != null) {
                try {
                    FileOutputStream(cached).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    return cached
                } finally {
                    bmp.recycle()
                }
            }
        }
        return originalFile
    }

    fun exportToSystemGallery(file: File, displayName: String, mimeType: String): Boolean {
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType.ifBlank { "image/jpeg" })
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/LivePhotoLink"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false

            try {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(file).use { inp ->
                        inp.copyTo(out, bufferSize = 64 * 1024)
                    }
                } ?: return false

                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }.onFailure {
            Log.e(TAG, "Failed to export $displayName to system gallery", it)
        }.getOrDefault(false)
    }

    fun trimDisplayOnlyCache(maxFiles: Int = 100) {
        val files = displayOnlyDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (files.size > maxFiles) {
            files.drop(maxFiles).forEach { it.delete() }
        }
    }

    /**
     * Deletes all temporary session-cached photos (used on disconnect or app close in DISPLAY_ONLY mode).
     */
    fun clearDisplayOnlyCache() {
        displayOnlyDir.listFiles()?.forEach { it.delete() }
        dngPreviewCacheDir.listFiles()?.forEach { it.delete() }
    }

    fun loadExistingPrivatePhotos(): List<ReceivedPhoto> {
        val files = privateDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.contains("_preview.") }
            ?.sortedBy { it.lastModified() }
            ?: return emptyList()

        return files.map { file ->
            val (w, h, orient) = readImageMetadata(file, 0, 0, ExifInterface.ORIENTATION_NORMAL)
            val ext = file.extension.lowercase()
            val mime = when (ext) {
                "png" -> "image/png"
                "heic", "heif" -> "image/heic"
                "dng" -> "image/x-adobe-dng"
                else -> "image/jpeg"
            }
            ReceivedPhoto(
                photoId = file.nameWithoutExtension,
                fileName = file.name.substringAfter("_", file.name),
                filePath = file.absolutePath,
                mimeType = mime,
                fileSize = file.length(),
                width = w,
                height = h,
                orientation = orient,
                receivedAtMs = file.lastModified(),
                sha256 = "",
                isPreview = false
            )
        }
    }

    fun clearAllHistoryAndCache() {
        privateDir.listFiles()?.forEach { it.delete() }
        displayOnlyDir.listFiles()?.forEach { it.delete() }
        dngPreviewCacheDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "PhotoStorageManager"
    }
}
