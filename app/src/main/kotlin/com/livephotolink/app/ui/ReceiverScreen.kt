package com.livephotolink.app.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.livephotolink.app.data.AppSettings
import com.livephotolink.app.model.ConnectionStatus
import com.livephotolink.app.model.PairingQrPayload
import com.livephotolink.app.model.ReceivedPhoto
import com.livephotolink.app.model.ReceiverUiState
import com.livephotolink.app.ui.theme.StatusConnected
import com.livephotolink.app.ui.theme.StatusConnecting
import com.livephotolink.app.ui.theme.StatusError
import com.livephotolink.app.util.FormatUtils
import com.livephotolink.app.util.QrCodeUtils
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ReceiverScreen(
    settings: AppSettings,
    state: ReceiverUiState,
    onTogglePauseAutoSwitch: (Boolean) -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var showControls by remember { mutableStateOf(false) }
    var showPairingDialog by remember { mutableStateOf(false) }

    val hasPhotos = state.photos.isNotEmpty()

    // Keep screen on when configured
    DisposableEffect(activity, settings.keepScreenOn) {
        if (settings.keepScreenOn) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Immersive full-screen mode: hide system bars while viewing photos
    DisposableEffect(activity, hasPhotos, showControls) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hasPhotos && !showControls) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    if (!hasPhotos) {
        ReceiverWaitingScreen(
            settings = settings,
            state = state,
            onOpenSettings = onOpenSettings
        )
    } else {
        ReceiverGalleryScreen(
            settings = settings,
            state = state,
            showControls = showControls,
            onToggleControls = { showControls = !showControls },
            onTogglePauseAutoSwitch = onTogglePauseAutoSwitch,
            onOpenPairing = { showPairingDialog = true },
            onOpenSettings = onOpenSettings
        )
    }

    if (showPairingDialog) {
        PairingInfoDialog(
            settings = settings,
            state = state,
            onDismiss = { showPairingDialog = false }
        )
    }
}

@Composable
private fun ReceiverWaitingScreen(
    settings: AppSettings,
    state: ReceiverUiState,
    onOpenSettings: () -> Unit
) {
    val primaryIp = state.localIpAddresses.firstOrNull().orEmpty()
    val qrBitmap = remember(primaryIp, state.serverPort, state.pairingPin, settings.deviceName, settings.deviceId) {
        if (primaryIp.isNotBlank() && state.pairingPin.length == 6) {
            val payload = PairingQrPayload(
                host = primaryIp,
                port = state.serverPort,
                pin = state.pairingPin,
                deviceName = settings.deviceName,
                deviceId = settings.deviceId
            )
            QrCodeUtils.generateQrBitmap(payload.toUriString(), 520)
        } else null
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "LivePhoto Link",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "平板接收端 · 即時全螢幕展示",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "設定")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Connection Status Pill
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(receiverStatusColor(state.connectionStatus))
                        )
                        Text(
                            text = state.statusDetail,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "等待手機傳送照片…",
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "在手機開啟 LivePhoto Link 傳送端，掃描下方 QR Code 或輸入 IP 與配對碼即可連線。連線後使用三星原生相機拍照，照片將自動全螢幕顯示於此。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        if (qrBitmap != null) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color.White,
                                tonalElevation = 2.dp,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = "配對 QR Code",
                                    modifier = Modifier
                                        .size(210.dp)
                                        .padding(12.dp)
                                )
                            }
                        } else {
                            Text(
                                text = "尚未偵測到區域網路 IP，請確認平板已連接 Wi-Fi 或手機行動熱點。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = StatusError,
                                textAlign = TextAlign.Center
                            )
                        }

                        // Manual pairing info
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "配對碼",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = state.pairingPin.ifBlank { "------" },
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 6.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (state.localIpAddresses.isNotEmpty()) {
                                        "IP：${state.localIpAddresses.joinToString(" / ")}   連接埠：${state.serverPort}"
                                    } else {
                                        "連接埠：${state.serverPort}"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        state.incomingTransfer?.let { progress ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "正在接收：${progress.fileName}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { progress.fraction },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiverGalleryScreen(
    settings: AppSettings,
    state: ReceiverUiState,
    showControls: Boolean,
    onToggleControls: () -> Unit,
    onTogglePauseAutoSwitch: (Boolean) -> Unit,
    onOpenPairing: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val photos = state.photos
    val pagerState = rememberPagerState(
        initialPage = (photos.size - 1).coerceAtLeast(0),
        pageCount = { photos.size }
    )
    val scope = rememberCoroutineScope()
    var isCurrentPageZoomed by remember { mutableStateOf(false) }

    // Automatically jump to newest photo (lastIndex) when a new photo finishes transfer
    LaunchedEffect(state.latestPhotoEventId, photos.size) {
        if (state.latestPhotoEventId > 0L && !settings.pauseAutoSwitch && photos.isNotEmpty()) {
            pagerState.scrollToPage(photos.lastIndex)
        }
    }

    val currentPhoto = photos.getOrNull(pagerState.currentPage)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !isCurrentPageZoomed,
            key = { index -> photos.getOrNull(index)?.photoId ?: index },
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val photo = photos[page]
            ZoomablePhotoPage(
                photo = photo,
                isActivePage = page == pagerState.currentPage,
                onTap = onToggleControls,
                onZoomChanged = { zoomed ->
                    if (page == pagerState.currentPage) {
                        isCurrentPageZoomed = zoomed
                    }
                }
            )
        }

        // Subtle incoming transfer bar at very top when receiving a large 200MP photo
        state.incomingTransfer?.let { progress ->
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent
            )
        }

        // Tap-to-reveal HUD Overlay (hidden by default in full-screen)
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(receiverStatusColor(state.connectionStatus))
                        )
                        Text(
                            text = state.statusDetail,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "· ${pagerState.currentPage + 1} / ${photos.size}",
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = onOpenPairing) {
                            Icon(Icons.Rounded.QrCode2, contentDescription = "裝置配對", tint = Color.White)
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Rounded.Settings, contentDescription = "設定", tint = Color.White)
                        }
                    }
                }

                // Bottom Bar: Photo Metadata + Pause Auto-Switch + Jump to Latest
                currentPhoto?.let { photo ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.72f))
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = photo.fileName + if (photo.isPreview) "（快速預覽，原圖傳送中…）" else "",
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${FormatUtils.formatResolution(photo.width, photo.height)}  ·  ${FormatUtils.formatBytes(photo.fileSize)}  ·  接收時間 ${FormatUtils.formatTime(photo.receivedAtMs)}",
                                    color = Color.White.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = { onTogglePauseAutoSwitch(!settings.pauseAutoSwitch) }
                            ) {
                                Icon(
                                    imageVector = if (settings.pauseAutoSwitch) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (settings.pauseAutoSwitch) "恢復自動切換" else "暫停自動切換")
                            }

                            if (pagerState.currentPage < photos.lastIndex) {
                                Button(
                                    onClick = {
                                        scope.launch { pagerState.animateScrollToPage(photos.lastIndex) }
                                    }
                                ) {
                                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("返回最新照片")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomablePhotoPage(
    photo: ReceivedPhoto,
    isActivePage: Boolean,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val density = LocalDensity.current

    // Cap decode target to 2x display viewport max dimension so 200MP photos never OOM
    val maxDecodePx = remember(config.screenWidthDp, config.screenHeightDp, density) {
        val wPx = with(density) { config.screenWidthDp.dp.roundToPx() }
        val hPx = with(density) { config.screenHeightDp.dp.roundToPx() }
        (maxOf(wPx, hPx) * 2).coerceIn(1920, 4096)
    }

    var scale by remember(photo.photoId) { mutableFloatStateOf(1f) }
    var offset by remember(photo.photoId) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(isActivePage) {
        if (!isActivePage && scale != 1f) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    LaunchedEffect(scale) {
        onZoomChanged(scale > 1.02f)
    }

    val imageRequest = remember(photo.filePath, photo.isPreview, photo.fileSize, maxDecodePx) {
        val cacheKey = if (photo.isPreview) {
            "${photo.photoId}_preview"
        } else {
            "${photo.photoId}_full_${photo.fileSize}"
        }
        ImageRequest.Builder(context)
            .data(File(photo.filePath))
            .size(maxDecodePx, maxDecodePx)
            .memoryCacheKey(cacheKey)
            // Seamless preview -> original replacement without black flash
            .placeholderMemoryCacheKey("${photo.photoId}_preview")
            .crossfade(false)
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(photo.photoId) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            }
            .pointerInput(photo.photoId) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.count { it.pressed }
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()

                        // Only consume gestures when pinching (2+ fingers) or already zoomed in;
                        // when scale == 1f and 1 finger drags, leave unconsumed so HorizontalPager swipes smoothly.
                        if (pointerCount > 1 || scale > 1.02f) {
                            val newScale = (scale * zoomChange).coerceIn(1f, 6f)
                            if (newScale > 1.02f) {
                                val maxX = (size.width * (newScale - 1f)) / 2f
                                val maxY = (size.height * (newScale - 1f)) / 2f
                                offset = Offset(
                                    x = (offset.x + panChange.x).coerceIn(-maxX, maxX),
                                    y = (offset.y + panChange.y).coerceIn(-maxY, maxY)
                                )
                            } else {
                                offset = Offset.Zero
                            }
                            scale = newScale
                            event.changes.forEach { change ->
                                if (change.pressed) change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = photo.fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}

@Composable
private fun PairingInfoDialog(
    settings: AppSettings,
    state: ReceiverUiState,
    onDismiss: () -> Unit
) {
    val primaryIp = state.localIpAddresses.firstOrNull().orEmpty()
    val qrBitmap = remember(primaryIp, state.serverPort, state.pairingPin, settings.deviceName, settings.deviceId) {
        if (primaryIp.isNotBlank() && state.pairingPin.length == 6) {
            QrCodeUtils.generateQrBitmap(
                PairingQrPayload(
                    host = primaryIp,
                    port = state.serverPort,
                    pin = state.pairingPin,
                    deviceName = settings.deviceName,
                    deviceId = settings.deviceId
                ).toUriString(),
                480
            )
        } else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("平板配對資訊") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (qrBitmap != null) {
                    Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "配對 QR Code",
                            modifier = Modifier
                                .size(200.dp)
                                .padding(10.dp)
                        )
                    }
                }
                Text(
                    text = "配對碼：${state.pairingPin}",
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (state.localIpAddresses.isNotEmpty()) {
                        "IP：${state.localIpAddresses.joinToString(" / ")}\n連接埠：${state.serverPort}"
                    } else {
                        "尚未偵測到區域網路 IP\n連接埠：${state.serverPort}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("關閉") }
        }
    )
}

private fun receiverStatusColor(status: ConnectionStatus): Color = when (status) {
    ConnectionStatus.CONNECTED -> StatusConnected
    ConnectionStatus.CONNECTING, ConnectionStatus.AUTHENTICATING, ConnectionStatus.SEARCHING -> StatusConnecting
    ConnectionStatus.AUTH_FAILED, ConnectionStatus.ERROR -> StatusError
    ConnectionStatus.DISCONNECTED -> Color.Gray
}
