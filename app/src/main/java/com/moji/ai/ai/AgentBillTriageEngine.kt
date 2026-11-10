package com.moji.ai.ai

import android.util.Base64
import com.moji.ai.data.local.CaptureEvidenceEntity
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.model.CategoryCatalog
import com.moji.ai.data.model.CategoryDef
import com.moji.ai.data.model.TransactionType
import com.moji.ai.service.BillScreenshotStorage
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
import java.io.File
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

@Serializable
data class TriageBillItem(
    val transactionId: Long,
    val merchant: String,
    val note: String,
    val amountCents: Long = 0L,
    val amountCnyCents: Long,
    val currency: String = "CNY",
    val timestamp: Long,
    val type: TransactionType = TransactionType.EXPENSE,
    val currentCategory: String,
    val suggestedCategory: String,
    val isNewCategoryProposal: Boolean = false,
    val screenshotPath: String? = null,
    val evidenceSnippet: String = "",
    val reason: String,
    val confidence: Float = 0.92f
)

@Serializable
data class ProposedCategoryItem(
    val id: String,
    val isExpense: Boolean = true,
    val isSubCategory: Boolean = true,
    val parentName: String? = null,
    val categoryName: String,
    val fullCategoryPath: String,
    val iconAsset: String,
    val reason: String,
    val matchedCount: Int = 1
)

@Serializable
data class ProposedRuleItem(
    val id: String,
    val rule: SmartConditionRule,
    val reason: String,
    val matchedCount: Int = 1
)

@Serializable
data class ProposedMemoryItem(
    val id: String,
    val merchantKeyword: String,
    val targetCategory: String,
    val preferredWalletId: Long? = null,
    val reason: String,
    val matchedCount: Int = 1
)

@Serializable
data class AgentTriageReport(
    val scannedBillCount: Int = 0,
    val pendingTriageCount: Int = 0,
    val screenshotEvidenceCount: Int = 0,
    val usedCloudModel: Boolean = false,
    val summaryText: String = "",
    val reclassifyItems: List<TriageBillItem> = emptyList(),
    val proposedNewCategories: List<ProposedCategoryItem> = emptyList(),
    val proposedRules: List<ProposedRuleItem> = emptyList(),
    val proposedMemories: List<ProposedMemoryItem> = emptyList()
) {
    val totalActionCount: Int
        get() = reclassifyItems.size + proposedNewCategories.size + proposedRules.size + proposedMemories.size
}

/**
 * 默记 Agent 账单与截图分类梳理引擎（Option A 核心大脑）：
 * 1. 扫描账本中处于「待梳理」状态的账单，以及带有截图 (`screenshotPath`) / 原始文本证据 (`rawCapturedText`) 但落入宽泛分类的账单；
 * 2. 结合交易发生时段（如深夜 21:30–04:00）、商户全称、商品明细、截图视觉/OCR 证据与金额区间，输出：
 *    - 待梳理账单重分类建议 (`reclassifyItems`)
 *    - 建议新增的一级/二级分类及自动匹配的 iCost 线性图标 (`proposedNewCategories`)
 *    - 建议新增的 `SmartConditionRule` 条件规则 (`proposedRules`) 与 `MerchantMemoryEntry` 商户记忆 (`proposedMemories`)
 * 3. 支持「云端多模态/文本大模型 + 本地高置信度证据归纳」双引擎无缝协同。
 */
class AgentBillTriageEngine(
    private val configManager: AiConfigManager? = null,
    private val merchantMemoryManager: MerchantMemoryManager? = null,
    private val smartRuleEngine: SmartRuleEngine? = null
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private data class FineScenarioRule(
        val parentCategory: String,
        val subCategoryName: String,
        val isExpense: Boolean = true,
        val iconAsset: String,
        val keywords: List<String>,
        val excludeKeywords: List<String> = emptyList(),
        val requireNightHours: Boolean = false,
        val minAmountYuan: Double? = null,
        val maxAmountYuan: Double? = null,
        val ruleTitlePrefix: String,
        val categoryReason: String
    ) {
        val fullPath: String get() = "$parentCategory-$subCategoryName"
    }

    companion object {
        private val FINE_SCENARIO_RULES = listOf(
            FineScenarioRule(
                parentCategory = "餐饮",
                subCategoryName = "夜宵",
                iconAsset = "sub_meals.webp",
                keywords = listOf("夜宵", "烧烤", "小龙虾", "串串", "烤串", "大排档", "居酒屋", "炸鸡", "海底捞", "蛙", "酒馆"),
                excludeKeywords = listOf("打车", "代驾", "酒店"),
                requireNightHours = false,
                minAmountYuan = 15.0,
                maxAmountYuan = 500.0,
                ruleTitlePrefix = "夜宵聚餐自动归类",
                categoryReason = "账单截图/明细包含烧烤、夜宵等晚间餐饮消费特征，建议在「餐饮」下细分「夜宵」"
            ),
            FineScenarioRule(
                parentCategory = "运动",
                subCategoryName = "球类",
                iconAsset = "cat_sports.webp",
                keywords = listOf("羽毛球", "网球", "篮球", "乒乓球", "足球", "壁球", "匹克球", "球馆", "场地预定", "订场"),
                minAmountYuan = 20.0,
                maxAmountYuan = 600.0,
                ruleTitlePrefix = "球类运动订场归类",
                categoryReason = "账单出现羽毛球/网球等球馆订场或装备支出，建议在「运动」下新增「球类」二级分类"
            ),
            FineScenarioRule(
                parentCategory = "运动",
                subCategoryName = "健身",
                iconAsset = "cat_sports.webp",
                keywords = listOf("健身房", "乐刻", "Keep", "超级猩猩", "威尔仕", "私教", "游泳馆", "普拉提", "瑜伽"),
                minAmountYuan = 9.0,
                maxAmountYuan = 2000.0,
                ruleTitlePrefix = "健身训练自动归类",
                categoryReason = "检测到健身房/团课/游泳等周期性运动记录，建议在「运动」下新增「健身」二级分类"
            ),
            FineScenarioRule(
                parentCategory = "宠物",
                subCategoryName = "口粮",
                iconAsset = "cat_pet.webp",
                keywords = listOf("猫粮", "狗粮", "猫砂", "猫罐头", "冻干", "猫条", "皇家宠物", "渴望", "爱肯拿"),
                minAmountYuan = 15.0,
                maxAmountYuan = 1500.0,
                ruleTitlePrefix = "宠物口粮用品归类",
                categoryReason = "识别到猫粮/狗粮/猫砂等高频养宠支出，建议在「宠物」下细分「口粮」"
            ),
            FineScenarioRule(
                parentCategory = "宠物",
                subCategoryName = "医疗",
                iconAsset = "cat_pet.webp",
                keywords = listOf("宠物医院", "瑞鹏", "新瑞鹏", "宠物诊所", "猫三联", "狂犬疫苗", "驱虫", "宠物洗护"),
                minAmountYuan = 30.0,
                maxAmountYuan = 3000.0,
                ruleTitlePrefix = "宠物医疗洗护归类",
                categoryReason = "识别到宠物医院/疫苗/洗护账单，建议在「宠物」下细分「医疗」"
            ),
            FineScenarioRule(
                parentCategory = "应用软件",
                subCategoryName = "云与AI",
                iconAsset = "cat_app_software.webp",
                keywords = listOf("OpenAI", "Claude", "Anthropic", "DeepSeek", "Cursor", "GitHub", "阿里云", "腾讯云", "Google Cloud", "iCloud", "API充值", "算力", "Token", "云服务器"),
                minAmountYuan = 5.0,
                maxAmountYuan = 2000.0,
                ruleTitlePrefix = "AI与云服务订阅归类",
                categoryReason = "识别到 AI 订阅/API Token/云服务账单，建议在「应用软件」下新增「云与AI」二级分类"
            ),
            FineScenarioRule(
                parentCategory = "娱乐",
                subCategoryName = "影音会员",
                iconAsset = "cat_entertainment.webp",
                keywords = listOf("Bilibili", "哔哩哔哩", "大会员", "网易云音乐", "QQ音乐", "爱奇艺", "腾讯视频", "优酷", "芒果TV", "Netflix", "Spotify", "Apple Music"),
                minAmountYuan = 8.0,
                maxAmountYuan = 300.0,
                ruleTitlePrefix = "流媒体影音会员归类",
                categoryReason = "检测到视频/音乐平台会员续费账单，建议在「娱乐」下新增「影音会员」二级分类"
            ),
            FineScenarioRule(
                parentCategory = "汽车",
                subCategoryName = "充电",
                iconAsset = "sub_fuel.webp",
                keywords = listOf("特来电", "星星充电", "小桔充电", "蔚来能源", "充电桩", "超充", "充电服务费"),
                minAmountYuan = 10.0,
                maxAmountYuan = 300.0,
                ruleTitlePrefix = "新能源车充电归类",
                categoryReason = "检测到新能源充电桩账单，建议在「汽车」下新增「充电」二级分类"
            )
        )

        private val BROAD_REFINABLE_CATEGORIES = setOf(
            CategoryCatalog.PENDING_TRIAGE_CATEGORY,
            "其他", "日常-其他", "餐饮", "餐饮-三餐", "运动", "宠物", "应用软件", "娱乐", "汽车", "日用"
        )

        /**
         * 校验账单截图路径是否位于应用沙盒 `files/bill_screenshots/` 目录且为 `.webp` 格式，
         * 防止外部导入的备份 JSON 中携带恶意本地路径读取非截图私有文件。
         */
        fun resolveSafeScreenshotFile(path: String?): File? {
            if (path.isNullOrBlank()) return null
            return runCatching {
                val f = File(path)
                if (BillScreenshotStorage.isSafeScreenshotFile(f)) f else null
            }.getOrNull()
        }
    }

    suspend fun analyzeBills(
        transactions: List<TransactionEntity>,
        captureEvidences: List<CaptureEvidenceEntity> = emptyList(),
        existingRules: List<SmartConditionRule> = smartRuleEngine?.rules?.value.orEmpty(),
        existingMemories: List<MerchantMemoryEntry> = merchantMemoryManager?.memoriesFlow?.value.orEmpty(),
        expenseCategories: List<CategoryDef> = CategoryCatalog.expenseCategories,
        incomeCategories: List<CategoryDef> = CategoryCatalog.incomeCategories
    ): AgentTriageReport = withContext(Dispatchers.IO) {
        val eligibleTxs = transactions.filter {
            (it.type == TransactionType.EXPENSE || it.type == TransactionType.INCOME) &&
                it.category != CategoryCatalog.SYSTEM_BALANCE_ANCHOR_CATEGORY
        }
        val evidencesByTxId = captureEvidences
            .filter { it.transactionId > 0L }
            .groupBy { it.transactionId }

        val pendingList = eligibleTxs.filter { CategoryCatalog.isPendingTriage(it.category) }
        val screenshotCount = eligibleTxs.count { tx ->
            tx.screenshotPath.isNotBlank() ||
                evidencesByTxId[tx.id].orEmpty().any { it.screenshotPath.isNotBlank() }
        }

        val reclassifyItems = mutableListOf<TriageBillItem>()
        val categoryBuckets = linkedMapOf<String, ProposedCategoryItem>()
        val ruleBuckets = linkedMapOf<String, ProposedRuleItem>()
        val memoryBuckets = linkedMapOf<String, ProposedMemoryItem>()
        val pendingProposals = mutableListOf<Triple<Long, ProposedRuleItem?, ProposedMemoryItem?>>()

        for (tx in eligibleTxs) {
            val txEvidences = evidencesByTxId[tx.id].orEmpty()
            val resolvedScreenshot = tx.screenshotPath.takeIf { it.isNotBlank() }
                ?: txEvidences.asReversed().firstNotNullOfOrNull { it.screenshotPath.takeIf { p -> p.isNotBlank() } }
            val combinedText = buildString {
                append(tx.merchant).append(" ")
                append(tx.note).append(" ")
                append(tx.rawCapturedText).append(" ")
                for (ev in txEvidences) {
                    if (ev.rawText.isNotBlank()) {
                        append(ev.rawText).append(" ")
                    }
                }
            }.trim()

            val isPending = CategoryCatalog.isPendingTriage(tx.category)
            val isRefinable = tx.category in BROAD_REFINABLE_CATEGORIES

            if (!isPending && !isRefinable) continue

            val inference = inferTransactionTriage(
                tx = tx,
                combinedText = combinedText,
                hasScreenshot = !resolvedScreenshot.isNullOrBlank(),
                existingRules = existingRules,
                existingMemories = existingMemories,
                expenseCategories = expenseCategories,
                incomeCategories = incomeCategories
            ) ?: continue

            if (!isPending && inference.suggestedCategory == tx.category) continue

            // 若目标分类尚未在当前分类树中存在，加入「建议新增分类」提案
            if (inference.proposedCategory != null) {
                val prop = inference.proposedCategory
                val existingProp = categoryBuckets[prop.id]
                categoryBuckets[prop.id] = if (existingProp == null) {
                    prop
                } else {
                    existingProp.copy(matchedCount = existingProp.matchedCount + 1)
                }
            }

            // 收集待归档重分类账单（优先展示所有「待梳理」账单，以及命中细分新分类的账单）
            if (isPending || inference.isNewCategoryProposal || inference.confidence >= 0.90f) {
                reclassifyItems.add(
                    TriageBillItem(
                        transactionId = tx.id,
                        merchant = tx.merchant.ifBlank { if (tx.type == TransactionType.INCOME) "收入入账" else "日常消费" },
                        note = tx.note,
                        amountCents = tx.amountCents,
                        amountCnyCents = tx.amountCnyCents,
                        currency = tx.currency,
                        timestamp = tx.timestamp,
                        type = tx.type,
                        currentCategory = tx.category,
                        suggestedCategory = inference.suggestedCategory,
                        isNewCategoryProposal = inference.isNewCategoryProposal,
                        screenshotPath = resolvedScreenshot,
                        evidenceSnippet = extractEvidenceSnippet(tx, combinedText, !resolvedScreenshot.isNullOrBlank()),
                        reason = inference.reason,
                        confidence = inference.confidence
                    )
                )
            }

            if (inference.proposedRule != null || inference.proposedMemory != null) {
                pendingProposals.add(Triple(tx.id, inference.proposedRule, inference.proposedMemory))
            }
        }

        var usedCloud = false
        val aiCfg = configManager?.getConfig()
        if (aiCfg != null && aiCfg.apiKey.isNotBlank() && reclassifyItems.isNotEmpty()) {
            usedCloud = tryCloudLlmAugmentation(
                cfg = aiCfg,
                reclassifyItems = reclassifyItems,
                categoryBuckets = categoryBuckets,
                expenseCategories = expenseCategories,
                incomeCategories = incomeCategories
            )
        }

        val finalSuggestedByTxId = reclassifyItems.associate { it.transactionId to it.suggestedCategory }
        for ((txId, draftRuleRaw, draftMemRaw) in pendingProposals) {
            val finalCat = finalSuggestedByTxId[txId]
            val draftRule = if (draftRuleRaw != null && !finalCat.isNullOrBlank() && finalCat != draftRuleRaw.rule.targetCategory) {
                val newRuleKey = "RULE:$finalCat:${draftRuleRaw.rule.keywordContains}"
                draftRuleRaw.copy(
                    id = newRuleKey,
                    rule = draftRuleRaw.rule.copy(targetCategory = finalCat)
                )
            } else {
                draftRuleRaw
            }
            draftRule?.let { r ->
                val alreadyHasRule = existingRules.any {
                    it.targetCategory == r.rule.targetCategory &&
                        it.keywordContains.equals(r.rule.keywordContains, ignoreCase = true)
                }
                if (!alreadyHasRule) {
                    val prev = ruleBuckets[r.id]
                    ruleBuckets[r.id] = if (prev == null) r else prev.copy(matchedCount = prev.matchedCount + 1)
                }
            }

            val draftMem = if (draftMemRaw != null && !finalCat.isNullOrBlank() && finalCat != draftMemRaw.targetCategory) {
                draftMemRaw.copy(
                    id = "MEM:${draftMemRaw.merchantKeyword}:$finalCat",
                    targetCategory = finalCat,
                    reason = "记住商户「${draftMemRaw.merchantKeyword}」习惯分类为「$finalCat」"
                )
            } else {
                draftMemRaw
            }
            draftMem?.let { m ->
                val alreadyHasMem = existingMemories.any {
                    it.merchantKeyword.equals(m.merchantKeyword, ignoreCase = true) &&
                        it.category == m.targetCategory
                }
                if (!alreadyHasMem) {
                    val prev = memoryBuckets[m.id]
                    memoryBuckets[m.id] = if (prev == null) m else prev.copy(matchedCount = prev.matchedCount + 1)
                }
            }
        }

        val finalCategories = categoryBuckets.values.toList()
        val finalRules = ruleBuckets.values.toList().take(8).mapIndexed { index, item ->
            val uniqueRuleId = stableRuleId(item.id, index)
            item.copy(rule = item.rule.copy(id = uniqueRuleId))
        }
        val finalMemories = memoryBuckets.values.toList().take(10)
        val summary = buildSummaryText(
            pendingCount = pendingList.size,
            reclassifyCount = reclassifyItems.size,
            newCatCount = finalCategories.size,
            ruleCount = finalRules.size + finalMemories.size,
            screenshotCount = screenshotCount,
            usedCloud = usedCloud
        )

        AgentTriageReport(
            scannedBillCount = eligibleTxs.size,
            pendingTriageCount = pendingList.size,
            screenshotEvidenceCount = screenshotCount,
            usedCloudModel = usedCloud,
            summaryText = summary,
            reclassifyItems = reclassifyItems,
            proposedNewCategories = finalCategories,
            proposedRules = finalRules,
            proposedMemories = finalMemories
        )
    }

    private data class SingleInferenceResult(
        val suggestedCategory: String,
        val isNewCategoryProposal: Boolean,
        val reason: String,
        val confidence: Float,
        val proposedCategory: ProposedCategoryItem? = null,
        val proposedRule: ProposedRuleItem? = null,
        val proposedMemory: ProposedMemoryItem? = null
    )

    private fun inferTransactionTriage(
        tx: TransactionEntity,
        combinedText: String,
        hasScreenshot: Boolean,
        existingRules: List<SmartConditionRule>,
        existingMemories: List<MerchantMemoryEntry>,
        expenseCategories: List<CategoryDef>,
        incomeCategories: List<CategoryDef>
    ): SingleInferenceResult? {
        val isExpense = tx.type != TransactionType.INCOME
        val isPending = CategoryCatalog.isPendingTriage(tx.category)
        val amountYuan = tx.amountCnyCents / 100.0
        val cal = Calendar.getInstance().apply { timeInMillis = tx.timestamp }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val isLateNight = hour >= 22 || hour in 0..3
        val evidenceTag = if (hasScreenshot) "截图与账单证据" else "账单文本特征"

        // 1. 收入账单梳理（优先对齐 CategoryCatalog.defaultIncomeCategories 中的已有分类名）
        if (!isExpense) {
            if (!isPending) return null
            val targetCat = when {
                combinedText.contains("工资") || combinedText.contains("薪") ->
                    if (hasCatInList("薪资-工资", incomeCategories)) "薪资-工资" else "工资"
                combinedText.contains("奖") || combinedText.contains("年终") ->
                    if (hasCatInList("薪资-奖金", incomeCategories)) "薪资-奖金" else "奖金"
                combinedText.contains("兼职") || combinedText.contains("稿费") || combinedText.contains("劳务") ->
                    if (hasCatInList("薪资-兼职", incomeCategories)) "薪资-兼职" else "兼职"
                combinedText.contains("理财") || combinedText.contains("利息") || combinedText.contains("分红") -> "理财"
                combinedText.contains("报销") -> "报销"
                combinedText.contains("退款") || combinedText.contains("退费") -> "退款"
                tx.amountCnyCents >= 300_000L ->
                    if (hasCatInList("薪资-工资", incomeCategories)) "薪资-工资" else "工资"
                else ->
                    if (hasCatInList("人情-红包收款", incomeCategories)) "人情-红包收款" else "红包"
            }
            val exists = hasCatInList(targetCat, incomeCategories)
            val propCat = if (!exists) buildProposedCategory(targetCat, isExpense = false, reason = "根据收入账单特征补充分类") else null
            return SingleInferenceResult(
                suggestedCategory = targetCat,
                isNewCategoryProposal = !exists,
                reason = "根据${evidenceTag}识别为「$targetCat」",
                confidence = 0.93f,
                proposedCategory = propCat
            )
        }

        // 2. 优先检查已有「非宽泛」When -> Then 规则与商户记忆（防止默认泛化规则如「餐饮-三餐」截胡细分新分类归纳）
        if (isPending) {
            val matchedSpecificRule = existingRules.firstOrNull { rule ->
                matchesSmartRule(rule, isExpense = isExpense, combinedText = combinedText, amountYuan = amountYuan, hour = hour) &&
                    rule.targetCategory !in BROAD_REFINABLE_CATEGORIES
            }
            if (matchedSpecificRule != null) {
                val catExists = hasCatInList(matchedSpecificRule.targetCategory, expenseCategories)
                val propCat = if (!catExists) {
                    buildProposedCategory(matchedSpecificRule.targetCategory, isExpense = true, reason = "补全已有分类规则引用的细分分类")
                } else null
                return SingleInferenceResult(
                    suggestedCategory = matchedSpecificRule.targetCategory,
                    isNewCategoryProposal = !catExists,
                    reason = "命中已有分类规则「${matchedSpecificRule.name}」",
                    confidence = 0.98f,
                    proposedCategory = propCat
                )
            }
            val matchedSpecificMem = existingMemories.firstOrNull { mem ->
                combinedText.contains(mem.merchantKeyword, ignoreCase = true) &&
                    !CategoryCatalog.isPendingTriage(mem.category) &&
                    mem.category !in BROAD_REFINABLE_CATEGORIES
            }
            if (matchedSpecificMem != null) {
                val catExists = hasCatInList(matchedSpecificMem.category, expenseCategories)
                val propCat = if (!catExists) {
                    buildProposedCategory(matchedSpecificMem.category, isExpense = true, reason = "补全已记住商户引用的细分分类")
                } else null
                return SingleInferenceResult(
                    suggestedCategory = matchedSpecificMem.category,
                    isNewCategoryProposal = !catExists,
                    reason = "命中已记住商户「${matchedSpecificMem.merchantKeyword} → ${matchedSpecificMem.category}」",
                    confidence = 0.97f,
                    proposedCategory = propCat
                )
            }
        }

        // 3. 细分新分类场景归纳（如深夜餐饮/外卖 22:00-04:00 -> 餐饮-夜宵，羽毛球馆 -> 运动-球类，高于默认宽泛规则「餐饮-三餐」）
        val isNightDiningSignal = isLateNight && (
            combinedText.contains("美团") || combinedText.contains("饿了么") ||
                combinedText.contains("外卖") || combinedText.contains("餐饮") ||
                combinedText.contains("烧烤") || combinedText.contains("夜宵") ||
                combinedText.contains("小龙虾") || combinedText.contains("海底捞") ||
                combinedText.contains("麦当劳") || combinedText.contains("肯德基")
            )
        val matchedFineRule = FINE_SCENARIO_RULES.firstOrNull { rule ->
            rule.excludeKeywords.none { combinedText.contains(it, ignoreCase = true) } &&
                rule.keywords.any { combinedText.contains(it, ignoreCase = true) } &&
                (!rule.requireNightHours || isLateNight)
        } ?: if (isNightDiningSignal) FINE_SCENARIO_RULES.first() else null

        if (matchedFineRule != null) {
            val targetPath = matchedFineRule.fullPath
            val catExists = hasCatInList(targetPath, expenseCategories)
            val hitKw = matchedFineRule.keywords.firstOrNull { combinedText.contains(it, ignoreCase = true) }
                ?: tx.merchant.takeIf { it.isNotBlank() }
                ?: "夜宵"
            val propCat = if (!catExists) {
                ProposedCategoryItem(
                    id = "EXPENSE:$targetPath",
                    isExpense = true,
                    isSubCategory = true,
                    parentName = matchedFineRule.parentCategory,
                    categoryName = matchedFineRule.subCategoryName,
                    fullCategoryPath = targetPath,
                    iconAsset = matchedFineRule.iconAsset,
                    reason = matchedFineRule.categoryReason
                )
            } else null

            val cleanMerchant = cleanMerchantKeyword(tx.merchant, hitKw)
            val ruleKey = "RULE:$targetPath:$hitKw"
            val propRule = ProposedRuleItem(
                id = ruleKey,
                rule = SmartConditionRule(
                    id = stableRuleId(ruleKey, tx.id.toInt()),
                    name = "${matchedFineRule.ruleTitlePrefix}（$hitKw）",
                    enabled = true,
                    keywordContains = hitKw,
                    minAmountYuan = matchedFineRule.minAmountYuan,
                    maxAmountYuan = matchedFineRule.maxAmountYuan,
                    targetCategory = targetPath
                ),
                reason = "从${evidenceTag}提炼关键词「$hitKw」与金额区间，下次自动归入「$targetPath」"
            )
            val propMem = if (cleanMerchant.length >= 2) {
                ProposedMemoryItem(
                    id = "MEM:$cleanMerchant:$targetPath",
                    merchantKeyword = cleanMerchant,
                    targetCategory = targetPath,
                    preferredWalletId = tx.sourceWalletId,
                    reason = "记住商户「$cleanMerchant」习惯分类为「$targetPath」"
                )
            } else null

            return SingleInferenceResult(
                suggestedCategory = targetPath,
                isNewCategoryProposal = !catExists,
                reason = "根据${evidenceTag}中的「$hitKw」${if (isLateNight) "与晚间时段(${String.format(Locale.CHINA, "%02d:%02d", hour, cal.get(Calendar.MINUTE))})" else ""}，建议归入「$targetPath」",
                confidence = 0.95f,
                proposedCategory = propCat,
                proposedRule = propRule,
                proposedMemory = propMem
            )
        }

        // 4. 若非「待梳理」且未命中细分新分类场景，则无需改动已有分类
        if (!isPending) return null

        // 5. 检查其余已有 When -> Then 规则与商户记忆（包含「晨间小额早餐自动归类 -> 餐饮-三餐」等带时段校验的预设规则）
        val matchedBroadRule = existingRules.firstOrNull { rule ->
            matchesSmartRule(rule, isExpense = isExpense, combinedText = combinedText, amountYuan = amountYuan, hour = hour)
        }
        if (matchedBroadRule != null) {
            val catExists = hasCatInList(matchedBroadRule.targetCategory, expenseCategories)
            val propCat = if (!catExists) {
                buildProposedCategory(matchedBroadRule.targetCategory, isExpense = true, reason = "补全已有分类规则引用的分类")
            } else null
            return SingleInferenceResult(
                suggestedCategory = matchedBroadRule.targetCategory,
                isNewCategoryProposal = !catExists,
                reason = "命中已有分类规则「${matchedBroadRule.name}」",
                confidence = 0.96f,
                proposedCategory = propCat
            )
        }
        val matchedBroadMem = existingMemories.firstOrNull { mem ->
            combinedText.contains(mem.merchantKeyword, ignoreCase = true) &&
                !CategoryCatalog.isPendingTriage(mem.category)
        }
        if (matchedBroadMem != null) {
            val catExists = hasCatInList(matchedBroadMem.category, expenseCategories)
            val propCat = if (!catExists) {
                buildProposedCategory(matchedBroadMem.category, isExpense = true, reason = "补全已记住商户引用的分类")
            } else null
            return SingleInferenceResult(
                suggestedCategory = matchedBroadMem.category,
                isNewCategoryProposal = !catExists,
                reason = "命中已记住商户「${matchedBroadMem.merchantKeyword} → ${matchedBroadMem.category}」",
                confidence = 0.95f,
                proposedCategory = propCat
            )
        }

        // 6. 对「待梳理」账单执行加权语义分类 + 时段/金额启发式兜底
        val (weightedCat, weightedConf) = WeightedCategoryClassifier.classifyExpense(combinedText, tx.merchant)
        val resolvedCat = if (weightedConf > 0.80f) {
            weightedCat
        } else {
            when {
                isLateNight && amountYuan <= 150.0 -> "餐饮-夜宵"
                hour in 6..9 && amountYuan <= 35.0 -> "餐饮-三餐"
                (hour in 11..13 || hour in 17..20) && amountYuan <= 80.0 -> "餐饮-三餐"
                (hour in 7..9 || hour in 18..20) && amountYuan in 1.5..8.0 -> "交通-地铁"
                else -> "日用"
            }
        }
        val catExists = hasCatInList(resolvedCat, expenseCategories)
        val propCat = if (!catExists) {
            buildProposedCategory(resolvedCat, isExpense = true, reason = "根据待梳理账单时段与消费场景自动补全细分分类")
        } else null
        val cleanMerchant = cleanMerchantKeyword(tx.merchant, "")
        val propMem = if (cleanMerchant.length >= 2 && weightedConf > 0.80f) {
            ProposedMemoryItem(
                id = "MEM:$cleanMerchant:$resolvedCat",
                merchantKeyword = cleanMerchant,
                targetCategory = resolvedCat,
                preferredWalletId = tx.sourceWalletId,
                reason = "从待梳理账单学习商户「$cleanMerchant → $resolvedCat」"
            )
        } else null
        val ruleKey = "RULE:$resolvedCat:$cleanMerchant"
        val propRule = if (cleanMerchant.length >= 2 && weightedConf > 0.80f) {
            ProposedRuleItem(
                id = ruleKey,
                rule = SmartConditionRule(
                    id = stableRuleId(ruleKey, tx.id.toInt()),
                    name = "${cleanMerchant}自动归入$resolvedCat",
                    enabled = true,
                    keywordContains = cleanMerchant,
                    targetCategory = resolvedCat
                ),
                reason = "当账单包含「$cleanMerchant」时自动归入「$resolvedCat」"
            )
        } else null

        val reasonText = if (weightedConf > 0.80f) {
            "根据${evidenceTag}识别出商户/商品语义，归入「$resolvedCat」"
        } else {
            "结合交易时段(${String.format(Locale.CHINA, "%02d:%02d", hour, cal.get(Calendar.MINUTE))})与金额(¥${String.format(Locale.US, "%.2f", amountYuan)})智能归入「$resolvedCat」"
        }
        return SingleInferenceResult(
            suggestedCategory = resolvedCat,
            isNewCategoryProposal = !catExists,
            reason = reasonText,
            confidence = if (weightedConf > 0.80f) weightedConf else 0.86f,
            proposedCategory = propCat,
            proposedRule = propRule,
            proposedMemory = propMem
        )
    }

    /**
     * 完整对齐 `SmartRuleEngine.evaluateAndApply` 的关键词、金额区间、时间窗（支持跨午夜）与收支类型校验。
     */
    private fun matchesSmartRule(
        rule: SmartConditionRule,
        isExpense: Boolean,
        combinedText: String,
        amountYuan: Double,
        hour: Int
    ): Boolean {
        if (!rule.enabled || rule.interceptIgnore || rule.targetCategory.isBlank()) return false
        if (rule.onlyExpense && !isExpense) return false
        if (rule.keywordContains.isNotBlank()) {
            val keywords = rule.keywordContains
                .split(",", "，", "|", "、")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (keywords.isNotEmpty() && keywords.none { kw -> combinedText.contains(kw, ignoreCase = true) }) {
                return false
            }
        }
        if (rule.minAmountYuan != null && amountYuan < rule.minAmountYuan) return false
        if (rule.maxAmountYuan != null && amountYuan > rule.maxAmountYuan) return false
        if (rule.startHour24 != null && rule.endHour24 != null) {
            val s = rule.startHour24.coerceIn(0, 23)
            val e = rule.endHour24.coerceIn(0, 23)
            val inWindow = if (s <= e) {
                hour in s..e
            } else {
                hour >= s || hour <= e
            }
            if (!inWindow) return false
        }
        return true
    }

    /**
     * 为每条自动归纳出的条件规则生成互不冲突且不与预设规则（101..104）碰撞的确定性正整数 ID。
     */
    private fun stableRuleId(ruleKey: String, ordinalOrTxSalt: Int): Long {
        val hash = (ruleKey.hashCode().toLong() and 0x7FFFFFFFL)
        return 10_000_000L + (hash * 100L) + ((ordinalOrTxSalt and 0x3F) + 1L)
    }

    private fun hasCatInList(fullCategory: String, list: List<CategoryDef>): Boolean =
        CategoryCatalog.hasCategoryInList(fullCategory, list)

    private fun buildProposedCategory(
        fullPath: String,
        isExpense: Boolean,
        reason: String
    ): ProposedCategoryItem {
        val parts = fullPath.split("-", "·", "/").map { it.trim() }.filter { it.isNotEmpty() }
        val mainName = parts.firstOrNull() ?: fullPath.trim()
        val subName = parts.getOrNull(1)
        val isSub = !subName.isNullOrBlank()
        val targetName = subName ?: mainName
        val parentName = if (isSub) mainName else null
        val iconAsset = CategoryCatalog.matchBestIconAsset(
            name = targetName,
            parentName = parentName,
            isExpense = isExpense
        )
        return ProposedCategoryItem(
            id = "${if (isExpense) "EXPENSE" else "INCOME"}:$fullPath",
            isExpense = isExpense,
            isSubCategory = isSub,
            parentName = parentName,
            categoryName = targetName,
            fullCategoryPath = if (isSub) "$mainName-$subName" else mainName,
            iconAsset = iconAsset,
            reason = reason
        )
    }

    private fun cleanMerchantKeyword(rawMerchant: String, fallbackKw: String): String {
        val cleaned = rawMerchant.trim()
            .replace(Regex("""\([^)]*\)|（[^）]*）"""), "")
            .replace(Regex("""(有限公司|股份有限公司|专营店|旗舰店|官方店|餐饮店|便利店)$"""), "")
            .trim()
        val stopwords = setOf(
            "日常商户", "日常消费", "收入入账", "商户退款", "微信支付", "支付宝", "云闪付", "待梳理"
        )
        return if (cleaned.length >= 2 && cleaned !in stopwords) cleaned else fallbackKw.trim()
    }

    private fun extractEvidenceSnippet(
        tx: TransactionEntity,
        combinedText: String,
        hasScreenshot: Boolean
    ): String {
        val raw = tx.rawCapturedText.ifBlank { combinedText }
            .replace("\n", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val prefix = if (hasScreenshot) "[含账单截图] " else ""
        return (prefix + raw.take(68)).trim()
    }

    private fun buildSummaryText(
        pendingCount: Int,
        reclassifyCount: Int,
        newCatCount: Int,
        ruleCount: Int,
        screenshotCount: Int,
        usedCloud: Boolean
    ): String {
        if (reclassifyCount == 0 && newCatCount == 0 && ruleCount == 0) {
            return "当前账本分类与规则已非常整洁，暂无待梳理账单或新增建议。"
        }
        val engineLabel = if (usedCloud) "多模态大模型 + 本地证据引擎" else "本地截图与账单语义引擎"
        return buildString {
            append("由 $engineLabel 完成梳理：")
            if (pendingCount > 0) append("处理 $pendingCount 笔「待梳理」账单，")
            if (screenshotCount > 0) append("结合 $screenshotCount 份截图证据，")
            append("共生成 $reclassifyCount 笔分类归档、$newCatCount 项新分类建议与 $ruleCount 条自动规则/记忆。")
        }
    }

    private fun tryCloudLlmAugmentation(
        cfg: AiConfig,
        reclassifyItems: MutableList<TriageBillItem>,
        categoryBuckets: LinkedHashMap<String, ProposedCategoryItem>,
        expenseCategories: List<CategoryDef>,
        incomeCategories: List<CategoryDef>
    ): Boolean {
        if (cfg.apiKey.isBlank()) return false
        return runCatching {
            val sampleItems = reclassifyItems.take(8)
            val expSummary = expenseCategories.joinToString("；") { c ->
                if (c.subCategories.isEmpty()) c.name else "${c.name}(${c.subCategories.joinToString("/") { it.name }})"
            }
            val incSummary = incomeCategories.joinToString("；") { c ->
                if (c.subCategories.isEmpty()) c.name else "${c.name}(${c.subCategories.joinToString("/") { it.name }})"
            }
            val billsPrompt = sampleItems.joinToString("\n") { item ->
                val typeTag = if (item.type == TransactionType.INCOME) "收入" else "支出"
                "- id=${item.transactionId}, 类型=$typeTag, 商户=${item.merchant}, 备注=${item.note}, 金额=¥${String.format(Locale.US, "%.2f", item.amountCnyCents / 100.0)}, 证据=${item.evidenceSnippet}, 初筛建议=${item.suggestedCategory}"
            }
            val screenshotOwner = sampleItems.firstNotNullOfOrNull { item ->
                resolveSafeScreenshotFile(item.screenshotPath)?.let { file ->
                    if (file.length() in 1..512_000) {
                        runCatching { Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) }.getOrNull()
                            ?.let { encoded -> item.transactionId to encoded }
                    } else null
                }
            }
            val base64Screenshot = screenshotOwner?.second
            val screenshotNotice = if (screenshotOwner != null) {
                "\n（注意：随附的截图图片仅属于账单 id=${screenshotOwner.first}，切勿将其商品内容套用到其他 id 的账单）"
            } else ""

            val prompt = """
                你是默记(MoJi)的智能记账分类梳理 Agent。
                当前支出分类树：$expSummary
                当前收入分类树：$incSummary
                待梳理账单列表：
                $billsPrompt$screenshotNotice
                请核对每笔账单最贴合的「一级-二级」分类（若现有分类不足，可提出「一级-新二级」分类）。
                严格只返回 JSON 数组：[{"id":123,"category":"餐饮-夜宵","reason":"简短理由"}]
            """.trimIndent()

            val rawResp = if (cfg.provider == AiProviderType.GEMINI_OFFICIAL) {
                callGeminiTriage(cfg, prompt, base64Screenshot)
            } else {
                callOpenAiTriage(cfg, prompt)
            } ?: return false

            val startIdx = rawResp.indexOf('[')
            val endIdx = rawResp.lastIndexOf(']')
            if (startIdx < 0 || endIdx <= startIdx) return false
            val arr = json.parseToJsonElement(rawResp.substring(startIdx, endIdx + 1)).jsonArray
            for (elem in arr) {
                val obj = elem.jsonObject
                val txId = obj["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue
                val rawNewCat = obj["category"]?.jsonPrimitive?.content?.trim().orEmpty()
                val newCat = rawNewCat
                    .split("-", "·", "/", "—", ">", "›")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(2)
                    .joinToString("-")
                val newReason = obj["reason"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (newCat.isBlank() || CategoryCatalog.isPendingTriage(newCat)) continue
                val idx = reclassifyItems.indexOfFirst { it.transactionId == txId }
                if (idx >= 0) {
                    val isExp = reclassifyItems[idx].type != TransactionType.INCOME
                    val targetCatList = if (isExp) expenseCategories else incomeCategories
                    val exists = hasCatInList(newCat, targetCatList)
                    if (!exists) {
                        val prop = buildProposedCategory(newCat, isExpense = isExp, reason = newReason.ifBlank { "AI 结合账单特征建议新增分类" })
                        categoryBuckets.putIfAbsent(prop.id, prop)
                    }
                    reclassifyItems[idx] = reclassifyItems[idx].copy(
                        suggestedCategory = newCat,
                        isNewCategoryProposal = !exists,
                        reason = newReason.ifBlank { reclassifyItems[idx].reason }
                    )
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun callOpenAiTriage(cfg: AiConfig, prompt: String): String? {
        val url = "${cfg.baseUrl.trimEnd('/')}/chat/completions"
        val bodyJson = buildJsonObject {
            put("model", cfg.modelName)
            put("temperature", 0.1)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${cfg.apiKey}")
            .post(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), bodyJson).toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bodyStr = resp.body?.string() ?: return null
            val root = json.parseToJsonElement(bodyStr).jsonObject
            return root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
        }
    }

    private fun callGeminiTriage(cfg: AiConfig, prompt: String, base64Webp: String?): String? {
        val baseUrl = cfg.baseUrl.trimEnd('/').ifBlank { "https://generativelanguage.googleapis.com/v1beta" }
        val encodedModel = URLEncoder.encode(cfg.modelName.trim(), "UTF-8")
        val url = "$baseUrl/models/$encodedModel:generateContent"
        val bodyJson = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", prompt) })
                        if (!base64Webp.isNullOrBlank()) {
                            add(buildJsonObject {
                                put("inline_data", buildJsonObject {
                                    put("mime_type", "image/webp")
                                    put("data", base64Webp)
                                })
                            })
                        }
                    })
                })
            })
        }
        val req = Request.Builder()
            .url(url)
            .apply {
                if (cfg.apiKey.isNotBlank()) {
                    addHeader("x-goog-api-key", cfg.apiKey)
                }
            }
            .post(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), bodyJson).toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bodyStr = resp.body?.string() ?: return null
            val root = json.parseToJsonElement(bodyStr).jsonObject
            return root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
        }
    }
}



