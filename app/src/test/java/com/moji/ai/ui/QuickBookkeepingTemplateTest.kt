package com.moji.ai.ui

import com.moji.ai.data.backup.BackupAndCsvTransferEngine
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickBookkeepingTemplateTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val sampleWallets = listOf(
        WalletEntity(
            id = 1L,
            name = "招商银行储蓄卡",
            type = WalletType.DEBIT_BANK,
            balanceCents = 500_000L
        ),
        WalletEntity(
            id = 4L,
            name = "微信零钱",
            type = WalletType.DIGITAL_WALLET,
            balanceCents = 80_000L
        ),
        WalletEntity(
            id = 9L,
            name = "招行信用卡",
            type = WalletType.CREDIT,
            balanceCents = 120_000L
        )
    )

    @Test
    fun legacyTemplateJson_deserializesWithDefaultFields() {
        val legacyJson = """
            [
              {
                "id": 101,
                "title": "早餐包子",
                "typeName": "EXPENSE",
                "category": "餐饮-三餐",
                "amountCents": 850,
                "currency": "CNY",
                "walletId": 4,
                "note": "肉包豆浆",
                "tags": "早餐"
              }
            ]
        """.trimIndent()

        val decoded = json.decodeFromString<List<QuickBookkeepingTemplate>>(legacyJson)
        assertEquals(1, decoded.size)
        val tpl = decoded.first()
        assertEquals("早餐包子", tpl.title)
        assertNull(tpl.targetWalletId)
        assertFalse(tpl.reimbursable)
        assertEquals(TransactionType.EXPENSE, tpl.resolvedType())
    }

    @Test
    fun presetTemplates_mergeSkipsExistingByNameAndType() {
        val presets = QuickTemplateHelper.buildPresetTemplates(sampleWallets)
        assertEquals(8, presets.size)
        assertTrue(presets.any { it.title == "日常超市" && it.amountCents == 0L })
        assertTrue(presets.any { it.title == "发工资" && it.resolvedType() == TransactionType.INCOME })
        assertTrue(presets.any { it.title == "信用卡还款" && it.resolvedType() == TransactionType.TRANSFER })

        val initial = listOf(presets.first().copy(id = 500L))
        val (mergedOnce, addedOnce) = QuickTemplateHelper.mergePresetTemplates(initial, presets, baseId = 1000L)
        assertEquals(7, addedOnce)
        assertEquals(8, mergedOnce.size)

        val (mergedTwice, addedTwice) = QuickTemplateHelper.mergePresetTemplates(mergedOnce, presets, baseId = 2000L)
        assertEquals(0, addedTwice)
        assertEquals(8, mergedTwice.size)
    }

    @Test
    fun templateCrud_upsertDuplicateAndMove_preserveOrderAndUniqueIds() {
        val first = QuickBookkeepingTemplate(
            id = 10L,
            title = "通勤地铁",
            typeName = "EXPENSE",
            category = "交通-地铁",
            amountCents = 400L,
            walletId = 4L
        )
        val (afterInsert, savedFirst) = QuickTemplateHelper.upsertTemplate(emptyList(), first)
        assertEquals(1, afterInsert.size)
        assertEquals("通勤地铁", savedFirst.title)

        val (afterDup, copyItem) = QuickTemplateHelper.duplicateTemplate(afterInsert, savedFirst.id, newId = 20L)
        assertNotNull(copyItem)
        assertEquals(2, afterDup.size)
        assertEquals("通勤地铁 副本", copyItem!!.title)

        val afterMoveUp = QuickTemplateHelper.moveTemplate(afterDup, index = 1, delta = -1)
        assertEquals(copyItem.id, afterMoveUp.first().id)

        val edited = copyItem.copy(title = "晚高峰打车", amountCents = 3500L, reimbursable = true)
        val (afterUpdate, updatedItem) = QuickTemplateHelper.upsertTemplate(afterMoveUp, edited)
        assertEquals(2, afterUpdate.size)
        assertEquals("晚高峰打车", updatedItem.title)
        assertTrue(updatedItem.reimbursable)
    }

    @Test
    fun formValidation_interceptsInvalidInputsAndAcceptsDynamicZeroAmount() {
        assertTrue(
            QuickTemplateHelper.validateTemplateInput(
                title = "   ",
                type = TransactionType.EXPENSE,
                amountInput = "12",
                walletId = 4L,
                targetWalletId = null
            ).isFailure
        )
        assertTrue(
            QuickTemplateHelper.validateTemplateInput(
                title = "这是一个超过二十个字符长度限制的超级无敌长模板名称测试",
                type = TransactionType.EXPENSE,
                amountInput = "12",
                walletId = 4L,
                targetWalletId = null
            ).isFailure
        )
        assertTrue(
            QuickTemplateHelper.validateTemplateInput(
                title = "负数测试",
                type = TransactionType.EXPENSE,
                amountInput = "-5.5",
                walletId = 4L,
                targetWalletId = null
            ).isFailure
        )
        assertEquals(
            0L,
            QuickTemplateHelper.validateTemplateInput(
                title = "日常超市",
                type = TransactionType.EXPENSE,
                amountInput = "",
                walletId = 4L,
                targetWalletId = null
            ).getOrThrow()
        )
        assertTrue(
            QuickTemplateHelper.validateTemplateInput(
                title = "同名账户转账",
                type = TransactionType.TRANSFER,
                amountInput = "100",
                walletId = 4L,
                targetWalletId = 4L
            ).isFailure
        )
        assertEquals(
            10_000L,
            QuickTemplateHelper.validateTemplateInput(
                title = "信用卡还款",
                type = TransactionType.TRANSFER,
                amountInput = "100",
                walletId = 1L,
                targetWalletId = 9L
            ).getOrThrow()
        )
    }

    @Test
    fun quickRecordOutcome_routesZeroAmountAndDanglingWalletsProperly() {
        val dynamicTpl = QuickBookkeepingTemplate(
            id = 1L,
            title = "日常超市",
            typeName = "EXPENSE",
            category = "购物-超市",
            amountCents = 0L,
            walletId = 4L
        )
        val dynamicOutcome = QuickTemplateHelper.evaluateQuickRecordOutcome(dynamicTpl, sampleWallets, "默认账本")
        assertTrue(dynamicOutcome is TemplateExecutionOutcome.OpenInCalculator)

        // 绑定钱包已被删除的支出模板自动回退到首个非负债有效钱包（id=1L）
        val deletedWalletExpense = QuickBookkeepingTemplate(
            id = 2L,
            title = "出差打车",
            typeName = "EXPENSE",
            category = "交通-打车",
            amountCents = 4200L,
            walletId = 999L,
            reimbursable = true,
            tags = "出差,差旅交通"
        )
        val expOutcome = QuickTemplateHelper.evaluateQuickRecordOutcome(deletedWalletExpense, sampleWallets, "默认账本")
        assertTrue(expOutcome is TemplateExecutionOutcome.ReadyToRecord)
        val recordedExp = (expOutcome as TemplateExecutionOutcome.ReadyToRecord).transaction
        assertEquals(1L, recordedExp.sourceWalletId)
        assertTrue(recordedExp.reimbursable)

        // 资产账户转入信用卡账户自动升级为 REPAYMENT
        val repayTpl = QuickBookkeepingTemplate(
            id = 3L,
            title = "还招行信用卡",
            typeName = "TRANSFER",
            category = "内部转账",
            amountCents = 50_000L,
            walletId = 1L,
            targetWalletId = 9L
        )
        val repayOutcome = QuickTemplateHelper.evaluateQuickRecordOutcome(repayTpl, sampleWallets, "默认账本")
        assertTrue(repayOutcome is TemplateExecutionOutcome.ReadyToRecord)
        val recordedRepay = (repayOutcome as TemplateExecutionOutcome.ReadyToRecord).transaction
        assertEquals(TransactionType.REPAYMENT, recordedRepay.type)
        assertEquals("信用还款", recordedRepay.category)

        // 转账模板目标账户已删除时自动转为拉起计算器确认
        val brokenTransferTpl = repayTpl.copy(targetWalletId = 888L)
        val brokenOutcome = QuickTemplateHelper.evaluateQuickRecordOutcome(brokenTransferTpl, sampleWallets, "默认账本")
        assertTrue(brokenOutcome is TemplateExecutionOutcome.OpenInCalculator)
    }

    @Test
    fun categoryAndTagCascade_updatesTemplatesAndSkipsTransferOnCategoryRename() {
        val list = listOf(
            QuickBookkeepingTemplate(
                id = 1L,
                title = "午餐",
                typeName = "EXPENSE",
                category = "餐饮-三餐",
                amountCents = 2500L,
                tags = "早餐,出差"
            ),
            QuickBookkeepingTemplate(
                id = 2L,
                title = "转账模板",
                typeName = "TRANSFER",
                category = "内部转账",
                amountCents = 10000L,
                walletId = 1L,
                targetWalletId = 4L,
                tags = "出差"
            )
        )
        val (catCascaded, catChanged) = QuickTemplateHelper.cascadeCategoryInTemplates(
            templates = list,
            oldCategory = "餐饮",
            newCategory = "美食",
            includeSubPrefix = true,
            preserveSubSuffix = true,
            isExpense = true
        )
        assertTrue(catChanged)
        assertEquals("美食-三餐", catCascaded[0].category)
        assertEquals("内部转账", catCascaded[1].category)

        val (tagCascaded, tagChanged) = QuickTemplateHelper.cascadeTagInTemplates(
            templates = catCascaded,
            oldTag = "出差",
            newTag = "商务差旅"
        )
        assertTrue(tagChanged)
        assertEquals("早餐,商务差旅", tagCascaded[0].tags)
        assertEquals("商务差旅", tagCascaded[1].tags)
    }

    @Test
    fun fullBackupJson_exportsAndRestoresQuickTemplatesWithCompatibility() {
        val templates = listOf(
            QuickBookkeepingTemplate(
                id = 77L,
                title = "瑞幸咖啡",
                typeName = "EXPENSE",
                category = "餐饮-咖啡",
                amountCents = 990L,
                walletId = 4L,
                tags = "早餐",
                reimbursable = true
            )
        )
        val exportedJson = BackupAndCsvTransferEngine.exportFullBackupJson(
            ledgers = listOf("默认账本"),
            monthlyBudgetCents = 300_000L,
            wallets = sampleWallets,
            transactions = emptyList(),
            recurringRules = emptyList(),
            merchantMemories = emptyList(),
            quickTemplates = templates
        )
        assertTrue(exportedJson.contains("quickTemplates"))
        assertTrue(exportedJson.contains("瑞幸咖啡"))

        val parsed = BackupAndCsvTransferEngine.parseFullBackupJson(exportedJson)
        assertNotNull(parsed)
        val restoredSnapshot = parsed!!.third
        assertEquals(1, restoredSnapshot.quickTemplates.size)
        assertEquals("瑞幸咖啡", restoredSnapshot.quickTemplates.first().title)
        assertTrue(restoredSnapshot.quickTemplates.first().reimbursable)

        // 旧版不含 quickTemplates 字段的备份 JSON 也能无损解析
        val oldBackupJson = """{"schemaVersion":1,"appName":"MoJi","ledgers":["默认账本"]}"""
        val parsedOld = BackupAndCsvTransferEngine.parseFullBackupJson(oldBackupJson)
        assertNotNull(parsedOld)
        assertTrue(parsedOld!!.third.quickTemplates.isEmpty())
    }
}
