// SPDX-FileCopyrightText: 2026 Johan Finn
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.finnjohan.patchnow.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.finnjohan.patchnow.BuildConfig
import io.github.finnjohan.patchnow.R
import io.github.finnjohan.patchnow.data.Settings
import io.github.finnjohan.patchnow.logic.PLATFORMS
import io.github.finnjohan.patchnow.logic.SOFTWARE
import io.github.finnjohan.patchnow.logic.SOFTWARE_NAMES
import io.github.finnjohan.patchnow.work.Notifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    s: Settings,
    onBack: () -> Unit,
    onChange: ((Settings) -> Settings) -> Unit,
    onUnhide: (String) -> Unit,
    onRequestNotifications: () -> Unit
) {
    val context = LocalContext.current
    var canNotify by remember { mutableStateOf(Notifier.canNotify(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {

            // ---- Nivå ----------------------------------------------------------
            SectionTitle(stringResource(R.string.what_counts))
            RadioRow(stringResource(R.string.level_strict), s.level == "strict") { onChange { it.copy(level = "strict") } }
            RadioRow(stringResource(R.string.level_normal) + " " + stringResource(R.string.recommended), s.level == "normal") {
                onChange { it.copy(level = "normal") }
            }
            RadioRow(stringResource(R.string.level_broad), s.level == "broad") { onChange { it.copy(level = "broad") } }
            SmallText(
                stringResource(when (s.level) { "strict" -> R.string.help_strict; "broad" -> R.string.help_broad; else -> R.string.help_normal }),
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            // ---- Plattformar ---------------------------------------------------
            SectionTitle(stringResource(R.string.platforms))
            SmallText(stringResource(R.string.platforms_help), modifier = Modifier.padding(horizontal = 16.dp))
            for (p in PLATFORMS) {
                SwitchRow(stringResource(platformLongRes(p)), stringResource(platformHelpRes(p)), s.platforms[p] == true) { on ->
                    onChange { it.copy(platforms = it.platforms + (p to on)) }
                }
            }

            // ---- Program du kör ------------------------------------------------
            SectionTitle(stringResource(R.string.software))
            SmallText(stringResource(R.string.software_help), modifier = Modifier.padding(horizontal = 16.dp))
            for (k in SOFTWARE) {
                SwitchRow(SOFTWARE_NAMES.getValue(k), null, k in s.software) { on ->
                    onChange { it.copy(software = if (on) it.software + k else it.software - k) }
                }
            }

            // ---- Notiser -------------------------------------------------------
            SectionTitle(stringResource(R.string.notifications))
            if (!canNotify) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.notifications_off), color = LocalAppColors.current.watch)
                    Row {
                        TextButton(onClick = {
                            if (Build.VERSION.SDK_INT >= 33) onRequestNotifications()
                            val i = Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(i)
                        }) { Text(stringResource(R.string.open_notification_settings)) }
                    }
                }
            }
            SwitchRow(stringResource(R.string.notify_on), null, s.notifyOn) { on -> onChange { it.copy(notifyOn = on) } }
            SwitchRow(stringResource(R.string.quiet_on), null, s.quietOn, enabled = s.notifyOn) { on -> onChange { it.copy(quietOn = on) } }
            OutlinedButton(
                onClick = { canNotify = Notifier.notifyTest(context) || Notifier.canNotify(context) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) { Text(stringResource(R.string.test_notification)) }

            // ---- Bakgrund ------------------------------------------------------
            SectionTitle(stringResource(R.string.check_often))
            val bg = listOf(0 to R.string.check_never, 60 to R.string.check_1h, 180 to R.string.check_3h, 360 to R.string.check_6h)
            for ((m, label) in bg) RadioRow(stringResource(label), s.bgMinutes == m) { onChange { it.copy(bgMinutes = m) } }
            SmallText(stringResource(R.string.check_help), modifier = Modifier.padding(horizontal = 16.dp))

            // ---- Tidsperiod ----------------------------------------------------
            SectionTitle(stringResource(R.string.time_span))
            for (d in listOf(14, 30, 60, 90)) RadioRow(stringResource(R.string.last_days, d), s.windowDays == d) {
                onChange { it.copy(windowDays = d) }
            }

            // ---- Dolda leverantörer --------------------------------------------
            SectionTitle(stringResource(R.string.hidden_vendors))
            if (s.muted.isEmpty()) {
                SmallText(stringResource(R.string.hidden_vendors_empty), modifier = Modifier.padding(horizontal = 16.dp))
            } else {
                for (v in s.muted.sorted()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(v, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onUnhide(v) }) { Text(stringResource(R.string.show_again)) }
                    }
                }
            }

            // ---- Utseende och språk --------------------------------------------
            SectionTitle(stringResource(R.string.theme))
            RadioRow(stringResource(R.string.theme_auto), s.theme == "auto") { onChange { it.copy(theme = "auto") } }
            RadioRow(stringResource(R.string.theme_light), s.theme == "light") { onChange { it.copy(theme = "light") } }
            RadioRow(stringResource(R.string.theme_dark), s.theme == "dark") { onChange { it.copy(theme = "dark") } }

            SectionTitle(stringResource(R.string.language))
            SmallText(stringResource(R.string.language_help), modifier = Modifier.padding(horizontal = 16.dp))
            if (Build.VERSION.SDK_INT >= 33) {
                TextButton(onClick = {
                    val i = Intent(AndroidSettings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.language_open)) }
            }

            // ---- Om ------------------------------------------------------------
            SectionTitle(stringResource(R.string.about))
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.about_text), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SmallText(stringResource(R.string.sources))
                Spacer(Modifier.height(6.dp))
                SmallText(stringResource(R.string.nvd_notice))
                Spacer(Modifier.height(6.dp))
                SmallText(stringResource(R.string.disclaimer))
                Spacer(Modifier.height(6.dp))
                SmallText(stringResource(R.string.version_line, BuildConfig.VERSION_NAME))
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
