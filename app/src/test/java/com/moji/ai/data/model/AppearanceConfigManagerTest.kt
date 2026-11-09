package com.moji.ai.data.model

import com.moji.ai.data.backup.BackupAndCsvTransferEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppearanceConfigManagerTest {

    @Before
    fun setUp() {
        AppearanceConfigManager.resetForTesting()
    }

    @After
    fun tearDown() {
        AppearanceConfigManager.resetForTesting()
    }

    @Test
    fun defaultConfig_matchesICostStandardDefaultsAndSummary() {
        val cfg = AppearanceConfigManager.configFlow.value
        assertEquals(AppThemeMode.LIGHT, cfg.themeMode)
        assertEquals(AmountColorScheme.RED_EXPENSE_GREEN_INCOME, cfg.amountColorScheme)
        assertEquals(AppFontScalePreset.STANDARD, cfg.fontScalePreset)
        assertEquals(1.00f, cfg.fontScalePreset.scale, 0.0001f)
        assertTrue(cfg.showThousandSeparator)
        assertFalse(cfg.hideTrailingZeroDecimals)
        assertEquals("浅色模式 · 红支绿收", cfg.settingsTrailingText)
        assertEquals("浅色模式 · 红支绿收", cfg.settingsTrailingSummary)
        assertFalse(AppearanceConfigManager.isDarkThemeActive)
    }

    @Test
    fun resolveIsDarkTheme_handlesLightDarkAndFollowSystemCorrectly() {
        AppearanceConfigManager.setThemeMode(AppThemeMode.LIGHT)
        AppearanceConfigManager.updateSystemDarkState(true)
        assertFalse(AppearanceConfigManager.isDarkThemeActive)

        AppearanceConfigManager.setThemeMode(AppThemeMode.DARK)
        AppearanceConfigManager.updateSystemDarkState(false)
        assertTrue(AppearanceConfigManager.isDarkThemeActive)

        AppearanceConfigManager.setThemeMode(AppThemeMode.FOLLOW_SYSTEM)
        AppearanceConfigManager.updateSystemDarkState(false)
        assertFalse(AppearanceConfigManager.isDarkThemeActive)

        AppearanceConfigManager.updateSystemDarkState(true)
        assertTrue(AppearanceConfigManager.isDarkThemeActive)
    }

    @Test
    fun themeColorSchemeAndFontScale_updatesSummaryAndResetRestoresDefaults() {
        AppearanceConfigManager.setThemeMode(AppThemeMode.DARK)
        AppearanceConfigManager.setAmountColorScheme(AmountColorScheme.GREEN_EXPENSE_RED_INCOME)
        AppearanceConfigManager.setFontScalePreset(AppFontScalePreset.LARGE)
        AppearanceConfigManager.setShowThousandSeparator(false)
        AppearanceConfigManager.setHideTrailingZeroDecimals(true)

        val updated = AppearanceConfigManager.configFlow.value
        assertEquals("深色模式 · 绿支红收", updated.settingsTrailingText)
        assertEquals(0xFF22B573, updated.amountColorScheme.expenseHex)
        assertEquals(0xFFE65540, updated.amountColorScheme.incomeHex)
        assertEquals(1.10f, updated.fontScalePreset.scale, 0.0001f)
        assertFalse(updated.showThousandSeparator)
        assertTrue(updated.hideTrailingZeroDecimals)

        AppearanceConfigManager.setThemeMode(AppThemeMode.FOLLOW_SYSTEM)
        AppearanceConfigManager.setAmountColorScheme(AmountColorScheme.MONOCHROME)
        assertEquals("跟随系统 · 极简单色", AppearanceConfigManager.configFlow.value.settingsTrailingText)

        val reset = AppearanceConfigManager.resetToDefaults()
        assertEquals(AppThemeMode.LIGHT, reset.themeMode)
        assertEquals(AmountColorScheme.RED_EXPENSE_GREEN_INCOME, reset.amountColorScheme)
        assertEquals(AppFontScalePreset.STANDARD, reset.fontScalePreset)
        assertTrue(reset.showThousandSeparator)
        assertFalse(reset.hideTrailingZeroDecimals)
        assertEquals("浅色模式 · 红支绿收", reset.settingsTrailingText)
    }

    @Test
    fun formatDisplayAmount_handlesThousandSeparatorAndTrailingZeroDecimals() {
        // 1. 默认：开启千分位 + 不隐藏 .00
        val defaultCfg = AppearanceSettingsConfig(
            showThousandSeparator = true,
            hideTrailingZeroDecimals = false
        )
        assertEquals("1,280.00", AppearanceConfigManager.formatDisplayAmount(1280.0, defaultCfg))
        assertEquals("8,500.00", AppearanceConfigManager.formatDisplayAmount(8500.0, defaultCfg))
        assertEquals("1,280.50", AppearanceConfigManager.formatDisplayAmount(1280.5, defaultCfg))
        assertEquals("35.80", AppearanceConfigManager.formatDisplayAmount(35.8, defaultCfg))

        // 2. 开启千分位 + 隐藏整数 .00（带角分的金额仍保留两位小数）
        val hideZeroWithCommaCfg = AppearanceSettingsConfig(
            showThousandSeparator = true,
            hideTrailingZeroDecimals = true
        )
        assertEquals("1,280", AppearanceConfigManager.formatDisplayAmount(1280.0, hideZeroWithCommaCfg))
        assertEquals("35", AppearanceConfigManager.formatDisplayAmount(35.0, hideZeroWithCommaCfg))
        assertEquals("1,280.50", AppearanceConfigManager.formatDisplayAmount(1280.5, hideZeroWithCommaCfg))
        assertEquals("35.80", AppearanceConfigManager.formatDisplayAmount(35.8, hideZeroWithCommaCfg))

        // 3. 关闭千分位 + 隐藏整数 .00
        val hideZeroNoCommaCfg = AppearanceSettingsConfig(
            showThousandSeparator = false,
            hideTrailingZeroDecimals = true
        )
        assertEquals("1280", AppearanceConfigManager.formatDisplayAmount(1280.0, hideZeroNoCommaCfg))
        assertEquals("1280.50", AppearanceConfigManager.formatDisplayAmount(1280.5, hideZeroNoCommaCfg))

        // 4. 关闭千分位 + 不隐藏 .00
        val noCommaKeepZeroCfg = AppearanceSettingsConfig(
            showThousandSeparator = false,
            hideTrailingZeroDecimals = false
        )
        assertEquals("1280.00", AppearanceConfigManager.formatDisplayAmount(1280.0, noCommaKeepZeroCfg))
        assertEquals("1280.50", AppearanceConfigManager.formatDisplayAmount(1280.5, noCommaKeepZeroCfg))
    }

    @Test
    fun backupExportAndRestore_isBackwardCompatibleWithLegacySnapshot() {
        AppearanceConfigManager.setThemeMode(AppThemeMode.DARK)
        AppearanceConfigManager.setAmountColorScheme(AmountColorScheme.GREEN_EXPENSE_RED_INCOME)
        AppearanceConfigManager.setFontScalePreset(AppFontScalePreset.COMPACT)
        AppearanceConfigManager.setShowThousandSeparator(false)
        AppearanceConfigManager.setHideTrailingZeroDecimals(true)
        val exported = AppearanceConfigManager.exportConfigJson()

        AppearanceConfigManager.resetForTesting()
        assertEquals(AppThemeMode.LIGHT, AppearanceConfigManager.configFlow.value.themeMode)

        // 空串、null 或非法 JSON 不报错且保持当前配置不变
        assertFalse(AppearanceConfigManager.restoreFromBackupJson(null))
        assertFalse(AppearanceConfigManager.restoreFromBackupJson("   "))
        assertFalse(AppearanceConfigManager.restoreFromBackupJson("{invalid_json"))
        assertEquals(AppThemeMode.LIGHT, AppearanceConfigManager.configFlow.value.themeMode)

        // 恢复合法备份 JSON
        assertTrue(AppearanceConfigManager.restoreFromBackupJson(exported))
        val restored = AppearanceConfigManager.configFlow.value
        assertEquals(AppThemeMode.DARK, restored.themeMode)
        assertEquals(AmountColorScheme.GREEN_EXPENSE_RED_INCOME, restored.amountColorScheme)
        assertEquals(AppFontScalePreset.COMPACT, restored.fontScalePreset)
        assertFalse(restored.showThousandSeparator)
        assertTrue(restored.hideTrailingZeroDecimals)

        // 验证旧版 .mojibackup（不含 appearanceSettingsJson 字段）反序列化 100% 向下兼容
        val legacyBackupJson = """{"schemaVersion":1,"appName":"MoJi","ledgers":["默认账本"]}"""
        val parsed = BackupAndCsvTransferEngine.parseFullBackupJson(legacyBackupJson)
        assertNotNull(parsed)
        assertEquals("", parsed?.third?.appearanceSettingsJson)
    }
}
