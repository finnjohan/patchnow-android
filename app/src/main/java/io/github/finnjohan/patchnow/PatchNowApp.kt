// SPDX-FileCopyrightText: 2026 Johan Finn
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.finnjohan.patchnow

import android.app.Application
import io.github.finnjohan.patchnow.data.Repository
import io.github.finnjohan.patchnow.work.Notifier
import io.github.finnjohan.patchnow.work.Scheduler

class PatchNowApp : Application() {
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
        repo = Repository(this, demo = false)
        repo.load()
        Scheduler.apply(this, repo.prefs.settings().bgMinutes)
    }
}
