package com.wizwear.control.data

import android.content.Context
import android.net.Network
import com.wizwear.control.net.FoundBulb
import com.wizwear.control.net.PilotState
import com.wizwear.control.net.WifiNetworkProvider
import com.wizwear.control.net.WizClient
import com.wizwear.control.complication.WizComplicationService
import com.wizwear.control.tile.WizTileService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

sealed interface BulbStatus {
    data object Unknown : BulbStatus
    data object Offline : BulbStatus
    data class Online(val state: PilotState) : BulbStatus
}

enum class Outcome { OK, NO_WIFI, OFFLINE }

/** Everything the UI and the tile need: saved bulbs, live state, and commands. */
class WizRepository(private val context: Context) {

    private val store = BulbStore(context)
    private val wifi = WifiNetworkProvider(context)
    private val client = WizClient(context)

    private val _bulbs = MutableStateFlow(store.load())
    val bulbs: StateFlow<List<Bulb>> = _bulbs.asStateFlow()

    // Seeded from disk so the tile/complication can show the last known state
    // without turning Wi-Fi on after the process was killed.
    private val _status = MutableStateFlow<Map<String, BulbStatus>>(
        store.loadStates().mapValues { BulbStatus.Online(it.value) },
    )
    val status: StateFlow<Map<String, BulbStatus>> = _status.asStateFlow()

    @Volatile private var lastRefreshAt = 0L

    /** Refresh only if the cached state is older than [maxAgeMs] (saves Wi-Fi wake-ups). */
    suspend fun refreshIfStale(maxAgeMs: Long = 60_000): Outcome =
        if (System.currentTimeMillis() - lastRefreshAt < maxAgeMs) Outcome.OK else refreshAll()

    /** Flip every saved bulb, using the first bulb's state as the reference. */
    suspend fun toggleAll(): Outcome {
        val first = _bulbs.value.firstOrNull()?.mac ?: return Outcome.OFFLINE
        if (stateOf(first) == null) refreshAll()
        val target = !(stateOf(first)?.on ?: false)
        return forAll { setPower(it, target) }
    }

    fun stateOf(mac: String): PilotState? = (_status.value[mac] as? BulbStatus.Online)?.state

    // ── Discovery ──────────────────────────────────────────────

    /** Scan the LAN, add new bulbs, and fix IPs of known ones. */
    suspend fun discover(): Pair<Outcome, Int> = withWifi { net ->
        val found = scan(net, sweep = true)
        merge(found)
        (if (found.isEmpty()) Outcome.OFFLINE else Outcome.OK) to found.size
    } ?: (Outcome.NO_WIFI to 0)

    private suspend fun scan(net: Network, sweep: Boolean): List<FoundBulb> =
        client.discover(net, wifi.broadcastAddress(net), wifi.localAddress(net)?.first, sweep)

    private fun merge(found: List<FoundBulb>) {
        if (found.isEmpty()) return
        val current = _bulbs.value.toMutableList()
        found.forEach { f ->
            val i = current.indexOfFirst { it.mac == f.mac }
            if (i >= 0) current[i] = current[i].copy(ip = f.ip)
            else current += Bulb(f.mac, f.ip, BulbStore.defaultName(f.mac))
            f.state?.let { s -> setStatus(f.mac, BulbStatus.Online(s)) }
        }
        save(current)
    }

    fun remove(mac: String) {
        save(_bulbs.value.filterNot { it.mac == mac })
        _status.update { it - mac }
    }

    private fun save(list: List<Bulb>) {
        _bulbs.value = list
        store.save(list)
    }

    // ── State ─────────────────────────────────────────────────

    suspend fun refreshAll(): Outcome = withWifi { net ->
        val list = _bulbs.value
        if (list.isEmpty()) return@withWifi Outcome.OK
        coroutineScope { list.map { async { refreshOne(net, it) } }.awaitAll() }
        lastRefreshAt = System.currentTimeMillis()
        Outcome.OK
    } ?: Outcome.NO_WIFI

    /** [reachable] already reads and stores the state; no second getPilot. */
    private suspend fun refreshOne(net: Network, bulb: Bulb): Boolean {
        val ok = reachable(net, bulb)
        if (ok != null) {
            // Learn what the bulb supports once (RGB / tunable white / dimmable).
            if (ok.kind == null) {
                client.getCapabilities(net, ok.ip)?.let { c ->
                    save(_bulbs.value.map {
                        if (it.mac == ok.mac) it.copy(kind = c.kind, minKelvin = c.minKelvin, maxKelvin = c.maxKelvin) else it
                    })
                }
            }
            return true
        }
        setStatus(bulb.mac, BulbStatus.Offline)
        return false
    }

    /**
     * Returns the bulb with a working IP. If the stored IP does not answer
     * (router gave it a new lease), rediscover it by MAC.
     */
    private suspend fun reachable(net: Network, bulb: Bulb): Bulb? {
        client.getPilot(net, bulb.ip)?.let { (mac, s) ->
            if (mac == null || mac == bulb.mac) {
                setStatus(bulb.mac, BulbStatus.Online(s))
                return bulb
            }
        }
        val found = scan(net, sweep = false).ifEmpty { scan(net, sweep = true) }
        merge(found)
        return _bulbs.value.firstOrNull { it.mac == bulb.mac && found.any { f -> f.mac == it.mac } }
    }

    // ── Commands ──────────────────────────────────────────────

    suspend fun toggle(mac: String): Outcome {
        val on = stateOf(mac)?.on
        return if (on == null) {
            refreshAll()
            setPower(mac, !(stateOf(mac)?.on ?: false))
        } else setPower(mac, !on)
    }

    suspend fun setPower(mac: String, on: Boolean) =
        apply(mac, JSONObject().put("state", on)) { it.copy(on = on) }

    /** 0 (or below) turns the light off; 1–9 is raised to the bulb's 10% floor. */
    suspend fun setBrightness(mac: String, percent: Int): Outcome {
        if (percent <= 0) return setPower(mac, false)
        val v = percent.coerceIn(MIN_DIM, 100)
        return apply(mac, JSONObject().put("state", true).put("dimming", v)) { it.copy(on = true, dimming = v) }
    }

    suspend fun setTemperature(mac: String, kelvin: Int): Outcome {
        // Clamp to what this bulb actually supports (e.g. 2700–6500 K for tunable white).
        val b = _bulbs.value.firstOrNull { it.mac == mac }
        val k = kelvin.coerceIn(b?.minKelvin ?: MIN_TEMP, b?.maxKelvin ?: MAX_TEMP)
        return apply(mac, JSONObject().put("state", true).put("temp", k)) {
            it.copy(on = true, temp = k, r = null, g = null, b = null, sceneId = null)
        }
    }

    suspend fun setColor(mac: String, r: Int, g: Int, b: Int) =
        apply(mac, JSONObject().put("state", true).put("r", r).put("g", g).put("b", b)) {
            it.copy(on = true, r = r, g = g, b = b, temp = null, sceneId = null)
        }

    suspend fun setScene(mac: String, sceneId: Int) =
        apply(mac, JSONObject().put("state", true).put("sceneId", sceneId)) {
            it.copy(on = true, sceneId = sceneId, temp = null, r = null, g = null, b = null)
        }

    /** Run [command] on every saved bulb (used by the tile and "all" actions). */
    suspend fun forAll(command: suspend WizRepository.(String) -> Outcome): Outcome {
        val results = _bulbs.value.map { command(it.mac) }
        return when {
            results.isEmpty() -> Outcome.OFFLINE
            results.any { it == Outcome.OK } -> Outcome.OK
            else -> results.first()
        }
    }

    /**
     * Optimistic: the UI shows the new state immediately, the UDP packet goes
     * out after, and the previous state comes back only if the bulb never answers.
     */
    private suspend fun apply(mac: String, params: JSONObject, optimistic: (PilotState) -> PilotState): Outcome {
        val previous = _status.value[mac]
        val base = stateOf(mac) ?: PilotState(false, null, null, null, null, null, null, null)
        setStatus(mac, BulbStatus.Online(optimistic(base)))

        val outcome = withWifi { net ->
            val bulb = _bulbs.value.firstOrNull { it.mac == mac } ?: return@withWifi Outcome.OFFLINE
            var ok = client.setPilot(net, bulb.ip, params)
            if (!ok) {
                val fixed = reachable(net, bulb)
                ok = fixed != null && client.setPilot(net, fixed.ip, params)
            }
            if (ok) Outcome.OK else Outcome.OFFLINE
        } ?: Outcome.NO_WIFI

        when (outcome) {
            Outcome.OK -> Unit
            Outcome.OFFLINE -> setStatus(mac, BulbStatus.Offline)
            Outcome.NO_WIFI -> setStatus(mac, previous ?: BulbStatus.Unknown)
        }
        return outcome
    }

    // ── Foreground: keep Wi-Fi warm while the app is on screen ─

    private val bgScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )

    fun setForeground(foreground: Boolean) {
        if (foreground) {
            wifi.hold()
            bgScope.launch { wifi.acquire() } // bring Wi-Fi up before the first tap
        } else {
            wifi.unhold()
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    private suspend fun <T> withWifi(block: suspend (Network) -> T): T? {
        val net = wifi.acquire() ?: return null
        return try {
            block(net)
        } finally {
            wifi.releaseLater()
        }
    }

    private fun setStatus(mac: String, s: BulbStatus) {
        val before = _status.value[mac]
        _status.update { it + (mac to s) }
        if (before != s) {
            if (s is BulbStatus.Online) {
                store.saveStates(_status.value.mapNotNull { (k, v) -> (v as? BulbStatus.Online)?.let { k to it.state } }.toMap())
            }
            WizTileService.requestUpdate(context)
            WizComplicationService.requestUpdate(context)
        }
    }

    companion object {
        const val MIN_DIM = 10
        const val MIN_TEMP = 2200
        const val MAX_TEMP = 6500
    }
}
