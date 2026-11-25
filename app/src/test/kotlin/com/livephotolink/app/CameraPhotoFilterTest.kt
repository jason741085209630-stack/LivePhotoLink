package com.livephotolink.app

import com.livephotolink.app.sender.CameraPhotoFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPhotoFilterTest {

    @Test
    fun acceptsSamsungCameraFormats() {
        assertTrue(CameraPhotoFilter.isCameraPhoto("DCIM/Camera/", "20261125_101500.jpg", "image/jpeg", 0))
        assertTrue(CameraPhotoFilter.isCameraPhoto("DCIM/Camera", "20261125_101501.heic", "image/heic", 0))
        assertTrue(CameraPhotoFilter.isCameraPhoto("DCIM/Camera/", "20261125_101502.png", "image/png", 0))
        assertTrue(CameraPhotoFilter.isCameraPhoto("DCIM/Camera/", "20261125_101503.dng", "image/x-adobe-dng", 0))
    }

    @Test
    fun rejectsScreenshotsDownloadsAndPendingFiles() {
        assertFalse(CameraPhotoFilter.isCameraPhoto("DCIM/Screenshots/", "Screenshot_2026.png", "image/png", 0))
        assertFalse(CameraPhotoFilter.isCameraPhoto("Pictures/Screenshots/", "Screenshot_2026.jpg", "image/jpeg", 0))
        assertFalse(CameraPhotoFilter.isCameraPhoto("Download/", "photo.jpg", "image/jpeg", 0))
        assertFalse(CameraPhotoFilter.isCameraPhoto("Pictures/LINE/", "12345.jpg", "image/jpeg", 0))
        assertFalse(CameraPhotoFilter.isCameraPhoto("DCIM/Camera/", "20261125_101500.jpg", "image/jpeg", isPending = 1))
        assertFalse(CameraPhotoFilter.isCameraPhoto("DCIM/Camera/", "20261125_101500.jpg", "image/jpeg", isPending = 0, isTrashed = 1))
        assertFalse(CameraPhotoFilter.isCameraPhoto("DCIM/Camera/", ".pending-1234-IMG.jpg", "image/jpeg", 0))
        assertFalse(CameraPhotoFilter.isCameraPhoto("DCIM/Camera/", "Screenshot_in_camera.jpg", "image/jpeg", 0))
    }

    @Test
    fun filtersHistoricalPhotosBySessionStart() {
        val sessionStart = 1_700_000_000L
        assertFalse(CameraPhotoFilter.isAfterSessionStart(sessionStart - 10, sessionStart - 5, sessionStart))
        assertTrue(CameraPhotoFilter.isAfterSessionStart(sessionStart, sessionStart, sessionStart))
        assertTrue(CameraPhotoFilter.isAfterSessionStart(sessionStart + 5, sessionStart + 5, sessionStart))
    }
}
