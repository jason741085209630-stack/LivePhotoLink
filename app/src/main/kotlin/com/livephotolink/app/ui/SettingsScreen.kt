package com.livephotolink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.livephotolink.app.data.AppSettings
import com.livephotolink.app.model.AppMode
import com.livephotolink.app.model.DarkThemeMode
import com.livephotolink.app.model.StorageMode
import com.livephotolink.app.util.PermissionUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onSwitchMode: (AppMode) -> Unit,
    onUpdateDeviceName: (String) -> Unit,
    onUpdateDarkThemeMode: (DarkThemeMode) -> Unit,
    onUpdateFastPreview: (Boolean) -> Unit,
    onUpdateKeepScreenOn: (Boolean) -> Unit,
    onUpdateStorageMode: (StorageMode) -> Unit,
    onRegeneratePin: () -> Unit,
    onClearHistoryAndCache: () -> Unit
) {
    val context = LocalContext.current
    var deviceNameInput by remember(settings.deviceName) { mutableStateOf(settings.deviceName) }
    var showClearConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Mode Switch
            SettingsSectionCard(title = "運作模式") {
                Text(
                    text = if (settings.appMode == AppMode.SENDER) "目前模式：傳送端（手機）" else "目前模式：接收端（平板）",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = settings.appMode == AppMode.SENDER,
                        onClick = { onSwitchMode(AppMode.SENDER) },
                        label = { Text("傳送端（手機）") }
                    )
                    FilterChip(
                        selected = settings.appMode == AppMode.RECEIVER,
                        onClick = { onSwitchMode(AppMode.RECEIVER) },
                        label = { Text("接收端（平板）") }
                    )
                }
            }

            // 2. Device Name & Theme
            SettingsSectionCard(title = "一般設定") {
                OutlinedTextField(
                    value = deviceNameInput,
                    onValueChange = { deviceNameInput = it },
                    label = { Text("裝置顯示名稱") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onUpdateDeviceName(deviceNameInput) },
                    enabled = deviceNameInput.trim().isNotEmpty() && deviceNameInput.trim() != settings.deviceName
                ) {
                    Text("儲存裝置名稱")
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                Text("深色模式", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.darkThemeMode == DarkThemeMode.SYSTEM,
                        onClick = { onUpdateDarkThemeMode(DarkThemeMode.SYSTEM) },
                        label = { Text("跟隨系統") }
                    )
                    FilterChip(
                        selected = settings.darkThemeMode == DarkThemeMode.LIGHT,
                        onClick = { onUpdateDarkThemeMode(DarkThemeMode.LIGHT) },
                        label = { Text("淺色") }
                    )
                    FilterChip(
                        selected = settings.darkThemeMode == DarkThemeMode.DARK,
                        onClick = { onUpdateDarkThemeMode(DarkThemeMode.DARK) },
                        label = { Text("深色") }
                    )
                }
            }

            // 3. Sender-specific Settings
            if (settings.appMode == AppMode.SENDER) {
                SettingsSectionCard(title = "傳送端設定") {
                    SettingSwitchRow(
                        title = "快速預覽模式",
                        subtitle = "大型照片（50MP/200MP）先傳送小尺寸預覽圖讓平板立刻顯示，再於背景補傳原始檔案並無縫替換。",
                        checked = settings.fastPreviewEnabled,
                        onCheckedChange = onUpdateFastPreview
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("三星 One UI 背景運作建議", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "為確保退至背景並使用三星原生相機時不被系統休眠，建議將本 App 的電池設為「不受限制」。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { PermissionUtils.openBatteryOptimizationSettings(context) }) {
                            Text("電池最佳化設定")
                        }
                        OutlinedButton(onClick = { PermissionUtils.openAppDetailsSettings(context) }) {
                            Text("應用程式權限")
                        }
                    }
                }
            }

            // 4. Receiver-specific Settings
            if (settings.appMode == AppMode.RECEIVER) {
                SettingsSectionCard(title = "接收端與照片管理") {
                    SettingSwitchRow(
                        title = "展示時保持螢幕常亮",
                        subtitle = "停留於接收畫面時防止平板自動休眠熄屏。",
                        checked = settings.keepScreenOn,
                        onCheckedChange = onUpdateKeepScreenOn
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Text("照片儲存模式", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StorageOptionChip(
                            selected = settings.storageMode == StorageMode.DISPLAY_ONLY,
                            title = "連線階段緩存（預設）",
                            desc = "連線期間暫存照片供左右滑動查看前一張，中斷連線或關閉應用時自動刪除。",
                            onClick = { onUpdateStorageMode(StorageMode.DISPLAY_ONLY) }
                        )
                        StorageOptionChip(
                            selected = settings.storageMode == StorageMode.APP_PRIVATE,
                            title = "永久保存於 App 專用空間",
                            desc = "保留在 LivePhoto Link 內部空間，斷線後仍可瀏覽，不干擾系統相簿。",
                            onClick = { onUpdateStorageMode(StorageMode.APP_PRIVATE) }
                        )
                        StorageOptionChip(
                            selected = settings.storageMode == StorageMode.SYSTEM_GALLERY,
                            title = "同時儲存至平板系統相簿",
                            desc = "原始檔案同步匯出至 Pictures/LivePhotoLink，保留完整 EXIF。",
                            onClick = { onUpdateStorageMode(StorageMode.SYSTEM_GALLERY) }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("目前配對碼：${settings.receiverPairingPin}", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "重新產生後，手機需以新配對碼重新配對。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = onRegeneratePin) {
                            Text("重設配對碼")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("清除所有歷史照片與快取")
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清除歷史照片與快取？") },
            text = { Text("這將刪除平板上 LivePhoto Link 專用空間與暫存中的所有照片（不會影響手機原始照片，也不會刪除已匯出至系統相簿的檔案）。") },
            confirmButton = {
                Button(onClick = {
                    showClearConfirm = false
                    onClearHistoryAndCache()
                }) {
                    Text("確認清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StorageOptionChip(
    selected: Boolean,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        label = {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(desc, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}
