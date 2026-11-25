package com.example.vpnui

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the Xray config for the tunnel the same way the proven v2rayNG does it:
 * our own inbounds, DNS and routing; from the subscription we keep ONLY the proxy outbound(s).
 * (Provider configs are made for the Windows TUN mode and their routing/DNS rules do not fit this setup.)
 */
object XrayConfig {
    const val TEST_URL = "https://www.gstatic.com/generate_204"

    private val NOT_PROXY = setOf("freedom", "blackhole", "dns", "loopback")
    private val OWN_TAGS = setOf("direct", "block", "dns-out")

    class Session(
        val socksPort: Int,
        val httpPort: Int,
        val user: String,
        val pass: String
    )

    fun fullConfig(server: VpnServer, s: Session, opts: AppSettings): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))
        root.put(
            "inbounds",
            JSONArray()
                .put(
                    JSONObject()
                        .put("tag", "socks")
                        .put("listen", "127.0.0.1")
                        .put("port", s.socksPort)
                        .put("protocol", "socks")
                        .put(
                            "settings",
                            JSONObject()
                                .put("auth", "password")
                                .put("udp", true)
                                .put("accounts", JSONArray().put(JSONObject().put("user", s.user).put("pass", s.pass)))
                        )
                        .put(
                            "sniffing",
                            JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                        )
                )
                .put(
                    JSONObject()
                        .put("tag", "http-local")
                        .put("listen", "127.0.0.1")
                        .put("port", s.httpPort)
                        .put("protocol", "http")
                        .put("settings", JSONObject().put("allowTransparent", false))
                )
        )

        // proxy outbound(s) from the subscription, then our own helpers
        val provided = proxyOutbounds(server)
        val outs = JSONArray()
        for (i in 0 until provided.length()) {
            val o = provided.optJSONObject(i) ?: continue
            if (o.optString("tag") in OWN_TAGS) continue
            outs.put(o)
        }
        outs.put(JSONObject().put("protocol", "freedom").put("tag", "direct").put("settings", JSONObject().put("domainStrategy", "UseIPv4")))
        outs.put(JSONObject().put("protocol", "blackhole").put("tag", "block"))
        outs.put(JSONObject().put("protocol", "dns").put("tag", "dns-out"))
        root.put("outbounds", outs)

        root.put("dns", dns(opts))
        root.put("routing", routing(opts))
        return root.toString()
    }

    /** Config for the delay test: outbounds only, proxy first (the test uses the first outbound). */
    fun testConfig(server: VpnServer): String {
        val outs = proxyOutbounds(server)
        val ordered = JSONArray()
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("tag") == "proxy") ordered.put(o)
        }
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("tag") != "proxy") ordered.put(o)
        }
        return JSONObject().put("outbounds", ordered).toString()
    }

    /** Outbounds of the server with Windows-only socket settings removed and a guaranteed "proxy" tag. */
    private fun proxyOutbounds(server: VpnServer): JSONArray {
        val j = JSONObject(server.raw)
        val src = j.optJSONArray("outbounds") ?: JSONArray().put(j)
        val out = JSONArray()
        var hasProxy = false
        for (i in 0 until src.length()) {
            val o = src.optJSONObject(i) ?: continue
            val stream = o.optJSONObject("streamSettings")
            val sockopt = stream?.optJSONObject("sockopt")
            if (sockopt != null) {
                sockopt.remove("interface")
                if (sockopt.length() == 0) stream.remove("sockopt")
            }
            if (o.optString("tag") == "proxy") hasProxy = true
            out.put(o)
        }
        if (!hasProxy) {
            for (i in 0 until out.length()) {
                val o = out.optJSONObject(i) ?: continue
                if (o.optString("protocol") !in NOT_PROXY) {
                    o.put("tag", "proxy")
                    break
                }
            }
        }
        return out
    }

    private fun dns(opts: AppSettings): JSONObject {
        val servers = JSONArray()
        if (opts.directLocal) {
            servers.put(JSONObject().put("address", "77.88.8.8").put("domains", JSONArray().put("geosite:category-ru")).put("skipFallback", true))
        }
        if (opts.dnsProtection) {
            servers.put("https://1.1.1.1/dns-query").put("https://8.8.8.8/dns-query")
        } else {
            servers.put("1.1.1.1").put("8.8.8.8")
        }
        return JSONObject().put("tag", "dns-in").put("queryStrategy", "UseIPv4").put("servers", servers)
    }

    private fun routing(opts: AppSettings): JSONObject {
        fun rule(vararg pairs: Pair<String, Any>): JSONObject {
            val o = JSONObject().put("type", "field")
            pairs.forEach { (k, v) -> o.put(k, v) }
            return o
        }
        val rules = JSONArray()
        // DNS packets from the tunnel are answered by the core's own DNS module
        rules.put(rule("inboundTag" to JSONArray().put("socks"), "port" to "53", "outboundTag" to "dns-out"))
        if (opts.directLocal) {
            rules.put(rule("inboundTag" to JSONArray().put("dns-in"), "ip" to JSONArray().put("77.88.8.8"), "outboundTag" to "direct"))
        }
        if (opts.bypassLan) rules.put(rule("ip" to JSONArray().put("geoip:private"), "outboundTag" to "direct"))
        if (opts.directLocal) {
            rules.put(rule("domain" to JSONArray().put("geosite:category-ru"), "outboundTag" to "direct"))
            rules.put(rule("ip" to JSONArray().put("geoip:ru"), "outboundTag" to "direct"))
        }
        rules.put(rule("network" to "tcp,udp", "outboundTag" to "proxy"))
        return JSONObject()
            .put("domainStrategy", if (opts.directLocal) "IPIfNonMatch" else "AsIs")
            .put("rules", rules)
    }
}
