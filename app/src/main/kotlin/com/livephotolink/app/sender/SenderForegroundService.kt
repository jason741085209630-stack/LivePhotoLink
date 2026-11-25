package com.livephotolink.app.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.livephotolink.app.MainActivity
import com.livephotolink.app.R
import com.livephotolink.app.data.AppSettings
import com.livephotolink.app.data.AppSettingsRepository
import com.livephotolink.app.model.ConnectionStatus
import com.livephotolink.app.model.PairedDevice
import com.livephotolink.app.model.RecentSentPhoto
import com.livephotolink.app.model.TransferProgress
import com.livephotolink.app.network.AuthRejectedException
import com.livephotolink.app.network.CryptoEngine
import com.livephotolink.app.network.EncryptedChannel
import com.livephotolink.app.network.NsdDiscoveryManager
import com.livephotolink.app.network.PhotoStartHeader
import com.livephotolink.app.network.PhotoTransferProtocol
import com.livephotolink.app.network.ProtocolConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID

class SenderForegroundService : Service() {

    private data class QueuedPhoto(
        val photoId: String,
        val candidate: CapturedPhotoCandidate,
        var attempts: Int = 0
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settingsRepo: AppSettingsRepository
    private lateinit var nsdManager: NsdDiscoveryManager
    private var photoMonitor: CameraPhotoMonitor? = null

    private val queueMutex = Mutex()
    private val transferQueue = ArrayDeque<QueuedPhoto>()
    private val enqueuedMediaIds = mutableSetOf<Long>()

    @Volatile
    private var activeChannel: EncryptedChannel? = null
    @Volatile
    private var currentSettings: AppSettings = AppSettings()

    private var connectionLoopJob: Job? = null
    private var nsdObserveJob: Job? = null
    private var settingsObserveJob: Job? = null

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepo = AppSettingsRepository(applicationContext)
        nsdManager = NsdDiscoveryManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfCleanly()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                activeChannel?.close()
                activeChannel = null
            }
        }

        startForegroundNotification("正在準備即時照片傳送服務…")
        SenderRepository.setServiceRunning(true)
        acquireWifiLock()

        if (settingsObserveJob == null) {
            settingsObserveJob = serviceScope.launch {
                settingsRepo.ensureInitialized()
                settingsRepo.settingsFlow.collect { settings ->
                    val prevDevice = currentSettings.pairedDevice
                    currentSettings = settings
                    SenderRepository.updatePairedDevice(settings.pairedDevice)
                    if (prevDevice.host != settings.pairedDevice.host ||
                        prevDevice.port != settings.pairedDevice.port ||
                        prevDevice.pairingPin != settings.pairedDevice.pairingPin
                    ) {
                        activeChannel?.close()
                        activeChannel = null
                    }
                }
            }
        }

        if (nsdObserveJob == null) {
            nsdManager.startDiscovery()
            nsdObserveJob = serviceScope.launch {
                nsdManager.discoveredDevices.collect { devices ->
                    SenderRepository.updateDiscoveredDevices(devices)
                    // If our paired device changed IP on Wi-Fi, update target automatically
                    val paired = currentSettings.pairedDevice
                    if (paired.deviceId.isNotBlank()) {
                        val match = devices.firstOrNull { it.deviceId == paired.deviceId }
                        if (match != null && (match.host != paired.host || match.port != paired.port)) {
                            settingsRepo.setPairedDevice(
                                paired.copy(
                                    host = match.host,
                                    port = match.port,
                                    deviceName = match.deviceName
                                )
                            )
                        }
                    }
                }
            }
        }

        if (photoMonitor == null) {
            photoMonitor = CameraPhotoMonitor(applicationContext, serviceScope) { candidate ->
                serviceScope.launch {
                    enqueueCandidate(candidate)
                }
            }.also { it.start() }
        }

        if (connectionLoopJob == null || connectionLoopJob?.isActive != true) {
            connectionLoopJob = serviceScope.launch {
                runConnectionAndTransferLoop()
            }
        }

        return START_STICKY
    }

    private suspend fun enqueueCandidate(candidate: CapturedPhotoCandidate) {
        queueMutex.withLock {
            if (!enqueuedMediaIds.add(candidate.mediaId)) return
            val item = QueuedPhoto(
                photoId = "p_${candidate.mediaId}_${System.currentTimeMillis()}",
                candidate = candidate
            )
            transferQueue.addLast(item)
            SenderRepository.updateQueuedCount(transferQueue.size)
            Log.i(TAG, "Enqueued new camera photo: ${candidate.displayName} (${candidate.sizeBytes} bytes)")
        }
    }

    private suspend fun runConnectionAndTransferLoop() {
        var reconnectBackoffMs = 1_500L
        var lastPingMs = 0L

        while (serviceScope.isActive) {
            try {
                val settings = settingsRepo.settingsFlow.first()
                currentSettings = settings
                val paired = settings.pairedDevice

                if (!paired.isConfigured) {
                    SenderRepository.updateConnection(
                        ConnectionStatus.DISCONNECTED,
                        "尚未配對平板接收端"
                    )
                    updateNotification("尚未配對平板，請先完成配對")
                    delay(2_000L)
                    continue
                }

                // 1. Ensure connected & authenticated
                var channel = activeChannel
                if (channel == null) {
                    channel = connectToReceiver(settings, paired)
                    if (channel == null) {
                        delay(reconnectBackoffMs)
                        reconnectBackoffMs = (reconnectBackoffMs * 2).coerceAtMost(10_000L)
                        continue
                    }
                    activeChannel = channel
                    reconnectBackoffMs = 1_500L
                    lastPingMs = System.currentTimeMillis()
                    val displayName = channel.peerDeviceName.ifBlank { paired.deviceName.ifBlank { paired.host } }
                    SenderRepository.updateConnection(
                        ConnectionStatus.CONNECTED,
                        "已連線至 $displayName (${paired.host})"
                    )
                    updateNotification("已連線至 $displayName｜等待三星相機新照片")
                }

                // 2. Process next queued photo if available
                val nextItem = queueMutex.withLock { transferQueue.firstOrNull() }
                if (nextItem != null) {
                    val sentOk = transferQueuedPhoto(channel, nextItem, settings.fastPreviewEnabled)
                    if (sentOk) {
                        queueMutex.withLock {
                            transferQueue.removeFirstOrNull()
                            SenderRepository.updateQueuedCount(transferQueue.size)
                        }
                        lastPingMs = System.currentTimeMillis()
                    } else {
                        nextItem.attempts++
                        if (nextItem.attempts >= MAX_PHOTO_RETRIES) {
                            queueMutex.withLock {
                                transferQueue.removeFirstOrNull()
                                SenderRepository.updateQueuedCount(transferQueue.size)
                            }
                            SenderRepository.recordTransferResult(
                                RecentSentPhoto(
                                    photoId = nextItem.photoId,
                                    contentUri = nextItem.candidate.contentUri.toString(),
                                    fileName = nextItem.candidate.displayName,
                                    fileSize = nextItem.candidate.sizeBytes,
                                    timestampMs = System.currentTimeMillis(),
                                    success = false,
                                    errorMessage = "重試超過上限"
                                )
                            )
                        } else {
                            // Force reconnect before retrying
                            channel.close()
                            activeChannel = null
                            delay(1_000L)
                        }
                    }
                    continue
                }

                // 3. Idle heartbeat to detect silent disconnects
                val now = System.currentTimeMillis()
                if (now - lastPingMs >= ProtocolConstants.HEARTBEAT_INTERVAL_MS) {
                    channel.sendFrame(ProtocolConstants.FRAME_PING)
                    val resp = channel.readFrame()
                    if (resp.type != ProtocolConstants.FRAME_PONG && resp.type != ProtocolConstants.FRAME_PING) {
                        throw IOException("Unexpected heartbeat response: ${resp.type}")
                    }
                    lastPingMs = System.currentTimeMillis()
                }

                delay(200L)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthRejectedException) {
                Log.e(TAG, "Authentication rejected", e)
                activeChannel?.close()
                activeChannel = null
                SenderRepository.updateConnection(
                    ConnectionStatus.AUTH_FAILED,
                    e.message ?: "配對碼驗證失敗，請重新配對"
                )
                updateNotification("配對碼驗證失敗，請檢查平板配對碼")
                delay(5_000L)
            } catch (e: Exception) {
                Log.w(TAG, "Connection dropped or transfer error: ${e.message}")
                activeChannel?.close()
                activeChannel = null
                SenderRepository.updateActiveTransfer(null)
                SenderRepository.updateConnection(
                    ConnectionStatus.CONNECTING,
                    "連線中斷，正在自動重新連線…"
                )
                updateNotification("連線中斷，正在自動重新連線…")
                delay(reconnectBackoffMs)
                reconnectBackoffMs = (reconnectBackoffMs * 2).coerceAtMost(10_000L)
            }
        }
    }

    private fun connectToReceiver(settings: AppSettings, paired: PairedDevice): EncryptedChannel? {
        SenderRepository.updateConnection(
            ConnectionStatus.CONNECTING,
            "正在連線至 ${paired.deviceName.ifBlank { paired.host }}…"
        )
        updateNotification("正在連線至 ${paired.deviceName.ifBlank { paired.host }}…")

        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(paired.host, paired.port), 5_000)
            SenderRepository.updateConnection(ConnectionStatus.AUTHENTICATING, "正在進行加密驗證…")
            EncryptedChannel.handshakeAsClient(
                socket = socket,
                localDeviceId = settings.deviceId,
                localDeviceName = settings.deviceName,
                pairingPin = paired.pairingPin
            )
        } catch (e: AuthRejectedException) {
            runCatching { socket.close() }
            throw e
        } catch (e: Exception) {
            runCatching { socket.close() }
            Log.w(TAG, "Failed to connect to ${paired.host}:${paired.port}: ${e.message}")
            SenderRepository.updateConnection(
                ConnectionStatus.CONNECTING,
                "找不到平板 (${paired.host})，稍後重試…"
            )
            null
        }
    }

    private fun transferQueuedPhoto(
        channel: EncryptedChannel,
        item: QueuedPhoto,
        fastPreviewEnabled: Boolean
    ): Boolean {
        val candidate = item.candidate
        acquireTransferWakeLock()
        try {
            // Optional Fast Preview for large non-DNG photos
            val isDng = candidate.displayName.endsWith(".dng", ignoreCase = true) ||
                candidate.mimeType.contains("dng", ignoreCase = true)
            if (fastPreviewEnabled && !isDng && candidate.sizeBytes >= PreviewGenerator.FAST_PREVIEW_MIN_BYTES) {
                val preview = PreviewGenerator.generatePreview(applicationContext, candidate.contentUri)
                if (preview != null) {
                    val previewSha = CryptoEngine.toHex(
                        MessageDigest.getInstance("SHA-256").digest(preview.bytes)
                    )
                    val previewHeader = PhotoStartHeader(
                        transferId = UUID.randomUUID().toString(),
                        photoId = item.photoId,
                        fileName = candidate.displayName.substringBeforeLast('.') + ".jpg",
                        mimeType = "image/jpeg",
                        totalSize = preview.bytes.size.toLong(),
                        sha256 = previewSha,
                        width = preview.width,
                        height = preview.height,
                        orientation = preview.orientation,
                        dateTakenMs = candidate.dateTakenMs,
                        isPreview = true
                    )
                    PhotoTransferProtocol.sendPhotoStream(
                        channel = channel,
                        header = previewHeader,
                        inputStream = ByteArrayInputStream(preview.bytes)
                    ) { sent, total, speed ->
                        SenderRepository.updateActiveTransfer(
                            TransferProgress(
                                fileName = candidate.displayName,
                                bytesSent = sent,
                                totalBytes = total,
                                speedBytesPerSec = speed,
                                isPreview = true
                            )
                        )
                    }
                }
            }

            // Compute exact streaming SHA-256 and byte length of original file
            val (sha256, actualSize) = PhotoTransferProtocol.computeSha256AndSize {
                openOriginalPhotoStream(candidate.contentUri)
            }
            if (actualSize <= 0L) {
                Log.w(TAG, "Photo ${candidate.displayName} has 0 bytes, skipping")
                return true
            }

            val header = PhotoStartHeader(
                transferId = UUID.randomUUID().toString(),
                photoId = item.photoId,
                fileName = candidate.displayName,
                mimeType = candidate.mimeType,
                totalSize = actualSize,
                sha256 = sha256,
                width = candidate.width,
                height = candidate.height,
                orientation = candidate.orientation,
                dateTakenMs = candidate.dateTakenMs,
                isPreview = false
            )

            updateNotification("正在傳送 ${candidate.displayName}…")
            val ack = PhotoTransferProtocol.sendPhotoStream(
                channel = channel,
                header = header,
                inputStream = openOriginalPhotoStream(candidate.contentUri)
            ) { sent, total, speed ->
                SenderRepository.updateActiveTransfer(
                    TransferProgress(
                        fileName = candidate.displayName,
                        bytesSent = sent,
                        totalBytes = total,
                        speedBytesPerSec = speed,
                        isPreview = false
                    )
                )
            }

            SenderRepository.updateActiveTransfer(null)

            if (ack.ok && ack.sha256.equals(sha256, ignoreCase = true)) {
                SenderRepository.recordTransferResult(
                    RecentSentPhoto(
                        photoId = item.photoId,
                        contentUri = candidate.contentUri.toString(),
                        fileName = candidate.displayName,
                        fileSize = actualSize,
                        timestampMs = System.currentTimeMillis(),
                        success = true
                    )
                )
                val state = SenderRepository.state.value
                updateNotification("已傳送 ${state.successCount} 張照片｜待命偵測中")
                Log.i(TAG, "Verified transfer complete: ${candidate.displayName} ($actualSize bytes)")
                return true
            } else {
                Log.w(TAG, "Transfer ACK failed for ${candidate.displayName}: ${ack.error}")
                return false
            }
        } catch (e: Exception) {
            SenderRepository.updateActiveTransfer(null)
            Log.e(TAG, "Error transferring ${candidate.displayName}", e)
            throw e
        } finally {
            releaseTransferWakeLock()
        }
    }

    private fun openOriginalPhotoStream(uri: Uri): InputStream {
        // Attempt setRequireOriginal so GPS/EXIF metadata is preserved when ACCESS_MEDIA_LOCATION is granted
        val originalUri = runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
        return runCatching {
            contentResolver.openInputStream(originalUri)
        }.getOrNull()
            ?: contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open input stream for $uri")
    }

    private fun stopSelfCleanly() {
        photoMonitor?.stop()
        photoMonitor = null
        nsdManager.stopDiscovery()
        activeChannel?.close()
        activeChannel = null
        connectionLoopJob?.cancel()
        nsdObserveJob?.cancel()
        settingsObserveJob?.cancel()
        releaseTransferWakeLock()
        releaseWifiLock()
        SenderRepository.setServiceRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        photoMonitor?.stop()
        nsdManager.stopDiscovery()
        activeChannel?.close()
        releaseTransferWakeLock()
        releaseWifiLock()
        SenderRepository.setServiceRunning(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_sender_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_sender_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SenderForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("LivePhoto Link 傳送端運作中")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "停止服務", stopIntent)
            .build()
    }

    private fun startForegroundNotification(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun acquireWifiLock() {
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiLock == null) {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(mode, "LivePhotoLink:SenderWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
        }
    }

    private fun releaseWifiLock() {
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private fun acquireTransferWakeLock() {
        runCatching {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LivePhotoLink:TransferWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(60_000L)
            }
        }
    }

    private fun releaseTransferWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }

    companion object {
        private const val TAG = "SenderForegroundService"
        private const val CHANNEL_ID = "livephotolink_sender"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_PHOTO_RETRIES = 5

        const val ACTION_START = "com.livephotolink.app.action.SENDER_START"
        const val ACTION_STOP = "com.livephotolink.app.action.SENDER_STOP"
        const val ACTION_RECONNECT = "com.livephotolink.app.action.SENDER_RECONNECT"

        fun start(context: Context) {
            val intent = Intent(context, SenderForegroundService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SenderForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun reconnect(context: Context) {
            val intent = Intent(context, SenderForegroundService::class.java).apply {
                action = ACTION_RECONNECT
            }
            context.startService(intent)
        }
    }
}
