package com.moji.ai.data.model

import com.moji.ai.data.backup.BackupAndCsvTransferEngine
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.ui.QuickBookkeepingTemplate
import com.moji.ai.ui.QuickTemplateHelper
import com.moji.ai.ui.TemplateExecutionOutcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CurrencyRateManagementTest {

    @Before
    fun setUp() {
        CurrencyRateManager.resetAllCustomRates()
    }

    @After
    fun tearDown() {
        CurrencyRateManager.resetAllCustomRates()
    }

    @Test
    fun currencyCode_hasTwelveEntriesAndCommonForeignFlags() {
        assertEquals(12, CurrencyCode.entries.size)
        assertEquals(11, CurrencyCode.foreignEntries.size)
        assertFalse(CurrencyCode.CNY.isCommonForeign)
        assertTrue(CurrencyCode.USD.isCommonForeign)
        assertTrue(CurrencyCode.JPY.isCommonForeign)
        assertTrue(CurrencyCode.KRW.isCommonForeign)
        assertEquals(CurrencyCode.CNY, CurrencyCode.fromCode(null))
        assertEquals(CurrencyCode.CNY, CurrencyCode.fromCode("  "))
        assertEquals(CurrencyCode.SGD, CurrencyCode.fromCode("sgd"))
        assertEquals(CurrencyCode.THB, CurrencyCode.fromCode("THB"))
    }

    @Test
    fun validateCustomRateInput_rejectsInvalidAndRoundsUpToSixDecimals() {
        assertTrue(CurrencyRateManager.validateCustomRateInput("").isFailure)
        assertTrue(CurrencyRateManager.validateCustomRateInput("   ").isFailure)
        assertTrue(CurrencyRateManager.validateCustomRateInput("abc").isFailure)
        assertTrue(CurrencyRateManager.validateCustomRateInput("0").isFailure)
        assertTrue(CurrencyRateManager.validateCustomRateInput("-7.2").isFailure)
        assertTrue(CurrencyRateManager.validateCustomRateInput("1000.01").isFailure)
        assertTrue(CurrencyRateManager.validateCustomRateInput("0.0000001").isFailure)

        val valid = CurrencyRateManager.validateCustomRateInput("7.2567894")
        assertTrue(valid.isSuccess)
        assertEquals(7.256789, valid.getOrThrow(), 1e-9)
    }

    @Test
    fun customRateLifecycle_setResetAndBaseCurrencyGuard() {
        assertEquals(7.22, CurrencyRateManager.getEffectiveRateToCny("USD"), 1e-6)
        assertFalse(CurrencyRateManager.isCustomRate("USD"))

        // 本位币 CNY 拒绝自定义修改
        val cnyAttempt = CurrencyRateManager.setCustomRate("CNY", "8.00")
        assertTrue(cnyAttempt.isFailure)
        assertEquals(1.0, CurrencyRateManager.getEffectiveRateToCny("CNY"), 1e-9)

        // 自定义 USD 与 JPY 汇率
        val usdRes = CurrencyRateManager.setCustomRate("USD", "7.35")
        val jpyRes = CurrencyRateManager.setCustomRate("JPY", "0.0512")
        assertTrue(usdRes.isSuccess)
        assertTrue(jpyRes.isSuccess)
        assertTrue(CurrencyRateManager.isCustomRate("USD"))
        assertTrue(CurrencyRateManager.isCustomRate("JPY"))
        assertEquals(7.35, CurrencyRateManager.getEffectiveRateToCny("USD"), 1e-6)
        assertEquals(0.0512, CurrencyRateManager.getEffectiveRateToCny("JPY"), 1e-6)

        // 恢复单个币种默认汇率
        CurrencyRateManager.resetCurrencyRate("USD")
        assertFalse(CurrencyRateManager.isCustomRate("USD"))
        assertEquals(7.22, CurrencyRateManager.getEffectiveRateToCny("USD"), 1e-6)
        assertTrue(CurrencyRateManager.isCustomRate("JPY"))

        // 恢复全部默认汇率
        CurrencyRateManager.resetAllCustomRates()
        assertTrue(CurrencyRateManager.customRates.isEmpty())
        assertEquals(0.048, CurrencyRateManager.getEffectiveRateToCny("JPY"), 1e-6)
    }

    @Test
    fun templateExecution_usesEffectiveCustomRateWithoutMutatingHistory() {
        val wallets = listOf(
            WalletEntity(id = 1L, name = "外币卡", type = WalletType.DEBIT_BANK, currency = "USD", balanceCents = 100_00L)
        )
        val tpl = QuickBookkeepingTemplate(
            id = 10L,
            title = "ChatGPT Plus 订阅",
            typeName = TransactionType.EXPENSE.name,
            category = "数码",
            amountCents = 20_00L, // $20.00
            currency = "USD",
            walletId = 1L
        )

        val defaultOutcome = QuickTemplateHelper.evaluateQuickRecordOutcome(tpl, wallets, "默认账本")
        val defaultTx = (defaultOutcome as TemplateExecutionOutcome.ReadyToRecord).transaction
        assertEquals(7.22, defaultTx.exchangeRateToCny, 1e-6)
        assertEquals(144_40L, defaultTx.amountCnyCents)

        // 更新 USD 汇率为 7.30 后，新生成的账单按 7.30 折算，而之前的 defaultTx 快照保持 144_40L 不变
        CurrencyRateManager.setCustomRate("USD", 7.30)
        val customOutcome = QuickTemplateHelper.evaluateQuickRecordOutcome(tpl, wallets, "默认账本")
        val customTx = (customOutcome as TemplateExecutionOutcome.ReadyToRecord).transaction
        assertEquals(7.30, customTx.exchangeRateToCny, 1e-6)
        assertEquals(146_00L, customTx.amountCnyCents)
        assertEquals(144_40L, defaultTx.amountCnyCents)
    }

    @Test
    fun fullBackupSnapshot_roundTripsCustomRatesAndSupportsLegacyBackup() {
        CurrencyRateManager.setCustomRate("USD", 7.28)
        CurrencyRateManager.setCustomRate("HKD", 0.945)

        val backupJson = BackupAndCsvTransferEngine.exportFullBackupJson(
            ledgers = listOf("默认账本"),
            monthlyBudgetCents = 300_000L,
            wallets = emptyList(),
            transactions = emptyList(),
            recurringRules = emptyList(),
            merchantMemories = emptyList(),
            customCurrencyRates = CurrencyRateManager.customRates
        )

        val parsed = BackupAndCsvTransferEngine.parseFullBackupJson(backupJson)
        assertNotNull(parsed)
        val snapshot = parsed!!.third
        assertEquals(7.28, snapshot.customCurrencyRates["USD"] ?: 0.0, 1e-6)
        assertEquals(0.945, snapshot.customCurrencyRates["HKD"] ?: 0.0, 1e-6)

        // 模拟清空后通过 replaceAllCustomRates 恢复（自动过滤非法项与 CNY）
        CurrencyRateManager.resetAllCustomRates()
        CurrencyRateManager.replaceAllCustomRates(
            snapshot.customCurrencyRates + mapOf("CNY" to 2.0, "EUR" to -1.0, "GBP" to 9999.0)
        )
        assertEquals(2, CurrencyRateManager.customRates.size)
        assertEquals(7.28, CurrencyRateManager.getEffectiveRateToCny("USD"), 1e-6)
        assertEquals(0.945, CurrencyRateManager.getEffectiveRateToCny("HKD"), 1e-6)
        assertFalse(CurrencyRateManager.isCustomRate("CNY"))
        assertFalse(CurrencyRateManager.isCustomRate("EUR"))
        assertFalse(CurrencyRateManager.isCustomRate("GBP"))

        // 旧版无 customCurrencyRates 字段的备份仍可正常解析
        val legacyBackupJson = """{"schemaVersion":1,"appName":"MoJi","ledgers":["默认账本"]}"""
        val legacyParsed = BackupAndCsvTransferEngine.parseFullBackupJson(legacyBackupJson)
        assertNotNull(legacyParsed)
        assertTrue(legacyParsed!!.third.customCurrencyRates.isEmpty())
    }
}
