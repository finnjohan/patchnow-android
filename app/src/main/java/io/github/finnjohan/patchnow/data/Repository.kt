// SPDX-FileCopyrightText: 2026 Johan Finn
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.finnjohan.patchnow.data

import android.content.Context
import io.github.finnjohan.patchnow.logic.CERTSE_URL
import io.github.finnjohan.patchnow.logic.DAY_MS
import io.github.finnjohan.patchnow.logic.Epss
import io.github.finnjohan.patchnow.logic.FeedItem
import io.github.finnjohan.patchnow.logic.Item
import io.github.finnjohan.patchnow.logic.KEV_MIRROR_URL
import io.github.finnjohan.patchnow.logic.KEV_URL
import io.github.finnjohan.patchnow.logic.Kev
import io.github.finnjohan.patchnow.logic.Nvd
import io.github.finnjohan.patchnow.logic.RECENT_DAYS
import io.github.finnjohan.patchnow.logic.assess
import io.github.finnjohan.patchnow.logic.dayKey
import io.github.finnjohan.patchnow.logic.epssCandidates
import io.github.finnjohan.patchnow.logic.epssFromJson
import io.github.finnjohan.patchnow.logic.epssToJson
import io.github.finnjohan.patchnow.logic.epssUrls
import io.github.finnjohan.patchnow.logic.feedFromJson
import io.github.finnjohan.patchnow.logic.feedToJson
import io.github.finnjohan.patchnow.logic.inQuietHours
import io.github.finnjohan.patchnow.logic.kevFromJson
import io.github.finnjohan.patchnow.logic.kevToJson
import io.github.finnjohan.patchnow.logic.merge
import io.github.finnjohan.patchnow.logic.mergeNvd
import io.github.finnjohan.patchnow.logic.nvdFromJson
import io.github.finnjohan.patchnow.logic.nvdKevChangesUrl
import io.github.finnjohan.patchnow.logic.nvdKevUrl
import io.github.finnjohan.patchnow.logic.nvdRecentChangesUrl
import io.github.finnjohan.patchnow.logic.nvdRecentUrl
import io.github.finnjohan.patchnow.logic.nvdToJson
import io.github.finnjohan.patchnow.logic.parseEpss
import io.github.finnjohan.patchnow.logic.parseFeed
import io.github.finnjohan.patchnow.logic.parseKev
import io.github.finnjohan.patchnow.logic.parseNvd
import io.github.finnjohan.patchnow.work.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

data class UiState(
    val items: List<Item> = emptyList(),
    val criticalCount: Int = 0,
    val newCount: Int = 0,
    val handledCount: Int = 0,
    val hiddenCount: Int = 0,
    val fetchedAt: Long = 0,
    val loading: Boolean = false,
    val errors: Map<String, String> = emptyMap(),
    val settings: Settings = Settings(),
    val demo: Boolean = false,
    val version: Int = 0               // ökas vid varje ombyggnad, så att listan ritas om
)

// Hämtar, sparar och bedömer. En instans för appen (demo-läget har en egen).
class Repository(private val context: Context, val demo: Boolean) {

    val prefs = Prefs(context, if (demo) "patchnow-demo" else "patchnow")
    private val dir = File(context.filesDir, if (demo) "demo" else "cache").apply { mkdirs() }
    private val mutex = Mutex()

    private var kevList: List<Kev> = emptyList()
    private var nvdKev: Map<String, Nvd> = emptyMap()
    private var nvdRecent: Map<String, Nvd> = emptyMap()
    private var epssMap: Map<String, Epss> = emptyMap()
    private var feedList: List<FeedItem> = emptyList()

    private val _state = MutableStateFlow(UiState(settings = prefs.settings(), demo = demo))
    val state: StateFlow<UiState> = _state

    // Demo-läget har en fast tid, så att de sparade svaren ser färska ut
    private val demoNow: Long = LocalDateTime.of(2026, 11, 19, 9, 12).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    fun now(): Long = if (demo) demoNow else System.currentTimeMillis()

    // ---- Cache ----------------------------------------------------------------

    private fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText(Charsets.UTF_8)
    private fun write(name: String, text: String) = File(dir, name).writeText(text, Charsets.UTF_8)

    fun load() {
        if (demo) { loadDemo(); return }
        try { read("kev.json")?.let { kevList = kevFromJson(it) } } catch (e: Exception) { }
        try { read("nvdKev.json")?.let { nvdKev = nvdFromJson(it) } } catch (e: Exception) { }
        try { read("nvdRecent.json")?.let { nvdRecent = nvdFromJson(it) } } catch (e: Exception) { }
        try { read("epss.json")?.let { epssMap = epssFromJson(it) } } catch (e: Exception) { }
        try { read("certse.json")?.let { feedList = feedFromJson(it) } } catch (e: Exception) { }
        prefs.prune(now())
        rebuild(notify = false)
    }

    // ---- Inställningar --------------------------------------------------------

    fun updateSettings(change: (Settings) -> Settings) {
        prefs.save(change(prefs.settings()))
        rebuild(notify = false)
    }

    fun setHandled(id: String, on: Boolean) {
        val h = prefs.handled()
        if (on) h[id] = now() else h.remove(id)
        prefs.saveHandled(h)
        rebuild(notify = false)
    }

    fun muteVendor(vendor: String) = updateSettings { it.copy(muted = it.muted + vendor.lowercase()) }
    fun unmuteVendor(vendor: String) = updateSettings { it.copy(muted = it.muted - vendor.lowercase()) }

    // ---- Hämtning -------------------------------------------------------------

    private fun maxAgeMinutes(s: Settings): Long = if (s.bgMinutes > 0) maxOf(10L, s.bgMinutes - 5L) else 50L

    suspend fun refresh(force: Boolean) {
        if (demo) return
        mutex.withLock {
            withContext(Dispatchers.IO) { doRefresh(force) }
        }
    }

    private fun doRefresh(force: Boolean) {
        val s = prefs.settings()
        val t = now()
        val fetchedAt = prefs.fetchedAt
        if (!force && fetchedAt > 0 && (t - fetchedAt) / 60_000 < maxAgeMinutes(s)) return
        _state.value = _state.value.copy(loading = true)
        val errors = HashMap<String, String>()
        val full = force || t - prefs.fullAt > DAY_MS || prefs.nvdKevAt == 0L

        // 1. CISA KEV (med ETag, så att en oförändrad katalog bara ger 304)
        val etagHeaders = if (prefs.kevEtag.isNotEmpty() && kevList.isNotEmpty()) mapOf("If-None-Match" to prefs.kevEtag) else emptyMap()
        var r = httpGet(KEV_URL, etagHeaders)
        if (r.status != 200 && r.status != 304) r = httpGet(KEV_MIRROR_URL)
        when (r.status) {
            304 -> { }
            200 -> try {
                kevList = parseKev(r.body, dayKey(t - 90 * DAY_MS))
                prefs.kevEtag = r.etag ?: ""
                write("kev.json", kevToJson(kevList))
            } catch (e: Exception) { errors["kev"] = "server" }
            else -> errors["kev"] = errorKind(r.status)
        }

        // 2. NVD: KEV-poster (fullt fönster en gång per dygn, annars ändringar)
        val since = t - s.windowDays * DAY_MS
        r = httpGet(if (full) nvdKevUrl(t, s.windowDays) else nvdKevChangesUrl(prefs.nvdKevAt - 3_600_000, t))
        if (r.status == 200) {
            try {
                val fresh = parseNvd(r.body)
                nvdKev = mergeNvd(if (full) emptyMap() else nvdKev, fresh, since)
                prefs.nvdKevAt = t
                write("nvdKev.json", nvdToJson(nvdKev))
            } catch (e: Exception) { errors["nvd"] = "server" }
        } else errors["nvd"] = errorKind(r.status)

        // 3. NVD: nya kritiska CVE:er
        val recentSince = t - RECENT_DAYS * DAY_MS
        val recentFull = full || prefs.nvdRecentAt == 0L
        r = httpGet(if (recentFull) nvdRecentUrl(t, RECENT_DAYS) else nvdRecentChangesUrl(prefs.nvdRecentAt - 3_600_000, t))
        if (r.status == 200) {
            try {
                val fresh = parseNvd(r.body)
                nvdRecent = mergeNvd(if (recentFull) emptyMap() else nvdRecent, fresh, recentSince)
                prefs.nvdRecentAt = t
                write("nvdRecent.json", nvdToJson(nvdRecent))
            } catch (e: Exception) { errors["nvd"] = "server" }
        } else errors["nvd"] = errorKind(r.status)

        // 4. CERT-SE
        r = httpGet(CERTSE_URL)
        if (r.status == 200) {
            try { feedList = parseFeed(r.body); write("certse.json", feedToJson(feedList)) }
            catch (e: Exception) { errors["certse"] = "server" }
        } else errors["certse"] = errorKind(r.status)

        // 5. EPSS för det som kan bli kritiskt
        val fresh = HashMap<String, Epss>()
        for (u in epssUrls(epssCandidates(kevInWindow(s), combinedNvd(s)))) {
            r = httpGet(u)
            if (r.status == 200) {
                try { fresh.putAll(parseEpss(r.body)) } catch (e: Exception) { errors["epss"] = "server" }
            } else errors["epss"] = errorKind(r.status)
        }
        if (fresh.isNotEmpty()) { epssMap = fresh; write("epss.json", epssToJson(epssMap)) }

        prefs.fetchedAt = t
        if (full && errors["nvd"] == null) prefs.fullAt = t
        _state.value = _state.value.copy(loading = false, errors = errors)
        rebuild(notify = true)
    }

    // ---- Bedömning ------------------------------------------------------------

    private fun kevInWindow(s: Settings): List<Kev> {
        val since = dayKey(now() - s.windowDays * DAY_MS)
        return kevList.filter { it.added >= since }
    }

    // Samma gräns som kevInWindow (hela dagar), annars tappar dagen längst bak sin CVSS
    private fun combinedNvd(s: Settings): Map<String, Nvd> {
        val since = dayKey(now() - s.windowDays * DAY_MS)
        val out = LinkedHashMap<String, Nvd>(nvdRecent)
        for ((id, n) in nvdKev) {
            if (n.kevAdded.isNotEmpty() && n.kevAdded < since) continue
            out[id] = n
        }
        return out
    }

    // notify: om nya kritiska poster ska ge notis (efter en riktig hämtning)
    @Synchronized
    fun rebuild(notify: Boolean) {
        val s = prefs.settings()
        val t = now()
        val list = merge(kevInWindow(s), combinedNvd(s), epssMap, feedList, t - RECENT_DAYS * DAY_MS, t - s.windowDays * DAY_MS)
        val handled = prefs.handled()
        val res = assess(list, s.level, s.muted, handled.keys, s.platforms, s.software)

        val seen = prefs.seen()
        val crit = res.items.filter { it.tier == 2 && !it.handled }
        val newOnes = crit.filter { !seen.containsKey(it.id) }
        val firstRun = !prefs.initialized
        if (firstRun) {
            // Första hämtningen: allt som redan finns räknas som gammalt, ingen notis
            if (prefs.fetchedAt > 0) {
                for (it in crit) seen[it.id] = t - 3 * DAY_MS
                prefs.saveSeen(seen)
                prefs.initialized = true
            }
        } else if (newOnes.isNotEmpty()) {
            for (it in newOnes) seen[it.id] = t
            prefs.saveSeen(seen)
        }

        for (it in res.items) {
            val first = seen[it.id]
            it.isNew = it.tier == 2 && !it.handled && first != null && t - first < 2 * DAY_MS
        }

        _state.value = _state.value.copy(
            items = res.items,
            criticalCount = crit.size,
            newCount = res.items.count { it.isNew },
            handledCount = res.items.count { it.handled },
            hiddenCount = res.hidden,
            fetchedAt = prefs.fetchedAt,
            settings = s,
            version = _state.value.version + 1
        )

        if (notify && !firstRun) notifyNew(newOnes, crit, s, t)
    }

    // Nya kritiska ger en notis, eller väntar till efter tysta timmar.
    private fun notifyNew(newOnes: List<Item>, crit: List<Item>, s: Settings, t: Long) {
        if (!s.notifyOn) return
        val ids = prefs.pending + newOnes.map { it.id }
        if (ids.isEmpty()) return
        if (s.quietOn && inQuietHours(t, 22, 7)) {
            prefs.pending = ids
            return
        }
        prefs.pending = emptySet()
        val items = crit.filter { ids.contains(it.id) }
        if (items.isNotEmpty()) Notifier.notifyNew(context, items)
    }

    fun itemById(id: String): Item? = _state.value.items.firstOrNull { it.id == id }

    // ---- Demo -----------------------------------------------------------------

    private fun asset(name: String): String = context.assets.open("fixtures/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun hasDemoData(): Boolean = try { context.assets.open("fixtures/kev.json").close(); true } catch (e: Exception) { false }

    private fun loadDemo() {
        val t = now()
        prefs.clearAll()
        // Demo: Apache och nginx valda, så att "Program du kör" syns i listan
        prefs.save(Settings(quietOn = false, software = setOf("apache", "nginx")))
        kevList = parseKev(asset("kev.json"), dayKey(t - 90 * DAY_MS))
        nvdKev = parseNvd(asset("nvd-kev.json"))
        nvdRecent = parseNvd(asset("nvd-recent.json"))
        epssMap = emptyMap()
        feedList = parseFeed(asset("certse.xml"))
        prefs.fetchedAt = t - 4 * 60_000
        prefs.initialized = true
        // Allt utom de två nyaste kritiska räknas som sett sedan tidigare, och den äldsta är åtgärdad
        val s = prefs.settings()
        val list = merge(kevInWindow(s), combinedNvd(s), epssMap, feedList, t - RECENT_DAYS * DAY_MS, t - s.windowDays * DAY_MS)
        val crit = assess(list, s.level, emptySet(), emptySet(), s.platforms, s.software).items.filter { it.tier == 2 }
        val seen = HashMap<String, Long>()
        crit.drop(2).forEach { seen[it.id] = t - 10 * DAY_MS }
        crit.take(2).forEach { seen[it.id] = t - 3 * 3_600_000 }
        prefs.saveSeen(seen)
        if (crit.isNotEmpty()) prefs.saveHandled(mapOf(crit.last().id to t))
        rebuild(notify = false)
    }

    // Test: låtsas att den nyaste kritiska posten just dök upp, så att en notis skickas.
    fun simulateNew() {
        val first = _state.value.items.firstOrNull { it.tier == 2 && !it.handled } ?: return
        val seen = prefs.seen()
        seen.remove(first.id)
        prefs.saveSeen(seen)
        rebuild(notify = true)
    }
}
