package com.moji.ai.service

import android.content.Context
import android.content.SharedPreferences
import com.moji.ai.ai.AiConfigManager
import com.moji.ai.data.backup.MoJiCloudSyncClient
import com.moji.ai.data.backup.WebDavSyncClient
import com.moji.ai.data.local.CaptureEvidenceEntity
import com.moji.ai.data.local.TransactionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil

/**
 * 识账调试日志 4 档空间配额定义（AC-4）：
 * - 双阈值约束：文件字节上限 (`maxBytes`) 与总条数上限 (`maxEntries`)；
 * - 任一指标触顶时按时间戳 FIFO 淘汰最旧 20% 并原子重写，绝不停止记录。
 */
@Serializable
enum class DebugTraceQuotaTier(
    val prefValue: String,
    val label: String,
    val displayLabel: String,
    val maxBytes: Long,
    val maxEntries: Int,
    val isDefault: Boolean = false
) {
    MB_1(
        prefValue = "MB_1",
        label = "1 MB",
        displayLabel = "1 MB",
        maxBytes = 1L * 1024L * 1024L,
        maxEntries = 300
    ),
    MB_5(
        prefValue = "MB_5",
        label = "5 MB",
        displayLabel = "5 MB (默认)",
        maxBytes = 5L * 1024L * 1024L,
        maxEntries = 1_500,
        isDefault = true
    ),
    MB_10(
        prefValue = "MB_10",
        label = "10 MB",
        displayLabel = "10 MB",
        maxBytes = 10L * 1024L * 1024L,
        maxEntries = 3_000
    ),
    MB_20(
        prefValue = "MB_20",
        label = "20 MB",
        displayLabel = "20 MB",
        maxBytes = 20L * 1024L * 1024L,
        maxEntries = 6_000
    );

    companion object {
        fun fromPrefValue(raw: String?): DebugTraceQuotaTier =
            entries.firstOrNull { it.prefValue == raw || it.name == raw } ?: MB_5
    }
}

/**
 * 调试推理轨迹最终落点分类（AC-2 / AC-3）
 */
@Serializable
enum class DebugTraceOutcome(val badgeLabel: String) {
    RECORDED("已记账"),
    REFUND_OFFSET("退货冲抵"),
    FUSED_PATCH("跨道融合"),
    DEDUP_BLOCKED("防重拦截"),
    DUPLICATE_SUSPECTED("疑似重复"),
    NOISE_IGNORED("噪音忽略"),
    WINDOW_GUARD_BLOCKED("窗口拦截")
}

/**
 * 阶段 1：原始来源采集（SOURCE_CAPTURE）
 */
@Serializable
data class SourceCaptureStage(
    val captureSource: String = "",
    val packageName: String = "",
    val verifiedActivity: String = "",
    val structureFingerprint: String = "",
    val capturedAtMillis: Long = 0L,
    val rawTextPreview: String = "",
    val screenshotRef: String = "",
    val screenshotSha256: String = ""
)

/**
 * 阶段 2：窗口门禁与会话追踪（WINDOW_SESSION_GUARD）
 */
@Serializable
data class WindowSessionGuardStage(
    val whitelistAllowed: Boolean = true,
    val whitelistSummary: String = "",
    val containerRole: String = "",
    val guardAccepted: Boolean = true,
    val guardRuleName: String = "",
    val cashierVisitId: Long = 0L,
    val sessionAction: String = "",
    val sessionResetReason: String = "",
    val stablePageDedupHit: Boolean = false,
    val suppressedRepeatCount: Int = 0
)

/**
 * 阶段 3：解析引擎路由（PARSE_ENGINE_ROUTE）
 */
@Serializable
data class ParseEngineRouteStage(
    val routeSelected: String = "",
    val provider: String = "",
    val modelName: String = "",
    val maskedEndpoint: String = "",
    val latencyMs: Long = 0L,
    val llmResponseSummary: String = "",
    val fallbackOrSkipReason: String = ""
)

/**
 * 阶段 4：结构化字段提取与方向清洗（FIELD_EXTRACTION）
 */
@Serializable
data class FieldExtractionStage(
    val candidateAmounts: List<String> = emptyList(),
    val amountRuleBranch: String = "",
    val actualPaidCents: Long = 0L,
    val originalPriceCents: Long = 0L,
    val discountCents: Long = 0L,
    val directionType: String = "",
    val strippedSafeguardPhrases: List<String> = emptyList(),
    val matchedDirectionKeywords: List<String> = emptyList(),
    val linkedExpenseId: Long? = null,
    val merchantSummary: String = "",
    val orderNoSummary: String = "",
    val occurredAtSummary: String = ""
)

/**
 * 阶段 5：分类决策与账户映射（CATEGORY_WALLET_MAPPING）
 */
@Serializable
data class CategoryWalletMappingStage(
    val finalCategory: String = "",
    val categoryDecisionSource: String = "",
    val smartRuleSummary: String = "",
    val merchantMemorySummary: String = "",
    val weightedKeywordsHit: List<String> = emptyList(),
    val weightedConfidence: Float = 0f,
    val finalWalletId: Long? = null,
    val finalWalletName: String = "",
    val walletMatchBasis: String = ""
)

/**
 * 阶段 6：防重与跨通道融合管线（PIPELINE_DEDUP_FUSION）
 */
@Serializable
data class PipelineDedupFusionStage(
    val slidingWindowBatchSize: Int = 1,
    val slidingWindowMergedSources: List<String> = emptyList(),
    val dbCandidatesInspected: List<String> = emptyList(),
    val matchedRuleId: String = "",
    val matchedCandidateId: Long? = null,
    val fusedFieldsDiff: List<String> = emptyList(),
    val finalTransactionId: Long? = null,
    val finalSummary: String = ""
)

/**
 * 单条 6 阶段全链路推理日志实体（落盘于 `filesDir/diagnostics/capture_debug_traces.jsonl`）
 */
@Serializable
data class CaptureDebugTraceEntry(
    val traceId: String = UUID.randomUUID().toString(),
    val timestampMillis: Long = System.currentTimeMillis(),
    val outcome: DebugTraceOutcome = DebugTraceOutcome.NOISE_IGNORED,
    val transactionId: Long? = null,
    val orderKey: String = "",
    val merchant: String = "",
    val amountCents: Long = 0L,
    val currency: String = "CNY",
    val headline: String = "",
    val sourceCapture: SourceCaptureStage = SourceCaptureStage(),
    val windowSessionGuard: WindowSessionGuardStage = WindowSessionGuardStage(),
    val parseEngineRoute: ParseEngineRouteStage = ParseEngineRouteStage(),
    val fieldExtraction: FieldExtractionStage = FieldExtractionStage(),
    val categoryWalletMapping: CategoryWalletMappingStage = CategoryWalletMappingStage(),
    val pipelineDedupFusion: PipelineDedupFusionStage = PipelineDedupFusionStage()
)

/**
 * 无障碍前置阶段向协调器透传的真实窗口骨架指纹与会话守卫快照
 */
data class UpstreamWindowCaptureContext(
    val verifiedActivity: String = "",
    val structureFingerprint: String = "",
    val whitelistAllowed: Boolean = true,
    val whitelistSummary: String = "",
    val containerRole: String = "",
    val guardRuleName: String = "",
    val cashierVisitId: Long = 0L,
    val sessionAction: String = "",
    val sessionResetReason: String = "",
    val stablePageDedupHit: Boolean = false
)

/**
 * 调试模式实时容量与开关状态快照（供设置页响应式渲染）
 */
data class DebugTraceStorageStats(
    val enabled: Boolean = false,
    val tier: DebugTraceQuotaTier = DebugTraceQuotaTier.MB_5,
    val usedBytes: Long = 0L,
    val entryCount: Int = 0,
    val oldestTimestampMillis: Long = 0L
) {
    val usedMbText: String
        get() = String.format(Locale.US, "%.2f", usedBytes.toDouble() / (1024.0 * 1024.0))

    val maxMbText: String
        get() = String.format(Locale.US, "%.2f", tier.maxBytes.toDouble() / (1024.0 * 1024.0))

    val usageFraction: Float
        get() {
            val byteRatio = if (tier.maxBytes > 0L) usedBytes.toFloat() / tier.maxBytes.toFloat() else 0f
            val countRatio = if (tier.maxEntries > 0) entryCount.toFloat() / tier.maxEntries.toFloat() else 0f
            return maxOf(byteRatio, countRatio).coerceIn(0f, 1f)
        }
}

/**
 * 独立本地调试偏好抽象：
 * 生产环境固定使用私有 `moji_debug_trace_prefs`（严禁注册到云同步监听，确保开关切换零云同步副作用）。
 */
interface DebugTraceConfigStore {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
    fun getQuotaTier(): DebugTraceQuotaTier
    fun setQuotaTier(tier: DebugTraceQuotaTier)
}

class SharedPrefsDebugTraceConfigStore(
    private val prefs: SharedPreferences
) : DebugTraceConfigStore {
    override fun isEnabled(): Boolean = prefs.getBoolean(KEY_DEBUG_TRACE_ENABLED, false)

    override fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_TRACE_ENABLED, enabled).apply()
    }

    override fun getQuotaTier(): DebugTraceQuotaTier =
        DebugTraceQuotaTier.fromPrefValue(prefs.getString(KEY_DEBUG_TRACE_QUOTA_TIER, DebugTraceQuotaTier.MB_5.prefValue))

    override fun setQuotaTier(tier: DebugTraceQuotaTier) {
        prefs.edit().putString(KEY_DEBUG_TRACE_QUOTA_TIER, tier.prefValue).apply()
    }

    companion object {
        const val PREFS_NAME = "moji_debug_trace_prefs"
        const val KEY_DEBUG_TRACE_ENABLED = "debug_trace_enabled"
        const val KEY_DEBUG_TRACE_QUOTA_TIER = "debug_trace_quota_tier"
    }
}

/**
 * 核心调试推理轨迹环形持久化管理器（支持注入临时目录、假配置与可控时钟做确定性单元测试）
 */
class CaptureDebugTraceManager(
    private val baseDir: File,
    private val configStore: DebugTraceConfigStore,
    private val secretProvider: () -> Collection<String> = { emptyList() },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    val logFile: File
        get() = File(File(baseDir, DIAGNOSTICS_DIR_NAME), TRACE_FILE_NAME)

    private val fileLock = Any()

    private data class WindowBlockDebounceState(
        val traceId: String,
        val firstSeenMillis: Long,
        var suppressedCount: Int
    )

    private val windowBlockDebounceMap = LinkedHashMap<String, WindowBlockDebounceState>()
    private val suppressedRepeatOverrides = LinkedHashMap<String, Int>()

    private val _statsFlow = MutableStateFlow(
        DebugTraceStorageStats(
            enabled = configStore.isEnabled(),
            tier = configStore.getQuotaTier()
        )
    )
    val statsFlow: StateFlow<DebugTraceStorageStats> = _statsFlow.asStateFlow()

    init {
        refreshStatsFromDisk()
    }

    fun isEnabled(): Boolean = configStore.isEnabled()

    fun getQuotaTier(): DebugTraceQuotaTier = configStore.getQuotaTier()

    /**
     * 切换调试模式开关（AC-1）：
     * - 关闭时仅停止新写入并清空内存去抖缓存，绝不删除已落盘的历史 `.jsonl` 日志文件；
     * - 开启时恢复写入并刷新统计。
     */
    fun setEnabled(enabled: Boolean) {
        synchronized(fileLock) {
            configStore.setEnabled(enabled)
            if (!enabled) {
                windowBlockDebounceMap.clear()
            }
            refreshStatsLocked()
        }
    }

    /**
     * 切换空间档位（AC-4）：
     * 若从大档位切到小档位（如 `20 MB -> 1 MB`）且现有日志超出新上限，立即触发 FIFO 20% 裁剪并刷新占用统计。
     */
    fun setQuotaTier(tier: DebugTraceQuotaTier) {
        synchronized(fileLock) {
            configStore.setQuotaTier(tier)
            if (logFile.exists()) {
                val entries = readValidEntriesFromDiskLocked()
                val currentBytes = logFile.length()
                if (entries.size > tier.maxEntries || currentBytes > tier.maxBytes) {
                    val trimmed = trimEntriesToFitTier(entries, tier)
                    writeEntriesAtomicallyLocked(trimmed)
                }
            }
            refreshStatsLocked()
        }
    }

    /**
     * 清空全部落盘推理日志（需 UI 二次确认后调用，AC-1.3）
     */
    fun clearAllTraces() {
        synchronized(fileLock) {
            windowBlockDebounceMap.clear()
            suppressedRepeatOverrides.clear()
            val target = logFile
            val tmp = File(target.parentFile, "${target.name}.tmp")
            if (tmp.exists()) {
                runCatching { tmp.delete() }
            }
            if (target.exists()) {
                runCatching { target.delete() }
            }
            _statsFlow.value = DebugTraceStorageStats(
                enabled = configStore.isEnabled(),
                tier = configStore.getQuotaTier(),
                usedBytes = 0L,
                entryCount = 0,
                oldestTimestampMillis = 0L
            )
        }
    }

    /**
     * 追加一条 6 阶段完整推理轨迹（AC-1 / AC-2 / AC-4）：
     * - 关闭态下严格零磁盘写入、零目录创建；
     * - 落盘前 100% 强制脱敏 API Key / Bearer Token / SyncKey / WebDAV 密码并截断超长原文。
     */
    fun appendTrace(entry: CaptureDebugTraceEntry): Boolean {
        if (!configStore.isEnabled()) {
            return false
        }
        synchronized(fileLock) {
            if (!configStore.isEnabled()) {
                return false
            }
            val tier = configStore.getQuotaTier()
            val sanitizedEntry = sanitizeEntry(entry)
            val encodedLine = jsonCodec.encodeToString(sanitizedEntry) + "\n"
            val lineBytes = encodedLine.toByteArray(Charsets.UTF_8)

            val target = logFile
            val existingBytes = if (target.exists()) target.length() else 0L
            val currentStats = _statsFlow.value
            val currentCount = if (target.exists()) currentStats.entryCount else 0

            val exceedsSingleLineCap = lineBytes.size.toLong() > tier.maxBytes
            if (exceedsSingleLineCap) {
                return false
            }

            val wouldExceedCount = (currentCount + 1) > tier.maxEntries
            val wouldExceedBytes = (existingBytes + lineBytes.size.toLong()) > tier.maxBytes

            if (!wouldExceedCount && !wouldExceedBytes) {
                target.parentFile?.mkdirs()
                FileOutputStream(target, true).use { fos ->
                    fos.write(lineBytes)
                    fos.flush()
                }
                val newUsedBytes = target.length()
                val newCount = currentCount + 1
                val newOldest = if (currentCount == 0 || currentStats.oldestTimestampMillis <= 0L) {
                    sanitizedEntry.timestampMillis
                } else {
                    minOf(currentStats.oldestTimestampMillis, sanitizedEntry.timestampMillis)
                }
                _statsFlow.value = DebugTraceStorageStats(
                    enabled = true,
                    tier = tier,
                    usedBytes = newUsedBytes,
                    entryCount = newCount,
                    oldestTimestampMillis = newOldest
                )
            } else {
                val allEntries = readValidEntriesFromDiskLocked().toMutableList()
                allEntries.add(sanitizedEntry)
                val trimmed = trimEntriesToFitTier(allEntries, tier)
                writeEntriesAtomicallyLocked(trimmed)
                refreshStatsLocked(preloadedEntries = trimmed)
            }
            return true
        }
    }

    /**
     * 记录前置窗口守卫或会话/页面防重追踪器拦截事件，并实施 15s 防刷屏静默去抖（AC-3）：
     * - 忽略系统桌面、自身应用及纯聊天列表/朋友圈/聊天输入框切换；
     * - 同一 `packageName + structureFingerprint + guardRuleName` 在 15 秒内重复触发时合并计数，不产生重复追加写入。
     */
    fun recordWindowGuardBlocked(
        packageName: String,
        verifiedActivity: String,
        structureFingerprint: String,
        whitelistAllowed: Boolean,
        whitelistSummary: String,
        containerRole: String,
        guardRuleName: String,
        detailReason: String,
        rawTextSample: String = "",
        cashierVisitId: Long = 0L,
        sessionAction: String = "",
        sessionResetReason: String = "",
        stablePageDedupHit: Boolean = false,
        capturedAtMillis: Long = clock()
    ): Boolean {
        if (!configStore.isEnabled()) {
            return false
        }
        if (shouldIgnoreNoiseWindowSwitch(
                packageName = packageName,
                verifiedActivity = verifiedActivity,
                containerRole = containerRole,
                guardRuleName = guardRuleName,
                rawTextSample = rawTextSample
            )
        ) {
            return false
        }

        synchronized(fileLock) {
            if (!configStore.isEnabled()) {
                return false
            }
            val now = capturedAtMillis
            val expiredIter = windowBlockDebounceMap.entries.iterator()
            while (expiredIter.hasNext()) {
                val item = expiredIter.next()
                if (now - item.value.firstSeenMillis >= WINDOW_BLOCK_DEBOUNCE_MS) {
                    expiredIter.remove()
                }
            }

            val fpOrAct = structureFingerprint.ifBlank { verifiedActivity.ifBlank { "unknown_window" } }
            val debounceKey = "$packageName|$fpOrAct|$guardRuleName"
            val activeDebounce = windowBlockDebounceMap[debounceKey]
            if (activeDebounce != null && now - activeDebounce.firstSeenMillis < WINDOW_BLOCK_DEBOUNCE_MS) {
                activeDebounce.suppressedCount += 1
                suppressedRepeatOverrides[activeDebounce.traceId] = activeDebounce.suppressedCount
                if (suppressedRepeatOverrides.size > 512) {
                    val eldestKey = suppressedRepeatOverrides.keys.firstOrNull()
                    if (eldestKey != null) suppressedRepeatOverrides.remove(eldestKey)
                }
                return false
            }

            val traceId = UUID.randomUUID().toString()
            windowBlockDebounceMap[debounceKey] = WindowBlockDebounceState(
                traceId = traceId,
                firstSeenMillis = now,
                suppressedCount = 0
            )
            if (windowBlockDebounceMap.size > 256) {
                val eldestKey = windowBlockDebounceMap.keys.firstOrNull()
                if (eldestKey != null) windowBlockDebounceMap.remove(eldestKey)
            }

            val entry = CaptureDebugTraceEntry(
                traceId = traceId,
                timestampMillis = now,
                outcome = DebugTraceOutcome.WINDOW_GUARD_BLOCKED,
                headline = "前置窗口拦截 · $guardRuleName ($detailReason)",
                sourceCapture = SourceCaptureStage(
                    captureSource = "AI_ACCESSIBILITY",
                    packageName = packageName,
                    verifiedActivity = verifiedActivity,
                    structureFingerprint = structureFingerprint,
                    capturedAtMillis = now,
                    rawTextPreview = rawTextSample.take(MAX_RAW_TEXT_PREVIEW_CHARS)
                ),
                windowSessionGuard = WindowSessionGuardStage(
                    whitelistAllowed = whitelistAllowed,
                    whitelistSummary = whitelistSummary,
                    containerRole = containerRole,
                    guardAccepted = false,
                    guardRuleName = guardRuleName,
                    cashierVisitId = cashierVisitId,
                    sessionAction = sessionAction.ifBlank { "BLOCKED_UPSTREAM" },
                    sessionResetReason = sessionResetReason,
                    stablePageDedupHit = stablePageDedupHit,
                    suppressedRepeatCount = 0
                ),
                parseEngineRoute = ParseEngineRouteStage(
                    routeSelected = "SKIPPED_UPSTREAM",
                    fallbackOrSkipReason = "前置窗口守卫拦截($guardRuleName)：$detailReason"
                ),
                fieldExtraction = FieldExtractionStage(
                    directionType = "BLOCKED",
                    amountRuleBranch = "前置拦截未进入金额解析"
                ),
                categoryWalletMapping = CategoryWalletMappingStage(
                    categoryDecisionSource = "SKIPPED_UPSTREAM",
                    walletMatchBasis = "前置拦截未进入账户匹配"
                ),
                pipelineDedupFusion = PipelineDedupFusionStage(
                    slidingWindowBatchSize = 0,
                    matchedRuleId = guardRuleName,
                    finalSummary = detailReason
                )
            )
            return appendTrace(entry)
        }
    }

    /**
     * 当异步截屏落盘完成后，为对应入账轨迹补齐截图相对路径与 SHA-256 哈希引用（不存二进制）
     */
    fun attachScreenshotRefToTransactionTrace(
        transactionId: Long,
        screenshotPath: String,
        screenshotSha256: String
    ) {
        if (!configStore.isEnabled() || transactionId <= 0L || screenshotPath.isBlank()) {
            return
        }
        synchronized(fileLock) {
            if (!configStore.isEnabled() || !logFile.exists()) return
            val entries = readValidEntriesFromDiskLocked().toMutableList()
            val targetIdx = entries.indexOfLast {
                it.transactionId == transactionId || it.pipelineDedupFusion.finalTransactionId == transactionId
            }
            if (targetIdx < 0) return
            val refName = File(screenshotPath).name
            val existing = entries[targetIdx]
            if (existing.sourceCapture.screenshotRef == refName &&
                existing.sourceCapture.screenshotSha256 == screenshotSha256
            ) {
                return
            }
            entries[targetIdx] = existing.copy(
                sourceCapture = existing.sourceCapture.copy(
                    screenshotRef = redactText(refName),
                    screenshotSha256 = screenshotSha256.take(64)
                )
            )
            writeEntriesAtomicallyLocked(entries)
            refreshStatsLocked(preloadedEntries = entries)
        }
    }

    /**
     * 读取全部有效推理轨迹（按时间倒序，最新在前；自动容错跳过损坏 JSON 行）
     */
    fun readAllTracesNewestFirst(): List<CaptureDebugTraceEntry> {
        synchronized(fileLock) {
            val entries = readValidEntriesFromDiskLocked()
            return entries.sortedByDescending { it.timestampMillis }
        }
    }

    /**
     * 查询某笔账单关联的全部多段推理轨迹（按时间升序排列，完整展示「初始入账 + 后续跨通道融合补全」全链路）
     */
    fun getTracesForTransaction(
        transactionId: Long,
        orderKey: String = ""
    ): List<CaptureDebugTraceEntry> {
        if (transactionId <= 0L && orderKey.isBlank()) return emptyList()
        synchronized(fileLock) {
            val cleanOrderKey = orderKey.trim()
            return readValidEntriesFromDiskLocked()
                .filter { entry ->
                    (transactionId > 0L && (
                        entry.transactionId == transactionId ||
                            entry.pipelineDedupFusion.finalTransactionId == transactionId ||
                            entry.pipelineDedupFusion.matchedCandidateId == transactionId
                        )) ||
                        (cleanOrderKey.isNotEmpty() && entry.orderKey == cleanOrderKey)
                }
                .sortedBy { it.timestampMillis }
        }
    }

    /**
     * 将单条轨迹编码为已脱敏的格式化 JSON（供「复制单条 JSON」使用）
     */
    fun encodeTracePrettyJson(entry: CaptureDebugTraceEntry): String =
        prettyJsonCodec.encodeToString(sanitizeEntry(entry))

    /**
     * 导出标准 `.jsonl` 诊断包到输出流（双重强制脱敏，AC-4.3 / AC-5.1）
     */
    fun exportJsonlToStream(outputStream: OutputStream): Int {
        val snapshot = synchronized(fileLock) {
            readValidEntriesFromDiskLocked().sortedBy { it.timestampMillis }
        }
        val writer = outputStream.bufferedWriter(Charsets.UTF_8)
        for (entry in snapshot) {
            val safeLine = jsonCodec.encodeToString(sanitizeEntry(entry))
            writer.write(safeLine)
            writer.write("\n")
        }
        writer.flush()
        return snapshot.size
    }

    fun redactText(raw: String): String =
        CaptureDebugTraceStore.redactSecrets(raw, secretProvider())

    fun refreshStatsFromDisk() {
        synchronized(fileLock) {
            refreshStatsLocked()
        }
    }

    private fun refreshStatsLocked(preloadedEntries: List<CaptureDebugTraceEntry>? = null) {
        val enabled = configStore.isEnabled()
        val tier = configStore.getQuotaTier()
        val target = logFile
        if (!target.exists() || target.length() <= 0L) {
            _statsFlow.value = DebugTraceStorageStats(
                enabled = enabled,
                tier = tier,
                usedBytes = 0L,
                entryCount = 0,
                oldestTimestampMillis = 0L
            )
            return
        }
        val entries = preloadedEntries ?: readValidEntriesFromDiskLocked()
        val oldest = entries.minOfOrNull { it.timestampMillis } ?: 0L
        _statsFlow.value = DebugTraceStorageStats(
            enabled = enabled,
            tier = tier,
            usedBytes = target.length(),
            entryCount = entries.size,
            oldestTimestampMillis = oldest
        )
    }

    private fun readValidEntriesFromDiskLocked(): List<CaptureDebugTraceEntry> {
        val target = logFile
        if (!target.exists()) return emptyList()
        val parsed = ArrayList<CaptureDebugTraceEntry>()
        runCatching {
            target.useLines(Charsets.UTF_8) { lines ->
                for (rawLine in lines) {
                    val trimmed = rawLine.trim()
                    if (trimmed.isEmpty()) continue
                    val decoded = runCatching {
                        jsonCodec.decodeFromString<CaptureDebugTraceEntry>(trimmed)
                    }.getOrNull() ?: continue
                    val overrideSuppressed = suppressedRepeatOverrides[decoded.traceId]
                    val merged = if (overrideSuppressed != null &&
                        overrideSuppressed > decoded.windowSessionGuard.suppressedRepeatCount
                    ) {
                        decoded.copy(
                            windowSessionGuard = decoded.windowSessionGuard.copy(
                                suppressedRepeatCount = overrideSuppressed
                            )
                        )
                    } else {
                        decoded
                    }
                    parsed.add(sanitizeEntry(merged))
                }
            }
        }
        return parsed
    }

    /**
     * 双阈值 FIFO 淘汰核心算法（AC-4.2）：
     * 按时间戳升序排列，每次淘汰最旧 20% 记录，直到条数 `<= maxEntries` 且总 UTF-8 字节数 `<= maxBytes`。
     */
    private fun trimEntriesToFitTier(
        rawEntries: List<CaptureDebugTraceEntry>,
        tier: DebugTraceQuotaTier
    ): List<CaptureDebugTraceEntry> {
        if (rawEntries.isEmpty()) return emptyList()
        val working = rawEntries.sortedBy { it.timestampMillis }.toMutableList()
        val encodedLines = working.mapTo(ArrayList(working.size)) { entry ->
            (jsonCodec.encodeToString(sanitizeEntry(entry)) + "\n").toByteArray(Charsets.UTF_8).size.toLong()
        }
        var totalBytes = encodedLines.sum()

        while (working.isNotEmpty() && (working.size > tier.maxEntries || totalBytes > tier.maxBytes)) {
            val evictCount = maxOf(1, ceil(working.size * FIFO_EVICT_RATIO).toInt())
                .coerceAtMost(working.size)
            repeat(evictCount) {
                val removedEntry = working.removeAt(0)
                val removedBytes = encodedLines.removeAt(0)
                totalBytes -= removedBytes
                suppressedRepeatOverrides.remove(removedEntry.traceId)
            }
        }
        return working
    }

    private fun writeEntriesAtomicallyLocked(entries: List<CaptureDebugTraceEntry>) {
        val target = logFile
        val parent = target.parentFile ?: baseDir
        if (!parent.exists()) {
            parent.mkdirs()
        }
        val tmpFile = File(parent, "${target.name}.tmp")
        FileOutputStream(tmpFile, false).use { fos ->
            val writer = fos.bufferedWriter(Charsets.UTF_8)
            for (entry in entries) {
                writer.write(jsonCodec.encodeToString(sanitizeEntry(entry)))
                writer.write("\n")
            }
            writer.flush()
            runCatching { fos.fd.sync() }
        }
        val moved = runCatching {
            Files.move(
                tmpFile.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
            true
        }.getOrElse {
            runCatching {
                Files.move(
                    tmpFile.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                true
            }.getOrElse {
                if (target.exists()) target.delete()
                tmpFile.renameTo(target)
            }
        }
        if (!moved && tmpFile.exists()) {
            tmpFile.copyTo(target, overwrite = true)
            tmpFile.delete()
        }
    }

    private fun sanitizeEntry(entry: CaptureDebugTraceEntry): CaptureDebugTraceEntry {
        val secrets = secretProvider()
        fun r(s: String): String = CaptureDebugTraceStore.redactSecrets(s, secrets)
        fun rl(list: List<String>): List<String> = list.map { r(it) }

        return entry.copy(
            orderKey = r(entry.orderKey),
            merchant = r(entry.merchant),
            headline = r(entry.headline),
            sourceCapture = entry.sourceCapture.copy(
                packageName = r(entry.sourceCapture.packageName),
                verifiedActivity = r(entry.sourceCapture.verifiedActivity),
                structureFingerprint = r(entry.sourceCapture.structureFingerprint),
                rawTextPreview = r(entry.sourceCapture.rawTextPreview).take(MAX_RAW_TEXT_PREVIEW_CHARS),
                screenshotRef = r(entry.sourceCapture.screenshotRef.substringAfterLast('/')),
                screenshotSha256 = r(entry.sourceCapture.screenshotSha256)
            ),
            windowSessionGuard = entry.windowSessionGuard.copy(
                whitelistSummary = r(entry.windowSessionGuard.whitelistSummary),
                containerRole = r(entry.windowSessionGuard.containerRole),
                guardRuleName = r(entry.windowSessionGuard.guardRuleName),
                sessionAction = r(entry.windowSessionGuard.sessionAction),
                sessionResetReason = r(entry.windowSessionGuard.sessionResetReason)
            ),
            parseEngineRoute = entry.parseEngineRoute.copy(
                provider = r(entry.parseEngineRoute.provider),
                modelName = r(entry.parseEngineRoute.modelName),
                maskedEndpoint = r(entry.parseEngineRoute.maskedEndpoint),
                llmResponseSummary = r(entry.parseEngineRoute.llmResponseSummary).take(600),
                fallbackOrSkipReason = r(entry.parseEngineRoute.fallbackOrSkipReason)
            ),
            fieldExtraction = entry.fieldExtraction.copy(
                candidateAmounts = rl(entry.fieldExtraction.candidateAmounts),
                amountRuleBranch = r(entry.fieldExtraction.amountRuleBranch),
                strippedSafeguardPhrases = rl(entry.fieldExtraction.strippedSafeguardPhrases),
                matchedDirectionKeywords = rl(entry.fieldExtraction.matchedDirectionKeywords),
                merchantSummary = r(entry.fieldExtraction.merchantSummary),
                orderNoSummary = r(entry.fieldExtraction.orderNoSummary),
                occurredAtSummary = r(entry.fieldExtraction.occurredAtSummary)
            ),
            categoryWalletMapping = entry.categoryWalletMapping.copy(
                finalCategory = r(entry.categoryWalletMapping.finalCategory),
                categoryDecisionSource = r(entry.categoryWalletMapping.categoryDecisionSource),
                smartRuleSummary = r(entry.categoryWalletMapping.smartRuleSummary),
                merchantMemorySummary = r(entry.categoryWalletMapping.merchantMemorySummary),
                weightedKeywordsHit = rl(entry.categoryWalletMapping.weightedKeywordsHit),
                finalWalletName = r(entry.categoryWalletMapping.finalWalletName),
                walletMatchBasis = r(entry.categoryWalletMapping.walletMatchBasis)
            ),
            pipelineDedupFusion = entry.pipelineDedupFusion.copy(
                slidingWindowMergedSources = rl(entry.pipelineDedupFusion.slidingWindowMergedSources),
                dbCandidatesInspected = rl(entry.pipelineDedupFusion.dbCandidatesInspected),
                matchedRuleId = r(entry.pipelineDedupFusion.matchedRuleId),
                fusedFieldsDiff = rl(entry.pipelineDedupFusion.fusedFieldsDiff),
                finalSummary = r(entry.pipelineDedupFusion.finalSummary)
            )
        )
    }

    /**
     * 过滤桌面切换、本应用切换与日常纯聊天列表/输入框切换，避免日常使用刷爆调试配额（AC-3）
     */
    private fun shouldIgnoreNoiseWindowSwitch(
        packageName: String,
        verifiedActivity: String,
        containerRole: String,
        guardRuleName: String,
        rawTextSample: String
    ): Boolean {
        val pkgLower = packageName.trim().lowercase(Locale.ROOT)
        if (pkgLower.isEmpty() ||
            pkgLower.startsWith("com.moji.ai") ||
            pkgLower.contains("launcher") ||
            pkgLower == "com.android.systemui" ||
            pkgLower == "com.miui.home" ||
            pkgLower == "com.oppo.launcher" ||
            pkgLower == "com.huawei.android.launcher" ||
            pkgLower == "com.bbk.launcher2" ||
            pkgLower == "com.sec.android.app.launcher"
        ) {
            return true
        }

        val actLower = verifiedActivity.trim().lowercase(Locale.ROOT)
        // 纯聊天主界面、朋友圈、系统输入法、会话输入框不计入支付窗口拦截日志
        if (containerRole == "NON_TRANSACTION_BLOCKED" || guardRuleName == "NON_TRANSACTION_CONTAINER") {
            return true
        }
        if (guardRuleName == "VISIBLE_EDITABLE_INPUT") {
            return true
        }
        if (actLower.contains("launcherui") ||
            actLower.contains(".chatting.") ||
            actLower.contains("snstimelineui") ||
            actLower.contains("homeactivity")
        ) {
            return true
        }
        if (guardRuleName == "CHAT_FEED_OR_BILL_LIST") {
            val isPureChatOrHome = rawTextSample.contains("按住 说话") ||
                rawTextSample.contains("切换到按住说话") ||
                rawTextSample.contains("切换到发消息") ||
                rawTextSample.contains("全部会话") ||
                (rawTextSample.contains("扫一扫") && rawTextSample.contains("收付款"))
            if (isPureChatOrHome) {
                return true
            }
        }
        return false
    }

    companion object {
        const val DIAGNOSTICS_DIR_NAME = "diagnostics"
        const val TRACE_FILE_NAME = "capture_debug_traces.jsonl"
        const val MAX_RAW_TEXT_PREVIEW_CHARS = 2_000
        const val WINDOW_BLOCK_DEBOUNCE_MS = 15_000L
        private const val FIFO_EVICT_RATIO = 0.20

        internal val jsonCodec = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        internal val prettyJsonCodec = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            prettyPrint = true
        }
    }
}

/**
 * 全局识账调试追踪单例入口（由 `MoJiApp.onCreate` 初始化）
 */
object CaptureDebugTraceStore {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var delegateManager: CaptureDebugTraceManager? = null

    private val fallbackStatsFlow = MutableStateFlow(DebugTraceStorageStats())

    val statsFlow: StateFlow<DebugTraceStorageStats>
        get() = delegateManager?.statsFlow ?: fallbackStatsFlow

    fun init(context: Context, aiConfigManager: AiConfigManager? = null) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (delegateManager != null) return
        synchronized(this) {
            if (delegateManager == null) {
                val prefs = appCtx.getSharedPreferences(
                    SharedPrefsDebugTraceConfigStore.PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                delegateManager = CaptureDebugTraceManager(
                    baseDir = appCtx.filesDir,
                    configStore = SharedPrefsDebugTraceConfigStore(prefs),
                    secretProvider = {
                        buildList {
                            runCatching { aiConfigManager?.getConfig()?.apiKey?.let(::add) }
                            runCatching {
                                val cloudCfg = MoJiCloudSyncClient.configFlow.value
                                add(cloudCfg.syncKey)
                                add(cloudCfg.serverAccessCode)
                                add(cloudCfg.accessToken)
                            }
                            runCatching {
                                val webDavCfg = WebDavSyncClient.loadConfig(appCtx)
                                add(webDavCfg.appPassword)
                            }
                        }
                    }
                )
            }
        }
    }

    fun managerOrNull(): CaptureDebugTraceManager? = delegateManager

    fun isEnabled(): Boolean = delegateManager?.isEnabled() == true

    fun setEnabled(enabled: Boolean) {
        delegateManager?.setEnabled(enabled)
    }

    fun setQuotaTier(tier: DebugTraceQuotaTier) {
        delegateManager?.setQuotaTier(tier)
    }

    fun clearAllTraces() {
        delegateManager?.clearAllTraces()
    }

    fun readAllTracesNewestFirst(): List<CaptureDebugTraceEntry> =
        delegateManager?.readAllTracesNewestFirst().orEmpty()

    fun getTracesForTransaction(transactionId: Long, orderKey: String = ""): List<CaptureDebugTraceEntry> =
        delegateManager?.getTracesForTransaction(transactionId, orderKey).orEmpty()

    fun exportJsonlToStream(outputStream: OutputStream): Int =
        delegateManager?.exportJsonlToStream(outputStream) ?: 0

    fun encodeTracePrettyJson(entry: CaptureDebugTraceEntry): String =
        delegateManager?.encodeTracePrettyJson(entry)
            ?: CaptureDebugTraceManager.prettyJsonCodec.encodeToString(entry)

    private val BEARER_TOKEN_REGEX = Regex("""(?i)(Bearer\s+)[A-Za-z0-9._~+/=-]{6,}""")
    private val SK_KEY_REGEX = Regex("""\bsk-[A-Za-z0-9_-]{6,}\b""")
    private val GEMINI_AIZA_KEY_REGEX = Regex("""\bAIza[0-9A-Za-z_-]{12,}\b""")
    private val MOJI_SYNC_KEY_REGEX = Regex("""\bMOJI(?:QA)?-[A-Z0-9]{4}(?:-[A-Z0-9]{4}){2,4}\b""")
    private val URL_QUERY_SECRET_REGEX = Regex(
        """(?i)([?&](?:key|api_key|apikey|token|access_token|secret|password|sync_key|access_code)=)([^&\s"']+)"""
    )
    private val JSON_SECRET_FIELD_REGEX = Regex(
        """(?i)("(?:apiKey|api_key|x-goog-api-key|authorization|syncKey|sync_key|appPassword|password|accessToken|access_token|serverAccessCode)"\s*:\s*")([^"]+)(")"""
    )
    private val HEADER_SECRET_KV_REGEX = Regex(
        """(?i)((?:x-goog-api-key|authorization)\s*[:=]\s*)(?:Bearer\s+)?([^\s,;"']+)"""
    )

    /**
     * 100% 强制密钥脱敏引擎（AC-4.3）：
     * 同时覆盖当前配置的精确敏感串（API Key / SyncKey / AccessCode / WebDAV 密码）与通用凭据正则模式。
     */
    fun redactSecrets(raw: String, extraSecrets: Collection<String> = emptyList()): String {
        if (raw.isEmpty()) return raw
        var result = raw
        val normalizedSecrets = extraSecrets
            .map { it.trim() }
            .filter { it.length >= 4 }
            .distinct()
            .sortedByDescending { it.length }

        for (secret in normalizedSecrets) {
            if (result.contains(secret)) {
                result = result.replace(secret, "***REDACTED***")
            }
        }
        result = BEARER_TOKEN_REGEX.replace(result, "$1***REDACTED***")
        result = SK_KEY_REGEX.replace(result, "sk-***REDACTED***")
        result = GEMINI_AIZA_KEY_REGEX.replace(result, "AIza***REDACTED***")
        result = MOJI_SYNC_KEY_REGEX.replace(result, "MOJI-***REDACTED***")
        result = URL_QUERY_SECRET_REGEX.replace(result, "$1***REDACTED***")
        result = JSON_SECRET_FIELD_REGEX.replace(result, "$1***REDACTED***$3")
        result = HEADER_SECRET_KV_REGEX.replace(result, "$1***REDACTED***")
        return result
    }

    /**
     * 构建账单详情页「复制完整溯源报告」的结构化脱敏文本（AC-5.2）
     */
    fun formatTransactionProvenanceReport(
        tx: TransactionEntity,
        evidences: List<CaptureEvidenceEntity>,
        traces: List<CaptureDebugTraceEntry>,
        extraSecrets: Collection<String> = emptyList()
    ): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
        val rawReport = buildString {
            appendLine("=== 默记 MoJi 账单数据来源与推理溯源报告 ===")
            appendLine("账单 ID: #${tx.id}")
            appendLine("来源标记: ${tx.captureSource}")
            appendLine("交易类型: ${tx.type.displayName} | 分类: ${tx.category}")
            appendLine(
                "实付金额: ${String.format(Locale.US, "%.2f", tx.amountCents / 100.0)} ${tx.currency}" +
                    if (tx.discountCents > 0L) " (优惠 ${String.format(Locale.US, "%.2f", tx.discountCents / 100.0)})" else ""
            )
            appendLine("商户名称: ${tx.merchant}")
            appendLine("交易时间: ${fmt.format(Date(tx.timestamp))}")
            if (tx.capturedAtMillis > 0L) {
                appendLine("采集时间: ${fmt.format(Date(tx.capturedAtMillis))}")
            }
            if (tx.orderKey.isNotBlank() || tx.transactionNo.isNotBlank() || tx.merchantOrderNo.isNotBlank()) {
                appendLine("单号标识: orderKey=${tx.orderKey} | txNo=${tx.transactionNo} | mchNo=${tx.merchantOrderNo}")
            }
            if (tx.paymentMethodRaw.isNotBlank()) {
                appendLine("支付方式原文: ${tx.paymentMethodRaw}")
            }
            appendLine()
            appendLine("--- 一、绑定的多源采集凭据 (${evidences.size} 份) ---")
            if (evidences.isEmpty()) {
                appendLine("（无独立 capture_evidences 明细，回退账单主表 rawCapturedText）")
                if (tx.rawCapturedText.isNotBlank()) {
                    appendLine(tx.rawCapturedText.take(1200))
                }
            } else {
                evidences.sortedBy { it.capturedAtMillis }.forEachIndexed { idx, ev ->
                    appendLine(
                        "[凭据 ${idx + 1}] 通道=${ev.captureSource} | 包名=${ev.sourcePackage} | " +
                            "采集=${fmt.format(Date(ev.capturedAtMillis))} | 发生=${fmt.format(Date(ev.occurredAtMillis))}"
                    )
                    if (ev.transactionNo.isNotBlank() || ev.merchantOrderNo.isNotBlank() || ev.paymentMethodRaw.isNotBlank()) {
                        appendLine("  单号=${ev.transactionNo} | 商户单号=${ev.merchantOrderNo} | 付款方式=${ev.paymentMethodRaw}")
                    }
                    if (ev.screenshotSha256.isNotBlank() || ev.screenshotPath.isNotBlank()) {
                        appendLine("  截图引用=${File(ev.screenshotPath).name} (sha256=${ev.screenshotSha256.take(16)})")
                    }
                    appendLine("  原文: ${ev.rawText.take(800)}")
                }
            }
            appendLine()
            appendLine("--- 二、6 步全链路推理轨迹 (${traces.size} 段) ---")
            if (traces.isEmpty()) {
                appendLine("（该笔账单入账时「识账调试模式」处于关闭状态，或更早轨迹已被环形配额淘汰）")
            } else {
                traces.forEachIndexed { idx, tr ->
                    appendLine("【第 ${idx + 1} 段 · ${tr.outcome.badgeLabel} (${tr.outcome.name}) · ${fmt.format(Date(tr.timestampMillis))}】")
                    appendLine("  摘要: ${tr.headline}")
                    appendLine(
                        "  1. SOURCE_CAPTURE: source=${tr.sourceCapture.captureSource}, pkg=${tr.sourceCapture.packageName}, " +
                            "act=${tr.sourceCapture.verifiedActivity}, fp=${tr.sourceCapture.structureFingerprint}, " +
                            "shot=${tr.sourceCapture.screenshotRef}(${tr.sourceCapture.screenshotSha256.take(12)})"
                    )
                    appendLine(
                        "  2. WINDOW_SESSION_GUARD: whitelist=${tr.windowSessionGuard.whitelistAllowed}(${tr.windowSessionGuard.whitelistSummary}), " +
                            "role=${tr.windowSessionGuard.containerRole}, rule=${tr.windowSessionGuard.guardRuleName}, " +
                            "visitId=${tr.windowSessionGuard.cashierVisitId}, session=${tr.windowSessionGuard.sessionAction}, " +
                            "resetReason=${tr.windowSessionGuard.sessionResetReason}, stableDedup=${tr.windowSessionGuard.stablePageDedupHit}"
                    )
                    appendLine(
                        "  3. PARSE_ENGINE_ROUTE: route=${tr.parseEngineRoute.routeSelected}, provider=${tr.parseEngineRoute.provider}, " +
                            "model=${tr.parseEngineRoute.modelName}, endpoint=${tr.parseEngineRoute.maskedEndpoint}, " +
                            "latency=${tr.parseEngineRoute.latencyMs}ms, note=${tr.parseEngineRoute.fallbackOrSkipReason.ifBlank { tr.parseEngineRoute.llmResponseSummary }}"
                    )
                    appendLine(
                        "  4. FIELD_EXTRACTION: branch=${tr.fieldExtraction.amountRuleBranch}, candidates=${tr.fieldExtraction.candidateAmounts.joinToString()}, " +
                            "paid=${tr.fieldExtraction.actualPaidCents}分, orig=${tr.fieldExtraction.originalPriceCents}分, disc=${tr.fieldExtraction.discountCents}分, " +
                            "dir=${tr.fieldExtraction.directionType}(keywords=${tr.fieldExtraction.matchedDirectionKeywords.joinToString()}), " +
                            "strippedSafeguards=${tr.fieldExtraction.strippedSafeguardPhrases.joinToString()}, linkedExp=${tr.fieldExtraction.linkedExpenseId ?: "无"}, " +
                            "merchant=${tr.fieldExtraction.merchantSummary}, order=${tr.fieldExtraction.orderNoSummary}, time=${tr.fieldExtraction.occurredAtSummary}"
                    )
                    appendLine(
                        "  5. CATEGORY_WALLET_MAPPING: category=${tr.categoryWalletMapping.finalCategory}(source=${tr.categoryWalletMapping.categoryDecisionSource}), " +
                            "smartRule=${tr.categoryWalletMapping.smartRuleSummary}, memory=${tr.categoryWalletMapping.merchantMemorySummary}, " +
                            "weighted=${tr.categoryWalletMapping.weightedKeywordsHit.joinToString()}(conf=${tr.categoryWalletMapping.weightedConfidence}), " +
                            "wallet=${tr.categoryWalletMapping.finalWalletName.ifBlank { "不关联账户" }}(basis=${tr.categoryWalletMapping.walletMatchBasis})"
                    )
                    appendLine(
                        "  6. PIPELINE_DEDUP_FUSION: batch=${tr.pipelineDedupFusion.slidingWindowBatchSize}(${tr.pipelineDedupFusion.slidingWindowMergedSources.joinToString()}), " +
                            "candidates=${tr.pipelineDedupFusion.dbCandidatesInspected.joinToString(" ; ")}, " +
                            "rule=${tr.pipelineDedupFusion.matchedRuleId}, matchedId=${tr.pipelineDedupFusion.matchedCandidateId ?: "无"}, " +
                            "diff=${tr.pipelineDedupFusion.fusedFieldsDiff.joinToString(" ; ")}, finalTxId=${tr.pipelineDedupFusion.finalTransactionId ?: "无"}"
                    )
                }
            }
        }
        val activeSecrets = buildList {
            addAll(extraSecrets)
            delegateManager?.let {
                // 通过 delegateManager.redactText 一并应用运行期配置的敏感串
                return it.redactText(rawReport)
            }
        }
        return redactSecrets(rawReport, activeSecrets)
    }
}
