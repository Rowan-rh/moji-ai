package com.moji.ai.ai

import android.content.Context
import android.content.SharedPreferences
import android.provider.Telephony
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

@Serializable
enum class CaptureAppGroup(
    val title: String,
    val subtitle: String
) {
    CORE_PAYMENT_AND_BANK(
        title = "核心支付与银行短信",
        subtitle = "日常扫码、转账与银行卡动账核心通道，建议双通道保持开启"
    ),
    ECOMMERCE_AND_LIFE(
        title = "电商与外卖平台",
        subtitle = "默认仅开通知监听；关闭无障碍自动扫屏可避免逛商品/订单页误触"
    ),
    CONTENT_AND_SOCIAL(
        title = "内容社区与特卖",
        subtitle = "刷短视频与图文高频场景，默认关闭自动监听以杜绝误识别"
    ),
    CUSTOM(
        title = "自定义应用",
        subtitle = "手动添加的地方城商行或专用支付应用包名"
    )
}

@Serializable
data class CaptureAppWhitelistEntry(
    val packageName: String = "",
    val appName: String = "",
    val group: CaptureAppGroup = CaptureAppGroup.CUSTOM,
    val enableNotification: Boolean = true,
    val enableAccessibility: Boolean = false,
    val isCustom: Boolean = false
)

@Serializable
private data class CaptureWhitelistBackupEnvelope(
    val version: Int = 2,
    val entries: List<CaptureAppWhitelistEntry> = emptyList(),
    val blockedWindows: List<WindowPageSignature> = emptyList()
)

/**
 * 应用粒度双通道静默识账白名单与窗口结构签名屏蔽管理器：
 * 1. 将「通知栏自动识账」与「无障碍支付页自动扫屏」按应用包名独立解耦开关；
 * 2. 热路径采用 `@Volatile` 不可变快照集合，O(1) 无锁判准，非白名单应用与已屏蔽页面结构 0 节点遍历开销；
 * 3. 系统短信代表项 `com.android.mms` 动态兼容各厂商默认短信包名（带 60s 内存缓存避免高频 Binder 调用）；
 * 4. 支持按 `(packageName, activityName, structureFingerprint)` 三元组一键屏蔽误识别页面结构，并随备份/云同步自动迁移；
 * 5. 不限制控制中心「默记识屏记账」快捷设置磁贴的手动强制识屏。
 */
object CaptureAppWhitelistManager {

    const val PREFS_NAME = "moji_capture_whitelist_prefs"
    const val KEY_WHITELIST_JSON = "capture_app_whitelist_v1"
    const val KEY_BLOCKED_WINDOWS_JSON = "capture_blocked_window_signatures_v1"
    const val KEY_RECENT_WINDOWS_JSON = "capture_recent_window_signatures_v1"
    const val SMS_REPRESENTATIVE_PACKAGE = "com.android.mms"

    private const val MAX_CUSTOM_APPS = 200
    private const val MAX_BLOCKED_WINDOWS = 300
    private const val MAX_RECENT_WINDOWS = 15
    private const val MAX_APP_NAME_LENGTH = 32
    private const val SMS_PACKAGE_CACHE_TTL_MS = 60_000L

    private val PACKAGE_NAME_REGEX = Regex(
        """^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$"""
    )
    private val HEX_FINGERPRINT_REGEX = Regex("""^[0-9a-fA-F]{8,32}$""")

    val KNOWN_OEM_SMS_PACKAGES = setOf(
        "com.android.mms",
        "com.android.mms.service",
        "com.google.android.apps.messaging",
        "com.oplus.mms",
        "com.coloros.mms",
        "com.huawei.message",
        "com.hihonor.mms",
        "com.samsung.android.messaging",
        "com.miui.smsextra"
    )

    private val BLOCKED_SELF_OR_SYSTEM_PACKAGES = setOf(
        "com.moji.ai",
        "com.moji.ai.qa",
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.coloros.smartsidebar",
        "com.coloros.assistantscreen",
        "com.coloros.oshare",
        "com.oplus.securitykeyboard",
        "com.tencent.wetype",
        "com.google.android.inputmethod.latin",
        "com.baidu.input",
        "com.sohu.inputmethod.sogou",
        "com.iflytek.inputmethod",
        "li.songe.gkd"
    )

    private val jsonCodec = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val builtInPresets: List<CaptureAppWhitelistEntry> = listOf(
        // 第一梯队：核心支付、系统短信与主流银行（默认双通道全开）
        preset("com.tencent.mm", "微信", CaptureAppGroup.CORE_PAYMENT_AND_BANK, notif = true, access = true),
        preset("com.eg.android.AlipayGphone", "支付宝", CaptureAppGroup.CORE_PAYMENT_AND_BANK, notif = true, access = true),
        preset("com.unionpay", "云闪付", CaptureAppGroup.CORE_PAYMENT_AND_BANK, notif = true, access = true),
        preset(SMS_REPRESENTATIVE_PACKAGE, "系统短信（955xx 银行扣款）", CaptureAppGroup.CORE_PAYMENT_AND_BANK, notif = true, access = true),
        preset("cmb.pb", "招商银行", CaptureAppGroup.CORE_PAYMENT_AND_BANK, notif = true, access = true),
        preset("com.cmblife", "掌上生活", CaptureAppGroup.CORE_PAYMENT_AND_BANK, notif = true, access = true),
        preset("com.icbc", "工商银行", CaptureAppGroup.CORE_PAYMENT_AND_BANK, notif = true, access = true),
        preset("com.chinamworld.main", "建设银行", CaptureAppGroup.CORE_PAYMENT_AND_BANK, notif = true, access = true),
        preset("com.abchina.ebank", "农业银行", CaptureAppGroup.CORE_PAYMENT_AND_BANK, notif = true, access = true),
        preset("com.chinamworld.bocmbci", "中国银行", CaptureAppGroup.CORE_PAYMENT_AND_BANK, notif = true, access = true),
        preset("com.bankcomm.Bankcomm", "交通银行", CaptureAppGroup.CORE_PAYMENT_AND_BANK, notif = true, access = true),
        preset("com.yitong.mbank.psbc", "邮储银行", CaptureAppGroup.CORE_PAYMENT_AND_BANK, notif = true, access = true),
        preset("com.pingan.paces.ccms", "平安口袋银行", CaptureAppGroup.CORE_PAYMENT_AND_BANK, notif = true, access = true),
        preset("com.citiccard.mobilebank", "动卡空间（中信）", CaptureAppGroup.CORE_PAYMENT_AND_BANK, notif = true, access = true),
        preset("com.spdbccc.app", "浦大喜奔（浦发）", CaptureAppGroup.CORE_PAYMENT_AND_BANK, notif = true, access = true),

        // 第二梯队：主流电商与外卖平台（默认开通知、关无障碍自动扫屏防误触）
        preset("com.jingdong.app.mall", "京东", CaptureAppGroup.ECOMMERCE_AND_LIFE, notif = true, access = false),
        preset("com.xunmeng.pinduoduo", "拼多多", CaptureAppGroup.ECOMMERCE_AND_LIFE, notif = true, access = false),
        preset("com.taobao.taobao", "淘宝", CaptureAppGroup.ECOMMERCE_AND_LIFE, notif = true, access = false),
        preset("com.tmall.wireless", "天猫", CaptureAppGroup.ECOMMERCE_AND_LIFE, notif = true, access = false),
        preset("com.sankuai.meituan", "美团", CaptureAppGroup.ECOMMERCE_AND_LIFE, notif = true, access = false),
        preset("com.sankuai.meituan.takeoutnew", "美团外卖", CaptureAppGroup.ECOMMERCE_AND_LIFE, notif = true, access = false),
        preset("me.ele", "饿了么", CaptureAppGroup.ECOMMERCE_AND_LIFE, notif = true, access = false),

        // 第三梯队：短视频、图文社区与特卖（默认双通道关闭，按需手动开启）
        preset("com.ss.android.ugc.aweme", "抖音", CaptureAppGroup.CONTENT_AND_SOCIAL, notif = false, access = false),
        preset("com.ss.android.ugc.aweme.lite", "抖音极速版", CaptureAppGroup.CONTENT_AND_SOCIAL, notif = false, access = false),
        preset("com.ss.android.ugc.live", "抖音火山版", CaptureAppGroup.CONTENT_AND_SOCIAL, notif = false, access = false),
        preset("com.xingin.xhs", "小红书", CaptureAppGroup.CONTENT_AND_SOCIAL, notif = false, access = false),
        preset("com.achievo.vipshop", "唯品会", CaptureAppGroup.CONTENT_AND_SOCIAL, notif = false, access = false)
    )

    private val builtInPresetByPkg: Map<String, CaptureAppWhitelistEntry> =
        builtInPresets.associateBy { it.packageName }

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var configuredPackages: Set<String> = emptySet()

    @Volatile
    private var fastNotifPackages: Set<String> = emptySet()

    @Volatile
    private var fastAccessPackages: Set<String> = emptySet()

    @Volatile
    private var smsNotificationEnabled: Boolean = true

    @Volatile
    private var smsAccessibilityEnabled: Boolean = true

    @Volatile
    private var cachedSystemDefaultSmsPkg: String = ""

    @Volatile
    private var cachedSystemDefaultSmsAtMs: Long = 0L

    @Volatile
    private var fastBlockedWindowKeys: Set<String> = emptySet()

    private val _entriesFlow = MutableStateFlow(builtInPresets)
    val entriesFlow: StateFlow<List<CaptureAppWhitelistEntry>> = _entriesFlow.asStateFlow()

    private val _blockedWindowsFlow = MutableStateFlow<List<WindowPageSignature>>(emptyList())
    val blockedWindowsFlow: StateFlow<List<WindowPageSignature>> = _blockedWindowsFlow.asStateFlow()

    private val _recentCapturedWindowsFlow = MutableStateFlow<List<WindowPageSignature>>(emptyList())
    val recentCapturedWindowsFlow: StateFlow<List<WindowPageSignature>> = _recentCapturedWindowsFlow.asStateFlow()

    val currentEntries: List<CaptureAppWhitelistEntry>
        get() = _entriesFlow.value

    val currentBlockedWindows: List<WindowPageSignature>
        get() = _blockedWindowsFlow.value

    val currentRecentCapturedWindows: List<WindowPageSignature>
        get() = _recentCapturedWindowsFlow.value

    init {
        rebuildFastSnapshots(builtInPresets)
    }

    private fun preset(
        packageName: String,
        appName: String,
        group: CaptureAppGroup,
        notif: Boolean,
        access: Boolean
    ): CaptureAppWhitelistEntry = CaptureAppWhitelistEntry(
        packageName = packageName,
        appName = appName,
        group = group,
        enableNotification = notif,
        enableAccessibility = access,
        isCustom = false
    )

    fun defaultPresetEntries(): List<CaptureAppWhitelistEntry> = builtInPresets

    @Synchronized
    fun init(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp

        val rawBlocked = sp.getString(KEY_BLOCKED_WINDOWS_JSON, null)
        val decodedBlocked = if (!rawBlocked.isNullOrBlank()) {
            runCatching { jsonCodec.decodeFromString<List<WindowPageSignature>>(rawBlocked) }.getOrNull().orEmpty()
        } else {
            emptyList()
        }
        applyBlockedWindows(normalizeWindowSignatures(decodedBlocked, MAX_BLOCKED_WINDOWS), persist = false)

        val rawRecent = sp.getString(KEY_RECENT_WINDOWS_JSON, null)
        val decodedRecent = if (!rawRecent.isNullOrBlank()) {
            runCatching { jsonCodec.decodeFromString<List<WindowPageSignature>>(rawRecent) }.getOrNull().orEmpty()
        } else {
            emptyList()
        }
        _recentCapturedWindowsFlow.value = normalizeWindowSignatures(decodedRecent, MAX_RECENT_WINDOWS)

        val raw = sp.getString(KEY_WHITELIST_JSON, null)
        if (raw.isNullOrBlank()) {
            applyAndPersist(builtInPresets)
            return
        }
        val decoded = runCatching {
            jsonCodec.decodeFromString<List<CaptureAppWhitelistEntry>>(raw)
        }.getOrNull()
        val normalized = if (decoded != null) {
            normalizeAndMergeWithPresets(decoded)
        } else {
            builtInPresets
        }
        applyAndPersist(normalized)
    }

    @Synchronized
    fun resetForTesting() {
        prefs = null
        cachedSystemDefaultSmsPkg = ""
        cachedSystemDefaultSmsAtMs = 0L
        _entriesFlow.value = builtInPresets
        _blockedWindowsFlow.value = emptyList()
        _recentCapturedWindowsFlow.value = emptyList()
        fastBlockedWindowKeys = emptySet()
        rebuildFastSnapshots(builtInPresets)
    }

    fun isBlockedSystemOrSelfPackage(packageName: String): Boolean {
        val clean = packageName.trim()
        if (clean.isEmpty()) return true
        if (clean in BLOCKED_SELF_OR_SYSTEM_PACKAGES) return true
        if (clean.startsWith("com.moji.ai.")) return true
        return false
    }

    fun isValidAndroidPackageFormat(packageName: String): Boolean {
        val clean = packageName.trim()
        return clean.length in 3..128 && PACKAGE_NAME_REGEX.matches(clean)
    }

    fun validateCustomPackageName(rawPackageName: String): Result<String> {
        val clean = rawPackageName.trim()
        if (clean.isEmpty()) {
            return Result.failure(IllegalArgumentException("请输入应用包名（如 com.bankofbeijing.mobilebanking）"))
        }
        if (!isValidAndroidPackageFormat(clean)) {
            return Result.failure(IllegalArgumentException("包名格式无效，须为包含点号的标准 Android 包名（如 com.xxx.yyy）"))
        }
        if (isBlockedSystemOrSelfPackage(clean)) {
            return Result.failure(IllegalArgumentException("不能将默记自身、系统组件或输入法加入识账白名单"))
        }
        val existing = _entriesFlow.value.firstOrNull { it.packageName == clean }
        if (existing != null) {
            return Result.failure(IllegalArgumentException("包名已存在于白名单中（${existing.appName}），请直接在列表中开关"))
        }
        if (clean in KNOWN_OEM_SMS_PACKAGES) {
            return Result.failure(IllegalArgumentException("系统短信包名已由内置「系统短信」项自动兼容覆盖，直接开关该项即可"))
        }
        return Result.success(clean)
    }

    fun isNotificationAllowed(packageName: String, context: Context? = null): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty() || isBlockedSystemOrSelfPackage(pkg)) return false
        if (pkg in configuredPackages) return pkg in fastNotifPackages
        if (smsNotificationEnabled && isSystemSmsPackage(pkg, context)) return true
        return false
    }

    fun isAccessibilityAllowed(packageName: String, context: Context? = null): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty() || isBlockedSystemOrSelfPackage(pkg)) return false
        if (pkg in configuredPackages) return pkg in fastAccessPackages
        if (smsAccessibilityEnabled && isSystemSmsPackage(pkg, context)) return true
        return false
    }

    private fun isSystemSmsPackage(pkg: String, context: Context?): Boolean {
        if (pkg in KNOWN_OEM_SMS_PACKAGES) return true
        if (context == null) return false
        val defaultSms = resolveCachedDefaultSmsPackage(context)
        return defaultSms.isNotEmpty() && pkg == defaultSms
    }

    private fun resolveCachedDefaultSmsPackage(context: Context): String {
        val now = System.currentTimeMillis()
        if (now - cachedSystemDefaultSmsAtMs < SMS_PACKAGE_CACHE_TTL_MS) {
            return cachedSystemDefaultSmsPkg
        }
        val resolved = runCatching {
            Telephony.Sms.getDefaultSmsPackage(context.applicationContext).orEmpty().trim()
        }.getOrDefault("")
        cachedSystemDefaultSmsPkg = resolved
        cachedSystemDefaultSmsAtMs = now
        return resolved
    }

    @Synchronized
    fun setChannelEnabled(
        packageName: String,
        enableNotification: Boolean? = null,
        enableAccessibility: Boolean? = null
    ): CaptureAppWhitelistEntry? {
        val cleanPkg = packageName.trim()
        var updatedTarget: CaptureAppWhitelistEntry? = null
        val updatedList = _entriesFlow.value.map { item ->
            if (item.packageName == cleanPkg) {
                val next = item.copy(
                    enableNotification = enableNotification ?: item.enableNotification,
                    enableAccessibility = enableAccessibility ?: item.enableAccessibility
                )
                updatedTarget = next
                next
            } else {
                item
            }
        }
        if (updatedTarget != null) {
            applyAndPersist(updatedList)
        }
        return updatedTarget
    }

    @Synchronized
    fun setGroupChannelEnabled(
        group: CaptureAppGroup,
        enableNotification: Boolean? = null,
        enableAccessibility: Boolean? = null
    ): List<CaptureAppWhitelistEntry> {
        val updatedList = _entriesFlow.value.map { item ->
            if (item.group == group) {
                item.copy(
                    enableNotification = enableNotification ?: item.enableNotification,
                    enableAccessibility = enableAccessibility ?: item.enableAccessibility
                )
            } else {
                item
            }
        }
        applyAndPersist(updatedList)
        return updatedList
    }

    @Synchronized
    fun addCustomApp(
        rawPackageName: String,
        rawAppName: String,
        enableNotification: Boolean = true,
        enableAccessibility: Boolean = true
    ): Result<CaptureAppWhitelistEntry> {
        val cleanPkg = validateCustomPackageName(rawPackageName).getOrElse {
            return Result.failure(it)
        }
        val currentCustomCount = _entriesFlow.value.count { it.isCustom }
        if (currentCustomCount >= MAX_CUSTOM_APPS) {
            return Result.failure(IllegalArgumentException("自定义应用数量已达上限（$MAX_CUSTOM_APPS 个）"))
        }
        val fallbackLabel = cleanPkg.substringAfterLast('.').ifBlank { cleanPkg }
        val cleanName = rawAppName.trim().take(MAX_APP_NAME_LENGTH).ifEmpty { fallbackLabel }
        val newEntry = CaptureAppWhitelistEntry(
            packageName = cleanPkg,
            appName = cleanName,
            group = CaptureAppGroup.CUSTOM,
            enableNotification = enableNotification,
            enableAccessibility = enableAccessibility,
            isCustom = true
        )
        applyAndPersist(_entriesFlow.value + newEntry)
        return Result.success(newEntry)
    }

    @Synchronized
    fun removeCustomApp(packageName: String): Boolean {
        val cleanPkg = packageName.trim()
        val before = _entriesFlow.value
        val after = before.filterNot { it.isCustom && it.packageName == cleanPkg }
        if (after.size == before.size) return false
        applyAndPersist(after)
        return true
    }

    @Synchronized
    fun resetToDefaults(): List<CaptureAppWhitelistEntry> {
        applyAndPersist(builtInPresets)
        return builtInPresets
    }

    fun resolveAppDisplayName(packageName: String): String {
        val clean = packageName.trim()
        val configured = _entriesFlow.value.firstOrNull { it.packageName == clean }?.appName
        if (!configured.isNullOrBlank()) return configured
        if (clean in KNOWN_OEM_SMS_PACKAGES) return "系统短信"
        return clean.substringAfterLast('.').ifBlank { clean }
    }

    /**
     * O(1) 无锁检查指定页面的 `(packageName, activityName, structureFingerprint)` 是否已被用户拉黑
     */
    fun isWindowSignatureBlocked(
        packageName: String,
        activityName: String,
        structureFingerprint: String
    ): Boolean {
        if (structureFingerprint.isBlank()) return false
        val key = CaptureWindowGuard.buildSignatureKey(packageName, activityName, structureFingerprint)
        return key in fastBlockedWindowKeys
    }

    @Synchronized
    fun blockWindowSignature(signature: WindowPageSignature): Boolean {
        val normalized = normalizeSingleSignature(signature) ?: return false
        val key = normalized.signatureKey
        if (key in fastBlockedWindowKeys) return false
        val current = _blockedWindowsFlow.value
        val updated = listOf(normalized) + current.filterNot { it.signatureKey == key }
        applyBlockedWindows(updated.take(MAX_BLOCKED_WINDOWS), persist = true)
        return true
    }

    @Synchronized
    fun unblockWindowSignature(signatureKey: String): Boolean {
        val target = signatureKey.trim().lowercase(Locale.ROOT)
        val before = _blockedWindowsFlow.value
        val after = before.filterNot { it.signatureKey == target }
        if (after.size == before.size) return false
        applyBlockedWindows(after, persist = true)
        return true
    }

    @Synchronized
    fun clearBlockedWindowSignatures() {
        applyBlockedWindows(emptyList(), persist = true)
    }

    @Synchronized
    fun recordRecentCapturedWindow(
        packageName: String,
        activityName: String,
        structureFingerprint: String,
        rawPageText: String,
        capturedAtMillis: Long = System.currentTimeMillis()
    ) {
        if (structureFingerprint.isBlank()) return
        val candidate = normalizeSingleSignature(
            WindowPageSignature(
                packageName = packageName,
                activityName = activityName,
                structureFingerprint = structureFingerprint,
                appName = resolveAppDisplayName(packageName),
                sampleSummary = CaptureWindowGuard.summarizeCapturedPage(rawPageText),
                createdAtMillis = capturedAtMillis
            )
        ) ?: return
        val key = candidate.signatureKey
        val updated = (listOf(candidate) + _recentCapturedWindowsFlow.value.filterNot { it.signatureKey == key })
            .take(MAX_RECENT_WINDOWS)
        _recentCapturedWindowsFlow.value = updated
        prefs?.edit()?.putString(KEY_RECENT_WINDOWS_JSON, jsonCodec.encodeToString(updated))?.apply()
    }

    @Synchronized
    fun clearRecentCapturedWindows() {
        _recentCapturedWindowsFlow.value = emptyList()
        prefs?.edit()?.remove(KEY_RECENT_WINDOWS_JSON)?.apply()
    }

    fun enabledCounts(entries: List<CaptureAppWhitelistEntry> = _entriesFlow.value): Pair<Int, Int> {
        var notifCount = 0
        var accessCount = 0
        for (item in entries) {
            if (item.enableNotification) notifCount++
            if (item.enableAccessibility) accessCount++
        }
        return notifCount to accessCount
    }

    fun settingsTrailingSummary(
        entries: List<CaptureAppWhitelistEntry> = _entriesFlow.value,
        blockedWindows: List<WindowPageSignature> = _blockedWindowsFlow.value
    ): String {
        val (notifCount, accessCount) = enabledCounts(entries)
        return if (blockedWindows.isEmpty()) {
            "通知 $notifCount · 识屏 $accessCount"
        } else {
            "通知 $notifCount · 识屏 $accessCount · 屏蔽页 ${blockedWindows.size}"
        }
    }

    @Synchronized
    fun exportConfigJson(): String {
        val blocked = _blockedWindowsFlow.value
        if (blocked.isEmpty()) {
            return jsonCodec.encodeToString(_entriesFlow.value)
        }
        return jsonCodec.encodeToString(
            CaptureWhitelistBackupEnvelope(
                version = 2,
                entries = _entriesFlow.value,
                blockedWindows = blocked
            )
        )
    }

    @Synchronized
    fun restoreFromBackupJson(rawJson: String): Boolean {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("{")) {
            val envelope = runCatching {
                jsonCodec.decodeFromString<CaptureWhitelistBackupEnvelope>(trimmed)
            }.getOrNull() ?: return false
            val normalizedEntries = normalizeAndMergeWithPresets(envelope.entries)
            val normalizedBlocked = normalizeWindowSignatures(envelope.blockedWindows, MAX_BLOCKED_WINDOWS)
            applyAndPersist(normalizedEntries)
            applyBlockedWindows(normalizedBlocked, persist = true)
            return true
        }
        val decoded = runCatching {
            jsonCodec.decodeFromString<List<CaptureAppWhitelistEntry>>(trimmed)
        }.getOrNull() ?: return false
        val normalized = normalizeAndMergeWithPresets(decoded)
        applyAndPersist(normalized)
        applyBlockedWindows(emptyList(), persist = true)
        return true
    }

    private fun normalizeWindowSignatures(
        rawList: List<WindowPageSignature>,
        limit: Int
    ): List<WindowPageSignature> {
        val byKey = LinkedHashMap<String, WindowPageSignature>()
        for (item in rawList) {
            val clean = normalizeSingleSignature(item) ?: continue
            val key = clean.signatureKey
            if (!byKey.containsKey(key)) {
                byKey[key] = clean
            }
            if (byKey.size >= limit) break
        }
        return byKey.values.toList()
    }

    private fun normalizeSingleSignature(raw: WindowPageSignature): WindowPageSignature? {
        val pkg = raw.packageName.trim()
        if (!isValidAndroidPackageFormat(pkg) || isBlockedSystemOrSelfPackage(pkg)) return null
        val fp = raw.structureFingerprint.trim().lowercase(Locale.ROOT)
        if (!HEX_FINGERPRINT_REGEX.matches(fp)) return null
        val act = raw.activityName.trim().take(160)
        val appLabel = raw.appName.trim().take(MAX_APP_NAME_LENGTH).ifEmpty { resolveAppDisplayName(pkg) }
        val summary = raw.sampleSummary.trim().take(48)
        val ts = if (raw.createdAtMillis > 0L) raw.createdAtMillis else System.currentTimeMillis()
        return WindowPageSignature(
            packageName = pkg,
            activityName = act,
            structureFingerprint = fp,
            appName = appLabel,
            sampleSummary = summary,
            createdAtMillis = ts
        )
    }

    private fun applyBlockedWindows(list: List<WindowPageSignature>, persist: Boolean) {
        _blockedWindowsFlow.value = list
        fastBlockedWindowKeys = list.mapTo(HashSet(list.size)) { it.signatureKey }
        if (persist) {
            prefs?.edit()?.putString(KEY_BLOCKED_WINDOWS_JSON, jsonCodec.encodeToString(list))?.apply()
        }
    }

    internal fun normalizeAndMergeWithPresets(
        rawEntries: List<CaptureAppWhitelistEntry>
    ): List<CaptureAppWhitelistEntry> {
        val incomingByPkg = LinkedHashMap<String, CaptureAppWhitelistEntry>()
        for (entry in rawEntries) {
            val pkg = entry.packageName.trim()
            if (!isValidAndroidPackageFormat(pkg) || isBlockedSystemOrSelfPackage(pkg)) continue
            if (!incomingByPkg.containsKey(pkg)) {
                incomingByPkg[pkg] = entry.copy(packageName = pkg)
            }
        }

        val merged = ArrayList<CaptureAppWhitelistEntry>(builtInPresets.size + 16)
        for (preset in builtInPresets) {
            val saved = incomingByPkg.remove(preset.packageName)
            if (saved != null) {
                merged.add(
                    preset.copy(
                        enableNotification = saved.enableNotification,
                        enableAccessibility = saved.enableAccessibility,
                        isCustom = false
                    )
                )
            } else {
                merged.add(preset)
            }
        }

        var customAdded = 0
        for ((pkg, custom) in incomingByPkg) {
            if (pkg in builtInPresetByPkg || pkg in KNOWN_OEM_SMS_PACKAGES) continue
            if (customAdded >= MAX_CUSTOM_APPS) break
            val cleanName = custom.appName.trim().take(MAX_APP_NAME_LENGTH)
                .ifEmpty { pkg.substringAfterLast('.').ifBlank { pkg } }
            merged.add(
                CaptureAppWhitelistEntry(
                    packageName = pkg,
                    appName = cleanName,
                    group = CaptureAppGroup.CUSTOM,
                    enableNotification = custom.enableNotification,
                    enableAccessibility = custom.enableAccessibility,
                    isCustom = true
                )
            )
            customAdded++
        }
        return merged
    }

    @Synchronized
    private fun applyAndPersist(entries: List<CaptureAppWhitelistEntry>) {
        _entriesFlow.value = entries
        rebuildFastSnapshots(entries)
        prefs?.edit()?.putString(KEY_WHITELIST_JSON, jsonCodec.encodeToString(entries))?.apply()
    }

    private fun rebuildFastSnapshots(entries: List<CaptureAppWhitelistEntry>) {
        val allConfigured = HashSet<String>(entries.size)
        val notifSet = HashSet<String>(entries.size)
        val accessSet = HashSet<String>(entries.size)
        var smsNotif = false
        var smsAccess = false
        for (item in entries) {
            allConfigured.add(item.packageName)
            if (item.packageName == SMS_REPRESENTATIVE_PACKAGE) {
                smsNotif = item.enableNotification
                smsAccess = item.enableAccessibility
            }
            if (item.enableNotification) {
                notifSet.add(item.packageName)
            }
            if (item.enableAccessibility) {
                accessSet.add(item.packageName)
            }
        }
        smsNotificationEnabled = smsNotif
        smsAccessibilityEnabled = smsAccess
        configuredPackages = allConfigured
        fastNotifPackages = notifSet
        fastAccessPackages = accessSet
    }
}
