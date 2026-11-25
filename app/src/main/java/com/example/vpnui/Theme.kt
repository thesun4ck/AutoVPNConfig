package com.example.vpnui

import android.os.Build
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

object Status {
    val Disconnected = Color(0xFF3478F6)
    val Connecting = Color(0xFFF59E0B)
    val Connected = Color(0xFF22C55E)
    val Error = Color(0xFFEF4444)
    val Timeout = Color(0xFF8E9AAF)
}

val Accent = Color(0xFF3478F6)

fun pingColor(ping: Int?): Color = when {
    ping == null -> Status.Timeout
    ping <= 60 -> Status.Connected
    ping <= 120 -> Status.Connecting
    else -> Status.Error
}

data class Palette(
    val theme: AppThemeMode,
    val cardBg: Color,
    val cardBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val surfaceSubtle: Color,
    val divider: Color,
    val powerCoreBg: Color,
    val powerCoreBorder: Color,
    val powerIconIdle: Color,
    val navBg: Color,
    val navBorder: Color,
    val navPill: Color,
    val dialogBg: Color,
    val dialogBorder: Color,
    val inputBg: Color,
    val switchOff: Color
) {
    val isLight: Boolean get() = theme == AppThemeMode.LIGHT
    val isGlass: Boolean get() = theme == AppThemeMode.GLASS
}

private fun w(a: Float) = Color.White.copy(alpha = a)

fun paletteFor(theme: AppThemeMode): Palette = when (theme) {
    AppThemeMode.LIGHT -> Palette(
        theme = theme,
        cardBg = Color(0xFFFFFFFF), cardBorder = Color(0xFFE1EAF6),
        textPrimary = Color(0xFF17243A), textSecondary = Color(0xFF667895), textMuted = Color(0xFF8496B2),
        surfaceSubtle = Color(0xFFEAF1FB), divider = Color(0xFFE6EEF8),
        powerCoreBg = Color(0xFFFFFFFF), powerCoreBorder = Color(0xFFE1EAF6), powerIconIdle = Accent,
        navBg = w(0.94f), navBorder = Color(0xFFE1EAF6), navPill = Accent.copy(alpha = 0.13f),
        dialogBg = Color(0xFFFFFFFF), dialogBorder = Color(0xFFE1EAF6),
        inputBg = Color(0xFFF3F7FD), switchOff = Color(0xFFCBD8EA)
    )
    AppThemeMode.DARK -> Palette(
        theme = theme,
        cardBg = Color(0xFF17263D), cardBorder = Color(0xFF2B3E5A),
        textPrimary = Color(0xFFF3F7FF), textSecondary = Color(0xFFB4C3D9), textMuted = Color(0xFF7F93B0),
        surfaceSubtle = Color(0xFF20334F), divider = Color(0xFF263854),
        powerCoreBg = Color(0xFF223A5E), powerCoreBorder = Color(0xFF3A5782), powerIconIdle = Color(0xFF5C98FF),
        navBg = Color(0xFF111F34), navBorder = Color(0xFF253752), navPill = Accent.copy(alpha = 0.24f),
        dialogBg = Color(0xFF17263D), dialogBorder = Color(0xFF2B3E5A),
        inputBg = Color(0xFF0E1B2F), switchOff = Color(0xFF2D4261)
    )
    AppThemeMode.GLASS -> {
        // Android older than 12 cannot blur, so cards become flat and more opaque to stay readable
        val legacy = Build.VERSION.SDK_INT < 31
        Palette(
            theme = theme,
            cardBg = if (legacy) Color(0xFF222A4C).copy(alpha = 0.78f) else w(0.13f),
            cardBorder = w(0.23f),
            textPrimary = Color.White, textSecondary = Color(0xFFEBF3FF).copy(alpha = 0.78f),
            textMuted = Color(0xFFD7E3FA).copy(alpha = 0.62f),
            surfaceSubtle = w(0.12f), divider = w(0.15f),
            powerCoreBg = w(0.15f), powerCoreBorder = w(0.32f), powerIconIdle = Color.White,
            navBg = Color(0xFF0F1430).copy(alpha = if (legacy) 0.92f else 0.56f), navBorder = w(0.20f), navPill = w(0.18f),
            dialogBg = Color(0xFF181E3E).copy(alpha = 0.95f), dialogBorder = w(0.26f),
            inputBg = w(0.10f), switchOff = w(0.24f)
        )
    }
}

val LocalPalette = compositionLocalOf { paletteFor(AppThemeMode.GLASS) }
