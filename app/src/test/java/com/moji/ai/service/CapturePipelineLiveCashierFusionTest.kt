package com.moji.ai.service

import com.moji.ai.ai.AiAccountingEngine
import com.moji.ai.ai.WindowContainerRole
import com.moji.ai.ai.WindowRejectReason
import com.moji.ai.data.local.CaptureEvidenceEntity
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType
import com.moji.ai.data.repository.CompositeMedicalFusionResult
import com.moji.ai.data.repository.CompositeMedicalRecordResult
import com.moji.ai.data.repository.LedgerRepository
import com.moji.ai.data.repository.LedgerTransactionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class CapturePipelineLiveCashierFusionTest {

    private lateinit var store: InMemoryPersistenceGateway
    private lateinit var uiRecorder: RecordingUiGateway
    private lateinit var coordinator: CapturePipelineCoordinator

    @Before
    fun setUp() {
        store = InMemoryPersistenceGateway(
            initialWallets = listOf(
                WalletEntity(
                    id = 1L,
                    name = "微信零钱",
                    type = WalletType.DIGITAL_WALLET,
                    subTypeLabel = "微信钱包",
                    brandCode = "WECHAT",
                    balanceCents = 50_000L,
                    aliases = "微信,微信支付,零钱"
                ),
                WalletEntity(
                    id = 2L,
                    name = "余额宝",
                    type = WalletType.DIGITAL_WALLET,
                    subTypeLabel = "支付宝",
                    brandCode = "ALIPAY",
                    balanceCents = 100_000L,
                    aliases = "支付宝,余额宝,账户余额"
                ),
                WalletEntity(
                    id = 3L,
                    name = "招商银行储蓄卡",
                    type = WalletType.DEBIT_BANK,
                    subTypeLabel = "储蓄卡",
                    brandCode = "CMB",
                    cardLast4 = "8473",
                    balanceCents = 200_000L,
                    aliases = "招行储蓄卡,8473"
                ),
                WalletEntity(
                    id = 4L,
                    name = "招商银行信用卡",
                    type = WalletType.CREDIT,
                    subTypeLabel = "信用卡",
                    brandCode = "CMB",
                    cardLast4 = "3354",
                    balanceCents = 10_000L,
                    creditLimitCents = 1_000_000L,
                    aliases = "招行信用卡,3354"
                )
            )
        )
        uiRecorder = RecordingUiGateway()
        coordinator = CapturePipelineCoordinator(
            repository = store,
            aiEngine = AiAccountingEngine(configManager = null, merchantMemoryManager = null),
            uiGateway = uiRecorder
        )
    }

    @Test
    fun ac2_embeddedAlipaySdkNarrowPassThrough_allowsVerifiedSdkOnlyWhenAlipayEnabledAndBlocksHostPages() {
        // 1. 拼多多宿主的支付宝内嵌 SDK 收银台：支付宝无障碍开启且 Activity 经核验命中时放行
        assertTrue(
            PaymentAccessibilityService.shouldAllowHostAccessibility(
                packageName = "com.xunmeng.pinduoduo",
                verifiedActivity = "com.alipay.sdk.app.H5PayResultActivity",
                isDirectHostAllowed = false,
                isAlipayAllowed = true
            )
        )
        assertTrue(
            PaymentAccessibilityService.shouldAllowHostAccessibility(
                packageName = "com.taobao.taobao",
                verifiedActivity = "com.alipay.sdk.app.AlipayResultActivity",
                isDirectHostAllowed = false,
                isAlipayAllowed = true
            )
        )

        // 2. 若用户在白名单中关闭了支付宝无障碍识屏，则内嵌支付宝 SDK 也联动关闭
        assertFalse(
            PaymentAccessibilityService.shouldAllowHostAccessibility(
                packageName = "com.xunmeng.pinduoduo",
                verifiedActivity = "com.alipay.sdk.app.H5PayResultActivity",
                isDirectHostAllowed = false,
                isAlipayAllowed = false
            )
        )

        // 3. 逛拼多多 NewPageActivity、商品详情、订单列表、未核验伪类名时 100% 拦截
        for (nonSdkActivity in listOf(
            "",
            "android.widget.FrameLayout",
            "com.xunmeng.pinduoduo.activity.NewPageActivity",
            "com.xunmeng.pinduoduo.goods.GoodsDetailActivity",
            "com.xunmeng.pinduoduo.order.OrderListActivity"
        )) {
            assertFalse(
                "Must block ecommerce host activity: $nonSdkActivity",
                PaymentAccessibilityService.shouldAllowHostAccessibility(
                    packageName = "com.xunmeng.pinduoduo",
                    verifiedActivity = nonSdkActivity,
                    isDirectHostAllowed = false,
                    isAlipayAllowed = true
                )
            )
        }
    }

    @Test
    fun ac1_and_ac4_stage1ExactSecondLiveCashier_survivesRestartAndUpgradesInPlaceOnStage2AlipayDetail() = runBlocking {
        // 构造恰好落在 `.000` 整秒的一阶段收银台采集时刻（2026-11-22 10:13:25.000）
        val stage1CapturedMillis = buildLocalMillis(2026, Calendar.NOVEMBER, 22, 10, 13, 25, 0)
        assertEquals(0L, stage1CapturedMillis % 1_000L)
        // 二阶段支付宝账单详情里的真实支付时间早 2 秒（2026-11-22 10:13:23.000）
        val realPaidMillis = buildLocalMillis(2026, Calendar.NOVEMBER, 22, 10, 13, 23, 0)

        // 一阶段：收银台无时间完成页即时入账
        val stage1Tx = coordinator.processCapturedEventForTest(
            rawText = "支付成功 | ¥0.08 | 羊驼老头 | ¥0.10 | 百次立减 | -¥0.02 | 付款方式 | 招商银行储蓄卡(8473) | 完成",
            packageName = "com.eg.android.AlipayGphone",
            captureSource = "AI_LIVE_CASHIER",
            capturedAtMillis = stage1CapturedMillis,
            cashierVisitId = 1L
        )
        assertNotNull(stage1Tx)
        assertEquals("AI_LIVE_CASHIER", stage1Tx!!.captureSource)
        assertEquals(8L, stage1Tx.amountCents)
        assertEquals(2L, stage1Tx.discountCents)
        assertEquals(3L, stage1Tx.sourceWalletId)
        assertEquals(stage1CapturedMillis, stage1Tx.timestamp)
        assertEquals(199_992L, store.getWalletBalance(3L))

        // 注入一条旧版无时间无单号的普通 AI_ACCESSIBILITY 脏记录，验证重启清理只删脏数据、绝不误删 AI_LIVE_CASHIER
        val dirtyId = store.recordTransaction(
            TransactionEntity(
                type = TransactionType.EXPENSE,
                amountCents = 1200L,
                amountCnyCents = 1200L,
                category = "餐饮",
                merchant = "过渡页脏数据",
                sourceWalletId = 2L,
                timestamp = stage1CapturedMillis,
                capturedAtMillis = stage1CapturedMillis,
                captureSource = "AI_ACCESSIBILITY",
                rawCapturedText = "[AI_ACCESSIBILITY | com.eg.android.AlipayGphone]: 支付成功 | 过渡页脏数据 | ¥12.00"
            )
        )
        coordinator.repairLegacyEcommerceRecordsIfNeeded()
        assertNull("Dirty undated AI_ACCESSIBILITY record must be cleaned", store.getTransactionById(dirtyId))
        assertNotNull("AI_LIVE_CASHIER record must survive restart cleanup", store.getTransactionById(stage1Tx.id))

        // 二阶段：用户点开支付宝账单详情页（带真实秒级支付时间 + 28 位交易号 + 商品说明）
        val stage2Fused = coordinator.processCapturedEventForTest(
            rawText = "账单详情 | 羊驼老头 | -0.08 | 交易成功 | 商品说明 | 加厚垃圾袋100只装 | 付款方式 | 招商银行储蓄卡(8473) | 支付时间 | 2026-11-22 10:13:23 | 订单号 | 2026112222001483920192837465",
            packageName = "com.eg.android.AlipayGphone",
            captureSource = "AI_ACCESSIBILITY",
            capturedAtMillis = stage1CapturedMillis + 12_000L
        )
        assertNotNull(stage2Fused)
        assertEquals(stage1Tx.id, stage2Fused!!.id)
        assertEquals("AI_FUSED", stage2Fused.captureSource)
        assertEquals("Exact .000 fallback timestamp must still calibrate to real paid time", realPaidMillis, stage2Fused.timestamp)
        assertEquals("2026112222001483920192837465", stage2Fused.orderKey)
        assertTrue(stage2Fused.note.contains("加厚垃圾袋100只装"))
        assertEquals(1, store.getAllTransactions().size)
        // 余额严格只扣一次 0.08 元
        assertEquals(199_992L, store.getWalletBalance(3L))
        assertEquals("已补全账单详情", uiRecorder.recordedBanners.last().statusTitle)
    }

    @Test
    fun ac4_stage1GenericCashier_fusesWithStage2BankNotificationAndStage3PddOrderDetailWithoutDoubleDeduction() = runBlocking {
        val stage1Time = buildLocalMillis(2026, Calendar.NOVEMBER, 22, 14, 20, 10, 0)

        // 一阶段：微信跳转收银台完成页（仅展示平台商户与泛支付方式“微信支付”）
        val stage1 = coordinator.processCapturedEventForTest(
            rawText = "支付成功 | 拼多多平台商户 | ¥19.90 | 支付方式 | 微信支付 | 返回商家",
            packageName = "com.tencent.mm",
            captureSource = "AI_LIVE_CASHIER",
            capturedAtMillis = stage1Time,
            cashierVisitId = 1L
        )
        assertNotNull(stage1)
        assertEquals(1L, stage1!!.sourceWalletId)
        assertEquals(48_010L, store.getWalletBalance(1L))
        assertEquals(10_000L, store.getWalletBalance(4L))

        // 二阶段：招行信用卡(3354)扣款短信到达 -> 自动将扣款钱包从微信零钱校准为招行信用卡(3354)，退回零钱余额并增加信用卡待还
        val stage2Bank = coordinator.processCapturedEventForTest(
            rawText = "【招商银行】您尾号3354信用卡11月22日14:20在财付通-拼多多平台商户消费人民币19.90元",
            packageName = "com.android.mms",
            captureSource = "AI_NOTIFICATION",
            capturedAtMillis = stage1Time + 3_000L
        )
        assertNotNull(stage2Bank)
        assertEquals(stage1.id, stage2Bank!!.id)
        assertEquals("AI_FUSED", stage2Bank.captureSource)
        assertEquals(4L, stage2Bank.sourceWalletId)
        assertEquals("WeChat wallet balance must be restored after bank card calibration", 50_000L, store.getWalletBalance(1L))
        assertEquals("Credit card liability must reflect single ¥19.90 charge", 11_990L, store.getWalletBalance(4L))
        assertEquals(1, store.getAllTransactions().size)

        // 三阶段：随后查看拼多多订单详情页 -> 继续融合具体店铺名、商品标题与拼多多订单号，仍保持单笔账单与余额守恒
        val stage3Pdd = coordinator.processCapturedEventForTest(
            rawText = "订单详情 | 百亿补贴·维达官方旗舰店 | 维达超韧抽纸3层120抽*24包 | 实付 ¥19.90 | 订单编号 261122-99887766554433 | 支付时间 2026-11-22 14:20:08 | 支付方式 微信支付",
            packageName = "com.xunmeng.pinduoduo",
            captureSource = "AI_ACCESSIBILITY",
            capturedAtMillis = stage1Time + 25_000L
        )
        assertNotNull(stage3Pdd)
        assertEquals(stage1.id, stage3Pdd!!.id)
        assertEquals("261122-99887766554433", stage3Pdd.orderKey)
        assertEquals(4L, stage3Pdd.sourceWalletId)
        assertTrue(stage3Pdd.merchant.contains("维达"))
        assertEquals(1, store.getAllTransactions().size)
        assertEquals(50_000L, store.getWalletBalance(1L))
        assertEquals(11_990L, store.getWalletBalance(4L))
    }

    @Test
    fun ac4_and_ac5_twoConsecutiveSameAmountLiveCashierVisits_recordBothAndTriggerAmbiguityGuardOnStage2() = runBlocking {
        val tracker = CashierVisitSessionTracker()
        val t1 = buildLocalMillis(2026, Calendar.NOVEMBER, 22, 16, 0, 5, 0)
        val t2 = t1 + 8_000L // 15 秒内连续第 2 笔同金额 ¥9.90 支付

        // Visit 1：首次进入收银台完成页放行；同页停留复扫被会话锁拦截
        val decision1 = tracker.evaluateLiveCashierFrame(
            packageName = "com.eg.android.AlipayGphone",
            rawText = "支付成功 | 瑞幸咖啡 | ¥9.90 | 付款方式 | 余额宝 | 完成",
            hasExplicitOrderNo = false,
            forceManual = false
        )
        assertTrue(decision1.shouldDispatch && decision1.visitId > 0L)
        assertFalse(
            "Lingering/scrolling on same cashier completion page must not re-dispatch",
            tracker.evaluateLiveCashierFrame(
                packageName = "com.eg.android.AlipayGphone",
                rawText = "支付成功 | 瑞幸咖啡 | ¥9.90 | 付款方式 | 余额宝 | 完成 | 下方推荐卡片",
                hasExplicitOrderNo = false,
                forceManual = false
            ).shouldDispatch
        )

        // 切回宿主再拉起第 2 次收银台（先经过待支付态 -> 再完成支付），分配全新 visitId
        tracker.onContainerOrRejectTransition(
            packageName = "com.xunmeng.pinduoduo",
            containerRole = WindowContainerRole.SHARED_OR_WEB_STRICT
        )
        tracker.onContainerOrRejectTransition(
            packageName = "com.eg.android.AlipayGphone",
            containerRole = WindowContainerRole.DEDICATED_PAYMENT_OR_BILL,
            rejectReason = WindowRejectReason.CASHIER_OR_UNPAID_STATE
        )
        val decision2 = tracker.evaluateLiveCashierFrame(
            packageName = "com.eg.android.AlipayGphone",
            rawText = "支付成功 | 瑞幸咖啡 | ¥9.90 | 付款方式 | 余额宝 | 完成",
            hasExplicitOrderNo = false,
            forceManual = false
        )
        assertTrue(decision2.shouldDispatch && decision2.visitId > decision1.visitId)

        // 15 秒内两笔独立 Visit 的 ¥9.90 均正常入账，余额扣减 2 次（共 19.80 元）
        val recorded1 = coordinator.processCapturedEventForTest(
            rawText = "支付成功 | 瑞幸咖啡 | ¥9.90 | 付款方式 | 余额宝 | 完成",
            packageName = "com.eg.android.AlipayGphone",
            captureSource = "AI_LIVE_CASHIER",
            capturedAtMillis = t1,
            cashierVisitId = decision1.visitId
        )
        val recorded2 = coordinator.processCapturedEventForTest(
            rawText = "支付成功 | 瑞幸咖啡 | ¥9.90 | 付款方式 | 余额宝 | 完成",
            packageName = "com.eg.android.AlipayGphone",
            captureSource = "AI_LIVE_CASHIER",
            capturedAtMillis = t2,
            cashierVisitId = decision2.visitId
        )
        assertNotNull(recorded1)
        assertNotNull(recorded2)
        assertFalse(recorded1!!.id == recorded2!!.id)
        assertEquals(2, store.getAllTransactions().size)
        assertEquals(100_000L - 1_980L, store.getWalletBalance(2L))

        // 重启修复亦不得把两次独立 Visit 的同金额收银台账单误合并
        coordinator.repairLegacyEcommerceRecordsIfNeeded()
        assertEquals(2, store.getAllTransactions().size)

        // AC-4 歧义保护：当窗口内存在 ≥2 笔同金额未绑单号的 AI_LIVE_CASHIER 记录时，二阶段新到一笔 ¥9.90 账单详情禁止盲目吞并，必须走疑似重复提示！
        val ambiguousResult = coordinator.processCapturedEventForTest(
            rawText = "账单详情 | 瑞幸咖啡 | -9.90 | 交易成功 | 付款方式 | 余额宝 | 支付时间 | 2026-11-22 16:00:11 | 订单号 | 2026112222001483920192839999",
            packageName = "com.eg.android.AlipayGphone",
            captureSource = "AI_ACCESSIBILITY",
            capturedAtMillis = t2 + 4_000L
        )
        assertNull("Ambiguous stage-2 event must not auto-merge or silently insert", ambiguousResult)
        assertEquals(2, store.getAllTransactions().size)
        assertEquals(1, uiRecorder.duplicateBanners.size)
        assertTrue(uiRecorder.duplicateBanners.single().subtitle.contains("歧义"))
        val pendingIds = coordinator.getPendingDuplicateCandidateIdsForTest()
        assertEquals(1, pendingIds.size)

        // 用户若主动在疑似重复胶囊点击「录入」，可正常落库
        val forced = coordinator.forceRecordDuplicateCandidate(pendingIds.single())
        assertNotNull(forced)
        assertEquals(3, store.getAllTransactions().size)
    }

    @Test
    fun ac5_sameVisitInstantDiscountAnimation_calibratesPaidAmountAndRefundsOverDeductedWallet() = runBlocking {
        val tracker = CashierVisitSessionTracker()
        val t0 = buildLocalMillis(2026, Calendar.NOVEMBER, 22, 18, 30, 0, 0)

        // 第 1 帧：原价 ¥10.00（立减动画尚未展开）
        val frame1Text = "支付成功 | 全家便利店 | ¥10.00 | 付款方式 | 招商银行储蓄卡(8473) | 完成"
        val visitDecision1 = tracker.evaluateLiveCashierFrame(
            packageName = "com.eg.android.AlipayGphone",
            rawText = frame1Text,
            hasExplicitOrderNo = false,
            forceManual = false
        )
        assertTrue(visitDecision1.shouldDispatch)
        val txFrame1 = coordinator.processCapturedEventForTest(
            rawText = frame1Text,
            packageName = "com.eg.android.AlipayGphone",
            captureSource = "AI_LIVE_CASHIER",
            capturedAtMillis = t0,
            cashierVisitId = visitDecision1.visitId
        )
        assertNotNull(txFrame1)
        assertEquals(1_000L, txFrame1!!.amountCents)
        assertEquals(199_000L, store.getWalletBalance(3L))

        // 第 2 帧（同一次收银台会话 2 秒后）：立减动画完成展示 实付 ¥9.20 + 立减 -¥0.80 -> 会话锁允许立减校准帧通过并复用同一 visitId
        val frame2Text = "支付成功 | ¥9.20 | 全家便利店 | ¥10.00 | 支付立减 | -¥0.80 | 付款方式 | 招商银行储蓄卡(8473) | 完成"
        val visitDecision2 = tracker.evaluateLiveCashierFrame(
            packageName = "com.eg.android.AlipayGphone",
            rawText = frame2Text,
            hasExplicitOrderNo = false,
            forceManual = false
        )
        assertTrue(visitDecision2.shouldDispatch)
        assertEquals("Instant-discount frame in same visit must reuse visitId", visitDecision1.visitId, visitDecision2.visitId)

        val calibratedTx = coordinator.processCapturedEventForTest(
            rawText = frame2Text,
            packageName = "com.eg.android.AlipayGphone",
            captureSource = "AI_LIVE_CASHIER",
            capturedAtMillis = t0 + 2_000L,
            cashierVisitId = visitDecision2.visitId
        )
        assertNotNull(calibratedTx)
        assertEquals(txFrame1.id, calibratedTx!!.id)
        assertEquals(920L, calibratedTx.amountCents)
        assertEquals(80L, calibratedTx.discountCents)
        assertEquals(1, store.getAllTransactions().size)
        // 多扣的 0.80 元已原子退回招商银行储蓄卡(8473)
        assertEquals(199_080L, store.getWalletBalance(3L))
    }

    private fun buildLocalMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        millis: Int
    ): Long = Calendar.getInstance().apply {
        set(year, month, day, hour, minute, second)
        set(Calendar.MILLISECOND, millis)
    }.timeInMillis

    private class RecordingUiGateway : PipelineUiGateway {
        data class RecordedBannerCall(
            val statusTitle: String,
            val amountText: String,
            val subtitle: String,
            val transactionId: Long
        )

        data class DuplicateBannerCall(
            val amountText: String,
            val subtitle: String,
            val walletName: String,
            val matchedTransactionId: Long?
        )

        val cancelledTxIds = mutableListOf<Long>()
        val recordedBanners = mutableListOf<RecordedBannerCall>()
        val duplicateBanners = mutableListOf<DuplicateBannerCall>()

        override fun cancelNotificationForTransaction(txId: Long) {
            cancelledTxIds.add(txId)
        }

        override fun sendSilentRecordedNotification(tx: TransactionEntity, wallets: List<WalletEntity>) = Unit

        override fun sendSuspectedDuplicateNotification(
            candidateId: Long,
            tx: TransactionEntity,
            wallets: List<WalletEntity>,
            duplicateReason: String,
            matchedTransactionId: Long?
        ) = Unit

        override fun showRecordedBanner(
            statusTitle: String,
            amountText: String,
            subtitle: String,
            isIncome: Boolean,
            transactionId: Long
        ) {
            recordedBanners.add(RecordedBannerCall(statusTitle, amountText, subtitle, transactionId))
        }

        override fun showSuspectedDuplicateBanner(
            amountText: String,
            subtitle: String,
            walletName: String,
            matchedTransactionId: Long?,
            onForceRecord: () -> Unit
        ) {
            duplicateBanners.add(DuplicateBannerCall(amountText, subtitle, walletName, matchedTransactionId))
        }
    }

    private class InMemoryPersistenceGateway(
        initialWallets: List<WalletEntity>
    ) : PipelinePersistenceGateway, LedgerTransactionStore {
        private val txIdGen = AtomicLong(100L)
        private val evIdGen = AtomicLong(1L)
        private val wallets = initialWallets.associateBy { it.id }.toMutableMap()
        private val txs = linkedMapOf<Long, TransactionEntity>()
        private val evidences = mutableListOf<CaptureEvidenceEntity>()

        fun getWalletBalance(walletId: Long): Long = wallets.getValue(walletId).balanceCents

        override suspend fun getWalletById(id: Long): WalletEntity? = wallets[id]
        override suspend fun getAllWallets(): List<WalletEntity> = wallets.values.toList()
        override suspend fun updateWallet(wallet: WalletEntity) {
            wallets[wallet.id] = wallet
        }

        override suspend fun clearAllTransactionsAndEvidences() {
            txs.clear()
            evidences.clear()
        }

        override suspend fun getTransactionById(id: Long): TransactionEntity? = txs[id]
        override suspend fun getAllTransactions(): List<TransactionEntity> =
            txs.values.sortedByDescending { it.timestamp }

        override suspend fun findTransactionByOrderKey(orderKey: String): TransactionEntity? =
            if (orderKey.isBlank()) null else txs.values.firstOrNull { it.orderKey == orderKey }

        override suspend fun findTransactionByNo(txNo: String): TransactionEntity? =
            if (txNo.isBlank()) null else txs.values.firstOrNull { it.transactionNo == txNo }

        override suspend fun findTransactionByMerchantOrderNo(orderNo: String): TransactionEntity? =
            if (orderNo.isBlank()) null else txs.values.firstOrNull { it.merchantOrderNo == orderNo }

        override suspend fun findTransactionsByAmountAndOccurredWindow(
            amountCents: Long,
            occurredAtMillis: Long,
            toleranceMillis: Long
        ): List<TransactionEntity> = txs.values.filter {
            it.amountCents == amountCents && abs(it.timestamp - occurredAtMillis) <= toleranceMillis
        }

        override suspend fun findTransactionsByAmountAndTimeOrCapturedWindow(
            amountCents: Long,
            occurredAtMillis: Long,
            capturedAtMillis: Long,
            toleranceMillis: Long
        ): List<TransactionEntity> = txs.values.filter {
            it.amountCents == amountCents && (
                abs(it.timestamp - occurredAtMillis) <= toleranceMillis ||
                    (it.capturedAtMillis > 0L && capturedAtMillis > 0L && abs(it.capturedAtMillis - capturedAtMillis) <= toleranceMillis)
                )
        }

        override suspend fun getSplitChildrenOfExpense(parentId: Long): List<TransactionEntity> =
            txs.values.filter { it.relatedTransactionId == parentId && it.type == TransactionType.EXPENSE }

        override suspend fun getRecentRefundableExpenses(limit: Int): List<TransactionEntity> =
            txs.values.filter { it.type == TransactionType.EXPENSE }.sortedByDescending { it.timestamp }.take(limit)

        override suspend fun insertTransaction(tx: TransactionEntity): Long {
            val id = if (tx.id > 0L) tx.id else txIdGen.incrementAndGet()
            txs[id] = tx.copy(id = id)
            return id
        }

        override suspend fun updateTransaction(tx: TransactionEntity) {
            txs[tx.id] = tx
        }

        override suspend fun deleteTransaction(tx: TransactionEntity) {
            txs.remove(tx.id)
        }

        override suspend fun recordTransaction(tx: TransactionEntity): Long =
            LedgerRepository.recordSingleTransactionOnStore(this, tx)

        override suspend fun recordCompositeMedicalTransaction(
            mainTx: TransactionEntity,
            poolSubsidyCents: Long,
            personalAccountCents: Long,
            unspecifiedMedicalCents: Long,
            commercialDiscountCents: Long
        ): CompositeMedicalRecordResult = LedgerRepository.recordCompositeMedicalOnStore(
            store = this,
            mainTx = mainTx,
            poolSubsidyCents = poolSubsidyCents,
            personalAccountCents = personalAccountCents,
            unspecifiedMedicalCents = unspecifiedMedicalCents,
            commercialDiscountCents = commercialDiscountCents
        )

        override suspend fun enrichCompositeMedicalOnExistingTransaction(
            existingMainId: Long,
            enrichedCandidateMain: TransactionEntity,
            baseEnrichedFields: List<String>,
            poolSubsidyCents: Long,
            personalAccountCents: Long,
            unspecifiedMedicalCents: Long,
            commercialDiscountCents: Long
        ): CompositeMedicalFusionResult = LedgerRepository.enrichCompositeMedicalOnStore(
            store = this,
            existingMainId = existingMainId,
            enrichedCandidateMain = enrichedCandidateMain,
            baseEnrichedFields = baseEnrichedFields,
            poolSubsidyCents = poolSubsidyCents,
            personalAccountCents = personalAccountCents,
            unspecifiedMedicalCents = unspecifiedMedicalCents,
            commercialDiscountCents = commercialDiscountCents
        )

        override suspend fun updateExistingTransaction(updatedTx: TransactionEntity): Boolean =
            LedgerRepository.updateExistingTransactionOnStore(this, updatedTx)

        override suspend fun undoAndDeleteTransaction(id: Long): Boolean =
            LedgerRepository.undoAndDeleteTransactionOnStore(this, id).first

        override suspend fun attachBillScreenshotIfNeeded(
            transactionId: Long,
            screenshotPath: String,
            screenshotSha256: String,
            nowMillis: Long
        ): Boolean {
            val existing = txs[transactionId] ?: return false
            if (existing.screenshotPath.isNotBlank() || screenshotPath.isBlank()) return false
            txs[transactionId] = existing.copy(
                screenshotPath = screenshotPath,
                screenshotSha256 = screenshotSha256,
                updatedAtMillis = nowMillis
            )
            return true
        }

        override suspend fun insertCaptureEvidence(evidence: CaptureEvidenceEntity): Long {
            val id = evIdGen.incrementAndGet()
            evidences.add(evidence.copy(id = id))
            return id
        }

        override suspend fun getEvidencesForTransaction(txId: Long): List<CaptureEvidenceEntity> =
            evidences.filter { it.transactionId == txId }
    }
}
