package com.livephotolink.app

import com.livephotolink.app.model.ReceivedPhoto
import com.livephotolink.app.network.AuthRejectedException
import com.livephotolink.app.network.EncryptedChannel
import com.livephotolink.app.network.PhotoStartHeader
import com.livephotolink.app.network.PhotoTransferProtocol
import com.livephotolink.app.network.ProtocolConstants
import com.livephotolink.app.network.protocolJson
import com.livephotolink.app.receiver.ReceiverRepository
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CryptoAndTransferIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun rejectsWrongPairingPin() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val serverFuture = executor.submit<Throwable?> {
                server.accept().use { sock ->
                    try {
                        EncryptedChannel.handshakeAsServer(sock, "tab-1", "Tab S11", "123456")
                        null
                    } catch (e: Throwable) {
                        e
                    }
                }
            }

            var clientError: Throwable? = null
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { clientSock ->
                try {
                    EncryptedChannel.handshakeAsClient(clientSock, "phone-1", "S24 Ultra", "654321")
                } catch (e: Throwable) {
                    clientError = e
                }
            }

            val serverError = serverFuture.get(5, TimeUnit.SECONDS)
            assertTrue("Expected AuthRejectedException on client", clientError is AuthRejectedException)
            assertTrue("Expected AuthRejectedException on server", serverError is AuthRejectedException)
        }
        executor.shutdownNow()
    }

    @Test
    fun burstTenPhotosEncryptedTransferIntegrity() {
        val pin = "739204"
        val receiveDir = tempFolder.newFolder("received_burst")
        val random = Random(42)
        val payloads = (1..10).map { idx ->
            val size = 128 * 1024 + idx * 17_321 // ~145 KB to ~300 KB each, spanning multiple 64KB chunks
            ByteArray(size).also { random.nextBytes(it) }
        }

        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val receivedFiles = mutableListOf<PhotoTransferProtocol.ReceivedTransferResult>()
            val serverFuture = executor.submit {
                server.accept().use { sock ->
                    val channel = EncryptedChannel.handshakeAsServer(sock, "tab-1", "Galaxy Tab S11", pin)
                    repeat(10) {
                        val frame = channel.readFrame()
                        assertEquals(ProtocolConstants.FRAME_PHOTO_START, frame.type)
                        val header = protocolJson.decodeFromString<PhotoStartHeader>(
                            String(frame.payload, StandardCharsets.UTF_8)
                        )
                        val res = PhotoTransferProtocol.receivePhotoBody(channel, header, receiveDir)
                        assertNotNull(res)
                        receivedFiles.add(res!!)
                    }
                    channel.close()
                }
            }

            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { clientSock ->
                val channel = EncryptedChannel.handshakeAsClient(clientSock, "phone-1", "Galaxy S24 Ultra", pin)
                assertEquals("Galaxy Tab S11", channel.peerDeviceName)

                payloads.forEachIndexed { idx, data ->
                    val (sha, total) = PhotoTransferProtocol.computeSha256AndSize { ByteArrayInputStream(data) }
                    val header = PhotoStartHeader(
                        transferId = "tx-$idx",
                        photoId = "photo-$idx",
                        fileName = "IMG_BURST_$idx.jpg",
                        mimeType = "image/jpeg",
                        totalSize = total,
                        sha256 = sha,
                        width = 4000,
                        height = 3000
                    )
                    val ack = PhotoTransferProtocol.sendPhotoStream(
                        channel = channel,
                        header = header,
                        inputStream = ByteArrayInputStream(data)
                    )
                    assertTrue("ACK must be ok for burst #$idx", ack.ok)
                    assertEquals(sha, ack.sha256)
                }
                channel.close()
            }

            serverFuture.get(15, TimeUnit.SECONDS)
            assertEquals(10, receivedFiles.size)
            payloads.forEachIndexed { idx, expected ->
                val actualBytes = receivedFiles[idx].file.readBytes()
                assertArrayEquals("Byte-for-byte mismatch on burst photo #$idx", expected, actualBytes)
            }
            val leftoverParts = receiveDir.listFiles()?.filter { it.name.endsWith(".part") }.orEmpty()
            assertTrue("No .part temp files should remain", leftoverParts.isEmpty())
        }
        executor.shutdownNow()
    }

    @Test
    fun rejectsCorruptedChecksumAndDeletesPartFile() {
        val pin = "990011"
        val receiveDir = tempFolder.newFolder("received_corrupt")
        val data = ByteArray(100_000) { (it % 251).toByte() }

        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val serverFuture = executor.submit<PhotoTransferProtocol.ReceivedTransferResult?> {
                server.accept().use { sock ->
                    val channel = EncryptedChannel.handshakeAsServer(sock, "tab-1", "Tab S11", pin)
                    val frame = channel.readFrame()
                    val header = protocolJson.decodeFromString<PhotoStartHeader>(
                        String(frame.payload, StandardCharsets.UTF_8)
                    )
                    val res = PhotoTransferProtocol.receivePhotoBody(channel, header, receiveDir)
                    channel.close()
                    res
                }
            }

            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { clientSock ->
                val channel = EncryptedChannel.handshakeAsClient(clientSock, "phone-1", "S24 Ultra", pin)
                val header = PhotoStartHeader(
                    transferId = "tx-bad",
                    photoId = "photo-bad",
                    fileName = "CORRUPT.jpg",
                    mimeType = "image/jpeg",
                    totalSize = data.size.toLong(),
                    sha256 = "0000000000000000000000000000000000000000000000000000000000000000"
                )
                val ack = PhotoTransferProtocol.sendPhotoStream(
                    channel = channel,
                    header = header,
                    inputStream = ByteArrayInputStream(data)
                )
                assertFalse("Corrupted checksum must be rejected", ack.ok)
                channel.close()
            }

            val result = serverFuture.get(10, TimeUnit.SECONDS)
            assertNull("Corrupted file must not be returned as complete", result)
            assertTrue("Target dir must contain no partial or corrupted files", receiveDir.listFiles().isNullOrEmpty())
        }
        executor.shutdownNow()
    }

    @Test
    fun receiverRepositoryUpgradesPreviewInPlaceWithoutDuplication() {
        ReceiverRepository.clearPhotos()
        val previewFile = tempFolder.newFile("p1_preview.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val fullFile = tempFolder.newFile("p1_full.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6)) }

        ReceiverRepository.onPhotoReceived(
            ReceivedPhoto(
                photoId = "p1",
                fileName = "IMG_001.jpg",
                filePath = previewFile.absolutePath,
                mimeType = "image/jpeg",
                fileSize = 3L,
                width = 1600,
                height = 1200,
                orientation = 1,
                receivedAtMs = 1000L,
                sha256 = "aaa",
                isPreview = true
            )
        )
        val eventAfterPreview = ReceiverRepository.state.value.latestPhotoEventId
        assertEquals(1, ReceiverRepository.state.value.photos.size)
        assertTrue(ReceiverRepository.state.value.photos.first().isPreview)

        ReceiverRepository.onPhotoReceived(
            ReceivedPhoto(
                photoId = "p1",
                fileName = "IMG_001.jpg",
                filePath = fullFile.absolutePath,
                mimeType = "image/jpeg",
                fileSize = 6L,
                width = 12000,
                height = 9000,
                orientation = 1,
                receivedAtMs = 2000L,
                sha256 = "bbb",
                isPreview = false
            )
        )
        assertEquals(1, ReceiverRepository.state.value.photos.size)
        assertFalse(ReceiverRepository.state.value.photos.first().isPreview)
        assertEquals(fullFile.absolutePath, ReceiverRepository.state.value.photos.first().filePath)
        assertEquals("Upgrading preview in-place must not re-trigger jump event", eventAfterPreview, ReceiverRepository.state.value.latestPhotoEventId)
        assertFalse("Old preview file should be deleted on upgrade", previewFile.exists())

        // Verify chronological ordering: second photo appended at lastIndex so swiping right shows previous photo
        val secondFile = tempFolder.newFile("p2_full.jpg").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        ReceiverRepository.onPhotoReceived(
            ReceivedPhoto(
                photoId = "p2",
                fileName = "IMG_002.jpg",
                filePath = secondFile.absolutePath,
                mimeType = "image/jpeg",
                fileSize = 3L,
                width = 4000,
                height = 3000,
                orientation = 1,
                receivedAtMs = 3000L,
                sha256 = "ccc",
                isPreview = false
            )
        )
        assertEquals(2, ReceiverRepository.state.value.photos.size)
        assertEquals("p1", ReceiverRepository.state.value.photos[0].photoId)
        assertEquals("p2", ReceiverRepository.state.value.photos[1].photoId)
        ReceiverRepository.clearPhotos()
        assertTrue(ReceiverRepository.state.value.photos.isEmpty())
    }
}
