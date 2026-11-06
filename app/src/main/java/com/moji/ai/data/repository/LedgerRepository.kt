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

    suspend fun getAllTransactions(): List<TransactionEntity> = db.transactionDao().getAllTransactions()

    suspend fun getAllRecurringRules(): List<RecurringRuleEntity> = db.recurringRuleDao().getAllRules()

    suspend fun getRecentRefundableExpenses(limit: Int = 15): List<TransactionEntity> =
        db.transactionDao().getRecentRefundableExpenses(limit)

    /**
     * 账户真实余额锚点登记 + 差额自动生成平账流水（对标 blue-whale-keeper 与 moneywallet）：
     * - 当 recordAdjustmentTx = true 时，自动计算「真实余额 - 账面余额」的差额，生成一笔「平账」支出/收入/冲销流水并原子化校准余额；
     * - 当 recordAdjustmentTx = false 时，仅直接调整期初/账面余额。
     */
    suspend fun reconcileWalletBalance(
        walletId: Long,
        actualBalanceCents: Long,
        ledgerName: String = "默认账本",
        recordAdjustmentTx: Boolean = true,
        customNote: String = ""
    ): TransactionEntity? = db.withTransaction {
        val walletDao = db.walletDao()
        val wallet = walletDao.getWalletById(walletId) ?: return@withTransaction null
        val sanitizedActual = actualBalanceCents.coerceAtLeast(0L)
        val currentBook = wallet.balanceCents
        val diff = sanitizedActual - currentBook
        if (diff == 0L) return@withTransaction null

        if (!recordAdjustmentTx) {
            walletDao.updateWallet(wallet.copy(balanceCents = sanitizedActual))
            return@withTransaction null
        }

        val rate = CurrencyCode.fromCode(wallet.currency).defaultRateToCny
        val absDiff = kotlin.math.abs(diff)
        val absCnyCents = (absDiff * rate).roundToLong()

        val adjustmentTx = if (!wallet.type.isLiability) {
            // 资产账户：真实余额 < 账面余额 => 期间有漏记支出；真实余额 > 账面余额 => 期间有漏记收入
            if (diff < 0L) {
                TransactionEntity(
                    ledgerName = ledgerName,
                    type = TransactionType.EXPENSE,
                    amountCents = absDiff,
                    currency = wallet.currency,
                    exchangeRateToCny = rate,
                    amountCnyCents = absCnyCents,
                    category = "平账",
                    merchant = "${wallet.name} 余额锚点校准",
                    note = customNote.ifBlank { "真实余额比账面少 ${String.format("%.2f", absDiff / 100.0)}，自动生成漏记支出补差" },
                    sourceWalletId = wallet.id,
                    tags = "平账",
                    captureSource = "BALANCE_ANCHOR"
                )
            } else {
                TransactionEntity(
                    ledgerName = ledgerName,
                    type = TransactionType.INCOME,
                    amountCents = absDiff,
                    currency = wallet.currency,
                    exchangeRateToCny = rate,
                    amountCnyCents = absCnyCents,
                    category = "平账",
                    merchant = "${wallet.name} 余额锚点校准",
                    note = customNote.ifBlank { "真实余额比账面多 ${String.format("%.2f", absDiff / 100.0)}，自动生成漏记收入补差" },
                    sourceWalletId = null,
                    targetWalletId = wallet.id,
                    tags = "平账",
                    captureSource = "BALANCE_ANCHOR"
                )
            }
        } else {
            // 信用账户（balanceCents 为待还欠款）：真实欠款 > 账面欠款 => 漏记消费；真实欠款 < 账面欠款 => 冲减欠款
            if (diff > 0L) {
                TransactionEntity(
                    ledgerName = ledgerName,
                    type = TransactionType.EXPENSE,
                    amountCents = absDiff,
                    currency = wallet.currency,
                    exchangeRateToCny = rate,
                    amountCnyCents = absCnyCents,
                    category = "平账",
                    merchant = "${wallet.name} 欠款锚点校准",
                    note = customNote.ifBlank { "真实待还比账面多 ${String.format("%.2f", absDiff / 100.0)}，自动补记信用消费" },
                    sourceWalletId = wallet.id,
                    tags = "平账",
                    captureSource = "BALANCE_ANCHOR"
                )
            } else {
                TransactionEntity(
                    ledgerName = ledgerName,
                    type = TransactionType.REFUND_OFFSET,
                    amountCents = absDiff,
                    currency = wallet.currency,
                    exchangeRateToCny = rate,
                    amountCnyCents = absCnyCents,
                    category = "平账",
                    merchant = "${wallet.name} 欠款锚点校准",
                    note = customNote.ifBlank { "真实待还比账面少 ${String.format("%.2f", absDiff / 100.0)}，自动冲减信用欠款" },
                    sourceWalletId = null,
                    targetWalletId = wallet.id,
                    tags = "平账",
                    captureSource = "BALANCE_ANCHOR"
                )
            }
        }

        val id = recordTransaction(adjustmentTx)
        adjustmentTx.copy(id = id)
    }

    /**
     * 批量导入 CSV 流水（带重复流水过滤与可选余额联动）
     */
    suspend fun importExternalTransactions(
        candidates: List<TransactionEntity>,
        applyToWalletBalance: Boolean = false
    ): Int = db.withTransaction {
        val existing = db.transactionDao().getAllTransactions()
        val existingKeys = existing.map {
            "${it.timestamp / 60_000L}_${it.type.name}_${it.amountCents}_${it.merchant.take(8)}"
        }.toMutableSet()

        var importedCount = 0
        for (item in candidates) {
            val dedupKey = "${item.timestamp / 60_000L}_${item.type.name}_${item.amountCents}_${item.merchant.take(8)}"
            if (dedupKey in existingKeys) continue
            existingKeys.add(dedupKey)
            if (applyToWalletBalance) {
                recordTransaction(item.copy(id = 0L))
            } else {
                db.transactionDao().insertTransaction(item.copy(id = 0L))
            }
            importedCount++
        }
        importedCount
    }

    /**
     * 全量备份恢复（原子化合并或恢复钱包、交易与周期规则）
     */
    suspend fun restoreFullSnapshot(
        wallets: List<WalletEntity>,
        transactions: List<TransactionEntity>,
        recurringRules: List<RecurringRuleEntity>
    ): Int = db.withTransaction {
        if (wallets.isNotEmpty()) {
            db.walletDao().insertAll(wallets)
        }
        if (transactions.isNotEmpty()) {
            db.transactionDao().insertAllTransactions(transactions)
        }
        if (recurringRules.isNotEmpty()) {
            db.recurringRuleDao().insertAllRules(recurringRules)
        }
        transactions.size
    }

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
