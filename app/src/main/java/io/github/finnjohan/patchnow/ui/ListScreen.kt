// SPDX-FileCopyrightText: 2026 Johan Finn
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.finnjohan.patchnow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.finnjohan.patchnow.R
import io.github.finnjohan.patchnow.data.UiState
import io.github.finnjohan.patchnow.logic.Item
import io.github.finnjohan.patchnow.logic.title
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onHandled: (String, Boolean) -> Unit,
    onHide: (String) -> Unit,
    onUnhide: (String) -> Unit
) {
    var showHandled by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val colors = LocalAppColors.current

    val critical = state.items.filter { it.tier == 2 && !it.handled }
    val watch = state.items.filter { it.tier == 1 && !it.handled }
    val handled = state.items.filter { it.handled }

    fun hide(vendor: String) {
        onHide(vendor)
        scope.launch {
            val r = snackbar.showSnackbar(context.getString(R.string.vendor_hidden, vendor), context.getString(R.string.undo))
            if (r == SnackbarResult.ActionPerformed) onUnhide(vendor)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.loading && !state.demo) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { if (!state.demo) onRefresh() },
            modifier = Modifier.padding(pad).fillMaxSize()
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "summary") { Summary(state) }

                if (critical.isNotEmpty()) {
                    item(key = "h-critical") { SectionTitle(stringResource(R.string.section_critical), colors.critical) }
                    items(critical, key = { "c-" + it.id }) { AlertRow(it, onOpen, onHandled, ::hide) }
                }
                if (watch.isNotEmpty()) {
                    item(key = "h-watch") { SectionTitle(stringResource(R.string.section_watch), MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(watch, key = { "w-" + it.id }) { AlertRow(it, onOpen, onHandled, ::hide) }
                }
                if (showHandled && handled.isNotEmpty()) {
                    item(key = "h-handled") { SectionTitle(stringResource(R.string.section_handled), MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(handled, key = { "h-" + it.id }) { AlertRow(it, onOpen, onHandled, ::hide) }
                }

                item(key = "footer") {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (state.items.isEmpty() && state.fetchedAt > 0) {
                            Text(stringResource(R.string.empty_period), textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.hiddenCount > 0) {
                            SmallText(pluralStringResource(R.plurals.hidden_by_platform, state.hiddenCount, state.hiddenCount) +
                                " " + stringResource(R.string.change_platforms),
                                modifier = Modifier.clickable { onSettings() })
                        }
                        if (handled.isNotEmpty()) {
                            OutlinedButton(onClick = { showHandled = !showHandled }) {
                                Text(if (showHandled) stringResource(R.string.hide_handled)
                                     else stringResource(R.string.show_handled, handled.size))
                            }
                        }
                        SmallText(stringResource(R.string.swipe_hint))
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Summary(state: UiState) {
    val colors = LocalAppColors.current
    val crit = state.criticalCount > 0
    val bg = if (crit) colors.critical.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(12.dp).background(bg, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(if (crit) Icons.Filled.Warning else Icons.Filled.CheckCircle, contentDescription = null,
            tint = if (crit) colors.critical else colors.positive, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                when {
                    state.fetchedAt == 0L && state.items.isEmpty() ->
                        stringResource(if (state.loading) R.string.checking else R.string.no_data)
                    !crit -> stringResource(R.string.nothing_critical)
                    else -> pluralStringResource(R.plurals.to_act_on, state.criticalCount, state.criticalCount)
                },
                style = MaterialTheme.typography.titleLarge
            )
            if (state.newCount > 0) {
                Text(pluralStringResource(R.plurals.new_recent, state.newCount, state.newCount),
                    color = colors.critical, fontWeight = FontWeight.Medium)
            }
            val checked = if (state.fetchedAt > 0) stringResource(R.string.checked_at, formatClock(state.fetchedAt)) + " · " else ""
            SmallText(checked + stringResource(R.string.level_line, levelName(state.settings.level), state.settings.windowDays))
            val err = errorText(state)
            if (err != null) SmallText(err, color = colors.watch)
        }
    }
}

@Composable
private fun errorText(state: UiState): String? {
    val names = linkedMapOf("kev" to "CISA", "nvd" to "NVD", "epss" to "EPSS", "certse" to "CERT-SE")
    val failed = names.filterKeys { state.errors.containsKey(it) }.values.toList()
    if (failed.isEmpty()) return null
    val limited = state.errors.values.any { it == "limit" }
    return when {
        failed.size == 4 && !limited -> stringResource(R.string.error_offline, formatClock(state.fetchedAt))
        limited -> stringResource(R.string.error_limit, failed.joinToString(", "))
        else -> stringResource(R.string.error_partial, failed.joinToString(", "))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AlertRow(it: Item, onOpen: (String) -> Unit, onHandled: (String, Boolean) -> Unit, onHide: (String) -> Unit) {
    val colors = LocalAppColors.current
    val canHide = it.kind == "cve" && it.vendor.isNotEmpty()
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            when (v) {
                SwipeToDismissBoxValue.StartToEnd -> onHandled(it.id, !it.handled)
                SwipeToDismissBoxValue.EndToStart -> if (canHide) onHide(it.vendor)
                SwipeToDismissBoxValue.Settled -> { }
            }
            false   // raden ligger kvar; listan uppdateras i stället
        }
    )
    val tint = if (it.tier == 2 && !it.handled) colors.critical else colors.watch

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromEndToStart = canHide,
        backgroundContent = {
            val toRight = dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier.fillMaxSize().background(if (toRight) colors.positive else MaterialTheme.colorScheme.secondary)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (toRight) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (toRight) Icons.Filled.Done else Icons.Filled.Clear, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (toRight) stringResource(if (it.handled) R.string.not_handled else R.string.mark_handled)
                        else stringResource(R.string.hide_vendor, it.vendor),
                        color = Color.White
                    )
                }
            }
        }
    ) {
        Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable { onOpen(it.id) }.padding(end = 16.dp)) {
                Box(Modifier.padding(vertical = 10.dp).width(4.dp).fillMaxHeight()
                    .background(if (it.handled) Color.Transparent else tint))
                Column(Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title(it), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                            color = if (it.handled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        if (it.isNew) {
                            Spacer(Modifier.width(8.dp))
                            Chip(stringResource(R.string.chip_new), colors.critical, filled = true)
                        }
                    }
                    SmallText(listOf(if (it.kind == "cve") it.id else "", formatDay(it.date)).filter { s -> s.isNotEmpty() }.joinToString(" · "))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val grey = MaterialTheme.colorScheme.onSurfaceVariant
                        if (it.kind == "certse") Chip("CERT-SE", tint)
                        platformShort(it.platform)?.let { p -> Chip(p, grey) }
                        if (it.cvss >= 0) Chip("CVSS " + cvssText(it.cvss), if (it.cvss >= 9) tint else grey)
                        if (it.kev != null) Chip(stringResource(R.string.chip_exploited), tint)
                        if (it.kev?.ransomware == true) Chip(stringResource(R.string.chip_ransomware), tint)
                        if (it.epss >= 0.1) Chip("EPSS " + Math.round(it.epss * 100) + " %", if (it.epss >= 0.4) tint else grey)
                        if (it.kind != "certse" && it.certse.isNotEmpty()) Chip("CERT-SE", grey)
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
