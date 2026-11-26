package com.moji.ai.service

import com.moji.ai.ai.AiAccountingEngine
import com.moji.ai.ai.CaptureWindowGuard
import com.moji.ai.ai.WindowContainerRole
import com.moji.ai.ai.WindowInspectionInput
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

    @Test
    fun ac1_multitaskReentryToUnclosedWechatAndAlipayCashier_reusesVisitIdWithZeroDuplicatesAndZeroNotifications() = runBlocking {
        val tracker = CashierVisitSessionTracker()
        val t0 = buildLocalMillis(2026, Calendar.NOVEMBER, 26, 19, 38, 18, 0)
        val wechatCashierAct = "com.tencent.mm.plugin.lite.ui.WxaLiteAppTransparentLiteUI"
        val nayukiCashierText = "支付成功 | 奈雪的茶 | ¥19.80 | 支付方式 | 招商银行信用卡(3354) | 完成"

        // 首次进入微信收银台完成页：正常放行并分配 visitId
        val initialDecision = tracker.evaluateLiveCashierFrame(
            packageName = "com.tencent.mm",
            rawText = nayukiCashierText,
            hasExplicitOrderNo = false,
            forceManual = false,
            verifiedActivity = wechatCashierAct,
            nowMillis = t0
        )
        assertTrue(initialDecision.shouldDispatch)
        val initialTx = coordinator.processCapturedEventForTest(
            rawText = nayukiCashierText,
            packageName = "com.tencent.mm",
            captureSource = "AI_LIVE_CASHIER",
            capturedAtMillis = t0,
            cashierVisitId = initialDecision.visitId,
            verifiedActivity = wechatCashierAct
        )
        assertNotNull(initialTx)
        assertEquals(1, store.getAllTransactions().size)
        assertEquals(1, uiRecorder.recordedBanners.size)

        // 10 分钟内未点「完成/关闭」，连续 4 次往返：桌面 -> 微信聊天 LauncherUI -> 打开默记 com.moji.ai 查看账单 -> 9 分钟后再从桌面切回
        // 第 1 次往返：切桌面 Launcher -> 切回未关闭收银台
        assertFalse(tracker.onExternalAppFocused("com.android.launcher"))
        val reentry1 = tracker.evaluateLiveCashierFrame(
            packageName = "com.tencent.mm",
            rawText = nayukiCashierText,
            hasExplicitOrderNo = false,
            verifiedActivity = wechatCashierAct,
            nowMillis = t0 + 18_000L
        )
        assertFalse("Re-entering unclosed cashier from Launcher must not re-dispatch", reentry1.shouldDispatch)
        assertEquals(initialDecision.visitId, reentry1.visitId)

        // 第 2 次往返：切微信聊天主界面 LauncherUI -> 切回未关闭收银台
        assertFalse(
            tracker.onContainerOrRejectTransition(
                packageName = "com.tencent.mm",
                containerRole = WindowContainerRole.NON_TRANSACTION_BLOCKED,
                rejectReason = WindowRejectReason.NON_TRANSACTION_CONTAINER,
                verifiedActivity = "com.tencent.mm.ui.LauncherUI"
            )
        )
        val reentry2 = tracker.evaluateLiveCashierFrame(
            packageName = "com.tencent.mm",
            rawText = nayukiCashierText,
            hasExplicitOrderNo = false,
            verifiedActivity = wechatCashierAct,
            nowMillis = t0 + 55_000L
        )
        assertFalse("Re-entering unclosed cashier from WeChat chat must not re-dispatch", reentry2.shouldDispatch)
        assertEquals(initialDecision.visitId, reentry2.visitId)

        // 第 3 次往返：打开 com.moji.ai 查看账单 -> 切回未关闭收银台
        assertFalse(tracker.onExternalAppFocused("com.moji.ai"))
        val reentry3 = tracker.evaluateLiveCashierFrame(
            packageName = "com.tencent.mm",
            rawText = nayukiCashierText,
            hasExplicitOrderNo = false,
            verifiedActivity = wechatCashierAct,
            nowMillis = t0 + 140_000L
        )
        assertFalse("Re-entering unclosed cashier from com.moji.ai must not re-dispatch", reentry3.shouldDispatch)
        assertEquals(initialDecision.visitId, reentry3.visitId)

        // 第 4 次往返（9 分钟后，跨过协调器 65 秒短时窗）：即使恢复探测或 OCR 兜底再次将同 visitId 送入协调器，也必须静默拦截！
        assertFalse(tracker.onExternalAppFocused("com.moji.ai.qa"))
        val reentry4 = tracker.evaluateLiveCashierFrame(
            packageName = "com.tencent.mm",
            rawText = nayukiCashierText,
            hasExplicitOrderNo = false,
            verifiedActivity = wechatCashierAct,
            nowMillis = t0 + 9 * 60_000L
        )
        assertFalse(reentry4.shouldDispatch)
        assertEquals(initialDecision.visitId, reentry4.visitId)
        val blockedRescan = coordinator.processCapturedEventForTest(
            rawText = nayukiCashierText,
            packageName = "com.tencent.mm",
            captureSource = "AI_LIVE_CASHIER",
            capturedAtMillis = t0 + 9 * 60_000L,
            cashierVisitId = reentry4.visitId,
            verifiedActivity = wechatCashierAct
        )
        assertNull(blockedRescan)
        assertEquals("Bill count must stay 1 after >=3 multitask round-trips", 1, store.getAllTransactions().size)
        assertEquals("Recorded banner count must stay 1 (0 new recorded prompts)", 1, uiRecorder.recordedBanners.size)
        assertEquals("Suspected duplicate notifications must be 0", 0, uiRecorder.suspectedDuplicateNotifications.size)
        assertEquals("Suspected duplicate banners must be 0", 0, uiRecorder.duplicateBanners.size)

        // 同步验证支付宝 MspUniRenderActivity 未关闭完成页切桌面与默记 3 次往返同样 0 新增
        val alipayTracker = CashierVisitSessionTracker()
        val alipayAct = "com.alipay.android.msp.ui.views.MspUniRenderActivity"
        val alipayText = "支付成功 | 瑞幸咖啡 | ¥9.90 | 付款方式 | 余额宝 | 完成"
        val alipayInit = alipayTracker.evaluateLiveCashierFrame(
            packageName = "com.eg.android.AlipayGphone",
            rawText = alipayText,
            hasExplicitOrderNo = false,
            verifiedActivity = alipayAct,
            nowMillis = t0
        )
        assertTrue(alipayInit.shouldDispatch)
        for (targetPkg in listOf("com.android.launcher", "com.moji.ai", "com.tencent.mm")) {
            alipayTracker.onExternalAppFocused(targetPkg)
            val re = alipayTracker.evaluateLiveCashierFrame(
                packageName = "com.eg.android.AlipayGphone",
                rawText = alipayText,
                hasExplicitOrderNo = false,
                verifiedActivity = alipayAct,
                nowMillis = t0 + 45_000L
            )
            assertFalse(re.shouldDispatch)
            assertEquals(alipayInit.visitId, re.visitId)
        }
    }

    @Test
    fun ac2_genuineConsecutiveSameAmountPayments_resetVisitOnEveryPreconditionSignal() = runBlocking {
        val tracker = CashierVisitSessionTracker()
        val wechatCashierAct = "com.tencent.mm.plugin.lite.ui.WxaLiteAppTransparentLiteUI"
        val cashierText = "支付成功 | 奈雪的茶 | ¥19.80 | 支付方式 | 招商银行信用卡(3354) | 完成"
        var now = buildLocalMillis(2026, Calendar.NOVEMBER, 26, 20, 0, 0, 0)

        fun assertNewVisitAfterSignal(triggerDescription: String, triggerSignal: () -> Unit) {
            val prevVisitId = tracker.activeVisitId
            triggerSignal()
            now += 15_000L
            val next = tracker.evaluateLiveCashierFrame(
                packageName = "com.tencent.mm",
                rawText = cashierText,
                hasExplicitOrderNo = false,
                verifiedActivity = wechatCashierAct,
                nowMillis = now
            )
            assertTrue("Must dispatch new payment after $triggerDescription", next.shouldDispatch)
            assertTrue("Must allocate strictly greater visitId after $triggerDescription", next.visitId > prevVisitId)
        }

        // 第 1 笔基准入账
        val first = tracker.evaluateLiveCashierFrame(
            packageName = "com.tencent.mm",
            rawText = cashierText,
            hasExplicitOrderNo = false,
            verifiedActivity = wechatCashierAct,
            nowMillis = now
        )
        assertTrue(first.shouldDispatch)

        // 信号 1：待付款收银台词（确认付款/立即支付）
        assertNewVisitAfterSignal("unpaid cashier state") {
            tracker.onContainerOrRejectTransition(
                packageName = "com.tencent.mm",
                containerRole = WindowContainerRole.DEDICATED_PAYMENT_OR_BILL,
                rejectReason = WindowRejectReason.CASHIER_OR_UNPAID_STATE,
                verifiedActivity = wechatCashierAct
            )
        }
        // 信号 2：密码/生物核身敏感窗口
        assertNewVisitAfterSignal("password/biometric verification") {
            tracker.onContainerOrRejectTransition(
                packageName = "com.tencent.mm",
                containerRole = WindowContainerRole.SENSITIVE_AUTH_BLOCKED,
                verifiedActivity = "com.tencent.mm.plugin.wallet_core.ui.WalletPwdConfirmUI"
            )
        }
        // 信号 3：重新拉起 WalletBrandUI 支付桥接页
        assertNewVisitAfterSignal("re-launching WalletBrandUI") {
            tracker.onContainerOrRejectTransition(
                packageName = "com.tencent.mm",
                containerRole = WindowContainerRole.DEDICATED_PAYMENT_OR_BILL,
                verifiedActivity = "com.tencent.mm.plugin.wallet_index.ui.WalletBrandUI"
            )
        }
        // 信号 4：原收银台窗口移除关闭回到宿主
        assertNewVisitAfterSignal("closing cashier window to host") {
            tracker.onCashierWindowClosedToHost()
        }
        // 信号 5：点击完成返回小程序宿主 AppBrandUI00
        assertNewVisitAfterSignal("returning to AppBrandUI00 host") {
            tracker.onContainerOrRejectTransition(
                packageName = "com.tencent.mm",
                containerRole = WindowContainerRole.SHARED_OR_WEB_STRICT,
                rejectReason = WindowRejectReason.MISSING_OCCURRED_TIME,
                verifiedActivity = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI00"
            )
        }
    }

    @Test
    fun ac3_and_ac4_wechatMiniProgramOrderPage_fusesBidirectionallyWithCashierAndBankAndBlocksLongStayRefresh() = runBlocking {
        val tOrder = buildLocalMillis(2026, Calendar.NOVEMBER, 26, 19, 38, 18, 0)
        val appBrandAct = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI00"

        // A. 正向链路：收银台完成页 + cmb.pb 银行通知先到 -> 8 分钟后打开 AppBrandUI00 小程序取餐/订单页 -> 原位融合为唯一 1 笔 AI_FUSED
        val cashierTx = coordinator.processCapturedEventForTest(
            rawText = "支付成功 | 奈雪的茶 | ¥19.80 | 支付方式 | 微信支付 | 完成",
            packageName = "com.tencent.mm",
            captureSource = "AI_LIVE_CASHIER",
            capturedAtMillis = tOrder,
            cashierVisitId = 1L,
            verifiedActivity = "com.tencent.mm.plugin.lite.ui.WxaLiteAppTransparentLiteUI"
        )
        assertNotNull(cashierTx)

        val bankCalibrated = coordinator.processCapturedEventForTest(
            rawText = "交易提醒 | 您尾号3354的信用卡11月26日19:38在财付通-奈雪的茶消费人民币19.80元",
            packageName = "cmb.pb",
            captureSource = "AI_NOTIFICATION",
            capturedAtMillis = tOrder + 3_000L
        )
        assertNotNull(bankCalibrated)
        assertEquals(cashierTx!!.id, bankCalibrated!!.id)
        assertEquals(4L, bankCalibrated.sourceWalletId)

        val miniProgramPickupText = "奈雪的茶 | 制作中 | 取餐码 A086 | 前方还有3杯 | 实付 ¥19.80 | 霸气茉莉初雪 ×1 | 合计 ¥26.00 | 订单号 NY20261126193818 | 下单时间 2026-11-26 19:38:18 | 支付方式 微信支付"
        val fusedFromMiniProgram = coordinator.processCapturedEventForTest(
            rawText = miniProgramPickupText,
            packageName = "com.tencent.mm",
            captureSource = "AI_ACCESSIBILITY",
            capturedAtMillis = tOrder + 8 * 60_000L, // 8 分钟后（≤15 分钟跨通道窗口）
            verifiedActivity = appBrandAct
        )
        assertNotNull(fusedFromMiniProgram)
        assertEquals(cashierTx.id, fusedFromMiniProgram!!.id)
        assertEquals("AI_FUSED", fusedFromMiniProgram.captureSource)
        assertEquals("NY20261126193818", fusedFromMiniProgram.orderKey)
        assertEquals(4L, fusedFromMiniProgram.sourceWalletId)
        assertTrue("Fused note must retain mini-program drink name", fusedFromMiniProgram.note.contains("霸气茉莉初雪"))
        assertEquals(1, store.getAllTransactions().size)
        assertEquals(11_980L, store.getWalletBalance(4L))

        // AC-4：在同一小程序订单/取餐页停留 15s~10min，取餐码与排队状态动态刷新时幂等静默拦截（0 重记、0 疑似重复通知）
        val dedupTracker = StablePageDedupTracker()
        val idInitial = PaymentAccessibilityService.extractStablePageIdentity("com.tencent.mm", miniProgramPickupText)
        assertFalse(dedupTracker.shouldSkipDispatch(idInitial, nowMillis = tOrder + 8 * 60_000L))

        for ((offsetSec, dynamicStatus) in listOf(
            20L to "请取餐 A086 | 饮品已制作完成",
            150L to "请尽快取餐 A086 | 饮品已放入取餐柜",
            540L to "已完成 A086 | 感谢光临奈雪"
        )) {
            val refreshedText = "奈雪的茶 | $dynamicStatus | 实付 ¥19.80 | 霸气茉莉初雪 ×1 | 合计 ¥26.00 | 订单号 NY20261126193818 | 下单时间 2026-11-26 19:38:18 | 支付方式 微信支付"
            val refreshedId = PaymentAccessibilityService.extractStablePageIdentity("com.tencent.mm", refreshedText)
            assertEquals("Dynamic pickup code/status refresh must keep identical stable page identity", idInitial, refreshedId)
            assertTrue(
                "StablePageDedupTracker must skip dispatch at +${offsetSec}s",
                dedupTracker.shouldSkipDispatch(refreshedId, nowMillis = tOrder + 8 * 60_000L + offsetSec * 1_000L)
            )
            // 即使绕过无障碍前置缓存进入协调器，10 分钟内同页重扫也必须静默拦截不弹疑似重复通知
            val coordinatorRepeat = coordinator.processCapturedEventForTest(
                rawText = refreshedText,
                packageName = "com.tencent.mm",
                captureSource = "AI_ACCESSIBILITY",
                capturedAtMillis = tOrder + 8 * 60_000L + offsetSec * 1_000L,
                verifiedActivity = appBrandAct
            )
            assertNull(coordinatorRepeat)
        }
        assertEquals(1, store.getAllTransactions().size)
        assertEquals(0, uiRecorder.duplicateBanners.size)
        assertEquals(0, uiRecorder.suspectedDuplicateNotifications.size)

        // B. 反向链路：小程序商家订单页先入账 -> 2 分钟后收到 cmb.pb 银行扣款通知 -> 同样双向原位融合为唯一 1 笔 AI_FUSED！
        val tHeytea = buildLocalMillis(2026, Calendar.NOVEMBER, 26, 20, 15, 0, 0)
        val heyteaMiniFirst = coordinator.processCapturedEventForTest(
            rawText = "喜茶GO | 制作中 | 多肉葡萄 ×1 | 实付 ¥18.50 | 订单编号 HY20261126201500 | 下单时间 2026-11-26 20:15:00 | 支付方式 微信支付",
            packageName = "com.tencent.mm",
            captureSource = "AI_ACCESSIBILITY",
            capturedAtMillis = tHeytea,
            verifiedActivity = appBrandAct
        )
        assertNotNull(heyteaMiniFirst)
        assertEquals(1L, heyteaMiniFirst!!.sourceWalletId)

        val heyteaBankLater = coordinator.processCapturedEventForTest(
            rawText = "交易提醒 | 您尾号3354的信用卡11月26日20:15在财付通-喜茶消费人民币18.50元",
            packageName = "cmb.pb",
            captureSource = "AI_NOTIFICATION",
            capturedAtMillis = tHeytea + 120_000L
        )
        assertNotNull(heyteaBankLater)
        assertEquals(heyteaMiniFirst.id, heyteaBankLater!!.id)
        assertEquals("AI_FUSED", heyteaBankLater.captureSource)
        assertEquals("HY20261126201500", heyteaBankLater.orderKey)
        assertEquals(4L, heyteaBankLater.sourceWalletId)
        assertTrue(heyteaBankLater.note.contains("多肉葡萄"))
        assertEquals("WeChat wallet balance must be restored after bank calibration", 50_000L, store.getWalletBalance(1L))
        assertEquals("Total bills must be exactly 2 (1 Nayuki + 1 Heytea)", 2, store.getAllTransactions().size)
    }

    @Test
    fun ac5_unnumberedCashierUpgradedToFused_dualChannelOcrAndRescanProduceZeroOrphansAndZeroDoubleNotifications() = runBlocking {
        val t1 = buildLocalMillis(2026, Calendar.NOVEMBER, 26, 19, 12, 15, 0)
        val cashierAct = "com.tencent.mm.plugin.lite.ui.WxaLiteAppTransparentLiteUI"
        val cashierText = "支付成功 | 奈雪的茶 | ¥19.80 | 支付方式 | 微信支付 | 完成"

        // 1. 无单号 AI_LIVE_CASHIER 首次入账（产生唯一 1 条入账胶囊）
        val liveTx = coordinator.processCapturedEventForTest(
            rawText = cashierText,
            packageName = "com.tencent.mm",
            captureSource = "AI_LIVE_CASHIER",
            capturedAtMillis = t1,
            cashierVisitId = 1L,
            verifiedActivity = cashierAct
        )
        assertNotNull(liveTx)
        assertEquals(1, uiRecorder.recordedBanners.size)

        // 2. 无单号 cmb.pb 通知到达，将记录升级为 AI_FUSED（此时 orderKey 仍为空）
        val fusedByNotif = coordinator.processCapturedEventForTest(
            rawText = "交易提醒 | 您尾号3354的信用卡11月26日19:12在财付通-奈雪的茶消费人民币19.80元",
            packageName = "cmb.pb",
            captureSource = "AI_NOTIFICATION",
            capturedAtMillis = t1 + 2_000L
        )
        assertNotNull(fusedByNotif)
        assertEquals(liveTx!!.id, fusedByNotif!!.id)
        assertEquals("AI_FUSED", fusedByNotif.captureSource)
        assertEquals("", fusedByNotif.orderKey)
        assertEquals("已补全账单详情", uiRecorder.recordedBanners.last().statusTitle)

        // 3. 520ms 后同页自动 OCR 双通道帧到达 + 3 分钟后恢复探测重扫同一完成页 -> 均命中同会话原记录静默拦截，绝不产生双通知或孤儿候选
        val bannerCountBeforeRescan = uiRecorder.recordedBanners.size
        val ocrFrame = coordinator.processCapturedEventForTest(
            rawText = cashierText,
            packageName = "com.tencent.mm",
            captureSource = "AI_OCR",
            capturedAtMillis = t1 + 2_520L,
            cashierVisitId = 1L,
            verifiedActivity = cashierAct
        )
        val recoveryRescan = coordinator.processCapturedEventForTest(
            rawText = cashierText,
            packageName = "com.tencent.mm",
            captureSource = "AI_LIVE_CASHIER",
            capturedAtMillis = t1 + 180_000L,
            cashierVisitId = 1L,
            verifiedActivity = cashierAct
        )
        assertNull(ocrFrame)
        assertNull(recoveryRescan)
        assertEquals(1, store.getAllTransactions().size)
        assertEquals("Zero extra banners on dual-channel OCR & recovery rescan", bannerCountBeforeRescan, uiRecorder.recordedBanners.size)
        assertEquals("Zero rapid double suspected-duplicate notifications", 0, uiRecorder.suspectedDuplicateNotifications.size)
        assertEquals(0, uiRecorder.duplicateBanners.size)

        // 4. 2 分钟后真实发生第 2 笔同金额消费（visit=2）并收到第 2 条 cmb.pb 通知 -> 优先精准校准未升级的 visit=2，绝不因 visit=1 已是 AI_FUSED 而误报多候选歧义！
        val t2 = t1 + 120_000L
        val liveTx2 = coordinator.processCapturedEventForTest(
            rawText = cashierText,
            packageName = "com.tencent.mm",
            captureSource = "AI_LIVE_CASHIER",
            capturedAtMillis = t2,
            cashierVisitId = 2L,
            verifiedActivity = cashierAct
        )
        assertNotNull(liveTx2)
        val fused2 = coordinator.processCapturedEventForTest(
            rawText = "交易提醒 | 您尾号3354的信用卡11月26日19:14在财付通-奈雪的茶消费人民币19.80元",
            packageName = "cmb.pb",
            captureSource = "AI_NOTIFICATION",
            capturedAtMillis = t2 + 2_000L
        )
        assertNotNull("Second bank notification must cleanly calibrate uncalibrated visit=2 instead of triggering ambiguity", fused2)
        assertEquals(liveTx2!!.id, fused2!!.id)
        assertEquals(4L, fused2.sourceWalletId)
        assertEquals(2, store.getAllTransactions().size)
        assertEquals(0, uiRecorder.duplicateBanners.size)
    }

    @Test
    fun ac8_tonightRealDeviceNayukiRefundAndReorderTimelineReplay_plusTwoCupsCounterexample() = runBlocking {
        val visitTracker = CashierVisitSessionTracker()
        val pageDedupTracker = StablePageDedupTracker()
        val cashierAct = "com.tencent.mm.plugin.lite.ui.WxaLiteAppTransparentLiteUI"
        val appBrandAct = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI00"
        val initialCmbLiability = store.getWalletBalance(4L) // 10_000L

        // ==================== Step 1: 19:12 下第 1 单（¥19.80） ====================
        val t1912 = buildLocalMillis(2026, Calendar.NOVEMBER, 26, 19, 12, 15, 0)
        val cashierText1 = "支付成功 | 奈雪的茶 | ¥19.80 | 支付方式 | 微信支付 | 完成"
        val v1 = visitTracker.evaluateLiveCashierFrame(
            packageName = "com.tencent.mm",
            rawText = cashierText1,
            hasExplicitOrderNo = false,
            verifiedActivity = cashierAct,
            nowMillis = t1912
        )
        assertTrue(v1.shouldDispatch)
        val order1 = coordinator.processCapturedEventForTest(
            rawText = cashierText1,
            packageName = "com.tencent.mm",
            captureSource = "AI_LIVE_CASHIER",
            capturedAtMillis = t1912,
            cashierVisitId = v1.visitId,
            verifiedActivity = cashierAct
        )
        assertNotNull(order1)
        coordinator.processCapturedEventForTest(
            rawText = "交易提醒 | 您尾号3354的信用卡11月26日19:12在财付通-奈雪的茶消费人民币19.80元",
            packageName = "cmb.pb",
            captureSource = "AI_NOTIFICATION",
            capturedAtMillis = t1912 + 3_000L
        )
        assertEquals(initialCmbLiability + 1_980L, store.getWalletBalance(4L))

        // 用户点完成返回小程序
        visitTracker.onCashierWindowClosedToHost()

        // ==================== Step 2: 19:37:23 小程序退单跳转「已退单 / 已取消」状态页 -> 100% 拦截 ====================
        val t1937Cancel = buildLocalMillis(2026, Calendar.NOVEMBER, 26, 19, 37, 23, 0)
        val cancelledPageText = "奈雪的茶 | 已退单 | 霸气茉莉初雪 ×1 | 实付 ¥19.80 | 合计 ¥26.00 | 订单号 NY20261126191215 | 下单时间 2026-11-26 19:12:15"
        val guardCancelEval = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.tencent.mm",
                verifiedActivity = appBrandAct,
                rawPageText = cancelledPageText,
                nowMillis = t1937Cancel
            )
        )
        assertFalse(guardCancelEval.accepted)
        assertEquals(WindowRejectReason.CLOSED_FAILED_OR_REFUND_PENDING, guardCancelEval.reason)
        assertNull(
            coordinator.processCapturedEventForTest(
                rawText = cancelledPageText,
                packageName = "com.tencent.mm",
                captureSource = "AI_ACCESSIBILITY",
                capturedAtMillis = t1937Cancel,
                verifiedActivity = appBrandAct
            )
        )

        // ==================== Step 3: 19:37:32 招行掌上生活 cmb.pb 推送「退货」通知 -> REFUND_OFFSET 自动冲抵 19:12 原单 ====================
        val t1937Refund = buildLocalMillis(2026, Calendar.NOVEMBER, 26, 19, 37, 32, 0)
        val refundTx = coordinator.processCapturedEventForTest(
            rawText = "交易提醒 | 您尾号3354的信用卡11月26日19:37在财付通-奈雪的茶退货人民币19.80元",
            packageName = "cmb.pb",
            captureSource = "AI_NOTIFICATION",
            capturedAtMillis = t1937Refund
        )
        assertNotNull(refundTx)
        assertEquals(TransactionType.REFUND_OFFSET, refundTx!!.type)
        assertEquals(order1!!.id, refundTx.relatedTransactionId)
        assertEquals("19:12 order #1 must be 100% offset by 19:37 CMB refund", 1_980L, store.getTransactionById(order1.id)!!.refundedAmountCents)
        assertEquals("Credit card liability must be restored to initial after 1 charge + 1 refund", initialCmbLiability, store.getWalletBalance(4L))

        // ==================== Step 4: 19:38:18 重新拉起 WalletBrandUI 下唯一有效的 ~20 元单 ====================
        val t1938 = buildLocalMillis(2026, Calendar.NOVEMBER, 26, 19, 38, 18, 0)
        visitTracker.onContainerOrRejectTransition(
            packageName = "com.tencent.mm",
            containerRole = WindowContainerRole.DEDICATED_PAYMENT_OR_BILL,
            verifiedActivity = "com.tencent.mm.plugin.wallet_index.ui.WalletBrandUI"
        )
        val v2 = visitTracker.evaluateLiveCashierFrame(
            packageName = "com.tencent.mm",
            rawText = cashierText1,
            hasExplicitOrderNo = false,
            verifiedActivity = cashierAct,
            nowMillis = t1938
        )
        assertTrue(v2.shouldDispatch && v2.visitId > v1.visitId)
        val order2 = coordinator.processCapturedEventForTest(
            rawText = cashierText1,
            packageName = "com.tencent.mm",
            captureSource = "AI_LIVE_CASHIER",
            capturedAtMillis = t1938,
            cashierVisitId = v2.visitId,
            verifiedActivity = cashierAct
        )
        assertNotNull(order2)
        coordinator.processCapturedEventForTest(
            rawText = "交易提醒 | 您尾号3354的信用卡11月26日19:38在财付通-奈雪的茶消费人民币19.80元",
            packageName = "cmb.pb",
            captureSource = "AI_NOTIFICATION",
            capturedAtMillis = t1938 + 3_000L
        )

        // ==================== Step 5: 3 次切 MainActivity / 微信聊天 / 桌面往返未关闭 WxaLiteAppTransparentLiteUI ====================
        for ((idx, target) in listOf("com.moji.ai", "com.tencent.mm.ui.LauncherUI", "com.android.launcher").withIndex()) {
            if (target == "com.tencent.mm.ui.LauncherUI") {
                visitTracker.onContainerOrRejectTransition(
                    packageName = "com.tencent.mm",
                    containerRole = WindowContainerRole.NON_TRANSACTION_BLOCKED,
                    rejectReason = WindowRejectReason.NON_TRANSACTION_CONTAINER,
                    verifiedActivity = target
                )
            } else {
                visitTracker.onExternalAppFocused(target)
            }
            val re = visitTracker.evaluateLiveCashierFrame(
                packageName = "com.tencent.mm",
                rawText = cashierText1,
                hasExplicitOrderNo = false,
                verifiedActivity = cashierAct,
                nowMillis = t1938 + (idx + 1) * 35_000L
            )
            assertFalse(re.shouldDispatch)
            assertEquals(v2.visitId, re.visitId)
        }

        // ==================== Step 6: 关闭收银台返回 AppBrandUI00 取餐页（上方实付 ¥19.80 + 下方合计 ¥26.00）并停留刷新 ====================
        visitTracker.onCashierWindowClosedToHost()
        val pickupText = "奈雪的茶 | 制作中 | 取餐码 A088 | 实付 ¥19.80 | 霸气茉莉初雪 ×1 | 优惠 -¥6.20 | 合计 ¥26.00 | 订单号 NY20261126193818 | 下单时间 2026-11-26 19:38:18 | 支付方式 微信支付"
        val pickupPageId = PaymentAccessibilityService.extractStablePageIdentity("com.tencent.mm", pickupText)
        assertFalse(pageDedupTracker.shouldSkipDispatch(pickupPageId, nowMillis = t1938 + 150_000L))
        val fusedOrder2 = coordinator.processCapturedEventForTest(
            rawText = pickupText,
            packageName = "com.tencent.mm",
            captureSource = "AI_ACCESSIBILITY",
            capturedAtMillis = t1938 + 150_000L,
            verifiedActivity = appBrandAct
        )
        assertNotNull(fusedOrder2)
        assertEquals(order2!!.id, fusedOrder2!!.id)
        assertEquals("AI_FUSED", fusedOrder2.captureSource)
        assertEquals(1_980L, fusedOrder2.amountCents)
        assertEquals("NY20261126193818", fusedOrder2.orderKey)
        assertEquals(4L, fusedOrder2.sourceWalletId)
        assertTrue(fusedOrder2.note.contains("霸气茉莉初雪"))

        // 停留 2 分钟后取餐状态刷新 -> 被幂等锁拦截
        val refreshedPickupText = pickupText.replace("制作中 | 取餐码 A088", "请取餐 | 取餐码 A088")
        assertTrue(
            pageDedupTracker.shouldSkipDispatch(
                PaymentAccessibilityService.extractStablePageIdentity("com.tencent.mm", refreshedPickupText),
                nowMillis = t1938 + 270_000L
            )
        )

        // ==================== AC-8 核心终态断言：全链路净有效支出严格仅 1 笔 ¥19.80！ ====================
        val allAfterReplay = store.getAllTransactions()
        val activeUnrefundedExpenses = allAfterReplay.filter {
            it.type == TransactionType.EXPENSE && (it.amountCents - it.refundedAmountCents) > 0L
        }
        assertEquals("Must have ONLY 1 active unrefunded expense (~20 RMB)", 1, activeUnrefundedExpenses.size)
        assertEquals(order2.id, activeUnrefundedExpenses.single().id)
        assertEquals(1_980L, activeUnrefundedExpenses.single().amountCents)
        val totalNetExpenseCents = allAfterReplay
            .filter { it.type == TransactionType.EXPENSE }
            .sumOf { (it.amountCents - it.refundedAmountCents).coerceAtLeast(0L) }
        assertEquals(1_980L, totalNetExpenseCents)
        assertEquals(initialCmbLiability + 1_980L, store.getWalletBalance(4L))
        assertEquals(0, uiRecorder.duplicateBanners.size)
        assertEquals(0, uiRecorder.suspectedDuplicateNotifications.size)

        // ==================== 反例验证：紧接着真实再买第 2 杯同金额 ¥19.80 -> 100% 独立入账 ====================
        val tSecondCup = buildLocalMillis(2026, Calendar.NOVEMBER, 26, 19, 45, 0, 0)
        visitTracker.onContainerOrRejectTransition(
            packageName = "com.tencent.mm",
            containerRole = WindowContainerRole.DEDICATED_PAYMENT_OR_BILL,
            verifiedActivity = "com.tencent.mm.plugin.wallet_index.ui.WalletBrandUI"
        )
        val v3 = visitTracker.evaluateLiveCashierFrame(
            packageName = "com.tencent.mm",
            rawText = cashierText1,
            hasExplicitOrderNo = false,
            verifiedActivity = cashierAct,
            nowMillis = tSecondCup
        )
        assertTrue(v3.shouldDispatch && v3.visitId > v2.visitId)
        val secondCupTx = coordinator.processCapturedEventForTest(
            rawText = cashierText1,
            packageName = "com.tencent.mm",
            captureSource = "AI_LIVE_CASHIER",
            capturedAtMillis = tSecondCup,
            cashierVisitId = v3.visitId,
            verifiedActivity = cashierAct
        )
        assertNotNull(secondCupTx)
        assertFalse(secondCupTx!!.id == order2.id)
        val finalActiveExpenses = store.getAllTransactions().filter {
            it.type == TransactionType.EXPENSE && (it.amountCents - it.refundedAmountCents) > 0L
        }
        assertEquals("Buying 2nd cup back-to-back must produce 2 independent valid expenses", 2, finalActiveExpenses.size)
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
        val silentNotifications = mutableListOf<Long>()
        val suspectedDuplicateNotifications = mutableListOf<Pair<Long, String>>()
        val recordedBanners = mutableListOf<RecordedBannerCall>()
        val duplicateBanners = mutableListOf<DuplicateBannerCall>()

        override fun cancelNotificationForTransaction(txId: Long) {
            cancelledTxIds.add(txId)
        }

        override fun sendSilentRecordedNotification(tx: TransactionEntity, wallets: List<WalletEntity>) {
            silentNotifications.add(tx.id)
        }

        override fun sendSuspectedDuplicateNotification(
            candidateId: Long,
            tx: TransactionEntity,
            wallets: List<WalletEntity>,
            duplicateReason: String,
            matchedTransactionId: Long?
        ) {
            suspectedDuplicateNotifications.add(candidateId to duplicateReason)
        }

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
            LedgerRepository.updateExistingTransactionOnStore(
                this,
                updatedTx,
                nowMillis = updatedTx.updatedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
            )

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
