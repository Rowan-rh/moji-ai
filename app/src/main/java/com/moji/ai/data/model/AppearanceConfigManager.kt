package com.moji.ai.data.model

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.abs

@Serializable
enum class AppThemeMode(val label: String, val subtitle: String) {
    LIGHT("浅色模式", "经典清爽白卡"),
    DARK("深色模式", "夜间护眼暗色"),
    FOLLOW_SYSTEM("跟随系统", "自动匹配系统设置")
}

@Serializable
enum class AmountColorScheme(
    val label: String,
    val subtitle: String,
    val expenseHex: Long,
    val incomeHex: Long
) {
    RED_EXPENSE_GREEN_INCOME(
        label = "红支绿收",
        subtitle = "经典 iCost 配色（红支绿收）",
        expenseHex = 0xFFE65540,
        incomeHex = 0xFF22B573
    ),
    GREEN_EXPENSE_RED_INCOME(
        label = "绿支红收",
        subtitle = "国际通用习惯（绿支红收）",
        expenseHex = 0xFF22B573,
        incomeHex = 0xFFE65540
    ),
    MONOCHROME(
        label = "极简单色",
        subtitle = "克制石墨黑支 · 晴空蓝收",
        expenseHex = 0xFF1F2937,
        incomeHex = 0xFF0EA5E9
    )
}

@Serializable
enum class AppFontScalePreset(
    val label: String,
    val percentLabel: String,
    val scale: Float
) {
    COMPACT("紧凑", "92%", 0.92f),
    STANDARD("标准", "100%", 1.00f),
    LARGE("大字版", "110%", 1.10f)
}

@Serializable
data class AppearanceSettingsConfig(
    val themeMode: AppThemeMode = AppThemeMode.LIGHT,
    val amountColorScheme: AmountColorScheme = AmountColorScheme.RED_EXPENSE_GREEN_INCOME,
    val fontScalePreset: AppFontScalePreset = AppFontScalePreset.STANDARD,
    val showThousandSeparator: Boolean = true,
    val hideTrailingZeroDecimals: Boolean = false
) {
    val settingsTrailingText: String
        get() = AppearanceConfigManager.settingsTrailingSummary(this)
    val settingsTrailingSummary: String
        get() = settingsTrailingText
}

object AppearanceConfigManager {
    const val PREFS_NAME = "moji_appearance_prefs"
    const val KEY_APPEARANCE_SETTINGS_JSON = "appearance_settings_json_v1"

    private val jsonCodec = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    var isSystemDarkActive: Boolean = false
        private set

    private var composeSystemDarkState by mutableStateOf(false)
    private var composeConfigState by mutableStateOf(AppearanceSettingsConfig())

    private val _configFlow = MutableStateFlow(AppearanceSettingsConfig())
    val configFlow: StateFlow<AppearanceSettingsConfig> = _configFlow.asStateFlow()

    val currentConfig: AppearanceSettingsConfig
        get() = composeConfigState

    val isDarkThemeActive: Boolean
        get() = resolveIsDarkTheme(composeConfigState, composeSystemDarkState)

    @Synchronized
    fun init(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        val raw = sp.getString(KEY_APPEARANCE_SETTINGS_JSON, null)
        if (raw.isNullOrBlank()) {
            val initial = AppearanceSettingsConfig()
            _configFlow.value = initial
            composeConfigState = initial
            persist(initial)
            return
        }
        val decoded = runCatching {
            jsonCodec.decodeFromString<AppearanceSettingsConfig>(raw)
        }.getOrNull() ?: AppearanceSettingsConfig()
        _configFlow.value = decoded
        composeConfigState = decoded
    }

    @Synchronized
    private fun persist(config: AppearanceSettingsConfig) {
        _configFlow.value = config
        composeConfigState = config
        prefs?.edit()?.putString(KEY_APPEARANCE_SETTINGS_JSON, jsonCodec.encodeToString(config))?.apply()
    }

    fun updateSystemDarkState(systemDark: Boolean) {
        isSystemDarkActive = systemDark
        composeSystemDarkState = systemDark
    }

    fun resolveIsDarkTheme(
        config: AppearanceSettingsConfig = composeConfigState,
        systemDark: Boolean = composeSystemDarkState
    ): Boolean = when (config.themeMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.FOLLOW_SYSTEM -> systemDark
    }

    fun settingsTrailingSummary(
        config: AppearanceSettingsConfig = composeConfigState
    ): String = "${config.themeMode.label} · ${config.amountColorScheme.label}"

    fun formatDisplayAmount(
        amount: Double,
        config: AppearanceSettingsConfig = composeConfigState
    ): String {
        val safeAmount = abs(amount)
        val pattern = if (config.showThousandSeparator) "%,.2f" else "%.2f"
        val formatted = String.format(Locale.US, pattern, safeAmount)
        return if (config.hideTrailingZeroDecimals && formatted.endsWith(".00")) {
            formatted.removeSuffix(".00")
        } else {
            formatted
        }
    }

    @Synchronized
    fun setThemeMode(mode: AppThemeMode): AppearanceSettingsConfig =
        _configFlow.value.copy(themeMode = mode).also { persist(it) }

    @Synchronized
    fun setAmountColorScheme(scheme: AmountColorScheme): AppearanceSettingsConfig =
        _configFlow.value.copy(amountColorScheme = scheme).also { persist(it) }

    @Synchronized
    fun setFontScalePreset(preset: AppFontScalePreset): AppearanceSettingsConfig =
        _configFlow.value.copy(fontScalePreset = preset).also { persist(it) }

    @Synchronized
    fun setShowThousandSeparator(show: Boolean): AppearanceSettingsConfig =
        _configFlow.value.copy(showThousandSeparator = show).also { persist(it) }

    @Synchronized
    fun setHideTrailingZeroDecimals(hide: Boolean): AppearanceSettingsConfig =
        _configFlow.value.copy(hideTrailingZeroDecimals = hide).also { persist(it) }

    @Synchronized
    fun resetToDefaults(): AppearanceSettingsConfig =
        AppearanceSettingsConfig().also { persist(it) }

    @Synchronized
    fun exportConfigJson(): String = jsonCodec.encodeToString(_configFlow.value)

    @Synchronized
    fun restoreFromBackupJson(rawJson: String?): Boolean {
        if (rawJson.isNullOrBlank()) return false
        val decoded = runCatching {
            jsonCodec.decodeFromString<AppearanceSettingsConfig>(rawJson.trim())
        }.getOrNull() ?: return false
        persist(decoded)
        return true
    }

    @Synchronized
    fun resetForTesting(
        config: AppearanceSettingsConfig = AppearanceSettingsConfig(),
        systemDark: Boolean = false
    ) {
        isSystemDarkActive = systemDark
        composeSystemDarkState = systemDark
        persist(config)
    }
}
