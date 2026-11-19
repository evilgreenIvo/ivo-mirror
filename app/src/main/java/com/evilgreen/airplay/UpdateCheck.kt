/*
 * IVO Mirror · receba a tela do iPhone no IVO LIVE
 * Copyright (C) 2026 evilgreenIvo — GPL-3.0-or-later (see LICENSE)
 */
package com.evilgreen.airplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks GitHub (at most twice a day) whether there's a newer IVO Mirror; the screen then shows a card
 * that opens the release page. Nothing is downloaded by the app itself.
 */
object UpdateCheck {
    private const val LATEST = "https://api.github.com/repos/evilgreenIvo/ivo-mirror/releases/latest"
    private const val EVERY_MS = 12 * 60 * 60 * 1000L

    /** [done] runs on the main thread when the answer changed what the screen should show. */
    fun maybeCheck(context: Context, done: () -> Unit) {
        val s = Settings(context.applicationContext)
        val now = System.currentTimeMillis()
        if (now - s.updateCheckedAt < EVERY_MS) return
        s.updateCheckedAt = now
        Thread({
            try {
                val c = URL(LATEST).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "IVO-Mirror")
                if (c.responseCode != 200) return@Thread
                val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val tag = json.optString("tag_name").removePrefix("v")
                val url = json.optString("html_url")
                val newer = isNewer(tag, BuildConfig.VERSION_NAME)
                val was = s.newerVersion
                s.newerVersion = if (newer) tag else ""
                s.newerUrl = if (newer) url else ""
                if (was != s.newerVersion) {
                    if (newer) LogBook.add("Versão nova no GitHub: $tag")
                    Handler(Looper.getMainLooper()).post(done)
                }
            } catch (_: Exception) {
                // No internet or GitHub busy: try again next time.
                s.updateCheckedAt = 0L
            }
        }, "IvoMirror-update").apply { isDaemon = true; start() }
    }

    /** "0.8.1" newer than "0.8.0"? (numbers compared one by one; anything else is ignored) */
    fun isNewer(theirs: String, ours: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(theirs)
        val b = parts(ours)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
