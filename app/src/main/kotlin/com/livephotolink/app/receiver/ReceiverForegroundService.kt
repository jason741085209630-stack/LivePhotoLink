package com.livephotolink.app.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.livephotolink.app.MainActivity
import com.livephotolink.app.R
import com.livephotolink.app.data.AppSettings
import com.livephotolink.app.data.AppSettingsRepository
import com.livephotolink.app.model.ConnectionStatus
import com.livephotolink.app.model.ReceivedPhoto
import com.livephotolink.app.model.StorageMode
import com.livephotolink.app.model.TransferProgress
import com.livephotolink.app.network.AuthRejectedException
import com.livephotolink.app.network.EncryptedChannel
import com.livephotolink.app.network.NetworkUtils
import com.livephotolink.app.network.NsdDiscoveryManager
import com.livephotolink.app.network.PhotoStartHeader
import com.livephotolink.app.network.PhotoTransferProtocol
import com.livephotolink.app.network.ProtocolConstants
import com.livephotolink.app.network.protocolJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class ReceiverForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settingsRepo: AppSettingsRepository
    private lateinit var storageManager: PhotoStorageManager
    private lateinit var nsdManager: NsdDiscoveryManager

    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var activeClientChannel: EncryptedChannel? = null
    @Volatile
    private var currentSettings: AppSettings = AppSettings()

    private var serverLoopJob: Job? = null
    private var ipRefreshJob: Job? = null
    private var settingsJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepo = AppSettingsRepository(applicationContext)
        storageManager = PhotoStorageManager(applicationContext)
        nsdManager = NsdDiscoveryManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        startForegroundNotification("等待手機傳送即時照片…")
        acquireWifiLock()

        if (settingsJob == null) {
            settingsJob = serviceScope.launch {
                val init = settingsRepo.ensureInitialized()
                currentSettings = init
                if (init.storageMode == StorageMode.DISPLAY_ONLY) {
                    // Clean any stale cache from a previous terminated session
                    storageManager.clearDisplayOnlyCache()
                    ReceiverRepository.clearPhotos()
                } else {
                    ReceiverRepository.loadInitialPhotos(storageManager.loadExistingPrivatePhotos())
                }
                startServerIfNeeded(init)

                settingsRepo.settingsFlow.collect { updated ->
                    currentSettings = updated
                    ReceiverRepository.updateNetworkInfo(
                        localIps = NetworkUtils.getLocalIpv4Addresses(),
                        pin = updated.receiverPairingPin
                    )
                }
            }
        }

        if (ipRefreshJob == null) {
            ipRefreshJob = serviceScope.launch {
                while (isActive) {
                    delay(5_000L)
                    ReceiverRepository.updateNetworkInfo(
                        localIps = NetworkUtils.getLocalIpv4Addresses(),
                        pin = currentSettings.receiverPairingPin
                    )
                }
            }
        }

        return START_STICKY
    }

    private fun startServerIfNeeded(settings: AppSettings) {
        if (serverLoopJob?.isActive == true) return
        serverLoopJob = serviceScope.launch {
            val socket = bindServerSocket(settings.receiverPort)
            serverSocket = socket
            val boundPort = socket.localPort
            val ips = NetworkUtils.getLocalIpv4Addresses()

            ReceiverRepository.setServerRunning(
                running = true,
                port = boundPort,
                pin = settings.receiverPairingPin,
                localIps = ips
            )
            nsdManager.registerReceiverService(
                deviceName = settings.deviceName,
                deviceId = settings.deviceId,
                port = boundPort
            )
            updateNotification("待命接收中 (${ips.firstOrNull() ?: "LAN"}:$boundPort)")
            Log.i(TAG, "Receiver TCP server listening on port $boundPort, IPs=$ips")

            while (isActive && !socket.isClosed) {
                val clientSocket = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (socket.isClosed || !isActive) break
                    Log.w(TAG, "Accept error: ${e.message}")
                    delay(500L)
                    continue
                }
                handleClientConnection(clientSocket)
            }
        }
    }

    private fun bindServerSocket(preferredPort: Int): ServerSocket {
        return try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(preferredPort))
            }
        } catch (e: IOException) {
            Log.w(TAG, "Preferred port $preferredPort busy, binding ephemeral port")
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
            }
        }
    }

    private fun handleClientConnection(clientSocket: Socket) {
        val remoteIp = clientSocket.inetAddress?.hostAddress ?: "unknown"
        Log.i(TAG, "Incoming connection from $remoteIp")

        // Close any stale client session
        activeClientChannel?.close()
        activeClientChannel = null

        var channel: EncryptedChannel? = null
        try {
            ReceiverRepository.updateConnection(
                ConnectionStatus.AUTHENTICATING,
                "正在驗證來自 $remoteIp 的連線…"
            )
            channel = EncryptedChannel.handshakeAsServer(
                socket = clientSocket,
                localDeviceId = currentSettings.deviceId,
                localDeviceName = currentSettings.deviceName,
                pairingPin = currentSettings.receiverPairingPin
            )
            activeClientChannel = channel
            val senderName = channel.peerDeviceName.ifBlank { remoteIp }
            ReceiverRepository.updateConnection(
                ConnectionStatus.CONNECTED,
                "已連線：$senderName",
                senderName = senderName
            )
            updateNotification("已連線：$senderName｜即時顯示模式")

            while (serviceScope.isActive) {
                val frame = channel.readFrame()
                when (frame.type) {
                    ProtocolConstants.FRAME_PING -> {
                        channel.sendFrame(ProtocolConstants.FRAME_PONG)
                    }
                    ProtocolConstants.FRAME_PONG -> {
                        // keepalive ack
                    }
                    ProtocolConstants.FRAME_PHOTO_START -> {
                        val header = protocolJson.decodeFromString<PhotoStartHeader>(
                            String(frame.payload, StandardCharsets.UTF_8)
                        )
                        receiveAndStorePhoto(channel, header, senderName)
                    }
                    else -> {
                        Log.w(TAG, "Ignoring unexpected frame type: ${frame.type}")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthRejectedException) {
            Log.w(TAG, "Rejected unauthorized device $remoteIp: ${e.message}")
            ReceiverRepository.updateConnection(
                ConnectionStatus.AUTH_FAILED,
                "已拒絕未授權裝置 ($remoteIp)"
            )
        } catch (e: EOFException) {
            Log.i(TAG, "Sender disconnected cleanly")
            ReceiverRepository.updateConnection(
                ConnectionStatus.DISCONNECTED,
                "手機已斷線，等待自動重新連線…"
            )
            updateNotification("等待手機重新連線…")
        } catch (e: Exception) {
            Log.w(TAG, "Client session ended: ${e.message}")
            ReceiverRepository.updateConnection(
                ConnectionStatus.DISCONNECTED,
                "連線中斷，等待手機重新連線…"
            )
            updateNotification("等待手機重新連線…")
        } finally {
            ReceiverRepository.updateIncomingTransfer(null)
            channel?.close()
            if (activeClientChannel === channel) activeClientChannel = null
            runCatching { clientSocket.close() }
            if (currentSettings.storageMode == StorageMode.DISPLAY_ONLY) {
                storageManager.clearDisplayOnlyCache()
                ReceiverRepository.clearPhotos()
                Log.i(TAG, "Cleared session photo cache after disconnect")
            }
        }
    }

    private fun receiveAndStorePhoto(
        channel: EncryptedChannel,
        header: PhotoStartHeader,
        senderName: String
    ) {
        val mode = currentSettings.storageMode
        val targetDir = storageManager.getTargetDir(mode)

        val result = PhotoTransferProtocol.receivePhotoBody(
            channel = channel,
            header = header,
            targetDir = targetDir
        ) { received, total, speed ->
            ReceiverRepository.updateIncomingTransfer(
                TransferProgress(
                    fileName = header.fileName,
                    bytesSent = received,
                    totalBytes = total,
                    speedBytesPerSec = speed,
                    isPreview = header.isPreview
                )
            )
        }
        ReceiverRepository.updateIncomingTransfer(null)

        if (result == null) {
            Log.w(TAG, "Integrity check failed for ${header.fileName}")
            return
        }

        val (width, height, orientation) = storageManager.readImageMetadata(
            file = result.file,
            fallbackWidth = header.width,
            fallbackHeight = header.height,
            fallbackOrientation = header.orientation
        )

        var savedToGallery = false
        if (!header.isPreview && mode == StorageMode.SYSTEM_GALLERY) {
            savedToGallery = storageManager.exportToSystemGallery(
                file = result.file,
                displayName = header.fileName,
                mimeType = header.mimeType
            )
        }

        if (mode == StorageMode.DISPLAY_ONLY) {
            storageManager.trimDisplayOnlyCache(maxFiles = 100)
        }

        val displayFile = if (!header.isPreview) {
            storageManager.resolveDisplayFile(result.file, header.mimeType)
        } else {
            result.file
        }

        val receivedPhoto = ReceivedPhoto(
            photoId = header.photoId,
            fileName = header.fileName,
            filePath = displayFile.absolutePath,
            mimeType = header.mimeType,
            fileSize = header.totalSize,
            width = width,
            height = height,
            orientation = orientation,
            receivedAtMs = System.currentTimeMillis(),
            sha256 = result.verifiedSha256,
            isPreview = header.isPreview,
            savedToGallery = savedToGallery
        )
        ReceiverRepository.onPhotoReceived(receivedPhoto)
        updateNotification("已接收 ${header.fileName}（來自 $senderName）")
        Log.i(TAG, "Received & verified ${header.fileName} (preview=${header.isPreview}, ${header.totalSize} bytes)")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (currentSettings.storageMode == StorageMode.DISPLAY_ONLY) {
            storageManager.clearDisplayOnlyCache()
            ReceiverRepository.clearPhotos()
            Log.i(TAG, "App task removed: cleared session photo cache and stopping receiver")
            stopSelfCleanly()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun stopSelfCleanly() {
        nsdManager.unregisterReceiverService()
        activeClientChannel?.close()
        activeClientChannel = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverLoopJob?.cancel()
        ipRefreshJob?.cancel()
        settingsJob?.cancel()
        releaseWifiLock()
        if (currentSettings.storageMode == StorageMode.DISPLAY_ONLY) {
            storageManager.clearDisplayOnlyCache()
            ReceiverRepository.clearPhotos()
        }
        ReceiverRepository.setServerRunning(false, currentSettings.receiverPort, currentSettings.receiverPairingPin, emptyList())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        nsdManager.unregisterReceiverService()
        activeClientChannel?.close()
        runCatching { serverSocket?.close() }
        releaseWifiLock()
        if (currentSettings.storageMode == StorageMode.DISPLAY_ONLY) {
            storageManager.clearDisplayOnlyCache()
            ReceiverRepository.clearPhotos()
        }
        ReceiverRepository.setServerRunning(false, currentSettings.receiverPort, currentSettings.receiverPairingPin, emptyList())
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_receiver_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_receiver_desc)
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
            2,
            Intent(this, ReceiverForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("LivePhoto Link 接收端待命中")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "停止接收", stopIntent)
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
                wifiLock = wm?.createWifiLock(mode, "LivePhotoLink:ReceiverWifiLock")?.apply {
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

    companion object {
        private const val TAG = "ReceiverForegroundService"
        private const val CHANNEL_ID = "livephotolink_receiver"
        private const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.livephotolink.app.action.RECEIVER_START"
        const val ACTION_STOP = "com.livephotolink.app.action.RECEIVER_STOP"

        fun start(context: Context) {
            val intent = Intent(context, ReceiverForegroundService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ReceiverForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
