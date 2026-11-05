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
import com.moji.ai.data.model.WalletType
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

    private val _aiConfig = MutableStateFlow(configManager.getConfig())
    val aiConfig: StateFlow<AiConfig> = _aiConfig.asStateFlow()

    private val _sandboxStatus = MutableStateFlow<String?>(null)
    val sandboxStatus: StateFlow<String?> = _sandboxStatus.asStateFlow()

    private val _isParsing = MutableStateFlow(false)
    val isParsing: StateFlow<Boolean> = _isParsing.asStateFlow()

    fun updateAiConfig(newConfig: AiConfig) {
        configManager.saveConfig(newConfig)
        _aiConfig.value = newConfig
    }

    /**
     * 在 AI 识账沙盒中模拟收到一条微信/支付宝/银行通知或页面文本，验证后台静默记账与通知栏推送
     */
    fun simulateCapturedEvent(rawText: String, sourcePkg: String = "com.tencent.mm") {
        if (rawText.isBlank()) return
        viewModelScope.launch {
            _isParsing.value = true
            _sandboxStatus.value = "正在通过 AI 引擎解析语义并匹配钱包..."
            try {
                val tx = app.captureCoordinator.processImmediateText(
                    rawText = rawText,
                    packageName = sourcePkg,
                    captureSource = "AI_SANDBOX"
                )
                if (tx != null) {
                    val sym = CurrencyCode.fromCode(tx.currency).symbol
                    _sandboxStatus.value = "✅ 静默入账成功：[${tx.type.displayName}] ${tx.category} $sym${String.format("%.2f", tx.amountCents / 100.0)} (${tx.merchant})，已推送系统通知栏！"
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

    fun undoTransaction(txId: Long) {
        viewModelScope.launch {
            repository.undoAndDeleteTransaction(txId)
        }
    }

    fun addManualWallet(
        name: String,
        type: WalletType,
        currency: String,
        balanceYuan: Double,
        creditLimitYuan: Double?,
        cardLast4: String?,
        aliases: String
    ) {
        viewModelScope.launch {
            repository.saveWallet(
                WalletEntity(
                    name = name,
                    type = type,
                    currency = currency,
                    balanceCents = (balanceYuan * 100).roundToLong(),
                    creditLimitCents = creditLimitYuan?.let { (it * 100).roundToLong() },
                    cardLast4 = cardLast4?.takeIf { it.isNotBlank() },
                    aliases = aliases,
                    colorHex = when (type) {
                        WalletType.DIGITAL_WALLET -> 0xFF07C160
                        WalletType.DEBIT_BANK -> 0xFFDC2626
                        WalletType.CREDIT -> 0xFF7C3AED
                        WalletType.CASH_OTHER -> 0xFF0D9488
                    }
                )
            )
        }
    }

    fun addManualTransaction(
        type: TransactionType,
        amountYuan: Double,
        currency: String,
        category: String,
        merchant: String,
        note: String,
        sourceWalletId: Long?,
        targetWalletId: Long?
    ) {
        viewModelScope.launch {
            val code = CurrencyCode.fromCode(currency)
            val cents = (amountYuan * 100).roundToLong()
            val cnyCents = (cents * code.defaultRateToCny).roundToLong()
            val tx = TransactionEntity(
                type = type,
                amountCents = cents,
                currency = code.name,
                exchangeRateToCny = code.defaultRateToCny,
                amountCnyCents = cnyCents,
                category = category,
                merchant = merchant,
                note = note,
                sourceWalletId = sourceWalletId,
                targetWalletId = targetWalletId,
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
}
