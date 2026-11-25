import CryptoKit
import Foundation
import Network

enum ProtocolConstants {
    static let protocolVersion = 1
    static let defaultPort: UInt16 = 48910
    static let bonjourServiceType = "_livephotolink._tcp"
    static let chunkSize = 64 * 1024
    static let maxFrameSize = 2 * 1024 * 1024
    static let heartbeatIntervalSec: TimeInterval = 5.0

    static let frameHello: UInt8 = 0x01
    static let frameAuthError: UInt8 = 0x02
    static let framePing: UInt8 = 0x10
    static let framePong: UInt8 = 0x11
    static let framePhotoStart: UInt8 = 0x20
    static let framePhotoChunk: UInt8 = 0x21
    static let framePhotoEnd: UInt8 = 0x22
    static let framePhotoAck: UInt8 = 0x23
}

struct HelloMessage: Codable {
    var version: Int = ProtocolConstants.protocolVersion
    var role: String
    var deviceId: String
    var deviceName: String
    var ephemeralPubKeyHex: String
    var nonceHex: String
    var authTagHex: String
}

struct PhotoStartHeader: Codable {
    var transferId: String
    var photoId: String
    var fileName: String
    var mimeType: String
    var totalSize: Int64
    var sha256: String
    var width: Int = 0
    var height: Int = 0
    var orientation: Int = 1
    var dateTakenMs: Int64 = 0
    var isPreview: Bool = false
}

struct PhotoAckMessage: Codable {
    var transferId: String
    var photoId: String
    var ok: Bool
    var sha256: String = ""
    var error: String? = nil
}

struct DecryptedFrame {
    let type: UInt8
    let payload: Data
}

enum ProtocolError: LocalizedError {
    case authRejected(String)
    case invalidFrame(String)
    case connectionClosed
    case connectionFailed(String)

    var errorDescription: String? {
        switch self {
        case .authRejected(let msg): return msg
        case .invalidFrame(let msg): return msg
        case .connectionClosed: return "連線已中斷"
        case .connectionFailed(let msg): return msg
        }
    }
}

/// Async buffered reader/writer wrapper over Apple's NWConnection.
actor NWBufferedTransport {
    private let connection: NWConnection
    private var readBuffer = Data()

    init(connection: NWConnection) {
        self.connection = connection
    }

    func send(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error = error {
                    cont.resume(throwing: error)
                } else {
                    cont.resume()
                }
            })
        }
    }

    func readExactly(_ count: Int) async throws -> Data {
        while readBuffer.count < count {
            let chunk = try await receiveNextChunk()
            readBuffer.append(chunk)
        }
        let result = Data(readBuffer.prefix(count))
        readBuffer.removeFirst(count)
        return result
    }

    private func receiveNextChunk() async throws -> Data {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Data, Error>) in
            connection.receive(minimumIncompleteLength: 1, maximumLength: 128 * 1024) { data, _, isComplete, error in
                if let error = error {
                    cont.resume(throwing: error)
                    return
                }
                if let data = data, !data.isEmpty {
                    cont.resume(returning: data)
                    return
                }
                if isComplete {
                    cont.resume(throwing: ProtocolError.connectionClosed)
                    return
                }
                cont.resume(throwing: ProtocolError.connectionClosed)
            }
        }
    }

    nonisolated func close() {
        connection.cancel()
    }
}

/// Authenticated AES-256-GCM encrypted framed channel over NWConnection.
actor EncryptedChannel {
    private let transport: NWBufferedTransport
    private let sessionKey: SymmetricKey
    private let sendDir: UInt8
    private let recvDir: UInt8
    private var sendSeq: Int64 = 0
    private var recvSeq: Int64 = 0

    let peerDeviceId: String
    let peerDeviceName: String

    private init(
        transport: NWBufferedTransport,
        sessionKey: SymmetricKey,
        sendDir: UInt8,
        recvDir: UInt8,
        peerDeviceId: String,
        peerDeviceName: String
    ) {
        self.transport = transport
        self.sessionKey = sessionKey
        self.sendDir = sendDir
        self.recvDir = recvDir
        self.peerDeviceId = peerDeviceId
        self.peerDeviceName = peerDeviceName
    }

    func sendFrame(type: UInt8, payload: Data = Data()) async throws {
        var plain = Data(capacity: 1 + payload.count)
        plain.append(type)
        plain.append(payload)

        let encrypted = try CryptoEngine.encrypt(
            sessionKey: sessionKey,
            direction: sendDir,
            sequenceNumber: sendSeq,
            plaintext: plain
        )
        sendSeq += 1

        var packet = Data(capacity: 4 + encrypted.count)
        var lenBE = Int32(encrypted.count).bigEndian
        withUnsafeBytes(of: &lenBE) { packet.append(contentsOf: $0) }
        packet.append(encrypted)
        try await transport.send(packet)
    }

    func sendJSON<T: Encodable>(type: UInt8, value: T) async throws {
        let data = try JSONEncoder().encode(value)
        try await sendFrame(type: type, payload: data)
    }

    func readFrame() async throws -> DecryptedFrame {
        let lenData = try await transport.readExactly(4)
        let len = lenData.withUnsafeBytes { $0.load(as: Int32.self).bigEndian }
        guard len > 0, Int(len) <= ProtocolConstants.maxFrameSize else {
            throw ProtocolError.invalidFrame("Invalid encrypted frame size: \(len)")
        }
        let ciphertext = try await transport.readExactly(Int(len))
        let plain = try CryptoEngine.decrypt(
            sessionKey: sessionKey,
            direction: recvDir,
            sequenceNumber: recvSeq,
            ciphertextAndTag: ciphertext
        )
        recvSeq += 1
        guard let type = plain.first else {
            throw ProtocolError.invalidFrame("Empty decrypted frame")
        }
        let payload = plain.count > 1 ? Data(plain.dropFirst()) : Data()
        return DecryptedFrame(type: type, payload: payload)
    }

    nonisolated func close() {
        transport.close()
    }

    // MARK: - Handshake helpers

    private static func writePlainFrame(transport: NWBufferedTransport, type: UInt8, payload: Data) async throws {
        var packet = Data(capacity: 5 + payload.count)
        packet.append(type)
        var lenBE = Int32(payload.count).bigEndian
        withUnsafeBytes(of: &lenBE) { packet.append(contentsOf: $0) }
        packet.append(payload)
        try await transport.send(packet)
    }

    private static func readPlainFrame(transport: NWBufferedTransport) async throws -> (UInt8, Data) {
        let header = try await transport.readExactly(5)
        let type = header[0]
        let len = header.subdata(in: 1..<5).withUnsafeBytes { $0.load(as: Int32.self).bigEndian }
        guard len >= 0, Int(len) <= ProtocolConstants.maxFrameSize else {
            throw ProtocolError.invalidFrame("Invalid handshake frame size: \(len)")
        }
        let payload = len > 0 ? try await transport.readExactly(Int(len)) : Data()
        return (type, payload)
    }

    static func handshakeAsClient(
        connection: NWConnection,
        localDeviceId: String,
        localDeviceName: String,
        pairingPin: String
    ) async throws -> EncryptedChannel {
        let transport = NWBufferedTransport(connection: connection)
        let pairingKey = CryptoEngine.derivePairingKey(pin: pairingPin)
        let keyPair = CryptoEngine.generateEphemeralKeyPair()
        let pubDer = keyPair.publicKey.derRepresentation
        let clientNonce = CryptoEngine.randomNonce()
        let authTag = CryptoEngine.computeAuthTag(
            pairingKey: pairingKey,
            role: "SENDER",
            deviceId: localDeviceId,
            pubKeyDer: pubDer,
            nonce: clientNonce
        )

        let clientHello = HelloMessage(
            role: "SENDER",
            deviceId: localDeviceId,
            deviceName: localDeviceName,
            ephemeralPubKeyHex: CryptoEngine.toHex(pubDer),
            nonceHex: CryptoEngine.toHex(clientNonce),
            authTagHex: CryptoEngine.toHex(authTag)
        )
        let helloBytes = try JSONEncoder().encode(clientHello)
        try await writePlainFrame(transport: transport, type: ProtocolConstants.frameHello, payload: helloBytes)

        let (respType, respBytes) = try await readPlainFrame(transport: transport)
        if respType == ProtocolConstants.frameAuthError {
            let msg = String(data: respBytes, encoding: .utf8) ?? "配對碼驗證失敗"
            throw ProtocolError.authRejected(msg)
        }
        guard respType == ProtocolConstants.frameHello else {
            throw ProtocolError.invalidFrame("Unexpected handshake frame: \(respType)")
        }

        let serverHello = try JSONDecoder().decode(HelloMessage.self, from: respBytes)
        guard let serverPubDer = CryptoEngine.fromHex(serverHello.ephemeralPubKeyHex),
              let serverNonce = CryptoEngine.fromHex(serverHello.nonceHex),
              let serverTag = CryptoEngine.fromHex(serverHello.authTagHex) else {
            throw ProtocolError.invalidFrame("Malformed server HELLO hex")
        }

        guard CryptoEngine.verifyAuthTag(
            pairingKey: pairingKey,
            role: "RECEIVER",
            deviceId: serverHello.deviceId,
            pubKeyDer: serverPubDer,
            nonce: serverNonce,
            providedTag: serverTag
        ) else {
            throw ProtocolError.authRejected("接收端憑證驗證失敗")
        }

        let sessionKey = try CryptoEngine.deriveSessionKey(
            localPrivateKey: keyPair,
            peerPublicKeyDer: serverPubDer,
            clientNonce: clientNonce,
            serverNonce: serverNonce
        )

        return EncryptedChannel(
            transport: transport,
            sessionKey: sessionKey,
            sendDir: CryptoEngine.dirClientToServer,
            recvDir: CryptoEngine.dirServerToClient,
            peerDeviceId: serverHello.deviceId,
            peerDeviceName: serverHello.deviceName
        )
    }

    static func handshakeAsServer(
        connection: NWConnection,
        localDeviceId: String,
        localDeviceName: String,
        pairingPin: String
    ) async throws -> EncryptedChannel {
        let transport = NWBufferedTransport(connection: connection)
        let (reqType, reqBytes) = try await readPlainFrame(transport: transport)
        guard reqType == ProtocolConstants.frameHello else {
            throw ProtocolError.invalidFrame("Expected HELLO frame, got \(reqType)")
        }

        let clientHello = try JSONDecoder().decode(HelloMessage.self, from: reqBytes)
        let pairingKey = CryptoEngine.derivePairingKey(pin: pairingPin)
        guard let clientPubDer = CryptoEngine.fromHex(clientHello.ephemeralPubKeyHex),
              let clientNonce = CryptoEngine.fromHex(clientHello.nonceHex),
              let clientTag = CryptoEngine.fromHex(clientHello.authTagHex) else {
            throw ProtocolError.invalidFrame("Malformed client HELLO hex")
        }

        guard CryptoEngine.verifyAuthTag(
            pairingKey: pairingKey,
            role: "SENDER",
            deviceId: clientHello.deviceId,
            pubKeyDer: clientPubDer,
            nonce: clientNonce,
            providedTag: clientTag
        ) else {
            try? await writePlainFrame(
                transport: transport,
                type: ProtocolConstants.frameAuthError,
                payload: Data("配對碼錯誤，拒絕連線".utf8)
            )
            throw ProtocolError.authRejected("配對碼錯誤：\(clientHello.deviceName)")
        }

        let keyPair = CryptoEngine.generateEphemeralKeyPair()
        let pubDer = keyPair.publicKey.derRepresentation
        let serverNonce = CryptoEngine.randomNonce()
        let authTag = CryptoEngine.computeAuthTag(
            pairingKey: pairingKey,
            role: "RECEIVER",
            deviceId: localDeviceId,
            pubKeyDer: pubDer,
            nonce: serverNonce
        )

        let serverHello = HelloMessage(
            role: "RECEIVER",
            deviceId: localDeviceId,
            deviceName: localDeviceName,
            ephemeralPubKeyHex: CryptoEngine.toHex(pubDer),
            nonceHex: CryptoEngine.toHex(serverNonce),
            authTagHex: CryptoEngine.toHex(authTag)
        )
        let helloBytes = try JSONEncoder().encode(serverHello)
        try await writePlainFrame(transport: transport, type: ProtocolConstants.frameHello, payload: helloBytes)

        let sessionKey = try CryptoEngine.deriveSessionKey(
            localPrivateKey: keyPair,
            peerPublicKeyDer: clientPubDer,
            clientNonce: clientNonce,
            serverNonce: serverNonce
        )

        return EncryptedChannel(
            transport: transport,
            sessionKey: sessionKey,
            sendDir: CryptoEngine.dirServerToClient,
            recvDir: CryptoEngine.dirClientToServer,
            peerDeviceId: clientHello.deviceId,
            peerDeviceName: clientHello.deviceName
        )
    }
}
