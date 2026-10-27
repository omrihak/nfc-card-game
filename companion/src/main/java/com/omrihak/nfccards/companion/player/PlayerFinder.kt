package com.omrihak.nfccards.companion.player

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress

/** A player found on the Wi-Fi. [address] is "host:port". */
data class FoundPlayer(val name: String, val address: String)

/**
 * Finds players on the local network by their mDNS (NSD) announcement, `_tapandplay._tcp`.
 * Callbacks arrive on a binder thread.
 */
class PlayerFinder(context: Context, private val onFound: (FoundPlayer) -> Unit) {

    private val nsd = context.getSystemService(NsdManager::class.java)
    private var discovery: NsdManager.DiscoveryListener? = null

    // Older Android versions resolve one service at a time, so resolves are queued.
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Looking for players")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(toResolve) { toResolve.addLast(info) }
                resolveNext()
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed: $errorCode")
                discovery = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            discovery = listener
        } catch (e: Exception) {
            Log.w(TAG, "Discovery unavailable", e)
        }
    }

    fun stop() {
        discovery?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.d(TAG, "Stop discovery: ${e.message}")
            }
        }
        discovery = null
        synchronized(toResolve) { toResolve.clear() }
    }

    @Suppress("DEPRECATION") // resolveService is simplest across API 26..35.
    private fun resolveNext() {
        val next = synchronized(toResolve) {
            if (resolving) return
            toResolve.removeFirstOrNull()?.also { resolving = true }
        } ?: return
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                hostOf(info)?.let { host ->
                    onFound(FoundPlayer(info.serviceName, "$host:${info.port}"))
                }
                done()
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.d(TAG, "Resolve failed for ${info.serviceName}: $errorCode")
                done()
            }

            private fun done() {
                synchronized(toResolve) { resolving = false }
                resolveNext()
            }
        })
    }

    /** Prefers IPv4: plain to put in a URL, and what home routers hand out. */
    @Suppress("DEPRECATION")
    private fun hostOf(info: NsdServiceInfo): String? {
        val addresses: List<InetAddress> =
            if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull() ?: return null
        val host = address.hostAddress ?: return null
        return if (address is Inet4Address) host else "[${host.substringBefore('%')}]"
    }

    private companion object {
        const val TAG = "PlayerFinder"
        const val SERVICE_TYPE = "_tapandplay._tcp"
    }
}
