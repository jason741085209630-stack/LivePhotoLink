import Combine
import Foundation
import UIKit

enum AppMode: String, Codable, CaseIterable {
    case unset = "UNSET"
    case sender = "SENDER"
    case receiver = "RECEIVER"
}

enum StorageMode: String, Codable, CaseIterable {
    case displayOnly = "DISPLAY_ONLY"
    case appPrivate = "APP_PRIVATE"
    case systemGallery = "SYSTEM_GALLERY"
}

enum DarkThemeMode: String, Codable, CaseIterable {
    case system = "SYSTEM"
    case light = "LIGHT"
    case dark = "DARK"
}

enum ConnectionStatus: String {
    case disconnected
    case searching
    case connecting
    case authenticating
    case connected
    case authFailed
    case error
}

struct PairedDevice: Codable, Equatable {
    var deviceId: String = ""
    var deviceName: String = ""
    var host: String = ""
    var port: Int = 48910
    var pairingPin: String = ""

    var isConfigured: Bool {
        !host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        (1...65535).contains(port) &&
        pairingPin.count == 6 &&
        pairingPin.allSatisfy(\.isNumber)
    }
}

struct DiscoveredDevice: Identifiable, Equatable {
    var id: String { "\(serviceName)-\(host)-\(port)" }
    let serviceName: String
    let deviceId: String
    let deviceName: String
    let host: String
    let port: Int
}

struct TransferProgress: Equatable {
    var fileName: String = ""
    var bytesSent: Int64 = 0
    var totalBytes: Int64 = 0
    var speedBytesPerSec: Int64 = 0
    var isPreview: Bool = false

    var fraction: Double {
        guard totalBytes > 0 else { return 0 }
        return min(1.0, max(0.0, Double(bytesSent) / Double(totalBytes)))
    }
}

struct RecentSentPhoto: Identifiable, Equatable {
    var id: String { photoId }
    let photoId: String
    let fileName: String
    let fileSize: Int64
    let timestampMs: Int64
    let success: Bool
    let errorMessage: String?
    let localThumbPath: String?
}

struct ReceivedPhoto: Identifiable, Equatable {
    var id: String { photoId }
    let photoId: String
    let fileName: String
    let filePath: String
    let mimeType: String
    let fileSize: Int64
    let width: Int
    let height: Int
    let orientation: Int
    let receivedAtMs: Int64
    let sha256: String
    let isPreview: Bool
    let savedToGallery: Bool
}

struct PairingQrPayload: Equatable {
    let host: String
    let port: Int
    let pin: String
    let deviceName: String
    let deviceId: String

    func toUriString() -> String {
        var comps = URLComponents()
        comps.scheme = "livephotolink"
        comps.host = "pair"
        comps.queryItems = [
            URLQueryItem(name: "host", value: host),
            URLQueryItem(name: "port", value: String(port)),
            URLQueryItem(name: "pin", value: pin),
            URLQueryItem(name: "name", value: deviceName),
            URLQueryItem(name: "id", value: deviceId)
        ]
        return comps.string ?? ""
    }

    static func parse(_ raw: String) -> PairingQrPayload? {
        guard raw.hasPrefix("livephotolink://pair?"),
              let comps = URLComponents(string: raw),
              let items = comps.queryItems else {
            return nil
        }
        var dict: [String: String] = [:]
        for item in items {
            if let value = item.value {
                dict[item.name] = value
            }
        }
        guard let host = dict["host"], !host.isEmpty,
              let portStr = dict["port"], let port = Int(portStr), (1...65535).contains(port),
              let pin = dict["pin"], pin.count == 6, pin.allSatisfy(\.isNumber) else {
            return nil
        }
        let name = (dict["name"] ?? "").isEmpty ? "接收端裝置" : dict["name"]!
        let id = dict["id"] ?? ""
        return PairingQrPayload(host: host, port: port, pin: pin, deviceName: name, deviceId: id)
    }
}

enum FormatUtils {
    static func formatBytes(_ bytes: Int64) -> String {
        if bytes < 1024 { return "\(bytes) B" }
        let kb = Double(bytes) / 1024.0
        if kb < 1024.0 { return String(format: "%.1f KB", kb) }
        let mb = kb / 1024.0
        if mb < 1024.0 { return String(format: "%.2f MB", mb) }
        let gb = mb / 1024.0
        return String(format: "%.2f GB", gb)
    }

    static func formatSpeed(_ bytesPerSec: Int64) -> String {
        "\(formatBytes(bytesPerSec))/s"
    }

    static func formatResolution(width: Int, height: Int) -> String {
        guard width > 0, height > 0 else { return "未知解析度" }
        let mp = Double(width * height) / 1_000_000.0
        if mp >= 1.0 {
            return String(format: "%d × %d (%.1f MP)", width, height, mp)
        }
        return "\(width) × \(height)"
    }

    static func formatTime(_ timestampMs: Int64) -> String {
        guard timestampMs > 0 else { return "--:--:--" }
        let date = Date(timeIntervalSince1970: TimeInterval(timestampMs) / 1000.0)
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter.string(from: date)
    }
}

final class AppSettingsStore: ObservableObject {
    @Published var appMode: AppMode {
        didSet { UserDefaults.standard.set(appMode.rawValue, forKey: Keys.appMode) }
    }
    @Published var deviceId: String {
        didSet { UserDefaults.standard.set(deviceId, forKey: Keys.deviceId) }
    }
    @Published var deviceName: String {
        didSet { UserDefaults.standard.set(deviceName, forKey: Keys.deviceName) }
    }
    @Published var receiverPin: String {
        didSet { UserDefaults.standard.set(receiverPin, forKey: Keys.receiverPin) }
    }
    @Published var receiverPort: Int {
        didSet { UserDefaults.standard.set(receiverPort, forKey: Keys.receiverPort) }
    }
    @Published var pairedDevice: PairedDevice {
        didSet {
            if let data = try? JSONEncoder().encode(pairedDevice) {
                UserDefaults.standard.set(data, forKey: Keys.pairedDevice)
            }
        }
    }
    @Published var keepScreenOn: Bool {
        didSet { UserDefaults.standard.set(keepScreenOn, forKey: Keys.keepScreenOn) }
    }
    @Published var storageMode: StorageMode {
        didSet { UserDefaults.standard.set(storageMode.rawValue, forKey: Keys.storageMode) }
    }
    @Published var darkThemeMode: DarkThemeMode {
        didSet { UserDefaults.standard.set(darkThemeMode.rawValue, forKey: Keys.darkThemeMode) }
    }
    @Published var pauseAutoSwitch: Bool {
        didSet { UserDefaults.standard.set(pauseAutoSwitch, forKey: Keys.pauseAutoSwitch) }
    }

    private enum Keys {
        static let appMode = "lpl_app_mode"
        static let deviceId = "lpl_device_id"
        static let deviceName = "lpl_device_name"
        static let receiverPin = "lpl_receiver_pin"
        static let receiverPort = "lpl_receiver_port"
        static let pairedDevice = "lpl_paired_device"
        static let keepScreenOn = "lpl_keep_screen_on"
        static let storageMode = "lpl_storage_mode"
        static let darkThemeMode = "lpl_dark_theme_mode"
        static let pauseAutoSwitch = "lpl_pause_auto_switch"
    }

    init() {
        let ud = UserDefaults.standard
        self.appMode = AppMode(rawValue: ud.string(forKey: Keys.appMode) ?? "") ?? .unset

        let savedId = ud.string(forKey: Keys.deviceId) ?? ""
        let resolvedId = savedId.isEmpty ? String(UUID().uuidString.prefix(12)) : savedId
        self.deviceId = resolvedId
        ud.set(resolvedId, forKey: Keys.deviceId)

        let savedName = ud.string(forKey: Keys.deviceName) ?? ""
        let resolvedName = savedName.isEmpty ? UIDevice.current.name : savedName
        self.deviceName = resolvedName
        ud.set(resolvedName, forKey: Keys.deviceName)

        let savedPin = ud.string(forKey: Keys.receiverPin) ?? ""
        let resolvedPin = savedPin.count == 6 ? savedPin : Self.generatePin()
        self.receiverPin = resolvedPin
        ud.set(resolvedPin, forKey: Keys.receiverPin)

        let savedPort = ud.integer(forKey: Keys.receiverPort)
        self.receiverPort = savedPort > 0 ? savedPort : 48910

        if let data = ud.data(forKey: Keys.pairedDevice),
           let decoded = try? JSONDecoder().decode(PairedDevice.self, from: data) {
            self.pairedDevice = decoded
        } else {
            self.pairedDevice = PairedDevice()
        }

        self.keepScreenOn = ud.object(forKey: Keys.keepScreenOn) as? Bool ?? true
        self.storageMode = StorageMode(rawValue: ud.string(forKey: Keys.storageMode) ?? "") ?? .displayOnly
        self.darkThemeMode = DarkThemeMode(rawValue: ud.string(forKey: Keys.darkThemeMode) ?? "") ?? .system
        self.pauseAutoSwitch = ud.bool(forKey: Keys.pauseAutoSwitch)
    }

    func regeneratePin() {
        receiverPin = Self.generatePin()
    }

    static func generatePin() -> String {
        String(format: "%06d", Int.random(in: 0..<1_000_000))
    }
}
