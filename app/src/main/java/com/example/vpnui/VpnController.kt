package com.example.vpnui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.v2ray.ang.service.TProxyService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.Executors
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ConnState { OFF, CONNECTING, ON, ERROR }

/**
 * Single source of truth for the connection. The UI, the notification and the quick settings tile
 * all talk to this object. The VPN service (XrayVpnService) does the heavy lifting and reports back.
 */
object VpnController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ConnState.OFF)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _servers = MutableStateFlow<List<VpnServer>>(emptyList())
    val servers: StateFlow<List<VpnServer>> = _servers.asStateFlow()

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    private val _connectedSince = MutableStateFlow(0L)
    val connectedSince: StateFlow<Long> = _connectedSince.asStateFlow()

    private val _isWifi = MutableStateFlow(true)
    val isWifi: StateFlow<Boolean> = _isWifi.asStateFlow()

    private val _pinging = MutableStateFlow(false)
    val pinging: StateFlow<Boolean> = _pinging.asStateFlow()

    private val _lastPingAt = MutableStateFlow(0L)
    val lastPingAt: StateFlow<Long> = _lastPingAt.asStateFlow()

    /** Local http proxy of the running core (0 when not running). Used by the IP check. */
    @Volatile var httpPort: Int = 0

    private lateinit var appContext: Context
    private var inited = false
    private var firstPingDone = false
    private var monitorJob: Job? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun init(context: Context) {
        AppStore.init(context)
        if (inited) return
        inited = true
        appContext = context.applicationContext
        _servers.value = loadServers()
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        _isWifi.value = detectWifi(cm)
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { _isWifi.value = detectWifi(cm) }
                    override fun onLost(network: Network) { _isWifi.value = detectWifi(cm) }
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { _isWifi.value = detectWifi(cm) }
                }
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun detectWifi(cm: ConnectivityManager): Boolean {
        var wifi = false
        var cell = false
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cell = true
        }
        return wifi || !cell
    }

    // ---------------------------------------------------------------- servers on disk

    private fun serversFile() = File(appContext.filesDir, "servers.json")

    private fun loadServers(): List<VpnServer> = runCatching {
        val arr = JSONArray(serversFile().readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            VpnServer(o.getString("id"), o.getString("name"), null, o.optString("host"), o.optInt("port", 443), o.getString("raw"))
        }
    }.getOrDefault(emptyList())

    private fun saveServers(list: List<VpnServer>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("name", s.name).put("host", s.host).put("port", s.port).put("raw", s.raw))
        }
        runCatching { serversFile().writeText(arr.toString()) }
    }

    fun refreshSubscription() {
        val url = AppStore.settings.value.subscriptionUrl
        // when the VPN is already up, the download goes through it (helps if the provider site is blocked)
        val proxy = if (_state.value == ConnState.ON) httpPort else 0
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Subscription.fetch(url, appContext, proxy) } }
            result.onSuccess { r ->
                if (r.servers.isEmpty()) {
                    log("error", "log_sub", "d_sub_empty")
                } else {
                    AppStore.update {
                        copy(
                            // without a header the previous (or manually entered) values are kept
                            subExpire = r.info?.expire ?: subExpire,
                            subTotal = r.info?.total ?: subTotal,
                            subUsed = r.info?.used ?: subUsed,
                            subFetchedAt = System.currentTimeMillis()
                        )
                    }
                    log("muted", "log_sub", "d_sub_headers", r.headers.ifBlank { "-" })
                    val inf = r.info
                    if (inf == null) {
                        log("warn", "log_sub", "d_sub_noinfo")
                    } else {
                        val days = if (inf.expire > 0) Math.ceil((inf.expire * 1000 - System.currentTimeMillis()) / 86_400_000.0).toInt().toString() else "-"
                        log("ok", "log_sub", "d_sub_info", days)
                    }
                    applyServers(r.servers)
                }
            }.onFailure { e ->
                log("error", "log_sub", "d_sub_fail", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Loads servers from pasted text (xray JSON, links or base64). Returns how many were found. */
    fun importText(text: String): Int {
        val list = runCatching { SubscriptionParser.parse(text) }.getOrDefault(emptyList())
        if (list.isEmpty()) {
            log("error", "log_sub", "d_sub_empty")
            return 0
        }
        applyServers(list)
        return list.size
    }

    private fun applyServers(list: List<VpnServer>) {
        _servers.value = list
        saveServers(list)
        log("ok", "log_sub", "d_sub_ok", list.size.toString())
        scope.launch { pingAll() }
    }

    // ---------------------------------------------------------------- logs

    @Synchronized
    fun log(kind: String, titleKey: String, detailKey: String = "", vararg args: String) {
        val line = LogLine(timeFmt.format(Date()), kind, titleKey, detailKey, args.toList())
        _logs.update { (listOf(line) + it).take(200) }
    }

    fun clearLogs() { _logs.value = emptyList() }

    // ---------------------------------------------------------------- connect / disconnect

    fun toggle(context: Context) {
        val st = _state.value
        if (st == ConnState.ON || st == ConnState.CONNECTING) disconnect() else connect(context)
    }

    /** The VPN permission must already be granted (the UI asks for it). */
    fun connect(context: Context) {
        init(context)
        val st = _state.value
        if (st == ConnState.ON || st == ConnState.CONNECTING) return
        val app = context.applicationContext
        if (VpnService.prepare(app) != null) {
            _state.value = ConnState.ERROR
            log("error", "log_error", "d_no_permission")
            return
        }
        beginConnecting()
        runCatching {
            ContextCompat.startForegroundService(
                app, Intent(app, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_START)
            )
        }.onFailure { e -> engineFailed("d_engine_error", e.message ?: e.javaClass.simpleName) }
    }

    fun beginConnecting() {
        _state.value = ConnState.CONNECTING
        log("info", "log_connecting")
    }

    fun disconnect() {
        monitorJob?.cancel()
        val was = _state.value
        _activeId.value = null
        _connectedSince.value = 0L
        httpPort = 0
        _state.value = ConnState.OFF
        XrayVpnService.instance?.shutdownEngine()
        if (was != ConnState.OFF) log("muted", "log_disconnected", "d_user")
    }

    // ---------------------------------------------------------------- callbacks from the service

    fun pickTargetForStart(): VpnServer? = pickTarget()

    fun engineStarted(serverId: String) {
        if (_state.value != ConnState.CONNECTING) return
        _activeId.value = serverId
        _connectedSince.value = SystemClock.elapsedRealtime()
        _state.value = ConnState.ON
        val name = _servers.value.firstOrNull { it.id == serverId }?.name?.let { splitFlag(it).second } ?: serverId
        log("ok", "log_connected", "d_server", name)
        startMonitor()
    }

    fun engineFailed(detailKey: String, vararg args: String) {
        monitorJob?.cancel()
        _activeId.value = null
        _connectedSince.value = 0L
        httpPort = 0
        _state.value = ConnState.ERROR
        log("error", "log_error", detailKey, *args)
        XrayVpnService.instance?.shutdownEngine()
    }

    /** The core died by itself. With Kill Switch the tunnel stays up and swallows all traffic. */
    fun engineStopped() {
        if (_state.value != ConnState.ON) return
        monitorJob?.cancel()
        _connectedSince.value = 0L
        _state.value = ConnState.ERROR
        if (AppStore.settings.value.killSwitch) {
            log("error", "log_killswitch", "d_killswitch")
        } else {
            log("error", "log_error", "d_core_stopped")
            _activeId.value = null
            httpPort = 0
            XrayVpnService.instance?.shutdownEngine()
        }
    }

    // ---------------------------------------------------------------- server choice

    private fun candidates(): List<VpnServer> {
        val s = AppStore.settings.value
        val all = _servers.value
        val list = if (!s.autoNetwork) all else {
            val ids = if (_isWifi.value) wifiIdsOf(s, all) else mobileIdsOf(s, all)
            all.filter { it.id in ids }
        }
        return orderServers(list, s.favorites)
    }

    private fun pickTarget(): VpnServer? {
        val s = AppStore.settings.value
        if (!s.autoBest) {
            _servers.value.firstOrNull { it.id == s.selectedId }?.let { return it }
        }
        val c = candidates().ifEmpty { orderServers(_servers.value, s.favorites) }
        if (c.isEmpty()) return null
        if (s.autoBest) {
            val alive = c.filter { it.ping != null }
            if (alive.isEmpty()) return c.first()
            val best = alive.minByOrNull { it.pingOrMax } ?: return c.first()
            val fav = alive.filter { it.id in s.favorites }.minByOrNull { it.pingOrMax }
            // a favorite wins unless it is clearly slower than the best one
            return if (fav != null && fav.pingOrMax <= best.pingOrMax + 25) fav else best
        }
        return c.first()
    }

    /** Call after the user changed selection, lists or auto mode while connected. */
    fun reevaluateNow() = reevaluate("log_switch")

    private fun reevaluate(reasonKey: String) {
        if (_state.value != ConnState.ON) return
        val s = AppStore.settings.value
        val cur = _servers.value.firstOrNull { it.id == _activeId.value }
        var target = pickTarget()
        var reason = reasonKey

        if (cur != null && cur.ping == null && s.failover) {
            val alt = candidates().filter { it.id != cur.id && it.ping != null }.minByOrNull { it.pingOrMax }
            if (alt != null) {
                target = alt
                reason = "log_failover"
            }
        } else if (s.autoBest && cur != null && cur.ping != null && target != null && target.id != cur.id) {
            val stillAllowed = candidates().any { it.id == cur.id }
            // hysteresis: do not hop between servers for a 15 ms gain
            if (stillAllowed && target.pingOrMax + 15 >= cur.pingOrMax) target = cur
        }

        // "Switch on high ping": leave a server that answers slower than 120 ms if a clearly faster one exists
        if (s.highPing && cur != null && cur.ping != null && cur.pingOrMax > 120 && (target == null || target.id == cur.id)) {
            val alt = candidates()
                .filter { it.id != cur.id && it.ping != null && it.pingOrMax < cur.pingOrMax - 15 }
                .minByOrNull { it.pingOrMax }
            if (alt != null) target = alt
        }

        if (target != null && target.id != cur?.id) switchTo(cur, target, reason)
    }

    private fun switchTo(from: VpnServer?, to: VpnServer, reasonKey: String) {
        _activeId.value = to.id
        XrayVpnService.instance?.swapServer(to)
        log("info", reasonKey, "d_switch", from?.let { splitFlag(it.name).second } ?: "-", splitFlag(to.name).second)
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            launch {
                _isWifi.drop(1).collect {
                    if (AppStore.settings.value.autoNetwork) reevaluate("log_net_switch")
                }
            }
            launch {
                watchAfterStart()
                healthLoop()
            }
            while (isActive) {
                delay(AppStore.settings.value.intervalMin * 60_000L)
                pingAll()
                reevaluate("log_switch")
            }
        }
    }

    // ---------------------------------------------------------------- real traffic check

    /** One real request through the running core (local http inbound -> proxy server -> internet). */
    private suspend fun probe(): Long = withContext(Dispatchers.IO) {
        val port = httpPort
        if (port <= 0) return@withContext -1L
        runCatching {
            val t0 = SystemClock.elapsedRealtime()
            val c = URL(XrayConfig.TEST_URL).openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))) as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.instanceFollowRedirects = false
            try {
                if (c.responseCode in 200..399) SystemClock.elapsedRealtime() - t0 else -1L
            } finally {
                c.disconnect()
            }
        }.getOrDefault(-1L)
    }

    /** While connected: checks that internet really works through the server. Two failures in a row = server is dead. */
    private suspend fun healthLoop() {
        delay(10_000)
        var fails = 0
        var healthy: Boolean? = null
        var backoff = 8_000L
        while (_state.value == ConnState.ON) {
            // no network at all (airplane mode, tunnel): do not burn battery on pointless checks
            if (!hasUnderlyingInternet()) {
                delay(20_000)
                continue
            }
            val ms = probe()
            if (ms >= 0) {
                if (healthy != true) log("ok", "log_health", "d_health_ok", ms.toString())
                healthy = true
                fails = 0
                backoff = 8_000L
                delay(60_000)
            } else {
                fails++
                if (healthy != false) log("error", "log_health", "d_health_fail")
                healthy = false
                if (fails >= 2) {
                    fails = 0
                    onActiveServerDead()
                    backoff = (backoff * 2).coerceAtMost(180_000L) // back off instead of hammering
                }
                delay(backoff)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun hasUnderlyingInternet(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@any false
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private suspend fun onActiveServerDead() {
        val s = AppStore.settings.value
        val cur = _servers.value.firstOrNull { it.id == _activeId.value } ?: return
        _servers.update { list -> list.map { if (it.id == cur.id) it.copy(ping = null) else it } }
        if (!s.failover) return
        // a full re-check is expensive: reuse results that are less than a minute old
        if (System.currentTimeMillis() - _lastPingAt.value > 60_000L) pingAll()
        val alt = candidates().filter { it.id != cur.id && it.ping != null }.minByOrNull { it.pingOrMax }
            ?: _servers.value.filter { it.id != cur.id && it.ping != null }.minByOrNull { it.pingOrMax }
        if (alt != null && _state.value == ConnState.ON) switchTo(cur, alt, "log_failover")
    }

    /** [txPackets, txBytes, rxPackets, rxBytes] of the tunnel: tx = apps to network (upload), rx = network to apps (download). */
    fun tunnelStats(): LongArray? = runCatching { TProxyService.TProxyGetStats() }.getOrNull()

    // ---------------------------------------------------------------- ping (real delay through every server)

    fun pingOnce(context: Context) {
        init(context)
        if (firstPingDone) return
        firstPingDone = true
        val s = AppStore.settings.value
        val validUrl = s.subscriptionUrl.startsWith("http://") || s.subscriptionUrl.startsWith("https://")
        val connected = _state.value == ConnState.ON
        // the expiry date and the server list stay fresh without pressing anything
        if (validUrl && System.currentTimeMillis() - s.subFetchedAt > 6 * 3600_000L) refreshSubscription()
        if (!connected) scope.launch { pingAll() }
    }

    fun checkPingNow() {
        scope.launch {
            pingAll()
            reevaluate("log_switch")
        }
    }

    private val pingPool = Executors.newCachedThreadPool()
    private val pingScope = CoroutineScope(SupervisorJob() + pingPool.asCoroutineDispatcher())

    /** Checks every server. Results appear one by one; every server has a hard time limit, so this can never hang. */
    private suspend fun pingAll() {
        val list = _servers.value
        if (list.isEmpty()) {
            log("error", "log_ping", "d_no_servers")
            return
        }
        if (!_pinging.compareAndSet(false, true)) return
        try {
            log("info", "log_ping", "d_ping_start", list.size.toString())
            withContext(Dispatchers.IO) { XrayCore.init(appContext) }
            val gate = Semaphore(3)
            val verbose = AppStore.settings.value.verboseLogs
            var answered = 0
            var best: Int? = null
            coroutineScope {
                list.forEach { s ->
                    launch {
                        gate.withPermit {
                            val ms = measure(s)
                            _servers.update { cur -> cur.map { if (it.id == s.id) it.copy(ping = ms) else it } }
                            if (ms != null) {
                                answered++
                                val b = best
                                if (b == null || ms < b) best = ms
                            }
                            if (verbose) log("muted", "log_ping", "d_ping_one", splitFlag(s.name).second, ms?.let { "$it ms" } ?: "timeout")
                        }
                    }
                }
            }
            _lastPingAt.value = System.currentTimeMillis()
            val b = best
            if (b != null) log("info", "log_ping", "d_ping_summary", answered.toString(), list.size.toString(), b.toString())
            else log("error", "log_ping", "d_ping_none")
        } catch (e: Exception) {
            log("error", "log_ping", "d_ping_error", e.message ?: e.javaClass.simpleName)
        } finally {
            _pinging.value = false
        }
    }

    private suspend fun measure(s: VpnServer): Int? {
        val job = pingScope.async { measureBlocking(s) }
        val r = withTimeoutOrNull(12_000L) { job.await() }
        if (r == null) job.cancel()
        return r
    }

    private fun measureBlocking(s: VpnServer): Int? {
        val cfg = XrayConfig.testConfig(s)
        val udpBased = cfg.contains("\"hysteria\"") || cfg.contains("\"kcp\"")
        if (!udpBased && s.host.isNotEmpty()) {
            // dead servers are dropped in 3 seconds instead of waiting for the long delay test
            val reachable = runCatching {
                Socket().use { it.connect(InetSocketAddress(s.host, s.port), 3000) }
                true
            }.getOrDefault(false)
            if (!reachable) return null
        }
        val ms = XrayCore.measureDelay(cfg, XrayConfig.TEST_URL)
        return if (ms > 0) ms.toInt() else null
    }

    // ---------------------------------------------------------------- no traffic after connect: change server

    /** [txBytes, rxBytes] of the tunnel. */
    private fun trafficNow(): Pair<Long, Long> {
        val st = tunnelStats()
        return if (st != null && st.size >= 4) st[1] to st[3] else 0L to 0L
    }

    /**
     * Right after connecting: if nothing comes back through the tunnel for 5 seconds, switch to the next server at once.
     * Up to 3 servers are tried. If none works, the numbers are written to the log (then the problem is not the server).
     */
    private suspend fun watchAfterStart() {
        if (!AppStore.settings.value.failover) return
        val tried = HashSet<String>()
        var attempt = 0
        while (attempt < 3 && _state.value == ConnState.ON) {
            attempt++
            val cur = _servers.value.firstOrNull { it.id == _activeId.value } ?: return
            tried.add(cur.id)
            val (tx0, rx0) = trafficNow()
            delay(5_000)
            if (_state.value != ConnState.ON) return
            val (tx1, rx1) = trafficNow()
            val sentKb = ((tx1 - tx0).coerceAtLeast(0) / 1024).toString()
            val gotKb = ((rx1 - rx0).coerceAtLeast(0) / 1024).toString()
            val dead = when {
                rx1 - rx0 > 0 -> false
                tx1 - tx0 > 0 -> true      // apps send, nothing comes back
                else -> probe() < 0        // idle phone: ask the core directly
            }
            if (!dead) {
                log("ok", "log_watch", "d_watch_ok", gotKb)
                return
            }
            log("error", "log_watch", "d_watch_dead", sentKb, gotKb)
            _servers.update { list -> list.map { if (it.id == cur.id) it.copy(ping = null) else it } }
            val alt = candidates().firstOrNull { it.id !in tried && it.ping != null }
                ?: candidates().firstOrNull { it.id !in tried }
                ?: _servers.value.firstOrNull { it.id !in tried }
            if (alt == null) {
                log("error", "log_watch", "d_watch_giveup", sentKb, gotKb)
                return
            }
            switchTo(cur, alt, "log_failover")
            delay(1_500)
        }
        if (_state.value == ConnState.ON) {
            val (tx, rx) = trafficNow()
            log("error", "log_watch", "d_watch_giveup", (tx / 1024).toString(), (rx / 1024).toString())
        }
    }
}

data class NetCheck(val ip: String, val country: String, val isp: String, val viaVpn: Boolean)

/** Asks an IP info service. When the VPN is up the request goes through the core's local http proxy. */
suspend fun runNetCheck(): NetCheck? = withContext(Dispatchers.IO) {
    runCatching {
        val port = VpnController.httpPort
        val viaVpn = VpnController.state.value == ConnState.ON && port > 0
        val url = URL("https://ipwho.is/")
        val conn = (if (viaVpn) url.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))) else url.openConnection()) as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        val body = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val j = JSONObject(body)
        NetCheck(
            ip = j.optString("ip", "?"),
            country = j.optString("country", "?"),
            isp = j.optJSONObject("connection")?.optString("isp", "?") ?: "?",
            viaVpn = viaVpn
        )
    }.getOrNull()
}
