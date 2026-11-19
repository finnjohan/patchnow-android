// SPDX-FileCopyrightText: 2026 Johan Finn
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.finnjohan.patchnow.data

import io.github.finnjohan.patchnow.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

class HttpResult(val status: Int, val body: String, val etag: String?)

// Enkel GET. status 0 = ingen anslutning eller tidsgräns. gzip hanteras av HttpURLConnection.
fun httpGet(url: String, headers: Map<String, String> = emptyMap()): HttpResult {
    var c: HttpURLConnection? = null
    return try {
        c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "PatchNow/${BuildConfig.VERSION_NAME} (Android; https://github.com/finnjohan/patchnow-android)")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        val code = c.responseCode
        val body = if (code == 200) c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } else ""
        HttpResult(code, body, c.getHeaderField("ETag"))
    } catch (e: Exception) {
        HttpResult(0, "", null)
    } finally {
        c?.disconnect()
    }
}

fun errorKind(status: Int): String = when (status) {
    0 -> "offline"
    403, 429, 503 -> "limit"
    else -> "server"
}
