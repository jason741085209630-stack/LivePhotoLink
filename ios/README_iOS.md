# LivePhoto Link — iOS / iPadOS 原生版建置與安裝指南（免 Mac）

本目錄包含與 Android 版 **100% 協定互通**的原生 **SwiftUI（iOS 16+ / iPadOS 16+）** 專案，採用零第三方套件設計（純 Apple `SwiftUI`、`Network`、`CryptoKit`、`AVFoundation`、`Photos`、`ImageIO`），可在沒有實體 Mac 的情況下透過 **GitHub Actions 免費雲端 macOS** 編譯出 `.ipa`，再於 **Windows 11** 安裝至您的 iPhone 或 iPad。

---

## 一、跨平台互通能力

- **Android（S24 Ultra）拍 → iPad / iPhone 即時全螢幕接收**（最推薦，支援向右滑動查看前一張、雙指縮放、斷線／關閉應用自動清除快取）。
- **iPhone 拍 → Android（Galaxy Tab S11）或 iPad 即時全螢幕接收**（支援內建快拍即拍即傳，以及切回 App 時自動補傳相簿新拍照片）。
- 與 Android 版共用完全相同的：
  - Bonjour / NSD 服務探索（`_livephotolink._tcp`）
  - 6 位數配對碼（PBKDF2-HMAC-SHA256）＋ 雙向 HMAC-SHA256 驗證 ＋ 臨時 ECDH P-256（SPKI DER）＋ AES-256-GCM 逐幀加密
  - SHA-256 完整性驗證與 `livephotolink://pair?...` QR Code 配對格式

---

## 二、免 Mac 取得 `LivePhotoLink.ipa`（GitHub Actions）

1. 在 GitHub 建立一個新倉庫（建議設為 Public，macOS Runner 免費；Private 倉庫每月也有免費額度）。
2. 在 Windows 11 將整個 `LivePhotoLink` 專案資料夾推送到您的 GitHub 倉庫：
   ```powershell
   cd C:\Users\jason\.gemini\antigravity\scratch\LivePhotoLink
   git init
   git add .
   git commit -m "Add Android and iOS LivePhoto Link"
   git branch -M main
   git remote add origin https://github.com/<您的帳號>/<倉庫名稱>.git
   git push -u origin main
   ```
3. 打開 GitHub 網頁 → 點選上方 **Actions** 分頁 → 選擇 **「Build iOS IPA (No Mac Needed)」** → 若未自動觸發可點 **Run workflow**。
4. 等待約 3～5 分鐘完成（流程會自動用 XcodeGen 建立專案、執行 `ProtocolInteropTests` 單元測試並打包）。
5. 建置完成後，在該次執行頁面的下方 **Artifacts** 區塊下載 **`LivePhotoLink-iOS-IPA`**，解壓縮即可得到 **`LivePhotoLink-unsigned.ipa`**。

---

## 三、在 Windows 11 安裝 `.ipa` 到 iPhone / iPad

### 方式 A：使用 Sideloadly（最簡單）
1. 至官方網站下載並安裝 Windows 版 **Sideloadly** 與 **iTunes（非 Microsoft Store 版）**。
2. 用傳輸線連接 iPhone / iPad 至 Windows 11，並在裝置上點選「信任這部電腦」。
3. 開啟 Sideloadly，將 `LivePhotoLink-unsigned.ipa` 拖曳進去，輸入您的 **Apple ID** 並點擊 **Start**。
4. 安裝完成後，至 iPhone / iPad 的 **`設定 → 一般 → VPN 與裝置管理`** → 信任您的 Apple ID 憑證即可開啟使用。

### 方式 B：使用 AltServer（支援同 Wi-Fi 自動續簽）
1. 在 Windows 11 安裝官方 **AltServer**，並安裝 AltStore 到 iPhone / iPad。
2. 將 `LivePhotoLink-unsigned.ipa` 傳到 iPhone / iPad 的「檔案」App，用 AltStore 開啟安裝。
3. 只要電腦與 iPhone / iPad 連在同一 Wi-Fi，AltStore 會在 7 天到期前自動續簽。

> **注意事項**：免費 Apple ID 側載的 App 效期為 7 天（可無限次續簽）；若您擁有付費 Apple Developer 帳號（US\$99/年），則可直接改用 TestFlight 分發（效期 90 天）。
