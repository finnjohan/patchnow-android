// SPDX-FileCopyrightText: 2026 Johan Finn
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.finnjohan.patchnow.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

// Samma fall som tests/logic.test.js i PatchNow för Ubuntu Touch, med riktiga
// svar från CISA KEV, NVD (via fkie-cad/nvd-json-data-feeds) och CERT-SE.
class LogicTest {

    private fun fixture(name: String): String =
        LogicTest::class.java.getResourceAsStream("/fixtures/$name")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private val zone: ZoneId = ZoneId.systemDefault()
    private val now = LocalDateTime.of(2026, 11, 19, 9, 0).atZone(zone).toInstant().toEpochMilli()
    private val all = PLATFORMS.associate { it to true }

    private fun realList(): List<Item> {
        val kev = parseKev(fixture("kev.json"), "2026-10-20")
        val nvd = parseNvd(fixture("nvd-kev.json"))
        val feed = parseFeed(fixture("certse.xml"))
        return merge(kev, nvd, emptyMap(), feed, now - 10 * DAY_MS, now - 30 * DAY_MS)
    }

    private fun item(cvss: Double, vector: String, epss: Double, kev: KevInfo?, desc: String = ""): Item =
        Item("cve", "X", "", "", "", desc, kev, cvss, vector, "3.1", emptyMap(), emptyList(), epss, -1.0, "",
            ArrayList(), emptyList(), "", 0)

    @Test fun urls() {
        val t = LocalDateTime.of(2026, 11, 19, 7, 5, 9).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("2026-11-19T07:05:09.000", nvdDate(t))
        val t0 = LocalDateTime.of(2026, 11, 19, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertTrue(nvdKevUrl(t0, 200).contains("kevStartDate=2026-08-21T00:00:00.000"))
        assertTrue(nvdRecentUrl(0, 10).contains("cvssV3Severity=CRITICAL"))
        assertTrue(nvdKevChangesUrl(0, 1).contains("hasKev&lastModStartDate="))
        val ids = (0 until 120).map { "CVE-2026-1" + it.toString().padStart(5, '0') }
        val u = epssUrls(ids)
        assertEquals(3, u.size)
        assertTrue(u.all { it.substringAfter("cve=").length < 2000 })
    }

    @Test fun parsing() {
        val kev = parseKev(fixture("kev.json"), "2026-10-20")
        assertTrue(kev.size > 20 && kev.all { it.added >= "2026-10-20" })
        val nvd = parseNvd(fixture("nvd-kev.json"))
        assertEquals(10.0, nvd.getValue("CVE-2026-97229").cvss, 0.001)
        assertTrue(remoteNoAuth(nvd.getValue("CVE-2026-97229").vector))
        assertEquals("yes", nvd.getValue("CVE-2015-3306").ssvc["automatable"])
        assertTrue(nvd.getValue("CVE-2024-10441").cpes.contains("o:synology:diskstation_manager"))
        val e = parseEpss("{\"status\":\"OK\",\"data\":[{\"cve\":\"CVE-1\",\"epss\":\"0.912340000\",\"percentile\":\"0.99\"}]}")
        assertEquals(0.91234, e.getValue("CVE-1").epss, 1e-9)
        val feed = parseFeed(fixture("certse.xml"))
        assertEquals(5, feed.size)
        assertTrue(feed[0].critical)
        assertEquals("CVE-2026-114938", feed[0].cves[0])
        assertFalse(feed[1].critical)
        assertEquals("CVE-2026-98432", feed[4].cves[0])
        assertTrue(feed[0].published > 0)
        val rss = parseFeed("<rss><channel><item><title>Critical flaw &amp; more</title><link>https://x/y</link>" +
            "<pubDate>Tue, 17 Nov 2026 10:00:00 GMT</pubDate><description><![CDATA[CVE-2026-1234]]></description></item></channel></rss>")
        assertTrue(rss[0].critical)
        assertEquals("https://x/y", rss[0].link)
        assertEquals("CVE-2026-1234", rss[0].cves[0])
        assertTrue(rss[0].published > 0)
    }

    @Test fun mergeNvdReplacesAndPrunes() {
        fun n(kevAdded: String, cvss: Double, published: String = "") =
            Nvd(cvss, "", "", "", published, emptyMap(), emptyList(), kevAdded, "", "", "")
        val since = LocalDateTime.of(2026, 10, 1, 0, 0).atZone(zone).toInstant().toEpochMilli()
        val m = mergeNvd(mapOf("A" to n("2026-01-01", 1.0), "B" to n("2026-11-01", -1.0)),
            mapOf("B" to n("2026-11-01", 9.8), "C" to n("", 5.0, "2026-11-18T10:00:00.000")), since)
        assertFalse(m.containsKey("A"))
        assertEquals(9.8, m.getValue("B").cvss, 0.001)
        assertTrue(m.containsKey("C"))
    }

    @Test fun assessmentWithRealData() {
        for (lvl in listOf("strict", "normal", "broad")) {
            val a = assess(realList(), lvl, emptySet(), emptySet(), all).items
            println("    $lvl: ${a.count { it.tier == 2 }} kritiska av ${a.size}")
        }
        val norm = assess(realList(), "normal", emptySet(), emptySet(), all).items
        fun byId(id: String) = norm.first { it.id == id }
        assertEquals(2, byId("CVE-2026-90436").tier)
        assertEquals(1, byId("CVE-2026-123039").tier)
        assertEquals(2, norm.first().tier)
        assertEquals(1, norm.last().tier)
        assertTrue(norm.any { it.kind == "certse" && it.tier == 2 })
        val muted = assess(realList(), "normal", setOf("apache"), emptySet(), all).items
        assertFalse(muted.any { it.vendor == "Apache" })
        val h = assess(realList(), "normal", emptySet(), setOf("CVE-2026-90436"), all).items
        assertEquals("CVE-2026-90436", h.last().id)
    }

    @Test fun levels() {
        val net = "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"
        assertEquals(2, evaluate(item(9.8, net, 0.5, null), "normal").tier)
        assertEquals(1, evaluate(item(9.8, net, 0.5, null), "strict").tier)
        assertEquals(0, evaluate(item(9.8, net, 0.01, null), "broad").tier)
        val desc = "An unauthenticated remote attacker can execute arbitrary code via a crafted request."
        val k = KevInfo("2026-11-01", "", false, "")
        assertEquals(2, evaluate(item(-1.0, "", -1.0, k, desc), "normal").tier)
        assertEquals(1, evaluate(item(-1.0, "", -1.0, k, desc), "strict").tier)
    }

    @Test fun platforms() {
        val norm = assess(realList(), "normal", emptySet(), emptySet(), all).items
        val plat = norm.associate { it.id to it.platform }
        val expect = mapOf(
            "CVE-2026-96951" to "windows", "CVE-2026-46300" to "linux", "CVE-2015-3306" to "apps",
            "CVE-2024-10441" to "device", "CVE-2026-116606" to "device", "CVE-2026-111560" to "device",
            "CVE-2026-76418" to "device", "CVE-2026-76519" to "device", "CVE-2026-0322" to "device",
            "CVE-2025-61624" to "device", "CVE-2026-104935" to "device", "CVE-2026-88779" to "device",
            "CVE-2026-19868" to "device", "CVE-2026-114625" to "device", "CVE-2026-102764" to "device",
            "CVE-2026-41709" to "virt", "CVE-2024-27834" to "client", "CVE-2026-124194" to "client",
            "CVE-2026-125350" to "client", "CVE-2026-97229" to "apps", "CVE-2026-109618" to "apps",
            "CVE-2026-111497" to "apps", "CVE-2026-21589" to "apps", "CVE-2026-123218" to "apps",
            "CVE-2026-90436" to "apps", "CVE-2026-123158" to "apps"
        )
        val wrong = expect.filter { (id, p) -> plat[id] != null && plat[id] != p }
        assertTrue("fel plattform: " + wrong.map { (id, p) -> "$id blev ${plat[id]}, väntat $p" }, wrong.isEmpty())
        assertTrue(expect.keys.count { plat[it] != null } >= 20)
        assertEquals("device", platformOf("cve", "Cisco", "IOS XE", "", emptyList()))
        assertEquals("windows", platformOf("cve", "Microsoft", "Exchange Server", "", emptyList()))
        assertEquals("apps", platformOf("certse", "CERT-SE", "", "Kritisk sårbarhet i Oracle Java", emptyList()))
        val def = assess(realList(), "normal", emptySet(), emptySet(), DEFAULT_PLATFORMS)
        assertFalse(def.items.any { it.platform == "device" || it.platform == "client" })
        assertTrue(def.hidden > 0)
        println("    normal: ${norm.count { it.tier == 2 }} kritiska utan filter, ${def.items.count { it.tier == 2 }} med standardfiltret")
    }

    @Test fun titles() {
        fun t(v: String, p: String, name: String = "") = title(Item("cve", "CVE-1", v, p, name, "", null, -1.0, "", "",
            emptyMap(), emptyList(), -1.0, -1.0, "", ArrayList(), emptyList(), "", 0))
        assertEquals("GPUStack", t("GPUStack", "GPUStack"))
        assertEquals("Barracuda Email Security Gateway (ESG)", t("Barracuda Networks", "Barracuda Email Security Gateway (ESG)"))
        assertEquals("Synology DiskStation Manager (DSM)", t("Synology", "DiskStation Manager (DSM)"))
        assertEquals("Foo Bar RCE", t("", "", "Foo Bar RCE Vulnerability"))
    }

    @Test fun quietHours() {
        fun at(h: Int, m: Int) = LocalDateTime.of(2026, 11, 19, h, m).atZone(zone).toInstant().toEpochMilli()
        assertTrue(inQuietHours(at(23, 30), 22, 7))
        assertTrue(inQuietHours(at(5, 0), 22, 7))
        assertFalse(inQuietHours(at(7, 0), 22, 7))
        assertFalse(inQuietHours(at(12, 0), 22, 7))
    }

    @Test fun cacheRoundTrip() {
        val kev = parseKev(fixture("kev.json"), "2026-10-20")
        assertEquals(kev, kevFromJson(kevToJson(kev)))
        val nvd = parseNvd(fixture("nvd-kev.json"))
        assertEquals(nvd, nvdFromJson(nvdToJson(nvd)))
        val feed = parseFeed(fixture("certse.xml"))
        assertEquals(feed, feedFromJson(feedToJson(feed)))
        val e = mapOf("CVE-1" to Epss(0.5, 0.9))
        assertEquals(e, epssFromJson(epssToJson(e)))
    }
}
