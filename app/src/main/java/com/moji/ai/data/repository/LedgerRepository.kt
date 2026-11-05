package com.moji.ai.data.repository

import androidx.room.withTransaction
import com.moji.ai.data.local.AppDatabase
import com.moji.ai.data.local.RecurringRuleEntity
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.TransactionType
import kotlin.math.roundToLong

class LedgerRepository(private val db: AppDatabase) {

    val walletsFlow = db.walletDao().observeAllWallets()
    val transactionsFlow = db.transactionDao().observeAllTransactions()
    val recurringRulesFlow = db.recurringRuleDao().observeAllRules()

    suspend fun getAllWallets(): List<WalletEntity> = db.walletDao().getAllWallets()

    suspend fun getRecentRefundableExpenses(limit: Int = 15): List<TransactionEntity> =
        db.transactionDao().getRecentRefundableExpenses(limit)

    suspend fun recordTransaction(tx: TransactionEntity): Long = db.withTransaction {
        val txDao = db.transactionDao()
        val finalTx = applyWalletImpact(tx)
        txDao.insertTransaction(finalTx)
    }

    /**
     * 编辑已有账单：先回滚旧账单对钱包/冲销的影响，再应用新账单影响并更新原记录
     */
    suspend fun updateExistingTransaction(updatedTx: TransactionEntity): Boolean = db.withTransaction {
        val txDao = db.transactionDao()
        val oldTx = txDao.getTransactionById(updatedTx.id) ?: return@withTransaction false
        revertWalletImpact(oldTx)
        val applied = applyWalletImpact(updatedTx)
        txDao.updateTransaction(applied)
        true
    }

    /**
     * 对指定消费账单发起全额或部分退款（自动生成“退款入账”流水并扣减原账单净支出）
     */
    suspend fun refundExpense(
        expenseId: Long,
        refundAmountCents: Long,
        receiveWalletId: Long?,
        refundNote: String = ""
    ): Long? = db.withTransaction {
        val txDao = db.transactionDao()
        val expense = txDao.getTransactionById(expenseId) ?: return@withTransaction null
        if (expense.type != TransactionType.EXPENSE) return@withTransaction null

        val maxRefundable = (expense.amountCnyCents - expense.refundedAmountCents).coerceAtLeast(0L)
        val actualRefund = refundAmountCents.coerceAtMost(maxRefundable)
        if (actualRefund <= 0L) return@withTransaction null

        val targetWalletId = receiveWalletId ?: expense.sourceWalletId
        val refundTx = TransactionEntity(
            ledgerName = expense.ledgerName,
            type = TransactionType.REFUND_OFFSET,
            amountCents = actualRefund,
            currency = "CNY",
            exchangeRateToCny = 1.0,
            amountCnyCents = actualRefund,
            category = "退款入账",
            merchant = if (expense.merchant.isNotBlank()) "${expense.category} · ${expense.merchant}" else expense.category,
            note = refundNote.ifBlank { "关联原账单退款冲销" },
            sourceWalletId = null,
            targetWalletId = targetWalletId,
            relatedTransactionId = expense.id,
            captureSource = "MANUAL"
        )
        recordTransaction(refundTx)
    }

    /**
     * 对待报销账单完成报销入账
     */
    suspend fun settleReimbursement(
        expenseId: Long,
        receiveWalletId: Long?
    ): Boolean = db.withTransaction {
        val txDao = db.transactionDao()
        val walletDao = db.walletDao()
        val expense = txDao.getTransactionById(expenseId) ?: return@withTransaction false
        if (!expense.reimbursable || expense.reimbursed) return@withTransaction false

        val targetId = receiveWalletId ?: expense.sourceWalletId
        targetId?.let { wid ->
            walletDao.getWalletById(wid)?.let { w ->
                val delta = convertCentsBetweenCurrencies(expense.amountCents, expense.currency, w.currency, expense.exchangeRateToCny)
                val newBal = if (w.type.isLiability) (w.balanceCents - delta).coerceAtLeast(0L) else w.balanceCents + delta
                walletDao.updateWallet(w.copy(balanceCents = newBal))
            }
        }
        txDao.updateTransaction(expense.copy(reimbursed = true))
        true
    }

    suspend fun undoAndDeleteTransaction(transactionId: Long): Boolean = db.withTransaction {
        val txDao = db.transactionDao()
        val tx = txDao.getTransactionById(transactionId) ?: return@withTransaction false
        revertWalletImpact(tx)
        txDao.deleteTransaction(tx)
        true
    }

    suspend fun saveWallet(wallet: WalletEntity): Long = db.walletDao().insertWallet(wallet)

    suspend fun updateWallet(wallet: WalletEntity) = db.walletDao().updateWallet(wallet)

    suspend fun deleteWallet(wallet: WalletEntity) = db.walletDao().deleteWallet(wallet)

    suspend fun saveRecurringRule(rule: RecurringRuleEntity): Long = db.recurringRuleDao().insertRule(rule)

    suspend fun deleteRecurringRule(rule: RecurringRuleEntity) = db.recurringRuleDao().deleteRule(rule)

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
                note = "周期性账单自动入账 (${rule.frequency.displayName})",
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

    private suspend fun applyWalletImpact(tx: TransactionEntity): TransactionEntity {
        val walletDao = db.walletDao()
        val txDao = db.transactionDao()
        val totalOutCents = tx.amountCents + tx.feeCents

        when (tx.type) {
            TransactionType.EXPENSE, TransactionType.LOAN_OUT -> {
                tx.sourceWalletId?.let { walletId ->
                    walletDao.getWalletById(walletId)?.let { wallet ->
                        val delta = convertCentsBetweenCurrencies(totalOutCents, tx.currency, wallet.currency, tx.exchangeRateToCny)
                        val newBalance = if (wallet.type.isLiability) wallet.balanceCents + delta else wallet.balanceCents - delta
                        walletDao.updateWallet(wallet.copy(balanceCents = newBalance))
                    }
                }
            }

            TransactionType.INCOME, TransactionType.LOAN_IN -> {
                val targetId = tx.targetWalletId ?: tx.sourceWalletId
                targetId?.let { walletId ->
                    walletDao.getWalletById(walletId)?.let { wallet ->
                        val delta = convertCentsBetweenCurrencies(tx.amountCents - tx.feeCents, tx.currency, wallet.currency, tx.exchangeRateToCny)
                        val newBalance = if (wallet.type.isLiability) (wallet.balanceCents - delta).coerceAtLeast(0L) else wallet.balanceCents + delta
                        walletDao.updateWallet(wallet.copy(balanceCents = newBalance))
                    }
                }
            }

            TransactionType.TRANSFER -> {
                tx.sourceWalletId?.let { fromId ->
                    walletDao.getWalletById(fromId)?.let { fromWallet ->
                        val delta = convertCentsBetweenCurrencies(totalOutCents, tx.currency, fromWallet.currency, tx.exchangeRateToCny)
                        val newBal = if (fromWallet.type.isLiability) fromWallet.balanceCents + delta else fromWallet.balanceCents - delta
                        walletDao.updateWallet(fromWallet.copy(balanceCents = newBal))
                    }
                }
                tx.targetWalletId?.let { toId ->
                    walletDao.getWalletById(toId)?.let { toWallet ->
                        val delta = convertCentsBetweenCurrencies(tx.amountCents, tx.currency, toWallet.currency, tx.exchangeRateToCny)
                        val newBal = if (toWallet.type.isLiability) (toWallet.balanceCents - delta).coerceAtLeast(0L) else toWallet.balanceCents + delta
                        walletDao.updateWallet(toWallet.copy(balanceCents = newBal))
                    }
                }
            }

            TransactionType.REPAYMENT -> {
                tx.sourceWalletId?.let { fromId ->
                    walletDao.getWalletById(fromId)?.let { fromWallet ->
                        val delta = convertCentsBetweenCurrencies(totalOutCents, tx.currency, fromWallet.currency, tx.exchangeRateToCny)
                        walletDao.updateWallet(fromWallet.copy(balanceCents = fromWallet.balanceCents - delta))
                    }
                }
                tx.targetWalletId?.let { creditId ->
                    walletDao.getWalletById(creditId)?.let { creditWallet ->
                        val delta = convertCentsBetweenCurrencies(tx.amountCents, tx.currency, creditWallet.currency, tx.exchangeRateToCny)
                        walletDao.updateWallet(creditWallet.copy(balanceCents = (creditWallet.balanceCents - delta).coerceAtLeast(0L)))
                    }
                }
            }

            TransactionType.REFUND_OFFSET -> {
                val receiveWalletId = tx.targetWalletId ?: tx.sourceWalletId
                receiveWalletId?.let { walletId ->
                    walletDao.getWalletById(walletId)?.let { wallet ->
                        val delta = convertCentsBetweenCurrencies(tx.amountCents, tx.currency, wallet.currency, tx.exchangeRateToCny)
                        val newBal = if (wallet.type.isLiability) (wallet.balanceCents - delta).coerceAtLeast(0L) else wallet.balanceCents + delta
                        walletDao.updateWallet(wallet.copy(balanceCents = newBal))
                    }
                }
                val targetExpense = tx.relatedTransactionId?.let { txDao.getTransactionById(it) }
                    ?: findBestMatchingExpenseForRefund(tx)
                if (targetExpense != null) {
                    val updatedRefunded = (targetExpense.refundedAmountCents + tx.amountCnyCents)
                        .coerceAtMost(targetExpense.amountCnyCents)
                    txDao.updateTransaction(targetExpense.copy(refundedAmountCents = updatedRefunded))
                    return tx.copy(relatedTransactionId = targetExpense.id)
                }
            }
        }
        return tx
    }

    private suspend fun revertWalletImpact(tx: TransactionEntity) {
        val walletDao = db.walletDao()
        val txDao = db.transactionDao()
        val totalOutCents = tx.amountCents + tx.feeCents

        when (tx.type) {
            TransactionType.EXPENSE, TransactionType.LOAN_OUT -> {
                tx.sourceWalletId?.let { walletId ->
                    walletDao.getWalletById(walletId)?.let { wallet ->
                        val delta = convertCentsBetweenCurrencies(totalOutCents, tx.currency, wallet.currency, tx.exchangeRateToCny)
                        val restored = if (wallet.type.isLiability) (wallet.balanceCents - delta).coerceAtLeast(0L) else wallet.balanceCents + delta
                        walletDao.updateWallet(wallet.copy(balanceCents = restored))
                    }
                }
            }
            TransactionType.INCOME, TransactionType.LOAN_IN -> {
                val targetId = tx.targetWalletId ?: tx.sourceWalletId
                targetId?.let { walletId ->
                    walletDao.getWalletById(walletId)?.let { wallet ->
                        val delta = convertCentsBetweenCurrencies(tx.amountCents - tx.feeCents, tx.currency, wallet.currency, tx.exchangeRateToCny)
                        val restored = if (wallet.type.isLiability) wallet.balanceCents + delta else wallet.balanceCents - delta
                        walletDao.updateWallet(wallet.copy(balanceCents = restored))
                    }
                }
            }
            TransactionType.TRANSFER -> {
                tx.sourceWalletId?.let { fromId ->
                    walletDao.getWalletById(fromId)?.let { w ->
                        val delta = convertCentsBetweenCurrencies(totalOutCents, tx.currency, w.currency, tx.exchangeRateToCny)
                        val restored = if (w.type.isLiability) (w.balanceCents - delta).coerceAtLeast(0L) else w.balanceCents + delta
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
                        val delta = convertCentsBetweenCurrencies(totalOutCents, tx.currency, w.currency, tx.exchangeRateToCny)
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
    }

    private suspend fun findBestMatchingExpenseForRefund(refundTx: TransactionEntity): TransactionEntity? {
        val candidates = db.transactionDao().getRecentRefundableExpenses(15)
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
