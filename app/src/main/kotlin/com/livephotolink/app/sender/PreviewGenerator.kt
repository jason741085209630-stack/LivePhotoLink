package com.livephotolink.app.sender

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Generates a compact JPEG preview for Fast Preview mode using subsampled decoding
 * so 50MP/200MP Samsung photos never cause OOM.
 */
object PreviewGenerator {

    private const val MAX_PREVIEW_DIM = 1600
    private const val PREVIEW_JPEG_QUALITY = 82
    const val FAST_PREVIEW_MIN_BYTES = 1_500_000L // 1.5 MB threshold

    data class PreviewResult(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val orientation: Int
    )

    fun generatePreview(context: Context, uri: Uri): PreviewResult? {
        return runCatching {
            val resolver = context.contentResolver

            val orientation = resolver.openInputStream(uri)?.use { stream ->
                runCatching {
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL

            // Pass 1: bounds only
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOpts)
            }
            val srcW = boundsOpts.outWidth
            val srcH = boundsOpts.outHeight
            if (srcW <= 0 || srcH <= 0) return null

            var sampleSize = 1
            val maxDim = max(srcW, srcH)
            while (maxDim / (sampleSize * 2) >= MAX_PREVIEW_DIM) {
                sampleSize *= 2
            }

            // Pass 2: subsampled decode
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: return null

            try {
                val scale = MAX_PREVIEW_DIM.toFloat() / max(bitmap.width, bitmap.height).toFloat()
                val finalBitmap = if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    bitmap
                }
                try {
                    val out = ByteArrayOutputStream()
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_JPEG_QUALITY, out)
                    PreviewResult(
                        bytes = out.toByteArray(),
                        width = finalBitmap.width,
                        height = finalBitmap.height,
                        orientation = orientation
                    )
                } finally {
                    if (finalBitmap !== bitmap) finalBitmap.recycle()
                }
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
    }
}
