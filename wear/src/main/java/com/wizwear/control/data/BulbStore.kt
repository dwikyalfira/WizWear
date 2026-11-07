package com.wizwear.control.data

import android.content.Context
import com.wizwear.control.net.BulbKind
import com.wizwear.control.net.PilotState
import org.json.JSONArray
import org.json.JSONObject

/**
 * A saved bulb. The MAC is the identity; the IP may change with DHCP.
 * [kind] and the Kelvin range come from the bulb itself (null until read once).
 */
data class Bulb(
    val mac: String,
    val ip: String,
    val name: String,
    val kind: BulbKind? = null,
    val minKelvin: Int = 2700,
    val maxKelvin: Int = 6500,
) {
    val hasColor: Boolean get() = kind == null || kind == BulbKind.RGB
    val hasTemperature: Boolean get() = kind != BulbKind.DIMMABLE
}

/** Tiny persisted list of bulbs (SharedPreferences + JSON). */
class BulbStore(context: Context) {

    private val prefs = context.getSharedPreferences("bulbs", Context.MODE_PRIVATE)

    fun load(): List<Bulb> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                val mac = o.getString("mac")
                // Old builds saved the Indonesian default "Lampu XXXX"; show the English default instead.
                val name = o.optString("name", defaultName(mac))
                    .let { if (it == "Lampu " + mac.takeLast(4).uppercase()) defaultName(mac) else it }
                Bulb(
                    mac, o.getString("ip"), name,
                    kind = o.optString("kind").takeIf { it.isNotEmpty() }?.let { k -> runCatching { BulbKind.valueOf(k) }.getOrNull() },
                    minKelvin = o.optInt("kMin", 2700),
                    maxKelvin = o.optInt("kMax", 6500),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(bulbs: List<Bulb>) {
        val arr = JSONArray()
        bulbs.forEach {
            arr.put(
                JSONObject().put("mac", it.mac).put("ip", it.ip).put("name", it.name)
                    .put("kind", it.kind?.name ?: "").put("kMin", it.minKelvin).put("kMax", it.maxKelvin),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    /** Last known light state per MAC (power, brightness, white temperature). */
    fun loadStates(): Map<String, PilotState> {
        val raw = prefs.getString(KEY_STATES, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { mac ->
                val s = o.getJSONObject(mac)
                PilotState(
                    on = s.optBoolean("on"),
                    dimming = s.optInt("dim", -1).takeIf { it >= 0 },
                    temp = s.optInt("temp", -1).takeIf { it >= 0 },
                    r = null, g = null, b = null,
                    sceneId = s.optInt("scene", -1).takeIf { it > 0 },
                    rssi = null,
                )
            }
        }.getOrDefault(emptyMap())
    }

    fun saveStates(states: Map<String, PilotState>) {
        val o = JSONObject()
        states.forEach { (mac, s) ->
            o.put(
                mac,
                JSONObject().put("on", s.on)
                    .put("dim", s.dimming ?: -1)
                    .put("temp", s.temp ?: -1)
                    .put("scene", s.sceneId ?: -1),
            )
        }
        prefs.edit().putString(KEY_STATES, o.toString()).apply()
    }

    companion object {
        private const val KEY = "list"
        private const val KEY_STATES = "states"
        fun defaultName(mac: String) = "Bulb " + mac.takeLast(4).uppercase()
    }
}
