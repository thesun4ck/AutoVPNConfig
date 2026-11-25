package com.example.vpnui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/** Autostart after the phone boots, if the user enabled it and the VPN permission is already granted. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        VpnController.init(context)
        if (!AppStore.settings.value.autoStart) return
        if (VpnService.prepare(context) != null) return
        VpnController.connect(context)
    }
}
