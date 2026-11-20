package com.moji.ai.data.backup

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.moji.ai.BuildConfig
import com.moji.ai.MoJiApp
import com.moji.ai.ai.CaptureAppWhitelistManager
import com.moji.ai.data.local.RecurringRuleEntity
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.AppearanceConfigManager
import com.moji.ai.data.model.CategoryCatalog
import com.moji.ai.data.model.CurrencyRateManager
import com.moji.ai.data.model.DedicatedFundManager
import com.moji.ai.data.model.ReminderConfigManager
import com.moji.ai.data.model.TagCatalog
import com.moji.ai.data.model.UserProfileManager
import com.moji.ai.service.BillScreenshotStorage
import com.moji.ai.ui.InstallmentPlanItem
import com.moji.ai.ui.QuickBookkeepingTemplate
import com.moji.ai.ui.QuickTemplateHelper
import com.moji.ai.ui.SavingPlanItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit

@Serializable
data class MoJiCloudConfig(
    val serverUrl: String = "",
    val serverAccessCode: String = "",
    val syncKey: String = "",
    val memberLabel: String = "",
    val autoSyncEnabled: Boolean = true,
    val userId: String = "",
    val accessToken: String = "",
    val tokenExpiresAtMillis: Long = 0L,
    val boundTargetSignature: String = "",
    val lastSyncedRevision: Long = 0L,
    val lastSyncTimeMillis: Long = 0L,
    val remoteRevision: Long = 0L,
    val remoteTxCount: Int = 0,
    val remoteWalletCount: Int = 0,
    val remoteScreenshotCount: Int = 0,
    val remoteUpdatedBy: String = "",
    val remoteUpdatedAtMillis: Long = 0L
) {
    val currentTargetSignature: String
        get() = MoJiCloudSyncClient.computeTargetSignature(serverUrl, syncKey)

    val isTargetBound: Boolean
        get() = serverUrl.isNotBlank() &&
            syncKey.isNotBlank() &&
            userId.isNotBlank() &&
            boundTargetSignature.isNotBlank() &&
            boundTargetSignature == currentTargetSignature

    val effectiveLastSyncedRevision: Long
        get() = if (isTargetBound) lastSyncedRevision else 0L

    /**
     * 仅当已成功完成首次同步（或已与云端版本对齐）时，才允许后台静默自动推送，
     * 杜绝新手机刚填入旧密钥、尚未拉取恢复时自动把空账本推上云端。
     */
    val canAutoPushSafely: Boolean
        get() = autoSyncEnabled &&
            isTargetBound &&
            (remoteRevision == 0L || lastSyncedRevision == remoteRevision)
}

class CloudSyncConflictException(
    override val message: String,
    val remoteSummary: MoJiCloudSyncClient.AccountSummaryDto? = null
) : IllegalStateException(message)

private class CloudUnauthorizedException(message: String) : IllegalStateException(message)

data class CloudPulledRestoreBundle(
    val wallets: List<WalletEntity>,
    val transactions: List<TransactionEntity>,
    val recurringRules: List<RecurringRuleEntity>,
    val snapshot: MoJiFullBackupSnapshot,
    val revision: Long,
    val syncedScreenshots: Int,
    val updatedConfig: MoJiCloudConfig,
    val failedScreenshots: Int = 0
)

/**
 * 默记（MoJi）自托管云存储客户端（对接 Rust `moji-cloud-server`）：
 * 1. 前期采用客户端生成的密码学专属密钥（Sync Key）免登录绑定，不同家庭成员各自生成独立 Key 区分账单；
 * 2. 所有同步与原图接口统一走内部 `user_id` 签发的 `Bearer` 令牌，后期平滑衔接微信/手机号登录零迁移；
 * 3. 支持 `expected_revision` 乐观并发保护与 WebP 识屏账单原图内容寻址增量同步。
 */
object MoJiCloudSyncClient {

    private const val PREFS_NAME = "moji_cloud_sync_prefs"
    private const val KEY_CONFIG_JSON = "cloud_sync_config_json"
    private const val KEY_FALLBACK_DEVICE_ID = "fallback_device_id"
    private const val PAIR_SCHEME_PREFIX = "moji-pair://v1"

    // 去除易混淆字符 0/O/1/I 后的 32 字符字母表（每字符 5 bit 熵，16 字符共 80 bit 随机熵）
    private const val KEY_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024
    private const val MAX_SCREENSHOT_BYTES = 8 * 1024 * 1024

    private val syncMutex = Mutex()
    private val secureRandom = SecureRandom()

    private const val KEY_PENDING_AUTO_PUSH = "pending_auto_push"

    private val _configFlow = MutableStateFlow(MoJiCloudConfig())
    val configFlow: StateFlow<MoJiCloudConfig> = _configFlow.asStateFlow()

    private val _localMutationSignal = MutableStateFlow(0L)
    val localMutationSignal: StateFlow<Long> = _localMutationSignal.asStateFlow()

    @Volatile
    private var isRestoringFlag: Boolean = false

    @Volatile
    private var suppressAutoSyncUntilNano: Long = 0L

    @Volatile
    private var lastRestoredState: Pair<Triple<Long, Long, Long>, Long>? = null

    fun init(context: Context) {
        _configFlow.value = loadConfig(context)
    }

    fun beginRestoreSuppression() {
        isRestoringFlag = true
        suppressAutoSyncUntilNano = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
    }

    fun endRestoreSuppression(cooldownMs: Long = 2_500L) {
        suppressAutoSyncUntilNano = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cooldownMs)
        isRestoringFlag = false
    }

    fun isRestoringNow(): Boolean = isRestoringFlag

    fun remainingSuppressionMillis(): Long {
        val diffNano = suppressAutoSyncUntilNano - System.nanoTime()
        return if (diffNano > 0L) TimeUnit.NANOSECONDS.toMillis(diffNano).coerceAtLeast(1L) else 0L
    }

    fun shouldSuppressAutoSync(): Boolean {
        return isRestoringFlag || (System.nanoTime() - suppressAutoSyncUntilNano < 0L)
    }

    private fun mix64(value: Long): Long {
        var z = value + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun computeRoomSignature(
        wallets: List<WalletEntity>,
        txs: List<TransactionEntity>,
        rules: List<RecurringRuleEntity>
    ): Triple<Long, Long, Long> {
        var walletHash = mix64(wallets.size.toLong())
        for (w in wallets) {
            walletHash += mix64((w.id * 31L) xor w.hashCode().toLong())
        }
        var txHash = mix64(txs.size.toLong())
        for (t in txs) {
            val normalizedCode = if (t.screenshotSha256.isEmpty()) {
                t.hashCode()
            } else {
                t.copy(screenshotSha256 = "").hashCode()
            }
            txHash += mix64((t.id * 31L) xor normalizedCode.toLong())
        }
        var ruleHash = mix64(rules.size.toLong())
        for (r in rules) {
            ruleHash += mix64((r.id * 31L) xor r.hashCode().toLong())
        }
        return Triple(walletHash, txHash, ruleHash)
    }

    fun recordRestoredState(
        signature: Triple<Long, Long, Long>?,
        mutationSeq: Long = _localMutationSignal.value
    ) {
        lastRestoredState = signature?.let { it to mutationSeq }
    }

    fun consumeIfMatchesRestoredState(
        signature: Triple<Long, Long, Long>,
        mutationSeq: Long
    ): Boolean {
        val expected = lastRestoredState ?: return false
        lastRestoredState = null
        return expected.first == signature && expected.second == mutationSeq
    }

    fun hasPendingAutoPush(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_PENDING_AUTO_PUSH, false)
    }

    fun markPendingAutoPush(context: Context, notifySignal: Boolean = true) {
        if (isRestoringFlag) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PENDING_AUTO_PUSH, false)) {
            prefs.edit().putBoolean(KEY_PENDING_AUTO_PUSH, true).apply()
        }
        if (notifySignal) {
            lastRestoredState = null
            _localMutationSignal.value += 1L
        }
    }

    fun clearPendingAutoPush(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PENDING_AUTO_PUSH, false)) {
            prefs.edit().putBoolean(KEY_PENDING_AUTO_PUSH, false).apply()
        }
    }

    /**
     * 当用户从外部 JSON 或 WebDAV 恢复全量备份时，将本地默记云修订号置为未对齐状态，
     * 防止外部备份在未经确认的情况下自动静默覆盖默记云服务器数据。
     */
    fun markLocalSnapshotDivergedFromCloud(context: Context) {
        clearPendingAutoPush(context)
        val current = _configFlow.value
        if (current.isTargetBound && current.remoteRevision > 0L && current.lastSyncedRevision != 0L) {
            saveConfig(context, current.copy(lastSyncedRevision = 0L))
        }
    }

    /**
     * 在本地 Room 数据库原子恢复成功之后，正式提交云端拉取到的最新修订号配置。
     * 若恢复快照刚将默认机型名/旧昵称同步为云端快照昵称，优先保留已同步的 `latest.memberLabel`，
     * 避免被拉取前构造的默认机型名覆盖回退；若用户显式设置了自定义设备备注则原样保留。
     */
    fun commitPulledConfigAfterRestore(
        context: Context,
        pulledConfig: MoJiCloudConfig,
        preRestoreMemberLabel: String? = null
    ) {
        clearPendingAutoPush(context)
        val latest = _configFlow.value
        val merged = mergePulledConfigAfterRestore(
            latest = latest,
            pulledConfig = pulledConfig,
            preRestoreMemberLabel = preRestoreMemberLabel
        )
        saveConfig(context, merged)
    }

    internal fun mergePulledConfigAfterRestore(
        latest: MoJiCloudConfig,
        pulledConfig: MoJiCloudConfig,
        defaultDeviceLabel: String = defaultDeviceName(),
        preRestoreMemberLabel: String? = null
    ): MoJiCloudConfig {
        if (latest.currentTargetSignature != pulledConfig.currentTargetSignature) {
            return pulledConfig
        }
        val pulledTrimmed = pulledConfig.memberLabel.trim()
        val latestTrimmed = latest.memberLabel.trim()
        val preTrimmed = preRestoreMemberLabel?.trim()
        val shouldPreferLatestLabel = latestTrimmed.isNotEmpty() && (
            pulledTrimmed.isEmpty() ||
                pulledTrimmed == defaultDeviceLabel ||
                (preTrimmed != null && pulledTrimmed == preTrimmed)
            )
        val resolvedMemberLabel = if (shouldPreferLatestLabel) {
            latestTrimmed
        } else {
            pulledConfig.memberLabel
        }
        return pulledConfig.copy(
            autoSyncEnabled = latest.autoSyncEnabled,
            memberLabel = resolvedMemberLabel
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val jsonCodec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    val expectedKeyPrefix: String
        get() = if (BuildConfig.IS_QA_BUILD) "MOJIQA-" else "MOJI-"

    val currentAppEnv: String
        get() = if (BuildConfig.IS_QA_BUILD) "qa" else "prod"

    @Serializable
    data class AccountSummaryDto(
        @SerialName("latest_revision") val latestRevision: Long = 0L,
        @SerialName("wallet_count") val walletCount: Int = 0,
        @SerialName("tx_count") val txCount: Int = 0,
        @SerialName("ledger_count") val ledgerCount: Int = 0,
        @SerialName("screenshot_count") val screenshotCount: Int = 0,
        @SerialName("snapshot_bytes") val snapshotBytes: Long = 0L,
        @SerialName("snapshot_sha256") val snapshotSha256: String = "",
        @SerialName("updated_by_device") val updatedByDevice: String = "",
        @SerialName("updated_by_member") val updatedByMember: String = "",
        @SerialName("updated_at_ms") val updatedAtMs: Long = 0L
    )

    @Serializable
    private data class KeyLoginReqDto(
        @SerialName("sync_key") val syncKey: String,
        @SerialName("server_access_code") val serverAccessCode: String? = null,
        @SerialName("member_label") val memberLabel: String = "",
        @SerialName("device_id") val deviceId: String = "",
        @SerialName("device_name") val deviceName: String = "",
        @SerialName("app_env") val appEnv: String = "prod",
        @SerialName("app_version") val appVersion: String = ""
    )

    @Serializable
    private data class KeyLoginRespDto(
        @SerialName("access_token") val accessToken: String = "",
        @SerialName("expires_at_ms") val expiresAtMs: Long = 0L,
        @SerialName("user_id") val userId: String = "",
        @SerialName("key_hint") val keyHint: String = "",
        @SerialName("is_new_account") val isNewAccount: Boolean = false,
        @SerialName("account_summary") val accountSummary: AccountSummaryDto = AccountSummaryDto()
    )

    @Serializable
    private data class PutSnapshotReqDto(
        @SerialName("expected_revision") val expectedRevision: Long? = null,
        @SerialName("force_overwrite") val forceOverwrite: Boolean = false,
        @SerialName("device_id") val deviceId: String = "",
        @SerialName("device_name") val deviceName: String = "",
        @SerialName("member_label") val memberLabel: String = "",
        @SerialName("snapshot_json") val snapshotJson: String
    )

    @Serializable
    private data class PutSnapshotRespDto(
        @SerialName("account_summary") val accountSummary: AccountSummaryDto = AccountSummaryDto()
    )

    @Serializable
    private data class PulledSnapshotRespDto(
        val revision: Long = 0L,
        @SerialName("snapshot_sha256") val snapshotSha256: String = "",
        @SerialName("updated_at_ms") val updatedAtMs: Long = 0L,
        @SerialName("updated_by_device") val updatedByDevice: String = "",
        @SerialName("updated_by_member") val updatedByMember: String = "",
        @SerialName("snapshot_json") val snapshotJson: String = "",
        @SerialName("available_screenshots") val availableScreenshots: List<String> = emptyList()
    )

    @Serializable
    private data class MissingScreenshotsReqDto(
        @SerialName("sha256_list") val sha256List: List<String>
    )

    @Serializable
    private data class MissingScreenshotsRespDto(
        @SerialName("missing_sha256") val missingSha256: List<String> = emptyList()
    )

    @Serializable
    private data class ErrorRespDto(
        val error: String = "",
        val message: String = "",
        @SerialName("account_summary") val accountSummary: AccountSummaryDto? = null
    )

    fun generateSyncKey(): String {
        val groups = (0 until 4).joinToString("-") {
            buildString(4) {
                repeat(4) {
                    append(KEY_ALPHABET[secureRandom.nextInt(KEY_ALPHABET.length)])
                }
            }
        }
        return "$expectedKeyPrefix$groups"
    }

    fun defaultDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
            .takeUnless { it.equals("unknown", ignoreCase = true) }
            .orEmpty()
        val model = Build.MODEL.orEmpty().trim()
            .takeUnless { it.equals("unknown", ignoreCase = true) }
            .orEmpty()
        return when {
            manufacturer.isEmpty() -> model.ifEmpty { "Android 设备" }
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }.take(48)
    }

    fun loadConfig(context: Context): MoJiCloudConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CONFIG_JSON, null)
        val parsed = if (!raw.isNullOrBlank()) {
            runCatching { jsonCodec.decodeFromString<MoJiCloudConfig>(raw) }.getOrNull()
        } else {
            null
        }
        val base = parsed ?: MoJiCloudConfig()
        val ensuredKey = base.syncKey.trim().uppercase(Locale.US).ifBlank { generateSyncKey() }
        val preferredDefaultLabel = UserProfileManager.currentConfig.nickname
            .trim()
            .takeIf { it.isNotEmpty() && it != UserProfileManager.DEFAULT_NICKNAME }
            ?: defaultDeviceName()
        val ensuredLabel = base.memberLabel.trim().ifBlank { preferredDefaultLabel }
        val normalized = base.copy(syncKey = ensuredKey, memberLabel = ensuredLabel)
        if (parsed == null || parsed.syncKey != ensuredKey || parsed.memberLabel != ensuredLabel) {
            saveConfig(context, normalized)
        }
        return normalized
    }

    fun saveConfig(context: Context, config: MoJiCloudConfig) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONFIG_JSON, jsonCodec.encodeToString(config)).apply()
        _configFlow.value = config
    }

    /**
     * 生成脱敏后的专属密钥展示摘要（如 `MOJI-****-NPQR` / `MOJIQA-****-NPQR`），用于设置页个人资料卡状态胶囊。
     */
    fun maskedSyncKeyHint(rawKey: String): String {
        val clean = rawKey.trim().uppercase(Locale.US)
        if (clean.isBlank()) return ""
        val prefix = when {
            clean.startsWith("MOJIQA-") -> "MOJIQA-"
            clean.startsWith("MOJI-") -> "MOJI-"
            else -> clean.substringBefore('-', "").let { if (it.isNotEmpty()) "$it-" else "" }
        }
        val suffix = clean.substringAfterLast('-').takeLast(4)
        return when {
            prefix.isNotEmpty() && suffix.length == 4 -> "${prefix}****-$suffix"
            clean.length > 8 -> "${clean.take(4)}****${clean.takeLast(4)}"
            else -> "****"
        }
    }

    /**
     * 同步个人资料昵称至 `memberLabel`：
     * - 用户主动修改昵称（`fromSnapshotRestore = false`）：已配置云同步或当前标签为空/默认机型/旧昵称时同步更新；
     * - 快照恢复联动（`fromSnapshotRestore = true`）：仅当本地 `memberLabel` 为空、默认机型名或恢复前旧昵称时才填入快照昵称，
     *   保护家庭共享等场景下用户为单台设备显式自定义的专属备注（如 `iPad` / `家人`）。
     */
    fun syncMemberLabelWithNickname(
        context: Context,
        newNickname: String,
        previousNickname: String = "",
        fromSnapshotRestore: Boolean = false
    ): Boolean {
        val current = _configFlow.value
        val targetLabel = resolveSyncedMemberLabel(
            current = current,
            newNickname = newNickname,
            previousNickname = previousNickname,
            fromSnapshotRestore = fromSnapshotRestore
        ) ?: return false
        saveConfig(context, current.copy(memberLabel = targetLabel))
        if (current.isTargetBound && !fromSnapshotRestore) {
            markPendingAutoPush(context, notifySignal = true)
        }
        return true
    }

    internal fun resolveSyncedMemberLabel(
        current: MoJiCloudConfig,
        newNickname: String,
        previousNickname: String = "",
        fromSnapshotRestore: Boolean = false,
        defaultDeviceLabel: String = defaultDeviceName()
    ): String? {
        val normalizedNick = UserProfileManager.normalizeNickname(newNickname)
        val currentTrimmed = current.memberLabel.trim()
        val prevTrimmed = previousNickname.trim()
        val targetLabel = if (normalizedNick == UserProfileManager.DEFAULT_NICKNAME) {
            if (prevTrimmed.isNotEmpty() && currentTrimmed == prevTrimmed) {
                defaultDeviceLabel
            } else {
                return null
            }
        } else {
            val isDefaultOrPreviousLabel = currentTrimmed.isEmpty() ||
                currentTrimmed == defaultDeviceLabel ||
                (prevTrimmed.isNotEmpty() && currentTrimmed == prevTrimmed)
            val isCloudConfigured = current.isTargetBound || current.serverUrl.isNotBlank()
            val shouldSync = if (fromSnapshotRestore) {
                isDefaultOrPreviousLabel
            } else {
                isCloudConfigured || isDefaultOrPreviousLabel
            }
            if (!shouldSync) return null
            normalizedNick
        }
        return targetLabel.takeIf { it != current.memberLabel }
    }

    /**
     * 从 Application 全局上下文单次原子构建完整备份快照并补齐存量账单缺失的截图 SHA-256，
     * 确保快照 JSON 内的 `screenshotSha256` 与上传至云端的原图哈希 100% 对应。
     */
    suspend fun buildSnapshotFromApp(app: MoJiApp): Pair<String, List<TransactionEntity>> =
        withContext(Dispatchers.IO) {
            val uiPrefs = app.getSharedPreferences("moji_icost_ui_prefs", Context.MODE_PRIVATE)
            val savedLedgersRaw = uiPrefs.getString("saved_ledgers", null)
            val ledgers = if (savedLedgersRaw.isNullOrBlank()) {
                listOf("默认账本", "日常家用", "旅行基金")
            } else {
                savedLedgersRaw.split("||").map { it.trim() }.filter { it.isNotEmpty() }
                    .ifEmpty { listOf("默认账本") }
            }
            val monthlyBudgetCents = uiPrefs.getLong("monthly_budget_cents", 0L)
            val templates = uiPrefs.getString("saved_templates_json", null)?.let { raw ->
                runCatching {
                    QuickTemplateHelper.sanitizeTemplates(
                        jsonCodec.decodeFromString<List<QuickBookkeepingTemplate>>(raw)
                    )
                }.getOrNull()
            }.orEmpty()
            val savingPlans = uiPrefs.getString("saved_saving_plans_json", null)?.let { raw ->
                runCatching { jsonCodec.decodeFromString<List<SavingPlanItem>>(raw) }.getOrNull()
            }.orEmpty()
            val installments = uiPrefs.getString("saved_installments_json", null)?.let { raw ->
                runCatching { jsonCodec.decodeFromString<List<InstallmentPlanItem>>(raw) }.getOrNull()
            }.orEmpty()

            val freshWallets = app.repository.getAllWallets()
            val rawTxs = app.repository.getAllTransactions()
            val freshRules = app.repository.getAllRecurringRules()

            val backfilledHashes = ArrayList<Pair<Long, String>>()
            val enrichedTxs = rawTxs.map { tx ->
                val existingSha = tx.screenshotSha256.trim().lowercase(Locale.US)
                if (existingSha.length == 64 && existingSha.all { it in '0'..'9' || it in 'a'..'f' }) {
                    if (existingSha != tx.screenshotSha256 && tx.id > 0L) {
                        backfilledHashes.add(tx.id to existingSha)
                    }
                    if (existingSha == tx.screenshotSha256) tx else tx.copy(screenshotSha256 = existingSha)
                } else if (tx.screenshotPath.isNotBlank()) {
                    val computed = BillScreenshotStorage.computeFileSha256IfSafe(File(tx.screenshotPath), app)
                    if (computed != null) {
                        if (tx.id > 0L) {
                            backfilledHashes.add(tx.id to computed)
                        }
                        tx.copy(screenshotSha256 = computed)
                    } else {
                        tx
                    }
                } else {
                    tx
                }
            }
            if (backfilledHashes.isNotEmpty()) {
                runCatching { app.repository.backfillTransactionScreenshotHashes(backfilledHashes) }
            }

            val snapshotJson = BackupAndCsvTransferEngine.exportFullBackupJson(
                ledgers = ledgers,
                monthlyBudgetCents = monthlyBudgetCents,
                wallets = freshWallets,
                transactions = enrichedTxs,
                recurringRules = freshRules,
                merchantMemories = app.merchantMemoryManager.memoriesFlow.value,
                customExpenseCategories = CategoryCatalog.expenseCategories,
                customIncomeCategories = CategoryCatalog.incomeCategories,
                customTags = TagCatalog.tags,
                quickTemplates = templates,
                smartRules = app.smartRuleEngine.rules.value,
                savingPlans = savingPlans,
                installmentPlans = installments,
                customCurrencyRates = CurrencyRateManager.customRates,
                reminderSettingsJson = ReminderConfigManager.exportConfigJson(),
                appearanceSettingsJson = AppearanceConfigManager.exportConfigJson(),
                captureWhitelistJson = CaptureAppWhitelistManager.exportConfigJson(),
                dedicatedFundSettingsJson = DedicatedFundManager.exportConfigJson(),
                userProfileJson = UserProfileManager.exportBackupJson()
            )
            snapshotJson to enrichedTxs
        }

    fun computeTargetSignature(serverUrl: String, syncKey: String): String {
        val cleanUrl = serverUrl.trim().trimEnd('/').lowercase(Locale.US)
        val cleanKey = syncKey.trim().uppercase(Locale.US)
        if (cleanUrl.isEmpty() || cleanKey.isEmpty()) return ""
        return sha256Hex("$cleanUrl|$cleanKey".toByteArray(Charsets.UTF_8)).take(32)
    }

    /**
     * 生成分享给家人的服务器配对口令（仅含服务器地址与家庭接入码，不含个人 Sync Key，确保家人生成独立账本）
     */
    fun encodeFamilyPairingText(serverUrl: String, serverAccessCode: String): Result<String> {
        val validatedUrl = validateBaseUrl(serverUrl).getOrElse { return Result.failure(it) }
        val cleanUrl = validatedUrl.toString().trimEnd('/')
        val encodedUrl = URLEncoder.encode(cleanUrl, Charsets.UTF_8.name())
        val sb = StringBuilder("$PAIR_SCHEME_PREFIX?url=$encodedUrl")
        if (serverAccessCode.isNotBlank()) {
            val encodedCode = URLEncoder.encode(serverAccessCode.trim(), Charsets.UTF_8.name())
            sb.append("&code=").append(encodedCode)
        }
        return Result.success(sb.toString())
    }

    fun decodeFamilyPairingText(rawText: String): Result<Pair<String, String>> {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("剪贴板内容为空"))
        }
        if (!trimmed.startsWith("moji-pair://", ignoreCase = true) &&
            (trimmed.uppercase(Locale.US).startsWith("MOJI-") ||
                trimmed.uppercase(Locale.US).startsWith("MOJIQA-"))
        ) {
            return Result.failure(
                IllegalArgumentException("剪贴板中是「个人专属同步密钥」（用于本人换机恢复同一账本）。若给家人配置独立账本，请复制「家人配对口令」")
            )
        }
        if (trimmed.startsWith("moji-pair://", ignoreCase = true)) {
            val queryPart = trimmed.substringAfter('?', "")
            if (queryPart.isEmpty()) {
                return Result.failure(IllegalArgumentException("配对口令格式无效"))
            }
            val params = queryPart.split('&')
                .mapNotNull { kv ->
                    val idx = kv.indexOf('=')
                    if (idx <= 0) null
                    else {
                        val k = kv.substring(0, idx)
                        val v = runCatching {
                            URLDecoder.decode(kv.substring(idx + 1), Charsets.UTF_8.name())
                        }.getOrDefault("")
                        k to v
                    }
                }
                .toMap()
            val url = params["url"].orEmpty().trim()
            val code = params["code"].orEmpty().trim()
            val validated = validateBaseUrl(url).getOrElse { return Result.failure(it) }
            return Result.success(validated.toString().trimEnd('/') to code)
        }
        val directUrl = validateBaseUrl(trimmed).getOrElse {
            return Result.failure(IllegalArgumentException("未识别到有效的云端服务器地址或家人配对口令"))
        }
        return Result.success(directUrl.toString().trimEnd('/') to "")
    }

    fun validateSyncKey(rawKey: String): Result<String> {
        val cleaned = rawKey.trim().uppercase(Locale.US)
        if (!cleaned.startsWith(expectedKeyPrefix)) {
            val msg = if (BuildConfig.IS_QA_BUILD) {
                "QA 独立测试包必须使用 MOJIQA- 开头的测试密钥，禁止使用正式版密钥"
            } else {
                "同步密钥必须以 MOJI- 开头（格式如 MOJI-XXXX-XXXX-XXXX-XXXX）"
            }
            return Result.failure(IllegalArgumentException(msg))
        }
        val groups = cleaned.removePrefix(expectedKeyPrefix).split('-')
        val validAsciiGroups = groups.size in 4..8 && groups.all { g ->
            g.length == 4 && g.all { c -> c in 'A'..'Z' || c in '0'..'9' }
        }
        if (!validAsciiGroups) {
            return Result.failure(
                IllegalArgumentException("同步密钥格式无效，应为 4 组以上 4 位大写英文字母或数字（如 ${expectedKeyPrefix}8F3K-92LP-7X4Q-2M9N）")
            )
        }
        return Result.success(cleaned)
    }

    fun validateServerUrl(rawUrl: String): Result<String> =
        validateBaseUrl(rawUrl).map { it.toString().trimEnd('/') }

    private fun validateBaseUrl(rawUrl: String): Result<HttpUrl> {
        val trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("请先填写云端服务器地址（如 https://sync.example.com）"))
        }
        val parsed = trimmed.toHttpUrlOrNull()
            ?: return Result.failure(IllegalArgumentException("云端服务器地址格式无效，请输入完整的 https:// 地址"))

        val isLocalLoopback = parsed.host.equals("localhost", ignoreCase = true) ||
            parsed.host == "127.0.0.1" ||
            parsed.host == "10.0.2.2"
        if (!parsed.isHttps && !isLocalLoopback) {
            return Result.failure(
                IllegalArgumentException("为保障财务账单与同步密钥安全，公网 ECS 服务必须使用 HTTPS 加密连接")
            )
        }
        return Result.success(parsed)
    }

    private fun stableDeviceId(context: Context): String {
        val appCtx = context.applicationContext
        val rawAndroidId = runCatching {
            Settings.Secure.getString(appCtx.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty().trim()
        if (rawAndroidId.length >= 8) {
            return sha256Hex("moji-dev:$currentAppEnv:$rawAndroidId".toByteArray(Charsets.UTF_8)).take(20)
        }
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_FALLBACK_DEVICE_ID, null)
        if (!cached.isNullOrBlank()) return cached
        val generated = java.util.UUID.randomUUID().toString().replace("-", "").take(20)
        prefs.edit().putString(KEY_FALLBACK_DEVICE_ID, generated).apply()
        return generated
    }

    suspend fun verifyAndBindKey(
        context: Context,
        draftConfig: MoJiCloudConfig
    ): Result<Pair<MoJiCloudConfig, String>> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            performKeyLoginInternal(context, draftConfig, persistImmediately = true)
        }
    }

    private fun mergeWithLatestPersistedState(draft: MoJiCloudConfig): MoJiCloudConfig {
        val latest = _configFlow.value
        if (latest.currentTargetSignature != draft.currentTargetSignature) return draft
        val mergedLastRev = maxOf(draft.lastSyncedRevision, latest.lastSyncedRevision)
        val useLatestToken = latest.accessToken.isNotBlank() &&
            latest.tokenExpiresAtMillis > draft.tokenExpiresAtMillis
        return draft.copy(
            userId = latest.userId.ifBlank { draft.userId },
            accessToken = if (useLatestToken) latest.accessToken else draft.accessToken,
            tokenExpiresAtMillis = maxOf(draft.tokenExpiresAtMillis, latest.tokenExpiresAtMillis),
            boundTargetSignature = latest.boundTargetSignature.ifBlank { draft.boundTargetSignature },
            lastSyncedRevision = mergedLastRev,
            remoteRevision = maxOf(draft.remoteRevision, latest.remoteRevision)
        )
    }

    private fun performKeyLoginInternal(
        context: Context,
        draftConfig: MoJiCloudConfig,
        persistImmediately: Boolean
    ): Result<Pair<MoJiCloudConfig, String>> {
        val baseUrl = validateBaseUrl(draftConfig.serverUrl).getOrElse { return Result.failure(it) }
        val normalizedKey = validateSyncKey(draftConfig.syncKey).getOrElse { return Result.failure(it) }
        val cleanMember = draftConfig.memberLabel.trim().ifBlank { defaultDeviceName() }

        return try {
            val loginUrl = baseUrl.newBuilder()
                .addPathSegments("api/v1/auth/key-login")
                .build()
            val reqPayload = KeyLoginReqDto(
                syncKey = normalizedKey,
                serverAccessCode = draftConfig.serverAccessCode.trim().takeIf { it.isNotEmpty() },
                memberLabel = cleanMember,
                deviceId = stableDeviceId(context),
                deviceName = defaultDeviceName(),
                appEnv = currentAppEnv,
                appVersion = BuildConfig.VERSION_NAME
            )
            val bodyBytes = jsonCodec.encodeToString(reqPayload).toByteArray(Charsets.UTF_8)
            val req = Request.Builder()
                .url(loginUrl)
                .post(bodyBytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val respText = readCappedUtf8(resp.body?.byteStream(), 2 * 1024 * 1024)
                if (!resp.isSuccessful) {
                    val errDto = runCatching { jsonCodec.decodeFromString<ErrorRespDto>(respText) }.getOrNull()
                    val msg = errDto?.message?.takeIf { it.isNotBlank() }
                        ?: "云端鉴权失败（HTTP ${resp.code}）"
                    return Result.failure(IllegalStateException(msg))
                }
                val loginResp = jsonCodec.decodeFromString<KeyLoginRespDto>(respText)
                val summary = loginResp.accountSummary
                val targetSig = computeTargetSignature(baseUrl.toString(), normalizedKey)
                val latestStored = _configFlow.value
                val prevLastRev = when {
                    latestStored.boundTargetSignature == targetSig -> latestStored.lastSyncedRevision
                    draftConfig.boundTargetSignature == targetSig -> draftConfig.lastSyncedRevision
                    else -> 0L
                }
                val updatedByLabel = summary.updatedByMember.ifBlank { summary.updatedByDevice }
                val updatedCfg = draftConfig.copy(
                    serverUrl = baseUrl.toString().trimEnd('/'),
                    syncKey = normalizedKey,
                    memberLabel = cleanMember,
                    userId = loginResp.userId,
                    accessToken = loginResp.accessToken,
                    tokenExpiresAtMillis = loginResp.expiresAtMs,
                    boundTargetSignature = targetSig,
                    lastSyncedRevision = prevLastRev,
                    remoteRevision = summary.latestRevision,
                    remoteTxCount = summary.txCount,
                    remoteWalletCount = summary.walletCount,
                    remoteScreenshotCount = summary.screenshotCount,
                    remoteUpdatedBy = updatedByLabel,
                    remoteUpdatedAtMillis = summary.updatedAtMs
                )
                if (persistImmediately) {
                    saveConfig(context, updatedCfg)
                }

                val tip = if (loginResp.isNewAccount || summary.latestRevision == 0L) {
                    "✅ 密钥绑定成功（云端尚无快照），点击「上传到云端」即可建立首次备份"
                } else {
                    val who = if (updatedByLabel.isNotBlank()) " · 由「$updatedByLabel」更新" else ""
                    "✅ 已连接云端：当前最新 v${summary.latestRevision}（${summary.txCount} 笔账单 / ${summary.screenshotCount} 张原图$who）"
                }
                Result.success(updatedCfg to tip)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IllegalStateException("连接云端服务器失败：${e.message ?: e.javaClass.simpleName}", e))
        }
    }

    private fun ensureValidSessionInternal(
        context: Context,
        draftConfig: MoJiCloudConfig,
        persistImmediately: Boolean
    ): Result<MoJiCloudConfig> {
        val merged = mergeWithLatestPersistedState(draftConfig)
        val now = System.currentTimeMillis()
        if (merged.isTargetBound &&
            merged.accessToken.isNotBlank() &&
            merged.tokenExpiresAtMillis > now + 60_000L
        ) {
            return Result.success(merged)
        }
        return performKeyLoginInternal(context, merged, persistImmediately = persistImmediately).map { it.first }
    }

    /**
     * 后台自动同步专用入口：在持有 `syncMutex` 后才读取最新 `_configFlow` 与构建数据库快照，
     * 彻底避免与手动上传或密钥重置产生旧配置回写竞态。
     */
    suspend fun autoPushFromBackground(app: MoJiApp): Result<String> = withContext(Dispatchers.IO) {
        if (shouldSuppressAutoSync()) {
            return@withContext Result.success("suppressed")
        }
        syncMutex.withLock {
            if (shouldSuppressAutoSync()) {
                return@withContext Result.success("suppressed")
            }
            val latestCfg = _configFlow.value
            if (!latestCfg.canAutoPushSafely) {
                return@withContext Result.success("not_eligible")
            }
            val (snapshotJson, enrichedTxs) = buildSnapshotFromApp(app)
            uploadInternalUnderLock(
                context = app,
                draftConfig = latestCfg,
                backupJson = snapshotJson,
                localTransactions = enrichedTxs,
                forceOverwrite = false,
                isBackgroundAutoSync = true
            ).map { it.second }
        }
    }

    suspend fun uploadSnapshotAndScreenshots(
        context: Context,
        draftConfig: MoJiCloudConfig,
        backupJson: String,
        localTransactions: List<TransactionEntity>,
        forceOverwrite: Boolean = false
    ): Result<Pair<MoJiCloudConfig, String>> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            uploadInternalUnderLock(
                context = context,
                draftConfig = draftConfig,
                backupJson = backupJson,
                localTransactions = localTransactions,
                forceOverwrite = forceOverwrite,
                isBackgroundAutoSync = false
            )
        }
    }

    private fun uploadInternalUnderLock(
        context: Context,
        draftConfig: MoJiCloudConfig,
        backupJson: String,
        localTransactions: List<TransactionEntity>,
        forceOverwrite: Boolean,
        isBackgroundAutoSync: Boolean
    ): Result<Pair<MoJiCloudConfig, String>> {
        var authedConfig = ensureValidSessionInternal(context, draftConfig, persistImmediately = true)
            .getOrElse { return Result.failure(it) }
        val baseUrl = validateBaseUrl(authedConfig.serverUrl)
            .getOrElse { return Result.failure(it) }

        return try {
            val attemptResult = runUploadAttemptOnce(
                context = context,
                baseUrl = baseUrl,
                config = authedConfig,
                backupJson = backupJson,
                localTransactions = localTransactions,
                forceOverwrite = forceOverwrite
            )

            val (uploadedScreenshotCount, finalSummary) = if (attemptResult.isFailure &&
                attemptResult.exceptionOrNull() is CloudUnauthorizedException
            ) {
                // Token 签名在服务端已轮换失效：刷新会话后完整重跑「缺失原图上传 + 快照上传」
                authedConfig = performKeyLoginInternal(context, authedConfig, persistImmediately = true)
                    .getOrElse { return Result.failure(it) }.first
                runUploadAttemptOnce(
                    context = context,
                    baseUrl = baseUrl,
                    config = authedConfig,
                    backupJson = backupJson,
                    localTransactions = localTransactions,
                    forceOverwrite = forceOverwrite
                ).getOrElse { err ->
                    handleConflictMetadataIfNeeded(context, authedConfig, err)
                    return Result.failure(err)
                }
            } else {
                attemptResult.getOrElse { err ->
                    handleConflictMetadataIfNeeded(context, authedConfig, err)
                    return Result.failure(err)
                }
            }

            // 若在后台上传期间用户切换了别的密钥或关闭了自动同步，尊重最新开关且绝不覆盖新密钥
            val currentStored = _configFlow.value
            if (isBackgroundAutoSync && currentStored.currentTargetSignature != authedConfig.currentTargetSignature) {
                return Result.failure(IllegalStateException("同步目标已变更，放弃写回旧会话状态"))
            }
            val effectiveAutoSync = if (isBackgroundAutoSync &&
                currentStored.currentTargetSignature == authedConfig.currentTargetSignature
            ) {
                currentStored.autoSyncEnabled
            } else {
                authedConfig.autoSyncEnabled
            }

            val now = System.currentTimeMillis()
            val updatedCfg = authedConfig.copy(
                autoSyncEnabled = effectiveAutoSync,
                lastSyncedRevision = finalSummary.latestRevision,
                lastSyncTimeMillis = now,
                remoteRevision = finalSummary.latestRevision,
                remoteTxCount = finalSummary.txCount,
                remoteWalletCount = finalSummary.walletCount,
                remoteScreenshotCount = finalSummary.screenshotCount,
                remoteUpdatedBy = finalSummary.updatedByMember.ifBlank { finalSummary.updatedByDevice },
                remoteUpdatedAtMillis = finalSummary.updatedAtMs
            )
            saveConfig(context, updatedCfg)
            clearPendingAutoPush(context)

            val kbStr = String.format(Locale.US, "%.1f", backupJson.toByteArray(Charsets.UTF_8).size / 1024.0)
            val ssNote = if (uploadedScreenshotCount > 0) "，新增上传 $uploadedScreenshotCount 张账单原图" else ""
            Result.success(
                updatedCfg to "✅ 云端同步成功：v${finalSummary.latestRevision}（${finalSummary.txCount} 笔账单 · $kbStr KB$ssNote）"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudSyncConflictException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("上传云端备份失败：${e.message ?: e.javaClass.simpleName}", e))
        }
    }

    private fun handleConflictMetadataIfNeeded(
        context: Context,
        authedConfig: MoJiCloudConfig,
        err: Throwable
    ) {
        if (err is CloudSyncConflictException && err.remoteSummary != null) {
            val currentStored = _configFlow.value
            if (currentStored.currentTargetSignature != authedConfig.currentTargetSignature) return
            val s = err.remoteSummary
            val preservedLastRev = maxOf(currentStored.lastSyncedRevision, authedConfig.lastSyncedRevision)
            val syncedMeta = authedConfig.copy(
                autoSyncEnabled = currentStored.autoSyncEnabled,
                lastSyncedRevision = preservedLastRev,
                remoteRevision = s.latestRevision,
                remoteTxCount = s.txCount,
                remoteWalletCount = s.walletCount,
                remoteScreenshotCount = s.screenshotCount,
                remoteUpdatedBy = s.updatedByMember.ifBlank { s.updatedByDevice },
                remoteUpdatedAtMillis = s.updatedAtMs
            )
            saveConfig(context, syncedMeta)
        }
    }

    private fun runUploadAttemptOnce(
        context: Context,
        baseUrl: HttpUrl,
        config: MoJiCloudConfig,
        backupJson: String,
        localTransactions: List<TransactionEntity>,
        forceOverwrite: Boolean
    ): Result<Pair<Int, AccountSummaryDto>> {
        val uploadedScreenshots = uploadMissingScreenshotsInternal(
            context = context,
            baseUrl = baseUrl,
            accessToken = config.accessToken,
            transactions = localTransactions
        ).getOrElse { return Result.failure(it) }

        val summary = putSnapshotOnceInternal(
            context = context,
            baseUrl = baseUrl,
            config = config,
            backupJson = backupJson,
            forceOverwrite = forceOverwrite
        ).getOrElse { return Result.failure(it) }

        return Result.success(uploadedScreenshots to summary)
    }

    private fun putSnapshotOnceInternal(
        context: Context,
        baseUrl: HttpUrl,
        config: MoJiCloudConfig,
        backupJson: String,
        forceOverwrite: Boolean
    ): Result<AccountSummaryDto> {
        val snapUrl = baseUrl.newBuilder()
            .addPathSegments("api/v1/sync/snapshot")
            .build()
        val reqDto = PutSnapshotReqDto(
            expectedRevision = config.effectiveLastSyncedRevision,
            forceOverwrite = forceOverwrite,
            deviceId = stableDeviceId(context),
            deviceName = defaultDeviceName(),
            memberLabel = config.memberLabel.trim().ifBlank { defaultDeviceName() },
            snapshotJson = backupJson
        )
        val bodyBytes = jsonCodec.encodeToString(reqDto).toByteArray(Charsets.UTF_8)
        val req = Request.Builder()
            .url(snapUrl)
            .header("Authorization", "Bearer ${config.accessToken}")
            .put(bodyBytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val respText = readCappedUtf8(resp.body?.byteStream(), 2 * 1024 * 1024)
            if (resp.code == 401) {
                return Result.failure(CloudUnauthorizedException("云端会话凭证已过期"))
            }
            if (resp.code == 409) {
                val errDto = runCatching { jsonCodec.decodeFromString<ErrorRespDto>(respText) }.getOrNull()
                return Result.failure(
                    CloudSyncConflictException(
                        message = errDto?.message?.ifBlank { "云端存在更新的数据版本，已拦截覆盖" }
                            ?: "云端存在更新的数据版本，已拦截覆盖",
                        remoteSummary = errDto?.accountSummary
                    )
                )
            }
            if (!resp.isSuccessful) {
                val errDto = runCatching { jsonCodec.decodeFromString<ErrorRespDto>(respText) }.getOrNull()
                val msg = errDto?.message?.takeIf { it.isNotBlank() }
                    ?: "上传快照返回 HTTP ${resp.code}"
                return Result.failure(IllegalStateException("$msg (HTTP ${resp.code})"))
            }
            val parsed = jsonCodec.decodeFromString<PutSnapshotRespDto>(respText)
            return Result.success(parsed.accountSummary)
        }
    }

    private fun uploadMissingScreenshotsInternal(
        context: Context,
        baseUrl: HttpUrl,
        accessToken: String,
        transactions: List<TransactionEntity>
    ): Result<Int> {
        val localScreenshotMap = LinkedHashMap<String, File>()
        for (tx in transactions) {
            if (tx.screenshotPath.isBlank()) continue
            val file = File(tx.screenshotPath)
            if (!BillScreenshotStorage.isSafeScreenshotFile(file, context)) continue
            val sha = tx.screenshotSha256.trim().lowercase(Locale.US).takeIf {
                it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' }
            } ?: BillScreenshotStorage.computeFileSha256IfSafe(file, context) ?: continue
            localScreenshotMap.putIfAbsent(sha, file)
        }
        if (localScreenshotMap.isEmpty()) return Result.success(0)

        val checkUrl = baseUrl.newBuilder()
            .addPathSegments("api/v1/screenshots/missing")
            .build()
        val checkPayload = jsonCodec.encodeToString(
            MissingScreenshotsReqDto(sha256List = localScreenshotMap.keys.toList())
        ).toByteArray(Charsets.UTF_8)
        val checkReq = Request.Builder()
            .url(checkUrl)
            .header("Authorization", "Bearer $accessToken")
            .post(checkPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val missingHashes = httpClient.newCall(checkReq).execute().use { resp ->
            val txt = readCappedUtf8(resp.body?.byteStream(), 2 * 1024 * 1024)
            if (resp.code == 401) {
                return Result.failure(CloudUnauthorizedException("检查原图时凭证已过期"))
            }
            if (!resp.isSuccessful) {
                val errDto = runCatching { jsonCodec.decodeFromString<ErrorRespDto>(txt) }.getOrNull()
                val msg = errDto?.message?.takeIf { it.isNotBlank() }
                    ?: "检查云端缺失原图失败（HTTP ${resp.code}）"
                return Result.failure(IllegalStateException(msg))
            }
            runCatching { jsonCodec.decodeFromString<MissingScreenshotsRespDto>(txt).missingSha256 }
                .getOrElse { err ->
                    return Result.failure(IllegalStateException("解析云端原图校验响应失败：${err.message}", err))
                }
        }

        var uploaded = 0
        for (sha in missingHashes) {
            val file = localScreenshotMap[sha] ?: continue
            if (file.length() !in 1L..MAX_SCREENSHOT_BYTES.toLong()) continue
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            if (bytes.isEmpty() || bytes.size > MAX_SCREENSHOT_BYTES) continue
            if (!BillScreenshotStorage.isValidWebpBytes(bytes)) continue
            if (sha256Hex(bytes) != sha) continue

            val putUrl = baseUrl.newBuilder()
                .addPathSegments("api/v1/screenshots")
                .addPathSegment(sha)
                .build()
            val putReq = Request.Builder()
                .url(putUrl)
                .header("Authorization", "Bearer $accessToken")
                .put(bytes.toRequestBody("image/webp".toMediaType()))
                .build()
            httpClient.newCall(putReq).execute().use { putResp ->
                val respTxt = readCappedUtf8(putResp.body?.byteStream(), 64 * 1024)
                if (putResp.code == 401) {
                    return Result.failure(CloudUnauthorizedException("上传原图时凭证已过期"))
                }
                if (!putResp.isSuccessful) {
                    val errDto = runCatching { jsonCodec.decodeFromString<ErrorRespDto>(respTxt) }.getOrNull()
                    val msg = errDto?.message?.takeIf { it.isNotBlank() }
                        ?: "上传账单原图失败（HTTP ${putResp.code}）"
                    return Result.failure(IllegalStateException(msg))
                }
                uploaded++
            }
        }
        return Result.success(uploaded)
    }

    suspend fun downloadSnapshotAndScreenshots(
        context: Context,
        draftConfig: MoJiCloudConfig
    ): Result<CloudPulledRestoreBundle> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            // 注意：此处仅在重登时持久化新 accessToken（保留原 lastSyncedRevision），绝不在本地入库前提前推进 lastSyncedRevision！
            var authedConfig = ensureValidSessionInternal(context, draftConfig, persistImmediately = true)
                .getOrElse { return@withContext Result.failure(it) }
            val baseUrl = validateBaseUrl(authedConfig.serverUrl)
                .getOrElse { return@withContext Result.failure(it) }

            try {
                val firstPull = pullSnapshotDtoOnceInternal(baseUrl, authedConfig.accessToken)
                val pulledDto = if (firstPull.isFailure && firstPull.exceptionOrNull() is CloudUnauthorizedException) {
                    authedConfig = performKeyLoginInternal(context, authedConfig, persistImmediately = true)
                        .getOrElse { return@withContext Result.failure(it) }.first
                    pullSnapshotDtoOnceInternal(baseUrl, authedConfig.accessToken)
                        .getOrElse { return@withContext Result.failure(it) }
                } else {
                    firstPull.getOrElse { return@withContext Result.failure(it) }
                }

                val parsedTriple = BackupAndCsvTransferEngine.parseFullBackupJson(pulledDto.snapshotJson)
                    ?: return@withContext Result.failure(IllegalStateException("云端快照数据格式损坏，无法解析"))

                val (wallets, rawTransactions, snapshot) = parsedTriple
                val rules = BackupAndCsvTransferEngine.toRecurringRuleEntities(snapshot.recurringRules)
                val availableRemoteSet = pulledDto.availableScreenshots.map { it.lowercase(Locale.US) }.toSet()

                var downloadedCount = 0
                val resolvedPathBySha = HashMap<String, String>()
                val failedShas = HashSet<String>()
                var refreshedDuringScreenshots = false

                val rewrittenTransactions = rawTransactions.map { tx ->
                    val sha = tx.screenshotSha256.trim().lowercase(Locale.US)
                    if (sha.length != 64 || !sha.all { it in '0'..'9' || it in 'a'..'f' }) {
                        val keepPath = tx.screenshotPath.takeIf {
                            it.isNotBlank() && BillScreenshotStorage.isSafeScreenshotFile(File(it), context)
                        }.orEmpty()
                        tx.copy(screenshotPath = keepPath)
                    } else {
                        val cachedLocal = resolvedPathBySha[sha]
                            ?: BillScreenshotStorage.resolveOrSaveCloudScreenshot(
                                context = context,
                                expectedSha256Raw = sha,
                                existingPathHint = tx.screenshotPath,
                                downloadedWebpBytes = null
                            )
                        val finalPath = if (cachedLocal != null) {
                            resolvedPathBySha[sha] = cachedLocal
                            cachedLocal
                        } else if (sha in availableRemoteSet && sha !in failedShas) {
                            var dlRes = downloadSingleScreenshotBytes(baseUrl, authedConfig.accessToken, sha)
                            if (dlRes.isFailure &&
                                dlRes.exceptionOrNull() is CloudUnauthorizedException &&
                                !refreshedDuringScreenshots
                            ) {
                                refreshedDuringScreenshots = true
                                performKeyLoginInternal(context, authedConfig, persistImmediately = true)
                                    .getOrNull()?.let { (newCfg, _) ->
                                        authedConfig = newCfg
                                        dlRes = downloadSingleScreenshotBytes(baseUrl, authedConfig.accessToken, sha)
                                    }
                            }
                            val dlBytes = dlRes.getOrNull()
                            val savedPath = if (dlBytes != null) {
                                BillScreenshotStorage.resolveOrSaveCloudScreenshot(
                                    context = context,
                                    expectedSha256Raw = sha,
                                    existingPathHint = null,
                                    downloadedWebpBytes = dlBytes
                                )
                            } else {
                                null
                            }
                            if (savedPath != null) {
                                downloadedCount++
                                resolvedPathBySha[sha] = savedPath
                            } else {
                                failedShas.add(sha)
                            }
                            savedPath.orEmpty()
                        } else {
                            ""
                        }
                        tx.copy(screenshotPath = finalPath, screenshotSha256 = sha)
                    }
                }

                val now = System.currentTimeMillis()
                // 仅构造待提交的 updatedCfg 返回给调用方，待本地 Room 事务恢复成功后再由 commitPulledConfigAfterRestore 正式写入！
                val updatedCfg = authedConfig.copy(
                    lastSyncedRevision = pulledDto.revision,
                    lastSyncTimeMillis = now,
                    remoteRevision = pulledDto.revision,
                    remoteTxCount = rewrittenTransactions.size,
                    remoteWalletCount = wallets.size,
                    remoteScreenshotCount = availableRemoteSet.size,
                    remoteUpdatedBy = pulledDto.updatedByMember.ifBlank { pulledDto.updatedByDevice },
                    remoteUpdatedAtMillis = pulledDto.updatedAtMs
                )

                Result.success(
                    CloudPulledRestoreBundle(
                        wallets = wallets,
                        transactions = rewrittenTransactions,
                        recurringRules = rules,
                        snapshot = snapshot,
                        revision = pulledDto.revision,
                        syncedScreenshots = downloadedCount,
                        failedScreenshots = failedShas.size,
                        updatedConfig = updatedCfg
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(IllegalStateException("从云端拉取恢复失败：${e.message ?: e.javaClass.simpleName}", e))
            }
        }
    }

    private fun pullSnapshotDtoOnceInternal(
        baseUrl: HttpUrl,
        accessToken: String
    ): Result<PulledSnapshotRespDto> {
        val getUrl = baseUrl.newBuilder()
            .addPathSegments("api/v1/sync/snapshot")
            .build()
        val req = Request.Builder()
            .url(getUrl)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val respText = readCappedUtf8(resp.body?.byteStream(), MAX_SNAPSHOT_BYTES)
            if (resp.code == 401) {
                return Result.failure(CloudUnauthorizedException("拉取快照时凭证已过期"))
            }
            if (!resp.isSuccessful) {
                val errDto = runCatching { jsonCodec.decodeFromString<ErrorRespDto>(respText) }.getOrNull()
                val msg = errDto?.message?.takeIf { it.isNotBlank() }
                    ?: "下载云端快照失败（HTTP ${resp.code}）"
                return Result.failure(IllegalStateException(msg))
            }
            return Result.success(jsonCodec.decodeFromString<PulledSnapshotRespDto>(respText))
        }
    }

    private fun downloadSingleScreenshotBytes(
        baseUrl: HttpUrl,
        accessToken: String,
        sha256: String
    ): Result<ByteArray> {
        val url = baseUrl.newBuilder()
            .addPathSegments("api/v1/screenshots")
            .addPathSegment(sha256)
            .build()
        var lastError: Throwable? = null
        for (attempt in 0..1) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401) {
                        return Result.failure(CloudUnauthorizedException("下载原图时凭证已过期"))
                    }
                    if (resp.isSuccessful) {
                        val bytes = readCappedBytes(resp.body?.byteStream(), MAX_SCREENSHOT_BYTES)
                        if (BillScreenshotStorage.isValidWebpBytes(bytes)) {
                            return Result.success(bytes)
                        }
                        return Result.failure(IllegalStateException("云端原图非合法 WebP 格式"))
                    }
                    if (resp.code in 500..599 && attempt == 0) {
                        lastError = IllegalStateException("HTTP ${resp.code}")
                    } else {
                        return Result.failure(IllegalStateException("下载原图失败（HTTP ${resp.code}）"))
                    }
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt > 0) break
            }
        }
        return Result.failure(lastError ?: IllegalStateException("下载原图失败"))
    }

    private fun readCappedBytes(stream: java.io.InputStream?, maxBytes: Int): ByteArray {
        stream ?: return ByteArray(0)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) {
                throw IllegalStateException("云端响应体积超出安全上限（>${maxBytes / (1024 * 1024)}MB）")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun readCappedUtf8(stream: java.io.InputStream?, maxBytes: Int): String {
        return readCappedBytes(stream, maxBytes).toString(Charsets.UTF_8)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
