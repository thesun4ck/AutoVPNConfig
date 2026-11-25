package com.example.vpnui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest

data class SubInfo(val expire: Long, val total: Long, val used: Long)

data class SubFetch(val servers: List<VpnServer>, val info: SubInfo?, val headers: String = "")

object Subscription {

    /** Downloads the subscription. Providers answer differently per client, so several User-Agents are tried. */
    fun fetch(url: String, context: Context, proxyPort: Int = 0): SubFetch {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        var lastError: Exception? = null
        for (ua in listOf("Happ/3.0.0", "v2rayNG/2.0.0", "Incy/1.0")) {
            try {
                val (body, userInfo, headerText) = httpGet(url, ua, androidId, proxyPort)
                val parsed = SubscriptionParser.parse(body)
                if (parsed.isNotEmpty()) {
                    // some providers put the expiry into a fake "server" name instead of the header
                    val (servers, nameInfo) = extractInfoFromNames(parsed)
                    return SubFetch(servers, parseUserInfo(userInfo) ?: nameInfo, headerText)
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("empty")
    }

    private val INFO_WORDS = listOf("остал", "остаток", "expire", "days left", "срок", "до ", "⏳", "📅", "дн.", "дней")
    private val DAYS_RE = Regex("(\\d{1,4})\\s*(дн|дней|день|day|days|д\\.)", RegexOption.IGNORE_CASE)
    private val DATE_DMY = Regex("(\\d{2})\\.(\\d{2})\\.(\\d{4})")
    private val DATE_YMD = Regex("(\\d{4})-(\\d{2})-(\\d{2})")

    /** Fake entries like "⏳ Осталось 25 дн." are removed from the list and turned into the expiry date. */
    private fun extractInfoFromNames(list: List<VpnServer>): Pair<List<VpnServer>, SubInfo?> {
        var info: SubInfo? = null
        val kept = ArrayList<VpnServer>()
        for (s in list) {
            val n = s.name.lowercase()
            val dummyHost = s.host.isEmpty() || s.host == "0.0.0.0" || s.host == "127.0.0.1" || s.host == "localhost"
            val looksLikeInfo = INFO_WORDS.any { n.contains(it) }
            val expire = if (dummyHost && looksLikeInfo) expiryFromText(s.name) else 0L
            if (expire > 0L) {
                if (info == null) info = SubInfo(expire, 0L, 0L)
            } else {
                kept.add(s)
            }
        }
        return (if (kept.isEmpty()) list else kept) to info
    }

    private fun expiryFromText(text: String): Long {
        DATE_DMY.find(text)?.let { m ->
            val (d, mo, y) = m.destructured
            return java.util.GregorianCalendar(y.toInt(), mo.toInt() - 1, d.toInt(), 23, 59).timeInMillis / 1000
        }
        DATE_YMD.find(text)?.let { m ->
            val (y, mo, d) = m.destructured
            return java.util.GregorianCalendar(y.toInt(), mo.toInt() - 1, d.toInt(), 23, 59).timeInMillis / 1000
        }
        DAYS_RE.find(text)?.let { m -> return System.currentTimeMillis() / 1000 + m.groupValues[1].toLong() * 86_400L }
        return 0L
    }

    /** "upload=0; download=123; total=1000; expire=1735689600" */
    private fun parseUserInfo(header: String?): SubInfo? {
        if (header.isNullOrBlank()) return null
        val m = HashMap<String, Long>()
        header.split(";").forEach { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2) kv[1].trim().toDoubleOrNull()?.let { m[kv[0].trim().lowercase()] = it.toLong() }
        }
        val total = m["total"] ?: 0L
        val expire = m["expire"] ?: 0L
        if (total == 0L && expire == 0L) return null
        return SubInfo(expire, total, (m["upload"] ?: 0L) + (m["download"] ?: 0L))
    }

    private fun httpGet(url: String, userAgent: String, hwid: String, proxyPort: Int): Triple<String, String?, String> {
        val u = URL(url)
        val conn = (if (proxyPort > 0) u.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))) else u.openConnection()) as HttpURLConnection
        try {
            conn.connectTimeout = 12_000
            conn.readTimeout = 25_000
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("x-hwid", hwid)
            conn.setRequestProperty("x-device-os", "Android")
            conn.setRequestProperty("x-ver-os", Build.VERSION.RELEASE)
            conn.setRequestProperty("x-device-model", Build.MODEL)
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val skip = setOf("set-cookie", "content-length", "date", "connection", "server", "vary", "transfer-encoding", "cf-ray", "alt-svc")
            val headerText = conn.headerFields.entries
                .filter { it.key != null && it.key.lowercase() !in skip }
                .joinToString("; ") { "${it.key.lowercase()}=${it.value.joinToString(",").take(70)}" }
                .take(500)
            return Triple(body, conn.getHeaderField("subscription-userinfo"), headerText)
        } finally {
            conn.disconnect()
        }
    }
}

object SubscriptionParser {

    fun parse(body: String): List<VpnServer> {
        val text = body.trim().removePrefix("\uFEFF")
        val servers = when {
            text.startsWith("[") -> fromJsonArray(JSONArray(text))
            text.startsWith("{") -> fromJsonObject(JSONObject(text))
            else -> fromLinks(decodeLinks(text))
        }
        return dedupe(servers)
    }

    // ---------------------------------------------------------------- json

    private fun fromJsonArray(arr: JSONArray): List<VpnServer> {
        val out = ArrayList<VpnServer>()
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            when (item) {
                is JSONObject -> fromJsonObject(item).let(out::addAll)
                is String -> parseLink(item.trim())?.let(out::add)
            }
        }
        return out
    }

    private fun fromJsonObject(obj: JSONObject): List<VpnServer> {
        if (!obj.has("outbounds")) return emptyList()
        val outs = obj.optJSONArray("outbounds") ?: return emptyList()
        var proxy: JSONObject? = null
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("tag") == "proxy") { proxy = o; break }
            if (proxy == null && o.optString("protocol") !in setOf("freedom", "blackhole", "dns", "loopback")) proxy = o
        }
        val (host, port) = addressOf(proxy)
        val name = obj.optString("remarks").ifBlank { host.ifBlank { "server" } }
        return listOf(VpnServer(id = "", name = name, ping = null, host = host, port = port, raw = obj.toString()))
    }

    private fun addressOf(outbound: JSONObject?): Pair<String, Int> {
        val st = outbound?.optJSONObject("settings") ?: return "" to 443
        val first = st.optJSONArray("vnext")?.optJSONObject(0)
            ?: st.optJSONArray("servers")?.optJSONObject(0)
            ?: st
        return first.optString("address") to first.optInt("port", 443)
    }

    // ---------------------------------------------------------------- links

    private fun decodeLinks(text: String): String {
        if (text.contains("://")) return text
        return runCatching {
            val cleaned = text.replace('-', '+').replace('_', '/').replace("\\s".toRegex(), "")
            String(Base64.decode(cleaned, Base64.DEFAULT))
        }.getOrDefault(text)
    }

    private fun fromLinks(text: String): List<VpnServer> =
        text.lineSequence().map { it.trim() }.filter { it.contains("://") }.mapNotNull { parseLink(it) }.toList()

    private fun parseLink(link: String): VpnServer? = runCatching {
        when {
            link.startsWith("vless://") -> parseVless(link)
            link.startsWith("trojan://") -> parseTrojan(link)
            link.startsWith("vmess://") -> parseVmess(link)
            link.startsWith("hysteria2://") || link.startsWith("hy2://") -> parseHysteria2(link)
            else -> null
        }
    }.getOrNull()

    private fun q(u: Uri, key: String): String = u.getQueryParameter(key).orEmpty()

    private fun parseVless(link: String): VpnServer {
        val u = Uri.parse(link)
        val host = u.host.orEmpty()
        val port = if (u.port > 0) u.port else 443
        val user = JSONObject()
            .put("id", u.userInfo.orEmpty())
            .put("encryption", q(u, "encryption").ifBlank { "none" })
            .put("flow", q(u, "flow"))
        val outbound = JSONObject()
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(user)))))
            .put("streamSettings", stream(u, host))
        return server(u.fragment ?: host, host, port, outbound)
    }

    private fun parseTrojan(link: String): VpnServer {
        val u = Uri.parse(link)
        val host = u.host.orEmpty()
        val port = if (u.port > 0) u.port else 443
        val outbound = JSONObject()
            .put("protocol", "trojan")
            .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().put("address", host).put("port", port).put("password", u.userInfo.orEmpty()))))
            .put("streamSettings", stream(u, host, defaultSecurity = "tls"))
        return server(u.fragment ?: host, host, port, outbound)
    }

    private fun parseVmess(link: String): VpnServer {
        val json = JSONObject(String(Base64.decode(link.removePrefix("vmess://").trim(), Base64.DEFAULT)))
        val host = json.optString("add")
        val port = json.optString("port").toIntOrNull() ?: 443
        val net = json.optString("net").ifBlank { "tcp" }
        val st = JSONObject().put("network", net)
        val hostHeader = json.optString("host")
        val path = json.optString("path")
        when (net) {
            "ws" -> st.put("wsSettings", JSONObject().put("path", path.ifBlank { "/" }).put("headers", JSONObject().apply { if (hostHeader.isNotBlank()) put("Host", hostHeader) }))
            "grpc" -> st.put("grpcSettings", JSONObject().put("serviceName", path))
        }
        if (json.optString("tls") == "tls") {
            st.put("security", "tls")
            st.put(
                "tlsSettings",
                JSONObject()
                    .put("serverName", json.optString("sni").ifBlank { hostHeader.ifBlank { host } })
                    .put("fingerprint", json.optString("fp").ifBlank { "chrome" })
            )
        }
        val user = JSONObject().put("id", json.optString("id")).put("alterId", json.optString("aid").toIntOrNull() ?: 0).put("security", json.optString("scy").ifBlank { "auto" })
        val outbound = JSONObject()
            .put("protocol", "vmess")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(user)))))
            .put("streamSettings", st)
        return server(json.optString("ps").ifBlank { host }, host, port, outbound)
    }

    /** Best effort: Xray 2.x+ hysteria outbound. Servers delivered as JSON configs do not go through this. */
    private fun parseHysteria2(link: String): VpnServer {
        val u = Uri.parse(link.replaceFirst("hy2://", "hysteria2://"))
        val host = u.host.orEmpty()
        val port = if (u.port > 0) u.port else 443
        val tls = JSONObject()
            .put("serverName", q(u, "sni").ifBlank { host })
            .put("alpn", JSONArray().put("h3"))
        if (q(u, "insecure") == "1") tls.put("allowInsecure", true)
        val st = JSONObject()
            .put("network", "hysteria")
            .put("security", "tls")
            .put("tlsSettings", tls)
            .put("hysteriaSettings", JSONObject().put("version", 2).put("auth", u.userInfo.orEmpty()))
        val outbound = JSONObject()
            .put("protocol", "hysteria")
            .put("settings", JSONObject().put("version", 2).put("address", host).put("port", port))
            .put("streamSettings", st)
        return server(u.fragment ?: host, host, port, outbound)
    }

    private fun stream(u: Uri, host: String, defaultSecurity: String = "none"): JSONObject {
        val type = q(u, "type").ifBlank { "tcp" }
        val security = q(u, "security").ifBlank { defaultSecurity }
        val st = JSONObject().put("network", type).put("security", security)
        val path = q(u, "path")
        val hostHeader = q(u, "host")
        when (type) {
            "ws" -> st.put("wsSettings", JSONObject().put("path", path.ifBlank { "/" }).put("headers", JSONObject().apply { if (hostHeader.isNotBlank()) put("Host", hostHeader) }))
            "grpc" -> st.put("grpcSettings", JSONObject().put("serviceName", q(u, "serviceName")).put("multiMode", q(u, "mode") == "multi"))
            "httpupgrade" -> st.put("httpupgradeSettings", JSONObject().put("path", path.ifBlank { "/" }).put("host", hostHeader))
            "xhttp", "splithttp" -> st.put("xhttpSettings", JSONObject().put("path", path.ifBlank { "/" }).put("host", hostHeader).put("mode", q(u, "mode").ifBlank { "auto" }))
        }
        val fp = q(u, "fp").ifBlank { "chrome" }
        when (security) {
            "tls" -> {
                val tls = JSONObject().put("serverName", q(u, "sni").ifBlank { hostHeader.ifBlank { host } }).put("fingerprint", fp)
                val alpn = q(u, "alpn")
                if (alpn.isNotBlank()) tls.put("alpn", JSONArray(alpn.split(",")))
                if (q(u, "allowInsecure") == "1") tls.put("allowInsecure", true)
                st.put("tlsSettings", tls)
            }
            "reality" -> st.put(
                "realitySettings",
                JSONObject()
                    .put("serverName", q(u, "sni"))
                    .put("fingerprint", fp)
                    .put("publicKey", q(u, "pbk"))
                    .put("shortId", q(u, "sid"))
                    .put("spiderX", q(u, "spx"))
            )
        }
        return st
    }

    private fun server(name: String, host: String, port: Int, outbound: JSONObject): VpnServer {
        outbound.put("tag", "proxy")
        return VpnServer(id = "", name = name, ping = null, host = host, port = port, raw = outbound.toString())
    }

    // ---------------------------------------------------------------- ids

    /** Stable ids (survive a refresh), so favorites and the selected server are kept. */
    private fun dedupe(list: List<VpnServer>): List<VpnServer> {
        val seen = HashMap<String, Int>()
        return list.map { s ->
            val base = sha1("${s.name}|${s.host}|${s.port}").take(10)
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            s.copy(id = if (n == 1) base else "${base}_$n")
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
