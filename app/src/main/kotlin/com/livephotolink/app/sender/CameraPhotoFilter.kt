package com.livephotolink.app.sender

/**
 * Pure filtering rules for Samsung / Android DCIM/Camera photos.
 * Excludes screenshots, downloads, trashed/pending files, and unsupported formats.
 */
object CameraPhotoFilter {

    private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "heic", "heif", "dng")
    private val SUPPORTED_MIME_TYPES = setOf(
        "image/jpeg",
        "image/jpg",
        "image/png",
        "image/heic",
        "image/heif",
        "image/x-adobe-dng",
        "image/dng"
    )

    fun isCameraPhoto(
        relativePath: String?,
        displayName: String?,
        mimeType: String?,
        isPending: Int,
        isTrashed: Int = 0
    ): Boolean {
        if (isPending != 0 || isTrashed != 0) return false
        val name = displayName?.trim().orEmpty()
        if (name.isEmpty()) return false
        if (name.startsWith(".") || name.contains(".pending", ignoreCase = true) || name.contains(".trashed", ignoreCase = true)) {
            return false
        }

        val lowerName = name.lowercase()
        if (lowerName.contains("screenshot") || lowerName.contains("screen_shot") || lowerName.startsWith("scr_")) {
            return false
        }

        val normalizedPath = (relativePath ?: "")
            .replace('\\', '/')
            .trim()
            .trimStart('/')
            .lowercase()

        // Must be inside DCIM/Camera (or subfolder like DCIM/Camera/...)
        val inCameraFolder = normalizedPath == "dcim/camera" ||
            normalizedPath.startsWith("dcim/camera/")
        if (!inCameraFolder) return false

        // Reject screenshots/downloads even if somehow nested
        if (normalizedPath.contains("screenshot") || normalizedPath.contains("download")) {
            return false
        }

        val ext = lowerName.substringAfterLast('.', "")
        val mimeLower = mimeType?.lowercase().orEmpty()
        return ext in SUPPORTED_EXTENSIONS || mimeLower in SUPPORTED_MIME_TYPES
    }

    fun isAfterSessionStart(
        dateAddedSec: Long,
        dateModifiedSec: Long,
        sessionStartSec: Long
    ): Boolean {
        val effectiveSec = maxOf(dateAddedSec, dateModifiedSec)
        return effectiveSec >= sessionStartSec
    }
}
