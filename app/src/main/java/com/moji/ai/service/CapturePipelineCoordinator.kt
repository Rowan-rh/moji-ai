package com.moji.ai.service

import com.moji.ai.ai.AiAccountingEngine
import com.moji.ai.ai.AiParseTraceCollector
import com.moji.ai.ai.SmartRuleEngine
import com.moji.ai.ai.SmartRuleExecutionResult
import com.moji.ai.data.backup.MoJiCloudSyncClient
import com.moji.ai.data.local.CaptureEvidenceEntity
import java.io.File
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CategoryCatalog
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.DedicatedFundManager
import com.moji.ai.data.model.TagCatalog
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.repository.CompositeMedicalFusionResult
import com.moji.ai.data.repository.CompositeMedicalRecordResult
import com.moji.ai.data.repository.LedgerRepository
import com.moji.ai.notification.SilentBookkeepingNotifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToLong

@Serializable
enum class CaptureEventOutcome(val badgeLabel: String) {
    RECORDED("已静默入账"),
    DUPLICATE_SUSPECTED("疑似重复·未录入"),
    FUSED_PATCH("证据融合补全"),
    DEDUP_BLOCKED("防重静默拦截"),
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

internal interface PipelinePersistenceGateway {
    suspend fun clearAllTransactionsAndEvidences()
    suspend fun getAllTransactions(): List<TransactionEntity>
    suspend fun getAllWallets(): List<WalletEntity>
    suspend fun getRecentRefundableExpenses(limit: Int = 15): List<TransactionEntity>
    suspend fun getTransactionById(id: Long): TransactionEntity?
    suspend fun findTransactionByOrderKey(orderKey: String): TransactionEntity?
    suspend fun findTransactionByNo(txNo: String): TransactionEntity?
    suspend fun findTransactionByMerchantOrderNo(orderNo: String): TransactionEntity?
    suspend fun findTransactionsByAmountAndOccurredWindow(
        amountCents: Long,
        occurredAtMillis: Long,
        toleranceMillis: Long
    ): List<TransactionEntity>
    suspend fun findTransactionsByAmountAndTimeOrCapturedWindow(
        amountCents: Long,
        occurredAtMillis: Long,
        capturedAtMillis: Long,
        toleranceMillis: Long
    ): List<TransactionEntity>
    suspend fun recordTransaction(tx: TransactionEntity): Long
    suspend fun recordCompositeMedicalTransaction(
        mainTx: TransactionEntity,
        poolSubsidyCents: Long,
        personalAccountCents: Long,
        unspecifiedMedicalCents: Long,
        commercialDiscountCents: Long
    ): CompositeMedicalRecordResult
    suspend fun enrichCompositeMedicalOnExistingTransaction(
        existingMainId: Long,
        enrichedCandidateMain: TransactionEntity,
        baseEnrichedFields: List<String>,
        poolSubsidyCents: Long,
        personalAccountCents: Long,
        unspecifiedMedicalCents: Long,
        commercialDiscountCents: Long
    ): CompositeMedicalFusionResult
    suspend fun updateExistingTransaction(updatedTx: TransactionEntity): Boolean
    suspend fun undoAndDeleteTransaction(id: Long): Boolean
    suspend fun attachBillScreenshotIfNeeded(
        transactionId: Long,
        screenshotPath: String,
        screenshotSha256: String,
        nowMillis: Long
    ): Boolean
    suspend fun insertCaptureEvidence(evidence: CaptureEvidenceEntity): Long
    suspend fun getEvidencesForTransaction(txId: Long): List<CaptureEvidenceEntity>
}

internal interface PipelineUiGateway {
    fun cancelNotificationForTransaction(txId: Long)
    fun sendSilentRecordedNotification(tx: TransactionEntity, wallets: List<WalletEntity>)
    fun sendSuspectedDuplicateNotification(
        candidateId: Long,
        tx: TransactionEntity,
        wallets: List<WalletEntity>,
        duplicateReason: String,
        matchedTransactionId: Long?
    )
    fun showRecordedBanner(
        statusTitle: String,
        amountText: String,
        subtitle: String,
        isIncome: Boolean,
        transactionId: Long
    )
    fun showSuspectedDuplicateBanner(
        amountText: String,
        subtitle: String,
        walletName: String,
        matchedTransactionId: Long?,
        onForceRecord: () -> Unit
    )
}

private class RepositoryPersistenceGateway(
    private val repository: LedgerRepository
) : PipelinePersistenceGateway {
    override suspend fun clearAllTransactionsAndEvidences() {
        repository.clearAllTransactionsAndEvidences()
    }
    override suspend fun getAllTransactions(): List<TransactionEntity> = repository.getAllTransactions()
    override suspend fun getAllWallets(): List<WalletEntity> = repository.getAllWallets()
    override suspend fun getRecentRefundableExpenses(limit: Int): List<TransactionEntity> =
        repository.getRecentRefundableExpenses(limit)
    override suspend fun getTransactionById(id: Long): TransactionEntity? = repository.getTransactionById(id)
    override suspend fun findTransactionByOrderKey(orderKey: String): TransactionEntity? =
        repository.findTransactionByOrderKey(orderKey)
    override suspend fun findTransactionByNo(txNo: String): TransactionEntity? = repository.findTransactionByNo(txNo)
    override suspend fun findTransactionByMerchantOrderNo(orderNo: String): TransactionEntity? =
        repository.findTransactionByMerchantOrderNo(orderNo)
    override suspend fun findTransactionsByAmountAndOccurredWindow(
        amountCents: Long,
        occurredAtMillis: Long,
        toleranceMillis: Long
    ): List<TransactionEntity> =
        repository.findTransactionsByAmountAndOccurredWindow(amountCents, occurredAtMillis, toleranceMillis)
    override suspend fun findTransactionsByAmountAndTimeOrCapturedWindow(
        amountCents: Long,
        occurredAtMillis: Long,
        capturedAtMillis: Long,
        toleranceMillis: Long
    ): List<TransactionEntity> = repository.findTransactionsByAmountAndTimeOrCapturedWindow(
        amountCents = amountCents,
        occurredAtMillis = occurredAtMillis,
        capturedAtMillis = capturedAtMillis,
        toleranceMillis = toleranceMillis
    )
    override suspend fun recordTransaction(tx: TransactionEntity): Long = repository.recordTransaction(tx)
    override suspend fun recordCompositeMedicalTransaction(
        mainTx: TransactionEntity,
        poolSubsidyCents: Long,
        personalAccountCents: Long,
        unspecifiedMedicalCents: Long,
        commercialDiscountCents: Long
    ): CompositeMedicalRecordResult = repository.recordCompositeMedicalTransaction(
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
    ): CompositeMedicalFusionResult = repository.enrichCompositeMedicalOnExistingTransaction(
        existingMainId = existingMainId,
        enrichedCandidateMain = enrichedCandidateMain,
        baseEnrichedFields = baseEnrichedFields,
        poolSubsidyCents = poolSubsidyCents,
        personalAccountCents = personalAccountCents,
        unspecifiedMedicalCents = unspecifiedMedicalCents,
        commercialDiscountCents = commercialDiscountCents
    )
    override suspend fun updateExistingTransaction(updatedTx: TransactionEntity): Boolean =
        repository.updateExistingTransaction(updatedTx)
    override suspend fun undoAndDeleteTransaction(id: Long): Boolean = repository.undoAndDeleteTransaction(id)
    override suspend fun attachBillScreenshotIfNeeded(
        transactionId: Long,
        screenshotPath: String,
        screenshotSha256: String,
        nowMillis: Long
    ): Boolean = repository.attachBillScreenshotIfNeeded(transactionId, screenshotPath, screenshotSha256, nowMillis)
    override suspend fun insertCaptureEvidence(evidence: CaptureEvidenceEntity): Long =
        repository.insertCaptureEvidence(evidence)
    override suspend fun getEvidencesForTransaction(txId: Long): List<CaptureEvidenceEntity> =
        repository.getEvidencesForTransaction(txId)
}

private class DefaultPipelineUiGateway(
    private val notifier: SilentBookkeepingNotifier
) : PipelineUiGateway {
    override fun cancelNotificationForTransaction(txId: Long) {
        notifier.cancelForTransaction(txId)
    }

    override fun sendSilentRecordedNotification(tx: TransactionEntity, wallets: List<WalletEntity>) {
        notifier.sendSilentRecordedNotification(tx, wallets)
    }

    override fun sendSuspectedDuplicateNotification(
        candidateId: Long,
        tx: TransactionEntity,
        wallets: List<WalletEntity>,
        duplicateReason: String,
        matchedTransactionId: Long?
    ) {
        notifier.sendSuspectedDuplicateNotification(
            candidateId = candidateId,
            tx = tx,
            wallets = wallets,
            duplicateReason = duplicateReason,
            matchedTransactionId = matchedTransactionId
        )
    }

    override fun showRecordedBanner(
        statusTitle: String,
        amountText: String,
        subtitle: String,
        isIncome: Boolean,
        transactionId: Long
    ) {
        RecognitionBanner.showRecorded(
            statusTitle = statusTitle,
            amountText = amountText,
            subtitle = subtitle,
            isIncome = isIncome,
            transactionId = transactionId
        )
    }

    override fun showSuspectedDuplicateBanner(
        amountText: String,
        subtitle: String,
        walletName: String,
        matchedTransactionId: Long?,
        onForceRecord: () -> Unit
    ) {
        RecognitionBanner.showSuspectedDuplicate(
            amountText = amountText,
            subtitle = subtitle,
            walletName = walletName,
            matchedTransactionId = matchedTransactionId,
            onForceRecord = onForceRecord
        )
    }
}

/**
 * 智能防重、三通道滑动窗口聚合与双规则重复判定协调器：
 *
 * 核心留存四要素：
 * 1) 订单唯一 Key (`orderKey`)：从交易单号 / 订单号 / 商户单号提取；
 * 2) 实际购物发生时间 (`timestamp`)：从账单「支付时间/创建时间」提取（专用收银台即时完成页以 `AI_LIVE_CASHIER` 标记暂用采集时刻，支持二阶段详情原位校准）；
 * 3) 金额 (`amountCents`)；
 * 4) 购物数量 (`itemQuantity` / `quantityDesc`)。
 *
 * 严格执行两条重复判定规则：
 * - 规则 1：如果订单唯一 Key (`orderKey` / `transactionNo` / `merchantOrderNo`) 重复，100% 判定为重复；
 * - 规则 2：如果没有订单 Key（新账单或已有账单任一方无 `orderKey`），但「实际交易发生时间 + 金额」一模一样，100% 判定为重复。
 *
 * 判定为重复时的处理策略：
 * - 默认绝不录入数据库；
 * - 若已有账单缺少订单 Key 而新采集到了订单 Key（如用户在支付宝点开“更多”或二阶段进入账单/订单详情），静默为原账单补全 `orderKey` 与精确支付时间；
 * - 若同时存在 ≥2 笔同金额未绑单号的 `AI_LIVE_CASHIER` 候选导致归属歧义，禁止盲目吞并，走疑似重复弹窗路径交用户确认；
 * - 在一加流体云胶囊弹窗提示「检测到疑似重复」，默认不录入；仅当用户主动点击弹窗上的「录入」按钮时才写入数据库。
 */
class CapturePipelineCoordinator internal constructor(
    private val repository: PipelinePersistenceGateway,
    private val aiEngine: AiAccountingEngine,
    private val uiGateway: PipelineUiGateway,
    private val smartRuleEngine: SmartRuleEngine? = null,
    private val debugTraceStore: CaptureDebugTraceManager? = CaptureDebugTraceStore.managerOrNull()
) {
    constructor(
        repository: LedgerRepository,
        aiEngine: AiAccountingEngine,
        notifier: SilentBookkeepingNotifier,
        smartRuleEngine: SmartRuleEngine? = null,
        debugTraceStore: CaptureDebugTraceManager? = CaptureDebugTraceStore.managerOrNull()
    ) : this(
        repository = RepositoryPersistenceGateway(repository),
        aiEngine = aiEngine,
        uiGateway = DefaultPipelineUiGateway(notifier),
        smartRuleEngine = smartRuleEngine,
        debugTraceStore = debugTraceStore
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val recentProcessedFingerprints = ConcurrentHashMap<String, Long>()
    private val recentDuplicatePromptCooldown = ConcurrentHashMap<String, Long>()
    private val recentRecordedTransactions = ConcurrentHashMap<String, TransactionEntity>()
    private val pendingDuplicateCandidates = ConcurrentHashMap<Long, TransactionEntity>()
    private val candidateIdGenerator = AtomicLong(50_000L)

    private val pendingLock = Any()
    private val pipelineMutex = Mutex()
    private var pendingTexts = mutableListOf<String>()
    private var pendingPackages = mutableSetOf<String>()
    private var pendingSources = mutableSetOf<String>()
    private var pendingSourceLabels = mutableListOf<String>()
    private var pendingUpstreamContext: UpstreamWindowCaptureContext? = null
    private var pendingScreenshotPath: String = ""
    private var pendingScreenshotSha256: String = ""
    private var pendingCapturedAtMillis: Long = 0L
    private var debounceJob: Job? = null

    private data class DrainedCaptureBatch(
        val mergedText: String,
        val primaryPackage: String,
        val mergedSource: String,
        val batchSize: Int,
        val mergedSourceLabels: List<String>,
        val upstreamContext: UpstreamWindowCaptureContext?,
        val screenshotPath: String,
        val screenshotSha256: String,
        val capturedAtMillis: Long
    )

    private val _observabilityLogs = MutableStateFlow(defaultDiagnosticSeedLogs())
    val observabilityLogs: StateFlow<List<CaptureObservabilityLogEntry>> = _observabilityLogs.asStateFlow()

    private val financialKeywordRegex = Regex(
        """(支付成功|付款成功|交易成功|账单详情|交易详情|当前状态|交易状态|交易单号|商户单号|转账单号|订单号|订单编号|支付方式|付款方式|实付|下单时间|医保支付|医保统筹|医保个账|统筹支付|统筹基金|个账支付|历年账户|家庭共济|医保|对方已收钱|已存入零钱|已收钱|消费撤销|交易冲正|冲正|消费|扣款|支出|转出|转账|收款|到账|还款|退货|已退款|已退单|退款|退回|零钱|余额宝|花呗|白条|信用卡|储蓄卡|个人消费卡|尾号|¥|￥|RMB|USD|HKD|\$)""",
        RegexOption.IGNORE_CASE
    )
    private val numberRegex = Regex("""[0-9]+(\.[0-9]{1,2})?""")
    private val txNumberRegex = Regex(
        """(?:交易单号|订单号|转账单号|商户单号|商家订单号|流水号)\s*(?:\||[:：])?\s*([0-9A-Za-z_-]{10,44})"""
    )

    suspend fun clearAllTransactionsAndResetState(context: android.content.Context) {
        synchronized(pendingLock) {
            debounceJob?.cancel()
            debounceJob = null
            BillScreenshotStorage.deleteIfExists(pendingScreenshotPath)
            pendingTexts.clear()
            pendingPackages.clear()
            pendingSources.clear()
            pendingSourceLabels.clear()
            pendingUpstreamContext = null
            pendingScreenshotPath = ""
            pendingScreenshotSha256 = ""
            pendingCapturedAtMillis = 0L
        }
        pipelineMutex.withLock {
            repository.clearAllTransactionsAndEvidences()
            recentProcessedFingerprints.clear()
            recentDuplicatePromptCooldown.clear()
            recentRecordedTransactions.clear()
            pendingDuplicateCandidates.clear()
            PaymentAccessibilityService.instance?.resetDispatchedState()
            _observabilityLogs.value = emptyList()
            runCatching {
                val dir = java.io.File(context.filesDir, "bill_screenshots")
                dir.listFiles()?.forEach { f ->
                    if (BillScreenshotStorage.isSafeScreenshotFile(f, context)) {
                        f.delete()
                    }
                }
            }
        }
    }

    /**
     * 启动时自动清理并修复：
     * 1. 支付成功页立减动画前后的重复记录；
     * 2. 跨应用同一笔订单的重复记录（如拼多多/京东订单详情页 + 支付宝/微信账单详情页分别扫了一次同一笔 9.60 元订单），自动将双方信息融合为一笔并退回重复扣减的余额；
     * 3. 补全曾被异步截图覆盖的订单号与下单时间，并用最新分类权重校准历史电商账单分类。
     */
    suspend fun repairLegacyEcommerceRecordsIfNeeded() {
        pipelineMutex.withLock {
        runCatching {
            val allTxs = repository.getAllTransactions()
            val wallets = repository.getAllWallets()
            val recentExpenses = repository.getRecentRefundableExpenses(15)

            // 1. 清理：
            // (a) 支付成功页（如支付宝碰一下立减）第一帧先录了原价、几秒后立减动画完成又录了实扣价产生的重复原价记录；
            // (b) 没有任何明确支付/交易时间且无订单号的裸完成页脏记录（仅限无障碍/OCR 页面采集，排除通知栏与沙盒测试通道）。
            for (tx in allTxs) {
                if (tx.captureSource in setOf("MANUAL", "RECURRING", "BALANCE_ANCHOR", "CSV_IMPORT") ||
                    DedicatedFundManager.isCompositeMedicalPersonalChild(tx) ||
                    DedicatedFundManager.isCompositeMedicalMain(tx)
                ) {
                    continue
                }
                if (tx.discountCents == 0L && tx.orderKey.isBlank()) {
                    val hasPostDiscountSibling = allTxs.any { other ->
                        other.id != tx.id &&
                            other.type == tx.type &&
                            other.currency == tx.currency &&
                            other.merchant == tx.merchant &&
                            other.discountCents > 0L &&
                            other.amountCents + other.discountCents == tx.amountCents &&
                            abs(other.timestamp - tx.timestamp) <= 15_000L
                    }
                    if (hasPostDiscountSibling) {
                        if (repository.undoAndDeleteTransaction(tx.id)) {
                            uiGateway.cancelNotificationForTransaction(tx.id)
                        }
                        continue
                    }
                }
                if (tx.captureSource !in setOf("AI_NOTIFICATION", "AI_SANDBOX", "AI_LIVE_CASHIER") &&
                    tx.orderKey.isBlank() && tx.transactionNo.isBlank() && tx.merchantOrderNo.isBlank()
                ) {
                    val evidences = repository.getEvidencesForTransaction(tx.id)
                    val fullSourceText = (evidences.map { it.rawText } + listOf(tx.rawCapturedText))
                        .filter { it.isNotBlank() }
                        .joinToString(" | ")
                    val isExemptedLiveOrNotifEvidence = fullSourceText.contains("[AI_NOTIFICATION") ||
                        fullSourceText.contains("[AI_SANDBOX") ||
                        fullSourceText.contains("[AI_LIVE_CASHIER") ||
                        evidences.any {
                            it.captureSource == "AI_NOTIFICATION" ||
                                it.captureSource == "AI_SANDBOX" ||
                                it.captureSource == "AI_LIVE_CASHIER"
                        }
                    val refMillis = tx.capturedAtMillis.takeIf { it > 0L } ?: tx.timestamp
                    if (!isExemptedLiveOrNotifEvidence && !AiAccountingEngine.hasExplicitOccurredTime(fullSourceText, refMillis)) {
                        if (repository.undoAndDeleteTransaction(tx.id)) {
                            uiGateway.cancelNotificationForTransaction(tx.id)
                        }
                        continue
                    }
                }
            }

            // 2. 先从 capture_evidences 恢复曾因异步截图覆盖或首帧无时间而缺失的精确支付时间/备注/订单号，并重算旧版拼多多/京东异常记录
            val afterStep1 = repository.getAllTransactions()
            for (tx in afterStep1) {
                if (tx.captureSource in setOf("MANUAL", "RECURRING", "BALANCE_ANCHOR", "CSV_IMPORT") ||
                    DedicatedFundManager.isCompositeMedicalPersonalChild(tx) ||
                    DedicatedFundManager.isCompositeMedicalMain(tx)
                ) {
                    continue
                }
                val evidences = repository.getEvidencesForTransaction(tx.id)
                val fullSourceText = (evidences.map { it.rawText } + listOf(tx.rawCapturedText))
                    .filter { it.isNotBlank() }
                    .joinToString(" | ")
                val refMillis = tx.capturedAtMillis.takeIf { it > 0L } ?: tx.timestamp
                val explicitTimeFromEvidence = AiAccountingEngine.extractOccurredTimeMillis(fullSourceText, refMillis)
                if (explicitTimeFromEvidence != null &&
                    (tx.captureSource == "AI_LIVE_CASHIER" || tx.timestamp % 1_000L != 0L || tx.note == tx.merchant)
                ) {
                    val pkgHintForEvidence = when {
                        fullSourceText.contains("com.tencent.mm") -> "com.tencent.mm"
                        fullSourceText.contains("com.eg.android.AlipayGphone") -> "com.eg.android.AlipayGphone"
                        else -> ""
                    }
                    val reparsedFromEv = aiEngine.localSmartFallbackParse(
                        rawText = fullSourceText,
                        sourceAppPackage = pkgHintForEvidence,
                        wallets = wallets,
                        recentExpenses = recentExpenses,
                        referenceMillis = refMillis
                    )
                    val upgradedNote = if (tx.note == tx.merchant &&
                        !reparsedFromEv?.note.isNullOrBlank() &&
                        reparsedFromEv?.note != tx.merchant
                    ) {
                        reparsedFromEv!!.note
                    } else {
                        tx.note
                    }
                    if (tx.timestamp != explicitTimeFromEvidence || upgradedNote != tx.note) {
                        repository.updateExistingTransaction(
                            tx.copy(
                                timestamp = explicitTimeFromEvidence,
                                note = upgradedNote,
                                updatedAtMillis = System.currentTimeMillis()
                            )
                        )
                    }
                }

                val isPddTx = fullSourceText.contains("com.xunmeng.pinduoduo") || tx.orderKey.matches(Regex("""^\d{6}-\d{8,}$"""))
                val isJdTx = fullSourceText.contains("com.jingdong.app.mall") || tx.category == "购物-京东"
                if (tx.paymentMethodRaw.contains("发票类型")) {
                    val fallbackCleanedPm = tx.paymentMethodRaw
                        .replace(Regex("""\s*发票类型.*"""), "")
                        .trim()
                    val cleanBankPm = evidences.map { it.paymentMethodRaw }
                        .firstOrNull { it.isNotBlank() && !it.contains("发票类型") && it !in setOf("微信支付", "支付宝", "在线支付") }
                        ?: fallbackCleanedPm
                    val cleanedNote = tx.note
                        .replace("微信支付 发票类型 不开发票", cleanBankPm)
                        .replace(Regex("""\s*发票类型\s*不开发票"""), "")
                        .trim()
                    val bankWalletId = if (cleanBankPm.isNotBlank()) {
                        wallets.firstOrNull { !it.cardLast4.isNullOrBlank() && cleanBankPm.contains(it.cardLast4) }?.id
                            ?: tx.sourceWalletId
                    } else {
                        tx.sourceWalletId
                    }
                    repository.updateExistingTransaction(
                        tx.copy(
                            paymentMethodRaw = cleanBankPm,
                            sourceWalletId = bankWalletId,
                            note = cleanedNote,
                            updatedAtMillis = System.currentTimeMillis()
                        )
                    )
                    continue
                }
                val needsReparse = tx.orderKey.isBlank() ||
                    (isPddTx && (
                        tx.note.contains("狂降，火热进行中") ||
                            tx.merchant in setOf("门卫代收", "先用后付", "直播中", "退货包运费", "调节快充支架")
                        )) ||
                    (isJdTx && (
                        tx.note.contains("回头") ||
                            (tx.discountCents == 0L && (fullSourceText.contains("共减") || fullSourceText.contains("到手")))
                        ))

                if (needsReparse) {
                    val pkgHint = when {
                        isPddTx -> "com.xunmeng.pinduoduo"
                        isJdTx -> "com.jingdong.app.mall"
                        fullSourceText.contains("com.eg.android.AlipayGphone") -> "com.eg.android.AlipayGphone"
                        fullSourceText.contains("com.tencent.mm") -> "com.tencent.mm"
                        else -> ""
                    }
                    val isLiveCashierTx = tx.captureSource == "AI_LIVE_CASHIER" ||
                        fullSourceText.contains("[AI_LIVE_CASHIER")
                    val reparsed = aiEngine.localSmartFallbackParse(
                        rawText = fullSourceText,
                        sourceAppPackage = pkgHint,
                        wallets = wallets,
                        recentExpenses = recentExpenses,
                        referenceMillis = tx.capturedAtMillis.takeIf { it > 0L } ?: tx.timestamp,
                        captureMode = if (isLiveCashierTx) "AI_LIVE_CASHIER" else ""
                    )
                    if (reparsed != null) {
                        val newAmountCents = (reparsed.amount * 100.0).roundToLong()
                        if (newAmountCents > 0L) {
                            val resolvedKey = reparsed.orderKey
                                .ifBlank { reparsed.transactionNo }
                                .ifBlank { evidences.firstNotNullOfOrNull { it.transactionNo.takeIf { s -> s.isNotBlank() } }.orEmpty() }
                                .ifBlank { tx.orderKey }
                            val repairedTx = tx.copy(
                                amountCents = if (isPddTx || isJdTx) newAmountCents else tx.amountCents,
                                amountCnyCents = if (isPddTx || isJdTx) newAmountCents else tx.amountCnyCents,
                                discountCents = maxOf(tx.discountCents, reparsed.discountCents),
                                category = if (tx.category in setOf("餐饮", "交通-火车", "购物-拼多多", "购物-京东") && reparsed.category != "餐饮") reparsed.category else tx.category,
                                merchant = if (tx.merchant in setOf("门卫代收", "先用后付", "直播中", "退货包运费", "调节快充支架", "拼多多", "京东")) reparsed.merchant else tx.merchant,
                                note = if (tx.note.contains("狂降，火热进行中") || tx.note.contains("回头") || ((isPddTx || isJdTx) && reparsed.note.isNotBlank())) reparsed.note else tx.note,
                                timestamp = reparsed.occurredAtMillis ?: tx.timestamp,
                                orderKey = resolvedKey,
                                transactionNo = if (isJdTx && tx.transactionNo.endsWith("1") && resolvedKey.length == 16) resolvedKey else tx.transactionNo.ifBlank { reparsed.transactionNo.ifBlank { resolvedKey } },
                                merchantOrderNo = tx.merchantOrderNo.ifBlank { reparsed.merchantOrderNo },
                                paymentMethodRaw = reparsed.paymentMethodRaw.takeIf { it.isNotBlank() && (it != "先用后付" || !tx.paymentMethodRaw.contains("支付宝")) }
                                    ?: tx.paymentMethodRaw,
                                updatedAtMillis = System.currentTimeMillis()
                            )
                            repository.updateExistingTransaction(repairedTx)
                            continue
                        }
                    }
                }

                // 校准历史电商支出与退款分类（如充气宝误归类为交通-火车、跑鞋归为购物-拼多多、一次性马桶垫/垃圾袋归为餐饮、破窗器归为购物-拼多多）
                if ((tx.type == com.moji.ai.data.model.TransactionType.EXPENSE ||
                        tx.type == com.moji.ai.data.model.TransactionType.REFUND_OFFSET) &&
                    tx.category in setOf("交通-火车", "餐饮", "购物-拼多多", "购物-京东", "购物-淘宝")
                ) {
                    val (reclassifiedCat, conf) = com.moji.ai.ai.WeightedCategoryClassifier.classifyExpense(
                        rawText = "${tx.merchant} ${tx.note}",
                        merchant = tx.merchant
                    )
                    val shouldUpdateCat = when {
                        tx.category == "交通-火车" && reclassifiedCat != "交通-火车" -> true
                        tx.category == "餐饮" && reclassifiedCat != "餐饮" -> true
                        tx.category.startsWith("购物-") && conf >= 0.85f && reclassifiedCat != "餐饮" -> true
                        else -> false
                    }
                    if (shouldUpdateCat && reclassifiedCat != tx.category) {
                        repository.updateExistingTransaction(
                            tx.copy(category = reclassifiedCat, updatedAtMillis = System.currentTimeMillis())
                        )
                    }
                }
            }

            // 3. 自动扫描并合并：
            // (a) 「电商订单详情（拼多多/京东/淘宝/抖音） ↔ 支付账单详情（支付宝/微信）」产生的跨端同笔订单重复记录；
            // (b) 「(支付时间 OR 登记时间) + 相同金额」命中的重复自动入账记录。
            val currentTxs = repository.getAllTransactions().sortedBy { it.id }
            val deletedIds = mutableSetOf<Long>()
            for (i in currentTxs.indices) {
                val primary = currentTxs[i]
                if (primary.id in deletedIds) continue
                if (primary.captureSource in setOf("MANUAL", "RECURRING", "BALANCE_ANCHOR", "CSV_IMPORT") ||
                    DedicatedFundManager.isCompositeMedicalPersonalChild(primary)
                ) continue
                for (j in (i + 1) until currentTxs.size) {
                    val secondary = currentTxs[j]
                    if (secondary.id in deletedIds) continue
                    if (secondary.captureSource in setOf("MANUAL", "RECURRING", "BALANCE_ANCHOR", "CSV_IMPORT") ||
                        DedicatedFundManager.isCompositeMedicalPersonalChild(secondary)
                    ) continue
                    val priKey = primary.orderKey.ifBlank { primary.transactionNo }.ifBlank { primary.merchantOrderNo }
                    val secKey = secondary.orderKey.ifBlank { secondary.transactionNo }.ifBlank { secondary.merchantOrderNo }
                    val priIsLiveCashier = isUnnumberedLiveCashierOrigin(primary)
                    val secIsLiveCashier = isUnnumberedLiveCashierOrigin(secondary)
                    val priVisit = extractCashierVisitTag(primary.rawCapturedText)
                    val secVisit = extractCashierVisitTag(secondary.rawCapturedText)
                    // 保护独立收银台会话产生的连续同金额支付记录，以及存在 ≥2 笔同金额无单号收银台记录的歧义场景
                    if (priIsLiveCashier && secIsLiveCashier) {
                        if (priVisit.isNotBlank() && secVisit.isNotBlank() && priVisit != secVisit) {
                            continue
                        }
                        continue
                    }
                    if (priIsLiveCashier xor secIsLiveCashier) {
                        val ambiguousCount = currentTxs.count { cand ->
                            cand.id !in deletedIds &&
                                isUnnumberedLiveCashierOrigin(cand) &&
                                cand.type == primary.type &&
                                cand.currency == primary.currency &&
                                cand.amountCents == primary.amountCents &&
                                abs(cand.timestamp - (if (priIsLiveCashier) secondary.timestamp else primary.timestamp)) <= 180_000L
                        }
                        if (ambiguousCount >= 2) {
                            continue
                        }
                    }
                    val hasConflictingSameChannelKey = priKey.isNotBlank() &&
                        secKey.isNotBlank() &&
                        priKey != secKey &&
                        !isCrossChannelSameOrderPair(primary, secondary) &&
                        !hasCrossEmbeddedOrderNumber(primary, secondary, secKey, secondary.rawCapturedText)
                    val isPaymentTimeClose = abs(primary.timestamp - secondary.timestamp) <= 65_000L ||
                        (primary.timestamp / 60_000L == secondary.timestamp / 60_000L)
                    val isCapturedTimeClose = primary.capturedAtMillis > 0L && secondary.capturedAtMillis > 0L &&
                        abs(primary.timestamp - secondary.timestamp) <= 15 * 60_000L && (
                            abs(primary.capturedAtMillis - secondary.capturedAtMillis) <= 65_000L ||
                                (primary.capturedAtMillis / 60_000L == secondary.capturedAtMillis / 60_000L)
                            )
                    val isSamePaymentOrCapturedTimeAndAmount = primary.type == secondary.type &&
                        primary.currency == secondary.currency &&
                        primary.amountCents == secondary.amountCents &&
                        !hasConflictingSameChannelKey &&
                        (isPaymentTimeClose || isCapturedTimeClose)
                    if (isSamePaymentOrCapturedTimeAndAmount || isCrossChannelSameOrderPair(primary, secondary)) {
                        val incomingResolvedKey = secondary.orderKey
                            .ifBlank { secondary.transactionNo }
                            .ifBlank { secondary.merchantOrderNo }
                        val fused = buildEnrichedTransactionIfHasNewDetails(
                            existing = primary,
                            incoming = secondary,
                            resolvedOrderKey = incomingResolvedKey,
                            screenshotPath = secondary.screenshotPath,
                            screenshotSha256 = secondary.screenshotSha256,
                            capturedAtMillis = maxOf(primary.capturedAtMillis, secondary.capturedAtMillis)
                        )?.first ?: primary

                        repository.updateExistingTransaction(fused)
                        val secEvidences = repository.getEvidencesForTransaction(secondary.id)
                        for (ev in secEvidences) {
                            repository.insertCaptureEvidence(ev.copy(transactionId = primary.id))
                        }
                        if (repository.undoAndDeleteTransaction(secondary.id)) {
                            uiGateway.cancelNotificationForTransaction(secondary.id)
                        }
                        deletedIds.add(secondary.id)
                    }
                }
            }

            // 4. 清理旧版宽松退款匹配误挂到无关消费（如将 10 月小米/安踏退款挂到 11 月 5000 元转账账单）的错误冲销关联
            val afterMergeTxs = repository.getAllTransactions()
            val txById = afterMergeTxs.associateBy { it.id }
            for (tx in afterMergeTxs) {
                if (tx.type == TransactionType.REFUND_OFFSET && tx.relatedTransactionId != null) {
                    val parent = txById[tx.relatedTransactionId]
                    val isGenuineMatch = parent != null && (
                        (tx.orderKey.isNotBlank() && tx.orderKey == parent.orderKey) ||
                            (parent.amountCnyCents == tx.amountCnyCents &&
                                parent.timestamp <= tx.timestamp + 86_400_000L &&
                                (parent.merchant.contains(tx.merchant, ignoreCase = true) ||
                                    tx.merchant.contains(parent.merchant, ignoreCase = true)))
                        )
                    if (!isGenuineMatch) {
                        repository.updateExistingTransaction(
                            tx.copy(
                                relatedTransactionId = null,
                                updatedAtMillis = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
        }
        }
    }

    fun onRawFinancialEventCaptured(
        rawText: String,
        packageName: String,
        captureSource: String,
        screenshotPath: String = "",
        screenshotSha256: String = "",
        capturedAtMillis: Long = System.currentTimeMillis(),
        cashierVisitId: Long = 0L,
        verifiedActivity: String = "",
        upstreamContext: UpstreamWindowCaptureContext? = null
    ) {
        val cleaned = rawText.trim()
        if (cleaned.length < 4) {
            BillScreenshotStorage.deleteIfExists(screenshotPath)
            return
        }
        if (AiAccountingEngine.isNonTransactionNotificationOrPromo(cleaned, packageName, captureSource)) {
            BillScreenshotStorage.deleteIfExists(screenshotPath)
            appendLog(
                packageName = packageName,
                captureSource = captureSource,
                rawText = cleaned.take(120),
                outcome = CaptureEventOutcome.NOISE_IGNORED,
                detailSummary = "命中月度汇总账单或群发营销广告过滤规则"
            )
            recordPreFilterNoiseTrace(
                rawText = cleaned,
                packageName = packageName,
                captureSource = captureSource,
                capturedAtMillis = capturedAtMillis,
                cashierVisitId = cashierVisitId,
                verifiedActivity = verifiedActivity,
                upstreamContext = upstreamContext,
                ruleCode = "PROMO_OR_MONTHLY_STATEMENT_FILTER",
                reasonSummary = "命中月度汇总账单或群发营销广告过滤规则"
            )
            return
        }
        if (!financialKeywordRegex.containsMatchIn(cleaned) || !numberRegex.containsMatchIn(cleaned)) {
            BillScreenshotStorage.deleteIfExists(screenshotPath)
            if (cleaned.length >= 8 && (cleaned.contains("验证码") || cleaned.contains("物流") || cleaned.contains("快递"))) {
                appendLog(
                    packageName = packageName,
                    captureSource = captureSource,
                    rawText = cleaned.take(120),
                    outcome = CaptureEventOutcome.NOISE_IGNORED,
                    detailSummary = "未命中金融动账关键词或属于验证码/物流提醒"
                )
            }
            if (cleaned.length >= 6) {
                recordPreFilterNoiseTrace(
                    rawText = cleaned,
                    packageName = packageName,
                    captureSource = captureSource,
                    capturedAtMillis = capturedAtMillis,
                    cashierVisitId = cashierVisitId,
                    verifiedActivity = verifiedActivity,
                    upstreamContext = upstreamContext,
                    ruleCode = "MISSING_FINANCIAL_KEYWORD_OR_AMOUNT",
                    reasonSummary = "未命中金融动账关键词或数字金额特征（如验证码/物流/普通文本）"
                )
            }
            return
        }

        val now = System.currentTimeMillis()
        recentProcessedFingerprints.entries.removeIf { now - it.value > 25_000L }
        recentDuplicatePromptCooldown.entries.removeIf { now - it.value > 15_000L }
        pendingDuplicateCandidates.entries.removeIf { now - it.value.capturedAtMillis > 600_000L }

        // 同一页面 15 秒内完全一致的节点快照不重复进入管道；新收银台 Visit 携带独立 visitId 确保 15s 内连续两笔同金额支付不被静默丢弃
        val exactHash = if (cashierVisitId > 0L) {
            "${packageName}_v${cashierVisitId}_${cleaned.hashCode()}"
        } else {
            "${packageName}_${cleaned.hashCode()}"
        }
        if (recentProcessedFingerprints.containsKey(exactHash) && screenshotPath.isBlank()) {
            return
        }
        recentProcessedFingerprints[exactHash] = now

        // 无障碍页面、收银台完成页与端侧 OCR 已经是完整页面文本，0ms 立即直通解析（消除人为等待延迟）；
        // 仅系统通知栏通道保留 220ms 短窗口以合并同一时刻到达的微信+银行双通知。
        val waitWindowMs = if (
            captureSource == "AI_ACCESSIBILITY" ||
            captureSource == "AI_OCR" ||
            captureSource == "AI_LIVE_CASHIER"
        ) 0L else 220L

        synchronized(pendingLock) {
            val taggedLine = buildTaggedCaptureLine(
                captureSource = captureSource,
                packageName = packageName,
                cashierVisitId = cashierVisitId,
                verifiedActivity = verifiedActivity,
                cleanedText = cleaned
            )
            pendingTexts.add(taggedLine)
            pendingPackages.add(packageName)
            pendingSources.add(captureSource)
            pendingSourceLabels.add("$captureSource($packageName)")
            if (upstreamContext != null) {
                pendingUpstreamContext = upstreamContext
            }
            if (screenshotPath.isNotBlank()) {
                if (pendingScreenshotPath.isNotBlank() && pendingScreenshotPath != screenshotPath) {
                    BillScreenshotStorage.deleteIfExists(pendingScreenshotPath)
                }
                pendingScreenshotPath = screenshotPath
                pendingScreenshotSha256 = screenshotSha256
            }
            if (pendingCapturedAtMillis == 0L) {
                pendingCapturedAtMillis = capturedAtMillis
            }

            debounceJob?.cancel()
            debounceJob = scope.launch {
                if (waitWindowMs > 0L) {
                    delay(waitWindowMs)
                }
                val batch = drainPendingBatch() ?: return@launch
                // 在独立协程中串行执行 AI 解析与落库，避免新事件 debounceJob.cancel() 中断正在写库的事务
                scope.launch {
                    pipelineMutex.withLock {
                        executeAiPipeline(
                            mergedText = batch.mergedText,
                            packageName = batch.primaryPackage,
                            captureSource = batch.mergedSource,
                            screenshotPath = batch.screenshotPath,
                            screenshotSha256 = batch.screenshotSha256,
                            capturedAtMillis = batch.capturedAtMillis,
                            batchSize = batch.batchSize,
                            mergedSourceLabels = batch.mergedSourceLabels,
                            upstreamContext = batch.upstreamContext
                        )
                    }
                }
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
    ) = pipelineMutex.withLock {
        executeAiPipeline(
            mergedText = rawText,
            packageName = packageName,
            captureSource = captureSource,
            screenshotPath = "",
            screenshotSha256 = "",
            capturedAtMillis = System.currentTimeMillis()
        )
    }

    /**
     * 供单元测试确定性同步执行单次识账管道（可控 `capturedAtMillis` 与收银台 `cashierVisitId`）
     */
    internal suspend fun processCapturedEventForTest(
        rawText: String,
        packageName: String,
        captureSource: String,
        capturedAtMillis: Long,
        cashierVisitId: Long = 0L,
        verifiedActivity: String = "",
        screenshotPath: String = "",
        screenshotSha256: String = "",
        upstreamContext: UpstreamWindowCaptureContext? = null
    ): TransactionEntity? = pipelineMutex.withLock {
        val cleaned = rawText.trim()
        if (!cleaned.startsWith("[AI_") &&
            (!financialKeywordRegex.containsMatchIn(cleaned) || !numberRegex.containsMatchIn(cleaned))
        ) {
            recordPreFilterNoiseTrace(
                rawText = cleaned,
                packageName = packageName,
                captureSource = captureSource,
                capturedAtMillis = capturedAtMillis,
                cashierVisitId = cashierVisitId,
                verifiedActivity = verifiedActivity,
                upstreamContext = upstreamContext,
                ruleCode = "MISSING_FINANCIAL_KEYWORD_OR_AMOUNT",
                reasonSummary = "未命中金融动账关键词或数字金额特征"
            )
            return@withLock null
        }
        val merged = if (rawText.startsWith("[AI_")) {
            rawText
        } else {
            buildTaggedCaptureLine(
                captureSource = captureSource,
                packageName = packageName,
                cashierVisitId = cashierVisitId,
                verifiedActivity = verifiedActivity,
                cleanedText = rawText
            )
        }
        executeAiPipeline(
            mergedText = merged,
            packageName = packageName,
            captureSource = captureSource,
            screenshotPath = screenshotPath,
            screenshotSha256 = screenshotSha256,
            capturedAtMillis = capturedAtMillis,
            batchSize = 1,
            mergedSourceLabels = listOf("$captureSource($packageName)"),
            upstreamContext = upstreamContext
        )
    }

    internal fun getPendingDuplicateCandidateIdsForTest(): Set<Long> =
        pendingDuplicateCandidates.keys.toSet()

    /**
     * 当用户在「检测到疑似重复」弹窗或通知中主动点击「录入」按钮时调用：
     * 将默认未录入的候选账单正式写入数据库并刷新通知。
     */
    suspend fun forceRecordDuplicateCandidate(candidateId: Long): TransactionEntity? = pipelineMutex.withLock {
        val candidate = pendingDuplicateCandidates.remove(candidateId) ?: return@withLock null
        val txToRecord = candidate.copy(
            id = 0L,
            duplicateSuspected = true,
            updatedAtMillis = System.currentTimeMillis()
        )
        val medBreakdown = AiAccountingEngine.extractMedicalBreakdown(txToRecord.rawCapturedText)
        val isSelfPayZeroPureMed = DedicatedFundManager.parseStructuredMedicalNote(txToRecord.note).selfPayZeroExplicit
        val savedTx = if (txToRecord.type == TransactionType.EXPENSE &&
            txToRecord.paymentMethodRaw != "医保支付" &&
            !isSelfPayZeroPureMed &&
            medBreakdown.totalMedicalCents > 0L &&
            txToRecord.amountCents > 0L
        ) {
            val commercialDisc = (txToRecord.discountCents - medBreakdown.totalMedicalCents).coerceAtLeast(0L)
            repository.recordCompositeMedicalTransaction(
                mainTx = txToRecord,
                poolSubsidyCents = medBreakdown.poolSubsidyCents,
                personalAccountCents = medBreakdown.personalAccountCents,
                unspecifiedMedicalCents = medBreakdown.unspecifiedMedicalCents,
                commercialDiscountCents = commercialDisc
            ).mainTransaction
        } else {
            val newId = repository.recordTransaction(txToRecord)
            txToRecord.copy(id = newId)
        }
        saveEvidenceRecord(savedTx, savedTx.captureSource, "user.force.record", savedTx.rawCapturedText)

        val wallets = repository.getAllWallets()
        val walletObj = wallets.firstOrNull { it.id == (savedTx.sourceWalletId ?: savedTx.targetWalletId) }
        val walletName = walletObj?.let { w ->
            if (!w.cardLast4.isNullOrBlank()) "${w.name}(${w.cardLast4})" else w.name
        } ?: "不关联账户"

        appendLog(
            packageName = "user.force.record",
            captureSource = savedTx.captureSource,
            rawText = savedTx.rawCapturedText.take(120),
            outcome = CaptureEventOutcome.RECORDED,
            detailSummary = "[用户手动确认录入] ${savedTx.category} · ${savedTx.merchant} (${savedTx.amountCents / 100.0} ${savedTx.currency}) -> $walletName",
            transactionId = savedTx.id
        )

        showOverlayBannerForRecordedTransaction(savedTx, walletName, isFused = false)
        uiGateway.sendSilentRecordedNotification(savedTx, wallets)
        savedTx
    }

    fun clearObservabilityLogs() {
        _observabilityLogs.value = emptyList()
    }

    private fun drainPendingBatch(): DrainedCaptureBatch? {
        synchronized(pendingLock) {
            if (pendingTexts.isEmpty()) return null
            val batch = DrainedCaptureBatch(
                mergedText = pendingTexts.joinToString("\n---\n"),
                primaryPackage = pendingPackages.firstOrNull() ?: "unknown",
                mergedSource = if (pendingSources.size > 1) "AI_MERGED" else pendingSources.first(),
                batchSize = pendingTexts.size,
                mergedSourceLabels = pendingSourceLabels.toList(),
                upstreamContext = pendingUpstreamContext,
                screenshotPath = pendingScreenshotPath,
                screenshotSha256 = pendingScreenshotSha256,
                capturedAtMillis = if (pendingCapturedAtMillis > 0L) pendingCapturedAtMillis else System.currentTimeMillis()
            )
            pendingTexts.clear()
            pendingPackages.clear()
            pendingSources.clear()
            pendingSourceLabels.clear()
            pendingUpstreamContext = null
            pendingScreenshotPath = ""
            pendingScreenshotSha256 = ""
            pendingCapturedAtMillis = 0L
            return batch
        }
    }

    private suspend fun executeAiPipeline(
        mergedText: String,
        packageName: String,
        captureSource: String,
        screenshotPath: String,
        screenshotSha256: String,
        capturedAtMillis: Long,
        batchSize: Int = 1,
        mergedSourceLabels: List<String> = listOf("$captureSource($packageName)"),
        upstreamContext: UpstreamWindowCaptureContext? = null
    ): TransactionEntity? {
        val wallets = repository.getAllWallets()
        val recentExpenses = repository.getRecentRefundableExpenses(15)
        val traceEnabled = debugTraceStore?.isEnabled() == true
        val traceCollector = if (traceEnabled) AiParseTraceCollector() else null
        val incomingVisitTagEarly = extractCashierVisitTag(mergedText)

        val rawParsedTx = aiEngine.parseCapturedText(
            rawText = mergedText,
            sourceAppPackage = packageName,
            captureMode = captureSource,
            wallets = wallets,
            recentExpenses = recentExpenses,
            capturedAtMillis = capturedAtMillis,
            traceCollector = traceCollector
        )

        if (rawParsedTx == null) {
            BillScreenshotStorage.deleteIfExists(screenshotPath)
            appendLog(
                packageName = packageName,
                captureSource = captureSource,
                rawText = mergedText.take(140),
                outcome = CaptureEventOutcome.NOISE_IGNORED,
                detailSummary = "AI/语义引擎判定不包含有效金额或非真实资金变动"
            )
            if (traceCollector != null) {
                recordPipelineDebugTrace(
                    outcome = DebugTraceOutcome.NOISE_IGNORED,
                    headline = "噪音过滤 · 解析引擎判定非真实资金变动",
                    mergedText = mergedText,
                    packageName = packageName,
                    captureSource = captureSource,
                    capturedAtMillis = capturedAtMillis,
                    screenshotPath = "",
                    screenshotSha256 = "",
                    upstreamContext = upstreamContext,
                    visitTag = incomingVisitTagEarly,
                    traceCollector = traceCollector,
                    finalTx = null,
                    smartRuleSummary = "",
                    smartRuleOverrodeCategory = false,
                    batchSize = batchSize,
                    mergedSourceLabels = mergedSourceLabels,
                    dbCandidatesInspected = emptyList(),
                    matchedRuleId = "PARSE_REJECTED_NON_FINANCIAL",
                    matchedCandidateId = null,
                    fusedFieldsDiff = emptyList(),
                    finalTransactionId = null,
                    finalSummary = "AI/本地语义引擎判定不包含有效金额或处于非动账/取消状态"
                )
            }
            return null
        }

        // 若已开启「家庭共享账本」，日常 EXPENSE / INCOME 默认标记 syncToFamily = true；随后再过条件规则以便命中「不同步家庭」时自动豁免
        val initialFamilyCandidate = if (MoJiCloudSyncClient.isFamilyAutoShareActive() && rawParsedTx.isEligibleForFamilySync()) {
            rawParsedTx.copy(syncToFamily = true)
        } else {
            rawParsedTx
        }

        // 执行 When -> Then 可视化条件规则管道
        val ruleEval = smartRuleEngine?.evaluateAndApply(initialFamilyCandidate, mergedText)
        val parsedTx: TransactionEntity
        val matchedRuleNames: String
        var smartRuleTraceSummary = ""
        var smartRuleOverrodeCategory = false
        when (ruleEval) {
            is SmartRuleExecutionResult.Intercepted -> {
                BillScreenshotStorage.deleteIfExists(screenshotPath)
                val interceptDetail = "触发条件规则【${ruleEval.matchedRule.name}】静默拦截不入账"
                appendLog(
                    packageName = packageName,
                    captureSource = captureSource,
                    rawText = mergedText.take(140),
                    outcome = CaptureEventOutcome.NOISE_IGNORED,
                    detailSummary = interceptDetail
                )
                if (traceCollector != null) {
                    recordPipelineDebugTrace(
                        outcome = DebugTraceOutcome.NOISE_IGNORED,
                        headline = "条件规则拦截 · ${ruleEval.matchedRule.name}",
                        mergedText = mergedText,
                        packageName = packageName,
                        captureSource = captureSource,
                        capturedAtMillis = capturedAtMillis,
                        screenshotPath = "",
                        screenshotSha256 = "",
                        upstreamContext = upstreamContext,
                        visitTag = incomingVisitTagEarly,
                        traceCollector = traceCollector,
                        finalTx = rawParsedTx,
                        smartRuleSummary = "拦截规则:${ruleEval.matchedRule.name}(当${ruleEval.matchedRule.conditionSummary()})",
                        smartRuleOverrodeCategory = false,
                        batchSize = batchSize,
                        mergedSourceLabels = mergedSourceLabels,
                        dbCandidatesInspected = emptyList(),
                        matchedRuleId = "SMART_RULE_INTERCEPTED",
                        matchedCandidateId = null,
                        fusedFieldsDiff = emptyList(),
                        finalTransactionId = null,
                        finalSummary = interceptDetail
                    )
                }
                return null
            }
            is SmartRuleExecutionResult.Transformed -> {
                parsedTx = ruleEval.transaction
                matchedRuleNames = if (ruleEval.matchedRules.isNotEmpty()) {
                    " [规则:${ruleEval.matchedRules.joinToString("+") { it.name }}]"
                } else ""
                if (ruleEval.matchedRules.isNotEmpty()) {
                    smartRuleTraceSummary = ruleEval.matchedRules.joinToString(" + ") {
                        "${it.name}(当${it.conditionSummary()} -> ${it.actionSummary()})"
                    }
                    smartRuleOverrodeCategory = parsedTx.category != rawParsedTx.category
                }
            }
            null -> {
                parsedTx = initialFamilyCandidate
                matchedRuleNames = ""
            }
        }

        val resolvedOrderKey = parsedTx.orderKey
            .ifBlank { parsedTx.transactionNo }
            .ifBlank { parsedTx.merchantOrderNo }
        val amountTypeFingerprint = "AMT_${parsedTx.type}_${parsedTx.amountCents}_${parsedTx.currency}"
        val incomingIsLiveCashier = captureSource == "AI_LIVE_CASHIER" || mergedText.contains("[AI_LIVE_CASHIER")
        val incomingVisitTag = extractCashierVisitTag(mergedText)

        // =========================================================================
        // 识别是否为同一笔账单（按订单唯一 Key 或「相同支付时间 + 相同金额」认定为同一笔钱）：
        // 1. 若用户点开详情页或点开“更多”露出了新细节（如订单号、商户单号、支付方式、商品说明、具体商户、数量、优惠、真实秒级支付时间等），
        //    自动认定为同一笔账单并将新细节增量合并回原账单，流体云提示「已补全账单详情」；
        // 2. 若二阶段通知/详情面对 ≥2 笔同金额未绑单号的 `AI_LIVE_CASHIER` 候选导致归属歧义，禁止盲目吞并，直接走疑似重复弹窗交用户确认；
        // 3. 若原账单各项细节均已齐全（无任何新信息可补充），则判定为重复扫描，默认不重复录入，流体云提示「检测到疑似重复」（点「录入」才强制入账）。
        // =========================================================================
        var duplicateMatchedTx: TransactionEntity? = null
        var duplicateReasonLabel = ""
        var matchedRuleCode = ""
        var hasAmbiguousLiveCashierCandidates = false
        val inspectedCandidatesSummary = ArrayList<String>(8)

        fun formatCandidateBrief(cand: TransactionEntity): String {
            val vTag = extractCashierVisitTag(cand.rawCapturedText).ifBlank { "-" }
            val deltaSec = (parsedTx.timestamp - cand.timestamp) / 1000L
            return "#${cand.id}[src=${cand.captureSource},amt=${cand.amountCents},visit=$vTag,Δt=${deltaSec}s]"
        }

        // 规则 1：检查订单唯一 Key (`orderKey` / `transactionNo` / `merchantOrderNo`) 交叉命中
        if (resolvedOrderKey.isNotBlank()) {
            duplicateMatchedTx = repository.findTransactionByOrderKey(resolvedOrderKey)
                ?: repository.findTransactionByNo(resolvedOrderKey)
                ?: repository.findTransactionByMerchantOrderNo(resolvedOrderKey)
                ?: (if (parsedTx.transactionNo.isNotBlank()) {
                    repository.findTransactionByNo(parsedTx.transactionNo)
                        ?: repository.findTransactionByOrderKey(parsedTx.transactionNo)
                } else null)
                ?: (if (parsedTx.merchantOrderNo.isNotBlank()) {
                    repository.findTransactionByMerchantOrderNo(parsedTx.merchantOrderNo)
                        ?: repository.findTransactionByOrderKey(parsedTx.merchantOrderNo)
                } else null)

            if (duplicateMatchedTx != null) {
                duplicateReasonLabel = "订单Key一致(${resolvedOrderKey.takeLast(8)})"
                matchedRuleCode = "RULE_1_ORDER_KEY_EXACT"
                if (traceEnabled) {
                    inspectedCandidatesSummary.add(formatCandidateBrief(duplicateMatchedTx))
                }
            }
        }

        // 规则 1b（AC-1 / AC-5）：同一收银台访问会话（相同 `visitTag`）无论相隔多久或原记录是否已被通知升级为 `AI_FUSED`，直接幂等命中原记录！
        if (duplicateMatchedTx == null && incomingIsLiveCashier && incomingVisitTag.isNotBlank()) {
            val sameVisitCandidates = repository.findTransactionsByAmountAndOccurredWindow(
                amountCents = parsedTx.amountCents,
                occurredAtMillis = parsedTx.timestamp,
                toleranceMillis = 24 * 3600_000L
            ) + (if (parsedTx.discountCents > 0L) {
                repository.findTransactionsByAmountAndOccurredWindow(
                    amountCents = parsedTx.amountCents + parsedTx.discountCents,
                    occurredAtMillis = parsedTx.timestamp,
                    toleranceMillis = 24 * 3600_000L
                )
            } else {
                emptyList()
            })
            val distinctSameVisitPool = sameVisitCandidates
                .distinctBy { it.id }
                .filterNot { DedicatedFundManager.isCompositeMedicalPersonalChild(it) }
            if (traceEnabled) {
                distinctSameVisitPool.take(6).forEach { inspectedCandidatesSummary.add(formatCandidateBrief(it)) }
            }
            val matchedBySameVisit = distinctSameVisitPool
                .firstOrNull { existing ->
                    existing.type == parsedTx.type &&
                        existing.currency == parsedTx.currency &&
                        extractCashierVisitTag(existing.rawCapturedText) == incomingVisitTag
                }
            if (matchedBySameVisit != null) {
                duplicateMatchedTx = matchedBySameVisit
                duplicateReasonLabel = "同一收银台会话复扫($incomingVisitTag)"
                matchedRuleCode = "RULE_1B_SAME_CASHIER_VISIT"
            }
        }

        // 规则 2：若规则 1 未命中，检查：
        // (a) 跨端内嵌订单号匹配；
        // (a2) 二阶段通知/账单详情/电商订单详情 对 无单号 `AI_LIVE_CASHIER` 收银台记录的单向时序窗校准（唯一候选原位升级 AI_FUSED，≥2 候选防误吞走疑似重复确认）；
        // (b) 同一支付时间（timestamp）或同一登记时间（capturedAtMillis）+ 相同金额匹配（保护不同收银台 Visit 会话的连续同金额支付）；
        // (c) 跨应用同笔购物融合；
        // (d) 同一收银台立减动画完成校准实扣金额。
        if (duplicateMatchedTx == null) {
            val sameDayAmountCandidates = repository.findTransactionsByAmountAndOccurredWindow(
                amountCents = parsedTx.amountCents,
                occurredAtMillis = parsedTx.timestamp,
                toleranceMillis = 24 * 3600_000L
            ).filterNot { DedicatedFundManager.isCompositeMedicalPersonalChild(it) }
            val timeOrCapturedCandidates = repository.findTransactionsByAmountAndTimeOrCapturedWindow(
                amountCents = parsedTx.amountCents,
                occurredAtMillis = parsedTx.timestamp,
                capturedAtMillis = capturedAtMillis,
                toleranceMillis = 65_000L
            ).filterNot { DedicatedFundManager.isCompositeMedicalPersonalChild(it) }
            val combinedCandidates = (timeOrCapturedCandidates + sameDayAmountCandidates).distinctBy { it.id }
            if (traceEnabled && inspectedCandidatesSummary.isEmpty()) {
                combinedCandidates.take(8).forEach { inspectedCandidatesSummary.add(formatCandidateBrief(it)) }
            }

            // 2a. 跨端内嵌订单号匹配（如微信支付商品详情中包含京东/淘宝/美团订单编号）
            val matchedByEmbeddedOrderNo = combinedCandidates.firstOrNull { existing ->
                hasCrossEmbeddedOrderNumber(existing, parsedTx, resolvedOrderKey, mergedText)
            }
            if (matchedByEmbeddedOrderNo != null) {
                duplicateMatchedTx = matchedByEmbeddedOrderNo
                duplicateReasonLabel = "跨端订单号关联(${resolvedOrderKey.ifBlank { matchedByEmbeddedOrderNo.orderKey }.takeLast(8)})"
                matchedRuleCode = "RULE_2A_EMBEDDED_CROSS_ORDER_NO"
            }

            // 2a-live. 二阶段通知/详情页回填无单号 `AI_LIVE_CASHIER` 记录（单向时序窗 -15s..180s 或跨端同单规则）
            if (duplicateMatchedTx == null && !incomingIsLiveCashier) {
                val rawLiveCandidates = combinedCandidates.filter { existing ->
                    isMatchingLiveCashierStage1Candidate(
                        existing = existing,
                        incoming = parsedTx,
                        incomingPackage = packageName,
                        incomingRawText = mergedText
                    )
                }
                // 若候选池中既有已被通知校准过的 `AI_FUSED` 旧单，又存在唯一 1 笔全新的待校准 `AI_LIVE_CASHIER` 新单，优先校准该唯一新单
                val uncalibratedLiveCandidates = rawLiveCandidates.filter { it.captureSource == "AI_LIVE_CASHIER" }
                val liveCashierCandidates = if (rawLiveCandidates.size >= 2 && uncalibratedLiveCandidates.size == 1) {
                    uncalibratedLiveCandidates
                } else {
                    rawLiveCandidates
                }
                if (liveCashierCandidates.size >= 2) {
                    duplicateMatchedTx = liveCashierCandidates.first()
                    hasAmbiguousLiveCashierCandidates = true
                    duplicateReasonLabel = "存在${liveCashierCandidates.size}笔同金额收银台待校准记录，归属存在歧义"
                    matchedRuleCode = "RULE_2A_LIVE_CASHIER_AMBIGUOUS_GUARD"
                } else if (liveCashierCandidates.size == 1) {
                    duplicateMatchedTx = liveCashierCandidates.single()
                    duplicateReasonLabel = "收银台记录二阶段详情校准"
                    matchedRuleCode = "RULE_2A_LIVE_CASHIER_STAGE2_CALIBRATION"
                }
            }

            // 2b. (相同支付时间 OR 近距离登记时间) + 相同类型/币种/金额去重（若同渠道且双方均有不同交易单号，或属于两次独立收银台 Visit 会话，绝不误判为重复）
            if (duplicateMatchedTx == null) {
                var matchedByCapturedClock = false
                fun isDistinctLiveCashierVisitPair(existing: TransactionEntity): Boolean {
                    if (!incomingIsLiveCashier || !isUnnumberedLiveCashierOrigin(existing)) {
                        return false
                    }
                    val existingVisitTag = extractCashierVisitTag(existing.rawCapturedText)
                    if (incomingVisitTag.isNotBlank() && existingVisitTag.isNotBlank() && incomingVisitTag != existingVisitTag) {
                        return true
                    }
                    return false
                }

                fun isSamePaymentOrCapturedEvent(existing: TransactionEntity): Boolean {
                    if (existing.type != parsedTx.type || existing.currency != parsedTx.currency) {
                        return false
                    }
                    if (isDistinctLiveCashierVisitPair(existing)) {
                        return false
                    }
                    // 非 AI_LIVE_CASHIER 的二阶段事件对无单号收银台记录统一由上方 2a-live 按单向窗口严格裁决，避免超窗误吞
                    if (!incomingIsLiveCashier && isUnnumberedLiveCashierOrigin(existing)) {
                        return false
                    }
                    val existingKey = existing.orderKey
                        .ifBlank { existing.transactionNo }
                        .ifBlank { existing.merchantOrderNo }
                    if (existingKey.isNotBlank() &&
                        resolvedOrderKey.isNotBlank() &&
                        existingKey != resolvedOrderKey &&
                        !isCrossChannelSameOrderPair(existing, parsedTx, packageName, mergedText) &&
                        !hasCrossEmbeddedOrderNumber(existing, parsedTx, resolvedOrderKey, mergedText)
                    ) {
                        return false
                    }
                    val isPaymentTimeMatch = abs(existing.timestamp - parsedTx.timestamp) <= 65_000L ||
                        (existing.timestamp / 60_000L == parsedTx.timestamp / 60_000L)
                    val isCapturedTimeMatch = existing.capturedAtMillis > 0L &&
                        capturedAtMillis > 0L &&
                        abs(existing.timestamp - parsedTx.timestamp) <= 15 * 60_000L && (
                            abs(existing.capturedAtMillis - capturedAtMillis) <= 65_000L ||
                                (existing.capturedAtMillis / 60_000L == capturedAtMillis / 60_000L)
                            )
                    if (!isPaymentTimeMatch && isCapturedTimeMatch) {
                        matchedByCapturedClock = true
                    }
                    return isPaymentTimeMatch || isCapturedTimeMatch
                }

                val matchedByTimeAndAmount = combinedCandidates.firstOrNull { existing ->
                    isSamePaymentOrCapturedEvent(existing)
                } ?: recentRecordedTransactions[amountTypeFingerprint]?.takeIf { recent ->
                    isSamePaymentOrCapturedEvent(recent)
                }?.let { recent ->
                    repository.getTransactionById(recent.id)
                }

                if (matchedByTimeAndAmount != null) {
                    duplicateMatchedTx = matchedByTimeAndAmount
                    val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
                    duplicateReasonLabel = if (matchedByCapturedClock) {
                        "登记时间(${fmt.format(Date(capturedAtMillis))})与金额一致"
                    } else {
                        "支付时间(${fmt.format(Date(parsedTx.timestamp))})与金额一致"
                    }
                    matchedRuleCode = "RULE_2B_SAME_TIME_AND_AMOUNT"
                }
            }

            // 2c. 跨应用同笔购物识别（如拼多多/京东/淘宝订单详情页 ↔ 支付宝/微信账单详情页扫了同一件商品）
            if (duplicateMatchedTx == null) {
                val crossChannelCandidates = sameDayAmountCandidates.filter { existing ->
                    if (!incomingIsLiveCashier && isUnnumberedLiveCashierOrigin(existing)) {
                        false
                    } else if (incomingIsLiveCashier && isUnnumberedLiveCashierOrigin(existing)) {
                        val existingVisitTag = extractCashierVisitTag(existing.rawCapturedText)
                        !(incomingVisitTag.isNotBlank() && existingVisitTag.isNotBlank() && incomingVisitTag != existingVisitTag)
                    } else {
                        isCrossChannelSameOrderPair(
                            txA = existing,
                            txB = parsedTx,
                            txBPackage = packageName,
                            txBRawText = mergedText
                        )
                    }
                }
                val matchedByCrossChannel = crossChannelCandidates.singleOrNull()
                if (matchedByCrossChannel != null) {
                    duplicateMatchedTx = matchedByCrossChannel
                    duplicateReasonLabel = "跨应用同笔订单融合(${matchedByCrossChannel.merchant.take(6)}↔${parsedTx.merchant.take(6)})"
                    matchedRuleCode = "RULE_2C_CROSS_CHANNEL_SAME_ORDER"
                }
            }

            // 2d. 兼容支付成功页（如支付宝碰一下立减/微信立减）第一帧先展示原价、几秒后立减动画完成展示实扣价的场景
            if (duplicateMatchedTx == null && parsedTx.discountCents > 0L) {
                val preDiscountCandidates = repository.findTransactionsByAmountAndOccurredWindow(
                    amountCents = parsedTx.amountCents + parsedTx.discountCents,
                    occurredAtMillis = parsedTx.timestamp,
                    toleranceMillis = 45_000L
                )
                val matchedPreDiscount = preDiscountCandidates.firstOrNull { existing ->
                    val existingVisitTag = extractCashierVisitTag(existing.rawCapturedText)
                    val sameOrUnsetVisit = incomingVisitTag.isBlank() ||
                        existingVisitTag.isBlank() ||
                        incomingVisitTag == existingVisitTag
                    existing.type == parsedTx.type &&
                        existing.currency == parsedTx.currency &&
                        existing.discountCents == 0L &&
                        existing.merchant == parsedTx.merchant &&
                        sameOrUnsetVisit &&
                        abs(existing.timestamp - parsedTx.timestamp) <= 15_000L
                }
                if (matchedPreDiscount != null) {
                    duplicateMatchedTx = matchedPreDiscount
                    duplicateReasonLabel = "立减动画完成校准实扣金额"
                    matchedRuleCode = "RULE_2D_POST_DISCOUNT_CALIBRATION"
                    if (traceEnabled) {
                        inspectedCandidatesSummary.add(formatCandidateBrief(matchedPreDiscount))
                    }
                }
            }
        }

        val medBreakdown = AiAccountingEngine.extractMedicalBreakdown(mergedText)
        val isSelfPayZeroPureMed = DedicatedFundManager.parseStructuredMedicalNote(parsedTx.note).selfPayZeroExplicit
        val isMixedMedicalExpense = parsedTx.type == TransactionType.EXPENSE &&
            parsedTx.paymentMethodRaw != "医保支付" &&
            !isSelfPayZeroPureMed &&
            medBreakdown.totalMedicalCents > 0L &&
            parsedTx.amountCents > 0L

        if (duplicateMatchedTx != null && DedicatedFundManager.isCompositeMedicalPersonalChild(duplicateMatchedTx)) {
            duplicateMatchedTx = duplicateMatchedTx.relatedTransactionId?.let { repository.getTransactionById(it) }
                ?: duplicateMatchedTx
        }

        if (duplicateMatchedTx != null) {
            val matched = duplicateMatchedTx
            val enrichment = if (hasAmbiguousLiveCashierCandidates) {
                null
            } else {
                buildEnrichedTransactionIfHasNewDetails(
                    existing = matched,
                    incoming = parsedTx,
                    resolvedOrderKey = resolvedOrderKey,
                    screenshotPath = screenshotPath,
                    screenshotSha256 = screenshotSha256,
                    capturedAtMillis = capturedAtMillis
                )
            }

            if (!hasAmbiguousLiveCashierCandidates && isMixedMedicalExpense) {
                val rawBaseMain = enrichment?.first ?: matched
                val baseMain = if (rawBaseMain.screenshotPath.isBlank() && screenshotPath.isNotBlank()) {
                    rawBaseMain.copy(
                        screenshotPath = screenshotPath,
                        screenshotSha256 = screenshotSha256.ifBlank { rawBaseMain.screenshotSha256 }
                    )
                } else {
                    rawBaseMain
                }
                val baseFields = enrichment?.second ?: emptyList()
                val commercialDisc = (parsedTx.discountCents - medBreakdown.totalMedicalCents).coerceAtLeast(0L)
                val fusionResult = repository.enrichCompositeMedicalOnExistingTransaction(
                    existingMainId = matched.id,
                    enrichedCandidateMain = baseMain,
                    baseEnrichedFields = baseFields,
                    poolSubsidyCents = medBreakdown.poolSubsidyCents,
                    personalAccountCents = medBreakdown.personalAccountCents,
                    unspecifiedMedicalCents = medBreakdown.unspecifiedMedicalCents,
                    commercialDiscountCents = commercialDisc
                )
                if (fusionResult.wasModified) {
                    val enrichedTx = fusionResult.updatedMainTransaction
                    if (screenshotPath.isNotBlank() && screenshotPath != enrichedTx.screenshotPath) {
                        BillScreenshotStorage.deleteIfExists(screenshotPath)
                    }
                    saveEvidenceRecord(enrichedTx, "AI_FUSED", packageName, mergedText)
                    recentRecordedTransactions[amountTypeFingerprint] = enrichedTx
                    recentDuplicatePromptCooldown["DUP_${enrichedTx.id}"] = capturedAtMillis

                    val updatedWallets = repository.getAllWallets()
                    val walletObj = updatedWallets.firstOrNull { it.id == (enrichedTx.sourceWalletId ?: enrichedTx.targetWalletId) }
                    val walletName = walletObj?.let { w ->
                        if (!w.cardLast4.isNullOrBlank()) "${w.name}(${w.cardLast4})" else w.name
                    } ?: "不关联账户"

                    val enrichedSummary = fusionResult.enrichedFields.joinToString("、")
                    val fusionSummaryText = "认定为同一笔医疗账单 #${enrichedTx.id}（$duplicateReasonLabel），已融合医保明细：$enrichedSummary"
                    appendLog(
                        packageName = packageName,
                        captureSource = "AI_FUSED",
                        rawText = mergedText.take(140),
                        outcome = CaptureEventOutcome.FUSED_PATCH,
                        detailSummary = fusionSummaryText,
                        transactionId = enrichedTx.id
                    )
                    if (traceCollector != null) {
                        recordPipelineDebugTrace(
                            outcome = DebugTraceOutcome.FUSED_PATCH,
                            headline = "跨道医疗融合 · 账单 #${enrichedTx.id} ($enrichedSummary)",
                            mergedText = mergedText,
                            packageName = packageName,
                            captureSource = captureSource,
                            capturedAtMillis = capturedAtMillis,
                            screenshotPath = enrichedTx.screenshotPath,
                            screenshotSha256 = enrichedTx.screenshotSha256,
                            upstreamContext = upstreamContext,
                            visitTag = incomingVisitTag,
                            traceCollector = traceCollector,
                            finalTx = enrichedTx,
                            smartRuleSummary = smartRuleTraceSummary,
                            smartRuleOverrodeCategory = smartRuleOverrodeCategory,
                            batchSize = batchSize,
                            mergedSourceLabels = mergedSourceLabels,
                            dbCandidatesInspected = inspectedCandidatesSummary,
                            matchedRuleId = matchedRuleCode.ifBlank { "FUSED_MEDICAL_ENRICHMENT" },
                            matchedCandidateId = matched.id,
                            fusedFieldsDiff = buildDetailedFusionDiff(matched, enrichedTx, fusionResult.enrichedFields, updatedWallets),
                            finalTransactionId = enrichedTx.id,
                            finalSummary = fusionSummaryText
                        )
                    }

                    showOverlayBannerForEnrichedTransaction(
                        tx = enrichedTx,
                        walletName = walletName,
                        enrichedFields = fusionResult.enrichedFields
                    )
                    return enrichedTx
                }
            } else if (enrichment != null) {
                // 情况 A：点开详情页/点开“更多”补充了新细节（订单号、商户单号、支付方式、商品说明、商户全称、数量、优惠等）-> 自动补全到原账单！
                val (enrichedTx, enrichedFields) = enrichment
                if (screenshotPath.isNotBlank() && screenshotPath != enrichedTx.screenshotPath) {
                    BillScreenshotStorage.deleteIfExists(screenshotPath)
                }
                repository.updateExistingTransaction(enrichedTx)
                saveEvidenceRecord(enrichedTx, "AI_FUSED", packageName, mergedText)
                recentRecordedTransactions[amountTypeFingerprint] = enrichedTx
                recentDuplicatePromptCooldown["DUP_${enrichedTx.id}"] = capturedAtMillis

                val updatedWallets = repository.getAllWallets()
                val walletObj = updatedWallets.firstOrNull { it.id == (enrichedTx.sourceWalletId ?: enrichedTx.targetWalletId) }
                val walletName = walletObj?.let { w ->
                    if (!w.cardLast4.isNullOrBlank()) "${w.name}(${w.cardLast4})" else w.name
                } ?: "不关联账户"

                val enrichedSummary = enrichedFields.joinToString("、")
                val fusionSummaryText = "认定为同一笔账单 #${enrichedTx.id}（$duplicateReasonLabel），已自动补全细节：$enrichedSummary"
                appendLog(
                    packageName = packageName,
                    captureSource = "AI_FUSED",
                    rawText = mergedText.take(140),
                    outcome = CaptureEventOutcome.FUSED_PATCH,
                    detailSummary = fusionSummaryText,
                    transactionId = enrichedTx.id
                )
                if (traceCollector != null) {
                    recordPipelineDebugTrace(
                        outcome = DebugTraceOutcome.FUSED_PATCH,
                        headline = "跨道融合补全 · 账单 #${enrichedTx.id} ($enrichedSummary)",
                        mergedText = mergedText,
                        packageName = packageName,
                        captureSource = captureSource,
                        capturedAtMillis = capturedAtMillis,
                        screenshotPath = enrichedTx.screenshotPath,
                        screenshotSha256 = enrichedTx.screenshotSha256,
                        upstreamContext = upstreamContext,
                        visitTag = incomingVisitTag,
                        traceCollector = traceCollector,
                        finalTx = enrichedTx,
                        smartRuleSummary = smartRuleTraceSummary,
                        smartRuleOverrodeCategory = smartRuleOverrodeCategory,
                        batchSize = batchSize,
                        mergedSourceLabels = mergedSourceLabels,
                        dbCandidatesInspected = inspectedCandidatesSummary,
                        matchedRuleId = matchedRuleCode.ifBlank { "FUSED_DETAIL_ENRICHMENT" },
                        matchedCandidateId = matched.id,
                        fusedFieldsDiff = buildDetailedFusionDiff(matched, enrichedTx, enrichedFields, updatedWallets),
                        finalTransactionId = enrichedTx.id,
                        finalSummary = fusionSummaryText
                    )
                }

                showOverlayBannerForEnrichedTransaction(
                    tx = enrichedTx,
                    walletName = walletName,
                    enrichedFields = enrichedFields
                )
                return enrichedTx
            }

            // 情况 B：原账单所有细节早已齐全，没有任何新字段可补充 -> 判定为重复扫描，默认不录入数据库
            var effectiveMatchedScreenshotPath = matched.screenshotPath
            var effectiveMatchedScreenshotSha = matched.screenshotSha256
            if (matched.screenshotPath.isBlank() && screenshotPath.isNotBlank()) {
                val attached = repository.attachBillScreenshotIfNeeded(
                    transactionId = matched.id,
                    screenshotPath = screenshotPath,
                    screenshotSha256 = screenshotSha256,
                    nowMillis = capturedAtMillis
                )
                if (attached) {
                    effectiveMatchedScreenshotPath = screenshotPath
                    effectiveMatchedScreenshotSha = screenshotSha256
                    debugTraceStore?.attachScreenshotRefToTransactionTrace(
                        transactionId = matched.id,
                        screenshotPath = screenshotPath,
                        screenshotSha256 = screenshotSha256
                    )
                } else {
                    BillScreenshotStorage.deleteIfExists(screenshotPath)
                }
            } else if (screenshotPath.isNotBlank() && screenshotPath != matched.screenshotPath) {
                BillScreenshotStorage.deleteIfExists(screenshotPath)
            }

            // 防止同一收银台会话复扫、同笔二阶段重复通知，或小程序/订单页停留 15s~10min 动态刷新时弹出“疑似重复”打扰用户（AC-1 / AC-4 / AC-5）
            if (!hasAmbiguousLiveCashierCandidates && captureSource != "AI_SANDBOX") {
                val withinTenMinutesOfCaptureOrTouch =
                    (capturedAtMillis - matched.capturedAtMillis in 0L..(10 * 60_000L)) ||
                        (matched.updatedAtMillis > 0L && capturedAtMillis - matched.updatedAtMillis in 0L..(10 * 60_000L))
                val isSilentIdempotentRescan = incomingIsLiveCashier ||
                    (captureSource == "AI_NOTIFICATION" && abs(parsedTx.timestamp - matched.timestamp) <= 15 * 60_000L) ||
                    ((captureSource == "AI_ACCESSIBILITY" || captureSource == "AI_OCR") && withinTenMinutesOfCaptureOrTouch)
                if (isSilentIdempotentRescan) {
                    val silentSummary = "幂等静默拦截重复事件（$duplicateReasonLabel），匹配账单 #${matched.id}"
                    appendLog(
                        packageName = packageName,
                        captureSource = captureSource,
                        rawText = mergedText.take(140),
                        outcome = CaptureEventOutcome.DEDUP_BLOCKED,
                        detailSummary = silentSummary,
                        transactionId = matched.id
                    )
                    if (traceCollector != null) {
                        recordPipelineDebugTrace(
                            outcome = DebugTraceOutcome.DEDUP_BLOCKED,
                            headline = "幂等防重拦截 · 匹配账单 #${matched.id} ($duplicateReasonLabel)",
                            mergedText = mergedText,
                            packageName = packageName,
                            captureSource = captureSource,
                            capturedAtMillis = capturedAtMillis,
                            screenshotPath = effectiveMatchedScreenshotPath,
                            screenshotSha256 = effectiveMatchedScreenshotSha,
                            upstreamContext = upstreamContext,
                            visitTag = incomingVisitTag,
                            traceCollector = traceCollector,
                            finalTx = parsedTx,
                            smartRuleSummary = smartRuleTraceSummary,
                            smartRuleOverrodeCategory = smartRuleOverrodeCategory,
                            batchSize = batchSize,
                            mergedSourceLabels = mergedSourceLabels,
                            dbCandidatesInspected = inspectedCandidatesSummary,
                            matchedRuleId = matchedRuleCode.ifBlank { "SILENT_IDEMPOTENT_DEDUP" },
                            matchedCandidateId = matched.id,
                            fusedFieldsDiff = emptyList(),
                            finalTransactionId = matched.id,
                            finalSummary = silentSummary
                        )
                    }
                    return null
                }
            }

            val promptCooldownKey = if (hasAmbiguousLiveCashierCandidates) {
                "DUP_AMBIGUOUS_${matched.id}"
            } else {
                "DUP_${matched.id}"
            }
            val cooldownWindowMs = if (hasAmbiguousLiveCashierCandidates) 3_000L else 15_000L
            val lastPromptTime = recentDuplicatePromptCooldown[promptCooldownKey] ?: 0L
            if (lastPromptTime > 0L &&
                capturedAtMillis - lastPromptTime < cooldownWindowMs &&
                captureSource != "AI_SANDBOX"
            ) {
                if (traceCollector != null) {
                    recordPipelineDebugTrace(
                        outcome = DebugTraceOutcome.DEDUP_BLOCKED,
                        headline = "重复提示冷却期内静默拦截 · 匹配账单 #${matched.id}",
                        mergedText = mergedText,
                        packageName = packageName,
                        captureSource = captureSource,
                        capturedAtMillis = capturedAtMillis,
                        screenshotPath = effectiveMatchedScreenshotPath,
                        screenshotSha256 = effectiveMatchedScreenshotSha,
                        upstreamContext = upstreamContext,
                        visitTag = incomingVisitTag,
                        traceCollector = traceCollector,
                        finalTx = parsedTx,
                        smartRuleSummary = smartRuleTraceSummary,
                        smartRuleOverrodeCategory = smartRuleOverrodeCategory,
                        batchSize = batchSize,
                        mergedSourceLabels = mergedSourceLabels,
                        dbCandidatesInspected = inspectedCandidatesSummary,
                        matchedRuleId = "${matchedRuleCode.ifBlank { "DEDUP" }}_PROMPT_COOLDOWN",
                        matchedCandidateId = matched.id,
                        fusedFieldsDiff = emptyList(),
                        finalTransactionId = matched.id,
                        finalSummary = "处于重复提示冷却期(${cooldownWindowMs}ms)，静默跳过弹窗"
                    )
                }
                return null
            }
            recentDuplicatePromptCooldown[promptCooldownKey] = capturedAtMillis

            val walletObj = wallets.firstOrNull { it.id == (parsedTx.sourceWalletId ?: parsedTx.targetWalletId) }
            val walletName = walletObj?.let { w ->
                if (!w.cardLast4.isNullOrBlank()) "${w.name}(${w.cardLast4})" else w.name
            } ?: "不关联账户"

            val candidateId = candidateIdGenerator.incrementAndGet()
            val candidateTx = parsedTx.copy(
                orderKey = resolvedOrderKey,
                screenshotPath = effectiveMatchedScreenshotPath,
                screenshotSha256 = effectiveMatchedScreenshotSha,
                duplicateSuspected = true,
                note = "${parsedTx.note.ifBlank { parsedTx.merchant }} [用户手动确认录入]".trim().take(140)
            )
            pendingDuplicateCandidates[candidateId] = candidateTx

            val dupSummary = "检测到疑似重复（默认未录入）：$duplicateReasonLabel，匹配账单 #${matched.id}（${parsedTx.merchant} ¥${String.format(Locale.US, "%.2f", parsedTx.amountCents / 100.0)}）"
            appendLog(
                packageName = packageName,
                captureSource = captureSource,
                rawText = mergedText.take(140),
                outcome = CaptureEventOutcome.DUPLICATE_SUSPECTED,
                detailSummary = dupSummary,
                transactionId = matched.id
            )
            if (traceCollector != null) {
                recordPipelineDebugTrace(
                    outcome = DebugTraceOutcome.DUPLICATE_SUSPECTED,
                    headline = "疑似重复待确认 · 匹配账单 #${matched.id} ($duplicateReasonLabel)",
                    mergedText = mergedText,
                    packageName = packageName,
                    captureSource = captureSource,
                    capturedAtMillis = capturedAtMillis,
                    screenshotPath = effectiveMatchedScreenshotPath,
                    screenshotSha256 = effectiveMatchedScreenshotSha,
                    upstreamContext = upstreamContext,
                    visitTag = incomingVisitTag,
                    traceCollector = traceCollector,
                    finalTx = candidateTx,
                    smartRuleSummary = smartRuleTraceSummary,
                    smartRuleOverrodeCategory = smartRuleOverrodeCategory,
                    batchSize = batchSize,
                    mergedSourceLabels = mergedSourceLabels,
                    dbCandidatesInspected = inspectedCandidatesSummary,
                    matchedRuleId = matchedRuleCode.ifBlank { "DUPLICATE_SUSPECTED" },
                    matchedCandidateId = matched.id,
                    fusedFieldsDiff = emptyList(),
                    finalTransactionId = matched.id,
                    finalSummary = dupSummary
                )
            }

            // 弹出流体云胶囊：“检测到疑似重复 · 默认未录入”，带【录入】按钮；点击胶囊主体打开已匹配的账单详情
            showSuspectedDuplicateBanner(
                candidateId = candidateId,
                tx = candidateTx,
                walletName = walletName,
                duplicateReason = duplicateReasonLabel,
                matchedTransactionId = matched.id
            )
            scope.launch {
                delay(680L)
                uiGateway.sendSuspectedDuplicateNotification(
                    candidateId = candidateId,
                    tx = candidateTx,
                    wallets = wallets,
                    duplicateReason = duplicateReasonLabel,
                    matchedTransactionId = matched.id
                )
            }
            return null
        }

        // =========================================================================
        // 确认非重复新账单：先立即写入数据库并弹出 120Hz 流体云胶囊，待展开动画完成后后台静默补抓截图与通知
        // =========================================================================
        val txToRecord = parsedTx.copy(
            orderKey = resolvedOrderKey,
            screenshotPath = screenshotPath,
            screenshotSha256 = screenshotSha256,
            duplicateSuspected = false
        )

        val savedTx = if (isMixedMedicalExpense) {
            val commercialDisc = (txToRecord.discountCents - medBreakdown.totalMedicalCents).coerceAtLeast(0L)
            repository.recordCompositeMedicalTransaction(
                mainTx = txToRecord,
                poolSubsidyCents = medBreakdown.poolSubsidyCents,
                personalAccountCents = medBreakdown.personalAccountCents,
                unspecifiedMedicalCents = medBreakdown.unspecifiedMedicalCents,
                commercialDiscountCents = commercialDisc
            ).mainTransaction
        } else {
            val newId = repository.recordTransaction(txToRecord)
            txToRecord.copy(id = newId)
        }
        saveEvidenceRecord(savedTx, captureSource, packageName, mergedText)

        recentRecordedTransactions[amountTypeFingerprint] = savedTx
        recentProcessedFingerprints[amountTypeFingerprint] = capturedAtMillis
        // 刚入账后的 15 秒内，同一页面后续探测波次直接静默，不再弹出“疑似重复”打扰用户
        recentDuplicatePromptCooldown["DUP_${savedTx.id}"] = capturedAtMillis

        val walletObj = wallets.firstOrNull { it.id == (savedTx.sourceWalletId ?: savedTx.targetWalletId) }
        val walletName = walletObj?.let { w ->
            if (!w.cardLast4.isNullOrBlank()) "${w.name}(${w.cardLast4})" else w.name
        } ?: "不关联账户"

        val keySuffix = if (savedTx.orderKey.isNotBlank()) " · Key:${savedTx.orderKey.takeLast(6)}" else ""
        val qtySuffix = if (savedTx.quantityDesc.isNotBlank()) " · ${savedTx.quantityDesc}" else ""

        val recordedSummary = "[${savedTx.type.displayName}] ${savedTx.category} · ${savedTx.merchant} (${savedTx.amountCents / 100.0} ${savedTx.currency}$qtySuffix) -> $walletName$keySuffix$matchedRuleNames"
        appendLog(
            packageName = packageName,
            captureSource = captureSource,
            rawText = mergedText.take(140),
            outcome = CaptureEventOutcome.RECORDED,
            detailSummary = recordedSummary,
            transactionId = savedTx.id
        )
        if (traceCollector != null) {
            val isRefundOffset = savedTx.type == TransactionType.REFUND_OFFSET
            val traceOutcome = if (isRefundOffset) DebugTraceOutcome.REFUND_OFFSET else DebugTraceOutcome.RECORDED
            val traceRuleCode = if (isRefundOffset) {
                if (savedTx.relatedTransactionId != null) "REFUND_OFFSET_LINKED_EXPENSE(#${savedTx.relatedTransactionId})" else "REFUND_OFFSET_STANDALONE"
            } else {
                "NEW_RECORD_NO_DUPLICATE"
            }
            val traceHeadline = if (isRefundOffset) {
                "退货冲抵入账 · 账单 #${savedTx.id} ${savedTx.merchant} (+¥${String.format(Locale.US, "%.2f", savedTx.amountCents / 100.0)})"
            } else {
                "成功入账 · 账单 #${savedTx.id} ${savedTx.merchant} (¥${String.format(Locale.US, "%.2f", savedTx.amountCents / 100.0)})"
            }
            recordPipelineDebugTrace(
                outcome = traceOutcome,
                headline = traceHeadline,
                mergedText = mergedText,
                packageName = packageName,
                captureSource = captureSource,
                capturedAtMillis = capturedAtMillis,
                screenshotPath = savedTx.screenshotPath,
                screenshotSha256 = savedTx.screenshotSha256,
                upstreamContext = upstreamContext,
                visitTag = incomingVisitTag,
                traceCollector = traceCollector,
                finalTx = savedTx,
                smartRuleSummary = smartRuleTraceSummary,
                smartRuleOverrodeCategory = smartRuleOverrodeCategory,
                batchSize = batchSize,
                mergedSourceLabels = mergedSourceLabels,
                dbCandidatesInspected = inspectedCandidatesSummary,
                matchedRuleId = traceRuleCode,
                matchedCandidateId = savedTx.relatedTransactionId,
                fusedFieldsDiff = emptyList(),
                finalTransactionId = savedTx.id,
                finalSummary = recordedSummary
            )
        }

        // 1. 若需补抓无障碍账单原图：在弹出流体云胶囊前先做前台宿主复核并抓取 SurfaceFlinger 屏幕帧，
        //    彻底杜绝切回聊天/桌面后误截隐私页面，且避免将本应用流体云胶囊截入账单原图；WebP 编码写盘继续留在后台异步完成。
        val pendingSavedScreenshot: CompletableDeferred<SavedScreenshotInfo?>? = if (
            screenshotPath.isBlank() &&
            (captureSource == "AI_ACCESSIBILITY" ||
                captureSource == "AI_LIVE_CASHIER" ||
                captureSource == "AI_MERGED")
        ) {
            val service = PaymentAccessibilityService.instance
            if (service != null) {
                val frameCapturedDeferred = CompletableDeferred<Unit>()
                val savedDeferred = CompletableDeferred<SavedScreenshotInfo?>()
                val abortBeforeBanner = AtomicBoolean(false)
                service.captureBillEvidenceScreenshot(
                    expectedPackage = packageName,
                    abortSignal = abortBeforeBanner,
                    onFrameCaptured = { frameCapturedDeferred.complete(Unit) },
                    onSaved = { info -> savedDeferred.complete(info) }
                )
                val frameDone = withTimeoutOrNull(320L) {
                    frameCapturedDeferred.await()
                }
                if (frameDone == null) {
                    abortBeforeBanner.set(true)
                }
                savedDeferred
            } else {
                null
            }
        } else {
            null
        }

        // 2. 弹出一加 120Hz 流体云实况胶囊（带「撤销」按钮）
        showOverlayBannerForRecordedTransaction(savedTx, walletName, isFused = false)

        // 3. 错峰 680ms（待流体云展开动画完全结束后）在后台静默写入通知栏，并走单列条件原子更新绑定原图，杜绝整行覆写冲掉并发分类/融合更新
        scope.launch {
            delay(680L)
            val updatedWallets = repository.getAllWallets()
            uiGateway.sendSilentRecordedNotification(savedTx, updatedWallets)
            if (pendingSavedScreenshot != null) {
                val accessibilityCaptured = withTimeoutOrNull(1_800L) {
                    pendingSavedScreenshot.await()
                }
                if (accessibilityCaptured != null) {
                    val attached = repository.attachBillScreenshotIfNeeded(
                        transactionId = savedTx.id,
                        screenshotPath = accessibilityCaptured.absolutePath,
                        screenshotSha256 = accessibilityCaptured.sha256,
                        nowMillis = System.currentTimeMillis()
                    )
                    if (!attached) {
                        BillScreenshotStorage.deleteIfExists(accessibilityCaptured.absolutePath)
                    } else {
                        debugTraceStore?.attachScreenshotRefToTransactionTrace(
                            transactionId = savedTx.id,
                            screenshotPath = accessibilityCaptured.absolutePath,
                            screenshotSha256 = accessibilityCaptured.sha256
                        )
                        pipelineMutex.withLock {
                            val cached = recentRecordedTransactions[amountTypeFingerprint]
                            if (cached != null && cached.id == savedTx.id && cached.screenshotPath.isBlank()) {
                                recentRecordedTransactions[amountTypeFingerprint] = cached.copy(
                                    screenshotPath = accessibilityCaptured.absolutePath,
                                    screenshotSha256 = accessibilityCaptured.sha256
                                )
                            }
                        }
                    }
                }
            }
        }
        return savedTx
    }

    private suspend fun saveEvidenceRecord(
        tx: TransactionEntity,
        captureSource: String,
        packageName: String,
        rawText: String
    ) {
        runCatching {
            repository.insertCaptureEvidence(
                CaptureEvidenceEntity(
                    transactionId = tx.id,
                    captureSource = captureSource,
                    sourcePackage = packageName,
                    occurredAtMillis = tx.timestamp,
                    capturedAtMillis = tx.capturedAtMillis,
                    amountCents = tx.amountCents,
                    transactionNo = tx.orderKey.ifBlank { tx.transactionNo },
                    merchantOrderNo = tx.merchantOrderNo,
                    paymentMethodRaw = tx.paymentMethodRaw,
                    screenshotPath = tx.screenshotPath,
                    screenshotSha256 = tx.screenshotSha256,
                    rawText = rawText.take(800)
                )
            )
        }
    }

    /**
     * 检查两笔同金额账单是否存在跨端内嵌订单号引用（如微信支付商品详情写着「京东-订单编号3673486013948720」，而京东订单号为 `3673486013948720`）
     */
    private fun hasCrossEmbeddedOrderNumber(
        existing: TransactionEntity,
        incoming: TransactionEntity,
        incomingResolvedKey: String,
        incomingRawText: String
    ): Boolean {
        if (existing.type != incoming.type ||
            existing.currency != incoming.currency ||
            existing.amountCents != incoming.amountCents
        ) return false
        val existingHaystack = "${existing.note} | ${existing.rawCapturedText} | ${existing.orderKey} | ${existing.transactionNo} | ${existing.merchantOrderNo}"
        val incomingHaystack = "${incoming.note} | $incomingRawText | $incomingResolvedKey | ${incoming.transactionNo} | ${incoming.merchantOrderNo}"

        val incomingCodes = listOf(incomingResolvedKey, incoming.transactionNo, incoming.merchantOrderNo)
            .map { it.trim() }
            .filter { it.length >= 8 }
        if (incomingCodes.any { existingHaystack.contains(it) }) return true

        val existingCodes = listOf(existing.orderKey, existing.transactionNo, existing.merchantOrderNo)
            .map { it.trim() }
            .filter { it.length >= 8 }
        if (existingCodes.any { incomingHaystack.contains(it) }) return true

        return false
    }

    /**
     * 判断两笔相同金额、相同收支类型的记录是否属于「同一笔订单在电商平台（拼多多/京东/淘宝/抖音）与支付工具（支付宝/微信/银行）分别采集的记录」：
     * 例如：
     * - 拼多多订单 `#68`（`2026-10-29 21:39:06`，`¥9.60`，商户=`百亿补贴·E路驰`，订单号=`261029-183458916720060`，支付方式=`支付宝`）
     * - 支付宝账单 `#78`（`2026-10-29 21:39:12`，`¥9.60`，商户=`拼多多平台商户`，订单号=`2026102923001463691406989771`，商户单号=`XP23261029...`，支付方式=`招商银行信用卡(3354)`）
     */
    private fun isCrossChannelSameOrderPair(
        txA: TransactionEntity,
        txB: TransactionEntity,
        txBPackage: String = "",
        txBRawText: String = ""
    ): Boolean {
        if (txA.type != txB.type ||
            txA.currency != txB.currency ||
            txA.amountCents != txB.amountCents
        ) return false

        val aRaw = txA.rawCapturedText
        val bRaw = txBRawText.ifBlank { txB.rawCapturedText }
        val aIsEcommerce = isEcommerceOrderRecord(txA, "", aRaw)
        val bIsEcommerce = isEcommerceOrderRecord(txB, txBPackage, bRaw)
        val aIsPayment = isPaymentChannelRecord(txA, "", aRaw)
        val bIsPayment = isPaymentChannelRecord(txB, txBPackage, bRaw)

        // 必须一侧是电商平台订单详情，另一侧是支付通道账单详情
        val isOppositeChannels = (aIsEcommerce && bIsPayment && !bIsEcommerce) ||
            (bIsEcommerce && aIsPayment && !aIsEcommerce)
        if (!isOppositeChannels) return false

        if (hasCrossEmbeddedOrderNumber(txA, txB, txB.orderKey.ifBlank { txB.transactionNo }, bRaw)) {
            return true
        }

        val timeDiffMs = abs(txA.timestamp - txB.timestamp)
        val aIsDateOnly = isDateOnlyFallbackTimestamp(txA.timestamp)
        val bIsDateOnly = isDateOnlyFallbackTimestamp(txB.timestamp)
        val isSameDayWithDateFallback = (aIsDateOnly || bIsDateOnly) && isSameCalendarDay(txA.timestamp, txB.timestamp)

        // 下单时间与支付时间通常相差数秒至数分钟（上限 15 分钟）；若拼多多未展开「更多」仅靠订单号前缀解析出当天 12:00:00，则要求同一自然日
        if (timeDiffMs > 15 * 60_000L && !isSameDayWithDateFallback) {
            return false
        }

        // 若双方均显式携带 4 位银行卡尾号且不相同，拒绝跨通道误合
        val tailA = extractExplicitCardTail4(txA.paymentMethodRaw)
        val tailB = extractExplicitCardTail4(txB.paymentMethodRaw)
        if (tailA.isNotBlank() && tailB.isNotBlank() && tailA != tailB) {
            return false
        }

        val familyA = detectPlatformFamily(txA, "", aRaw)
        val familyB = detectPlatformFamily(txB, txBPackage, bRaw)
        if (familyA.isNotBlank() && familyB.isNotBlank() && familyA != familyB) {
            return false
        }

        val aIsMiniProgramOrder = isWechatMiniProgramOrderRecord(txA, "", aRaw)
        val bIsMiniProgramOrder = isWechatMiniProgramOrderRecord(txB, txBPackage, bRaw)
        if (aIsMiniProgramOrder || bIsMiniProgramOrder) {
            return areMerchantsCompatibleForMiniProgramFusion(txA.merchant, txB.merchant)
        }

        // 若双方的平台归属明确一致（如均为 PDD / JD / TAOBAO / DOUYIN / MEITUAN），或下单与扣款时间相差在 90 秒以内，认定为同一笔交易！
        return (familyA.isNotBlank() && familyA == familyB) || timeDiffMs <= 90_000L
    }

    /**
     * 识别微信小程序商家订单/取餐页（如 `AppBrandUI*` 奈雪/喜茶/瑞幸/麦当劳小程序订单详情与取餐页，`AC-3`）：
     * - 严格排除收银台实时完成页（`AI_LIVE_CASHIER`）、系统通知栏（`AI_NOTIFICATION`）与微信官方 42000/1000 账单流水详情页。
     */
    private fun isWechatMiniProgramOrderRecord(tx: TransactionEntity, pkg: String, rawText: String): Boolean {
        if (tx.captureSource == "AI_LIVE_CASHIER" ||
            tx.captureSource == "AI_NOTIFICATION" ||
            rawText.contains("[AI_LIVE_CASHIER") ||
            rawText.contains("[AI_NOTIFICATION")
        ) {
            return false
        }
        if (tx.transactionNo.matches(WECHAT_OFFICIAL_BILL_TX_NO_REGEX) ||
            tx.orderKey.matches(WECHAT_OFFICIAL_BILL_TX_NO_REGEX) ||
            rawText.contains("本服务由财付通提供")
        ) {
            return false
        }
        val isWechatPkg = pkg == "com.tencent.mm" || rawText.contains("com.tencent.mm")
        if (!isWechatPkg) return false

        val capturedAct = extractCapturedActivity(rawText).lowercase(Locale.ROOT)
        if (capturedAct.contains(".appbrand.") || capturedAct.contains("appbrandui")) {
            return true
        }
        val hasMiniProgramPickupMarkers = rawText.contains("取餐码") ||
            rawText.contains("取茶号") ||
            rawText.contains("取单号") ||
            rawText.contains("待取餐") ||
            rawText.contains("请取餐") ||
            rawText.contains("制作中") ||
            rawText.contains("门店自取")
        return hasMiniProgramPickupMarkers && (rawText.contains("下单时间") || rawText.contains("订单编号") || rawText.contains("订单号"))
    }

    private fun areMerchantsCompatibleForMiniProgramFusion(merchantA: String, merchantB: String): Boolean {
        val genericOrLegalSuffixCheck: (String) -> Boolean = { m ->
            val trimmed = m.trim()
            trimmed.isBlank() ||
                trimmed in setOf("日常商户", "微信支付", "微信", "支付宝", "财付通", "消费", "支出", "商户退款") ||
                trimmed.endsWith("平台商户") ||
                trimmed.endsWith("有限公司") ||
                trimmed.endsWith("餐饮管理")
        }
        val clean: (String) -> String = { raw ->
            raw.trim()
                .removePrefix("财付通-")
                .removePrefix("微信支付-")
                .removePrefix("支付宝-")
                .removePrefix("快捷支付-")
                .replace(Regex("""[(（][^()（）]{1,18}[)）]$"""), "")
                .trim()
        }
        val ca = clean(merchantA)
        val cb = clean(merchantB)
        if (genericOrLegalSuffixCheck(ca) || genericOrLegalSuffixCheck(cb)) return true
        return ca.contains(cb, ignoreCase = true) || cb.contains(ca, ignoreCase = true)
    }

    private fun isEcommerceOrderRecord(tx: TransactionEntity, pkg: String, rawText: String): Boolean {
        if (isWechatMiniProgramOrderRecord(tx, pkg, rawText)) {
            return true
        }
        val combined = "$pkg | $rawText | ${tx.category}"
        if (combined.contains("com.xunmeng.pinduoduo") ||
            combined.contains("com.jingdong.app.mall") ||
            combined.contains("com.taobao.taobao") ||
            combined.contains("com.tmall.wireless") ||
            combined.contains("com.ss.android.ugc.aweme") ||
            combined.contains("com.achievo.vipshop") ||
            combined.contains("com.xingin.xhs")
        ) {
            return true
        }
        if (tx.orderKey.matches(Regex("""^\d{6}-\d{8,}$"""))) return true
        return false
    }

    private fun isPaymentChannelRecord(tx: TransactionEntity, pkg: String, rawText: String): Boolean {
        if (isWechatMiniProgramOrderRecord(tx, pkg, rawText)) {
            return false
        }
        val combined = "$pkg | $rawText".lowercase(Locale.ROOT)
        if (combined.contains("com.eg.android.alipaygphone") ||
            combined.contains("com.tencent.mm") ||
            combined.contains("com.unionpay") ||
            combined.contains("com.android.mms") ||
            combined.contains("cmb.pb") ||
            combined.contains("com.cmblife") ||
            combined.contains("cmbchina") ||
            combined.contains("icbc") ||
            combined.contains("com.chinamworld") ||
            combined.contains("ccb") ||
            combined.contains("abchina") ||
            combined.contains("boc") ||
            combined.contains("bankcomm") ||
            combined.contains("psbc") ||
            combined.contains("pingan") ||
            combined.contains("spdb") ||
            combined.contains("citic") ||
            combined.contains("cib") ||
            combined.contains("cmbc") ||
            combined.contains("cebbank") ||
            combined.contains("cgb")
        ) {
            return true
        }
        if (tx.merchant.endsWith("平台商户") ||
            tx.transactionNo.matches(Regex("""^(?:202\d{21,30}|42000\d{18,28})$"""))
        ) {
            return true
        }
        return false
    }

    private fun detectPlatformFamily(tx: TransactionEntity, pkg: String, rawText: String): String {
        val text = "$pkg | ${tx.merchant} | ${tx.note} | ${tx.category} | ${tx.orderKey} | ${tx.merchantOrderNo} | $rawText"
        return when {
            text.contains("pinduoduo") || text.contains("拼多多") || text.contains("多多支付") ||
                text.contains("百亿补贴") || tx.orderKey.matches(Regex("""^\d{6}-\d{8,}$""")) ||
                tx.merchantOrderNo.startsWith("XP") -> "PDD"
            text.contains("jingdong") || text.contains("京东") || text.contains("白条") -> "JD"
            text.contains("taobao") || text.contains("tmall") || text.contains("淘宝") || text.contains("天猫") -> "TAOBAO"
            text.contains("aweme") || text.contains("抖音") -> "DOUYIN"
            text.contains("meituan") || text.contains("美团") -> "MEITUAN"
            else -> ""
        }
    }

    private fun isDateOnlyFallbackTimestamp(ts: Long): Boolean {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        return cal.get(java.util.Calendar.HOUR_OF_DAY) == 12 &&
            cal.get(java.util.Calendar.MINUTE) == 0 &&
            cal.get(java.util.Calendar.SECOND) == 0
    }

    private fun isSameCalendarDay(tsA: Long, tsB: Long): Boolean {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        return fmt.format(Date(tsA)) == fmt.format(Date(tsB))
    }

    private fun isUnnumberedLiveCashierOrigin(tx: TransactionEntity): Boolean {
        val key = tx.orderKey.ifBlank { tx.transactionNo }.ifBlank { tx.merchantOrderNo }
        if (key.isNotBlank()) return false
        return tx.captureSource == "AI_LIVE_CASHIER" || tx.rawCapturedText.contains("[AI_LIVE_CASHIER")
    }

    private fun extractCashierVisitTag(rawText: String): String {
        val m = CASHIER_VISIT_TAG_REGEX.find(rawText) ?: return ""
        val pkg = m.groupValues[1].trim()
        val visitId = m.groupValues[2].trim()
        return if (visitId.isNotEmpty()) "$pkg#v$visitId" else ""
    }

    private fun hasExplicitSecondTimeInText(rawText: String): Boolean =
        EXPLICIT_SECOND_TIME_REGEX.containsMatchIn(rawText)

    private fun extractExplicitCardTail4(text: String): String =
        EXPLICIT_CARD_TAIL4_REGEX.find(text)?.let { m ->
            m.groupValues[1].ifEmpty { m.groupValues[2] }
        }.orEmpty()

    /**
     * 判断数据库内尚未绑定单号的 `AI_LIVE_CASHIER` 记录是否属于当前二阶段通知/账单详情/电商订单详情的候选：
     * - 金额、币种、收支类型一致；
     * - 满足单向合理时序窗（`existing.timestamp - incoming.timestamp in -15_000L..180_000L`，兼容收银台在支付完成后数秒至 3 分钟内展示或轻微时钟偏差）或跨端同单配对规则；
     * - 银行卡尾号与具体商户无硬冲突。
     */
    private fun isMatchingLiveCashierStage1Candidate(
        existing: TransactionEntity,
        incoming: TransactionEntity,
        incomingPackage: String,
        incomingRawText: String
    ): Boolean {
        if (!isUnnumberedLiveCashierOrigin(existing)) return false
        if (existing.type != incoming.type ||
            existing.currency != incoming.currency ||
            existing.amountCents != incoming.amountCents
        ) {
            return false
        }

        val timeDeltaMs = existing.timestamp - incoming.timestamp
        val negativeToleranceMs = if (existing.timestamp % 60_000L == 0L) -65_000L else -15_000L
        val inDirectionalWindow = timeDeltaMs in negativeToleranceMs..180_000L
        val isCrossChannelPair = isCrossChannelSameOrderPair(
            txA = existing,
            txB = incoming,
            txBPackage = incomingPackage,
            txBRawText = incomingRawText
        )
        if (!inDirectionalWindow && !isCrossChannelPair) {
            return false
        }

        // 若双方均显式携带了 4 位银行卡尾号且不相同，拒绝串单
        val existingTail4 = extractExplicitCardTail4(existing.paymentMethodRaw)
        val incomingTail4 = extractExplicitCardTail4(incoming.paymentMethodRaw)
        if (existingTail4.isNotBlank() && incomingTail4.isNotBlank() && existingTail4 != incomingTail4) {
            return false
        }

        if (isCrossChannelPair || incoming.captureSource == "AI_NOTIFICATION") {
            return true
        }

        val genericOrPlatformMerchants = setOf(
            "日常商户", "收款入账", "商户退款", "个人账户间流转", "消费", "支出",
            "拼多多", "拼多多平台商户", "上海寻梦信息技术有限公司",
            "京东", "京东商城平台商户", "江苏京东信息技术有限公司", "北京京东世纪贸易有限公司", "网银在线",
            "淘宝", "天猫", "支付宝(中国)网络技术有限公司",
            "抖音商城", "抖音生活服务", "抖音支付", "武汉合众易宝科技有限公司", "合众易宝",
            "美团", "美团平台商户", "北京三快在线科技有限公司", "饿了么", "支付宝", "微信", "微信支付", "财付通"
        )
        val exMerchant = existing.merchant.trim()
        val inMerchant = incoming.merchant.trim()
        val exGeneric = exMerchant.isBlank() || exMerchant in genericOrPlatformMerchants || exMerchant.endsWith("平台商户")
        val inGeneric = inMerchant.isBlank() || inMerchant in genericOrPlatformMerchants || inMerchant.endsWith("平台商户")
        if (exGeneric || inGeneric) {
            return true
        }
        return exMerchant.contains(inMerchant, ignoreCase = true) ||
            inMerchant.contains(exMerchant, ignoreCase = true)
    }

    /**
     * 当按「同样支付时间 + 同样金额」、「跨应用同笔订单」或「同样订单号」匹配到已有账单时，双向融合双方最优细节：
     * - 从电商订单侧保留：具体店铺名称（如 `百亿补贴·E路驰`）、具体商品标题（如 `便携车载破窗器...`）、原价与优惠明细、电商订单编号（`261029-...`）；
     * - 从支付宝/微信账单侧保留：真实扣款银行卡/花呗账户（如 `招商银行信用卡(3354)` 及对应 `sourceWalletId`）、支付交易单号（`2026102923...`）与商户单号（`XP23...`）。
     */
    internal fun buildEnrichedTransactionIfHasNewDetails(
        existing: TransactionEntity,
        incoming: TransactionEntity,
        resolvedOrderKey: String,
        screenshotPath: String,
        screenshotSha256: String,
        capturedAtMillis: Long
    ): Pair<TransactionEntity, List<String>>? {
        val enrichedFields = mutableListOf<String>()

        val existingIsEcommerce = isEcommerceOrderRecord(existing, "", existing.rawCapturedText)
        val incomingIsEcommerce = isEcommerceOrderRecord(incoming, "", incoming.rawCapturedText)

        var updatedOrderKey = existing.orderKey
        var updatedTxNo = existing.transactionNo
        var updatedMerchantOrderNo = existing.merchantOrderNo

        if (existing.orderKey.isBlank() && resolvedOrderKey.isNotBlank()) {
            updatedOrderKey = resolvedOrderKey
            enrichedFields.add("订单号(${resolvedOrderKey.takeLast(6)})")
        } else if (!existingIsEcommerce && incomingIsEcommerce && resolvedOrderKey.isNotBlank() && resolvedOrderKey != existing.orderKey) {
            // 原账单是支付宝/微信，新扫的是电商订单：将 orderKey 设为电商订单号，同时将原支付宝/微信单号保留在 transactionNo
            if (updatedTxNo.isBlank()) updatedTxNo = existing.orderKey
            updatedOrderKey = resolvedOrderKey
            enrichedFields.add("电商订单号(${resolvedOrderKey.takeLast(6)})")
        }

        if (incoming.transactionNo.isNotBlank()) {
            if (updatedTxNo.isBlank()) {
                updatedTxNo = incoming.transactionNo
                if (enrichedFields.none { it.contains("订单号") }) {
                    enrichedFields.add("交易单号(${incoming.transactionNo.takeLast(6)})")
                }
            } else if (existingIsEcommerce && !incomingIsEcommerce && incoming.transactionNo != updatedTxNo) {
                // 原账单是电商订单（transactionNo 存的是电商订单号），新扫的是支付宝/微信：将 transactionNo 升级为支付流水号
                updatedTxNo = incoming.transactionNo
                enrichedFields.add("支付单号(${incoming.transactionNo.takeLast(6)})")
            }
        }

        if (updatedMerchantOrderNo.isBlank() && incoming.merchantOrderNo.isNotBlank()) {
            updatedMerchantOrderNo = incoming.merchantOrderNo
            enrichedFields.add("商户单号(${incoming.merchantOrderNo.takeLast(6)})")
        }

        val genericChannelPayMethods = setOf(
            "支付宝", "微信支付", "微信", "多多支付", "京东支付", "抖音支付", "在线支付", "先用后付"
        )
        val existingPayIsGeneric = existing.paymentMethodRaw.isBlank() || existing.paymentMethodRaw in genericChannelPayMethods
        val incomingPayIsSpecific = incoming.paymentMethodRaw.isNotBlank() && incoming.paymentMethodRaw !in genericChannelPayMethods

        var updatedPayMethod = existing.paymentMethodRaw
        var updatedSourceWalletId = existing.sourceWalletId
        var updatedTargetWalletId = existing.targetWalletId
        if (incoming.paymentMethodRaw.isNotBlank() &&
            (existing.paymentMethodRaw.isBlank() ||
                (existingPayIsGeneric && incomingPayIsSpecific) ||
                (!incoming.paymentMethodRaw.equals(existing.paymentMethodRaw, ignoreCase = true) &&
                    incomingPayIsSpecific &&
                    incoming.paymentMethodRaw.length > existing.paymentMethodRaw.length &&
                    !existing.paymentMethodRaw.contains(incoming.paymentMethodRaw)))
        ) {
            updatedPayMethod = incoming.paymentMethodRaw
            if (incoming.sourceWalletId != null) updatedSourceWalletId = incoming.sourceWalletId
            if (incoming.targetWalletId != null) updatedTargetWalletId = incoming.targetWalletId
            enrichedFields.add("扣款账户(${incoming.paymentMethodRaw.take(12)})")
        }

        val genericMerchants = setOf(
            "日常商户", "收款入账", "商户退款", "个人账户间流转", "消费", "支出",
            "拼多多", "拼多多平台商户", "上海寻梦信息技术有限公司",
            "京东", "京东商城平台商户", "江苏京东信息技术有限公司", "北京京东世纪贸易有限公司", "网银在线",
            "淘宝", "天猫", "支付宝(中国)网络技术有限公司",
            "抖音商城", "抖音生活服务", "抖音支付", "武汉合众易宝科技有限公司", "合众易宝",
            "美团", "美团平台商户", "北京三快在线科技有限公司", "支付宝", "财付通"
        )
        val existingIsGeneric = existing.merchant.isBlank() ||
            existing.merchant in genericMerchants ||
            existing.merchant.endsWith("平台商户") ||
            existing.merchant.contains("京东信息技术") ||
            existing.merchant.contains("寻梦信息技术") ||
            (incomingIsEcommerce && !existingIsEcommerce && existing.merchant.endsWith("有限公司"))
        val incomingIsSpecific = incoming.merchant.isNotBlank() &&
            incoming.merchant !in genericMerchants &&
            !incoming.merchant.endsWith("平台商户") &&
            !incoming.merchant.contains("京东信息技术") &&
            !incoming.merchant.contains("寻梦信息技术")
        var updatedMerchant = existing.merchant
        var updatedCategory = existing.category
        if (incomingIsSpecific && (existingIsGeneric ||
                (incoming.merchant.length > existing.merchant.length && incoming.merchant.contains(existing.merchant)))
        ) {
            updatedMerchant = incoming.merchant
            if (!CategoryCatalog.isPendingTriage(incoming.category) &&
                (CategoryCatalog.isPendingTriage(existing.category) ||
                    !incoming.category.startsWith("购物-") ||
                    existing.category.startsWith("购物-") ||
                    existing.category == "餐饮")
            ) {
                updatedCategory = incoming.category
            }
            enrichedFields.add("商户(${incoming.merchant.take(8)})")
        } else if (CategoryCatalog.isPendingTriage(existing.category) && !CategoryCatalog.isPendingTriage(incoming.category)) {
            updatedCategory = incoming.category
            enrichedFields.add("分类(${incoming.category})")
        }

        val isSameAmount = incoming.amountCents == existing.amountCents
        val isPostDiscountRevision = existing.discountCents <= 0L &&
            incoming.discountCents > 0L &&
            existing.amountCents == incoming.amountCents + incoming.discountCents
        var updatedAmountCents = existing.amountCents
        var updatedAmountCnyCents = existing.amountCnyCents
        if (isPostDiscountRevision) {
            updatedAmountCents = incoming.amountCents
            updatedAmountCnyCents = incoming.amountCnyCents
            enrichedFields.add("实扣(¥${String.format(Locale.US, "%.2f", incoming.amountCents / 100.0)})")
        }

        var updatedDiscount = existing.discountCents
        if ((isSameAmount || isPostDiscountRevision) && existing.discountCents <= 0L && incoming.discountCents > 0L) {
            updatedDiscount = incoming.discountCents
            enrichedFields.add("优惠(¥${String.format(Locale.US, "%.2f", incoming.discountCents / 100.0)})")
        }

        var updatedNote = existing.note
        val cleanIncomingNote = incoming.note.trim()
        val existingProductDetail = extractProductDetailFromStructuredNote(
            note = existing.note,
            merchant = existing.merchant,
            payMethod = existing.paymentMethodRaw,
            genericPayMethods = genericChannelPayMethods
        )
        val incomingProductDetail = extractProductDetailFromStructuredNote(
            note = cleanIncomingNote,
            merchant = incoming.merchant,
            payMethod = incoming.paymentMethodRaw,
            genericPayMethods = genericChannelPayMethods
        )
        val existingNoteIsGenericOrderRef = existing.note.isBlank() ||
            existing.note == existing.merchant ||
            existing.note.contains("商户单号 ") ||
            existing.note.contains("订单编号")
        val incomingNoteHasProductTitle = cleanIncomingNote.isNotBlank() &&
            cleanIncomingNote != incoming.merchant &&
            !cleanIncomingNote.contains("[用户手动确认录入]") &&
            !cleanIncomingNote.contains("商户单号 ") &&
            !cleanIncomingNote.contains("订单编号")

        if ((isSameAmount || isPostDiscountRevision) && cleanIncomingNote.isNotBlank() && !cleanIncomingNote.contains("[用户手动确认录入]")) {
            if (isPostDiscountRevision) {
                updatedNote = mergeStructuredNoteWithProductAndPayMethod(
                    preferredHeaderNote = cleanIncomingNote,
                    productDetail = incomingProductDetail.ifBlank { existingProductDetail },
                    payMethod = updatedPayMethod
                )
            } else if (incomingProductDetail.isNotBlank() &&
                (existingProductDetail.isBlank() ||
                    (incomingProductDetail.length > existingProductDetail.length && !existingProductDetail.contains(incomingProductDetail)))
            ) {
                val headerSourceNote = if (updatedDiscount > 0L && existing.discountCents <= 0L) {
                    cleanIncomingNote
                } else {
                    existing.note.ifBlank { cleanIncomingNote }
                }
                updatedNote = mergeStructuredNoteWithProductAndPayMethod(
                    preferredHeaderNote = headerSourceNote,
                    productDetail = incomingProductDetail,
                    payMethod = updatedPayMethod
                )
                enrichedFields.add("商品说明")
            } else if (existingNoteIsGenericOrderRef && incomingNoteHasProductTitle) {
                updatedNote = cleanIncomingNote
                enrichedFields.add("商品说明")
            } else if (existingProductDetail.isBlank() &&
                incomingNoteHasProductTitle &&
                !existing.note.contains(cleanIncomingNote.take(10)) &&
                cleanIncomingNote.length > existing.note.length
            ) {
                updatedNote = cleanIncomingNote
                enrichedFields.add("商品说明")
            }
        }

        // 若融合后扣款账户已升级为具体银行卡/花呗（如「招商银行信用卡(3354)」），同步把备注末尾的泛渠道名替换或追加为真实扣款账户
        if (updatedPayMethod.isNotBlank() && updatedPayMethod !in genericChannelPayMethods && updatedNote.isNotBlank()) {
            var replacedGeneric = false
            for (genericPm in genericChannelPayMethods) {
                if (updatedNote.endsWith("· $genericPm")) {
                    updatedNote = updatedNote.removeSuffix("· $genericPm") + "· $updatedPayMethod"
                    replacedGeneric = true
                    break
                } else if (updatedNote.endsWith("| $genericPm")) {
                    updatedNote = updatedNote.removeSuffix("| $genericPm") + "| $updatedPayMethod"
                    replacedGeneric = true
                    break
                }
            }
            if (!replacedGeneric && !updatedNote.contains(updatedPayMethod) && updatedPayMethod.length <= 24) {
                updatedNote = if (updatedNote.contains(" | ")) {
                    "$updatedNote · $updatedPayMethod".take(96)
                } else if (updatedNote.startsWith("原价¥")) {
                    "$updatedNote | $updatedPayMethod".take(96)
                } else {
                    updatedNote
                }
            }
        }

        // 用融合后的最终商户名 + 商品备注重新校准一次分类（例如破窗器 -> 日用）
        val (reclassifiedCat, reclassifiedConf) = com.moji.ai.ai.WeightedCategoryClassifier.classifyExpense(
            rawText = "$updatedMerchant $updatedNote",
            merchant = updatedMerchant
        )
        if (reclassifiedConf >= 0.85f &&
            reclassifiedCat != "餐饮" &&
            !CategoryCatalog.isPendingTriage(reclassifiedCat) &&
            (updatedCategory.startsWith("购物-") ||
                updatedCategory == "餐饮" ||
                CategoryCatalog.isPendingTriage(updatedCategory))
        ) {
            if (CategoryCatalog.isPendingTriage(updatedCategory) && enrichedFields.none { it.startsWith("分类(") }) {
                enrichedFields.add("分类($reclassifiedCat)")
            }
            updatedCategory = reclassifiedCat
        }

        var updatedQty = existing.itemQuantity
        var updatedQtyDesc = existing.quantityDesc
        if (existing.itemQuantity <= 1 && incoming.itemQuantity > 1) {
            updatedQty = incoming.itemQuantity
            updatedQtyDesc = incoming.quantityDesc
            enrichedFields.add("数量(${incoming.quantityDesc})")
        }

        // 如果原账单属于 `AI_LIVE_CASHIER`（暂用采集时刻，即使恰好落在 `.000` 整秒也必须视为待校准时间！）、
        // 或原账单时间只有整分/日期（如通知栏时间或订单号日期前缀 12:00:00）、或带非零毫秒，而新识别提取到了真实支付时间，同步校准！
        val existingIsLiveCashier = existing.captureSource == "AI_LIVE_CASHIER" ||
            isUnnumberedLiveCashierOrigin(existing)
        val incomingIsLiveCashier = incoming.captureSource == "AI_LIVE_CASHIER" ||
            incoming.rawCapturedText.contains("[AI_LIVE_CASHIER")
        val existingWasFallbackTime = existingIsLiveCashier ||
            existing.timestamp % 1_000L != 0L ||
            existing.timestamp % 60_000L == 0L ||
            isDateOnlyFallbackTimestamp(existing.timestamp)
        val incomingHasExactSecondTime = !incomingIsLiveCashier &&
            incoming.timestamp % 1_000L == 0L &&
            !isDateOnlyFallbackTimestamp(incoming.timestamp) &&
            (incoming.timestamp % 60_000L != 0L || hasExplicitSecondTimeInText(incoming.rawCapturedText))
        val incomingHasExplicitMinuteTimeForLiveCashier = existingIsLiveCashier &&
            !incomingIsLiveCashier &&
            !isDateOnlyFallbackTimestamp(incoming.timestamp) &&
            AiAccountingEngine.hasExplicitOccurredTime(incoming.rawCapturedText, capturedAtMillis)
        val updatedTimestamp = if (
            existingWasFallbackTime &&
            (incomingHasExactSecondTime || incomingHasExplicitMinuteTimeForLiveCashier) &&
            incoming.timestamp != existing.timestamp
        ) {
            enrichedFields.add("支付时间")
            incoming.timestamp
        } else if (existingIsLiveCashier &&
            (incomingHasExactSecondTime || incomingHasExplicitMinuteTimeForLiveCashier) &&
            incoming.timestamp == existing.timestamp
        ) {
            enrichedFields.add("支付时间")
            incoming.timestamp
        } else {
            existing.timestamp
        }

        val (updatedTags, newTagsAdded) = mergeEnrichedTags(existing.tags, incoming.tags)
        if (newTagsAdded.isNotEmpty()) {
            enrichedFields.add("标签(${newTagsAdded.joinToString(",")})")
        }

        if (enrichedFields.isEmpty()) {
            return null
        }

        val enrichedTx = existing.copy(
            amountCents = updatedAmountCents,
            amountCnyCents = updatedAmountCnyCents,
            merchant = updatedMerchant,
            category = updatedCategory,
            note = updatedNote,
            tags = updatedTags,
            sourceWalletId = updatedSourceWalletId,
            targetWalletId = updatedTargetWalletId,
            itemQuantity = updatedQty,
            quantityDesc = updatedQtyDesc,
            discountCents = updatedDiscount,
            timestamp = updatedTimestamp,
            orderKey = updatedOrderKey,
            transactionNo = updatedTxNo,
            merchantOrderNo = updatedMerchantOrderNo,
            paymentMethodRaw = updatedPayMethod,
            screenshotPath = existing.screenshotPath.ifBlank { screenshotPath },
            screenshotSha256 = existing.screenshotSha256.ifBlank { screenshotSha256 },
            captureSource = "AI_FUSED",
            updatedAtMillis = capturedAtMillis
        )
        return enrichedTx to enrichedFields
    }

    private fun showOverlayBannerForEnrichedTransaction(
        tx: TransactionEntity,
        walletName: String,
        enrichedFields: List<String>
    ) {
        val isIncome = tx.type == TransactionType.INCOME || tx.type == TransactionType.REFUND_OFFSET
        val sign = when (tx.type) {
            TransactionType.INCOME, TransactionType.REFUND_OFFSET -> "+"
            TransactionType.EXPENSE -> "-"
            else -> ""
        }
        val symbol = CurrencyCode.fromCode(tx.currency).symbol
        val amtStr = String.format(Locale.US, "%.2f", tx.amountCents / 100.0)
        val enrichedSummary = enrichedFields.joinToString("/")
        val subtitle = "已补充 $enrichedSummary · ${tx.merchant.ifBlank { tx.category }} · $walletName"

        uiGateway.showRecordedBanner(
            statusTitle = "已补全账单详情",
            amountText = "$sign$symbol$amtStr",
            subtitle = subtitle,
            isIncome = isIncome,
            transactionId = tx.id
        )
    }

    private fun showOverlayBannerForRecordedTransaction(
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
        val symbol = CurrencyCode.fromCode(tx.currency).symbol
        val amtStr = String.format(Locale.US, "%.2f", tx.amountCents / 100.0)
        val statusText = if (isFused) "已补全账单" else "已自动记账"
        val amountText = "$sign$symbol$amtStr"
        val merchantPart = tx.merchant.ifBlank { tx.category }
        val occurredShort = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(tx.timestamp))
        val isCompositeMain = TagCatalog.parseTags(tx.tags).contains("组合支付") &&
            !DedicatedFundManager.isCompositeMedicalPersonalChild(tx)
        val parsedMedNote = if (isCompositeMain) {
            DedicatedFundManager.parseStructuredMedicalNote(tx.note)
        } else {
            null
        }
        val discountHint = when {
            isCompositeMain && parsedMedNote != null && parsedMedNote.personalAccountCents > 0L -> {
                val origCents = parsedMedNote.originalCostCents.takeIf { it > 0L }
                    ?: (tx.amountCents + tx.discountCents + parsedMedNote.personalAccountCents)
                val origStr = String.format(Locale.US, "%.2f", origCents / 100.0)
                val personalStr = String.format(Locale.US, "%.2f", parsedMedNote.personalAccountCents / 100.0)
                val poolCents = parsedMedNote.poolSubsidyCents
                val commercialCents = (tx.discountCents - poolCents).coerceAtLeast(0L)
                val poolPart = if (poolCents > 0L) {
                    "·统筹$symbol${String.format(Locale.US, "%.2f", poolCents / 100.0)}"
                } else {
                    ""
                }
                val commercialPart = if (commercialCents > 0L) {
                    "·优惠$symbol${String.format(Locale.US, "%.2f", commercialCents / 100.0)}"
                } else {
                    ""
                }
                "组合双扣·原价$symbol$origStr$poolPart$commercialPart·个账$symbol$personalStr·自付$symbol$amtStr · "
            }
            tx.type == TransactionType.EXPENSE && tx.discountCents > 0L -> {
                val origStr = String.format(Locale.US, "%.2f", (tx.amountCents + tx.discountCents) / 100.0)
                val discStr = String.format(Locale.US, "%.2f", tx.discountCents / 100.0)
                "原价$symbol$origStr·优惠$symbol$discStr·实扣$symbol$amtStr · "
            }
            else -> ""
        }
        val subtitle = "${discountHint}${tx.category} · $merchantPart · $walletName · $occurredShort"

        uiGateway.showRecordedBanner(
            statusTitle = statusText,
            amountText = amountText,
            subtitle = subtitle,
            isIncome = isIncome,
            transactionId = tx.id
        )
    }

    private fun showSuspectedDuplicateBanner(
        candidateId: Long,
        tx: TransactionEntity,
        walletName: String,
        duplicateReason: String,
        matchedTransactionId: Long? = null
    ) {
        val sign = when (tx.type) {
            TransactionType.INCOME, TransactionType.REFUND_OFFSET -> "+"
            TransactionType.EXPENSE -> "-"
            else -> ""
        }
        val symbol = CurrencyCode.fromCode(tx.currency).symbol
        val amtStr = String.format(Locale.US, "%.2f", tx.amountCents / 100.0)
        val merchantPart = tx.merchant.ifBlank { tx.category }
        val subtitle = "$merchantPart · $duplicateReason · 默认未录入"

        uiGateway.showSuspectedDuplicateBanner(
            amountText = "$sign$symbol$amtStr",
            subtitle = subtitle,
            walletName = walletName,
            matchedTransactionId = matchedTransactionId,
            onForceRecord = {
                scope.launch {
                    forceRecordDuplicateCandidate(candidateId)
                }
            }
        )
    }

    private fun extractQuickTransactionNo(text: String): String? {
        return txNumberRegex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.length >= 10 }
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

    private fun extractCapturedActivity(rawText: String): String =
        CAPTURED_ACTIVITY_TAG_REGEX.find(rawText)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    private fun recordPreFilterNoiseTrace(
        rawText: String,
        packageName: String,
        captureSource: String,
        capturedAtMillis: Long,
        cashierVisitId: Long,
        verifiedActivity: String,
        upstreamContext: UpstreamWindowCaptureContext?,
        ruleCode: String,
        reasonSummary: String
    ) {
        val store = debugTraceStore ?: return
        if (!store.isEnabled()) return
        val effectiveAct = upstreamContext?.verifiedActivity?.takeIf { it.isNotBlank() } ?: verifiedActivity
        val entry = CaptureDebugTraceEntry(
            timestampMillis = capturedAtMillis,
            outcome = DebugTraceOutcome.NOISE_IGNORED,
            headline = "前置噪音过滤 · $reasonSummary",
            sourceCapture = SourceCaptureStage(
                captureSource = captureSource,
                packageName = packageName,
                verifiedActivity = effectiveAct,
                structureFingerprint = upstreamContext?.structureFingerprint.orEmpty(),
                capturedAtMillis = capturedAtMillis,
                rawTextPreview = rawText.take(CaptureDebugTraceManager.MAX_RAW_TEXT_PREVIEW_CHARS)
            ),
            windowSessionGuard = buildWindowSessionGuardStage(
                captureSource = captureSource,
                packageName = packageName,
                upstreamContext = upstreamContext,
                visitTag = if (cashierVisitId > 0L) cashierVisitId.toString() else ""
            ),
            parseEngineRoute = ParseEngineRouteStage(
                routeSelected = "PRE_FILTER_SKIPPED",
                fallbackOrSkipReason = reasonSummary
            ),
            fieldExtraction = FieldExtractionStage(
                directionType = "IGNORED",
                amountRuleBranch = reasonSummary
            ),
            categoryWalletMapping = CategoryWalletMappingStage(
                categoryDecisionSource = "SKIPPED_NOISE",
                walletMatchBasis = "前置噪音过滤未进入账户匹配"
            ),
            pipelineDedupFusion = PipelineDedupFusionStage(
                slidingWindowBatchSize = 1,
                slidingWindowMergedSources = listOf("$captureSource($packageName)"),
                matchedRuleId = ruleCode,
                finalSummary = reasonSummary
            )
        )
        store.appendTrace(entry)
    }

    private fun buildWindowSessionGuardStage(
        captureSource: String,
        packageName: String,
        upstreamContext: UpstreamWindowCaptureContext?,
        visitTag: String
    ): WindowSessionGuardStage {
        if (upstreamContext != null) {
            return WindowSessionGuardStage(
                whitelistAllowed = upstreamContext.whitelistAllowed,
                whitelistSummary = upstreamContext.whitelistSummary,
                containerRole = upstreamContext.containerRole,
                guardAccepted = true,
                guardRuleName = upstreamContext.guardRuleName,
                cashierVisitId = upstreamContext.cashierVisitId,
                sessionAction = upstreamContext.sessionAction,
                sessionResetReason = upstreamContext.sessionResetReason,
                stablePageDedupHit = upstreamContext.stablePageDedupHit
            )
        }
        val numericVisit = visitTag.toLongOrNull() ?: 0L
        return when (captureSource) {
            "AI_NOTIFICATION" -> WindowSessionGuardStage(
                whitelistAllowed = true,
                whitelistSummary = "通知栏应用白名单放行($packageName)",
                containerRole = "NOTIFICATION_CHANNEL",
                guardAccepted = true,
                guardRuleName = "NOTIFICATION_WHITELIST_PASS",
                cashierVisitId = 0L,
                sessionAction = "NOTIFICATION_DIRECT"
            )
            "AI_SANDBOX" -> WindowSessionGuardStage(
                whitelistAllowed = true,
                whitelistSummary = "沙盒测试通道直通",
                containerRole = "SANDBOX_CHANNEL",
                guardAccepted = true,
                guardRuleName = "SANDBOX_DIRECT_PASS",
                cashierVisitId = numericVisit,
                sessionAction = "SANDBOX_SIMULATION"
            )
            else -> WindowSessionGuardStage(
                whitelistAllowed = true,
                whitelistSummary = "页面白名单已放行($packageName)",
                containerRole = if (captureSource == "AI_LIVE_CASHIER") "DEDICATED_PAYMENT_OR_BILL" else "ACCESSIBILITY_WINDOW",
                guardAccepted = true,
                guardRuleName = if (captureSource == "AI_LIVE_CASHIER") "ACCEPTED_LIVE_CASHIER" else "ACCEPTED_BILL_WINDOW",
                cashierVisitId = numericVisit,
                sessionAction = if (numericVisit > 0L) "VISIT_$numericVisit" else "PAGE_DISPATCHED"
            )
        }
    }

    private fun buildDetailedFusionDiff(
        before: TransactionEntity,
        after: TransactionEntity,
        enrichedFields: List<String>,
        wallets: List<WalletEntity>
    ): List<String> {
        val diffs = ArrayList<String>(enrichedFields.size + 4)
        val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
        fun walletLabel(id: Long?): String {
            if (id == null) return "null(不关联)"
            val w = wallets.firstOrNull { it.id == id } ?: return "#$id"
            return if (!w.cardLast4.isNullOrBlank()) "#$id(${w.name}·${w.cardLast4})" else "#$id(${w.name})"
        }
        if (before.captureSource != after.captureSource) {
            diffs.add("captureSource: ${before.captureSource} -> ${after.captureSource}")
        }
        if (before.amountCents != after.amountCents || before.discountCents != after.discountCents) {
            diffs.add("amount/discount: ${before.amountCents}分(优惠${before.discountCents}) -> ${after.amountCents}分(优惠${after.discountCents})")
        }
        if (before.merchant != after.merchant) {
            diffs.add("merchant: ${before.merchant.ifBlank { "(空)" }} -> ${after.merchant}")
        }
        if (before.category != after.category) {
            diffs.add("category: ${before.category} -> ${after.category}")
        }
        if (before.orderKey != after.orderKey) {
            diffs.add("orderKey: ${before.orderKey.ifBlank { "(空)" }} -> ${after.orderKey}")
        }
        if (before.transactionNo != after.transactionNo) {
            diffs.add("transactionNo: ${before.transactionNo.ifBlank { "(空)" }} -> ${after.transactionNo}")
        }
        if (before.merchantOrderNo != after.merchantOrderNo) {
            diffs.add("merchantOrderNo: ${before.merchantOrderNo.ifBlank { "(空)" }} -> ${after.merchantOrderNo}")
        }
        if (before.sourceWalletId != after.sourceWalletId) {
            diffs.add("sourceWalletId: ${walletLabel(before.sourceWalletId)} -> ${walletLabel(after.sourceWalletId)}")
        }
        if (before.paymentMethodRaw != after.paymentMethodRaw) {
            diffs.add("paymentMethodRaw: ${before.paymentMethodRaw.ifBlank { "(空)" }} -> ${after.paymentMethodRaw}")
        }
        if (before.timestamp != after.timestamp) {
            diffs.add("timestamp: ${timeFmt.format(Date(before.timestamp))} -> ${timeFmt.format(Date(after.timestamp))}")
        }
        if (before.note != after.note) {
            diffs.add("note: ${before.note.take(40)} -> ${after.note.take(40)}")
        }
        if (diffs.isEmpty() && enrichedFields.isNotEmpty()) {
            diffs.add("补全字段: ${enrichedFields.joinToString("、")}")
        }
        return diffs
    }

    private fun recordPipelineDebugTrace(
        outcome: DebugTraceOutcome,
        headline: String,
        mergedText: String,
        packageName: String,
        captureSource: String,
        capturedAtMillis: Long,
        screenshotPath: String,
        screenshotSha256: String,
        upstreamContext: UpstreamWindowCaptureContext?,
        visitTag: String,
        traceCollector: AiParseTraceCollector,
        finalTx: TransactionEntity?,
        smartRuleSummary: String,
        smartRuleOverrodeCategory: Boolean,
        batchSize: Int,
        mergedSourceLabels: List<String>,
        dbCandidatesInspected: List<String>,
        matchedRuleId: String,
        matchedCandidateId: Long?,
        fusedFieldsDiff: List<String>,
        finalTransactionId: Long?,
        finalSummary: String
    ) {
        val store = debugTraceStore ?: return
        if (!store.isEnabled()) return
        val effectiveAct = upstreamContext?.verifiedActivity?.takeIf { it.isNotBlank() }
            ?: extractCapturedActivity(mergedText)
        val shotRef = if (screenshotPath.isNotBlank()) File(screenshotPath).name else ""

        val baseField = traceCollector.fieldStage
        val enrichedField = if (finalTx != null) {
            baseField.copy(
                actualPaidCents = finalTx.amountCents,
                discountCents = maxOf(baseField.discountCents, finalTx.discountCents),
                directionType = finalTx.type.name,
                linkedExpenseId = finalTx.relatedTransactionId ?: baseField.linkedExpenseId,
                merchantSummary = baseField.merchantSummary.ifBlank { finalTx.merchant },
                orderNoSummary = baseField.orderNoSummary.ifBlank {
                    "orderKey=${finalTx.orderKey.ifBlank { "无" }}, txNo=${finalTx.transactionNo.ifBlank { "无" }}, mchNo=${finalTx.merchantOrderNo.ifBlank { "无" }}"
                }
            )
        } else {
            baseField
        }

        val baseMapping = traceCollector.mappingStage
        val enrichedMapping = baseMapping.copy(
            finalCategory = finalTx?.category ?: baseMapping.finalCategory,
            categoryDecisionSource = when {
                smartRuleOverrodeCategory -> "SMART_RULE"
                baseMapping.categoryDecisionSource.isNotBlank() -> baseMapping.categoryDecisionSource
                else -> "DEFAULT_CATEGORY"
            },
            smartRuleSummary = smartRuleSummary.ifBlank { baseMapping.smartRuleSummary },
            finalWalletId = finalTx?.let { it.sourceWalletId ?: it.targetWalletId } ?: baseMapping.finalWalletId
        )

        val resolvedOrderKey = finalTx?.let {
            it.orderKey.ifBlank { it.transactionNo }.ifBlank { it.merchantOrderNo }
        }.orEmpty()

        val entry = CaptureDebugTraceEntry(
            timestampMillis = capturedAtMillis,
            outcome = outcome,
            transactionId = finalTransactionId,
            orderKey = resolvedOrderKey,
            merchant = finalTx?.merchant.orEmpty(),
            amountCents = finalTx?.amountCents ?: enrichedField.actualPaidCents,
            currency = finalTx?.currency ?: "CNY",
            headline = headline,
            sourceCapture = SourceCaptureStage(
                captureSource = captureSource,
                packageName = packageName,
                verifiedActivity = effectiveAct,
                structureFingerprint = upstreamContext?.structureFingerprint.orEmpty(),
                capturedAtMillis = capturedAtMillis,
                rawTextPreview = mergedText.take(CaptureDebugTraceManager.MAX_RAW_TEXT_PREVIEW_CHARS),
                screenshotRef = shotRef,
                screenshotSha256 = screenshotSha256
            ),
            windowSessionGuard = buildWindowSessionGuardStage(
                captureSource = captureSource,
                packageName = packageName,
                upstreamContext = upstreamContext,
                visitTag = visitTag
            ),
            parseEngineRoute = traceCollector.routeStage,
            fieldExtraction = enrichedField,
            categoryWalletMapping = enrichedMapping,
            pipelineDedupFusion = PipelineDedupFusionStage(
                slidingWindowBatchSize = batchSize,
                slidingWindowMergedSources = mergedSourceLabels,
                dbCandidatesInspected = dbCandidatesInspected,
                matchedRuleId = matchedRuleId,
                matchedCandidateId = matchedCandidateId,
                fusedFieldsDiff = fusedFieldsDiff,
                finalTransactionId = finalTransactionId,
                finalSummary = finalSummary
            )
        )
        store.appendTrace(entry)
    }

    companion object {
        private val CASHIER_VISIT_TAG_REGEX = Regex(
            """\[AI_[A-Z0-9_]+\s*\|\s*([^|\]]+?)\s*\|\s*visit=(\d+)(?:\s*\|[^\]]*)?\]:"""
        )
        private val CAPTURED_ACTIVITY_TAG_REGEX = Regex(
            """\[AI_[A-Z0-9_]+\s*\|[^\]]*\bact=([^|\]]+?)(?:\s*\|[^\]]*)?\]:"""
        )
        private val WECHAT_OFFICIAL_BILL_TX_NO_REGEX = Regex(
            """^(?:42000\d{18,28}|1000\d{20,32})$"""
        )
        private val EXPLICIT_SECOND_TIME_REGEX = Regex(
            """\b\d{1,2}:\d{2}:\d{2}\b|\d{1,2}时\d{1,2}分\d{1,2}秒"""
        )
        private val EXPLICIT_CARD_TAIL4_REGEX = Regex(
            """[(（]\s*(\d{4})\s*[)）]|(?:尾号|储蓄卡|借记卡|信用卡)\s*(\d{4})(?!\d)"""
        )

        private fun buildTaggedCaptureLine(
            captureSource: String,
            packageName: String,
            cashierVisitId: Long,
            verifiedActivity: String,
            cleanedText: String
        ): String {
            val cleanAct = verifiedActivity.trim()
            return buildString {
                append("[$captureSource | $packageName")
                if (cashierVisitId > 0L) {
                    append(" | visit=$cashierVisitId")
                }
                if (cleanAct.isNotEmpty()) {
                    append(" | act=$cleanAct")
                }
                append("]: ")
                append(cleanedText)
            }
        }

        private fun extractProductDetailFromStructuredNote(
            note: String,
            merchant: String,
            payMethod: String,
            genericPayMethods: Set<String>
        ): String {
            val trimmed = note.trim()
            if (trimmed.isEmpty() || trimmed.contains("[用户手动确认录入]")) return ""
            val body = if (trimmed.startsWith("原价¥")) {
                if (trimmed.contains(" | ")) trimmed.substringAfter(" | ").trim() else return ""
            } else {
                trimmed
            }
            val payLikeRegex = Regex(""".*(?:银行|信用卡|储蓄卡|借记卡|一卡通|个人消费卡|零钱|余额宝|花呗|白条|月付).*""")
            val parts = body.split(" · ")
                .map { it.trim() }
                .filter { part ->
                    part.isNotEmpty() &&
                        !part.equals(payMethod.trim(), ignoreCase = true) &&
                        part !in genericPayMethods &&
                        !part.equals(merchant.trim(), ignoreCase = true) &&
                        !part.startsWith("商户单号") &&
                        !part.startsWith("订单编号") &&
                        !payLikeRegex.matches(part)
                }
            return parts.joinToString(" · ").trim()
        }

        private fun mergeStructuredNoteWithProductAndPayMethod(
            preferredHeaderNote: String,
            productDetail: String,
            payMethod: String
        ): String {
            val trimmedHeaderSource = preferredHeaderNote.trim()
            val header = if (trimmedHeaderSource.startsWith("原价¥")) {
                trimmedHeaderSource.substringBefore(" | ").trim()
            } else {
                ""
            }
            val tailParts = buildList {
                if (productDetail.isNotBlank()) add(productDetail.trim())
                if (payMethod.isNotBlank() && payMethod.length <= 24) add(payMethod.trim())
            }
            val tail = tailParts.joinToString(" · ")
            return when {
                header.isNotBlank() && tail.isNotBlank() -> "$header | $tail".take(96)
                header.isNotBlank() -> header
                tail.isNotBlank() -> tail.take(96)
                else -> trimmedHeaderSource
            }
        }

        internal fun mergeEnrichedTags(existingTags: String, incomingTags: String): Pair<String, List<String>> {
            val existingTagList = TagCatalog.parseTags(existingTags)
            val incomingTagList = TagCatalog.parseTags(incomingTags)
            val newTagsAdded = incomingTagList.filter { inc ->
                existingTagList.none { it.equals(inc, ignoreCase = true) }
            }
            return if (newTagsAdded.isNotEmpty()) {
                TagCatalog.joinTags(existingTagList + incomingTagList) to newTagsAdded
            } else {
                existingTags to emptyList()
            }
        }

        private fun defaultDiagnosticSeedLogs(): List<CaptureObservabilityLogEntry> {
            val now = System.currentTimeMillis()
            return listOf(
                CaptureObservabilityLogEntry(
                    id = 1L,
                    timestampMillis = now - 180_000L,
                    packageName = "com.eg.android.AlipayGphone",
                    captureSource = "AI_ACCESSIBILITY",
                    rawText = "账单详情 | 盒马 | -49.76 | 支付时间 | 2026-11-07 09:06:06 | 订单号 | 2026110723001163691456808770",
                    outcome = CaptureEventOutcome.DUPLICATE_SUSPECTED,
                    detailSummary = "检测到疑似重复（默认未录入）：订单Key重复(56808770)，匹配账单 #27（盒马 ¥49.76）"
                ),
                CaptureObservabilityLogEntry(
                    id = 2L,
                    timestampMillis = now - 420_000L,
                    packageName = "com.tencent.mm",
                    captureSource = "AI_ACCESSIBILITY",
                    rawText = "账单详情 | 山东高速 | -7.51 | 支付时间 | 2026-11-05 16:34:54 | 交易单号 | 4200002975202611059216731849",
                    outcome = CaptureEventOutcome.RECORDED,
                    detailSummary = "[支出] 交通-高速过路 · 山东高速 (7.51 CNY · 1件) -> 储蓄卡 · Key:731849"
                )
            )
        }
    }
}
