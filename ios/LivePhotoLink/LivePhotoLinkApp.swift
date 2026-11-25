import SwiftUI

@main
struct LivePhotoLinkApp: App {
    @StateObject private var settings: AppSettingsStore
    @StateObject private var receiverManager: ReceiverManager
    @StateObject private var senderManager: SenderManager
    @StateObject private var bonjourBrowser = BonjourBrowser()

    @Environment(\.scenePhase) private var scenePhase
    @State private var showSettings = false

    init() {
        let store = AppSettingsStore()
        _settings = StateObject(wrappedValue: store)
        _receiverManager = StateObject(wrappedValue: ReceiverManager(settings: store))
        _senderManager = StateObject(wrappedValue: SenderManager(settings: store))
    }

    var body: some Scene {
        WindowGroup {
            Group {
                switch settings.appMode {
                case .unset:
                    ModeSelectionView { mode in
                        switchMode(to: mode)
                    }
                case .receiver:
                    ReceiverView(
                        settings: settings,
                        manager: receiverManager,
                        onOpenSettings: { showSettings = true }
                    )
                case .sender:
                    SenderView(
                        settings: settings,
                        manager: senderManager,
                        bonjour: bonjourBrowser,
                        onOpenSettings: { showSettings = true }
                    )
                }
            }
            .tint(.green)
            .preferredColorScheme(colorScheme(for: settings.darkThemeMode))
            .sheet(isPresented: $showSettings) {
                SettingsView(
                    settings: settings,
                    onSwitchMode: { mode in
                        switchMode(to: mode)
                        showSettings = false
                    },
                    onClearCache: {
                        receiverManager.clearAllHistoryAndCache()
                    }
                )
            }
            .onChange(of: scenePhase) { phase in
                if phase == .background && settings.appMode == .receiver && settings.storageMode == .displayOnly {
                    // Clean session cache when app leaves foreground/closes in displayOnly mode
                    receiverManager.stop()
                } else if phase == .active {
                    if settings.appMode == .receiver && !receiverManager.isServerRunning {
                        receiverManager.start()
                    } else if settings.appMode == .sender && senderManager.isRunning {
                        senderManager.scanRecentCameraAssets()
                    }
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.willTerminateNotification)) { _ in
                if settings.storageMode == .displayOnly {
                    receiverManager.clearDisplayOnlyCache()
                }
            }
        }
    }

    private func switchMode(to mode: AppMode) {
        if mode == .sender {
            receiverManager.stop()
        } else if mode == .receiver {
            senderManager.stop()
            bonjourBrowser.stop()
        }
        settings.appMode = mode
        if mode == .receiver {
            receiverManager.start()
        }
    }

    private func colorScheme(for mode: DarkThemeMode) -> ColorScheme? {
        switch mode {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}
