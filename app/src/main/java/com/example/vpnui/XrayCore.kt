package com.example.vpnui

import android.content.Context
import android.provider.Settings
import android.util.Base64
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File

object XrayCore {
    private var inited = false
    private var controller: CoreController? = null
    @Volatile private var gen = 0
    @Volatile private var stopping = false

    /** Called when the core dies by itself (not through stop()). */
    @Volatile var onUnexpectedStop: (() -> Unit)? = null

    private fun newHandler(myGen: Int) = object : CoreCallbackHandler {
        override fun onEmitStatus(code: Long, message: String?): Long = 0L

        override fun shutdown(): Long {
            if (myGen == gen && !stopping) onUnexpectedStop?.invoke()
            return 0L
        }

        override fun startup(): Long = 0L
    }

    @Synchronized
    fun init(context: Context) {
        if (inited) return
        val app = context.applicationContext
        val dir = app.getDir("assets", Context.MODE_PRIVATE)
        val updated = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        // geoip.dat and geosite.dat come bundled inside libv2ray.aar; the core wants them as real files
        for (name in listOf("geoip.dat", "geosite.dat")) {
            val target = File(dir, name)
            if (!target.exists() || target.lastModified() < updated) {
                app.assets.open(name).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        Seq.setContext(app)
        val androidId = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val key = Base64.encodeToString(androidId.toByteArray().copyOf(32), Base64.NO_PADDING or Base64.URL_SAFE or Base64.NO_WRAP)
        Libv2ray.initCoreEnv(dir.absolutePath, key)
        inited = true
    }

    @Synchronized
    fun start(config: String) {
        stop()
        stopping = false
        val c = Libv2ray.newCoreController(newHandler(++gen))
        // tun fd 0: packets arrive through the local socks inbound (hev-socks5-tunnel does the TUN part)
        c.startLoop(config, 0)
        if (!c.isRunning) error("Core failed to start")
        controller = c
    }

    @Synchronized
    fun stop() {
        val c = controller ?: return
        stopping = true
        gen++
        controller = null
        runCatching { c.stopLoop() }
    }

    fun isRunning(): Boolean = controller?.isRunning ?: false

    /** Real delay through the outbound in ms, or -1 on failure. */
    fun measureDelay(config: String, url: String): Long =
        runCatching { Libv2ray.measureOutboundDelay(config, url) }.getOrDefault(-1L)
}
