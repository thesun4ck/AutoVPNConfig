package com.example.vpnui

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class AppThemeMode { LIGHT, DARK, GLASS }

data class AppSettings(
    val theme: AppThemeMode = AppThemeMode.GLASS,
    val language: AppLang = AppLang.RU,
    val subscriptionUrl: String = "",
    val autoBest: Boolean = true,
    val intervalMin: Int = 10,
    val failover: Boolean = true,
    val autoNetwork: Boolean = true,
    val autoStart: Boolean = false,
    val killSwitch: Boolean = true,
    val selectedId: String? = null,
    val favorites: Set<String> = emptySet(),
    /** null means: pick by name (everything except "обход" servers). */
    val wifiIds: Set<String>? = null,
    /** null means: pick by name (only "обход" servers). */
    val mobileIds: Set<String>? = null,
    val excludedApps: Set<String> = emptySet(),
    val bgUri: String? = null,
    val bgIsGif: Boolean = false,
    val bgBlur: Float = 18f,
    val bgDim: Float = 0.28f,
    val highPing: Boolean = true,
    val bypassLan: Boolean = true,
    val directLocal: Boolean = false,
    val dnsProtection: Boolean = false,
    val verboseLogs: Boolean = false,
    val haptic: Boolean = true,
    /** From the provider header: unix seconds of expiry (0 = unknown), traffic limit and used bytes. */
    val subExpire: Long = 0L,
    val subTotal: Long = 0L,
    val subUsed: Long = 0L,
    val subFetchedAt: Long = 0L
)

object AppStore {
    private lateinit var plain: SharedPreferences
    private lateinit var secure: SharedPreferences
    private var ready = false
    private var lastSecureUrl: String? = null

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (ready) return
        val app = context.applicationContext
        plain = app.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
        secure = runCatching {
            val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                app, "vpn_secure", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse { app.getSharedPreferences("vpn_secure_fallback", Context.MODE_PRIVATE) }
        _settings.value = load()
        lastSecureUrl = _settings.value.subscriptionUrl
        ready = true
    }

    @Synchronized
    fun update(block: AppSettings.() -> AppSettings) {
        val next = _settings.value.block()
        _settings.value = next
        if (ready) save(next)
    }

    private inline fun <reified T : Enum<T>> enumOr(value: String?, default: T): T =
        runCatching { enumValueOf<T>(value ?: "") }.getOrDefault(default)

    private fun setOrNull(key: String): Set<String>? =
        if (plain.contains(key)) plain.getStringSet(key, null)?.toSet() else null

    private fun load(): AppSettings {
        val defLang = if (Locale.getDefault().language == "ru") AppLang.RU else AppLang.EN
        val d = AppSettings(language = defLang)
        return AppSettings(
            theme = enumOr(plain.getString("theme", null), d.theme),
            language = enumOr(plain.getString("lang", null), d.language),
            subscriptionUrl = secure.getString("sub_url", "") ?: "",
            autoBest = plain.getBoolean("auto_best", d.autoBest),
            intervalMin = plain.getInt("interval", d.intervalMin),
            failover = plain.getBoolean("failover", d.failover),
            autoNetwork = plain.getBoolean("auto_net", d.autoNetwork),
            autoStart = plain.getBoolean("auto_start", d.autoStart),
            killSwitch = plain.getBoolean("kill", d.killSwitch),
            selectedId = plain.getString("selected", null),
            favorites = plain.getStringSet("favs", emptySet())?.toSet() ?: emptySet(),
            wifiIds = setOrNull("wifi"),
            mobileIds = setOrNull("mobile"),
            excludedApps = plain.getStringSet("excluded", emptySet())?.toSet() ?: emptySet(),
            bgUri = plain.getString("bg_uri", null),
            bgIsGif = plain.getBoolean("bg_gif", d.bgIsGif),
            bgBlur = plain.getFloat("bg_blur", d.bgBlur),
            bgDim = plain.getFloat("bg_dim", d.bgDim),
            highPing = plain.getBoolean("high_ping", d.highPing),
            bypassLan = plain.getBoolean("bypass_lan", d.bypassLan),
            directLocal = plain.getBoolean("direct_local", d.directLocal),
            dnsProtection = plain.getBoolean("dns_protection", d.dnsProtection),
            verboseLogs = plain.getBoolean("verbose", d.verboseLogs),
            haptic = plain.getBoolean("haptic", d.haptic),
            subExpire = plain.getLong("sub_expire", 0L),
            subTotal = plain.getLong("sub_total", 0L),
            subUsed = plain.getLong("sub_used", 0L),
            subFetchedAt = plain.getLong("sub_fetched", 0L)
        )
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }

    private fun SharedPreferences.Editor.putSetOrRemove(key: String, value: Set<String>?) {
        if (value == null) remove(key) else putStringSet(key, value)
    }

    private fun save(s: AppSettings) {
        plain.edit().apply {
            putString("theme", s.theme.name)
            putString("lang", s.language.name)
            putBoolean("auto_best", s.autoBest)
            putInt("interval", s.intervalMin)
            putBoolean("failover", s.failover)
            putBoolean("auto_net", s.autoNetwork)
            putBoolean("auto_start", s.autoStart)
            putBoolean("kill", s.killSwitch)
            putOrRemove("selected", s.selectedId)
            putStringSet("favs", s.favorites)
            putSetOrRemove("wifi", s.wifiIds)
            putSetOrRemove("mobile", s.mobileIds)
            putStringSet("excluded", s.excludedApps)
            putOrRemove("bg_uri", s.bgUri)
            putBoolean("bg_gif", s.bgIsGif)
            putFloat("bg_blur", s.bgBlur)
            putFloat("bg_dim", s.bgDim)
            putBoolean("high_ping", s.highPing)
            putBoolean("bypass_lan", s.bypassLan)
            putBoolean("direct_local", s.directLocal)
            putBoolean("dns_protection", s.dnsProtection)
            putBoolean("verbose", s.verboseLogs)
            putBoolean("haptic", s.haptic)
            putLong("sub_expire", s.subExpire)
            putLong("sub_total", s.subTotal)
            putLong("sub_used", s.subUsed)
            putLong("sub_fetched", s.subFetchedAt)
        }.apply()
        // encryption is expensive: only when the link really changed (sliders save many times per second)
        if (s.subscriptionUrl != lastSecureUrl) {
            secure.edit().putString("sub_url", s.subscriptionUrl).apply()
            lastSecureUrl = s.subscriptionUrl
        }
    }
}
