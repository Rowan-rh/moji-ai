package com.moji.ai.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moji.ai.MoJiApp
import com.moji.ai.ai.AiConfig
import com.moji.ai.ai.MerchantMemoryEntry
import com.moji.ai.ai.SmartConditionRule
import com.moji.ai.data.backup.BackupAndCsvTransferEngine
import com.moji.ai.data.backup.CsvParsePreviewResult
import com.moji.ai.data.backup.WebDavConfig
import com.moji.ai.data.backup.WebDavSyncClient
import com.moji.ai.data.local.RecurringRuleEntity
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.AppLockManager
import com.moji.ai.data.model.CategoryCatalog
import com.moji.ai.data.model.CategoryDef
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.CurrencyRateManager
import com.moji.ai.data.model.PasswordProtectionConfig
import com.moji.ai.data.model.PinVerifyResult
import com.moji.ai.data.model.RecurrenceFrequency
import com.moji.ai.data.model.SubCategoryDef
import com.moji.ai.data.model.TagCatalog
import com.moji.ai.data.model.TagDef
import com.moji.ai.data.model.TransactionType
import com.moji.ai.service.CaptureObservabilityLogEntry
import com.moji.ai.update.AppUpdateConfig
import com.moji.ai.update.ReleaseUpdateInfo
import com.moji.ai.update.UpdateCheckState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

@Serializable
data class QuickBookkeepingTemplate(
    val id: Long = 0L,
    val title: String = "",
    val typeName: String = "EXPENSE",
    val category: String = "餐饮",
    val amountCents: Long = 0L,
    val currency: String = "CNY",
    val walletId: Long? = 4L,
    val targetWalletId: Long? = null,
    val note: String = "",
    val tags: String = "",
    val reimbursable: Boolean = false
) {
    fun resolvedType(): TransactionType {
        return when (runCatching { TransactionType.valueOf(typeName) }.getOrDefault(TransactionType.EXPENSE)) {
            TransactionType.TRANSFER, TransactionType.REPAYMENT -> TransactionType.TRANSFER
            TransactionType.INCOME, TransactionType.LOAN_IN, TransactionType.REFUND_OFFSET -> TransactionType.INCOME
            else -> TransactionType.EXPENSE
        }
    }
}

sealed class TemplateExecutionOutcome {
    data class ReadyToRecord(val transaction: TransactionEntity) : TemplateExecutionOutcome()
    data class OpenInCalculator(
        val template: QuickBookkeepingTemplate,
        val message: String
    ) : TemplateExecutionOutcome()
}

object QuickTemplateHelper {
    const val MAX_TEMPLATE_TITLE_LENGTH = 20

    fun validateTemplateInput(
        title: String,
        type: TransactionType,
        amountInput: String,
        walletId: Long?,
        targetWalletId: Long?
    ): Result<Long> {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) {
            return Result.failure(IllegalArgumentException("请输入模板名称"))
        }
        if (cleanTitle.length > MAX_TEMPLATE_TITLE_LENGTH) {
            return Result.failure(IllegalArgumentException("模板名称不能超过 $MAX_TEMPLATE_TITLE_LENGTH 个字符"))
        }
        val trimmedAmount = amountInput.trim()
        val cents = if (trimmedAmount.isEmpty()) {
            0L
        } else {
            val parsed = trimmedAmount.toDoubleOrNull()
                ?: return Result.failure(IllegalArgumentException("请输入合法的预设金额"))
            if (parsed.isNaN() || parsed.isInfinite() || parsed < 0.0) {
                return Result.failure(IllegalArgumentException("预设金额不能为负数"))
            }
            if (parsed >= 1_000_000_000.0) {
                return Result.failure(IllegalArgumentException("预设金额过大，最多 9 位整数"))
            }
            (parsed * 100.0).roundToLong().coerceAtLeast(0L)
        }
        if (type == TransactionType.TRANSFER) {
            if (walletId == null) {
                return Result.failure(IllegalArgumentException("请选择转出账户"))
            }
            if (targetWalletId == null) {
                return Result.failure(IllegalArgumentException("请选择转入账户"))
            }
            if (walletId == targetWalletId) {
                return Result.failure(IllegalArgumentException("转出与转入账户不能相同"))
            }
        }
        return Result.success(cents)
    }

    fun sanitizeTemplates(
        rawList: List<QuickBookkeepingTemplate>,
        fallbackBaseId: Long = 1000L
    ): List<QuickBookkeepingTemplate> {
        val usedIds = mutableSetOf<Long>()
        var nextFallbackId = fallbackBaseId
        return rawList.mapNotNull { item ->
            val cleanTitle = item.title.trim().take(MAX_TEMPLATE_TITLE_LENGTH)
            if (cleanTitle.isEmpty()) return@mapNotNull null
            val resolvedType = item.resolvedType()
            val cleanCategory = when (resolvedType) {
                TransactionType.TRANSFER -> "内部转账"
                TransactionType.INCOME -> item.category.trim().ifEmpty { "工资" }
                else -> item.category.trim().ifEmpty { "餐饮" }
            }
            val cleanCents = item.amountCents.coerceAtLeast(0L)
            val cleanCurrency = CurrencyCode.fromCode(item.currency).name
            val cleanTargetWalletId = if (resolvedType == TransactionType.TRANSFER) {
                item.targetWalletId?.takeIf { it != item.walletId }
            } else {
                null
            }
            val cleanNote = item.note.trim().take(60)
            val cleanTags = TagCatalog.joinTags(TagCatalog.parseTags(item.tags))
            val cleanReimbursable = resolvedType == TransactionType.EXPENSE && item.reimbursable
            val candidateId = if (item.id > 0L && usedIds.add(item.id)) {
                item.id
            } else {
                while (!usedIds.add(nextFallbackId)) {
                    nextFallbackId++
                }
                nextFallbackId
            }
            QuickBookkeepingTemplate(
                id = candidateId,
                title = cleanTitle,
                typeName = resolvedType.name,
                category = cleanCategory,
                amountCents = cleanCents,
                currency = cleanCurrency,
                walletId = item.walletId,
                targetWalletId = cleanTargetWalletId,
                note = cleanNote,
                tags = cleanTags,
                reimbursable = cleanReimbursable
            )
        }
    }

    private fun resolveExistingCategoryOrFallback(
        isExpense: Boolean,
        preferredFull: String,
        fallbackMain: String
    ): String {
        val list = if (isExpense) CategoryCatalog.expenseCategories else CategoryCatalog.incomeCategories
        val parts = preferredFull.split("-")
        val mainName = parts.firstOrNull().orEmpty()
        val subName = parts.getOrNull(1).orEmpty()
        val mainCat = list.firstOrNull { it.name == mainName }
            ?: list.firstOrNull { it.name == fallbackMain }
            ?: list.firstOrNull()
            ?: return fallbackMain
        return if (subName.isNotBlank() && mainCat.subCategories.any { it.name == subName }) {
            "${mainCat.name}-$subName"
        } else {
            mainCat.name
        }
    }

    fun buildPresetTemplates(wallets: List<WalletEntity>): List<QuickBookkeepingTemplate> {
        val assetWallets = wallets.filter { !it.type.isLiability }
        val liabilityWallets = wallets.filter { it.type.isLiability }
        val defaultExpenseWalletId = assetWallets.firstOrNull {
            it.name.contains("微信") || it.name.contains("支付宝")
        }?.id ?: assetWallets.firstOrNull()?.id ?: wallets.firstOrNull()?.id ?: 4L
        val defaultIncomeWalletId = assetWallets.firstOrNull {
            it.name.contains("银行") || it.name.contains("储蓄") || it.name.contains("工资")
        }?.id ?: defaultExpenseWalletId
        val defaultCreditWalletId = liabilityWallets.firstOrNull()?.id
            ?: wallets.firstOrNull { it.id != defaultExpenseWalletId }?.id

        return listOf(
            QuickBookkeepingTemplate(
                id = 1L,
                title = "早餐",
                typeName = TransactionType.EXPENSE.name,
                category = resolveExistingCategoryOrFallback(true, "餐饮-三餐", "餐饮"),
                amountCents = 1200L,
                currency = "CNY",
                walletId = defaultExpenseWalletId,
                note = "早餐",
                tags = "早餐"
            ),
            QuickBookkeepingTemplate(
                id = 2L,
                title = "通勤地铁",
                typeName = TransactionType.EXPENSE.name,
                category = resolveExistingCategoryOrFallback(true, "交通-地铁", "交通"),
                amountCents = 400L,
                currency = "CNY",
                walletId = defaultExpenseWalletId,
                note = "通勤地铁",
                tags = "固定开支"
            ),
            QuickBookkeepingTemplate(
                id = 3L,
                title = "工作午餐",
                typeName = TransactionType.EXPENSE.name,
                category = resolveExistingCategoryOrFallback(true, "餐饮-三餐", "餐饮"),
                amountCents = 2500L,
                currency = "CNY",
                walletId = defaultExpenseWalletId,
                note = "工作午餐",
                tags = ""
            ),
            QuickBookkeepingTemplate(
                id = 4L,
                title = "瑞幸咖啡",
                typeName = TransactionType.EXPENSE.name,
                category = resolveExistingCategoryOrFallback(true, "餐饮-咖啡", "餐饮"),
                amountCents = 990L,
                currency = "CNY",
                walletId = defaultExpenseWalletId,
                note = "瑞幸咖啡",
                tags = ""
            ),
            QuickBookkeepingTemplate(
                id = 5L,
                title = "日常超市",
                typeName = TransactionType.EXPENSE.name,
                category = resolveExistingCategoryOrFallback(true, "购物-超市", "购物"),
                amountCents = 0L,
                currency = "CNY",
                walletId = defaultExpenseWalletId,
                note = "日常超市采购",
                tags = ""
            ),
            QuickBookkeepingTemplate(
                id = 6L,
                title = "每月房租",
                typeName = TransactionType.EXPENSE.name,
                category = resolveExistingCategoryOrFallback(true, "住房-房租", "住房"),
                amountCents = 250_000L,
                currency = "CNY",
                walletId = defaultExpenseWalletId,
                note = "每月房租",
                tags = "固定开支"
            ),
            QuickBookkeepingTemplate(
                id = 7L,
                title = "发工资",
                typeName = TransactionType.INCOME.name,
                category = resolveExistingCategoryOrFallback(false, "工资", "工资"),
                amountCents = 0L,
                currency = "CNY",
                walletId = defaultIncomeWalletId,
                note = "月度薪资入账",
                tags = ""
            ),
            QuickBookkeepingTemplate(
                id = 8L,
                title = "信用卡还款",
                typeName = TransactionType.TRANSFER.name,
                category = "内部转账",
                amountCents = 0L,
                currency = "CNY",
                walletId = defaultExpenseWalletId,
                targetWalletId = defaultCreditWalletId,
                note = "信用卡账单还款",
                tags = ""
            )
        )
    }

    fun mergePresetTemplates(
        existing: List<QuickBookkeepingTemplate>,
        presets: List<QuickBookkeepingTemplate>,
        baseId: Long = System.currentTimeMillis()
    ): Pair<List<QuickBookkeepingTemplate>, Int> {
        val usedIds = existing.map { it.id }.toMutableSet()
        var nextId = baseId.coerceAtLeast((usedIds.maxOrNull() ?: 0L) + 1L)
        val toAdd = mutableListOf<QuickBookkeepingTemplate>()
        for (preset in presets) {
            val alreadyExists = existing.any {
                it.title.equals(preset.title, ignoreCase = true) &&
                    it.resolvedType() == preset.resolvedType()
            } || toAdd.any {
                it.title.equals(preset.title, ignoreCase = true) &&
                    it.resolvedType() == preset.resolvedType()
            }
            if (!alreadyExists) {
                while (!usedIds.add(nextId)) {
                    nextId++
                }
                toAdd.add(preset.copy(id = nextId))
            }
        }
        return (existing + toAdd) to toAdd.size
    }

    fun upsertTemplate(
        existing: List<QuickBookkeepingTemplate>,
        candidate: QuickBookkeepingTemplate,
        nowId: Long = System.currentTimeMillis()
    ): Pair<List<QuickBookkeepingTemplate>, QuickBookkeepingTemplate> {
        val sanitizedSingle = sanitizeTemplates(
            listOf(candidate.copy(id = if (candidate.id > 0L) candidate.id else nowId)),
            fallbackBaseId = nowId
        ).first()
        val existingIdx = existing.indexOfFirst { it.id == sanitizedSingle.id }
        val updatedList = if (existingIdx >= 0) {
            existing.toMutableList().apply { set(existingIdx, sanitizedSingle) }
        } else {
            val usedIds = existing.map { it.id }.toMutableSet()
            var finalId = sanitizedSingle.id
            while (!usedIds.add(finalId)) {
                finalId++
            }
            val inserted = sanitizedSingle.copy(id = finalId)
            return (listOf(inserted) + existing) to inserted
        }
        return updatedList to sanitizedSingle
    }

    fun duplicateTemplate(
        existing: List<QuickBookkeepingTemplate>,
        sourceId: Long,
        newId: Long = System.currentTimeMillis()
    ): Pair<List<QuickBookkeepingTemplate>, QuickBookkeepingTemplate?> {
        val idx = existing.indexOfFirst { it.id == sourceId }
        if (idx < 0) return existing to null
        val src = existing[idx]
        val usedIds = existing.map { it.id }.toMutableSet()
        var candidateId = newId.coerceAtLeast((usedIds.maxOrNull() ?: 0L) + 1L)
        while (!usedIds.add(candidateId)) {
            candidateId++
        }
        val baseTitle = src.title.trim()
        val copyTitle = if (baseTitle.length <= MAX_TEMPLATE_TITLE_LENGTH - 3) {
            "$baseTitle 副本"
        } else {
            "${baseTitle.take(MAX_TEMPLATE_TITLE_LENGTH - 2)}副本"
        }
        val duplicated = src.copy(
            id = candidateId,
            title = copyTitle
        )
        val mutable = existing.toMutableList()
        mutable.add(idx + 1, duplicated)
        return mutable.toList() to duplicated
    }

    fun moveTemplate(
        existing: List<QuickBookkeepingTemplate>,
        index: Int,
        delta: Int
    ): List<QuickBookkeepingTemplate> {
        if (index !in existing.indices) return existing
        val targetIndex = index + delta
        if (targetIndex !in existing.indices) return existing
        val mutable = existing.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(targetIndex, item)
        return mutable.toList()
    }

    fun cascadeCategoryInTemplates(
        templates: List<QuickBookkeepingTemplate>,
        oldCategory: String,
        newCategory: String,
        includeSubPrefix: Boolean,
        preserveSubSuffix: Boolean,
        isExpense: Boolean?
    ): Pair<List<QuickBookkeepingTemplate>, Boolean> {
        var changed = false
        val updated = templates.map { tpl ->
            val resolvedType = tpl.resolvedType()
            // 转账模板固定为内部转账，不参与支出/收入分类级联
            if (resolvedType == TransactionType.TRANSFER) return@map tpl
            val tplIsExpense = resolvedType == TransactionType.EXPENSE
            if (isExpense != null && tplIsExpense != isExpense) return@map tpl
            val mapped = CategoryCatalog.remapCascadedCategory(
                current = tpl.category,
                oldCategory = oldCategory,
                newCategory = newCategory,
                includeSubPrefix = includeSubPrefix,
                preserveSubSuffix = preserveSubSuffix
            )
            if (mapped != null && mapped != tpl.category) {
                changed = true
                tpl.copy(category = mapped)
            } else {
                tpl
            }
        }
        return updated to changed
    }

    fun cascadeTagInTemplates(
        templates: List<QuickBookkeepingTemplate>,
        oldTag: String,
        newTag: String?
    ): Pair<List<QuickBookkeepingTemplate>, Boolean> {
        var changed = false
        val updated = templates.map { tpl ->
            val mapped = TagCatalog.remapTagsString(tpl.tags, oldTag, newTag)
            if (mapped != null && mapped != tpl.tags) {
                changed = true
                tpl.copy(tags = mapped)
            } else {
                tpl
            }
        }
        return updated to changed
    }

    fun evaluateQuickRecordOutcome(
        tpl: QuickBookkeepingTemplate,
        wallets: List<WalletEntity>,
        ledgerName: String
    ): TemplateExecutionOutcome {
        val resolvedType = tpl.resolvedType()
        if (tpl.amountCents <= 0L) {
            return TemplateExecutionOutcome.OpenInCalculator(
                template = tpl,
                message = "已填入模板「${tpl.title}」，请输入本次记账金额"
            )
        }
        val rate = CurrencyRateManager.getEffectiveRateToCny(tpl.currency)
        val cnyCents = (tpl.amountCents * rate).roundToLong()
        val normalizedTags = TagCatalog.joinTags(TagCatalog.parseTags(tpl.tags))

        if (resolvedType == TransactionType.TRANSFER) {
            val fromWallet = wallets.firstOrNull { it.id == tpl.walletId }
            val toWallet = wallets.firstOrNull { it.id == tpl.targetWalletId }
            if (fromWallet == null || toWallet == null || fromWallet.id == toWallet.id) {
                return TemplateExecutionOutcome.OpenInCalculator(
                    template = tpl,
                    message = "请确认「${tpl.title}」的转出与转入账户后记账"
                )
            }
            val actualType = if (!fromWallet.type.isLiability && toWallet.type.isLiability) {
                TransactionType.REPAYMENT
            } else {
                TransactionType.TRANSFER
            }
            val finalCategory = if (actualType == TransactionType.REPAYMENT) "信用还款" else "内部转账"
            val tx = TransactionEntity(
                ledgerName = ledgerName,
                type = actualType,
                amountCents = tpl.amountCents,
                currency = tpl.currency,
                exchangeRateToCny = rate,
                amountCnyCents = cnyCents,
                category = finalCategory,
                merchant = tpl.title,
                note = tpl.note.ifBlank { tpl.title },
                tags = normalizedTags,
                sourceWalletId = fromWallet.id,
                targetWalletId = toWallet.id,
                reimbursable = false,
                captureSource = "TEMPLATE"
            )
            return TemplateExecutionOutcome.ReadyToRecord(tx)
        }

        val fallbackWallet = wallets.firstOrNull { !it.type.isLiability } ?: wallets.firstOrNull()
        val resolvedWallet = wallets.firstOrNull { it.id == tpl.walletId } ?: fallbackWallet
        val tx = TransactionEntity(
            ledgerName = ledgerName,
            type = resolvedType,
            amountCents = tpl.amountCents,
            currency = tpl.currency,
            exchangeRateToCny = rate,
            amountCnyCents = cnyCents,
            category = tpl.category,
            merchant = tpl.title,
            note = tpl.note.ifBlank { tpl.title },
            tags = normalizedTags,
            sourceWalletId = if (resolvedType == TransactionType.INCOME) null else resolvedWallet?.id,
            targetWalletId = if (resolvedType == TransactionType.INCOME) resolvedWallet?.id else null,
            reimbursable = resolvedType == TransactionType.EXPENSE && tpl.reimbursable,
            captureSource = "TEMPLATE"
        )
        return TemplateExecutionOutcome.ReadyToRecord(tx)
    }
}

@Serializable
data class SavingPlanItem(
    val id: Long,
    val title: String,
    val targetCents: Long,
    val savedCents: Long,
    val deadlineLabel: String = "2026-12-31"
)

@Serializable
data class InstallmentPlanItem(
    val id: Long,
    val title: String,
    val walletId: Long = 1L,
    val totalCents: Long,
    val feePerPeriodCents: Long = 0L,
    val totalPeriods: Int = 12,
    val paidPeriods: Int = 3,
    val category: String = "数码"
)

/**
 * 一次性的界面提示（Snackbar）。onUndo 不为空时提供「撤销」按钮。
 */
data class UiMessage(
    val text: String,
    val onUndo: (() -> Unit)? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MoJiApp
    private val repository = app.repository
    private val configManager = app.aiConfigManager
    private val uiPrefs = application.getSharedPreferences("moji_icost_ui_prefs", Context.MODE_PRIVATE)

    val wallets: StateFlow<List<WalletEntity>> = repository.walletsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val transactions: StateFlow<List<TransactionEntity>> = repository.transactionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recurringRules: StateFlow<List<RecurringRuleEntity>> = repository.recurringRulesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val merchantMemories: StateFlow<List<MerchantMemoryEntry>> = app.merchantMemoryManager.memoriesFlow

    val smartRules: StateFlow<List<SmartConditionRule>> = app.smartRuleEngine.rules

    val expenseCategories: StateFlow<List<CategoryDef>> = CategoryCatalog.expenseCategoriesFlow

    val incomeCategories: StateFlow<List<CategoryDef>> = CategoryCatalog.incomeCategoriesFlow

    val tags: StateFlow<List<TagDef>> = TagCatalog.tagsFlow

    val customCurrencyRates: StateFlow<Map<String, Double>> = CurrencyRateManager.customRatesFlow

    val passwordProtectionConfig: StateFlow<PasswordProtectionConfig> = AppLockManager.configFlow

    val isAppSessionLocked: StateFlow<Boolean> = AppLockManager.isSessionLockedFlow

    val observabilityLogs: StateFlow<List<CaptureObservabilityLogEntry>> = app.captureCoordinator.observabilityLogs

    val appUpdateConfig: StateFlow<AppUpdateConfig> = app.appUpdateManager.configFlow

    val appUpdateState: StateFlow<UpdateCheckState> = app.appUpdateManager.stateFlow

    private val _webDavConfig = MutableStateFlow(WebDavSyncClient.loadConfig(application))
    val webDavConfig: StateFlow<WebDavConfig> = _webDavConfig.asStateFlow()

    /**
     * 从流体云胶囊或系统通知点击跳转进入 App 时，待直接打开详情页的账单 ID
     */
    private val _pendingOpenTransactionId = MutableStateFlow<Long?>(null)
    val pendingOpenTransactionId: StateFlow<Long?> = _pendingOpenTransactionId.asStateFlow()

    fun requestOpenTransactionDetail(txId: Long) {
        if (txId > 0L) {
            _pendingOpenTransactionId.value = txId
        }
    }

    fun consumePendingOpenTransactionDetail() {
        _pendingOpenTransactionId.value = null
    }

    suspend fun getTransactionByIdDirect(txId: Long): TransactionEntity? {
        return repository.getTransactionById(txId)
    }

    private val _ledgers = MutableStateFlow(loadSavedLedgers())
    val ledgers: StateFlow<List<String>> = _ledgers.asStateFlow()

    private val _currentLedger = MutableStateFlow(
        uiPrefs.getString("current_ledger", "默认账本") ?: "默认账本"
    )
    val currentLedger: StateFlow<String> = _currentLedger.asStateFlow()

    private val _hideAssetAmounts = MutableStateFlow(
        uiPrefs.getBoolean("hide_asset_amounts", false)
    )
    val hideAssetAmounts: StateFlow<Boolean> = _hideAssetAmounts.asStateFlow()

    // 未设置预算时为 0，界面显示「未设置」而不是虚构一个默认金额
    private val _monthlyBudgetCents = MutableStateFlow(
        uiPrefs.getLong("monthly_budget_cents", 0L)
    )
    val monthlyBudgetCents: StateFlow<Long> = _monthlyBudgetCents.asStateFlow()

    private val _aiConfig = MutableStateFlow(configManager.getConfig())
    val aiConfig: StateFlow<AiConfig> = _aiConfig.asStateFlow()

    private val _sandboxStatus = MutableStateFlow<String?>(null)
    val sandboxStatus: StateFlow<String?> = _sandboxStatus.asStateFlow()

    private val jsonCodec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _uiMessages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val uiMessages: SharedFlow<UiMessage> = _uiMessages.asSharedFlow()

    /**
     * @param undoTransactionId 一键生成的账单 id：撤销时删除该账单并回滚余额
     * @param extraUndo 撤销时额外需要回滚的状态（如分期已还期数、周期账单下次触发时间）
     */
    private fun postMessage(
        text: String,
        undoTransactionId: Long? = null,
        extraUndo: (suspend () -> Unit)? = null
    ) {
        val onUndo: (() -> Unit)? = if (undoTransactionId != null || extraUndo != null) {
            {
                viewModelScope.launch {
                    undoTransactionId?.let { repository.undoAndDeleteTransaction(it) }
                    extraUndo?.invoke()
                }
            }
        } else null
        _uiMessages.tryEmit(UiMessage(text, onUndo))
    }

    private val _isParsing = MutableStateFlow(false)
    val isParsing: StateFlow<Boolean> = _isParsing.asStateFlow()

    private val _templates = MutableStateFlow(loadSavedTemplates())
    val templates: StateFlow<List<QuickBookkeepingTemplate>> = _templates.asStateFlow()

    private val _pendingTemplateDraftForCalc = MutableStateFlow<QuickBookkeepingTemplate?>(null)
    val pendingTemplateDraftForCalc: StateFlow<QuickBookkeepingTemplate?> = _pendingTemplateDraftForCalc.asStateFlow()

    private val _savingPlans = MutableStateFlow(loadSavedSavingPlans())
    val savingPlans: StateFlow<List<SavingPlanItem>> = _savingPlans.asStateFlow()

    private val _installmentPlans = MutableStateFlow(loadSavedInstallments())
    val installmentPlans: StateFlow<List<InstallmentPlanItem>> = _installmentPlans.asStateFlow()

    private fun loadSavedTemplates(): List<QuickBookkeepingTemplate> {
        val raw = uiPrefs.getString("saved_templates_json", null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                val decoded = jsonCodec.decodeFromString<List<QuickBookkeepingTemplate>>(raw)
                return QuickTemplateHelper.sanitizeTemplates(decoded)
            }
        }
        return emptyList()
    }

    private fun persistTemplates(updated: List<QuickBookkeepingTemplate>) {
        val clean = QuickTemplateHelper.sanitizeTemplates(updated)
        _templates.value = clean
        uiPrefs.edit().putString("saved_templates_json", jsonCodec.encodeToString(clean)).apply()
    }

    fun requestFillCalculatorWithTemplate(tpl: QuickBookkeepingTemplate, message: String? = null) {
        _pendingTemplateDraftForCalc.value = tpl
        if (!message.isNullOrBlank()) {
            postMessage(message)
        }
    }

    fun consumePendingTemplateDraftForCalc() {
        _pendingTemplateDraftForCalc.value = null
    }

    private fun loadSavedSavingPlans(): List<SavingPlanItem> {
        val raw = uiPrefs.getString("saved_saving_plans_json", null)
        if (!raw.isNullOrBlank()) {
            runCatching { return jsonCodec.decodeFromString<List<SavingPlanItem>>(raw) }
        }
        return emptyList()
    }

    private fun loadSavedInstallments(): List<InstallmentPlanItem> {
        val raw = uiPrefs.getString("saved_installments_json", null)
        if (!raw.isNullOrBlank()) {
            runCatching { return jsonCodec.decodeFromString<List<InstallmentPlanItem>>(raw) }
        }
        return emptyList()
    }

    fun saveTemplateFromTransaction(tx: TransactionEntity) {
        val normalizedType = when (tx.type) {
            TransactionType.INCOME, TransactionType.LOAN_IN, TransactionType.REFUND_OFFSET -> TransactionType.INCOME
            TransactionType.TRANSFER, TransactionType.REPAYMENT -> TransactionType.TRANSFER
            else -> TransactionType.EXPENSE
        }
        val primaryWalletId = when (normalizedType) {
            TransactionType.INCOME -> tx.targetWalletId ?: tx.sourceWalletId
            TransactionType.TRANSFER -> tx.sourceWalletId
            else -> tx.sourceWalletId ?: tx.targetWalletId
        }
        val secondaryWalletId = if (normalizedType == TransactionType.TRANSFER) tx.targetWalletId else null
        val title = tx.merchant.ifBlank { tx.note.ifBlank { tx.category } }.trim().take(QuickTemplateHelper.MAX_TEMPLATE_TITLE_LENGTH)
        val newItem = QuickBookkeepingTemplate(
            id = System.currentTimeMillis(),
            title = title.ifEmpty { tx.category },
            typeName = normalizedType.name,
            category = if (normalizedType == TransactionType.TRANSFER) {
                "内部转账"
            } else {
                tx.category
            },
            amountCents = tx.amountCents.coerceAtLeast(0L),
            currency = tx.currency.ifBlank { "CNY" },
            walletId = primaryWalletId,
            targetWalletId = secondaryWalletId,
            note = tx.note.ifBlank { tx.merchant },
            tags = TagCatalog.joinTags(TagCatalog.parseTags(tx.tags)),
            reimbursable = normalizedType == TransactionType.EXPENSE && tx.reimbursable
        )
        val duplicated = _templates.value.any {
            it.title == newItem.title &&
                it.category == newItem.category &&
                it.amountCents == newItem.amountCents &&
                it.resolvedType() == newItem.resolvedType() &&
                it.walletId == newItem.walletId &&
                it.targetWalletId == newItem.targetWalletId
        }
        if (duplicated) {
            postMessage("已存在相同的模板")
            return
        }
        val (updated, _) = QuickTemplateHelper.upsertTemplate(_templates.value, newItem)
        persistTemplates(updated)
        postMessage("已存为模板「${newItem.title}」，可在首页或模板管理中一键记账")
    }

    fun addCustomTemplate(title: String, category: String, amountYuan: Double, walletId: Long?) {
        val cents = (amountYuan * 100.0).roundToLong().coerceAtLeast(0L)
        val item = QuickBookkeepingTemplate(
            id = System.currentTimeMillis(),
            title = title.trim().ifEmpty { category }.take(QuickTemplateHelper.MAX_TEMPLATE_TITLE_LENGTH),
            typeName = "EXPENSE",
            category = category,
            amountCents = cents,
            currency = "CNY",
            walletId = walletId,
            note = title.trim()
        )
        val (updated, _) = QuickTemplateHelper.upsertTemplate(_templates.value, item)
        persistTemplates(updated)
    }

    fun upsertTemplate(
        template: QuickBookkeepingTemplate,
        onDone: (Result<QuickBookkeepingTemplate>) -> Unit = {}
    ) {
        val isEditing = _templates.value.any { it.id == template.id && template.id > 0L }
        val (updatedList, savedItem) = QuickTemplateHelper.upsertTemplate(_templates.value, template)
        persistTemplates(updatedList)
        TagCatalog.syncDiscoveredTagsFromHistory(listOf(savedItem.tags))
        postMessage(if (isEditing) "已更新模板「${savedItem.title}」" else "已新增模板「${savedItem.title}」")
        onDone(Result.success(savedItem))
    }

    fun duplicateTemplate(id: Long) {
        val (updated, copyItem) = QuickTemplateHelper.duplicateTemplate(_templates.value, id)
        if (copyItem != null) {
            persistTemplates(updated)
            postMessage("已复制模板「${copyItem.title}」")
        }
    }

    fun moveTemplate(index: Int, delta: Int) {
        val updated = QuickTemplateHelper.moveTemplate(_templates.value, index, delta)
        if (updated != _templates.value) {
            persistTemplates(updated)
        }
    }

    fun deleteTemplate(id: Long) {
        val target = _templates.value.firstOrNull { it.id == id }
        val updated = _templates.value.filterNot { it.id == id }
        persistTemplates(updated)
        if (target != null) {
            postMessage("已删除模板「${target.title}」")
        }
    }

    fun clearAllTemplates() {
        if (_templates.value.isEmpty()) return
        persistTemplates(emptyList())
        postMessage("已清空全部记账模板")
    }

    fun addPresetTemplates() {
        val presets = QuickTemplateHelper.buildPresetTemplates(wallets.value)
        val (merged, addedCount) = QuickTemplateHelper.mergePresetTemplates(_templates.value, presets)
        if (addedCount > 0) {
            persistTemplates(merged)
            TagCatalog.syncDiscoveredTagsFromHistory(presets.map { it.tags })
            postMessage("已加载 $addedCount 个常用记账模板")
        } else {
            postMessage("常用预设模板已全部存在")
        }
    }

    fun updateCustomCurrencyRate(
        currencyCode: String,
        rawRateInput: String,
        onDone: (Result<Double>) -> Unit = {}
    ) {
        val res = CurrencyRateManager.setCustomRate(currencyCode, rawRateInput)
        res.fold(
            onSuccess = { rate ->
                val code = CurrencyCode.fromCode(currencyCode)
                postMessage("已更新 ${code.displayName} 汇率为 1 ${code.name} = ¥${CurrencyRateManager.formatRate(rate)}")
            },
            onFailure = { err ->
                postMessage(err.message ?: "汇率格式无效")
            }
        )
        onDone(res)
    }

    fun resetCurrencyRate(currencyCode: String) {
        val code = CurrencyCode.fromCode(currencyCode)
        if (code == CurrencyCode.CNY) return
        CurrencyRateManager.resetCurrencyRate(code.name)
        postMessage("已恢复 ${code.displayName} 默认汇率（1 ${code.name} = ¥${CurrencyRateManager.formatRate(code.defaultRateToCny)}）")
    }

    fun resetAllCustomCurrencyRates() {
        CurrencyRateManager.resetAllCustomRates()
        postMessage("已恢复全部币种默认参考汇率")
    }

    fun applyQuickTemplateNow(tpl: QuickBookkeepingTemplate) {
        viewModelScope.launch {
            val allWallets = wallets.value.ifEmpty { repository.getAllWallets() }
            when (val outcome = QuickTemplateHelper.evaluateQuickRecordOutcome(tpl, allWallets, _currentLedger.value)) {
                is TemplateExecutionOutcome.OpenInCalculator -> {
                    requestFillCalculatorWithTemplate(outcome.template, outcome.message)
                }
                is TemplateExecutionOutcome.ReadyToRecord -> {
                    val newId = repository.recordTransaction(outcome.transaction)
                    postMessage("已记一笔「${tpl.title}」", undoTransactionId = newId)
                }
            }
        }
    }

    fun duplicateTransactionNow(tx: TransactionEntity) {
        viewModelScope.launch {
            val copyTx = tx.copy(
                id = 0L,
                refundedAmountCents = 0L,
                reimbursed = false,
                timestamp = System.currentTimeMillis(),
                captureSource = "MANUAL_COPY"
            )
            val newId = repository.recordTransaction(copyTx)
            postMessage("已复制一笔到今天", undoTransactionId = newId)
        }
    }

    fun toggleOrSettleReimbursement(tx: TransactionEntity) {
        viewModelScope.launch {
            if (!tx.reimbursable) {
                repository.updateExistingTransaction(tx.copy(reimbursable = true))
                postMessage("已标记为待报销")
            } else if (!tx.reimbursed) {
                repository.settleReimbursement(tx.id, tx.sourceWalletId)
                postMessage("报销已入账")
            }
        }
    }

    fun addSavingPlan(title: String, targetYuan: Double, savedYuan: Double, deadlineLabel: String) {
        val item = SavingPlanItem(
            id = System.currentTimeMillis(),
            title = title.trim().ifEmpty { "存钱计划" },
            targetCents = (targetYuan * 100.0).roundToLong().coerceAtLeast(100L),
            savedCents = (savedYuan * 100.0).roundToLong().coerceAtLeast(0L),
            deadlineLabel = deadlineLabel.ifBlank { "2026-12-31" }
        )
        val updated = listOf(item) + _savingPlans.value
        _savingPlans.value = updated
        uiPrefs.edit().putString("saved_saving_plans_json", jsonCodec.encodeToString(updated)).apply()
    }

    fun depositToSavingPlan(planId: Long, depositYuan: Double) {
        val addCents = (depositYuan * 100.0).roundToLong().coerceAtLeast(0L)
        if (addCents <= 0L) return
        val updated = _savingPlans.value.map {
            if (it.id == planId) it.copy(savedCents = it.savedCents + addCents) else it
        }
        _savingPlans.value = updated
        uiPrefs.edit().putString("saved_saving_plans_json", jsonCodec.encodeToString(updated)).apply()
        postMessage("已存入 ¥${String.format("%.2f", addCents / 100.0)}")
    }

    fun deleteSavingPlan(planId: Long) {
        val updated = _savingPlans.value.filterNot { it.id == planId }
        _savingPlans.value = updated
        uiPrefs.edit().putString("saved_saving_plans_json", jsonCodec.encodeToString(updated)).apply()
    }

    fun addInstallmentPlan(
        title: String,
        walletId: Long,
        totalYuan: Double,
        feePerPeriodYuan: Double,
        totalPeriods: Int,
        category: String = "数码"
    ) {
        val item = InstallmentPlanItem(
            id = System.currentTimeMillis(),
            title = title.trim().ifEmpty { "分期账单" },
            walletId = walletId,
            totalCents = (totalYuan * 100.0).roundToLong().coerceAtLeast(100L),
            feePerPeriodCents = (feePerPeriodYuan * 100.0).roundToLong().coerceAtLeast(0L),
            totalPeriods = totalPeriods.coerceIn(2, 60),
            paidPeriods = 0,
            category = category
        )
        val updated = listOf(item) + _installmentPlans.value
        _installmentPlans.value = updated
        uiPrefs.edit().putString("saved_installments_json", jsonCodec.encodeToString(updated)).apply()
    }

    fun payNextInstallmentPeriod(plan: InstallmentPlanItem) {
        if (plan.paidPeriods >= plan.totalPeriods) return
        val nextPeriod = plan.paidPeriods + 1
        val periods = plan.totalPeriods.coerceAtLeast(1)
        // 整除余下的分摊到最后一期，保证各期合计等于分期总额
        val principalPerPeriod = plan.totalCents / periods +
            if (nextPeriod == periods) plan.totalCents % periods else 0L
        val totalPeriodCents = principalPerPeriod + plan.feePerPeriodCents

        val updated = _installmentPlans.value.map {
            if (it.id == plan.id) it.copy(paidPeriods = nextPeriod) else it
        }
        _installmentPlans.value = updated
        uiPrefs.edit().putString("saved_installments_json", jsonCodec.encodeToString(updated)).apply()

        viewModelScope.launch {
            val tx = TransactionEntity(
                ledgerName = _currentLedger.value,
                type = TransactionType.EXPENSE,
                amountCents = totalPeriodCents,
                currency = "CNY",
                exchangeRateToCny = 1.0,
                amountCnyCents = totalPeriodCents,
                // 手续费已包含在 amountCents 中；再填 feeCents 会被账务引擎重复扣款
                feeCents = 0L,
                category = plan.category,
                merchant = "${plan.title} (${nextPeriod}/${plan.totalPeriods}期)",
                note = if (plan.feePerPeriodCents > 0L) {
                    "分期第${nextPeriod}期，含手续费 ¥${String.format("%.2f", plan.feePerPeriodCents / 100.0)}"
                } else {
                    "分期第${nextPeriod}期"
                },
                sourceWalletId = plan.walletId,
                tags = "分期",
                captureSource = "INSTALLMENT"
            )
            val newId = repository.recordTransaction(tx)
            postMessage(
                "已记入第 ${nextPeriod}/${plan.totalPeriods} 期还款",
                undoTransactionId = newId,
                extraUndo = {
                    val reverted = _installmentPlans.value.map {
                        if (it.id == plan.id) it.copy(paidPeriods = (it.paidPeriods - 1).coerceAtLeast(0)) else it
                    }
                    _installmentPlans.value = reverted
                    uiPrefs.edit().putString("saved_installments_json", jsonCodec.encodeToString(reverted)).apply()
                }
            )
        }
    }

    fun deleteInstallmentPlan(planId: Long) {
        val updated = _installmentPlans.value.filterNot { it.id == planId }
        _installmentPlans.value = updated
        uiPrefs.edit().putString("saved_installments_json", jsonCodec.encodeToString(updated)).apply()
    }

    private fun loadSavedLedgers(): List<String> {
        val raw = uiPrefs.getString("saved_ledgers", null)
        if (raw.isNullOrBlank()) return listOf("默认账本", "日常家用", "旅行基金")
        val parsed = raw.split("||").map { it.trim() }.filter { it.isNotEmpty() }
        return if (parsed.isEmpty()) listOf("默认账本") else parsed
    }

    fun selectLedger(name: String) {
        _currentLedger.value = name
        uiPrefs.edit().putString("current_ledger", name).apply()
    }

    fun createLedger(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty() && trimmed !in _ledgers.value) {
            val updated = _ledgers.value + trimmed
            _ledgers.value = updated
            uiPrefs.edit().putString("saved_ledgers", updated.joinToString("||")).apply()
        }
        if (trimmed.isNotEmpty()) {
            selectLedger(trimmed)
        }
    }

    fun toggleHideAssetAmounts() {
        val next = !_hideAssetAmounts.value
        _hideAssetAmounts.value = next
        uiPrefs.edit().putBoolean("hide_asset_amounts", next).apply()
    }

    fun updateMonthlyBudgetYuan(budgetYuan: Double) {
        val cents = (budgetYuan * 100.0).roundToLong().coerceAtLeast(0L)
        _monthlyBudgetCents.value = cents
        uiPrefs.edit().putLong("monthly_budget_cents", cents).apply()
    }

    fun updateAiConfig(newConfig: AiConfig) {
        configManager.saveConfig(newConfig)
        _aiConfig.value = newConfig
    }

    fun simulateCapturedEvent(rawText: String, sourcePkg: String = "com.tencent.mm") {
        if (rawText.isBlank()) return
        viewModelScope.launch {
            _isParsing.value = true
            _sandboxStatus.value = "正在识别…"
            try {
                val tx = app.captureCoordinator.processImmediateText(
                    rawText = rawText,
                    packageName = sourcePkg,
                    captureSource = "AI_SANDBOX"
                )
                if (tx != null) {
                    val sym = CurrencyCode.fromCode(tx.currency).symbol
                    _sandboxStatus.value = "已记账：${tx.type.displayName} · ${tx.category} · $sym${String.format("%.2f", tx.amountCents / 100.0)}（${tx.merchant}）"
                } else {
                    _sandboxStatus.value = "没有识别到收支信息，未记账"
                }
            } catch (e: Exception) {
                _sandboxStatus.value = "识别失败：${e.message}"
            } finally {
                _isParsing.value = false
            }
        }
    }

    fun saveOrUpdateTransaction(tx: TransactionEntity) {
        viewModelScope.launch {
            if (tx.id == 0L) {
                repository.recordTransaction(tx)
            } else {
                repository.updateExistingTransaction(tx)
            }
            app.merchantMemoryManager.learnFromTransaction(tx)
        }
    }

    fun upsertMerchantMemory(keyword: String, category: String, preferredWalletId: Long? = null) {
        app.merchantMemoryManager.upsertMemory(keyword, category, preferredWalletId)
    }

    fun deleteMerchantMemory(keyword: String) {
        app.merchantMemoryManager.deleteMemory(keyword)
    }

    fun clearObservabilityLogs() {
        app.captureCoordinator.clearObservabilityLogs()
    }

    /**
     * 账户真实余额锚点登记 + 差额自动生成平账流水
     */
    fun reconcileWalletBalance(
        walletId: Long,
        actualBalanceYuan: Double,
        recordAdjustmentTx: Boolean = true,
        customNote: String = ""
    ) {
        viewModelScope.launch {
            val actualCents = (actualBalanceYuan * 100.0).roundToLong().coerceAtLeast(0L)
            repository.reconcileWalletBalance(
                walletId = walletId,
                actualBalanceCents = actualCents,
                ledgerName = _currentLedger.value,
                recordAdjustmentTx = recordAdjustmentTx,
                customNote = customNote
            )
        }
    }

    fun generateFullBackupJson(): String {
        return BackupAndCsvTransferEngine.exportFullBackupJson(
            ledgers = _ledgers.value,
            monthlyBudgetCents = _monthlyBudgetCents.value,
            wallets = wallets.value,
            transactions = transactions.value,
            recurringRules = recurringRules.value,
            merchantMemories = merchantMemories.value,
            customExpenseCategories = CategoryCatalog.expenseCategories,
            customIncomeCategories = CategoryCatalog.incomeCategories,
            customTags = TagCatalog.tags,
            quickTemplates = _templates.value,
            customCurrencyRates = CurrencyRateManager.customRates
        )
    }

    fun generateCsvExportString(): String {
        return BackupAndCsvTransferEngine.exportTransactionsToCsv(
            transactions = transactions.value,
            wallets = wallets.value
        )
    }

    fun previewCsvImport(rawCsv: String): CsvParsePreviewResult {
        return BackupAndCsvTransferEngine.parseExternalCsv(
            rawCsv = rawCsv,
            defaultLedger = _currentLedger.value,
            wallets = wallets.value,
            merchantMemoryManager = app.merchantMemoryManager,
            existingLedgers = _ledgers.value
        )
    }

    fun commitCsvImport(
        rawCsv: String,
        applyToWalletBalance: Boolean,
        onResult: (String) -> Unit
    ) {
        viewModelScope.launch {
            val preview = previewCsvImport(rawCsv)
            if (preview.parsedTransactions.isEmpty() &&
                preview.newWalletsToCreate.isEmpty() &&
                preview.existingWalletsToUpdate.isEmpty()
            ) {
                onResult("没有识别到可导入的账本、资产或账单（格式：${preview.detectedSourceFormat}）")
                return@launch
            }
            preview.discoveredLedgers.forEach { createLedger(it) }
            val summary = repository.importExternalBatch(
                preview = preview,
                applyToWalletBalance = applyToWalletBalance
            )
            TagCatalog.syncDiscoveredTagsFromHistory(preview.parsedTransactions.map { it.tags })
            val parts = mutableListOf<String>()
            if (preview.newLedgers.isNotEmpty()) {
                parts.add("新增 ${preview.newLedgers.size} 个账本（${preview.newLedgers.joinToString("、")}）")
            }
            if (summary.createdWalletCount > 0) {
                parts.add("新建 ${summary.createdWalletCount} 个资产账户")
            }
            if (summary.updatedWalletCount > 0) {
                parts.add("同步 ${summary.updatedWalletCount} 个账户余额")
            }
            if (summary.importedTxCount > 0) {
                parts.add("导入 ${summary.importedTxCount} 笔账单")
            }
            if (summary.skippedDuplicateTxCount > 0) {
                parts.add("跳过 ${summary.skippedDuplicateTxCount} 笔重复流水")
            }
            onResult(if (parts.isEmpty()) "无新增数据（全部已存在）" else "批量导入完成：${parts.joinToString(" · ")}")
        }
    }

    fun restoreFromBackupJson(
        rawJson: String,
        onResult: (String) -> Unit
    ) {
        viewModelScope.launch {
            val parsed = BackupAndCsvTransferEngine.parseFullBackupJson(rawJson)
            if (parsed == null) {
                onResult("备份内容无效或已损坏，无法恢复")
                return@launch
            }
            val (backupWallets, backupTxs, snapshot) = parsed
            val backupRules = BackupAndCsvTransferEngine.toRecurringRuleEntities(snapshot.recurringRules)
            val txCount = repository.restoreFullSnapshot(backupWallets, backupTxs, backupRules)
            if (snapshot.customExpenseCategories.isNotEmpty() || snapshot.customIncomeCategories.isNotEmpty()) {
                CategoryCatalog.replaceAllCategories(
                    expenseList = snapshot.customExpenseCategories,
                    incomeList = snapshot.customIncomeCategories
                )
            }
            if (snapshot.customTags.isNotEmpty()) {
                TagCatalog.replaceAllTags(snapshot.customTags)
            }
            if (snapshot.quickTemplates.isNotEmpty()) {
                persistTemplates(snapshot.quickTemplates)
            }
            if (snapshot.customCurrencyRates.isNotEmpty()) {
                CurrencyRateManager.replaceAllCustomRates(snapshot.customCurrencyRates)
            }
            TagCatalog.syncDiscoveredTagsFromHistory(
                backupTxs.map { it.tags } + snapshot.quickTemplates.map { it.tags }
            )
            snapshot.merchantMemories.forEach { m ->
                app.merchantMemoryManager.upsertMemory(m.merchantKeyword, m.category, m.preferredWalletId)
            }
            snapshot.ledgers.forEach { createLedger(it) }
            if (snapshot.monthlyBudgetCents > 0L) {
                _monthlyBudgetCents.value = snapshot.monthlyBudgetCents
                uiPrefs.edit().putLong("monthly_budget_cents", snapshot.monthlyBudgetCents).apply()
            }
            onResult("恢复完成：${backupWallets.size} 个账户、$txCount 笔账单")
        }
    }

    fun deleteTransaction(txId: Long) {
        viewModelScope.launch {
            if (repository.undoAndDeleteTransaction(txId)) {
                postMessage("账单已删除，余额已回滚")
            }
        }
    }

    fun refundExpense(
        expenseId: Long,
        refundYuan: Double,
        receiveWalletId: Long?,
        note: String
    ) {
        viewModelScope.launch {
            val cents = (refundYuan * 100.0).roundToLong()
            if (cents > 0L) {
                val refundId = repository.refundExpense(expenseId, cents, receiveWalletId, note)
                if (refundId != null) {
                    postMessage("已退款 ¥${String.format("%.2f", cents / 100.0)}")
                }
            }
        }
    }

    fun settleReimbursement(expenseId: Long, receiveWalletId: Long?) {
        viewModelScope.launch {
            repository.settleReimbursement(expenseId, receiveWalletId)
            postMessage("报销已入账")
        }
    }

    fun batchSettleReimbursements(
        expenseIds: List<Long>,
        receiveWalletId: Long?,
        onResult: (String) -> Unit
    ) {
        viewModelScope.launch {
            val settled = repository.batchSettleReimbursements(expenseIds, receiveWalletId)
            onResult("已报销 $settled 笔，款项已存入账户")
        }
    }

    fun splitExpenseTransaction(
        expenseId: Long,
        categoryA: String,
        amountAYuan: Double,
        categoryB: String,
        noteB: String
    ) {
        viewModelScope.launch {
            val partACents = (amountAYuan * 100.0).roundToLong()
            if (partACents <= 0L) return@launch
            repository.splitExpenseTransaction(
                originalTxId = expenseId,
                categoryA = categoryA,
                amountACents = partACents,
                categoryB = categoryB,
                noteB = noteB
            )
        }
    }

    fun upsertSmartRule(rule: SmartConditionRule) {
        app.smartRuleEngine.upsertRule(rule)
    }

    fun toggleSmartRule(ruleId: Long, enabled: Boolean) {
        app.smartRuleEngine.toggleRuleEnabled(ruleId, enabled)
    }

    fun deleteSmartRule(ruleId: Long) {
        app.smartRuleEngine.deleteRule(ruleId)
    }

    fun updateWebDavConfig(config: WebDavConfig) {
        WebDavSyncClient.saveConfig(app, config)
        _webDavConfig.value = config
    }

    fun uploadBackupToWebDav(
        config: WebDavConfig,
        onResult: (String) -> Unit
    ) {
        updateWebDavConfig(config)
        viewModelScope.launch {
            val backupJson = generateFullBackupJson()
            val res = WebDavSyncClient.uploadSnapshot(config, backupJson)
            res.fold(
                onSuccess = { msg ->
                    val updated = config.copy(lastSyncTimeMillis = System.currentTimeMillis())
                    updateWebDavConfig(updated)
                    onResult(msg)
                },
                onFailure = { err ->
                    onResult("上传失败：${err.message}")
                }
            )
        }
    }

    fun restoreBackupFromWebDav(
        config: WebDavConfig,
        onResult: (String) -> Unit
    ) {
        updateWebDavConfig(config)
        viewModelScope.launch {
            val res = WebDavSyncClient.downloadSnapshot(config)
            res.fold(
                onSuccess = { json ->
                    restoreFromBackupJson(json, onResult)
                },
                onFailure = { err ->
                    onResult("下载失败：${err.message}")
                }
            )
        }
    }

    fun saveOrUpdateWallet(wallet: WalletEntity) {
        viewModelScope.launch {
            if (wallet.id == 0L) {
                repository.saveWallet(wallet)
            } else {
                repository.updateWallet(wallet)
            }
        }
    }

    fun deleteWallet(wallet: WalletEntity) {
        viewModelScope.launch {
            repository.deleteWallet(wallet)
            postMessage("已删除账户「${wallet.name}」")
        }
    }

    fun repayCreditWallet(
        creditWallet: WalletEntity,
        fromAssetWalletId: Long,
        amountYuan: Double
    ) {
        viewModelScope.launch {
            val cents = (amountYuan * 100.0).roundToLong()
            if (cents <= 0L) return@launch
            val rate = CurrencyRateManager.getEffectiveRateToCny(creditWallet.currency)
            val tx = TransactionEntity(
                ledgerName = _currentLedger.value,
                type = TransactionType.REPAYMENT,
                amountCents = cents,
                currency = creditWallet.currency,
                exchangeRateToCny = rate,
                amountCnyCents = (cents * rate).roundToLong(),
                category = "信用还款",
                merchant = "偿还 ${creditWallet.name}",
                sourceWalletId = fromAssetWalletId,
                targetWalletId = creditWallet.id,
                captureSource = "MANUAL"
            )
            repository.recordTransaction(tx)
        }
    }

    fun addRecurringRule(
        title: String,
        amountYuan: Double,
        currency: String,
        category: String,
        walletId: Long,
        frequency: RecurrenceFrequency
    ) {
        viewModelScope.launch {
            repository.saveRecurringRule(
                RecurringRuleEntity(
                    title = title,
                    type = TransactionType.EXPENSE,
                    amountCents = (amountYuan * 100).roundToLong(),
                    currency = currency,
                    category = category,
                    walletId = walletId,
                    frequency = frequency,
                    nextTriggerTimeMillis = System.currentTimeMillis() + frequency.daysInterval * 86400_000L
                )
            )
        }
    }

    fun triggerRecurringRuleNow(rule: RecurringRuleEntity) {
        viewModelScope.launch {
            val dueCopy = rule.copy(nextTriggerTimeMillis = System.currentTimeMillis() - 1000L)
            repository.saveRecurringRule(dueCopy)
            val created = repository.processDueRecurringRules()
            val allWallets = repository.getAllWallets()
            created.forEach { app.notifier.sendSilentRecordedNotification(it, allWallets) }
            val mine = created.firstOrNull { it.merchant == rule.title }
            if (mine != null) {
                postMessage(
                    "已记一笔「${rule.title}」",
                    undoTransactionId = mine.id,
                    extraUndo = { repository.saveRecurringRule(rule) }
                )
            }
        }
    }

    fun deleteRecurringRule(rule: RecurringRuleEntity) {
        viewModelScope.launch {
            repository.deleteRecurringRule(rule)
            postMessage("已删除周期账单「${rule.title}」")
        }
    }

    fun updateAppUpdateConfig(config: AppUpdateConfig) {
        app.appUpdateManager.updateConfig(config)
    }

    fun checkForAppUpdate(isManual: Boolean = true) {
        viewModelScope.launch {
            app.appUpdateManager.checkForUpdate(isManual = isManual)
        }
    }

    fun downloadAndInstallAppUpdate(context: Context, release: ReleaseUpdateInfo) {
        viewModelScope.launch {
            val resultState = app.appUpdateManager.downloadReleaseApk(release)
            if (resultState is UpdateCheckState.ReadyToInstall) {
                app.appUpdateManager.triggerInstallApk(context, resultState.apkFilePath)
            }
        }
    }

    fun triggerInstallDownloadedApk(context: Context, apkFilePath: String) {
        app.appUpdateManager.triggerInstallApk(context, apkFilePath)
    }

    fun ignoreAppUpdateTag(tag: String) {
        app.appUpdateManager.ignoreReleaseTag(tag)
        postMessage("已忽略版本 $tag 的自动弹窗提醒")
    }

    suspend fun countTransactionsByCategory(
        category: String,
        includeSubPrefix: Boolean,
        isExpense: Boolean? = null
    ): Int = repository.countTransactionsByCategory(category, includeSubPrefix, isExpense)

    private suspend fun cascadeCategoryEverywhere(
        oldCategory: String,
        newCategory: String,
        includeSubPrefix: Boolean,
        preserveSubSuffix: Boolean,
        isExpense: Boolean? = null
    ): Int {
        val updatedTxCount = repository.migrateCategoryCascade(
            oldCategory = oldCategory,
            newCategory = newCategory,
            includeSubPrefix = includeSubPrefix,
            preserveSubSuffix = preserveSubSuffix,
            isExpense = isExpense
        )
        val (updatedTemplates, templatesChanged) = QuickTemplateHelper.cascadeCategoryInTemplates(
            templates = _templates.value,
            oldCategory = oldCategory,
            newCategory = newCategory,
            includeSubPrefix = includeSubPrefix,
            preserveSubSuffix = preserveSubSuffix,
            isExpense = isExpense
        )
        if (templatesChanged) {
            persistTemplates(updatedTemplates)
        }

        if (isExpense != false) {
            var installmentsChanged = false
            val updatedInstallments = _installmentPlans.value.map { inst ->
                val mapped = CategoryCatalog.remapCascadedCategory(
                    current = inst.category,
                    oldCategory = oldCategory,
                    newCategory = newCategory,
                    includeSubPrefix = includeSubPrefix,
                    preserveSubSuffix = preserveSubSuffix
                )
                if (mapped != null) {
                    installmentsChanged = true
                    inst.copy(category = mapped)
                } else inst
            }
            if (installmentsChanged) {
                _installmentPlans.value = updatedInstallments
                uiPrefs.edit().putString("saved_installments_json", jsonCodec.encodeToString(updatedInstallments)).apply()
            }
        }

        app.smartRuleEngine.migrateCategoryCascade(
            oldCategory = oldCategory,
            newCategory = newCategory,
            includeSubPrefix = includeSubPrefix,
            preserveSubSuffix = preserveSubSuffix,
            isExpense = isExpense
        )
        app.merchantMemoryManager.migrateCategoryCascade(
            oldCategory = oldCategory,
            newCategory = newCategory,
            includeSubPrefix = includeSubPrefix,
            preserveSubSuffix = preserveSubSuffix
        )
        return updatedTxCount
    }

    fun addPrimaryCategory(
        isExpense: Boolean,
        name: String,
        assetFile: String,
        onDone: (Result<CategoryDef>) -> Unit = {}
    ) {
        val res = CategoryCatalog.addPrimaryCategory(isExpense, name, assetFile)
        res.onSuccess { created ->
            postMessage("已新增${if (isExpense) "支出" else "收入"}分类「${created.name}」")
        }
        onDone(res)
    }

    fun updatePrimaryCategory(
        isExpense: Boolean,
        oldName: String,
        newName: String,
        newAssetFile: String,
        onDone: (Result<CategoryDef>) -> Unit = {}
    ) {
        val res = CategoryCatalog.updatePrimaryCategory(isExpense, oldName, newName, newAssetFile)
        res.onSuccess { updated ->
            if (oldName != updated.name) {
                viewModelScope.launch {
                    val cnt = cascadeCategoryEverywhere(
                        oldCategory = oldName,
                        newCategory = updated.name,
                        includeSubPrefix = true,
                        preserveSubSuffix = true,
                        isExpense = isExpense
                    )
                    postMessage(
                        if (cnt > 0) "已将「$oldName」重命名为「${updated.name}」（同步迁移 $cnt 笔历史账单）"
                        else "已将「$oldName」重命名为「${updated.name}」"
                    )
                    onDone(res)
                }
            } else {
                postMessage("已更新「${updated.name}」图标")
                onDone(res)
            }
        }.onFailure {
            onDone(res)
        }
    }

    fun movePrimaryCategory(isExpense: Boolean, index: Int, delta: Int) {
        CategoryCatalog.movePrimaryCategory(isExpense, index, delta)
    }

    fun deletePrimaryCategoryAndMigrate(
        isExpense: Boolean,
        categoryName: String,
        migrateTargetCategory: String,
        onDone: (Result<Unit>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = CategoryCatalog.deletePrimaryCategory(isExpense, categoryName)
            res.onSuccess {
                val remaining = if (isExpense) CategoryCatalog.expenseCategories else CategoryCatalog.incomeCategories
                val fallbackTarget = remaining.firstOrNull { it.name == "其他" }?.name
                    ?: remaining.firstOrNull()?.name
                    ?: "其他"
                val target = migrateTargetCategory.takeIf { candidate ->
                    candidate.isNotBlank() && remaining.any {
                        it.name == candidate || candidate.startsWith("${it.name}-")
                    }
                } ?: fallbackTarget
                val cnt = cascadeCategoryEverywhere(
                    oldCategory = categoryName,
                    newCategory = target,
                    includeSubPrefix = true,
                    preserveSubSuffix = false,
                    isExpense = isExpense
                )
                postMessage(
                    if (cnt > 0) "已删除「$categoryName」，$cnt 笔账单已迁移至「$target」"
                    else "已删除分类「$categoryName」"
                )
            }
            onDone(res)
        }
    }

    fun addSubCategory(
        isExpense: Boolean,
        parentName: String,
        subName: String,
        assetFile: String,
        onDone: (Result<SubCategoryDef>) -> Unit = {}
    ) {
        val res = CategoryCatalog.addSubCategory(isExpense, parentName, subName, assetFile)
        res.onSuccess { created ->
            postMessage("已在「$parentName」下新增「${created.name}」")
        }
        onDone(res)
    }

    fun updateSubCategory(
        isExpense: Boolean,
        parentName: String,
        oldSubName: String,
        newSubName: String,
        newAssetFile: String,
        onDone: (Result<SubCategoryDef>) -> Unit = {}
    ) {
        val res = CategoryCatalog.updateSubCategory(isExpense, parentName, oldSubName, newSubName, newAssetFile)
        res.onSuccess { updated ->
            if (oldSubName != updated.name) {
                viewModelScope.launch {
                    val oldFull = "$parentName-$oldSubName"
                    val newFull = "$parentName-${updated.name}"
                    val cnt = cascadeCategoryEverywhere(
                        oldCategory = oldFull,
                        newCategory = newFull,
                        includeSubPrefix = false,
                        preserveSubSuffix = false,
                        isExpense = isExpense
                    )
                    postMessage(
                        if (cnt > 0) "已将「$oldFull」重命名为「$newFull」（同步迁移 $cnt 笔历史账单）"
                        else "已将「$oldFull」重命名为「$newFull」"
                    )
                    onDone(res)
                }
            } else {
                postMessage("已更新「$parentName-${updated.name}」图标")
                onDone(res)
            }
        }.onFailure {
            onDone(res)
        }
    }

    fun moveSubCategory(isExpense: Boolean, parentName: String, subIndex: Int, delta: Int) {
        CategoryCatalog.moveSubCategory(isExpense, parentName, subIndex, delta)
    }

    fun deleteSubCategoryAndMigrate(
        isExpense: Boolean,
        parentName: String,
        subName: String,
        migrateTargetCategory: String,
        onDone: (Result<Unit>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = CategoryCatalog.deleteSubCategory(isExpense, parentName, subName)
            res.onSuccess {
                val oldFull = "$parentName-$subName"
                val target = migrateTargetCategory.ifBlank { parentName }
                val cnt = cascadeCategoryEverywhere(
                    oldCategory = oldFull,
                    newCategory = target,
                    includeSubPrefix = false,
                    preserveSubSuffix = false,
                    isExpense = isExpense
                )
                postMessage(
                    if (cnt > 0) "已删除「$oldFull」，$cnt 笔账单已迁移至「$target」"
                    else "已删除二级分类「$oldFull」"
                )
            }
            onDone(res)
        }
    }

    fun restoreDefaultCategories(isExpense: Boolean? = null, mergeOnly: Boolean = true) {
        CategoryCatalog.restoreDefaultCategories(isExpense = isExpense, mergeOnly = mergeOnly)
        postMessage(if (mergeOnly) "已补全缺失的默认分类" else "已重置为默认分类树")
    }

    suspend fun countTransactionsByTag(tagName: String): Int =
        repository.countTransactionsByTag(tagName)

    private suspend fun cascadeTagEverywhere(
        oldTag: String,
        newTag: String?
    ): Int {
        val updatedTxCount = repository.migrateTagCascade(oldTag = oldTag, newTag = newTag)

        val (updatedTemplates, templatesChanged) = QuickTemplateHelper.cascadeTagInTemplates(
            templates = _templates.value,
            oldTag = oldTag,
            newTag = newTag
        )
        if (templatesChanged) {
            persistTemplates(updatedTemplates)
        }

        app.smartRuleEngine.migrateTagCascade(oldTag = oldTag, newTag = newTag)
        TagCatalog.recordTagRemap(oldTag = oldTag, newTag = newTag)
        return updatedTxCount
    }

    fun addTag(
        name: String,
        colorHex: Long,
        groupName: String = "日常",
        onDone: (Result<TagDef>) -> Unit = {}
    ) {
        val res = TagCatalog.addTag(name = name, colorHex = colorHex, groupName = groupName)
        res.onSuccess { created ->
            postMessage("已新增标签「#${created.name}」")
        }
        onDone(res)
    }

    fun updateTag(
        oldName: String,
        newName: String,
        colorHex: Long,
        groupName: String = "日常",
        onDone: (Result<TagDef>) -> Unit = {}
    ) {
        val res = TagCatalog.updateTag(
            oldName = oldName,
            newName = newName,
            colorHex = colorHex,
            groupName = groupName
        )
        res.onSuccess { updated ->
            if (oldName != updated.name) {
                viewModelScope.launch {
                    val cnt = cascadeTagEverywhere(oldTag = oldName, newTag = updated.name)
                    postMessage(
                        if (cnt > 0) "已将「#$oldName」重命名为「#${updated.name}」（同步更新 $cnt 笔历史账单）"
                        else "已将「#$oldName」重命名为「#${updated.name}」"
                    )
                    onDone(res)
                }
            } else {
                postMessage("已更新标签「#${updated.name}」")
                onDone(res)
            }
        }.onFailure {
            onDone(res)
        }
    }

    fun moveTag(index: Int, delta: Int) {
        TagCatalog.moveTag(index, delta)
    }

    fun mergeTag(
        sourceName: String,
        targetName: String,
        onDone: (Result<TagDef>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = TagCatalog.mergeTagInCatalog(sourceName = sourceName, targetName = targetName)
            res.onSuccess { targetDef ->
                val cnt = cascadeTagEverywhere(oldTag = sourceName, newTag = targetDef.name)
                postMessage(
                    if (cnt > 0) "已将「#$sourceName」合并入「#${targetDef.name}」（同步合并 $cnt 笔历史账单并自动去重）"
                    else "已将「#$sourceName」合并入「#${targetDef.name}」"
                )
            }
            onDone(res)
        }
    }

    fun deleteTag(
        tagName: String,
        cascadeRemoveFromHistory: Boolean,
        replacementTagName: String? = null,
        onDone: (Result<Unit>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val muteDiscovery = !cascadeRemoveFromHistory
            val res = TagCatalog.deleteTagFromCatalog(
                name = tagName,
                muteDiscoveryIfKeptInHistory = muteDiscovery
            )
            res.onSuccess {
                if (cascadeRemoveFromHistory) {
                    val cleanReplacement = replacementTagName?.trim()?.takeIf { it.isNotEmpty() && it != tagName }
                    val cnt = cascadeTagEverywhere(oldTag = tagName, newTag = cleanReplacement)
                    if (cleanReplacement != null) {
                        postMessage(
                            if (cnt > 0) "已删除「#$tagName」，并将 $cnt 笔历史账单替换为「#$cleanReplacement」"
                            else "已删除标签「#$tagName」"
                        )
                    } else {
                        postMessage(
                            if (cnt > 0) "已删除「#$tagName」，并从 $cnt 笔历史账单及规则中移除该标签"
                            else "已删除标签「#$tagName」"
                        )
                    }
                } else {
                    postMessage("已从标签列表中移除「#$tagName」（保留历史账单上的标签记录）")
                }
            }
            onDone(res)
        }
    }

    fun restoreDefaultTags(mergeOnly: Boolean = true) {
        TagCatalog.restoreDefaultTags(mergeOnly = mergeOnly)
        postMessage(if (mergeOnly) "已补全缺失的默认标签" else "已重置为默认标签列表")
    }

    fun verifyAppLockPin(pin: String, unlockSessionOnSuccess: Boolean = true): PinVerifyResult {
        return AppLockManager.verifyPin(pin = pin, unlockSessionOnSuccess = unlockSessionOnSuccess)
    }

    fun enablePasswordProtection(
        newPin: String,
        confirmPin: String,
        hint: String = ""
    ): Result<PasswordProtectionConfig> {
        val res = AppLockManager.enablePasswordProtection(newPin, confirmPin, hint)
        res.onSuccess {
            postMessage("已开启密码保护")
        }
        return res
    }

    fun changeAppLockPin(
        oldPin: String,
        newPin: String,
        confirmPin: String
    ): Result<PasswordProtectionConfig> {
        val res = AppLockManager.changePin(oldPin = oldPin, newPin = newPin, confirmPin = confirmPin)
        res.onSuccess {
            postMessage("安全密码已修改")
        }
        return res
    }

    fun disablePasswordProtection(currentPin: String): Result<PasswordProtectionConfig> {
        val res = AppLockManager.disablePasswordProtection(currentPin)
        res.onSuccess {
            postMessage("已关闭密码保护")
        }
        return res
    }

    fun updateAppLockTimeout(timeoutSeconds: Int) {
        val updated = AppLockManager.updateLockTimeout(timeoutSeconds)
        postMessage("自动锁定已设为「${updated.timeoutDisplayLabel}」")
    }

    fun updateHideRecentTaskPreview(hide: Boolean) {
        AppLockManager.updateHideRecentTaskPreview(hide)
        postMessage(if (hide) "已开启多任务模糊与防截屏保护" else "已关闭多任务模糊与防截屏保护")
    }

    fun updateAppLockPinHint(rawHint: String): Result<PasswordProtectionConfig> {
        val res = AppLockManager.updatePinHint(rawHint)
        res.onSuccess { updated ->
            postMessage(if (updated.pinHint.isNotBlank()) "已保存密码提示语" else "已清空密码提示语")
        }
        return res
    }

    fun lockAppSessionNow() {
        AppLockManager.lockNow()
    }
}
