package com.livephotolink.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.livephotolink.app.data.AppSettings
import com.livephotolink.app.data.AppSettingsRepository
import com.livephotolink.app.model.AppMode
import com.livephotolink.app.model.DarkThemeMode
import com.livephotolink.app.model.PairedDevice
import com.livephotolink.app.model.StorageMode
import com.livephotolink.app.receiver.PhotoStorageManager
import com.livephotolink.app.receiver.ReceiverForegroundService
import com.livephotolink.app.receiver.ReceiverRepository
import com.livephotolink.app.sender.SenderForegroundService
import com.livephotolink.app.sender.SenderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val settingsRepo = AppSettingsRepository(appContext)
    private val storageManager = PhotoStorageManager(appContext)

    val settings: StateFlow<AppSettings> = settingsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val senderState = SenderRepository.state
    val receiverState = ReceiverRepository.state

    init {
        viewModelScope.launch {
            val init = settingsRepo.ensureInitialized()
            if (init.appMode == AppMode.RECEIVER && !ReceiverRepository.state.value.isServerRunning) {
                ReceiverForegroundService.start(appContext)
            }
        }
    }

    fun selectMode(mode: AppMode) {
        viewModelScope.launch {
            if (mode == AppMode.SENDER) {
                ReceiverForegroundService.stop(appContext)
            } else if (mode == AppMode.RECEIVER) {
                SenderForegroundService.stop(appContext)
            }
            settingsRepo.setAppMode(mode)
            if (mode == AppMode.RECEIVER) {
                ReceiverForegroundService.start(appContext)
            }
        }
    }

    fun toggleSenderService(enabled: Boolean) {
        if (enabled) {
            SenderForegroundService.start(appContext)
        } else {
            SenderForegroundService.stop(appContext)
        }
    }

    fun pairDevice(device: PairedDevice) {
        viewModelScope.launch {
            settingsRepo.setPairedDevice(device)
            if (SenderRepository.state.value.isServiceRunning) {
                SenderForegroundService.reconnect(appContext)
            } else {
                SenderForegroundService.start(appContext)
            }
        }
    }

    fun reconnectSender() {
        SenderForegroundService.reconnect(appContext)
    }

    fun updateDeviceName(name: String) {
        viewModelScope.launch { settingsRepo.setDeviceName(name) }
    }

    fun updateDarkThemeMode(mode: DarkThemeMode) {
        viewModelScope.launch { settingsRepo.setDarkThemeMode(mode) }
    }

    fun updateFastPreview(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setFastPreviewEnabled(enabled) }
    }

    fun updateKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setKeepScreenOn(enabled) }
    }

    fun updateStorageMode(mode: StorageMode) {
        viewModelScope.launch { settingsRepo.setStorageMode(mode) }
    }

    fun togglePauseAutoSwitch(paused: Boolean) {
        viewModelScope.launch { settingsRepo.setPauseAutoSwitch(paused) }
    }

    fun regeneratePin() {
        viewModelScope.launch { settingsRepo.regenerateReceiverPin() }
    }

    fun clearHistoryAndCache() {
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.clearAllHistoryAndCache()
            ReceiverRepository.clearPhotos()
        }
    }
}
