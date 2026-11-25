import Combine
import CryptoKit
import Foundation
import Network
import Photos
import UIKit

@MainActor
final class SenderManager: NSObject, ObservableObject, PHPhotoLibraryChangeObserver {

    @Published var isRunning: Bool = false
    @Published var connectionStatus: ConnectionStatus = .disconnected
    @Published var statusDetail: String = "尚未啟動傳送服務"
    @Published var activeTransfer: TransferProgress? = nil
    @Published var queuedCount: Int = 0
    @Published var successCount: Int = 0
    @Published var failureCount: Int = 0
    @Published var recentPhotos: [RecentSentPhoto] = []

    private struct OutgoingItem {
        let photoId: String
        let fileName: String
        let mimeType: String
        let data: Data
        let width: Int
        let height: Int
        var attempts: Int = 0
    }

    private let settings: AppSettingsStore
    private let networkQueue = DispatchQueue(label: "com.livephotolink.sender.network", qos: .userInitiated)
    private var loopTask: Task<Void, Never>?
    private var activeChannel: EncryptedChannel?
    private var queue: [OutgoingItem] = []
    private var seenAssetIds = Set<String>()
    private var sessionStartDate = Date()

    init(settings: AppSettingsStore) {
        self.settings = settings
        super.init()
    }

    func start() {
        guard !isRunning else { return }
        isRunning = true
        sessionStartDate = Date().addingTimeInterval(-2)
        seenAssetIds.removeAll()
        statusDetail = "正在準備連線…"

        startPhotoLibraryObservation()
        loopTask = Task { [weak self] in
            await self?.runLoop()
        }
    }

    func stop() {
        isRunning = false
        loopTask?.cancel()
        loopTask = nil
        activeChannel?.close()
        activeChannel = nil
        PHPhotoLibrary.shared().unregisterChangeObserver(self)
        connectionStatus = .disconnected
        statusDetail = "傳送服務已停止"
        activeTransfer = nil
    }

    func reconnect() {
        activeChannel?.close()
        activeChannel = nil
    }

    /// Enqueues a photo captured via the built-in quick camera or picker.
    func enqueueCapturedImage(_ image: UIImage) {
        guard let data = image.jpegData(compressionQuality: 0.95) else { return }
        let ts = Int64(Date().timeIntervalSince1970 * 1000)
        let item = OutgoingItem(
            photoId: "ios_\(ts)_\(UUID().uuidString.prefix(6))",
            fileName: "IMG_\(ts).jpg",
            mimeType: "image/jpeg",
            data: data,
            width: Int(image.size.width * image.scale),
            height: Int(image.size.height * image.scale)
        )
        queue.append(item)
        queuedCount = queue.count
    }

    private func startPhotoLibraryObservation() {
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { [weak self] status in
            guard status == .authorized || status == .limited, let self else { return }
            PHPhotoLibrary.shared().register(self)
            Task { @MainActor in
                self.scanRecentCameraAssets()
            }
        }
    }

    nonisolated func photoLibraryDidChange(_ changeInstance: PHChange) {
        Task { @MainActor in
            guard self.isRunning else { return }
            self.scanRecentCameraAssets()
        }
    }

    /// Scans for new camera photos taken after sessionStartDate (excludes screenshots).
    func scanRecentCameraAssets() {
        let options = PHFetchOptions()
        options.predicate = NSPredicate(
            format: "mediaType == %d AND creationDate >= %@",
            PHAssetMediaType.image.rawValue,
            sessionStartDate as NSDate
        )
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: true)]

        let results = PHAsset.fetchAssets(with: options)
        results.enumerateObjects { [weak self] asset, _, _ in
            guard let self else { return }
            if asset.mediaSubtypes.contains(.photoScreenshot) { return }
            if self.seenAssetIds.contains(asset.localIdentifier) { return }
            self.seenAssetIds.insert(asset.localIdentifier)
            self.exportAssetOriginal(asset)
        }
    }

    private func exportAssetOriginal(_ asset: PHAsset) {
        let resources = PHAssetResource.assetResources(for: asset)
        guard let resource = resources.first(where: { $0.type == .photo || $0.type == .fullSizePhoto }) ?? resources.first else {
            return
        }
        let reqOpts = PHAssetResourceRequestOptions()
        reqOpts.isNetworkAccessAllowed = false

        var buffer = Data()
        PHAssetResourceManager.default().requestData(for: resource, options: reqOpts) { chunk in
            buffer.append(chunk)
        } completionHandler: { [weak self] error in
            guard error == nil, !buffer.isEmpty, let self else { return }
            let ext = (resource.originalFilename as NSString).pathExtension.lowercased()
            let mime: String
            switch ext {
            case "png": mime = "image/png"
            case "heic", "heif": mime = "image/heic"
            case "dng": mime = "image/x-adobe-dng"
            default: mime = "image/jpeg"
            }
            let item = OutgoingItem(
                photoId: "ios_\(asset.localIdentifier.prefix(12))_\(Int64(Date().timeIntervalSince1970))",
                fileName: resource.originalFilename,
                mimeType: mime,
                data: buffer,
                width: asset.pixelWidth,
                height: asset.pixelHeight
            )
            Task { @MainActor in
                self.queue.append(item)
                self.queuedCount = self.queue.count
            }
        }
    }

    private func runLoop() async {
        var backoffSec: Double = 1.5
        var lastPing = Date.distantPast

        while !Task.isCancelled && isRunning {
            let paired = settings.pairedDevice
            guard paired.isConfigured else {
                connectionStatus = .disconnected
                statusDetail = "尚未配對接收端裝置"
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                continue
            }

            if activeChannel == nil {
                connectionStatus = .connecting
                statusDetail = "正在連線至 \(paired.deviceName.isEmpty ? paired.host : paired.deviceName)…"
                do {
                    let conn = try await connectNW(host: paired.host, port: UInt16(paired.port))
                    connectionStatus = .authenticating
                    statusDetail = "正在進行加密驗證…"
                    let channel = try await EncryptedChannel.handshakeAsClient(
                        connection: conn,
                        localDeviceId: settings.deviceId,
                        localDeviceName: settings.deviceName,
                        pairingPin: paired.pairingPin
                    )
                    activeChannel = channel
                    backoffSec = 1.5
                    lastPing = Date()
                    let peer = channel.peerDeviceName.isEmpty ? paired.host : channel.peerDeviceName
                    connectionStatus = .connected
                    statusDetail = "已連線至 \(peer) (\(paired.host))"
                } catch ProtocolError.authRejected(let msg) {
                    connectionStatus = .authFailed
                    statusDetail = "配對碼錯誤：\(msg)"
                    try? await Task.sleep(nanoseconds: 5_000_000_000)
                    continue
                } catch {
                    connectionStatus = .connecting
                    statusDetail = "找不到接收端 (\(paired.host))，自動重試中…"
                    try? await Task.sleep(nanoseconds: UInt64(backoffSec * 1_000_000_000))
                    backoffSec = min(10.0, backoffSec * 2.0)
                    continue
                }
            }

            guard let channel = activeChannel else { continue }

            if !queue.isEmpty {
                var item = queue[0]
                do {
                    let ok = try await sendItem(channel: channel, item: item)
                    if ok {
                        queue.removeFirst()
                        queuedCount = queue.count
                        successCount += 1
                        recordResult(item: item, success: true, error: nil)
                        lastPing = Date()
                    } else {
                        throw ProtocolError.invalidFrame("接收端校驗未通過")
                    }
                } catch {
                    activeTransfer = nil
                    item.attempts += 1
                    if item.attempts >= 5 {
                        queue.removeFirst()
                        queuedCount = queue.count
                        failureCount += 1
                        recordResult(item: item, success: false, error: error.localizedDescription)
                    } else {
                        queue[0] = item
                        channel.close()
                        activeChannel = nil
                    }
                }
                continue
            }

            // Heartbeat
            if Date().timeIntervalSince(lastPing) >= ProtocolConstants.heartbeatIntervalSec {
                do {
                    try await channel.sendFrame(type: ProtocolConstants.framePing)
                    let resp = try await channel.readFrame()
                    if resp.type != ProtocolConstants.framePong && resp.type != ProtocolConstants.framePing {
                        throw ProtocolError.invalidFrame("Unexpected heartbeat frame")
                    }
                    lastPing = Date()
                } catch {
                    channel.close()
                    activeChannel = nil
                    connectionStatus = .connecting
                    statusDetail = "連線中斷，正在自動重新連線…"
                }
            }

            try? await Task.sleep(nanoseconds: 200_000_000)
        }
    }

    private func sendItem(channel: EncryptedChannel, item: OutgoingItem) async throws -> Bool {
        let shaHex = CryptoEngine.toHex(Data(SHA256.hash(data: item.data)))
        let header = PhotoStartHeader(
            transferId: UUID().uuidString,
            photoId: item.photoId,
            fileName: item.fileName,
            mimeType: item.mimeType,
            totalSize: Int64(item.data.count),
            sha256: shaHex,
            width: item.width,
            height: item.height,
            orientation: 1,
            dateTakenMs: Int64(Date().timeIntervalSince1970 * 1000),
            isPreview: false
        )

        try await channel.sendJSON(type: ProtocolConstants.framePhotoStart, value: header)

        var offset = 0
        let total = item.data.count
        let start = Date()
        var lastUpdate = Date.distantPast

        while offset < total {
            let end = min(offset + ProtocolConstants.chunkSize, total)
            let chunk = item.data.subdata(in: offset..<end)
            try await channel.sendFrame(type: ProtocolConstants.framePhotoChunk, payload: chunk)
            offset = end

            let now = Date()
            if now.timeIntervalSince(lastUpdate) >= 0.08 || offset == total {
                let elapsed = max(0.001, now.timeIntervalSince(start))
                let speed = Int64(Double(offset) / elapsed)
                lastUpdate = now
                activeTransfer = TransferProgress(
                    fileName: item.fileName,
                    bytesSent: Int64(offset),
                    totalBytes: Int64(total),
                    speedBytesPerSec: speed,
                    isPreview: false
                )
            }
        }

        try await channel.sendFrame(type: ProtocolConstants.framePhotoEnd)
        activeTransfer = nil

        while true {
            let frame = try await channel.readFrame()
            if frame.type == ProtocolConstants.framePhotoAck {
                let ack = try JSONDecoder().decode(PhotoAckMessage.self, from: frame.payload)
                return ack.ok && ack.sha256.lowercased() == shaHex.lowercased()
            } else if frame.type == ProtocolConstants.framePong {
                continue
            } else if frame.type == ProtocolConstants.framePing {
                try await channel.sendFrame(type: ProtocolConstants.framePong)
            } else {
                throw ProtocolError.invalidFrame("Unexpected frame waiting for ACK")
            }
        }
    }

    private func connectNW(host: String, port: UInt16) async throws -> NWConnection {
        let nwHost = NWEndpoint.Host(host)
        guard let nwPort = NWEndpoint.Port(rawValue: port) else {
            throw ProtocolError.connectionFailed("無效的連接埠")
        }
        let connection = NWConnection(host: nwHost, port: nwPort, using: .tcp)

        return try await withCheckedThrowingContinuation { cont in
            var resumed = false
            connection.stateUpdateHandler = { state in
                guard !resumed else { return }
                switch state {
                case .ready:
                    resumed = true
                    connection.stateUpdateHandler = nil
                    cont.resume(returning: connection)
                case .failed(let err):
                    resumed = true
                    connection.cancel()
                    cont.resume(throwing: err)
                case .cancelled:
                    resumed = true
                    cont.resume(throwing: ProtocolError.connectionClosed)
                default:
                    break
                }
            }
            connection.start(queue: networkQueue)
        }
    }

    private func recordResult(item: OutgoingItem, success: Bool, error: String?) {
        let record = RecentSentPhoto(
            photoId: item.photoId,
            fileName: item.fileName,
            fileSize: Int64(item.data.count),
            timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
            success: success,
            errorMessage: error,
            localThumbPath: nil
        )
        recentPhotos.insert(record, at: 0)
        if recentPhotos.count > 30 {
            recentPhotos = Array(recentPhotos.prefix(30))
        }
    }
}
