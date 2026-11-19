// SPDX-FileCopyrightText: 2026 Johan Finn
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.finnjohan.patchnow.data

import android.content.Context
import android.content.SharedPreferences
import io.github.finnjohan.patchnow.logic.DEFAULT_PLATFORMS
import io.github.finnjohan.patchnow.logic.PLATFORMS
import org.json.JSONObject

data class Settings(
    val level: String = "normal",
    val windowDays: Int = 30,
    val bgMinutes: Int = 180,          // 0 = bara när appen öppnas
    val notifyOn: Boolean = true,
    val quietOn: Boolean = true,
    val theme: String = "auto",
    val platforms: Map<String, Boolean> = DEFAULT_PLATFORMS,
    val muted: Set<String> = emptySet()
)

// Inställningar, sedda och åtgärdade poster samt metadata om hämtningarna.
class Prefs(context: Context, name: String) {
    private val p: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun settings(): Settings {
        val plat = HashMap<String, Boolean>()
        val saved = try { JSONObject(p.getString("platforms", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        for (k in PLATFORMS) plat[k] = if (saved.has(k)) saved.optBoolean(k) else DEFAULT_PLATFORMS.getValue(k)
        return Settings(
            level = p.getString("level", "normal") ?: "normal",
            windowDays = p.getInt("windowDays", 30),
            bgMinutes = p.getInt("bgMinutes", 180),
            notifyOn = p.getBoolean("notifyOn", true),
            quietOn = p.getBoolean("quietOn", true),
            theme = p.getString("theme", "auto") ?: "auto",
            platforms = plat,
            muted = p.getStringSet("muted", emptySet())?.toSet() ?: emptySet()
        )
    }

    fun save(s: Settings) {
        val plat = JSONObject()
        for ((k, v) in s.platforms) plat.put(k, v)
        p.edit()
            .putString("level", s.level)
            .putInt("windowDays", s.windowDays)
            .putInt("bgMinutes", s.bgMinutes)
            .putBoolean("notifyOn", s.notifyOn)
            .putBoolean("quietOn", s.quietOn)
            .putString("theme", s.theme)
            .putString("platforms", plat.toString())
            .putStringSet("muted", s.muted)
            .apply()
    }

    // ---- id -> tid (ms) ------------------------------------------------------

    private fun timeMap(key: String): MutableMap<String, Long> {
        val o = try { JSONObject(p.getString(key, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val out = HashMap<String, Long>()
        for (k in o.keys()) out[k] = o.optLong(k)
        return out
    }

    private fun saveTimeMap(key: String, m: Map<String, Long>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        p.edit().putString(key, o.toString()).apply()
    }

    fun seen(): MutableMap<String, Long> = timeMap("seen")
    fun saveSeen(m: Map<String, Long>) = saveTimeMap("seen", m)
    fun handled(): MutableMap<String, Long> = timeMap("handled")
    fun saveHandled(m: Map<String, Long>) = saveTimeMap("handled", m)

    // Rensar sedda och åtgärdade poster som är äldre än ett halvår.
    fun prune(nowMs: Long) {
        val limit = nowMs - 183L * 86_400_000L
        saveSeen(seen().filterValues { it >= limit })
        saveHandled(handled().filterValues { it >= limit })
    }

    // Nya kritiska som väntar på att tysta timmar ska ta slut
    var pending: Set<String>
        get() = p.getStringSet("pending", emptySet())?.toSet() ?: emptySet()
        set(v) { p.edit().putStringSet("pending", v).apply() }

    var initialized: Boolean
        get() = p.getBoolean("initialized", false)
        set(v) { p.edit().putBoolean("initialized", v).apply() }

    // ---- Hämtningar -------------------------------------------------------

    var fetchedAt: Long
        get() = p.getLong("fetchedAt", 0)
        set(v) { p.edit().putLong("fetchedAt", v).apply() }
    var nvdKevAt: Long
        get() = p.getLong("nvdKevAt", 0)
        set(v) { p.edit().putLong("nvdKevAt", v).apply() }
    var nvdRecentAt: Long
        get() = p.getLong("nvdRecentAt", 0)
        set(v) { p.edit().putLong("nvdRecentAt", v).apply() }
    var fullAt: Long
        get() = p.getLong("fullAt", 0)
        set(v) { p.edit().putLong("fullAt", v).apply() }
    var kevEtag: String
        get() = p.getString("kevEtag", "") ?: ""
        set(v) { p.edit().putString("kevEtag", v).apply() }

    fun clearAll() { p.edit().clear().apply() }
}
