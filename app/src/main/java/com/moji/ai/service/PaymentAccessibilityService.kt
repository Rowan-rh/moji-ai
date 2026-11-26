package com.moji.ai.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
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
import com.moji.ai.ai.WindowRejectReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 收银台单次访问会话防滞留、多任务往返防重与立减动画刷新追踪器（`AC-1` / `AC-2` / `AC-5`）：
 * 1. 同一收银台访问（Visit）内停留、上下滚动推荐区，或未点击「完成/关闭」直接切去桌面、微信聊天主任务、打开默记查看账单并在 10 分钟内多次切回同一未关闭完成页（核心商户+实付+优惠未变且未经历新支付前置信号），复用原 `visitId` 静默拦截（`0` 新增入账、`0` 新增通知）；
 * 2. 首帧录了原价（无立减优惠）、紧接着立减动画完成刷出更低实付金额与优惠金额时，放行触发协调器原位立减校准；
 * 3. 若用户在同一页面展开露出了此前未出现的订单号，放行触发原位单号补全；
 * 4. 一旦经历任意新支付前置信号（待付款收银台词 `确认付款/立即支付`、密码/生物核身窗口或密码节点、发起支付桥接页 `WalletBrandUI/BaseScanUI`、关闭收银台返回宿主 `AppBrandUI*` / `WebViewUI` / 电商宿主），立即重置会话并分配新 `visitId`，确保连续两笔同金额真实消费 100% 独立入账。
 */
internal class CashierVisitSessionTracker {
    data class LiveCashierDispatchDecision(
        val shouldDispatch: Boolean,
        val visitId: Long
    )

    private var visitSequence: Long = 0L

    var activeVisitId: Long = 0L
        private set

    var activePackage: String = ""
        private set

    var completedActivity: String = ""
        private set

    var hasDispatchedInCurrentVisit: Boolean = false
        private set

    var leftCashierWindow: Boolean = false
        private set

    private var lastPaidCents: Long = 0L
    private var lastDiscountCents: Long = 0L
    private var lastHasExplicitOrderNo: Boolean = false
    private var lastMerchantHint: String = ""
    private var lastDispatchedAtMillis: Long = 0L

    @Synchronized
    fun resetSession(): Boolean {
        val hadActive = activeVisitId != 0L || hasDispatchedInCurrentVisit
        activeVisitId = 0L
        activePackage = ""
        completedActivity = ""
        hasDispatchedInCurrentVisit = false
        leftCashierWindow = false
        lastPaidCents = 0L
        lastDiscountCents = 0L
        lastHasExplicitOrderNo = false
        lastMerchantHint = ""
        lastDispatchedAtMillis = 0L
        return hadActive
    }

    /**
     * 当焦点临时切到非白名单外部应用（如系统桌面 Launcher、默记 `com.moji.ai*` 查看账单）或内置电商宿主时调用：
     * - 切回内置电商宿主（拼多多/淘宝/京东/美团等）代表已离开独立收银台回到购物端，立即重置会话；
     * - 切去桌面、默记自身或其他普通应用时仅标记 `leftCashierWindow = true`，保留 10 分钟内切回同一未关闭完成页的防重记忆（`AC-1`）。
     */
    @Synchronized
    fun onExternalAppFocused(packageName: String): Boolean {
        if (activeVisitId == 0L && !hasDispatchedInCurrentVisit) return false
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return false
        if (CaptureWindowGuard.isBuiltinEcommerceHostPackage(pkg)) {
            return resetSession()
        }
        if (pkg != activePackage) {
            leftCashierWindow = true
        }
        return false
    }

    /**
     * 当原收银台窗口被移除关闭且焦点回到同包非专用宿主页面（如关闭 `WxaLiteAppTransparentLiteUI` 回到 `AppBrandUI*`）时调用（`AC-2`）。
     */
    @Synchronized
    fun onCashierWindowClosedToHost(): Boolean = resetSession()

    @Synchronized
    fun onContainerOrRejectTransition(
        packageName: String,
        containerRole: WindowContainerRole,
        rejectReason: WindowRejectReason? = null,
        verifiedActivity: String = ""
    ): Boolean {
        if (activeVisitId == 0L && !hasDispatchedInCurrentVisit) return false
        if (CaptureWindowGuard.isNewPaymentInitiationActivity(packageName, verifiedActivity)) {
            return resetSession()
        }
        if (containerRole == WindowContainerRole.SENSITIVE_AUTH_BLOCKED) {
            return resetSession()
        }
        if (rejectReason == WindowRejectReason.CASHIER_OR_UNPAID_STATE ||
            rejectReason == WindowRejectReason.SENSITIVE_AUTH_PAGE ||
            rejectReason == WindowRejectReason.CLOSED_FAILED_OR_REFUND_PENDING
        ) {
            return resetSession()
        }
        if (packageName != activePackage) {
            if (CaptureWindowGuard.isBuiltinEcommerceHostPackage(packageName) &&
                !CaptureWindowGuard.isEmbeddedAlipayCashierActivity(verifiedActivity)
            ) {
                return resetSession()
            }
            if (containerRole == WindowContainerRole.DEDICATED_PAYMENT_OR_BILL) {
                return resetSession()
            }
            if (containerRole == WindowContainerRole.SHARED_OR_WEB_STRICT &&
                (verifiedActivity.isNotBlank() || rejectReason == null)
            ) {
                return resetSession()
            }
            leftCashierWindow = true
            return false
        }

        // 同包内切去微信聊天主界面/朋友圈/账单列表（NON_TRANSACTION_BLOCKED）：小程序任务栈顶的收银台可能并未关闭，仅标记暂时离开
        if (containerRole == WindowContainerRole.NON_TRANSACTION_BLOCKED ||
            rejectReason == WindowRejectReason.NON_TRANSACTION_CONTAINER ||
            rejectReason == WindowRejectReason.CHAT_FEED_OR_BILL_LIST
        ) {
            leftCashierWindow = true
            return false
        }

        // 同包内回到共享业务宿主（如点击「完成/关闭」从 WxaLiteAppTransparentLiteUI 返回小程序 AppBrandUI*、WebViewUI 或支付宝 AlipayLogin）：立即重置会话
        if (containerRole == WindowContainerRole.SHARED_OR_WEB_STRICT &&
            (verifiedActivity.isNotBlank() || rejectReason == null)
        ) {
            return resetSession()
        }
        return false
    }

    @Synchronized
    fun evaluateLiveCashierFrame(
        packageName: String,
        rawText: String,
        hasExplicitOrderNo: Boolean,
        forceManual: Boolean = false,
        verifiedActivity: String = "",
        nowMillis: Long = System.currentTimeMillis()
    ): LiveCashierDispatchDecision {
        if (forceManual) {
            val manualVisitId = ++visitSequence
            return LiveCashierDispatchDecision(shouldDispatch = true, visitId = manualVisitId)
        }
        if (hasDispatchedInCurrentVisit &&
            lastDispatchedAtMillis > 0L &&
            nowMillis - lastDispatchedAtMillis > REENTRY_TTL_MS
        ) {
            resetSession()
        }

        val (paidCents, discountCents) = extractQuickCashierPaidAndDiscountCents(rawText)
        val merchantHint = extractQuickCashierMerchantHint(rawText)

        if (activeVisitId != 0L && activePackage == packageName && hasDispatchedInCurrentVisit) {
            val activityChanged = completedActivity.isNotBlank() &&
                verifiedActivity.isNotBlank() &&
                !completedActivity.equals(verifiedActivity, ignoreCase = true)
            if (activityChanged) {
                resetSession()
            } else if (leftCashierWindow) {
                val samePaidAndDiscount = paidCents > 0L &&
                    paidCents == lastPaidCents &&
                    discountCents == lastDiscountCents
                val sameMerchant = lastMerchantHint.isBlank() ||
                    merchantHint.isBlank() ||
                    lastMerchantHint.equals(merchantHint, ignoreCase = true)
                val revealedNewOrderNo = !lastHasExplicitOrderNo && hasExplicitOrderNo
                if (samePaidAndDiscount && sameMerchant && !revealedNewOrderNo) {
                    leftCashierWindow = false
                    return LiveCashierDispatchDecision(shouldDispatch = false, visitId = activeVisitId)
                } else if (samePaidAndDiscount && sameMerchant && revealedNewOrderNo) {
                    leftCashierWindow = false
                    lastHasExplicitOrderNo = true
                    lastDispatchedAtMillis = nowMillis
                    return LiveCashierDispatchDecision(shouldDispatch = true, visitId = activeVisitId)
                } else {
                    resetSession()
                }
            }
        }

        if (activeVisitId == 0L || activePackage != packageName) {
            activeVisitId = ++visitSequence
            activePackage = packageName
            completedActivity = verifiedActivity
            hasDispatchedInCurrentVisit = false
            leftCashierWindow = false
            lastPaidCents = 0L
            lastDiscountCents = 0L
            lastHasExplicitOrderNo = false
            lastMerchantHint = ""
            lastDispatchedAtMillis = 0L
        }

        if (!hasDispatchedInCurrentVisit) {
            hasDispatchedInCurrentVisit = true
            leftCashierWindow = false
            if (verifiedActivity.isNotBlank()) completedActivity = verifiedActivity
            lastPaidCents = paidCents
            lastDiscountCents = discountCents
            lastHasExplicitOrderNo = hasExplicitOrderNo
            lastMerchantHint = merchantHint
            lastDispatchedAtMillis = nowMillis
            return LiveCashierDispatchDecision(shouldDispatch = true, visitId = activeVisitId)
        }

        // 立减动画完成帧：首帧尚未刷出立减（discount=0），当前帧出现明确立减且实付低于首帧原价
        val isPostDiscountAnimationFrame = lastDiscountCents <= 0L &&
            discountCents > 0L &&
            paidCents > 0L &&
            lastPaidCents > paidCents
        val revealedNewOrderNo = !lastHasExplicitOrderNo && hasExplicitOrderNo

        if (isPostDiscountAnimationFrame || revealedNewOrderNo) {
            if (paidCents > 0L) lastPaidCents = paidCents
            if (discountCents > 0L) lastDiscountCents = discountCents
            if (hasExplicitOrderNo) lastHasExplicitOrderNo = true
            if (merchantHint.isNotBlank()) lastMerchantHint = merchantHint
            lastDispatchedAtMillis = nowMillis
            return LiveCashierDispatchDecision(shouldDispatch = true, visitId = activeVisitId)
        }

        return LiveCashierDispatchDecision(shouldDispatch = false, visitId = activeVisitId)
    }

    companion object {
        const val REENTRY_TTL_MS = 10 * 60_000L

        private val DISCOUNT_QUICK_REGEX = Regex(
            """(?:百次立减|随机立减|笔笔立减|支付立减|银行卡立减|碰一下立减|官方立减|支付优惠|立减优惠|已优惠|共减|优惠|立减|抵扣|减免)\s*(?:[:：,，\s]|\|)*\s*(?:[-−]\s*)?(?:¥|￥)?\s*(?:[-−]\s*)?([0-9]{1,5}(?:\.[0-9]{1,2})?)\s*元?"""
        )
        private val EXPLICIT_PAID_QUICK_REGEX = Regex(
            """(?:实付款|实付金额|合计实付|实扣金额|实扣|实际支付|实付)\s*(?:[:：,，\s]|\|)*\s*(?:¥|￥)?\s*([0-9]{1,7}(?:\.[0-9]{1,2})?)\s*元?"""
        )
        private val CURRENCY_QUICK_REGEX = Regex(
            """(?:¥|￥)\s*([0-9]{1,7}(?:\.[0-9]{1,2})?)|([0-9]{1,7}(?:\.[0-9]{1,2})?)\s*元"""
        )
        private val DISCOUNT_TOKEN_HINTS = listOf("优惠", "立减", "抵扣", "减免", "补贴", "红包", "代金券")
        private val QUICK_MERCHANT_IGNORE_EXACT = setOf(
            "支付成功", "付款成功", "交易成功", "扣款成功", "转账成功", "收款成功", "代付成功",
            "完成", "关闭", "返回", "更多", "返回商家", "返回商户", "返回应用", "完成付款", "付款完成",
            "点击给商家对账", "给商家对账", "出示给商家", "查看订单", "进入小程序",
            "支付方式", "付款方式", "收款方式", "扣款方式", "商品", "商品说明", "收款方", "商户全称",
            "微信支付", "微信", "零钱", "零钱通", "支付宝", "余额宝", "花呗", "蚂蚁花呗", "白条"
        )

        internal fun extractQuickCashierMerchantHint(rawText: String): String {
            val segments = rawText
                .replace(Regex("""\[AI_[A-Z0-9_]+\s*\|[^\]]+\]:\s*"""), "")
                .split('|', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            var skipNextPayValue = false
            for (seg in segments) {
                if (skipNextPayValue) {
                    skipNextPayValue = false
                    continue
                }
                if (seg in setOf("支付方式", "付款方式", "收款方式", "扣款方式")) {
                    skipNextPayValue = true
                    continue
                }
                if (seg.length !in 2..28 || seg in QUICK_MERCHANT_IGNORE_EXACT) continue
                if (seg.startsWith("¥") || seg.startsWith("￥") || seg.startsWith("-") || seg.startsWith("+") || seg.startsWith("−")) continue
                if (CURRENCY_QUICK_REGEX.containsMatchIn(seg) || seg.all { it.isDigit() || it == '.' || it == '-' || it == ':' }) continue
                if (DISCOUNT_TOKEN_HINTS.any { seg.contains(it) }) continue
                if (seg.contains("银行") || seg.contains("信用卡") || seg.contains("储蓄卡") || seg.contains("借记卡") ||
                    seg.contains("尾号") || seg.contains("给商家对账") || seg.contains("出示给商家") ||
                    seg.contains("返回商家") || seg.contains("返回商户") || seg.startsWith("202")
                ) continue
                return seg
            }
            return ""
        }

        internal fun extractQuickCashierPaidAndDiscountCents(rawText: String): Pair<Long, Long> {
            val normalized = rawText
                .replace(Regex("""(¥|￥)\s*\|?\s*(\d{1,7})\s*\|\s*(\.\d{1,2})"""), "$1$2$3")
                .replace(Regex("""(¥|￥)\s*\|?\s*(\d{1,7})\.\s*\|\s*(\d{1,2})"""), "$1$2.$3")
                .replace(Regex("""(¥|￥)\s*\|\s*(\d{1,7}(?:\.\d{1,2})?)"""), "$1$2")

            val discountCents = DISCOUNT_QUICK_REGEX.findAll(normalized)
                .mapNotNull { m -> m.groupValues[1].toDoubleOrNull()?.let { (it * 100.0).roundToLong() } }
                .filter { it > 0L }
                .distinct()
                .sum()

            val explicitPaid = EXPLICIT_PAID_QUICK_REGEX.find(normalized)?.groupValues?.getOrNull(1)
                ?.toDoubleOrNull()?.let { (it * 100.0).roundToLong() } ?: 0L
            if (explicitPaid > 0L) {
                return explicitPaid to discountCents
            }

            val segments = normalized.split('|', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            val candidateCents = ArrayList<Long>(4)
            for (i in segments.indices) {
                val seg = segments[i]
                val prev = segments.getOrNull(i - 1).orEmpty()
                if (DISCOUNT_TOKEN_HINTS.any { seg.contains(it) } ||
                    (prev.isNotEmpty() && !CURRENCY_QUICK_REGEX.containsMatchIn(prev) && DISCOUNT_TOKEN_HINTS.any { prev.contains(it) })
                ) {
                    continue
                }
                for (m in CURRENCY_QUICK_REGEX.findAll(seg)) {
                    val v = m.groupValues[1].ifEmpty { m.groupValues[2] }.toDoubleOrNull() ?: continue
                    val c = (v * 100.0).roundToLong()
                    if (c > 0L) candidateCents.add(c)
                }
            }
            if (discountCents > 0L && candidateCents.size >= 2) {
                for (low in candidateCents) {
                    if (candidateCents.any { high -> low < high && low + discountCents == high }) {
                        return low to discountCents
                    }
                }
            }
            return (candidateCents.firstOrNull() ?: 0L) to discountCents
        }
    }
}

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
        private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
        private const val CONTENT_THROTTLE_MS = 120L
        private const val OCR_COOLDOWN_MS = 4_500L
        private const val MAX_TRAVERSE_DEPTH = 50
        private const val MAX_TRAVERSE_NODES = 550
        private const val MAX_ACTIVITY_CACHE_SIZE = 512

        /**
         * `AC-2` / `OP-2 = 是`：判断宿主窗口是否允许进入无障碍自动识屏管道。
         * - 若宿主应用自身已开启无障碍自动识屏（如微信/支付宝/云闪付/银行），直接放行；
         * - 若宿主属于默认关闭全包无障碍的内置电商/外卖应用（如拼多多/淘宝/京东/天猫/美团/饿了么），
         *   仅当「支付宝」无障碍处于开启状态，且经 `isVerifiedDeclaredActivity` 核实的真实 Activity
         *   命中内嵌支付宝专用收银台（如 `com.alipay.sdk.app.*` / `H5PayActivity` / `H5PayResultActivity`）时窄口放行；
         * - 拼多多 `NewPageActivity`、商品详情、订单列表、拼单活动页维持 100% 拦截。
         */
        internal fun shouldAllowHostAccessibility(
            packageName: String,
            verifiedActivity: String,
            isDirectHostAllowed: Boolean,
            isAlipayAllowed: Boolean
        ): Boolean {
            if (isDirectHostAllowed) return true
            if (!isAlipayAllowed) return false
            if (!CaptureWindowGuard.isBuiltinEcommerceHostPackage(packageName)) return false
            return CaptureWindowGuard.isEmbeddedAlipayCashierActivity(verifiedActivity)
        }

        private val PAGE_IDENTITY_ORDER_REGEX = Regex(
            """(?:交易单号|转账单号|订单编号|订单号|商户单号|商家订单号|支付宝交易号|流水号)\s*(?:[:：,，\s]|\|)*\s*([0-9A-Za-z_-]{10,44})"""
        )
        private val PAGE_IDENTITY_TIME_REGEX = Regex(
            """(?:支付时间|付款时间|下单时间|拼单时间|创建时间|转账时间|收款时间|退款时间)\s*(?:[:：,，\s]|\|)*\s*(20\d{2}[-年/]\d{1,2}[-月/]\d{1,2}日?\s+\d{1,2}[:时]\d{2}(?:[:分]\d{2}秒?)?)"""
        )
        private val PAGE_IDENTITY_STRONG_AMOUNT_REGEX = Regex(
            """(?<!订单|不支持)(?:实付款|实付金额|合计实付|实际支付|实扣金额|实扣|实付)\s*(?:[:：,，\s]|\|)*\s*(?:¥|￥)?\s*\d{1,7}(?:\.\d{1,2})?\s*[元)）]?"""
        )
        private val PAGE_IDENTITY_FALLBACK_AMOUNT_REGEX = Regex(
            """(?:应付总额|应付金额|应付款|需付款|合计|到手价|到手|支出|收入|付款|收款)\s*(?:[:：,，\s]|\|)*\s*(?:¥|￥)?\s*\d{1,7}(?:\.\d{1,2})?\s*[元)）]?|[-+−]?\d{1,7}\.\d{1,2}"""
        )

        /**
         * 生成包含「详情丰富度」的稳定页面指纹（`AC-4`）：
         * - 仅提取显式标签后的真实订单号、下单/支付时间与优先强实付金额（忽略动态取餐码/排队进度/倒计时变化）；
         * - 停留在同一小程序订单/取餐页 15s~10min 动态刷新时指纹恒定，0 重复触发；
         * - 若用户点开「更多」新露出了订单号、支付方式或商品说明，指纹立即变化并触发原位增量补全。
         */
        internal fun extractStablePageIdentity(pkg: String, fullPageText: String): String {
            val cleanText = com.moji.ai.ai.AiAccountingEngine.stripEcommerceRecommendationFooter(fullPageText)
            val orders = PAGE_IDENTITY_ORDER_REGEX.findAll(cleanText)
                .map { it.groupValues[1].trim() }
                .filter { it.count { ch -> ch.isDigit() } >= 8 }
                .distinct()
                .joinToString(",")
            val timeMatch = PAGE_IDENTITY_TIME_REGEX.find(cleanText)?.groupValues?.getOrNull(1).orEmpty()
            val amtMatch = PAGE_IDENTITY_STRONG_AMOUNT_REGEX.find(cleanText)?.value
                ?: PAGE_IDENTITY_FALLBACK_AMOUNT_REGEX.find(cleanText)?.value.orEmpty()
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

    private val cashierVisitTracker = CashierVisitSessionTracker()
    private val stablePageDedupTracker = StablePageDedupTracker()
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
        cashierVisitTracker.resetSession()
        stablePageDedupTracker.clear()
    }

    private fun isAccessibilityAllowedForWindow(pkg: String, verifiedActivity: String): Boolean {
        if (pkg.isBlank()) return false
        val directAllowed = CaptureAppWhitelistManager.isAccessibilityAllowed(pkg, this)
        if (directAllowed) return true
        val alipayAllowed = CaptureAppWhitelistManager.isAccessibilityAllowed(ALIPAY_PACKAGE, this)
        return shouldAllowHostAccessibility(
            packageName = pkg,
            verifiedActivity = verifiedActivity,
            isDirectHostAllowed = false,
            isAlipayAllowed = alipayAllowed
        )
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
            val removedAct = windowActivities.remove(event.windowId)
            val removedPkg = windowPackages.remove(event.windowId)
            if (!removedPkg.isNullOrBlank() &&
                !removedAct.isNullOrBlank() &&
                CaptureWindowGuard.classifyActivityContainer(removedPkg, removedAct) == WindowContainerRole.DEDICATED_PAYMENT_OR_BILL
            ) {
                val stillFocusedWin = resolveFocusedApplicationWindow()
                val stillFocusedPkg = stillFocusedWin?.packageName?.takeIf { it !in systemOverlayPackages }
                if (stillFocusedPkg == removedPkg) {
                    val underlyingAct = stillFocusedWin.id.takeIf { it >= 0 }?.let { winId ->
                        if (windowPackages[winId] == stillFocusedPkg) windowActivities[winId] else null
                    }.orEmpty()
                    if (underlyingAct.isNotBlank() &&
                        CaptureWindowGuard.classifyActivityContainer(stillFocusedPkg, underlyingAct) == WindowContainerRole.SHARED_OR_WEB_STRICT
                    ) {
                        cashierVisitTracker.onCashierWindowClosedToHost()
                    }
                }
            }
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
            val isDirectAllowed = CaptureAppWhitelistManager.isAccessibilityAllowed(pkg, this)
            val candidateVerifiedActivity = when {
                isRealActivity -> normalizedCls
                CaptureWindowGuard.isLikelyViewOrDialogClass(normalizedCls) -> resolveActivityForEventWindow(pkg, event.windowId)
                isDirectAllowed -> lastVerifiedActivityByPkg[pkg].orEmpty()
                else -> ""
            }

            if (!isAccessibilityAllowedForWindow(pkg, candidateVerifiedActivity)) {
                if (isRealActivity || !CaptureWindowGuard.isLikelyViewOrDialogClass(normalizedCls)) {
                    activePackage = pkg
                    activeVerifiedActivity = if (isRealActivity) normalizedCls else ""
                    lastVerifiedActivityByPkg.remove(pkg)
                    clearWindowActivityMappingsForPackage(pkg)
                    isCurrentlyOnBillDetailPage = false
                    lastDispatchedSignature = ""
                    lastDispatchedMillis = 0L
                    cashierVisitTracker.onExternalAppFocused(pkg)
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
                activeVerifiedActivity = candidateVerifiedActivity
            }

            val currentRole = CaptureWindowGuard.classifyActivityContainer(pkg, activeVerifiedActivity)
            if (cashierVisitTracker.onContainerOrRejectTransition(
                    packageName = pkg,
                    containerRole = currentRole,
                    verifiedActivity = activeVerifiedActivity
                )
            ) {
                lastDispatchedSignature = ""
                lastDispatchedMillis = 0L
            }
            if (currentRole == WindowContainerRole.SENSITIVE_AUTH_BLOCKED ||
                currentRole == WindowContainerRole.NON_TRANSACTION_BLOCKED
            ) {
                clearScheduledProbes()
                isCurrentlyOnBillDetailPage = false
                lastDispatchedSignature = ""
                lastDispatchedMillis = 0L
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
            val focusedAct = if (focused != null) {
                focusedWin.id.takeIf { it >= 0 }?.let { winId ->
                    if (windowPackages[winId] == focused) windowActivities[winId] else null
                } ?: lastVerifiedActivityByPkg[focused].orEmpty()
            } else {
                ""
            }
            when {
                focused != null && isAccessibilityAllowedForWindow(focused, focusedAct) -> {
                    activePackage = focused
                    if (focusedAct.isNotBlank()) {
                        activeVerifiedActivity = focusedAct
                        lastVerifiedActivityByPkg[focused] = focusedAct
                    }
                    focused
                }
                focused != null -> {
                    activePackage = focused
                    activeVerifiedActivity = ""
                    isCurrentlyOnBillDetailPage = false
                    lastDispatchedSignature = ""
                    lastDispatchedMillis = 0L
                    cashierVisitTracker.onExternalAppFocused(focused)
                    clearScheduledProbes()
                    return
                }
                rawPkg.isNotBlank() && isAccessibilityAllowedForWindow(rawPkg, resolveActivityForEventWindow(rawPkg, event.windowId)) -> {
                    activePackage = rawPkg
                    rawPkg
                }
                rawPkg.isBlank() && isAccessibilityAllowedForWindow(activePackage, activeVerifiedActivity) -> {
                    activePackage
                }
                else -> return
            }
        } else {
            when {
                rawPkg.isNotBlank() -> {
                    val resolvedRawAct = resolveActivityForEventWindow(rawPkg, event.windowId)
                    if (!isAccessibilityAllowedForWindow(rawPkg, resolvedRawAct)) {
                        return
                    }
                    if (activePackage != rawPkg) {
                        activePackage = rawPkg
                        activeVerifiedActivity = resolvedRawAct
                    }
                    rawPkg
                }
                isAccessibilityAllowedForWindow(activePackage, activeVerifiedActivity) -> activePackage
                else -> {
                    val focused = resolveFocusedApplicationPackage()?.takeIf { it !in systemOverlayPackages }
                    val focusedAct = if (focused != null) lastVerifiedActivityByPkg[focused].orEmpty() else ""
                    if (focused != null && isAccessibilityAllowedForWindow(focused, focusedAct)) {
                        activePackage = focused
                        activeVerifiedActivity = focusedAct
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

            var manualBlockedReason: String? = null
            val capturedByNodes = inspectCurrentScreen(
                pkg = pkg,
                eventSource = null,
                allowAutoOcrFallback = false,
                forceManual = true,
                onManualBlocked = { reason -> manualBlockedReason = reason }
            )
            val blockedReason = manualBlockedReason
            if (capturedByNodes) {
                onResultMessage?.invoke("已通过页面节点识别账单并保存截图")
            } else if (blockedReason != null) {
                onResultMessage?.invoke(blockedReason)
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

    private fun isDeviceCurrentlyLocked(): Boolean {
        val km = getSystemService(KeyguardManager::class.java)
        return km?.isDeviceLocked == true || km?.isKeyguardLocked == true
    }

    /**
     * 供 CapturePipelineCoordinator 在无障碍节点识账成功入库后静默抓取当前账单页面截图并持久化到本地。
     * 截图前复核设备未锁屏且当前焦点窗口仍为原采集宿主，并在胶囊展示前先完成帧捕获以避免截入本应用悬浮窗；
     * 回调与 WebP 压缩全部运行在后台单线程池 screenshotIoExecutor，绝不阻塞主线程动画！
     */
    fun captureBillEvidenceScreenshot(
        expectedPackage: String = "",
        abortSignal: AtomicBoolean? = null,
        onFrameCaptured: (() -> Unit)? = null,
        onSaved: (SavedScreenshotInfo?) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onFrameCaptured?.invoke()
            onSaved(null)
            return
        }
        mainHandler.post {
            if (abortSignal?.get() == true || isDeviceCurrentlyLocked()) {
                onFrameCaptured?.invoke()
                onSaved(null)
                return@post
            }
            if (expectedPackage.isNotBlank()) {
                val currentAct = resolveActivityForEventWindow(expectedPackage, -1)
                if (!isAccessibilityAllowedForWindow(expectedPackage, currentAct)) {
                    onFrameCaptured?.invoke()
                    onSaved(null)
                    return@post
                }
                val focusedNow = resolveFocusedApplicationPackage()?.takeIf { it !in systemOverlayPackages }
                if (focusedNow == null || focusedNow != expectedPackage) {
                    onFrameCaptured?.invoke()
                    onSaved(null)
                    return@post
                }
                val currentRole = CaptureWindowGuard.classifyActivityContainer(expectedPackage, currentAct)
                if (currentRole == WindowContainerRole.SENSITIVE_AUTH_BLOCKED ||
                    currentRole == WindowContainerRole.NON_TRANSACTION_BLOCKED
                ) {
                    onFrameCaptured?.invoke()
                    onSaved(null)
                    return@post
                }
            }
            if (RecognitionBanner.isShowingOrAnimating()) {
                RecognitionBanner.dismissImmediate()
            }
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotIoExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            onFrameCaptured?.invoke()
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
                            onFrameCaptured?.invoke()
                            onSaved(null)
                        }
                    }
                )
            } catch (_: Throwable) {
                onFrameCaptured?.invoke()
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
                val probeAct = resolveActivityForEventWindow(pkg, -1)
                if (!isAccessibilityAllowedForWindow(pkg, probeAct)) return@Runnable
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
        forceManual: Boolean = false,
        onManualBlocked: ((String) -> Unit)? = null
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
            if (cashierVisitTracker.resetSession()) {
                lastDispatchedSignature = ""
                lastDispatchedMillis = 0L
            }
            if (forceManual) {
                onManualBlocked?.invoke("当前为密码或身份核验敏感页面，已拦截识屏")
            }
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
            if (cashierVisitTracker.onContainerOrRejectTransition(
                    packageName = pkg,
                    containerRole = eval.containerRole,
                    rejectReason = eval.reason,
                    verifiedActivity = snapshot.verifiedActivity
                )
            ) {
                lastDispatchedSignature = ""
                lastDispatchedMillis = 0L
            }
            if (forceManual && eval.reason in setOf(
                    WindowRejectReason.SENSITIVE_AUTH_PAGE,
                    WindowRejectReason.NON_TRANSACTION_CONTAINER,
                    WindowRejectReason.CASHIER_OR_UNPAID_STATE,
                    WindowRejectReason.CLOSED_FAILED_OR_REFUND_PENDING,
                    WindowRejectReason.CHAT_FEED_OR_BILL_LIST,
                    WindowRejectReason.AMBIGUOUS_PROMO_AMOUNTS
                )
            ) {
                onManualBlocked?.invoke("已跳过：${eval.reason.summary}")
                return false
            }
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
        val resolvedSource = if (eval.isLiveCashierFallback) "AI_LIVE_CASHIER" else "AI_ACCESSIBILITY"
        return dispatchCapturedText(
            fullPageText = cleanFullText,
            pkg = pkg,
            verifiedActivity = snapshot.verifiedActivity,
            structureFingerprint = snapshot.structureFingerprint,
            captureSource = resolvedSource,
            hasExplicitOrderNo = eval.hasExplicitOrderNo,
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
        hasExplicitOrderNo: Boolean = false,
        forceManual: Boolean = false
    ): Boolean {
        val now = System.currentTimeMillis()
        val pageIdentity = extractStablePageIdentity(pkg, fullPageText)
        var cashierVisitId = 0L
        if (captureSource == "AI_LIVE_CASHIER") {
            val visitDecision = cashierVisitTracker.evaluateLiveCashierFrame(
                packageName = pkg,
                rawText = fullPageText,
                hasExplicitOrderNo = hasExplicitOrderNo,
                forceManual = forceManual,
                verifiedActivity = verifiedActivity,
                nowMillis = now
            )
            if (!visitDecision.shouldDispatch) {
                return true
            }
            cashierVisitId = visitDecision.visitId
        } else {
            if (stablePageDedupTracker.shouldSkipDispatch(pageIdentity, forceManual = forceManual, nowMillis = now)) {
                return true
            }
        }

        lastDispatchedSignature = if (cashierVisitId > 0L) "$pageIdentity|visit:$cashierVisitId" else pageIdentity
        lastDispatchedMillis = now
        Log.d(
            "MoJiCapture",
            "Dispatch bill page: pkg=$pkg, src=$captureSource, act=${verifiedActivity.substringAfterLast('.')}, fp=$structureFingerprint, visit=$cashierVisitId"
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
            capturedAtMillis = now,
            cashierVisitId = cashierVisitId,
            verifiedActivity = verifiedActivity
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
        if (!isAccessibilityAllowedForWindow(snapshot.packageName, snapshot.verifiedActivity)) return false
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
            if (isDeviceCurrentlyLocked()) {
                return@postDelayed
            }
            if (!isManualTrigger) {
                val currentAct = expectedActivity.ifBlank {
                    lastVerifiedActivityByPkg[pkg].orEmpty().ifBlank { activeVerifiedActivity }
                }
                if (!isAccessibilityAllowedForWindow(pkg, currentAct)) {
                    return@postDelayed
                }
                val focusedNow = resolveFocusedApplicationPackage()?.takeIf { it !in systemOverlayPackages }
                if (focusedNow == null || focusedNow != pkg) {
                    return@postDelayed
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
                                            val msg = "屏幕未满足有效账单要素（${ocrEval.reason.summary}）"
                                            if (onResultMessage == null) {
                                                Toast.makeText(
                                                    this@PaymentAccessibilityService,
                                                    "默记：$msg",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            onResultMessage?.invoke(msg)
                                        }
                                        return@addOnSuccessListener
                                    }

                                    val resolvedOcrSource = if (ocrEval.isLiveCashierFallback) "AI_LIVE_CASHIER" else "AI_OCR"
                                    dispatchCapturedText(
                                        fullPageText = orderedText,
                                        pkg = pkg,
                                        verifiedActivity = currentAct,
                                        structureFingerprint = expectedFingerprint,
                                        captureSource = resolvedOcrSource,
                                        screenshotPath = savedScreenshot?.absolutePath.orEmpty(),
                                        screenshotSha256 = savedScreenshot?.sha256.orEmpty(),
                                        hasExplicitOrderNo = ocrEval.hasExplicitOrderNo,
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

    override fun onInterrupt() {
        clearScheduledProbes()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        clearScheduledProbes()
        cashierVisitTracker.resetSession()
        stablePageDedupTracker.clear()
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
        cashierVisitTracker.resetSession()
        stablePageDedupTracker.clear()
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

/**
 * 小程序/订单详情页长时停留与往返防重追踪器（`AC-4`）：
 * - 对携带明确订单号或下单/支付时间的结构化页面身份保留 10 分钟幂等锁，杜绝在小程序取餐页停留 >15s 且取餐码/制作状态动态刷新时重复派发或重复弹通知；
 * - 对无单号无时间的普通文本回退保留 15 秒防抖；
 * - 若点开「更多」露出了新字段，或下了第二笔单（单号/时间/实付变化），`pageIdentity` 立即改变并正常放行。
 */
internal class StablePageDedupTracker {
    companion object {
        const val STRUCTURED_PAGE_TTL_MS = 10 * 60_000L
        const val FALLBACK_PAGE_TTL_MS = 15_000L
        private const val MAX_ENTRIES = 64
    }

    private val recentIdentities = LinkedHashMap<String, Long>(32, 0.75f, true)

    @Synchronized
    fun clear() {
        recentIdentities.clear()
    }

    @Synchronized
    fun shouldSkipDispatch(
        pageIdentity: String,
        forceManual: Boolean = false,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val iter = recentIdentities.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val ttl = ttlForIdentity(entry.key)
            if (nowMillis - entry.value > ttl) {
                iter.remove()
            }
        }

        if (!forceManual) {
            val lastSeen = recentIdentities[pageIdentity]
            if (lastSeen != null && nowMillis - lastSeen <= ttlForIdentity(pageIdentity)) {
                return true
            }
        }

        if (recentIdentities.size >= MAX_ENTRIES) {
            val eldestKey = recentIdentities.keys.firstOrNull()
            if (eldestKey != null) {
                recentIdentities.remove(eldestKey)
            }
        }
        recentIdentities[pageIdentity] = nowMillis
        return false
    }

    private fun ttlForIdentity(identity: String): Long {
        val hasStructuredOrder = identity.contains("|orders:") &&
            !identity.substringAfter("|orders:").startsWith("|")
        val hasStructuredTime = identity.contains("|time:") &&
            !identity.substringAfter("|time:").startsWith("|")
        return if (hasStructuredOrder || hasStructuredTime) {
            STRUCTURED_PAGE_TTL_MS
        } else {
            FALLBACK_PAGE_TTL_MS
        }
    }
}
