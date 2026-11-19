// SPDX-FileCopyrightText: 2026 Johan Finn
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.finnjohan.patchnow.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.finnjohan.patchnow.BuildConfig
import io.github.finnjohan.patchnow.PatchNowApp
import io.github.finnjohan.patchnow.data.Repository
import io.github.finnjohan.patchnow.data.Settings
import io.github.finnjohan.patchnow.work.Notifier
import io.github.finnjohan.patchnow.work.Scheduler
import kotlinx.coroutines.launch

sealed class Screen {
    object List : Screen()
    object Settings : Screen()
    data class Detail(val id: String) : Screen()
}

class MainActivity : ComponentActivity() {

    private lateinit var repo: Repository
    private var screen by mutableStateOf<Screen>(Screen.List)

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Demo-läge för skärmdumpar (bara i debug-bygget, som har testdata i assets)
        val demo = BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)
        repo = if (demo) {
            Repository(this, demo = true).also { r -> if (r.hasDemoData()) r.load() }
        } else {
            (application as PatchNowApp).repo
        }

        if (demo) {
            val theme = intent.getStringExtra("theme")
            if (theme != null) repo.updateSettings { it.copy(theme = theme) }
            when (intent.getStringExtra("page")) {
                "settings" -> screen = Screen.Settings
                "detail" -> {
                    val items = repo.state.value.items
                    val i = intent.getIntExtra("index", 0)
                    if (i in items.indices) screen = Screen.Detail(items[i].id)
                }
            }
            if (intent.getBooleanExtra("simulateNew", false)) repo.simulateNew()
        } else {
            handleIntent(intent)
            if (Build.VERSION.SDK_INT >= 33 && repo.prefs.settings().notifyOn &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            val state by repo.state.collectAsState()
            PatchNowTheme(state.settings.theme) {
                val dark = isDark(state.settings.theme)
                DisposableEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                                         else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                        navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                                             else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    )
                    onDispose { }
                }
                App()
            }
        }
    }

    @Composable
    private fun App() {
        val state by repo.state.collectAsState()
        BackHandler(enabled = screen != Screen.List) { screen = Screen.List }
        when (val s = screen) {
            is Screen.List -> ListScreen(
                state = state,
                onRefresh = { lifecycleScope.launch { repo.refresh(force = true) } },
                onOpen = { id -> screen = Screen.Detail(id) },
                onSettings = { screen = Screen.Settings },
                onHandled = { id, on -> repo.setHandled(id, on) },
                onHide = { v -> repo.muteVendor(v) },
                onUnhide = { v -> repo.unmuteVendor(v) }
            )
            is Screen.Detail -> DetailScreen(
                item = repo.itemById(s.id),
                now = repo.now(),
                onBack = { screen = Screen.List },
                onHandled = { id, on -> screen = Screen.List; repo.setHandled(id, on) }
            )
            is Screen.Settings -> SettingsScreen(
                s = state.settings,
                onBack = { screen = Screen.List },
                onChange = { change -> changeSettings(change) },
                onUnhide = { v -> repo.unmuteVendor(v) },
                onRequestNotifications = {
                    if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            )
        }
    }

    private fun changeSettings(change: (Settings) -> Settings) {
        val before = repo.prefs.settings()
        repo.updateSettings(change)
        val after = repo.prefs.settings()
        if (repo.demo) return
        if (after.bgMinutes != before.bgMinutes) Scheduler.apply(this, after.bgMinutes)
        if (after.windowDays != before.windowDays) lifecycleScope.launch { repo.refresh(force = true) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // Tryck på en notis öppnar sårbarheten
    private fun handleIntent(intent: Intent?) {
        val id = intent?.getStringExtra(Notifier.EXTRA_ITEM) ?: return
        screen = Screen.Detail(id)
    }

    override fun onResume() {
        super.onResume()
        if (!repo.demo) lifecycleScope.launch { repo.refresh(force = false) }
    }
}
