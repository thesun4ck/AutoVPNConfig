package com.example.vpnui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.v2ray.ang.service.TProxyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Packets path: apps -> TUN -> hev-socks5-tunnel -> local socks (Xray core) -> server.
 * All engine work runs on one single thread, so start, swap and stop never overlap.
 */
class XrayVpnService : VpnService() {
    private val engineThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + engineThread)

    private var tun: ParcelFileDescriptor? = null
    private var session: XrayConfig.Session? = null
    private var observing = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        VpnController.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, Str.t(AppStore.settings.value.language, "notif_channel"), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        if (intent?.action == ACTION_STOP) {
            VpnController.disconnect()
            return START_NOT_STICKY
        }
        startForegroundNow()
        // Started by the system (Always-on VPN) or by us
        if (VpnController.state.value == ConnState.OFF) VpnController.beginConnecting()

        if (!observing) {
            observing = true
            scope.launch {
                combine(VpnController.state, VpnController.activeId, AppStore.settings.map { it.language }.distinctUntilChanged()) { st, id, _ -> st to id }
                    .collect { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) }
            }
        }
        scope.launch { startEngine() }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // the user switched this VPN off in the system settings or another VPN took over
        VpnController.disconnect()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        teardown()
        scope.cancel()
        engineThread.close()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- engine

    private fun startEngine() {
        if (VpnController.state.value != ConnState.CONNECTING) {
            shutdownNow()
            return
        }
        val target = VpnController.pickTargetForStart()
        if (target == null) {
            VpnController.engineFailed("d_no_servers")
            return
        }
        try {
            teardown() // a restart after a kill switch hold begins from a clean state
            XrayCore.init(this)
            val s = XrayConfig.Session(freePort(), freePort(), token(), token())
            session = s

            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(MTU)
                .addAddress(TUN_ADDRESS, 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
            // our own traffic (core, pings, subscription) must not loop back into the tunnel
            runCatching { builder.addDisallowedApplication(packageName) }
            AppStore.settings.value.excludedApps.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }

            val iface = builder.establish() ?: error("VPN interface was not created")
            tun = iface

            val yaml = File(filesDir, "hev-socks5-tunnel.yaml")
            yaml.writeText(hevConfig(s))
            if (!TProxyService.TProxyStartService(yaml.absolutePath, iface.fd)) error("Tunnel did not start")

            XrayCore.onUnexpectedStop = { scope.launch { VpnController.engineStopped() } }
            XrayCore.start(XrayConfig.fullConfig(target, s, AppStore.settings.value))

            if (VpnController.state.value != ConnState.CONNECTING) {
                shutdownNow()
                return
            }
            VpnController.httpPort = s.httpPort
            VpnController.engineStarted(target.id)
        } catch (e: Throwable) {
            teardown()
            VpnController.engineFailed("d_engine_error", e.message ?: e.javaClass.simpleName)
        }
    }

    /** Switches the server without touching the TUN interface. */
    fun swapServer(target: VpnServer) {
        scope.launch {
            val s = session ?: return@launch
            try {
                XrayCore.start(XrayConfig.fullConfig(target, s, AppStore.settings.value))
            } catch (e: Throwable) {
                VpnController.engineStopped()
            }
        }
    }

    fun shutdownEngine() {
        scope.launch { shutdownNow() }
    }

    private fun shutdownNow() {
        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        XrayCore.onUnexpectedStop = null
        runCatching { TProxyService.TProxyStopService() }
        XrayCore.stop()
        runCatching { tun?.close() }
        tun = null
        session = null
    }

    private fun hevConfig(s: XrayConfig.Session): String = buildString {
        appendLine("tunnel:")
        appendLine("  mtu: $MTU")
        appendLine("  ipv4: $TUN_ADDRESS")
        appendLine("socks5:")
        appendLine("  port: ${s.socksPort}")
        appendLine("  address: 127.0.0.1")
        appendLine("  udp: 'udp'")
        appendLine("  username: '${s.user}'")
        appendLine("  password: '${s.pass}'")
        appendLine("misc:")
        appendLine("  tcp-read-write-timeout: 300000")
        appendLine("  udp-read-write-timeout: 60000")
        appendLine("  log-level: warn")
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun token(): String {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- notification

    private fun startForegroundNow() {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    private fun buildNotification(): Notification {
        val lang = AppStore.settings.value.language
        val st = VpnController.state.value
        val server = VpnController.servers.value.firstOrNull { it.id == VpnController.activeId.value }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, XrayVpnService::class.java).setAction(ACTION_STOP),
            flags
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_vpn_tile)
            .setContentTitle(Str.t(lang, if (st == ConnState.ON) "notif_on" else "notif_connecting"))
            .setContentText(server?.let { splitFlag(it.name).second })
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, Str.t(lang, "notif_stop"), stop)
            .build()
    }

    companion object {
        const val CHANNEL = "vpn_status"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.example.vpnui.START"
        const val ACTION_STOP = "com.example.vpnui.STOP"
        private const val MTU = 1500
        private const val TUN_ADDRESS = "172.19.0.1"

        @Volatile
        var instance: XrayVpnService? = null
    }
}
