package com.moji.ai.data.backup

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.moji.ai.BuildConfig
import com.moji.ai.MoJiApp
import com.moji.ai.data.local.RecurringRuleEntity
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.AppearanceConfigManager
import com.moji.ai.data.model.CategoryCatalog
import com.moji.ai.data.model.CurrencyRateManager
import com.moji.ai.data.model.ReminderConfigManager
import com.moji.ai.data.model.TagCatalog
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

data class CloudPulledRestoreBundle(
    val wallets: List<WalletEntity>,
    val transactions: List<TransactionEntity>,
    val recurringRules: List<RecurringRuleEntity>,
    val snapshot: MoJiFullBackupSnapshot,
    val revision: Long,
    val syncedScreenshots: Int,
    val updatedConfig: MoJiCloudConfig
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

    private val _configFlow = MutableStateFlow(MoJiCloudConfig())
    val configFlow: StateFlow<MoJiCloudConfig> = _configFlow.asStateFlow()

    @Volatile
    var isRestoringSnapshot: Boolean = false

    fun init(context: Context) {
        _configFlow.value = loadConfig(context)
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
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
        val ensuredLabel = base.memberLabel.trim().ifBlank { defaultDeviceName() }
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
     * 从 Application 全局上下文构建完整备份快照（支持纯后台静默入账后无需打开 Activity 即可自动推送上云）
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
            val freshTxs = app.repository.getAllTransactions()
            val freshRules = app.repository.getAllRecurringRules()

            val snapshotJson = BackupAndCsvTransferEngine.exportFullBackupJson(
                ledgers = ledgers,
                monthlyBudgetCents = monthlyBudgetCents,
                wallets = freshWallets,
                transactions = freshTxs,
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
                appearanceSettingsJson = AppearanceConfigManager.exportConfigJson()
            )
            snapshotJson to freshTxs
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
        if (groups.size !in 4..8 || groups.any { g -> g.length != 4 || !g.all { it.isLetterOrDigit() } }) {
            return Result.failure(
                IllegalArgumentException("同步密钥格式无效，应为 4 组以上 4 位字母数字（如 ${expectedKeyPrefix}8F3K-92LP-7X4Q-2M9N）")
            )
        }
        return Result.success(cleaned)
    }

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
            performKeyLoginInternal(context, draftConfig)
        }
    }

    private fun performKeyLoginInternal(
        context: Context,
        draftConfig: MoJiCloudConfig
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
                val prevLastRev = if (draftConfig.boundTargetSignature == targetSig) {
                    draftConfig.lastSyncedRevision
                } else {
                    0L
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
                saveConfig(context, updatedCfg)

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
        draftConfig: MoJiCloudConfig
    ): Result<MoJiCloudConfig> {
        val now = System.currentTimeMillis()
        if (draftConfig.isTargetBound &&
            draftConfig.accessToken.isNotBlank() &&
            draftConfig.tokenExpiresAtMillis > now + 60_000L
        ) {
            return Result.success(draftConfig)
        }
        return performKeyLoginInternal(context, draftConfig).map { it.first }
    }

    suspend fun uploadSnapshotAndScreenshots(
        context: Context,
        draftConfig: MoJiCloudConfig,
        backupJson: String,
        localTransactions: List<TransactionEntity>,
        forceOverwrite: Boolean = false
    ): Result<Pair<MoJiCloudConfig, String>> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            var authedConfig = ensureValidSessionInternal(context, draftConfig)
                .getOrElse { return@withContext Result.failure(it) }
            val baseUrl = validateBaseUrl(authedConfig.serverUrl)
                .getOrElse { return@withContext Result.failure(it) }

            try {
                // 1. 先增量上传所有尚未同步的识屏账单 WebP 原图（确保快照引用完整性）
                val uploadedScreenshotCount = uploadMissingScreenshotsInternal(
                    context = context,
                    baseUrl = baseUrl,
                    accessToken = authedConfig.accessToken,
                    transactions = localTransactions
                )

                // 2. 上传全量账本快照（如遇到 Token 401 失效则自动重签重试一次）
                val snapshotRes = putSnapshotOnceInternal(
                    context = context,
                    baseUrl = baseUrl,
                    config = authedConfig,
                    backupJson = backupJson,
                    forceOverwrite = forceOverwrite
                )
                val finalSummary = if (snapshotRes.isFailure &&
                    snapshotRes.exceptionOrNull()?.message?.contains("HTTP 401") == true
                ) {
                    authedConfig = performKeyLoginInternal(context, authedConfig)
                        .getOrElse { return@withContext Result.failure(it) }.first
                    putSnapshotOnceInternal(
                        context = context,
                        baseUrl = baseUrl,
                        config = authedConfig,
                        backupJson = backupJson,
                        forceOverwrite = forceOverwrite
                    ).getOrElse { return@withContext Result.failure(it) }
                } else {
                    snapshotRes.getOrElse { err ->
                        if (err is CloudSyncConflictException && err.remoteSummary != null) {
                            val s = err.remoteSummary
                            val syncedMeta = authedConfig.copy(
                                remoteRevision = s.latestRevision,
                                remoteTxCount = s.txCount,
                                remoteWalletCount = s.walletCount,
                                remoteScreenshotCount = s.screenshotCount,
                                remoteUpdatedBy = s.updatedByMember.ifBlank { s.updatedByDevice },
                                remoteUpdatedAtMillis = s.updatedAtMs
                            )
                            saveConfig(context, syncedMeta)
                        }
                        return@withContext Result.failure(err)
                    }
                }

                val now = System.currentTimeMillis()
                val updatedCfg = authedConfig.copy(
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
    ): Int {
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
        if (localScreenshotMap.isEmpty()) return 0

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
            if (!resp.isSuccessful) return 0
            val txt = readCappedUtf8(resp.body?.byteStream(), 2 * 1024 * 1024)
            runCatching { jsonCodec.decodeFromString<MissingScreenshotsRespDto>(txt).missingSha256 }
                .getOrDefault(emptyList())
        }

        var uploaded = 0
        for (sha in missingHashes) {
            val file = localScreenshotMap[sha] ?: continue
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            if (bytes.isEmpty() || bytes.size > MAX_SCREENSHOT_BYTES) continue
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
                if (putResp.isSuccessful) {
                    uploaded++
                }
            }
        }
        return uploaded
    }

    suspend fun downloadSnapshotAndScreenshots(
        context: Context,
        draftConfig: MoJiCloudConfig
    ): Result<CloudPulledRestoreBundle> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val authedConfig = ensureValidSessionInternal(context, draftConfig)
                .getOrElse { return@withContext Result.failure(it) }
            val baseUrl = validateBaseUrl(authedConfig.serverUrl)
                .getOrElse { return@withContext Result.failure(it) }

            try {
                val getUrl = baseUrl.newBuilder()
                    .addPathSegments("api/v1/sync/snapshot")
                    .build()
                val req = Request.Builder()
                    .url(getUrl)
                    .header("Authorization", "Bearer ${authedConfig.accessToken}")
                    .get()
                    .build()

                val pulledDto = httpClient.newCall(req).execute().use { resp ->
                    val respText = readCappedUtf8(resp.body?.byteStream(), MAX_SNAPSHOT_BYTES)
                    if (!resp.isSuccessful) {
                        val errDto = runCatching { jsonCodec.decodeFromString<ErrorRespDto>(respText) }.getOrNull()
                        val msg = errDto?.message?.takeIf { it.isNotBlank() }
                            ?: "下载云端快照失败（HTTP ${resp.code}）"
                        return@withContext Result.failure(IllegalStateException(msg))
                    }
                    jsonCodec.decodeFromString<PulledSnapshotRespDto>(respText)
                }

                val parsedTriple = BackupAndCsvTransferEngine.parseFullBackupJson(pulledDto.snapshotJson)
                    ?: return@withContext Result.failure(IllegalStateException("云端快照数据格式损坏，无法解析"))

                val (wallets, rawTransactions, snapshot) = parsedTriple
                val rules = BackupAndCsvTransferEngine.toRecurringRuleEntities(snapshot.recurringRules)
                val availableRemoteSet = pulledDto.availableScreenshots.map { it.lowercase(Locale.US) }.toSet()

                var downloadedCount = 0
                val resolvedPathBySha = HashMap<String, String>()

                val rewrittenTransactions = rawTransactions.map { tx ->
                    val sha = tx.screenshotSha256.trim().lowercase(Locale.US)
                    if (sha.length != 64 || !sha.all { it in '0'..'9' || it in 'a'..'f' }) {
                        // 若无 SHA-256，仅当本地原路径确实为本机安全截图时保留，否则置空防跨设备断链
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
                        } else if (sha in availableRemoteSet) {
                            val dlBytes = downloadSingleScreenshotBytes(baseUrl, authedConfig.accessToken, sha)
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
                            }
                            savedPath.orEmpty()
                        } else {
                            ""
                        }
                        tx.copy(screenshotPath = finalPath, screenshotSha256 = sha)
                    }
                }

                val now = System.currentTimeMillis()
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
                saveConfig(context, updatedCfg)

                Result.success(
                    CloudPulledRestoreBundle(
                        wallets = wallets,
                        transactions = rewrittenTransactions,
                        recurringRules = rules,
                        snapshot = snapshot,
                        revision = pulledDto.revision,
                        syncedScreenshots = downloadedCount,
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

    private fun downloadSingleScreenshotBytes(
        baseUrl: HttpUrl,
        accessToken: String,
        sha256: String
    ): ByteArray? {
        return runCatching {
            val url = baseUrl.newBuilder()
                .addPathSegments("api/v1/screenshots")
                .addPathSegment(sha256)
                .build()
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                readCappedBytes(resp.body?.byteStream(), MAX_SCREENSHOT_BYTES)
            }
        }.getOrNull()
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
