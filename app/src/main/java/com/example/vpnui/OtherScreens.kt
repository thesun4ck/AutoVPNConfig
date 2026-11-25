package com.example.vpnui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.math.roundToInt

private fun upd(block: AppSettings.() -> AppSettings) = AppStore.update(block)

// ------------------------------------------------------------------ logs

private fun logDot(kind: String): Color = when (kind) {
    "ok" -> Status.Connected
    "error" -> Status.Error
    "muted" -> Status.Timeout
    "warn" -> Status.Connecting
    else -> Status.Disconnected
}

@Composable
fun LogsScreen(logs: List<LogLine>) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val lang = LocalLang.current
    Column(Modifier.fillMaxSize().padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (logs.isEmpty()) {
            VpnCard(modifier = Modifier.weight(1f), padding = 30.dp) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.Description, null, tint = p.textMuted, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(tr("logs_empty"), color = p.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(logs) { log ->
                    VpnCard(padding = 14.dp) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = 5.dp).size(10.dp).background(logDot(log.kind), CircleShape))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(tr(log.titleKey), color = p.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Text(log.time, color = p.textMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                                if (log.detailKey.isNotEmpty()) {
                                    Text(tr(log.detailKey, *log.args.toTypedArray()), color = p.textSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        VpnButton(
            tr("logs_copy"),
            onClick = {
                val text = logs.joinToString("\n") { l ->
                    val detail = if (l.detailKey.isEmpty()) "" else Str.t(lang, l.detailKey, *l.args.toTypedArray())
                    "${l.time} | ${Str.t(lang, l.titleKey)} | $detail"
                }
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("logs", text))
                Toast.makeText(context, Str.t(lang, "logs_copied"), Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(), enabled = logs.isNotEmpty(), icon = Icons.Default.ContentCopy, height = 52.dp, radius = 18.dp
        )
    }
}

// ------------------------------------------------------------------ settings

@Composable
fun SettingsScreen(
    s: AppSettings,
    wifiCount: Int,
    mobileCount: Int,
    onWifi: () -> Unit,
    onMobile: () -> Unit,
    onApps: () -> Unit,
    onPhoto: () -> Unit,
    onGif: () -> Unit,
    onAbout: () -> Unit
) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. subscription
        SectionLabel(1, tr("sec_sub"))
        SubscriptionCard(s)

        // 2. automatic choice
        SectionLabel(2, tr("sec_auto"))
        VpnCard(padding = 10.dp) {
            SettingSwitchRow(tr("sw_best"), tr("sw_best_sub"), s.autoBest) { v ->
                upd { copy(autoBest = v) }
                VpnController.reevaluateNow()
            }
            SettingSwitchRow(tr("sw_highping"), tr("sw_highping_sub"), s.highPing) { v -> upd { copy(highPing = v) } }
            SettingSwitchRow(tr("sw_failover"), tr("sw_failover_sub"), s.failover) { v -> upd { copy(failover = v) } }
            Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("interval_title"), color = p.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 30).forEach { v ->
                        val selected = s.intervalMin == v
                        Box(
                            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp))
                                .background(if (selected && s.autoBest) Accent else p.surfaceSubtle)
                                .border(1.dp, if (selected && s.autoBest) Accent else p.cardBorder, RoundedCornerShape(14.dp))
                                .clickable(enabled = s.autoBest, role = Role.Button) { upd { copy(intervalMin = v) } },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                tr("interval_min", v),
                                color = if (selected && s.autoBest) Color.White else if (s.autoBest) p.textPrimary else p.textMuted,
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                VpnButton(tr("btn_ping_now"), { VpnController.checkPingNow() }, Modifier.fillMaxWidth(), BtnStyle.SUBTLE)
            }
        }

        // 3. network
        SectionLabel(3, tr("sec_net"))
        VpnCard(padding = 10.dp) {
            SettingNavRow(tr("row_wifi"), tr("selected_count", wifiCount), onWifi)
            SettingNavRow(tr("row_mobile"), tr("selected_count", mobileCount), onMobile)
            SettingSwitchRow(tr("sw_autonet"), tr("sw_autonet_sub"), s.autoNetwork) { v ->
                upd { copy(autoNetwork = v) }
                VpnController.reevaluateNow()
            }
        }

        // 4. routing
        SectionLabel(4, tr("sec_routing"))
        VpnCard(padding = 10.dp) {
            SettingNavRow(tr("row_apps"), tr("selected_count", s.excludedApps.size), onApps)
            SettingSwitchRow(tr("sw_lan"), tr("sw_lan_sub"), s.bypassLan) { v -> upd { copy(bypassLan = v) } }
            SettingSwitchRow(tr("sw_directlocal"), tr("sw_directlocal_sub"), s.directLocal) { v -> upd { copy(directLocal = v) } }
        }

        // 5. security
        SectionLabel(5, tr("sec_security"))
        VpnCard(padding = 10.dp) {
            SettingSwitchRow(tr("sw_kill"), tr("sw_kill_sub"), s.killSwitch) { v -> upd { copy(killSwitch = v) } }
            SettingSwitchRow(tr("sw_dns"), tr("sw_dns_sub"), s.dnsProtection) { v -> upd { copy(dnsProtection = v) } }
        }

        // 6. quick access
        SectionLabel(6, tr("sec_quick"))
        QuickAccessCard(s)

        // 7. appearance: exactly three themes
        SectionLabel(7, tr("sec_look"))
        VpnCard(padding = 14.dp) {
            Text(tr("theme_count"), color = p.textMuted, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeOption(Modifier.weight(1f), AppThemeMode.LIGHT, tr("theme_light"), s.theme)
                ThemeOption(Modifier.weight(1f), AppThemeMode.DARK, tr("theme_dark"), s.theme)
                ThemeOption(Modifier.weight(1f), AppThemeMode.GLASS, tr("theme_glass"), s.theme)
            }
            SettingNavRow(tr("row_lang"), s.language.title) {
                upd { copy(language = if (language == AppLang.RU) AppLang.EN else AppLang.RU) }
            }
        }

        // 8. custom background (liquid glass only)
        SectionLabel(8, tr("sec_bg"))
        VpnCard(padding = 16.dp) {
            Text(tr("bg_for_glass"), color = p.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VpnButton(tr("btn_photo"), onPhoto, Modifier.weight(1f), BtnStyle.SUBTLE, icon = Icons.Default.Image, radius = 14.dp)
                VpnButton(tr("btn_gif"), onGif, Modifier.weight(1f), BtnStyle.SUBTLE, icon = Icons.Default.Gif, radius = 14.dp)
            }
            val sliderColors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = p.surfaceSubtle)
            Row(Modifier.fillMaxWidth()) {
                Text(tr("bg_blur"), color = p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("${s.bgBlur.roundToInt()} px", color = p.textSecondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
            Slider(value = s.bgBlur, onValueChange = { v -> upd { copy(bgBlur = v) } }, valueRange = 0f..32f, colors = sliderColors)
            if (Build.VERSION.SDK_INT < 31) Text(tr("bg_blur_note"), color = p.textMuted, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth()) {
                Text(tr("bg_dim"), color = p.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("${(s.bgDim * 100).roundToInt()}%", color = p.textSecondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
            Slider(value = s.bgDim, onValueChange = { v -> upd { copy(bgDim = v) } }, valueRange = 0f..0.8f, colors = sliderColors)
            VpnButton(tr("btn_reset"), { upd { copy(bgUri = null, bgBlur = 18f, bgDim = 0.28f) } }, Modifier.fillMaxWidth(), BtnStyle.SUBTLE, radius = 14.dp)
        }

        // 9. more
        SectionLabel(9, tr("sec_more"))
        VpnCard(padding = 10.dp) {
            SettingSwitchRow(tr("sw_verbose"), tr("sw_verbose_sub"), s.verboseLogs) { v -> upd { copy(verboseLogs = v) } }
            SettingSwitchRow(tr("sw_haptic"), tr("sw_haptic_sub"), s.haptic) { v -> upd { copy(haptic = v) } }
            SettingNavRow(tr("row_about"), tr("version", "1.1.0"), onAbout)
        }
    }
}

@Composable
private fun ThemeOption(modifier: Modifier, mode: AppThemeMode, label: String, current: AppThemeMode) {
    val p = LocalPalette.current
    val selected = mode == current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.defaultHeight().clip(shape)
            .background(if (selected) Accent.copy(alpha = 0.14f) else p.surfaceSubtle)
            .border(if (selected) 2.dp else 1.dp, if (selected) Accent else p.cardBorder, shape)
            .clickable(role = Role.RadioButton) { upd { copy(theme = mode) } }
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val previewShape = RoundedCornerShape(12.dp)
        when (mode) {
            AppThemeMode.LIGHT -> Box(Modifier.size(40.dp).clip(previewShape).background(Color(0xFFF3F7FD)).border(1.dp, Color(0xFFCBD8EA), previewShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(20.dp).background(Accent, CircleShape))
            }
            AppThemeMode.DARK -> Box(Modifier.size(40.dp).clip(previewShape).background(Color(0xFF0C1728)).border(1.dp, Color(0xFF2B3E5A), previewShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(20.dp).background(Color(0xFF4E8FFF), CircleShape))
            }
            AppThemeMode.GLASS -> Box(
                Modifier.size(40.dp).clip(previewShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF0D1E55), Color(0xFF3E186F))))
                    .border(1.dp, Color.White.copy(alpha = 0.35f), previewShape),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(width = 24.dp, height = 16.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.22f)))
            }
        }
        Text(label, color = p.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2)
    }
}

private fun Modifier.defaultHeight(): Modifier = this.then(Modifier.height(96.dp))

@Composable
private fun SubscriptionCard(s: AppSettings) {
    val url = s.subscriptionUrl
    var showDays by remember { mutableStateOf(false) }
    val p = LocalPalette.current
    val context = LocalContext.current
    val lang = LocalLang.current
    val valid = url.startsWith("http://") || url.startsWith("https://")
    val showError = url.isNotEmpty() && !valid
    VpnCard(padding = 16.dp) {
        OutlinedTextField(
            value = url,
            onValueChange = { v -> upd { copy(subscriptionUrl = v.trim()) } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = showError,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.5.sp, color = p.textPrimary),
            placeholder = { Text(tr("sub_placeholder"), color = p.textMuted, fontSize = 13.5.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent, unfocusedBorderColor = p.cardBorder, errorBorderColor = Status.Error,
                focusedContainerColor = p.inputBg, unfocusedContainerColor = p.inputBg, errorContainerColor = p.inputBg,
                focusedTextColor = p.textPrimary, unfocusedTextColor = p.textPrimary, cursorColor = Accent
            )
        )
        if (showError) Text(tr("sub_invalid"), color = Status.Error, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        else Text(tr("sub_supported"), color = p.textSecondary, fontSize = 12.sp)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VpnButton(
                tr("btn_paste"),
                onClick = {
                    val t = context.getSystemService(ClipboardManager::class.java).primaryClip
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    if (t != null) {
                        if (t.startsWith("http://") || t.startsWith("https://")) {
                            upd { copy(subscriptionUrl = t.lineSequence().first().trim()) }
                        } else {
                            val n = VpnController.importText(t)
                            val msg = if (n > 0) Str.t(lang, "d_sub_ok", n.toString()) else Str.t(lang, "d_sub_empty")
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.weight(1f), style = BtnStyle.SUBTLE, icon = Icons.Default.ContentPaste, radius = 14.dp
            )
            VpnButton(
                tr("btn_scan"),
                onClick = { scanQr(context) { v -> upd { copy(subscriptionUrl = v.trim()) } } },
                modifier = Modifier.weight(1f), style = BtnStyle.SUBTLE, icon = Icons.Default.QrCodeScanner, radius = 14.dp
            )
        }
        Text(tr("sub_hint"), color = p.textMuted, fontSize = 11.sp)
        VpnButton(tr("btn_refresh"), { VpnController.refreshSubscription() }, Modifier.fillMaxWidth(), enabled = valid, icon = Icons.Default.Refresh)
        if (s.subFetchedAt > 0L || s.subExpire > 0L) {
            val days = if (s.subExpire > 0L) Math.ceil((s.subExpire * 1000 - System.currentTimeMillis()) / 86_400_000.0).toInt() else null
            Text(
                if (days != null) (if (days <= 0) tr("sub_expired") else tr("sub_days_left", days)) else tr("sub_noinfo_hint"),
                color = if (days != null && days <= 3) Status.Error else p.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium
            )
        }
        VpnButton(tr("sub_manual"), { showDays = true }, Modifier.fillMaxWidth(), BtnStyle.SUBTLE, height = 44.dp, radius = 14.dp)
    }
    if (showDays) {
        DaysDialog(
            onDismiss = { showDays = false },
            onSave = { d ->
                val now = System.currentTimeMillis()
                upd { copy(subExpire = now / 1000 + d * 86_400L, subFetchedAt = if (subFetchedAt == 0L) now else subFetchedAt) }
            }
        )
    }
}

private fun scanQr(context: Context, onResult: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { code -> code.rawValue?.let(onResult) }
        .addOnFailureListener {
            Toast.makeText(context, Str.t(AppStore.settings.value.language, "scan_failed"), Toast.LENGTH_SHORT).show()
        }
}

@Composable
private fun QuickAccessCard(s: AppSettings) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val lang = LocalLang.current
    VpnCard(padding = 10.dp) {
        SettingSwitchRow(tr("sw_autostart"), tr("sw_autostart_sub"), s.autoStart) { v -> upd { copy(autoStart = v) } }
        Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("quick_text"), color = p.textSecondary, fontSize = 12.sp)
            if (Build.VERSION.SDK_INT >= 33) {
                VpnButton(
                    tr("btn_add_tile"),
                    onClick = {
                        val sbm = context.getSystemService(android.app.StatusBarManager::class.java)
                        sbm.requestAddTileService(
                            android.content.ComponentName(context, VpnTileService::class.java),
                            "VPN",
                            android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_vpn_tile),
                            context.mainExecutor
                        ) { result ->
                            val key = when (result) {
                                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "tile_added"
                                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "tile_exists"
                                else -> null
                            }
                            key?.let { Toast.makeText(context, Str.t(lang, it), Toast.LENGTH_SHORT).show() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), style = BtnStyle.SUBTLE, icon = Icons.Default.Add
                )
            }
            Text(tr("quick_manual"), color = p.textMuted, fontSize = 11.sp)
        }
    }
}
