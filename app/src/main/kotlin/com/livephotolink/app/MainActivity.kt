package com.livephotolink.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.livephotolink.app.model.AppMode
import com.livephotolink.app.ui.ModeSelectionScreen
import com.livephotolink.app.ui.ReceiverScreen
import com.livephotolink.app.ui.SenderScreen
import com.livephotolink.app.ui.SettingsScreen
import com.livephotolink.app.ui.theme.LivePhotoLinkTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by viewModel.settings.collectAsState()
            val senderState by viewModel.senderState.collectAsState()
            val receiverState by viewModel.receiverState.collectAsState()
            var showSettings by rememberSaveable { mutableStateOf(false) }

            BackHandler(enabled = showSettings) {
                showSettings = false
            }

            LivePhotoLinkTheme(darkThemeMode = settings.darkThemeMode) {
                when {
                    showSettings -> {
                        SettingsScreen(
                            settings = settings,
                            onBack = { showSettings = false },
                            onSwitchMode = { mode ->
                                viewModel.selectMode(mode)
                                showSettings = false
                            },
                            onUpdateDeviceName = viewModel::updateDeviceName,
                            onUpdateDarkThemeMode = viewModel::updateDarkThemeMode,
                            onUpdateFastPreview = viewModel::updateFastPreview,
                            onUpdateKeepScreenOn = viewModel::updateKeepScreenOn,
                            onUpdateStorageMode = viewModel::updateStorageMode,
                            onRegeneratePin = viewModel::regeneratePin,
                            onClearHistoryAndCache = viewModel::clearHistoryAndCache
                        )
                    }

                    settings.appMode == AppMode.UNSET -> {
                        ModeSelectionScreen(onSelectMode = viewModel::selectMode)
                    }

                    settings.appMode == AppMode.SENDER -> {
                        SenderScreen(
                            settings = settings,
                            state = senderState,
                            onToggleService = viewModel::toggleSenderService,
                            onPairDevice = viewModel::pairDevice,
                            onReconnect = viewModel::reconnectSender,
                            onOpenSettings = { showSettings = true }
                        )
                    }

                    settings.appMode == AppMode.RECEIVER -> {
                        ReceiverScreen(
                            settings = settings,
                            state = receiverState,
                            onTogglePauseAutoSwitch = viewModel::togglePauseAutoSwitch,
                            onOpenSettings = { showSettings = true }
                        )
                    }
                }
            }
        }
    }
}
