// SPDX-FileCopyrightText: 2026 Johan Finn
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.finnjohan.patchnow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.finnjohan.patchnow.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun Chip(text: String, tint: Color, filled: Boolean = false) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .then(if (filled) Modifier.background(tint, shape) else Modifier.border(1.dp, tint, shape))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(text, color = if (filled) Color.White else tint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SectionTitle(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = color,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp))
}

@Composable
fun SmallText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier)
}

@Composable
fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun SwitchRow(label: String, help: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (help != null) SmallText(help)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun FactRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(key, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(130.dp))
        Text(value, modifier = Modifier.weight(1f))
    }
}

@Composable
fun locale(): Locale {
    val c = LocalConfiguration.current
    return if (c.locales.isEmpty) Locale.getDefault() else c.locales[0]
}

@Composable
fun formatDay(ms: Long): String {
    if (ms <= 0) return ""
    val l = locale()
    val pattern = if (l.language == "sv") "d MMM" else "MMM d"
    return DateTimeFormatter.ofPattern(pattern, l).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())).removeSuffix(".")
}

fun formatClock(ms: Long): String =
    DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@Composable
fun levelName(level: String): String = stringResource(
    when (level) {
        "strict" -> R.string.level_strict
        "broad" -> R.string.level_broad
        else -> R.string.level_normal
    }
)

@Composable
fun platformShort(p: String): String? = when (p) {
    "windows" -> stringResource(R.string.platform_windows)
    "linux" -> stringResource(R.string.platform_linux)
    "virt" -> stringResource(R.string.platform_virt)
    else -> null
}

fun platformLongRes(p: String): Int = when (p) {
    "windows" -> R.string.platformlong_windows
    "linux" -> R.string.platformlong_linux
    "virt" -> R.string.platformlong_virt
    "device" -> R.string.platformlong_device
    "client" -> R.string.platformlong_client
    else -> R.string.platformlong_apps
}

fun platformHelpRes(p: String): Int = when (p) {
    "windows" -> R.string.platformhelp_windows
    "linux" -> R.string.platformhelp_linux
    "virt" -> R.string.platformhelp_virt
    "device" -> R.string.platformhelp_device
    "client" -> R.string.platformhelp_client
    else -> R.string.platformhelp_apps
}

fun cvssText(v: Double): String = String.format(Locale.ROOT, "%.1f", v)
