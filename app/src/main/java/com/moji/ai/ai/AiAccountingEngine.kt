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
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

@Serializable
data class AiParsedTransactionResult(
    val isValidTransaction: Boolean = false,
    val type: String = "EXPENSE",               // EXPENSE, INCOME, TRANSFER, REPAYMENT, REFUND_OFFSET
    val amount: Double = 0.0,                   // 金额（元）
    val currency: String = "CNY",               // 币种代码 CNY, USD, HKD, EUR, JPY, GBP
    val category: String = "日常-其他",
    val merchant: String = "",
    val note: String = "",
    val sourceWalletId: Long? = null,
    val targetWalletId: Long? = null,
    val relatedExpenseId: Long? = null,         // 若是退款或朋友AA收款，关联的原支出ID
    val confidence: Float = 0.9f
)

/**
 * 核心 AI 记账大脑：
 * 1. 支持 OpenAI 兼容接口（DeepSeek / 通义千问 / 硅基流动 / Ollama）及 Google Gemini 接口。
 * 2. 动态把用户的【钱包列表（含尾号、别名）】与【近期可冲销消费账单】注入 Prompt。
 * 3. 内置智能本地语义引擎作为兜底（无网络或未填 API Key 时也能秒级解析微信/支付宝账单详情页、支付成功页、通知与端侧 OCR 文本）。
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
            "返回", "关闭", "更多", "完成", "账单详情", "交易详情",
            "当前状态", "交易状态", "支付成功", "付款成功", "交易成功",
            "对方已收钱", "已收钱", "已存入零钱", "已转账", "退款成功", "已全额退款",
            "商品", "商品说明", "转账说明", "收款方备注", "留言", "备注",
            "商户名称", "商户全称", "收款方", "收款方全称", "对方账户", "收单机构",
            "支付方式", "付款方式", "收款方式", "扣款方式", "退款方式", "转入账户",
            "支付时间", "转账时间", "收款时间", "创建时间", "到账时间", "退款时间",
            "交易单号", "商户单号", "转账单号", "订单号", "商家订单号",
            "账单服务", "对订单有疑惑", "定位到此交易", "在此商户的交易",
            "申请退款", "再来一单", "查看往来记录", "投诉", "常见问题"
        )

        private val GENERIC_MERCHANT_BLACKLIST = setOf(
            "微信支付", "微信", "支付宝", "云闪付", "招商银行", "工商银行", "建设银行",
            "中国银行", "农业银行", "交通银行", "账单详情", "交易详情", "支付成功",
            "付款成功", "交易成功", "当前状态", "交易状态", "返回", "完成", "更多",
            "个人", "商户", "消费", "支出", "收入", "二维码收款", "扫二维码付款"
        )
    }

    suspend fun parseCapturedText(
        rawText: String,
        sourceAppPackage: String,
        captureMode: String,
        wallets: List<WalletEntity>,
        recentExpenses: List<TransactionEntity>
    ): TransactionEntity? = withContext(Dispatchers.IO) {
        val config = configManager.getConfig()

        val parsed: AiParsedTransactionResult? = if (
            config.apiKey.isNotBlank() ||
            config.baseUrl.contains("127.0.0.1") ||
            config.baseUrl.contains("10.0.2.2") ||
            config.baseUrl.contains("localhost")
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
            localSmartFallbackParse(rawText, sourceAppPackage, wallets, recentExpenses)
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

        TransactionEntity(
            type = txType,
            amountCents = amountCents,
            currency = currencyCode.name,
            exchangeRateToCny = currencyCode.defaultRateToCny,
            amountCnyCents = amountCnyCents,
            category = finalResult.category.ifBlank { defaultCategoryForType(txType) },
            merchant = finalResult.merchant,
            note = finalResult.note,
            sourceWalletId = finalResult.sourceWalletId,
            targetWalletId = finalResult.targetWalletId,
            relatedTransactionId = finalResult.relatedExpenseId,
            captureSource = captureMode,
            rawCapturedText = rawText.take(600),
            aiConfidence = finalResult.confidence
        )
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
你是一个专业的安卓全自动记账 AI 引擎。请分析用户手机通知栏、无障碍节点或截屏 OCR 抓取到的原始文本，判断是否为真实的资金变动事件，并严格输出 JSON 对象（不要包含 Markdown 代码块包裹）。

【当前用户的钱包列表】：
$walletDesc

【用户历史商户记忆偏好（若商户命中，100%优先使用该分类）】：
$memoryDesc

【近期可冲抵的消费账单列表（用于匹配退款、报销或朋友AA收款）】：
$recentDesc

【5种交易类型 (type) 判定规则】：
1. EXPENSE（支出）：日常消费付款、扫码支付、外卖、打车、网购扣款。sourceWalletId 填付款钱包 ID。
2. INCOME（收入）：发工资、奖金、理财收益、无关联消费的普通收款。targetWalletId 填收款钱包 ID。
3. TRANSFER（内部转账）：用户自己的银行卡充值到微信零钱、零钱提现到银行卡、余额宝转入转出等。sourceWalletId 填转出钱包 ID，targetWalletId 填转入钱包 ID。
4. REPAYMENT（信用还款）：用储蓄卡/微信/支付宝偿还信用卡、花呗、白条账单。sourceWalletId 填扣款资产钱包 ID，targetWalletId 填被还款的信用账户钱包 ID。
5. REFUND_OFFSET（退款/报销/AA冲销）：商户退款到账、退货退款、同事/朋友转账分摊AA餐费或报销。targetWalletId 填收款钱包 ID，并在近期消费列表中找到最匹配的 relatedExpenseId。

【标准分类参考】：
餐饮-正餐、餐饮-咖啡茶饮、交通-打车公交、购物-日用百货、购物-数码电器、娱乐-休闲、居住-房租水电、医疗-健康、人情-红包往来、薪资-工资、内部转账、信用还款、退款冲销。

【输出 JSON 结构要求】：
{
  "isValidTransaction": true,
  "type": "EXPENSE | INCOME | TRANSFER | REPAYMENT | REFUND_OFFSET",
  "amount": 28.50,
  "currency": "CNY | USD | HKD | EUR | JPY | GBP",
  "category": "分类名称",
  "merchant": "商户或交易对方名称",
  "note": "商品或交易简短摘要",
  "sourceWalletId": 1,
  "targetWalletId": null,
  "relatedExpenseId": null,
  "confidence": 0.95
}
如果文本只是验证码、广告促销、物流提醒或未完成的支付页面，请返回 {"isValidTransaction": false}。
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
     * 针对微信/支付宝「账单详情」「交易详情」「支付成功」、系统通知栏及端侧 OCR 的竖线分隔节点做结构化键值提取。
     */
    fun localSmartFallbackParse(
        rawText: String,
        sourceAppPackage: String,
        wallets: List<WalletEntity>,
        recentExpenses: List<TransactionEntity>
    ): AiParsedTransactionResult? {
        val strippedText = rawText.replace(Regex("""\[AI_[A-Z_]+\s*\|\s*[^\]]+\]:\s*"""), "").trim()
        if (strippedText.contains("验证码") ||
            (strippedText.contains("设置") && !strippedText.contains("支付") && !strippedText.contains("账单") && !strippedText.contains("元"))
        ) {
            return null
        }

        // 将无障碍/OCR 的 " | " 分隔节点拆为有序 token 列表
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
        val goodsValue = findValueAfterLabel("商品", "商品说明", "转账说明", "收款方备注", "留言", "备注").orEmpty()
        val merchantFieldVal = findValueAfterLabel("商户名称", "商户全称", "收款方", "收款方全称", "对方账户").orEmpty()
        val payMethodVal = findValueAfterLabel("支付方式", "付款方式", "收款方式", "扣款方式", "退款方式", "转入账户").orEmpty()
        val billCategoryVal = findValueAfterLabel("账单分类", "商品分类").orEmpty()

        // 1. 识别币种与主金额（兼顾带 ¥/元 金额、支付宝「支出12.9元」与微信/支付宝账单详情页的独立正负小数如 "-28.50" / "+100.00"）
        val currency = when {
            strippedText.contains("USD", ignoreCase = true) || strippedText.contains("美元") || (strippedText.contains("$") && !strippedText.contains("HK$")) -> "USD"
            strippedText.contains("HKD", ignoreCase = true) || strippedText.contains("港币") || strippedText.contains("HK$") -> "HKD"
            strippedText.contains("EUR", ignoreCase = true) || strippedText.contains("欧元") || strippedText.contains("€") -> "EUR"
            strippedText.contains("JPY", ignoreCase = true) || strippedText.contains("日元") -> "JPY"
            else -> "CNY"
        }

        val extractedAmount = extractPrimaryAmount(strippedText, tokens) ?: return null
        val amount = extractedAmount.amount
        if (amount <= 0.0) return null

        // 2. 提取商户名称（优先取账单详情主金额上方标题 / 商户全称字段 / 通知括号）
        val extractedMerchant = extractStructuredMerchant(
            strippedText = strippedText,
            tokens = tokens,
            amountTokenIndex = extractedAmount.tokenIndex,
            merchantFieldVal = merchantFieldVal,
            goodsValue = goodsValue,
            fallback = "日常商户"
        )

        // 3. 组建干净的备注（优先使用商品说明 + 支付方式，避免把原始节点树前缀写入备注）
        val cleanNote = buildCleanNote(
            goodsValue = goodsValue,
            payMethodVal = payMethodVal,
            statusValue = statusValue,
            strippedText = strippedText
        )

        // 4. 匹配关联的钱包（优先根据「支付方式 / 付款方式」字段匹配，其次全文匹配，最后按包名兜底）
        fun matchWalletByHint(text: String, preferCredit: Boolean? = null): WalletEntity? {
            val searchPool = if (payMethodVal.isNotBlank()) "$payMethodVal | $text" else text

            // 先按支付方式中的银行卡后四位精准匹配
            if (payMethodVal.isNotBlank()) {
                for (w in wallets) {
                    if (!w.cardLast4.isNullOrBlank() && payMethodVal.contains(w.cardLast4)) {
                        if (preferCredit == null || w.type.isLiability == preferCredit) return w
                    }
                }
                for (w in wallets) {
                    val names = listOf(w.name) + w.aliases.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
                    if (names.any { payMethodVal.contains(it, ignoreCase = true) } ||
                        (payMethodVal.contains("零钱") && w.name.contains("微信")) ||
                        ((payMethodVal.contains("余额") || payMethodVal.contains("花呗")) && w.name.contains("支付宝"))
                    ) {
                        if (preferCredit == null || w.type.isLiability == preferCredit) return w
                    }
                }
            }

            // 再按全文银行卡后四位匹配
            for (w in wallets) {
                if (!w.cardLast4.isNullOrBlank() && searchPool.contains(w.cardLast4)) {
                    if (preferCredit == null || w.type.isLiability == preferCredit) return w
                }
            }
            // 再按名称与别名匹配
            for (w in wallets) {
                val names = listOf(w.name) + w.aliases.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
                if (names.any { searchPool.contains(it, ignoreCase = true) }) {
                    if (preferCredit == null || w.type.isLiability == preferCredit) return w
                }
            }
            // 按来源 App 包名兜底
            if (sourceAppPackage.contains("tencent.mm")) {
                return wallets.firstOrNull { it.name.contains("微信") }
            }
            if (sourceAppPackage.contains("eg.android.AlipayGphone")) {
                return wallets.firstOrNull { it.name.contains("支付宝") || it.name.contains("余额宝") }
            }
            return wallets.firstOrNull { preferCredit == null || it.type.isLiability == preferCredit }
        }

        // 5. 判断是否为【信用卡/花呗还款】
        if (strippedText.contains("还款成功") || strippedText.contains("还花呗") || strippedText.contains("还信用卡") ||
            statusValue.contains("还款") || extractedMerchant.contains("信用卡还款") || extractedMerchant.contains("花呗还款")
        ) {
            val creditWallet = matchWalletByHint(strippedText, preferCredit = true)
                ?: wallets.firstOrNull { it.type.isLiability }
            val debitWallet = wallets.firstOrNull {
                !it.type.isLiability && (
                    (!it.cardLast4.isNullOrBlank() && strippedText.contains(it.cardLast4)) ||
                        strippedText.contains(it.name.take(4))
                    )
            } ?: wallets.firstOrNull { !it.type.isLiability }

            return AiParsedTransactionResult(
                isValidTransaction = true,
                type = TransactionType.REPAYMENT.name,
                amount = amount,
                currency = currency,
                category = "信用还款",
                merchant = creditWallet?.name ?: "信用账户还款",
                note = cleanNote.ifBlank { "信用账户自动还款" },
                sourceWalletId = debitWallet?.id,
                targetWalletId = creditWallet?.id,
                confidence = 0.92f
            )
        }

        // 6. 判断是否为【资产内部转账（提现/充值/转入零钱通/余额宝）】
        if (strippedText.contains("零钱提现") || strippedText.contains("充值到零钱") || strippedText.contains("转入余额宝") ||
            strippedText.contains("转入零钱通") || strippedText.contains("内部转账") || strippedText.contains("提现已发起")
        ) {
            val wechatOrAlipay = wallets.firstOrNull {
                strippedText.contains("微信") && it.name.contains("微信") ||
                    (strippedText.contains("余额宝") || strippedText.contains("支付宝")) && it.name.contains("支付宝")
            } ?: wallets.firstOrNull { !it.type.isLiability }
            val bankCard = wallets.firstOrNull {
                !it.type.isLiability && it.id != wechatOrAlipay?.id &&
                    ((!it.cardLast4.isNullOrBlank() && strippedText.contains(it.cardLast4)) || it.name.contains("储蓄") || it.name.contains("银行"))
            } ?: wallets.lastOrNull { !it.type.isLiability && it.id != wechatOrAlipay?.id }

            val isRechargeToDigital = strippedText.contains("充值") || strippedText.contains("转入余额宝") || strippedText.contains("转入零钱通")
            return AiParsedTransactionResult(
                isValidTransaction = true,
                type = TransactionType.TRANSFER.name,
                amount = amount,
                currency = currency,
                category = "内部转账",
                merchant = "个人账户间流转",
                note = if (isRechargeToDigital) "银行卡转入数字钱包" else "数字钱包提现至银行卡",
                sourceWalletId = if (isRechargeToDigital) bankCard?.id else wechatOrAlipay?.id,
                targetWalletId = if (isRechargeToDigital) wechatOrAlipay?.id else bankCard?.id,
                confidence = 0.91f
            )
        }

        // 7. 判断是否为【退款 / 报销 / 朋友 AA 收款冲销】（排除详情页底部按钮「申请退款 / AA收款 / 报销」）
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
            (extractedAmount.sign != '-' && (
                textWithoutActionButtons.contains("AA分摊", ignoreCase = true) ||
                    textWithoutActionButtons.contains("收到报销") ||
                    textWithoutActionButtons.contains("报销款")
                ))

        if (isRefundEvent) {
            val receiveWallet = matchWalletByHint(strippedText)
            val matchedExpense = recentExpenses.firstOrNull {
                (it.amountCnyCents - it.refundedAmountCents) >= (amount * 100).roundToLong()
            }
            return AiParsedTransactionResult(
                isValidTransaction = true,
                type = TransactionType.REFUND_OFFSET.name,
                amount = amount,
                currency = currency,
                category = matchedExpense?.category ?: "退款冲销",
                merchant = if (extractedMerchant != "日常商户") extractedMerchant else "商户退款",
                note = cleanNote.ifBlank { "自动冲抵原消费支出" },
                targetWalletId = receiveWallet?.id,
                relatedExpenseId = matchedExpense?.id,
                confidence = 0.91f
            )
        }

        // 8. 判断是否为【日常收入（工资/收款/红包入账/账单详情正号金额）】
        val isExplicitIncome = extractedAmount.sign == '+' ||
            statusValue.contains("已存入零钱") ||
            statusValue.contains("已收钱") ||
            statusValue.contains("收款成功") ||
            ((strippedText.contains("工资") || strippedText.contains("薪资") || strippedText.contains("收到红包") || strippedText.contains("收款成功") || strippedText.contains("二维码收款")) &&
                extractedAmount.sign != '-' && !strippedText.contains("支付成功") && !strippedText.contains("消费"))

        if (isExplicitIncome) {
            val targetWallet = matchWalletByHint(strippedText, preferCredit = false)
            return AiParsedTransactionResult(
                isValidTransaction = true,
                type = TransactionType.INCOME.name,
                amount = amount,
                currency = currency,
                category = if (strippedText.contains("工资") || strippedText.contains("薪")) "薪资-工资" else "人情-红包收款",
                merchant = if (extractedMerchant != "日常商户") extractedMerchant else "收款入账",
                note = cleanNote.ifBlank { "自动识别收款入账" },
                targetWalletId = targetWallet?.id,
                confidence = 0.90f
            )
        }

        // 9. 默认判定为【日常消费支出 (EXPENSE)】：结合商户记忆自学习 + 支付宝/微信原生账单分类 + 带排除词权重分类器
        val classifyContext = "$extractedMerchant $goodsValue $billCategoryVal $strippedText"
        val memoryHit = merchantMemoryManager?.matchMemory(classifyContext, extractedMerchant)
        val explicitWallet = if (currency == "USD") {
            wallets.firstOrNull { it.currency == "USD" } ?: matchWalletByHint(strippedText)
        } else {
            val hasExplicitHint = payMethodVal.isNotBlank() || wallets.any { w ->
                (!w.cardLast4.isNullOrBlank() && strippedText.contains(w.cardLast4)) ||
                    strippedText.contains(w.name, ignoreCase = true)
            }
            if (hasExplicitHint) {
                matchWalletByHint(strippedText)
            } else {
                memoryHit?.preferredWalletId?.let { pid -> wallets.firstOrNull { it.id == pid } }
                    ?: matchWalletByHint(strippedText)
            }
        }

        val (weightedCat, weightedConf) = WeightedCategoryClassifier.classifyExpense(classifyContext, extractedMerchant)
        val finalCategory = memoryHit?.category ?: weightedCat
        val finalConfidence = if (memoryHit != null) 0.98f else weightedConf

        return AiParsedTransactionResult(
            isValidTransaction = true,
            type = TransactionType.EXPENSE.name,
            amount = amount,
            currency = currency,
            category = finalCategory,
            merchant = extractedMerchant,
            note = cleanNote,
            sourceWalletId = explicitWallet?.id,
            confidence = finalConfidence
        )
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
        // 1. 先在 tokens 中寻找微信/支付宝「账单详情」或「支付成功」顶部的独立主金额节点
        //    例如 "-28.50"、"+100.00"、"¥28.50"、"￥28.50"、"28.50元"、"支出12.9元"、"收入1.00元"
        val tokenAmountRegex = Regex("""^(?:(支出|付款|消费|收入|收款)\s*)?([-+−])?\s*(?:¥|￥|RMB|CNY|USD|HKD|EUR|JPY|\$)?\s*([0-9]{1,7}(?:\.[0-9]{1,2})?)\s*(?:元)?$""")
        for (i in tokens.indices) {
            val tk = tokens[i].replace(",", "").trim()
            // 必须是带小数点、或带货币符号/元/支出/收入前缀的 token，避免把纯整数（如积分 "2"）当成金额
            val hasExplicitMarker = tk.contains(".") || tk.contains("¥") || tk.contains("￥") ||
                tk.contains("元") || tk.startsWith("支出") || tk.startsWith("收入") ||
                tk.startsWith("-") || tk.startsWith("+")
            if (!hasExplicitMarker) continue

            val prevTk = tokens.getOrNull(i - 1).orEmpty()
            // 排除优惠抵扣行（如 "优惠 | -¥0.20" 或 "立减 | -1.00"）
            if (prevTk.contains("优惠") || prevTk.contains("立减") || prevTk.contains("抵扣") || prevTk.contains("减免")) {
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
                return ExtractedAmountInfo(amount = num, sign = inferredSign, tokenIndex = i)
            }
        }

        // 2. 在全文中匹配带货币符号/关键词的金额
        val inlineAmountRegex = Regex(
            """(?:¥|￥|RMB|CNY|USD|HKD|EUR|JPY|HK\$|\$|€|金额[:：]?|付款[:：]?|已支付|支出|消费|转出|到账|还款|退款|收款)\s*([-+−])?\s*([0-9]+(?:\.[0-9]{1,2})?)|([-+−])?\s*([0-9]+(?:\.[0-9]{1,2})?)\s*(?:元|美元|港币|欧元|日元)"""
        )
        for (m in inlineAmountRegex.findAll(strippedText)) {
            val signStr = m.groupValues[1].ifEmpty { m.groupValues[3] }
            val sign = signStr.firstOrNull()?.let { if (it == '−') '-' else it }
            val numStr = m.groupValues[2].ifEmpty { m.groupValues[4] }
            val num = numStr.toDoubleOrNull() ?: continue
            if (num > 0.0) {
                return ExtractedAmountInfo(amount = num, sign = sign, tokenIndex = -1)
            }
        }

        return null
    }

    private fun extractStructuredMerchant(
        strippedText: String,
        tokens: List<String>,
        amountTokenIndex: Int,
        merchantFieldVal: String,
        goodsValue: String,
        fallback: String
    ): String {
        // 1. 微信/支付宝「账单详情」或「支付成功」页面中，紧邻主金额上方的标题往往就是最直观的商户名
        //    例如：["返回", "账单详情", "瑞幸咖啡", "-9.90", "当前状态", "支付成功"]
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

        // 2. 如果标题是泛化词（如“扫二维码付款”），优先使用「商户全称 / 收款方」字段值
        if (!titleBeforeAmount.isNullOrBlank()) {
            return titleBeforeAmount
        }
        if (isCleanMerchantCandidate(merchantFieldVal)) {
            return merchantFieldVal
        }

        // 3. 通知栏/短信正则提取
        val patterns = listOf(
            Regex("""(?:商户名称|商户全称|收款方|向商户|向|在|商户)[:：\s]*【?([^\s|,，。；;】\n]{2,22})】?"""),
            Regex("""微信支付[:：]\s*([^\s|,，。；;\n]{2,18})"""),
            Regex("""【([^】]{2,16})】""")
        )
        for (p in patterns) {
            for (m in p.findAll(strippedText)) {
                val candidate = m.groupValues[1].trim()
                if (isCleanMerchantCandidate(candidate)) {
                    return candidate
                }
            }
        }

        // 4. 历史商户记忆与知名品牌词典匹配
        merchantMemoryManager?.memoriesFlow?.value?.firstOrNull {
            strippedText.contains(it.merchantKeyword, ignoreCase = true)
        }?.let { return it.merchantKeyword }

        val knownMerchants = listOf(
            "瑞幸咖啡", "星巴克", "Manner", "霸王茶姬", "喜茶", "奈雪", "蜜雪冰城", "古茗", "茶百道",
            "盒马", "山姆会员商店", "叮咚买菜", "朴朴超市", "唐久", "全家", "罗森", "7-Eleven", "美宜佳",
            "美团外卖", "美团", "饿了么", "麦当劳", "肯德基", "海底捞", "萨莉亚", "必胜客",
            "滴滴出行", "高德打车", "12306", "中国石化", "中国石油", "哈啰",
            "京东", "拼多多", "淘宝", "天猫", "优衣库", "GitHub", "OpenAI"
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
        return true
    }

    private fun buildCleanNote(
        goodsValue: String,
        payMethodVal: String,
        statusValue: String,
        strippedText: String
    ): String {
        val parts = mutableListOf<String>()
        if (goodsValue.isNotBlank() && goodsValue.length <= 36) {
            parts.add(goodsValue)
        }
        if (payMethodVal.isNotBlank() && payMethodVal.length <= 24) {
            parts.add(payMethodVal)
        }
        if (parts.isNotEmpty()) {
            return parts.joinToString(" · ").take(45)
        }
        if (statusValue.isNotBlank()) {
            return statusValue.take(30)
        }
        return strippedText
            .replace("|", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(42)
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
