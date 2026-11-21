// SPDX-FileCopyrightText: 2026 Johan Finn
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.finnjohan.patchnow.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.finnjohan.patchnow.R
import io.github.finnjohan.patchnow.logic.Item
import io.github.finnjohan.patchnow.logic.title
import io.github.finnjohan.patchnow.ui.MainActivity
import java.util.Locale

object Notifier {
    const val CHANNEL = "critical"
    const val EXTRA_ITEM = "itemId"

    fun ensureChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL, context.getString(R.string.channel_critical), NotificationManager.IMPORTANCE_HIGH)
        ch.description = context.getString(R.string.channel_critical_desc)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun openIntent(context: Context, itemId: String?, code: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (itemId != null) i.putExtra(EXTRA_ITEM, itemId)
        return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun headline(it: Item): String =
        if (it.kind == "certse") "CERT-SE – ${it.name}"
        else title(it).let { t -> if (t == it.id) it.id else "${it.id} – $t" }

    private fun summary(context: Context, it: Item): String {
        val parts = ArrayList<String>()
        if (it.kev != null) parts.add(context.getString(R.string.chip_exploited))
        if (it.cvss >= 0) parts.add("CVSS " + String.format(Locale.ROOT, "%.1f", it.cvss))
        if (it.kev?.ransomware == true) parts.add(context.getString(R.string.chip_ransomware))
        if (it.certse.isNotEmpty()) parts.add("CERT-SE")
        return parts.joinToString(" · ")
    }

    @SuppressLint("MissingPermission")
    fun notifyNew(context: Context, items: List<Item>) {
        if (items.isEmpty() || !canNotify(context)) return
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
        val id: Int
        if (items.size == 1) {
            val it = items[0]
            id = it.id.hashCode()
            b.setContentTitle(headline(it))
                .setContentText(summary(context, it))
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary(context, it) + "\n" + it.desc))
                .setContentIntent(openIntent(context, it.id, id))
        } else {
            id = 1
            val style = NotificationCompat.InboxStyle()
            for (it in items.take(6)) style.addLine(headline(it))
            b.setContentTitle(context.resources.getQuantityString(R.plurals.notify_many, items.size, items.size))
                .setContentText(items.joinToString(", ") { title(it) })
                .setStyle(style)
                .setNumber(items.size)
                .setContentIntent(openIntent(context, null, id))
        }
        NotificationManagerCompat.from(context).notify(id, b.build())
    }

    @SuppressLint("MissingPermission")
    fun notifyTest(context: Context): Boolean {
        if (!canNotify(context)) return false
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(context.getString(R.string.notify_test_title))
            .setContentText(context.getString(R.string.notify_test_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, null, 2))
        NotificationManagerCompat.from(context).notify(2, b.build())
        return true
    }
}
