package com.livephotolink.app.model

import kotlinx.serialization.Serializable
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class AppMode {
    UNSET,
    SENDER,
    RECEIVER
}

enum class StorageMode {
    APP_PRIVATE,
    SYSTEM_GALLERY,
    DISPLAY_ONLY
}

enum class DarkThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class ConnectionStatus {
    DISCONNECTED,
    SEARCHING,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    AUTH_FAILED,
    ERROR
}

@Serializable
data class PairedDevice(
    val deviceId: String = "",
    val deviceName: String = "",
    val host: String = "",
    val port: Int = 48910,
    val pairingPin: String = ""
) {
    val isConfigured: Boolean
        get() = host.isNotBlank() && port in 1..65535 && pairingPin.length == 6
}

data class DiscoveredDevice(
    val serviceName: String,
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val port: Int
)

data class TransferProgress(
    val fileName: String = "",
    val bytesSent: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val isPreview: Boolean = false
) {
    val fraction: Float
        get() = if (totalBytes > 0L) (bytesSent.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

data class RecentSentPhoto(
    val photoId: String,
    val contentUri: String,
    val fileName: String,
    val fileSize: Long,
    val timestampMs: Long,
    val success: Boolean,
    val errorMessage: String? = null
)

data class SenderUiState(
    val isServiceRunning: Boolean = false,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val statusDetail: String = "尚未啟動傳送服務",
    val pairedDevice: PairedDevice = PairedDevice(),
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    val activeTransfer: TransferProgress? = null,
    val queuedCount: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val recentPhotos: List<RecentSentPhoto> = emptyList()
)

data class ReceivedPhoto(
    val photoId: String,
    val fileName: String,
    val filePath: String,
    val mimeType: String,
    val fileSize: Long,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val receivedAtMs: Long,
    val sha256: String,
    val isPreview: Boolean = false,
    val savedToGallery: Boolean = false
)

data class ReceiverUiState(
    val isServerRunning: Boolean = false,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val statusDetail: String = "等待手機連線",
    val localIpAddresses: List<String> = emptyList(),
    val serverPort: Int = 48910,
    val pairingPin: String = "",
    val connectedSenderName: String? = null,
    val incomingTransfer: TransferProgress? = null,
    val photos: List<ReceivedPhoto> = emptyList(),
    val latestPhotoEventId: Long = 0L
)

data class PairingQrPayload(
    val host: String,
    val port: Int,
    val pin: String,
    val deviceName: String,
    val deviceId: String
) {
    fun toUriString(): String {
        val encName = URLEncoder.encode(deviceName, StandardCharsets.UTF_8.name())
        val encId = URLEncoder.encode(deviceId, StandardCharsets.UTF_8.name())
        return "livephotolink://pair?host=$host&port=$port&pin=$pin&name=$encName&id=$encId"
    }

    companion object {
        fun parse(raw: String): PairingQrPayload? {
            if (!raw.startsWith("livephotolink://pair?")) return null
            val query = raw.substringAfter("?", "")
            val params = query.split("&").mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null
                else part.substring(0, idx) to URLDecoder.decode(
                    part.substring(idx + 1),
                    StandardCharsets.UTF_8.name()
                )
            }.toMap()

            val host = params["host"]?.takeIf { it.isNotBlank() } ?: return null
            val port = params["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val pin = params["pin"]?.takeIf { it.length == 6 && it.all(Char::isDigit) } ?: return null
            val name = params["name"]?.ifBlank { "平板接收端" } ?: "平板接收端"
            val id = params["id"].orEmpty()
            return PairingQrPayload(host = host, port = port, pin = pin, deviceName = name, deviceId = id)
        }
    }
}
