/*
 * IVO Mirror · receba a tela do iPhone no IVO LIVE
 * Copyright (C) 2026 evilgreenIvo — GPL-3.0-or-later (see LICENSE)
 */
package com.evilgreen.airplay

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** "IVO Mirror" in the quick settings: one tap turns the receiver on or off without opening the app. */
class MirrorTile : TileService() {

    override fun onStartListening() {
        paint()
        ReceiverService.onTile = { paint() }
    }

    override fun onStopListening() {
        ReceiverService.onTile = null
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (ReceiverService.running) {
            ReceiverService.stop(this)
        } else {
            try {
                ReceiverService.start(this)
            } catch (_: Exception) {
                // Android refused to start it from here: the app opens and starts it.
                val open = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_START, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(PendingIntent.getActivity(this, 0, open,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                } else {
                    @Suppress("DEPRECATION") startActivityAndCollapse(open)
                }
            }
        }
        qsTile?.let { t -> t.state = if (ReceiverService.running) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE; t.updateTile() }
    }

    private fun paint() {
        val t = qsTile ?: return
        val on = ReceiverService.running
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = getString(when {
            !on -> R.string.phase_off
            ReceiverService.phase == Phase.RECEIVING -> R.string.phase_receiving
            else -> R.string.phase_ready
        })
        t.updateTile()
    }
}
