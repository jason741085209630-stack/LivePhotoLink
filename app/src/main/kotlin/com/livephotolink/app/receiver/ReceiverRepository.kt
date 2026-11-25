package com.livephotolink.app.receiver

import com.livephotolink.app.model.ConnectionStatus
import com.livephotolink.app.model.ReceivedPhoto
import com.livephotolink.app.model.ReceiverUiState
import com.livephotolink.app.model.TransferProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

object ReceiverRepository {

    private val _state = MutableStateFlow(ReceiverUiState())
    val state: StateFlow<ReceiverUiState> = _state.asStateFlow()

    fun setServerRunning(running: Boolean, port: Int, pin: String, localIps: List<String>) {
        _state.update {
            it.copy(
                isServerRunning = running,
                serverPort = port,
                pairingPin = pin,
                localIpAddresses = localIps,
                connectionStatus = if (!running) ConnectionStatus.DISCONNECTED else it.connectionStatus,
                statusDetail = if (!running) "接收服務已停止" else "等待手機連線",
                connectedSenderName = if (!running) null else it.connectedSenderName,
                incomingTransfer = if (!running) null else it.incomingTransfer
            )
        }
    }

    fun updateNetworkInfo(localIps: List<String>, pin: String) {
        _state.update { it.copy(localIpAddresses = localIps, pairingPin = pin) }
    }

    fun updateConnection(status: ConnectionStatus, detail: String, senderName: String? = null) {
        _state.update {
            it.copy(
                connectionStatus = status,
                statusDetail = detail,
                connectedSenderName = senderName
            )
        }
    }

    fun updateIncomingTransfer(progress: TransferProgress?) {
        _state.update { it.copy(incomingTransfer = progress) }
    }

    fun loadInitialPhotos(photos: List<ReceivedPhoto>) {
        _state.update { current ->
            if (current.photos.isEmpty()) current.copy(photos = photos) else current
        }
    }

    /**
     * Inserts a newly received photo or seamlessly upgrades an existing preview photo
     * with its verified original file without changing list position or causing UI flicker.
     */
    fun onPhotoReceived(photo: ReceivedPhoto) {
        _state.update { current ->
            val existingIdx = current.photos.indexOfFirst { it.photoId == photo.photoId }
            if (existingIdx >= 0) {
                val existing = current.photos[existingIdx]
                // Clean up old preview file if upgrading to original
                if (existing.isPreview && !photo.isPreview && existing.filePath != photo.filePath) {
                    runCatching { File(existing.filePath).delete() }
                }
                val mutable = current.photos.toMutableList()
                mutable[existingIdx] = photo
                current.copy(photos = mutable)
            } else {
                // Append chronologically so newest is at lastIndex and swiping right shows the previous photo
                current.copy(
                    photos = current.photos + photo,
                    latestPhotoEventId = current.latestPhotoEventId + 1L
                )
            }
        }
    }

    fun clearPhotos() {
        _state.update { it.copy(photos = emptyList()) }
    }
}
