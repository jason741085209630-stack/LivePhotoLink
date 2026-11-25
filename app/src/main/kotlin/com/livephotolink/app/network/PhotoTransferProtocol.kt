package com.livephotolink.app.network

import kotlinx.serialization.encodeToString
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Reusable streaming photo transfer helpers over [EncryptedChannel].
 * Streams files in 64 KB chunks so 200MP photos never cause OOM.
 * Verifies exact byte count and SHA-256 before atomically committing the file on the receiver.
 */
object PhotoTransferProtocol {

    fun computeSha256AndSize(streamProvider: () -> InputStream): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(ProtocolConstants.CHUNK_SIZE)
        var total = 0L
        streamProvider().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                total += read
            }
        }
        return CryptoEngine.toHex(digest.digest()) to total
    }

    fun sendPhotoStream(
        channel: EncryptedChannel,
        header: PhotoStartHeader,
        inputStream: InputStream,
        onProgress: (bytesSent: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit = { _, _, _ -> }
    ): PhotoAckMessage {
        channel.sendJson(
            ProtocolConstants.FRAME_PHOTO_START,
            protocolJson.encodeToString(header)
        )

        val buffer = ByteArray(ProtocolConstants.CHUNK_SIZE)
        var bytesSent = 0L
        val startNs = System.nanoTime()
        var lastProgressMs = 0L

        inputStream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                channel.sendFrame(ProtocolConstants.FRAME_PHOTO_CHUNK, chunk)
                bytesSent += read

                val nowMs = System.currentTimeMillis()
                if (nowMs - lastProgressMs >= 80L || bytesSent == header.totalSize) {
                    val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0).coerceAtLeast(0.001)
                    val speed = (bytesSent / elapsedSec).toLong()
                    onProgress(bytesSent, header.totalSize, speed)
                    lastProgressMs = nowMs
                }
            }
        }

        channel.sendFrame(ProtocolConstants.FRAME_PHOTO_END)

        // Wait for PHOTO_ACK (skip any interleaved PONG frames)
        while (true) {
            val frame = channel.readFrame()
            when (frame.type) {
                ProtocolConstants.FRAME_PHOTO_ACK -> {
                    return protocolJson.decodeFromString<PhotoAckMessage>(
                        String(frame.payload, StandardCharsets.UTF_8)
                    )
                }
                ProtocolConstants.FRAME_PONG -> continue
                ProtocolConstants.FRAME_PING -> channel.sendFrame(ProtocolConstants.FRAME_PONG)
                else -> throw IOException("Unexpected frame waiting for PHOTO_ACK: ${frame.type}")
            }
        }
    }

    data class ReceivedTransferResult(
        val header: PhotoStartHeader,
        val file: File,
        val verifiedSha256: String
    )

    /**
     * Receives a single photo after FRAME_PHOTO_START has already been read.
     * Writes to a temporary .part file and only renames atomically after size + SHA-256 match.
     */
    fun receivePhotoBody(
        channel: EncryptedChannel,
        header: PhotoStartHeader,
        targetDir: File,
        onProgress: (bytesReceived: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit = { _, _, _ -> }
    ): ReceivedTransferResult? {
        if (!targetDir.exists()) targetDir.mkdirs()
        val safeName = sanitizeFileName(header.fileName)
        val suffix = if (header.isPreview) "_preview" else ""
        val base = safeName.substringBeforeLast('.', safeName)
        val ext = safeName.substringAfterLast('.', "jpg")
        val finalFile = File(targetDir, "${header.photoId}_${base}${suffix}.$ext")
        val partFile = File(targetDir, ".${header.transferId}.part")

        val digest = MessageDigest.getInstance("SHA-256")
        var bytesReceived = 0L
        val startNs = System.nanoTime()
        var lastProgressMs = 0L

        try {
            BufferedOutputStream(FileOutputStream(partFile), ProtocolConstants.CHUNK_SIZE).use { out ->
                loop@ while (true) {
                    val frame = channel.readFrame()
                    when (frame.type) {
                        ProtocolConstants.FRAME_PHOTO_CHUNK -> {
                            out.write(frame.payload)
                            digest.update(frame.payload)
                            bytesReceived += frame.payload.size

                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastProgressMs >= 80L || bytesReceived == header.totalSize) {
                                val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0).coerceAtLeast(0.001)
                                val speed = (bytesReceived / elapsedSec).toLong()
                                onProgress(bytesReceived, header.totalSize, speed)
                                lastProgressMs = nowMs
                            }
                        }
                        ProtocolConstants.FRAME_PHOTO_END -> break@loop
                        ProtocolConstants.FRAME_PING -> channel.sendFrame(ProtocolConstants.FRAME_PONG)
                        else -> throw IOException("Unexpected frame during photo transfer: ${frame.type}")
                    }
                }
                out.flush()
            }

            val actualSha = CryptoEngine.toHex(digest.digest())
            val sizeMatch = bytesReceived == header.totalSize
            val shaMatch = actualSha.equals(header.sha256, ignoreCase = true)

            if (!sizeMatch || !shaMatch) {
                partFile.delete()
                val err = if (!sizeMatch) {
                    "檔案大小不符 (expected=${header.totalSize}, actual=$bytesReceived)"
                } else {
                    "SHA-256 校驗失敗"
                }
                channel.sendJson(
                    ProtocolConstants.FRAME_PHOTO_ACK,
                    protocolJson.encodeToString(
                        PhotoAckMessage(
                            transferId = header.transferId,
                            photoId = header.photoId,
                            ok = false,
                            sha256 = actualSha,
                            error = err
                        )
                    )
                )
                return null
            }

            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }

            channel.sendJson(
                ProtocolConstants.FRAME_PHOTO_ACK,
                protocolJson.encodeToString(
                    PhotoAckMessage(
                        transferId = header.transferId,
                        photoId = header.photoId,
                        ok = true,
                        sha256 = actualSha
                    )
                )
            )

            return ReceivedTransferResult(
                header = header,
                file = finalFile,
                verifiedSha256 = actualSha
            )
        } catch (e: Exception) {
            partFile.delete()
            throw e
        }
    }

    fun sanitizeFileName(name: String): String {
        val clean = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return clean.ifBlank { "photo.jpg" }
    }
}
