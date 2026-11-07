package com.wizwear.control.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Wear OS routes traffic through the phone's Bluetooth proxy whenever it can,
 * and that path never reaches the LAN. This asks the system for the Wi-Fi
 * network explicitly (without requiring internet), keeps it while commands
 * are flowing, and releases it after a short idle period to save battery.
 */
class WifiNetworkProvider(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var network: Network? = null
    private var releaseJob: Job? = null

    /** Returns a usable Wi-Fi [Network], turning Wi-Fi on if needed, or null on timeout. */
    suspend fun acquire(timeoutMs: Long = 10_000): Network? = mutex.withLock {
        releaseJob?.cancel()
        network?.takeIf { isWifi(it) }?.let { return it }

        // Already on Wi-Fi as the default network (phone out of range, etc.).
        cm.activeNetwork?.takeIf { isWifi(it) }?.let { return it }

        val ready = CompletableDeferred<Network>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                network = n
                ready.complete(n)
            }

            override fun onLost(n: Network) {
                if (network == n) network = null
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // Home LAN may have no internet; that is fine for the bulbs.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        unregister()
        runCatching { cm.requestNetwork(request, cb) }
            .onFailure {
                Log.w(TAG, "requestNetwork failed", it)
                return null
            }
        callback = cb
        val n = withTimeoutOrNull(timeoutMs) { ready.await() }
        if (n == null) {
            Log.w(TAG, "Wi-Fi did not come up in ${timeoutMs}ms")
            unregister()
        }
        n
    }

    @Volatile private var held = false

    /** While the app is on screen, never drop Wi-Fi between taps. */
    fun hold() {
        held = true
        releaseJob?.cancel()
    }

    /** App left the screen: release Wi-Fi after the normal idle period. */
    fun unhold() {
        held = false
        releaseLater()
    }

    /**
     * Keep Wi-Fi for [idleMs] more, then let Wear OS go back to Bluetooth.
     * Short on purpose: the app holds Wi-Fi itself while it is on screen, so
     * this only covers tile/complication taps and the moment after leaving.
     */
    fun releaseLater(idleMs: Long = 5_000) {
        releaseJob?.cancel()
        if (held) return
        releaseJob = scope.launch {
            delay(idleMs)
            mutex.withLock { unregister() }
        }
    }

    fun linkProperties(n: Network): LinkProperties? = cm.getLinkProperties(n)

    /** Subnet broadcast address of [n] (e.g. 192.168.1.255), if it has IPv4. */
    fun broadcastAddress(n: Network): InetAddress? {
        val la = linkProperties(n)?.linkAddresses?.firstOrNull { it.address is Inet4Address } ?: return null
        val ip = la.address.address
        val prefix = la.prefixLength
        val ipInt = ip.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        val bc = (ipInt and mask) or mask.inv()
        return InetAddress.getByAddress(
            byteArrayOf((bc ushr 24).toByte(), (bc ushr 16).toByte(), (bc ushr 8).toByte(), bc.toByte()),
        )
    }

    /** Own IPv4 address and prefix length on [n]. */
    fun localAddress(n: Network): Pair<Inet4Address, Int>? {
        val la = linkProperties(n)?.linkAddresses?.firstOrNull { it.address is Inet4Address } ?: return null
        return (la.address as Inet4Address) to la.prefixLength
    }

    private fun isWifi(n: Network): Boolean =
        cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    private fun unregister() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        network = null
    }

    private companion object {
        const val TAG = "WizWifi"
    }
}
