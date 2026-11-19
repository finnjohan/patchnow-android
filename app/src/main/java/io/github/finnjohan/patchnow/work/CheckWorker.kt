// SPDX-FileCopyrightText: 2026 Johan Finn
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.finnjohan.patchnow.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.finnjohan.patchnow.PatchNowApp

class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as PatchNowApp).repo
        return try {
            repo.refresh(force = false)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
