// SPDX-FileCopyrightText: 2026 Johan Finn
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.finnjohan.patchnow.logic

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// Ren logik utan Android-beroenden: adresser, tolkning av flödena, plattform
// och bedömningen av vad som är kritiskt. Samma regler som PatchNow för
// Ubuntu Touch (Logic.js). Testas med JUnit (LogicTest).

const val KEV_URL = "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json"
const val KEV_MIRROR_URL = "https://raw.githubusercontent.com/cisagov/kev-data/main/known_exploited_vulnerabilities.json"
const val NVD_URL = "https://services.nvd.nist.gov/rest/json/cves/2.0"
const val EPSS_URL = "https://api.first.org/data/v1/epss"
const val CERTSE_URL = "https://www.cert.se/feed.xml"

const val DAY_MS = 86_400_000L
const val RECENT_DAYS = 10      // nya CVE:er utanför KEV som bedöms med CVSS + EPSS
const val EPSS_BATCH = 50       // FIRST tar högst 2000 tecken i cve-parametern

// ---- Datatyper ---------------------------------------------------------------

data class Kev(
    val id: String, val vendor: String, val product: String, val name: String, val desc: String,
    val added: String, val due: String, val ransomware: Boolean, val action: String
)

data class Nvd(
    val cvss: Double, val vector: String, val version: String, val desc: String, val published: String,
    val ssvc: Map<String, String>, val cpes: List<String>,
    val kevAdded: String, val kevDue: String, val kevName: String, val kevAction: String,
    val sw: List<String> = emptyList()   // CPE:ns target_sw, t.ex. "drupal" för Drupal-moduler
)

data class Epss(val epss: Double, val pct: Double)

data class FeedItem(
    val title: String, val link: String, val published: Long, val cves: List<String>,
    val critical: Boolean, val summary: String
)

data class KevInfo(val added: String, val due: String, val ransomware: Boolean, val action: String)

data class CertRef(val title: String, val link: String, val critical: Boolean)

class Item(
    val kind: String,              // "cve" eller "certse"
    val id: String,
    val vendor: String,
    val product: String,
    val name: String,
    val desc: String,
    val kev: KevInfo?,
    val cvss: Double,              // -1 = saknas
    val vector: String,
    val cvssVersion: String,
    val ssvc: Map<String, String>,
    val cpes: List<String>,
    val epss: Double,              // -1 = saknas
    val pct: Double,
    val published: String,
    val certse: MutableList<CertRef>,
    val cves: List<String>,
    val link: String,
    val date: Long,
    val sw: List<String> = emptyList()
) {
    var tier = 0                   // 2 = åtgärda nu, 1 = bevaka
    var reasons: List<String> = emptyList()
    var handled = false
    var platform = "apps"
    var software = ""              // valt program som posten gäller, "" = inget
    var isNew = false
}

class Assessed(val items: List<Item>, val hidden: Int)

// ---- Adresser ----------------------------------------------------------------

private val NVD_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'.000'")

// NVD vill ha ISO 8601 utan tidszon (tolkas som UTC).
fun nvdDate(ms: Long): String = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC).format(NVD_FMT)

// CVE:er som lagts till i KEV de senaste `days` dagarna (NVD tillåter högst 90).
fun nvdKevUrl(nowMs: Long, days: Int): String {
    val d = days.coerceIn(1, 90)
    return "$NVD_URL?kevStartDate=${nvdDate(nowMs - d * DAY_MS)}&kevEndDate=${nvdDate(nowMs)}"
}

fun nvdKevChangesUrl(fromMs: Long, nowMs: Long): String =
    "$NVD_URL?hasKev&lastModStartDate=${nvdDate(fromMs)}&lastModEndDate=${nvdDate(nowMs)}"

// Nya CVE:er med CVSS 3 = CRITICAL, publicerade de senaste `days` dagarna.
fun nvdRecentUrl(nowMs: Long, days: Int): String =
    "$NVD_URL?cvssV3Severity=CRITICAL&noRejected&pubStartDate=${nvdDate(nowMs - days * DAY_MS)}&pubEndDate=${nvdDate(nowMs)}"

fun nvdRecentChangesUrl(fromMs: Long, nowMs: Long): String =
    "$NVD_URL?cvssV3Severity=CRITICAL&noRejected&lastModStartDate=${nvdDate(fromMs)}&lastModEndDate=${nvdDate(nowMs)}"

fun epssUrls(ids: List<String>): List<String> =
    ids.chunked(EPSS_BATCH).map { "$EPSS_URL?cve=" + it.joinToString(",") }

fun nvdLink(id: String) = "https://nvd.nist.gov/vuln/detail/$id"
fun kevLink(id: String) = "https://www.cisa.gov/known-exploited-vulnerabilities-catalog?search_api_fulltext=$id"

// ---- Datum -------------------------------------------------------------------

fun dayKey(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()

// "2026-11-18" som lokal midnatt i ms, 0 om det inte går att tolka.
fun parseDay(s: String, zone: ZoneId = ZoneId.systemDefault()): Long {
    if (s.length < 10) return 0
    return try { LocalDate.parse(s.substring(0, 10)).atStartOfDay(zone).toInstant().toEpochMilli() } catch (e: Exception) { 0 }
}

fun daysBetween(fromMs: Long, toMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    val a = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
    val b = Instant.ofEpochMilli(toMs).atZone(zone).toLocalDate()
    return ChronoUnit.DAYS.between(a, b)
}

// Tider som "2026-11-18T14:20:00+01:00", "2026-11-06T10:00:00.000" (UTC) eller RFC 822.
fun parseTime(s: String): Long {
    val t = s.trim()
    if (t.isEmpty()) return 0
    try { return OffsetDateTime.parse(t).toInstant().toEpochMilli() } catch (e: Exception) { }
    try { return LocalDateTime.parse(t).toInstant(ZoneOffset.UTC).toEpochMilli() } catch (e: Exception) { }
    try { return ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() } catch (e: Exception) { }
    return 0
}

// ---- Tolkning ----------------------------------------------------------------

// CISA KEV: bara poster tillagda från och med `sinceDay` ("YYYY-MM-DD").
fun parseKev(text: String, sinceDay: String): List<Kev> {
    val d = JSONObject(text)
    val arr = d.getJSONArray("vulnerabilities")
    val out = ArrayList<Kev>()
    for (i in 0 until arr.length()) {
        val v = arr.getJSONObject(i)
        val id = v.optString("cveID")
        val added = v.optString("dateAdded")
        if (id.isEmpty() || (sinceDay.isNotEmpty() && added < sinceDay)) continue
        out.add(Kev(id, v.optString("vendorProject"), v.optString("product"), v.optString("vulnerabilityName"),
            v.optString("shortDescription"), added, v.optString("dueDate"),
            v.optString("knownRansomwareCampaignUse") == "Known", v.optString("requiredAction")))
    }
    return out
}

private class Metric(val score: Double, val vector: String, val version: String)

// Högsta poäng av en metrik. NVD:s egen (Primary) går före leverantörens (Secondary).
private fun bestMetric(list: JSONArray?): Metric? {
    if (list == null || list.length() == 0) return null
    val all = (0 until list.length()).map { list.getJSONObject(it) }
    val primary = all.filter { it.optString("type") == "Primary" }
    val pool = if (primary.isNotEmpty()) primary else all
    var best: Metric? = null
    for (m in pool) {
        val c = m.optJSONObject("cvssData") ?: continue
        if (!c.has("baseScore")) continue
        val s = c.optDouble("baseScore", -1.0)
        if (best == null || s > best.score) best = Metric(s, c.optString("vectorString"), c.optString("version"))
    }
    return best
}

// NVD API 2.0: tabell id -> Nvd
fun parseNvd(text: String): Map<String, Nvd> {
    val d = JSONObject(text)
    val arr = d.getJSONArray("vulnerabilities")
    val out = LinkedHashMap<String, Nvd>()
    for (i in 0 until arr.length()) {
        val c = arr.getJSONObject(i).optJSONObject("cve") ?: continue
        val id = c.optString("id")
        if (id.isEmpty()) continue
        val m = c.optJSONObject("metrics") ?: JSONObject()
        val best = bestMetric(m.optJSONArray("cvssMetricV31")) ?: bestMetric(m.optJSONArray("cvssMetricV30"))
            ?: bestMetric(m.optJSONArray("cvssMetricV40"))
        val ssvc = HashMap<String, String>()
        val sl = m.optJSONArray("ssvcV203") ?: m.optJSONArray("ssvcV2")
        if (sl != null) for (j in 0 until sl.length()) {
            val opts = sl.getJSONObject(j).optJSONObject("ssvcData")?.optJSONArray("options") ?: continue
            for (k in 0 until opts.length()) {
                val o = opts.getJSONObject(k)
                for (key in o.keys()) ssvc[key] = o.get(key).toString().lowercase()
            }
        }
        val cpes = ArrayList<String>()
        val sw = ArrayList<String>()
        val confs = c.optJSONArray("configurations")
        if (confs != null) for (ci in 0 until confs.length()) {
            val nodes = confs.getJSONObject(ci).optJSONArray("nodes") ?: continue
            for (ni in 0 until nodes.length()) {
                val ms = nodes.getJSONObject(ni).optJSONArray("cpeMatch") ?: continue
                for (mi in 0 until ms.length()) {
                    val cm = ms.getJSONObject(mi)
                    if (!cm.optBoolean("vulnerable")) continue
                    val parts = cm.optString("criteria").split(":")
                    if (parts.size < 5) continue
                    val key3 = parts[2] + ":" + parts[3] + ":" + parts[4]
                    if (key3 !in cpes && cpes.size < 60) cpes.add(key3)
                    val tsw = if (parts.size > 10) parts[10] else ""
                    if (tsw != "*" && tsw != "-" && tsw.isNotEmpty() && tsw !in sw && sw.size < 10) sw.add(tsw)
                }
            }
        }
        var desc = ""
        val ds = c.optJSONArray("descriptions")
        if (ds != null) for (n in 0 until ds.length()) {
            val o = ds.getJSONObject(n)
            if (o.optString("lang") == "en") { desc = o.optString("value"); break }
        }
        out[id] = Nvd(best?.score ?: -1.0, best?.vector ?: "", best?.version ?: "", desc, c.optString("published"),
            ssvc, cpes, c.optString("cisaExploitAdd"), c.optString("cisaActionDue"),
            c.optString("cisaVulnerabilityName"), c.optString("cisaRequiredAction"), sw)
    }
    return out
}

// Lägger in nya NVD-poster i en tidigare tabell och tar bort det som är för gammalt.
fun mergeNvd(old: Map<String, Nvd>, fresh: Map<String, Nvd>, keepSinceMs: Long): Map<String, Nvd> {
    val out = LinkedHashMap<String, Nvd>()
    fun keep(id: String, n: Nvd) {
        val t = if (n.kevAdded.isNotEmpty()) parseDay(n.kevAdded) else parseTime(n.published)
        if (keepSinceMs <= 0 || t == 0L || t >= keepSinceMs) out[id] = n
    }
    for ((k, v) in old) keep(k, v)
    for ((k, v) in fresh) keep(k, v)
    return out
}

// FIRST EPSS: tabell id -> Epss (värdena kommer som strängar)
fun parseEpss(text: String): Map<String, Epss> {
    val arr = JSONObject(text).getJSONArray("data")
    val out = HashMap<String, Epss>()
    for (i in 0 until arr.length()) {
        val r = arr.getJSONObject(i)
        out[r.optString("cve")] = Epss(r.optString("epss").toDoubleOrNull() ?: -1.0, r.optString("percentile").toDoubleOrNull() ?: -1.0)
    }
    return out
}

private fun decodeEntities(s: String): String =
    Regex("<!\\[CDATA\\[([\\s\\S]*?)\\]\\]>").replace(s) { it.groupValues[1] }
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'")
        .let { t -> Regex("&#(\\d+);").replace(t) { m -> m.groupValues[1].toInt().toChar().toString() } }
        .replace("&amp;", "&")

private fun stripTags(s: String): String = Regex("<[^>]*>").replace(s, " ").replace(Regex("\\s+"), " ").trim()

private fun tagText(block: String, tag: String): String {
    val m = Regex("<$tag(\\s[^>]*)?>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE).find(block) ?: return ""
    return decodeEntities(m.groupValues[2]).trim()
}

fun findCves(s: String): List<String> = Regex("CVE-\\d{4}-\\d{4,7}").findAll(s).map { it.value }.distinct().toList()

// CERT-SE skriver "Kritisk sårbarhet i …" eller "Kritiska sårbarheter i …" när det är bråttom.
fun isCriticalTitle(t: String): Boolean =
    Regex("^\\s*(kritisk|kritiska|critical)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) ||
        Regex("allvarlig(a)? sårbarhet(er)? .*utnyttja", RegexOption.IGNORE_CASE).containsMatchIn(t)

// Atom (CERT-SE använder Atom) eller RSS 2.0.
fun parseFeed(text: String): List<FeedItem> {
    val out = ArrayList<FeedItem>()
    for (m in Regex("<(entry|item)[\\s>][\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE).findAll(text)) {
        val b = m.value
        val title = stripTags(tagText(b, "title"))
        val lm = Regex("<link[^>]*href=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(b)
        val link = if (lm != null) decodeEntities(lm.groupValues[1]) else tagText(b, "link")
        val date = tagText(b, "published").ifEmpty { tagText(b, "updated") }.ifEmpty { tagText(b, "pubDate") }
        val body = tagText(b, "content").ifEmpty { tagText(b, "summary") }.ifEmpty { tagText(b, "description") }
        val summary = stripTags(body)
        out.add(FeedItem(title, link, parseTime(date), findCves("$title $body"), isCriticalTitle(title),
            if (summary.length > 400) summary.substring(0, 400) else summary))
    }
    return out
}

// ---- Plattform ---------------------------------------------------------------
// windows, linux, apps (serverprogram på Windows/Linux), virt (hypervisorer),
// device (nätverksutrustning och apparater), client (datorer och mobiler).

val PLATFORMS = listOf("windows", "linux", "apps", "virt", "device", "client")
val DEFAULT_PLATFORMS: Map<String, Boolean> =
    mapOf("windows" to true, "linux" to true, "apps" to true, "virt" to true, "device" to false, "client" to false)

private val CPE_WINDOWS = Regex("^(o:microsoft:windows|a:microsoft:(exchange|sharepoint|sql_server|outlook_web|internet_information|iis|windows_server|system_center|active_directory|hyper-v|dynamics))")
private val CPE_KERNEL = Regex("^o:linux:linux_kernel")
// Linuxdistributioner listas när ett paket i dem är sårbart. De avgör inte ensamma.
private val CPE_DISTRO = Regex("^o:(redhat:|debian:|canonical:|suse:|opensuse:|fedoraproject:|oracle:linux|almalinux:|rockylinux:|alpinelinux:|centos:)")
private val CPE_VIRT = Regex("^(o:vmware:esxi|a:vmware:(vcenter|esxi|vsphere)|o:citrix:hypervisor|o:xen:|a:xen:|o:proxmox:|a:proxmox:)")
private val CPE_CLIENT = Regex("^(a:apple:|o:apple:|o:google:android|a:google:(chrome|android)|a:mozilla:|a:microsoft:(office|365_apps|word|excel|powerpoint|outlook$|edge|teams|visio|project)|a:webkitgtk:|a:wpewebkit:|o:samsung:)")
private val CPE_APPLIANCE = Regex("^a:(cisco|fortinet|paloaltonetworks|sonicwall|f5|checkpoint|juniper|zyxel|mikrotik|barracuda|arista|synology|qnap|ubiquiti|draytek|watchguard|hikvision|dahua|tp-link|dlink|netgear|array_networks|progress:loadmaster|kemp|ivanti:(connect_secure|policy_secure|pulse|neurons_for_zero_trust)|citrix:(netscaler|adc|gateway|application_delivery)|beyondtrust:(remote_support|privileged_remote_access)|sophos:(firewall|xg))")
private val OS_VENDORS = Regex("^o:(microsoft|linux|redhat|debian|canonical|suse|opensuse|fedoraproject|oracle|almalinux|rockylinux|alpinelinux|centos|vmware|citrix|xen|proxmox|freebsd|openbsd|netbsd)")

private val TXT_CLIENT = Regex("\\b(apple|ios|ipados|macos|safari|webkit|android|pixel|chrome|chromium|samsung mobile|qualcomm|mali gpu|firefox|thunderbird)\\b|microsoft (office|word|excel|outlook|powerpoint|edge|teams)")
private val TXT_DEVICE = Regex("\\b(cisco|fortinet|forti\\w+|palo alto|pan-os|netscaler|citrix adc|citrix gateway|connect secure|policy secure|pulse secure|sonicwall|sonicos|barracuda|big-ip|f5|check point|mikrotik|routeros|zyxel|juniper|junos|arista|velocloud|tp-link|d-link|netgear|synology|qnap|hikvision|dahua|ubiquiti|draytek|watchguard|loadmaster|aruba|ruckus|tenda|totolink|digiever|router|routers|nas|camera|nvr|dvr|printer|firmware)\\b")
private val TXT_WINDOWS = Regex("\\b(windows|exchange server|sharepoint|sql server|iis|active directory|hyper-v|win32k|ntlm|smb)\\b|microsoft")
private val TXT_LINUX = Regex("\\b(linux|kernel|red hat|rhel|debian|ubuntu|suse|glibc|sudo|systemd|polkit|openssh|xz utils)\\b")
private val TXT_VIRT = Regex("\\b(esxi|vcenter|vsphere|proxmox|xenserver|citrix hypervisor)\\b")

fun platformOf(kind: String, vendor: String, product: String, name: String, cpes: List<String>): String {
    if (cpes.isNotEmpty()) {
        if (cpes.any { CPE_WINDOWS.containsMatchIn(it) }) return "windows"
        if (cpes.any { CPE_KERNEL.containsMatchIn(it) }) return "linux"
        if (cpes.any { CPE_VIRT.containsMatchIn(it) }) return "virt"
        var nClient = 0
        var nDevice = 0
        var nApp = 0
        var nDistro = 0
        for (c in cpes) {
            when {
                CPE_DISTRO.containsMatchIn(c) -> nDistro++
                c.startsWith("h:") || CPE_APPLIANCE.containsMatchIn(c) ||
                    (c.startsWith("o:") && !OS_VENDORS.containsMatchIn(c) && !CPE_CLIENT.containsMatchIn(c)) -> nDevice++
                CPE_CLIENT.containsMatchIn(c) -> nClient++
                else -> nApp++
            }
        }
        // Serverprogram vinner (t.ex. ett bibliotek som också finns i en apparat)
        if (nApp > 0) return "apps"
        if (nDevice > 0) return "device"
        if (nClient > 0) return "client"
        if (nDistro > 0) return "linux"
    }
    val text = "$vendor $product $name".lowercase()
    return when {
        TXT_VIRT.containsMatchIn(text) -> "virt"
        TXT_DEVICE.containsMatchIn(text) -> "device"
        TXT_CLIENT.containsMatchIn(text) -> "client"
        TXT_WINDOWS.containsMatchIn(text) -> "windows"
        TXT_LINUX.containsMatchIn(text) -> "linux"
        else -> "apps"
    }
}

fun platformOf(it: Item): String = platformOf(it.kind, it.vendor, it.product, it.name, it.cpes)

// ---- Sammanslagning och bedömning --------------------------------------------

// Nåbar över nätet utan inloggning och utan att någon behöver klicka.
fun remoteNoAuth(vector: String): Boolean = vector.contains("AV:N") && vector.contains("PR:N") && vector.contains("UI:N")

// Reserv när NVD inte har satt någon poäng än.
fun looksLikeUnauthRce(desc: String): Boolean =
    Regex("unauthenticated|without authentication|unauthorized remote|authentication bypass", RegexOption.IGNORE_CASE).containsMatchIn(desc) &&
        Regex("arbitrary code|arbitrary commands|remote code execution|code execution|command injection|execute (os |system )?commands", RegexOption.IGNORE_CASE).containsMatchIn(desc)

private class Level(val cvss: Double, val epss: Double)

private val LEVELS = mapOf("strict" to Level(9.0, 0.7), "normal" to Level(9.0, 0.4), "broad" to Level(8.0, 0.2))

fun merge(kev: List<Kev>, nvd: Map<String, Nvd>, epss: Map<String, Epss>, feed: List<FeedItem>,
          recentSinceMs: Long, feedSinceMs: Long): List<Item> {
    val byId = HashMap<String, Item>()
    val list = ArrayList<Item>()

    fun add(id: String, vendor: String, product: String, name: String, desc: String, k: KevInfo?, date: Long): Item {
        val n = nvd[id]
        val e = epss[id]
        val it = Item("cve", id, vendor, product, if (name.isNotEmpty()) name else (n?.kevName ?: ""),
            if (n != null && n.desc.isNotEmpty()) n.desc else desc, k,
            n?.cvss ?: -1.0, n?.vector ?: "", n?.version ?: "", n?.ssvc ?: emptyMap(), n?.cpes ?: emptyList(),
            e?.epss ?: -1.0, e?.pct ?: -1.0, n?.published ?: "", ArrayList(), emptyList(), nvdLink(id), date,
            n?.sw ?: emptyList())
        byId[id] = it
        list.add(it)
        return it
    }

    for (k in kev) {
        if (byId.containsKey(k.id)) continue
        add(k.id, k.vendor, k.product, k.name, k.desc, KevInfo(k.added, k.due, k.ransomware, k.action), parseDay(k.added))
    }
    // NVD kan ha KEV-poster som CISA:s fil inte hunnit få (eller tvärtom)
    for ((id, n) in nvd) {
        if (byId.containsKey(id)) continue
        if (n.kevAdded.isNotEmpty()) {
            add(id, "", "", "", "", KevInfo(n.kevAdded, n.kevDue, false, n.kevAction), parseDay(n.kevAdded))
        } else {
            val pub = parseTime(n.published)
            if (pub != 0L && recentSinceMs > 0 && pub < recentSinceMs) continue
            add(id, "", "", "", "", null, pub)
        }
    }
    for (a in feed) {
        if (feedSinceMs > 0 && a.published != 0L && a.published < feedSinceMs) continue
        var linked = false
        for (c in a.cves) {
            val hit = byId[c] ?: continue
            hit.certse.add(CertRef(a.title, a.link, a.critical))
            linked = true
        }
        if (a.critical && !linked) {
            list.add(Item("certse", a.link, "CERT-SE", "", a.title, a.summary, null, -1.0, "", "", emptyMap(), emptyList(),
                -1.0, -1.0, "", mutableListOf(CertRef(a.title, a.link, true)), a.cves, a.link, a.published))
        }
    }
    return list
}

// ---- Program du kör ------------------------------------------------------------
// Kritiska sårbarheter (CVSS 9+) i valda program visas alltid, även innan de utnyttjas.
// Matchning: CPE (och target_sw) i första hand. Utan CPE: leverantör/produkt och beskrivning.

val SOFTWARE = listOf("php", "drupal", "cakephp", "nginx", "java", "apache",
    "vmware", "proxmox", "mysql", "jenkins", "gitlab")
val SOFTWARE_NAMES = mapOf("php" to "PHP", "drupal" to "Drupal", "cakephp" to "CakePHP", "nginx" to "nginx",
    "java" to "Java (JDK/JRE)", "apache" to "Apache HTTP Server",
    "vmware" to "VMware vSphere (ESX, vCenter)", "proxmox" to "Proxmox (VE, Backup Server)",
    "mysql" to "MySQL / MariaDB", "jenkins" to "Jenkins", "gitlab" to "GitLab")
val SOFTWARE_SHORT = mapOf("php" to "PHP", "drupal" to "Drupal", "cakephp" to "CakePHP", "nginx" to "nginx",
    "java" to "Java", "apache" to "Apache httpd",
    "vmware" to "VMware", "proxmox" to "Proxmox", "mysql" to "MySQL", "jenkins" to "Jenkins", "gitlab" to "GitLab")

private val SW_CPE = mapOf(
    "php" to Regex("^a:php:php$"),
    "drupal" to Regex("^a:drupal:"),
    "cakephp" to Regex("^a:cakephp:cakephp"),
    "nginx" to Regex("^a:(f5|nginx):nginx|^a:kubernetes:ingress[-_]nginx"),
    "java" to Regex("^a:oracle:(jdk|jre|java_se|openjdk|graalvm)|^a:(openjdk:|eclipse:temurin|azul:zulu|bellsoft:liberica|amazon:corretto|ibm:(java|semeru)|redhat:(openjdk|build_of_openjdk)|microsoft:(openjdk|build_of_openjdk))"),
    "apache" to Regex("^a:apache:http_server$"),
    // vSphere och det som hör till (inte Spring, Tanzu, Horizon eller Workstation/Fusion)
    "vmware" to Regex("^[ao]:(vmware|broadcom):(esxi?|vcenter|vsphere|cloud_foundation|vcloud|nsx|aria|vrealize|hcx|avi|tools|vmware_tools|telco_cloud|sd-wan|site_recovery|vsan|cloud_director)"),
    "proxmox" to Regex("^a:proxmox:"),
    "mysql" to Regex("^a:(oracle:mysql(_server)?$|mysql:mysql$|mariadb:mariadb$|percona:percona_server$)"),
    "jenkins" to Regex("^a:jenkins:"),
    "gitlab" to Regex("^a:gitlab:")
)
private val SW_TARGET = mapOf("drupal" to Regex("^drupal"), "php" to Regex("^php$"), "jenkins" to Regex("^jenkins$"))
// Leverantör + produkt (KEV) och CERT-SE-rubriker, i gemener
private val SW_NAME = mapOf(
    "php" to Regex("(^|[^\\w-])php([^\\w-]|$)"),
    "drupal" to Regex("\\bdrupal\\b"),
    "cakephp" to Regex("\\bcakephp\\b"),
    "nginx" to Regex("\\bnginx\\b"),
    "java" to Regex("\\b(java se|java runtime|openjdk|jdk|jre)\\b|\\boracle java\\b"),
    "apache" to Regex("\\bapache http server\\b|\\bapache httpd\\b"),
    "vmware" to Regex("\\bvmware\\b|\\besxi?\\b|\\bvcenter\\b|\\bvsphere\\b"),
    "proxmox" to Regex("\\bproxmox\\b"),
    "mysql" to Regex("\\bmysql\\b|\\bmariadb\\b"),
    "jenkins" to Regex("\\bjenkins\\b"),
    "gitlab" to Regex("\\bgitlab\\b")
)
// Beskrivningen, i gemener (bara när NVD inte har listat produkterna än)
private val SW_DESC = mapOf(
    "php" to Regex("\\bphp (versions?|before|prior to|through) ?\\d|\\bin php \\d|\\bphp \\d+\\.\\d+(\\.\\d+)?\\b"),
    "drupal" to Regex("\\bdrupal\\b"),
    "cakephp" to Regex("\\bcakephp\\b"),
    "nginx" to Regex("\\bnginx\\b"),
    "java" to Regex("\\b(oracle java se|java se|openjdk|java runtime environment)\\b"),
    "apache" to Regex("\\bapache http server\\b|\\bapache httpd\\b"),
    "vmware" to Regex("\\bvmware (esxi?|vcenter|vsphere|cloud foundation|nsx|aria|vrealize|hcx|avi|tools|telco cloud|vsan)\\b|\\bvcenter server\\b|\\besxi?\\b"),
    "proxmox" to Regex("\\bproxmox\\b"),
    // "MySQL" i en SQL-injektion i en webbapp räcker inte
    "mysql" to Regex("\\b(oracle )?mysql server\\b|\\boracle mysql\\b|\\bmysql (versions?|before|prior to|through) ?\\d|\\bmysql \\d+\\.\\d+(\\.\\d+)?\\b|\\bmariadb (server|versions?|before|prior to|through|\\d)"),
    "jenkins" to Regex("\\bjenkins\\b"),
    "gitlab" to Regex("\\bgitlab (ce|ee|community edition|enterprise edition|runner)\\b|\\bin gitlab\\b|\\bgitlab \\d+\\.\\d")
)
// Undantag för namn och beskrivning (CPE är redan exakt)
private val SW_NOT = mapOf(
    "vmware" to Regex("\\b(spring|tanzu|horizon|workspace one|airwatch|carbon black|greenplum|rabbitmq|salt ?stack|gemfire)\\b|^(?!.*\\b(esxi?|vcenter|vsphere|cloud foundation)\\b).*\\b(workstation|fusion)\\b"),
    "gitlab" to Regex("\\bjenkins\\b|\\bplugin\\b"),
    "mysql" to Regex("\\b(connector|workbench|mysql router|mysql shell|enterprise monitor|phpmyadmin)\\b|\\bsql injection\\b")
)
private fun excluded(k: String, text: String): Boolean = SW_NOT[k]?.containsMatchIn(text) == true

// Vilket av de valda programmen posten gäller, eller "".
fun softwareOf(it: Item, software: Set<String>): String {
    if (software.isEmpty()) return ""
    val names = (if (it.kind == "certse") it.name else it.vendor + " " + it.product).lowercase()
    val desc = it.desc.lowercase()
    for (k in SOFTWARE) {
        if (k !in software) continue
        if (it.cpes.any { c -> SW_CPE.getValue(k).containsMatchIn(c) }) return k
        val t = SW_TARGET[k]
        if (t != null && it.sw.any { s -> t.containsMatchIn(s) }) return k
        if (names.isNotBlank() && SW_NAME.getValue(k).containsMatchIn(names) && !excluded(k, names)) return k
        if (it.cpes.isEmpty() && SW_DESC.getValue(k).containsMatchIn(desc) && !excluded(k, desc)) return k
    }
    return ""
}

class Evaluation(val tier: Int, val reasons: List<String>)

// tier 2 = kritiskt (åtgärda nu), 1 = bevaka, 0 = visas inte. reasons = nycklar för översättning.
// sw: nyckel från softwareOf ("" = inget valt program)
fun evaluate(it: Item, level: String, sw: String = ""): Evaluation {
    val l = LEVELS[level] ?: LEVELS.getValue("normal")
    val r = ArrayList<String>()
    val net = remoteNoAuth(it.vector)
    val scored = it.cvss >= 0
    val auto = it.ssvc["automatable"] == "yes" && it.ssvc["technicalImpact"] == "total"
    val rceText = looksLikeUnauthRce(it.desc)
    val certCrit = it.certse.any { c -> c.critical }

    if (it.kind == "certse") {
        if (sw.isNotEmpty()) r.add("software")
        r.add("certse")
        return Evaluation(if (sw.isNotEmpty() || level != "strict") 2 else 1, r)
    }

    if (sw.isNotEmpty()) r.add("software")
    if (it.kev != null) r.add("kev")
    r.add(if (scored) "cvss" else "unscored")
    if (net) r.add("net")
    if (auto) r.add("ssvc")
    if (!scored && rceText) r.add("rcetext")
    if (it.kev != null && it.kev.ransomware) r.add("ransomware")
    if (it.epss >= l.epss) r.add("epss")
    if (it.certse.isNotEmpty()) r.add(if (certCrit) "certsecrit" else "certse")

    var tier = 0
    val k = it.kev
    if (k != null) {
        tier = 1
        if (scored) {
            when (level) {
                "strict" -> if (it.cvss >= l.cvss && net && it.ssvc["automatable"] != "no") tier = 2
                "broad" -> if (it.cvss >= l.cvss || (net && it.cvss >= 7.0) || k.ransomware) tier = 2
                else -> if (it.cvss >= l.cvss && net) tier = 2
                        else if ((k.ransomware || certCrit) && it.cvss >= 8.0) tier = 2
            }
        } else {
            if (level == "strict") { if (auto && rceText) tier = 2 }
            else if (auto || rceText || (level == "broad" && k.ransomware)) tier = 2
        }
    } else {
        val bad = scored && it.cvss >= 9.0 && net
        tier = when {
            bad && it.epss >= l.epss -> 2
            bad && certCrit && level != "strict" -> 2
            bad && (it.epss >= 0.1 || it.certse.isNotEmpty()) -> 1
            it.certse.isNotEmpty() && scored && it.cvss >= 9.0 -> 1
            else -> 0
        }
    }
    // Program du kör: utnyttjad, CVSS 9+ eller CERT-SE varnar = kritiskt
    if (sw.isNotEmpty() && (it.kev != null || (scored && it.cvss >= 9.0) || (!scored && rceText) || certCrit)) tier = 2
    return Evaluation(tier, r)
}

// Bedömer, filtrerar bort tystade leverantörer och plattformar som inte bevakas, och sorterar:
// åtgärdade sist, kritiska först, nyast först. hidden = kritiska som plattformsfiltret tog bort.
// software: valda program, som visas oavsett dolda leverantörer och plattformar.
fun assess(list: List<Item>, level: String, muted: Set<String>, handled: Set<String>, platforms: Map<String, Boolean>,
           software: Set<String> = emptySet()): Assessed {
    val out = ArrayList<Item>()
    var hidden = 0
    for (it in list) {
        val sw = softwareOf(it, software)
        val ev = evaluate(it, level, sw)
        if (ev.tier == 0) continue
        if (sw.isEmpty() && it.kind == "cve" && muted.contains(it.vendor.lowercase())) continue
        it.platform = platformOf(it)
        it.software = sw
        val on = sw.isNotEmpty() || (platforms[it.platform] ?: (DEFAULT_PLATFORMS[it.platform] ?: true))
        if (!on) {
            if (ev.tier == 2) hidden++
            continue
        }
        it.tier = ev.tier
        it.reasons = ev.reasons
        it.handled = handled.contains(it.id)
        out.add(it)
    }
    val sorted = out.sortedWith(compareBy<Item>({ if (it.handled) 1 else 0 }, { -it.tier }, { -it.date }))
    return Assessed(sorted, hidden)
}

// Vilka id:n behöver EPSS: allt i KEV och nya CVE:er som annars kunde vara kritiska.
fun epssCandidates(kev: List<Kev>, nvd: Map<String, Nvd>): List<String> {
    val ids = LinkedHashSet<String>()
    for (k in kev) ids.add(k.id)
    for ((id, n) in nvd) if (n.kevAdded.isNotEmpty() || (n.cvss >= 8.0 && remoteNoAuth(n.vector))) ids.add(id)
    return ids.toList()
}

fun title(it: Item): String {
    if (it.kind == "certse") return it.name
    val v = it.vendor
    val p = it.product
    if (v.isEmpty() && p.isEmpty()) return when {
        it.name.isNotEmpty() -> it.name.removeSuffix(" Vulnerability")
        it.software.isNotEmpty() -> SOFTWARE_NAMES[it.software] ?: it.id
        else -> it.id
    }
    if (p.isEmpty() || p.lowercase() == v.lowercase()) return if (v.isNotEmpty()) v else p
    // "Barracuda Networks" + "Barracuda Email Security Gateway" -> bara produkten
    val first = v.split(" ")[0].lowercase()
    if (first.isNotEmpty() && p.lowercase().startsWith(first)) return p
    return "$v $p"
}

// ---- Tyst tid ----------------------------------------------------------------

fun inQuietHours(nowMs: Long, fromHour: Int, toHour: Int, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    val t = Instant.ofEpochMilli(nowMs).atZone(zone)
    val h = t.hour + t.minute / 60.0
    return if (fromHour > toHour) (h >= fromHour || h < toHour) else (h >= fromHour && h < toHour)
}

// ---- Cache som JSON ----------------------------------------------------------

fun kevToJson(list: List<Kev>): String {
    val a = JSONArray()
    for (k in list) a.put(JSONObject().put("id", k.id).put("vendor", k.vendor).put("product", k.product)
        .put("name", k.name).put("desc", k.desc).put("added", k.added).put("due", k.due)
        .put("ransomware", k.ransomware).put("action", k.action))
    return a.toString()
}

fun kevFromJson(text: String): List<Kev> {
    val a = JSONArray(text)
    return (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        Kev(o.optString("id"), o.optString("vendor"), o.optString("product"), o.optString("name"), o.optString("desc"),
            o.optString("added"), o.optString("due"), o.optBoolean("ransomware"), o.optString("action"))
    }
}

fun nvdToJson(map: Map<String, Nvd>): String {
    val o = JSONObject()
    for ((id, n) in map) {
        val s = JSONObject()
        for ((k, v) in n.ssvc) s.put(k, v)
        o.put(id, JSONObject().put("cvss", n.cvss).put("vector", n.vector).put("version", n.version)
            .put("desc", n.desc).put("published", n.published).put("ssvc", s).put("cpes", JSONArray(n.cpes))
            .put("kevAdded", n.kevAdded).put("kevDue", n.kevDue).put("kevName", n.kevName).put("kevAction", n.kevAction)
            .put("sw", JSONArray(n.sw)))
    }
    return o.toString()
}

fun nvdFromJson(text: String): Map<String, Nvd> {
    val o = JSONObject(text)
    val out = LinkedHashMap<String, Nvd>()
    for (id in o.keys()) {
        val n = o.getJSONObject(id)
        val s = n.optJSONObject("ssvc") ?: JSONObject()
        val ssvc = HashMap<String, String>()
        for (k in s.keys()) ssvc[k] = s.optString(k)
        val ca = n.optJSONArray("cpes") ?: JSONArray()
        val sa = n.optJSONArray("sw") ?: JSONArray()
        out[id] = Nvd(n.optDouble("cvss", -1.0), n.optString("vector"), n.optString("version"), n.optString("desc"),
            n.optString("published"), ssvc, (0 until ca.length()).map { ca.getString(it) },
            n.optString("kevAdded"), n.optString("kevDue"), n.optString("kevName"), n.optString("kevAction"),
            (0 until sa.length()).map { sa.getString(it) })
    }
    return out
}

fun epssToJson(map: Map<String, Epss>): String {
    val o = JSONObject()
    for ((id, e) in map) o.put(id, JSONArray().put(e.epss).put(e.pct))
    return o.toString()
}

fun epssFromJson(text: String): Map<String, Epss> {
    val o = JSONObject(text)
    val out = HashMap<String, Epss>()
    for (id in o.keys()) {
        val a = o.getJSONArray(id)
        out[id] = Epss(a.getDouble(0), a.getDouble(1))
    }
    return out
}

fun feedToJson(list: List<FeedItem>): String {
    val a = JSONArray()
    for (f in list) a.put(JSONObject().put("title", f.title).put("link", f.link).put("published", f.published)
        .put("cves", JSONArray(f.cves)).put("critical", f.critical).put("summary", f.summary))
    return a.toString()
}

fun feedFromJson(text: String): List<FeedItem> {
    val a = JSONArray(text)
    return (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        val c = o.optJSONArray("cves") ?: JSONArray()
        FeedItem(o.optString("title"), o.optString("link"), o.optLong("published"),
            (0 until c.length()).map { c.getString(it) }, o.optBoolean("critical"), o.optString("summary"))
    }
}
