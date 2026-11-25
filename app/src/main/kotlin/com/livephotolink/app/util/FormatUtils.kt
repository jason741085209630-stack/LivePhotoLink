package com.livephotolink.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatUtils {

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    fun formatSpeed(bytesPerSec: Long): String {
        return "${formatBytes(bytesPerSec)}/s"
    }

    fun formatResolution(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "未知解析度"
        val mp = (width.toLong() * height.toLong()) / 1_000_000.0
        return if (mp >= 1.0) {
            String.format(Locale.US, "%d × %d (%.1f MP)", width, height, mp)
        } else {
            "$width × $height"
        }
    }

    fun formatTime(timestampMs: Long): String {
        if (timestampMs <= 0L) return "--:--:--"
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestampMs))
    }

    fun formatDateTime(timestampMs: Long): String {
        if (timestampMs <= 0L) return ""
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestampMs))
    }
}
