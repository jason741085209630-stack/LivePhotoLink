import XCTest
@testable import LivePhotoLink

final class ProtocolInteropTests: XCTestCase {

    func testQrPayloadRoundTrip() {
        let original = PairingQrPayload(
            host: "192.168.1.88",
            port: 48910,
            pin: "482910",
            deviceName: "iPad Pro 展示機",
            deviceId: "ipad-001"
        )
        let uri = original.toUriString()
        let parsed = PairingQrPayload.parse(uri)
        XCTAssertEqual(parsed, original)
    }

    func testCryptoKeyExchangeAndAesGcmRoundTrip() throws {
        let pin = "739204"
        let pairingKey = CryptoEngine.derivePairingKey(pin: pin)
        XCTAssertEqual(pairingKey.count, 32)

        let clientKP = CryptoEngine.generateEphemeralKeyPair()
        let serverKP = CryptoEngine.generateEphemeralKeyPair()
        let clientPubDer = clientKP.publicKey.derRepresentation
        let serverPubDer = serverKP.publicKey.derRepresentation
        // X.509 SPKI DER for P-256 must be 91 bytes (matching Android KeyPair.public.encoded)
        XCTAssertEqual(clientPubDer.count, 91)
        XCTAssertEqual(serverPubDer.count, 91)

        let clientNonce = CryptoEngine.randomNonce()
        let serverNonce = CryptoEngine.randomNonce()

        let clientTag = CryptoEngine.computeAuthTag(
            pairingKey: pairingKey,
            role: "SENDER",
            deviceId: "s24u",
            pubKeyDer: clientPubDer,
            nonce: clientNonce
        )
        XCTAssertTrue(CryptoEngine.verifyAuthTag(
            pairingKey: pairingKey,
            role: "SENDER",
            deviceId: "s24u",
            pubKeyDer: clientPubDer,
            nonce: clientNonce,
            providedTag: clientTag
        ))

        let wrongKey = CryptoEngine.derivePairingKey(pin: "000000")
        XCTAssertFalse(CryptoEngine.verifyAuthTag(
            pairingKey: wrongKey,
            role: "SENDER",
            deviceId: "s24u",
            pubKeyDer: clientPubDer,
            nonce: clientNonce,
            providedTag: clientTag
        ))

        let clientSessionKey = try CryptoEngine.deriveSessionKey(
            localPrivateKey: clientKP,
            peerPublicKeyDer: serverPubDer,
            clientNonce: clientNonce,
            serverNonce: serverNonce
        )
        let serverSessionKey = try CryptoEngine.deriveSessionKey(
            localPrivateKey: serverKP,
            peerPublicKeyDer: clientPubDer,
            clientNonce: clientNonce,
            serverNonce: serverNonce
        )

        let message = Data("Hello from Android S24 Ultra to iPad!".utf8)
        let encrypted = try CryptoEngine.encrypt(
            sessionKey: clientSessionKey,
            direction: CryptoEngine.dirClientToServer,
            sequenceNumber: 0,
            plaintext: message
        )
        let decrypted = try CryptoEngine.decrypt(
            sessionKey: serverSessionKey,
            direction: CryptoEngine.dirClientToServer,
            sequenceNumber: 0,
            ciphertextAndTag: encrypted
        )
        XCTAssertEqual(decrypted, message)
    }
}
