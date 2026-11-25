package com.livephotolink.app.network

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure JCA authenticated key exchange and transport encryption:
 * - Pairing PIN -> PBKDF2-HMAC-SHA256 pairing key
 * - Mutual HMAC-SHA256 authentication over ephemeral ECDH (secp256r1) public keys + random nonces
 * - Session key = SHA-256(ECDH_shared || clientNonce || serverNonce || context)
 * - Per-frame AES-256-GCM encryption with deterministic 96-bit direction+sequence nonce
 */
object CryptoEngine {

    private const val PBKDF2_ITERATIONS = 10_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private val PAIRING_SALT = "LivePhotoLink-PairingSalt-v1".toByteArray(StandardCharsets.UTF_8)
    private val SESSION_CONTEXT = "LivePhotoLink-SessionKey-v1".toByteArray(StandardCharsets.UTF_8)
    private val secureRandom = SecureRandom()

    const val DIR_CLIENT_TO_SERVER: Byte = 0x01
    const val DIR_SERVER_TO_CLIENT: Byte = 0x02

    fun randomNonce(size: Int = 16): ByteArray {
        return ByteArray(size).also { secureRandom.nextBytes(it) }
    }

    fun derivePairingKey(pin: String): ByteArray {
        val spec = PBEKeySpec(pin.trim().toCharArray(), PAIRING_SALT, PBKDF2_ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    fun generateEphemeralKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        return kpg.generateKeyPair()
    }

    fun computeAuthTag(
        pairingKey: ByteArray,
        role: String,
        deviceId: String,
        pubKeyBytes: ByteArray,
        nonce: ByteArray
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pairingKey, "HmacSHA256"))
        mac.update(role.toByteArray(StandardCharsets.UTF_8))
        mac.update(0)
        mac.update(deviceId.toByteArray(StandardCharsets.UTF_8))
        mac.update(0)
        mac.update(pubKeyBytes)
        mac.update(nonce)
        return mac.doFinal()
    }

    fun verifyAuthTag(
        pairingKey: ByteArray,
        role: String,
        deviceId: String,
        pubKeyBytes: ByteArray,
        nonce: ByteArray,
        providedTag: ByteArray
    ): Boolean {
        val expected = computeAuthTag(pairingKey, role, deviceId, pubKeyBytes, nonce)
        return MessageDigest.isEqual(expected, providedTag)
    }

    fun deriveSessionKey(
        localPrivateKey: PrivateKey,
        peerPublicKeyBytes: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray
    ): SecretKey {
        val keyFactory = KeyFactory.getInstance("EC")
        val peerPubKey = keyFactory.generatePublic(X509EncodedKeySpec(peerPublicKeyBytes))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(localPrivateKey)
        ka.doPhase(peerPubKey, true)
        val sharedSecret = ka.generateSecret()

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sharedSecret)
        digest.update(clientNonce)
        digest.update(serverNonce)
        digest.update(SESSION_CONTEXT)
        return SecretKeySpec(digest.digest(), "AES")
    }

    fun encrypt(
        sessionKey: SecretKey,
        direction: Byte,
        sequenceNumber: Long,
        plaintext: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = buildNonce(direction, sequenceNumber)
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(plaintext)
    }

    fun decrypt(
        sessionKey: SecretKey,
        direction: Byte,
        sequenceNumber: Long,
        ciphertext: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = buildNonce(direction, sequenceNumber)
        cipher.init(Cipher.DECRYPT_MODE, sessionKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }

    private fun buildNonce(direction: Byte, sequenceNumber: Long): ByteArray {
        val buf = ByteBuffer.allocate(NONCE_BYTES)
        buf.put(direction)
        buf.put(0)
        buf.put(0)
        buf.put(0)
        buf.putLong(sequenceNumber)
        return buf.array()
    }

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Invalid hex length" }
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }
}
