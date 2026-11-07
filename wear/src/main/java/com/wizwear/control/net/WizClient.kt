package com.wizwear.control.net

import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Pilot (light) state as reported by `getPilot`. */
data class PilotState(
    val on: Boolean,
    val dimming: Int?,
    val temp: Int?,
    val r: Int?,
    val g: Int?,
    val b: Int?,
    val sceneId: Int?,
    val rssi: Int?,
) {
    companion object {
        fun fromJson(result: JSONObject) = PilotState(
            on = result.optBoolean("state", false),
            dimming = result.optIntOrNull("dimming"),
            temp = result.optIntOrNull("temp"),
            r = result.optIntOrNull("r"),
            g = result.optIntOrNull("g"),
            b = result.optIntOrNull("b"),
            sceneId = result.optIntOrNull("sceneId")?.takeIf { it > 0 },
            rssi = result.optIntOrNull("rssi"),
        )

        private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key)) optInt(key) else null
    }
}

enum class BulbKind { RGB, TUNABLE_WHITE, DIMMABLE }

data class Capabilities(val kind: BulbKind, val module: String, val minKelvin: Int, val maxKelvin: Int)

/** A bulb that answered during discovery. */
data class FoundBulb(val ip: String, val mac: String, val state: PilotState?)

/**
 * The WiZ local protocol: JSON over UDP, port 38899. Every socket is bound to
 * the Wi-Fi [Network] so packets never go out over the Bluetooth proxy.
 */
class WizClient(context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

    suspend fun getPilot(network: Network, ip: String): Pair<String?, PilotState>? {
        val res = request(network, ip, JSONObject().put("method", "getPilot").put("params", JSONObject()))
            ?: return null
        val result = res.optJSONObject("result") ?: return null
        return result.optString("mac").ifBlank { null } to PilotState.fromJson(result)
    }

    /**
     * What the bulb can do, from `getSystemConfig` (module name, e.g.
     * ESP24_SHTWW_01 = tunable white) and `getModelConfig` (CCT range).
     */
    suspend fun getCapabilities(network: Network, ip: String): Capabilities? {
        val sys = request(network, ip, JSONObject().put("method", "getSystemConfig").put("params", JSONObject()))
            ?.optJSONObject("result") ?: return null
        val module = sys.optString("moduleName")
        val kind = when {
            module.contains("RGB", ignoreCase = true) -> BulbKind.RGB
            module.contains("TW", ignoreCase = true) -> BulbKind.TUNABLE_WHITE
            module.contains("DW", ignoreCase = true) -> BulbKind.DIMMABLE
            else -> BulbKind.RGB // unknown: show everything rather than hide controls
        }
        val model = request(network, ip, JSONObject().put("method", "getModelConfig").put("params", JSONObject()))
            ?.optJSONObject("result")
        val cct = model?.optJSONArray("cctRange")
        val (lo, hi) = if (cct != null && cct.length() >= 2) {
            cct.optInt(0) to cct.optInt(cct.length() - 1)
        } else if (kind == BulbKind.RGB) 2200 to 6500 else 2700 to 6500
        return Capabilities(kind, module, lo.coerceAtLeast(1000), hi.coerceAtLeast(lo + 100))
    }

    suspend fun setPilot(network: Network, ip: String, params: JSONObject): Boolean {
        val res = request(network, ip, JSONObject().put("method", "setPilot").put("params", params))
            ?: return false
        return res.optJSONObject("result")?.optBoolean("success", false) == true
    }

    /**
     * Find bulbs on the LAN: broadcast first (fast), then a /24 sweep when
     * the router drops broadcasts — the same fallback the YASB script uses.
     */
    suspend fun discover(network: Network, broadcast: InetAddress?, local: InetAddress?, sweep: Boolean = true): List<FoundBulb> =
        withContext(Dispatchers.IO) {
            val lock = wifiManager?.createMulticastLock("wizwear")?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
            try {
                val found = LinkedHashMap<String, FoundBulb>()
                DatagramSocket().use { socket ->
                    network.bindSocket(socket)
                    socket.broadcast = true
                    val getPilot = JSONObject().put("method", "getPilot").put("params", JSONObject())
                        .toString().toByteArray()
                    val targets = buildList {
                        broadcast?.let { add(it) }
                        add(InetAddress.getByName("255.255.255.255"))
                    }
                    repeat(2) {
                        targets.forEach { t -> runCatching { socket.send(DatagramPacket(getPilot, getPilot.size, t, PORT)) } }
                    }
                    collect(socket, 1_500, found)

                    if (found.isEmpty() && sweep && local != null) {
                        val base = local.address
                        for (host in 1..254) {
                            if (host == (base[3].toInt() and 0xFF)) continue
                            val addr = InetAddress.getByAddress(byteArrayOf(base[0], base[1], base[2], host.toByte()))
                            runCatching { socket.send(DatagramPacket(getPilot, getPilot.size, addr, PORT)) }
                        }
                        collect(socket, 2_000, found)
                    }
                }
                found.values.toList()
            } finally {
                runCatching { lock?.release() }
            }
        }

    private fun collect(socket: DatagramSocket, windowMs: Long, into: MutableMap<String, FoundBulb>) {
        val deadline = System.currentTimeMillis() + windowMs
        val buf = ByteArray(2048)
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            socket.soTimeout = left.toInt().coerceAtLeast(1)
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                break
            }
            val json = runCatching { JSONObject(String(packet.data, 0, packet.length)) }.getOrNull() ?: continue
            val result = json.optJSONObject("result") ?: continue
            val mac = result.optString("mac")
            if (mac.isBlank()) continue
            val ip = packet.address.hostAddress ?: continue
            into[mac] = FoundBulb(ip, mac, PilotState.fromJson(result))
        }
    }

    /** One request with a couple of retries — WiZ bulbs drop UDP now and then. */
    private suspend fun request(network: Network, ip: String, body: JSONObject, attempts: Int = 3): JSONObject? =
        withContext(Dispatchers.IO) {
            val bytes = body.toString().toByteArray()
            val addr = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return@withContext null
            runCatching {
                DatagramSocket().use { socket ->
                    network.bindSocket(socket)
                    socket.soTimeout = 700
                    val buf = ByteArray(2048)
                    repeat(attempts) {
                        socket.send(DatagramPacket(bytes, bytes.size, addr, PORT))
                        try {
                            val packet = DatagramPacket(buf, buf.size)
                            socket.receive(packet)
                            return@runCatching JSONObject(String(packet.data, 0, packet.length))
                        } catch (_: SocketTimeoutException) {
                            // retry
                        }
                    }
                    null
                }
            }.onFailure { Log.w(TAG, "UDP to $ip failed", it) }.getOrNull()
        }

    companion object {
        const val PORT = 38899
        private const val TAG = "WizClient"
    }
}
