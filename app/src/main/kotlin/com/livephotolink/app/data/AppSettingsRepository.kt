package com.livephotolink.app.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.livephotolink.app.model.AppMode
import com.livephotolink.app.model.DarkThemeMode
import com.livephotolink.app.model.PairedDevice
import com.livephotolink.app.model.StorageMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "livephotolink_prefs")

data class AppSettings(
    val appMode: AppMode = AppMode.UNSET,
    val deviceId: String = "",
    val deviceName: String = Build.MODEL ?: "Android 裝置",
    val receiverPairingPin: String = "",
    val receiverPort: Int = 48910,
    val pairedDevice: PairedDevice = PairedDevice(),
    val fastPreviewEnabled: Boolean = false,
    val keepScreenOn: Boolean = true,
    val storageMode: StorageMode = StorageMode.DISPLAY_ONLY,
    val darkThemeMode: DarkThemeMode = DarkThemeMode.SYSTEM,
    val pauseAutoSwitch: Boolean = false
)

class AppSettingsRepository(private val context: Context) {

    private object Keys {
        val APP_MODE = stringPreferencesKey("app_mode")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val RECEIVER_PIN = stringPreferencesKey("receiver_pin")
        val RECEIVER_PORT = intPreferencesKey("receiver_port")
        val PAIRED_DEVICE_ID = stringPreferencesKey("paired_device_id")
        val PAIRED_DEVICE_NAME = stringPreferencesKey("paired_device_name")
        val PAIRED_HOST = stringPreferencesKey("paired_host")
        val PAIRED_PORT = intPreferencesKey("paired_port")
        val PAIRED_PIN = stringPreferencesKey("paired_pin")
        val FAST_PREVIEW = booleanPreferencesKey("fast_preview")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val STORAGE_MODE = stringPreferencesKey("storage_mode")
        val DARK_THEME_MODE = stringPreferencesKey("dark_theme_mode")
        val PAUSE_AUTO_SWITCH = booleanPreferencesKey("pause_auto_switch")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            appMode = prefs[Keys.APP_MODE]?.let { runCatching { AppMode.valueOf(it) }.getOrNull() } ?: AppMode.UNSET,
            deviceId = prefs[Keys.DEVICE_ID].orEmpty(),
            deviceName = prefs[Keys.DEVICE_NAME]?.ifBlank { defaultDeviceName() } ?: defaultDeviceName(),
            receiverPairingPin = prefs[Keys.RECEIVER_PIN].orEmpty(),
            receiverPort = prefs[Keys.RECEIVER_PORT] ?: 48910,
            pairedDevice = PairedDevice(
                deviceId = prefs[Keys.PAIRED_DEVICE_ID].orEmpty(),
                deviceName = prefs[Keys.PAIRED_DEVICE_NAME].orEmpty(),
                host = prefs[Keys.PAIRED_HOST].orEmpty(),
                port = prefs[Keys.PAIRED_PORT] ?: 48910,
                pairingPin = prefs[Keys.PAIRED_PIN].orEmpty()
            ),
            fastPreviewEnabled = prefs[Keys.FAST_PREVIEW] ?: false,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: true,
            storageMode = prefs[Keys.STORAGE_MODE]?.let { runCatching { StorageMode.valueOf(it) }.getOrNull() }
                ?: StorageMode.DISPLAY_ONLY,
            darkThemeMode = prefs[Keys.DARK_THEME_MODE]?.let { runCatching { DarkThemeMode.valueOf(it) }.getOrNull() }
                ?: DarkThemeMode.SYSTEM,
            pauseAutoSwitch = prefs[Keys.PAUSE_AUTO_SWITCH] ?: false
        )
    }

    suspend fun ensureInitialized(): AppSettings {
        val current = settingsFlow.first()
        if (current.deviceId.isBlank() || current.receiverPairingPin.length != 6) {
            val newId = current.deviceId.ifBlank { UUID.randomUUID().toString().take(12) }
            val newPin = if (current.receiverPairingPin.length == 6) current.receiverPairingPin else generatePin()
            context.dataStore.edit { prefs ->
                prefs[Keys.DEVICE_ID] = newId
                prefs[Keys.RECEIVER_PIN] = newPin
                if (prefs[Keys.DEVICE_NAME].isNullOrBlank()) {
                    prefs[Keys.DEVICE_NAME] = defaultDeviceName()
                }
            }
            return settingsFlow.first()
        }
        return current
    }

    suspend fun setAppMode(mode: AppMode) {
        context.dataStore.edit { it[Keys.APP_MODE] = mode.name }
    }

    suspend fun setDeviceName(name: String) {
        val clean = name.trim().ifBlank { defaultDeviceName() }
        context.dataStore.edit { it[Keys.DEVICE_NAME] = clean }
    }

    suspend fun regenerateReceiverPin(): String {
        val pin = generatePin()
        context.dataStore.edit { it[Keys.RECEIVER_PIN] = pin }
        return pin
    }

    suspend fun setPairedDevice(device: PairedDevice) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PAIRED_DEVICE_ID] = device.deviceId
            prefs[Keys.PAIRED_DEVICE_NAME] = device.deviceName.ifBlank { device.host }
            prefs[Keys.PAIRED_HOST] = device.host.trim()
            prefs[Keys.PAIRED_PORT] = device.port
            prefs[Keys.PAIRED_PIN] = device.pairingPin.trim()
        }
    }

    suspend fun clearPairedDevice() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.PAIRED_DEVICE_ID)
            prefs.remove(Keys.PAIRED_DEVICE_NAME)
            prefs.remove(Keys.PAIRED_HOST)
            prefs.remove(Keys.PAIRED_PORT)
            prefs.remove(Keys.PAIRED_PIN)
        }
    }

    suspend fun setFastPreviewEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.FAST_PREVIEW] = enabled }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setStorageMode(mode: StorageMode) {
        context.dataStore.edit { it[Keys.STORAGE_MODE] = mode.name }
    }

    suspend fun setDarkThemeMode(mode: DarkThemeMode) {
        context.dataStore.edit { it[Keys.DARK_THEME_MODE] = mode.name }
    }

    suspend fun setPauseAutoSwitch(paused: Boolean) {
        context.dataStore.edit { it[Keys.PAUSE_AUTO_SWITCH] = paused }
    }

    companion object {
        private val secureRandom = SecureRandom()

        fun generatePin(): String {
            val number = secureRandom.nextInt(1_000_000)
            return String.format("%06d", number)
        }

        fun defaultDeviceName(): String {
            val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
            val model = Build.MODEL?.trim().orEmpty()
            return when {
                model.isBlank() -> "Android 裝置"
                manufacturer.isNotBlank() && !model.startsWith(manufacturer, ignoreCase = true) ->
                    "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
                else -> model
            }
        }
    }
}
