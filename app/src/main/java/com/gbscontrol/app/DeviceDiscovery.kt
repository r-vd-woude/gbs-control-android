package com.gbscontrol.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.ArrayDeque

/** Discovers both API-aware and legacy GBS-Control services without assuming Internet access. */
class DeviceDiscovery(context: Context, private val onFound: (DiscoveredDevice) -> Unit) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val listeners = mutableMapOf<String, NsdManager.DiscoveryListener>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        SERVICE_TYPES.forEach { type ->
            if (listeners.containsKey(type)) return@forEach
            val listener = listenerFor(type)
            listeners[type] = listener
            try {
                nsd?.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (error: Exception) {
                Log.w(TAG, "Discovery failed for $type", error)
                listeners.remove(type)
            }
        }
    }

    private fun listenerFor(type: String) = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Start discovery failed for $type: $errorCode")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onServiceLost(service: NsdServiceInfo) = Unit

        override fun onServiceFound(service: NsdServiceInfo) {
            val isApiService = type.startsWith("_gbs-control")
            if (isApiService || service.serviceName.contains("gbs", ignoreCase = true)) {
                synchronized(pending) { pending.add(service) }
                resolveNext()
            }
        }
    }

    private fun resolveNext() {
        val manager = nsd ?: return
        val service = synchronized(pending) {
            if (resolving || pending.isEmpty()) return
            resolving = true
            pending.removeFirst()
        }
        try {
            @Suppress("DEPRECATION")
            manager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = finishResolve()

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress
                    if (!host.isNullOrBlank()) {
                        val version = serviceInfo.attributes["api"]?.toString(Charsets.UTF_8)?.toIntOrNull()
                        onFound(
                            DiscoveredDevice(
                                name = serviceInfo.serviceName,
                                host = host,
                                port = serviceInfo.port.takeIf { it > 0 } ?: 80,
                                apiVersion = version,
                            )
                        )
                    }
                    finishResolve()
                }
            })
        } catch (error: Exception) {
            Log.w(TAG, "Resolve failed", error)
            finishResolve()
        }
    }

    private fun finishResolve() {
        synchronized(pending) { resolving = false }
        resolveNext()
    }

    fun stop() {
        listeners.values.forEach { listener ->
            try {
                nsd?.stopServiceDiscovery(listener)
            } catch (_: Exception) {
                // Discovery may already have stopped after an Android network transition.
            }
        }
        listeners.clear()
        synchronized(pending) { pending.clear() }
    }

    companion object {
        private const val TAG = "DeviceDiscovery"
        private val SERVICE_TYPES = listOf("_gbs-control._tcp.", "_http._tcp.")
    }
}
