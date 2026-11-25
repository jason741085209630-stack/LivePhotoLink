import SwiftUI

struct ModeSelectionView: View {
    let onSelectMode: (AppMode) -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                Spacer(minLength: 32)

                VStack(spacing: 8) {
                    Text("LivePhoto Link")
                        .font(.largeTitle.bold())
                        .foregroundColor(.green)
                    Text("Android / Apple 跨平台區域網路即時照片傳輸與展示")
                        .font(.headline)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                    Text("請選擇這台裝置的角色，之後可隨時於設定中切換。")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                }

                VStack(spacing: 16) {
                    modeCard(
                        icon: "ipad.landscape",
                        title: "接收端（iPad / 展示螢幕）",
                        subtitle: "推薦搭配 S24 Ultra 或 iPhone 傳送端",
                        description: "自動接收手機傳來的原始高畫質照片，立即全螢幕顯示最新畫面；支援向右滑動查看前一張、雙指縮放，斷線或關閉應用時自動清除快取。",
                        action: { onSelectMode(.receiver) }
                    )

                    modeCard(
                        icon: "camera.fill",
                        title: "傳送端（iPhone / 手機）",
                        subtitle: "傳送照片至 Galaxy Tab S11 或 iPad",
                        description: "支援與 Android / iPad 接收端加密連線，可透過內建快拍即拍即傳或自動偵測相簿新拍攝照片。",
                        action: { onSelectMode(.sender) }
                    )
                }
                .frame(maxWidth: 540)
            }
            .padding(24)
        }
    }

    private func modeCard(
        icon: String,
        title: String,
        subtitle: String,
        description: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 16) {
                Image(systemName: icon)
                    .font(.title)
                    .foregroundColor(.green)
                    .frame(width: 52, height: 52)
                    .background(Color.green.opacity(0.14))
                    .clipShape(RoundedRectangle(cornerRadius: 14))

                VStack(alignment: .leading, spacing: 6) {
                    Text(title)
                        .font(.title3.bold())
                        .foregroundColor(.primary)
                    Text(subtitle)
                        .font(.caption.weight(.semibold))
                        .foregroundColor(.green)
                    Text(description)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.leading)
                }
                Spacer()
            }
            .padding(18)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 20))
        }
        .buttonStyle(.plain)
    }
}
