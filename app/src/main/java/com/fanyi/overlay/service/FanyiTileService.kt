package com.fanyi.overlay.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.fanyi.overlay.MainActivity
import com.fanyi.overlay.R
import com.fanyi.overlay.data.SessionBus
import com.fanyi.overlay.model.RunPhase

class FanyiTileService : TileService() {
    override fun onStartListening() {
        render()
    }

    override fun onClick() {
        val running = CaptureService.hasLiveSession()
        val paused = CaptureService.isPausedNow()
        when {
            running && paused -> CaptureService.instanceAction { it.resumeFromShade() }
            running -> CaptureService.instanceAction { it.pauseFromShade() }
            else -> launchConsent()
        }
        render()
    }

    private fun launchConsent() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(CaptureService.EXTRA_AUTO_START, true)
        }
        val pending = PendingIntent.getActivity(
            this,
            7,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val phase = SessionBus.phaseValue
        val active = phase == RunPhase.LISTENING || phase == RunPhase.STARTING
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.subtitle = when (phase) {
            RunPhase.LISTENING, RunPhase.STARTING -> "翻译中"
            RunPhase.PAUSED -> "已暂停"
            else -> "未开始"
        }
        tile.updateTile()
    }

    companion object {
        fun poke(context: Context) {
            requestListeningState(context, ComponentName(context, FanyiTileService::class.java))
        }
    }
}
