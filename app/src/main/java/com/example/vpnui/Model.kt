package com.example.vpnui

data class VpnServer(
    val id: String,
    val name: String,
    val ping: Int?,
    val host: String = "",
    val port: Int = 443,
    /** Xray JSON: either a full config from the subscription or a single outbound object. */
    val raw: String = ""
)

val VpnServer.pingOrMax: Int get() = ping ?: Int.MAX_VALUE

data class LogLine(
    val time: String,
    val kind: String,
    val titleKey: String,
    val detailKey: String = "",
    val args: List<String> = emptyList()
)

private val LTE_WORD = Regex("(^|[^a-z])lte([^a-z]|$)")

/** Servers meant for mobile internet are recognised by name, in any letter case: "обход", "LTE", "белые списки". */
fun isBypass(name: String): Boolean {
    val n = name.lowercase()
    return n.contains("обход") ||
        n.contains("белые списки") || n.contains("белый список") ||
        LTE_WORD.containsMatchIn(n)
}

fun wifiIdsOf(s: AppSettings, all: List<VpnServer>): Set<String> =
    s.wifiIds ?: all.filter { !isBypass(it.name) }.map { it.id }.toSet()

fun mobileIdsOf(s: AppSettings, all: List<VpnServer>): Set<String> =
    s.mobileIds ?: all.filter { isBypass(it.name) }.map { it.id }.toSet()

fun orderServers(list: List<VpnServer>, favorites: Set<String>): List<VpnServer> =
    list.sortedWith(
        compareByDescending<VpnServer> { it.id in favorites }
            .thenBy { it.pingOrMax }
            .thenBy { it.name }
    )

/** Splits "🇳🇱 Name" into flag and clean name. Falls back to a generic icon. */
fun splitFlag(name: String): Pair<String, String> {
    if (name.isNotEmpty()) {
        val c1 = name.codePointAt(0)
        val n1 = Character.charCount(c1)
        if (name.length > n1) {
            val c2 = name.codePointAt(n1)
            if (c1 in 0x1F1E6..0x1F1FF && c2 in 0x1F1E6..0x1F1FF) {
                val end = n1 + Character.charCount(c2)
                return name.substring(0, end) to name.substring(end).trim()
            }
        }
    }
    return (if (isBypass(name)) "📶" else "🌐") to name
}
