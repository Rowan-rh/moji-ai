package com.moji.ai.ai

import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * 3. 内置智能本地语义引擎作为兜底（无网络或未填 API Key 时也能秒级解析消费、转账、信用卡还款、AA收款与外币）。
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

    suspend fun parseCapturedText(
        rawText: String,
        sourceAppPackage: String,
        captureMode: String,
        wallets: List<WalletEntity>,
        recentExpenses: List<TransactionEntity>
    ): TransactionEntity? = withContext(Dispatchers.IO) {
        val config = configManager.getConfig()

        val parsed: AiParsedTransactionResult? = if (config.apiKey.isNotBlank() || config.baseUrl.contains("127.0.0.1") || config.baseUrl.contains("10.0.2.2") || config.baseUrl.contains("localhost")) {
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
你是一个专业的安卓全自动记账 AI 引擎。请分析用户手机通知栏或无障碍屏幕抓取到的原始文本，判断是否为真实的资金变动事件，并严格输出 JSON 对象（不要包含 Markdown 代码块包裹）。

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
     * 内置高智商本地语义解析器（当用户未配置 API Key 或处于离线环境时，依然支持：
     * 微信/支付宝消费、尾号银行卡匹配、内部提现/充值转账、信用卡/花呗还款、退款与朋友AA冲销、外币识别）
     */
    fun localSmartFallbackParse(
        rawText: String,
        sourceAppPackage: String,
        wallets: List<WalletEntity>,
        recentExpenses: List<TransactionEntity>
    ): AiParsedTransactionResult? {
        if (rawText.contains("验证码") || rawText.contains("设置") && !rawText.contains("支付") && !rawText.contains("元")) {
            return null
        }

        // 1. 识别币种与金额
        val currency = when {
            rawText.contains("USD", ignoreCase = true) || rawText.contains("美元") || rawText.contains("$") && !rawText.contains("HK$") -> "USD"
            rawText.contains("HKD", ignoreCase = true) || rawText.contains("港币") || rawText.contains("HK$") -> "HKD"
            rawText.contains("EUR", ignoreCase = true) || rawText.contains("欧元") || rawText.contains("€") -> "EUR"
            rawText.contains("JPY", ignoreCase = true) || rawText.contains("日元") -> "JPY"
            else -> "CNY"
        }

        val amountRegex = Regex("""(?:¥|￥|RMB|CNY|USD|HKD|EUR|JPY|HK\$|\$|€|金额[:：]?|付款[:：]?|支出|消费|转出|到账|还款|退款|收款)\s*([0-9]+(?:\.[0-9]{1,2})?)|([0-9]+(?:\.[0-9]{1,2})?)\s*(?:元|美元|港币|欧元|日元)""")
        val match = amountRegex.find(rawText) ?: return null
        val amountStr = match.groupValues[1].ifEmpty { match.groupValues[2] }
        val amount = amountStr.toDoubleOrNull() ?: return null
        if (amount <= 0.0) return null

        // 2. 匹配关联的钱包
        fun matchWalletByHint(text: String, preferCredit: Boolean? = null): WalletEntity? {
            // 先按银行卡后四位精准匹配
            for (w in wallets) {
                if (!w.cardLast4.isNullOrBlank() && text.contains(w.cardLast4)) {
                    if (preferCredit == null || w.type.isLiability == preferCredit) return w
                }
            }
            // 再按名称与别名匹配
            for (w in wallets) {
                val names = listOf(w.name) + w.aliases.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
                if (names.any { text.contains(it, ignoreCase = true) }) {
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

        // 3. 判断是否为【信用卡/花呗还款】
        if (rawText.contains("还款") || rawText.contains("还花呗") || rawText.contains("还信用卡")) {
            val creditWallet = matchWalletByHint(rawText, preferCredit = true)
                ?: wallets.firstOrNull { it.type.isLiability }
            val debitWallet = wallets.firstOrNull {
                !it.type.isLiability && (
                    (!it.cardLast4.isNullOrBlank() && rawText.contains(it.cardLast4)) ||
                        rawText.contains(it.name.take(4))
                    )
            } ?: wallets.firstOrNull { !it.type.isLiability }

            return AiParsedTransactionResult(
                isValidTransaction = true,
                type = TransactionType.REPAYMENT.name,
                amount = amount,
                currency = currency,
                category = "信用还款",
                merchant = creditWallet?.name ?: "信用账户还款",
                note = "AI 识别信用账户自动还款",
                sourceWalletId = debitWallet?.id,
                targetWalletId = creditWallet?.id,
                confidence = 0.92f
            )
        }

        // 4. 判断是否为【资产内部转账（提现/充值/转入零钱通/余额宝）】
        if (rawText.contains("零钱提现") || rawText.contains("充值到零钱") || rawText.contains("转入余额宝") ||
            rawText.contains("转入零钱通") || rawText.contains("内部转账") || rawText.contains("提现到")
        ) {
            val wechatOrAlipay = wallets.firstOrNull {
                rawText.contains("微信") && it.name.contains("微信") ||
                    (rawText.contains("余额宝") || rawText.contains("支付宝")) && it.name.contains("支付宝")
            } ?: wallets.firstOrNull { !it.type.isLiability }
            val bankCard = wallets.firstOrNull {
                !it.type.isLiability && it.id != wechatOrAlipay?.id &&
                    ((!it.cardLast4.isNullOrBlank() && rawText.contains(it.cardLast4)) || it.name.contains("储蓄") || it.name.contains("银行"))
            } ?: wallets.lastOrNull { !it.type.isLiability && it.id != wechatOrAlipay?.id }

            val isRechargeToDigital = rawText.contains("充值") || rawText.contains("转入余额宝") || rawText.contains("转入零钱通")
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

        // 5. 判断是否为【退款 / 报销 / 朋友 AA 收款冲销】
        if (rawText.contains("退款") || rawText.contains("退回") || rawText.contains("AA", ignoreCase = true) ||
            rawText.contains("平摊") || rawText.contains("报销") || (rawText.contains("收到转账") && (rawText.contains("饭") || rawText.contains("餐") || rawText.contains("车") || rawText.contains("单")))
        ) {
            val receiveWallet = matchWalletByHint(rawText)
            val matchedExpense = recentExpenses.firstOrNull {
                (it.amountCnyCents - it.refundedAmountCents) >= (amount * 100).roundToLong()
            }
            return AiParsedTransactionResult(
                isValidTransaction = true,
                type = TransactionType.REFUND_OFFSET.name,
                amount = amount,
                currency = currency,
                category = matchedExpense?.category ?: "退款冲销",
                merchant = if (rawText.contains("退款")) "商户退款" else "朋友AA/报销收款",
                note = "自动冲抵原消费支出",
                targetWalletId = receiveWallet?.id,
                relatedExpenseId = matchedExpense?.id,
                confidence = 0.90f
            )
        }

        // 6. 判断是否为【日常收入（工资/收款/入账）】
        if ((rawText.contains("工资") || rawText.contains("薪资") || rawText.contains("收到红包") || rawText.contains("收款成功") || rawText.contains("转入")) &&
            !rawText.contains("支付成功") && !rawText.contains("消费")
        ) {
            val targetWallet = matchWalletByHint(rawText, preferCredit = false)
            return AiParsedTransactionResult(
                isValidTransaction = true,
                type = TransactionType.INCOME.name,
                amount = amount,
                currency = currency,
                category = if (rawText.contains("工资") || rawText.contains("薪")) "薪资-工资" else "人情-红包收款",
                merchant = extractMerchantName(rawText, "收入入账"),
                note = "AI 自动识别收入",
                targetWalletId = targetWallet?.id,
                confidence = 0.88f
            )
        }

        // 7. 默认判定为【日常消费支出 (EXPENSE)】：结合商户记忆自学习 + 带排除词权重分类器
        val extractedMerchant = extractMerchantName(rawText, "日常商户")
        val memoryHit = merchantMemoryManager?.matchMemory(rawText, extractedMerchant)
        val explicitWallet = if (currency == "USD") {
            wallets.firstOrNull { it.currency == "USD" } ?: matchWalletByHint(rawText)
        } else {
            // 若文本中显式含卡号尾号或钱包名称，优先使用显式匹配的钱包；否则回退到商户记忆常用钱包
            val hasExplicitHint = wallets.any { w ->
                (!w.cardLast4.isNullOrBlank() && rawText.contains(w.cardLast4)) ||
                    rawText.contains(w.name, ignoreCase = true)
            }
            if (hasExplicitHint) {
                matchWalletByHint(rawText)
            } else {
                memoryHit?.preferredWalletId?.let { pid -> wallets.firstOrNull { it.id == pid } }
                    ?: matchWalletByHint(rawText)
            }
        }

        val (weightedCat, weightedConf) = WeightedCategoryClassifier.classifyExpense(rawText, extractedMerchant)
        val finalCategory = memoryHit?.category ?: weightedCat
        val finalConfidence = if (memoryHit != null) 0.98f else weightedConf

        return AiParsedTransactionResult(
            isValidTransaction = true,
            type = TransactionType.EXPENSE.name,
            amount = amount,
            currency = currency,
            category = finalCategory,
            merchant = extractedMerchant,
            note = rawText.replace("\n", " ").take(42),
            sourceWalletId = explicitWallet?.id,
            confidence = finalConfidence
        )
    }

    private fun extractMerchantName(text: String, fallback: String): String {
        val patterns = listOf(
            Regex("""(?:商户名称|商户全称|收款方|收单机构|向商户|向|在|商户)[:：\s]*【?([^\s,，。；;】\n]{2,18})】?"""),
            Regex("""【([^】]{2,14})】""")
        )
        for (p in patterns) {
            val matches = p.findAll(text).toList()
            for (m in matches) {
                val candidate = m.groupValues[1].trim()
                if (candidate.isNotBlank() && candidate !in listOf("微信支付", "支付宝", "招商银行", "工商银行", "建设银行", "中国银行", "云闪付")) {
                    return candidate
                }
            }
        }
        merchantMemoryManager?.memoriesFlow?.value?.firstOrNull {
            text.contains(it.merchantKeyword, ignoreCase = true)
        }?.let { return it.merchantKeyword }

        val knownMerchants = listOf(
            "瑞幸咖啡", "星巴克", "Manner", "霸王茶姬", "喜茶", "奈雪", "蜜雪冰城",
            "盒马", "山姆会员商店", "叮咚买菜", "朴朴超市", "唐久", "全家", "罗森", "7-Eleven",
            "美团外卖", "美团", "饿了么", "麦当劳", "肯德基", "海底捞",
            "滴滴出行", "高德打车", "12306", "中国石化", "中国石油",
            "京东", "拼多多", "淘宝", "天猫", "优衣库", "GitHub", "OpenAI"
        )
        knownMerchants.firstOrNull { text.contains(it, ignoreCase = true) }?.let { return it }
        return fallback
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
