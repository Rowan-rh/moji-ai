package com.moji.ai.ai

import android.content.Context
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.model.CategoryCatalog
import com.moji.ai.data.model.TagCatalog
import com.moji.ai.data.model.TransactionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar

/**
 * 可视化条件规则实体（When 条件组合 -> Then 自动动作）
 * 对标 pennywiseai-tracker Smart Rules Engine 与 AutoAccounting 自定义规则管道
 */
@Serializable
data class SmartConditionRule(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val enabled: Boolean = true,
    // When 条件
    val keywordContains: String = "",      // 逗号分隔的关键词（任一命中即满足；留空代表不限关键词）
    val minAmountYuan: Double? = null,     // 最小金额（元）
    val maxAmountYuan: Double? = null,     // 最大金额（元）
    val startHour24: Int? = null,          // 起始小时 (0..23)，支持跨零点（如 21 -> 3）
    val endHour24: Int? = null,            // 结束小时 (0..23)
    val onlyExpense: Boolean = true,       // 仅对支出流水生效
    // Then 动作
    val targetCategory: String = "",       // 覆盖分类（非空生效）
    val targetLedger: String = "",         // 覆盖账本（非空生效）
    val markReimbursable: Boolean = false, // 自动标记为待报销
    val appendTag: String = "",            // 自动追加标签
    val overrideSourceWalletId: Long? = null,
    val interceptIgnore: Boolean = false   // 若为 true，则直接作为非账单噪声拦截不入账
) {
    fun conditionSummary(): String {
        val parts = mutableListOf<String>()
        if (keywordContains.isNotBlank()) parts.add("含「$keywordContains」")
        if (minAmountYuan != null || maxAmountYuan != null) {
            val minStr = minAmountYuan?.let { "≥¥${it.toInt()}" } ?: ""
            val maxStr = maxAmountYuan?.let { "≤¥${it.toInt()}" } ?: ""
            parts.add("金额$minStr$maxStr")
        }
        if (startHour24 != null && endHour24 != null) {
            parts.add(String.format("%02d:00-%02d:59", startHour24, endHour24))
        }
        return if (parts.isEmpty()) "任意交易" else parts.joinToString(" + ")
    }

    fun actionSummary(): String {
        if (interceptIgnore) return "静默拦截（不入账）"
        val actions = mutableListOf<String>()
        if (targetCategory.isNotBlank()) actions.add("归入「$targetCategory」")
        if (targetLedger.isNotBlank()) actions.add("账本「$targetLedger」")
        if (markReimbursable) actions.add("标为待报销")
        if (appendTag.isNotBlank()) actions.add("标签#$appendTag")
        return if (actions.isEmpty()) "保持原样" else actions.joinToString(" · ")
    }
}

sealed class SmartRuleExecutionResult {
    data class Intercepted(val matchedRule: SmartConditionRule) : SmartRuleExecutionResult()
    data class Transformed(
        val transaction: TransactionEntity,
        val matchedRules: List<SmartConditionRule>
    ) : SmartRuleExecutionResult()
}

class SmartRuleEngine(context: Context) {

    private val prefs = context.getSharedPreferences("moji_smart_rules_prefs", Context.MODE_PRIVATE)
    private val jsonCodec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _rules = MutableStateFlow(loadRules())
    val rules: StateFlow<List<SmartConditionRule>> = _rules.asStateFlow()

    private fun loadRules(): List<SmartConditionRule> {
        val raw = prefs.getString("smart_rules_json", null)
        if (raw.isNullOrBlank()) {
            val defaults = defaultPresetRules()
            saveInternal(defaults)
            return defaults
        }
        return try {
            jsonCodec.decodeFromString<List<SmartConditionRule>>(raw)
        } catch (_: Exception) {
            defaultPresetRules()
        }
    }

    private fun saveInternal(list: List<SmartConditionRule>) {
        prefs.edit().putString("smart_rules_json", jsonCodec.encodeToString(list)).apply()
    }

    fun upsertRule(rule: SmartConditionRule) {
        val current = _rules.value.toMutableList()
        val idx = current.indexOfFirst { it.id == rule.id }
        if (idx >= 0) {
            current[idx] = rule
        } else {
            current.add(0, rule)
        }
        _rules.value = current
        saveInternal(current)
    }

    fun toggleRuleEnabled(ruleId: Long, enabled: Boolean) {
        val updated = _rules.value.map { if (it.id == ruleId) it.copy(enabled = enabled) else it }
        _rules.value = updated
        saveInternal(updated)
    }

    fun deleteRule(ruleId: Long) {
        val updated = _rules.value.filterNot { it.id == ruleId }
        _rules.value = updated
        saveInternal(updated)
    }

    /**
     * 当分类被重命名或删除迁移时，同步级联更新条件规则中的 targetCategory
     */
    fun migrateCategoryCascade(
        oldCategory: String,
        newCategory: String,
        includeSubPrefix: Boolean,
        preserveSubSuffix: Boolean,
        isExpense: Boolean? = null
    ) {
        var changed = false
        val updated = _rules.value.map { rule ->
            if (isExpense == false && rule.onlyExpense) return@map rule
            val mapped = CategoryCatalog.remapCascadedCategory(
                current = rule.targetCategory,
                oldCategory = oldCategory,
                newCategory = newCategory,
                includeSubPrefix = includeSubPrefix,
                preserveSubSuffix = preserveSubSuffix
            )
            if (mapped != null) {
                changed = true
                rule.copy(targetCategory = mapped)
            } else {
                rule
            }
        }
        if (changed) {
            _rules.value = updated
            saveInternal(updated)
        }
    }

    /**
     * 当标签被重命名、合并或从规则中移除时，精确按 Token 级联更新条件规则中的 appendTag
     */
    fun migrateTagCascade(
        oldTag: String,
        newTag: String?
    ) {
        var changed = false
        val updated = _rules.value.map { rule ->
            val mapped = TagCatalog.remapTagsString(rule.appendTag, oldTag, newTag)
            if (mapped != null && mapped != rule.appendTag) {
                changed = true
                rule.copy(appendTag = mapped)
            } else {
                rule
            }
        }
        if (changed) {
            _rules.value = updated
            saveInternal(updated)
        }
    }

    /**
     * 对 AI 或离线引擎产出的候选账单执行用户自定义 When -> Then 规则管道
     */
    fun evaluateAndApply(
        candidate: TransactionEntity,
        rawText: String
    ): SmartRuleExecutionResult {
        val activeRules = _rules.value.filter { it.enabled }
        if (activeRules.isEmpty()) {
            return SmartRuleExecutionResult.Transformed(candidate, emptyList())
        }

        val cal = Calendar.getInstance().apply { timeInMillis = candidate.timestamp }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val amountYuan = candidate.amountCnyCents / 100.0
        val haystack = "${candidate.merchant} ${candidate.category} ${candidate.note} $rawText"

        var currentTx = candidate
        val hitRules = mutableListOf<SmartConditionRule>()

        for (rule in activeRules) {
            if (rule.onlyExpense && currentTx.type != TransactionType.EXPENSE) continue

            // 1. 关键词匹配
            if (rule.keywordContains.isNotBlank()) {
                val keywords = rule.keywordContains
                    .split(",", "，", "|", " ")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (keywords.isNotEmpty() && keywords.none { kw -> haystack.contains(kw, ignoreCase = true) }) {
                    continue
                }
            }

            // 2. 金额区间匹配
            if (rule.minAmountYuan != null && amountYuan < rule.minAmountYuan) continue
            if (rule.maxAmountYuan != null && amountYuan > rule.maxAmountYuan) continue

            // 3. 时间段匹配（支持跨午夜，如 21:00 ~ 03:59）
            if (rule.startHour24 != null && rule.endHour24 != null) {
                val s = rule.startHour24.coerceIn(0, 23)
                val e = rule.endHour24.coerceIn(0, 23)
                val inWindow = if (s <= e) {
                    hour in s..e
                } else {
                    hour >= s || hour <= e
                }
                if (!inWindow) continue
            }

            // 命中规则：若为拦截规则则立即终止入账
            if (rule.interceptIgnore) {
                return SmartRuleExecutionResult.Intercepted(rule)
            }

            hitRules.add(rule)
            val newTags = if (rule.appendTag.isNotBlank()) {
                TagCatalog.joinTags(TagCatalog.parseTags(currentTx.tags) + TagCatalog.parseTags(rule.appendTag))
            } else {
                currentTx.tags
            }

            currentTx = currentTx.copy(
                category = rule.targetCategory.ifBlank { currentTx.category },
                ledgerName = rule.targetLedger.ifBlank { currentTx.ledgerName },
                reimbursable = if (rule.markReimbursable) true else currentTx.reimbursable,
                tags = newTags,
                sourceWalletId = rule.overrideSourceWalletId ?: currentTx.sourceWalletId
            )
        }

        return SmartRuleExecutionResult.Transformed(currentTx, hitRules)
    }

    companion object {
        fun defaultPresetRules(): List<SmartConditionRule> = listOf(
            SmartConditionRule(
                id = 101L,
                name = "晨间小额早餐自动归类",
                enabled = true,
                keywordContains = "包子,豆浆,早餐,便利,唐久,罗森,7-11,麦当劳,肯德基,瑞幸",
                minAmountYuan = 1.0,
                maxAmountYuan = 28.0,
                startHour24 = 6,
                endHour24 = 9,
                onlyExpense = true,
                targetCategory = "餐饮-三餐",
                appendTag = "早餐"
            ),
            SmartConditionRule(
                id = 102L,
                name = "打车差旅自动标记待报销",
                enabled = true,
                keywordContains = "滴滴,高德打车,曹操出行,T3出行,享道出行,出租车",
                minAmountYuan = 15.0,
                onlyExpense = true,
                targetCategory = "交通-打车",
                markReimbursable = true,
                appendTag = "差旅交通"
            ),
            SmartConditionRule(
                id = 103L,
                name = "深夜外卖夜宵自动打标",
                enabled = true,
                keywordContains = "外卖,美团,饿了么,烧烤,炸鸡,海底捞",
                startHour24 = 21,
                endHour24 = 3,
                onlyExpense = true,
                targetCategory = "餐饮-三餐",
                appendTag = "夜宵"
            ),
            SmartConditionRule(
                id = 104L,
                name = "验证码与安全校验静默拦截",
                enabled = true,
                keywordContains = "验证码,动态码,安全校验,登录提醒",
                onlyExpense = false,
                interceptIgnore = true
            )
        )
    }
}
