package com.moji.ai.data.backup

import com.moji.ai.ai.AiConfig
import com.moji.ai.ai.AiProviderType
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.RecurrenceFrequency
import com.moji.ai.data.model.TagCatalog
import com.moji.ai.data.model.TagDef
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType
import com.moji.ai.service.BillScreenshotStorage
import com.moji.ai.ui.screens.netWorthImpact
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

    @Test
    fun netWorthImpact_accountsForSettledReimbursementInflowAcrossCreditAndDebitWallets() {
        val creditWallet = WalletEntity(
            id = 1L,
            name = "招商信用卡",
            type = WalletType.CREDIT,
            balanceCents = 0L,
            includeInNetWorth = true
        )
        val debitWallet = WalletEntity(
            id = 2L,
            name = "招商储蓄卡",
            type = WalletType.DEBIT_BANK,
            balanceCents = 50_000L,
            includeInNetWorth = true
        )
        val walletMap = mapOf(1L to creditWallet, 2L to debitWallet)
        val reimbursedExpense = TransactionEntity(
            id = 99L,
            ledgerName = "默认账本",
            type = TransactionType.EXPENSE,
            amountCents = 12_000L,
            amountCnyCents = 12_000L,
            category = "交通-打车",
            merchant = "滴滴出行",
            sourceWalletId = 1L,
            targetWalletId = 2L,
            reimbursable = true,
            reimbursed = true,
            timestamp = 1_762_574_400_000L
        )

        val (deltaAssets, deltaLiabilities) = netWorthImpact(reimbursedExpense, walletMap)
        assertEquals(12_000L, deltaAssets)
        assertEquals(12_000L, deltaLiabilities)
    }

    @Test
    fun aiConfig_allowsEmptyApiKeyForLoopbackAndPrivateLanEndpointsOnly() {
        fun cfg(url: String, provider: AiProviderType = AiProviderType.OPENAI_COMPATIBLE) = AiConfig(
            provider = provider,
            baseUrl = url,
            apiKey = "",
            modelName = "qwen2.5:7b",
            enableAccessibilityCapture = true,
            enableNotificationCapture = true,
            enableHeuristicFallback = true
        )

        assertTrue(cfg("http://127.0.0.1:11434/v1").allowsEmptyApiKey())
        assertTrue(cfg("http://192.168.1.88:11434/v1").allowsEmptyApiKey())
        assertTrue(cfg("http://10.0.0.5:8000/v1").allowsEmptyApiKey())
        assertTrue(cfg("http://172.20.10.2:1234/v1").allowsEmptyApiKey())
        assertFalse(cfg("http://172.32.0.1:11434/v1").allowsEmptyApiKey())
        assertFalse(cfg("https://api.deepseek.com/v1").allowsEmptyApiKey())
        assertFalse(cfg("http://192.168.1.88:11434/v1", AiProviderType.GEMINI_OFFICIAL).allowsEmptyApiKey())
    }

    @Test
    fun backupSchemaV3_distinguishesExplicitEmptyTagCatalogFromLegacyOmittedTags() {
        val exportedEmptyTagsJson = BackupAndCsvTransferEngine.exportFullBackupJson(
            ledgers = listOf("默认账本"),
            monthlyBudgetCents = 0L,
            wallets = emptyList(),
            transactions = emptyList(),
            recurringRules = emptyList(),
            merchantMemories = emptyList(),
            customTags = emptyList()
        )
        val parsed = BackupAndCsvTransferEngine.parseFullBackupJson(exportedEmptyTagsJson)
        assertNotNull(parsed)
        assertEquals(3, parsed!!.third.schemaVersion)

        TagCatalog.replaceAllTags(listOf(TagDef(name = "测试标签")), allowEmpty = false)
        assertEquals(1, TagCatalog.tags.size)
        // v1/v2 缺省空列表不覆盖现有标签
        TagCatalog.replaceAllTags(emptyList(), allowEmpty = false)
        assertEquals(1, TagCatalog.tags.size)
        // v3 显式空列表精确清空自定义标签，且静默屏蔽存量历史标签，防止冷启动重扫把已删标签复活
        TagCatalog.replaceAllTags(
            customList = emptyList(),
            allowEmpty = parsed.third.schemaVersion >= 3,
            historicalTagStrings = listOf("出差,餐饮", "#聚餐")
        )
        assertTrue(TagCatalog.tags.isEmpty())
        TagCatalog.syncDiscoveredTagsFromHistory(listOf("出差,餐饮", "#聚餐"))
        assertTrue(TagCatalog.tags.isEmpty())
    }

    @Test
    fun isValidWebpBytes_and_computeFileSha256IfSafe_rejectNonWebpFiles() {
        val validHeader = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0x20, 0x00, 0x00, 0x00,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
            'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), ' '.code.toByte()
        )
        assertTrue(BillScreenshotStorage.isValidWebpBytes(validHeader))
        assertFalse(BillScreenshotStorage.isValidWebpBytes("{\"error\":\"server\"}".toByteArray()))

        val tmpDir = java.nio.file.Files.createTempDirectory("moji_ss_test").toFile()
        try {
            val ssDir = java.io.File(tmpDir, "files/bill_screenshots").apply { mkdirs() }
            val badFile = java.io.File(ssDir, "corrupt.webp").apply {
                writeBytes("not-a-webp-payload".toByteArray())
            }
            val goodFile = java.io.File(ssDir, "valid.webp").apply {
                writeBytes(validHeader)
            }
            assertEquals(null, BillScreenshotStorage.computeFileSha256IfSafe(badFile, null))
            val goodSha = BillScreenshotStorage.computeFileSha256IfSafe(goodFile, null)
            assertNotNull(goodSha)
            assertEquals(64, goodSha!!.length)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun fullBackupAndSyncDtos_roundTripAllSerializableModelsAndEnums() {
        val wallet = WalletEntity(
            id = 1L,
            name = "招商储蓄卡",
            type = WalletType.DEBIT_BANK,
            balanceCents = 123_450L,
            currency = com.moji.ai.data.model.CurrencyCode.HKD.name,
            cardLast4 = "8848",
            includeInNetWorth = true
        )
        val tx = TransactionEntity(
            id = 2L,
            ledgerName = "默认账本",
            type = TransactionType.EXPENSE,
            amountCents = 3580L,
            currency = com.moji.ai.data.model.CurrencyCode.HKD.name,
            exchangeRateToCny = 0.93,
            amountCnyCents = 3329L,
            category = "餐饮-咖啡",
            merchant = "Manner Coffee",
            tags = "早餐,医保",
            sourceWalletId = 1L,
            timestamp = 1_762_574_400_000L
        )
        val rule = com.moji.ai.data.local.RecurringRuleEntity(
            id = 3L,
            title = "每月房租",
            type = TransactionType.EXPENSE,
            amountCents = 300_000L,
            category = "居住-房租",
            walletId = 1L,
            frequency = RecurrenceFrequency.MONTHLY,
            nextTriggerTimeMillis = 1_765_000_000_000L
        )
        val appearanceJson = kotlinx.serialization.json.Json.encodeToString(
            com.moji.ai.data.model.AppearanceSettingsConfig.serializer(),
            com.moji.ai.data.model.AppearanceSettingsConfig(
                themeMode = com.moji.ai.data.model.AppThemeMode.DARK,
                amountColorScheme = com.moji.ai.data.model.AmountColorScheme.GREEN_EXPENSE_RED_INCOME,
                fontScalePreset = com.moji.ai.data.model.AppFontScalePreset.LARGE
            )
        )
        val fullJson = BackupAndCsvTransferEngine.exportFullBackupJson(
            ledgers = listOf("默认账本", "旅行基金"),
            monthlyBudgetCents = 500_000L,
            wallets = listOf(wallet),
            transactions = listOf(tx),
            recurringRules = listOf(rule),
            merchantMemories = listOf(
                com.moji.ai.ai.MerchantMemoryEntry(
                    merchantKeyword = "Manner",
                    category = "餐饮-咖啡",
                    preferredWalletId = 1L
                )
            ),
            customExpenseCategories = listOf(
                com.moji.ai.data.model.CategoryDef(
                    name = "餐饮",
                    assetFile = "cat_dining.webp",
                    subCategories = listOf(com.moji.ai.data.model.SubCategoryDef("咖啡", "sub_coffee.webp"))
                )
            ),
            customTags = listOf(TagDef(name = "早餐", groupName = "日常")),
            quickTemplates = listOf(
                com.moji.ai.ui.QuickBookkeepingTemplate(
                    id = 10L,
                    title = "早咖啡",
                    category = "餐饮-咖啡",
                    amountCents = 1500L,
                    typeName = TransactionType.EXPENSE.name
                )
            ),
            smartRules = listOf(
                com.moji.ai.ai.SmartConditionRule(
                    id = 20L,
                    name = "深夜外卖",
                    keywordContains = "美团",
                    targetCategory = "餐饮-夜宵"
                )
            ),
            savingPlans = listOf(com.moji.ai.ui.SavingPlanItem(id = 30L, title = "应急金", targetCents = 1_000_000L)),
            installmentPlans = listOf(com.moji.ai.ui.InstallmentPlanItem(id = 40L, title = "手机分期", totalCents = 600_000L, totalPeriods = 12)),
            customCurrencyRates = mapOf("HKD" to 0.935),
            appearanceSettingsJson = appearanceJson
        )

        val parsed = BackupAndCsvTransferEngine.parseFullBackupJson(fullJson)
        assertNotNull(parsed)
        val (restoredWallets, restoredTxs, snapshot) = parsed!!
        assertEquals(1, restoredWallets.size)
        assertEquals(WalletType.DEBIT_BANK, restoredWallets.single().type)
        assertEquals("HKD", restoredWallets.single().currency)
        assertEquals(1, restoredTxs.size)
        assertEquals("Manner Coffee", restoredTxs.single().merchant)
        assertEquals(1, snapshot.recurringRules.size)
        assertEquals(RecurrenceFrequency.MONTHLY.name, snapshot.recurringRules.single().frequency)
        assertEquals(0.935, snapshot.customCurrencyRates["HKD"]!!, 1e-6)

        val encodedCurrency = kotlinx.serialization.json.Json.encodeToString(
            com.moji.ai.data.model.CurrencyCode.serializer(),
            com.moji.ai.data.model.CurrencyCode.HKD
        )
        assertEquals(
            com.moji.ai.data.model.CurrencyCode.HKD,
            kotlinx.serialization.json.Json.decodeFromString(
                com.moji.ai.data.model.CurrencyCode.serializer(),
                encodedCurrency
            )
        )
        val encodedWalletType = kotlinx.serialization.json.Json.encodeToString(
            WalletType.serializer(),
            WalletType.CREDIT
        )
        assertEquals(
            WalletType.CREDIT,
            kotlinx.serialization.json.Json.decodeFromString(WalletType.serializer(), encodedWalletType)
        )

        val decodedAppearance = kotlinx.serialization.json.Json.decodeFromString(
            com.moji.ai.data.model.AppearanceSettingsConfig.serializer(),
            snapshot.appearanceSettingsJson
        )
        assertEquals(com.moji.ai.data.model.AppThemeMode.DARK, decodedAppearance.themeMode)

        val summaryDto = MoJiCloudSyncClient.AccountSummaryDto(
            latestRevision = 9L,
            walletCount = 1,
            txCount = 1,
            ledgerCount = 2
        )
        val encodedSummary = kotlinx.serialization.json.Json.encodeToString(
            MoJiCloudSyncClient.AccountSummaryDto.serializer(),
            summaryDto
        )
        val decodedSummary = kotlinx.serialization.json.Json.decodeFromString(
            MoJiCloudSyncClient.AccountSummaryDto.serializer(),
            encodedSummary
        )
        assertEquals(9L, decodedSummary.latestRevision)
        assertEquals(2, decodedSummary.ledgerCount)
    }
}
