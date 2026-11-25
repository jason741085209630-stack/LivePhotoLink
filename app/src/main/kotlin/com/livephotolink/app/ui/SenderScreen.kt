package com.livephotolink.app.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.livephotolink.app.data.AppSettings
import com.livephotolink.app.model.ConnectionStatus
import com.livephotolink.app.model.DiscoveredDevice
import com.livephotolink.app.model.PairedDevice
import com.livephotolink.app.model.RecentSentPhoto
import com.livephotolink.app.model.SenderUiState
import com.livephotolink.app.network.ProtocolConstants
import com.livephotolink.app.ui.theme.StatusConnected
import com.livephotolink.app.ui.theme.StatusConnecting
import com.livephotolink.app.ui.theme.StatusError
import com.livephotolink.app.util.FormatUtils
import com.livephotolink.app.util.PermissionUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SenderScreen(
    settings: AppSettings,
    state: SenderUiState,
    onToggleService: (Boolean) -> Unit,
    onPairDevice: (PairedDevice) -> Unit,
    onReconnect: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    var showManualDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var selectedDiscovered by remember { mutableStateOf<DiscoveredDevice?>(null) }

    val senderPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (PermissionUtils.hasMediaReadPermission(context)) {
            onToggleService(true)
        }
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showQrScanner = true
    }

    val hasMediaPerm = PermissionUtils.hasMediaReadPermission(context)
    val isBatteryExempt = PermissionUtils.isIgnoringBatteryOptimizations(context)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("LivePhoto Link", fontWeight = FontWeight.Bold)
                        Text(
                            text = "傳送端（手機）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    if (state.isServiceRunning) {
                        IconButton(onClick = onReconnect) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "重新連線")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "設定")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Main Service Toggle & Connection Status Card
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "即時照片傳送服務",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(statusColor(state.connectionStatus))
                                    )
                                    Text(
                                        text = state.statusDetail,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Switch(
                                checked = state.isServiceRunning,
                                onCheckedChange = { enable ->
                                    if (enable) {
                                        val missing = PermissionUtils.missingPermissions(
                                            context,
                                            PermissionUtils.requiredSenderPermissions()
                                        )
                                        if (missing.isNotEmpty() && !hasMediaPerm) {
                                            senderPermLauncher.launch(missing.toTypedArray())
                                        } else {
                                            if (missing.isNotEmpty()) {
                                                senderPermLauncher.launch(missing.toTypedArray())
                                            }
                                            onToggleService(true)
                                        }
                                    } else {
                                        onToggleService(false)
                                    }
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Paired Tablet Banner
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.TabletAndroid,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (settings.pairedDevice.isConfigured) {
                                            settings.pairedDevice.deviceName.ifBlank { "已配對平板" }
                                        } else {
                                            "尚未配對平板接收端"
                                        },
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = if (settings.pairedDevice.isConfigured) {
                                            "${settings.pairedDevice.host}:${settings.pairedDevice.port} · 已啟用 AES-256-GCM 加密"
                                        } else {
                                            "請點選下方搜尋到的平板、掃描 QR Code 或手動輸入 IP"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Active Transfer Progress
                        state.activeTransfer?.let { progress ->
                            Spacer(modifier = Modifier.height(14.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (progress.isPreview) "快速預覽：${progress.fileName}" else "正在傳送：${progress.fileName}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = FormatUtils.formatSpeed(progress.speedBytesPerSec),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { progress.fraction },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                                Text(
                                    text = "${FormatUtils.formatBytes(progress.bytesSent)} / ${FormatUtils.formatBytes(progress.totalBytes)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 2. Stats Row (Success / Failed / Queued)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatCard(
                        label = "成功傳送",
                        value = state.successCount.toString(),
                        valueColor = StatusConnected,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        label = "失敗次數",
                        value = state.failureCount.toString(),
                        valueColor = if (state.failureCount > 0) StatusError else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        label = "佇列待傳",
                        value = state.queuedCount.toString(),
                        valueColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 3. Pairing Controls (QR Code + Manual IP + Discovered NSD Tablets)
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("裝置配對", style = MaterialTheme.typography.titleMedium)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (PermissionUtils.hasCameraPermission(context)) {
                                        showQrScanner = true
                                    } else {
                                        cameraPermLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("掃描 QR Code")
                            }
                            FilledTonalButton(
                                onClick = { showManualDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("手動輸入 IP")
                            }
                        }

                        if (state.discoveredDevices.isNotEmpty()) {
                            Text(
                                text = "區域網路自動搜尋到的平板（點擊配對）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            state.discoveredDevices.forEach { device ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedDiscovered = device }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text(device.deviceName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "${device.host}:${device.port}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text("連線", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        } else if (state.isServiceRunning) {
                            Text(
                                text = "正在透過區域網路（mDNS/NSD）搜尋同 Wi-Fi 或熱點下的平板…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 4. Recent Sent Photos Thumbnails
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("最近傳送的照片", style = MaterialTheme.typography.titleMedium)
                        if (state.recentPhotos.isEmpty()) {
                            Text(
                                text = "開啟服務後，直接切回「三星原生相機」拍照即可自動傳送（不會同步歷史相簿）。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(state.recentPhotos, key = { it.photoId }) { item ->
                                    RecentPhotoThumb(item)
                                }
                            }
                        }
                    }
                }
            }

            // 5. Permissions & Samsung One UI Battery Optimization Shortcuts
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("權限與背景執行設定（Samsung One UI）", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = buildString {
                                append(if (hasMediaPerm) "✓ 相片讀取權限已開啟" else "✗ 尚未授權相片讀取權限")
                                append("\n")
                                append(if (isBatteryExempt) "✓ 電池最佳化已排除（背景穩定）" else "！ 建議排除電池最佳化以防止三星相機拍攝時中斷")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { PermissionUtils.openBatteryOptimizationSettings(context) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("電池不受限制")
                            }
                            OutlinedButton(
                                onClick = {
                                    val missing = PermissionUtils.missingPermissions(
                                        context,
                                        PermissionUtils.requiredSenderPermissions()
                                    )
                                    if (missing.isNotEmpty()) {
                                        senderPermLauncher.launch(missing.toTypedArray())
                                    } else {
                                        PermissionUtils.openAppDetailsSettings(context)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("檢查權限")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showManualDialog) {
        ManualPairDialog(
            initial = settings.pairedDevice,
            onDismiss = { showManualDialog = false },
            onConfirm = { paired ->
                showManualDialog = false
                onPairDevice(paired)
            }
        )
    }

    selectedDiscovered?.let { discovered ->
        PinEntryDialog(
            device = discovered,
            onDismiss = { selectedDiscovered = null },
            onConfirm = { pin ->
                selectedDiscovered = null
                onPairDevice(
                    PairedDevice(
                        deviceId = discovered.deviceId,
                        deviceName = discovered.deviceName,
                        host = discovered.host,
                        port = discovered.port,
                        pairingPin = pin
                    )
                )
            }
        )
    }

    if (showQrScanner) {
        QrScannerDialog(
            onDismiss = { showQrScanner = false },
            onScanned = { payload ->
                showQrScanner = false
                onPairDevice(
                    PairedDevice(
                        deviceId = payload.deviceId,
                        deviceName = payload.deviceName,
                        host = payload.host,
                        port = payload.port,
                        pairingPin = payload.pin
                    )
                )
            }
        )
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = valueColor,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RecentPhotoThumb(item: RecentSentPhoto) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.width(96.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(Uri.parse(item.contentUri))
                    .size(256, 256)
                    .crossfade(true)
                    .build(),
                contentDescription = item.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Icon(
                imageVector = if (item.success) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                contentDescription = null,
                tint = if (item.success) StatusConnected else StatusError,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(18.dp)
                    .background(Color.White.copy(alpha = 0.85f), CircleShape)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = item.fileName,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = FormatUtils.formatBytes(item.fileSize),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ManualPairDialog(
    initial: PairedDevice,
    onDismiss: () -> Unit,
    onConfirm: (PairedDevice) -> Unit
) {
    var host by remember { mutableStateOf(initial.host) }
    var portText by remember { mutableStateOf(initial.port.takeIf { it > 0 }?.toString() ?: ProtocolConstants.DEFAULT_PORT.toString()) }
    var pin by remember { mutableStateOf(initial.pairingPin) }
    var name by remember { mutableStateOf(initial.deviceName.ifBlank { "Galaxy Tab 平板" }) }

    val port = portText.toIntOrNull()
    val valid = host.trim().isNotEmpty() && port != null && port in 1..65535 && pin.length == 6 && pin.all(Char::isDigit)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手動輸入平板 IP 配對") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("平板 IP 位址（例如 192.168.1.50）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit) },
                    label = { Text("連接埠（預設 48910）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 6) pin = it.filter(Char::isDigit) },
                    label = { Text("6 位數配對碼") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("平板顯示名稱（選填）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onConfirm(
                        PairedDevice(
                            deviceId = initial.deviceId,
                            deviceName = name.trim().ifBlank { host.trim() },
                            host = host.trim(),
                            port = port ?: ProtocolConstants.DEFAULT_PORT,
                            pairingPin = pin
                        )
                    )
                }
            ) {
                Text("儲存並連線")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun PinEntryDialog(
    device: DiscoveredDevice,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("連線至 ${device.deviceName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "位址：${device.host}:${device.port}\n請輸入平板畫面上顯示的 6 位數配對碼：",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 6) pin = it.filter(Char::isDigit) },
                    label = { Text("6 位數配對碼") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = pin.length == 6,
                onClick = { onConfirm(pin) }
            ) {
                Text("配對連線")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun statusColor(status: ConnectionStatus): Color = when (status) {
    ConnectionStatus.CONNECTED -> StatusConnected
    ConnectionStatus.CONNECTING, ConnectionStatus.AUTHENTICATING, ConnectionStatus.SEARCHING -> StatusConnecting
    ConnectionStatus.AUTH_FAILED, ConnectionStatus.ERROR -> StatusError
    ConnectionStatus.DISCONNECTED -> Color.Gray
}
