import CommonCrypto
import CryptoKit
import Foundation

/// 100% byte-compatible with Android's CryptoEngine.kt:
/// - Pairing PIN -> PBKDF2-HMAC-SHA256 (10,000 iterations, 32-byte key)
/// - Ephemeral ECDH P-256 (secp256r1) public key in X.509 SPKI DER format
/// - Mutual HMAC-SHA256 authentication over (role || 0 || deviceId || 0 || pubKeyDER || nonce)
/// - Session key = SHA-256(ECDH_shared || clientNonce || serverNonce || "LivePhotoLink-SessionKey-v1")
/// - Per-frame AES-256-GCM encryption with 12-byte nonce [direction, 0, 0, 0, seq(big-endian Int64)]
///   and wire ciphertext = (ciphertext || 16-byte tag)
enum CryptoEngine {

    static let dirClientToServer: UInt8 = 0x01
    static let dirServerToClient: UInt8 = 0x02

    private static let pbkdf2Iterations: UInt32 = 10_000
    private static let pairingSalt = Data("LivePhotoLink-PairingSalt-v1".utf8)
    private static let sessionContext = Data("LivePhotoLink-SessionKey-v1".utf8)

    static func randomNonce(size: Int = 16) -> Data {
        var bytes = [UInt8](repeating: 0, count: size)
        let status = SecRandomCopyBytes(kSecRandomDefault, size, &bytes)
        if status != errSecSuccess {
            for i in 0..<size { bytes[i] = UInt8.random(in: 0...255) }
        }
        return Data(bytes)
    }

    static func derivePairingKey(pin: String) -> Data {
        let trimmed = pin.trimmingCharacters(in: .whitespacesAndNewlines)
        let passwordData = Data(trimmed.utf8)
        var derived = Data(count: 32)
        derived.withUnsafeMutableBytes { derivedBuf in
            pairingSalt.withUnsafeBytes { saltBuf in
                passwordData.withUnsafeBytes { passBuf in
                    _ = CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        passBuf.bindMemory(to: Int8.self).baseAddress,
                        passwordData.count,
                        saltBuf.bindMemory(to: UInt8.self).baseAddress,
                        pairingSalt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                        pbkdf2Iterations,
                        derivedBuf.bindMemory(to: UInt8.self).baseAddress,
                        32
                    )
                }
            }
        }
        return derived
    }

    static func generateEphemeralKeyPair() -> P256.KeyAgreement.PrivateKey {
        P256.KeyAgreement.PrivateKey()
    }

    static func computeAuthTag(
        pairingKey: Data,
        role: String,
        deviceId: String,
        pubKeyDer: Data,
        nonce: Data
    ) -> Data {
        var mac = HMAC<SHA256>(key: SymmetricKey(data: pairingKey))
        mac.update(data: Data(role.utf8))
        mac.update(data: Data([0]))
        mac.update(data: Data(deviceId.utf8))
        mac.update(data: Data([0]))
        mac.update(data: pubKeyDer)
        mac.update(data: nonce)
        return Data(mac.finalize())
    }

    static func verifyAuthTag(
        pairingKey: Data,
        role: String,
        deviceId: String,
        pubKeyDer: Data,
        nonce: Data,
        providedTag: Data
    ) -> Bool {
        let expected = computeAuthTag(
            pairingKey: pairingKey,
            role: role,
            deviceId: deviceId,
            pubKeyDer: pubKeyDer,
            nonce: nonce
        )
        return constantTimeEquals(expected, providedTag)
    }

    static func deriveSessionKey(
        localPrivateKey: P256.KeyAgreement.PrivateKey,
        peerPublicKeyDer: Data,
        clientNonce: Data,
        serverNonce: Data
    ) throws -> SymmetricKey {
        let peerPub = try P256.KeyAgreement.PublicKey(derRepresentation: peerPublicKeyDer)
        let sharedSecret = try localPrivateKey.sharedSecretFromKeyAgreement(with: peerPub)
        let sharedData = sharedSecret.withUnsafeBytes { Data($0) }

        var hasher = SHA256()
        hasher.update(data: sharedData)
        hasher.update(data: clientNonce)
        hasher.update(data: serverNonce)
        hasher.update(data: sessionContext)
        return SymmetricKey(data: Data(hasher.finalize()))
    }

    static func encrypt(
        sessionKey: SymmetricKey,
        direction: UInt8,
        sequenceNumber: Int64,
        plaintext: Data
    ) throws -> Data {
        let nonce = try AES.GCM.Nonce(data: buildNonce(direction: direction, sequenceNumber: sequenceNumber))
        let sealed = try AES.GCM.seal(plaintext, using: sessionKey, nonce: nonce)
        // Java JCA AES/GCM/NoPadding outputs (ciphertext || 16-byte tag)
        return sealed.ciphertext + sealed.tag
    }

    static func decrypt(
        sessionKey: SymmetricKey,
        direction: UInt8,
        sequenceNumber: Int64,
        ciphertextAndTag: Data
    ) throws -> Data {
        guard ciphertextAndTag.count >= 16 else {
            throw ProtocolError.invalidFrame("Ciphertext too short")
        }
        let splitIdx = ciphertextAndTag.count - 16
        let ct = ciphertextAndTag.prefix(splitIdx)
        let tag = ciphertextAndTag.suffix(16)
        let nonce = try AES.GCM.Nonce(data: buildNonce(direction: direction, sequenceNumber: sequenceNumber))
        let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ct, tag: tag)
        return try AES.GCM.open(box, using: sessionKey)
    }

    static func buildNonce(direction: UInt8, sequenceNumber: Int64) -> Data {
        var data = Data(capacity: 12)
        data.append(direction)
        data.append(0)
        data.append(0)
        data.append(0)
        var bigEndianSeq = sequenceNumber.bigEndian
        withUnsafeBytes(of: &bigEndianSeq) { data.append(contentsOf: $0) }
        return data
    }

    static func toHex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    static func fromHex(_ hex: String) -> Data? {
        guard hex.count % 2 == 0 else { return nil }
        var data = Data(capacity: hex.count / 2)
        var idx = hex.startIndex
        while idx < hex.endIndex {
            let nextIdx = hex.index(idx, offsetBy: 2)
            guard let byte = UInt8(hex[idx..<nextIdx], radix: 16) else { return nil }
            data.append(byte)
            idx = nextIdx
        }
        return data
    }

    private static func constantTimeEquals(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<a.count {
            diff |= a[i] ^ b[i]
        }
        return diff == 0
    }
}
