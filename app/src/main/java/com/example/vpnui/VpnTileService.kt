package com.example.vpnui

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class VpnTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        VpnController.init(this)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch {
            combine(VpnController.state, VpnController.activeId, AppStore.settings.map { it.language }.distinctUntilChanged()) { a, b, c -> Triple(a, b, c) }
                .collect { refresh() }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
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
        val st = VpnController.state.value
        val turningOn = st != ConnState.ON && st != ConnState.CONNECTING
        if (turningOn && VpnService.prepare(applicationContext) != null) {
            // first run: Android needs the user to confirm the VPN permission, so the app opens once
            val open = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_CONNECT, true)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(open)
            }
        } else {
            VpnController.toggle(applicationContext)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val lang = AppStore.settings.value.language
        val st = VpnController.state.value
        tile.state = if (st == ConnState.ON) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "VPN"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when (st) {
                ConnState.ON -> VpnController.servers.value
                    .firstOrNull { it.id == VpnController.activeId.value }
                    ?.let { splitFlag(it.name).second }
                    ?: Str.t(lang, "state_on")
                ConnState.CONNECTING -> Str.t(lang, "state_connecting")
                ConnState.ERROR -> Str.t(lang, "state_error")
                ConnState.OFF -> Str.t(lang, "state_off")
            }
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_tile)
        tile.updateTile()
    }
}
