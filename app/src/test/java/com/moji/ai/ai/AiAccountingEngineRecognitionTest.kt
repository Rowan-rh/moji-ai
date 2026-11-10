package com.moji.ai.ai

import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType
import com.moji.ai.service.BillScreenshotStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Calendar

class AiAccountingEngineRecognitionTest {

    private lateinit var engine: AiAccountingEngine
    private lateinit var testWallets: List<WalletEntity>

    @Before
    fun setUp() {
        engine = AiAccountingEngine(configManager = null, merchantMemoryManager = null)
        testWallets = listOf(
            WalletEntity(
                id = 1L,
                name = "微信零钱",
                type = WalletType.DIGITAL_WALLET,
                subTypeLabel = "微信钱包",
                brandCode = "WECHAT",
                aliases = "微信,微信支付,零钱,微信零钱"
            ),
            WalletEntity(
                id = 2L,
                name = "账户余额",
                type = WalletType.DIGITAL_WALLET,
                subTypeLabel = "支付宝",
                brandCode = "ALIPAY",
                aliases = "支付宝,余额宝,账户余额"
            ),
            WalletEntity(
                id = 3L,
                name = "邮储银行储蓄卡 3870",
                type = WalletType.DEBIT_BANK,
                subTypeLabel = "储蓄卡",
                brandCode = "PSBC",
                cardLast4 = "3870",
                aliases = "邮储银行储蓄卡,邮储银行,3870"
            ),
            WalletEntity(
                id = 4L,
                name = "招商银行信用卡 3354",
                type = WalletType.CREDIT,
                subTypeLabel = "信用卡",
                brandCode = "CMB",
                cardLast4 = "3354",
                aliases = "招商银行信用卡,3354"
            )
        )
    }

    @Test
    fun wechatQrPaymentDetail_ignoresHeaderButtonsAndExtractsRealMerchant() {
        val rawDrunk = "[AI_ACCESSIBILITY | com.tencent.mm]: 返回 | Drunk | 联系 | 主页 | 交易详情 | -2.20 | 扫二维码付款-给Drunk | 当前状态 | 支付成功 | 收款方备注 | 二维码收款 | 支付方式 | 零钱 | 转账时间 | 2026年11月05日 17:46:44 | 转账单号 | 10001073012026110500728276841092 | 交易服务 | 对订单有疑惑 | 发起群收款 | 本服务由财付通提供"
        val parsedDrunk = engine.localSmartFallbackParse(
            rawText = rawDrunk,
            sourceAppPackage = "com.tencent.mm",
            wallets = testWallets,
            recentExpenses = emptyList()
        )
        assertNotNull(parsedDrunk)
        assertEquals(TransactionType.EXPENSE.name, parsedDrunk!!.type)
        assertEquals(2.20, parsedDrunk.amount, 0.001)
        assertEquals("Drunk", parsedDrunk.merchant)
        assertEquals("10001073012026110500728276841092", parsedDrunk.orderKey)
        assertEquals(1L, parsedDrunk.sourceWalletId)

        val rawVeg = "[AI_ACCESSIBILITY | com.tencent.mm]: 返回 | 蔬菜李丨未央店 | 联系 | 主页 | 交易详情 | 返回 | 蔬菜李丨未央店 | 联系 | 主页 | 交易详情 | -1.30 | 扫二维码付款-给蔬菜李丨未央店 | 当前状态 | 支付成功 | 收款方备注 | 二维码收款 | 支付方式 | 零钱 | 转账时间 | 2026年11月05日 17:45:31 | 转账单号 | 10001073012026110500993151520117 | 交易服务 | 对订单有疑惑 | 发起群收款 | 本服务由财付通提供"
        val parsedVeg = engine.localSmartFallbackParse(
            rawText = rawVeg,
            sourceAppPackage = "com.tencent.mm",
            wallets = testWallets,
            recentExpenses = emptyList()
        )
        assertNotNull(parsedVeg)
        assertEquals(1.30, parsedVeg!!.amount, 0.001)
        assertEquals("蔬菜李丨未央店", parsedVeg.merchant)
        assertEquals("10001073012026110500993151520117", parsedVeg.orderKey)
    }

    @Test
    fun promoChatAndMonthlyCarrierStatement_areFilteredAsNonTransactions() {
        val wechatPromo = "[AI_NOTIFICATION | com.tencent.mm]: 尊人爱己 | [火]双十一限定福利来袭[火]\n       111.1元，\n[表情]免费送美甲本甲[表情]1次\n[表情]面部清洁1次\n[表情]头疗1次\n[表情]国医ai智能体检1次\n[表情]身体特调1次 \n变美、疏通、养身一站式安排[闪烁]\n名额有限，只限20个名额，已转账为准"
        assertTrue(AiAccountingEngine.isNonTransactionNotificationOrPromo(wechatPromo, "com.tencent.mm", "AI_NOTIFICATION"))
        assertNull(
            engine.localSmartFallbackParse(
                rawText = wechatPromo,
                sourceAppPackage = "com.tencent.mm",
                wallets = testWallets,
                recentExpenses = emptyList()
            )
        )

        val mobileMonthlyBill = "[AI_NOTIFICATION | com.android.mms]: 10086 | 【话费账单】尊敬的136****6957客户，您10月01日-10月31日共消费11.00元。\n\n主要消费项目包括：\n-套餐及固定费11.00元；\n访问中国移动APP查询费用详情，请点击 https://dx.10086.cn/A/iAY2HA 。"
        assertTrue(AiAccountingEngine.isNonTransactionNotificationOrPromo(mobileMonthlyBill, "com.android.mms", "AI_NOTIFICATION"))
        assertNull(
            engine.localSmartFallbackParse(
                rawText = mobileMonthlyBill,
                sourceAppPackage = "com.android.mms",
                wallets = testWallets,
                recentExpenses = emptyList()
            )
        )
    }

    @Test
    fun bankSms_parsesShortYearDateIncomeExpenseRepaymentAndCardTail3870() {
        // 1. 网联入账 +300.00元（严禁误记为餐饮支出，也严禁把余额1257.79元误当成交易金额）
        val smsNetUnionIncome = "[AI_NOTIFICATION | com.android.mms]: 95580 | 【邮储银行】26年11月08日20:12您尾号3870账户网联入账，收入金额300.00元，余额1257.79元"
        assertFalse(AiAccountingEngine.isNonTransactionNotificationOrPromo(smsNetUnionIncome, "com.android.mms", "AI_NOTIFICATION"))
        val parsedIncome = engine.localSmartFallbackParse(
            rawText = smsNetUnionIncome,
            sourceAppPackage = "com.android.mms",
            wallets = testWallets,
            recentExpenses = emptyList()
        )
        assertNotNull(parsedIncome)
        assertEquals(TransactionType.INCOME.name, parsedIncome!!.type)
        assertEquals(300.00, parsedIncome.amount, 0.001)
        assertEquals("其他", parsedIncome.category)
        assertEquals("网联入账", parsedIncome.merchant)
        assertEquals("邮储银行储蓄卡(3870)", parsedIncome.paymentMethodRaw)
        assertEquals(3L, parsedIncome.targetWalletId)
        assertNotNull(parsedIncome.occurredAtMillis)
        val cal = Calendar.getInstance().apply { timeInMillis = parsedIncome.occurredAtMillis!! }
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, cal.get(Calendar.MONTH))
        assertEquals(8, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(20, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(12, cal.get(Calendar.MINUTE))

        // 2. 银联入账 +0.30元
        val smsUnionPayIncome = "[AI_NOTIFICATION | com.android.mms]: 95580 | 【邮储银行】26年11月09日22:13您尾号3870账户银联入账，收入金额0.30元，余额1353.19元"
        val parsedUnionPay = engine.localSmartFallbackParse(
            rawText = smsUnionPayIncome,
            sourceAppPackage = "com.android.mms",
            wallets = testWallets,
            recentExpenses = emptyList()
        )
        assertNotNull(parsedUnionPay)
        assertEquals(TransactionType.INCOME.name, parsedUnionPay!!.type)
        assertEquals(0.30, parsedUnionPay.amount, 0.001)
        assertEquals("银联入账", parsedUnionPay.merchant)
        assertEquals(3L, parsedUnionPay.targetWalletId)

        // 3. 快捷支付-抖音支付 -4.90元 -> 商户提取为「抖音支付」，扣款账户匹配尾号 3870 邮储储蓄卡
        val smsDouyinExpense = "[AI_NOTIFICATION | com.android.mms]: 95580 | 【邮储银行】26年11月09日18:48您尾号3870账户快捷支付-抖音支付，支出金额4.90元，余额1352.89元"
        val parsedExpense = engine.localSmartFallbackParse(
            rawText = smsDouyinExpense,
            sourceAppPackage = "com.android.mms",
            wallets = testWallets,
            recentExpenses = emptyList()
        )
        assertNotNull(parsedExpense)
        assertEquals(TransactionType.EXPENSE.name, parsedExpense!!.type)
        assertEquals(4.90, parsedExpense.amount, 0.001)
        assertEquals("抖音支付", parsedExpense.merchant)
        assertEquals("邮储银行储蓄卡(3870)", parsedExpense.paymentMethodRaw)
        assertEquals(3L, parsedExpense.sourceWalletId)

        // 4. 自动还款 -2.30元 -> 识别为 REPAYMENT（信用还款），从储蓄卡(3870)转入信用账户
        val smsAutoRepay = "[AI_NOTIFICATION | com.android.mms]: 95580 | 【邮储银行】26年11月10日18:00您尾号3870账户自动还款，支出金额2.30元，余额1350.89元"
        val parsedRepay = engine.localSmartFallbackParse(
            rawText = smsAutoRepay,
            sourceAppPackage = "com.android.mms",
            wallets = testWallets,
            recentExpenses = emptyList()
        )
        assertNotNull(parsedRepay)
        assertEquals(TransactionType.REPAYMENT.name, parsedRepay!!.type)
        assertEquals(2.30, parsedRepay.amount, 0.001)
        assertEquals("信用还款", parsedRepay.category)
        assertEquals(3L, parsedRepay.sourceWalletId)
        assertEquals(4L, parsedRepay.targetWalletId)
    }

    @Test
    fun douyinCompletionWaterfallWithoutActualPaid_isRejectedInsteadOfPickingOneYuanNewbiePrice() {
        val rawDouyinWaterfall = "[AI_ACCESSIBILITY | com.ss.android.ugc.aweme]: 抖音支付 (邮储银行储蓄卡3870) 付款 领600积分 | 支付成功 | ¥ | 1 | 新人价 | 店铺销量1万+ | ¥ | 39 | .9 | 已售1万+件 | ¥ | 1 | 新人价 | 已售1万+件 | ¥ | 1 | .66 | 券后价 | 已售1000+件 | ¥ | 2 | .8 | 新人价 | 已售5000+件 | 直播中，六一快乐，热度值60，按钮 | 直播中 | 六一快乐 | 60 | ¥ | 1 | 券后价 | 已售1000+件 | ¥ | 1 | .06 | 已售9000+件 | 返回"
        val stripped = AiAccountingEngine.stripEcommerceRecommendationFooter(rawDouyinWaterfall)
        assertFalse(stripped.contains("新人价"))
        assertFalse(stripped.contains("券后价"))

        val parsed = engine.localSmartFallbackParse(
            rawText = rawDouyinWaterfall,
            sourceAppPackage = "com.ss.android.ugc.aweme",
            wallets = testWallets,
            recentExpenses = emptyList()
        )
        assertNull(parsed)
    }

    @Test
    fun wechatPendingReceiptCounterparty_unwrapsPendingPrefixAndMatchesInlineBankCardLast4() {
        val rawWechatTransfer = "[AI_ACCESSIBILITY | com.tencent.mm]: 支付成功 | 待心始终如一确认收款 | ￥203.85 | 转账时间 | 2026-11-09 13:24:23 | 完成"
        val parsed = engine.localSmartFallbackParse(
            rawText = rawWechatTransfer,
            sourceAppPackage = "com.tencent.mm",
            wallets = testWallets,
            recentExpenses = emptyList()
        )
        assertNotNull(parsed)
        assertEquals(TransactionType.EXPENSE.name, parsed!!.type)
        assertEquals(203.85, parsed.amount, 0.001)
        assertEquals("心始终如一", parsed.merchant)

        val matchedWallet = engine.matchWalletForPayment(
            payMethodRaw = "抖音支付 (邮储银行储蓄卡3870)",
            fullText = "抖音支付 (邮储银行储蓄卡3870) 付款",
            sourceAppPackage = "com.ss.android.ugc.aweme",
            wallets = testWallets
        )
        assertNotNull(matchedWallet)
        assertEquals(3L, matchedWallet!!.id)
    }

    @Test
    fun notificationWithoutExplicitTime_fallsBackToReferenceMillisOnlyForNotificationOrSandbox() {
        val refMillis = 1_762_776_000_000L
        val wechatPayNotif = "微信支付 | 微信支付收款25.00元(朋友到店)"
        val parsedNotif = engine.localSmartFallbackParse(
            rawText = wechatPayNotif,
            sourceAppPackage = "com.tencent.mm",
            wallets = testWallets,
            recentExpenses = emptyList(),
            referenceMillis = refMillis,
            captureMode = "AI_NOTIFICATION"
        )
        assertNotNull(parsedNotif)
        assertEquals(25.00, parsedNotif!!.amount, 0.001)
        assertEquals(refMillis, parsedNotif.occurredAtMillis)

        // 无障碍页面若无明确交易时间仍必须严格拦截，防止把过渡完成页误入账
        val bareCompletionPage = "支付成功 | 测试商户 | ¥25.00 | 完成"
        val parsedAccessibility = engine.localSmartFallbackParse(
            rawText = bareCompletionPage,
            sourceAppPackage = "com.tencent.mm",
            wallets = testWallets,
            recentExpenses = emptyList(),
            referenceMillis = refMillis,
            captureMode = "AI_ACCESSIBILITY"
        )
        assertNull(parsedAccessibility)
    }

    @Test
    fun chineseTimeWithoutSecondsAndCrossYearMonthDay_parseAccurately() {
        val refCal = Calendar.getInstance().apply {
            set(2027, Calendar.JANUARY, 2, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val cnTimeNoSeconds = "账单详情 | 瑞幸咖啡 | -12.90 | 支付时间 | 2026年11月08日 15时56分 | 支付方式 | 零钱"
        val parsed = engine.localSmartFallbackParse(
            rawText = cnTimeNoSeconds,
            sourceAppPackage = "com.tencent.mm",
            wallets = testWallets,
            recentExpenses = emptyList(),
            referenceMillis = refCal.timeInMillis,
            captureMode = "AI_ACCESSIBILITY"
        )
        assertNotNull(parsed)
        val parsedCal = Calendar.getInstance().apply { timeInMillis = parsed!!.occurredAtMillis!! }
        assertEquals(2026, parsedCal.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, parsedCal.get(Calendar.MONTH))
        assertEquals(8, parsedCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(15, parsedCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(56, parsedCal.get(Calendar.MINUTE))
        assertEquals(0, parsedCal.get(Calendar.SECOND))

        val crossYearMillis = AiAccountingEngine.extractOccurredTimeMillis(
            text = "支付时间 12-31 23:58",
            referenceMillis = refCal.timeInMillis
        )
        assertNotNull(crossYearMillis)
        val crossYearCal = Calendar.getInstance().apply { timeInMillis = crossYearMillis!! }
        assertEquals(2026, crossYearCal.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, crossYearCal.get(Calendar.MONTH))
        assertEquals(31, crossYearCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun parenthesizedYear_doesNotFalseMatchWalletCardLast4() {
        val walletsWith2026 = testWallets + WalletEntity(
            id = 99L,
            name = "测试卡 2026",
            type = WalletType.CREDIT,
            cardLast4 = "2026"
        )
        val matched = engine.matchWalletForPayment(
            payMethodRaw = "零钱",
            fullText = "账单时间 (2026-11-10) 支付方式 零钱",
            sourceAppPackage = "com.tencent.mm",
            wallets = walletsWith2026
        )
        assertNotNull(matched)
        assertEquals(1L, matched!!.id)
    }

    @Test
    fun refundOffset_requiresMatchingOrderKeyOrConsistentMerchantAndAmount() {
        val expenseTime = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 9, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val sbuxExpense = TransactionEntity(
            id = 10L,
            type = TransactionType.EXPENSE,
            amountCents = 5000L,
            amountCnyCents = 5000L,
            category = "餐饮-咖啡",
            merchant = "星巴克",
            sourceWalletId = 4L,
            orderKey = "ORD_SBUX_001",
            timestamp = expenseTime
        )

        val unrelatedRefund = "账单详情 | 瑞幸咖啡 | +50.00 | 当前状态 | 退款成功 | 退款时间 | 2026-11-10 12:00:00"
        val parsedUnrelated = engine.localSmartFallbackParse(
            rawText = unrelatedRefund,
            sourceAppPackage = "com.tencent.mm",
            wallets = testWallets,
            recentExpenses = listOf(sbuxExpense),
            captureMode = "AI_ACCESSIBILITY"
        )
        assertNotNull(parsedUnrelated)
        assertEquals(TransactionType.REFUND_OFFSET.name, parsedUnrelated!!.type)
        assertNull(parsedUnrelated.relatedExpenseId)

        val genuineRefund = "账单详情 | 星巴克 | +50.00 | 当前状态 | 退款成功 | 退款时间 | 2026-11-10 12:00:00"
        val parsedGenuine = engine.localSmartFallbackParse(
            rawText = genuineRefund,
            sourceAppPackage = "com.tencent.mm",
            wallets = testWallets,
            recentExpenses = listOf(sbuxExpense),
            captureMode = "AI_ACCESSIBILITY"
        )
        assertNotNull(parsedGenuine)
        assertEquals(10L, parsedGenuine!!.relatedExpenseId)
    }

    @Test
    fun billScreenshotPathValidation_blocksTraversalAndNonWebpFiles() {
        val tempRoot = Files.createTempDirectory("moji_shot_test").toFile()
        try {
            val shotDir = File(tempRoot, "files/bill_screenshots").apply { mkdirs() }
            val dbDir = File(tempRoot, "databases").apply { mkdirs() }
            val validWebp = File(shotDir, "bill_1001_abcdef.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val fakeDbInShotDir = File(shotDir, "moji_ai.db").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val realDb = File(dbDir, "moji_ai.db").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val traversalRef = File(shotDir, "../../databases/moji_ai.db")

            assertTrue(BillScreenshotStorage.isSafeScreenshotFile(validWebp))
            assertFalse(BillScreenshotStorage.isSafeScreenshotFile(fakeDbInShotDir))
            assertFalse(BillScreenshotStorage.isSafeScreenshotFile(realDb))
            assertFalse(BillScreenshotStorage.isSafeScreenshotFile(traversalRef))

            BillScreenshotStorage.deleteIfExists(traversalRef.path)
            assertTrue(realDb.exists())
            BillScreenshotStorage.deleteIfExists(validWebp.path)
            assertFalse(validWebp.exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
