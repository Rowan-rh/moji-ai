package com.moji.ai.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moji.ai.MoJiApp
import com.moji.ai.ai.AiConfig
import com.moji.ai.data.local.RecurringRuleEntity
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.RecurrenceFrequency
import com.moji.ai.data.model.TransactionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MoJiApp
    private val repository = app.repository
    private val configManager = app.aiConfigManager

    val wallets: StateFlow<List<WalletEntity>> = repository.walletsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val transactions: StateFlow<List<TransactionEntity>> = repository.transactionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recurringRules: StateFlow<List<RecurringRuleEntity>> = repository.recurringRulesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _ledgers = MutableStateFlow(listOf("默认账本", "日常家用", "旅行基金"))
    val ledgers: StateFlow<List<String>> = _ledgers.asStateFlow()

    private val _currentLedger = MutableStateFlow("默认账本")
    val currentLedger: StateFlow<String> = _currentLedger.asStateFlow()

    private val _hideAssetAmounts = MutableStateFlow(false)
    val hideAssetAmounts: StateFlow<Boolean> = _hideAssetAmounts.asStateFlow()

    private val _aiConfig = MutableStateFlow(configManager.getConfig())
    val aiConfig: StateFlow<AiConfig> = _aiConfig.asStateFlow()

    private val _sandboxStatus = MutableStateFlow<String?>(null)
    val sandboxStatus: StateFlow<String?> = _sandboxStatus.asStateFlow()

    private val _isParsing = MutableStateFlow(false)
    val isParsing: StateFlow<Boolean> = _isParsing.asStateFlow()

    fun selectLedger(name: String) {
        _currentLedger.value = name
    }

    fun createLedger(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty() && trimmed !in _ledgers.value) {
            _ledgers.value = _ledgers.value + trimmed
        }
        if (trimmed.isNotEmpty()) {
            _currentLedger.value = trimmed
        }
    }

    fun toggleHideAssetAmounts() {
        _hideAssetAmounts.value = !_hideAssetAmounts.value
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
