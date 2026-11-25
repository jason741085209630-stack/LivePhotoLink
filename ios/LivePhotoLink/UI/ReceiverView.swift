import ImageIO
import SwiftUI
import UIKit

struct ReceiverView: View {
    @ObservedObject var settings: AppSettingsStore
    @ObservedObject var manager: ReceiverManager
    let onOpenSettings: () -> Void

    @State private var showControls = false
    @State private var showPairingSheet = false
    @State private var selectedPhotoId: String = ""

    var body: some View {
        Group {
            if manager.photos.isEmpty {
                waitingView
            } else {
                galleryView
            }
        }
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = settings.keepScreenOn
            if !manager.isServerRunning {
                manager.start()
            }
        }
        .onChange(of: settings.keepScreenOn) { enabled in
            UIApplication.shared.isIdleTimerDisabled = enabled
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
        }
        .sheet(isPresented: $showPairingSheet) {
            pairingSheet
        }
    }

    private var waitingView: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    HStack(spacing: 8) {
                        Circle()
                            .fill(statusColor(manager.connectionStatus))
                            .frame(width: 10, height: 10)
                        Text(manager.statusDetail)
                            .font(.subheadline.weight(.medium))
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(Color(.secondarySystemBackground))
                    .clipShape(Capsule())

                    VStack(spacing: 14) {
                        Text("等待手機傳送照片…")
                            .font(.title2.bold())
                        Text("在 Android（S24 Ultra）或 iPhone 開啟 LivePhoto Link 傳送端，掃描下方 QR Code 或輸入 IP 與配對碼即可連線。")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)

                        if let ip = manager.localIpAddresses.first,
                           let qrImg = QrCodeGenerator.generate(
                               from: PairingQrPayload(
                                   host: ip,
                                   port: manager.serverPort,
                                   pin: settings.receiverPin,
                                   deviceName: settings.deviceName,
                                   deviceId: settings.deviceId
                               ).toUriString()
                           ) {
                            Image(uiImage: qrImg)
                                .interpolation(.none)
                                .resizable()
                                .scaledToFit()
                                .frame(width: 210, height: 210)
                                .padding(12)
                                .background(Color.white)
                                .clipShape(RoundedRectangle(cornerRadius: 16))
                        } else {
                            Text("尚未偵測到區域網路 IP，請確認已連接 Wi-Fi 或手機行動熱點。")
                                .font(.subheadline)
                                .foregroundColor(.red)
                                .multilineTextAlignment(.center)
                        }

                        VStack(spacing: 4) {
                            Text("6 位數配對碼")
                                .font(.caption)
                                .foregroundColor(.secondary)
                            Text(settings.receiverPin)
                                .font(.system(size: 32, weight: .bold, design: .monospaced))
                                .kerning(6)
                                .foregroundColor(.green)
                            Text("IP：\(manager.localIpAddresses.isEmpty ? "未連線" : manager.localIpAddresses.joined(separator: " / "))   連接埠：\(manager.serverPort)")
                                .font(.footnote)
                                .foregroundColor(.secondary)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(14)
                        .background(Color.green.opacity(0.1))
                        .clipShape(RoundedRectangle(cornerRadius: 14))

                        if let transfer = manager.incomingTransfer {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("正在接收：\(transfer.fileName)")
                                    .font(.caption)
                                    .foregroundColor(.green)
                                ProgressView(value: transfer.fraction)
                                    .tint(.green)
                            }
                        }
                    }
                    .padding(24)
                    .frame(maxWidth: 540)
                    .background(Color(.secondarySystemBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 24))
                }
                .padding(24)
            }
            .navigationTitle("LivePhoto Link 接收端")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button(action: onOpenSettings) {
                        Image(systemName: "gearshape.fill")
                    }
                }
            }
        }
    }

    private var galleryView: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            // Chronological order: newest at lastIndex, swipe right to view previous photo
            TabView(selection: $selectedPhotoId) {
                ForEach(manager.photos) { photo in
                    ZoomablePhotoItemView(photo: photo) {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            showControls.toggle()
                        }
                    }
                    .tag(photo.photoId)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .ignoresSafeArea()
            .onAppear {
                if selectedPhotoId.isEmpty, let latest = manager.photos.last {
                    selectedPhotoId = latest.photoId
                }
            }
            .onChange(of: manager.latestPhotoEventId) { _ in
                if !settings.pauseAutoSwitch, let latest = manager.photos.last {
                    selectedPhotoId = latest.photoId
                }
            }

            if let transfer = manager.incomingTransfer {
                VStack {
                    ProgressView(value: transfer.fraction)
                        .tint(.green)
                    Spacer()
                }
                .ignoresSafeArea(edges: .top)
            }

            if showControls {
                overlayHud
                    .transition(.opacity)
            }
        }
        .statusBarHidden(!showControls)
    }

    private var overlayHud: some View {
        let currentIndex = manager.photos.firstIndex(where: { $0.photoId == selectedPhotoId }) ?? max(0, manager.photos.count - 1)
        let currentPhoto = manager.photos.indices.contains(currentIndex) ? manager.photos[currentIndex] : nil

        return VStack {
            // Top bar
            HStack {
                HStack(spacing: 8) {
                    Circle()
                        .fill(statusColor(manager.connectionStatus))
                        .frame(width: 10, height: 10)
                    Text(manager.statusDetail)
                        .font(.subheadline)
                        .foregroundColor(.white)
                        .lineLimit(1)
                    Text("· \(currentIndex + 1) / \(manager.photos.count)")
                        .font(.caption)
                        .foregroundColor(.white.opacity(0.75))
                }
                Spacer()
                Button(action: { showPairingSheet = true }) {
                    Image(systemName: "qrcode")
                        .font(.title3)
                        .foregroundColor(.white)
                        .padding(8)
                }
                Button(action: onOpenSettings) {
                    Image(systemName: "gearshape.fill")
                        .font(.title3)
                        .foregroundColor(.white)
                        .padding(8)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Color.black.opacity(0.65))

            Spacer()

            // Bottom bar
            if let photo = currentPhoto {
                VStack(alignment: .leading, spacing: 10) {
                    Text(photo.fileName + (photo.isPreview ? "（快速預覽，原圖傳送中…）" : ""))
                        .font(.headline)
                        .foregroundColor(.white)
                        .lineLimit(1)
                    Text("\(FormatUtils.formatResolution(width: photo.width, height: photo.height))  ·  \(FormatUtils.formatBytes(photo.fileSize))  ·  接收時間 \(FormatUtils.formatTime(photo.receivedAtMs))")
                        .font(.subheadline)
                        .foregroundColor(.white.opacity(0.8))

                    HStack(spacing: 12) {
                        Button {
                            settings.pauseAutoSwitch.toggle()
                        } label: {
                            Label(
                                settings.pauseAutoSwitch ? "恢復自動切換" : "暫停自動切換",
                                systemImage: settings.pauseAutoSwitch ? "play.fill" : "pause.fill"
                            )
                            .font(.subheadline.weight(.medium))
                        }
                        .buttonStyle(.bordered)
                        .tint(.white)

                        if currentIndex < manager.photos.count - 1, let latest = manager.photos.last {
                            Button {
                                withAnimation {
                                    selectedPhotoId = latest.photoId
                                }
                            } label: {
                                Label("返回最新照片", systemImage: "sparkles")
                                    .font(.subheadline.weight(.semibold))
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(.green)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20)
                .padding(.vertical, 14)
                .background(Color.black.opacity(0.72))
            }
        }
    }

    private var pairingSheet: some View {
        NavigationStack {
            VStack(spacing: 16) {
                if let ip = manager.localIpAddresses.first,
                   let qrImg = QrCodeGenerator.generate(
                       from: PairingQrPayload(
                           host: ip,
                           port: manager.serverPort,
                           pin: settings.receiverPin,
                           deviceName: settings.deviceName,
                           deviceId: settings.deviceId
                       ).toUriString()
                   ) {
                    Image(uiImage: qrImg)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 220, height: 220)
                        .padding(12)
                        .background(Color.white)
                        .clipShape(RoundedRectangle(cornerRadius: 16))
                }
                Text("配對碼：\(settings.receiverPin)")
                    .font(.title2.monospaced().bold())
                    .foregroundColor(.green)
                Text("IP：\(manager.localIpAddresses.joined(separator: " / "))\n連接埠：\(manager.serverPort)")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(24)
            .navigationTitle("接收端配對資訊")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { showPairingSheet = false }
                }
            }
        }
    }

    private func statusColor(_ status: ConnectionStatus) -> Color {
        switch status {
        case .connected: return .green
        case .connecting, .authenticating, .searching: return .orange
        case .authFailed, .error: return .red
        case .disconnected: return .gray
        }
    }
}

/// Memory-safe high-res image viewer using CGImageSource downsampling (safe for 200MP photos)
/// with smooth pinch-to-zoom and double-tap zoom that does not block TabView horizontal paging.
private struct ZoomablePhotoItemView: View {
    let photo: ReceivedPhoto
    let onSingleTap: () -> Void

    @State private var loadedImage: UIImage?
    @State private var scale: CGFloat = 1.0
    @State private var lastScale: CGFloat = 1.0

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black
                if let uiImage = loadedImage {
                    Image(uiImage: uiImage)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: geo.size.width, height: geo.size.height)
                        .scaleEffect(scale)
                        .gesture(
                            MagnificationGesture()
                                .onChanged { value in
                                    scale = min(6.0, max(1.0, lastScale * value))
                                }
                                .onEnded { _ in
                                    lastScale = scale
                                    if scale < 1.05 {
                                        withAnimation { scale = 1.0; lastScale = 1.0 }
                                    }
                                }
                        )
                } else {
                    ProgressView().tint(.green)
                }
            }
            .contentShape(Rectangle())
            .onTapGesture(count: 2) {
                withAnimation {
                    if scale > 1.05 {
                        scale = 1.0
                        lastScale = 1.0
                    } else {
                        scale = 2.5
                        lastScale = 2.5
                    }
                }
            }
            .onTapGesture(count: 1) {
                onSingleTap()
            }
            .task(id: "\(photo.filePath)-\(photo.fileSize)") {
                let maxPx = max(geo.size.width, geo.size.height) * UIScreen.main.scale * 1.5
                let targetMax = max(1920, min(4096, Int(maxPx)))
                loadedImage = await Task.detached(priority: .userInitiated) {
                    downsampleImage(atPath: photo.filePath, maxPixelSize: targetMax)
                }.value
            }
        }
    }

    private nonisolated func downsampleImage(atPath path: String, maxPixelSize: Int) -> UIImage? {
        let url = URL(fileURLWithPath: path)
        let sourceOpts: [CFString: Any] = [kCGImageSourceShouldCache: false]
        guard let source = CGImageSourceCreateWithURL(url as CFURL, sourceOpts as CFDictionary) else {
            return UIImage(contentsOfFile: path)
        }
        let thumbOpts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize
        ]
        if let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbOpts as CFDictionary) {
            return UIImage(cgImage: cg)
        }
        return UIImage(contentsOfFile: path)
    }
}
