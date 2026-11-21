// SPDX-FileCopyrightText: 2026 Johan Finn
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.finnjohan.patchnow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.finnjohan.patchnow.R
import io.github.finnjohan.patchnow.logic.Item
import io.github.finnjohan.patchnow.logic.SOFTWARE_NAMES
import io.github.finnjohan.patchnow.logic.daysBetween
import io.github.finnjohan.patchnow.logic.kevLink
import io.github.finnjohan.patchnow.logic.nvdLink
import io.github.finnjohan.patchnow.logic.parseDay
import io.github.finnjohan.patchnow.logic.parseTime
import io.github.finnjohan.patchnow.logic.title
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(item: Item?, now: Long, onBack: () -> Unit, onHandled: (String, Boolean) -> Unit) {
    val uri = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (item == null) "" else if (item.kind == "cve") item.id else "CERT-SE") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (item != null) {
                        IconButton(onClick = { onHandled(item.id, !item.handled) }) {
                            Icon(if (item.handled) Icons.Filled.Close else Icons.Filled.Check,
                                contentDescription = stringResource(if (item.handled) R.string.not_handled else R.string.mark_handled))
                        }
                        IconButton(onClick = { uri.openUri(item.link) }) {
                            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = stringResource(R.string.open_in_browser))
                        }
                    }
                }
            )
        }
    ) { pad ->
        if (item == null) return@Scaffold
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title(item), style = MaterialTheme.typography.headlineSmall)
            if (item.kind == "cve" && item.name.isNotEmpty()) {
                Text(item.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Verdict(item)
            if (item.kind == "cve") Facts(item, now)

            if (item.kev != null && item.kev.action.isNotEmpty()) {
                Text(stringResource(R.string.what_to_do), fontWeight = FontWeight.Medium)
                Text(item.kev.action)
            }
            if (item.desc.isNotEmpty()) {
                Text(stringResource(R.string.description), fontWeight = FontWeight.Medium)
                Text(item.desc)
            }
            if (item.kind == "certse" && item.cves.isNotEmpty()) Text(item.cves.joinToString(", "))

            Text(stringResource(R.string.read_more), fontWeight = FontWeight.Medium)
            Column {
                for (c in item.certse) LinkRow("CERT-SE: " + c.title) { uri.openUri(c.link) }
                if (item.kind == "cve") {
                    LinkRow(stringResource(R.string.link_nvd)) { uri.openUri(nvdLink(item.id)) }
                    if (item.kev != null) LinkRow(stringResource(R.string.link_kev)) { uri.openUri(kevLink(item.id)) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Verdict(item: Item) {
    val colors = LocalAppColors.current
    val tint = if (item.tier == 2 && !item.handled) colors.critical else colors.watch
    Column(
        Modifier.fillMaxWidth().background(tint.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(if (item.handled) R.string.section_handled else if (item.tier == 2) R.string.section_critical else R.string.section_watch),
            color = tint, fontWeight = FontWeight.Medium
        )
        for (r in item.reasons) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(if (r == "unscored") Icons.Filled.Info else Icons.Filled.Check, contentDescription = null,
                    modifier = Modifier.size(18.dp).padding(top = 2.dp))
                Spacer(Modifier.width(10.dp))
                Text(reasonText(item, r))
            }
        }
    }
}

@Composable
private fun reasonText(item: Item, key: String): String = when (key) {
    "software" -> stringResource(R.string.reason_software, SOFTWARE_NAMES[item.software] ?: item.software)
    "kev" -> stringResource(R.string.reason_kev, formatDay(parseDay(item.kev?.added ?: "")))
    "cvss" -> stringResource(R.string.reason_cvss, cvssText(item.cvss))
    "unscored" -> stringResource(R.string.reason_unscored)
    "net" -> stringResource(R.string.reason_net)
    "ssvc" -> stringResource(R.string.reason_ssvc)
    "rcetext" -> stringResource(R.string.reason_rcetext)
    "ransomware" -> stringResource(R.string.reason_ransomware)
    "epss" -> stringResource(R.string.reason_epss, Math.round(item.epss * 100).toInt())
    "certsecrit" -> stringResource(R.string.reason_certsecrit)
    "certse" -> stringResource(if (item.kind == "certse") R.string.reason_certse_item else R.string.reason_certse)
    else -> key
}

@Composable
private fun Facts(item: Item, now: Long) {
    Column {
        FactRow(stringResource(R.string.fact_platform), stringResource(platformLongRes(item.platform)))
        if (item.software.isNotEmpty()) FactRow(stringResource(R.string.fact_software), SOFTWARE_NAMES[item.software] ?: item.software)
        if (item.cvss >= 0) FactRow(stringResource(R.string.fact_cvss),
            cvssText(item.cvss) + if (item.cvssVersion.isNotEmpty()) "  (v${item.cvssVersion})" else "")
        if (item.epss >= 0) FactRow(stringResource(R.string.fact_epss),
            stringResource(R.string.fact_epss_value, String.format(Locale.ROOT, "%.1f", item.epss * 100), Math.floor(item.pct * 100).toInt()))
        val kev = item.kev
        if (kev != null) {
            FactRow(stringResource(R.string.fact_in_kev), formatDay(parseDay(kev.added)))
            if (kev.due.isNotEmpty()) {
                val dueMs = parseDay(kev.due)
                val d = daysBetween(now, dueMs)
                val when_ = formatDay(dueMs)
                FactRow(stringResource(R.string.fact_deadline), when {
                    d < 0 -> stringResource(R.string.due_passed, when_)
                    d == 0L -> stringResource(R.string.due_today, when_)
                    else -> pluralStringResource(R.plurals.due_in, d.toInt(), d.toInt(), when_)
                })
            }
        }
        if (item.published.isNotEmpty()) FactRow(stringResource(R.string.fact_published), formatDay(parseTime(item.published)))
        if (item.vector.isNotEmpty()) FactRow(stringResource(R.string.fact_vector), item.vector.replace(Regex("^CVSS:[0-9.]+/"), ""))
    }
}

@Composable
private fun LinkRow(text: String, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), maxLines = 1)
            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp))
        }
        HorizontalDivider()
    }
}
