package com.moji.ai.data.repository

import androidx.room.withTransaction
import com.moji.ai.data.backup.BackupAndCsvTransferEngine
import com.moji.ai.data.backup.CsvParsePreviewResult
import com.moji.ai.data.backup.MoJiCloudSyncClient
import com.moji.ai.data.local.AppDatabase
import com.moji.ai.data.local.FamilySharedTransactionEntity
import com.moji.ai.data.local.RecurringRuleEntity
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CategoryCatalog
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.CurrencyRateManager
import com.moji.ai.data.model.DedicatedFundKind
import com.moji.ai.data.model.DedicatedFundManager
import com.moji.ai.data.model.DedicatedFundPlanConfig
import com.moji.ai.data.model.PayslipRecognitionDraft
import com.moji.ai.data.model.RecurrenceFrequency
import com.moji.ai.data.model.TagCatalog
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

data class BatchImportCommitSummary(
    val createdWalletCount: Int,
    val updatedWalletCount: Int,
    val importedTxCount: Int,
    val skippedDuplicateTxCount: Int
)

data class CompositeMedicalRecordResult(
    val mainTransaction: TransactionEntity,
    val childPersonalTransaction: TransactionEntity? = null
)

data class CompositeMedicalFusionResult(
    val updatedMainTransaction: TransactionEntity,
    val createdChildTransaction: TransactionEntity? = null,
    val enrichedFields: List<String> = emptyList(),
    val wasModified: Boolean = false
)

data class PayslipCommitRequest(
    val ledgerName: String = "日常账本",
    val draft: PayslipRecognitionDraft,
    val enableNetPay: Boolean = true,
    val payrollWalletId: Long? = null,
    val enableMedicalDeposit: Boolean = true,
    val medicalWalletId: Long? = null,
    val autoCreateMedicalWallet: Boolean = true,
    val medicalDepositCents: Long = draft.medicalPersonalAccountDepositCents,
    val enableProvidentDeposit: Boolean = true,
    val providentWalletId: Long? = null,
    val autoCreateProvidentWallet: Boolean = true,
    val providentDepositCents: Long = draft.bilateralProvidentDepositCents,
    val customNote: String = "",
    val captureSource: String = "PAYSLIP_IMPORT",
    val forceDuplicate: Boolean = false,
    val nowMillis: Long = System.currentTimeMillis()
)

sealed interface PayslipCommitOutcome {
    data class Success(
        val mainTransaction: TransactionEntity?,
        val medicalChildTransaction: TransactionEntity?,
        val providentChildTransaction: TransactionEntity?,
        val createdMedicalWallet: WalletEntity?,
        val createdProvidentWallet: WalletEntity?,
        val orderKey: String
    ) : PayslipCommitOutcome {
        val primaryUndoTransactionId: Long?
            get() = mainTransaction?.id ?: medicalChildTransaction?.id ?: providentChildTransaction?.id
        val recordedCount: Int
            get() = listOfNotNull(mainTransaction, medicalChildTransaction, providentChildTransaction).size
    }

    data class DuplicateDetected(
        val orderKey: String,
        val existingTransaction: TransactionEntity
    ) : PayslipCommitOutcome

    data class ValidationError(
        val message: String
    ) : PayslipCommitOutcome
}

internal interface LedgerTransactionStore {
    suspend fun getWalletById(id: Long): WalletEntity?
    suspend fun getAllWallets(): List<WalletEntity>
    suspend fun insertWallet(wallet: WalletEntity): Long =
        throw UnsupportedOperationException("insertWallet is not supported in this store")
    suspend fun updateWallet(wallet: WalletEntity)
    suspend fun getTransactionById(id: Long): TransactionEntity?
    suspend fun getAllTransactions(): List<TransactionEntity>
    suspend fun findTransactionByOrderKey(orderKey: String): TransactionEntity?
    suspend fun getSplitChildrenOfExpense(parentId: Long): List<TransactionEntity>
    suspend fun getRecentRefundableExpenses(limit: Int): List<TransactionEntity>
    suspend fun insertTransaction(tx: TransactionEntity): Long
    suspend fun updateTransaction(tx: TransactionEntity)
    suspend fun deleteTransaction(tx: TransactionEntity)
}

class LedgerRepository(private val db: AppDatabase) {

    private val recurringMutex = Mutex()

    private val roomStore: LedgerTransactionStore = object : LedgerTransactionStore {
        override suspend fun getWalletById(id: Long): WalletEntity? = db.walletDao().getWalletById(id)
        override suspend fun getAllWallets(): List<WalletEntity> = db.walletDao().getAllWallets()
        override suspend fun insertWallet(wallet: WalletEntity): Long = db.walletDao().insertWallet(wallet)
        override suspend fun updateWallet(wallet: WalletEntity) = db.walletDao().updateWallet(wallet)
        override suspend fun getTransactionById(id: Long): TransactionEntity? = db.transactionDao().getTransactionById(id)
        override suspend fun getAllTransactions(): List<TransactionEntity> = db.transactionDao().getAllTransactions()
        override suspend fun findTransactionByOrderKey(orderKey: String): TransactionEntity? =
            if (orderKey.isBlank()) null else db.transactionDao().findByOrderKey(orderKey)
        override suspend fun getSplitChildrenOfExpense(parentId: Long): List<TransactionEntity> =
            db.transactionDao().getSplitChildrenOfExpense(parentId)
        override suspend fun getRecentRefundableExpenses(limit: Int): List<TransactionEntity> =
            db.transactionDao().getRecentRefundableExpenses(limit)
        override suspend fun insertTransaction(tx: TransactionEntity): Long = db.transactionDao().insertTransaction(tx)
        override suspend fun updateTransaction(tx: TransactionEntity) = db.transactionDao().updateTransaction(tx)
        override suspend fun deleteTransaction(tx: TransactionEntity) = db.transactionDao().deleteTransaction(tx)
    }

    val walletsFlow = db.walletDao().observeAllWallets()
    val transactionsFlow = db.transactionDao().observeAllTransactions()
    val recurringRulesFlow = db.recurringRuleDao().observeAllRules()
    val familySharedTransactionsFlow = db.familySharedTransactionDao().observeActiveSharedTransactions()

    suspend fun getAllWallets(): List<WalletEntity> = db.walletDao().getAllWallets()

    suspend fun getAllTransactions(): List<TransactionEntity> = db.transactionDao().getAllTransactions()

    suspend fun getAllFamilySharedTransactions(): List<FamilySharedTransactionEntity> =
        db.familySharedTransactionDao().getAllSharedTransactions()

    suspend fun upsertFamilySharedTransactions(items: List<FamilySharedTransactionEntity>) {
        if (items.isEmpty()) return
        db.familySharedTransactionDao().upsertAll(items)
    }

    suspend fun deleteFamilySharedTransactionsByOwner(ownerUserId: String) {
        if (ownerUserId.isBlank()) return
        db.familySharedTransactionDao().deleteByOwnerUserId(ownerUserId)
    }

    suspend fun clearAllFamilySharedTransactions() {
        db.familySharedTransactionDao().deleteAll()
    }

    suspend fun setTransactionSyncToFamily(
        txId: Long,
        syncToFamily: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): TransactionEntity? = db.withTransaction {
        if (txId <= 0L) return@withTransaction null
        val txDao = db.transactionDao()
        val existing = txDao.getTransactionById(txId) ?: return@withTransaction null
        if (existing.deletedAtMillis != null) return@withTransaction null
        val effectiveFlag = syncToFamily && existing.isEligibleForFamilySync()
        if (existing.syncToFamily == effectiveFlag) return@withTransaction existing
        txDao.updateTransactionSyncToFamilyById(txId, effectiveFlag, nowMillis)
        txDao.getTransactionById(txId)
    }

    suspend fun enableFamilySyncForCurrentMonthEligibleTransactions(
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: java.util.TimeZone = java.util.TimeZone.getDefault()
    ): Int = db.withTransaction {
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH)
        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val startOfMonth = Calendar.getInstance(timeZone).apply {
            clear()
            set(year, month, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val endOfMonth = Calendar.getInstance(timeZone).apply {
            clear()
            set(year, month, maxDay, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        val txDao = db.transactionDao()
        var updatedCount = 0
        for (tx in txDao.getAllTransactions()) {
            if (tx.timestamp !in startOfMonth..endOfMonth) continue
            if (tx.syncToFamily) continue
            if (!tx.isEligibleForFamilySync()) continue
            txDao.updateTransactionSyncToFamilyById(tx.id, true, nowMillis)
            updatedCount++
        }
        updatedCount
    }

    suspend fun getAllNonEmptyTagStrings(): List<String> = db.transactionDao().getAllNonEmptyTagStrings()

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

    suspend fun getAllCaptureEvidences(): List<com.moji.ai.data.local.CaptureEvidenceEntity> =
        db.captureEvidenceDao().getAllEvidences()

    suspend fun getEvidencesForTransaction(txId: Long): List<com.moji.ai.data.local.CaptureEvidenceEntity> =
        db.captureEvidenceDao().getEvidencesForTransaction(txId)

    /**
     * 检查本地自然日（00:00:00.000 ~ 23:59:59.999）内是否存在未删除的非系统平账流水，
     * 用于定时记账提醒的「今日已记账则免打扰」静默跳过判断。
     */
    suspend fun hasRecordedTransactionToday(
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: java.util.TimeZone = java.util.TimeZone.getDefault()
    ): Boolean {
        val startCal = java.util.Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val startOfDay = startCal.timeInMillis
        val endCal = (startCal.clone() as java.util.Calendar).apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        val endOfDay = endCal.timeInMillis - 1L
        return db.transactionDao().countNonAnchorTransactionsInRange(startOfDay, endOfDay) > 0
    }

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
        // 资金账户余额不允许平账为负数；信用账户允许负数以精确表达溢缴款（多还款）状态
        val sanitizedActual = if (wallet.type.isLiability) actualBalanceCents else actualBalanceCents.coerceAtLeast(0L)
        val currentBook = wallet.balanceCents
        val diff = sanitizedActual - currentBook
        if (diff == 0L) return@withTransaction null

        if (!recordAdjustmentTx) {
            val now = System.currentTimeMillis()
            walletDao.updateWallet(
                wallet.copy(
                    balanceCents = sanitizedActual,
                    updatedAtMillis = now,
                    syncDirty = true
                )
            )
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
        val remainingExistingCounts = mutableMapOf<String, Int>()
        for (ex in existing) {
            val k = buildExternalImportDedupKey(ex)
            remainingExistingCounts[k] = (remainingExistingCounts[k] ?: 0) + 1
        }
        val importedOrderKeys = mutableSetOf<String>()

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

            val explicitOrderId = boundTx.orderKey.ifBlank { boundTx.transactionNo.ifBlank { boundTx.merchantOrderNo } }.trim()
            if (explicitOrderId.isNotEmpty() && !importedOrderKeys.add(explicitOrderId)) {
                skippedDuplicates++
                continue
            }

            val dedupKey = buildExternalImportDedupKey(boundTx)
            val existingRemain = remainingExistingCounts[dedupKey] ?: 0
            if (existingRemain > 0) {
                remainingExistingCounts[dedupKey] = existingRemain - 1
                skippedDuplicates++
                continue
            }

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
        val remainingExistingCounts = mutableMapOf<String, Int>()
        for (ex in existing) {
            val k = buildExternalImportDedupKey(ex)
            remainingExistingCounts[k] = (remainingExistingCounts[k] ?: 0) + 1
        }
        val importedOrderKeys = mutableSetOf<String>()

        var importedCount = 0
        for (item in candidates) {
            val explicitOrderId = item.orderKey.ifBlank { item.transactionNo.ifBlank { item.merchantOrderNo } }.trim()
            if (explicitOrderId.isNotEmpty() && !importedOrderKeys.add(explicitOrderId)) continue

            val dedupKey = buildExternalImportDedupKey(item)
            val existingRemain = remainingExistingCounts[dedupKey] ?: 0
            if (existingRemain > 0) {
                remainingExistingCounts[dedupKey] = existingRemain - 1
                continue
            }
            if (applyToWalletBalance) {
                recordTransaction(item.copy(id = 0L))
            } else {
                db.transactionDao().insertTransaction(item.copy(id = 0L))
            }
            importedCount++
        }
        importedCount
    }

    private fun buildExternalImportDedupKey(tx: TransactionEntity): String {
        val explicitOrderId = tx.orderKey.ifBlank { tx.transactionNo.ifBlank { tx.merchantOrderNo } }.trim()
        if (explicitOrderId.isNotEmpty()) {
            return "ORD|$explicitOrderId|${tx.type.name}|${tx.amountCents}"
        }
        return buildString {
            append(tx.timestamp / 1_000L).append('|')
            append(tx.type.name).append('|')
            append(tx.currency.uppercase()).append('|')
            append(tx.amountCents).append('|')
            append(tx.sourceWalletId ?: 0L).append('|')
            append(tx.targetWalletId ?: 0L).append('|')
            append(tx.category.trim()).append('|')
            append(tx.merchant.trim()).append('|')
            append(tx.note.trim().take(24)).append('|')
            append(tx.ledgerName.trim())
        }
    }

    /**
     * 全量备份恢复（原子化清空旧表状态并恢复快照中的钱包、交易与周期规则，避免残留孤儿流水或旧规则）
     */
    suspend fun restoreFullSnapshot(
        wallets: List<WalletEntity>,
        transactions: List<TransactionEntity>,
        recurringRules: List<RecurringRuleEntity>
    ): Int = db.withTransaction {
        db.captureEvidenceDao().deleteAllEvidences()
        db.transactionDao().deleteAllTransactions()
        db.recurringRuleDao().deleteAllRules()
        db.walletDao().deleteAllWallets()
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
        recordSingleTransactionOnStore(roomStore, tx)
    }

    /**
     * 组合支付（个人现金自费 + 医保个账 + 可选医保统筹）原子双扣入账：
     * - 当账本存在个人医保账户且 `effectivePersonal > 0 && mainTx.amountCents > 0` 时，在同一 Room 事务内原子写入「自费主单」与「`#MED_PERSONAL` 医保个账子单」，
     *   严格保持 `主单实付 + 子单实付 + 主单discountCents == 医疗原价` 守恒；
     * - 当账本未配置个人医保账户时，降级为单笔入账并在结构化备注与 `discountCents` 中完整保留统筹与个账金额。
     */
    suspend fun recordCompositeMedicalTransaction(
        mainTx: TransactionEntity,
        poolSubsidyCents: Long,
        personalAccountCents: Long,
        unspecifiedMedicalCents: Long = 0L,
        commercialDiscountCents: Long = 0L,
        personalMedicalWalletId: Long? = null
    ): CompositeMedicalRecordResult = db.withTransaction {
        recordCompositeMedicalOnStore(
            store = roomStore,
            mainTx = mainTx,
            poolSubsidyCents = poolSubsidyCents,
            personalAccountCents = personalAccountCents,
            unspecifiedMedicalCents = unspecifiedMedicalCents,
            commercialDiscountCents = commercialDiscountCents,
            personalMedicalWalletId = personalMedicalWalletId
        )
    }

    /**
     * 30 秒跨通道多帧证据融合（通知栏先到自费扣款 -> 账单详情页后到医保统筹+个账明细）：
     * 在同一 Room 事务内幂等更新主单（剥离个账后的 `discountCents`、结构化备注与 `医保,组合支付` 标签），
     * 并在账本存在个人医保账户且尚未创建 `#MED_PERSONAL` 子单时原子补建医保个账子单并扣减个人医保账户余额。
     */
    suspend fun enrichCompositeMedicalOnExistingTransaction(
        existingMainId: Long,
        enrichedCandidateMain: TransactionEntity,
        baseEnrichedFields: List<String>,
        poolSubsidyCents: Long,
        personalAccountCents: Long,
        unspecifiedMedicalCents: Long = 0L,
        commercialDiscountCents: Long = 0L
    ): CompositeMedicalFusionResult = db.withTransaction {
        enrichCompositeMedicalOnStore(
            store = roomStore,
            existingMainId = existingMainId,
            enrichedCandidateMain = enrichedCandidateMain,
            baseEnrichedFields = baseEnrichedFields,
            poolSubsidyCents = poolSubsidyCents,
            personalAccountCents = personalAccountCents,
            unspecifiedMedicalCents = unspecifiedMedicalCents,
            commercialDiscountCents = commercialDiscountCents
        )
    }

    /**
     * 原子附加无障碍账单截图（仅在目标流水仍存在且 screenshotPath 为空时单列更新），
     * 杜绝整行覆写回退期间发生的 30s 多帧详情融合或通知栏改分类/标报销改动。
     */
    suspend fun attachBillScreenshotIfNeeded(
        transactionId: Long,
        screenshotPath: String,
        screenshotSha256: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (transactionId <= 0L || screenshotPath.isBlank()) return false
        return db.transactionDao().attachScreenshotIfBlank(
            id = transactionId,
            screenshotPath = screenshotPath,
            screenshotSha256 = screenshotSha256,
            nowMillis = nowMillis
        ) > 0
    }

    /**
     * 编辑已有账单：若仅修改分类/备注/标签等非金额账户元数据则跳过余额回滚重算，避免汇率波动造成余额漂移；
     * 若金额、币种、账户、退款关联或已报销状态发生变更，则先按历史变动快照回滚旧影响再应用新影响；
     * 同时保护已部分退款支出账单编辑后折算金额不得小于已退金额 refundedAmountCents。
     */
    suspend fun updateExistingTransaction(updatedTx: TransactionEntity): Boolean = db.withTransaction {
        updateExistingTransactionOnStore(roomStore, updatedTx)
    }

    /**
     * 对指定消费账单发起全额或部分退款（refundAmountCents 采用原账单币种分值，按原单 exchangeRateToCny 精确折算，全额退完无尾差）
     */
    suspend fun refundExpense(
        expenseId: Long,
        refundAmountCents: Long,
        receiveWalletId: Long?,
        refundNote: String = ""
    ): Long? = db.withTransaction {
        refundExpenseOnStore(
            store = roomStore,
            expenseId = expenseId,
            refundAmountCents = refundAmountCents,
            receiveWalletId = receiveWalletId,
            refundNote = refundNote
        )
    }

    /**
     * 对待报销账单完成报销入账（持久化实际入账账户、核销变动快照与核销时间戳，确保后续删除或撤销报销可精确回滚）
     */
    suspend fun settleReimbursement(
        expenseId: Long,
        receiveWalletId: Long?
    ): Boolean = db.withTransaction {
        settleReimbursementOnStore(roomStore, expenseId, receiveWalletId)
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
        val now = System.currentTimeMillis()
        var settledCount = 0

        for (id in expenseIds.distinct()) {
            if (settleReimbursementOnStore(roomStore, id, receiveWalletId, now)) {
                settledCount++
            }
        }
        settledCount
    }

    /**
     * 单笔账单多分类拆分（Split Transaction，对标 MyExpenses 父子分拆账单）：
     * 在保持原付款钱包总扣款金额、累计已退金额与优惠金额守恒的前提下，将一笔消费拆分为两笔独立分类的子消费记录。
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
        if (orig.type != TransactionType.EXPENSE ||
            orig.reimbursed ||
            orig.amountCents < 2L ||
            orig.refundedAmountCents >= orig.amountCnyCents ||
            DedicatedFundManager.isCompositeMedicalPersonalChild(orig) ||
            DedicatedFundManager.isCompositeMedicalMain(orig) ||
            DedicatedFundManager.parseStructuredMedicalNote(orig.note).hasExplicitBreakdown
        ) {
            return@withTransaction false
        }

        val partACents = amountACents
        if (partACents <= 0L || partACents >= orig.amountCents) return@withTransaction false
        val partBCents = orig.amountCents - partACents

        val partACnyCents = if (orig.amountCnyCents >= 2L) {
            (partACents * orig.exchangeRateToCny).roundToLong().coerceIn(1L, orig.amountCnyCents - 1L)
        } else {
            (partACents * orig.exchangeRateToCny).roundToLong().coerceAtLeast(0L)
        }
        val partBCnyCents = (orig.amountCnyCents - partACnyCents).coerceAtLeast(0L)

        val refundA = orig.refundedAmountCents.coerceAtMost(partACnyCents)
        val refundB = (orig.refundedAmountCents - refundA).coerceIn(0L, partBCnyCents)

        val discountA = if (orig.discountCents > 0L) {
            ((orig.discountCents * partACents.toDouble()) / orig.amountCents.toDouble())
                .roundToLong()
                .coerceIn(0L, orig.discountCents)
        } else {
            0L
        }
        val discountB = (orig.discountCents - discountA).coerceAtLeast(0L)

        val totalOrigOut = (orig.amountCents + orig.feeCents).coerceAtLeast(1L)
        val partAOut = partACents + orig.feeCents
        val splitSourceDeltaA = orig.sourceWalletDeltaCents?.let { totalDelta ->
            ((totalDelta.toDouble() * partAOut.toDouble()) / totalOrigOut.toDouble())
                .roundToLong()
                .coerceIn(0L, totalDelta)
        }
        val splitSourceDeltaB = orig.sourceWalletDeltaCents?.let { totalDelta ->
            (totalDelta - (splitSourceDeltaA ?: 0L)).coerceAtLeast(0L)
        }
        val now = System.currentTimeMillis()

        val updatedOrig = orig.copy(
            category = categoryA.ifBlank { orig.category },
            amountCents = partACents,
            amountCnyCents = partACnyCents,
            discountCents = discountA,
            refundedAmountCents = refundA,
            sourceWalletDeltaCents = splitSourceDeltaA,
            note = if (orig.note.contains("[拆分")) orig.note else "${orig.note} [拆分1/2]".trim(),
            updatedAtMillis = now,
            syncRevision = orig.syncRevision + 1,
            syncDirty = true
        )
        txDao.updateTransaction(updatedOrig)

        val childB = orig.copy(
            id = 0L,
            syncUuid = UUID.randomUUID().toString(),
            category = categoryB.ifBlank { "日用" },
            amountCents = partBCents,
            amountCnyCents = partBCnyCents,
            discountCents = discountB,
            feeCents = 0L,
            refundedAmountCents = refundB,
            sourceWalletDeltaCents = splitSourceDeltaB,
            targetWalletDeltaCents = null,
            reimbursedAtMillis = null,
            relatedTransactionId = orig.id,
            orderKey = "",
            transactionNo = "",
            merchantOrderNo = "",
            dedupFingerprint = "",
            timestamp = orig.timestamp - 1L,
            note = noteB.ifBlank { "${orig.note} [拆分2/2]".trim() },
            updatedAtMillis = now,
            syncRevision = 1L,
            syncDirty = true
        )
        txDao.insertTransaction(childB)
        true
    }

    suspend fun undoAndDeleteTransaction(transactionId: Long): Boolean {
        var screenshotsToDelete: List<String> = emptyList()
        val deleted = db.withTransaction {
            val outcome = undoAndDeleteTransactionOnStore(roomStore, transactionId)
            screenshotsToDelete = outcome.second
            outcome.first
        }
        if (deleted) {
            for (path in screenshotsToDelete) {
                if (path.isNotBlank()) {
                    com.moji.ai.service.BillScreenshotStorage.deleteIfExists(path)
                }
            }
        }
        return deleted
    }

    suspend fun clearAllTransactionsAndEvidences() = db.withTransaction {
        db.transactionDao().deleteAllTransactions()
        db.captureEvidenceDao().deleteAllEvidences()
        val walletDao = db.walletDao()
        val currentWallets = walletDao.getAllWallets()
        val now = System.currentTimeMillis()
        for (w in currentWallets) {
            walletDao.updateWallet(
                w.copy(
                    balanceCents = 0L,
                    updatedAtMillis = now,
                    syncDirty = true
                )
            )
        }
    }

    suspend fun saveWallet(wallet: WalletEntity): Long = db.walletDao().insertWallet(wallet)

    suspend fun updateWallet(wallet: WalletEntity) = db.walletDao().updateWallet(wallet)

    suspend fun deleteWallet(wallet: WalletEntity) = db.withTransaction {
        val now = System.currentTimeMillis()
        val ruleDao = db.recurringRuleDao()
        val relatedRules = ruleDao.getAllRules().filter {
            it.walletId == wallet.id || it.targetWalletId == wallet.id
        }
        for (rule in relatedRules) {
            val clearedTarget = if (rule.targetWalletId == wallet.id) null else rule.targetWalletId
            val shouldDisable = rule.walletId == wallet.id ||
                (rule.targetWalletId == wallet.id &&
                    (rule.type == TransactionType.TRANSFER || rule.type == TransactionType.REPAYMENT))
            ruleDao.updateRule(
                rule.copy(
                    enabled = if (shouldDisable) false else rule.enabled,
                    targetWalletId = clearedTarget,
                    updatedAtMillis = now,
                    syncDirty = true
                )
            )
        }
        db.walletDao().deleteWallet(wallet)
        val deletedKind = DedicatedFundManager.resolveDedicatedFundKind(wallet)
        val hasRemainingSameKind = deletedKind != null && db.walletDao().getAllWallets().any {
            it.id != wallet.id && DedicatedFundManager.resolveDedicatedFundKind(it) == deletedKind
        }
        DedicatedFundManager.clearReferencesForDeletedWallet(
            deletedWalletId = wallet.id,
            deletedKind = deletedKind,
            hasRemainingWalletOfSameKind = hasRemainingSameKind
        )
    }

    suspend fun backfillTransactionScreenshotHashes(updates: List<Pair<Long, String>>) {
        if (updates.isEmpty()) return
        db.withTransaction {
            val txDao = db.transactionDao()
            for ((txId, sha) in updates) {
                if (txId > 0L && sha.length == 64) {
                    txDao.updateScreenshotSha256ById(txId, sha)
                }
            }
        }
    }

    /**
     * 清理早期旧版内置演示卡（id=6 尾号 8848）与演示周期规则，重置为标准通用零余额初始账户
     */
    suspend fun syncICostAllAccounts() = db.withTransaction {
        val walletDao = db.walletDao()
        val txDao = db.transactionDao()
        val ruleDao = db.recurringRuleDao()

        val oldWallets = walletDao.getAllWallets()
        // 仅在检测到旧版内置演示卡（id=6 且名称为招商银行储蓄卡、尾号 8848）时执行一次性迁移，绝不误伤用户真实尾号 8848 银行卡
        val hasLegacyDemoCard = oldWallets.any {
            it.id == 6L && it.cardLast4 == "8848" && it.name.contains("招商银行储蓄卡")
        }
        if (oldWallets.isNotEmpty() && !hasLegacyDemoCard) {
            return@withTransaction
        }
        val oldAlipayId = oldWallets.firstOrNull { it.brandCode == "ALIPAY" || it.name.contains("账户余额") || it.name.contains("支付宝") }?.id ?: 4L
        val oldWechatId = oldWallets.firstOrNull { it.brandCode == "WECHAT" || it.name.contains("微信零钱") }?.id ?: 5L

        walletDao.deleteAllWallets()
        walletDao.insertAll(AppDatabase.defaultWallets())

        val now = System.currentTimeMillis()
        val allTxs = txDao.getAllTransactions()
        for (tx in allTxs) {
            val newSourceId = when (tx.sourceWalletId) {
                oldAlipayId -> 3L
                oldWechatId -> 2L
                6L -> 4L
                else -> tx.sourceWalletId
            }
            val newTargetId = when (tx.targetWalletId) {
                oldAlipayId -> 3L
                oldWechatId -> 2L
                6L -> 4L
                else -> tx.targetWalletId
            }
            if (newSourceId != tx.sourceWalletId || newTargetId != tx.targetWalletId) {
                txDao.updateTransaction(
                    tx.copy(
                        sourceWalletId = newSourceId,
                        targetWalletId = newTargetId,
                        updatedAtMillis = now,
                        syncDirty = true
                    )
                )
            }
        }

        // 清理旧版绑定在演示卡 6L 上的「每月房租」演示周期规则
        for (rule in ruleDao.getAllRules()) {
            if (rule.title == "每月房租" && rule.walletId == 6L) {
                ruleDao.deleteRule(rule)
            }
        }
    }

    suspend fun saveRecurringRule(rule: RecurringRuleEntity): Long = db.recurringRuleDao().insertRule(rule)

    suspend fun deleteRecurringRule(rule: RecurringRuleEntity) = db.recurringRuleDao().deleteRule(rule)

    /**
     * 一键建档或更新「个人医保账户 / 住房公积金」专项保障账户，并按开关原子同步每月固定入账周期规则。
     */
    suspend fun setupOrUpdateDedicatedFund(
        kind: DedicatedFundKind,
        walletDraft: WalletEntity,
        planConfig: DedicatedFundPlanConfig,
        nowMillis: Long = System.currentTimeMillis()
    ): Pair<WalletEntity, DedicatedFundPlanConfig> = db.withTransaction {
        val walletDao = db.walletDao()
        val ruleDao = db.recurringRuleDao()

        val existingWalletRow = if (walletDraft.id > 0L) walletDao.getWalletById(walletDraft.id) else null
        val mergedAliases = DedicatedFundManager.mergeDefaultAliases(kind.brandCode, walletDraft.aliases)
        val normalizedWallet = walletDraft.copy(
            syncUuid = existingWalletRow?.syncUuid ?: walletDraft.syncUuid,
            brandCode = kind.brandCode,
            subTypeLabel = walletDraft.subTypeLabel.ifBlank { kind.subTypeLabel },
            aliases = mergedAliases,
            balanceCents = walletDraft.balanceCents.coerceAtLeast(0L),
            updatedAtMillis = nowMillis,
            syncDirty = true
        )
        val savedWallet: WalletEntity = if (normalizedWallet.id <= 0L) {
            val insertedId = walletDao.insertWallet(normalizedWallet.copy(id = 0L))
            normalizedWallet.copy(id = insertedId)
        } else {
            walletDao.updateWallet(normalizedWallet)
            normalizedWallet
        }

        val sanitizedPlan = planConfig.sanitized()
        val allRules = ruleDao.getAllRules()
        val existingRule = sanitizedPlan.linkedRecurringRuleId?.let { rid ->
            allRules.firstOrNull { rule ->
                rule.id == rid &&
                    rule.type == TransactionType.INCOME &&
                    (rule.title == kind.recurringRuleTitle ||
                        rule.targetWalletId == savedWallet.id ||
                        rule.walletId == savedWallet.id)
            }
        } ?: allRules.firstOrNull { rule ->
            rule.type == TransactionType.INCOME &&
                (rule.targetWalletId == savedWallet.id || rule.walletId == savedWallet.id) &&
                rule.title == kind.recurringRuleTitle
        }

        val finalPlan: DedicatedFundPlanConfig
        if (sanitizedPlan.autoCreateMonthlyRule && sanitizedPlan.totalMonthlyDepositCents > 0L) {
            val nextTrigger = computeNextFutureMonthlyTriggerMillis(nowMillis, sanitizedPlan.depositDayOfMonth)
            val ruleId = if (existingRule != null) {
                val updatedRule = existingRule.copy(
                    title = kind.recurringRuleTitle,
                    type = TransactionType.INCOME,
                    amountCents = sanitizedPlan.totalMonthlyDepositCents,
                    currency = savedWallet.currency,
                    category = kind.defaultIncomeCategory,
                    walletId = savedWallet.id,
                    targetWalletId = savedWallet.id,
                    frequency = RecurrenceFrequency.MONTHLY,
                    dayOfMonthOrWeek = sanitizedPlan.depositDayOfMonth,
                    nextTriggerTimeMillis = nextTrigger,
                    enabled = true,
                    updatedAtMillis = nowMillis,
                    syncDirty = true
                )
                ruleDao.updateRule(updatedRule)
                existingRule.id
            } else {
                ruleDao.insertRule(
                    RecurringRuleEntity(
                        title = kind.recurringRuleTitle,
                        type = TransactionType.INCOME,
                        amountCents = sanitizedPlan.totalMonthlyDepositCents,
                        currency = savedWallet.currency,
                        category = kind.defaultIncomeCategory,
                        walletId = savedWallet.id,
                        targetWalletId = savedWallet.id,
                        frequency = RecurrenceFrequency.MONTHLY,
                        dayOfMonthOrWeek = sanitizedPlan.depositDayOfMonth,
                        nextTriggerTimeMillis = nextTrigger,
                        enabled = true,
                        updatedAtMillis = nowMillis,
                        syncDirty = true
                    )
                )
            }
            finalPlan = sanitizedPlan.copy(enabled = true, linkedRecurringRuleId = ruleId)
        } else {
            if (existingRule != null && existingRule.enabled) {
                ruleDao.updateRule(
                    existingRule.copy(
                        enabled = false,
                        updatedAtMillis = nowMillis,
                        syncDirty = true
                    )
                )
            }
            finalPlan = sanitizedPlan.copy(
                enabled = sanitizedPlan.totalMonthlyDepositCents > 0L || sanitizedPlan.linkedPayrollWalletId != null,
                linkedRecurringRuleId = existingRule?.id
            )
        }

        DedicatedFundManager.updatePlan(kind, finalPlan)
        savedWallet to finalPlan
    }

    /**
     * 工资条三路自动分账单事务原子入账（`AC-B3` / `AC-B4` / `AC-B5`）：
     * - 基于 `PAYSLIP:<yyyyMM>:<netCents>:<grossCents>` 拦截同月同金额重复导入；
     * - 当勾选医保/公积金支路而专项账户尚不存在时，按预设在同一事务内自动创建（`includeInNetWorth = false`）；
     * - 原子写入实发工资主单（`INCOME`，`#工资`）+ 关联 `#PAYSLIP_MED` 医保个账子单 + 关联 `#PAYSLIP_PROV` 双边公积金子单。
     */
    suspend fun recordPayslipTransactions(
        request: PayslipCommitRequest
    ): PayslipCommitOutcome = db.withTransaction {
        recordPayslipOnStore(roomStore, request)
    }

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

    suspend fun processDueRecurringRules(nowMillis: Long = System.currentTimeMillis()): List<TransactionEntity> =
        recurringMutex.withLock {
            db.withTransaction {
                val dueRules = db.recurringRuleDao().getDueRules(nowMillis)
                val walletsById = db.walletDao().getAllWallets().associateBy { it.id }
                val created = mutableListOf<TransactionEntity>()
                for (rule in dueRules) {
                    val rate = CurrencyRateManager.getEffectiveRateToCny(rule.currency)
                    val cnyCents = (rule.amountCents * rate).roundToLong()
                    val isIncomeRule = rule.type == TransactionType.INCOME
                    val resolvedTargetWalletId = if (isIncomeRule) {
                        rule.targetWalletId ?: rule.walletId
                    } else {
                        rule.targetWalletId
                    }
                    val resolvedSourceWalletId = if (isIncomeRule) null else rule.walletId
                    val dedicatedTag = resolvedTargetWalletId?.let { wid ->
                        walletsById[wid]?.let { w -> DedicatedFundManager.resolveDedicatedFundKind(w)?.defaultTag }
                    } ?: when {
                        rule.title.contains("公积金") -> "公积金"
                        rule.title.contains("医保") -> "医保"
                        else -> ""
                    }
                    val rawRecurringTx = TransactionEntity(
                        type = rule.type,
                        amountCents = rule.amountCents,
                        currency = rule.currency,
                        exchangeRateToCny = rate,
                        amountCnyCents = cnyCents,
                        category = rule.category,
                        merchant = rule.title,
                        note = "周期性账单自动入账 (${rule.frequency.displayName})",
                        tags = dedicatedTag,
                        sourceWalletId = resolvedSourceWalletId,
                        targetWalletId = resolvedTargetWalletId,
                        captureSource = "RECURRING"
                    )
                    val tx = if (dedicatedTag.isEmpty() &&
                        MoJiCloudSyncClient.isFamilyAutoShareActive() &&
                        rawRecurringTx.isEligibleForFamilySync()
                    ) {
                        rawRecurringTx.copy(syncToFamily = true)
                    } else {
                        rawRecurringTx
                    }
                    val id = recordSingleTransactionOnStore(roomStore, tx)
                    created.add(tx.copy(id = id))

                    val nextMillis = rule.frequency.computeNextTriggerMillis(
                        baseMillis = rule.nextTriggerTimeMillis,
                        nowMillis = nowMillis,
                        anchorDay = rule.dayOfMonthOrWeek
                    )
                    db.recurringRuleDao().updateRule(
                        rule.copy(
                            nextTriggerTimeMillis = nextMillis,
                            updatedAtMillis = nowMillis,
                            syncDirty = true
                        )
                    )
                }
                created
            }
        }

    private suspend fun applyWalletImpact(tx: TransactionEntity): TransactionEntity =
        applyWalletImpactOnStore(roomStore, tx)

    private suspend fun revertWalletImpact(tx: TransactionEntity) =
        revertWalletImpactOnStore(roomStore, tx)

    private fun convertCentsBetweenCurrencies(
        amountCents: Long,
        fromCurrency: String,
        toCurrency: String,
        fromRateToCny: Double
    ): Long = convertCentsStatic(amountCents, fromCurrency, toCurrency, fromRateToCny)

    companion object {

        /**
         * 计算支出账单当前剩余可退的【原币种】分值上限：
         * - 未发生退款时直接返回原单 `amountCents`；
         * - 本位币剩余可退 `<= 0` 时返回 `0L`；
         * - 外币部分退款后按原单锁定的历史汇率 `exchangeRateToCny` 还原剩余原币种分值。
         */
        fun computeRemainingRefundableOrigCents(expense: TransactionEntity): Long {
            if (expense.type != TransactionType.EXPENSE) return 0L
            val remainingCnyCents = (expense.amountCnyCents - expense.refundedAmountCents).coerceAtLeast(0L)
            if (remainingCnyCents <= 0L) return 0L
            if (expense.refundedAmountCents <= 0L) return expense.amountCents.coerceAtLeast(0L)
            val isForeign = !expense.currency.equals("CNY", ignoreCase = true) && expense.exchangeRateToCny > 0.0
            if (!isForeign) return remainingCnyCents
            return (remainingCnyCents.toDouble() / expense.exchangeRateToCny)
                .roundToLong()
                .coerceIn(1L, expense.amountCents)
        }

        internal fun computeNextFutureMonthlyTriggerMillis(
            nowMillis: Long,
            depositDayOfMonth: Int
        ): Long {
            val cleanDay = depositDayOfMonth.coerceIn(1, 28)
            val cal = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val currentDay = cal.get(Calendar.DAY_OF_MONTH)
            if (currentDay >= cleanDay) {
                cal.add(Calendar.MONTH, 1)
            }
            cal.set(Calendar.DAY_OF_MONTH, cleanDay)
            return cal.timeInMillis
        }

        internal suspend fun recordSingleTransactionOnStore(
            store: LedgerTransactionStore,
            tx: TransactionEntity,
            nowMillis: Long = System.currentTimeMillis()
        ): Long {
            val normalizedReimb = if (!tx.reimbursable && tx.reimbursed) {
                tx.copy(reimbursed = false, reimbursedAtMillis = null)
            } else {
                tx
            }
            val normalized = if (normalizedReimb.syncToFamily && !normalizedReimb.isEligibleForFamilySync()) {
                normalizedReimb.copy(syncToFamily = false)
            } else {
                normalizedReimb
            }
            val finalTx = applyWalletImpactOnStore(store, normalized, nowMillis)
            val id = store.insertTransaction(finalTx)
            if (!finalTx.isSystemBalanceAnchor() && finalTx.tags.isNotBlank()) {
                TagCatalog.syncDiscoveredTagsFromHistory(listOf(finalTx.tags))
            }
            return id
        }

        internal suspend fun updateExistingTransactionOnStore(
            store: LedgerTransactionStore,
            updatedTx: TransactionEntity,
            nowMillis: Long = System.currentTimeMillis()
        ): Boolean {
            val oldTx = store.getTransactionById(updatedTx.id) ?: return false
            val normalizedReimb = if (!updatedTx.reimbursable && updatedTx.reimbursed) {
                updatedTx.copy(reimbursed = false, reimbursedAtMillis = null)
            } else {
                updatedTx
            }
            val normalizedBase = if (normalizedReimb.syncToFamily && !normalizedReimb.isEligibleForFamilySync()) {
                normalizedReimb.copy(syncToFamily = false)
            } else {
                normalizedReimb
            }

            // AC-1 ③：拦截已发生退款的支出账单被改为其他类型，或编辑后折算本位币金额小于累计已退金额 refundedAmountCents
            val normalizedTx = if (oldTx.type == TransactionType.EXPENSE && oldTx.refundedAmountCents > 0L) {
                if (normalizedBase.type != TransactionType.EXPENSE) return false
                val effectiveRefunded = maxOf(oldTx.refundedAmountCents, normalizedBase.refundedAmountCents)
                if (normalizedBase.amountCnyCents < effectiveRefunded) return false
                normalizedBase.copy(refundedAmountCents = effectiveRefunded)
            } else {
                if (normalizedBase.type == TransactionType.EXPENSE &&
                    normalizedBase.refundedAmountCents > 0L &&
                    normalizedBase.amountCnyCents < normalizedBase.refundedAmountCents
                ) {
                    return false
                }
                normalizedBase
            }

            val applied = if (hasSameFinancialImpactStatic(oldTx, normalizedTx)) {
                normalizedTx.copy(
                    sourceWalletDeltaCents = oldTx.sourceWalletDeltaCents,
                    targetWalletDeltaCents = oldTx.targetWalletDeltaCents,
                    reimbursedAtMillis = if (normalizedTx.reimbursed) {
                        normalizedTx.reimbursedAtMillis ?: oldTx.reimbursedAtMillis ?: nowMillis
                    } else {
                        null
                    }
                )
            } else {
                revertWalletImpactOnStore(store, oldTx, nowMillis)
                val prepared = normalizedTx.copy(
                    reimbursedAtMillis = if (normalizedTx.reimbursed) {
                        if (oldTx.reimbursed) {
                            oldTx.reimbursedAtMillis ?: normalizedTx.reimbursedAtMillis ?: nowMillis
                        } else {
                            normalizedTx.reimbursedAtMillis ?: nowMillis
                        }
                    } else {
                        null
                    }
                )
                applyWalletImpactOnStore(store, prepared, nowMillis)
            }
            store.updateTransaction(applied)
            if (!applied.isSystemBalanceAnchor() && applied.tags.isNotBlank()) {
                TagCatalog.syncDiscoveredTagsFromHistory(listOf(applied.tags))
            }
            return true
        }

        internal suspend fun refundExpenseOnStore(
            store: LedgerTransactionStore,
            expenseId: Long,
            refundAmountCents: Long,
            receiveWalletId: Long?,
            refundNote: String = "",
            nowMillis: Long = System.currentTimeMillis()
        ): Long? {
            val expense = store.getTransactionById(expenseId) ?: return null
            if (expense.type != TransactionType.EXPENSE) return null

            val maxRefundableCny = (expense.amountCnyCents - expense.refundedAmountCents).coerceAtLeast(0L)
            val maxRefundableOrig = computeRemainingRefundableOrigCents(expense)
            if (maxRefundableCny <= 0L || maxRefundableOrig <= 0L) return null

            val actualRefundOrigCents = refundAmountCents.coerceAtMost(maxRefundableOrig)
            if (actualRefundOrigCents <= 0L) return null

            val isForeign = !expense.currency.equals("CNY", ignoreCase = true) && expense.exchangeRateToCny > 0.0
            val refundCurrency = if (isForeign) expense.currency else "CNY"
            val refundRate = if (isForeign) expense.exchangeRateToCny else 1.0
            val actualRefundCnyCents = when {
                !isForeign -> actualRefundOrigCents.coerceAtMost(maxRefundableCny)
                actualRefundOrigCents >= maxRefundableOrig -> maxRefundableCny
                else -> (actualRefundOrigCents * refundRate).roundToLong().coerceIn(1L, maxRefundableCny)
            }

            val targetWalletId = receiveWalletId ?: expense.sourceWalletId
            val refundTx = TransactionEntity(
                ledgerName = expense.ledgerName,
                type = TransactionType.REFUND_OFFSET,
                amountCents = actualRefundOrigCents,
                currency = refundCurrency,
                exchangeRateToCny = refundRate,
                amountCnyCents = actualRefundCnyCents,
                category = "退款入账",
                merchant = if (expense.merchant.isNotBlank()) "${expense.category} · ${expense.merchant}" else expense.category,
                note = refundNote.ifBlank { "关联原账单退款冲销" },
                sourceWalletId = null,
                targetWalletId = targetWalletId,
                relatedTransactionId = expense.id,
                captureSource = "MANUAL",
                updatedAtMillis = nowMillis
            )
            return recordSingleTransactionOnStore(store, refundTx, nowMillis)
        }

        internal suspend fun settleReimbursementOnStore(
            store: LedgerTransactionStore,
            expenseId: Long,
            receiveWalletId: Long?,
            nowMillis: Long = System.currentTimeMillis()
        ): Boolean {
            val expense = store.getTransactionById(expenseId) ?: return false
            if (expense.type != TransactionType.EXPENSE || !expense.reimbursable || expense.reimbursed) {
                return false
            }

            val targetId = receiveWalletId ?: expense.targetWalletId ?: expense.sourceWalletId
                ?: return false
            val targetWallet = store.getWalletById(targetId) ?: return false
            val delta = computeNetReimbursementDeltaStatic(expense, targetWallet.currency)
            val newBal = if (targetWallet.type.isLiability) {
                targetWallet.balanceCents - delta
            } else {
                targetWallet.balanceCents + delta
            }
            store.updateWallet(targetWallet.copy(balanceCents = newBal, updatedAtMillis = nowMillis, syncDirty = true))
            store.updateTransaction(
                expense.copy(
                    reimbursed = true,
                    targetWalletId = targetId,
                    targetWalletDeltaCents = delta,
                    reimbursedAtMillis = nowMillis,
                    updatedAtMillis = nowMillis,
                    syncRevision = expense.syncRevision + 1,
                    syncDirty = true
                )
            )
            return true
        }

        internal suspend fun recordCompositeMedicalOnStore(
            store: LedgerTransactionStore,
            mainTx: TransactionEntity,
            poolSubsidyCents: Long,
            personalAccountCents: Long,
            unspecifiedMedicalCents: Long = 0L,
            commercialDiscountCents: Long = 0L,
            personalMedicalWalletId: Long? = null
        ): CompositeMedicalRecordResult {
            val cleanPool = poolSubsidyCents.coerceAtLeast(0L)
            val cleanPersonalExplicit = personalAccountCents.coerceAtLeast(0L)
            val cleanUnspecified = unspecifiedMedicalCents.coerceAtLeast(0L)
            val cleanCommercial = commercialDiscountCents.coerceAtLeast(0L)
            val effectivePersonal = cleanPersonalExplicit + cleanUnspecified

            val allWallets = store.getAllWallets()
            val medWallet = personalMedicalWalletId?.let { id -> allWallets.firstOrNull { it.id == id } }
                ?: DedicatedFundManager.findPersonalMedicalWallet(allWallets)

            val safeSourceWalletId = mainTx.sourceWalletId?.takeUnless { wid ->
                val candidate = allWallets.firstOrNull { it.id == wid }
                candidate != null && DedicatedFundManager.isMedicalInsuranceWallet(candidate)
            }
            val selfPayWalletName = safeSourceWalletId?.let { wid ->
                allWallets.firstOrNull { it.id == wid }?.name
            }.orEmpty()

            // 存在个人医保账户且有效个账金额 > 0 且现金自费主单 > 0 -> 原子双腿入账
            if (medWallet != null && effectivePersonal > 0L && mainTx.amountCents > 0L) {
                val totalOriginalCents = mainTx.amountCents + cleanPool + effectivePersonal + cleanCommercial
                // 主单 discountCents 仅保留统筹 + 商业立减（剥离已拆出独立入账的个账，严守 主单实付 + 子单实付 + 主单discountCents == 医疗原价）
                val mainDiscountCents = cleanPool + cleanCommercial
                val mainTags = TagCatalog.joinTags(
                    TagCatalog.parseTags(mainTx.tags) + listOf("医保", "组合支付")
                )
                val goodsPart = extractTailGoodsFromNote(mainTx.note, mainTx.merchant)
                val mainPayLabel = if (selfPayWalletName.isNotBlank()) {
                    "$selfPayWalletName+个人医保"
                } else if (mainTx.paymentMethodRaw.isNotBlank() && mainTx.paymentMethodRaw != "医保支付") {
                    "${mainTx.paymentMethodRaw}+个人医保"
                } else {
                    "个人自费+个人医保"
                }
                val mainTail = listOfNotNull(goodsPart.takeIf { it.isNotBlank() }, mainPayLabel).joinToString(" · ")
                val structuredMainNote = DedicatedFundManager.formatStructuredMedicalNote(
                    originalCostCents = totalOriginalCents,
                    poolSubsidyCents = cleanPool,
                    personalAccountCents = effectivePersonal,
                    unspecifiedMedicalCents = 0L,
                    commercialDiscountCents = cleanCommercial,
                    cashSelfPayCents = mainTx.amountCents,
                    tailSummary = mainTail
                ).take(140)

                val normalizedMain = (if (!mainTx.reimbursable && mainTx.reimbursed) {
                    mainTx.copy(reimbursed = false)
                } else {
                    mainTx
                }).copy(
                    id = 0L,
                    sourceWalletId = safeSourceWalletId,
                    discountCents = mainDiscountCents,
                    tags = mainTags,
                    note = structuredMainNote
                )
                val appliedMain = applyWalletImpactOnStore(store, normalizedMain)
                val mainId = store.insertTransaction(appliedMain)
                val savedMain = appliedMain.copy(id = mainId)

                val childOrderKey = if (savedMain.orderKey.isNotBlank()) {
                    "${savedMain.orderKey}${DedicatedFundManager.COMPOSITE_MEDICAL_PERSONAL_SUFFIX}"
                } else {
                    ""
                }
                val baseFp = savedMain.dedupFingerprint.ifBlank { "TX:$mainId" }
                val childFingerprint = "${baseFp}${DedicatedFundManager.COMPOSITE_MEDICAL_PERSONAL_SUFFIX}"
                val rate = if (savedMain.exchangeRateToCny > 0.0) savedMain.exchangeRateToCny else 1.0
                val childCnyCents = (effectivePersonal * rate).roundToLong()
                val personalYuanStr = String.format(Locale.US, "%.2f", effectivePersonal / 100.0)
                val childTail = listOfNotNull(
                    goodsPart.ifBlank { savedMain.merchant }.takeIf { it.isNotBlank() },
                    medWallet.name
                ).joinToString(" · ")
                val childNote = "${DedicatedFundManager.COMPOSITE_CHILD_NOTE_PREFIX}¥$personalYuanStr | $childTail".take(140)
                val childTags = TagCatalog.joinTags(listOf("医保", "医保个账", "组合支付"))

                val rawChild = TransactionEntity(
                    ledgerName = savedMain.ledgerName,
                    type = TransactionType.EXPENSE,
                    amountCents = effectivePersonal,
                    currency = savedMain.currency,
                    exchangeRateToCny = rate,
                    amountCnyCents = childCnyCents,
                    discountCents = 0L,
                    itemQuantity = 1,
                    quantityDesc = "1件",
                    category = savedMain.category.ifBlank { "医疗" },
                    merchant = savedMain.merchant,
                    note = childNote,
                    tags = childTags,
                    sourceWalletId = medWallet.id,
                    targetWalletId = null,
                    relatedTransactionId = mainId,
                    timestamp = savedMain.timestamp - 1L,
                    capturedAtMillis = savedMain.capturedAtMillis,
                    orderKey = childOrderKey,
                    transactionNo = "",
                    merchantOrderNo = "",
                    paymentMethodRaw = medWallet.name,
                    dedupFingerprint = childFingerprint,
                    captureSource = savedMain.captureSource,
                    rawCapturedText = savedMain.rawCapturedText,
                    aiConfidence = savedMain.aiConfidence,
                    updatedAtMillis = savedMain.updatedAtMillis
                )
                val appliedChild = applyWalletImpactOnStore(store, rawChild)
                val childId = store.insertTransaction(appliedChild)
                val savedChild = appliedChild.copy(id = childId)

                TagCatalog.syncDiscoveredTagsFromHistory(listOf(savedMain.tags, savedChild.tags))
                return CompositeMedicalRecordResult(
                    mainTransaction = savedMain,
                    childPersonalTransaction = savedChild
                )
            }

            // 未建个人医保账户（或无个账扣款）：仅写单笔主单，完整保留统筹/个账至结构化备注与 discountCents
            val totalOriginalSingle = mainTx.amountCents + cleanPool + effectivePersonal + cleanCommercial
            val singleDiscount = cleanPool + effectivePersonal + cleanCommercial
            val singleTags = if (cleanPool + effectivePersonal > 0L) {
                TagCatalog.joinTags(TagCatalog.parseTags(mainTx.tags) + listOf("医保"))
            } else {
                mainTx.tags
            }
            val singleGoods = extractTailGoodsFromNote(mainTx.note, mainTx.merchant)
            val singlePay = selfPayWalletName.ifBlank { mainTx.paymentMethodRaw }
            val singleTail = listOfNotNull(singleGoods.takeIf { it.isNotBlank() }, singlePay.takeIf { it.isNotBlank() }).joinToString(" · ")
            val singleNote = if (cleanPool + effectivePersonal > 0L) {
                DedicatedFundManager.formatStructuredMedicalNote(
                    originalCostCents = totalOriginalSingle,
                    poolSubsidyCents = cleanPool,
                    personalAccountCents = cleanPersonalExplicit,
                    unspecifiedMedicalCents = cleanUnspecified,
                    commercialDiscountCents = cleanCommercial,
                    cashSelfPayCents = mainTx.amountCents,
                    tailSummary = singleTail
                ).take(140)
            } else {
                mainTx.note
            }

            val normalizedSingle = (if (!mainTx.reimbursable && mainTx.reimbursed) {
                mainTx.copy(reimbursed = false)
            } else {
                mainTx
            }).copy(
                id = 0L,
                sourceWalletId = safeSourceWalletId,
                discountCents = singleDiscount,
                tags = singleTags,
                note = singleNote
            )
            val appliedSingle = applyWalletImpactOnStore(store, normalizedSingle)
            val singleId = store.insertTransaction(appliedSingle)
            val savedSingle = appliedSingle.copy(id = singleId)
            if (!savedSingle.isSystemBalanceAnchor() && savedSingle.tags.isNotBlank()) {
                TagCatalog.syncDiscoveredTagsFromHistory(listOf(savedSingle.tags))
            }
            return CompositeMedicalRecordResult(
                mainTransaction = savedSingle,
                childPersonalTransaction = null
            )
        }

        internal suspend fun enrichCompositeMedicalOnStore(
            store: LedgerTransactionStore,
            existingMainId: Long,
            enrichedCandidateMain: TransactionEntity,
            baseEnrichedFields: List<String>,
            poolSubsidyCents: Long,
            personalAccountCents: Long,
            unspecifiedMedicalCents: Long = 0L,
            commercialDiscountCents: Long = 0L
        ): CompositeMedicalFusionResult {
            val oldMain = store.getTransactionById(existingMainId)
                ?: return CompositeMedicalFusionResult(
                    updatedMainTransaction = enrichedCandidateMain,
                    wasModified = false
                )
            val cleanPool = poolSubsidyCents.coerceAtLeast(0L)
            val cleanPersonalExplicit = personalAccountCents.coerceAtLeast(0L)
            val cleanUnspecified = unspecifiedMedicalCents.coerceAtLeast(0L)
            val cleanCommercial = commercialDiscountCents.coerceAtLeast(0L)
            val effectivePersonal = cleanPersonalExplicit + cleanUnspecified

            val allWallets = store.getAllWallets()
            val medWallet = DedicatedFundManager.findPersonalMedicalWallet(allWallets)

            val resolvedOrderKey = enrichedCandidateMain.orderKey.ifBlank { oldMain.orderKey }
            val existingChild = store.getAllTransactions().firstOrNull { tx ->
                tx.id != oldMain.id &&
                    tx.relatedTransactionId == oldMain.id &&
                    DedicatedFundManager.isCompositeMedicalPersonalChild(tx)
            } ?: resolvedOrderKey.takeIf { it.isNotBlank() }?.let { key ->
                store.findTransactionByOrderKey("${key}${DedicatedFundManager.COMPOSITE_MEDICAL_PERSONAL_SUFFIX}")
            }

            // 保护主单 sourceWalletId 不被污染为医保卡
            val safeMainSourceWalletId = enrichedCandidateMain.sourceWalletId?.takeUnless { wid ->
                val w = allWallets.firstOrNull { it.id == wid }
                w != null && DedicatedFundManager.isMedicalInsuranceWallet(w)
            } ?: oldMain.sourceWalletId?.takeUnless { wid ->
                val w = allWallets.firstOrNull { it.id == wid }
                w != null && DedicatedFundManager.isMedicalInsuranceWallet(w)
            }

            val selfPayWalletName = safeMainSourceWalletId?.let { wid ->
                allWallets.firstOrNull { it.id == wid }?.name
            }.orEmpty()

            val fields = baseEnrichedFields.filterNot { it.startsWith("优惠(") }.toMutableList()
            val goodsPart = extractTailGoodsFromNote(enrichedCandidateMain.note, enrichedCandidateMain.merchant)

            if (medWallet != null && effectivePersonal > 0L && enrichedCandidateMain.amountCents > 0L) {
                val totalOriginalCents = enrichedCandidateMain.amountCents + cleanPool + effectivePersonal + cleanCommercial
                val targetMainDiscount = cleanPool + cleanCommercial
                val targetMainTags = TagCatalog.joinTags(
                    TagCatalog.parseTags(enrichedCandidateMain.tags) + listOf("医保", "组合支付")
                )
                val mainPayLabel = if (selfPayWalletName.isNotBlank()) {
                    "$selfPayWalletName+个人医保"
                } else {
                    "个人自费+个人医保"
                }
                val mainTail = listOfNotNull(goodsPart.takeIf { it.isNotBlank() }, mainPayLabel).joinToString(" · ")
                val targetMainNote = DedicatedFundManager.formatStructuredMedicalNote(
                    originalCostCents = totalOriginalCents,
                    poolSubsidyCents = cleanPool,
                    personalAccountCents = effectivePersonal,
                    unspecifiedMedicalCents = 0L,
                    commercialDiscountCents = cleanCommercial,
                    cashSelfPayCents = enrichedCandidateMain.amountCents,
                    tailSummary = mainTail
                ).take(140)

                val alreadyCompositeMain = DedicatedFundManager.isCompositeMedicalMain(oldMain) &&
                    oldMain.discountCents == targetMainDiscount &&
                    oldMain.sourceWalletId == safeMainSourceWalletId &&
                    oldMain.amountCents == enrichedCandidateMain.amountCents
                val childAlreadyAligned = existingChild != null &&
                    existingChild.amountCents == effectivePersonal &&
                    existingChild.sourceWalletId == medWallet.id
                if (childAlreadyAligned && alreadyCompositeMain && fields.isEmpty()) {
                    return CompositeMedicalFusionResult(
                        updatedMainTransaction = oldMain,
                        createdChildTransaction = null,
                        enrichedFields = emptyList(),
                        wasModified = false
                    )
                }

                if (cleanPool > 0L && !alreadyCompositeMain) {
                    fields.add("医保统筹(¥${String.format(Locale.US, "%.2f", cleanPool / 100.0)})")
                }

                val targetMain = enrichedCandidateMain.copy(
                    id = oldMain.id,
                    sourceWalletId = safeMainSourceWalletId,
                    discountCents = targetMainDiscount,
                    tags = targetMainTags,
                    note = targetMainNote,
                    orderKey = resolvedOrderKey,
                    captureSource = "AI_FUSED"
                )
                val appliedMain = if (hasSameFinancialImpactStatic(oldMain, targetMain)) {
                    targetMain.copy(
                        sourceWalletDeltaCents = oldMain.sourceWalletDeltaCents,
                        targetWalletDeltaCents = oldMain.targetWalletDeltaCents,
                        reimbursedAtMillis = if (targetMain.reimbursed) {
                            targetMain.reimbursedAtMillis ?: oldMain.reimbursedAtMillis
                        } else {
                            null
                        }
                    )
                } else {
                    revertWalletImpactOnStore(store, oldMain)
                    applyWalletImpactOnStore(store, targetMain)
                }
                store.updateTransaction(appliedMain)

                var createdChild: TransactionEntity? = null
                val childOrderKey = if (resolvedOrderKey.isNotBlank()) {
                    "${resolvedOrderKey}${DedicatedFundManager.COMPOSITE_MEDICAL_PERSONAL_SUFFIX}"
                } else {
                    ""
                }
                val rate = if (appliedMain.exchangeRateToCny > 0.0) appliedMain.exchangeRateToCny else 1.0
                val childCnyCents = (effectivePersonal * rate).roundToLong()
                val personalYuanStr = String.format(Locale.US, "%.2f", effectivePersonal / 100.0)
                val childTail = listOfNotNull(
                    goodsPart.ifBlank { appliedMain.merchant }.takeIf { it.isNotBlank() },
                    medWallet.name
                ).joinToString(" · ")
                val childNote = "${DedicatedFundManager.COMPOSITE_CHILD_NOTE_PREFIX}¥$personalYuanStr | $childTail".take(140)
                val childTags = TagCatalog.joinTags(listOf("医保", "医保个账", "组合支付"))

                if (existingChild == null) {
                    val baseFp = appliedMain.dedupFingerprint.ifBlank { "TX:${appliedMain.id}" }
                    val childFp = "${baseFp}${DedicatedFundManager.COMPOSITE_MEDICAL_PERSONAL_SUFFIX}"

                    val rawChild = TransactionEntity(
                        ledgerName = appliedMain.ledgerName,
                        type = TransactionType.EXPENSE,
                        amountCents = effectivePersonal,
                        currency = appliedMain.currency,
                        exchangeRateToCny = rate,
                        amountCnyCents = childCnyCents,
                        discountCents = 0L,
                        itemQuantity = 1,
                        quantityDesc = "1件",
                        category = appliedMain.category.ifBlank { "医疗" },
                        merchant = appliedMain.merchant,
                        note = childNote,
                        tags = childTags,
                        sourceWalletId = medWallet.id,
                        targetWalletId = null,
                        relatedTransactionId = appliedMain.id,
                        timestamp = appliedMain.timestamp - 1L,
                        capturedAtMillis = appliedMain.capturedAtMillis,
                        orderKey = childOrderKey,
                        paymentMethodRaw = medWallet.name,
                        dedupFingerprint = childFp,
                        captureSource = "AI_FUSED",
                        rawCapturedText = appliedMain.rawCapturedText,
                        aiConfidence = appliedMain.aiConfidence,
                        updatedAtMillis = appliedMain.updatedAtMillis
                    )
                    val appliedChild = applyWalletImpactOnStore(store, rawChild)
                    val childId = store.insertTransaction(appliedChild)
                    createdChild = appliedChild.copy(id = childId)
                    fields.add("医保个账扣卡(¥$personalYuanStr)")
                } else if (existingChild.amountCents != effectivePersonal || existingChild.sourceWalletId != medWallet.id) {
                    revertWalletImpactOnStore(store, existingChild)
                    val recalibratedChild = existingChild.copy(
                        amountCents = effectivePersonal,
                        currency = appliedMain.currency,
                        exchangeRateToCny = rate,
                        amountCnyCents = childCnyCents,
                        category = appliedMain.category.ifBlank { existingChild.category },
                        merchant = appliedMain.merchant.ifBlank { existingChild.merchant },
                        note = childNote,
                        tags = childTags,
                        sourceWalletId = medWallet.id,
                        paymentMethodRaw = medWallet.name,
                        orderKey = childOrderKey.ifBlank { existingChild.orderKey },
                        captureSource = "AI_FUSED",
                        updatedAtMillis = appliedMain.updatedAtMillis,
                        syncRevision = existingChild.syncRevision + 1,
                        syncDirty = true
                    )
                    val appliedChild = applyWalletImpactOnStore(store, recalibratedChild)
                    store.updateTransaction(appliedChild)
                    createdChild = appliedChild
                    fields.add("医保个账校准(¥$personalYuanStr)")
                } else if (resolvedOrderKey.isNotBlank() && existingChild.orderKey.isBlank()) {
                    store.updateTransaction(
                        existingChild.copy(
                            orderKey = childOrderKey,
                            updatedAtMillis = appliedMain.updatedAtMillis,
                            syncRevision = existingChild.syncRevision + 1,
                            syncDirty = true
                        )
                    )
                }

                TagCatalog.syncDiscoveredTagsFromHistory(
                    listOfNotNull(appliedMain.tags, createdChild?.tags)
                )
                return CompositeMedicalFusionResult(
                    updatedMainTransaction = appliedMain,
                    createdChildTransaction = createdChild,
                    enrichedFields = fields,
                    wasModified = true
                )
            }

            // 未配置个人医保卡或明细帧确认个账金额为 0 时的多帧融合：若有旧个账子单则原子回滚并删除，将统筹+个账完整写入主单 discountCents 与结构化备注
            if (existingChild != null) {
                revertWalletImpactOnStore(store, existingChild)
                store.deleteTransaction(existingChild)
                fields.add("冲回医保个账子单")
            }

            val totalOriginalSingle = enrichedCandidateMain.amountCents + cleanPool + effectivePersonal + cleanCommercial
            val targetDiscount = cleanPool + effectivePersonal + cleanCommercial
            val cleanedMainTags = TagCatalog.parseTags(enrichedCandidateMain.tags).filterNot { it == "组合支付" }
            val targetTags = if (cleanPool + effectivePersonal > 0L) {
                TagCatalog.joinTags(cleanedMainTags + listOf("医保"))
            } else {
                TagCatalog.joinTags(cleanedMainTags)
            }
            val singlePay = selfPayWalletName.ifBlank { enrichedCandidateMain.paymentMethodRaw }
            val singleTail = listOfNotNull(goodsPart.takeIf { it.isNotBlank() }, singlePay.takeIf { it.isNotBlank() }).joinToString(" · ")
            val targetNote = DedicatedFundManager.formatStructuredMedicalNote(
                originalCostCents = totalOriginalSingle,
                poolSubsidyCents = cleanPool,
                personalAccountCents = cleanPersonalExplicit,
                unspecifiedMedicalCents = cleanUnspecified,
                commercialDiscountCents = cleanCommercial,
                cashSelfPayCents = enrichedCandidateMain.amountCents,
                tailSummary = singleTail
            ).take(140)

            val oldParsedNote = DedicatedFundManager.parseStructuredMedicalNote(oldMain.note)
            if (existingChild == null && oldParsedNote.hasExplicitBreakdown && oldMain.discountCents == targetDiscount && fields.isEmpty()) {
                return CompositeMedicalFusionResult(
                    updatedMainTransaction = oldMain,
                    createdChildTransaction = null,
                    enrichedFields = emptyList(),
                    wasModified = false
                )
            }
            if (!oldParsedNote.hasExplicitBreakdown && (cleanPool + effectivePersonal) > 0L) {
                fields.add("医保明细(¥${String.format(Locale.US, "%.2f", (cleanPool + effectivePersonal) / 100.0)})")
            }

            val updatedSingle = enrichedCandidateMain.copy(
                id = oldMain.id,
                sourceWalletId = safeMainSourceWalletId,
                discountCents = targetDiscount,
                tags = targetTags,
                note = targetNote,
                orderKey = resolvedOrderKey,
                captureSource = "AI_FUSED"
            )
            val appliedSingle = if (hasSameFinancialImpactStatic(oldMain, updatedSingle)) {
                updatedSingle.copy(
                    sourceWalletDeltaCents = oldMain.sourceWalletDeltaCents,
                    targetWalletDeltaCents = oldMain.targetWalletDeltaCents,
                    reimbursedAtMillis = if (updatedSingle.reimbursed) {
                        updatedSingle.reimbursedAtMillis ?: oldMain.reimbursedAtMillis
                    } else {
                        null
                    }
                )
            } else {
                revertWalletImpactOnStore(store, oldMain)
                applyWalletImpactOnStore(store, updatedSingle)
            }
            store.updateTransaction(appliedSingle)
            TagCatalog.syncDiscoveredTagsFromHistory(listOf(appliedSingle.tags))
            return CompositeMedicalFusionResult(
                updatedMainTransaction = appliedSingle,
                createdChildTransaction = null,
                enrichedFields = fields,
                wasModified = true
            )
        }

        internal suspend fun recordPayslipOnStore(
            store: LedgerTransactionStore,
            request: PayslipCommitRequest
        ): PayslipCommitOutcome {
            val draft = request.draft
            val netPayCents = draft.netTakeHomePayCents.coerceAtLeast(0L)
            val medDepositCents = request.medicalDepositCents.coerceAtLeast(0L)
            val provDepositCents = request.providentDepositCents.coerceAtLeast(0L)

            val shouldRecordNet = request.enableNetPay && netPayCents > 0L
            val shouldRecordMed = request.enableMedicalDeposit && medDepositCents > 0L
            val shouldRecordProv = request.enableProvidentDeposit && provDepositCents > 0L

            if (!shouldRecordNet && !shouldRecordMed && !shouldRecordProv) {
                return PayslipCommitOutcome.ValidationError("请至少开启一条金额大于 0 的工资条入账支路")
            }

            val baseOrderKey = DedicatedFundManager.buildPayslipOrderKey(
                paydayTimestampMillis = draft.paydayTimestampMillis,
                netTakeHomePayCents = netPayCents,
                grossSalaryCents = draft.grossSalaryCents
            )
            val existingSameMonth = store.findTransactionByOrderKey(baseOrderKey)
                ?: store.findTransactionByOrderKey(baseOrderKey + DedicatedFundManager.PAYSLIP_MEDICAL_CHILD_SUFFIX)
                ?: store.findTransactionByOrderKey(baseOrderKey + DedicatedFundManager.PAYSLIP_PROVIDENT_CHILD_SUFFIX)

            if (existingSameMonth != null && !request.forceDuplicate) {
                return PayslipCommitOutcome.DuplicateDetected(
                    orderKey = baseOrderKey,
                    existingTransaction = existingSameMonth
                )
            }

            val effectiveOrderKey = if (existingSameMonth != null) {
                "${baseOrderKey}#FORCE_${request.nowMillis}"
            } else {
                baseOrderKey
            }

            val now = request.nowMillis
            val existingWallets = store.getAllWallets()

            val payrollWallet: WalletEntity? = if (shouldRecordNet && request.payrollWalletId != null) {
                val found = existingWallets.firstOrNull { it.id == request.payrollWalletId }
                    ?: return PayslipCommitOutcome.ValidationError("所选代发工资账户不存在，请重新选择")
                if (found.type.isLiability) {
                    return PayslipCommitOutcome.ValidationError("实发工资不能存入信用负债账户，请选择储蓄卡或资产账户")
                }
                found
            } else {
                null
            }

            var createdMedicalWallet: WalletEntity? = null
            val targetMedicalWallet: WalletEntity? = if (shouldRecordMed) {
                val explicitMed = request.medicalWalletId?.let { id -> existingWallets.firstOrNull { it.id == id } }
                    ?: DedicatedFundManager.findPersonalMedicalWallet(existingWallets)
                if (explicitMed != null) {
                    explicitMed
                } else if (request.autoCreateMedicalWallet) {
                    val kind = DedicatedFundKind.MEDICAL_INSURANCE
                    val draftWallet = WalletEntity(
                        name = kind.defaultName,
                        type = WalletType.CASH_OTHER,
                        subTypeLabel = kind.subTypeLabel,
                        brandCode = kind.brandCode,
                        balanceCents = 0L,
                        currency = "CNY",
                        colorHex = kind.defaultColorHex,
                        includeInNetWorth = false,
                        aliases = kind.defaultAliases,
                        updatedAtMillis = now,
                        syncDirty = true
                    )
                    val newId = store.insertWallet(draftWallet)
                    draftWallet.copy(id = newId).also { createdMedicalWallet = it }
                } else {
                    return PayslipCommitOutcome.ValidationError("尚未选择个人医保账户，请先选择或开启自动新建专项账户")
                }
            } else {
                null
            }

            var createdProvidentWallet: WalletEntity? = null
            val targetProvidentWallet: WalletEntity? = if (shouldRecordProv) {
                val explicitProv = request.providentWalletId?.let { id -> existingWallets.firstOrNull { it.id == id } }
                    ?: DedicatedFundManager.findHousingProvidentWallet(existingWallets)
                if (explicitProv != null) {
                    explicitProv
                } else if (request.autoCreateProvidentWallet) {
                    val kind = DedicatedFundKind.HOUSING_PROVIDENT
                    val draftWallet = WalletEntity(
                        name = kind.defaultName,
                        type = WalletType.CASH_OTHER,
                        subTypeLabel = kind.subTypeLabel,
                        brandCode = kind.brandCode,
                        balanceCents = 0L,
                        currency = "CNY",
                        colorHex = kind.defaultColorHex,
                        includeInNetWorth = false,
                        aliases = kind.defaultAliases,
                        updatedAtMillis = now,
                        syncDirty = true
                    )
                    val newId = store.insertWallet(draftWallet)
                    draftWallet.copy(id = newId).also { createdProvidentWallet = it }
                } else {
                    return PayslipCommitOutcome.ValidationError("尚未选择住房公积金账户，请先选择或开启自动新建专项账户")
                }
            } else {
                null
            }

            val cleanLedger = request.ledgerName.ifBlank { "日常账本" }
            val cleanEmployer = draft.employerName.trim()
            val discoveredTags = mutableListOf<String>()

            val savedMainTx: TransactionEntity? = if (shouldRecordNet) {
                val mainCurrency = payrollWallet?.currency ?: "CNY"
                val mainRate = CurrencyRateManager.getEffectiveRateToCny(mainCurrency)
                val mainCnyCents = (netPayCents * mainRate).roundToLong()
                val mainTags = TagCatalog.joinTags(listOf("工资"))
                discoveredTags.add(mainTags)
                val mainMerchant = if (cleanEmployer.isNotEmpty()) "$cleanEmployer · 工资" else "工资收入"
                val mainNote = DedicatedFundManager.formatPayslipMainNote(draft, request.customNote)
                val rawMain = TransactionEntity(
                    ledgerName = cleanLedger,
                    type = TransactionType.INCOME,
                    amountCents = netPayCents,
                    currency = mainCurrency,
                    exchangeRateToCny = mainRate,
                    amountCnyCents = mainCnyCents,
                    category = "工资",
                    merchant = mainMerchant,
                    note = mainNote,
                    tags = mainTags,
                    sourceWalletId = null,
                    targetWalletId = payrollWallet?.id,
                    timestamp = draft.paydayTimestampMillis,
                    capturedAtMillis = now,
                    orderKey = effectiveOrderKey,
                    paymentMethodRaw = payrollWallet?.name.orEmpty(),
                    dedupFingerprint = "KEY:${effectiveOrderKey}_$netPayCents",
                    captureSource = request.captureSource,
                    updatedAtMillis = now,
                    syncDirty = true,
                    syncToFamily = MoJiCloudSyncClient.isFamilyAutoShareActive()
                )
                val appliedMain = applyWalletImpactOnStore(store, rawMain)
                val mainId = store.insertTransaction(appliedMain)
                appliedMain.copy(id = mainId)
            } else {
                null
            }

            val savedMedicalChildTx: TransactionEntity? = if (shouldRecordMed && targetMedicalWallet != null) {
                val medCurrency = targetMedicalWallet.currency
                val medRate = CurrencyRateManager.getEffectiveRateToCny(medCurrency)
                val medCnyCents = (medDepositCents * medRate).roundToLong()
                val medOrderKey = effectiveOrderKey + DedicatedFundManager.PAYSLIP_MEDICAL_CHILD_SUFFIX
                val medTags = TagCatalog.joinTags(listOf("医保", "工资条"))
                discoveredTags.add(medTags)
                val medMerchant = if (cleanEmployer.isNotEmpty()) "$cleanEmployer · 医保个账划入" else "个人医保月度划入"
                val medNote = DedicatedFundManager.formatPayslipMedicalChildNote(
                    personalMedicalDeductionCents = draft.personalMedicalDeductionCents,
                    depositCents = medDepositCents,
                    customTail = request.customNote
                )
                val rawMed = TransactionEntity(
                    ledgerName = cleanLedger,
                    type = TransactionType.INCOME,
                    amountCents = medDepositCents,
                    currency = medCurrency,
                    exchangeRateToCny = medRate,
                    amountCnyCents = medCnyCents,
                    category = DedicatedFundKind.MEDICAL_INSURANCE.defaultIncomeCategory,
                    merchant = medMerchant,
                    note = medNote,
                    tags = medTags,
                    sourceWalletId = null,
                    targetWalletId = targetMedicalWallet.id,
                    relatedTransactionId = savedMainTx?.id,
                    timestamp = draft.paydayTimestampMillis,
                    capturedAtMillis = now,
                    orderKey = medOrderKey,
                    paymentMethodRaw = targetMedicalWallet.name,
                    dedupFingerprint = "KEY:${effectiveOrderKey}_$medDepositCents${DedicatedFundManager.PAYSLIP_MEDICAL_CHILD_SUFFIX}",
                    captureSource = request.captureSource,
                    updatedAtMillis = now,
                    syncDirty = true,
                    syncToFamily = false
                )
                val appliedMed = applyWalletImpactOnStore(store, rawMed)
                val medId = store.insertTransaction(appliedMed)
                appliedMed.copy(id = medId)
            } else {
                null
            }

            val savedProvidentChildTx: TransactionEntity? = if (shouldRecordProv && targetProvidentWallet != null) {
                val provCurrency = targetProvidentWallet.currency
                val provRate = CurrencyRateManager.getEffectiveRateToCny(provCurrency)
                val provCnyCents = (provDepositCents * provRate).roundToLong()
                val provOrderKey = effectiveOrderKey + DedicatedFundManager.PAYSLIP_PROVIDENT_CHILD_SUFFIX
                val provTags = TagCatalog.joinTags(listOf("公积金", "工资条"))
                discoveredTags.add(provTags)
                val provMerchant = if (cleanEmployer.isNotEmpty()) "$cleanEmployer · 住房公积金缴存" else "住房公积金月度缴存"
                val provNote = DedicatedFundManager.formatPayslipProvidentChildNote(
                    personalProvidentCents = draft.personalProvidentDeductionCents,
                    employerProvidentCents = draft.employerProvidentMatchCents,
                    totalDepositCents = provDepositCents,
                    customTail = request.customNote
                )
                val rawProv = TransactionEntity(
                    ledgerName = cleanLedger,
                    type = TransactionType.INCOME,
                    amountCents = provDepositCents,
                    currency = provCurrency,
                    exchangeRateToCny = provRate,
                    amountCnyCents = provCnyCents,
                    category = DedicatedFundKind.HOUSING_PROVIDENT.defaultIncomeCategory,
                    merchant = provMerchant,
                    note = provNote,
                    tags = provTags,
                    sourceWalletId = null,
                    targetWalletId = targetProvidentWallet.id,
                    relatedTransactionId = savedMainTx?.id,
                    timestamp = draft.paydayTimestampMillis,
                    capturedAtMillis = now,
                    orderKey = provOrderKey,
                    paymentMethodRaw = targetProvidentWallet.name,
                    dedupFingerprint = "KEY:${effectiveOrderKey}_$provDepositCents${DedicatedFundManager.PAYSLIP_PROVIDENT_CHILD_SUFFIX}",
                    captureSource = request.captureSource,
                    updatedAtMillis = now,
                    syncDirty = true,
                    syncToFamily = false
                )
                val appliedProv = applyWalletImpactOnStore(store, rawProv)
                val provId = store.insertTransaction(appliedProv)
                appliedProv.copy(id = provId)
            } else {
                null
            }

            if (payrollWallet != null) {
                val curSettings = DedicatedFundManager.currentSettings
                if (shouldRecordMed) {
                    val empMedCents = draft.personalMedicalDeductionCents.coerceAtLeast(0L)
                    val matchMedCents = (medDepositCents - empMedCents).coerceAtLeast(0L)
                    DedicatedFundManager.updatePlan(
                        DedicatedFundKind.MEDICAL_INSURANCE,
                        curSettings.medicalPlan.copy(
                            enabled = true,
                            employeeMonthlyCents = if (empMedCents > 0L) empMedCents else medDepositCents,
                            employerMonthlyCents = matchMedCents,
                            linkedPayrollWalletId = payrollWallet.id
                        )
                    )
                }
                if (shouldRecordProv) {
                    val empCents = draft.personalProvidentDeductionCents.coerceAtLeast(0L)
                    val matchCents = (provDepositCents - empCents).coerceAtLeast(0L)
                    DedicatedFundManager.updatePlan(
                        DedicatedFundKind.HOUSING_PROVIDENT,
                        DedicatedFundManager.currentSettings.providentPlan.copy(
                            enabled = true,
                            employeeMonthlyCents = if (empCents > 0L) empCents else provDepositCents,
                            employerMonthlyCents = matchCents,
                            linkedPayrollWalletId = payrollWallet.id
                        )
                    )
                }
            }

            if (discoveredTags.isNotEmpty()) {
                TagCatalog.syncDiscoveredTagsFromHistory(discoveredTags)
            }

            return PayslipCommitOutcome.Success(
                mainTransaction = savedMainTx,
                medicalChildTransaction = savedMedicalChildTx,
                providentChildTransaction = savedProvidentChildTx,
                createdMedicalWallet = createdMedicalWallet,
                createdProvidentWallet = createdProvidentWallet,
                orderKey = effectiveOrderKey
            )
        }

        internal suspend fun undoAndDeleteTransactionOnStore(
            store: LedgerTransactionStore,
            transactionId: Long
        ): Pair<Boolean, List<String>> {
            val tx = store.getTransactionById(transactionId) ?: return false to emptyList()
            val candidateScreenshots = mutableSetOf<String>()
            if (tx.screenshotPath.isNotBlank()) candidateScreenshots.add(tx.screenshotPath)

            if (!DedicatedFundManager.isPayslipDedicatedChild(tx)) {
                // 若删除的是工资条主单，在同一事务内查找并联动回滚删除关联的 `#PAYSLIP_MED` 与 `#PAYSLIP_PROV` 专项入账子单
                val linkedPayslipChildren = store.getAllTransactions().filter { other ->
                    other.id != tx.id &&
                        other.relatedTransactionId == tx.id &&
                        DedicatedFundManager.isPayslipDedicatedChild(other)
                }
                for (child in linkedPayslipChildren) {
                    if (child.screenshotPath.isNotBlank()) candidateScreenshots.add(child.screenshotPath)
                    revertWalletImpactOnStore(store, child)
                    store.deleteTransaction(child)
                }
            }

            if (!DedicatedFundManager.isCompositeMedicalPersonalChild(tx)) {
                // 若删除的是组合支付主单，在同一事务内查找并联动回滚所有 `#MED_PERSONAL` 关联医保个账子单
                val linkedMedicalChildren = store.getAllTransactions().filter { other ->
                    other.id != tx.id &&
                        other.relatedTransactionId == tx.id &&
                        DedicatedFundManager.isCompositeMedicalPersonalChild(other)
                }
                for (child in linkedMedicalChildren) {
                    if (child.screenshotPath.isNotBlank()) candidateScreenshots.add(child.screenshotPath)
                    revertWalletImpactOnStore(store, child)
                    store.deleteTransaction(child)
                }
            } else {
                // 若用户单独删除的是 `#MED_PERSONAL` 子单，同步清理父单的「组合支付」标记并将个账金额还原回父单优惠抵扣
                tx.relatedTransactionId?.let { parentId ->
                    store.getTransactionById(parentId)?.let { parent ->
                        if (DedicatedFundManager.isCompositeMedicalMain(parent)) {
                            val remainingTags = TagCatalog.parseTags(parent.tags).filterNot { it == "组合支付" }
                            store.updateTransaction(
                                parent.copy(
                                    discountCents = parent.discountCents + tx.amountCents,
                                    tags = TagCatalog.joinTags(remainingTags),
                                    updatedAtMillis = System.currentTimeMillis(),
                                    syncRevision = parent.syncRevision + 1,
                                    syncDirty = true
                                )
                            )
                        }
                    }
                }
            }

            revertWalletImpactOnStore(store, tx)
            store.deleteTransaction(tx)

            val remainingTxs = store.getAllTransactions()
            if (tx.type == TransactionType.EXPENSE) {
                val now = System.currentTimeMillis()
                for (other in remainingTxs) {
                    if (other.type == TransactionType.REFUND_OFFSET && other.relatedTransactionId == tx.id) {
                        store.updateTransaction(
                            other.copy(
                                relatedTransactionId = null,
                                updatedAtMillis = now,
                                syncRevision = other.syncRevision + 1,
                                syncDirty = true
                            )
                        )
                    }
                }
            }

            val unreferencedScreenshots = candidateScreenshots.filter { path ->
                remainingTxs.none { it.screenshotPath == path }
            }
            return true to unreferencedScreenshots
        }

        private fun extractTailGoodsFromNote(note: String, fallbackMerchant: String): String {
            if (!note.contains("|")) {
                return note.trim().takeUnless { it == fallbackMerchant }.orEmpty()
            }
            val afterPipe = note.substringAfter("|").trim()
            if (afterPipe.isBlank()) return ""
            val segs = afterPipe.split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
            return if (segs.size >= 2) {
                segs.dropLast(1).joinToString(" · ")
            } else {
                ""
            }
        }

        internal fun hasSameFinancialImpactStatic(a: TransactionEntity, b: TransactionEntity): Boolean {
            return a.type == b.type &&
                a.amountCents == b.amountCents &&
                a.amountCnyCents == b.amountCnyCents &&
                a.feeCents == b.feeCents &&
                a.currency.equals(b.currency, ignoreCase = true) &&
                a.exchangeRateToCny == b.exchangeRateToCny &&
                a.sourceWalletId == b.sourceWalletId &&
                a.targetWalletId == b.targetWalletId &&
                a.relatedTransactionId == b.relatedTransactionId &&
                a.refundedAmountCents == b.refundedAmountCents &&
                a.reimbursed == b.reimbursed
        }

        private fun computeNetReimbursementDeltaStatic(
            expense: TransactionEntity,
            targetWalletCurrency: String
        ): Long {
            if (expense.refundedAmountCents <= 0L) {
                return convertCentsStatic(
                    expense.amountCents,
                    expense.currency,
                    targetWalletCurrency,
                    expense.exchangeRateToCny
                )
            }
            val netCnyCents = (expense.amountCnyCents - expense.refundedAmountCents).coerceAtLeast(0L)
            if (netCnyCents <= 0L) return 0L
            if (expense.currency.equals(targetWalletCurrency, ignoreCase = true) && expense.exchangeRateToCny > 0.0) {
                return (netCnyCents / expense.exchangeRateToCny).roundToLong()
            }
            return convertCentsStatic(netCnyCents, "CNY", targetWalletCurrency, 1.0)
        }

        internal suspend fun applyWalletImpactOnStore(
            store: LedgerTransactionStore,
            tx: TransactionEntity,
            nowMillis: Long = System.currentTimeMillis()
        ): TransactionEntity {
            val totalOutCents = tx.amountCents + tx.feeCents

            when (tx.type) {
                TransactionType.EXPENSE, TransactionType.LOAN_OUT -> {
                    var srcDelta: Long? = null
                    tx.sourceWalletId?.let { walletId ->
                        store.getWalletById(walletId)?.let { wallet ->
                            val delta = convertCentsStatic(totalOutCents, tx.currency, wallet.currency, tx.exchangeRateToCny)
                            val newBalance = if (wallet.type.isLiability) wallet.balanceCents + delta else wallet.balanceCents - delta
                            store.updateWallet(wallet.copy(balanceCents = newBalance, updatedAtMillis = nowMillis, syncDirty = true))
                            srcDelta = delta
                        }
                    }
                    var reimbDelta: Long? = null
                    var reimbAtMillis: Long? = null
                    if (tx.type == TransactionType.EXPENSE && tx.reimbursed) {
                        val reimburseWalletId = tx.targetWalletId ?: tx.sourceWalletId
                        reimburseWalletId?.let { wid ->
                            store.getWalletById(wid)?.let { rWallet ->
                                val rDelta = computeNetReimbursementDeltaStatic(tx, rWallet.currency)
                                val newBal = if (rWallet.type.isLiability) rWallet.balanceCents - rDelta else rWallet.balanceCents + rDelta
                                store.updateWallet(rWallet.copy(balanceCents = newBal, updatedAtMillis = nowMillis, syncDirty = true))
                                reimbDelta = rDelta
                                reimbAtMillis = tx.reimbursedAtMillis ?: nowMillis
                            }
                        }
                    }
                    return tx.copy(
                        sourceWalletDeltaCents = srcDelta,
                        targetWalletDeltaCents = reimbDelta,
                        reimbursedAtMillis = reimbAtMillis
                    )
                }

                TransactionType.INCOME, TransactionType.LOAN_IN -> {
                    var dstDelta: Long? = null
                    val targetId = tx.targetWalletId ?: tx.sourceWalletId
                    targetId?.let { walletId ->
                        store.getWalletById(walletId)?.let { wallet ->
                            val delta = convertCentsStatic(tx.amountCents - tx.feeCents, tx.currency, wallet.currency, tx.exchangeRateToCny)
                            val newBalance = if (wallet.type.isLiability) wallet.balanceCents - delta else wallet.balanceCents + delta
                            store.updateWallet(wallet.copy(balanceCents = newBalance, updatedAtMillis = nowMillis, syncDirty = true))
                            dstDelta = delta
                        }
                    }
                    return tx.copy(
                        sourceWalletDeltaCents = null,
                        targetWalletDeltaCents = dstDelta,
                        reimbursedAtMillis = null
                    )
                }

                TransactionType.TRANSFER -> {
                    var srcDelta: Long? = null
                    var dstDelta: Long? = null
                    tx.sourceWalletId?.let { fromId ->
                        store.getWalletById(fromId)?.let { fromWallet ->
                            val delta = convertCentsStatic(totalOutCents, tx.currency, fromWallet.currency, tx.exchangeRateToCny)
                            val newBal = if (fromWallet.type.isLiability) fromWallet.balanceCents + delta else fromWallet.balanceCents - delta
                            store.updateWallet(fromWallet.copy(balanceCents = newBal, updatedAtMillis = nowMillis, syncDirty = true))
                            srcDelta = delta
                        }
                    }
                    tx.targetWalletId?.let { toId ->
                        store.getWalletById(toId)?.let { toWallet ->
                            val delta = convertCentsStatic(tx.amountCents, tx.currency, toWallet.currency, tx.exchangeRateToCny)
                            val newBal = if (toWallet.type.isLiability) toWallet.balanceCents - delta else toWallet.balanceCents + delta
                            store.updateWallet(toWallet.copy(balanceCents = newBal, updatedAtMillis = nowMillis, syncDirty = true))
                            dstDelta = delta
                        }
                    }
                    return tx.copy(
                        sourceWalletDeltaCents = srcDelta,
                        targetWalletDeltaCents = dstDelta,
                        reimbursedAtMillis = null
                    )
                }

                TransactionType.REPAYMENT -> {
                    var srcDelta: Long? = null
                    var dstDelta: Long? = null
                    tx.sourceWalletId?.let { fromId ->
                        store.getWalletById(fromId)?.let { fromWallet ->
                            val delta = convertCentsStatic(totalOutCents, tx.currency, fromWallet.currency, tx.exchangeRateToCny)
                            val newBal = if (fromWallet.type.isLiability) fromWallet.balanceCents + delta else fromWallet.balanceCents - delta
                            store.updateWallet(fromWallet.copy(balanceCents = newBal, updatedAtMillis = nowMillis, syncDirty = true))
                            srcDelta = delta
                        }
                    }
                    tx.targetWalletId?.let { creditId ->
                        store.getWalletById(creditId)?.let { creditWallet ->
                            val delta = convertCentsStatic(tx.amountCents, tx.currency, creditWallet.currency, tx.exchangeRateToCny)
                            val newBal = if (creditWallet.type.isLiability) creditWallet.balanceCents - delta else creditWallet.balanceCents + delta
                            store.updateWallet(creditWallet.copy(balanceCents = newBal, updatedAtMillis = nowMillis, syncDirty = true))
                            dstDelta = delta
                        }
                    }
                    return tx.copy(
                        sourceWalletDeltaCents = srcDelta,
                        targetWalletDeltaCents = dstDelta,
                        reimbursedAtMillis = null
                    )
                }

                TransactionType.REFUND_OFFSET -> {
                    var receiveDelta: Long? = null
                    val receiveWalletId = tx.targetWalletId ?: tx.sourceWalletId
                    receiveWalletId?.let { walletId ->
                        store.getWalletById(walletId)?.let { wallet ->
                            val delta = if (wallet.currency.equals("CNY", ignoreCase = true) && tx.amountCnyCents > 0L) {
                                tx.amountCnyCents
                            } else {
                                convertCentsStatic(tx.amountCents, tx.currency, wallet.currency, tx.exchangeRateToCny)
                            }
                            val newBal = if (wallet.type.isLiability) wallet.balanceCents - delta else wallet.balanceCents + delta
                            store.updateWallet(wallet.copy(balanceCents = newBal, updatedAtMillis = nowMillis, syncDirty = true))
                            receiveDelta = delta
                        }
                    }
                    val targetExpense = tx.relatedTransactionId?.let { store.getTransactionById(it) }
                        ?: findBestMatchingExpenseForRefundStatic(store, tx)
                    if (targetExpense != null) {
                        val prevRefunded = targetExpense.refundedAmountCents.coerceAtLeast(0L)
                        val updatedRefunded = (prevRefunded + tx.amountCnyCents.coerceAtLeast(0L))
                            .coerceAtMost(targetExpense.amountCnyCents)
                        var updatedExpenseTargetDelta = targetExpense.targetWalletDeltaCents

                        // AC-1 ①：父单已核销报销时，按更新前后净报销差额同步扣减报销入账钱包并更新 targetWalletDeltaCents
                        if (targetExpense.type == TransactionType.EXPENSE && targetExpense.reimbursed && updatedRefunded > prevRefunded) {
                            val reimburseWalletId = targetExpense.targetWalletId ?: targetExpense.sourceWalletId
                            val rWallet = reimburseWalletId?.let { store.getWalletById(it) }
                            if (rWallet != null) {
                                val oldRDelta = targetExpense.targetWalletDeltaCents
                                    ?: computeNetReimbursementDeltaStatic(targetExpense, rWallet.currency)
                                val prevNetCny = (targetExpense.amountCnyCents - prevRefunded).coerceAtLeast(0L)
                                val newNetCny = (targetExpense.amountCnyCents - updatedRefunded).coerceAtLeast(0L)
                                val newRDelta = when {
                                    newNetCny <= 0L -> 0L
                                    targetExpense.targetWalletDeltaCents != null && prevNetCny > 0L ->
                                        ((oldRDelta.toDouble() * newNetCny.toDouble()) / prevNetCny.toDouble())
                                            .roundToLong()
                                            .coerceIn(0L, oldRDelta)
                                    else ->
                                        computeNetReimbursementDeltaStatic(
                                            targetExpense.copy(refundedAmountCents = updatedRefunded),
                                            rWallet.currency
                                        )
                                }
                                val clawbackDelta = (oldRDelta - newRDelta).coerceAtLeast(0L)
                                if (clawbackDelta > 0L) {
                                    val newRBal = if (rWallet.type.isLiability) {
                                        rWallet.balanceCents + clawbackDelta
                                    } else {
                                        rWallet.balanceCents - clawbackDelta
                                    }
                                    store.updateWallet(
                                        rWallet.copy(
                                            balanceCents = newRBal,
                                            updatedAtMillis = nowMillis,
                                            syncDirty = true
                                        )
                                    )
                                }
                                updatedExpenseTargetDelta = newRDelta
                            }
                        }

                        store.updateTransaction(
                            targetExpense.copy(
                                refundedAmountCents = updatedRefunded,
                                targetWalletDeltaCents = updatedExpenseTargetDelta,
                                updatedAtMillis = nowMillis,
                                syncRevision = targetExpense.syncRevision + 1,
                                syncDirty = true
                            )
                        )
                        return tx.copy(
                            relatedTransactionId = targetExpense.id,
                            sourceWalletDeltaCents = null,
                            targetWalletDeltaCents = receiveDelta,
                            reimbursedAtMillis = null
                        )
                    }
                    return tx.copy(
                        sourceWalletDeltaCents = null,
                        targetWalletDeltaCents = receiveDelta,
                        reimbursedAtMillis = null
                    )
                }
            }
        }

        private suspend fun restoreRefundOnExpenseStatic(
            store: LedgerTransactionStore,
            expense: TransactionEntity,
            deductCnyCents: Long,
            nowMillis: Long
        ) {
            if (deductCnyCents <= 0L) return
            val prevRefunded = expense.refundedAmountCents.coerceAtLeast(0L)
            val restoredRefunded = (prevRefunded - deductCnyCents).coerceAtLeast(0L)
            var restoredTargetDelta = expense.targetWalletDeltaCents

            // AC-1 ②：删除关联退款恢复父单 refundedAmountCents 时，向已报销入账钱包补回对应净报销差额
            if (expense.type == TransactionType.EXPENSE && expense.reimbursed && restoredRefunded < prevRefunded) {
                val reimburseWalletId = expense.targetWalletId ?: expense.sourceWalletId
                val rWallet = reimburseWalletId?.let { store.getWalletById(it) }
                if (rWallet != null) {
                    val oldRDelta = expense.targetWalletDeltaCents
                        ?: computeNetReimbursementDeltaStatic(expense, rWallet.currency)
                    val prevNetCny = (expense.amountCnyCents - prevRefunded).coerceAtLeast(0L)
                    val newNetCny = (expense.amountCnyCents - restoredRefunded).coerceAtLeast(0L)
                    val newRDelta = when {
                        expense.targetWalletDeltaCents != null && prevNetCny > 0L ->
                            ((oldRDelta.toDouble() * newNetCny.toDouble()) / prevNetCny.toDouble())
                                .roundToLong()
                                .coerceAtLeast(oldRDelta)
                        rWallet.id == expense.sourceWalletId &&
                            expense.sourceWalletDeltaCents != null &&
                            expense.feeCents == 0L &&
                            expense.amountCnyCents > 0L ->
                            ((expense.sourceWalletDeltaCents.toDouble() * newNetCny.toDouble()) / expense.amountCnyCents.toDouble())
                                .roundToLong()
                                .coerceAtLeast(oldRDelta)
                        else ->
                            computeNetReimbursementDeltaStatic(
                                expense.copy(refundedAmountCents = restoredRefunded),
                                rWallet.currency
                            )
                    }
                    val restoreDiff = (newRDelta - oldRDelta).coerceAtLeast(0L)
                    if (restoreDiff > 0L) {
                        val newRBal = if (rWallet.type.isLiability) {
                            rWallet.balanceCents - restoreDiff
                        } else {
                            rWallet.balanceCents + restoreDiff
                        }
                        store.updateWallet(
                            rWallet.copy(
                                balanceCents = newRBal,
                                updatedAtMillis = nowMillis,
                                syncDirty = true
                            )
                        )
                    }
                    restoredTargetDelta = newRDelta
                }
            }

            store.updateTransaction(
                expense.copy(
                    refundedAmountCents = restoredRefunded,
                    targetWalletDeltaCents = restoredTargetDelta,
                    updatedAtMillis = nowMillis,
                    syncRevision = expense.syncRevision + 1,
                    syncDirty = true
                )
            )
        }

        internal suspend fun revertWalletImpactOnStore(
            store: LedgerTransactionStore,
            tx: TransactionEntity,
            nowMillis: Long = System.currentTimeMillis()
        ) {
            val totalOutCents = tx.amountCents + tx.feeCents

            when (tx.type) {
                TransactionType.EXPENSE, TransactionType.LOAN_OUT -> {
                    if (tx.type == TransactionType.EXPENSE && tx.reimbursed) {
                        val reimburseWalletId = tx.targetWalletId ?: tx.sourceWalletId
                        reimburseWalletId?.let { wid ->
                            store.getWalletById(wid)?.let { rWallet ->
                                val rDelta = tx.targetWalletDeltaCents
                                    ?: computeNetReimbursementDeltaStatic(tx, rWallet.currency)
                                val restoredBal = if (rWallet.type.isLiability) rWallet.balanceCents + rDelta else rWallet.balanceCents - rDelta
                                store.updateWallet(rWallet.copy(balanceCents = restoredBal, updatedAtMillis = nowMillis, syncDirty = true))
                            }
                        }
                    }
                    tx.sourceWalletId?.let { walletId ->
                        store.getWalletById(walletId)?.let { wallet ->
                            val delta = tx.sourceWalletDeltaCents
                                ?: convertCentsStatic(totalOutCents, tx.currency, wallet.currency, tx.exchangeRateToCny)
                            val restored = if (wallet.type.isLiability) wallet.balanceCents - delta else wallet.balanceCents + delta
                            store.updateWallet(wallet.copy(balanceCents = restored, updatedAtMillis = nowMillis, syncDirty = true))
                        }
                    }
                }
                TransactionType.INCOME, TransactionType.LOAN_IN -> {
                    val targetId = tx.targetWalletId ?: tx.sourceWalletId
                    targetId?.let { walletId ->
                        store.getWalletById(walletId)?.let { wallet ->
                            val delta = tx.targetWalletDeltaCents
                                ?: convertCentsStatic(tx.amountCents - tx.feeCents, tx.currency, wallet.currency, tx.exchangeRateToCny)
                            val restored = if (wallet.type.isLiability) wallet.balanceCents + delta else wallet.balanceCents - delta
                            store.updateWallet(wallet.copy(balanceCents = restored, updatedAtMillis = nowMillis, syncDirty = true))
                        }
                    }
                }
                TransactionType.TRANSFER -> {
                    tx.sourceWalletId?.let { fromId ->
                        store.getWalletById(fromId)?.let { w ->
                            val delta = tx.sourceWalletDeltaCents
                                ?: convertCentsStatic(totalOutCents, tx.currency, w.currency, tx.exchangeRateToCny)
                            val restored = if (w.type.isLiability) w.balanceCents - delta else w.balanceCents + delta
                            store.updateWallet(w.copy(balanceCents = restored, updatedAtMillis = nowMillis, syncDirty = true))
                        }
                    }
                    tx.targetWalletId?.let { toId ->
                        store.getWalletById(toId)?.let { w ->
                            val delta = tx.targetWalletDeltaCents
                                ?: convertCentsStatic(tx.amountCents, tx.currency, w.currency, tx.exchangeRateToCny)
                            val restored = if (w.type.isLiability) w.balanceCents + delta else w.balanceCents - delta
                            store.updateWallet(w.copy(balanceCents = restored, updatedAtMillis = nowMillis, syncDirty = true))
                        }
                    }
                }
                TransactionType.REPAYMENT -> {
                    tx.sourceWalletId?.let { fromId ->
                        store.getWalletById(fromId)?.let { w ->
                            val delta = tx.sourceWalletDeltaCents
                                ?: convertCentsStatic(totalOutCents, tx.currency, w.currency, tx.exchangeRateToCny)
                            val restored = if (w.type.isLiability) w.balanceCents - delta else w.balanceCents + delta
                            store.updateWallet(w.copy(balanceCents = restored, updatedAtMillis = nowMillis, syncDirty = true))
                        }
                    }
                    tx.targetWalletId?.let { creditId ->
                        store.getWalletById(creditId)?.let { w ->
                            val delta = tx.targetWalletDeltaCents
                                ?: convertCentsStatic(tx.amountCents, tx.currency, w.currency, tx.exchangeRateToCny)
                            val restored = if (w.type.isLiability) w.balanceCents + delta else w.balanceCents - delta
                            store.updateWallet(w.copy(balanceCents = restored, updatedAtMillis = nowMillis, syncDirty = true))
                        }
                    }
                }
                TransactionType.REFUND_OFFSET -> {
                    val receiveWalletId = tx.targetWalletId ?: tx.sourceWalletId
                    receiveWalletId?.let { walletId ->
                        store.getWalletById(walletId)?.let { w ->
                            val delta = tx.targetWalletDeltaCents
                                ?: if (w.currency.equals("CNY", ignoreCase = true) && tx.amountCnyCents > 0L) {
                                    tx.amountCnyCents
                                } else {
                                    convertCentsStatic(tx.amountCents, tx.currency, w.currency, tx.exchangeRateToCny)
                                }
                            val restored = if (w.type.isLiability) w.balanceCents + delta else w.balanceCents - delta
                            store.updateWallet(w.copy(balanceCents = restored, updatedAtMillis = nowMillis, syncDirty = true))
                        }
                    }
                    tx.relatedTransactionId?.let { parentId ->
                        var remainingToRevert = tx.amountCnyCents.coerceAtLeast(0L)
                        store.getTransactionById(parentId)?.let { parent ->
                            val deductParent = minOf(parent.refundedAmountCents.coerceAtLeast(0L), remainingToRevert)
                            if (deductParent > 0L) {
                                restoreRefundOnExpenseStatic(store, parent, deductParent, nowMillis)
                                remainingToRevert -= deductParent
                            }
                        }
                        if (remainingToRevert > 0L) {
                            val queue = ArrayDeque<Long>()
                            val visited = HashSet<Long>()
                            queue.addLast(parentId)
                            visited.add(parentId)
                            while (queue.isNotEmpty() && remainingToRevert > 0L) {
                                val currentParent = queue.removeFirst()
                                val splitChildren = store.getSplitChildrenOfExpense(currentParent)
                                for (child in splitChildren) {
                                    if (DedicatedFundManager.isCompositeMedicalPersonalChild(child)) continue
                                    if (!visited.add(child.id)) continue
                                    val deductChild = minOf(child.refundedAmountCents.coerceAtLeast(0L), remainingToRevert)
                                    if (deductChild > 0L) {
                                        restoreRefundOnExpenseStatic(store, child, deductChild, nowMillis)
                                        remainingToRevert -= deductChild
                                    }
                                    queue.addLast(child.id)
                                }
                            }
                        }
                    }
                }
            }
        }

        private suspend fun findBestMatchingExpenseForRefundStatic(
            store: LedgerTransactionStore,
            refundTx: TransactionEntity
        ): TransactionEntity? {
            val candidates = store.getRecentRefundableExpenses(30)
            val refundKeys = listOf(refundTx.orderKey, refundTx.transactionNo, refundTx.merchantOrderNo)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (refundKeys.isNotEmpty()) {
                candidates.firstOrNull { exp ->
                    val expKeys = listOf(exp.orderKey, exp.transactionNo, exp.merchantOrderNo)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    (exp.amountCnyCents - exp.refundedAmountCents) >= refundTx.amountCnyCents &&
                        refundKeys.any { it in expKeys }
                }?.let { return it }
            }
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

        private fun convertCentsStatic(
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
}
