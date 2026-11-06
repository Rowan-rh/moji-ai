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
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.RecurrenceFrequency
import com.moji.ai.data.model.TransactionType
import com.moji.ai.service.CaptureObservabilityLogEntry
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
    val id: Long,
    val title: String,
    val typeName: String = "EXPENSE",
    val category: String,
    val amountCents: Long,
    val currency: String = "CNY",
    val walletId: Long? = 4L,
    val note: String = ""
)

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
 * 一次性的界面提示（Snackbar）。undoTransactionId 不为空时提供「撤销」按钮，撤销即删除该笔新建账单并回滚余额。
 */
data class UiMessage(
    val text: String,
    val undoTransactionId: Long? = null
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

    val observabilityLogs: StateFlow<List<CaptureObservabilityLogEntry>> = app.captureCoordinator.observabilityLogs

    private val _webDavConfig = MutableStateFlow(WebDavSyncClient.loadConfig(application))
    val webDavConfig: StateFlow<WebDavConfig> = _webDavConfig.asStateFlow()

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

    private fun postMessage(text: String, undoTransactionId: Long? = null) {
        _uiMessages.tryEmit(UiMessage(text, undoTransactionId))
    }

    /** Snackbar「撤销」：删除刚刚一键生成的账单并回滚余额 */
    fun undoCreatedTransaction(txId: Long) {
        viewModelScope.launch {
            repository.undoAndDeleteTransaction(txId)
        }
    }

    private val _isParsing = MutableStateFlow(false)
    val isParsing: StateFlow<Boolean> = _isParsing.asStateFlow()

    private val _templates = MutableStateFlow(loadSavedTemplates())
    val templates: StateFlow<List<QuickBookkeepingTemplate>> = _templates.asStateFlow()

    private val _savingPlans = MutableStateFlow(loadSavedSavingPlans())
    val savingPlans: StateFlow<List<SavingPlanItem>> = _savingPlans.asStateFlow()

    private val _installmentPlans = MutableStateFlow(loadSavedInstallments())
    val installmentPlans: StateFlow<List<InstallmentPlanItem>> = _installmentPlans.asStateFlow()

    private fun loadSavedTemplates(): List<QuickBookkeepingTemplate> {
        val raw = uiPrefs.getString("saved_templates_json", null)
        if (!raw.isNullOrBlank()) {
            runCatching { return jsonCodec.decodeFromString<List<QuickBookkeepingTemplate>>(raw) }
        }
        return emptyList()
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
        val title = tx.merchant.ifBlank { tx.note.ifBlank { tx.category } }
        val newItem = QuickBookkeepingTemplate(
            id = System.currentTimeMillis(),
            title = title,
            typeName = tx.type.name,
            category = tx.category,
            amountCents = tx.amountCents,
            currency = tx.currency,
            walletId = tx.sourceWalletId ?: tx.targetWalletId,
            note = tx.merchant.ifBlank { tx.note }
        )
        val duplicated = _templates.value.any {
            it.title == newItem.title && it.category == newItem.category &&
                it.amountCents == newItem.amountCents && it.typeName == newItem.typeName
        }
        if (duplicated) {
            postMessage("已存在相同的模板")
            return
        }
        val updated = listOf(newItem) + _templates.value
        _templates.value = updated
        uiPrefs.edit().putString("saved_templates_json", jsonCodec.encodeToString(updated)).apply()
        postMessage("已存为模板「${newItem.title}」，可在首页一键记账")
    }

    fun addCustomTemplate(title: String, category: String, amountYuan: Double, walletId: Long?) {
        val cents = (amountYuan * 100.0).roundToLong().coerceAtLeast(1L)
        val item = QuickBookkeepingTemplate(
            id = System.currentTimeMillis(),
            title = title.trim().ifEmpty { category },
            typeName = "EXPENSE",
            category = category,
            amountCents = cents,
            currency = "CNY",
            walletId = walletId,
            note = title.trim()
        )
        val updated = listOf(item) + _templates.value
        _templates.value = updated
        uiPrefs.edit().putString("saved_templates_json", jsonCodec.encodeToString(updated)).apply()
    }

    fun deleteTemplate(id: Long) {
        val updated = _templates.value.filterNot { it.id == id }
        _templates.value = updated
        uiPrefs.edit().putString("saved_templates_json", jsonCodec.encodeToString(updated)).apply()
    }

    fun applyQuickTemplateNow(tpl: QuickBookkeepingTemplate) {
        viewModelScope.launch {
            val type = runCatching { TransactionType.valueOf(tpl.typeName) }.getOrDefault(TransactionType.EXPENSE)
            val rate = CurrencyCode.fromCode(tpl.currency).defaultRateToCny
            val cnyCents = (tpl.amountCents * rate).roundToLong()
            val tx = TransactionEntity(
                ledgerName = _currentLedger.value,
                type = type,
                amountCents = tpl.amountCents,
                currency = tpl.currency,
                exchangeRateToCny = rate,
                amountCnyCents = cnyCents,
                category = tpl.category,
                merchant = tpl.title,
                note = tpl.note,
                sourceWalletId = if (type == TransactionType.INCOME) null else tpl.walletId,
                targetWalletId = if (type == TransactionType.INCOME) tpl.walletId else null,
                captureSource = "TEMPLATE"
            )
            val newId = repository.recordTransaction(tx)
            postMessage("已记一笔「${tpl.title}」", undoTransactionId = newId)
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
        val principalPerPeriod = plan.totalCents / plan.totalPeriods.coerceAtLeast(1)
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
                feeCents = plan.feePerPeriodCents,
                category = plan.category,
                merchant = "${plan.title} (${nextPeriod}/${plan.totalPeriods}期)",
                note = "分期第${nextPeriod}期入账",
                sourceWalletId = plan.walletId,
                tags = "分期",
                captureSource = "INSTALLMENT"
            )
            val newId = repository.recordTransaction(tx)
            postMessage("已记入第 ${nextPeriod}/${plan.totalPeriods} 期还款", undoTransactionId = newId)
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
            merchantMemories = merchantMemories.value
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
            merchantMemoryManager = app.merchantMemoryManager
        )
    }

    fun commitCsvImport(
        rawCsv: String,
        applyToWalletBalance: Boolean,
        onResult: (String) -> Unit
    ) {
        viewModelScope.launch {
            val preview = previewCsvImport(rawCsv)
            if (preview.parsedTransactions.isEmpty()) {
                onResult("没有识别到可导入的账单（格式：${preview.detectedSourceFormat}）")
                return@launch
            }
            val importedCount = repository.importExternalTransactions(
                candidates = preview.parsedTransactions,
                applyToWalletBalance = applyToWalletBalance
            )
            onResult("已导入 $importedCount 笔账单" + (preview.parsedTransactions.size - importedCount).let { if (it > 0) "，跳过 $it 笔重复账单" else "" })
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
            val tx = TransactionEntity(
                ledgerName = _currentLedger.value,
                type = TransactionType.REPAYMENT,
                amountCents = cents,
                currency = creditWallet.currency,
                exchangeRateToCny = CurrencyCode.fromCode(creditWallet.currency).defaultRateToCny,
                amountCnyCents = (cents * CurrencyCode.fromCode(creditWallet.currency).defaultRateToCny).roundToLong(),
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
                postMessage("已记一笔「${rule.title}」", undoTransactionId = mine.id)
            }
        }
    }

    fun deleteRecurringRule(rule: RecurringRuleEntity) {
        viewModelScope.launch {
            repository.deleteRecurringRule(rule)
            postMessage("已删除周期账单「${rule.title}」")
        }
    }
}
