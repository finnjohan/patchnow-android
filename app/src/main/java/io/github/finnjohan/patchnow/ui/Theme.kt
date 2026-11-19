// SPDX-FileCopyrightText: 2026 Johan Finn
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.finnjohan.patchnow.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

class AppColors(val critical: Color, val watch: Color, val positive: Color, val dark: Boolean)

val LocalAppColors = staticCompositionLocalOf { AppColors(Color(0xFFC7162B), Color(0xFFB45F06), Color(0xFF0E8420), false) }

private val LightScheme = lightColorScheme(
    primary = Color(0xFFC7162B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF8DADD),
    onPrimaryContainer = Color(0xFF410009),
    secondary = Color(0xFF5E5E5E),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF2F2F2)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFF0605A),
    onPrimary = Color(0xFF3B0006),
    primaryContainer = Color(0xFF5C1219),
    onPrimaryContainer = Color(0xFFFFDAD8),
    secondary = Color(0xFFBDBDBD),
    background = Color(0xFF111111),
    surface = Color(0xFF111111),
    surfaceVariant = Color(0xFF242424)
)

@Composable
fun isDark(theme: String): Boolean = when (theme) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun PatchNowTheme(theme: String, content: @Composable () -> Unit) {
    val dark = isDark(theme)
    val colors = if (dark) AppColors(Color(0xFFF0605A), Color(0xFFF5B041), Color(0xFF5CC46B), true)
                 else AppColors(Color(0xFFC7162B), Color(0xFFB45F06), Color(0xFF0E8420), false)
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
