package com.example.vpnui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer

const val EXTRA_CONNECT = "com.example.vpnui.EXTRA_CONNECT"

private enum class AppTab { SERVERS, LOGS, SETTINGS }

private fun upd(block: AppSettings.() -> AppSettings) = AppStore.update(block)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        VpnController.init(this)
        setContent { VpnApp() }
    }
}

private fun setBackground(context: Context, uri: Uri, gif: Boolean) {
    val resolver = context.contentResolver
    runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    AppStore.settings.value.bgUri?.let { old ->
        if (old != uri.toString()) {
            runCatching { resolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }
    // the custom background belongs to the liquid glass theme, so switch to it
    upd { copy(bgUri = uri.toString(), bgIsGif = gif, theme = AppThemeMode.GLASS) }
}

@Composable
private fun VpnApp() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val s by AppStore.settings.collectAsState()
    val connState by VpnController.state.collectAsState()
    val servers by VpnController.servers.collectAsState()
    val activeId by VpnController.activeId.collectAsState()
    val logs by VpnController.logs.collectAsState()
    val since by VpnController.connectedSince.collectAsState()
    val pinging by VpnController.pinging.collectAsState()
    val lastPingAt by VpnController.lastPingAt.collectAsState()

    var tab by rememberSaveable { mutableStateOf(AppTab.SERVERS) }
    var showWifi by rememberSaveable { mutableStateOf(false) }
    var showMobile by rememberSaveable { mutableStateOf(false) }
    var showApps by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }

    val theme = s.theme
    val palette = remember(theme) { paletteFor(theme) }

    DisposableEffect(theme) {
        val style = if (theme == AppThemeMode.LIGHT) {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose { }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        VpnController.pingOnce(context)
    }

    BackHandler(enabled = tab != AppTab.SERVERS) { tab = AppTab.SERVERS }

    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) VpnController.connect(context)
    }
    fun connectWithPermission() {
        val ask = VpnService.prepare(context)
        if (ask == null) VpnController.connect(context) else vpnPermission.launch(ask)
    }
    fun toggleVpn() {
        val st = VpnController.state.value
        if (st == ConnState.ON || st == ConnState.CONNECTING) VpnController.disconnect() else connectWithPermission()
    }

    // the quick settings tile opens the app once to get the VPN permission
    DisposableEffect(Unit) {
        val listener = Consumer<Intent> { i ->
            if (i.getBooleanExtra(EXTRA_CONNECT, false)) {
                i.removeExtra(EXTRA_CONNECT)
                connectWithPermission()
            }
        }
        if (activity.intent.getBooleanExtra(EXTRA_CONNECT, false)) {
            activity.intent.removeExtra(EXTRA_CONNECT)
            connectWithPermission()
        }
        activity.addOnNewIntentListener(listener)
        onDispose { activity.removeOnNewIntentListener(listener) }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) setBackground(context, uri, false)
    }
    val gifPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) setBackground(context, uri, true)
    }

    val scheme = if (theme == AppThemeMode.LIGHT) {
        lightColorScheme(primary = Accent, background = Color.Transparent, surface = palette.cardBg, onBackground = palette.textPrimary, onSurface = palette.textPrimary)
    } else {
        darkColorScheme(primary = Accent, background = Color.Transparent, surface = palette.cardBg, onBackground = palette.textPrimary, onSurface = palette.textPrimary)
    }

    CompositionLocalProvider(LocalLang provides s.language, LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme) {
            Box(Modifier.fillMaxSize()) {
                AppBackdrop(theme, s.bgUri, s.bgIsGif, s.bgBlur, s.bgDim) {
                    VpnController.log("error", "log_bg_failed", "d_bg_failed")
                    upd { copy(bgUri = null) }
                }
                Scaffold(
                    containerColor = Color.Transparent,
                    bottomBar = { AppBottomBar(tab) { tab = it } }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
                        AppHeader(tab) { tab = AppTab.SETTINGS }
                        when (tab) {
                            AppTab.SERVERS -> ServersScreen(
                                state = connState,
                                servers = orderServers(servers, s.favorites),
                                favorites = s.favorites,
                                wifiIds = wifiIdsOf(s, servers),
                                mobileIds = mobileIdsOf(s, servers),
                                highlightId = if (s.autoBest) activeId else s.selectedId,
                                autoBest = s.autoBest,
                                since = since,
                                pinging = pinging,
                                lastPingAt = lastPingAt,
                                haptic = s.haptic,
                                subExpire = s.subExpire,
                                subTotal = s.subTotal,
                                subUsed = s.subUsed,
                                onConnect = { toggleVpn() },
                                onSetAuto = { v ->
                                    upd { copy(autoBest = v) }
                                    VpnController.reevaluateNow()
                                },
                                onSelect = { id ->
                                    upd { copy(selectedId = id, autoBest = false) }
                                    VpnController.reevaluateNow()
                                },
                                onToggleFav = { id, fav ->
                                    upd { copy(favorites = if (fav) favorites + id else favorites - id) }
                                    val name = servers.firstOrNull { it.id == id }?.let { splitFlag(it.name).second } ?: id
                                    Toast.makeText(
                                        context,
                                        Str.t(s.language, if (fav) "fav_added" else "fav_removed", name),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onPingNow = { VpnController.checkPingNow() },
                                onOpenSettings = { tab = AppTab.SETTINGS }
                            )
                            AppTab.LOGS -> LogsScreen(logs)
                            AppTab.SETTINGS -> SettingsScreen(
                                s = s,
                                wifiCount = wifiIdsOf(s, servers).size,
                                mobileCount = mobileIdsOf(s, servers).size,
                                onWifi = { showWifi = true },
                                onMobile = { showMobile = true },
                                onApps = { showApps = true },
                                onPhoto = { photoPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) },
                                onGif = { gifPicker.launch(arrayOf("image/gif")) },
                                onAbout = { showAbout = true }
                            )
                        }
                    }
                }
            }

            if (showWifi) {
                ServerPickerDialog(
                    title = tr("dlg_wifi"), servers = servers, favorites = s.favorites,
                    selected = wifiIdsOf(s, servers),
                    defaultIds = servers.filter { !isBypass(it.name) }.map { it.id }.toSet(),
                    onDismiss = { showWifi = false },
                    onSave = { ids ->
                        upd { copy(wifiIds = ids) }
                        VpnController.reevaluateNow()
                    }
                )
            }
            if (showMobile) {
                ServerPickerDialog(
                    title = tr("dlg_mobile"), servers = servers, favorites = s.favorites,
                    selected = mobileIdsOf(s, servers),
                    defaultIds = servers.filter { isBypass(it.name) }.map { it.id }.toSet(),
                    onDismiss = { showMobile = false },
                    onSave = { ids ->
                        upd { copy(mobileIds = ids) }
                        VpnController.reevaluateNow()
                    }
                )
            }
            if (showApps) {
                AppPickerDialog(
                    selected = s.excludedApps,
                    onDismiss = { showApps = false },
                    onSave = { pkgs -> upd { copy(excludedApps = pkgs) } }
                )
            }
            if (showAbout) AboutDialog { showAbout = false }
        }
    }
}

@Composable
private fun AppHeader(tab: AppTab, onSettings: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (tab == AppTab.SERVERS) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Shield, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(tr("title_vpn"), color = p.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
            // touch area 48x48, visible circle 40x40
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onSettings),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(p.cardBg).border(1.dp, p.cardBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Settings, tr("tab_settings"), tint = p.textPrimary, modifier = Modifier.size(20.dp)) }
            }
        } else {
            Box(Modifier.height(48.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    tr(if (tab == AppTab.LOGS) "tab_logs" else "tab_settings"),
                    color = p.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold
                )
            }
            if (tab == AppTab.LOGS) {
                VpnButton(
                    tr("logs_clear"), { VpnController.clearLogs() }, style = BtnStyle.SUBTLE, height = 40.dp, radius = 14.dp
                )
            }
        }
    }
}

@Composable
private fun AppBottomBar(active: AppTab, onSelect: (AppTab) -> Unit) {
    val p = LocalPalette.current
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.navBorder))
        NavigationBar(containerColor = p.navBg, tonalElevation = 0.dp) {
            listOf(
                Triple(AppTab.SERVERS, "tab_servers", Icons.Default.Public),
                Triple(AppTab.LOGS, "tab_logs", Icons.Default.Description),
                Triple(AppTab.SETTINGS, "tab_settings", Icons.Default.Settings)
            ).forEach { (tab, key, icon) ->
                val label = tr(key)
                NavigationBarItem(
                    selected = active == tab,
                    onClick = { onSelect(tab) },
                    icon = { Icon(icon, label) },
                    label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Accent, selectedTextColor = Accent,
                        unselectedIconColor = p.textSecondary, unselectedTextColor = p.textSecondary,
                        indicatorColor = p.navPill
                    )
                )
            }
        }
    }
}
