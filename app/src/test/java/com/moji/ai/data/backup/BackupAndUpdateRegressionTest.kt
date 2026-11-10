package com.moji.ai.data.backup

import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.model.RecurrenceFrequency
import com.moji.ai.data.model.TransactionType
import com.moji.ai.update.AppUpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class BackupAndUpdateRegressionTest {

    @Test
    fun parseExternalCsv_handlesMultilineQuotedFieldsWithoutSplittingRecord() {
        val csv = """
            日期,时间,类型,金额,货币,一级分类,二级分类,账户1,账户2,备注,标签,账本,报销,优惠,手续费
            2026-11-08,12:30:00,支出,45.80,CNY,餐饮,午餐,微信零钱,,"第一行备注
            第二行备注,含逗号",聚餐,默认账本,,0.00,0.00
            2026-11-08,18:15:00,支出,18.00,CNY,交通,地铁,支付宝余额,,晚高峰通勤,,默认账本,,0.00,0.00
        """.trimIndent()

        val preview = BackupAndCsvTransferEngine.parseExternalCsv(
            rawCsv = csv,
            defaultLedger = "默认账本",
            wallets = emptyList(),
            merchantMemoryManager = null
        )

        assertEquals(2, preview.parsedTransactions.size)
        val first = preview.parsedTransactions[0]
        assertEquals(4580L, first.amountCents)
        assertTrue(first.merchant.contains("第一行备注") || first.note.contains("第一行备注"))
        assertEquals(1800L, preview.parsedTransactions[1].amountCents)
    }

    @Test
    fun exportTransactionsToCsv_escapesSpreadsheetFormulaInjectionPrefixes() {
        val dangerousTx = TransactionEntity(
            id = 1L,
            ledgerName = "默认账本",
            type = TransactionType.EXPENSE,
            amountCents = 2500L,
            currency = "CNY",
            amountCnyCents = 2500L,
            category = "餐饮",
            merchant = "=HYPERLINK(\"https://evil.example\",\"点我\")",
            note = "@SUM(1+1)",
            sourceWalletId = null,
            timestamp = 1_762_574_400_000L
        )

        val csv = BackupAndCsvTransferEngine.exportTransactionsToCsv(
            transactions = listOf(dangerousTx),
            wallets = emptyList()
        )

        assertTrue(csv.contains("\"'=HYPERLINK(\"\"https://evil.example\"\",\"\"点我\"\")\""))
        assertTrue(csv.contains("'@SUM(1+1)"))
    }

    @Test
    fun parseFullBackupJson_computesForeignCurrencyAmountCnyCentsWhenMissingInLegacyBackup() {
        val legacyJson = """
            {
              "schemaVersion": 2,
              "ledgers": ["默认账本"],
              "wallets": [],
              "transactions": [
                {
                  "id": 10,
                  "ledgerName": "默认账本",
                  "type": "EXPENSE",
                  "amountCents": 1000,
                  "currency": "USD",
                  "exchangeRateToCny": 7.22,
                  "category": "购物",
                  "merchant": "Apple US",
                  "timestamp": 1762574400000
                }
              ]
            }
        """.trimIndent()

        val parsed = BackupAndCsvTransferEngine.parseFullBackupJson(legacyJson)
        assertNotNull(parsed)
        val tx = parsed!!.second.single()
        assertEquals("USD", tx.currency)
        assertEquals(1000L, tx.amountCents)
        assertEquals(7220L, tx.amountCnyCents)
    }

    @Test
    fun isNewerVersion_respectsSemVerPrereleaseAndNumericBuildSuffixOrdering() {
        assertFalse(AppUpdateManager.isNewerVersion("v1.1.1", "1.1.1"))
        // 正式版高于同核心版本号的预发布版（rc / beta / alpha / qa）
        assertTrue(AppUpdateManager.isNewerVersion("v1.1.1", "1.1.1-rc1"))
        assertFalse(AppUpdateManager.isNewerVersion("v1.1.1-rc1", "1.1.1"))
        // 更高核心版本号的预发布版高于旧正式版
        assertTrue(AppUpdateManager.isNewerVersion("v1.1.2-beta1", "1.1.1"))
        // 同预发布前缀按数字递增比较（rc10 > rc2）
        assertTrue(AppUpdateManager.isNewerVersion("v1.1.1-rc.10", "1.1.1-rc.2"))
        // 纯日期构建后缀高于同核心版本无后缀包
        assertTrue(AppUpdateManager.isNewerVersion("v1.0.0-20261108", "1.0.0"))
        assertTrue(AppUpdateManager.isNewerVersion("v1.0.0-20261109", "1.0.0-20261108"))
    }

    @Test
    fun monthlyRecurrence_recoversAnchorDay31AfterShortFebruary() {
        val jan31 = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 31, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val febTrigger = RecurrenceFrequency.MONTHLY.computeNextTriggerMillis(
            baseMillis = jan31,
            nowMillis = jan31,
            anchorDay = 31
        )
        val febCal = Calendar.getInstance().apply { timeInMillis = febTrigger }
        assertEquals(2026, febCal.get(Calendar.YEAR))
        assertEquals(Calendar.FEBRUARY, febCal.get(Calendar.MONTH))
        assertEquals(28, febCal.get(Calendar.DAY_OF_MONTH))

        val marTrigger = RecurrenceFrequency.MONTHLY.computeNextTriggerMillis(
            baseMillis = febTrigger,
            nowMillis = febTrigger,
            anchorDay = 31
        )
        val marCal = Calendar.getInstance().apply { timeInMillis = marTrigger }
        assertEquals(2026, marCal.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, marCal.get(Calendar.MONTH))
        assertEquals(31, marCal.get(Calendar.DAY_OF_MONTH))
    }
}
