package com.example.vpnui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun themedCheckColors() = CheckboxDefaults.colors(
    checkedColor = Accent, uncheckedColor = LocalPalette.current.textMuted, checkmarkColor = Color.White
)

@Composable
private fun themedFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent, unfocusedBorderColor = LocalPalette.current.cardBorder,
    focusedContainerColor = LocalPalette.current.inputBg, unfocusedContainerColor = LocalPalette.current.inputBg,
    focusedTextColor = LocalPalette.current.textPrimary, unfocusedTextColor = LocalPalette.current.textPrimary, cursorColor = Accent
)

/** Servers for Wi-Fi or for mobile data. */
@Composable
fun ServerPickerDialog(
    title: String,
    servers: List<VpnServer>,
    favorites: Set<String>,
    selected: Set<String>,
    defaultIds: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit
) {
    val p = LocalPalette.current
    var local by remember(selected) { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    ThemedDialog(onDismiss) {
        DialogHeader(title, "", onDismiss)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(tr("match_hint"), color = p.textMuted, fontSize = 13.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.5.sp, color = p.textPrimary),
                shape = RoundedCornerShape(14.dp), colors = themedFieldColors()
            )
            VpnButton(
                tr("btn_auto_by_name"),
                onClick = {
                    val q = query.trim().lowercase()
                    local = if (q.isEmpty()) {
                        defaultIds
                    } else {
                        local + servers.filter { it.name.lowercase().contains(q) }.map { it.id }
                    }
                },
                modifier = Modifier.fillMaxWidth(), style = BtnStyle.SUBTLE, height = 44.dp, radius = 14.dp
            )
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp)) {
            orderServers(servers, favorites).forEach { server ->
                val (flag, label) = splitFlag(server.name)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { local = if (server.id in local) local - server.id else local + server.id }
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = server.id in local, onCheckedChange = null, colors = themedCheckColors())
                    Spacer(Modifier.width(8.dp))
                    Text(flag, fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, color = p.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            server.ping?.let { tr("ping_ms", it) } ?: "—",
                            color = pingColor(server.ping), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
        DialogFooter {
            VpnButton(tr("btn_cancel"), onDismiss, style = BtnStyle.SUBTLE, height = 44.dp, radius = 14.dp)
            VpnButton(tr("btn_save"), { onSave(local); onDismiss() }, height = 44.dp, radius = 14.dp)
        }
    }
}

private data class AppInfo(val label: String, val pkg: String, val icon: ImageBitmap?)

@Suppress("DEPRECATION")
private fun loadApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != context.packageName }
        .distinctBy { it.activityInfo.packageName }
        .map { ri ->
            AppInfo(
                label = ri.loadLabel(pm).toString(),
                pkg = ri.activityInfo.packageName,
                icon = runCatching { ri.loadIcon(pm).toBitmap(72, 72).asImageBitmap() }.getOrNull()
            )
        }
        .sortedBy { it.label.lowercase() }
}

/** Apps that skip the VPN. */
@Composable
fun AppPickerDialog(selected: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var query by remember { mutableStateOf("") }
    var local by remember(selected) { mutableStateOf(selected) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadApps(context) } }

    ThemedDialog(onDismiss) {
        DialogHeader(tr("dlg_apps"), tr("dlg_apps_hint"), onDismiss)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(tr("search"), color = p.textMuted, fontSize = 13.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.5.sp, color = p.textPrimary),
                shape = RoundedCornerShape(14.dp), colors = themedFieldColors()
            )
        }
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
        } else {
            val filtered = list.filter { it.label.contains(query, ignoreCase = true) }
            LazyColumn(Modifier.weight(1f, fill = false).padding(horizontal = 10.dp, vertical = 4.dp)) {
                items(filtered, key = { it.pkg }) { app ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { local = if (app.pkg in local) local - app.pkg else local + app.pkg }
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = app.pkg in local, onCheckedChange = null, colors = themedCheckColors())
                        Spacer(Modifier.width(8.dp))
                        app.icon?.let { Image(it, null, Modifier.size(32.dp)) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, color = p.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(app.pkg, color = p.textMuted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        DialogFooter {
            VpnButton(tr("btn_cancel"), onDismiss, style = BtnStyle.SUBTLE, height = 44.dp, radius = 14.dp)
            VpnButton(tr("btn_save"), { onSave(local); onDismiss() }, height = 44.dp, radius = 14.dp)
        }
    }
}

/** Manual entry of the days left, for providers that do not send the expiry. */
@Composable
fun DaysDialog(onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    val p = LocalPalette.current
    var text by remember { mutableStateOf("") }
    ThemedDialog(onDismiss) {
        DialogHeader(tr("dlg_days_title"), tr("dlg_days_hint"), onDismiss)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { v -> text = v.filter { it.isDigit() }.take(4) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(tr("days_field"), color = p.textMuted) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = p.textPrimary),
                shape = RoundedCornerShape(14.dp), colors = themedFieldColors()
            )
        }
        DialogFooter {
            VpnButton(tr("btn_cancel"), onDismiss, style = BtnStyle.SUBTLE, height = 44.dp, radius = 14.dp)
            VpnButton(tr("btn_save"), { text.toIntOrNull()?.let { onSave(it) }; onDismiss() }, enabled = text.isNotEmpty(), height = 44.dp, radius = 14.dp)
        }
    }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val p = LocalPalette.current
    ThemedDialog(onDismiss) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(Accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Shield, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(tr("title_vpn"), color = p.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
            Text(tr("version", "1.1.0"), color = p.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(tr("about_text"), color = p.textSecondary, fontSize = 12.5.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            VpnButton(tr("btn_close"), onDismiss, Modifier.fillMaxWidth())
        }
    }
}
