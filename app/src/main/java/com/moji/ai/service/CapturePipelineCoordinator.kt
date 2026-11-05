package com.moji.ai.service

import com.moji.ai.ai.AiAccountingEngine
import com.moji.ai.data.repository.LedgerRepository
import com.moji.ai.notification.SilentBookkeepingNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 智能防重与双通道（通知栏 + 无障碍页面）上下文合并协调器：
 *
 * 痛点解决：
 * 当用户在微信/支付宝付款成功时，往往 1.5 秒内会先后触发：
 * 1) 无障碍捕获到“支付成功 ¥28.50 瑞幸咖啡”
 * 2) 通知栏捕获到“招商银行信用卡(6612)消费人民币28.50元”
 *
 * 如果各自独立触发，不仅会重复记两笔账，而且还会丢失信息（页面知道商户，通知知道具体哪张银行卡）。
 * 本协调器开启 1200ms 滑动聚合窗口，将同一笔交易的页面文本与通知文本【拼接合并】后一次性交给 AI 解析！
 */
class CapturePipelineCoordinator(
    private val repository: LedgerRepository,
    private val aiEngine: AiAccountingEngine,
    private val notifier: SilentBookkeepingNotifier
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val recentProcessedFingerprints = ConcurrentHashMap<String, Long>()
    private val pendingLock = Any()
    private var pendingTexts = mutableListOf<String>()
    private var pendingPackages = mutableSetOf<String>()
    private var pendingSources = mutableSetOf<String>()
    private var debounceJob: Job? = null

    private val financialKeywordRegex = Regex(
        """(支付成功|付款成功|交易成功|消费|扣款|支出|转出|转账|收款|到账|还款|退款|退回|零钱|余额宝|花呗|白条|信用卡|储蓄卡|尾号|¥|￥|RMB|USD|HKD|\$)""",
        RegexOption.IGNORE_CASE
    )
    private val numberRegex = Regex("""[0-9]+(\.[0-9]{1,2})?""")

    fun onRawFinancialEventCaptured(
        rawText: String,
        packageName: String,
        captureSource: String
    ) {
        val cleaned = rawText.trim()
        if (cleaned.length < 4) return
        if (!financialKeywordRegex.containsMatchIn(cleaned) || !numberRegex.containsMatchIn(cleaned)) {
            return
        }

        // 15秒内完全相同的文本直接丢弃
        val exactHash = "${packageName}_${cleaned.hashCode()}"
        val now = System.currentTimeMillis()
        recentProcessedFingerprints.entries.removeIf { now - it.value > 20_000L }
        if (recentProcessedFingerprints.containsKey(exactHash)) {
            return
        }
        recentProcessedFingerprints[exactHash] = now

        synchronized(pendingLock) {
            pendingTexts.add("[$captureSource | $packageName]: $cleaned")
            pendingPackages.add(packageName)
            pendingSources.add(captureSource)

            debounceJob?.cancel()
            debounceJob = scope.launch {
                // 等待 1100ms 聚合窗口，合并同时到达的页面节点与银行/微信通知
                delay(1100L)
                flushAndProcessBatch()
            }
        }
    }

    /**
     * 供 App 内“AI 识账沙盒测试台”或快捷指令直接立即调用
     */
    suspend fun processImmediateText(
        rawText: String,
        packageName: String = "com.moji.ai.sandbox",
        captureSource: String = "AI_SANDBOX"
    ) = executeAiPipeline(rawText, packageName, captureSource)

    private suspend fun flushAndProcessBatch() {
        val mergedText: String
        val primaryPackage: String
        val mergedSource: String

        synchronized(pendingLock) {
            if (pendingTexts.isEmpty()) return
            mergedText = pendingTexts.joinToString("\n---\n")
            primaryPackage = pendingPackages.firstOrNull() ?: "unknown"
            mergedSource = if (pendingSources.size > 1) "AI_MERGED" else pendingSources.first()
            pendingTexts.clear()
            pendingPackages.clear()
            pendingSources.clear()
        }

        executeAiPipeline(mergedText, primaryPackage, mergedSource)
    }

    private suspend fun executeAiPipeline(
        mergedText: String,
        packageName: String,
        captureSource: String
    ): com.moji.ai.data.local.TransactionEntity? {
        val wallets = repository.getAllWallets()
        val recentExpenses = repository.getRecentRefundableExpenses(15)

        val parsedTx = aiEngine.parseCapturedText(
            rawText = mergedText,
            sourceAppPackage = packageName,
            captureMode = captureSource,
            wallets = wallets,
            recentExpenses = recentExpenses
        ) ?: return null

        // 二次幂等校验：防止 12 秒内相同金额 + 相同交易类型的重复入账
        val amountTypeFingerprint = "AMT_${parsedTx.type}_${parsedTx.amountCents}_${parsedTx.currency}"
        val now = System.currentTimeMillis()
        if (captureSource != "AI_SANDBOX") {
            val prevTime = recentProcessedFingerprints[amountTypeFingerprint]
            if (prevTime != null && (now - prevTime) < 12_000L) {
                return null
            }
            recentProcessedFingerprints[amountTypeFingerprint] = now
        }

        val newId = repository.recordTransaction(parsedTx)
        val savedTx = parsedTx.copy(id = newId)

        // 后台静默入账完成后，发送带“一键撤销”按钮的系统通知栏提醒
        val updatedWallets = repository.getAllWallets()
        notifier.sendSilentRecordedNotification(savedTx, updatedWallets)
        return savedTx
    }
}
