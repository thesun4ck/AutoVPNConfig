package com.example.vpnui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** The one card used everywhere: radius 22, theme aware (shadow in light, glass highlight in glass). */
@Composable
fun VpnCard(
    modifier: Modifier = Modifier,
    padding: Dp = 14.dp,
    onClick: (() -> Unit)? = null,
    borderColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(22.dp)
    var m = modifier.fillMaxWidth()
    if (p.isLight) m = m.shadow(3.dp, shape, ambientColor = Color(0x2217243A), spotColor = Color(0x2217243A))
    m = m.clip(shape).background(p.cardBg)
    if (p.isGlass) {
        m = m.drawBehind {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                    startY = 0f,
                    endY = size.height * 0.26f
                )
            )
        }
    }
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    m = m.border(1.dp, borderColor ?: p.cardBorder, shape)
    Column(m.padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

enum class BtnStyle { PRIMARY, SUBTLE }

@Composable
fun VpnButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BtnStyle = BtnStyle.PRIMARY,
    container: Color? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 48.dp,
    radius: Dp = 16.dp
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(radius)
    val bg = container ?: if (style == BtnStyle.PRIMARY) Accent else p.surfaceSubtle
    val fg = if (container != null || style == BtnStyle.PRIMARY) Color.White else p.textPrimary
    var m = modifier.defaultMinSize(minHeight = height).alpha(if (enabled) 1f else 0.45f).clip(shape).background(bg)
    if (style == BtnStyle.SUBTLE && container == null) m = m.border(1.dp, p.cardBorder, shape)
    Box(
        modifier = m.clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
fun FilterChipBtn(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(shape)
            .background(if (selected) Accent else p.surfaceSubtle)
            .border(1.dp, if (selected) Accent else p.cardBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (selected) Color.White else p.textSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun SectionLabel(number: Int, text: String) {
    val p = LocalPalette.current
    Text(
        "$number. ${text.uppercase()}",
        color = p.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
        modifier = Modifier.padding(horizontal = 2.dp)
    )
}

@Composable
fun SettingSwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f).clip(RoundedCornerShape(16.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .defaultMinSize(minHeight = 54.dp).padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = p.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Accent, checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White, uncheckedTrackColor = p.switchOff, uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
fun SettingNavRow(title: String, subtitle: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 54.dp).padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = p.textSecondary, fontSize = 12.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = p.textMuted)
    }
}

/** Dialog in the colors of the current theme. */
@Composable
fun ThemedDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val p = LocalPalette.current
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier.padding(18.dp).fillMaxWidth().heightIn(max = 620.dp).clip(shape).background(p.dialogBg).border(1.dp, p.dialogBorder, shape),
            content = content
        )
    }
}

@Composable
fun DialogHeader(title: String, subtitle: String, onClose: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (subtitle.isNotEmpty()) Text(subtitle, color = p.textSecondary, fontSize = 12.sp)
        }
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, null, tint = p.textSecondary)
        }
    }
}

@Composable
fun DialogFooter(content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().background(Color.Transparent).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}
