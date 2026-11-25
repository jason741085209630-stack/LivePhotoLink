import SwiftUI

struct SettingsView: View {
    @ObservedObject var settings: AppSettingsStore
    let onSwitchMode: (AppMode) -> Void
    let onClearCache: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var showClearAlert = false

    var body: some View {
        NavigationStack {
            Form {
                Section("運作模式") {
                    Picker("角色", selection: Binding(
                        get: { settings.appMode },
                        set: { onSwitchMode($0) }
                    )) {
                        Text("接收端（iPad / 展示）").tag(AppMode.receiver)
                        Text("傳送端（iPhone / 拍照）").tag(AppMode.sender)
                    }
                    .pickerStyle(.segmented)
                }

                Section("一般設定") {
                    TextField("裝置顯示名稱", text: $settings.deviceName)
                    Picker("外觀主題", selection: $settings.darkThemeMode) {
                        Text("跟隨系統").tag(DarkThemeMode.system)
                        Text("淺色").tag(DarkThemeMode.light)
                        Text("深色").tag(DarkThemeMode.dark)
                    }
                }

                if settings.appMode == .receiver {
                    Section("接收端與快取管理") {
                        Toggle("展示時保持螢幕常亮", isOn: $settings.keepScreenOn)
                            .tint(.green)

                        Picker("儲存模式", selection: $settings.storageMode) {
                            Text("連線階段緩存（斷線/關閉即刪）").tag(StorageMode.displayOnly)
                            Text("永久保存於 App 專用空間").tag(StorageMode.appPrivate)
                            Text("同時儲存至系統相簿").tag(StorageMode.systemGallery)
                        }

                        HStack {
                            Text("目前配對碼：\(settings.receiverPin)")
                                .font(.body.monospaced())
                            Spacer()
                            Button("重新產生") {
                                settings.regeneratePin()
                            }
                        }

                        Button("清除所有歷史照片與快取", role: .destructive) {
                            showClearAlert = true
                        }
                    }
                }
            }
            .navigationTitle("設定")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
            .alert("清除歷史照片與快取？", isPresented: $showClearAlert) {
                Button("取消", role: .cancel) {}
                Button("確認清除", role: .destructive) { onClearCache() }
            } message: {
                Text("這將刪除本 App 暫存與專用空間中的所有照片（不影響已匯出至系統相簿的檔案）。")
            }
        }
    }
}
