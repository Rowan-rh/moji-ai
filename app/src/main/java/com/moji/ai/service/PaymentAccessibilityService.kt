package com.moji.ai.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import kotlin.math.abs

/**
 * 免 Root 微信 / 支付宝 / 云闪付 / 银行 App 自动识账无障碍服务（参考 AutoAccounting、花有谱账本与蓝鲸记账）：
 * 1. 通过 SelectToSpeakService 兼容入口 + FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY 突破微信 WebView/LiteApp 节点屏蔽；
 * 2. 多窗口焦点择优遍历（深度 50、节点上限 500）+ 4 阶延迟探测（150/450/1000/1800ms），兼容异步渲染的支付完成页与账单详情页；
 * 3. Android 11+ 免 Root takeScreenshot + 端侧离线中文 OCR（按行 Y 坐标对齐左右分栏键值）兜底自绘 Canvas 页面与快捷磁贴手动识屏。
 */
open class PaymentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PaymentAccessibilityService? = null
            private set

        private val PROBE_DELAYS_MS = longArrayOf(150L, 450L, 1000L, 1800L)
        private val CONTENT_PROBE_DELAYS_MS = longArrayOf(120L, 480L)
        private const val OBSERVE_WINDOW_MS = 3_500L
        private const val WECHAT_TAIL_WINDOW_MS = 60_000L
        private const val CONTENT_THROTTLE_MS = 280L
        private const val OCR_COOLDOWN_MS = 2_800L
        private const val MAX_TRAVERSE_DEPTH = 50
        private const val MAX_TRAVERSE_NODES = 500
    }

    private val targetFinancialPackages = setOf(
        "com.tencent.mm",                   // 微信
        "com.eg.android.AlipayGphone",      // 支付宝
        "com.unionpay",                     // 云闪付
        "com.taobao.taobao",                // 淘宝
        "com.sankuai.meituan",              // 美团
        "com.xunmeng.pinduoduo",            // 拼多多
        "com.jingdong.app.mall",            // 京东
        "cmb.pb",                           // 招商银行
        "com.chinamworld.main",             // 建设银行
        "com.icbc",                         // 工商银行
        "com.android.mms"                   // 短信
    )

    private val systemIgnoredPackages = setOf(
        "com.android.systemui",
        "android",
        "com.moji.ai"
    )

    // 单笔账单完成态或详情页触发词
    private val pageTriggerKeywords = listOf(
        "支付成功", "付款成功", "交易成功", "转账成功", "收款成功",
        "对方已收钱", "已收钱", "已存入零钱", "还款成功", "提现已发起",
        "退款成功", "已全额退款", "已退款", "扣款成功", "代付成功",
        "账单详情", "交易详情", "当前状态", "交易状态", "交易单号",
        "商户单号", "转账单号", "商家订单号"
    )

    // 单笔详情页专属字段（用于区分「账单列表页 / 微信支付服务号对话页」与「单笔账单详情页」）
    private val singleBillDetailAnchors = listOf(
        "当前状态", "交易状态",
        "交易单号", "商户单号", "转账单号", "商家订单号", "订单号",
        "支付时间", "转账时间", "收款时间", "创建时间",
        "商户全称", "收款方全称"
    )

    // 账单列表页或微信支付服务号历史卡片流特征词（若出现这些词且没有任何单笔详情锚点，说明在浏览列表或服务号历史消息，不触发单笔入账）
    private val multiBillListMarkers = listOf(
        "全部账单", "收支统计", "月账单", "统计", "全部交易类型",
        "搜索小程序", "最近使用的小程序", "常用的小程序",
        "切换到发消息", "我的账单", "支付服务"
    )

    // 密码输入 / 待确认付款拦截词
    private val passwordOrPendingMarkers = listOf(
        "请输入支付密码", "请输入密码", "指纹支付", "面容支付", "验证指纹", "确认付款"
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scheduledProbes = mutableListOf<Runnable>()
    private val chineseOcrRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private var activePackage: String = ""
    private var activeClassName: String = ""
    private var observeUntilMillis: Long = 0L
    private var lastWechatActiveMillis: Long = 0L
    private var lastContentInspectMillis: Long = 0L
    private var lastDispatchedSignature: String = ""
    private var lastDispatchedMillis: Long = 0L
    private var lastAutoOcrMillis: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
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

        val app = applicationContext as? MoJiApp ?: return
        if (!app.aiConfigManager.getConfig().enableAccessibilityCapture) return

        val now = System.currentTimeMillis()
        val rawPkg = event.packageName?.toString().orEmpty()
        val pkg = if (rawPkg.isNotBlank() && rawPkg !in systemIgnoredPackages) {
            rawPkg
        } else {
            resolveFocusedApplicationPackage() ?: activePackage
        }
        if (pkg.isBlank() || pkg in systemIgnoredPackages) return

        if (pkg == "com.tencent.mm") {
            lastWechatActiveMillis = now
        }
        val inWechatTailWindow = (now - lastWechatActiveMillis) in 0..WECHAT_TAIL_WINDOW_MS
        if (pkg !in targetFinancialPackages && !inWechatTailWindow) return

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = event.className?.toString().orEmpty()
            activePackage = pkg
            if (cls.isNotBlank() && !cls.startsWith("android.widget.") && !cls.startsWith("android.view.")) {
                activeClassName = cls
            }
            if (isExcludedActivityClass(activeClassName)) {
                clearScheduledProbes()
                return
            }
            observeUntilMillis = now + OBSERVE_WINDOW_MS
            scheduleProbeWave(pkg, PROBE_DELAYS_MS)
            // 同时立即对当前事件做一次快速探测
            inspectCurrentScreen(pkg, event.source, allowAutoOcrFallback = false)
            return
        }

        // TYPE_WINDOW_CONTENT_CHANGED / TYPE_WINDOWS_CHANGED：
        // 微信「账单 -> 账单详情」与支付宝 H5 详情页常在同一个 WebView/LiteApp Activity 内发生 DOM 变化而不触发 WINDOW_STATE_CHANGED
        if (now - lastContentInspectMillis < CONTENT_THROTTLE_MS) return
        lastContentInspectMillis = now

        inspectCurrentScreen(pkg, event.source, allowAutoOcrFallback = false)
        scheduleProbeWave(pkg, CONTENT_PROBE_DELAYS_MS)
    }

    /**
     * 手动或从快捷设置磁贴触发一次当前屏幕识账（先读无障碍节点，若未命中金额则调用 Android 11+ 免 Root 截屏 OCR）
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
                ?.takeIf { it !in systemIgnoredPackages }
                ?: activePackage.ifBlank { "com.tencent.mm" }

            val capturedByNodes = inspectCurrentScreen(
                pkg = pkg,
                eventSource = null,
                allowAutoOcrFallback = false,
                forceManual = true
            )
            if (capturedByNodes) {
                onResultMessage?.invoke("已通过页面节点识别账单")
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

    private fun scheduleProbeWave(pkg: String, delays: LongArray) {
        clearScheduledProbes()
        for ( idx in delays.indices) {
            val delay = delays[idx]
            val isLastProbe = idx == delays.lastIndex
            val task = Runnable {
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
        val snippets = collectWindowSnippets(pkg, eventSource)
        if (snippets.isEmpty()) {
            if (allowAutoOcrFallback && shouldAttemptAutoOcr(pkg, "")) {
                captureScreenshotAndRunOcr(pkg, isManualTrigger = false, onResultMessage = null)
            }
            return false
        }

        val fullPageText = snippets.joinToString(" | ")
        if (!forceManual && !isValidSingleBillOrCompletionPage(fullPageText)) {
            return false
        }

        // 检查页面是否已经渲染出有效金额（包括 ¥12.34、12.34元 或账单详情页的独立金额 -12.34 / +12.34）
        val hasAmount = containsLikelyBillAmount(fullPageText)
        if (!hasAmount) {
            if (allowAutoOcrFallback && shouldAttemptAutoOcr(pkg, fullPageText)) {
                captureScreenshotAndRunOcr(pkg, isManualTrigger = false, onResultMessage = null)
            }
            return false
        }

        return dispatchCapturedText(
            fullPageText = fullPageText,
            pkg = pkg,
            captureSource = "AI_ACCESSIBILITY"
        )
    }

    private fun dispatchCapturedText(
        fullPageText: String,
        pkg: String,
        captureSource: String
    ): Boolean {
        val now = System.currentTimeMillis()
        val signature = "$pkg|${fullPageText.take(260)}"
        if (signature == lastDispatchedSignature && (now - lastDispatchedMillis) < 4_500L) {
            return true
        }
        lastDispatchedSignature = signature
        lastDispatchedMillis = now

        val app = applicationContext as? MoJiApp ?: return false
        app.captureCoordinator.onRawFinancialEventCaptured(
            rawText = fullPageText,
            packageName = pkg,
            captureSource = captureSource
        )
        return true
    }

    private fun isValidSingleBillOrCompletionPage(text: String): Boolean {
        // 含密码输入且尚未出现完成态/详情锚点时忽略
        val hasCompletionOrDetail = pageTriggerKeywords.any { text.contains(it) }
        if (!hasCompletionOrDetail) return false

        val hasDetailAnchor = singleBillDetailAnchors.any { text.contains(it) }
        if (passwordOrPendingMarkers.any { text.contains(it) } && !hasDetailAnchor && !text.contains("支付成功") && !text.contains("交易成功")) {
            return false
        }

        // 区分「月度账单列表页」与「单笔账单详情页」
        val looksLikeBillList = multiBillListMarkers.any { text.contains(it) }
        if (looksLikeBillList && !hasDetailAnchor) {
            return false
        }

        // 排除微信聊天窗口或服务号消息列表里未点开的卡片流
        if ((text.contains("按住 说话") || text.contains("切换到按住说话") ||
                text.contains("可按住 转文字") || text.contains("交易详情〉") ||
                text.contains("账单详情>")) && !hasDetailAnchor
        ) {
            return false
        }

        return true
    }

    private fun containsLikelyBillAmount(text: String): Boolean {
        // 1. 显式带货币符号或“元”的金额
        if (Regex("""(?:¥|￥)\s*\d+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?\s*元""").containsMatchIn(text)) {
            return true
        }
        // 2. 微信/支付宝「账单详情」页中带正负号或两位小数的独立金额节点（如 "| -28.50 |" 或 "| 15.00 |"）
        if (Regex("""(?:^|\||\s)[-+−]?\d{1,7}\.\d{2}(?:\s|\||$)""").containsMatchIn(text)) {
            return true
        }
        return false
    }

    private fun shouldAttemptAutoOcr(pkg: String, partialText: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (pkg != "com.tencent.mm" && pkg != "com.eg.android.AlipayGphone") return false
        val now = System.currentTimeMillis()
        if (now - lastAutoOcrMillis < OCR_COOLDOWN_MS) return false

        // 若已经命中多账单列表或服务号历史列表标记，绝不触发自动截屏 OCR
        if (multiBillListMarkers.any { partialText.contains(it) }) return false

        val cls = activeClassName.lowercase()
        if (cls.contains("launcherui") || cls.contains("billmainlist")) return false

        val isLikelyPayOrBillWindow = cls.contains("webview") ||
            cls.contains("liteapp") ||
            cls.contains("wallet") ||
            cls.contains("remittance") ||
            cls.contains("luckymoney") ||
            cls.contains("pay") ||
            cls.contains("nebula") ||
            cls.contains("xriver") ||
            cls.contains("h5")

        return isLikelyPayOrBillWindow
    }

    private fun captureScreenshotAndRunOcr(
        pkg: String,
        isManualTrigger: Boolean,
        onResultMessage: ((String) -> Unit)?
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        lastAutoOcrMillis = System.currentTimeMillis()

        // 截屏前先隐藏顶部胶囊横幅，避免把横幅文字截入图片
        RecognitionBanner.dismissImmediate()

        mainHandler.postDelayed({
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
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
                                onResultMessage?.invoke("截屏缓冲区转换失败")
                                return
                            }

                            val processedBitmap = cropAndScaleForOcr(rawBitmap)
                            if (processedBitmap !== rawBitmap) {
                                rawBitmap.recycle()
                            }

                            val image = InputImage.fromBitmap(processedBitmap, 0)
                            chineseOcrRecognizer.process(image)
                                .addOnSuccessListener { visionText ->
                                    processedBitmap.recycle()
                                    val orderedText = formatOcrLinesInReadingOrder(visionText)
                                    if (orderedText.isBlank()) {
                                        onResultMessage?.invoke("未识别到屏幕文字")
                                        return@addOnSuccessListener
                                    }
                                    if (!isManualTrigger && !isValidSingleBillOrCompletionPage(orderedText)) {
                                        return@addOnSuccessListener
                                    }
                                    if (!containsLikelyBillAmount(orderedText)) {
                                        onResultMessage?.invoke("屏幕中未检测到有效账单金额")
                                        return@addOnSuccessListener
                                    }
                                    dispatchCapturedText(
                                        fullPageText = orderedText,
                                        pkg = pkg,
                                        captureSource = "AI_OCR"
                                    )
                                    if (isManualTrigger) {
                                        Toast.makeText(
                                            this@PaymentAccessibilityService,
                                            "默记：已提取屏幕账单",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    onResultMessage?.invoke("已通过端侧 OCR 提取屏幕账单")
                                }
                                .addOnFailureListener {
                                    processedBitmap.recycle()
                                    onResultMessage?.invoke("OCR 识别失败：${it.localizedMessage ?: "未知错误"}")
                                }
                        }

                        override fun onFailure(errorCode: Int) {
                            onResultMessage?.invoke("免 Root 截屏受限 (code=$errorCode)")
                        }
                    }
                )
            } catch (e: Throwable) {
                onResultMessage?.invoke("截屏调用异常：${e.localizedMessage}")
            }
        }, 220L)
    }

    /**
     * 裁掉顶部状态栏（时间/电量百分比）与底部导航条，避免状态栏数字干扰金额提取，并按短边 1080px 等比缩放提升 OCR 速度
     */
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

    /**
     * 将 ML Kit 识别到的多列文本行按「从上到下、同行从左到右」重排：
     * 微信/支付宝「账单详情」左列是字段名（如「当前状态」「商户全称」「支付方式」），右列是对应值，
     * 默认块序会把左列全部排完再排右列；按 Y 轴行聚类后再按 X 轴排序，即可还原 `字段名 | 字段值` 顺序。
     */
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

    private fun resolveFocusedApplicationPackage(): String? {
        runCatching {
            val winList = windows
            if (!winList.isNullOrEmpty()) {
                val appWins = winList.filter {
                    !it.isInPictureInPictureMode &&
                        it.type == AccessibilityWindowInfo.TYPE_APPLICATION
                }
                val best = appWins.sortedWith(
                    compareByDescending<AccessibilityWindowInfo> { it.isFocused }
                        .thenByDescending { it.isActive }
                        .thenByDescending { it.layer }
                ).firstOrNull()
                val pkg = best?.root?.packageName?.toString()
                if (!pkg.isNullOrBlank()) return pkg
            }
        }
        return rootInActiveWindow?.packageName?.toString()
    }

    private fun collectWindowSnippets(
        targetPkg: String,
        eventSource: AccessibilityNodeInfo?
    ): List<String> {
        val outList = mutableListOf<String>()
        val visitedRoots = mutableSetOf<Int>()

        // 1. 优先遍历交互窗口列表中的目标应用窗口（只取 rootPkg == targetPkg，绝不混入其他记账 App 的悬浮窗或系统侧边栏！）
        runCatching {
            val winList = windows
            if (!winList.isNullOrEmpty()) {
                val candidates = winList
                    .filter {
                        !it.isInPictureInPictureMode &&
                            it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                            it.root?.packageName?.toString() == targetPkg
                    }
                    .sortedWith(
                        compareByDescending<AccessibilityWindowInfo> { it.isActive }
                            .thenByDescending { it.isFocused }
                            .thenByDescending { it.layer }
                    )

                for (win in candidates) {
                    val root = win.root ?: continue
                    if (visitedRoots.add(System.identityHashCode(root))) {
                        collectNodeTextRecursive(root, outList, depth = 0)
                    }
                    if (outList.size >= MAX_TRAVERSE_NODES) break
                }
            }
        }

        // 2. 补充 rootInActiveWindow（同样必须校验 rootPkg == targetPkg）
        runCatching {
            val activeRoot = rootInActiveWindow
            if (activeRoot != null && visitedRoots.add(System.identityHashCode(activeRoot))) {
                val rootPkg = activeRoot.packageName?.toString().orEmpty()
                if (rootPkg == targetPkg) {
                    collectNodeTextRecursive(activeRoot, outList, depth = 0)
                }
            }
        }

        // 3. 补充 event.source 向上回溯的根节点（针对 WebView 内部局部刷新事件，且仅当上方未采集到足够节点时补充）
        if (eventSource != null && outList.size < 25) {
            runCatching {
                var top: AccessibilityNodeInfo = eventSource
                var guard = 0
                while (guard < 35) {
                    val p = top.parent ?: break
                    top = p
                    guard++
                }
                if (top.packageName?.toString() == targetPkg && visitedRoots.add(System.identityHashCode(top))) {
                    collectNodeTextRecursive(top, outList, depth = 0)
                }
            }
        }

        // 保留相邻顺序并去重连续完全相同的碎片（注意不能用全局 distinct()，否则正文中与标题同名的商户会被吞掉！）
        val deduplicated = mutableListOf<String>()
        for (item in outList) {
            if (deduplicated.lastOrNull() != item) {
                deduplicated.add(item)
            }
        }
        return deduplicated
    }

    private fun collectNodeTextRecursive(
        node: AccessibilityNodeInfo?,
        outList: MutableList<String>,
        depth: Int
    ) {
        if (node == null || depth > MAX_TRAVERSE_DEPTH || outList.size >= MAX_TRAVERSE_NODES) return

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        if (!text.isNullOrEmpty() && text.length <= 120) {
            outList.add(text)
        }
        if (!desc.isNullOrEmpty() && desc.length <= 120 && desc != text) {
            outList.add(desc)
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (outList.size >= MAX_TRAVERSE_NODES) break
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectNodeTextRecursive(child, outList, depth + 1)
        }
    }

    private fun isExcludedActivityClass(className: String): Boolean {
        if (className.isBlank()) return false
        val lower = className.lowercase()
        return lower.contains("inputmethod") ||
            lower.contains("albumpreview") ||
            lower.contains("imagegallery") ||
            lower.contains("snsupload") ||
            lower.contains("scanner") ||
            lower.contains("camera")
    }

    override fun onInterrupt() {
        clearScheduledProbes()
    }

    override fun onDestroy() {
        clearScheduledProbes()
        RecognitionBanner.dismissImmediate()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }
}
