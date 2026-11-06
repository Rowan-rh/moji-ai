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
import kotlinx.coroutines.flow.MutableStateFlow
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

    private val _monthlyBudgetCents = MutableStateFlow(
        uiPrefs.getLong("monthly_budget_cents", 300_000L) // 默认月预算 ¥3,000.00
    )
    val monthlyBudgetCents: StateFlow<Long> = _monthlyBudgetCents.asStateFlow()

    private val _aiConfig = MutableStateFlow(configManager.getConfig())
    val aiConfig: StateFlow<AiConfig> = _aiConfig.asStateFlow()

    private val _sandboxStatus = MutableStateFlow<String?>(null)
    val sandboxStatus: StateFlow<String?> = _sandboxStatus.asStateFlow()

    private val jsonCodec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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
        return listOf(
            QuickBookkeepingTemplate(1L, "瑞幸冰美式", "EXPENSE", "餐饮-咖啡", 990L, "CNY", 5L, "瑞幸咖啡"),
            QuickBookkeepingTemplate(2L, "通勤地铁", "EXPENSE", "交通-地铁", 400L, "CNY", 4L, "地铁出行"),
            QuickBookkeepingTemplate(3L, "工作简餐", "EXPENSE", "餐饮-三餐", 2600L, "CNY", 5L, "工作日午餐"),
            QuickBookkeepingTemplate(4L, "月度话费", "EXPENSE", "通讯", 5900L, "CNY", 4L, "手机话费充值")
        )
    }

    private fun loadSavedSavingPlans(): List<SavingPlanItem> {
        val raw = uiPrefs.getString("saved_saving_plans_json", null)
        if (!raw.isNullOrBlank()) {
            runCatching { return jsonCodec.decodeFromString<List<SavingPlanItem>>(raw) }
        }
        return listOf(
            SavingPlanItem(1L, "年度旅行基金", 1_500_000L, 680_000L, "2026-12-31"),
            SavingPlanItem(2L, "应急备用金", 3_000_000L, 1_850_000L, "2027-06-30"),
            SavingPlanItem(3L, "数码装备换新", 800_000L, 520_000L, "2026-11-30")
        )
    }

    private fun loadSavedInstallments(): List<InstallmentPlanItem> {
        val raw = uiPrefs.getString("saved_installments_json", null)
        if (!raw.isNullOrBlank()) {
            runCatching { return jsonCodec.decodeFromString<List<InstallmentPlanItem>>(raw) }
        }
        return listOf(
            InstallmentPlanItem(1L, "MacBook Pro 12期免息", 1L, 1_499_900L, 0L, 12, 4, "数码-电脑"),
            InstallmentPlanItem(2L, "年度健身私教卡 6期", 3L, 360_000L, 1200L, 6, 2, "运动")
        )
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
        val updated = listOf(newItem) + _templates.value
        _templates.value = updated
        uiPrefs.edit().putString("saved_templates_json", jsonCodec.encodeToString(updated)).apply()
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
            repository.recordTransaction(tx)
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
            repository.recordTransaction(copyTx)
        }
    }

    fun toggleOrSettleReimbursement(tx: TransactionEntity) {
        viewModelScope.launch {
            if (!tx.reimbursable) {
                repository.updateExistingTransaction(tx.copy(reimbursable = true))
            } else if (!tx.reimbursed) {
                repository.settleReimbursement(tx.id, tx.sourceWalletId)
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
            repository.recordTransaction(tx)
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
            _sandboxStatus.value = "正在通过 AI 引擎解析语义并联动账户..."
            try {
                val tx = app.captureCoordinator.processImmediateText(
                    rawText = rawText,
                    packageName = sourcePkg,
                    captureSource = "AI_SANDBOX"
                )
                if (tx != null) {
                    val sym = CurrencyCode.fromCode(tx.currency).symbol
                    _sandboxStatus.value = "✅ 静默入账成功：[${tx.type.displayName}] ${tx.category} $sym${String.format("%.2f", tx.amountCents / 100.0)} (${tx.merchant})，已同步更新资产余额并推送系统通知！"
                } else {
                    _sandboxStatus.value = "⚠️ AI 判定该文本不包含有效资金变动（已忽略）"
                }
            } catch (e: Exception) {
                _sandboxStatus.value = "❌ 解析异常: ${e.message}"
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
                onResult("⚠️ 未识别到可导入的有效账单记录（格式：${preview.detectedSourceFormat}）")
                return@launch
            }
            val importedCount = repository.importExternalTransactions(
                candidates = preview.parsedTransactions,
                applyToWalletBalance = applyToWalletBalance
            )
            onResult("✅ 已从【${preview.detectedSourceFormat}】导入 $importedCount 笔账单（自动去重跳过 ${preview.parsedTransactions.size - importedCount} 笔，忽略非金融行 ${preview.skippedNonFinancialRows} 行）")
        }
    }

    fun restoreFromBackupJson(
        rawJson: String,
        onResult: (String) -> Unit
    ) {
        viewModelScope.launch {
            val parsed = BackupAndCsvTransferEngine.parseFullBackupJson(rawJson)
            if (parsed == null) {
                onResult("❌ 备份 JSON 格式无效或已损坏，无法恢复")
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
            onResult("✅ 全量备份恢复成功：恢复账户 ${backupWallets.size} 个、账单 $txCount 笔、商户记忆 ${snapshot.merchantMemories.size} 条")
        }
    }

    fun deleteTransaction(txId: Long) {
        viewModelScope.launch {
            repository.undoAndDeleteTransaction(txId)
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
                repository.refundExpense(expenseId, cents, receiveWalletId, note)
            }
        }
    }

    fun settleReimbursement(expenseId: Long, receiveWalletId: Long?) {
        viewModelScope.launch {
            repository.settleReimbursement(expenseId, receiveWalletId)
        }
    }

    fun batchSettleReimbursements(
        expenseIds: List<Long>,
        receiveWalletId: Long?,
        onResult: (String) -> Unit
    ) {
        viewModelScope.launch {
            val settled = repository.batchSettleReimbursements(expenseIds, receiveWalletId)
            onResult("✅ 已批量核销 $settled 笔待报销账单，并同步恢复收款账户余额")
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
                    onResult("❌ WebDAV 上传失败：${err.message}")
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
                    onResult("❌ WebDAV 下载失败：${err.message}")
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
        }
    }

    fun deleteRecurringRule(rule: RecurringRuleEntity) {
        viewModelScope.launch {
            repository.deleteRecurringRule(rule)
        }
    }
}
