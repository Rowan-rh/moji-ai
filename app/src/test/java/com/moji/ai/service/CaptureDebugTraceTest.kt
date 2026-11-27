package com.moji.ai.service

import com.moji.ai.ai.AiAccountingEngine
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Calendar
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class CaptureDebugTraceTest {

    private lateinit var tempDir: File
    private lateinit var configStore: FakeDebugTraceConfigStore
    private lateinit var store: TestPersistenceGateway
    private lateinit var uiGateway: NoOpUiGateway
    private var fakeClockMillis: Long = 1_764_200_000_000L
    private val configuredSecrets = mutableListOf(
        "myCustomProviderSecretKey2026",
        "familyAccessSecretCode77",
        "cloudJwtSessionSecret66",
        "nutstoreWebDavAppPwd55"
    )
    private lateinit var traceManager: CaptureDebugTraceManager
    private lateinit var coordinator: CapturePipelineCoordinator

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("moji_debug_trace_test_").toFile()
        configStore = FakeDebugTraceConfigStore()
        fakeClockMillis = buildLocalMillis(2026, Calendar.NOVEMBER, 27, 19, 38, 0)
        traceManager = CaptureDebugTraceManager(
            baseDir = tempDir,
            configStore = configStore,
            secretProvider = { configuredSecrets },
            clock = { fakeClockMillis }
        )
        store = TestPersistenceGateway(
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
                    name = "招商银行信用卡",
                    type = WalletType.CREDIT,
                    subTypeLabel = "信用卡",
                    brandCode = "CMB",
                    cardLast4 = "3354",
                    balanceCents = 10_000L,
                    creditLimitCents = 1_000_000L,
                    aliases = "招行信用卡,个人消费卡,3354"
                )
            )
        )
        uiGateway = NoOpUiGateway()
        coordinator = CapturePipelineCoordinator(
            repository = store,
            aiEngine = AiAccountingEngine(configManager = null, merchantMemoryManager = null),
            uiGateway = uiGateway,
            smartRuleEngine = null,
            debugTraceStore = traceManager
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun ac1_defaultClosed_zeroDiskWritesWhenDisabled_survivesRestart_stopWritePreservesFile_andClearDeletes() = runBlocking {
        // 1. 独立本地偏好名校验（与 MoJiApp 云同步监听偏好物理隔离）且初始严格关闭 + 默认 5MB 档位
        assertEquals("moji_debug_trace_prefs", SharedPrefsDebugTraceConfigStore.PREFS_NAME)
        assertFalse(traceManager.isEnabled())
        assertEquals(DebugTraceQuotaTier.MB_5, traceManager.getQuotaTier())
        assertFalse(traceManager.logFile.exists())

        // 2. 关闭态下执行入账与前置窗口拦截：严格零目录创建、零磁盘追加写入
        val txWhenOff = coordinator.processCapturedEventForTest(
            rawText = "微信支付 | 支付成功 | 瑞幸咖啡 | ¥9.90 | 付款方式 | 零钱 | 完成",
            packageName = "com.tencent.mm",
            captureSource = "AI_LIVE_CASHIER",
            capturedAtMillis = fakeClockMillis,
            cashierVisitId = 1L
        )
        assertNotNull(txWhenOff)
        val blockedWhenOff = traceManager.recordWindowGuardBlocked(
            packageName = "com.tencent.mm",
            verifiedActivity = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI00",
            structureFingerprint = "fp_cancel_01",
            whitelistAllowed = true,
            whitelistSummary = "微信无障碍放行",
            containerRole = "SHARED_OR_WEB_STRICT",
            guardRuleName = "CLOSED_FAILED_OR_REFUND_PENDING",
            detailReason = "订单已取消"
        )
        assertFalse(blockedWhenOff)
        assertFalse("Disabled mode must never create .jsonl file", traceManager.logFile.exists())
        assertEquals(0, traceManager.statsFlow.value.entryCount)

        // 3. 手动开启并切换 10MB 档位后写入 `filesDir/diagnostics/capture_debug_traces.jsonl`
        traceManager.setEnabled(true)
        traceManager.setQuotaTier(DebugTraceQuotaTier.MB_10)
        val txWhenOn = coordinator.processCapturedEventForTest(
            rawText = "微信支付 | 支付成功 | 喜茶 | ¥18.00 | 付款方式 | 零钱 | 完成",
            packageName = "com.tencent.mm",
            captureSource = "AI_LIVE_CASHIER",
            capturedAtMillis = fakeClockMillis + 5_000L,
            cashierVisitId = 2L
        )
        assertNotNull(txWhenOn)
        assertTrue(traceManager.logFile.exists())
        assertEquals(
            File(File(tempDir, "diagnostics"), "capture_debug_traces.jsonl").absolutePath,
            traceManager.logFile.absolutePath
        )
        assertEquals(1, traceManager.statsFlow.value.entryCount)

        // 4. 模拟杀进程重启：重新实例化管理器后开关状态、所选档位与已落盘日志完整保留
        val restartedManager = CaptureDebugTraceManager(
            baseDir = tempDir,
            configStore = configStore,
            secretProvider = { configuredSecrets },
            clock = { fakeClockMillis + 10_000L }
        )
        assertTrue(restartedManager.isEnabled())
        assertEquals(DebugTraceQuotaTier.MB_10, restartedManager.getQuotaTier())
        assertEquals(1, restartedManager.statsFlow.value.entryCount)
        assertEquals("喜茶", restartedManager.readAllTracesNewestFirst().single().merchant)

        // 5. 手动关闭开关：仅停止新写入，保留已录下的历史日志供查看/导出
        restartedManager.setEnabled(false)
        assertFalse(restartedManager.isEnabled())
        val appendedWhilePaused = restartedManager.appendTrace(
            CaptureDebugTraceEntry(
                timestampMillis = fakeClockMillis + 15_000L,
                outcome = DebugTraceOutcome.RECORDED,
                merchant = "关闭后不应写入"
            )
        )
        assertFalse(appendedWhilePaused)
        assertTrue("Turning off must preserve existing log file", restartedManager.logFile.exists())
        assertEquals(1, restartedManager.readAllTracesNewestFirst().size)

        // 6. 点击独立「清空日志」确认后才物理删除文件
        restartedManager.clearAllTraces()
        assertFalse(restartedManager.logFile.exists())
        assertEquals(0, restartedManager.statsFlow.value.entryCount)
        assertEquals(0L, restartedManager.statsFlow.value.usedBytes)
    }

    @Test
    fun ac2_and_ac5_sixStageStructuredReasoning_coversAllOutcomes_andChronologicalMultiSegmentProvenance() = runBlocking {
        traceManager.setEnabled(true)
        val t0 = buildLocalMillis(2026, Calendar.NOVEMBER, 27, 19, 38, 12)

        // ① RECORDED：微信小程序商家订单页（强实付 ¥19.80 优先于下方弱合计 ¥26.00）
        val miniProgramUpstream = UpstreamWindowCaptureContext(
            verifiedActivity = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI00",
            structureFingerprint = "ab12cd34ef567890",
            whitelistAllowed = true,
            whitelistSummary = "微信无障碍识屏已开启",
            containerRole = "SHARED_OR_WEB_STRICT",
            guardRuleName = "PASSED_WINDOW_GUARD",
            cashierVisitId = 0L,
            sessionAction = "NON_CASHIER_PAGE"
        )
        val stage1Tx = coordinator.processCapturedEventForTest(
            rawText = "奈雪的茶点单 | 订单已完成 | 霸气芝士草莓 x1 | 商品总价 ¥26.00 | 会员立减 -¥6.20 | 实付 ¥19.80 | 合计 ¥26.00 | 下单时间 2026-11-27 19:38:10 | 订单编号 NY2026112719381088",
            packageName = "com.tencent.mm",
            captureSource = "AI_ACCESSIBILITY",
            capturedAtMillis = t0,
            verifiedActivity = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI00",
            upstreamContext = miniProgramUpstream
        )
        assertNotNull(stage1Tx)
        assertEquals(1_980L, stage1Tx!!.amountCents)
        assertEquals(620L, stage1Tx.discountCents)

        // 模拟异步截屏保存完成后回填相对文件名与 SHA-256 哈希引用（不存图像二进制）
        traceManager.attachScreenshotRefToTransactionTrace(
            transactionId = stage1Tx.id,
            screenshotPath = "/data/user/0/com.moji.ai.qa/files/bill_screenshots/bill_nayuki_01.webp",
            screenshotSha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        )

        // ③ FUSED_PATCH：15 秒后招行掌上生活 `cmb.pb` 信用卡通知到达，原位跨通道融合补齐扣款信用卡(3354)
        val stage2FusedTx = coordinator.processCapturedEventForTest(
            rawText = "招商银行信用卡 | 消费提醒：您尾号3354的个人消费卡于11月27日19:38消费人民币19.80元",
            packageName = "cmb.pb",
            captureSource = "AI_NOTIFICATION",
            capturedAtMillis = t0 + 15_000L
        )
        assertNotNull(stage2FusedTx)
        assertEquals(stage1Tx.id, stage2FusedTx!!.id)
        assertEquals("AI_FUSED", stage2FusedTx.captureSource)
        assertEquals(2L, stage2FusedTx.sourceWalletId)

        // 校验 AC-5.2：单笔 AI_FUSED 账单按时间升序返回 2 段完整 6 阶段轨迹，且报告包含凭据与融合改写明细
        val nayukiTraces = traceManager.getTracesForTransaction(stage1Tx.id, stage2FusedTx.orderKey)
        assertEquals(2, nayukiTraces.size)
        val seg1 = nayukiTraces[0]
        val seg2 = nayukiTraces[1]
        assertEquals(DebugTraceOutcome.RECORDED, seg1.outcome)
        assertEquals("bill_nayuki_01.webp", seg1.sourceCapture.screenshotRef)
        assertTrue(seg1.sourceCapture.screenshotSha256.startsWith("9f86d081884c"))
        assertEquals("ab12cd34ef567890", seg1.sourceCapture.structureFingerprint)
        assertTrue(seg1.windowSessionGuard.guardAccepted)
        assertTrue(seg1.parseEngineRoute.routeSelected.isNotBlank())
        assertTrue(
            "Expected strong 实付 branch in stage 4, got: ${seg1.fieldExtraction.amountRuleBranch}",
            seg1.fieldExtraction.amountRuleBranch.contains("实付")
        )
        assertEquals("餐饮-奶茶", seg1.categoryWalletMapping.finalCategory)
        assertTrue(seg1.categoryWalletMapping.weightedKeywordsHit.isNotEmpty())
        assertEquals(stage1Tx.id, seg1.pipelineDedupFusion.finalTransactionId)

        assertEquals(DebugTraceOutcome.FUSED_PATCH, seg2.outcome)
        assertEquals("cmb.pb", seg2.sourceCapture.packageName)
        assertTrue(seg2.categoryWalletMapping.walletMatchBasis.contains("3354"))
        assertTrue(
            "Expected sourceWalletId diff in fusedFieldsDiff, got: ${seg2.pipelineDedupFusion.fusedFieldsDiff}",
            seg2.pipelineDedupFusion.fusedFieldsDiff.any { it.contains("sourceWalletId:") && it.contains("招商银行信用卡") }
        )

        val evidences = store.getEvidencesForTransaction(stage1Tx.id)
        val report = CaptureDebugTraceStore.formatTransactionProvenanceReport(
            tx = stage2FusedTx,
            evidences = evidences,
            traces = nayukiTraces
        )
        assertTrue(report.contains("第 1 段 · 已记账 (RECORDED)"))
        assertTrue(report.contains("第 2 段 · 跨道融合 (FUSED_PATCH)"))
        assertTrue(report.contains("SOURCE_CAPTURE") && report.contains("PIPELINE_DEDUP_FUSION"))

        // ④ DEDUP_BLOCKED：同一条招行消费通知无新增字段重复到达，静默幂等防重拦截
        val dedupBlockedResult = coordinator.processCapturedEventForTest(
            rawText = "招商银行信用卡 | 消费提醒：您尾号3354的个人消费卡于11月27日19:38消费人民币19.80元",
            packageName = "cmb.pb",
            captureSource = "AI_NOTIFICATION",
            capturedAtMillis = t0 + 25_000L
        )
        assertNull(dedupBlockedResult)
        assertTrue(traceManager.readAllTracesNewestFirst().any { it.outcome == DebugTraceOutcome.DEDUP_BLOCKED })

        // ⑤ DUPLICATE_SUSPECTED：通过沙盒重放一笔细节已齐全的同单账单，触发疑似重复未录入
        val dupSuspectedResult = coordinator.processCapturedEventForTest(
            rawText = "奈雪的茶 | 实付 ¥19.80 | 付款方式 招商银行信用卡(3354) | 下单时间 2026-11-27 19:38:10 | 订单编号 NY2026112719381088",
            packageName = "com.tencent.mm",
            captureSource = "AI_SANDBOX",
            capturedAtMillis = t0 + 35_000L
        )
        assertNull(dupSuspectedResult)
        assertTrue(coordinator.getPendingDuplicateCandidateIdsForTest().isNotEmpty())
        assertEquals(1, uiGateway.suspectedDuplicateCount)
        assertTrue(traceManager.readAllTracesNewestFirst().any { it.outcome == DebugTraceOutcome.DUPLICATE_SUSPECTED })

        // ② REFUND_OFFSET：招行信用卡「退货」通知（含 `7天无理由退货` 保障干扰词剥离），自动关联冲抵原单
        val refundTx = coordinator.processCapturedEventForTest(
            rawText = "招商银行信用卡 | 退货通知：您尾号3354的信用卡于11月27日19:39发生退货人民币19.80元（商品支持7天无理由退货）",
            packageName = "cmb.pb",
            captureSource = "AI_NOTIFICATION",
            capturedAtMillis = t0 + 60_000L
        )
        assertNotNull(refundTx)
        assertEquals(TransactionType.REFUND_OFFSET, refundTx!!.type)
        assertEquals(stage1Tx.id, refundTx.relatedTransactionId)
        val refundTrace = traceManager.readAllTracesNewestFirst().first { it.outcome == DebugTraceOutcome.REFUND_OFFSET }
        assertEquals(stage1Tx.id, refundTrace.fieldExtraction.linkedExpenseId)
        assertTrue(refundTrace.fieldExtraction.strippedSafeguardPhrases.any { it.contains("7天无理由退货") })
        assertTrue(refundTrace.fieldExtraction.matchedDirectionKeywords.any { it.contains("退货") })

        // ⑤ NOISE_IGNORED：非账单验证码通知 & 纯物流文本双路径噪音过滤留痕
        val noiseOtpResult = coordinator.processCapturedEventForTest(
            rawText = "招商银行 | 您的网上支付验证码为392817，本次消费确认付款金额19.80元，请勿泄露",
            packageName = "cmb.pb",
            captureSource = "AI_NOTIFICATION",
            capturedAtMillis = t0 + 70_000L
        )
        assertNull(noiseOtpResult)
        val noisePlainResult = coordinator.processCapturedEventForTest(
            rawText = "顺丰速运：您的包裹已到达丰巢智能柜，请及时前往领取",
            packageName = "com.sf.activity",
            captureSource = "AI_NOTIFICATION",
            capturedAtMillis = t0 + 75_000L
        )
        assertNull(noisePlainResult)
        assertTrue(traceManager.readAllTracesNewestFirst().count { it.outcome == DebugTraceOutcome.NOISE_IGNORED } >= 2)
    }

    @Test
    fun ac3_windowGuardBlocked_recordsPaymentReject_debouncesSameFingerprint15s_andIgnoresDesktopAndChat() {
        traceManager.setEnabled(true)

        // 1. 桌面 Launcher、本应用包名与微信纯聊天列表切换一律静默忽略（不刷屏占配额）
        assertFalse(
            traceManager.recordWindowGuardBlocked(
                packageName = "com.miui.home",
                verifiedActivity = "com.miui.home.launcher.Launcher",
                structureFingerprint = "fp_home",
                whitelistAllowed = false,
                whitelistSummary = "桌面",
                containerRole = "NON_TRANSACTION_BLOCKED",
                guardRuleName = "NON_TRANSACTION_CONTAINER",
                detailReason = "桌面切换"
            )
        )
        assertFalse(
            traceManager.recordWindowGuardBlocked(
                packageName = "com.moji.ai.qa",
                verifiedActivity = "com.moji.ai.MainActivity",
                structureFingerprint = "fp_self",
                whitelistAllowed = false,
                whitelistSummary = "自身包名",
                containerRole = "NON_TRANSACTION_BLOCKED",
                guardRuleName = "SELF_PACKAGE",
                detailReason = "自身应用"
            )
        )
        assertFalse(
            traceManager.recordWindowGuardBlocked(
                packageName = "com.tencent.mm",
                verifiedActivity = "com.tencent.mm.ui.LauncherUI",
                structureFingerprint = "fp_wx_chat",
                whitelistAllowed = true,
                whitelistSummary = "微信",
                containerRole = "NON_TRANSACTION_BLOCKED",
                guardRuleName = "NON_TRANSACTION_CONTAINER",
                detailReason = "纯聊天主界面",
                rawTextSample = "微信 | 全部会话 | 按住 说话"
            )
        )
        assertEquals(0, traceManager.statsFlow.value.entryCount)

        // 2. 支付应用内小程序「已退单」状态页首次被 CaptureWindowGuard 拦截：正常落盘 WINDOW_GUARD_BLOCKED
        val firstRecorded = traceManager.recordWindowGuardBlocked(
            packageName = "com.tencent.mm",
            verifiedActivity = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI00",
            structureFingerprint = "fp_appbrand_refund_99",
            whitelistAllowed = true,
            whitelistSummary = "微信无障碍放行",
            containerRole = "SHARED_OR_WEB_STRICT",
            guardRuleName = "CLOSED_FAILED_OR_REFUND_PENDING",
            detailReason = "小程序独立状态词：已退单",
            rawTextSample = "奈雪的茶 | 已退单 | 实付 ¥19.80",
            capturedAtMillis = fakeClockMillis
        )
        assertTrue(firstRecorded)
        assertEquals(1, traceManager.statsFlow.value.entryCount)

        // 3. 15 秒内（+4s、+12s）同一 packageName + structureFingerprint + guardRuleName 重复触发：静默合并计数，不追加磁盘行
        assertFalse(
            traceManager.recordWindowGuardBlocked(
                packageName = "com.tencent.mm",
                verifiedActivity = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI00",
                structureFingerprint = "fp_appbrand_refund_99",
                whitelistAllowed = true,
                whitelistSummary = "微信无障碍放行",
                containerRole = "SHARED_OR_WEB_STRICT",
                guardRuleName = "CLOSED_FAILED_OR_REFUND_PENDING",
                detailReason = "小程序独立状态词：已退单",
                capturedAtMillis = fakeClockMillis + 4_000L
            )
        )
        assertFalse(
            traceManager.recordWindowGuardBlocked(
                packageName = "com.tencent.mm",
                verifiedActivity = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI00",
                structureFingerprint = "fp_appbrand_refund_99",
                whitelistAllowed = true,
                whitelistSummary = "微信无障碍放行",
                containerRole = "SHARED_OR_WEB_STRICT",
                guardRuleName = "CLOSED_FAILED_OR_REFUND_PENDING",
                detailReason = "小程序独立状态词：已退单",
                capturedAtMillis = fakeClockMillis + 12_000L
            )
        )
        assertEquals(1, traceManager.statsFlow.value.entryCount)
        assertEquals(2, traceManager.readAllTracesNewestFirst().single().windowSessionGuard.suppressedRepeatCount)

        // 4. 超过 15 秒（+15.5s）后再次触发：允许重新落盘一条新记录
        assertTrue(
            traceManager.recordWindowGuardBlocked(
                packageName = "com.tencent.mm",
                verifiedActivity = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI00",
                structureFingerprint = "fp_appbrand_refund_99",
                whitelistAllowed = true,
                whitelistSummary = "微信无障碍放行",
                containerRole = "SHARED_OR_WEB_STRICT",
                guardRuleName = "CLOSED_FAILED_OR_REFUND_PENDING",
                detailReason = "小程序独立状态词：已退单",
                capturedAtMillis = fakeClockMillis + 15_500L
            )
        )
        assertEquals(2, traceManager.statsFlow.value.entryCount)
    }

    @Test
    fun ac4_fourQuotaTiers_dualThresholdFifo20PercentEviction_tierShrink_corruptLineSkip_andFullSecretRedaction() {
        // 1. 校验 4 档空间占用枚举参数与默认值
        assertEquals(1L * 1024L * 1024L, DebugTraceQuotaTier.MB_1.maxBytes)
        assertEquals(300, DebugTraceQuotaTier.MB_1.maxEntries)
        assertEquals(5L * 1024L * 1024L, DebugTraceQuotaTier.MB_5.maxBytes)
        assertEquals(1_500, DebugTraceQuotaTier.MB_5.maxEntries)
        assertTrue(DebugTraceQuotaTier.MB_5.isDefault)
        assertEquals(10L * 1024L * 1024L, DebugTraceQuotaTier.MB_10.maxBytes)
        assertEquals(3_000, DebugTraceQuotaTier.MB_10.maxEntries)
        assertEquals(20L * 1024L * 1024L, DebugTraceQuotaTier.MB_20.maxBytes)
        assertEquals(6_000, DebugTraceQuotaTier.MB_20.maxEntries)

        traceManager.setEnabled(true)

        // 2. 先在 20 MB 档位写入 360 条记录，再切回 1 MB（上限 300 条）：立即触发 20% 最旧 FIFO 裁剪（360 - 72 = 288 条）
        traceManager.setQuotaTier(DebugTraceQuotaTier.MB_20)
        for (i in 1..360) {
            traceManager.appendTrace(
                CaptureDebugTraceEntry(
                    traceId = "trace_$i",
                    timestampMillis = fakeClockMillis + i * 1_000L,
                    outcome = DebugTraceOutcome.RECORDED,
                    merchant = "商户_$i",
                    amountCents = 100L * i
                )
            )
        }
        assertEquals(360, traceManager.statsFlow.value.entryCount)

        traceManager.setQuotaTier(DebugTraceQuotaTier.MB_1)
        assertEquals(288, traceManager.statsFlow.value.entryCount)
        val afterShrink = traceManager.readAllTracesNewestFirst()
        assertEquals("trace_360", afterShrink.first().traceId)
        assertEquals("trace_73", afterShrink.last().traceId)
        assertFalse(File(traceManager.logFile.parentFile, "${traceManager.logFile.name}.tmp").exists())

        // 3. 在 1 MB 档位继续写到第 301 条（再写 13 条：288 + 13 = 301 > 300），触发条数上限 20% 淘汰（301 - 61 = 240 条）
        for (i in 361..373) {
            traceManager.appendTrace(
                CaptureDebugTraceEntry(
                    traceId = "trace_$i",
                    timestampMillis = fakeClockMillis + i * 1_000L,
                    outcome = DebugTraceOutcome.RECORDED,
                    merchant = "商户_$i"
                )
            )
        }
        assertEquals(240, traceManager.statsFlow.value.entryCount)
        assertEquals("trace_373", traceManager.readAllTracesNewestFirst().first().traceId)
        assertEquals("trace_134", traceManager.readAllTracesNewestFirst().last().traceId)

        // 4. 字节数阈值触顶测试 + 损坏行容错测试：注入一行损坏 JSON，再写入高字节条目触发 > 1 MiB 字节裁剪（条数仍 < 300）
        traceManager.clearAllTraces()
        traceManager.logFile.parentFile?.mkdirs()
        traceManager.logFile.writeText("{\"broken_json\": true\n", Charsets.UTF_8)
        traceManager.refreshStatsFromDisk()
        assertEquals("Corrupted line must be skipped cleanly", 0, traceManager.readAllTracesNewestFirst().size)

        val heavyChunk = "订单商品明细描述字段填充测试".repeat(140) // ~1960 字符 ≈ 5.8 KB UTF-8 / 条
        for (i in 1..210) {
            traceManager.appendTrace(
                CaptureDebugTraceEntry(
                    traceId = "heavy_$i",
                    timestampMillis = fakeClockMillis + i * 1_000L,
                    outcome = DebugTraceOutcome.RECORDED,
                    merchant = "大体积测试_$i",
                    sourceCapture = SourceCaptureStage(rawTextPreview = heavyChunk)
                )
            )
        }
        val statsAfterByteEvict = traceManager.statsFlow.value
        assertTrue(
            "Byte cap must keep file <= 1 MiB, got ${statsAfterByteEvict.usedBytes}",
            statsAfterByteEvict.usedBytes <= DebugTraceQuotaTier.MB_1.maxBytes
        )
        assertTrue("Byte cap must trigger before 210 entries", statsAfterByteEvict.entryCount < 210)
        assertEquals("heavy_210", traceManager.readAllTracesNewestFirst().first().traceId)

        // 5. 密钥与凭据 100% 强制脱敏红线测试（落盘 .jsonl / 导出流 / 单条 JSON / 账单溯源报告）
        traceManager.clearAllTraces()
        val sensitiveRaw = """
            Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.secretPayload
            openaiKey=sk-liveSecretKey9988776655
            geminiHeader x-goog-api-key: AIzaSyTestSecretKey1234567890ABCDEF
            endpoint=https://generativelanguage.googleapis.com/v1beta/models/gemini:generateContent?key=urlQuerySecret888
            customKey=myCustomProviderSecretKey2026
            syncKey=MOJI-ABCD-EFGH-JKLM-NPQR qaKey=MOJIQA-1234-5678-90AB-CDEF
            accessCode=familyAccessSecretCode77 accessToken=cloudJwtSessionSecret66
            webdavPwd=nutstoreWebDavAppPwd55
            {"apiKey":"jsonInlineApiKey4433","appPassword":"jsonWebDavPassword2211"}
        """.trimIndent()

        traceManager.appendTrace(
            CaptureDebugTraceEntry(
                traceId = "secret_test_1",
                timestampMillis = fakeClockMillis + 999_000L,
                outcome = DebugTraceOutcome.RECORDED,
                transactionId = 88L,
                merchant = "脱敏验证商户",
                sourceCapture = SourceCaptureStage(rawTextPreview = sensitiveRaw),
                parseEngineRoute = ParseEngineRouteStage(
                    routeSelected = "LLM_REMOTE_FALLBACK",
                    maskedEndpoint = "https://api.example.com/v1/chat?api_key=urlQuerySecret888",
                    fallbackOrSkipReason = sensitiveRaw
                )
            )
        )

        val persistedDiskText = traceManager.logFile.readText(Charsets.UTF_8)
        val exportedBytes = ByteArrayOutputStream().also { traceManager.exportJsonlToStream(it) }
        val exportedText = exportedBytes.toString(Charsets.UTF_8.name())
        val singleEntry = traceManager.readAllTracesNewestFirst().single()
        val prettyJson = traceManager.encodeTracePrettyJson(singleEntry)
        val provenanceReport = CaptureDebugTraceStore.formatTransactionProvenanceReport(
            tx = TransactionEntity(
                id = 88L,
                type = TransactionType.EXPENSE,
                amountCents = 1980L,
                amountCnyCents = 1980L,
                category = "餐饮",
                merchant = "脱敏验证商户",
                sourceWalletId = 2L,
                timestamp = fakeClockMillis,
                captureSource = "AI_NOTIFICATION",
                rawCapturedText = sensitiveRaw
            ),
            evidences = emptyList(),
            traces = listOf(singleEntry),
            extraSecrets = configuredSecrets
        )

        val forbiddenLeaks = listOf(
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
            "sk-liveSecretKey9988776655",
            "AIzaSyTestSecretKey1234567890ABCDEF",
            "urlQuerySecret888",
            "myCustomProviderSecretKey2026",
            "MOJI-ABCD-EFGH-JKLM-NPQR",
            "MOJIQA-1234-5678-90AB-CDEF",
            "familyAccessSecretCode77",
            "cloudJwtSessionSecret66",
            "nutstoreWebDavAppPwd55",
            "jsonInlineApiKey4433",
            "jsonWebDavPassword2211"
        )
        for (secret in forbiddenLeaks) {
            assertFalse("Persisted .jsonl leaked secret: $secret", persistedDiskText.contains(secret))
            assertFalse("Exported .jsonl leaked secret: $secret", exportedText.contains(secret))
            assertFalse("Pretty JSON leaked secret: $secret", prettyJson.contains(secret))
            assertFalse("Provenance report leaked secret: $secret", provenanceReport.contains(secret))
        }
        assertTrue(persistedDiskText.contains("REDACTED"))
        assertTrue(provenanceReport.contains("REDACTED"))
    }

    private fun buildLocalMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int
    ): Long = Calendar.getInstance().apply {
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month)
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, second)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private class FakeDebugTraceConfigStore : DebugTraceConfigStore {
        private var enabled: Boolean = false
        private var tier: DebugTraceQuotaTier = DebugTraceQuotaTier.MB_5

        override fun isEnabled(): Boolean = enabled
        override fun setEnabled(enabled: Boolean) {
            this.enabled = enabled
        }

        override fun getQuotaTier(): DebugTraceQuotaTier = tier
        override fun setQuotaTier(tier: DebugTraceQuotaTier) {
            this.tier = tier
        }
    }

    private class NoOpUiGateway : PipelineUiGateway {
        var suspectedDuplicateCount: Int = 0

        override fun cancelNotificationForTransaction(txId: Long) = Unit
        override fun sendSilentRecordedNotification(tx: TransactionEntity, wallets: List<WalletEntity>) = Unit
        override fun sendSuspectedDuplicateNotification(
            candidateId: Long,
            tx: TransactionEntity,
            wallets: List<WalletEntity>,
            duplicateReason: String,
            matchedTransactionId: Long?
        ) {
            suspectedDuplicateCount++
        }

        override fun showRecordedBanner(
            statusTitle: String,
            amountText: String,
            subtitle: String,
            isIncome: Boolean,
            transactionId: Long
        ) = Unit

        override fun showSuspectedDuplicateBanner(
            amountText: String,
            subtitle: String,
            walletName: String,
            matchedTransactionId: Long?,
            onForceRecord: () -> Unit
        ) {
            suspectedDuplicateCount++
        }
    }

    private class TestPersistenceGateway(
        initialWallets: List<WalletEntity>
    ) : PipelinePersistenceGateway, LedgerTransactionStore {
        private val txIdGen = AtomicLong(100L)
        private val evIdGen = AtomicLong(1000L)
        private val wallets = initialWallets.associateBy { it.id }.toMutableMap()
        private val txs = linkedMapOf<Long, TransactionEntity>()
        private val evidences = mutableListOf<CaptureEvidenceEntity>()

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
