package com.snipnotes.app

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: tap to pause/resume automatic capture. */
class CaptureTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        Prefs.setAutoCapture(this, !Prefs.autoCapture(this))
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = Prefs.autoCapture(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (android.os.Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) "On" else "Paused"
        tile.updateTile()
    }
}
