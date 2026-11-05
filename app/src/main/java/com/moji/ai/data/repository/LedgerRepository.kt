package com.moji.ai.data.repository

import androidx.room.withTransaction
import com.moji.ai.data.local.AppDatabase
import com.moji.ai.data.local.RecurringRuleEntity
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.TransactionType
import kotlin.math.roundToLong

/**
 * 全能账本事务引擎：
 * 保证任何记账/转账/还款/冲销/撤销操作在同一个 SQLite 事务中原子更新钱包余额与原账单状态。
 */
class LedgerRepository(private val db: AppDatabase) {

    val walletsFlow = db.walletDao().observeAllWallets()
    val transactionsFlow = db.transactionDao().observeAllTransactions()
    val recurringRulesFlow = db.recurringRuleDao().observeAllRules()

    suspend fun getAllWallets(): List<WalletEntity> = db.walletDao().getAllWallets()

    suspend fun getRecentRefundableExpenses(limit: Int = 15): List<TransactionEntity> =
        db.transactionDao().getRecentRefundableExpenses(limit)

    /**
     * 核心入账方法：自动处理多币种折算、钱包余额加减、信用账户负债增减、以及退款/AA冲销关联
     */
    suspend fun recordTransaction(tx: TransactionEntity): Long = db.withTransaction {
        val walletDao = db.walletDao()
        val txDao = db.transactionDao()

        when (tx.type) {
            TransactionType.EXPENSE -> {
                tx.sourceWalletId?.let { walletId ->
                    walletDao.getWalletById(walletId)?.let { wallet ->
                        val delta = convertCentsBetweenCurrencies(tx.amountCents, tx.currency, wallet.currency, tx.exchangeRateToCny)
                        val newBalance = if (wallet.type.isLiability) {
                            // 信用卡/花呗消费：待还欠款增加
                            wallet.balanceCents + delta
                        } else {
                            // 储蓄卡/零钱消费：可用余额减少
                            wallet.balanceCents - delta
                        }
                        walletDao.updateWallet(wallet.copy(balanceCents = newBalance))
                    }
                }
            }

            TransactionType.INCOME -> {
                val targetId = tx.targetWalletId ?: tx.sourceWalletId
                targetId?.let { walletId ->
                    walletDao.getWalletById(walletId)?.let { wallet ->
                        val delta = convertCentsBetweenCurrencies(tx.amountCents, tx.currency, wallet.currency, tx.exchangeRateToCny)
                        val newBalance = if (wallet.type.isLiability) {
                            wallet.balanceCents - delta
                        } else {
                            wallet.balanceCents + delta
                        }
                        walletDao.updateWallet(wallet.copy(balanceCents = newBalance))
                    }
                }
            }

            TransactionType.TRANSFER -> {
                // 内部转账：转出账户扣减，转入账户增加（支持跨币种转账换算）
                tx.sourceWalletId?.let { fromId ->
                    walletDao.getWalletById(fromId)?.let { fromWallet ->
                        val delta = convertCentsBetweenCurrencies(tx.amountCents, tx.currency, fromWallet.currency, tx.exchangeRateToCny)
                        val newBal = if (fromWallet.type.isLiability) fromWallet.balanceCents + delta else fromWallet.balanceCents - delta
                        walletDao.updateWallet(fromWallet.copy(balanceCents = newBal))
                    }
                }
                tx.targetWalletId?.let { toId ->
                    walletDao.getWalletById(toId)?.let { toWallet ->
                        val delta = convertCentsBetweenCurrencies(tx.amountCents, tx.currency, toWallet.currency, tx.exchangeRateToCny)
                        val newBal = if (toWallet.type.isLiability) toWallet.balanceCents - delta else toWallet.balanceCents + delta
                        walletDao.updateWallet(toWallet.copy(balanceCents = newBal))
                    }
                }
            }

            TransactionType.REPAYMENT -> {
                // 信用卡/花呗还款：sourceWallet（储蓄卡/零钱）扣款，targetWallet（信用卡/花呗）欠款减少
                tx.sourceWalletId?.let { fromId ->
                    walletDao.getWalletById(fromId)?.let { fromWallet ->
                        val delta = convertCentsBetweenCurrencies(tx.amountCents, tx.currency, fromWallet.currency, tx.exchangeRateToCny)
                        walletDao.updateWallet(fromWallet.copy(balanceCents = fromWallet.balanceCents - delta))
                    }
                }
                tx.targetWalletId?.let { creditId ->
                    walletDao.getWalletById(creditId)?.let { creditWallet ->
                        val delta = convertCentsBetweenCurrencies(tx.amountCents, tx.currency, creditWallet.currency, tx.exchangeRateToCny)
                        // 待还欠款减少
                        walletDao.updateWallet(creditWallet.copy(balanceCents = (creditWallet.balanceCents - delta).coerceAtLeast(0L)))
                    }
                }
            }

            TransactionType.REFUND_OFFSET -> {
                // 退款 / 报销 / 朋友 AA 收款冲抵原支出：
                // 1. 收款钱包恢复余额（如果是退回花呗/信用卡则减少欠款，如果是退回微信零钱则增加余额）
                val receiveWalletId = tx.targetWalletId ?: tx.sourceWalletId
                receiveWalletId?.let { walletId ->
                    walletDao.getWalletById(walletId)?.let { wallet ->
                        val delta = convertCentsBetweenCurrencies(tx.amountCents, tx.currency, wallet.currency, tx.exchangeRateToCny)
                        val newBal = if (wallet.type.isLiability) {
                            (wallet.balanceCents - delta).coerceAtLeast(0L)
                        } else {
                            wallet.balanceCents + delta
                        }
                        walletDao.updateWallet(wallet.copy(balanceCents = newBal))
                    }
                }

                // 2. 自动找到原消费支出，累加已冲销金额 refundedAmountCents
                val targetExpense = tx.relatedTransactionId?.let { txDao.getTransactionById(it) }
                    ?: findBestMatchingExpenseForRefund(tx)

                val finalTx = if (targetExpense != null) {
                    val updatedRefunded = (targetExpense.refundedAmountCents + tx.amountCnyCents)
                        .coerceAtMost(targetExpense.amountCnyCents)
                    txDao.updateTransaction(targetExpense.copy(refundedAmountCents = updatedRefunded))
                    tx.copy(relatedTransactionId = targetExpense.id)
                } else {
                    tx
                }
                return@withTransaction txDao.insertTransaction(finalTx)
            }
        }

        txDao.insertTransaction(tx)
    }

    /**
     * 一键撤销入账（通知栏点击“撤销”或流水中删除账单时，完整回滚所有关联钱包余额及冲销额度）
     */
    suspend fun undoAndDeleteTransaction(transactionId: Long): Boolean = db.withTransaction {
        val walletDao = db.walletDao()
        val txDao = db.transactionDao()
        val tx = txDao.getTransactionById(transactionId) ?: return@withTransaction false

        when (tx.type) {
            TransactionType.EXPENSE -> {
                tx.sourceWalletId?.let { walletId ->
                    walletDao.getWalletById(walletId)?.let { wallet ->
                        val delta = convertCentsBetweenCurrencies(tx.amountCents, tx.currency, wallet.currency, tx.exchangeRateToCny)
                        val restored = if (wallet.type.isLiability) wallet.balanceCents - delta else wallet.balanceCents + delta
                        walletDao.updateWallet(wallet.copy(balanceCents = restored))
                    }
                }
            }
            TransactionType.INCOME -> {
                val targetId = tx.targetWalletId ?: tx.sourceWalletId
                targetId?.let { walletId ->
                    walletDao.getWalletById(walletId)?.let { wallet ->
                        val delta = convertCentsBetweenCurrencies(tx.amountCents, tx.currency, wallet.currency, tx.exchangeRateToCny)
                        val restored = if (wallet.type.isLiability) wallet.balanceCents + delta else wallet.balanceCents - delta
                        walletDao.updateWallet(wallet.copy(balanceCents = restored))
                    }
                }
            }
            TransactionType.TRANSFER -> {
                tx.sourceWalletId?.let { fromId ->
                    walletDao.getWalletById(fromId)?.let { w ->
                        val delta = convertCentsBetweenCurrencies(tx.amountCents, tx.currency, w.currency, tx.exchangeRateToCny)
                        val restored = if (w.type.isLiability) w.balanceCents - delta else w.balanceCents + delta
                        walletDao.updateWallet(w.copy(balanceCents = restored))
                    }
                }
                tx.targetWalletId?.let { toId ->
                    walletDao.getWalletById(toId)?.let { w ->
                        val delta = convertCentsBetweenCurrencies(tx.amountCents, tx.currency, w.currency, tx.exchangeRateToCny)
                        val restored = if (w.type.isLiability) w.balanceCents + delta else w.balanceCents - delta
                        walletDao.updateWallet(w.copy(balanceCents = restored))
                    }
                }
            }
            TransactionType.REPAYMENT -> {
                tx.sourceWalletId?.let { fromId ->
                    walletDao.getWalletById(fromId)?.let { w ->
                        val delta = convertCentsBetweenCurrencies(tx.amountCents, tx.currency, w.currency, tx.exchangeRateToCny)
                        walletDao.updateWallet(w.copy(balanceCents = w.balanceCents + delta))
                    }
                }
                tx.targetWalletId?.let { creditId ->
                    walletDao.getWalletById(creditId)?.let { w ->
                        val delta = convertCentsBetweenCurrencies(tx.amountCents, tx.currency, w.currency, tx.exchangeRateToCny)
                        walletDao.updateWallet(w.copy(balanceCents = w.balanceCents + delta))
                    }
                }
            }
            TransactionType.REFUND_OFFSET -> {
                val receiveWalletId = tx.targetWalletId ?: tx.sourceWalletId
                receiveWalletId?.let { walletId ->
                    walletDao.getWalletById(walletId)?.let { w ->
                        val delta = convertCentsBetweenCurrencies(tx.amountCents, tx.currency, w.currency, tx.exchangeRateToCny)
                        val restored = if (w.type.isLiability) w.balanceCents + delta else w.balanceCents - delta
                        walletDao.updateWallet(w.copy(balanceCents = restored))
                    }
                }
                tx.relatedTransactionId?.let { parentId ->
                    txDao.getTransactionById(parentId)?.let { parent ->
                        val restoredRefund = (parent.refundedAmountCents - tx.amountCnyCents).coerceAtLeast(0L)
                        txDao.updateTransaction(parent.copy(refundedAmountCents = restoredRefund))
                    }
                }
            }
        }

        txDao.deleteTransaction(tx)
        true
    }

    suspend fun saveWallet(wallet: WalletEntity): Long = db.walletDao().insertWallet(wallet)

    suspend fun saveRecurringRule(rule: RecurringRuleEntity): Long = db.recurringRuleDao().insertRule(rule)

    suspend fun deleteRecurringRule(rule: RecurringRuleEntity) = db.recurringRuleDao().deleteRule(rule)

    /**
     * 执行到期的周期性账单（如每月房租、订阅扣费），并推进下一次触发时间
     */
    suspend fun processDueRecurringRules(nowMillis: Long = System.currentTimeMillis()): List<TransactionEntity> {
        val dueRules = db.recurringRuleDao().getDueRules(nowMillis)
        val created = mutableListOf<TransactionEntity>()
        for (rule in dueRules) {
            val rate = CurrencyCode.fromCode(rule.currency).defaultRateToCny
            val cnyCents = (rule.amountCents * rate).roundToLong()
            val tx = TransactionEntity(
                type = rule.type,
                amountCents = rule.amountCents,
                currency = rule.currency,
                exchangeRateToCny = rate,
                amountCnyCents = cnyCents,
                category = rule.category,
                merchant = rule.title,
                note = "周期性固定账单自动入账 (${rule.frequency.displayName})",
                sourceWalletId = rule.walletId,
                targetWalletId = rule.targetWalletId,
                captureSource = "RECURRING"
            )
            val id = recordTransaction(tx)
            created.add(tx.copy(id = id))

            val nextMillis = rule.nextTriggerTimeMillis + rule.frequency.daysInterval * 86400_000L
            db.recurringRuleDao().updateRule(rule.copy(nextTriggerTimeMillis = nextMillis))
        }
        return created
    }

    private suspend fun findBestMatchingExpenseForRefund(refundTx: TransactionEntity): TransactionEntity? {
        val candidates = db.transactionDao().getRecentRefundableExpenses(15)
        // 优先匹配相同分类或相同商户或金额大于等于退款额的最近消费
        return candidates.firstOrNull {
            val remaining = it.amountCnyCents - it.refundedAmountCents
            remaining >= refundTx.amountCnyCents &&
                (it.category == refundTx.category ||
                    (refundTx.merchant.isNotBlank() && it.merchant.contains(refundTx.merchant, ignoreCase = true)))
        } ?: candidates.firstOrNull { (it.amountCnyCents - it.refundedAmountCents) >= refundTx.amountCnyCents }
    }

    private fun convertCentsBetweenCurrencies(
        amountCents: Long,
        fromCurrency: String,
        toCurrency: String,
        fromRateToCny: Double
    ): Long {
        if (fromCurrency.equals(toCurrency, ignoreCase = true)) return amountCents
        val cnyCents = amountCents * fromRateToCny
        val toRate = CurrencyCode.fromCode(toCurrency).defaultRateToCny
        return (cnyCents / toRate).roundToLong()
    }
}
