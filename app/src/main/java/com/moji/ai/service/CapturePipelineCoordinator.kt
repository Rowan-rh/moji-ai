package com.moji.ai.service

import com.moji.ai.ai.AiAccountingEngine
import com.moji.ai.ai.SmartRuleEngine
import com.moji.ai.ai.SmartRuleExecutionResult
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.repository.LedgerRepository
import com.moji.ai.notification.SilentBookkeepingNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class CaptureEventOutcome(val badgeLabel: String) {
    RECORDED("已静默入账"),
    FUSED_PATCH("证据融合补全"),
    DEDUP_BLOCKED("幂等防重拦截"),
    NOISE_IGNORED("非账单噪音过滤")
}

@Serializable
data class CaptureObservabilityLogEntry(
    val id: Long = System.currentTimeMillis(),
    val timestampMillis: Long = System.currentTimeMillis(),
    val packageName: String,
    val captureSource: String,
    val rawText: String,
    val outcome: CaptureEventOutcome,
    val detailSummary: String,
    val transactionId: Long? = null
)

/**
 * 智能防重、三通道（通知栏 + 无障碍节点 + 免 Root 端侧 OCR）滑动窗口聚合 + 30s 跨通道事后证据融合协调器：
 *
 * 1) 滑动窗口合并：同时到达的页面节点与通知直接拼接为 AI_MERGED 一次性送入 AI；
 * 2) 交易单号级幂等去重 + 30s 跨通道事后证据融合（Evidence Fusion Patch）：
 *    - 提取微信/支付宝「交易单号 / 订单号 / 转账单号 / 商户单号」，防止多阶延迟探测重复入账；
 *    - 若银行扣款通知/短信比支付页面晚到 3~30 秒，识别到同金额同币种流水后自动补全尾号账户或具体商户；
 * 3) 免悬浮窗权限顶部胶囊提示（RecognitionBanner）+ 静默通知栏一键撤销。
 */
class CapturePipelineCoordinator(
    private val repository: LedgerRepository,
    private val aiEngine: AiAccountingEngine,
    private val notifier: SilentBookkeepingNotifier,
    private val smartRuleEngine: SmartRuleEngine? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val recentProcessedFingerprints = ConcurrentHashMap<String, Long>()
    private val recentTransactionNumbers = ConcurrentHashMap<String, Long>()
    private val recentRecordedTransactions = ConcurrentHashMap<String, TransactionEntity>()
    private val pendingLock = Any()
    private var pendingTexts = mutableListOf<String>()
    private var pendingPackages = mutableSetOf<String>()
    private var pendingSources = mutableSetOf<String>()
    private var debounceJob: Job? = null

    private val _observabilityLogs = MutableStateFlow(defaultDiagnosticSeedLogs())
    val observabilityLogs: StateFlow<List<CaptureObservabilityLogEntry>> = _observabilityLogs.asStateFlow()

    private val financialKeywordRegex = Regex(
        """(支付成功|付款成功|交易成功|账单详情|交易详情|当前状态|交易状态|交易单号|商户单号|转账单号|订单号|支付方式|付款方式|对方已收钱|已存入零钱|已收钱|消费|扣款|支出|转出|转账|收款|到账|还款|退款|退回|零钱|余额宝|花呗|白条|信用卡|储蓄卡|尾号|¥|￥|RMB|USD|HKD|\$)""",
        RegexOption.IGNORE_CASE
    )
    private val numberRegex = Regex("""[0-9]+(\.[0-9]{1,2})?""")
    private val txNumberRegex = Regex(
        """(?:交易单号|订单号|转账单号|商户单号|商家订单号)\s*(?:\||[:：])?\s*([0-9A-Za-z_-]{10,42})"""
    )
    private val billTimeRegex = Regex(
        """(?:支付时间|转账时间|收款时间|创建时间|到账时间|退款时间)\s*(?:\||[:：])?\s*(20\d{2}-\d{2}-\d{2}\s+\d{2}:\d{2}(?::\d{2})?)"""
    )

    fun onRawFinancialEventCaptured(
        rawText: String,
        packageName: String,
        captureSource: String
    ) {
        val cleaned = rawText.trim()
        if (cleaned.length < 4) return
        if (!financialKeywordRegex.containsMatchIn(cleaned) || !numberRegex.containsMatchIn(cleaned)) {
            if (cleaned.length >= 8 && (cleaned.contains("验证码") || cleaned.contains("物流") || cleaned.contains("快递"))) {
                appendLog(
                    packageName = packageName,
                    captureSource = captureSource,
                    rawText = cleaned.take(120),
                    outcome = CaptureEventOutcome.NOISE_IGNORED,
                    detailSummary = "未命中金融动账关键词或属于验证码/物流提醒"
                )
            }
            return
        }

        val now = System.currentTimeMillis()
        recentProcessedFingerprints.entries.removeIf { now - it.value > 30_000L }
        recentTransactionNumbers.entries.removeIf { now - it.value > 180_000L }

        // 1. 若包含明确的交易单号/订单号或账单支付时间戳且 3 分钟内已成功入账过同一单，直接静默拦截后续探测波次
        val txIdentity = extractTransactionIdentity(cleaned)
        if (txIdentity != null && recentTransactionNumbers.containsKey(txIdentity)) {
            return
        }

        // 2. 20秒内完全相同的文本直接拦截
        val exactHash = "${packageName}_${cleaned.hashCode()}"
        if (recentProcessedFingerprints.containsKey(exactHash)) {
            return
        }
        recentProcessedFingerprints[exactHash] = now

        val isCompleteBillDetail = (cleaned.contains("账单详情") || cleaned.contains("交易详情")) &&
            (txIdentity != null || cleaned.contains("支付时间") || cleaned.contains("创建时间"))
        val waitWindowMs = if (isCompleteBillDetail || captureSource == "AI_OCR") 450L else 950L

        synchronized(pendingLock) {
            pendingTexts.add("[$captureSource | $packageName]: $cleaned")
            pendingPackages.add(packageName)
            pendingSources.add(captureSource)

            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(waitWindowMs)
                flushAndProcessBatch()
            }
        }
    }

    /**
     * 供 App 内“AI 识账沙盒测试台”或可观测日志“一键重放”直接调用
     */
    suspend fun processImmediateText(
        rawText: String,
        packageName: String = "com.moji.ai.sandbox",
        captureSource: String = "AI_SANDBOX"
    ) = executeAiPipeline(rawText, packageName, captureSource)

    fun clearObservabilityLogs() {
        _observabilityLogs.value = emptyList()
    }

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
    ): TransactionEntity? {
        val txNo = extractTransactionIdentity(mergedText)
        val now = System.currentTimeMillis()
        if (captureSource != "AI_SANDBOX" && txNo != null && recentTransactionNumbers.containsKey(txNo)) {
            appendLog(
                packageName = packageName,
                captureSource = captureSource,
                rawText = mergedText.take(140),
                outcome = CaptureEventOutcome.DEDUP_BLOCKED,
                detailSummary = "账单标识 ($txNo) 已入账，跳过后续重复探测波次"
            )
            return null
        }

        val wallets = repository.getAllWallets()
        val recentExpenses = repository.getRecentRefundableExpenses(15)

        val rawParsedTx = aiEngine.parseCapturedText(
            rawText = mergedText,
            sourceAppPackage = packageName,
            captureMode = captureSource,
            wallets = wallets,
            recentExpenses = recentExpenses
        )

        if (rawParsedTx == null) {
            appendLog(
                packageName = packageName,
                captureSource = captureSource,
                rawText = mergedText.take(140),
                outcome = CaptureEventOutcome.NOISE_IGNORED,
                detailSummary = "AI/语义引擎判定不包含有效金额或非真实资金变动"
            )
            return null
        }

        // 执行 When -> Then 可视化条件规则管道
        val ruleEval = smartRuleEngine?.evaluateAndApply(rawParsedTx, mergedText)
        val parsedTx: TransactionEntity
        val matchedRuleNames: String
        when (ruleEval) {
            is SmartRuleExecutionResult.Intercepted -> {
                appendLog(
                    packageName = packageName,
                    captureSource = captureSource,
                    rawText = mergedText.take(140),
                    outcome = CaptureEventOutcome.NOISE_IGNORED,
                    detailSummary = "触发条件规则【${ruleEval.matchedRule.name}】静默拦截不入账"
                )
                return null
            }
            is SmartRuleExecutionResult.Transformed -> {
                parsedTx = ruleEval.transaction
                matchedRuleNames = if (ruleEval.matchedRules.isNotEmpty()) {
                    " [规则:${ruleEval.matchedRules.joinToString("+") { it.name }}]"
                } else ""
            }
            null -> {
                parsedTx = rawParsedTx
                matchedRuleNames = ""
            }
        }

        val amountTypeFingerprint = "AMT_${parsedTx.type}_${parsedTx.amountCents}_${parsedTx.currency}"

        // 30 秒跨通道事后证据融合（Evidence Fusion Patch）：
        val prevTx = recentRecordedTransactions[amountTypeFingerprint]
        if (prevTx != null && (now - prevTx.timestamp) <= 30_000L) {
            val prevHadGenericMerchant = prevTx.merchant.isBlank() || prevTx.merchant == "日常商户"
            val newHasSpecificMerchant = parsedTx.merchant.isNotBlank() && parsedTx.merchant != "日常商户"
            val newHasExplicitCardHint = wallets.any { w ->
                !w.cardLast4.isNullOrBlank() && mergedText.contains(w.cardLast4)
            }
            val walletUpgraded = newHasExplicitCardHint &&
                parsedTx.sourceWalletId != null &&
                parsedTx.sourceWalletId != prevTx.sourceWalletId

            if (prevHadGenericMerchant && newHasSpecificMerchant || walletUpgraded) {
                val fusedTx = prevTx.copy(
                    merchant = if (prevHadGenericMerchant && newHasSpecificMerchant) parsedTx.merchant else prevTx.merchant,
                    category = if (prevHadGenericMerchant && newHasSpecificMerchant) parsedTx.category else prevTx.category,
                    sourceWalletId = if (walletUpgraded) parsedTx.sourceWalletId else prevTx.sourceWalletId,
                    captureSource = "AI_FUSED",
                    rawCapturedText = "${prevTx.rawCapturedText}\n+ [30s证据补全]: ${mergedText.take(240)}".take(600)
                )
                repository.updateExistingTransaction(fusedTx)
                recentRecordedTransactions[amountTypeFingerprint] = fusedTx
                if (txNo != null) {
                    recentTransactionNumbers[txNo] = now
                }
                val walletName = wallets.firstOrNull { it.id == fusedTx.sourceWalletId }?.name ?: "默认账户"
                appendLog(
                    packageName = packageName,
                    captureSource = "AI_FUSED",
                    rawText = mergedText.take(140),
                    outcome = CaptureEventOutcome.FUSED_PATCH,
                    detailSummary = "30s内跨通道证据融合：补全账单 #${fusedTx.id} 商户「${fusedTx.merchant}」/ 账户「$walletName」",
                    transactionId = fusedTx.id
                )
                showOverlayBannerForTransaction(fusedTx, walletName, isFused = true)
                return fusedTx
            } else if (captureSource != "AI_SANDBOX") {
                if (txNo != null) {
                    recentTransactionNumbers[txNo] = now
                }
                appendLog(
                    packageName = packageName,
                    captureSource = captureSource,
                    rawText = mergedText.take(140),
                    outcome = CaptureEventOutcome.DEDUP_BLOCKED,
                    detailSummary = "30秒内存在同金额同类型账单 (#${prevTx.id}) 且无新增商户/尾号信息，已防重拦截",
                    transactionId = prevTx.id
                )
                return null
            }
        }

        val newId = repository.recordTransaction(parsedTx)
        val savedTx = parsedTx.copy(id = newId)
        recentRecordedTransactions[amountTypeFingerprint] = savedTx
        recentProcessedFingerprints[amountTypeFingerprint] = now
        if (txNo != null) {
            recentTransactionNumbers[txNo] = now
        }

        val walletName = wallets.firstOrNull { it.id == (savedTx.sourceWalletId ?: savedTx.targetWalletId) }?.name ?: "默认账户"
        appendLog(
            packageName = packageName,
            captureSource = captureSource,
            rawText = mergedText.take(140),
            outcome = CaptureEventOutcome.RECORDED,
            detailSummary = "[${savedTx.type.displayName}] ${savedTx.category} · ${savedTx.merchant} (${savedTx.amountCents / 100.0} ${savedTx.currency}) -> $walletName$matchedRuleNames",
            transactionId = savedTx.id
        )

        // 1. 弹出免悬浮窗权限的无障碍顶部胶囊横幅（对标花有谱 & AutoAccounting 即时反馈）
        showOverlayBannerForTransaction(savedTx, walletName, isFused = false)

        // 2. 后台静默入账完成后，发送带“一键撤销”按钮的系统通知栏提醒
        val updatedWallets = repository.getAllWallets()
        notifier.sendSilentRecordedNotification(savedTx, updatedWallets)
        return savedTx
    }

    private fun showOverlayBannerForTransaction(
        tx: TransactionEntity,
        walletName: String,
        isFused: Boolean
    ) {
        val isIncome = tx.type == TransactionType.INCOME || tx.type == TransactionType.REFUND_OFFSET
        val sign = when (tx.type) {
            TransactionType.INCOME, TransactionType.REFUND_OFFSET -> "+"
            TransactionType.EXPENSE -> "-"
            else -> ""
        }
        val amtStr = String.format(Locale.US, "%.2f", tx.amountCents / 100.0)
        val prefix = if (isFused) "已补全账单" else "已自动记账"
        val title = "$prefix $sign¥$amtStr"
        val merchantPart = tx.merchant.ifBlank { tx.category }
        val subtitle = "${tx.category} · $merchantPart · $walletName"
        RecognitionBanner.show(
            title = title,
            subtitle = subtitle,
            isIncome = isIncome
        )
    }

    private fun extractTransactionIdentity(text: String): String? {
        txNumberRegex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.length >= 10 }?.let {
            return "NO_$it"
        }
        billTimeRegex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.length >= 16 }?.let {
            return "TIME_$it"
        }
        return null
    }

    private fun appendLog(
        packageName: String,
        captureSource: String,
        rawText: String,
        outcome: CaptureEventOutcome,
        detailSummary: String,
        transactionId: Long? = null
    ) {
        val entry = CaptureObservabilityLogEntry(
            id = System.nanoTime(),
            timestampMillis = System.currentTimeMillis(),
            packageName = packageName,
            captureSource = captureSource,
            rawText = rawText,
            outcome = outcome,
            detailSummary = detailSummary,
            transactionId = transactionId
        )
        _observabilityLogs.value = (listOf(entry) + _observabilityLogs.value).take(50)
    }

    companion object {
        private fun defaultDiagnosticSeedLogs(): List<CaptureObservabilityLogEntry> {
            val now = System.currentTimeMillis()
            return listOf(
                CaptureObservabilityLogEntry(
                    id = 1L,
                    timestampMillis = now - 180_000L,
                    packageName = "com.tencent.mm + cmb.pb",
                    captureSource = "AI_FUSED",
                    rawText = "[无障碍] 微信支付成功 ¥9.90 瑞幸咖啡 + [通知] 招行信用卡(3354)消费人民币9.90元",
                    outcome = CaptureEventOutcome.FUSED_PATCH,
                    detailSummary = "30s内跨通道证据融合：合并页面商户「瑞幸咖啡」与通知尾号「招商银行信用卡(3354)」"
                ),
                CaptureObservabilityLogEntry(
                    id = 2L,
                    timestampMillis = now - 420_000L,
                    packageName = "com.eg.android.AlipayGphone",
                    captureSource = "AI_NOTIFICATION",
                    rawText = "【支付宝】付款成功：向商户【盒马】付款 32.80元，付款方式：账户余额",
                    outcome = CaptureEventOutcome.RECORDED,
                    detailSummary = "[支出] 餐饮-买菜 · 盒马 (32.80 CNY) -> 支付宝"
                ),
                CaptureObservabilityLogEntry(
                    id = 3L,
                    timestampMillis = now - 600_000L,
                    packageName = "com.tencent.mm",
                    captureSource = "AI_ACCESSIBILITY",
                    rawText = "微信支付 支付成功 ¥32.80 盒马鲜生",
                    outcome = CaptureEventOutcome.DEDUP_BLOCKED,
                    detailSummary = "30秒内存在同金额同类型账单且无新增尾号信息，已防重拦截"
                ),
                CaptureObservabilityLogEntry(
                    id = 4L,
                    timestampMillis = now - 900_000L,
                    packageName = "com.android.mms",
                    captureSource = "AI_NOTIFICATION",
                    rawText = "【顺丰速运】您的快件已到达蜂巢智能柜，取件码 882910",
                    outcome = CaptureEventOutcome.NOISE_IGNORED,
                    detailSummary = "未命中金融动账关键词或属于验证码/物流提醒"
                )
            )
        }
    }
}
