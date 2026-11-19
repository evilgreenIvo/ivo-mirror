/*
 * IVO Mirror · receba a tela do iPhone no IVO LIVE
 * Copyright (C) 2026 evilgreenIvo — GPL-3.0-or-later (see LICENSE)
 */
package com.evilgreen.airplay

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** The few choices of the app, kept on the phone. */
class Settings(private val context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("mirror", Context.MODE_PRIVATE)

    /** Qualidade Alta: the iPhone is told about a 2560x1440 screen (standing up: about twice as sharp). */
    var high: Boolean
        get() = sp.getBoolean("high", false)
        set(v) = sp.edit().putBoolean("high", v).apply()

    /**
     * Fixed 16:9 frame: IVO LIVE always gets 1920x1080 (or 1280x720), the iPhone's picture fitted in it
     * over a blurred copy. Turning the iPhone then doesn't restart the picture nor change the source's size.
     */
    var fixedFrame: Boolean
        get() = sp.getBoolean("fixed", true)
        set(v) = sp.edit().putBoolean("fixed", v).apply()

    /** The iPhone has to type [pinCode] the first time (then it's remembered). */
    var pinOn: Boolean
        get() = sp.getBoolean("pin_on", false)
        set(v) = sp.edit().putBoolean("pin_on", v).apply()

    /** The 4-digit code, made once at random. */
    val pinCode: Int
        get() {
            val c = sp.getInt("pin_code", 0)
            if (c in 1000..9999) return c
            val n = 1000 + java.security.SecureRandom().nextInt(9000)
            sp.edit().putInt("pin_code", n).apply()
            return n
        }

    /** A new code: the iPhones that knew the old one have to type the new one. */
    fun newPin() {
        sp.edit().putInt("pin_code", 0).apply()
        File(context.filesDir, "airplay-clients.txt").delete()
    }

    /** Turn the receiver on as soon as the app opens. */
    var autoStart: Boolean
        get() = sp.getBoolean("auto_start", false)
        set(v) = sp.edit().putBoolean("auto_start", v).apply()

    /** When the app last asked GitHub for a newer version (at most twice a day). */
    var updateCheckedAt: Long
        get() = sp.getLong("update_checked", 0L)
        set(v) = sp.edit().putLong("update_checked", v).apply()

    /** The newest version GitHub had, and its page ("" = none newer). */
    var newerVersion: String
        get() = sp.getString("newer_version", "") ?: ""
        set(v) = sp.edit().putString("newer_version", v).apply()
    var newerUrl: String
        get() = sp.getString("newer_url", "") ?: ""
        set(v) = sp.edit().putString("newer_url", v).apply()
}
