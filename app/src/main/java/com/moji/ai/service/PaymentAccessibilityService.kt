package com.moji.ai.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
import com.moji.ai.ai.AiAccountingEngine
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 免 Root 微信 / 支付宝 / 云闪付 / 银行 App 自动识账无障碍服务：
 * 1. 通过 SelectToSpeakService 兼容入口 + FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY 突破微信 WebView/LiteApp 节点屏蔽；
 * 2. 严格区分「真实单笔账单详情页 / 支付完成页」与「收银台待付款页 / 支付宝首页消息盒子 / 微信支付服务号对话列表 / 账单列表页」；
 * 3. Android 11+ 免 Root takeScreenshot + 本地账单截图持久化（WebP + SHA-256）+ 端侧离线中文 OCR。
 */
open class PaymentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PaymentAccessibilityService? = null
            private set

        private val PROBE_DELAYS_MS = longArrayOf(60L, 200L, 450L, 850L, 1350L, 1950L)
        private val CONTENT_PROBE_DELAYS_MS = longArrayOf(95L, 280L, 650L, 1250L)
        private const val OBSERVE_WINDOW_MS = 6_000L
        private const val WECHAT_TAIL_WINDOW_MS = 3_500L
        private const val CONTENT_THROTTLE_MS = 90L
        private const val OCR_COOLDOWN_MS = 4_500L
        private const val MAX_TRAVERSE_DEPTH = 50
        private const val MAX_TRAVERSE_NODES = 550

        private val BILL_AMOUNT_PRIMARY_REGEX = Regex(
            """(?<!本月支出)(?:¥|￥)\s*\d+(?:\.\d{1,2})?|(?:支出|收入|付款|收款|实付|实付款|应付款|应付金额|商品总额|合计)?\s*\d+(?:\.\d{1,2})?\s*元"""
        )
        private val BILL_AMOUNT_SPLIT_NODE_REGEX = Regex(
            """(?:¥|￥)\s*\|?\s*\d{1,7}\s*\|\s*\.\d{1,2}|(?:¥|￥)\s*\|?\s*\d{1,7}\.\s*\|\s*\d{1,2}|(?:¥|￥)\s*\|\s*\d{1,7}(?:\.\d{1,2})?"""
        )
        private val BILL_AMOUNT_SIGNED_DECIMAL_REGEX = Regex(
            """(?:^|\||\s)[-+−]?\d{1,7}\.\d{1,2}(?:\s|\||$)"""
        )
        private val PAGE_IDENTITY_ORDER_REGEX = Regex(
            """(?:交易单号|转账单号|订单编号|订单号|商户单号|商家订单号|支付宝交易号|流水号)\s*(?:[:：,，\s]|\|)*\s*([0-9A-Za-z_-]{10,44})"""
        )
        private val PAGE_IDENTITY_TIME_REGEX = Regex(
            """(?:支付时间|付款时间|下单时间|拼单时间|创建时间|转账时间|收款时间)\s*(?:[:：,，\s]|\|)*\s*(20\d{2}[-年/]\d{1,2}[-月/]\d{1,2}日?\s+\d{1,2}[:时]\d{2}(?:[:分]\d{2}秒?)?)"""
        )
        private val PAGE_IDENTITY_AMOUNT_REGEX = Regex(
            """(?:实付款|实付金额|合计实付|实扣|实付|应付总额|应付金额|应付款|需付款|合计|到手价|到手|支出|收入|付款|收款)\s*(?:[:：,，\s]|\|)*\s*(?:¥|￥)?\s*\d{1,7}(?:\.\d{1,2})?\s*[元)）]?|[-+−]?\d{1,7}\.\d{1,2}"""
        )
    }

    private val targetFinancialPackages = setOf(
        "com.tencent.mm",                   // 微信 / 微信支付
        "com.eg.android.AlipayGphone",      // 支付宝
        "com.unionpay",                     // 云闪付
        "com.xunmeng.pinduoduo",            // 拼多多 / 多多支付
        "com.jingdong.app.mall",            // 京东 / 京东支付 / 京东白条
        "com.ss.android.ugc.aweme",         // 抖音 / 抖音支付 / 抖音商城
        "com.ss.android.ugc.aweme.lite",    // 抖音极速版 / 抖音支付
        "com.ss.android.ugc.live",          // 抖音火山版
        "com.taobao.taobao",                // 淘宝
        "com.tmall.wireless",               // 天猫
        "com.sankuai.meituan",              // 美团 / 美团月付
        "com.sankuai.meituan.takeoutnew",   // 美团外卖
        "me.ele",                           // 饿了么
        "com.achievo.vipshop",              // 唯品会
        "com.xingin.xhs",                   // 小红书商城
        "cmb.pb",                           // 招商银行
        "com.chinamworld.main",             // 建设银行
        "com.icbc",                         // 工商银行
        "com.android.mms"                   // 短信
    )

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

    // 单笔详情页专属字段（同时覆盖微信/支付宝账单详情，以及拼多多/抖音/京东/淘宝/美团订单详情与支付结果页）
    private val singleBillDetailAnchors = listOf(
        "当前状态", "交易状态",
        "交易单号", "商户单号", "转账单号", "商家订单号", "订单号", "订单编号", "支付宝交易号",
        "支付时间", "创建时间", "付款时间", "转账时间", "收款时间", "下单时间", "拼单时间", "成交时间",
        "付款方式", "支付方式", "收款方式", "退回方式", "转入账户", "配送方式", "配送服务",
        "实付", "实付款", "实付金额", "应付总额", "应付金额", "应付款", "需付款", "商品总价", "商品总额", "订单总价", "订单金额",
        "店铺优惠", "平台优惠", "百亿补贴", "支付优惠", "多多支付", "抖音支付", "京东支付", "京东白条",
        "商品说明", "转账备注", "对方账户",
        "商户全称", "收款方全称", "收单机构",
        "退换/售后", "申请售后", "查看发票", "退换无忧",
        "账单分类", "账单管理", "计入收支", "对此订单有疑问", "对订单有疑惑", "申请电子回单", "申请电子凭证"
    )

    // 账单列表页、支付宝首页消息盒子、支付消息汇总页、微信支付服务号历史对话流、电商全部订单列表/购物车特征词
    // 注意：支付宝单笔「账单详情」页右上角固定带有「全部账单」按钮，分类中可能带有「交通出行」，故绝不能把「全部账单」「出行」「卡包」放进黑名单！
    private val multiBillOrFeedBlacklist = listOf(
        "收支统计", "月账单", "本月支出", "较上月", "全部交易类型", "支付消息", "服务消息",
        "搜索小程序", "最近使用的小程序", "常用的小程序",
        "切换到发消息", "我的账单", "支付服务", "浮窗", "退出星标", "转账过期退还通知",
        "扫一扫", "收付款", "消息盒子", "最近消息",
        "我的订单", "全部订单", "购物车", "去结算"
    )

    // 收银台待确认付款 / 密码输入页 / 电商提交订单结算页特征词（命中任一即 100% 属于未完成付款页面，绝不入账）
    private val cashierOrPendingBlacklist = listOf(
        "确认付款", "立即付款", "立即支付", "去付款", "去支付", "请输入支付密码",
        "请输入密码", "指纹支付", "面容支付", "验证指纹", "选择付款方式",
        "已选中,", "未选中,", "付款金额",
        "提交订单", "确认订单", "填写订单", "等待买家付款", "等待付款", "剩余支付时间", "去凑单"
    )

    // 真实支付完成结果页关键词（需先剔除“支付成功得绿色能量”等促销文案后再匹配）
    private val completionResultKeywords = listOf(
        "支付成功", "付款成功", "交易成功", "转账成功", "收款成功", "拼单成功", "下单成功", "购买成功",
        "对方已收钱", "已收钱", "已存入零钱", "还款成功", "提现已发起",
        "退款成功", "已全额退款", "扣款成功", "代付成功"
    )

    private val promoNoiseRegex = Regex("""(?:支付成功|付款成功)(?:得|可领|领|享|抽|立减|返|送|获)[^\s|，。]{1,14}""")

    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotIoExecutor = Executors.newSingleThreadExecutor()
    private val scheduledProbes = mutableListOf<Runnable>()
    @Volatile
    private var ocrRecognizerInitialized = false
    private val chineseOcrRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()).also {
            ocrRecognizerInitialized = true
        }
    }

    private var activePackage: String = ""
    private var activeClassName: String = ""
    private var observeUntilMillis: Long = 0L
    private var lastWechatActiveMillis: Long = 0L
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
        val rawCls = event.className?.toString().orEmpty()

        // 完全忽略我们自己的流体云悬浮窗视图及系统侧边栏/输入法/辅助工具产生的事件
        if (rawPkg in systemOverlayPackages) return
        if ((rawPkg == packageName || rawPkg.startsWith("com.moji.ai")) && !rawCls.contains("MainActivity")) return

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 仅在真正的窗口切换事件（TYPE_WINDOW_STATE_CHANGED）时才更新当前前台应用包名
            val pkg = if (rawPkg.isNotBlank()) {
                rawPkg
            } else {
                resolveFocusedApplicationPackage()?.takeIf { it !in systemOverlayPackages }.orEmpty()
            }
            if (pkg.isBlank() || pkg in systemOverlayPackages) return

            if (pkg !in targetFinancialPackages) {
                // 确认前台确实切出了目标金融应用（如回到桌面 Launcher 或切到其他 App）
                if (!rawCls.startsWith("android.widget.") && !rawCls.startsWith("android.view.")) {
                    activePackage = pkg
                    lastWechatActiveMillis = 0L
                    isCurrentlyOnBillDetailPage = false
                    lastDispatchedSignature = ""
                    clearScheduledProbes()
                }
                return
            }

            activePackage = pkg
            if (pkg == "com.tencent.mm") {
                lastWechatActiveMillis = now
            }
            if (rawCls.isNotBlank() && !rawCls.startsWith("android.widget.") && !rawCls.startsWith("android.view.")) {
                activeClassName = rawCls
            }
            if (isExcludedActivityClass(activeClassName)) {
                clearScheduledProbes()
                return
            }
            if (isKnownNonBillListOrHomeActivity(activeClassName)) {
                clearScheduledProbes()
                isCurrentlyOnBillDetailPage = false
                observeUntilMillis = 0L
                lastDispatchedSignature = ""
                return
            }

            isCurrentlyOnBillDetailPage = false
            if (now - lastDispatchedMillis > 1_200L) {
                lastDispatchedSignature = ""
            }
            observeUntilMillis = now + OBSERVE_WINDOW_MS
            inspectCurrentScreen(pkg, event.source, allowAutoOcrFallback = false)
            scheduleProbeWave(pkg, PROBE_DELAYS_MS)
            return
        }

        // 对于 TYPE_WINDOW_CONTENT_CHANGED / TYPE_WINDOWS_CHANGED：
        // 1. 绝不用非目标包名（如桌面手势条或悬浮球）覆盖 activePackage 或清空微信/支付宝探测队列；
        // 2. 若用户从多任务切换回已打开的支付宝/微信（此时可能仅触发 TYPE_WINDOWS_CHANGED 且 rawPkg 为空），主动解析当前焦点应用窗口包名！
        val pkg = when {
            rawPkg in targetFinancialPackages -> {
                activePackage = rawPkg
                rawPkg
            }
            activePackage in targetFinancialPackages -> activePackage
            else -> {
                val focused = if (eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || rawPkg.isBlank()) {
                    resolveFocusedApplicationPackage()
                } else {
                    null
                }
                if (focused != null && focused in targetFinancialPackages) {
                    activePackage = focused
                    focused
                } else {
                    return
                }
            }
        }

        if (pkg == "com.tencent.mm") {
            lastWechatActiveMillis = now
        }

        // 当顶部流体云胶囊正在执行 500ms 展开动画时，暂缓高频内容扫描以确保 120Hz 零掉帧
        if (RecognitionBanner.isAnimatingNow()) {
            return
        }

        if (isKnownNonBillListOrHomeActivity(activeClassName) && now > observeUntilMillis) return

        // 无论是否命中节流窗口，只要当前尚未锁定账单详情页（或用户在详情页点击了“更多”触发内容更新），
        // 均挂载轻量级尾随探测波（110ms / 360ms / 820ms），确保支付宝 H5 / BirdNest 异步渲染完成后必被扫描！
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
            if (!forceManual && allowAutoOcrFallback && shouldAttemptAutoOcr(pkg, "")) {
                captureScreenshotAndRunOcr(pkg, isManualTrigger = false, onResultMessage = null)
            }
            return false
        }

        val rawJoinedText = snippets.joinToString(" | ")
        val fullPageText = stripEcommerceRecommendationFooter(rawJoinedText)
        if (!forceManual && !isValidSingleBillOrCompletionPage(pkg, fullPageText)) {
            if (isCurrentlyOnBillDetailPage && snippets.size >= 3) {
                isCurrentlyOnBillDetailPage = false
                lastDispatchedSignature = ""
            }
            if (allowAutoOcrFallback && shouldAttemptAutoOcr(pkg, fullPageText)) {
                captureScreenshotAndRunOcr(pkg, isManualTrigger = false, onResultMessage = null)
            }
            return false
        }

        val hasAmount = containsLikelyBillAmount(fullPageText)
        val hasOccurredTime = AiAccountingEngine.hasExplicitOccurredTime(fullPageText)
        if (!hasAmount || (forceManual && !hasOccurredTime)) {
            if (!forceManual && allowAutoOcrFallback && shouldAttemptAutoOcr(pkg, fullPageText)) {
                captureScreenshotAndRunOcr(pkg, isManualTrigger = false, onResultMessage = null)
            }
            return false
        }

        isCurrentlyOnBillDetailPage = true
        return dispatchCapturedText(
            fullPageText = fullPageText,
            pkg = pkg,
            captureSource = "AI_ACCESSIBILITY",
            forceManual = forceManual
        )
    }

    private fun dispatchCapturedText(
        fullPageText: String,
        pkg: String,
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
        Log.d("MoJiCapture", "Dispatch bill page: pkg=$pkg, identity=$pageIdentity")

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

    /**
     * 严格判定当前窗口是否为「真实的单笔账单详情页」或「已完成的支付结果页」：
     * 1. 先剔除“支付成功得绿色能量5g”等营销噪音；
     * 2. 命中收银台待付款词（如“确认付款”“已选中,”）直接拦截；
     * 3. 命中支付宝首页/消息盒子/支付消息列表/微信支付服务号对话列表词（如“扫一扫”“消息盒子”“本月支出”“切换到发消息”“我的账单”“浮窗”）直接拦截；
     * 4. 针对拼多多/京东/淘宝/抖音等电商订单详情页，准确识别单笔详情页并拦截订单列表页。
     */
    private fun isValidSingleBillOrCompletionPage(pkg: String, rawText: String): Boolean {
        val clsLower = activeClassName.lowercase()
        if (clsLower.contains("orderlistactivity") || clsLower.contains("billmainlist")) {
            return false
        }

        val text = stripEcommerceRecommendationFooter(rawText)
            .replace(promoNoiseRegex, " ")
            .replace("加入购物车", " ")
            .replace("加购物车", " ")

        // 1. 凡是包含收银台待确认付款特征词的页面，100% 属于付款前页面，严禁触发
        if (cashierOrPendingBlacklist.any { text.contains(it) }) {
            return false
        }

        // 2. 过滤聊天会话输入框、服务号底栏与电商「全部/待付款/待收货/待评价」订单列表顶栏
        if (text.contains("按住 说话") || text.contains("切换到按住说话") ||
            text.contains("可按住 转文字") || text.contains("切换到发消息") ||
            text.contains("转账过期退还通知")
        ) {
            return false
        }
        val isEcommerceOrderListTabs = text.contains("待付款") &&
            text.contains("待收货") &&
            (text.contains("待发货") || text.contains("待评价") || text.contains("待分享") || text.contains("退款/售后"))
        if (isEcommerceOrderListTabs) {
            return false
        }

        // 3. 铁律：页面必须包含明确的支付/交易/下单时间（如 2026-11-08 14:54:50、昨天 21:18、11-08 14:54）。
        // 像微信/支付宝仅展示「支付成功 | 商户 | ¥95.00 | 完成」却没有任何时间的过渡结果页，一律不予录入！
        if (!AiAccountingEngine.hasExplicitOccurredTime(text)) {
            return false
        }

        val hasExplicitOrderNumberLabel = text.contains("交易单号") ||
            text.contains("商户单号") ||
            text.contains("商家订单号") ||
            text.contains("订单编号") ||
            text.contains("订单号") ||
            text.contains("转账单号")

        val isEcommercePackage = pkg in setOf(
            "com.xunmeng.pinduoduo",
            "com.jingdong.app.mall",
            "com.taobao.taobao",
            "com.tmall.wireless",
            "com.ss.android.ugc.aweme",
            "com.ss.android.ugc.aweme.lite",
            "com.achievo.vipshop",
            "com.xingin.xhs"
        )
        val hasOrderHistoryIndicators = text.contains("已签收") ||
            text.contains("物流轨迹") ||
            text.contains("申请退款") ||
            text.contains("申请售后") ||
            text.contains("退换/售后") ||
            text.contains("售后详情") ||
            text.contains("钱款去向") ||
            text.contains("商品快照")
        val isLivePaymentSuccessPage = (text.contains("支付成功") || text.contains("付款成功") || text.contains("拼单成功")) &&
            !hasOrderHistoryIndicators

        // 若当前 Activity 明确是京东/电商单笔订单详情容器（如 com.jd.lib.ordercenter.taro.OrderDetailActivityTaro）
        if (clsLower.contains("orderdetailactivity")) {
            val hasJdDetailSignal = hasExplicitOrderNumberLabel ||
                text.contains("实付") || text.contains("应付") || text.contains("商品总额") ||
                text.contains("订单金额") || text.contains("申请售后") || text.contains("退换/售后") ||
                text.contains("查看发票") || text.contains("再次购买") || text.contains("退换无忧") ||
                text.contains("下单时间")
            if (hasJdDetailSignal) return true
        }

        if ((isEcommercePackage || hasOrderHistoryIndicators) && !isLivePaymentSuccessPage) {
            val hasExplicitActualPaid = text.contains("实付") ||
                text.contains("应付总额") ||
                text.contains("应付金额") ||
                text.contains("应付款") ||
                text.contains("实扣") ||
                text.contains("需付款") ||
                text.contains("商品总额") ||
                text.contains("商品总价") ||
                text.contains("订单金额") ||
                text.contains("订单总价")
            if (pkg == "com.xunmeng.pinduoduo") {
                return hasExplicitOrderNumberLabel && hasExplicitActualPaid
            }
            if (hasExplicitOrderNumberLabel && (hasExplicitActualPaid || containsLikelyBillAmount(text))) {
                return true
            }
            // 兼容京东/淘宝订单详情页首屏（订单编号在下半屏尚未滑出，但首屏已包含明确的单笔详情锚点与价格）
            val hasJdOrTaobaoSingleDetailAnchors = (text.contains("申请售后") || text.contains("退换/售后")) &&
                (text.contains("查看发票") || text.contains("退换无忧") || text.contains("再次购买") ||
                    text.contains("配送服务") || text.contains("订单详情") || text.contains("感谢您在京东购物"))
            return hasJdOrTaobaoSingleDetailAnchors && (hasExplicitActualPaid || containsLikelyBillAmount(text))
        }

        // 3. 情况 A：单笔「账单详情 / 交易详情 / 订单详情 / 拼单成功」页面（要求带标题且至少命中 2 个单笔详情锚点，且不含支付宝首页顶栏“扫一扫+收付款”）
        val isBillDetailHeader = text.contains("账单详情") ||
            text.contains("交易详情") ||
            text.contains("订单详情") ||
            text.contains("拼单成功")
        val matchedDetailAnchorCount = singleBillDetailAnchors.count { text.contains(it) }
        val isAlipayHomeHeader = text.contains("扫一扫") && text.contains("收付款")
        if (!isAlipayHomeHeader && isBillDetailHeader && matchedDetailAnchorCount >= 2) {
            return true
        }

        // 4. 凡是包含账单列表、支付宝首页消息盒子、支付消息汇总列表特征词的页面，严禁触发
        if (multiBillOrFeedBlacklist.any { text.contains(it) }) {
            return false
        }
        if (!hasExplicitOrderNumberLabel && (
                text.contains("交易详情〉") || text.contains("账单详情>") ||
                    text.contains("查看详情") || text.contains("查看详情>")
                )
        ) {
            return false
        }

        if (matchedDetailAnchorCount >= 3 || (hasExplicitOrderNumberLabel && matchedDetailAnchorCount >= 2)) {
            return true
        }

        // 5. 情况 B：刚付完款的「支付成功 / 付款成功 / 拼单成功」结果页（注意：不含历史订单常见的“交易成功”）
        val liveCompletionKeywords = listOf(
            "支付成功", "付款成功", "拼单成功", "扣款成功", "转账成功", "收款成功", "还款成功"
        )
        val hasCompletionResult = liveCompletionKeywords.any { text.contains(it) }
        if (hasCompletionResult && (text.contains("完成") || text.contains("返回") || text.contains("回首页") || text.contains("查看订单") || matchedDetailAnchorCount >= 1)) {
            return true
        }

        return false
    }

    private fun containsLikelyBillAmount(text: String): Boolean {
        if (BILL_AMOUNT_PRIMARY_REGEX.containsMatchIn(text)) {
            return true
        }
        // 兼容京东/拼多多/抖音整数与小数分拆为相邻 TextView 的各种节点形态（如 "¥ | 29 | .90"、"¥29 | .90"、"¥ | 29. | 90"、"¥29. | 90"、"¥ | 29"）
        if (BILL_AMOUNT_SPLIT_NODE_REGEX.containsMatchIn(text)) {
            return true
        }
        if (BILL_AMOUNT_SIGNED_DECIMAL_REGEX.containsMatchIn(text)) {
            return true
        }
        return false
    }

    private fun shouldAttemptAutoOcr(pkg: String, partialText: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (pkg !in targetFinancialPackages) return false
        val now = System.currentTimeMillis()
        if (now - lastAutoOcrMillis < OCR_COOLDOWN_MS) return false

        val cls = activeClassName.lowercase()
        if (cls.contains("launcherui") || cls.contains("billmainlist") || cls.contains("orderlistactivity")) return false

        // 若当前 Activity 明确是京东 Taro 订单详情页（OrderDetailActivityTaro），节点未取到时直接允许自动截屏 OCR！
        if (cls.contains("orderdetailactivity")) {
            return true
        }

        val cleanedPartial = partialText
            .replace("加入购物车", " ")
            .replace("加购物车", " ")

        // 仅当页面明确是「账单详情 / 交易详情 / 订单详情 / 含有订单编号或京东售后锚点」但节点树未暴露完整金额时才自动截屏 OCR
        if (!cleanedPartial.contains("账单详情") &&
            !cleanedPartial.contains("交易详情") &&
            !cleanedPartial.contains("订单详情") &&
            !cleanedPartial.contains("订单编号") &&
            !(pkg == "com.jingdong.app.mall" && (cleanedPartial.contains("申请售后") || cleanedPartial.contains("退换/售后") || cleanedPartial.contains("实付款") || cleanedPartial.contains("应付款")))
        ) {
            return false
        }
        if (multiBillOrFeedBlacklist.any { cleanedPartial.contains(it) }) return false
        if (cashierOrPendingBlacklist.any { cleanedPartial.contains(it) }) return false

        return true
    }

    private fun captureScreenshotAndRunOcr(
        pkg: String,
        isManualTrigger: Boolean,
        onResultMessage: ((String) -> Unit)?
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val capturedAtMillis = System.currentTimeMillis()
        lastAutoOcrMillis = capturedAtMillis

        RecognitionBanner.dismissImmediate()

        mainHandler.postDelayed({
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
                                    if (!isManualTrigger && !isValidSingleBillOrCompletionPage(pkg, orderedText)) {
                                        BillScreenshotStorage.deleteIfExists(savedScreenshot?.absolutePath)
                                        return@addOnSuccessListener
                                    }
                                    if (!containsLikelyBillAmount(orderedText)) {
                                        BillScreenshotStorage.deleteIfExists(savedScreenshot?.absolutePath)
                                        onResultMessage?.invoke("屏幕中未检测到有效账单金额")
                                        return@addOnSuccessListener
                                    }
                                    dispatchCapturedText(
                                        fullPageText = orderedText,
                                        pkg = pkg,
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
                    val winNodes = mutableListOf<String>()
                    collectNodeTextRecursive(root, winNodes, depth = 0)
                    if (winNodes.isEmpty()) continue
                    val winJoined = winNodes.joinToString(" | ")
                    // 若最顶层窗口本身已经是完整的单笔账单详情页/完成页，直接采用该窗口，绝不混入底下压着的旧窗口（如首页或账单列表）
                    if (isValidSingleBillOrCompletionPage(targetPkg, winJoined)) {
                        return deduplicateConsecutive(winNodes)
                    }
                    for (item in winNodes) {
                        if (outList.size >= MAX_TRAVERSE_NODES) break
                        outList.add(item)
                    }
                    if (outList.size >= MAX_TRAVERSE_NODES) break
                }
            }
        }

        if (outList.isEmpty()) {
            runCatching {
                val activeRoot = rootInActiveWindow
                if (activeRoot != null) {
                    val rootPkg = activeRoot.packageName?.toString().orEmpty()
                    if (rootPkg == targetPkg) {
                        collectNodeTextRecursive(activeRoot, outList, depth = 0)
                    }
                }
            }
        }

        if (eventSource != null && outList.size < 25) {
            runCatching {
                var top: AccessibilityNodeInfo = eventSource
                var guard = 0
                while (guard < 35) {
                    val p = top.parent ?: break
                    top = p
                    guard++
                }
                if (top.packageName?.toString() == targetPkg) {
                    collectNodeTextRecursive(top, outList, depth = 0)
                }
            }
        }

        return deduplicateConsecutive(outList)
    }

    private fun deduplicateConsecutive(list: List<String>): List<String> {
        val deduplicated = mutableListOf<String>()
        for (item in list) {
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

        if (!text.isNullOrEmpty() && text.length <= 600) {
            outList.add(text)
        }
        if (!desc.isNullOrEmpty() && desc.length <= 600 && desc != text) {
            outList.add(desc)
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (outList.size >= MAX_TRAVERSE_NODES) break
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectNodeTextRecursive(child, outList, depth + 1)
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
            if (cleanText.contains("下单时间") || cleanText.contains("拼单时间") || cleanText.contains("支付时间")) append("T")
            if (cleanText.contains("合计") || cleanText.contains("到手") || cleanText.contains("共减")) append("D")
        }
        return if (orders.isNotBlank() || timeMatch.isNotBlank() || amtMatch.isNotBlank()) {
            "$pkg|orders:$orders|time:$timeMatch|amt:$amtMatch|flags:$detailFlags"
        } else {
            "$pkg|${cleanText.take(200)}"
        }
    }

    private fun isKnownNonBillListOrHomeActivity(className: String): Boolean {
        if (className.isBlank()) return false
        val lower = className.lowercase()
        // 注意：绝不能包含 alipaylogin，因为支付宝内许多 H5/Fragment 页面切换时外层 Activity 仍保持 AlipayLogin
        return lower.contains("chattingui") ||
            lower.contains("snstimeline") ||
            lower.contains("orderlistactivity")
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
        mainHandler.removeCallbacksAndMessages(null)
        RecognitionBanner.dismissImmediate()
        if (ocrRecognizerInitialized) {
            runCatching { chineseOcrRecognizer.close() }
            ocrRecognizerInitialized = false
        }
        runCatching { screenshotIoExecutor.shutdown() }
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }
}
