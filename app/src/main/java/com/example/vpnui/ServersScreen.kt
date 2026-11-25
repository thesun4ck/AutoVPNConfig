package com.example.vpnui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServersScreen(
    state: ConnState,
    servers: List<VpnServer>,
    favorites: Set<String>,
    wifiIds: Set<String>,
    mobileIds: Set<String>,
    highlightId: String?,
    autoBest: Boolean,
    since: Long,
    pinging: Boolean,
    lastPingAt: Long,
    haptic: Boolean,
    subExpire: Long,
    subTotal: Long,
    subUsed: Long,
    onConnect: () -> Unit,
    onSetAuto: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onToggleFav: (String, Boolean) -> Unit,
    onPingNow: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val p = LocalPalette.current
    val hapticFeedback = LocalHapticFeedback.current
    var filter by rememberSaveable { mutableStateOf("all") }

    val on = state == ConnState.ON
    val busy = state == ConnState.CONNECTING
    val activeServer = servers.firstOrNull { it.id == highlightId && on }
    val best = servers.filter { it.ping != null }.minByOrNull { it.pingOrMax }
    val visible = servers.filter {
        when (filter) {
            "wifi" -> it.id in wifiIds
            "mobile" -> it.id in mobileIds
            else -> true
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------- power button, status, hint
        Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PowerButton(state, onConnect)
            Spacer(Modifier.height(12.dp))
            val statusColor = statusColor(state)
            Text(
                tr(when (state) {
                    ConnState.OFF -> "state_off"
                    ConnState.CONNECTING -> "state_connecting"
                    ConnState.ON -> "state_on"
                    ConnState.ERROR -> "state_error"
                }),
                color = statusColor, fontSize = 19.sp, fontWeight = FontWeight.Bold
            )
            Text(
                tr(when (state) {
                    ConnState.OFF -> "hint_off"
                    ConnState.CONNECTING -> "hint_connecting"
                    ConnState.ON -> "hint_on"
                    ConnState.ERROR -> "hint_error"
                }),
                color = p.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }

        // ---------- active connection: server, timer, speeds
        if (on) {
            val srv = activeServer
            TelemetryCard(
                flag = srv?.let { splitFlag(it.name).first } ?: "🌐",
                name = srv?.let { splitFlag(it.name).second } ?: "",
                since = since
            )
        }

        // ---------- subscription: days left and traffic
        if (subExpire > 0L || subTotal > 0L) SubscriptionInfoCard(subExpire, subTotal, subUsed)

        // ---------- best server card: AUTO / manual
        VpnCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("best_server"), color = p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    val sub = if (autoBest) {
                        best?.let { tr("mode_best_auto", "${splitFlag(it.name).first} ${splitFlag(it.name).second} (${it.ping} ${tr("unit_ms")})") } ?: tr("mode_none")
                    } else {
                        servers.firstOrNull { it.id == highlightId }?.let { tr("mode_best_manual", "${splitFlag(it.name).first} ${splitFlag(it.name).second}") } ?: tr("mode_pick")
                    }
                    Text(sub, color = p.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                ModeSwitch(autoBest, onSetAuto)
            }
        }

        // ---------- title row with the small ping button
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("servers_title"), color = p.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            PingNowButton(pinging, onPingNow)
            FreshnessText(pinging, lastPingAt, Modifier.weight(1f))
            Text(tr("hold_hint"), color = p.textMuted, fontSize = 11.sp)
        }

        // ---------- filter chips
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChipBtn(tr("filter_all"), filter == "all") { filter = "all" }
            FilterChipBtn(tr("filter_wifi"), filter == "wifi") { filter = "wifi" }
            FilterChipBtn(tr("filter_mobile"), filter == "mobile") { filter = "mobile" }
        }

        // ---------- list or empty state
        if (visible.isEmpty()) {
            VpnCard(padding = 22.dp) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(p.surfaceSubtle), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Language, null, tint = p.textMuted)
                    }
                    Text(tr("empty_title"), color = p.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(tr("empty_text"), color = p.textSecondary, fontSize = 12.5.sp)
                    VpnButton(tr("btn_open_settings"), onOpenSettings, height = 44.dp, radius = 14.dp)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                visible.forEach { server ->
                    val fav = server.id in favorites
                    val picked = highlightId == server.id
                    val (flag, label) = splitFlag(server.name)
                    VpnCard(
                        padding = 0.dp,
                        borderColor = if (picked) Accent else null
                    ) {
                        Row(
                            Modifier.fillMaxWidth().defaultRowHeight()
                                .combinedClickable(
                                    onClick = { onSelect(server.id) },
                                    onLongClick = {
                                        if (haptic) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onToggleFav(server.id, !fav)
                                    }
                                )
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(flag, fontSize = 22.sp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(label, color = p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        server.ping?.let { tr("ping_ms", it) } ?: "—",
                                        color = pingColor(server.ping), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                                    )
                                    if (server.id in wifiIds) Tag(tr("filter_wifi"))
                                    if (server.id in mobileIds) Tag(tr("tag_4g"))
                                }
                            }
                            if (fav) {
                                Icon(Icons.Default.Favorite, tr("cd_fav"), tint = Status.Error, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Box(
                                Modifier.size(24.dp).border(2.dp, if (picked) Accent else p.textMuted, CircleShape)
                                    .background(if (picked) Accent else Color.Transparent, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (picked) Box(Modifier.size(10.dp).background(Color.White, CircleShape))
                            }
                        }
                    }
                }
            }
        }

        // ---------- bottom button
        val (btnText, btnColor) = when (state) {
            ConnState.OFF -> tr("btn_connect") to Status.Disconnected
            ConnState.CONNECTING -> tr("btn_cancel_connect") to Status.Connecting
            ConnState.ON -> tr("btn_disconnect") to Status.Error
            ConnState.ERROR -> tr("btn_retry") to Status.Error
        }
        VpnButton(btnText, onConnect, modifier = Modifier.fillMaxWidth(), container = btnColor, height = 52.dp, radius = 18.dp)

        NetCheckCard(state)
    }
}

private fun Modifier.defaultRowHeight(): Modifier = this.then(Modifier.height(62.dp))

private fun statusColor(state: ConnState): Color = when (state) {
    ConnState.OFF -> Status.Disconnected
    ConnState.CONNECTING -> Status.Connecting
    ConnState.ON -> Status.Connected
    ConnState.ERROR -> Status.Error
}

@Composable
private fun Tag(text: String) {
    val p = LocalPalette.current
    Text(
        text, color = p.textSecondary, fontSize = 10.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(p.surfaceSubtle).padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

@Composable
private fun ModeSwitch(auto: Boolean, onSet: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(p.surfaceSubtle).padding(4.dp)) {
        @Composable
        fun seg(text: String, selected: Boolean, minW: Int, onClick: () -> Unit) {
            Box(
                Modifier.height(40.dp).defaultMinWidth(minW).clip(RoundedCornerShape(13.dp))
                    .background(if (selected) Accent else Color.Transparent)
                    .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text, color = if (selected) Color.White else p.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        seg(tr("tag_auto"), auto, 62) { onSet(true) }
        seg(tr("tag_manual"), !auto, 72) { onSet(false) }
    }
}

private fun Modifier.defaultMinWidth(dp: Int): Modifier = this.then(Modifier.widthIn(min = dp.dp))

// ------------------------------------------------------------------ power button

/**
 * Animation phase 0..1 that only ticks while [active] and only about 30 times per second.
 * (An always-running infinite animation redraws the screen 60 times per second and heats the phone.)
 */
@Composable
private fun rememberPhase(active: Boolean, durationMs: Int): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        if (!active) {
            phase.floatValue = 0f
            return@LaunchedEffect
        }
        val start = SystemClock.uptimeMillis()
        while (true) {
            phase.floatValue = ((SystemClock.uptimeMillis() - start) % durationMs) / durationMs.toFloat()
            delay(33)
        }
    }
    return phase
}

@Composable
private fun PowerButton(state: ConnState, onClick: () -> Unit) {
    val p = LocalPalette.current
    val color = statusColor(state)
    // animations run only in the state that needs them (nothing ticks while disconnected)
    val spin by rememberPhase(state == ConnState.CONNECTING, 1050)
    val pulse by rememberPhase(state == ConnState.ON, 2300)

    Box(Modifier.size(154.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(154.dp)) {
            val stroke = 5.5.dp.toPx()
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = 72.dp.toPx()
            drawCircle(color.copy(alpha = 0.22f), radius = r, center = c, style = Stroke(stroke))
            if (state == ConnState.CONNECTING) {
                drawArc(
                    color = color, startAngle = spin * 360f - 90f, sweepAngle = 88f, useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            } else {
                drawCircle(color, radius = r, center = c, style = Stroke(stroke))
            }
            if (state == ConnState.ON) {
                for (phase in listOf(pulse, (pulse + 0.5f) % 1f)) {
                    val t = min(1f, phase / 0.75f)
                    drawCircle(
                        Status.Connected.copy(alpha = 0.45f * (1f - t)),
                        radius = 77.dp.toPx() * (1f + 0.24f * t), center = c, style = Stroke(2.5.dp.toPx())
                    )
                }
            }
        }

        var core = Modifier.size(134.dp)
        if (p.isLight) core = core.shadow(10.dp, CircleShape, ambientColor = Color(0x3317243A), spotColor = Color(0x3317243A))
        core = core.clip(CircleShape).background(p.powerCoreBg)
        if (p.isGlass) {
            core = core.drawBehind {
                drawCircle(
                    brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.04f), Color.Transparent)),
                    radius = size.minDimension / 2f - 2.dp.toPx()
                )
            }
        }
        core = core.border(1.dp, p.powerCoreBorder, CircleShape).clickable(role = Role.Button, onClick = onClick)
        Box(core, contentAlignment = Alignment.Center) {
            Icon(
                if (state == ConnState.ERROR) Icons.Default.ErrorOutline else Icons.Default.PowerSettingsNew,
                contentDescription = tr(if (state == ConnState.ON || state == ConnState.CONNECTING) "btn_disconnect" else "btn_connect"),
                tint = if (state == ConnState.OFF) p.powerIconIdle else color,
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

// ------------------------------------------------------------------ subscription

private fun fmtSize(bytes: Long, lang: AppLang): String =
    if (bytes >= 1L shl 30) String.format(Locale.US, "%.1f %s", bytes / (1024.0 * 1024 * 1024), Str.t(lang, "size_gb"))
    else String.format(Locale.US, "%.0f %s", bytes / (1024.0 * 1024), Str.t(lang, "size_mb"))

@Composable
private fun SubscriptionInfoCard(expire: Long, total: Long, used: Long) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    val now = remember { System.currentTimeMillis() }
    VpnCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("sub_card_title"), color = p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (expire > 0L) {
                val days = Math.ceil((expire * 1000 - now) / 86_400_000.0).toInt()
                val color = when {
                    days <= 3 -> Status.Error
                    days <= 7 -> Status.Connecting
                    else -> Status.Connected
                }
                Text(
                    if (days <= 0) tr("sub_expired") else tr("sub_days_left", days),
                    color = if (days <= 0) Status.Error else color, fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
            }
        }
        if (expire > 0L) {
            val date = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(expire * 1000))
            Text(tr("sub_until", date), color = p.textSecondary, fontSize = 12.sp)
        }
        if (total > 0L) {
            val fraction = (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.surfaceSubtle)) {
                Box(
                    Modifier.fillMaxWidth(fraction).fillMaxHeight().background(
                        if (fraction > 0.9f) Status.Error else if (fraction > 0.7f) Status.Connecting else Accent
                    )
                )
            }
            Text(tr("sub_traffic", fmtSize(used, lang), fmtSize(total, lang)), color = p.textSecondary, fontSize = 12.sp)
        } else if (used > 0L) {
            Text(tr("sub_traffic_used", fmtSize(used, lang)), color = p.textSecondary, fontSize = 12.sp)
        }
    }
}

// ------------------------------------------------------------------ telemetry

@Composable
private fun TelemetryCard(flag: String, name: String, since: Long) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    var elapsed by remember { mutableLongStateOf(0L) }
    var down by remember { mutableLongStateOf(0L) }
    var up by remember { mutableLongStateOf(0L) }

    // real numbers: counters of the tunnel itself, not of the whole phone
    LaunchedEffect(since) {
        var last = android.os.SystemClock.elapsedRealtime()
        var lastStats = VpnController.tunnelStats()
        elapsed = (last - since) / 1000
        while (true) {
            delay(1000)
            val now = android.os.SystemClock.elapsedRealtime()
            val st = VpnController.tunnelStats()
            val dt = (now - last).coerceAtLeast(1)
            if (st != null && lastStats != null && st.size >= 4 && lastStats.size >= 4) {
                up = ((st[1] - lastStats[1]).coerceAtLeast(0) * 1000 / dt)
                down = ((st[3] - lastStats[3]).coerceAtLeast(0) * 1000 / dt)
            } else {
                up = 0
                down = 0
            }
            lastStats = st
            last = now
            elapsed = (now - since) / 1000
        }
    }

    VpnCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(flag, fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("lbl_active_server"), color = p.textSecondary, fontSize = 11.sp)
                Text(name, color = p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(tr("lbl_time"), color = p.textSecondary, fontSize = 11.sp)
                Text(
                    String.format(Locale.US, "%02d:%02d:%02d", elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60),
                    color = p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SpeedTile(Modifier.weight(1f), tr("lbl_down"), fmtRate(down, lang), Status.Connected, true)
            SpeedTile(Modifier.weight(1f), tr("lbl_up"), fmtRate(up, lang), Status.Disconnected, false)
        }
    }
}

@Composable
private fun SpeedTile(modifier: Modifier, label: String, value: String, color: Color, isDown: Boolean) {
    val p = LocalPalette.current
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(p.surfaceSubtle).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(if (isDown) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward, null, tint = color, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, color = p.textSecondary, fontSize = 11.sp)
            Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
}

private fun fmtRate(bytesPerSec: Long, lang: AppLang): String {
    val bits = bytesPerSec * 8.0
    return if (bits >= 1_000_000) String.format(Locale.US, "%.1f %s", bits / 1_000_000, Str.t(lang, "unit_mbps"))
    else String.format(Locale.US, "%.0f %s", bits / 1000, Str.t(lang, "unit_kbps"))
}

// ------------------------------------------------------------------ ping button + freshness

@Composable
private fun PingNowButton(pinging: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val rot by rememberPhase(pinging, 800)
    // visible icon 32x32, touch area 48x48
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(enabled = !pinging, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(p.surfaceSubtle), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Refresh, tr("cd_ping_now"), tint = Accent,
                modifier = Modifier.size(16.dp).rotate(if (pinging) rot * 360f else 0f)
            )
        }
    }
}

@Composable
private fun FreshnessText(pinging: Boolean, last: Long, modifier: Modifier) {
    val p = LocalPalette.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    val text = when {
        pinging -> tr("ping_checking")
        last == 0L -> ""
        else -> {
            val sec = ((maxOf(now, last) - last) / 1000)
            if (sec < 45) tr("ping_fresh_now") else tr("ping_fresh_min", maxOf(1L, sec / 60).toInt())
        }
    }
    Text(text, color = p.textMuted, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, modifier = modifier, maxLines = 1)
}

// ------------------------------------------------------------------ connection check

@Composable
private fun NetCheckCard(state: ConnState) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<NetCheck?>(null) }
    var failed by remember { mutableStateOf(false) }

    suspend fun check() {
        busy = true
        failed = false
        val r = runNetCheck()
        result = r
        failed = r == null
        busy = false
    }

    // after the tunnel comes up, check the exit address by itself
    LaunchedEffect(state) {
        if (state == ConnState.ON) {
            delay(2500)
            check()
        } else {
            result = null
            failed = false
        }
    }

    VpnCard(padding = 16.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val dot = when {
                state == ConnState.ERROR -> Status.Error
                result?.viaVpn == true -> Status.Connected
                else -> Status.Disconnected
            }
            Box(Modifier.size(10.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(tr("net_title"), color = p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box(
                Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = !busy, role = Role.Button) { scope.launch { check() } }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(tr(if (busy) "net_checking" else "btn_check_short"), color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        val r = result
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CheckCell(Modifier.weight(1f), tr("lbl_ip"), r?.ip ?: "—", mono = true)
            CheckCell(Modifier.weight(1f), tr("lbl_country"), r?.country ?: "—")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CheckCell(Modifier.weight(1f), tr("lbl_isp"), r?.isp ?: "—")
            CheckCell(
                Modifier.weight(1f), tr("lbl_via"),
                if (r == null) "—" else tr(if (r.viaVpn) "via_yes" else "via_no"),
                color = if (r == null) null else if (r.viaVpn) Status.Connected else Status.Error, bold = true
            )
        }
        if (failed) Text(tr("net_failed"), color = Status.Error, fontSize = 12.sp)
    }
}

@Composable
private fun CheckCell(modifier: Modifier, label: String, value: String, mono: Boolean = false, color: Color? = null, bold: Boolean = false) {
    val p = LocalPalette.current
    Column(modifier) {
        Text(label, color = p.textSecondary, fontSize = 11.sp)
        Text(
            value, color = color ?: p.textPrimary, fontSize = 13.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}
