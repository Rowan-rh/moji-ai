package com.moji.ai.ai

import com.moji.ai.data.local.CaptureEvidenceEntity
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.model.CategoryCatalog
import com.moji.ai.data.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AgentBillTriageEngineTest {

    private fun buildTimestamp(hour: Int, minute: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.NOVEMBER)
            set(Calendar.DAY_OF_MONTH, 8)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Test
    fun pendingTriageConstant_isReservedAndMapsIconsProperly() {
        assertEquals("待梳理", CategoryCatalog.PENDING_TRIAGE_CATEGORY)
        assertTrue(CategoryCatalog.isPendingTriage("待梳理"))
        assertFalse(CategoryCatalog.isPendingTriage("餐饮-三餐"))

        // 「待梳理」与「平账」一样作为系统保留字，不允许被新建为常规自定义分类名称
        val validationError = CategoryCatalog.validateCategoryName(
            rawName = CategoryCatalog.PENDING_TRIAGE_CATEGORY,
            siblingNames = emptyList()
        )
        assertNotNull(validationError)

        // 自动匹配细分分类图标
        assertEquals("sub_meals.webp", CategoryCatalog.matchBestIconAsset("夜宵", "餐饮", isExpense = true))
        assertEquals("cat_sports.webp", CategoryCatalog.matchBestIconAsset("球类", "运动", isExpense = true))
        assertEquals("cat_pet.webp", CategoryCatalog.matchBestIconAsset("口粮", "宠物", isExpense = true))
        assertEquals("cat_app_software.webp", CategoryCatalog.matchBestIconAsset("云与AI", "应用软件", isExpense = true))
    }

    @Test
    fun analyzeBills_reclassifiesPendingBillsAndInducesNewCategoriesRulesAndMemories() = runBlocking {
        val engine = AgentBillTriageEngine()

        // 1. 深夜烧烤账单（处于「待梳理」状态，带截图证据） -> 应归纳出「餐饮-夜宵」新二级分类 + 规则 + 记忆
        val txLateNightBbq = TransactionEntity(
            id = 101L,
            type = TransactionType.EXPENSE,
            amountCents = 8600L,
            amountCnyCents = 8600L,
            category = CategoryCatalog.PENDING_TRIAGE_CATEGORY,
            merchant = "木屋烧烤",
            note = "和朋友吃烤串",
            sourceWalletId = 1L,
            screenshotPath = "/data/user/0/com.moji.ai.qa/files/bill_screenshots/bbq_101.webp",
            rawCapturedText = "支付成功 木屋烧烤 实付 ¥86.00 2026-11-08 23:18:10",
            timestamp = buildTimestamp(23, 18)
        )

        // 2. 羽毛球馆订场账单（处于「待梳理」状态，带截图证据） -> 应归纳出「运动-球类」新二级分类 + 规则
        val txBadminton = TransactionEntity(
            id = 102L,
            type = TransactionType.EXPENSE,
            amountCents = 12000L,
            amountCnyCents = 12000L,
            category = CategoryCatalog.PENDING_TRIAGE_CATEGORY,
            merchant = "奥体中心羽毛球馆",
            note = "周末双打场地预定",
            sourceWalletId = 1L,
            screenshotPath = "/data/user/0/com.moji.ai.qa/files/bill_screenshots/sport_102.webp",
            rawCapturedText = "奥体中心羽毛球馆 场地预定 120.00元",
            timestamp = buildTimestamp(15, 0)
        )

        // 3. 常规咖啡账单（处于「待梳理」状态，应直接归入现有「餐饮-咖啡」，不重复建议新建「餐饮-咖啡」）
        val txCoffee = TransactionEntity(
            id = 103L,
            type = TransactionType.EXPENSE,
            amountCents = 1650L,
            amountCnyCents = 1650L,
            category = CategoryCatalog.PENDING_TRIAGE_CATEGORY,
            merchant = "瑞幸咖啡",
            note = "生椰拿铁",
            sourceWalletId = 1L,
            timestamp = buildTimestamp(9, 15)
        )

        // 4. 系统平账流水（不应被扫描或修改）
        val txAnchor = TransactionEntity(
            id = 104L,
            type = TransactionType.EXPENSE,
            amountCents = 500L,
            amountCnyCents = 500L,
            category = CategoryCatalog.SYSTEM_BALANCE_ANCHOR_CATEGORY,
            merchant = "余额校准",
            sourceWalletId = 1L,
            timestamp = buildTimestamp(10, 0)
        )

        val report = engine.analyzeBills(
            transactions = listOf(txLateNightBbq, txBadminton, txCoffee, txAnchor),
            captureEvidences = listOf(
                CaptureEvidenceEntity(
                    id = 1L,
                    transactionId = 101L,
                    sourcePackage = "com.tencent.mm",
                    captureSource = "SCREENSHOT_OCR",
                    occurredAtMillis = txLateNightBbq.timestamp,
                    capturedAtMillis = txLateNightBbq.timestamp,
                    amountCents = 8600L,
                    rawText = "木屋烧烤 羊肉串 夜宵外卖",
                    screenshotPath = txLateNightBbq.screenshotPath
                )
            ),
            existingRules = emptyList(),
            existingMemories = emptyList(),
            expenseCategories = CategoryCatalog.defaultExpenseCategories,
            incomeCategories = CategoryCatalog.defaultIncomeCategories
        )

        assertEquals(3, report.scannedBillCount)
        assertEquals(3, report.pendingTriageCount)
        assertEquals(2, report.screenshotEvidenceCount)
        assertEquals(3, report.reclassifyItems.size)

        val bbqItem = report.reclassifyItems.first { it.transactionId == 101L }
        assertEquals("餐饮-夜宵", bbqItem.suggestedCategory)
        assertTrue(bbqItem.isNewCategoryProposal)

        val sportItem = report.reclassifyItems.first { it.transactionId == 102L }
        assertEquals("运动-球类", sportItem.suggestedCategory)
        assertTrue(sportItem.isNewCategoryProposal)

        val coffeeItem = report.reclassifyItems.first { it.transactionId == 103L }
        assertEquals("餐饮-咖啡", coffeeItem.suggestedCategory)
        assertFalse(coffeeItem.isNewCategoryProposal)

        // 验证建议新增分类包含「餐饮-夜宵」与「运动-球类」，且不包含已存在的「餐饮-咖啡」
        val proposedPaths = report.proposedNewCategories.map { it.fullCategoryPath }.toSet()
        assertTrue("餐饮-夜宵" in proposedPaths)
        assertTrue("运动-球类" in proposedPaths)
        assertFalse("餐饮-咖啡" in proposedPaths)

        // 验证自动生成了条件规则与商户记忆草案
        assertTrue(report.proposedRules.any { it.rule.targetCategory == "餐饮-夜宵" })
        assertTrue(report.proposedRules.any { it.rule.targetCategory == "运动-球类" })
        assertTrue(report.proposedMemories.any { it.merchantKeyword == "木屋烧烤" && it.targetCategory == "餐饮-夜宵" })
    }

    @Test
    fun analyzeBills_deduplicatesExistingRulesAndMemories() = runBlocking {
        val engine = AgentBillTriageEngine()
        val tx = TransactionEntity(
            id = 201L,
            type = TransactionType.EXPENSE,
            amountCents = 6800L,
            amountCnyCents = 6800L,
            category = CategoryCatalog.PENDING_TRIAGE_CATEGORY,
            merchant = "OpenAI",
            note = "Plus API充值",
            sourceWalletId = 1L,
            timestamp = buildTimestamp(14, 0)
        )
        val existingRules = listOf(
            SmartConditionRule(
                id = 1L,
                name = "AI与云服务订阅归类（OpenAI）",
                enabled = true,
                keywordContains = "OpenAI",
                targetCategory = "应用软件-云与AI"
            )
        )
        val existingMemories = listOf(
            MerchantMemoryEntry(
                merchantKeyword = "OpenAI",
                category = "应用软件-云与AI"
            )
        )

        val report = engine.analyzeBills(
            transactions = listOf(tx),
            existingRules = existingRules,
            existingMemories = existingMemories,
            expenseCategories = CategoryCatalog.defaultExpenseCategories
        )

        assertEquals(1, report.reclassifyItems.size)
        assertEquals("应用软件-云与AI", report.reclassifyItems.first().suggestedCategory)
        assertTrue(report.proposedRules.isEmpty())
        assertTrue(report.proposedMemories.isEmpty())
    }
}
