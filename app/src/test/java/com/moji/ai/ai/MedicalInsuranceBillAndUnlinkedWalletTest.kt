package com.moji.ai.ai

import com.moji.ai.data.local.AppDatabase
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.TagCatalog
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType
import com.moji.ai.service.CapturePipelineCoordinator
import com.moji.ai.ui.theme.formatWalletDisplayName
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class MedicalInsuranceBillAndUnlinkedWalletTest {

    private lateinit var engine: AiAccountingEngine
    private lateinit var standardWalletsWithoutMedical: List<WalletEntity>

    @Before
    fun setUp() {
        engine = AiAccountingEngine(configManager = null, merchantMemoryManager = null)
        standardWalletsWithoutMedical = listOf(
            WalletEntity(
                id = 1L,
                name = "花呗",
                type = WalletType.CREDIT,
                subTypeLabel = "蚂蚁花呗",
                brandCode = "HUABEI",
                aliases = "花呗,蚂蚁花呗,信用购"
            ),
            WalletEntity(
                id = 2L,
                name = "招商银行信用卡 3354",
                type = WalletType.CREDIT,
                subTypeLabel = "信用卡",
                brandCode = "CMB",
                cardLast4 = "3354",
                aliases = "招商银行信用卡,3354"
            ),
            WalletEntity(
                id = 3L,
                name = "微信零钱",
                type = WalletType.DIGITAL_WALLET,
                subTypeLabel = "微信钱包",
                brandCode = "WECHAT",
                aliases = "微信,微信支付,零钱,微信零钱"
            )
        )
    }

    @Test
    fun case1_pureMedicalInsuranceWithoutMedicalWallet_records4190MedicalTagAndNullWallet() = runBlocking {
        val rawAlipayMedical = "西安怡康医药连锁有限责任公司阿房四路一店 | -0.00 | 0元 | 立即领取10积分 | 支付成功 | 医保支付 | -41.90 | 账单分类 | 医疗健康 | 创建时间 | 2026-11-18 20:59:59 | 订单号 | 2026111822001438091426619645"

        val tx = engine.parseCapturedText(
            rawText = rawAlipayMedical,
            sourceAppPackage = "com.eg.android.AlipayGphone",
            captureMode = "AI_ACCESSIBILITY",
            wallets = standardWalletsWithoutMedical,
            recentExpenses = emptyList()
        )

        assertNotNull(tx)
        assertEquals(TransactionType.EXPENSE, tx!!.type)
        assertEquals(4190L, tx.amountCents)
        assertEquals(0L, tx.discountCents)
        assertTrue("分类应为医疗或其子类，实际为 ${tx.category}", tx.category == "医疗" || tx.category.startsWith("医疗-"))
        assertEquals("西安怡康医药连锁有限责任公司阿房四路一店", tx.merchant)
        assertNull("账本无医保钱包时 sourceWalletId 必须挂空 null", tx.sourceWalletId)
        assertNull(tx.targetWalletId)
        assertTrue("应自动打上医保标签，实际 tags=${tx.tags}", TagCatalog.containsTag(tx.tags, "医保"))
        assertEquals("医保支付", tx.paymentMethodRaw)
        assertEquals("2026111822001438091426619645", tx.orderKey)

        val cal = Calendar.getInstance().apply { timeInMillis = tx.timestamp }
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, cal.get(Calendar.MONTH))
        assertEquals(18, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(20, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, cal.get(Calendar.MINUTE))
        assertEquals(59, cal.get(Calendar.SECOND))

        // 验证即使账本包含全套默认钱包（含支付宝账户余额），纯医保无医保钱包时仍严格挂空 null
        val txWithDefaultWallets = engine.parseCapturedText(
            rawText = rawAlipayMedical,
            sourceAppPackage = "com.eg.android.AlipayGphone",
            captureMode = "AI_ACCESSIBILITY",
            wallets = AppDatabase.defaultWallets(),
            recentExpenses = emptyList()
        )
        assertNotNull(txWithDefaultWallets)
        assertNull(txWithDefaultWallets!!.sourceWalletId)
        assertEquals(4190L, txWithDefaultWallets.amountCents)

        // 验证即使历史商户记忆绑定了普通花呗钱包（preferredWalletId = 1L），纯医保且无医保类钱包时仍严格保持 null
        val memoryWithHuabei = MerchantMemoryManager(
            initialEntries = listOf(
                MerchantMemoryEntry(
                    merchantKeyword = "怡康医药",
                    category = "医疗-买药",
                    preferredWalletId = 1L
                )
            )
        )
        val engineWithMemory = AiAccountingEngine(configManager = null, merchantMemoryManager = memoryWithHuabei)
        val txWithMemory = engineWithMemory.parseCapturedText(
            rawText = rawAlipayMedical,
            sourceAppPackage = "com.eg.android.AlipayGphone",
            captureMode = "AI_ACCESSIBILITY",
            wallets = standardWalletsWithoutMedical,
            recentExpenses = emptyList()
        )
        assertNotNull(txWithMemory)
        assertNull("纯医保无医保类钱包时不得套用商户记忆中的花呗钱包", txWithMemory!!.sourceWalletId)
        assertEquals("医疗-买药", txWithMemory.category)
    }

    @Test
    fun case2_pureMedicalInsuranceWithMedicalCardWallet_matchesMedicalWalletAutomatically() = runBlocking {
        val medicalWallet = WalletEntity(
            id = 88L,
            name = "医保卡",
            type = WalletType.CASH_OTHER,
            subTypeLabel = "医保账户",
            brandCode = "MEDICAL_CARD",
            aliases = "医保,医保卡,社保卡"
        )
        val walletsWithMedical = standardWalletsWithoutMedical + medicalWallet
        val rawAlipayMedical = "西安怡康医药连锁有限责任公司阿房四路一店 | -0.00 | 0元 | 立即领取10积分 | 支付成功 | 医保支付 | -41.90 | 账单分类 | 医疗健康 | 创建时间 | 2026-11-18 20:59:59 | 订单号 | 2026111822001438091426619645"

        val tx = engine.parseCapturedText(
            rawText = rawAlipayMedical,
            sourceAppPackage = "com.eg.android.AlipayGphone",
            captureMode = "AI_ACCESSIBILITY",
            wallets = walletsWithMedical,
            recentExpenses = emptyList()
        )

        assertNotNull(tx)
        assertEquals(4190L, tx!!.amountCents)
        assertEquals(88L, tx.sourceWalletId)
        assertTrue(TagCatalog.containsTag(tx.tags, "医保"))
        assertEquals("医保支付", tx.paymentMethodRaw)
    }

    @Test
    fun pureMedicalInsurance_pooledAndPersonalSubItems_sumAccuratelyWithoutDoubleCounting() = runBlocking {
        val splitItemsOnly = "某市中心医院 | -0.00 | 支付成功 | 医保统筹 | -30.00 | 医保个账 | -11.90 | 创建时间 | 2026-11-18 21:01:00 | 订单号 | 2026111822001438091426619777"
        val txSplit = engine.parseCapturedText(
            rawText = splitItemsOnly,
            sourceAppPackage = "com.eg.android.AlipayGphone",
            captureMode = "AI_ACCESSIBILITY",
            wallets = standardWalletsWithoutMedical,
            recentExpenses = emptyList()
        )
        assertNotNull(txSplit)
        assertEquals(4190L, txSplit!!.amountCents)
        assertEquals("某市中心医院", txSplit.merchant)
        assertEquals("医疗-门诊", txSplit.category)
        assertTrue(TagCatalog.containsTag(txSplit.tags, "医保"))

        val summaryAndSplitBothPresent = "某市中心医院 | -0.00 | 支付成功 | 医保支付 | -41.90 | 医保统筹 | -30.00 | 医保个账 | -11.90 | 创建时间 | 2026-11-18 21:01:00 | 订单号 | 2026111822001438091426619778"
        val txBoth = engine.parseCapturedText(
            rawText = summaryAndSplitBothPresent,
            sourceAppPackage = "com.eg.android.AlipayGphone",
            captureMode = "AI_ACCESSIBILITY",
            wallets = standardWalletsWithoutMedical,
            recentExpenses = emptyList()
        )
        assertNotNull(txBoth)
        assertEquals(4190L, txBoth!!.amountCents)
    }

    @Test
    fun case3_mixedMedicalInsuranceAndSelfPay_recordsSelfPayAmountMedicalDiscountAndHuabeiWallet() = runBlocking {
        val rawMixedMedical = "西安怡康医药连锁有限责任公司阿房四路一店 | -23.10 | 支付成功 | 订单金额 | 65.00 | 医保支付 | -41.90 | 付款方式 | 花呗 | 创建时间 | 2026-11-18 21:05:00 | 订单号 | 2026111822001438091426619999"

        val tx = engine.parseCapturedText(
            rawText = rawMixedMedical,
            sourceAppPackage = "com.eg.android.AlipayGphone",
            captureMode = "AI_ACCESSIBILITY",
            wallets = standardWalletsWithoutMedical,
            recentExpenses = emptyList()
        )

        assertNotNull(tx)
        assertEquals(TransactionType.EXPENSE, tx!!.type)
        assertEquals(2310L, tx.amountCents)
        assertEquals(4190L, tx.discountCents)
        assertEquals(1L, tx.sourceWalletId)
        assertEquals("西安怡康医药连锁有限责任公司阿房四路一店", tx.merchant)
        assertTrue(TagCatalog.containsTag(tx.tags, "医保"))
    }

    @Test
    fun case5_nonMedicalZeroAmountPages_areRejectedAndReturnNull() = runBlocking {
        val rawClosedZero = "某某商户 | -0.00 | 0元 | 交易关闭 | 创建时间 | 2026-11-18 21:08:42"
        val parsedClosed = engine.parseCapturedText(
            rawText = rawClosedZero,
            sourceAppPackage = "com.eg.android.AlipayGphone",
            captureMode = "AI_ACCESSIBILITY",
            wallets = standardWalletsWithoutMedical,
            recentExpenses = emptyList()
        )
        assertNull(parsedClosed)

        val rawPlainZero = "某某商户 | -0.00 | 0元 | 支付成功 | 创建时间 | 2026-11-18 21:08:42 | 订单号 | 2026111822001438091426610000"
        val parsedPlainZero = engine.parseCapturedText(
            rawText = rawPlainZero,
            sourceAppPackage = "com.eg.android.AlipayGphone",
            captureMode = "AI_ACCESSIBILITY",
            wallets = standardWalletsWithoutMedical,
            recentExpenses = emptyList()
        )
        assertNull(parsedPlainZero)
    }

    @Test
    fun case6_unknownPaymentMethodAlipayExpense_returnsNullWalletInsteadOfGuessingHuabei() = runBlocking {
        val walletsOnlyHuabeiAndCredit = listOf(
            WalletEntity(
                id = 1L,
                name = "花呗",
                type = WalletType.CREDIT,
                subTypeLabel = "蚂蚁花呗",
                brandCode = "HUABEI",
                aliases = "花呗,蚂蚁花呗"
            ),
            WalletEntity(
                id = 2L,
                name = "招商银行信用卡 3354",
                type = WalletType.CREDIT,
                subTypeLabel = "信用卡",
                brandCode = "CMB",
                cardLast4 = "3354",
                aliases = "招商银行信用卡,3354"
            )
        )
        val rawUnknownPayAlipay = "[AI_ACCESSIBILITY | com.eg.android.AlipayGphone]: 账单详情 | 霸王茶姬(高新万达店) | -18.00 | 支付成功 | 创建时间 | 2026-11-18 19:20:10 | 支付宝交易号 | 2026111822001438091426888888"

        val tx = engine.parseCapturedText(
            rawText = rawUnknownPayAlipay,
            sourceAppPackage = "com.eg.android.AlipayGphone",
            captureMode = "AI_ACCESSIBILITY",
            wallets = walletsOnlyHuabeiAndCredit,
            recentExpenses = emptyList()
        )

        assertNotNull(tx)
        assertEquals(1800L, tx!!.amountCents)
        assertEquals("霸王茶姬(高新万达店)", tx.merchant)
        assertNull("未知支付方式严禁兜底猜测花呗或首个钱包", tx.sourceWalletId)

        // 包含全量默认钱包（含支付宝账户余额）时，仅因出现“支付宝交易号”也不得误匹配为账户余额
        val txAllDefault = engine.parseCapturedText(
            rawText = rawUnknownPayAlipay,
            sourceAppPackage = "com.eg.android.AlipayGphone",
            captureMode = "AI_ACCESSIBILITY",
            wallets = AppDatabase.defaultWallets(),
            recentExpenses = emptyList()
        )
        assertNotNull(txAllDefault)
        assertNull(txAllDefault!!.sourceWalletId)
    }

    @Test
    fun presetMedicalTag_walletDisplayFallback_windowGuard_and_multiFrameTagFusion() {
        // 1. 预设医保标签校验
        val medicalPreset = TagCatalog.defaultTags.firstOrNull { it.name == "医保" }
        assertNotNull(medicalPreset)
        assertEquals(0xFF14B8A6L, medicalPreset!!.colorHex)
        assertEquals("日常", medicalPreset.groupName)

        // 2. 空钱包统一展示为「不关联账户」
        assertEquals("不关联账户", formatWalletDisplayName(null))

        // 3. 多帧证据融合增量合并医保标签而不丢失既有标签
        val (mergedTags, added) = CapturePipelineCoordinator.mergeEnrichedTags("固定开支", "医保")
        assertEquals(listOf("医保"), added)
        assertEquals("固定开支,医保", mergedTags)
        val (unchangedTags, duplicateAdded) = CapturePipelineCoordinator.mergeEnrichedTags("医保", "医保")
        assertTrue(duplicateAdded.isEmpty())
        assertEquals("医保", unchangedTags)

        // 4. 窗口级防误触守卫放行支付宝纯医保账单详情页
        val nowMillis = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 18, 21, 0, 5)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val guardDecision = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.eg.android.AlipayGphone",
                verifiedActivity = "com.alipay.mobile.bill.detail.ui.BillDetailActivity",
                rawPageText = "账单详情 | 西安怡康医药连锁有限责任公司阿房四路一店 | -0.00 | 0元 | 立即领取10积分 | 支付成功 | 医保支付 | -41.90 | 账单分类 | 医疗健康 | 创建时间 | 2026-11-18 20:59:59 | 订单号 | 2026111822001438091426619645",
                nowMillis = nowMillis
            )
        )
        assertTrue("纯医保账单详情窗口应通过门禁，实际 reason=${guardDecision.reason}", guardDecision.accepted)
    }
}
