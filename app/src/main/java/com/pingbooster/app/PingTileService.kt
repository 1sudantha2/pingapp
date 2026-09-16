package com.pingbooster.app

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * Quick Settings tile: start and stop the boost straight from the control panel,
 * without opening the app.
 */
class PingTileService : TileService() {

    companion object {
        /** Asks the system to re-read this tile (cheap, only called on state changes). */
        fun requestRefresh(context: Context) {
            try {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, PingTileService::class.java)
                )
            } catch (_: Throwable) {
                // Tile not added yet, or the service is unavailable: nothing to refresh.
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        render(Booster.isRunning(this))
    }

    override fun onTileAdded() {
        super.onTileAdded()
        render(Booster.isRunning(this))
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { toggle() }
        } else {
            toggle()
        }
    }

    private fun toggle() {
        val nowRunning = Booster.toggle(this)
        // Immediate feedback; the service re-renders the tile once it is really up.
        render(nowRunning)
        Toast.makeText(
            this,
            if (nowRunning) R.string.tile_toast_on else R.string.tile_toast_off,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun render(running: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_booster)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val latency = StatusBus.latencyMs
            val detail = when {
                !running -> getString(R.string.tile_subtitle_idle)
                latency > 0 -> getString(R.string.tile_subtitle_running, latency)
                else -> getString(R.string.tile_subtitle_waiting)
            }
            tile.subtitle = detail
            tile.contentDescription = detail
            tile.stateDescription = detail
        }
        tile.updateTile()
    }
}
