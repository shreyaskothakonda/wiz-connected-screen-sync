package com.shreyas.wizsync.tv.store

import com.shreyas.wizsync.tv.sync.Presets
import com.shreyas.wizsync.tv.wiz.Discovered
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A WiZ bulb the user has discovered. [enabled] controls whether it takes part in sync. */
data class Bulb(val mac: String, val ip: String, val name: String, val enabled: Boolean)

/**
 * User settings: the bulbs, the selected preset and per-preset brightness.
 * Everything is held in memory, loaded once from [store] on construction, and written back
 * as one JSON document under [KEY] after every mutation.
 *
 * Thread safety: all methods are thread-safe (synchronised on this instance). `bulbs` and
 * `enabledBulbs()` return immutable snapshots that are not affected by later mutations.
 */
class Settings(private val store: KeyValueStore) {
    private var bulbState: List<Bulb> = emptyList()
    private var selectedPreset: String = DEFAULT_PRESET
    private val brightnessByPreset = mutableMapOf<String, Int>()

    init {
        load()
    }

    /** Immutable snapshot of all known bulbs, in insertion order. */
    @get:Synchronized
    val bulbs: List<Bulb> get() = bulbState

    @get:Synchronized
    val presetId: String get() = selectedPreset

    @Synchronized
    fun brightnessFor(presetId: String): Int =
        brightnessByPreset[presetId] ?: Presets.byId(presetId).defaultBrightness

    @Synchronized
    fun setBrightness(presetId: String, value: Int) {
        Presets.byId(presetId)
        brightnessByPreset[presetId] = value.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
        save()
    }

    @Synchronized
    fun setPreset(id: String) {
        Presets.byId(id)
        selectedPreset = id
        save()
    }

    /** Adds unknown bulbs (enabled, default name) and refreshes the IP of known ones. Never removes. */
    @Synchronized
    fun upsertFromDiscovery(found: List<Discovered>) {
        val updated = bulbState.toMutableList()
        for (d in found) {
            val idx = updated.indexOfFirst { it.mac.equals(d.mac, ignoreCase = true) }
            if (idx >= 0) {
                updated[idx] = updated[idx].copy(ip = d.ip)
            } else {
                updated += Bulb(d.mac, d.ip, defaultName(d.mac), enabled = true)
            }
        }
        bulbState = updated
        save()
    }

    @Synchronized
    fun setEnabled(mac: String, enabled: Boolean) = mutateBulb(mac) { it.copy(enabled = enabled) }

    @Synchronized
    fun rename(mac: String, name: String) = mutateBulb(mac) { it.copy(name = name) }

    @Synchronized
    fun updateIp(mac: String, ip: String) = mutateBulb(mac) { it.copy(ip = ip) }

    @Synchronized
    fun remove(mac: String) {
        val before = bulbState.size
        bulbState = bulbState.filterNot { it.mac.equals(mac, ignoreCase = true) }
        if (bulbState.size != before) save()
    }

    @Synchronized
    fun enabledBulbs(): List<Bulb> = bulbState.filter { it.enabled }

    private fun mutateBulb(mac: String, transform: (Bulb) -> Bulb) {
        val idx = bulbState.indexOfFirst { it.mac.equals(mac, ignoreCase = true) }
        if (idx < 0) return
        bulbState = bulbState.toMutableList().also { it[idx] = transform(it[idx]) }
        save()
    }

    private fun save() {
        store.put(KEY, toJson().toString())
    }

    private fun toJson(): JSONObject {
        val brightness = JSONObject()
        brightnessByPreset.forEach { (id, value) -> brightness.put(id, value) }
        val bulbArray = JSONArray()
        bulbState.forEach { b ->
            bulbArray.put(
                JSONObject()
                    .put("mac", b.mac)
                    .put("ip", b.ip)
                    .put("name", b.name)
                    .put("enabled", b.enabled)
            )
        }
        return JSONObject()
            .put("preset", selectedPreset)
            .put("brightness", brightness)
            .put("bulbs", bulbArray)
    }

    private fun load() {
        val raw = store.get(KEY) ?: return
        val root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return
        }

        root.optString("preset", "").takeIf { id -> Presets.ALL.any { it.id == id } }
            ?.let { selectedPreset = it }

        // Keep only integer brightness values for known presets, clamped to the valid range.
        // Anything else (strings, decimals, nulls) is skipped so the preset default applies.
        root.optJSONObject("brightness")?.let { b ->
            b.keys().forEach { key ->
                val value = b.opt(key)
                if (Presets.ALL.any { it.id == key } && value is Int) {
                    brightnessByPreset[key] = value.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
                }
            }
        }

        root.optJSONArray("bulbs")?.let { arr ->
            val loaded = mutableListOf<Bulb>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val mac = o.optString("mac", "")
                if (mac.isEmpty()) continue
                // Drop duplicates by MAC (case-insensitive), keeping the first occurrence.
                if (loaded.any { it.mac.equals(mac, ignoreCase = true) }) continue
                // A missing, blank or JSON-null name falls back to the default name.
                val rawName = if (o.isNull("name")) "" else o.optString("name", "")
                loaded += Bulb(
                    mac = mac,
                    ip = o.optString("ip", ""),
                    name = rawName.ifBlank { defaultName(mac) },
                    enabled = o.optBoolean("enabled", true),
                )
            }
            bulbState = loaded
        }
    }

    companion object {
        const val KEY = "settings"
        const val DEFAULT_PRESET = "movie"
        const val MIN_BRIGHTNESS = 10
        const val MAX_BRIGHTNESS = 100

        /** Default display name for a bulb, e.g. "WiZ 3F2A" for MAC "a8bb50003f2a". */
        fun defaultName(mac: String): String = "WiZ " + mac.takeLast(4).uppercase()
    }
}
