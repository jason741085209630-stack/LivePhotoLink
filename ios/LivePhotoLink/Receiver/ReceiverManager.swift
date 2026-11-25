import Combine
import CryptoKit
import Foundation
import ImageIO
import Network
import Photos

@MainActor
final class ReceiverManager: ObservableObject {

    @Published var isServerRunning: Bool = false
    @Published var connectionStatus: ConnectionStatus = .disconnected
    @Published var statusDetail: String = "等待手機連線"
    @Published var localIpAddresses: [String] = []
    @Published var serverPort: Int = Int(ProtocolConstants.defaultPort)
    @Published var connectedSenderName: String? = nil
    @Published var incomingTransfer: TransferProgress? = nil
    @Published var photos: [ReceivedPhoto] = []
    @Published var latestPhotoEventId: Int = 0

    private let settings: AppSettingsStore
    private var listener: NWListener?
    private var activeClientTask: Task<Void, Never>?
    private var activeChannel: EncryptedChannel?
    private var ipRefreshTask: Task<Void, Never>?
    private let networkQueue = DispatchQueue(label: "com.livephotolink.receiver.network", qos: .userInitiated)

    init(settings: AppSettingsStore) {
        self.settings = settings
    }

    private var displayOnlyDir: URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("live_display", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private var appPrivateDir: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("received_photos", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func start() {
        stop()

        if settings.storageMode == .displayOnly {
            clearDisplayOnlyCache()
            photos = []
        } else {
            loadExistingPrivatePhotos()
        }

        localIpAddresses = NetworkUtils.getLocalIPv4Addresses()

        do {
            let params = NWParameters.tcp
            params.allowLocalEndpointReuse = true
            let port = NWEndpoint.Port(rawValue: UInt16(settings.receiverPort)) ?? NWEndpoint.Port(rawValue: ProtocolConstants.defaultPort)!
            let newListener = try NWListener(using: params, on: port)

            var txt = NWTXTRecord()
            txt["name"] = String(settings.deviceName.prefix(40))
            txt["id"] = settings.deviceId
            txt["ver"] = String(ProtocolConstants.protocolVersion)
            newListener.service = NWListener.Service(
                name: "LivePhotoLink-\(settings.deviceId.prefix(6))",
                type: ProtocolConstants.bonjourServiceType,
                domain: nil,
                txtRecord: txt
            )

            newListener.stateUpdateHandler = { [weak self] state in
                Task { @MainActor in
                    guard let self else { return }
                    switch state {
                    case .ready:
                        self.isServerRunning = true
                        if let bound = newListener.port?.rawValue {
                            self.serverPort = Int(bound)
                        }
                        self.localIpAddresses = NetworkUtils.getLocalIPv4Addresses()
                        self.statusDetail = "等待手機連線"
                    case .failed(let err):
                        self.isServerRunning = false
                        self.connectionStatus = .error
                        self.statusDetail = "接收伺服器啟動失敗：\(err.localizedDescription)"
                    case .cancelled:
                        self.isServerRunning = false
                    default:
                        break
                    }
                }
            }

            newListener.newConnectionHandler = { [weak self] connection in
                Task { @MainActor in
                    self?.acceptClient(connection)
                }
            }

            listener = newListener
            newListener.start(queue: networkQueue)

            ipRefreshTask = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 5_000_000_000)
                    guard let self else { return }
                    self.localIpAddresses = NetworkUtils.getLocalIPv4Addresses()
                }
            }
        } catch {
            connectionStatus = .error
            statusDetail = "無法啟動監聽埠：\(error.localizedDescription)"
        }
    }

    func stop() {
        ipRefreshTask?.cancel()
        ipRefreshTask = nil
        activeClientTask?.cancel()
        activeClientTask = nil
        activeChannel?.close()
        activeChannel = nil
        listener?.cancel()
        listener = nil
        isServerRunning = false
        connectionStatus = .disconnected
        connectedSenderName = nil
        incomingTransfer = nil
        statusDetail = "接收服務已停止"

        if settings.storageMode == .displayOnly {
            clearDisplayOnlyCache()
            photos = []
        }
    }

    func clearAllHistoryAndCache() {
        clearDisplayOnlyCache()
        if let files = try? FileManager.default.contentsOfDirectory(at: appPrivateDir, includingPropertiesForKeys: nil) {
            files.forEach { try? FileManager.default.removeItem(at: $0) }
        }
        photos = []
    }

    func clearDisplayOnlyCache() {
        if let files = try? FileManager.default.contentsOfDirectory(at: displayOnlyDir, includingPropertiesForKeys: nil) {
            files.forEach { try? FileManager.default.removeItem(at: $0) }
        }
    }

    private func acceptClient(_ connection: NWConnection) {
        activeClientTask?.cancel()
        activeChannel?.close()
        activeChannel = nil

        connection.start(queue: networkQueue)

        let deviceId = settings.deviceId
        let deviceName = settings.deviceName
        let pin = settings.receiverPin

        activeClientTask = Task.detached(priority: .userInitiated) { [weak self] in
            guard let self else {
                connection.cancel()
                return
            }

            await MainActor.run {
                self.connectionStatus = .authenticating
                self.statusDetail = "正在進行加密驗證…"
            }

            var channel: EncryptedChannel?
            defer {
                channel?.close()
                connection.cancel()
                Task { @MainActor in
                    self.incomingTransfer = nil
                    if self.activeChannel === channel {
                        self.activeChannel = nil
                    }
                    self.connectedSenderName = nil
                    if self.connectionStatus != .authFailed {
                        self.connectionStatus = .disconnected
                        self.statusDetail = "手機已斷線，等待重新連線…"
                    }
                    // Session-scoped cache: delete cached photos immediately on disconnect
                    if self.settings.storageMode == .displayOnly {
                        self.clearDisplayOnlyCache()
                        self.photos = []
                    }
                }
            }

            do {
                let authed = try await EncryptedChannel.handshakeAsServer(
                    connection: connection,
                    localDeviceId: deviceId,
                    localDeviceName: deviceName,
                    pairingPin: pin
                )
                channel = authed
                let senderName = authed.peerDeviceName.isEmpty ? "傳送端裝置" : authed.peerDeviceName

                await MainActor.run {
                    self.activeChannel = authed
                    self.connectionStatus = .connected
                    self.connectedSenderName = senderName
                    self.statusDetail = "已連線：\(senderName)"
                }

                while !Task.isCancelled {
                    let frame = try await authed.readFrame()
                    switch frame.type {
                    case ProtocolConstants.framePing:
                        try await authed.sendFrame(type: ProtocolConstants.framePong)
                    case ProtocolConstants.framePong:
                        break
                    case ProtocolConstants.framePhotoStart:
                        let header = try JSONDecoder().decode(PhotoStartHeader.self, from: frame.payload)
                        try await self.receivePhotoBody(channel: authed, header: header)
                    default:
                        break
                    }
                }
            } catch ProtocolError.authRejected(let msg) {
                await MainActor.run {
                    self.connectionStatus = .authFailed
                    self.statusDetail = "已拒絕未授權裝置：\(msg)"
                }
            } catch {
                // Handled in defer
            }
        }
    }

    private nonisolated func receivePhotoBody(
        channel: EncryptedChannel,
        header: PhotoStartHeader
    ) async throws {
        let mode = await MainActor.run { self.settings.storageMode }
        let targetDir = await MainActor.run {
            mode == .displayOnly ? self.displayOnlyDir : self.appPrivateDir
        }

        let safeName = sanitizeFileName(header.fileName)
        let ext = (safeName as NSString).pathExtension.isEmpty ? "jpg" : (safeName as NSString).pathExtension
        let base = (safeName as NSString).deletingPathExtension
        let suffix = header.isPreview ? "_preview" : ""
        let finalURL = targetDir.appendingPathComponent("\(header.photoId)_\(base)\(suffix).\(ext)")
        let partURL = targetDir.appendingPathComponent(".\(header.transferId).part")

        FileManager.default.createFile(atPath: partURL.path, contents: nil)
        let fileHandle = try FileHandle(forWritingTo: partURL)
        var hasher = SHA256()
        var receivedBytes: Int64 = 0
        let startTime = Date()
        var lastProgressUpdate = Date.distantPast

        do {
            loop: while true {
                let frame = try await channel.readFrame()
                switch frame.type {
                case ProtocolConstants.framePhotoChunk:
                    try fileHandle.write(contentsOf: frame.payload)
                    hasher.update(data: frame.payload)
                    receivedBytes += Int64(frame.payload.count)

                    let now = Date()
                    if now.timeIntervalSince(lastProgressUpdate) >= 0.08 || receivedBytes == header.totalSize {
                        let elapsed = max(0.001, now.timeIntervalSince(startTime))
                        let speed = Int64(Double(receivedBytes) / elapsed)
                        lastProgressUpdate = now
                        await MainActor.run {
                            self.incomingTransfer = TransferProgress(
                                fileName: header.fileName,
                                bytesSent: receivedBytes,
                                totalBytes: header.totalSize,
                                speedBytesPerSec: speed,
                                isPreview: header.isPreview
                            )
                        }
                    }
                case ProtocolConstants.framePhotoEnd:
                    break loop
                case ProtocolConstants.framePing:
                    try await channel.sendFrame(type: ProtocolConstants.framePong)
                default:
                    throw ProtocolError.invalidFrame("Unexpected frame during transfer: \(frame.type)")
                }
            }
            try fileHandle.close()
        } catch {
            try? fileHandle.close()
            try? FileManager.default.removeItem(at: partURL)
            await MainActor.run { self.incomingTransfer = nil }
            throw error
        }

        await MainActor.run { self.incomingTransfer = nil }

        let actualSha = CryptoEngine.toHex(Data(hasher.finalize()))
        let valid = (receivedBytes == header.totalSize) && (actualSha.lowercased() == header.sha256.lowercased())

        if !valid {
            try? FileManager.default.removeItem(at: partURL)
            let ack = PhotoAckMessage(
                transferId: header.transferId,
                photoId: header.photoId,
                ok: false,
                sha256: actualSha,
                error: "檔案大小或 SHA-256 校驗失敗"
            )
            try await channel.sendJSON(type: ProtocolConstants.framePhotoAck, value: ack)
            return
        }

        if FileManager.default.fileExists(atPath: finalURL.path) {
            try? FileManager.default.removeItem(at: finalURL)
        }
        try FileManager.default.moveItem(at: partURL, to: finalURL)

        let ack = PhotoAckMessage(
            transferId: header.transferId,
            photoId: header.photoId,
            ok: true,
            sha256: actualSha
        )
        try await channel.sendJSON(type: ProtocolConstants.framePhotoAck, value: ack)

        let (w, h, orient) = readImageMetadata(url: finalURL, fallbackW: header.width, fallbackH: header.height, fallbackO: header.orientation)

        var savedToGallery = false
        if !header.isPreview && mode == .systemGallery {
            savedToGallery = await exportToPhotosLibrary(fileURL: finalURL)
        }

        if mode == .displayOnly {
            trimCache(dir: targetDir, maxFiles: 100)
        }

        let item = ReceivedPhoto(
            photoId: header.photoId,
            fileName: header.fileName,
            filePath: finalURL.path,
            mimeType: header.mimeType,
            fileSize: header.totalSize,
            width: w,
            height: h,
            orientation: orient,
            receivedAtMs: Int64(Date().timeIntervalSince1970 * 1000),
            sha256: actualSha,
            isPreview: header.isPreview,
            savedToGallery: savedToGallery
        )

        await MainActor.run {
            if let idx = self.photos.firstIndex(where: { $0.photoId == item.photoId }) {
                let old = self.photos[idx]
                if old.isPreview && !item.isPreview && old.filePath != item.filePath {
                    try? FileManager.default.removeItem(atPath: old.filePath)
                }
                self.photos[idx] = item
            } else {
                // Append chronologically so newest is at lastIndex and swiping right shows previous photo
                self.photos.append(item)
                self.latestPhotoEventId += 1
            }
        }
    }

    private func loadExistingPrivatePhotos() {
        guard let urls = try? FileManager.default.contentsOfDirectory(
            at: appPrivateDir,
            includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey]
        ) else { return }

        let sorted = urls
            .filter { !$0.lastPathComponent.hasSuffix(".part") && !$0.lastPathComponent.contains("_preview.") }
            .sorted { a, b in
                let da = (try? a.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                let db = (try? b.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                return da < db
            }

        photos = sorted.map { url in
            let size = Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            let mod = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date()
            let (w, h, o) = readImageMetadata(url: url, fallbackW: 0, fallbackH: 0, fallbackO: 1)
            return ReceivedPhoto(
                photoId: url.deletingPathExtension().lastPathComponent,
                fileName: url.lastPathComponent,
                filePath: url.path,
                mimeType: "image/jpeg",
                fileSize: size,
                width: w,
                height: h,
                orientation: o,
                receivedAtMs: Int64(mod.timeIntervalSince1970 * 1000),
                sha256: "",
                isPreview: false,
                savedToGallery: false
            )
        }
    }

    private nonisolated func readImageMetadata(url: URL, fallbackW: Int, fallbackH: Int, fallbackO: Int) -> (Int, Int, Int) {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] else {
            return (fallbackW, fallbackH, fallbackO)
        }
        let w = (props[kCGImagePropertyPixelWidth] as? Int) ?? fallbackW
        let h = (props[kCGImagePropertyPixelHeight] as? Int) ?? fallbackH
        let o = (props[kCGImagePropertyOrientation] as? Int) ?? fallbackO
        return (w, h, o)
    }

    private nonisolated func exportToPhotosLibrary(fileURL: URL) async -> Bool {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { return false }
        return await withCheckedContinuation { cont in
            PHPhotoLibrary.shared().performChanges {
                let req = PHAssetCreationRequest.forAsset()
                req.addResource(with: .photo, fileURL: fileURL, options: nil)
            } completionHandler: { ok, _ in
                cont.resume(returning: ok)
            }
        }
    }

    private nonisolated func trimCache(dir: URL, maxFiles: Int) {
        guard let urls = try? FileManager.default.contentsOfDirectory(
            at: dir,
            includingPropertiesForKeys: [.contentModificationDateKey]
        ) else { return }
        let valid = urls.filter { !$0.lastPathComponent.hasSuffix(".part") }
        guard valid.count > maxFiles else { return }
        let sorted = valid.sorted { a, b in
            let da = (try? a.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            let db = (try? b.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            return da > db
        }
        for url in sorted.dropFirst(maxFiles) {
            try? FileManager.default.removeItem(at: url)
        }
    }

    private nonisolated func sanitizeFileName(_ name: String) -> String {
        let last = (name as NSString).lastPathComponent
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "._-"))
        let cleaned = String(last.unicodeScalars.map { allowed.contains($0) ? Character($0) : "_" })
        return cleaned.isEmpty ? "photo.jpg" : cleaned
    }
}
