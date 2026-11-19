// SPDX-FileCopyrightText: 2026 Johan Finn
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.finnjohan.patchnow.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

// Bakgrundskollen körs av WorkManager, även när appen är stängd.
object Scheduler {
    private const val NAME = "patchnow-check"

    fun apply(context: Context, minutes: Int) {
        val wm = WorkManager.getInstance(context)
        if (minutes <= 0) {
            wm.cancelUniqueWork(NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<CheckWorker>(minutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
