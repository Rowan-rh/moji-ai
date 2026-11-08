package com.moji.ai.ai

import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

@Serializable
data class AiParsedTransactionResult(
    val isValidTransaction: Boolean = false,
    val type: String = "EXPENSE",               // EXPENSE, INCOME, TRANSFER, REPAYMENT, REFUND_OFFSET
    val amount: Double = 0.0,                   // 金额（元）
    val currency: String = "CNY",               // 币种代码 CNY, USD, HKD, EUR, JPY, GBP
    val itemQuantity: Int = 1,                  // 购物商品数量
    val quantityDesc: String = "1件",           // 数量描述（如 "1件"、"2件"、"多件"）
    val category: String = "日常-其他",
    val merchant: String = "",
    val note: String = "",
    val sourceWalletId: Long? = null,
    val targetWalletId: Long? = null,
    val relatedExpenseId: Long? = null,
    val occurredAtMillis: Long? = null,         // 实际购物/交易发生时间（毫秒），从账单上的支付时间解析
    val orderKey: String = "",                  // 订单唯一 Key（优先取平台订单号，其次取商户单号）
    val transactionNo: String = "",             // 支付平台流水号 / 交易单号 / 订单号 / 转账单号
    val merchantOrderNo: String = "",           // 商户单号 / 商家订单号
    val paymentMethodRaw: String = "",          // 账单上显示的原始支付方式（如 "招商银行信用卡(3354)"、"花呗"）
    val discountCents: Long = 0L,               // 混合支付中的优惠/立减/红包抵扣金额（分）
    val confidence: Float = 0.9f
)

/**
 * 核心 AI 记账大脑：
 * 1. 提取并留存【订单唯一 Key (orderKey)】、【实际购物发生时间 (occurredAtMillis，非截图时间)】、【交易金额】与【商品数量 (itemQuantity / quantityDesc)】；
 * 2. 支付账号匹配：银行卡按后 4 位 (`cardLast4`) 匹配，花呗/白条/零钱/账户余额按汉字匹配。
 */
class AiAccountingEngine(
    private val configManager: AiConfigManager,
    private val merchantMemoryManager: MerchantMemoryManager? = null
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    companion object {
        private val KNOWN_FIELD_LABELS = setOf(
            "返回", "关闭", "更多", "完成", "账单详情", "交易详情", "服务详情", "订单详情",
            "当前状态", "交易状态", "支付成功", "付款成功", "交易成功", "拼单成功", "待发货", "待收货", "已签收",
            "对方已收钱", "已收钱", "已存入零钱", "已转账", "退款成功", "已全额退款",
            "商品", "商品说明", "转账说明", "收款方备注", "留言", "备注",
            "商户名称", "商户全称", "收款方", "收款方全称", "对方账户", "收单机构",
            "支付方式", "付款方式", "收款方式", "扣款方式", "退款方式", "退回方式", "转入账户", "配送方式", "配送服务",
            "发票类型", "发票信息", "不开发票", "电子普通发票", "交易快照", "商品快照", "收起",
            "支付时间", "转账时间", "收款时间", "创建时间", "到账时间", "退款时间", "付款时间", "下单时间", "拼单时间", "成交时间", "发货时间",
            "交易单号", "商户单号", "转账单号", "订单号", "商家订单号", "流水号", "订单编号", "支付宝交易号", "快递单号",
            "商品总价", "商品总额", "商品金额", "订单总价", "订单金额", "原价", "总价",
            "实付", "实付款", "实付金额", "实扣", "实际支付", "应付总额", "合计实付",
            "店铺优惠", "平台优惠", "百亿补贴", "官方立减", "支付优惠", "优惠券", "红包抵扣", "京豆抵扣", "共减", "已优惠", "优惠金额", "运费",
            "账单服务", "对订单有疑惑", "对此订单有疑问", "定位到此交易", "在此商户的交易",
            "申请退款", "再来一单", "查看往来记录", "投诉", "常见问题", "全部账单", "复制",
            "添加", "请选择", "开关", "计入收支", "标签", "账单分类", "账单管理",
            "我的消费图鉴", "进入小程序", "联系商家", "联系客服", "拨打电话", "AA收款", "申请电子回单", "申请电子凭证",
            "支付奖励", "立即领取", "查看详情", "为您推荐"
        )

        private val GENERIC_MERCHANT_BLACKLIST = setOf(
            "微信支付", "微信", "支付宝", "云闪付", "招商银行", "工商银行", "建设银行",
            "中国银行", "农业银行", "交通银行", "账单详情", "交易详情", "订单详情", "支付成功",
            "付款成功", "交易成功", "转账成功", "收款成功", "还款成功", "提现已发起", "拼单成功",
            "待发货", "待收货", "已签收", "已完成", "买家已付款", "卖家已发货",
            "退款成功", "已全额退款", "扣款成功", "代付成功", "当前状态", "交易状态",
            "返回", "完成", "更多", "回首页", "个人", "商户", "消费", "支出", "收入",
            "二维码收款", "扫二维码付款", "浮窗", "退出星标", "搜索", "设置", "复制",
            "消息盒子", "最近消息", "全部账单", "服务详情", "账单管理", "账单分类",
            "商品总价", "商品总额", "实付款", "实付", "店铺优惠", "平台优惠", "百亿补贴", "运费",
            "我的消费图鉴", "添加", "请选择", "开关", "联系商家", "联系客服", "申请售后", "加入购物车"
        )

        private val txNoRegex = Regex(
            """(?:交易单号|转账单号|订单号|订单编号|支付宝交易号|交易流水号|流水号)\s*(?:\||[:：])?\s*([0-9A-Za-z_-]{10,44})"""
        )
        private val merchantOrderNoRegex = Regex(
            """(?:商户单号|商家订单号|商户订单号)\s*(?:\||[:：])?\s*([0-9A-Za-z_-]{8,44})"""
        )
        private val fullDatePatterns = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy年MM月dd日 HH:mm:ss",
            "yyyy年MM月dd日 HH:mm",
            "yyyy年M月d日 HH:mm:ss",
            "yyyy年M月d日 HH:mm",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm"
        )

        private val ecommerceFooterMarkers = listOf(
            "| 精选好货",
            "| 猜你喜欢",
            "| 相似商品",
            "| 相似推荐",
            "| 为你推荐",
            "| 相关推荐",
            "| 大家都在买",
            "| 你可能还喜欢",
            "| 500x9999%3E",
            "立即评价 | 再次拼单",
            "确认收货 | 查看物流 | 再次拼单",
            "再次拼单 | 查看物流 | 更多",
            "再次拼单 | 更多"
        )

        /**
         * 截断电商订单详情页底部的「精选好货 / 猜你喜欢 / 相似推荐」商品瀑布流，
         * 防止底部推荐商品的价格（如 ¥75、¥19.1、¥259.9）和标签（立减112元、先用后付）污染当前订单。
         */
        fun stripEcommerceRecommendationFooter(rawText: String): String {
            val segments = rawText.split(Regex("""(?=\[AI_[A-Z_]+\s*\|)""")).filter { it.isNotBlank() }
            if (segments.size > 1) {
                return segments.joinToString(" | ") { stripEcommerceRecommendationFooterSingle(it) }
            }
            return stripEcommerceRecommendationFooterSingle(rawText)
        }

        private fun stripEcommerceRecommendationFooterSingle(rawText: String): String {
            val minCutIndex = listOf(
                rawText.indexOf("订单编号"),
                rawText.indexOf("交易单号"),
                rawText.indexOf("实付款"),
                rawText.indexOf("实付"),
                rawText.indexOf("应付款"),
                rawText.indexOf("应付金额"),
                rawText.indexOf("商品总额"),
                rawText.indexOf("下单时间")
            ).filter { it >= 0 }.maxOrNull() ?: 0

            var cutPos = rawText.length
            for (marker in ecommerceFooterMarkers) {
                val idx = rawText.indexOf(marker, startIndex = minCutIndex)
                if (idx in 1 until cutPos) {
                    cutPos = idx
                }
            }
            return if (cutPos < rawText.length) rawText.substring(0, cutPos).trim() else rawText
        }

        /**
         * 判断采集文本中是否包含明确的支付/交易/下单时间（如 `2026-11-08 14:54:50`、`2026年11月08日 14:54:50`、`昨天 21:18`、`11-05 20:23`）。
         * 没有任何时间的页面（如仅显示“支付成功 + 商户 + 金额 + 完成”的过渡页）一律不予入账。
         */
        fun hasExplicitOccurredTime(
            text: String,
            referenceMillis: Long = System.currentTimeMillis()
        ): Boolean = extractOccurredTimeMillis(text = text, referenceMillis = referenceMillis) != null

        /**
         * 从账单文本提取「实际购物/交易发生时间（毫秒）」：
         * 支持 `2026-11-06 21:18:53`、`2026年11月5日 18:24:25`、`2026年11月01日 15时56分23秒`、`昨天 21:18`、`11-05 20:23` 等格式。
         * 注意：
         * 1. 自动剔除「物流轨迹：... 时间：...」中的快递签收时间，防止把快递签收时间误当成下单时间；
         * 2. 严禁使用当前截图时间或订单号前缀伪造时间，无明确时间时返回 `null`。
         */
        fun extractOccurredTimeMillis(
            text: String,
            referenceMillis: Long = System.currentTimeMillis(),
            @Suppress("UNUSED_PARAMETER") orderKey: String = ""
        ): Long? {
            val textWithoutLogistics = text.replace(
                Regex("""物流轨迹[:：][^|]*"""),
                " "
            )
            val normalized = textWithoutLogistics
                .replace(
                    Regex("""(\d{1,2})时(\d{1,2})分(?:(\d{1,2})秒)?"""),
                    "$1:$2:$3"
                )
                .replace(":|", "00|")
                .replace(Regex(""":(?=\s|$)"""), ":00")
                .replace(
                    Regex("""(20\d{2}[-年/]\d{1,2}[-月/]\d{1,2}日?)\s*\|\s*(\d{1,2}:\d{2}(?::\d{2})?)"""),
                    "$1 $2"
                )
                .replace(
                    Regex("""(20\d{2}年\d{1,2}月\d{1,2}日)(\d{1,2}:\d{2}(?::\d{2})?)"""),
                    "$1 $2"
                )
            val fullDateRegex = Regex(
                """(20\d{2}[-年/]\d{1,2}[-月/]\d{1,2}日?\s+\d{1,2}:\d{2}(?::\d{2})?)"""
            )
            fullDateRegex.find(normalized)?.groupValues?.getOrNull(1)?.trim()?.let { rawDate ->
                for (pattern in fullDatePatterns) {
                    val parsed = runCatching {
                        SimpleDateFormat(pattern, Locale.CHINA).apply { isLenient = false }.parse(rawDate)?.time
                    }.getOrNull()
                    if (parsed != null) return parsed
                }
            }

            val relativeRegex = Regex("""(今天|昨天|前天)\s*(\d{1,2}):(\d{2})(?::(\d{2}))?""")
            relativeRegex.find(textWithoutLogistics)?.let { m ->
                val dayWord = m.groupValues[1]
                val hour = m.groupValues[2].toIntOrNull() ?: return@let
                val minute = m.groupValues[3].toIntOrNull() ?: return@let
                val second = m.groupValues[4].toIntOrNull() ?: 0
                val cal = Calendar.getInstance().apply {
                    timeInMillis = referenceMillis
                    when (dayWord) {
                        "昨天" -> add(Calendar.DAY_OF_YEAR, -1)
                        "前天" -> add(Calendar.DAY_OF_YEAR, -2)
                    }
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, second)
                    set(Calendar.MILLISECOND, 0)
                }
                return cal.timeInMillis
            }

            val monthDayRegex = Regex("""(?<!\d)(?:(\d{1,2})月(\d{1,2})日\s*|(\d{1,2})-(\d{1,2})\s+)(\d{1,2}):(\d{2})(?::(\d{2}))?""")
            monthDayRegex.find(textWithoutLogistics)?.let { m ->
                val monthStr = m.groupValues[1].ifBlank { m.groupValues[3] }
                val dayStr = m.groupValues[2].ifBlank { m.groupValues[4] }
                val month = (monthStr.toIntOrNull() ?: return@let) - 1
                val day = dayStr.toIntOrNull() ?: return@let
                val hour = m.groupValues[5].toIntOrNull() ?: return@let
                val minute = m.groupValues[6].toIntOrNull() ?: return@let
                val second = m.groupValues[7].toIntOrNull() ?: 0
                if (month in 0..11 && day in 1..31 && hour in 0..23 && minute in 0..59) {
                    val cal = Calendar.getInstance().apply {
                        timeInMillis = referenceMillis
                        set(Calendar.MONTH, month)
                        set(Calendar.DAY_OF_MONTH, day)
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, second)
                        set(Calendar.MILLISECOND, 0)
                    }
                    return cal.timeInMillis
                }
            }

            return null
        }
    }

    suspend fun parseCapturedText(
        rawText: String,
        sourceAppPackage: String,
        captureMode: String,
        wallets: List<WalletEntity>,
        recentExpenses: List<TransactionEntity>,
        capturedAtMillis: Long = System.currentTimeMillis()
    ): TransactionEntity? = withContext(Dispatchers.IO) {
        val config = configManager.getConfig()

        val localFallback = localSmartFallbackParse(rawText, sourceAppPackage, wallets, recentExpenses, capturedAtMillis)

        // 对于无障碍/OCR 结构化账单详情页，若本地高智商解析器已高置信度提取出金额与字段，直接 0 延迟返回，避免网络请求拖慢流体云弹出速度
        val shouldSkipNetworkForFastScreenCapture = localFallback != null &&
            localFallback.isValidTransaction &&
            localFallback.amount > 0.0 &&
            (captureMode == "AI_ACCESSIBILITY" || captureMode == "AI_OCR" || captureMode == "AI_MERGED")

        val parsed: AiParsedTransactionResult? = if (
            !shouldSkipNetworkForFastScreenCapture && (
                config.apiKey.isNotBlank() ||
                    config.baseUrl.contains("127.0.0.1") ||
                    config.baseUrl.contains("10.0.2.2") ||
                    config.baseUrl.contains("localhost")
                )
        ) {
            runCatching {
                when (config.provider) {
                    AiProviderType.OPENAI_COMPATIBLE -> callOpenAiCompatibleApi(config, rawText, sourceAppPackage, wallets, recentExpenses)
                    AiProviderType.GEMINI_OFFICIAL -> callGeminiApi(config, rawText, sourceAppPackage, wallets, recentExpenses)
                }
            }.getOrNull()
        } else {
            null
        }

        val finalResult = parsed ?: if (config.enableHeuristicFallback) {
            localFallback
        } else {
            null
        }

        if (finalResult == null || !finalResult.isValidTransaction || finalResult.amount <= 0.0) {
            return@withContext null
        }

        val txType = runCatching { TransactionType.valueOf(finalResult.type.uppercase()) }
            .getOrDefault(TransactionType.EXPENSE)
        val currencyCode = CurrencyCode.fromCode(finalResult.currency)
        val amountCents = (finalResult.amount * 100.0).roundToLong()
        val amountCnyCents = (amountCents * currencyCode.defaultRateToCny).roundToLong()

        val resolvedTxNo = finalResult.transactionNo.ifBlank { localFallback?.transactionNo.orEmpty() }
        val resolvedMerchantNo = finalResult.merchantOrderNo.ifBlank { localFallback?.merchantOrderNo.orEmpty() }
        val resolvedOrderKey = finalResult.orderKey
            .ifBlank { resolvedTxNo }
            .ifBlank { resolvedMerchantNo }
        val resolvedPayMethod = finalResult.paymentMethodRaw.ifBlank { localFallback?.paymentMethodRaw.orEmpty() }
        // 严禁把当前采集/截图时间 (capturedAtMillis) 冒充为支付时间：若页面没有任何明确时间，直接拦截不予录入！
        val resolvedOccurredAt = extractOccurredTimeMillis(rawText, capturedAtMillis)
            ?: localFallback?.occurredAtMillis
            ?: finalResult.occurredAtMillis
            ?: return@withContext null

        val resolvedQuantity = if (finalResult.itemQuantity > 1) {
            finalResult.itemQuantity
        } else {
            localFallback?.itemQuantity ?: 1
        }
        val resolvedQuantityDesc = if (finalResult.quantityDesc.isNotBlank() && finalResult.quantityDesc != "1件") {
            finalResult.quantityDesc
        } else {
            localFallback?.quantityDesc ?: "1件"
        }

        val resolvedSourceWalletId = finalResult.sourceWalletId ?: localFallback?.sourceWalletId
        val resolvedTargetWalletId = finalResult.targetWalletId ?: localFallback?.targetWalletId
        val resolvedDiscountCents = maxOf(finalResult.discountCents, localFallback?.discountCents ?: 0L)

        val dedupKey = when {
            resolvedOrderKey.isNotBlank() -> "KEY:${resolvedOrderKey}_$amountCents"
            else -> "TIME:${resolvedOccurredAt / 1000L}_$amountCents"
        }

        TransactionEntity(
            type = txType,
            amountCents = amountCents,
            currency = currencyCode.name,
            exchangeRateToCny = currencyCode.defaultRateToCny,
            amountCnyCents = amountCnyCents,
            discountCents = resolvedDiscountCents,
            itemQuantity = resolvedQuantity,
            quantityDesc = resolvedQuantityDesc,
            category = finalResult.category.ifBlank { defaultCategoryForType(txType) },
            merchant = finalResult.merchant,
            note = finalResult.note,
            sourceWalletId = resolvedSourceWalletId,
            targetWalletId = resolvedTargetWalletId,
            relatedTransactionId = finalResult.relatedExpenseId,
            timestamp = resolvedOccurredAt,
            capturedAtMillis = capturedAtMillis,
            orderKey = resolvedOrderKey,
            transactionNo = resolvedTxNo,
            merchantOrderNo = resolvedMerchantNo,
            paymentMethodRaw = resolvedPayMethod,
            dedupFingerprint = dedupKey,
            captureSource = captureMode,
            rawCapturedText = rawText.take(1500),
            aiConfidence = finalResult.confidence,
            updatedAtMillis = capturedAtMillis
        )
    }

    /**
     * 支付账号精准匹配引擎：
     * 1. 银行卡（信用卡/储蓄卡）：根据后 4 位（如 `(3354)`、`尾号8848`）匹配 `wallet.cardLast4`；
     * 2. 花呗 / 白条 / 微信零钱 / 账户余额 / 余额宝：按汉字名称与 `aliases` 匹配。
     */
    fun matchWalletForPayment(
        payMethodRaw: String,
        fullText: String,
        sourceAppPackage: String,
        wallets: List<WalletEntity>,
        preferCredit: Boolean? = null
    ): WalletEntity? {
        val explicitCardLast4Pattern = Regex("""(?:[(（]|尾号|后4位|后四位|卡号)\s*(\d{4})\s*[)）]?""")
        val prioritizedScopes = listOf(payMethodRaw, fullText).filter { it.isNotBlank() }

        for (scopeText in prioritizedScopes) {
            val extractedLast4List = explicitCardLast4Pattern.findAll(scopeText)
                .map { it.groupValues[1] }
                .distinct()
                .toList()

            for (last4 in extractedLast4List) {
                val last4Candidates = wallets.filter { w ->
                    val walletLast4 = w.cardLast4?.trim()
                        ?: Regex("""(\d{4})""").find(w.name)?.groupValues?.getOrNull(1)
                    walletLast4 == last4 && (preferCredit == null || w.type.isLiability == preferCredit)
                }
                if (last4Candidates.size == 1) {
                    return last4Candidates.first()
                } else if (last4Candidates.size > 1) {
                    last4Candidates.firstOrNull { w ->
                        (scopeText.contains("信用卡") && w.type.isLiability) ||
                            ((scopeText.contains("储蓄卡") || scopeText.contains("借记卡")) && !w.type.isLiability) ||
                            scopeText.contains(w.name.take(4))
                    }?.let { return it }
                    return last4Candidates.first()
                }
            }

            val directLast4Candidates = wallets.filter { w ->
                val l4 = w.cardLast4?.trim()
                !l4.isNullOrBlank() &&
                    !l4.startsWith("202") &&
                    Regex("""(?<!\d)${Regex.escape(l4)}(?!\d)""").containsMatchIn(scopeText) &&
                    (preferCredit == null || w.type.isLiability == preferCredit)
            }
            if (directLast4Candidates.isNotEmpty()) {
                return directLast4Candidates.firstOrNull { w ->
                    (scopeText.contains("信用卡") && w.type.isLiability) ||
                        ((scopeText.contains("储蓄卡") || scopeText.contains("借记卡")) && !w.type.isLiability) ||
                        scopeText.contains(w.name.take(2))
                } ?: directLast4Candidates.first()
            }

            val chineseKeywordOrder = listOf(
                "花呗" to listOf("花呗", "蚂蚁花呗", "信用购", "抖音月付", "美团月付", "先用后付"),
                "白条" to listOf("白条", "京东白条"),
                "零钱" to listOf("零钱", "零钱通", "微信零钱", "微信支付"),
                "余额" to listOf("余额宝", "账户余额", "个人余额", "支付宝余额", "多多支付", "抖音支付", "京东支付")
            )
            for ((_, keywords) in chineseKeywordOrder) {
                if (keywords.any { scopeText.contains(it) }) {
                    wallets.firstOrNull { w ->
                        val walletTokens = listOf(w.name, w.subTypeLabel) +
                            w.aliases.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
                        keywords.any { kw -> walletTokens.any { wt -> wt.contains(kw) || kw.contains(wt) } } &&
                            (preferCredit == null || w.type.isLiability == preferCredit)
                    }?.let { return it }
                }
            }

            val scoredWallets = wallets
                .filter { preferCredit == null || it.type.isLiability == preferCredit }
                .mapNotNull { w ->
                    val names = (listOf(w.name) + w.aliases.split(",", "，").map { it.trim() })
                        .filter { it.length >= 2 && !it.all { ch -> ch.isDigit() } }
                    val bestMatchLen = names.filter { scopeText.contains(it, ignoreCase = true) }
                        .maxOfOrNull { it.length } ?: 0
                    if (bestMatchLen > 0) w to bestMatchLen else null
                }
                .sortedByDescending { it.second }

            if (scoredWallets.isNotEmpty()) {
                return scoredWallets.first().first
            }
        }

        if (sourceAppPackage.contains("tencent.mm")) {
            return wallets.firstOrNull { it.name.contains("微信") || it.name.contains("零钱") }
        }
        if (sourceAppPackage.contains("eg.android.AlipayGphone") || sourceAppPackage.contains("taobao") || sourceAppPackage.contains("tmall")) {
            return wallets.firstOrNull { it.name.contains("支付宝") || it.name.contains("账户余额") || it.name.contains("余额宝") || it.name.contains("花呗") }
        }
        if (sourceAppPackage.contains("jingdong")) {
            return wallets.firstOrNull { it.name.contains("白条") || it.name.contains("微信") }
        }
        if (sourceAppPackage.contains("pinduoduo") || sourceAppPackage.contains("aweme") || sourceAppPackage.contains("meituan")) {
            return wallets.firstOrNull { it.name.contains("微信") || it.name.contains("零钱") || it.name.contains("支付宝") }
        }
        return wallets.firstOrNull { preferCredit == null || it.type.isLiability == preferCredit }
    }

    /**
     * 提取商品购物数量（如 `"盒马 港式花胶鸡 1.5kg等多件"` -> `(2, "多件")`；`"生椰拿铁 x2"` -> `(2, "2件")`）
     */
    fun extractItemQuantity(goodsValue: String, fullText: String): Pair<Int, String> {
        val target = if (goodsValue.isNotBlank()) goodsValue else fullText
        Regex("""(?:共|总计|合计|数量[:：\s]*)\s*(\d{1,3})\s*(件|份|杯|个|箱|袋|瓶|本|双|单)?""").find(target)?.let { m ->
            val count = m.groupValues[1].toIntOrNull() ?: 1
            val unit = m.groupValues[2].ifBlank { "件" }
            if (count in 1..999) return count to "${count}$unit"
        }
        Regex("""[x×*]\s*(\d{1,2})(?!\d|\.)""").find(target)?.let { m ->
            val count = m.groupValues[1].toIntOrNull() ?: 1
            if (count in 1..99) return count to "${count}件"
        }
        if (target.contains("等多件") || target.contains("多件")) {
            return 2 to "多件"
        }
        return 1 to "1件"
    }

    private fun buildSystemPrompt(
        wallets: List<WalletEntity>,
        recentExpenses: List<TransactionEntity>
    ): String {
        val walletDesc = wallets.joinToString("\n") { w ->
            "- ID=${w.id}, 名称=\"${w.name}\", 类型=${w.type.name}, 币种=${w.currency}, 卡号尾号=${w.cardLast4 ?: "无"}, 别名=[${w.aliases}]"
        }
        val recentDesc = if (recentExpenses.isEmpty()) {
            "暂无近期支出记录"
        } else {
            recentExpenses.joinToString("\n") { tx ->
                val netYuan = (tx.amountCnyCents - tx.refundedAmountCents) / 100.0
                "- 账单ID=${tx.id}, 商户=\"${tx.merchant}\", 分类=\"${tx.category}\", 金额=${netYuan}元, 备注=\"${tx.note}\""
            }
        }
        val memoryDesc = merchantMemoryManager?.memoriesFlow?.value?.take(25)?.joinToString("\n") { m ->
            "- 商户包含\"${m.merchantKeyword}\" -> 优先分类=\"${m.category}\", 常用钱包ID=${m.preferredWalletId ?: "自动"}"
        }.orEmpty().ifBlank { "暂无历史商户偏好" }

        return """
你是一个专业的安卓全自动记账 AI 引擎。请分析用户手机通知栏、无障碍节点或截屏 OCR 抓取到的原始文本，判断是否为真实的已发生资金变动事件，并严格输出 JSON 对象。

【当前用户的钱包列表】：
$walletDesc

【支付账号匹配规则】：
- 如果是银行卡（储蓄卡/信用卡），优先根据后 4 位卡号尾号（如 3354、8848）匹配对应钱包 ID；
- 如果是花呗、白条、零钱、账户余额/余额宝，根据汉字名称或别名匹配对应钱包 ID。

【用户历史商户记忆偏好（若商户命中，100%优先使用该分类）】：
$memoryDesc

【近期可冲抵的消费账单列表（用于匹配退款、报销或朋友AA收款）】：
$recentDesc

【输出 JSON 结构要求】：
{
  "isValidTransaction": true,
  "type": "EXPENSE | INCOME | TRANSFER | REPAYMENT | REFUND_OFFSET",
  "amount": 28.50,
  "currency": "CNY | USD | HKD | EUR | JPY | GBP",
  "itemQuantity": 1,
  "quantityDesc": "1件",
  "category": "分类名称",
  "merchant": "商户或交易对方名称",
  "note": "商品或交易简短摘要",
  "sourceWalletId": 1,
  "targetWalletId": null,
  "relatedExpenseId": null,
  "orderKey": "订单唯一ID（优先取交易单号/订单号，其次商户单号）",
  "transactionNo": "交易单号/订单号/转账单号",
  "merchantOrderNo": "商户单号/商家订单号",
  "paymentMethodRaw": "原始支付方式文本（如 招商银行信用卡(3354) 或 花呗）",
  "confidence": 0.95
}
如果文本只是验证码、广告促销、物流提醒、账单列表页或待确认付款的收银台页面，请返回 {"isValidTransaction": false}。
        """.trimIndent()
    }

    private fun callOpenAiCompatibleApi(
        config: AiConfig,
        rawText: String,
        sourceAppPackage: String,
        wallets: List<WalletEntity>,
        recentExpenses: List<TransactionEntity>
    ): AiParsedTransactionResult? {
        val endpoint = config.baseUrl.trimEnd('/') + "/chat/completions"
        val requestJson = buildJsonObject {
            put("model", config.modelName)
            put("temperature", 0.1)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", buildSystemPrompt(wallets, recentExpenses))
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "来源应用包名: $sourceAppPackage\n捕获到的文本内容:\n$rawText")
                })
            })
        }

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (config.apiKey.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer ${config.apiKey}")
        }

        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val bodyStr = response.body?.string() ?: return null
            val root = json.parseToJsonElement(bodyStr).jsonObject
            val content = root["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: return null
            return parseCleanJsonResult(content)
        }
    }

    private fun callGeminiApi(
        config: AiConfig,
        rawText: String,
        sourceAppPackage: String,
        wallets: List<WalletEntity>,
        recentExpenses: List<TransactionEntity>
    ): AiParsedTransactionResult? {
        val base = config.baseUrl.trimEnd('/')
        val endpoint = "$base/models/${config.modelName}:generateContent?key=${config.apiKey}"

        val requestJson = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", buildSystemPrompt(wallets, recentExpenses)) })
                })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("text", "来源应用包名: $sourceAppPackage\n捕获到的文本内容:\n$rawText")
                        })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", 0.1)
                put("responseMimeType", "application/json")
            })
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bodyStr = response.body?.string() ?: return null
            val root = json.parseToJsonElement(bodyStr).jsonObject
            val content = root["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: return null
            return parseCleanJsonResult(content)
        }
    }

    private fun parseCleanJsonResult(rawContent: String): AiParsedTransactionResult? {
        val cleaned = rawContent.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return runCatching { json.decodeFromString<AiParsedTransactionResult>(cleaned) }.getOrNull()
    }

    /**
     * 内置高智商本地语义解析器：
     * 提取【订单唯一 Key】、【实际购物发生时间】、【金额】、【商品数量】与【支付账号】。
     */
    fun localSmartFallbackParse(
        rawText: String,
        sourceAppPackage: String,
        wallets: List<WalletEntity>,
        recentExpenses: List<TransactionEntity>,
        referenceMillis: Long = System.currentTimeMillis()
    ): AiParsedTransactionResult? {
        // 1. 先截断拼多多/京东/淘宝底部推荐商品瀑布流，修复端侧 OCR 常见形近字，再合并拆分金额（如 "¥ | 29 | .90"、"¥29 | .90"、"¥ | 29. | 90" -> "¥29.90"）
        val strippedText = stripEcommerceRecommendationFooter(rawText)
            .replace(Regex("""\[AI_[A-Z_]+\s*\|\s*[^\]]+\]:\s*"""), "")
            .replace("订单蝙号", "订单编号")
            .replace("订单编亏", "订单编号")
            .replace("支付万式", "支付方式")
            .replace("付款万式", "付款方式")
            .replace("微倌支付", "微信支付")
            .replace(Regex("""(¥|￥)\s*\|?\s*(\d{1,7})\s*\|\s*(\.\d{1,2})"""), "$1$2$3")
            .replace(Regex("""(¥|￥)\s*\|?\s*(\d{1,7})\.\s*\|\s*(\d{1,2})"""), "$1$2.$3")
            .replace(Regex("""(\d{1,7})\s*\|\s*(\.\d{1,2})(?=\s|\||$)"""), "$1$2")
            .replace(Regex("""(\d{1,7})\.\s*\|\s*(\d{1,2})(?=\s|\||$)"""), "$1.$2")
            .replace(Regex("""(¥|￥)\s*\|\s*(\d{1,7}(?:\.\d{1,2})?)"""), "$1$2")
            .trim()
        if (strippedText.contains("验证码") ||
            strippedText.contains("确认付款") ||
            strippedText.contains("立即付款") ||
            strippedText.contains("提交订单") ||
            strippedText.contains("确认订单") ||
            strippedText.contains("请输入支付密码") ||
            (strippedText.contains("设置") && !strippedText.contains("支付") && !strippedText.contains("账单") && !strippedText.contains("订单") && !strippedText.contains("元"))
        ) {
            return null
        }

        val tokens = strippedText
            .split("|", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "---" }

        fun findValueAfterLabel(vararg labels: String): String? {
            for (i in tokens.indices) {
                val tk = tokens[i]
                for (lb in labels) {
                    if (tk.equals(lb, ignoreCase = true)) {
                        val next = tokens.getOrNull(i + 1)?.trim()
                        if (!next.isNullOrBlank() && next !in KNOWN_FIELD_LABELS) {
                            return next
                        }
                    } else if (tk.startsWith("$lb:") || tk.startsWith("$lb：")) {
                        val inlineVal = tk.substringAfter(lb).trimStart(':', '：', ' ').trim()
                        if (inlineVal.isNotBlank()) return inlineVal
                    }
                }
            }
            return null
        }

        val statusValue = findValueAfterLabel("当前状态", "交易状态").orEmpty()
        val explicitGoodsValue = findValueAfterLabel("商品", "商品说明", "转账说明", "转账备注", "收款方备注", "理由", "留言", "备注")
            ?.takeIf { it != "添加" && it != "请选择" && !it.startsWith("使用记账本") }
            .orEmpty()
        val merchantFieldVal = findValueAfterLabel("商户名称", "商户全称", "收款方", "收款方全称", "对方账户", "服务详情", "店铺名称").orEmpty()
        val combinedPayMethod = run {
            val payLabels = setOf("支付方式", "付款方式", "收款方式", "扣款方式", "退款方式", "退回方式", "转入账户")
            var found = ""
            for (i in tokens.indices) {
                val tk = tokens[i]
                if (payLabels.any { tk.equals(it, ignoreCase = true) }) {
                    val parts = mutableListOf<String>()
                    for (j in (i + 1) until minOf(tokens.size, i + 6)) {
                        val nextTk = tokens[j].trim().trimEnd('>', '＞', 'へ', '、', 'v', 'V').trim()
                        if (nextTk.isBlank() || nextTk == "复制" || nextTk in KNOWN_FIELD_LABELS ||
                            nextTk.startsWith("202") || nextTk.all { it.isDigit() } && nextTk.length >= 10
                        ) {
                            break
                        }
                        parts.add(nextTk)
                    }
                    if (parts.isNotEmpty()) {
                        found = parts.joinToString(" ").take(36)
                        break
                    }
                }
            }
            found.ifBlank {
                findValueAfterLabel("支付方式", "付款方式", "收款方式", "扣款方式", "退款方式", "退回方式", "转入账户").orEmpty()
            }.ifBlank {
                when {
                    strippedText.contains("多多支付") -> "多多支付"
                    strippedText.contains("抖音月付") -> "抖音月付"
                    strippedText.contains("抖音支付") -> "抖音支付"
                    strippedText.contains("京东白条") -> "京东白条"
                    strippedText.contains("京东支付") -> "京东支付"
                    strippedText.contains("美团月付") -> "美团月付"
                    strippedText.contains("先用后付") -> "先用后付"
                    else -> ""
                }
            }
        }
        val payMethodVal = combinedPayMethod
        val billCategoryVal = findValueAfterLabel("账单分类", "商品分类").orEmpty()
        val payTimeVal = findValueAfterLabel(
            "支付时间", "付款时间", "下单时间", "拼单时间", "成交时间",
            "转账时间", "收款时间", "创建时间", "到账时间", "退款时间"
        ).orEmpty()

        val extractedTxNo = extractOrderNumberField(
            tokens = tokens,
            fullText = strippedText,
            regex = txNoRegex,
            labels = listOf("交易单号", "订单号", "订单编号", "转账单号", "支付宝交易号", "交易流水号", "流水号")
        )
        val extractedMerchantOrderNo = extractOrderNumberField(
            tokens = tokens,
            fullText = strippedText,
            regex = merchantOrderNoRegex,
            labels = listOf("商户单号", "商家订单号", "商户订单号")
        )
        val orderKey = extractedTxNo.ifBlank { extractedMerchantOrderNo }
        val occurredAtMillis = extractOccurredTimeMillis(
            text = if (payTimeVal.isNotBlank()) "$payTimeVal | $strippedText" else strippedText,
            referenceMillis = referenceMillis,
            orderKey = orderKey
        ) ?: return null

        // 1. 识别币种与【原价 / 优惠 / 实扣】三元组（iCost 模板规范）
        val currency = when {
            strippedText.contains("USD", ignoreCase = true) || strippedText.contains("美元") || (strippedText.contains("$") && !strippedText.contains("HK$")) -> "USD"
            strippedText.contains("HKD", ignoreCase = true) || strippedText.contains("港币") || strippedText.contains("HK$") -> "HKD"
            strippedText.contains("EUR", ignoreCase = true) || strippedText.contains("欧元") || strippedText.contains("€") -> "EUR"
            strippedText.contains("JPY", ignoreCase = true) || strippedText.contains("日元") -> "JPY"
            else -> "CNY"
        }

        val priceBreakdown = extractPriceBreakdown(strippedText, tokens) ?: return null
        val amount = priceBreakdown.actualPaidCents / 100.0
        if (amount <= 0.0) return null

        // 2. 提取电商/支付页面的店铺名与商品名
        val (ecommerceShop, ecommerceGoods) = extractEcommerceShopAndGoods(tokens, sourceAppPackage)
        val goodsValue = explicitGoodsValue.ifBlank { ecommerceGoods }
        val (itemQuantity, quantityDesc) = extractItemQuantity(goodsValue, strippedText)

        val extractedMerchant = extractStructuredMerchant(
            strippedText = strippedText,
            tokens = tokens,
            amountTokenIndex = priceBreakdown.primaryTokenIndex,
            merchantFieldVal = merchantFieldVal.ifBlank { ecommerceShop },
            goodsValue = goodsValue,
            sourceAppPackage = sourceAppPackage,
            fallback = defaultMerchantForPackage(sourceAppPackage)
        )

        // 3. 组建 iCost 模板式结构化备注（包含：原价多少、优惠了多少、实扣多少 + 商品与支付方式）
        val cleanNote = buildCleanNote(
            goodsValue = goodsValue,
            payMethodVal = payMethodVal,
            statusValue = statusValue,
            priceBreakdown = priceBreakdown,
            strippedText = strippedText
        )

        // 4. 信用卡/花呗还款
        if (strippedText.contains("还款成功") || strippedText.contains("还花呗") || strippedText.contains("还信用卡") ||
            statusValue.contains("还款") || extractedMerchant.contains("信用卡还款") || extractedMerchant.contains("花呗还款")
        ) {
            val creditWallet = matchWalletForPayment(payMethodVal, strippedText, sourceAppPackage, wallets, preferCredit = true)
                ?: wallets.firstOrNull { it.type.isLiability }
            val debitWallet = matchWalletForPayment(payMethodVal, strippedText, sourceAppPackage, wallets, preferCredit = false)
                ?: wallets.firstOrNull { !it.type.isLiability }

            return AiParsedTransactionResult(
                isValidTransaction = true,
                type = TransactionType.REPAYMENT.name,
                amount = amount,
                currency = currency,
                itemQuantity = itemQuantity,
                quantityDesc = quantityDesc,
                category = "信用还款",
                merchant = creditWallet?.name ?: "信用账户还款",
                note = cleanNote.ifBlank { "信用账户自动还款" },
                sourceWalletId = debitWallet?.id,
                targetWalletId = creditWallet?.id,
                occurredAtMillis = occurredAtMillis,
                orderKey = orderKey,
                transactionNo = extractedTxNo,
                merchantOrderNo = extractedMerchantOrderNo,
                paymentMethodRaw = payMethodVal,
                confidence = 0.92f
            )
        }

        // 5. 资产内部转账
        if (strippedText.contains("零钱提现") || strippedText.contains("充值到零钱") || strippedText.contains("转入余额宝") ||
            strippedText.contains("转入零钱通") || strippedText.contains("内部转账") || strippedText.contains("提现已发起")
        ) {
            val wechatOrAlipay = wallets.firstOrNull {
                strippedText.contains("微信") && it.name.contains("微信") ||
                    (strippedText.contains("余额宝") || strippedText.contains("支付宝")) && (it.name.contains("支付宝") || it.name.contains("余额"))
            } ?: wallets.firstOrNull { !it.type.isLiability }
            val bankCard = matchWalletForPayment(
                payMethodRaw = payMethodVal,
                fullText = strippedText,
                sourceAppPackage = sourceAppPackage,
                wallets = wallets.filter { !it.type.isLiability && it.id != wechatOrAlipay?.id },
                preferCredit = false
            ) ?: wallets.lastOrNull { !it.type.isLiability && it.id != wechatOrAlipay?.id }

            val isRechargeToDigital = strippedText.contains("充值") || strippedText.contains("转入余额宝") || strippedText.contains("转入零钱通")
            return AiParsedTransactionResult(
                isValidTransaction = true,
                type = TransactionType.TRANSFER.name,
                amount = amount,
                currency = currency,
                itemQuantity = itemQuantity,
                quantityDesc = quantityDesc,
                category = "内部转账",
                merchant = "个人账户间流转",
                note = if (isRechargeToDigital) "银行卡转入数字钱包" else "数字钱包提现至银行卡",
                sourceWalletId = if (isRechargeToDigital) bankCard?.id else wechatOrAlipay?.id,
                targetWalletId = if (isRechargeToDigital) wechatOrAlipay?.id else bankCard?.id,
                occurredAtMillis = occurredAtMillis,
                orderKey = orderKey,
                transactionNo = extractedTxNo,
                merchantOrderNo = extractedMerchantOrderNo,
                paymentMethodRaw = payMethodVal,
                confidence = 0.91f
            )
        }

        // 6. 退款 / 报销 / 朋友 AA 收款冲销
        val textWithoutActionButtons = strippedText
            .replace("申请退款", "")
            .replace("AA收款", "")
            .replace("发起AA", "")
            .replace("报销", "")
        val isRefundEvent = statusValue.contains("退款") ||
            textWithoutActionButtons.contains("退款成功") ||
            textWithoutActionButtons.contains("已全额退款") ||
            textWithoutActionButtons.contains("退款入账") ||
            textWithoutActionButtons.contains("退回") ||
            (priceBreakdown.sign != '-' && (
                textWithoutActionButtons.contains("AA分摊", ignoreCase = true) ||
                    textWithoutActionButtons.contains("收到报销") ||
                    textWithoutActionButtons.contains("报销款")
                ))

        if (isRefundEvent) {
            val receiveWallet = matchWalletForPayment(payMethodVal, strippedText, sourceAppPackage, wallets)
            val targetCents = (amount * 100).roundToLong()
            val matchedExpense = recentExpenses.firstOrNull {
                (orderKey.isNotBlank() && it.orderKey == orderKey) ||
                    it.amountCnyCents == targetCents
            }
            val fallbackCategory = WeightedCategoryClassifier.classifyExpense(
                "$extractedMerchant $goodsValue $billCategoryVal",
                extractedMerchant
            ).first
            return AiParsedTransactionResult(
                isValidTransaction = true,
                type = TransactionType.REFUND_OFFSET.name,
                amount = amount,
                currency = currency,
                itemQuantity = itemQuantity,
                quantityDesc = quantityDesc,
                category = matchedExpense?.category ?: fallbackCategory,
                merchant = if (extractedMerchant != "日常商户") extractedMerchant else "商户退款",
                note = cleanNote.ifBlank { "自动冲抵原消费支出" },
                targetWalletId = receiveWallet?.id,
                relatedExpenseId = matchedExpense?.id,
                occurredAtMillis = occurredAtMillis,
                orderKey = orderKey,
                transactionNo = extractedTxNo,
                merchantOrderNo = extractedMerchantOrderNo,
                paymentMethodRaw = payMethodVal,
                discountCents = priceBreakdown.discountCents,
                confidence = 0.91f
            )
        }

        // 7. 日常收入（仅当金额显式为 + 或状态明确为收款/存入零钱，且不含付款成功/支付成功/支出标记）
        val hasExpenseIndicator = priceBreakdown.sign == '-' ||
            strippedText.contains("付款成功") ||
            strippedText.contains("支付成功") ||
            strippedText.contains("拼单成功") ||
            strippedText.contains("实付款") ||
            strippedText.contains("实付") ||
            strippedText.contains("支出") ||
            strippedText.contains("扣款")
        val isExplicitIncome = !hasExpenseIndicator && (
            priceBreakdown.sign == '+' ||
                statusValue.contains("已存入零钱") ||
                statusValue.contains("已收钱") ||
                statusValue.contains("收款成功") ||
                goodsValue.contains("工资") ||
                goodsValue.contains("薪资") ||
                strippedText.contains("收到红包") ||
                strippedText.contains("二维码收款")
            )

        if (isExplicitIncome) {
            val targetWallet = matchWalletForPayment(payMethodVal, strippedText, sourceAppPackage, wallets, preferCredit = false)
            return AiParsedTransactionResult(
                isValidTransaction = true,
                type = TransactionType.INCOME.name,
                amount = amount,
                currency = currency,
                itemQuantity = itemQuantity,
                quantityDesc = quantityDesc,
                category = if (goodsValue.contains("工资") || goodsValue.contains("薪")) "薪资-工资" else "人情-红包收款",
                merchant = if (extractedMerchant != "日常商户") extractedMerchant else "收款入账",
                note = cleanNote.ifBlank { "自动识别收款入账" },
                targetWalletId = targetWallet?.id,
                occurredAtMillis = occurredAtMillis,
                orderKey = orderKey,
                transactionNo = extractedTxNo,
                merchantOrderNo = extractedMerchantOrderNo,
                paymentMethodRaw = payMethodVal,
                confidence = 0.90f
            )
        }

        // 8. 日常消费支出 (EXPENSE)
        val classifyContext = "$extractedMerchant $goodsValue $billCategoryVal"
        val memoryHit = merchantMemoryManager?.matchMemory(classifyContext, extractedMerchant)
        val explicitWallet = if (currency == "USD") {
            wallets.firstOrNull { it.currency == "USD" }
                ?: matchWalletForPayment(payMethodVal, strippedText, sourceAppPackage, wallets)
        } else {
            val matchedByRule = matchWalletForPayment(payMethodVal, strippedText, sourceAppPackage, wallets)
            val hasExplicitPayHint = payMethodVal.isNotBlank() || wallets.any { w ->
                (!w.cardLast4.isNullOrBlank() && strippedText.contains(w.cardLast4)) ||
                    strippedText.contains(w.name, ignoreCase = true)
            }
            if (hasExplicitPayHint && matchedByRule != null) {
                matchedByRule
            } else {
                memoryHit?.preferredWalletId?.let { pid -> wallets.firstOrNull { it.id == pid } }
                    ?: matchedByRule
            }
        }

        val (weightedCat, weightedConf) = WeightedCategoryClassifier.classifyExpense(classifyContext, extractedMerchant)
        val platformFallbackCat = when (sourceAppPackage) {
            "com.xunmeng.pinduoduo" -> "购物-拼多多"
            "com.jingdong.app.mall" -> "购物-京东"
            "com.taobao.taobao", "com.tmall.wireless" -> "购物-淘宝"
            "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite",
            "com.achievo.vipshop", "com.xingin.xhs" -> "日用"
            else -> weightedCat
        }
        val resolvedWeightedCat = if (weightedConf <= 0.80f && weightedCat == "餐饮") platformFallbackCat else weightedCat
        val finalCategory = memoryHit?.category ?: resolvedWeightedCat
        val finalConfidence = if (memoryHit != null) 0.98f else weightedConf

        return AiParsedTransactionResult(
            isValidTransaction = true,
            type = TransactionType.EXPENSE.name,
            amount = amount,
            currency = currency,
            itemQuantity = itemQuantity,
            quantityDesc = quantityDesc,
            category = finalCategory,
            merchant = extractedMerchant,
            note = cleanNote,
            sourceWalletId = explicitWallet?.id,
            occurredAtMillis = occurredAtMillis,
            orderKey = orderKey,
            transactionNo = extractedTxNo,
            merchantOrderNo = extractedMerchantOrderNo,
            paymentMethodRaw = payMethodVal,
            discountCents = priceBreakdown.discountCents,
            confidence = finalConfidence
        )
    }

    /**
     * iCost 模板式价格解析器：
     * 同时提取【原价 (originalPriceCents)】、【优惠了多少 (discountCents)】与【实扣多少 (actualPaidCents)】。
     * 针对拼多多（如 "使用15元平台满减券,实付:,67.8元,(免运费)"、"单价: 82.8 元"）、抖音支付、京东、淘宝、美团等电商订单详情页，
     * 优先锁定「实付款 / 实付金额 / 实付 / 实扣 / 应付总额」，防止把页面顶部的商品单价或底部推荐流价格误当成实际扣款金额！
     */
    private data class PriceBreakdownInfo(
        val actualPaidCents: Long,
        val originalPriceCents: Long,
        val discountCents: Long,
        val sign: Char?,
        val primaryTokenIndex: Int
    )

    private fun extractPriceBreakdown(
        strippedText: String,
        tokens: List<String>
    ): PriceBreakdownInfo? {
        // 1. 优先查找显式的「实付款 / 实付金额 / 实付 / 实扣 / 实际支付 / 合计实付 / 应付总额 / 应付款 / 合计 / 到手价」
        // 兼容：
        // - 拼多多："实付:,67.8元"
        // - 京东："实付款 | 共减¥22.49 合计¥27.39)" 以及商品区的 "到手¥27.39"
        val actualPaidRegex = Regex(
            """(?:实付款|实付金额|合计实付|实际支付|实扣金额|实扣|实付|应付总额|应付金额|应付款|需付款)(?:\s*(?:\||[:：,，\s])*\s*(?:共减|已减|优惠|已优惠|立减)\s*(?:¥|￥)?\s*[0-9]+(?:\.[0-9]{1,2})?\s*元?)?\s*(?:[:：,，\s]|\||合计|到手价?)*\s*(?:¥|￥|RMB|CNY)?\s*([0-9]{1,7}(?:\.[0-9]{1,2})?)\s*[元)）]?|(?:合计|到手价|到手)\s*(?:[:：,，\s]|\|)*\s*(?:¥|￥|RMB|CNY)\s*([0-9]{1,7}(?:\.[0-9]{1,2})?)\s*[元)）]?"""
        )
        var explicitActualPaidCents = 0L
        var explicitActualTokenIdx = -1

        val actualMatches = actualPaidRegex.findAll(strippedText).toList()
        // 优先取带小数点的匹配（防止 OCR 在某处漏掉小数点变成整数 2739，而另一处 "合计¥27.39" 或 "到手¥27.39" 有精确小数点）
        val bestActualMatch = actualMatches.lastOrNull { m ->
            val g = m.groupValues[1].ifEmpty { m.groupValues[2] }
            g.contains(".")
        } ?: actualMatches.lastOrNull()

        if (bestActualMatch != null) {
            val rawGroup = bestActualMatch.groupValues[1].ifEmpty { bestActualMatch.groupValues[2] }
            val valNum = rawGroup.toDoubleOrNull() ?: 0.0
            if (valNum > 0.0) {
                explicitActualPaidCents = (valNum * 100.0).roundToLong()
                explicitActualTokenIdx = tokens.indexOfFirst {
                    it.contains("实付") || it.contains("实扣") || it.contains("应付") || it.contains("合计") || it.contains("到手")
                }
            }
        }

        // 2. 查找显式的「商品总价 / 商品总额 / 合计补贴价 / 原价 / 单价 / 总价」
        val originalPriceRegex = Regex(
            """(?:商品总价|商品总额|商品金额|订单总价|订单金额|合计补贴价|原价|单价|总价)\s*(?:[:：,，\s]|\|)*\s*(?:¥|￥|RMB|CNY)?\s*([0-9]{1,7}(?:\.[0-9]{1,2})?)\s*元?"""
        )
        var explicitOriginalCents = 0L
        originalPriceRegex.find(strippedText)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { origNum ->
            if (origNum > 0.0 && origNum < 500000.0) {
                explicitOriginalCents = (origNum * 100.0).roundToLong()
            }
        }

        // 3. 提取优惠金额（支持拼多多「使用15元平台满减券」「满减优惠2元」「平台优惠-¥3」及各平台优惠明细）
        val explicitDiscountCents = extractDiscountCents(strippedText, tokens)

        // 4. 提取常规主金额（微信/支付宝账单详情或通知栏金额）
        val fallbackPrimary = extractPrimaryAmount(strippedText, tokens)
        val primaryCents = if (explicitActualPaidCents > 0L) {
            explicitActualPaidCents
        } else {
            val rawAmt = fallbackPrimary?.amount ?: return null
            (rawAmt * 100.0).roundToLong()
        }
        if (primaryCents <= 0L) return null

        // 若页面同时给出了明确的「原价/单价/商品总价」与「实付款」，且 原价 >= 实付款，直接以 (原价 - 实付款) 作为最精确的优惠金额！
        val resolvedDiscountCents: Long
        val resolvedOriginalCents: Long
        if (explicitActualPaidCents > 0L && explicitOriginalCents >= explicitActualPaidCents) {
            resolvedOriginalCents = explicitOriginalCents
            resolvedDiscountCents = explicitOriginalCents - explicitActualPaidCents
        } else if (explicitDiscountCents > 0L) {
            resolvedDiscountCents = explicitDiscountCents
            resolvedOriginalCents = primaryCents + explicitDiscountCents
        } else {
            resolvedDiscountCents = 0L
            resolvedOriginalCents = primaryCents
        }

        return PriceBreakdownInfo(
            actualPaidCents = primaryCents,
            originalPriceCents = resolvedOriginalCents,
            discountCents = resolvedDiscountCents,
            sign = if (explicitActualPaidCents > 0L) '-' else fallbackPrimary?.sign,
            primaryTokenIndex = if (explicitActualTokenIdx >= 0) explicitActualTokenIdx else (fallbackPrimary?.tokenIndex ?: -1)
        )
    }

    private fun extractDiscountCents(strippedText: String, tokens: List<String>): Long {
        // 1. 优先检查是否有「共减 / 已优惠 / 优惠合计 / 总优惠 / 优惠金额 / 满减优惠 / 使用X元...券」（兼容京东 "共减¥22.49" 无“元”字格式）
        val pddCouponDiscountRegex = Regex(
            """(?:共减|已优惠|优惠合计|总优惠|优惠金额|共计优惠|满减优惠)\s*(?:[:：,，\s]|\|)*\s*(?:¥|￥|-|−)?\s*([0-9]{1,5}(?:\.[0-9]{1,2})?)\s*元?|(?:使用|已享)\s*(?:[:：,，\s]|\|)*\s*(?:¥|￥|-|−)?\s*([0-9]{1,5}(?:\.[0-9]{1,2})?)\s*元"""
        )
        pddCouponDiscountRegex.find(strippedText)?.let { m ->
            val rawVal = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val v = rawVal.toDoubleOrNull()
            if (v != null && v > 0.0 && v < 50000.0) {
                return (v * 100.0).roundToLong()
            }
        }

        // 2. 扫描各分项优惠（店铺优惠、平台优惠、百亿补贴、立减、支付优惠、多多支付立减、抖音支付优惠、优惠券、红包抵扣、京豆抵扣、省钱月卡等）
        val itemDiscountRegex = Regex(
            """(?:店铺优惠|平台优惠|百亿补贴|官方立减|多多支付立减|抖音支付优惠|支付优惠|首单立减|优惠券|店铺券|平台券|红包抵扣|代金券|京豆抵扣|京豆|淘金币|省钱月卡|优惠|立减|抵扣|减免)\s*(?:[:：,，\s]|\|)*\s*(?:[-−]\s*)?(?:¥|￥)?\s*(?:[-−]\s*)?([0-9]{1,5}(?:\.[0-9]{1,2})?)\s*元?"""
        )
        var sumCents = 0L
        val seenMatches = mutableSetOf<String>()
        for (m in itemDiscountRegex.findAll(strippedText)) {
            val rawMatch = m.value.trim()
            val valNum = m.groupValues[1].toDoubleOrNull() ?: continue
            if (valNum > 0.0 && valNum < 20000.0 && seenMatches.add(rawMatch)) {
                sumCents += (valNum * 100.0).roundToLong()
            }
        }
        if (sumCents > 0L) return sumCents

        for (i in tokens.indices) {
            val tk = tokens[i]
            if (tk.contains("优惠") || tk.contains("立减") || tk.contains("补贴") || tk.contains("抵扣") || tk.contains("减免")) {
                val combined = "$tk | ${tokens.getOrNull(i + 1).orEmpty()}"
                val m = itemDiscountRegex.find(combined)
                val valNum = m?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                if (valNum != null && valNum > 0.0 && valNum < 20000.0) {
                    return (valNum * 100.0).roundToLong()
                }
            }
        }
        return 0L
    }

    private fun extractOrderNumberField(
        tokens: List<String>,
        fullText: String,
        regex: Regex,
        labels: List<String>
    ): String {
        val codeTokenRegex = Regex("""^[0-9A-Za-z_-]{2,44}$""")
        fun cleanOcrOrderCode(raw: String): String {
            var s = raw.trim()
            if (s.contains("复制")) {
                // 京东订单号与“复制”按钮之间有竖线分隔符 `|`，OCR 常将 `| 复制` 识别为 `1复制`
                s = s.substringBefore("复制").trim()
                if (s.length == 17 && s.endsWith("1") && s.all { it.isDigit() }) {
                    s = s.dropLast(1)
                }
            }
            return s
        }
        for (i in tokens.indices) {
            val tk = tokens[i]
            if (labels.any { tk.equals(it, ignoreCase = true) }) {
                val rawFirstPart = tokens.getOrNull(i + 1)?.trim().orEmpty()
                val firstPart = cleanOcrOrderCode(rawFirstPart)
                if (firstPart.matches(codeTokenRegex) && firstPart.any { it.isDigit() }) {
                    val secondPart = cleanOcrOrderCode(tokens.getOrNull(i + 2)?.trim().orEmpty())
                    val combined = if (
                        !rawFirstPart.contains("复制") &&
                        secondPart.isNotEmpty() &&
                        secondPart.length <= 16 &&
                        secondPart.all { it.isDigit() } &&
                        secondPart !in KNOWN_FIELD_LABELS
                    ) {
                        firstPart + secondPart
                    } else {
                        firstPart
                    }
                    if (combined.length >= 8) return combined
                }
            }
        }
        val fallbackRaw = regex.find(fullText)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (fallbackRaw.length == 17 && fallbackRaw.endsWith("1") && fallbackRaw.all { it.isDigit() } && fullText.contains("${fallbackRaw}复制")) {
            return fallbackRaw.dropLast(1)
        }
        return fallbackRaw
    }

    private data class ExtractedAmountInfo(
        val amount: Double,
        val sign: Char?,
        val tokenIndex: Int
    )

    private fun extractPrimaryAmount(
        strippedText: String,
        tokens: List<String>
    ): ExtractedAmountInfo? {
        val tokenAmountRegex = Regex("""^(?:(支出|付款|消费|收入|收款)\s*)?([-+−])?\s*(?:¥|￥|RMB|CNY|USD|HKD|EUR|JPY|\$)?\s*([0-9]{1,7}(?:\.[0-9]{1,2})?)\s*(?:元)?$""")
        val signOrCurrencyTokens = setOf("-", "+", "−", "¥", "￥", "支出", "收入", "付款", "收款")
        for (i in tokens.indices) {
            val rawTk = tokens[i].replace(",", "").trim()
            val prevTk = tokens.getOrNull(i - 1)?.trim().orEmpty()
            val tk = if (prevTk in signOrCurrencyTokens && rawTk.firstOrNull()?.isDigit() == true) {
                "$prevTk$rawTk"
            } else {
                rawTk
            }
            val hasExplicitMarker = tk.contains(".") || tk.contains("¥") || tk.contains("￥") ||
                tk.contains("元") || tk.startsWith("支出") || tk.startsWith("收入") ||
                tk.startsWith("付款") || tk.startsWith("收款") ||
                tk.startsWith("-") || tk.startsWith("+") || tk.startsWith("−")
            if (!hasExplicitMarker) continue

            if (prevTk.contains("优惠") || prevTk.contains("立减") || prevTk.contains("抵扣") ||
                prevTk.contains("减免") || prevTk.contains("本月支出") || prevTk.contains("累计")
            ) {
                continue
            }
            val m = tokenAmountRegex.matchEntire(tk) ?: continue
            val prefixWord = m.groupValues[1]
            val explicitSign = m.groupValues[2].firstOrNull()?.let { if (it == '−') '-' else it }
            val inferredSign = explicitSign ?: when (prefixWord) {
                "支出", "付款", "消费" -> '-'
                "收入", "收款" -> '+'
                else -> null
            }
            val num = m.groupValues[3].toDoubleOrNull() ?: continue
            if (num > 0.0) {
                return ExtractedAmountInfo(
                    amount = num,
                    sign = inferredSign,
                    tokenIndex = if (prevTk in signOrCurrencyTokens) maxOf(0, i - 1) else i
                )
            }
        }

        val inlineAmountRegex = Regex(
            """(?<!本月)(?:¥|￥|RMB|CNY|USD|HKD|EUR|JPY|HK\$|\$|€|金额[:：]?|付款[:：]?|已支付|支出|消费|转出|到账|还款|退款|收款)\s*([-+−])?\s*([0-9]+(?:\.[0-9]{1,2})?)|([-+−])?\s*([0-9]+(?:\.[0-9]{1,2})?)\s*(?:元|美元|港币|欧元|日元)|(?:^|\||\s)([-+−])\s*([0-9]{1,7}\.[0-9]{1,2})(?=\s|\||$)"""
        )
        for (m in inlineAmountRegex.findAll(strippedText)) {
            val signStr = m.groupValues[1].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[5] }
            val sign = signStr.firstOrNull()?.let { if (it == '−') '-' else it }
            val numStr = m.groupValues[2].ifEmpty { m.groupValues[4] }.ifEmpty { m.groupValues[6] }
            val num = numStr.toDoubleOrNull() ?: continue
            if (num > 0.0) {
                return ExtractedAmountInfo(amount = num, sign = sign, tokenIndex = -1)
            }
        }

        return null
    }

    /**
     * 从拼多多、京东、抖音、淘宝订单详情 token 流中智能提取【店铺名称】与【商品标题】
     */
    private fun extractEcommerceShopAndGoods(
        tokens: List<String>,
        sourceAppPackage: String?
    ): Pair<String, String> {
        var shopCandidate = ""
        var goodsCandidate = ""
        val hexOrAssetRegex = Regex("""^[0-9a-fA-F-]{16,}(\.[a-zA-Z0-9._-]+)?$""")
        val statusNoiseWords = setOf(
            "订单详情", "交易成功", "支付成功", "拼单成功", "待发货", "待收货", "已发货", "已完成", "已签收",
            "待评价", "交易完成", "买家已付款", "等待卖家发货", "商家已接单", "复制", "联系卖家", "联系商家",
            "官方客服", "申请售后", "申请退款", "售后详情", "钱款去向", "退换", "退换/售后", "加入购物车", "加购物车",
            "再来一单", "再次拼单", "再次购买", "查看发票", "退换无忧", "感谢您在京东购物，欢迎您再次光临！",
            "在线支付", "普通快递", "快递运输", "预约配送", "配送服务", "服务保障", "退换货运费险",
            "查看物流", "实付款", "应付款", "应付金额", "商品总价", "商品总额", "分享商品", "购后小助手", "查看使用说明书", "7天价保",
            "7天无理由退货", "正品险", "退货包运费", "退货包运费保障中", "退货包运费已失效", "下单店铺在直播中",
            "直播中", "先用后付", "立享低价", "狂降，火热进行中", "消费提醒", "立即评价", "确认收货", "电子普通发票", "个人"
        )

        fun isNoiseOrAddressToken(s: String): Boolean {
            if (s.length < 2 || s in KNOWN_FIELD_LABELS || s in GENERIC_MERCHANT_BLACKLIST || s in statusNoiseWords) return true
            if (s.contains(".png") || s.contains(".jpg") || s.contains(".webp") || s.contains("%3E") || hexOrAssetRegex.matches(s)) return true
            if (s.startsWith("物流轨迹") || s.startsWith("收货人") || s.startsWith("订单编号") || s.startsWith("交易单号") ||
                s.startsWith("商品快照") || s.startsWith("发生交易争议") || s.startsWith("查看更多") || s.startsWith("多人团") ||
                s.startsWith("狂降") || s.startsWith("退款成功") || s.startsWith("已购规格") || s.startsWith("请在") ||
                s.startsWith("预计") || s.startsWith("感谢您在京东购物") || s.startsWith("回头客") || s.startsWith("回头容") ||
                s.startsWith("无理由退货") || s.startsWith("快速解决") || s.startsWith("帮我催") || s.startsWith("商品降价") ||
                s.startsWith("评价得") || s.startsWith("进店") || s.startsWith("共减") || s.startsWith("0到手") || s.startsWith("到手")
            ) return true
            if (s.contains("好评") || s.contains("价保") || s.contains("双倍赔") || s.contains("退款/售后") ||
                s.contains("判断依据") || s.contains("交易快照") || s.contains("不开发票") || s.contains("微信支付")
            ) return true
            if (s.contains("****") || (s.contains("路") && (s.contains("院") || s.contains("号") || s.contains("门") || s.contains("小区"))) ||
                (s.contains("省") && s.contains("市") && s.contains("区"))
            ) return true
            return false
        }

        // 1. 优先识别拼多多/京东显式带「商品名称：xxx,单价: yyy」的结构化商品节点，并向上回溯提取紧邻的真实店铺名
        val explicitGoodsRegex = Regex("""商品名称[:：]\s*([^,，|]+)""")
        for (i in tokens.indices) {
            val tk = tokens[i]
            val m = explicitGoodsRegex.find(tk)
            if (m != null) {
                goodsCandidate = m.groupValues[1].trim().take(28)
                for (backIdx in (i - 1) downTo maxOf(0, i - 4)) {
                    val prev = tokens[backIdx].trim().removeSuffix(">").removeSuffix("＞").trim()
                    if (prev.length in 2..22 && !isNoiseOrAddressToken(prev) && !prev.contains("实付") && !prev.contains("单价")) {
                        shopCandidate = prev
                        break
                    }
                }
                break
            }
        }

        // 2. 若未命中显式「商品名称：」，扫描常规电商店铺名与商品标题
        for (tk in tokens) {
            val clean = tk.trim().removeSuffix(">").removeSuffix("＞").trim()
            if (isNoiseOrAddressToken(clean)) continue

            if (shopCandidate.isBlank() && clean.length in 2..24 && (
                    clean.endsWith("旗舰店") || clean.endsWith("专卖店") || clean.endsWith("专营店") ||
                        clean.endsWith("自营店") || clean.endsWith("官方店") || clean.endsWith("杂货铺") ||
                        clean.endsWith("优选店") || clean.endsWith("小店") || clean.endsWith("超市") ||
                        clean.contains("京东自营") || clean.endsWith("自营") || clean.startsWith("百亿补贴·")
                    )
            ) {
                shopCandidate = clean
                continue
            }

            if (goodsCandidate.isBlank() &&
                clean.length in 6..50 &&
                !clean.startsWith("202") &&
                !clean.all { it.isDigit() || it == '.' || it == '-' || it == ':' || it == '¥' || it == '￥' } &&
                clean.count { it.isDigit() } < clean.length / 2
            ) {
                if (clean != shopCandidate && !clean.contains("实付") && !clean.contains("优惠") && !clean.contains("联系方式")) {
                    goodsCandidate = clean.take(28)
                }
            }
        }
        return shopCandidate to goodsCandidate
    }

    private fun defaultMerchantForPackage(sourceAppPackage: String?): String = when (sourceAppPackage) {
        "com.xunmeng.pinduoduo" -> "拼多多"
        "com.jingdong.app.mall" -> "京东"
        "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite" -> "抖音商城"
        "com.taobao.taobao" -> "淘宝"
        "com.tmall.wireless" -> "天猫"
        "com.sankuai.meituan", "com.sankuai.meituan.takeoutnew" -> "美团"
        "me.ele" -> "饿了么"
        "com.achievo.vipshop" -> "唯品会"
        "com.xingin.xhs" -> "小红书"
        else -> "日常商户"
    }

    private fun extractStructuredMerchant(
        strippedText: String,
        tokens: List<String>,
        amountTokenIndex: Int,
        merchantFieldVal: String,
        goodsValue: String,
        sourceAppPackage: String?,
        fallback: String
    ): String {
        if (isCleanMerchantCandidate(merchantFieldVal)) {
            return merchantFieldVal
        }

        var titleBeforeAmount: String? = null
        if (amountTokenIndex > 0) {
            for (idx in (amountTokenIndex - 1) downTo maxOf(0, amountTokenIndex - 3)) {
                val candidate = tokens[idx]
                    .removePrefix("转账-转给")
                    .removePrefix("转账-来自")
                    .removePrefix("扫二维码付款-给")
                    .removePrefix("二维码收款-来自")
                    .trim()
                if (isCleanMerchantCandidate(candidate)) {
                    titleBeforeAmount = candidate
                    break
                }
            }
        }

        if (!titleBeforeAmount.isNullOrBlank()) {
            return titleBeforeAmount
        }

        val patterns = listOf(
            Regex("""(?:商户名称|商户全称|店铺名称|卖家名称|商家名称|收款方|向商户|向|在|商户)[:：\s]*【?([^\s|,，。；;】\n]{2,22})】?"""),
            Regex("""微信支付[:：]\s*([^\s|,，。；;\n]{2,18})""")
        )
        for (p in patterns) {
            for (m in p.findAll(strippedText)) {
                val candidate = m.groupValues[1].trim()
                if (isCleanMerchantCandidate(candidate)) {
                    return candidate
                }
            }
        }

        merchantMemoryManager?.memoriesFlow?.value?.firstOrNull {
            strippedText.contains(it.merchantKeyword, ignoreCase = true)
        }?.let { return it.merchantKeyword }

        val knownMerchants = listOf(
            "瑞幸咖啡", "星巴克", "Manner", "霸王茶姬", "喜茶", "奈雪", "蜜雪冰城", "古茗", "茶百道",
            "盒马", "山姆会员商店", "叮咚买菜", "朴朴超市", "唐久", "全家", "罗森", "7-Eleven", "美宜佳",
            "美团外卖", "美团", "饿了么", "麦当劳", "肯德基", "海底捞", "萨莉亚", "必胜客",
            "滴滴出行", "高德打车", "12306", "山东高速", "中国石化", "中国石油", "哈啰",
            "京东自营", "京东", "拼多多", "抖音商城", "抖音支付", "淘宝", "天猫", "唯品会", "优衣库", "GitHub", "OpenAI"
        )
        knownMerchants.firstOrNull { strippedText.contains(it, ignoreCase = true) }?.let { return it }

        if (isCleanMerchantCandidate(goodsValue)) {
            return goodsValue.take(16)
        }

        return fallback
    }

    private fun isCleanMerchantCandidate(candidate: String): Boolean {
        val clean = candidate.trim()
        if (clean.length < 2 || clean.length > 26) return false
        if (clean in KNOWN_FIELD_LABELS || clean in GENERIC_MERCHANT_BLACKLIST) return false
        if (clean.all { it.isDigit() || it == '.' || it == '-' || it == ':' }) return false
        if (clean.startsWith("202") && clean.contains(":")) return false
        if (clean.startsWith("使用") || clean.startsWith("通过") || clean.startsWith("付款金额") || clean.startsWith("狂降")) return false
        if (clean.contains(".png") || clean.contains(".jpg") || clean.contains("%3E") || clean.contains("****")) return false
        if (clean.contains("免密支付") || clean.contains("信用卡(") || clean.contains("储蓄卡(") || clean.contains("尾号")) return false
        if (clean.contains("商品总价") || clean.contains("实付款") || clean.contains("实付") || clean.contains("已优惠")) return false
        val merchantNoiseBlacklist = setOf(
            "门卫代收", "退货包运费", "退货包运费保障中", "退货包运费已失效", "正品险", "7天价保",
            "7天无理由退货", "下单店铺在直播中", "直播中", "先用后付", "分享商品", "申请退款",
            "售后详情", "钱款去向", "联系商家", "购后小助手", "查看使用说明书", "立享低价"
        )
        if (clean in merchantNoiseBlacklist) return false
        return true
    }

    /**
     * 对标 iCost 模板记录格式：
     * 包含【原价 · 优惠了多少 · 实扣多少】+ 商品说明 + 支付方式
     */
    private fun buildCleanNote(
        goodsValue: String,
        payMethodVal: String,
        statusValue: String,
        priceBreakdown: PriceBreakdownInfo,
        strippedText: String
    ): String {
        val origStr = String.format(Locale.CHINA, "%.2f", priceBreakdown.originalPriceCents / 100.0)
        val discStr = String.format(Locale.CHINA, "%.2f", priceBreakdown.discountCents / 100.0)
        val paidStr = String.format(Locale.CHINA, "%.2f", priceBreakdown.actualPaidCents / 100.0)
        val templateHeader = "原价¥$origStr · 优惠¥$discStr · 实扣¥$paidStr"

        val parts = mutableListOf<String>()
        if (goodsValue.isNotBlank() && goodsValue.length <= 36) {
            parts.add(goodsValue)
        }
        if (payMethodVal.isNotBlank() && payMethodVal.length <= 24) {
            parts.add(payMethodVal)
        }
        val detailSuffix = parts.joinToString(" · ")
        return if (detailSuffix.isNotBlank()) {
            "$templateHeader | $detailSuffix".take(96)
        } else {
            templateHeader
        }
    }

    private fun defaultCategoryForType(type: TransactionType): String = when (type) {
        TransactionType.EXPENSE -> "餐饮"
        TransactionType.INCOME -> "其他收入"
        TransactionType.TRANSFER -> "内部转账"
        TransactionType.REPAYMENT -> "信用还款"
        TransactionType.REFUND_OFFSET -> "退款入账"
        TransactionType.LOAN_IN -> "借入"
        TransactionType.LOAN_OUT -> "借出"
    }
}
