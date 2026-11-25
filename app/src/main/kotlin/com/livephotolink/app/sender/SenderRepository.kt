package com.livephotolink.app.sender

import com.livephotolink.app.model.ConnectionStatus
import com.livephotolink.app.model.DiscoveredDevice
import com.livephotolink.app.model.PairedDevice
import com.livephotolink.app.model.RecentSentPhoto
import com.livephotolink.app.model.SenderUiState
import com.livephotolink.app.model.TransferProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object SenderRepository {

    private val _state = MutableStateFlow(SenderUiState())
    val state: StateFlow<SenderUiState> = _state.asStateFlow()

    fun setServiceRunning(running: Boolean) {
        _state.update {
            it.copy(
                isServiceRunning = running,
                connectionStatus = if (!running) ConnectionStatus.DISCONNECTED else it.connectionStatus,
                statusDetail = if (!running) "傳送服務已停止" else it.statusDetail,
                activeTransfer = if (!running) null else it.activeTransfer,
                queuedCount = if (!running) 0 else it.queuedCount
            )
        }
    }

    fun updateConnection(status: ConnectionStatus, detail: String) {
        _state.update { it.copy(connectionStatus = status, statusDetail = detail) }
    }

    fun updatePairedDevice(device: PairedDevice) {
        _state.update { it.copy(pairedDevice = device) }
    }

    fun updateDiscoveredDevices(devices: List<DiscoveredDevice>) {
        _state.update { it.copy(discoveredDevices = devices) }
    }

    fun updateActiveTransfer(progress: TransferProgress?) {
        _state.update { it.copy(activeTransfer = progress) }
    }

    fun updateQueuedCount(count: Int) {
        _state.update { it.copy(queuedCount = count.coerceAtLeast(0)) }
    }

    fun recordTransferResult(photo: RecentSentPhoto) {
        _state.update { current ->
            val updatedList = (listOf(photo) + current.recentPhotos.filterNot { it.photoId == photo.photoId })
                .take(30)
            current.copy(
                recentPhotos = updatedList,
                successCount = if (photo.success) current.successCount + 1 else current.successCount,
                failureCount = if (!photo.success) current.failureCount + 1 else current.failureCount
            )
        }
    }

    fun resetSessionCounters() {
        _state.update {
            it.copy(
                successCount = 0,
                failureCount = 0,
                recentPhotos = emptyList(),
                activeTransfer = null,
                queuedCount = 0
            )
        }
    }
}
