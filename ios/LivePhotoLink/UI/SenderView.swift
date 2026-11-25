import SwiftUI

struct SenderView: View {
    @ObservedObject var settings: AppSettingsStore
    @ObservedObject var manager: SenderManager
    @ObservedObject var bonjour: BonjourBrowser
    let onOpenSettings: () -> Void

    @State private var showQrScanner = false
    @State private var showManualPair = false
    @State private var showCameraCapture = false
    @State private var selectedDiscovered: DiscoveredDevice? = nil
    @State private var discoveredPinInput = ""

    @State private var manualHost = ""
    @State private var manualPort = "48910"
    @State private var manualPin = ""
    @State private var manualName = "Galaxy Tab / iPad"

    var body: some View {
        NavigationStack {
            List {
                // 1. Service & Connection Card
                Section("連線狀態") {
                    Toggle("即時照片傳送服務", isOn: Binding(
                        get: { manager.isRunning },
                        set: { enable in
                            if enable {
                                manager.start()
                                bonjour.start()
                            } else {
                                manager.stop()
                                bonjour.stop()
                            }
                        }
                    ))
                    .tint(.green)

                    HStack(spacing: 10) {
                        Circle()
                            .fill(statusColor(manager.connectionStatus))
                            .frame(width: 10, height: 10)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(settings.pairedDevice.isConfigured ? settings.pairedDevice.deviceName : "尚未配對接收端")
                                .font(.headline)
                            Text(manager.statusDetail)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }

                    if let progress = manager.activeTransfer {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text("正在傳送：\(progress.fileName)")
                                    .font(.caption.weight(.medium))
                                    .lineLimit(1)
                                Spacer()
                                Text(FormatUtils.formatSpeed(progress.speedBytesPerSec))
                                    .font(.caption)
                                    .foregroundColor(.green)
                            }
                            ProgressView(value: progress.fraction)
                                .tint(.green)
                        }
                    }
                }

                // 2. Quick Actions & Stats
                Section("拍攝與統計") {
                    Button {
                        if !manager.isRunning { manager.start() }
                        showCameraCapture = true
                    } label: {
                        Label("內建快拍並立即傳送", systemImage: "camera.fill")
                            .font(.headline)
                            .foregroundColor(.green)
                    }

                    HStack {
                        statItem(title: "成功傳送", value: "\(manager.successCount)", color: .green)
                        Divider()
                        statItem(title: "失敗次數", value: "\(manager.failureCount)", color: manager.failureCount > 0 ? .red : .primary)
                        Divider()
                        statItem(title: "待傳佇列", value: "\(manager.queuedCount)", color: .orange)
                    }
                    .padding(.vertical, 4)

                    Text("提示：受 iOS 系統背景限制，使用內建快拍可達最穩定的即拍即傳；若改用系統原生相機，切回本 App 時會自動補傳剛剛拍攝的新照片。")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                // 3. Pairing
                Section("裝置配對（支援 Android 與 iPad 接收端）") {
                    Button {
                        showQrScanner = true
                    } label: {
                        Label("掃描接收端 QR Code", systemImage: "qrcode.viewfinder")
                    }

                    Button {
                        manualHost = settings.pairedDevice.host
                        manualPort = String(settings.pairedDevice.port)
                        manualPin = settings.pairedDevice.pairingPin
                        manualName = settings.pairedDevice.deviceName.isEmpty ? "Galaxy Tab / iPad" : settings.pairedDevice.deviceName
                        showManualPair = true
                    } label: {
                        Label("手動輸入 IP 與配對碼", systemImage: "network")
                    }

                    if !bonjour.discoveredDevices.isEmpty {
                        ForEach(bonjour.discoveredDevices) { device in
                            Button {
                                discoveredPinInput = ""
                                selectedDiscovered = device
                            } label: {
                                HStack {
                                    VStack(alignment: .leading) {
                                        Text(device.deviceName).font(.body.weight(.medium))
                                        Text("\(device.host):\(device.port)")
                                            .font(.caption)
                                            .foregroundColor(.secondary)
                                    }
                                    Spacer()
                                    Text("連線")
                                        .font(.subheadline.bold())
                                        .foregroundColor(.green)
                                }
                            }
                        }
                    }
                }

                // 4. Recent Sent
                if !manager.recentPhotos.isEmpty {
                    Section("最近傳送紀錄") {
                        ForEach(manager.recentPhotos) { item in
                            HStack {
                                Image(systemName: item.success ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                                    .foregroundColor(item.success ? .green : .red)
                                VStack(alignment: .leading) {
                                    Text(item.fileName).font(.subheadline).lineLimit(1)
                                    Text("\(FormatUtils.formatBytes(item.fileSize)) · \(FormatUtils.formatTime(item.timestampMs))")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("LivePhoto Link 傳送端")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    if manager.isRunning {
                        Button(action: { manager.reconnect() }) {
                            Image(systemName: "arrow.clockwise")
                        }
                    }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button(action: onOpenSettings) {
                        Image(systemName: "gearshape.fill")
                    }
                }
            }
            .onAppear {
                bonjour.start()
            }
            .sheet(isPresented: $showQrScanner) {
                QrCodeScannerSheet { payload in
                    settings.pairedDevice = PairedDevice(
                        deviceId: payload.deviceId,
                        deviceName: payload.deviceName,
                        host: payload.host,
                        port: payload.port,
                        pairingPin: payload.pin
                    )
                    if manager.isRunning { manager.reconnect() } else { manager.start() }
                }
            }
            .sheet(isPresented: $showCameraCapture) {
                CameraCaptureSheet { image in
                    manager.enqueueCapturedImage(image)
                }
            }
            .sheet(isPresented: $showManualPair) {
                manualPairSheet
            }
            .alert("連線至 \(selectedDiscovered?.deviceName ?? "")", isPresented: Binding(
                get: { selectedDiscovered != nil },
                set: { if !$0 { selectedDiscovered = nil } }
            )) {
                TextField("輸入 6 位數配對碼", text: $discoveredPinInput)
                    .keyboardType(.numberPad)
                Button("取消", role: .cancel) { selectedDiscovered = nil }
                Button("配對連線") {
                    if let dev = selectedDiscovered, discoveredPinInput.count == 6 {
                        settings.pairedDevice = PairedDevice(
                            deviceId: dev.deviceId,
                            deviceName: dev.deviceName,
                            host: dev.host,
                            port: dev.port,
                            pairingPin: discoveredPinInput
                        )
                        if manager.isRunning { manager.reconnect() } else { manager.start() }
                    }
                    selectedDiscovered = nil
                }
            } message: {
                Text("請輸入接收端畫面上顯示的 6 位數配對碼")
            }
        }
    }

    private var manualPairSheet: some View {
        NavigationStack {
            Form {
                Section("接收端連線資訊") {
                    TextField("IP 位址（例如 192.168.1.50）", text: $manualHost)
                        .keyboardType(.numbersAndPunctuation)
                    TextField("連接埠（預設 48910）", text: $manualPort)
                        .keyboardType(.numberPad)
                    TextField("6 位數配對碼", text: $manualPin)
                        .keyboardType(.numberPad)
                    TextField("裝置名稱", text: $manualName)
                }
            }
            .navigationTitle("手動輸入 IP 配對")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { showManualPair = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("儲存並連線") {
                        let port = Int(manualPort) ?? 48910
                        settings.pairedDevice = PairedDevice(
                            deviceId: "",
                            deviceName: manualName.isEmpty ? manualHost : manualName,
                            host: manualHost.trimmingCharacters(in: .whitespacesAndNewlines),
                            port: port,
                            pairingPin: manualPin.trimmingCharacters(in: .whitespacesAndNewlines)
                        )
                        showManualPair = false
                        if manager.isRunning { manager.reconnect() } else { manager.start() }
                    }
                    .disabled(manualHost.trimmingCharacters(in: .whitespaces).isEmpty || manualPin.count != 6)
                }
            }
        }
    }

    private func statItem(title: String, value: String, color: Color) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.title3.bold()).foregroundColor(color)
            Text(title).font(.caption).foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity)
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
