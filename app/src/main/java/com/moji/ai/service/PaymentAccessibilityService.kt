package com.moji.ai.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.moji.ai.MoJiApp
import com.moji.ai.ai.CaptureAppWhitelistManager
import com.moji.ai.ai.CaptureWindowGuard
import com.moji.ai.ai.WindowCandidate
import com.moji.ai.ai.WindowContainerRole
import com.moji.ai.ai.WindowInspectionInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 免 Root 微信 / 支付宝 / 云闪付 / 银行 App 自动识账无障碍服务：
 * 1. 通过 SelectToSpeakService 兼容入口 + FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY 突破微信 WebView/LiteApp 节点屏蔽；
 * 2. 融合窗口所有权单窗口锁死、PackageManager 真实 Activity 核验、视图骨架指纹（StructureFingerprint）屏蔽、
 *    可见节点与输入框（isEditable/isPassword）门禁及多维结构闭环，精准过滤聊天页、朋友圈、相册选图、商品详情页与关闭订单；
 * 3. Android 11+ 免 Root takeScreenshot + 本地账单截图持久化（WebP + SHA-256）+ 端侧离线中文 OCR。
 */
open class PaymentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PaymentAccessibilityService? = null
            private set

        private val _isConnectedFlow = MutableStateFlow(false)
        val isConnectedFlow: StateFlow<Boolean> = _isConnectedFlow.asStateFlow()

        private val PROBE_DELAYS_MS = longArrayOf(60L, 200L, 450L, 850L, 1350L, 1950L)
        private val CONTENT_PROBE_DELAYS_MS = longArrayOf(110L, 320L, 720L, 1300L)
        private const val CONTENT_THROTTLE_MS = 120L
        private const val OCR_COOLDOWN_MS = 4_500L
        private const val MAX_TRAVERSE_DEPTH = 50
        private const val MAX_TRAVERSE_NODES = 550
        private const val MAX_ACTIVITY_CACHE_SIZE = 512

        private val PAGE_IDENTITY_ORDER_REGEX = Regex(
            """(?:交易单号|转账单号|订单编号|订单号|商户单号|商家订单号|支付宝交易号|流水号)\s*(?:[:：,，\s]|\|)*\s*([0-9A-Za-z_-]{10,44})"""
        )
        private val PAGE_IDENTITY_TIME_REGEX = Regex(
            """(?:支付时间|付款时间|下单时间|拼单时间|创建时间|转账时间|收款时间|退款时间)\s*(?:[:：,，\s]|\|)*\s*(20\d{2}[-年/]\d{1,2}[-月/]\d{1,2}日?\s+\d{1,2}[:时]\d{2}(?:[:分]\d{2}秒?)?)"""
        )
        private val PAGE_IDENTITY_AMOUNT_REGEX = Regex(
            """(?:实付款|实付金额|合计实付|实扣|实付|应付总额|应付金额|应付款|需付款|合计|到手价|到手|支出|收入|付款|收款)\s*(?:[:：,，\s]|\|)*\s*(?:¥|￥)?\s*\d{1,7}(?:\.\d{1,2})?\s*[元)）]?|[-+−]?\d{1,7}\.\d{1,2}"""
        )
    }

    private data class CapturedWindowSnapshot(
        val packageName: String,
        val verifiedActivity: String,
        val structureFingerprint: String,
        val snippets: List<String>,
        val hasVisiblePasswordNode: Boolean,
        val hasVisibleEditableNode: Boolean,
        val isSignatureBlocked: Boolean,
        val blockedByContainer: Boolean
    )

    private class TraversalCollector {
        val snippets = ArrayList<String>(64)
        var hasVisiblePasswordNode: Boolean = false
        var hasVisibleEditableNode: Boolean = false
    }

    private val systemOverlayPackages = setOf(
        "com.android.systemui",
        "android",
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotIoExecutor = Executors.newSingleThreadExecutor()
    private val scheduledProbes = mutableListOf<Runnable>()

    // 绑定 windowId -> 已验证的真实 Activity，防止弹窗 FrameLayout / BottomSheet 污染底层页面类名
    private val windowActivities = ConcurrentHashMap<Int, String>()
    private val windowPackages = ConcurrentHashMap<Int, String>()
    private val lastVerifiedActivityByPkg = ConcurrentHashMap<String, String>()
    private val declaredActivityCache = ConcurrentHashMap<String, Boolean>()
    private val packageVisibilityCache = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var ocrRecognizerInitialized = false
    private val chineseOcrRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()).also {
            ocrRecognizerInitialized = true
        }
    }

    private var activePackage: String = ""
    private var activeVerifiedActivity: String = ""
    private var lastContentInspectMillis: Long = 0L
    private var lastDispatchedSignature: String = ""
    private var lastDispatchedMillis: Long = 0L
    private var isCurrentlyOnBillDetailPage: Boolean = false
    private var lastAutoOcrMillis: Long = 0L

    fun resetDispatchedState() {
        lastDispatchedSignature = ""
        lastDispatchedMillis = 0L
        isCurrentlyOnBillDetailPage = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnectedFlow.value = true
        runCatching {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = info.flags or
                AccessibilityServiceInfo.DEFAULT or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
            info.notificationTimeout = 100L
            serviceInfo = info
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { setCacheEnabled(false) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            (event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_REMOVED) != 0 &&
            event.windowId >= 0
        ) {
            windowActivities.remove(event.windowId)
            windowPackages.remove(event.windowId)
        }

        val app = applicationContext as? MoJiApp ?: return
        if (!app.aiConfigManager.getConfig().enableAccessibilityCapture) return

        val now = System.currentTimeMillis()
        val rawPkg = event.packageName?.toString().orEmpty()
        val rawCls = event.className?.toString().orEmpty()

        // 完全忽略我们自己的流体云悬浮窗视图及系统侧边栏/输入法/辅助工具产生的事件
        if (rawPkg in systemOverlayPackages) return
        if ((rawPkg == packageName || rawPkg.startsWith("com.moji.ai")) && !rawCls.contains("MainActivity")) return

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = if (rawPkg.isNotBlank()) {
                rawPkg
            } else {
                resolveFocusedApplicationPackage()?.takeIf { it !in systemOverlayPackages }.orEmpty()
            }
            if (pkg.isBlank() || pkg in systemOverlayPackages) return

            val normalizedCls = normalizeEventClassName(pkg, rawCls)
            val isRealActivity = isVerifiedDeclaredActivity(pkg, normalizedCls)

            if (!CaptureAppWhitelistManager.isAccessibilityAllowed(pkg, this)) {
                if (isRealActivity || !CaptureWindowGuard.isLikelyViewOrDialogClass(normalizedCls)) {
                    activePackage = pkg
                    activeVerifiedActivity = if (isRealActivity) normalizedCls else ""
                    isCurrentlyOnBillDetailPage = false
                    lastDispatchedSignature = ""
                    clearScheduledProbes()
                }
                return
            }

            activePackage = pkg
            if (isRealActivity) {
                activeVerifiedActivity = normalizedCls
                if (lastVerifiedActivityByPkg.size > 64) {
                    lastVerifiedActivityByPkg.clear()
                }
                lastVerifiedActivityByPkg[pkg] = normalizedCls
                if (event.windowId >= 0) {
                    if (windowActivities.size > 64) {
                        windowActivities.clear()
                        windowPackages.clear()
                    }
                    windowActivities[event.windowId] = normalizedCls
                    windowPackages[event.windowId] = pkg
                } else {
                    clearWindowActivityMappingsForPackage(pkg)
                }
            } else if (activeVerifiedActivity.isBlank()) {
                activeVerifiedActivity = lastVerifiedActivityByPkg[pkg].orEmpty()
            }

            val currentRole = CaptureWindowGuard.classifyActivityContainer(pkg, activeVerifiedActivity)
            if (currentRole == WindowContainerRole.SENSITIVE_AUTH_BLOCKED ||
                currentRole == WindowContainerRole.NON_TRANSACTION_BLOCKED
            ) {
                clearScheduledProbes()
                isCurrentlyOnBillDetailPage = false
                lastDispatchedSignature = ""
                return
            }

            isCurrentlyOnBillDetailPage = false
            if (now - lastDispatchedMillis > 1_200L) {
                lastDispatchedSignature = ""
            }
            inspectCurrentScreen(pkg, event.source, allowAutoOcrFallback = false)
            scheduleProbeWave(pkg, PROBE_DELAYS_MS)
            return
        }

        // 对于 TYPE_WINDOW_CONTENT_CHANGED / TYPE_WINDOWS_CHANGED：
        val pkg = if (eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val focusedWin = resolveFocusedApplicationWindow()
            val focused = focusedWin?.packageName?.takeIf { it !in systemOverlayPackages }
            when {
                focused != null && CaptureAppWhitelistManager.isAccessibilityAllowed(focused, this) -> {
                    activePackage = focused
                    val winAct = focusedWin.id.takeIf { it >= 0 }?.let { winId ->
                        if (windowPackages[winId] == focused) windowActivities[winId] else null
                    }
                    if (!winAct.isNullOrBlank()) {
                        activeVerifiedActivity = winAct
                        lastVerifiedActivityByPkg[focused] = winAct
                    } else if (activeVerifiedActivity.isBlank()) {
                        activeVerifiedActivity = lastVerifiedActivityByPkg[focused].orEmpty()
                    }
                    focused
                }
                focused != null -> {
                    activePackage = focused
                    activeVerifiedActivity = ""
                    isCurrentlyOnBillDetailPage = false
                    lastDispatchedSignature = ""
                    clearScheduledProbes()
                    return
                }
                rawPkg.isNotBlank() && CaptureAppWhitelistManager.isAccessibilityAllowed(rawPkg, this) -> {
                    activePackage = rawPkg
                    rawPkg
                }
                rawPkg.isBlank() && CaptureAppWhitelistManager.isAccessibilityAllowed(activePackage, this) -> {
                    activePackage
                }
                else -> return
            }
        } else {
            when {
                rawPkg.isNotBlank() -> {
                    if (!CaptureAppWhitelistManager.isAccessibilityAllowed(rawPkg, this)) {
                        return
                    }
                    if (activePackage != rawPkg) {
                        activePackage = rawPkg
                        activeVerifiedActivity = lastVerifiedActivityByPkg[rawPkg].orEmpty()
                    }
                    rawPkg
                }
                CaptureAppWhitelistManager.isAccessibilityAllowed(activePackage, this) -> activePackage
                else -> {
                    val focused = resolveFocusedApplicationPackage()?.takeIf { it !in systemOverlayPackages }
                    if (focused != null && CaptureAppWhitelistManager.isAccessibilityAllowed(focused, this)) {
                        activePackage = focused
                        activeVerifiedActivity = lastVerifiedActivityByPkg[focused].orEmpty()
                        focused
                    } else {
                        return
                    }
                }
            }
        }

        if (RecognitionBanner.isAnimatingNow()) {
            return
        }

        val currentActivity = resolveActivityForEventWindow(pkg, event.windowId)
        val containerRole = CaptureWindowGuard.classifyActivityContainer(pkg, currentActivity)
        if (containerRole == WindowContainerRole.SENSITIVE_AUTH_BLOCKED ||
            containerRole == WindowContainerRole.NON_TRANSACTION_BLOCKED
        ) {
            clearScheduledProbes()
            return
        }

        scheduleProbeWave(pkg, CONTENT_PROBE_DELAYS_MS)

        if (now - lastContentInspectMillis < CONTENT_THROTTLE_MS) return
        lastContentInspectMillis = now

        inspectCurrentScreen(pkg, event.source, allowAutoOcrFallback = false)
    }

    /**
     * 手动或从快捷设置磁贴触发一次当前屏幕识账（对齐 iCost 识屏：同时保存账单原图截图）
     */
    fun triggerManualScreenCapture(
        collapseShadeFirst: Boolean = false,
        onResultMessage: ((String) -> Unit)? = null
    ) {
        if (collapseShadeFirst && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) }
        }
        val delayMs = if (collapseShadeFirst) 550L else 250L
        mainHandler.postDelayed({
            val pkg = resolveFocusedApplicationPackage()
                ?.takeIf { it !in systemOverlayPackages }
                ?: activePackage.ifBlank { "com.tencent.mm" }

            val capturedByNodes = inspectCurrentScreen(
                pkg = pkg,
                eventSource = null,
                allowAutoOcrFallback = false,
                forceManual = true
            )
            if (capturedByNodes) {
                onResultMessage?.invoke("已通过页面节点识别账单并保存截图")
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                captureScreenshotAndRunOcr(
                    pkg = pkg,
                    isManualTrigger = true,
                    onResultMessage = onResultMessage
                )
            } else {
                onResultMessage?.invoke("当前页面未检测到完整账单节点")
            }
        }, delayMs)
    }

    /**
     * 供 CapturePipelineCoordinator 在无障碍节点识账成功入库后静默抓取当前账单页面截图并持久化到本地。
     * 回调与 WebP 压缩全部运行在后台单线程池 screenshotIoExecutor，绝不阻塞主线程动画！
     */
    fun captureBillEvidenceScreenshot(onSaved: (SavedScreenshotInfo?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onSaved(null)
            return
        }
        mainHandler.post {
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotIoExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            val hwBuffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace
                            val rawBitmap = try {
                                val wrapped = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            } catch (_: Throwable) {
                                null
                            } finally {
                                runCatching { hwBuffer.close() }
                            }
                            if (rawBitmap == null) {
                                onSaved(null)
                                return
                            }
                            val cropped = cropAndScaleForOcr(rawBitmap)
                            if (cropped !== rawBitmap) {
                                rawBitmap.recycle()
                            }
                            val saved = BillScreenshotStorage.saveBitmap(
                                context = this@PaymentAccessibilityService,
                                bitmap = cropped,
                                capturedAtMillis = System.currentTimeMillis()
                            )
                            cropped.recycle()
                            onSaved(saved)
                        }

                        override fun onFailure(errorCode: Int) {
                            onSaved(null)
                        }
                    }
                )
            } catch (_: Throwable) {
                onSaved(null)
            }
        }
    }

    private fun scheduleProbeWave(pkg: String, delays: LongArray) {
        clearScheduledProbes()
        for (idx in delays.indices) {
            val delay = delays[idx]
            val isLastProbe = idx == delays.lastIndex
            val task = Runnable {
                if (RecognitionBanner.isAnimatingNow()) return@Runnable
                if (!CaptureAppWhitelistManager.isAccessibilityAllowed(pkg, this@PaymentAccessibilityService)) return@Runnable
                inspectCurrentScreen(
                    pkg = pkg,
                    eventSource = null,
                    allowAutoOcrFallback = isLastProbe
                )
            }
            scheduledProbes.add(task)
            mainHandler.postDelayed(task, delay)
        }
    }

    private fun clearScheduledProbes() {
        scheduledProbes.forEach { mainHandler.removeCallbacks(it) }
        scheduledProbes.clear()
    }

    private fun inspectCurrentScreen(
        pkg: String,
        eventSource: AccessibilityNodeInfo?,
        allowAutoOcrFallback: Boolean,
        forceManual: Boolean = false
    ): Boolean {
        val snapshot = collectPrimaryWindowSnapshot(
            targetPkg = pkg,
            eventSource = eventSource,
            forceManual = forceManual
        ) ?: return false

        if (!forceManual && (snapshot.blockedByContainer || snapshot.isSignatureBlocked)) {
            return false
        }
        if (snapshot.hasVisiblePasswordNode) {
            return false
        }

        if (snapshot.snippets.isEmpty()) {
            if (!forceManual && allowAutoOcrFallback && shouldAttemptAutoOcr(snapshot, "")) {
                captureScreenshotAndRunOcr(
                    pkg = pkg,
                    isManualTrigger = false,
                    expectedActivity = snapshot.verifiedActivity,
                    expectedFingerprint = snapshot.structureFingerprint,
                    onResultMessage = null
                )
            }
            return false
        }

        val rawJoinedText = snapshot.snippets.joinToString(" | ")
        val eval = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = pkg,
                verifiedActivity = snapshot.verifiedActivity,
                structureFingerprint = snapshot.structureFingerprint,
                rawPageText = rawJoinedText,
                hasVisiblePasswordNode = snapshot.hasVisiblePasswordNode,
                hasVisibleEditableNode = snapshot.hasVisibleEditableNode,
                forceManual = forceManual,
                isSignatureBlocked = snapshot.isSignatureBlocked
            )
        )

        if (!eval.accepted) {
            if (isCurrentlyOnBillDetailPage && snapshot.snippets.size >= 3) {
                isCurrentlyOnBillDetailPage = false
                lastDispatchedSignature = ""
            }
            if (!forceManual && allowAutoOcrFallback && shouldAttemptAutoOcr(snapshot, eval.sanitizedText)) {
                captureScreenshotAndRunOcr(
                    pkg = pkg,
                    isManualTrigger = false,
                    expectedActivity = snapshot.verifiedActivity,
                    expectedFingerprint = snapshot.structureFingerprint,
                    onResultMessage = null
                )
            }
            return false
        }

        val cleanFullText = stripEcommerceRecommendationFooter(rawJoinedText)
        isCurrentlyOnBillDetailPage = true
        return dispatchCapturedText(
            fullPageText = cleanFullText,
            pkg = pkg,
            verifiedActivity = snapshot.verifiedActivity,
            structureFingerprint = snapshot.structureFingerprint,
            captureSource = "AI_ACCESSIBILITY",
            forceManual = forceManual
        )
    }

    private fun dispatchCapturedText(
        fullPageText: String,
        pkg: String,
        verifiedActivity: String = "",
        structureFingerprint: String = "",
        captureSource: String,
        screenshotPath: String = "",
        screenshotSha256: String = "",
        forceManual: Boolean = false
    ): Boolean {
        val now = System.currentTimeMillis()
        val pageIdentity = extractStablePageIdentity(pkg, fullPageText)
        if (!forceManual && pageIdentity == lastDispatchedSignature && (now - lastDispatchedMillis) < 15_000L) {
            return true
        }
        lastDispatchedSignature = pageIdentity
        lastDispatchedMillis = now
        Log.d(
            "MoJiCapture",
            "Dispatch bill page: pkg=$pkg, act=${verifiedActivity.substringAfterLast('.')}, fp=$structureFingerprint, identity=$pageIdentity"
        )

        if (!forceManual && structureFingerprint.isNotBlank()) {
            CaptureAppWhitelistManager.recordRecentCapturedWindow(
                packageName = pkg,
                activityName = verifiedActivity,
                structureFingerprint = structureFingerprint,
                rawPageText = fullPageText,
                capturedAtMillis = now
            )
        }

        val app = applicationContext as? MoJiApp ?: return false
        app.captureCoordinator.onRawFinancialEventCaptured(
            rawText = fullPageText,
            packageName = pkg,
            captureSource = captureSource,
            screenshotPath = screenshotPath,
            screenshotSha256 = screenshotSha256,
            capturedAtMillis = now
        )
        return true
    }

    private fun stripEcommerceRecommendationFooter(rawText: String): String =
        com.moji.ai.ai.AiAccountingEngine.stripEcommerceRecommendationFooter(rawText)

    private fun shouldAttemptAutoOcr(
        snapshot: CapturedWindowSnapshot,
        partialText: String
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (!CaptureAppWhitelistManager.isAccessibilityAllowed(snapshot.packageName, this)) return false
        val now = System.currentTimeMillis()
        if (now - lastAutoOcrMillis < OCR_COOLDOWN_MS) return false

        return CaptureWindowGuard.shouldAllowAutoOcrFallback(
            packageName = snapshot.packageName,
            verifiedActivity = snapshot.verifiedActivity,
            partialText = partialText,
            hasVisiblePasswordNode = snapshot.hasVisiblePasswordNode,
            hasVisibleEditableNode = snapshot.hasVisibleEditableNode,
            isSignatureBlocked = snapshot.isSignatureBlocked
        )
    }

    private fun captureScreenshotAndRunOcr(
        pkg: String,
        isManualTrigger: Boolean,
        expectedActivity: String = "",
        expectedFingerprint: String = "",
        onResultMessage: ((String) -> Unit)?
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val capturedAtMillis = System.currentTimeMillis()
        lastAutoOcrMillis = capturedAtMillis

        RecognitionBanner.dismissImmediate()

        mainHandler.postDelayed({
            if (!isManualTrigger) {
                if (!CaptureAppWhitelistManager.isAccessibilityAllowed(pkg, this@PaymentAccessibilityService)) {
                    return@postDelayed
                }
                val focusedNow = resolveFocusedApplicationPackage()?.takeIf { it !in systemOverlayPackages }
                if (focusedNow != null && focusedNow != pkg) {
                    return@postDelayed
                }
                val currentAct = expectedActivity.ifBlank {
                    lastVerifiedActivityByPkg[pkg].orEmpty().ifBlank { activeVerifiedActivity }
                }
                val currentRole = CaptureWindowGuard.classifyActivityContainer(pkg, currentAct)
                if (currentRole == WindowContainerRole.SENSITIVE_AUTH_BLOCKED ||
                    currentRole == WindowContainerRole.NON_TRANSACTION_BLOCKED
                ) {
                    return@postDelayed
                }
                if (expectedFingerprint.isNotBlank() &&
                    CaptureAppWhitelistManager.isWindowSignatureBlocked(pkg, currentAct, expectedFingerprint)
                ) {
                    return@postDelayed
                }
            }
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotIoExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            val hwBuffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace
                            val rawBitmap = try {
                                val wrapped = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            } catch (_: Throwable) {
                                null
                            } finally {
                                runCatching { hwBuffer.close() }
                            }

                            if (rawBitmap == null) {
                                mainHandler.post { onResultMessage?.invoke("截屏缓冲区转换失败") }
                                return
                            }

                            val processedBitmap = cropAndScaleForOcr(rawBitmap)
                            if (processedBitmap !== rawBitmap) {
                                rawBitmap.recycle()
                            }

                            val savedScreenshot = BillScreenshotStorage.saveBitmap(
                                context = this@PaymentAccessibilityService,
                                bitmap = processedBitmap,
                                capturedAtMillis = capturedAtMillis
                            )

                            val image = InputImage.fromBitmap(processedBitmap, 0)
                            chineseOcrRecognizer.process(image)
                                .addOnSuccessListener { visionText ->
                                    processedBitmap.recycle()
                                    val orderedText = stripEcommerceRecommendationFooter(formatOcrLinesInReadingOrder(visionText))
                                    if (orderedText.isBlank()) {
                                        BillScreenshotStorage.deleteIfExists(savedScreenshot?.absolutePath)
                                        onResultMessage?.invoke("未识别到屏幕文字")
                                        return@addOnSuccessListener
                                    }

                                    val currentAct = expectedActivity.ifBlank {
                                        lastVerifiedActivityByPkg[pkg].orEmpty()
                                    }
                                    val isBlockedSig = expectedFingerprint.isNotBlank() &&
                                        CaptureAppWhitelistManager.isWindowSignatureBlocked(pkg, currentAct, expectedFingerprint)
                                    val ocrEval = CaptureWindowGuard.evaluateBillWindow(
                                        WindowInspectionInput(
                                            packageName = pkg,
                                            verifiedActivity = currentAct,
                                            structureFingerprint = expectedFingerprint,
                                            rawPageText = orderedText,
                                            forceManual = isManualTrigger,
                                            isSignatureBlocked = isBlockedSig
                                        )
                                    )
                                    if (!ocrEval.accepted) {
                                        BillScreenshotStorage.deleteIfExists(savedScreenshot?.absolutePath)
                                        if (isManualTrigger) {
                                            onResultMessage?.invoke("屏幕未满足有效账单要素（${ocrEval.reason.summary}）")
                                        }
                                        return@addOnSuccessListener
                                    }

                                    dispatchCapturedText(
                                        fullPageText = orderedText,
                                        pkg = pkg,
                                        verifiedActivity = currentAct,
                                        structureFingerprint = expectedFingerprint,
                                        captureSource = "AI_OCR",
                                        screenshotPath = savedScreenshot?.absolutePath.orEmpty(),
                                        screenshotSha256 = savedScreenshot?.sha256.orEmpty(),
                                        forceManual = isManualTrigger
                                    )
                                    if (isManualTrigger && onResultMessage == null) {
                                        Toast.makeText(
                                            this@PaymentAccessibilityService,
                                            "默记：已提取屏幕账单并保存截图",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    onResultMessage?.invoke("已通过端侧 OCR 提取屏幕账单并保存截图")
                                }
                                .addOnFailureListener {
                                    processedBitmap.recycle()
                                    BillScreenshotStorage.deleteIfExists(savedScreenshot?.absolutePath)
                                    onResultMessage?.invoke("OCR 识别失败：${it.localizedMessage ?: "未知错误"}")
                                }
                        }

                        override fun onFailure(errorCode: Int) {
                            mainHandler.post { onResultMessage?.invoke("免 Root 截屏受限 (code=$errorCode)") }
                        }
                    }
                )
            } catch (e: Throwable) {
                mainHandler.post { onResultMessage?.invoke("截屏调用异常：${e.localizedMessage}") }
            }
        }, 220L)
    }

    private fun cropAndScaleForOcr(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= 100 || h <= 200) return source

        val topCrop = (h * 0.045f).toInt().coerceAtLeast(1)
        val bottomCrop = (h * 0.04f).toInt().coerceAtLeast(1)
        val cropHeight = (h - topCrop - bottomCrop).coerceAtLeast(100)

        val cropped = runCatching {
            Bitmap.createBitmap(source, 0, topCrop, w, cropHeight)
        }.getOrDefault(source)

        val targetShortEdge = 1080
        val shortEdge = minOf(cropped.width, cropped.height)
        if (shortEdge <= targetShortEdge) return cropped

        val scale = targetShortEdge.toFloat() / shortEdge.toFloat()
        val scaledW = (cropped.width * scale).toInt().coerceAtLeast(1)
        val scaledH = (cropped.height * scale).toInt().coerceAtLeast(1)
        val scaled = runCatching {
            Bitmap.createScaledBitmap(cropped, scaledW, scaledH, true)
        }.getOrDefault(cropped)

        if (scaled !== cropped && cropped !== source) {
            cropped.recycle()
        }
        return scaled
    }

    private fun formatOcrLinesInReadingOrder(visionText: Text): String {
        val allLines = mutableListOf<Text.Line>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                if (line.text.isNotBlank()) {
                    allLines.add(line)
                }
            }
        }
        if (allLines.isEmpty()) return ""

        val sortedByY = allLines.sortedBy { it.boundingBox?.centerY() ?: 0 }
        val rows = mutableListOf<MutableList<Text.Line>>()

        for (line in sortedByY) {
            val box = line.boundingBox
            val cy = box?.centerY() ?: 0
            val lineH = (box?.height() ?: 32).coerceAtLeast(20)
            val matchedRow = rows.lastOrNull { row ->
                val refBox = row.first().boundingBox
                val refCy = refBox?.centerY() ?: 0
                val refH = (refBox?.height() ?: 32).coerceAtLeast(20)
                abs(cy - refCy) <= maxOf(lineH, refH) * 0.65f
            }
            if (matchedRow != null) {
                matchedRow.add(line)
            } else {
                rows.add(mutableListOf(line))
            }
        }

        val orderedTokens = mutableListOf<String>()
        for (row in rows) {
            row.sortBy { it.boundingBox?.left ?: 0 }
            for (line in row) {
                val cleaned = line.text.trim()
                if (cleaned.isNotEmpty() && cleaned.length <= 120) {
                    orderedTokens.add(cleaned)
                }
            }
        }
        return orderedTokens.joinToString(" | ")
    }

    private fun normalizeEventClassName(pkg: String, rawCls: String): String {
        val trimmed = rawCls.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith(".") && pkg.isNotBlank()) {
            "$pkg$trimmed"
        } else {
            trimmed
        }
    }

    private fun clearWindowActivityMappingsForPackage(pkg: String) {
        val staleIds = windowPackages.entries
            .filter { it.value == pkg }
            .map { it.key }
        staleIds.forEach { winId ->
            windowActivities.remove(winId)
            windowPackages.remove(winId)
        }
    }

    private fun isPackageVisibleToPackageManager(pkg: String): Boolean {
        packageVisibilityCache[pkg]?.let { return it }
        val visible = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    pkg,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(pkg, 0)
            }
        }.isSuccess
        if (packageVisibilityCache.size >= MAX_ACTIVITY_CACHE_SIZE) {
            packageVisibilityCache.clear()
        }
        packageVisibilityCache[pkg] = visible
        return visible
    }

    /**
     * 通过 PackageManager 核实类名是否为应用在 AndroidManifest 中声明的真实 Activity（带内存缓存，零重复 IPC 开销）；
     * 对于用户运行时新增且受限于 Android 11+ `<queries>` 不可见的自定义包名，优雅回退至类名形态判定。
     */
    private fun isVerifiedDeclaredActivity(pkg: String, className: String): Boolean {
        if (pkg.isBlank() || CaptureWindowGuard.isLikelyViewOrDialogClass(className)) {
            return false
        }
        if (!isPackageVisibleToPackageManager(pkg)) {
            return true
        }
        val cacheKey = "$pkg/$className"
        declaredActivityCache[cacheKey]?.let { return it }

        val component = ComponentName.createRelative(pkg, className)
        val verified = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getActivityInfo(
                    component,
                    android.content.pm.PackageManager.ComponentInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getActivityInfo(component, 0)
            }
        }.isSuccess

        if (declaredActivityCache.size >= MAX_ACTIVITY_CACHE_SIZE) {
            declaredActivityCache.clear()
        }
        declaredActivityCache[cacheKey] = verified
        return verified
    }

    private fun resolveActivityForEventWindow(pkg: String, windowId: Int): String {
        if (windowId >= 0) {
            val cachedWinPkg = windowPackages[windowId]
            val cachedWinAct = windowActivities[windowId]
            if (cachedWinPkg == pkg && !cachedWinAct.isNullOrBlank()) {
                return cachedWinAct
            }
        }
        if (activePackage == pkg && activeVerifiedActivity.isNotBlank()) {
            return activeVerifiedActivity
        }
        return lastVerifiedActivityByPkg[pkg].orEmpty()
    }

    private fun resolveFocusedApplicationWindow(): WindowCandidate? {
        runCatching {
            val winList = windows
            if (!winList.isNullOrEmpty()) {
                try {
                    val candidates = winList.map { win ->
                        val rootNode = runCatching { win.root }.getOrNull()
                        val pkg = try {
                            rootNode?.packageName?.toString() ?: windowPackages[win.id]
                        } finally {
                            rootNode.recycleNodeCompat()
                        }
                        WindowCandidate(
                            id = win.id,
                            type = win.type,
                            isActive = win.isActive,
                            isFocused = win.isFocused,
                            layer = win.layer,
                            isInPictureInPictureMode = win.isInPictureInPictureMode,
                            packageName = pkg
                        )
                    }
                    val top = CaptureWindowGuard.selectTopApplicationWindow(candidates)
                    if (!top?.packageName.isNullOrBlank()) {
                        return top
                    }
                } finally {
                    winList.forEach { it.recycleWindowCompat() }
                }
            }
        }
        val fallbackRoot = runCatching { rootInActiveWindow }.getOrNull()
        val fallbackPkg = try {
            fallbackRoot?.packageName?.toString()
        } finally {
            fallbackRoot.recycleNodeCompat()
        }
        return fallbackPkg?.takeIf { it.isNotBlank() }?.let { pkg ->
            WindowCandidate(
                id = -1,
                type = AccessibilityWindowInfo.TYPE_APPLICATION,
                isActive = true,
                isFocused = true,
                layer = 0,
                isInPictureInPictureMode = false,
                packageName = pkg
            )
        }
    }

    private fun resolveFocusedApplicationPackage(): String? =
        resolveFocusedApplicationWindow()?.packageName

    private fun AccessibilityNodeInfo?.recycleNodeCompat() {
        if (this == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        @Suppress("DEPRECATION")
        runCatching { recycle() }
    }

    private fun AccessibilityWindowInfo?.recycleWindowCompat() {
        if (this == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        @Suppress("DEPRECATION")
        runCatching { recycle() }
    }

    /**
     * 锁定唯一顶层应用窗口并采集可见节点与结构元信息：
     * 1. 绝不将多个窗口的文本拼接混入（杜绝底栏聊天/首页文字与顶层弹窗拼成假账单）；
     * 2. 在遍历 550 个文本节点前，先做 Activity 容器分级与 5 层骨架指纹黑名单拦截；
     * 3. 仅遍历 `node.isVisibleToUser` 的可见节点，同时捕获 `isPassword` 与 `isEditable` 硬件特征。
     */
    private fun collectPrimaryWindowSnapshot(
        targetPkg: String,
        eventSource: AccessibilityNodeInfo?,
        forceManual: Boolean
    ): CapturedWindowSnapshot? {
        var resolvedActivity = resolveActivityForEventWindow(targetPkg, -1)
        var rootToInspect: AccessibilityNodeInfo? = null
        var shouldRecycleRoot = false

        runCatching {
            val winList = windows
            if (!winList.isNullOrEmpty()) {
                try {
                    val candidates = ArrayList<WindowCandidate>(winList.size)
                    for (win in winList) {
                        if (win.isInPictureInPictureMode || win.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                            continue
                        }
                        val cachedPkg = windowPackages[win.id]
                        val winPkg = if (!cachedPkg.isNullOrBlank()) {
                            cachedPkg
                        } else {
                            val r = runCatching { win.root }.getOrNull()
                            try {
                                r?.packageName?.toString()
                            } finally {
                                r.recycleNodeCompat()
                            }
                        }
                        if (!winPkg.isNullOrBlank() && winPkg in systemOverlayPackages) {
                            continue
                        }
                        candidates.add(
                            WindowCandidate(
                                id = win.id,
                                type = win.type,
                                isActive = win.isActive,
                                isFocused = win.isFocused,
                                layer = win.layer,
                                isInPictureInPictureMode = false,
                                packageName = winPkg
                            )
                        )
                    }

                    val selected = CaptureWindowGuard.selectTopApplicationWindow(candidates)
                    if (selected != null) {
                        // 当前最顶层焦点应用窗口已切走（不再是 targetPkg），立即放弃，防止残留延迟探测误读前台其他应用！
                        if (!selected.packageName.isNullOrBlank() && selected.packageName != targetPkg) {
                            return null
                        }
                        resolvedActivity = resolveActivityForEventWindow(targetPkg, selected.id)
                        val matchedWin = winList.firstOrNull { it.id == selected.id }
                        val winRoot = runCatching { matchedWin?.root }.getOrNull()
                        if (winRoot != null) {
                            val rootPkg = winRoot.packageName?.toString().orEmpty()
                            if (rootPkg.isEmpty() || rootPkg == targetPkg) {
                                rootToInspect = winRoot
                                shouldRecycleRoot = true
                            } else {
                                winRoot.recycleNodeCompat()
                                return null
                            }
                        }
                    }
                } finally {
                    winList.forEach { it.recycleWindowCompat() }
                }
            }
        }

        if (rootToInspect == null) {
            val activeRoot = runCatching { rootInActiveWindow }.getOrNull()
            if (activeRoot != null) {
                val rootPkg = activeRoot.packageName?.toString().orEmpty()
                if (rootPkg == targetPkg) {
                    rootToInspect = activeRoot
                    shouldRecycleRoot = true
                } else {
                    activeRoot.recycleNodeCompat()
                }
            }
        }

        if (rootToInspect == null && eventSource != null) {
            runCatching {
                var top: AccessibilityNodeInfo = eventSource
                var guard = 0
                while (guard < 35) {
                    val p = top.parent ?: break
                    if (top !== eventSource) {
                        top.recycleNodeCompat()
                    }
                    top = p
                    guard++
                }
                if (top.packageName?.toString() == targetPkg) {
                    rootToInspect = top
                    shouldRecycleRoot = top !== eventSource
                } else if (top !== eventSource) {
                    top.recycleNodeCompat()
                }
            }
        }

        val role = CaptureWindowGuard.classifyActivityContainer(targetPkg, resolvedActivity)
        val blockedByContainer = role == WindowContainerRole.SENSITIVE_AUTH_BLOCKED ||
            role == WindowContainerRole.NON_TRANSACTION_BLOCKED

        val root = rootToInspect
        if (root == null) {
            return CapturedWindowSnapshot(
                packageName = targetPkg,
                verifiedActivity = resolvedActivity,
                structureFingerprint = "",
                snippets = emptyList(),
                hasVisiblePasswordNode = role == WindowContainerRole.SENSITIVE_AUTH_BLOCKED,
                hasVisibleEditableNode = false,
                isSignatureBlocked = false,
                blockedByContainer = blockedByContainer
            )
        }

        try {
            if (!forceManual && blockedByContainer) {
                return CapturedWindowSnapshot(
                    packageName = targetPkg,
                    verifiedActivity = resolvedActivity,
                    structureFingerprint = "",
                    snippets = emptyList(),
                    hasVisiblePasswordNode = role == WindowContainerRole.SENSITIVE_AUTH_BLOCKED,
                    hasVisibleEditableNode = false,
                    isSignatureBlocked = false,
                    blockedByContainer = true
                )
            }

            val fingerprint = runCatching {
                CaptureWindowGuard.computeStructureFingerprintFromAccessibilityRoot(root)
            }.getOrDefault("")

            val sigBlocked = fingerprint.isNotEmpty() &&
                CaptureAppWhitelistManager.isWindowSignatureBlocked(
                    packageName = targetPkg,
                    activityName = resolvedActivity,
                    structureFingerprint = fingerprint
                )

            if (!forceManual && sigBlocked) {
                return CapturedWindowSnapshot(
                    packageName = targetPkg,
                    verifiedActivity = resolvedActivity,
                    structureFingerprint = fingerprint,
                    snippets = emptyList(),
                    hasVisiblePasswordNode = false,
                    hasVisibleEditableNode = false,
                    isSignatureBlocked = true,
                    blockedByContainer = false
                )
            }

            val collector = TraversalCollector()
            collectVisibleNodeRecursive(root, collector, depth = 0)

            return CapturedWindowSnapshot(
                packageName = targetPkg,
                verifiedActivity = resolvedActivity,
                structureFingerprint = fingerprint,
                snippets = deduplicateConsecutive(collector.snippets),
                hasVisiblePasswordNode = collector.hasVisiblePasswordNode,
                hasVisibleEditableNode = collector.hasVisibleEditableNode,
                isSignatureBlocked = sigBlocked,
                blockedByContainer = blockedByContainer
            )
        } finally {
            if (shouldRecycleRoot) {
                root.recycleNodeCompat()
            }
        }
    }

    private fun deduplicateConsecutive(list: List<String>): List<String> {
        val deduplicated = ArrayList<String>(list.size)
        for (item in list) {
            if (deduplicated.lastOrNull() != item) {
                deduplicated.add(item)
            }
        }
        return deduplicated
    }

    private fun collectVisibleNodeRecursive(
        node: AccessibilityNodeInfo?,
        collector: TraversalCollector,
        depth: Int
    ) {
        if (node == null ||
            collector.hasVisiblePasswordNode ||
            depth > MAX_TRAVERSE_DEPTH ||
            collector.snippets.size >= MAX_TRAVERSE_NODES
        ) {
            return
        }

        if (depth > 0 && !node.isVisibleToUser) {
            return
        }

        if (node.isPassword) {
            collector.hasVisiblePasswordNode = true
            return
        }
        if (node.isEditable) {
            collector.hasVisibleEditableNode = true
        }

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        if (!text.isNullOrEmpty() && text.length <= 600) {
            collector.snippets.add(text)
        }
        if (!desc.isNullOrEmpty() && desc.length <= 600 && desc != text) {
            collector.snippets.add(desc)
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (collector.hasVisiblePasswordNode || collector.snippets.size >= MAX_TRAVERSE_NODES) break
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            try {
                collectVisibleNodeRecursive(child, collector, depth + 1)
            } finally {
                child.recycleNodeCompat()
            }
        }
    }

    /**
     * 生成包含「详情丰富度」的稳定页面指纹：
     * - 仅提取显式标签后的真实订单号（排除拼多多图片文件名/MD5 哈希，防止滚动页面时因图片哈希变化重复触发）；
     * - 如果停留在同一账单页且未展开任何新字段，指纹不变，0 重复触发；
     * - 如果用户在账单详情页点开了「更多 / 查看更多订单信息」（新露出了订单号、商家订单号、支付时间、商品说明或付款方式），指纹立即变化并触发增量详情补全！
     */
    private fun extractStablePageIdentity(pkg: String, fullPageText: String): String {
        val cleanText = stripEcommerceRecommendationFooter(fullPageText)
        val orders = PAGE_IDENTITY_ORDER_REGEX.findAll(cleanText)
            .map { it.groupValues[1].trim() }
            .filter { it.count { ch -> ch.isDigit() } >= 10 }
            .distinct()
            .joinToString(",")
        val timeMatch = PAGE_IDENTITY_TIME_REGEX.find(cleanText)?.groupValues?.getOrNull(1).orEmpty()
        val amtMatch = PAGE_IDENTITY_AMOUNT_REGEX.find(cleanText)?.value.orEmpty()
        val detailFlags = buildString {
            if (cleanText.contains("交易单号") || cleanText.contains("订单号") || cleanText.contains("订单编号") || cleanText.contains("转账单号")) append("O")
            if (cleanText.contains("商户单号") || cleanText.contains("商家订单号")) append("M")
            if (cleanText.contains("支付方式") || cleanText.contains("付款方式") || cleanText.contains("收款方式")) append("P")
            if (cleanText.contains("商品说明") || cleanText.contains("商户全称") || cleanText.contains("收款方全称")) append("G")
            if (cleanText.contains("下单时间") || cleanText.contains("拼单时间") || cleanText.contains("支付时间") || cleanText.contains("退款时间")) append("T")
            if (cleanText.contains("合计") || cleanText.contains("到手") || cleanText.contains("共减")) append("D")
        }
        return if (orders.isNotBlank() || timeMatch.isNotBlank() || amtMatch.isNotBlank()) {
            "$pkg|orders:$orders|time:$timeMatch|amt:$amtMatch|flags:$detailFlags"
        } else {
            "$pkg|${cleanText.take(200)}"
        }
    }

    override fun onInterrupt() {
        clearScheduledProbes()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        clearScheduledProbes()
        windowActivities.clear()
        windowPackages.clear()
        if (instance === this) {
            instance = null
            _isConnectedFlow.value = false
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearScheduledProbes()
        windowActivities.clear()
        windowPackages.clear()
        mainHandler.removeCallbacksAndMessages(null)
        RecognitionBanner.dismissImmediate()
        if (ocrRecognizerInitialized) {
            runCatching { chineseOcrRecognizer.close() }
            ocrRecognizerInitialized = false
        }
        runCatching { screenshotIoExecutor.shutdown() }
        if (instance === this) {
            instance = null
            _isConnectedFlow.value = false
        }
        super.onDestroy()
    }
}
