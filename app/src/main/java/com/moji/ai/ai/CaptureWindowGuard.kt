package com.moji.ai.ai

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

/**
 * 窗口容器角色分级（融合花有谱 `PaymentPageDetector`、蓝鲸记账 `IMAGE_VIEWER_CLASSES` 与钱迹自动记账 `Analyze` 的容器路由策略）
 */
enum class WindowContainerRole(val label: String) {
    /** 密码、生物核身、安全键盘等隐私敏感窗口：硬拦截（禁止读节点、禁止截图 OCR） */
    SENSITIVE_AUTH_BLOCKED("敏感核身拦截"),

    /** 明确的非交易浏览容器（微信主界面/聊天、朋友圈、相册看图、商品详情、购物车、搜索、订单列表等）：硬拦截 */
    NON_TRANSACTION_BLOCKED("非交易容器拦截"),

    /** 明确的支付完成页或单笔账单/订单详情专用 Activity：高置信度放行进入内容校验 */
    DEDICATED_PAYMENT_OR_BILL("专用支付/详情容器"),

    /** 通用共享宿主容器（如 WebViewUI、AlipayLogin、小程序 AppBrandUI 或未知类名）：须通过严格结构闭环校验 */
    SHARED_OR_WEB_STRICT("共享/WebView严审容器")
}

/**
 * 窗口级防误触判定结论枚举（便于可观测性诊断与单测精确断言）
 */
enum class WindowRejectReason(val summary: String) {
    ACCEPTED("窗口校验通过"),
    SENSITIVE_AUTH_PAGE("密码或身份核验敏感页面"),
    NON_TRANSACTION_CONTAINER("聊天/朋友圈/相册/商品浏览等非账单窗口"),
    USER_BLOCKED_SIGNATURE("命中用户手动屏蔽的页面结构指纹"),
    VISIBLE_EDITABLE_INPUT("包含可见聊天/搜索/编辑输入框"),
    CASHIER_OR_UNPAID_STATE("收银台待付款或结算提交页"),
    CLOSED_FAILED_OR_REFUND_PENDING("交易已关闭/取消/失败或退款处理中"),
    CHAT_FEED_OR_BILL_LIST("会话流、消息盒子或汇总账单列表"),
    BALANCE_OR_ASSET_PAGE("零钱/余额宝/银行总资产概览页"),
    PRODUCT_BROWSING_PAGE("电商商品详情或导购浏览页"),
    MISSING_BILL_AMOUNT("未检测到有效账单金额"),
    MISSING_OCCURRED_TIME("缺少明确交易发生时间"),
    UNLABELED_AMBIGUOUS_TIME("仅有无字段标签的模糊时间（疑似聊天或评价时间）"),
    STALE_UNNUMBERED_PAGE("无单号页面的时间偏离当前过久（疑似旧截图或推文）"),
    AMBIGUOUS_PROMO_AMOUNTS("共享容器内存在多组促销金额且无唯一实付金额"),
    INSUFFICIENT_BILL_STRUCTURE("不满足单笔账单详情或实时支付完成结构")
}

data class WindowEvaluationResult(
    val accepted: Boolean,
    val reason: WindowRejectReason,
    val containerRole: WindowContainerRole,
    val sanitizedText: String = "",
    val occurredAtMillis: Long? = null,
    val hasExplicitOrderNo: Boolean = false
)

/**
 * 多窗口候选元数据（对齐花有谱 `CaptureWindow`：仅选非画中画最顶层焦点应用窗口，杜绝多窗口拼接串台）
 */
data class WindowCandidate(
    val id: Int,
    val type: Int,
    val isActive: Boolean,
    val isFocused: Boolean,
    val layer: Int,
    val isInPictureInPictureMode: Boolean = false,
    val packageName: String? = null
)

/**
 * 视图树轻量级骨架节点（对齐蓝鲸记账 `StructureFingerprint` 并升级剔除动态镜像文案）
 */
data class SkeletonNode(
    val className: String = "",
    val viewIdResourceName: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val children: List<SkeletonNode> = emptyList()
)

/**
 * 页面结构签名三元组 `(packageName, activityName, structureFingerprint)`：
 * 用于「最近识屏页面可观测记录」与「误触页面结构一键屏蔽」。
 */
@Serializable
data class WindowPageSignature(
    val packageName: String = "",
    val activityName: String = "",
    val structureFingerprint: String = "",
    val appName: String = "",
    val sampleSummary: String = "",
    val createdAtMillis: Long = 0L
) {
    val signatureKey: String
        get() = CaptureWindowGuard.buildSignatureKey(packageName, activityName, structureFingerprint)

    val shortActivityName: String
        get() = activityName.substringAfterLast('.').ifBlank { "共享容器" }
}

data class WindowInspectionInput(
    val packageName: String,
    val verifiedActivity: String,
    val structureFingerprint: String = "",
    val rawPageText: String,
    val hasVisiblePasswordNode: Boolean = false,
    val hasVisibleEditableNode: Boolean = false,
    val forceManual: Boolean = false,
    val isSignatureBlocked: Boolean = false,
    val nowMillis: Long = System.currentTimeMillis()
)

/**
 * 默记（MoJi）窗口级智能识账守卫：
 * 深度吸收三大开源自动记账项目核心机制：
 * 1. 花有谱 (`huayoupu-ledger`)：单窗口所有权选择、`getActivityInfo` 真实 Activity 门禁、`isEditable/isPassword` 输入态一票否决、`TransactionRejection` 关闭/失败态拦截、WebView 完成控件强约束；
 * 2. 蓝鲸记账 (`blue-whale-keeper`)：前 5 层 DOM 视图骨架哈希 (`StructureFingerprint`)、相册看图容器硬隔离、零钱/余额页排除、无单号完成页屏幕时效锁；
 * 3. 钱迹自动记账 (`Qianji_auto`)：聊天卡片与模板通知气泡排除、专用支付/详情 Activity 分流。
 */
object CaptureWindowGuard {

    const val SKELETON_MAX_DEPTH = 5
    const val SKELETON_MAX_CHILDREN = 12
    const val LIVE_COMPLETION_MAX_AGE_MS = 15 * 60_000L

    private const val APP_WINDOW_TYPE = AccessibilityWindowInfo.TYPE_APPLICATION

    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val DIGITS_REGEX = Regex("""\d+""")

    private val BILL_AMOUNT_PRIMARY_REGEX = Regex(
        """(?<!本月支出)(?:¥|￥)\s*\d+(?:\.\d{1,2})?|(?:支出|收入|付款|收款|实付|实付款|应付款|应付金额|商品总额|合计)?\s*\d+(?:\.\d{1,2})?\s*元"""
    )
    private val BILL_AMOUNT_SPLIT_NODE_REGEX = Regex(
        """(?:¥|￥)\s*\|?\s*\d{1,7}\s*\|\s*\.\d{1,2}|(?:¥|￥)\s*\|?\s*\d{1,7}\.\s*\|\s*\d{1,2}|(?:¥|￥)\s*\|\s*\d{1,7}(?:\.\d{1,2})?"""
    )
    private val BILL_AMOUNT_SIGNED_DECIMAL_REGEX = Regex(
        """(?:^|\||\s)[-+−]?\d{1,7}\.\d{1,2}(?:\s|\||$)"""
    )
    private val EXPLICIT_ORDER_NO_REGEX = Regex(
        """(?:交易单号|转账单号|订单编号|订单号|商户单号|商家订单号|支付宝交易号|交易流水号|流水号)\s*(?:[:：,，\s]|\|)*\s*([0-9A-Za-z_-]{10,44})"""
    )
    private val FULL_YEAR_DATE_REGEX = Regex(
        """(?<!\d)(?:20\d{2}[-年/]\d{1,2}[-月/]\d{1,2}|2\d年\d{1,2}月\d{1,2}日)"""
    )
    private val PROMO_NOISE_REGEX = Regex(
        """(?:支付成功|付款成功)(?:得|可领|领|享|抽|立减|返|送|获)[^\s|，。]{1,14}"""
    )
    private val CURRENCY_EXTRACT_REGEX = Regex(
        """(?:¥|￥)\s*([0-9]{1,7}(?:\.[0-9]{1,2})?)|([0-9]{1,7}(?:\.[0-9]{1,2})?)\s*元"""
    )

    private val STRUCTURAL_CONTROL_LABELS = setOf(
        "返回", "关闭", "更多", "完成", "分享", "客服", "主页", "搜索", "清空", "删除", "刷新"
    )

    private val SUMMARY_IGNORED_TOKENS = setOf(
        "返回", "更多", "关闭", "联系", "主页"
    )

    private val SENSITIVE_ACTIVITY_MARKERS = listOf(
        "password", "passwd", "pwd", "verification", "verify", "authentication",
        "biometric", "fingerprint", "faceid", "securekeyboard", "gesturelock", "lockactivity"
    )

    private val SENSITIVE_TEXT_MARKERS = listOf(
        "请输入支付密码", "请输入密码", "输入支付密码", "请输入交易密码",
        "指纹支付", "面容支付", "刷脸支付", "验证指纹", "验证面容",
        "指纹验证", "人脸验证", "面容验证", "短信验证码", "请输入验证码", "安全验证", "身份验证"
    )

    // 图片/相册/扫码/相机类容器（避免在扫一扫选图或群聊点开旧截图时触发读屏与 OCR）
    private val UNIVERSAL_MEDIA_OR_SCAN_CLASSES = listOf(
        "albumpreview", "imagegallery", "imagepreview", "photopreview",
        "mediapreview", "picturepreview", "imageviewer", "photoeditor",
        "cameraui", "imagepicker", "albumui", "mediapicker",
        "plugin.gallery", "beehive.photo", "scanner", "basescanui", "qrcode"
    )

    // 列表/聊天/商品详情/购物车/搜索/评价等明确非单笔账单容器
    private val UNIVERSAL_NON_BILL_CLASSES = listOf(
        "orderlistactivity", "orderlist", "billmainlist", "billhistory", "transactionrecord",
        "messagelist", "conversationlist", "chattingui", "chatactivity", "imactivity",
        "goodsdetail", "productdetail", "commoditydetail", "itemdetail", "skudetail",
        "cartactivity", "shoppingcart", "searchactivity", "searchresult",
        "commentactivity", "evaluation", "liveplay", "videoplay", "inputmethod"
    )

    private val WECHAT_BLOCKED_CLASS_MARKERS = listOf(
        "com.tencent.mm.ui.launcherui",
        ".chatting.",
        ".plugin.sns.", "snstimeline", "snsupload",
        ".plugin.finder.",
        ".plugin.gallery.",
        ".plugin.scanner.",
        ".plugin.brandservice.", "biztimeline",
        ".plugin.fts.",
        ".plugin.game.",
        ".plugin.shake.",
        ".plugin.nearby.",
        ".plugin.voip.",
        ".plugin.record.",
        ".plugin.emoji.",
        ".plugin.profile.",
        ".plugin.setting."
    )

    private val ALIPAY_BLOCKED_CLASS_MARKERS = listOf(
        ".scan.", "scanactivity", "camera", "beehive.photo", "photopreview", "imagepreview",
        "socialsdk", "chatmsg", "antforest", "antfarm", "searchactivity"
    )

    private val WECHAT_DEDICATED_EXACT = setOf(
        "com.tencent.mm.framework.app.uipagefragmentactivity",
        "com.tencent.mm.plugin.lite.ui.wxaliteapptransparentliteui",
        "com.tencent.mm.plugin.lite.ui.wxaliteappliteui"
    )

    private val WECHAT_DEDICATED_PLUGIN_MARKERS = listOf(
        ".wallet.", ".wallet_core.", ".wallet_index.", ".wallet_ecard.",
        ".remittance.", ".luckymoney.", ".offline.ui.walletoffline",
        ".wxpay.", ".aa.ui.", ".mall.ui."
    )

    private val UNIVERSAL_DEDICATED_PAY_OR_DETAIL_MARKERS = listOf(
        "mspcontaineractivity", "mspunirenderactivity",
        "orderdetailactivity", "orderdetail",
        "billdetail", "tradedetail", "transdetail", "transactiondetail",
        "paysuccess", "payresult", "paymentresult", "cashierresult", "remittancedetail"
    )

    // 付款前收银台或未付款结算页特征词
    private val CASHIER_OR_UNPAID_BLACKLIST = listOf(
        "确认付款", "确认支付", "立即付款", "立即支付", "去付款", "去支付",
        "选择付款方式", "已选中,", "未选中,", "付款金额",
        "提交订单", "确认订单", "填写订单", "去结算", "去凑单",
        "等待买家付款", "等待付款", "剩余支付时间", "未付款"
    )

    // 终态关闭、取消、失败或退款未完成状态（源自花有谱 TransactionRejection）
    private val CLOSED_FAILED_OR_REFUND_PENDING_BLACKLIST = listOf(
        "交易关闭", "交易已关闭", "订单关闭", "订单已关闭",
        "订单已取消", "交易已取消", "支付已取消", "付款已取消",
        "支付失败", "付款失败", "转账失败", "交易失败",
        "退款处理中", "退款申请中", "退款审核中", "退款受理中", "退款失败",
        "转账过期退还通知", "已退还"
    )

    // 账单列表、首页、会话流特征词（保留支付宝单笔详情右上角“全部账单”不入黑名单）
    private val MULTI_BILL_OR_FEED_BLACKLIST = listOf(
        "收支统计", "月账单", "本月支出", "较上月", "全部交易类型", "支付消息", "服务消息",
        "搜索小程序", "最近使用的小程序", "常用的小程序",
        "切换到发消息", "按住 说话", "切换到按住说话", "可按住 转文字",
        "全部会话", "点击查看全部消息",
        "我的账单", "支付服务", "浮窗", "退出星标",
        "扫一扫", "收付款", "消息盒子", "最近消息",
        "我的订单", "全部订单", "购物车"
    )

    // 零钱/余额宝/资产概览页特征词（源自蓝鲸记账 BALANCE_PAGE_MARKERS）
    private val BALANCE_ASSET_MARKERS = listOf(
        "零钱通", "零钱余额", "账户余额", "可用余额", "总资产",
        "累计收益", "昨日收益", "七日年化", "万份收益", "我的银行卡", "银行卡管理"
    )

    // 商品详情页浏览特征词（修复商品页“百亿补贴+退换无忧+¥29.9”被误判为已完成订单的漏洞）
    private val PRODUCT_BROWSING_STRONG_MARKERS = listOf(
        "领券购买", "立即抢购", "发起拼单", "单独购买", "去拼单", "参与拼单",
        "宝贝评价", "商品评价", "买家评价", "问大家", "商品参数", "进店逛逛", "关注店铺"
    )

    // 严格单笔交易元数据锚点（已彻底移除“百亿补贴/店铺优惠/退换无忧/配送方式”等商品详情通用词！）
    private val STRICT_BILL_FIELD_ANCHORS = listOf(
        "当前状态", "交易状态",
        "交易单号", "商户单号", "转账单号", "商家订单号", "订单号", "订单编号", "支付宝交易号", "流水号",
        "支付时间", "创建时间", "付款时间", "转账时间", "收款时间", "下单时间", "拼单时间", "成交时间", "交易时间", "到账时间", "退款时间", "扣款时间", "消费时间", "记账时间", "入账时间",
        "付款方式", "支付方式", "收款方式", "退回方式", "退款方式", "转入账户", "扣款方式",
        "医保支付", "医保统筹", "医保个账", "医保个人账户", "医保基金", "统筹支付", "统筹基金", "统筹基金支付", "基本医疗统筹", "大病统筹", "公务员补助", "医疗救助", "个账支付", "个人账户支付", "历年账户", "当年账户", "家庭共济", "医疗保险支付",
        "实付款", "实付金额", "合计实付", "实扣", "实际支付",
        "商品说明", "转账备注", "转账说明", "对方账户",
        "商户全称", "收款方全称", "收单机构",
        "账单分类", "账单管理", "计入收支", "对此订单有疑问", "对订单有疑惑", "申请电子回单", "申请电子凭证"
    )

    private val LABELED_BILL_TIME_KEYWORDS = listOf(
        "支付时间", "付款时间", "转账时间", "收款时间", "创建时间",
        "下单时间", "拼单时间", "成交时间", "交易时间", "到账时间", "退款时间",
        "扣款时间", "消费时间", "记账时间", "入账时间"
    )

    private val EXPLICIT_PAID_LABELS = listOf(
        "实付款", "实付金额", "合计实付", "实扣", "实际支付",
        "支付金额", "转账金额", "收款金额",
        "应付总额", "应付金额", "应付款", "订单金额", "订单总价", "商品总额", "商品总价", "实付",
        "医保支付", "医保统筹", "医保个账", "医保个人账户", "医保基金", "统筹支付", "统筹基金", "统筹基金支付", "基本医疗统筹", "大病统筹", "公务员补助", "医疗救助", "个账支付", "个人账户支付", "历年账户", "当年账户", "家庭共济", "医疗保险支付"
    )

    private val LIVE_COMPLETION_STATUS_KEYWORDS = listOf(
        "支付成功", "付款成功", "拼单成功", "扣款成功", "转账成功", "收款成功",
        "对方已收钱", "已收钱", "已存入零钱", "还款成功", "提现已发起",
        "退款成功", "已全额退款", "代付成功"
    )

    private val COMPLETION_CONTROL_KEYWORDS = listOf(
        "完成", "返回商家", "返回商户", "返回应用", "完成付款", "回首页", "查看订单", "继续购物"
    )

    private val PROMO_AMOUNT_LINE_MARKERS = listOf(
        "领券", "优惠券", "代金券", "抵扣券", "立减金", "红包", "积分", "返现", "补贴", "攒钱", "体验金"
    )

    private val ECOMMERCE_PACKAGES = setOf(
        "com.xunmeng.pinduoduo",
        "com.jingdong.app.mall",
        "com.taobao.taobao",
        "com.tmall.wireless",
        "com.ss.android.ugc.aweme",
        "com.ss.android.ugc.aweme.lite",
        "com.ss.android.ugc.live",
        "com.achievo.vipshop",
        "com.xingin.xhs",
        "com.sankuai.meituan",
        "com.sankuai.meituan.takeoutnew",
        "me.ele"
    )

    /**
     * 选择唯一的顶层前台应用窗口（忽略画中画与非应用悬浮层；优先 focused，其次 active，同级取最高 layer）
     */
    fun selectTopApplicationWindow(windows: List<WindowCandidate>): WindowCandidate? {
        val candidates = windows.filter {
            !it.isInPictureInPictureMode && it.type == APP_WINDOW_TYPE
        }
        if (candidates.isEmpty()) return null
        return candidates.filter { it.isFocused }.maxByOrNull { it.layer }
            ?: candidates.filter { it.isActive }.maxByOrNull { it.layer }
    }

    /**
     * 快速过滤明显属于普通 View / Dialog / Popup 的类名（避免覆盖真实 Activity 身份）
     */
    fun isLikelyViewOrDialogClass(className: String): Boolean {
        val trimmed = className.trim()
        if (trimmed.isEmpty() || !trimmed.contains('.')) return true
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.startsWith("android.widget.") ||
            lower.startsWith("android.view.") ||
            lower.startsWith("android.webkit.") ||
            lower.startsWith("com.android.internal.") ||
            lower.startsWith("androidx.compose.ui.window.") ||
            lower.startsWith("androidx.appcompat.app.alertdialog") ||
            lower.startsWith("com.google.android.material.bottomsheet.")
        ) {
            return true
        }
        val simple = lower.substringAfterLast('.')
        if (!simple.endsWith("activity") && !simple.endsWith("ui")) {
            if (simple.endsWith("layout") ||
                simple.endsWith("view") ||
                simple.endsWith("dialog") ||
                simple.endsWith("popup") ||
                simple.endsWith("bottomsheet") ||
                simple.endsWith("toast")
            ) {
                return true
            }
        }
        return false
    }

    /**
     * 对已核实的 Activity 容器进行角色分级
     */
    fun classifyActivityContainer(
        packageName: String,
        verifiedActivity: String
    ): WindowContainerRole {
        val pkg = packageName.trim()
        val actLower = verifiedActivity.trim().lowercase(Locale.ROOT)
        if (actLower.isEmpty()) return WindowContainerRole.SHARED_OR_WEB_STRICT

        if (SENSITIVE_ACTIVITY_MARKERS.any { actLower.contains(it) }) {
            return WindowContainerRole.SENSITIVE_AUTH_BLOCKED
        }

        if (UNIVERSAL_MEDIA_OR_SCAN_CLASSES.any { actLower.contains(it) } ||
            UNIVERSAL_NON_BILL_CLASSES.any { actLower.contains(it) }
        ) {
            return WindowContainerRole.NON_TRANSACTION_BLOCKED
        }

        if (pkg == "com.tencent.mm") {
            if (WECHAT_BLOCKED_CLASS_MARKERS.any { actLower.contains(it) }) {
                return WindowContainerRole.NON_TRANSACTION_BLOCKED
            }
            if (actLower in WECHAT_DEDICATED_EXACT ||
                (actLower.startsWith("com.tencent.mm.plugin.") &&
                    WECHAT_DEDICATED_PLUGIN_MARKERS.any { actLower.contains(it) })
            ) {
                return WindowContainerRole.DEDICATED_PAYMENT_OR_BILL
            }
            return WindowContainerRole.SHARED_OR_WEB_STRICT
        }

        if (pkg == "com.eg.android.AlipayGphone") {
            if (ALIPAY_BLOCKED_CLASS_MARKERS.any { actLower.contains(it) }) {
                return WindowContainerRole.NON_TRANSACTION_BLOCKED
            }
            if (UNIVERSAL_DEDICATED_PAY_OR_DETAIL_MARKERS.any { actLower.contains(it) }) {
                return WindowContainerRole.DEDICATED_PAYMENT_OR_BILL
            }
            return WindowContainerRole.SHARED_OR_WEB_STRICT
        }

        if (UNIVERSAL_DEDICATED_PAY_OR_DETAIL_MARKERS.any { actLower.contains(it) }) {
            return WindowContainerRole.DEDICATED_PAYMENT_OR_BILL
        }

        return WindowContainerRole.SHARED_OR_WEB_STRICT
    }

    /**
     * 计算纯骨架结构指纹（忽略动态金额/商户文本与屏幕像素坐标，仅对控件层级拓扑 + 短类名 + ViewId + 固定交互控制词做 16 位哈希）
     */
    fun computeStructureFingerprint(root: SkeletonNode?): String {
        if (root == null) return ""
        val sb = StringBuilder(256)
        appendSkeletonNode(root, depth = 0, out = sb)
        if (sb.isEmpty()) return ""
        return sha256Hex16(sb.toString())
    }

    /**
     * 直接从无障碍根节点高速提取前 5 层可见控件骨架指纹（不分配中间树对象，单次耗时 < 0.2ms）
     */
    fun computeStructureFingerprintFromAccessibilityRoot(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val sb = StringBuilder(256)
        appendAccessibilitySkeleton(root, depth = 0, out = sb)
        if (sb.isEmpty()) return ""
        return sha256Hex16(sb.toString())
    }

    private fun appendSkeletonNode(node: SkeletonNode, depth: Int, out: StringBuilder) {
        if (depth > SKELETON_MAX_DEPTH) return
        out.append(depth).append(':')
        out.append(normalizeShortClassName(node.className))
        val shortId = normalizeShortResourceId(node.viewIdResourceName)
        if (shortId.isNotEmpty()) {
            out.append('#').append(shortId)
        }
        val controlLabel = extractStructuralControlLabel(node.text, node.contentDescription)
        if (controlLabel.isNotEmpty()) {
            out.append('@').append(controlLabel)
        }
        out.append(';')

        val limit = minOf(node.children.size, SKELETON_MAX_CHILDREN)
        for (i in 0 until limit) {
            appendSkeletonNode(node.children[i], depth + 1, out)
        }
    }

    private fun appendAccessibilitySkeleton(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: StringBuilder
    ) {
        if (depth > SKELETON_MAX_DEPTH) return
        if (depth > 0 && !node.isVisibleToUser) return

        out.append(depth).append(':')
        out.append(normalizeShortClassName(node.className?.toString().orEmpty()))
        val shortId = normalizeShortResourceId(node.viewIdResourceName.orEmpty())
        if (shortId.isNotEmpty()) {
            out.append('#').append(shortId)
        }
        val controlLabel = extractStructuralControlLabel(
            text = node.text?.toString().orEmpty(),
            contentDesc = node.contentDescription?.toString().orEmpty()
        )
        if (controlLabel.isNotEmpty()) {
            out.append('@').append(controlLabel)
        }
        out.append(';')

        if (depth == SKELETON_MAX_DEPTH) return
        val totalChildren = node.childCount
        var visitedVisible = 0
        for (i in 0 until totalChildren) {
            if (visitedVisible >= SKELETON_MAX_CHILDREN) break
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            try {
                if (child.isVisibleToUser) {
                    visitedVisible++
                    appendAccessibilitySkeleton(child, depth + 1, out)
                }
            } finally {
                recycleNodeCompat(child)
            }
        }
    }

    private fun recycleNodeCompat(node: AccessibilityNodeInfo?) {
        if (node == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        @Suppress("DEPRECATION")
        runCatching { node.recycle() }
    }

    private fun normalizeShortClassName(rawClass: String): String =
        rawClass.trim().substringAfterLast('.').ifEmpty { "View" }

    private fun normalizeShortResourceId(rawResId: String): String =
        rawResId.trim().substringAfterLast('/').take(32)

    private fun extractStructuralControlLabel(text: String, contentDesc: String): String {
        val descTrimmed = contentDesc.trim()
        if (descTrimmed.isEmpty() || descTrimmed == text.trim()) return ""
        val normalized = descTrimmed
            .replace(DIGITS_REGEX, "#")
            .replace(WHITESPACE_REGEX, "")
        return if (normalized in STRUCTURAL_CONTROL_LABELS) normalized else ""
    }

    fun buildSignatureKey(
        packageName: String,
        activityName: String,
        structureFingerprint: String
    ): String {
        val pkg = packageName.trim()
        val act = activityName.trim().lowercase(Locale.ROOT).ifEmpty { "unknown" }
        val fp = structureFingerprint.trim().lowercase(Locale.ROOT)
        return "$pkg|$act|$fp"
    }

    fun containsLikelyBillAmount(text: String): Boolean {
        if (BILL_AMOUNT_PRIMARY_REGEX.containsMatchIn(text)) return true
        if (BILL_AMOUNT_SPLIT_NODE_REGEX.containsMatchIn(text)) return true
        if (BILL_AMOUNT_SIGNED_DECIMAL_REGEX.containsMatchIn(text)) return true
        return false
    }

    fun hasExplicitOrderNumber(text: String): Boolean =
        EXPLICIT_ORDER_NO_REGEX.findAll(text).any { m ->
            m.groupValues[1].count { ch -> ch.isDigit() } >= 8
        }

    /**
     * 核心窗口多维门禁评估函数（纯函数，无副作用）
     */
    fun evaluateBillWindow(input: WindowInspectionInput): WindowEvaluationResult {
        val pkg = input.packageName.trim()
        val containerRole = classifyActivityContainer(pkg, input.verifiedActivity)
        val footerStripped = AiAccountingEngine.stripEcommerceRecommendationFooter(input.rawPageText)
        val sanitized = footerStripped
            .replace(PROMO_NOISE_REGEX, " ")
            .replace("加入购物车", " ")
            .replace("加购物车", " ")

        // 第 1 关：隐私与密码核身硬拦截（即使手动识屏也绝不截取密码/核身窗口）
        if (containerRole == WindowContainerRole.SENSITIVE_AUTH_BLOCKED ||
            input.hasVisiblePasswordNode ||
            SENSITIVE_TEXT_MARKERS.any { sanitized.contains(it) }
        ) {
            return WindowEvaluationResult(
                accepted = false,
                reason = WindowRejectReason.SENSITIVE_AUTH_PAGE,
                containerRole = containerRole,
                sanitizedText = sanitized
            )
        }

        val hasAmount = containsLikelyBillAmount(sanitized)
        val occurredAtMillis = AiAccountingEngine.extractOccurredTimeMillis(
            text = sanitized,
            referenceMillis = input.nowMillis
        )
        val hasExplicitOrderNo = hasExplicitOrderNumber(sanitized)

        // 手动磁贴强制识屏：跳过自动容器白名单/骨架黑名单门禁，但仍拦截待付款收银台、已关闭订单、多笔账单列表与会话流
        if (input.forceManual) {
            if (CASHIER_OR_UNPAID_BLACKLIST.any { sanitized.contains(it) }) {
                return WindowEvaluationResult(false, WindowRejectReason.CASHIER_OR_UNPAID_STATE, containerRole, sanitized)
            }
            if (CLOSED_FAILED_OR_REFUND_PENDING_BLACKLIST.any { sanitized.contains(it) }) {
                return WindowEvaluationResult(false, WindowRejectReason.CLOSED_FAILED_OR_REFUND_PENDING, containerRole, sanitized)
            }
            val isManualOrderListTabs = sanitized.contains("待付款") &&
                sanitized.contains("待收货") &&
                (sanitized.contains("待发货") || sanitized.contains("待评价") || sanitized.contains("待分享") || sanitized.contains("退款/售后"))
            val isManualChatComposer = sanitized.contains("按住 说话") ||
                sanitized.contains("切换到按住说话") ||
                sanitized.contains("可按住 转文字") ||
                sanitized.contains("切换到发消息")
            val isManualSingleHeader = sanitized.contains("账单详情") ||
                sanitized.contains("交易详情") ||
                sanitized.contains("订单详情") ||
                sanitized.contains("转账详情") ||
                sanitized.contains("收款详情")
            val isManualAlipayHome = sanitized.contains("扫一扫") && sanitized.contains("收付款")
            if (isManualOrderListTabs || isManualChatComposer || isManualAlipayHome ||
                (!isManualSingleHeader && MULTI_BILL_OR_FEED_BLACKLIST.any { sanitized.contains(it) })
            ) {
                return WindowEvaluationResult(false, WindowRejectReason.CHAT_FEED_OR_BILL_LIST, containerRole, sanitized)
            }
            if (!hasAmount) {
                return WindowEvaluationResult(false, WindowRejectReason.MISSING_BILL_AMOUNT, containerRole, sanitized)
            }
            if (occurredAtMillis == null) {
                return WindowEvaluationResult(false, WindowRejectReason.MISSING_OCCURRED_TIME, containerRole, sanitized)
            }
            return WindowEvaluationResult(
                accepted = true,
                reason = WindowRejectReason.ACCEPTED,
                containerRole = containerRole,
                sanitizedText = sanitized,
                occurredAtMillis = occurredAtMillis,
                hasExplicitOrderNo = hasExplicitOrderNo
            )
        }

        // 第 2 关：非交易 Activity 容器与用户屏蔽页面指纹门禁
        if (containerRole == WindowContainerRole.NON_TRANSACTION_BLOCKED) {
            return WindowEvaluationResult(false, WindowRejectReason.NON_TRANSACTION_CONTAINER, containerRole, sanitized)
        }
        if (input.isSignatureBlocked) {
            return WindowEvaluationResult(false, WindowRejectReason.USER_BLOCKED_SIGNATURE, containerRole, sanitized)
        }

        // 第 3 关：可见可编辑输入框门禁（直接拦截聊天输入框、搜索框、评论区、发帖页）
        if (input.hasVisibleEditableNode && containerRole != WindowContainerRole.DEDICATED_PAYMENT_OR_BILL) {
            return WindowEvaluationResult(false, WindowRejectReason.VISIBLE_EDITABLE_INPUT, containerRole, sanitized)
        }

        // 第 4 关：付款前收银台 / 交易关闭 / 订单取消 / 支付失败 / 退款处理中门禁
        if (CASHIER_OR_UNPAID_BLACKLIST.any { sanitized.contains(it) }) {
            return WindowEvaluationResult(false, WindowRejectReason.CASHIER_OR_UNPAID_STATE, containerRole, sanitized)
        }
        if (CLOSED_FAILED_OR_REFUND_PENDING_BLACKLIST.any { sanitized.contains(it) }) {
            return WindowEvaluationResult(false, WindowRejectReason.CLOSED_FAILED_OR_REFUND_PENDING, containerRole, sanitized)
        }

        // 第 5 关：会话气泡、账单列表、余额资产页与商品详情导购页排除
        val isEcommerceOrderListTabs = sanitized.contains("待付款") &&
            sanitized.contains("待收货") &&
            (sanitized.contains("待发货") || sanitized.contains("待评价") || sanitized.contains("待分享") || sanitized.contains("退款/售后"))
        if (isEcommerceOrderListTabs) {
            return WindowEvaluationResult(false, WindowRejectReason.CHAT_FEED_OR_BILL_LIST, containerRole, sanitized)
        }

        val isAlipayHomeHeader = sanitized.contains("扫一扫") && sanitized.contains("收付款")
        if (isAlipayHomeHeader) {
            return WindowEvaluationResult(false, WindowRejectReason.CHAT_FEED_OR_BILL_LIST, containerRole, sanitized)
        }

        val isBillDetailHeader = sanitized.contains("账单详情") ||
            sanitized.contains("交易详情") ||
            sanitized.contains("订单详情") ||
            sanitized.contains("转账详情") ||
            sanitized.contains("收款详情")

        // 先检查聊天流/消息卡片链接与多账单汇总词（注意单笔详情标题优先于通用黑名单，但受聊天底部栏硬约束）
        val hasChatComposerMarkers = sanitized.contains("按住 说话") ||
            sanitized.contains("切换到按住说话") ||
            sanitized.contains("可按住 转文字") ||
            sanitized.contains("切换到发消息") ||
            sanitized.contains("全部会话") ||
            sanitized.contains("点击查看全部消息")
        if (hasChatComposerMarkers) {
            return WindowEvaluationResult(false, WindowRejectReason.CHAT_FEED_OR_BILL_LIST, containerRole, sanitized)
        }

        if (!hasExplicitOrderNo && (
                sanitized.contains("交易详情〉") ||
                    sanitized.contains("账单详情>") ||
                    sanitized.contains("查看账单详情") ||
                    sanitized.contains("查看详情") ||
                    sanitized.contains("查看详情>") ||
                    sanitized.contains("订单支付成功通知")
                )
        ) {
            return WindowEvaluationResult(false, WindowRejectReason.CHAT_FEED_OR_BILL_LIST, containerRole, sanitized)
        }

        if (!isBillDetailHeader && MULTI_BILL_OR_FEED_BLACKLIST.any { sanitized.contains(it) }) {
            return WindowEvaluationResult(false, WindowRejectReason.CHAT_FEED_OR_BILL_LIST, containerRole, sanitized)
        }

        // 零钱/余额宝/资产概览页拦截
        val hasWalletActionPair = sanitized.contains("充值") && sanitized.contains("提现")
        val balanceSignalCount = BALANCE_ASSET_MARKERS.count { sanitized.contains(it) }
        if (!hasExplicitOrderNo && !isBillDetailHeader && (hasWalletActionPair || balanceSignalCount >= 2)) {
            return WindowEvaluationResult(false, WindowRejectReason.BALANCE_OR_ASSET_PAGE, containerRole, sanitized)
        }

        // 电商商品详情浏览页拦截（无单号且出现“发起拼单/领券购买/宝贝评价/加入购物车+立即购买”）
        val hasAddToCartRaw = footerStripped.contains("加入购物车") || footerStripped.contains("加购物车")
        val hasProductBuyBar = hasAddToCartRaw && (
            sanitized.contains("立即购买") ||
                sanitized.contains("领券") ||
                (sanitized.contains("客服") && sanitized.contains("店铺"))
            )
        if (!hasExplicitOrderNo && !isBillDetailHeader && (
                PRODUCT_BROWSING_STRONG_MARKERS.any { sanitized.contains(it) } || hasProductBuyBar
                )
        ) {
            return WindowEvaluationResult(false, WindowRejectReason.PRODUCT_BROWSING_PAGE, containerRole, sanitized)
        }

        // 第 6 关：有效金额 + 带标签/四位年份时间校验 + 无单号完成页时效校验
        if (!hasAmount) {
            return WindowEvaluationResult(false, WindowRejectReason.MISSING_BILL_AMOUNT, containerRole, sanitized)
        }
        if (occurredAtMillis == null) {
            return WindowEvaluationResult(false, WindowRejectReason.MISSING_OCCURRED_TIME, containerRole, sanitized)
        }

        val hasLabeledBillTime = LABELED_BILL_TIME_KEYWORDS.any { sanitized.contains(it) } ||
            FULL_YEAR_DATE_REGEX.containsMatchIn(sanitized)
        if (!hasLabeledBillTime && !hasExplicitOrderNo) {
            return WindowEvaluationResult(
                accepted = false,
                reason = WindowRejectReason.UNLABELED_AMBIGUOUS_TIME,
                containerRole = containerRole,
                sanitizedText = sanitized,
                occurredAtMillis = occurredAtMillis
            )
        }

        if (!hasExplicitOrderNo && !isBillDetailHeader) {
            if (abs(input.nowMillis - occurredAtMillis) > LIVE_COMPLETION_MAX_AGE_MS) {
                return WindowEvaluationResult(
                    accepted = false,
                    reason = WindowRejectReason.STALE_UNNUMBERED_PAGE,
                    containerRole = containerRole,
                    sanitizedText = sanitized,
                    occurredAtMillis = occurredAtMillis
                )
            }
        }

        // 第 7 关：正特征结构闭环校验
        val strictAnchorCount = STRICT_BILL_FIELD_ANCHORS.count { sanitized.contains(it) }
        val hasExplicitPaidLabel = EXPLICIT_PAID_LABELS.any { sanitized.contains(it) }
        val isEcommercePkg = pkg in ECOMMERCE_PACKAGES
        val hasOrderHistoryIndicators = sanitized.contains("已签收") ||
            sanitized.contains("物流轨迹") ||
            sanitized.contains("申请退款") ||
            sanitized.contains("申请售后") ||
            sanitized.contains("退换/售后") ||
            sanitized.contains("售后详情") ||
            sanitized.contains("钱款去向") ||
            sanitized.contains("商品快照")
        val hasLiveCompletionStatus = LIVE_COMPLETION_STATUS_KEYWORDS.any { sanitized.contains(it) }
        val isLivePaymentSuccessPage = (sanitized.contains("支付成功") ||
            sanitized.contains("付款成功") ||
            sanitized.contains("拼单成功")) && !hasOrderHistoryIndicators

        // 路径 A：电商或带历史履约标识的单笔订单详情页
        if ((isEcommercePkg || hasOrderHistoryIndicators) && !isLivePaymentSuccessPage) {
            if (pkg == "com.xunmeng.pinduoduo") {
                val ok = hasExplicitOrderNo && hasExplicitPaidLabel && hasLabeledBillTime
                return buildFinalDecision(ok, containerRole, sanitized, occurredAtMillis, hasExplicitOrderNo)
            }
            if (hasExplicitOrderNo && hasExplicitPaidLabel && hasLabeledBillTime) {
                return buildFinalDecision(true, containerRole, sanitized, occurredAtMillis, true)
            }
            // 专用订单详情 Activity 首屏（订单编号在下半屏尚未滑出，但首屏同时具备明确下单时间与售后/发票锚点）
            if (containerRole == WindowContainerRole.DEDICATED_PAYMENT_OR_BILL && hasLabeledBillTime && hasExplicitPaidLabel) {
                val hasDedicatedDetailProof = isBillDetailHeader ||
                    sanitized.contains("感谢您在京东购物") ||
                    ((sanitized.contains("申请售后") || sanitized.contains("退换/售后")) &&
                        (sanitized.contains("查看发票") || sanitized.contains("再次购买")))
                return buildFinalDecision(hasDedicatedDetailProof, containerRole, sanitized, occurredAtMillis, hasExplicitOrderNo)
            }
            return buildFinalDecision(false, containerRole, sanitized, occurredAtMillis, hasExplicitOrderNo)
        }

        // 路径 B：支付平台 / 银行单笔「账单详情 / 交易详情」页
        if (isBillDetailHeader && strictAnchorCount >= 2 && hasLabeledBillTime) {
            return buildFinalDecision(true, containerRole, sanitized, occurredAtMillis, hasExplicitOrderNo)
        }
        if (hasExplicitOrderNo && strictAnchorCount >= 3 && hasLabeledBillTime) {
            return buildFinalDecision(true, containerRole, sanitized, occurredAtMillis, true)
        }

        // 路径 C：刚完成的「支付成功 / 付款成功 / 转账成功 / 拼单成功」结果页
        if (hasLiveCompletionStatus && hasLabeledBillTime) {
            val hasCompletionControl = COMPLETION_CONTROL_KEYWORDS.any { sanitized.contains(it) }
            if (containerRole == WindowContainerRole.SHARED_OR_WEB_STRICT && !hasExplicitOrderNo) {
                if (!hasCompletionControl) {
                    return buildFinalDecision(false, containerRole, sanitized, occurredAtMillis, false)
                }
                if (hasAmbiguousPromotionalAmountsWithoutPaidLabel(sanitized, hasExplicitPaidLabel)) {
                    return WindowEvaluationResult(
                        accepted = false,
                        reason = WindowRejectReason.AMBIGUOUS_PROMO_AMOUNTS,
                        containerRole = containerRole,
                        sanitizedText = sanitized,
                        occurredAtMillis = occurredAtMillis,
                        hasExplicitOrderNo = false
                    )
                }
                return buildFinalDecision(true, containerRole, sanitized, occurredAtMillis, false)
            }
            val ok = hasCompletionControl || strictAnchorCount >= 2
            return buildFinalDecision(ok, containerRole, sanitized, occurredAtMillis, hasExplicitOrderNo)
        }

        return buildFinalDecision(false, containerRole, sanitized, occurredAtMillis, hasExplicitOrderNo)
    }

    /**
     * 自动截图 OCR 前置门禁：
     * 严禁在非交易容器、已屏蔽页面指纹、含密码/编辑输入框页面触发截屏 OCR。
     */
    fun shouldAllowAutoOcrFallback(
        packageName: String,
        verifiedActivity: String,
        partialText: String,
        hasVisiblePasswordNode: Boolean,
        hasVisibleEditableNode: Boolean,
        isSignatureBlocked: Boolean
    ): Boolean {
        val role = classifyActivityContainer(packageName, verifiedActivity)
        if (role == WindowContainerRole.SENSITIVE_AUTH_BLOCKED ||
            role == WindowContainerRole.NON_TRANSACTION_BLOCKED
        ) {
            return false
        }
        if (isSignatureBlocked || hasVisiblePasswordNode) return false
        if (hasVisibleEditableNode && role != WindowContainerRole.DEDICATED_PAYMENT_OR_BILL) {
            return false
        }

        val cleaned = partialText
            .replace("加入购物车", " ")
            .replace("加购物车", " ")

        if (SENSITIVE_TEXT_MARKERS.any { cleaned.contains(it) }) return false
        if (CASHIER_OR_UNPAID_BLACKLIST.any { cleaned.contains(it) }) return false
        if (CLOSED_FAILED_OR_REFUND_PENDING_BLACKLIST.any { cleaned.contains(it) }) return false
        if (MULTI_BILL_OR_FEED_BLACKLIST.any { cleaned.contains(it) }) return false

        val actLower = verifiedActivity.lowercase(Locale.ROOT)
        if (actLower.contains("orderdetailactivity")) {
            return true
        }

        return cleaned.contains("账单详情") ||
            cleaned.contains("交易详情") ||
            cleaned.contains("订单详情") ||
            cleaned.contains("订单编号") ||
            cleaned.contains("交易单号") ||
            (packageName == "com.jingdong.app.mall" &&
                role == WindowContainerRole.DEDICATED_PAYMENT_OR_BILL &&
                (cleaned.contains("实付款") || cleaned.contains("应付款")))
    }

    /**
     * 生成脱敏可读的窗口摘要（用于在「最近识屏页面」列表帮助用户辨认是哪个页面，绝不落盘密码或长隐私串）
     */
    fun summarizeCapturedPage(rawText: String): String {
        val tokens = rawText.splitToSequence('|')
            .map { it.trim() }
            .filter { token ->
                token.isNotEmpty() &&
                    token.length <= 26 &&
                    token !in SUMMARY_IGNORED_TOKENS &&
                    !token.startsWith("[AI_")
            }
            .distinct()
            .take(3)
            .toList()
        return tokens.joinToString(" · ").take(42)
    }

    private fun hasAmbiguousPromotionalAmountsWithoutPaidLabel(
        sanitizedText: String,
        hasExplicitPaidLabel: Boolean
    ): Boolean {
        if (hasExplicitPaidLabel) return false
        val segments = sanitizedText.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        val nonPromoAmounts = LinkedHashSet<String>()
        val promoAmounts = LinkedHashSet<String>()
        for (seg in segments) {
            val amountsInSeg = CURRENCY_EXTRACT_REGEX.findAll(seg)
                .map { m -> m.groupValues[1].ifBlank { m.groupValues[2] } }
                .toList()
            if (amountsInSeg.isEmpty()) continue
            if (PROMO_AMOUNT_LINE_MARKERS.any { seg.contains(it) }) {
                promoAmounts.addAll(amountsInSeg)
            } else {
                nonPromoAmounts.addAll(amountsInSeg)
            }
        }
        // 若屏幕无唯一主金额（只有促销红包金额，或同时散落 ≥ 2 个不同的无标签金额且含促销词），按花有谱 Fail-Closed 原则拒绝猜测
        if (nonPromoAmounts.isEmpty() && promoAmounts.isNotEmpty()) return true
        if (nonPromoAmounts.size >= 2 && promoAmounts.isNotEmpty()) return true
        return false
    }

    private fun buildFinalDecision(
        accepted: Boolean,
        containerRole: WindowContainerRole,
        sanitized: String,
        occurredAtMillis: Long?,
        hasExplicitOrderNo: Boolean
    ): WindowEvaluationResult = WindowEvaluationResult(
        accepted = accepted,
        reason = if (accepted) WindowRejectReason.ACCEPTED else WindowRejectReason.INSUFFICIENT_BILL_STRUCTURE,
        containerRole = containerRole,
        sanitizedText = sanitized,
        occurredAtMillis = occurredAtMillis,
        hasExplicitOrderNo = hasExplicitOrderNo
    )

    private fun sha256Hex16(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val chars = CharArray(16)
        val hex = "0123456789abcdef"
        for (i in 0 until 8) {
            val v = digest[i].toInt() and 0xFF
            chars[i * 2] = hex[v ushr 4]
            chars[i * 2 + 1] = hex[v and 0x0F]
        }
        return String(chars)
    }
}
