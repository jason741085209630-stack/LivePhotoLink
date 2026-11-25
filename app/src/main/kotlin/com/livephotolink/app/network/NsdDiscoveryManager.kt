package com.livephotolink.app.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.livephotolink.app.model.DiscoveredDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Manages Android NSD (mDNS) service registration on the Receiver
 * and discovery on the Sender.
 */
class NsdDiscoveryManager(context: Context) {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val resolveExecutor = Executors.newSingleThreadExecutor()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    fun registerReceiverService(deviceName: String, deviceId: String, port: Int) {
        unregisterReceiverService()
        acquireMulticastLock()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "LivePhotoLink-${deviceId.take(6)}"
            serviceType = ProtocolConstants.SERVICE_TYPE
            setPort(port)
            setAttribute("name", deviceName.take(40))
            setAttribute("id", deviceId)
            setAttribute("ver", ProtocolConstants.PROTOCOL_VERSION.toString())
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD service registered: ${info.serviceName} on port $port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD service unregistered: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD unregistration failed: $errorCode")
            }
        }
        registrationListener = listener
        runCatching {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            Log.e(TAG, "Failed to register NSD service", it)
        }
    }

    fun unregisterReceiverService() {
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        registrationListener = null
        releaseMulticastLock()
    }

    fun startDiscovery() {
        stopDiscovery()
        acquireMulticastLock()
        _discoveredDevices.value = emptyList()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(TAG, "NSD discovery started for $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("livephotolink")) return
                resolveService(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _discoveredDevices.update { list ->
                    list.filterNot { it.serviceName == serviceInfo.serviceName }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "NSD discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD startDiscovery failed: $errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD stopDiscovery failed: $errorCode")
            }
        }
        discoveryListener = listener
        runCatching {
            nsdManager.discoverServices(
                ProtocolConstants.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener
            )
        }.onFailure {
            Log.e(TAG, "Failed to start NSD discovery", it)
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        discoveryListener = null
        releaseMulticastLock()
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                nsdManager.registerServiceInfoCallback(
                    serviceInfo,
                    resolveExecutor,
                    object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}

                        override fun onServiceUpdated(updatedInfo: NsdServiceInfo) {
                            val hostAddr = updatedInfo.hostAddresses
                                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                                ?.hostAddress
                                ?: updatedInfo.hostAddresses.firstOrNull()?.hostAddress
                            if (!hostAddr.isNullOrBlank() && updatedInfo.port > 0) {
                                addOrUpdateDevice(updatedInfo, hostAddr)
                                runCatching { nsdManager.unregisterServiceInfoCallback(this) }
                            }
                        }

                        override fun onServiceLost() {}
                        override fun onServiceInfoCallbackUnregistered() {}
                    }
                )
            }
        } else {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "NSD resolve failed for ${info.serviceName}: $errorCode")
                }

                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    val hostAddr = resolvedInfo.host?.hostAddress ?: return
                    addOrUpdateDevice(resolvedInfo, hostAddr)
                }
            })
        }
    }

    private fun addOrUpdateDevice(info: NsdServiceInfo, hostAddr: String) {
        val attrs = info.attributes
        val nameBytes = attrs["name"]
        val idBytes = attrs["id"]
        val deviceName = if (nameBytes != null) String(nameBytes, StandardCharsets.UTF_8) else info.serviceName
        val deviceId = if (idBytes != null) String(idBytes, StandardCharsets.UTF_8) else info.serviceName
        val discovered = DiscoveredDevice(
            serviceName = info.serviceName,
            deviceId = deviceId,
            deviceName = deviceName,
            host = hostAddr,
            port = info.port
        )
        _discoveredDevices.update { existing ->
            val filtered = existing.filterNot {
                it.deviceId == discovered.deviceId || it.host == discovered.host
            }
            filtered + discovered
        }
    }

    private fun acquireMulticastLock() {
        runCatching {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("LivePhotoLinkMulticast")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        runCatching {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        }
    }

    companion object {
        private const val TAG = "NsdDiscoveryManager"
    }
}
