package com.livephotolink.app.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.nio.charset.StandardCharsets
import javax.crypto.SecretKey

object ProtocolConstants {
    const val PROTOCOL_VERSION = 1
    const val DEFAULT_PORT = 48910
    const val SERVICE_TYPE = "_livephotolink._tcp."
    const val CHUNK_SIZE = 64 * 1024 // 64 KB streaming chunks
    const val MAX_FRAME_SIZE = 2 * 1024 * 1024 // 2 MB max single frame
    const val HEARTBEAT_INTERVAL_MS = 5_000L
    const val SOCKET_READ_TIMEOUT_MS = 20_000

    const val FRAME_HELLO: Byte = 0x01
    const val FRAME_AUTH_ERROR: Byte = 0x02
    const val FRAME_PING: Byte = 0x10
    const val FRAME_PONG: Byte = 0x11
    const val FRAME_PHOTO_START: Byte = 0x20
    const val FRAME_PHOTO_CHUNK: Byte = 0x21
    const val FRAME_PHOTO_END: Byte = 0x22
    const val FRAME_PHOTO_ACK: Byte = 0x23
}

val protocolJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class HelloMessage(
    val version: Int = ProtocolConstants.PROTOCOL_VERSION,
    val role: String, // "SENDER" or "RECEIVER"
    val deviceId: String,
    val deviceName: String,
    val ephemeralPubKeyHex: String,
    val nonceHex: String,
    val authTagHex: String
)

@Serializable
data class PhotoStartHeader(
    val transferId: String,
    val photoId: String,
    val fileName: String,
    val mimeType: String,
    val totalSize: Long,
    val sha256: String,
    val width: Int = 0,
    val height: Int = 0,
    val orientation: Int = 1,
    val dateTakenMs: Long = 0L,
    val isPreview: Boolean = false
)

@Serializable
data class PhotoAckMessage(
    val transferId: String,
    val photoId: String,
    val ok: Boolean,
    val sha256: String = "",
    val error: String? = null
)

data class DecryptedFrame(
    val type: Byte,
    val payload: ByteArray
)

class AuthRejectedException(message: String) : IOException(message)

/**
 * Authenticated, AES-256-GCM encrypted framed channel over a TCP Socket.
 */
class EncryptedChannel private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val sessionKey: SecretKey,
    private val sendDir: Byte,
    private val recvDir: Byte,
    val peerDeviceId: String,
    val peerDeviceName: String
) {
    private var sendSeq: Long = 0L
    private var recvSeq: Long = 0L
    private val writeLock = Any()

    fun sendFrame(type: Byte, payload: ByteArray = ByteArray(0)) {
        synchronized(writeLock) {
            val plain = ByteArray(1 + payload.size)
            plain[0] = type
            if (payload.isNotEmpty()) {
                System.arraycopy(payload, 0, plain, 1, payload.size)
            }
            val encrypted = CryptoEngine.encrypt(sessionKey, sendDir, sendSeq++, plain)
            output.writeInt(encrypted.size)
            output.write(encrypted)
            output.flush()
        }
    }

    fun sendJson(type: Byte, jsonString: String) {
        sendFrame(type, jsonString.toByteArray(StandardCharsets.UTF_8))
    }

    fun readFrame(): DecryptedFrame {
        val len = input.readInt()
        if (len <= 0 || len > ProtocolConstants.MAX_FRAME_SIZE) {
            throw IOException("Invalid encrypted frame size: $len")
        }
        val ciphertext = ByteArray(len)
        input.readFully(ciphertext)
        val plain = CryptoEngine.decrypt(sessionKey, recvDir, recvSeq++, ciphertext)
        if (plain.isEmpty()) {
            throw IOException("Empty decrypted frame")
        }
        val type = plain[0]
        val payload = if (plain.size > 1) plain.copyOfRange(1, plain.size) else ByteArray(0)
        return DecryptedFrame(type, payload)
    }

    fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private fun writePlainFrame(out: DataOutputStream, type: Byte, payload: ByteArray) {
            out.writeByte(type.toInt())
            out.writeInt(payload.size)
            out.write(payload)
            out.flush()
        }

        private fun readPlainFrame(inp: DataInputStream): Pair<Byte, ByteArray> {
            val type = inp.readByte()
            val len = inp.readInt()
            if (len < 0 || len > ProtocolConstants.MAX_FRAME_SIZE) {
                throw IOException("Invalid handshake frame size: $len")
            }
            val payload = ByteArray(len)
            inp.readFully(payload)
            return type to payload
        }

        fun handshakeAsClient(
            socket: Socket,
            localDeviceId: String,
            localDeviceName: String,
            pairingPin: String
        ): EncryptedChannel {
            socket.tcpNoDelay = true
            socket.soTimeout = ProtocolConstants.SOCKET_READ_TIMEOUT_MS
            val inp = DataInputStream(BufferedInputStream(socket.getInputStream(), 65536))
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 65536))

            val pairingKey = CryptoEngine.derivePairingKey(pairingPin)
            val keyPair = CryptoEngine.generateEphemeralKeyPair()
            val pubBytes = keyPair.public.encoded
            val clientNonce = CryptoEngine.randomNonce()
            val authTag = CryptoEngine.computeAuthTag(
                pairingKey = pairingKey,
                role = "SENDER",
                deviceId = localDeviceId,
                pubKeyBytes = pubBytes,
                nonce = clientNonce
            )

            val clientHello = HelloMessage(
                role = "SENDER",
                deviceId = localDeviceId,
                deviceName = localDeviceName,
                ephemeralPubKeyHex = CryptoEngine.toHex(pubBytes),
                nonceHex = CryptoEngine.toHex(clientNonce),
                authTagHex = CryptoEngine.toHex(authTag)
            )
            writePlainFrame(
                out,
                ProtocolConstants.FRAME_HELLO,
                protocolJson.encodeToString(clientHello).toByteArray(StandardCharsets.UTF_8)
            )

            val (respType, respBytes) = readPlainFrame(inp)
            if (respType == ProtocolConstants.FRAME_AUTH_ERROR) {
                throw AuthRejectedException(String(respBytes, StandardCharsets.UTF_8).ifBlank { "配對碼驗證失敗" })
            }
            if (respType != ProtocolConstants.FRAME_HELLO) {
                throw IOException("Unexpected handshake frame type: $respType")
            }

            val serverHello = protocolJson.decodeFromString<HelloMessage>(
                String(respBytes, StandardCharsets.UTF_8)
            )
            val serverPubBytes = CryptoEngine.fromHex(serverHello.ephemeralPubKeyHex)
            val serverNonce = CryptoEngine.fromHex(serverHello.nonceHex)
            val serverTag = CryptoEngine.fromHex(serverHello.authTagHex)

            val verified = CryptoEngine.verifyAuthTag(
                pairingKey = pairingKey,
                role = "RECEIVER",
                deviceId = serverHello.deviceId,
                pubKeyBytes = serverPubBytes,
                nonce = serverNonce,
                providedTag = serverTag
            )
            if (!verified) {
                throw AuthRejectedException("接收端憑證驗證失敗")
            }

            val sessionKey = CryptoEngine.deriveSessionKey(
                localPrivateKey = keyPair.private,
                peerPublicKeyBytes = serverPubBytes,
                clientNonce = clientNonce,
                serverNonce = serverNonce
            )

            return EncryptedChannel(
                socket = socket,
                input = inp,
                output = out,
                sessionKey = sessionKey,
                sendDir = CryptoEngine.DIR_CLIENT_TO_SERVER,
                recvDir = CryptoEngine.DIR_SERVER_TO_CLIENT,
                peerDeviceId = serverHello.deviceId,
                peerDeviceName = serverHello.deviceName
            )
        }

        fun handshakeAsServer(
            socket: Socket,
            localDeviceId: String,
            localDeviceName: String,
            pairingPin: String
        ): EncryptedChannel {
            socket.tcpNoDelay = true
            socket.soTimeout = ProtocolConstants.SOCKET_READ_TIMEOUT_MS
            val inp = DataInputStream(BufferedInputStream(socket.getInputStream(), 65536))
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 65536))

            val (reqType, reqBytes) = try {
                readPlainFrame(inp)
            } catch (e: EOFException) {
                throw IOException("Client disconnected before handshake", e)
            }
            if (reqType != ProtocolConstants.FRAME_HELLO) {
                throw IOException("Expected HELLO frame, got $reqType")
            }

            val clientHello = protocolJson.decodeFromString<HelloMessage>(
                String(reqBytes, StandardCharsets.UTF_8)
            )
            val pairingKey = CryptoEngine.derivePairingKey(pairingPin)
            val clientPubBytes = CryptoEngine.fromHex(clientHello.ephemeralPubKeyHex)
            val clientNonce = CryptoEngine.fromHex(clientHello.nonceHex)
            val clientTag = CryptoEngine.fromHex(clientHello.authTagHex)

            val verified = CryptoEngine.verifyAuthTag(
                pairingKey = pairingKey,
                role = "SENDER",
                deviceId = clientHello.deviceId,
                pubKeyBytes = clientPubBytes,
                nonce = clientNonce,
                providedTag = clientTag
            )
            if (!verified) {
                writePlainFrame(
                    out,
                    ProtocolConstants.FRAME_AUTH_ERROR,
                    "配對碼錯誤，拒絕連線".toByteArray(StandardCharsets.UTF_8)
                )
                throw AuthRejectedException("Client authentication failed from ${clientHello.deviceName}")
            }

            val keyPair = CryptoEngine.generateEphemeralKeyPair()
            val pubBytes = keyPair.public.encoded
            val serverNonce = CryptoEngine.randomNonce()
            val authTag = CryptoEngine.computeAuthTag(
                pairingKey = pairingKey,
                role = "RECEIVER",
                deviceId = localDeviceId,
                pubKeyBytes = pubBytes,
                nonce = serverNonce
            )

            val serverHello = HelloMessage(
                role = "RECEIVER",
                deviceId = localDeviceId,
                deviceName = localDeviceName,
                ephemeralPubKeyHex = CryptoEngine.toHex(pubBytes),
                nonceHex = CryptoEngine.toHex(serverNonce),
                authTagHex = CryptoEngine.toHex(authTag)
            )
            writePlainFrame(
                out,
                ProtocolConstants.FRAME_HELLO,
                protocolJson.encodeToString(serverHello).toByteArray(StandardCharsets.UTF_8)
            )

            val sessionKey = CryptoEngine.deriveSessionKey(
                localPrivateKey = keyPair.private,
                peerPublicKeyBytes = clientPubBytes,
                clientNonce = clientNonce,
                serverNonce = serverNonce
            )

            return EncryptedChannel(
                socket = socket,
                input = inp,
                output = out,
                sessionKey = sessionKey,
                sendDir = CryptoEngine.DIR_SERVER_TO_CLIENT,
                recvDir = CryptoEngine.DIR_CLIENT_TO_SERVER,
                peerDeviceId = clientHello.deviceId,
                peerDeviceName = clientHello.deviceName
            )
        }
    }
}
