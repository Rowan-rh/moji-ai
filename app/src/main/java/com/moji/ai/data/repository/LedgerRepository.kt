package com.moji.ai.data.repository

import androidx.room.withTransaction
import com.moji.ai.data.backup.BackupAndCsvTransferEngine
import com.moji.ai.data.backup.CsvParsePreviewResult
import com.moji.ai.data.local.AppDatabase
import com.moji.ai.data.local.RecurringRuleEntity
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CategoryCatalog
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.CurrencyRateManager
import com.moji.ai.data.model.TagCatalog
import com.moji.ai.data.model.TransactionType
import kotlin.math.roundToLong

data class BatchImportCommitSummary(
    val createdWalletCount: Int,
    val updatedWalletCount: Int,
    val importedTxCount: Int,
    val skippedDuplicateTxCount: Int
)

class LedgerRepository(private val db: AppDatabase) {

    val walletsFlow = db.walletDao().observeAllWallets()
    val transactionsFlow = db.transactionDao().observeAllTransactions()
    val recurringRulesFlow = db.recurringRuleDao().observeAllRules()

    suspend fun getAllWallets(): List<WalletEntity> = db.walletDao().getAllWallets()

    suspend fun getAllTransactions(): List<TransactionEntity> = db.transactionDao().getAllTransactions()

    suspend fun getAllRecurringRules(): List<RecurringRuleEntity> = db.recurringRuleDao().getAllRules()

    suspend fun getRecentRefundableExpenses(limit: Int = 15): List<TransactionEntity> =
        db.transactionDao().getRecentRefundableExpenses(limit)

    suspend fun getTransactionById(id: Long): TransactionEntity? =
        if (id <= 0L) null else db.transactionDao().getTransactionById(id)

    suspend fun findTransactionByOrderKey(orderKey: String): TransactionEntity? =
        if (orderKey.isBlank()) null else db.transactionDao().findByOrderKey(orderKey)

    suspend fun findTransactionByNo(txNo: String): TransactionEntity? =
        if (txNo.isBlank()) null else db.transactionDao().findByTransactionNo(txNo)

    suspend fun findTransactionByMerchantOrderNo(orderNo: String): TransactionEntity? =
        if (orderNo.isBlank()) null else db.transactionDao().findByMerchantOrderNo(orderNo)

    suspend fun findTransactionByScreenshotSha256(sha256: String): TransactionEntity? =
        if (sha256.isBlank()) null else db.transactionDao().findByScreenshotSha256(sha256)

    suspend fun findTransactionsByAmountAndOccurredWindow(
        amountCents: Long,
        occurredAtMillis: Long,
        toleranceMillis: Long = 60_000L
    ): List<TransactionEntity> =
        db.transactionDao().findByAmountAndOccurredWindow(amountCents, occurredAtMillis, toleranceMillis)

    suspend fun findTransactionsByAmountAndTimeOrCapturedWindow(
        amountCents: Long,
        occurredAtMillis: Long,
        capturedAtMillis: Long,
        toleranceMillis: Long = 65_000L
    ): List<TransactionEntity> =
        db.transactionDao().findByAmountAndTimeOrCapturedWindow(
            amountCents = amountCents,
            occurredAtMillis = occurredAtMillis,
            capturedAtMillis = capturedAtMillis,
            toleranceMillis = toleranceMillis
        )

    suspend fun insertCaptureEvidence(evidence: com.moji.ai.data.local.CaptureEvidenceEntity): Long =
        db.captureEvidenceDao().insertEvidence(evidence)

    suspend fun getEvidencesForTransaction(txId: Long): List<com.moji.ai.data.local.CaptureEvidenceEntity> =
        db.captureEvidenceDao().getEvidencesForTransaction(txId)

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

        val rate = CurrencyRateManager.getEffectiveRateToCny(wallet.currency)
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
     * 批量导入 iCost / 微信 / 支付宝 / 钱迹 CSV（支持原子化创建新资产账户、更新已有资产余额、绑定新建账户 ID 并去重入账）
     */
    suspend fun importExternalBatch(
        preview: CsvParsePreviewResult,
        applyToWalletBalance: Boolean = false
    ): BatchImportCommitSummary = db.withTransaction {
        val walletDao = db.walletDao()
        val txDao = db.transactionDao()

        val walletKeyToId = mutableMapOf<String, Long>()
        val walletsWithExplicitSnapshotBalance = mutableSetOf<Long>()

        for (w in walletDao.getAllWallets()) {
            walletKeyToId[BackupAndCsvTransferEngine.normalizeAccountKey(w.name, w.cardLast4)] = w.id
            walletKeyToId[BackupAndCsvTransferEngine.normalizeAccountKey(w.name, null)] = w.id
        }

        var updatedWalletCount = 0
        for (toUpdate in preview.existingWalletsToUpdate) {
            walletDao.updateWallet(toUpdate.copy(updatedAtMillis = System.currentTimeMillis()))
            walletKeyToId[BackupAndCsvTransferEngine.normalizeAccountKey(toUpdate.name, toUpdate.cardLast4)] = toUpdate.id
            walletKeyToId[BackupAndCsvTransferEngine.normalizeAccountKey(toUpdate.name, null)] = toUpdate.id
            walletsWithExplicitSnapshotBalance.add(toUpdate.id)
            updatedWalletCount++
        }

        var createdWalletCount = 0
        for (toCreate in preview.newWalletsToCreate) {
            val newId = walletDao.insertWallet(toCreate.copy(id = 0L, updatedAtMillis = System.currentTimeMillis()))
            walletKeyToId[BackupAndCsvTransferEngine.normalizeAccountKey(toCreate.name, toCreate.cardLast4)] = newId
            walletKeyToId[BackupAndCsvTransferEngine.normalizeAccountKey(toCreate.name, null)] = newId
            if (toCreate.balanceCents != 0L) {
                walletsWithExplicitSnapshotBalance.add(newId)
            }
            createdWalletCount++
        }

        val existing = txDao.getAllTransactions()
        val existingKeys = existing.map {
            "${it.timestamp / 60_000L}_${it.type.name}_${it.amountCents}"
        }.toMutableSet()

        var importedCount = 0
        var skippedDuplicates = 0

        for ((idx, item) in preview.parsedTransactions.withIndex()) {
            val resolvedSrcId = item.sourceWalletId ?: preview.sourceWalletNameByIndex[idx]?.let { walletKeyToId[it] }
            val resolvedDstId = item.targetWalletId ?: preview.targetWalletNameByIndex[idx]?.let { walletKeyToId[it] }

            val boundTx = item.copy(
                id = 0L,
                sourceWalletId = resolvedSrcId,
                targetWalletId = resolvedDstId
            )

            val dedupKey = "${boundTx.timestamp / 60_000L}_${boundTx.type.name}_${boundTx.amountCents}"
            if (dedupKey in existingKeys) {
                skippedDuplicates++
                continue
            }
            existingKeys.add(dedupKey)

            val touchesExplicitBalanceWallet = (resolvedSrcId != null && resolvedSrcId in walletsWithExplicitSnapshotBalance) ||
                (resolvedDstId != null && resolvedDstId in walletsWithExplicitSnapshotBalance)

            if (applyToWalletBalance && !touchesExplicitBalanceWallet) {
                recordTransaction(boundTx)
            } else {
                txDao.insertTransaction(boundTx)
            }
            importedCount++
        }

        BatchImportCommitSummary(
            createdWalletCount = createdWalletCount,
            updatedWalletCount = updatedWalletCount,
            importedTxCount = importedCount,
            skippedDuplicateTxCount = skippedDuplicates
        )
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
        val id = txDao.insertTransaction(finalTx)
        if (!finalTx.isSystemBalanceAnchor() && finalTx.tags.isNotBlank()) {
            TagCatalog.syncDiscoveredTagsFromHistory(listOf(finalTx.tags))
        }
        id
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
        if (!applied.isSystemBalanceAnchor() && applied.tags.isNotBlank()) {
            TagCatalog.syncDiscoveredTagsFromHistory(listOf(applied.tags))
        }
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

    /**
     * 批量报销核销入账（对标 Cashew 借贷/报销分批核销闭环）：
     * 一次性将多笔「待报销」消费标记为已报销，并原子化恢复指定收款账户余额。
     */
    suspend fun batchSettleReimbursements(
        expenseIds: List<Long>,
        receiveWalletId: Long?
    ): Int = db.withTransaction {
        if (expenseIds.isEmpty()) return@withTransaction 0
        val txDao = db.transactionDao()
        val walletDao = db.walletDao()
        var settledCount = 0

        for (id in expenseIds.distinct()) {
            val expense = txDao.getTransactionById(id) ?: continue
            if (!expense.reimbursable || expense.reimbursed) continue

            val targetId = receiveWalletId ?: expense.sourceWalletId
            targetId?.let { wid ->
                walletDao.getWalletById(wid)?.let { w ->
                    val netCents = (expense.amountCents - expense.refundedAmountCents).coerceAtLeast(0L)
                    val delta = convertCentsBetweenCurrencies(netCents, expense.currency, w.currency, expense.exchangeRateToCny)
                    val newBal = if (w.type.isLiability) (w.balanceCents - delta).coerceAtLeast(0L) else w.balanceCents + delta
                    walletDao.updateWallet(w.copy(balanceCents = newBal))
                }
            }
            txDao.updateTransaction(expense.copy(reimbursed = true))
            settledCount++
        }
        settledCount
    }

    /**
     * 单笔账单多分类拆分（Split Transaction，对标 MyExpenses 父子分拆账单）：
     * 在保持原付款钱包总扣款金额完全不变的前提下，将一笔消费拆分为两笔独立分类的子消费记录。
     */
    suspend fun splitExpenseTransaction(
        originalTxId: Long,
        categoryA: String,
        amountACents: Long,
        categoryB: String,
        noteB: String = ""
    ): Boolean = db.withTransaction {
        val txDao = db.transactionDao()
        val orig = txDao.getTransactionById(originalTxId) ?: return@withTransaction false
        if (orig.type != TransactionType.EXPENSE) return@withTransaction false

        val partACents = amountACents.coerceIn(1L, (orig.amountCents - 1L).coerceAtLeast(1L))
        val partBCents = orig.amountCents - partACents
        if (partBCents <= 0L) return@withTransaction false

        val partACnyCents = (partACents * orig.exchangeRateToCny).roundToLong()
        val partBCnyCents = (orig.amountCnyCents - partACnyCents).coerceAtLeast(1L)

        val updatedOrig = orig.copy(
            category = categoryA.ifBlank { orig.category },
            amountCents = partACents,
            amountCnyCents = partACnyCents,
            refundedAmountCents = orig.refundedAmountCents.coerceAtMost(partACnyCents),
            note = if (orig.note.contains("[拆分")) orig.note else "${orig.note} [拆分1/2]".trim()
        )
        txDao.updateTransaction(updatedOrig)

        val childB = orig.copy(
            id = 0L,
            category = categoryB.ifBlank { "日用" },
            amountCents = partBCents,
            amountCnyCents = partBCnyCents,
            discountCents = 0L,
            feeCents = 0L,
            refundedAmountCents = 0L,
            relatedTransactionId = orig.id,
            timestamp = orig.timestamp - 1L,
            note = noteB.ifBlank { "${orig.note} [拆分2/2]".trim() }
        )
        txDao.insertTransaction(childB)
        true
    }

    suspend fun undoAndDeleteTransaction(transactionId: Long): Boolean {
        var screenshotToDelete: String? = null
        val deleted = db.withTransaction {
            val txDao = db.transactionDao()
            val tx = txDao.getTransactionById(transactionId) ?: return@withTransaction false
            revertWalletImpact(tx)
            txDao.deleteTransaction(tx)
            if (tx.screenshotPath.isNotBlank()) {
                val stillReferenced = txDao.getAllTransactions().any {
                    it.id != tx.id && it.screenshotPath == tx.screenshotPath
                }
                if (!stillReferenced) {
                    screenshotToDelete = tx.screenshotPath
                }
            }
            true
        }
        if (deleted && !screenshotToDelete.isNullOrBlank()) {
            com.moji.ai.service.BillScreenshotStorage.deleteIfExists(screenshotToDelete)
        }
        return deleted
    }

    suspend fun clearAllTransactionsAndEvidences() = db.withTransaction {
        db.transactionDao().deleteAllTransactions()
        db.captureEvidenceDao().deleteAllEvidences()
        val walletDao = db.walletDao()
        val currentWallets = walletDao.getAllWallets()
        for (w in currentWallets) {
            walletDao.updateWallet(
                w.copy(
                    balanceCents = 0L,
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun saveWallet(wallet: WalletEntity): Long = db.walletDao().insertWallet(wallet)

    suspend fun updateWallet(wallet: WalletEntity) = db.walletDao().updateWallet(wallet)

    suspend fun deleteWallet(wallet: WalletEntity) = db.walletDao().deleteWallet(wallet)

    /**
     * 1:1 同步 iCost 全部 11 个资产账户（7 个信用账户 + 4 个资金账户）及余额、总额度、尾号与还款日，
     * 同时重映射已有流水的关联账户 ID，清理旧版演示用的“招商银行储蓄卡(8848)”及演示周期规则。
     */
    suspend fun syncICostAllAccounts() = db.withTransaction {
        val walletDao = db.walletDao()
        val txDao = db.transactionDao()
        val ruleDao = db.recurringRuleDao()

        val oldWallets = walletDao.getAllWallets()
        val oldAlipayId = oldWallets.firstOrNull { it.brandCode == "ALIPAY" || it.name.contains("账户余额") }?.id ?: 4L
        val oldWechatId = oldWallets.firstOrNull { it.brandCode == "WECHAT" || it.name.contains("微信零钱") }?.id ?: 5L

        walletDao.deleteAllWallets()
        walletDao.insertAll(AppDatabase.defaultWallets())

        val allTxs = txDao.getAllTransactions()
        for (tx in allTxs) {
            val pm = tx.paymentMethodRaw
            val newSourceId = when {
                pm.contains("3625") -> 11L
                pm.contains("5021") -> 10L
                pm.contains("3354") -> 1L
                tx.sourceWalletId == 1L -> 1L
                tx.sourceWalletId == oldAlipayId -> 8L
                tx.sourceWalletId == oldWechatId -> 9L
                else -> tx.sourceWalletId
            }
            val newTargetId = when {
                tx.targetWalletId == oldAlipayId -> 8L
                tx.targetWalletId == oldWechatId -> 9L
                else -> tx.targetWalletId
            }
            if (newSourceId != tx.sourceWalletId || newTargetId != tx.targetWalletId) {
                txDao.updateTransaction(
                    tx.copy(
                        sourceWalletId = newSourceId,
                        targetWalletId = newTargetId,
                        updatedAtMillis = System.currentTimeMillis()
                    )
                )
            }
        }

        // 清理旧版绑定在演示卡 8848 上的「每月房租」演示周期规则
        for (rule in ruleDao.getAllRules()) {
            if (rule.title == "每月房租" && rule.walletId == 6L) {
                ruleDao.deleteRule(rule)
            }
        }
    }

    suspend fun saveRecurringRule(rule: RecurringRuleEntity): Long = db.recurringRuleDao().insertRule(rule)

    suspend fun deleteRecurringRule(rule: RecurringRuleEntity) = db.recurringRuleDao().deleteRule(rule)

    private fun TransactionType.matchesDirectionFilter(isExpense: Boolean?): Boolean = when (isExpense) {
        true -> this == TransactionType.EXPENSE
        false -> this == TransactionType.INCOME
        null -> true
    }

    /**
     * 统计指定分类（含可选二级分类前缀）关联的未删除历史账单数量（排除系统平账流水）
     */
    suspend fun countTransactionsByCategory(
        category: String,
        includeSubPrefix: Boolean,
        isExpense: Boolean? = null
    ): Int {
        return db.transactionDao().getAllTransactions().count { tx ->
            !tx.isSystemBalanceAnchor() &&
                tx.type.matchesDirectionFilter(isExpense) &&
                CategoryCatalog.matchesCategoryScope(tx.category, category, includeSubPrefix)
        }
    }

    /**
     * 在 Room 事务中原子级联更新历史账单与周期性规则的分类字段，并刷新 updatedAtMillis 与 syncDirty
     * @return 实际受影响更新的历史账单笔数
     */
    suspend fun migrateCategoryCascade(
        oldCategory: String,
        newCategory: String,
        includeSubPrefix: Boolean,
        preserveSubSuffix: Boolean,
        isExpense: Boolean? = null
    ): Int = db.withTransaction {
        val now = System.currentTimeMillis()
        val txDao = db.transactionDao()
        val ruleDao = db.recurringRuleDao()
        var updatedTxCount = 0

        for (tx in txDao.getAllTransactions()) {
            if (tx.isSystemBalanceAnchor()) continue
            if (!tx.type.matchesDirectionFilter(isExpense)) continue
            val mapped = CategoryCatalog.remapCascadedCategory(
                current = tx.category,
                oldCategory = oldCategory,
                newCategory = newCategory,
                includeSubPrefix = includeSubPrefix,
                preserveSubSuffix = preserveSubSuffix
            )
            if (mapped != null) {
                txDao.updateTransactionCategoryById(tx.id, mapped, now)
                updatedTxCount++
            }
        }

        for (rule in ruleDao.getAllRules()) {
            if (!rule.type.matchesDirectionFilter(isExpense)) continue
            val mapped = CategoryCatalog.remapCascadedCategory(
                current = rule.category,
                oldCategory = oldCategory,
                newCategory = newCategory,
                includeSubPrefix = includeSubPrefix,
                preserveSubSuffix = preserveSubSuffix
            )
            if (mapped != null) {
                ruleDao.updateRuleCategoryById(rule.id, mapped, now)
            }
        }
        updatedTxCount
    }

    /**
     * 在 Room 事务中批量更新指定账单的分类字段（用于 Agent 梳理结果一键采纳生效）
     */
    suspend fun batchUpdateTransactionCategories(
        updates: Map<Long, String>
    ): Int = db.withTransaction {
        if (updates.isEmpty()) return@withTransaction 0
        val now = System.currentTimeMillis()
        val txDao = db.transactionDao()
        var updatedCount = 0
        for ((txId, newCat) in updates) {
            if (txId > 0L && newCat.isNotBlank()) {
                txDao.updateTransactionCategoryById(txId, newCat.trim(), now)
                updatedCount++
            }
        }
        updatedCount
    }

    /**
     * 统计包含指定标签 Token 的未删除历史账单数量（排除系统平账流水）
     */
    suspend fun countTransactionsByTag(tagName: String): Int {
        return db.transactionDao().getAllTransactions().count { tx ->
            !tx.isSystemBalanceAnchor() && TagCatalog.containsTag(tx.tags, tagName)
        }
    }

    /**
     * 在 Room 事务中按精确标签 Token 拆分并级联替换或移除历史账单中的标签（自动去重并刷新 updatedAtMillis / syncRevision / syncDirty）
     * @param oldTag 原标签名称
     * @param newTag 新标签名称（传入 null 或空串表示从账单标签中移除 oldTag）
     * @return 实际受影响更新的历史账单笔数
     */
    suspend fun migrateTagCascade(
        oldTag: String,
        newTag: String?
    ): Int = db.withTransaction {
        val now = System.currentTimeMillis()
        val txDao = db.transactionDao()
        var updatedTxCount = 0

        for (tx in txDao.getAllTransactions()) {
            if (tx.isSystemBalanceAnchor()) continue
            val mapped = TagCatalog.remapTagsString(tx.tags, oldTag, newTag)
            if (mapped != null) {
                txDao.updateTransactionTagsById(tx.id, mapped, now)
                updatedTxCount++
            }
        }
        updatedTxCount
    }

    suspend fun processDueRecurringRules(nowMillis: Long = System.currentTimeMillis()): List<TransactionEntity> {
        val dueRules = db.recurringRuleDao().getDueRules(nowMillis)
        val created = mutableListOf<TransactionEntity>()
        for (rule in dueRules) {
            val rate = CurrencyRateManager.getEffectiveRateToCny(rule.currency)
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
        val candidates = db.transactionDao().getRecentRefundableExpenses(30)
        val refundKeys = listOf(refundTx.orderKey, refundTx.transactionNo, refundTx.merchantOrderNo)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        // 1. 优先匹配相同订单号/交易单号/商户单号的原消费账单
        if (refundKeys.isNotEmpty()) {
            candidates.firstOrNull { exp ->
                val expKeys = listOf(exp.orderKey, exp.transactionNo, exp.merchantOrderNo)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                (exp.amountCnyCents - exp.refundedAmountCents) >= refundTx.amountCnyCents &&
                    refundKeys.any { it in expKeys }
            }?.let { return it }
        }
        // 2. 无单号匹配时，仅匹配消费时间不晚于退款时间（允许同日误差）、金额完全一致且商户或商品名一致的原账单
        val refundGoods = refundTx.note.substringAfter("| ", "").substringBefore(" · ").trim()
        return candidates.firstOrNull { exp ->
            val expGoods = exp.note.substringAfter("| ", "").substringBefore(" · ").trim()
            val sameMerchant = refundTx.merchant.isNotBlank() && exp.merchant.isNotBlank() &&
                (exp.merchant.contains(refundTx.merchant, ignoreCase = true) ||
                    refundTx.merchant.contains(exp.merchant, ignoreCase = true))
            val sameGoods = refundGoods.length >= 4 && expGoods.length >= 4 &&
                (expGoods.startsWith(refundGoods.take(8)) || refundGoods.startsWith(expGoods.take(8)))
            exp.refundedAmountCents == 0L &&
                exp.amountCnyCents == refundTx.amountCnyCents &&
                exp.timestamp <= refundTx.timestamp + 86_400_000L &&
                (sameMerchant || sameGoods)
        }
    }

    private fun convertCentsBetweenCurrencies(
        amountCents: Long,
        fromCurrency: String,
        toCurrency: String,
        fromRateToCny: Double
    ): Long {
        if (fromCurrency.equals(toCurrency, ignoreCase = true)) return amountCents
        val cnyCents = amountCents * fromRateToCny
        val toRate = CurrencyRateManager.getEffectiveRateToCny(toCurrency)
        return (cnyCents / toRate).roundToLong()
    }
}
