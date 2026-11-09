package com.moji.ai.data.backup

import android.util.Xml
import com.moji.ai.ai.MerchantMemoryEntry
import com.moji.ai.ai.MerchantMemoryManager
import com.moji.ai.ai.SmartConditionRule
import com.moji.ai.ai.WeightedCategoryClassifier
import com.moji.ai.data.local.RecurringRuleEntity
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.AppearanceConfigManager
import com.moji.ai.data.model.CategoryCatalog
import com.moji.ai.data.model.CategoryDef
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.CurrencyRateManager
import com.moji.ai.data.model.RecurrenceFrequency
import com.moji.ai.data.model.ReminderConfigManager
import com.moji.ai.data.model.TagCatalog
import com.moji.ai.data.model.TagDef
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType
import com.moji.ai.ui.InstallmentPlanItem
import com.moji.ai.ui.QuickBookkeepingTemplate
import com.moji.ai.ui.QuickTemplateHelper
import com.moji.ai.ui.SavingPlanItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.roundToLong

/**
 * BackupSchemaGuard 全字段默认值约束（对标 pennywiseai-tracker）：
 * 所有序列化 DTO 字段均声明安全默认值，确保旧版本备份文件在任何新版本客户端均可无损恢复。
 */
@Serializable
data class BackupWalletDto(
    val id: Long = 0L,
    val syncUuid: String = "",
    val name: String = "未命名账户",
    val type: String = "DIGITAL_WALLET",
    val subTypeLabel: String = "",
    val brandCode: String = "DEFAULT",
    val currency: String = "CNY",
    val balanceCents: Long = 0L,
    val creditLimitCents: Long? = null,
    val cardLast4: String? = null,
    val aliases: String = "",
    val billingDay: Int? = null,
    val repaymentDay: Int? = null,
    val includeInNetWorth: Boolean = true,
    val colorHex: Long = 0xFFE65540,
    val updatedAtMillis: Long = 0L
)

@Serializable
data class BackupTransactionDto(
    val id: Long = 0L,
    val syncUuid: String = "",
    val ledgerName: String = "默认账本",
    val type: String = "EXPENSE",
    val amountCents: Long = 0L,
    val currency: String = "CNY",
    val exchangeRateToCny: Double = 1.0,
    val amountCnyCents: Long = 0L,
    val discountCents: Long = 0L,
    val feeCents: Long = 0L,
    val itemQuantity: Int = 1,
    val quantityDesc: String = "1件",
    val category: String = "餐饮",
    val merchant: String = "",
    val note: String = "",
    val sourceWalletId: Long? = null,
    val targetWalletId: Long? = null,
    val relatedTransactionId: Long? = null,
    val refundedAmountCents: Long = 0L,
    val reimbursable: Boolean = false,
    val reimbursed: Boolean = false,
    val tags: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val capturedAtMillis: Long = 0L,
    val orderKey: String = "",
    val transactionNo: String = "",
    val merchantOrderNo: String = "",
    val paymentMethodRaw: String = "",
    val screenshotPath: String = "",
    val screenshotSha256: String = "",
    val dedupFingerprint: String = "",
    val duplicateSuspected: Boolean = false,
    val captureSource: String = "MANUAL",
    val rawCapturedText: String = "",
    val aiConfidence: Float = 1.0f,
    val updatedAtMillis: Long = 0L,
    val syncRevision: Long = 1L
)

@Serializable
data class BackupRecurringRuleDto(
    val id: Long = 0L,
    val title: String = "",
    val type: String = "EXPENSE",
    val amountCents: Long = 0L,
    val currency: String = "CNY",
    val category: String = "住房",
    val walletId: Long = 1L,
    val targetWalletId: Long? = null,
    val frequency: String = "MONTHLY",
    val dayOfMonthOrWeek: Int = 1,
    val nextTriggerTimeMillis: Long = System.currentTimeMillis(),
    val enabled: Boolean = true
)

@Serializable
data class MoJiFullBackupSnapshot(
    val schemaVersion: Int = 1,
    val appName: String = "MoJi",
    val exportedAtMillis: Long = System.currentTimeMillis(),
    val ledgers: List<String> = listOf("默认账本"),
    val monthlyBudgetCents: Long = 300_000L,
    val wallets: List<BackupWalletDto> = emptyList(),
    val transactions: List<BackupTransactionDto> = emptyList(),
    val recurringRules: List<BackupRecurringRuleDto> = emptyList(),
    val merchantMemories: List<MerchantMemoryEntry> = emptyList(),
    val customExpenseCategories: List<CategoryDef> = emptyList(),
    val customIncomeCategories: List<CategoryDef> = emptyList(),
    val customTags: List<TagDef> = emptyList(),
    val quickTemplates: List<QuickBookkeepingTemplate> = emptyList(),
    val smartRules: List<SmartConditionRule> = emptyList(),
    val savingPlans: List<SavingPlanItem> = emptyList(),
    val installmentPlans: List<InstallmentPlanItem> = emptyList(),
    val customCurrencyRates: Map<String, Double> = emptyMap(),
    val reminderSettingsJson: String = "",
    val appearanceSettingsJson: String = ""
)

data class CsvParsePreviewResult(
    val detectedSourceFormat: String,
    val parsedTransactions: List<TransactionEntity>,
    val skippedNonFinancialRows: Int,
    /** 本次导入涉及的全部账本名称 */
    val discoveredLedgers: List<String> = emptyList(),
    /** 其中当前尚未创建的账本名称（导入时自动新建） */
    val newLedgers: List<String> = emptyList(),
    /** 从资产表或账单「账户1/账户2」中识别出且当前不存在的账户（导入时自动创建） */
    val newWalletsToCreate: List<WalletEntity> = emptyList(),
    /** 从独立资产表行中识别出的已有账户余额/额度校准项 */
    val existingWalletsToUpdate: List<WalletEntity> = emptyList(),
    /** 每笔待导入账单对应的原始付款/转出账户名（用于入库时绑定新创建的钱包 ID） */
    val sourceWalletNameByIndex: Map<Int, String> = emptyMap(),
    /** 每笔待导入账单对应的原始收款/转入账户名（用于入库时绑定新创建的钱包 ID） */
    val targetWalletNameByIndex: Map<Int, String> = emptyMap()
)

/**
 * 全量 JSON 备份恢复 + 微信支付/支付宝/钱迹/iCost CSV 导入导出引擎
 * （对标 pennywiseai-tracker BackupSchemaGuard、Cashbook、huayoupu-ledger 与 double-entry-generator）
 */
object BackupAndCsvTransferEngine {

    private val backupJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun exportFullBackupJson(
        ledgers: List<String>,
        monthlyBudgetCents: Long,
        wallets: List<WalletEntity>,
        transactions: List<TransactionEntity>,
        recurringRules: List<RecurringRuleEntity>,
        merchantMemories: List<MerchantMemoryEntry>,
        customExpenseCategories: List<CategoryDef> = CategoryCatalog.expenseCategories,
        customIncomeCategories: List<CategoryDef> = CategoryCatalog.incomeCategories,
        customTags: List<TagDef> = TagCatalog.tags,
        quickTemplates: List<QuickBookkeepingTemplate> = emptyList(),
        smartRules: List<SmartConditionRule> = emptyList(),
        savingPlans: List<SavingPlanItem> = emptyList(),
        installmentPlans: List<InstallmentPlanItem> = emptyList(),
        customCurrencyRates: Map<String, Double> = CurrencyRateManager.customRates,
        reminderSettingsJson: String = ReminderConfigManager.exportConfigJson(),
        appearanceSettingsJson: String = AppearanceConfigManager.exportConfigJson()
    ): String {
        val snapshot = MoJiFullBackupSnapshot(
            schemaVersion = 1,
            exportedAtMillis = System.currentTimeMillis(),
            ledgers = ledgers.ifEmpty { listOf("默认账本") },
            monthlyBudgetCents = monthlyBudgetCents,
            wallets = wallets.map { w ->
                BackupWalletDto(
                    id = w.id,
                    syncUuid = w.syncUuid,
                    name = w.name,
                    type = w.type.name,
                    subTypeLabel = w.subTypeLabel,
                    brandCode = w.brandCode,
                    currency = w.currency,
                    balanceCents = w.balanceCents,
                    creditLimitCents = w.creditLimitCents,
                    cardLast4 = w.cardLast4,
                    aliases = w.aliases,
                    billingDay = w.billingDay,
                    repaymentDay = w.repaymentDay,
                    includeInNetWorth = w.includeInNetWorth,
                    colorHex = w.colorHex,
                    updatedAtMillis = w.updatedAtMillis
                )
            },
            transactions = transactions.map { tx ->
                BackupTransactionDto(
                    id = tx.id,
                    syncUuid = tx.syncUuid,
                    ledgerName = tx.ledgerName,
                    type = tx.type.name,
                    amountCents = tx.amountCents,
                    currency = tx.currency,
                    exchangeRateToCny = tx.exchangeRateToCny,
                    amountCnyCents = tx.amountCnyCents,
                    discountCents = tx.discountCents,
                    feeCents = tx.feeCents,
                    itemQuantity = tx.itemQuantity,
                    quantityDesc = tx.quantityDesc,
                    category = tx.category,
                    merchant = tx.merchant,
                    note = tx.note,
                    sourceWalletId = tx.sourceWalletId,
                    targetWalletId = tx.targetWalletId,
                    relatedTransactionId = tx.relatedTransactionId,
                    refundedAmountCents = tx.refundedAmountCents,
                    reimbursable = tx.reimbursable,
                    reimbursed = tx.reimbursed,
                    tags = tx.tags,
                    timestamp = tx.timestamp,
                    capturedAtMillis = tx.capturedAtMillis,
                    orderKey = tx.orderKey,
                    transactionNo = tx.transactionNo,
                    merchantOrderNo = tx.merchantOrderNo,
                    paymentMethodRaw = tx.paymentMethodRaw,
                    screenshotPath = tx.screenshotPath,
                    screenshotSha256 = tx.screenshotSha256,
                    dedupFingerprint = tx.dedupFingerprint,
                    duplicateSuspected = tx.duplicateSuspected,
                    captureSource = tx.captureSource,
                    rawCapturedText = tx.rawCapturedText,
                    aiConfidence = tx.aiConfidence,
                    updatedAtMillis = tx.updatedAtMillis,
                    syncRevision = tx.syncRevision
                )
            },
            recurringRules = recurringRules.map { r ->
                BackupRecurringRuleDto(
                    id = r.id,
                    title = r.title,
                    type = r.type.name,
                    amountCents = r.amountCents,
                    currency = r.currency,
                    category = r.category,
                    walletId = r.walletId,
                    targetWalletId = r.targetWalletId,
                    frequency = r.frequency.name,
                    dayOfMonthOrWeek = r.dayOfMonthOrWeek,
                    nextTriggerTimeMillis = r.nextTriggerTimeMillis,
                    enabled = r.enabled
                )
            },
            merchantMemories = merchantMemories,
            customExpenseCategories = customExpenseCategories,
            customIncomeCategories = customIncomeCategories,
            customTags = customTags,
            quickTemplates = QuickTemplateHelper.sanitizeTemplates(quickTemplates),
            smartRules = smartRules,
            savingPlans = savingPlans,
            installmentPlans = installmentPlans,
            customCurrencyRates = CurrencyRateManager.sanitizeRatesMap(customCurrencyRates),
            reminderSettingsJson = reminderSettingsJson,
            appearanceSettingsJson = appearanceSettingsJson
        )
        return backupJson.encodeToString(snapshot)
    }

    fun parseFullBackupJson(rawJson: String): Triple<List<WalletEntity>, List<TransactionEntity>, MoJiFullBackupSnapshot>? {
        val rawSnapshot = runCatching { backupJson.decodeFromString<MoJiFullBackupSnapshot>(rawJson.trim()) }.getOrNull()
            ?: return null
        val snapshot = rawSnapshot.copy(
            quickTemplates = QuickTemplateHelper.sanitizeTemplates(rawSnapshot.quickTemplates),
            customCurrencyRates = CurrencyRateManager.sanitizeRatesMap(rawSnapshot.customCurrencyRates)
        )
        val wallets = snapshot.wallets.map { dto ->
            WalletEntity(
                id = dto.id,
                syncUuid = dto.syncUuid.ifBlank { java.util.UUID.randomUUID().toString() },
                name = dto.name,
                type = runCatching { WalletType.valueOf(dto.type) }.getOrDefault(WalletType.DIGITAL_WALLET),
                subTypeLabel = dto.subTypeLabel,
                brandCode = dto.brandCode,
                currency = dto.currency,
                balanceCents = dto.balanceCents,
                creditLimitCents = dto.creditLimitCents,
                cardLast4 = dto.cardLast4,
                aliases = dto.aliases,
                billingDay = dto.billingDay,
                repaymentDay = dto.repaymentDay,
                includeInNetWorth = dto.includeInNetWorth,
                colorHex = dto.colorHex,
                updatedAtMillis = if (dto.updatedAtMillis > 0L) dto.updatedAtMillis else System.currentTimeMillis()
            )
        }
        val transactions = snapshot.transactions.map { dto ->
            TransactionEntity(
                id = dto.id,
                syncUuid = dto.syncUuid.ifBlank { java.util.UUID.randomUUID().toString() },
                ledgerName = dto.ledgerName,
                type = runCatching { TransactionType.valueOf(dto.type) }.getOrDefault(TransactionType.EXPENSE),
                amountCents = dto.amountCents,
                currency = dto.currency,
                exchangeRateToCny = dto.exchangeRateToCny,
                amountCnyCents = if (dto.amountCnyCents > 0L) dto.amountCnyCents else dto.amountCents,
                discountCents = dto.discountCents,
                feeCents = dto.feeCents,
                itemQuantity = dto.itemQuantity.coerceAtLeast(1),
                quantityDesc = dto.quantityDesc.ifBlank { "${dto.itemQuantity.coerceAtLeast(1)}件" },
                category = dto.category,
                merchant = dto.merchant,
                note = dto.note,
                sourceWalletId = dto.sourceWalletId,
                targetWalletId = dto.targetWalletId,
                relatedTransactionId = dto.relatedTransactionId,
                refundedAmountCents = dto.refundedAmountCents,
                reimbursable = dto.reimbursable,
                reimbursed = dto.reimbursed,
                tags = dto.tags,
                timestamp = dto.timestamp,
                capturedAtMillis = if (dto.capturedAtMillis > 0L) dto.capturedAtMillis else dto.timestamp,
                orderKey = dto.orderKey.ifBlank { dto.transactionNo.ifBlank { dto.merchantOrderNo } },
                transactionNo = dto.transactionNo,
                merchantOrderNo = dto.merchantOrderNo,
                paymentMethodRaw = dto.paymentMethodRaw,
                screenshotPath = dto.screenshotPath,
                screenshotSha256 = dto.screenshotSha256,
                dedupFingerprint = dto.dedupFingerprint,
                duplicateSuspected = dto.duplicateSuspected,
                captureSource = dto.captureSource,
                rawCapturedText = dto.rawCapturedText,
                aiConfidence = dto.aiConfidence,
                updatedAtMillis = if (dto.updatedAtMillis > 0L) dto.updatedAtMillis else dto.timestamp,
                syncRevision = dto.syncRevision
            )
        }
        return Triple(wallets, transactions, snapshot)
    }

    fun toRecurringRuleEntities(dtos: List<BackupRecurringRuleDto>): List<RecurringRuleEntity> {
        return dtos.map { dto ->
            RecurringRuleEntity(
                id = dto.id,
                title = dto.title,
                type = runCatching { TransactionType.valueOf(dto.type) }.getOrDefault(TransactionType.EXPENSE),
                amountCents = dto.amountCents,
                currency = dto.currency,
                category = dto.category,
                walletId = dto.walletId,
                targetWalletId = dto.targetWalletId,
                frequency = runCatching { RecurrenceFrequency.valueOf(dto.frequency) }.getOrDefault(RecurrenceFrequency.MONTHLY),
                dayOfMonthOrWeek = dto.dayOfMonthOrWeek,
                nextTriggerTimeMillis = dto.nextTriggerTimeMillis,
                enabled = dto.enabled
            )
        }
    }

    /**
     * 导出兼容 iCost / 钱迹 / Excel 的全量标准 UTF-8 CSV（包含资产账户表 + 多账本账单流水表）
     */
    fun exportTransactionsToCsv(
        transactions: List<TransactionEntity>,
        wallets: List<WalletEntity>
    ): String {
        val walletMap = wallets.associateBy { it.id }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        val sb = StringBuilder()

        if (wallets.isNotEmpty()) {
            sb.appendLine("# 资产与信用账户列表")
            sb.appendLine("账户名称,账户类型,当前余额,币种,信用额度,卡号后四位,每月还款日,计入净资产")
            for (w in wallets) {
                val balStr = String.format(Locale.US, "%.2f", w.balanceCents / 100.0)
                val limitStr = w.creditLimitCents?.let { String.format(Locale.US, "%.2f", it / 100.0) }.orEmpty()
                val cols = listOf(
                    w.name,
                    w.subTypeLabel.ifBlank { w.type.displayName },
                    balStr,
                    w.currency,
                    limitStr,
                    w.cardLast4.orEmpty(),
                    w.repaymentDay?.toString().orEmpty(),
                    if (w.includeInNetWorth) "是" else "否"
                ).joinToString(",") { escapeCsvField(it) }
                sb.appendLine(cols)
            }
            sb.appendLine()
        }

        sb.appendLine("# 账单明细列表")
        sb.appendLine("日期时间,账本,类型,分类,商户,金额(元),币种,优惠(元),手续费(元),付款/转出账户,收款/转入账户,报销状态,标签,备注,采集来源")
        for (tx in transactions) {
            val dateStr = fmt.format(Date(tx.timestamp))
            val amtStr = String.format(Locale.US, "%.2f", tx.amountCents / 100.0)
            val discStr = String.format(Locale.US, "%.2f", tx.discountCents / 100.0)
            val feeStr = String.format(Locale.US, "%.2f", tx.feeCents / 100.0)
            val srcName = tx.sourceWalletId?.let { walletMap[it]?.name }.orEmpty()
            val dstName = tx.targetWalletId?.let { walletMap[it]?.name }.orEmpty()
            val reimbState = when {
                tx.reimbursed -> "已报销"
                tx.reimbursable -> "待报销"
                else -> "无"
            }
            val cols = listOf(
                dateStr,
                tx.ledgerName,
                tx.type.displayName,
                tx.category,
                tx.merchant,
                amtStr,
                tx.currency,
                discStr,
                feeStr,
                srcName,
                dstName,
                reimbState,
                tx.tags,
                tx.note,
                tx.captureSource
            ).joinToString(",") { escapeCsvField(it) }
            sb.appendLine(cols)
        }
        return sb.toString()
    }

    /**
     * 自动识别并解析【iCost 官方账单/资产 CSV】、【微信支付官方 CSV】、【支付宝官方 CSV】、【钱迹 CSV】与【默记标准 CSV】
     * 支持同时提取并自动创建「账本」与「资产/信用账户」。
     */
    fun parseExternalCsv(
        rawCsv: String,
        defaultLedger: String,
        wallets: List<WalletEntity>,
        merchantMemoryManager: MerchantMemoryManager?,
        existingLedgers: List<String> = listOf("默认账本")
    ): CsvParsePreviewResult {
        val rawLines = rawCsv.lines().map { it.trim().removePrefix("﻿") }.filter { it.isNotEmpty() }
        if (rawLines.isEmpty()) return CsvParsePreviewResult("未知格式", emptyList(), 0)

        val existingLedgerSet = existingLedgers.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val discoveredLedgerSet = linkedSetOf<String>()
        val newWalletsByKey = linkedMapOf<String, WalletEntity>()
        val updatedWalletsById = linkedMapOf<Long, WalletEntity>()

        // 1. 先扫描是否包含独立的「资产账户表」区块（支持单独导入资产表，也支持「资产表 + 账单明细表」双表合一 CSV）
        val assetHeaderIdx = rawLines.indexOfFirst { line ->
            (line.contains("账户名称") || line.contains("资产名称")) &&
                (line.contains("账户类型") || line.contains("资产类型") || line.contains("余额") || line.contains("当前余额") || line.contains("欠款"))
        }

        val billHeaderIdx = rawLines.indexOfFirst { line ->
            (line.contains("交易时间") && (line.contains("交易对方") || line.contains("商品"))) ||
                (line.contains("日期时间") && line.contains("分类") && line.contains("金额")) ||
                (line.contains("时间") && line.contains("分类") && line.contains("金额")) ||
                (line.contains("日期") && (line.contains("一级分类") || line.contains("分类")) && line.contains("金额")) ||
                (line.contains("记录时间") && line.contains("分类") && line.contains("金额"))
        }

        if (assetHeaderIdx >= 0) {
            val assetHeaderCols = splitCsvOrTsvLine(rawLines[assetHeaderIdx]).map { it.trim().removePrefix("﻿") }
            val assetEndIdx = if (billHeaderIdx > assetHeaderIdx) billHeaderIdx else rawLines.size
            for (i in (assetHeaderIdx + 1) until assetEndIdx) {
                val line = rawLines[i]
                if (line.startsWith("#") || line.startsWith("[") || line.contains("账单明细")) continue
                val row = splitCsvOrTsvLine(line)
                if (row.size < 2) continue
                val colMap = assetHeaderCols.mapIndexed { idx, name -> name to row.getOrNull(idx)?.trim().orEmpty() }.toMap()
                val rawName = (colMap["账户名称"] ?: colMap["资产名称"] ?: "").trim()
                if (rawName.isBlank()) continue

                val typeHint = (colMap["账户类型"] ?: colMap["资产类型"] ?: colMap["类型"] ?: "").trim()
                val currencyStr = normalizeCurrencyCode(colMap["币种"] ?: colMap["货币"] ?: "CNY")
                val balYuan = parseCleanMoney(colMap["当前余额"] ?: colMap["余额"] ?: colMap["欠款"] ?: colMap["当前欠款"] ?: colMap["金额"] ?: "0") ?: 0.0
                val limitYuan = parseCleanMoney(colMap["信用额度"] ?: colMap["额度"] ?: "")
                val last4Explicit = (colMap["卡号后四位"] ?: colMap["尾号"] ?: "").filter { it.isDigit() }.take(4).ifBlank { null }
                val repayDay = (colMap["还款日"] ?: colMap["每月还款日"] ?: "").filter { it.isDigit() }.toIntOrNull()?.takeIf { it in 1..31 }
                val includeNetWorthStr = (colMap["计入净资产"] ?: "是").trim()
                val includeInNetWorth = includeNetWorthStr !in setOf("否", "false", "False", "0", "不计入")

                val synthesized = inferWalletEntityFromName(
                    rawAccountName = rawName,
                    currency = currencyStr,
                    explicitTypeHint = typeHint,
                    balanceCents = (kotlin.math.abs(balYuan) * 100.0).roundToLong(),
                    creditLimitCents = limitYuan?.takeIf { it > 0.0 }?.let { (it * 100.0).roundToLong() },
                    explicitLast4 = last4Explicit,
                    repaymentDay = repayDay,
                    includeInNetWorth = includeInNetWorth
                )
                val existingMatch = findStrictExistingWallet(synthesized.name, synthesized.cardLast4, wallets)
                if (existingMatch != null) {
                    updatedWalletsById[existingMatch.id] = existingMatch.copy(
                        balanceCents = synthesized.balanceCents,
                        creditLimitCents = synthesized.creditLimitCents ?: existingMatch.creditLimitCents,
                        cardLast4 = synthesized.cardLast4 ?: existingMatch.cardLast4,
                        repaymentDay = synthesized.repaymentDay ?: existingMatch.repaymentDay,
                        currency = synthesized.currency,
                        includeInNetWorth = synthesized.includeInNetWorth
                    )
                } else {
                    val normKey = normalizeAccountKey(synthesized.name, synthesized.cardLast4)
                    newWalletsByKey[normKey] = synthesized
                }
            }
        }

        if (billHeaderIdx < 0 || billHeaderIdx >= rawLines.lastIndex) {
            if (newWalletsByKey.isNotEmpty() || updatedWalletsById.isNotEmpty()) {
                return CsvParsePreviewResult(
                    detectedSourceFormat = "iCost 资产账户表 CSV",
                    parsedTransactions = emptyList(),
                    skippedNonFinancialRows = 0,
                    newWalletsToCreate = newWalletsByKey.values.toList(),
                    existingWalletsToUpdate = updatedWalletsById.values.toList()
                )
            }
            return CsvParsePreviewResult("未识别到有效表头", emptyList(), rawLines.size)
        }

        val headerCols = splitCsvOrTsvLine(rawLines[billHeaderIdx]).map { it.trim().removePrefix("﻿") }
        val isICostNative = headerCols.any { it == "一级分类" || it == "二级分类" || it == "账户1" || it == "账本" }
        val formatName = when {
            assetHeaderIdx >= 0 -> "iCost 账本+资产+账单全量表格"
            headerCols.any { it == "一级分类" || it == "二级分类" } -> "iCost 官方导出账单表格"
            headerCols.any { it.contains("当前状态") } && headerCols.any { it.contains("支付方式") } -> "微信支付官方账单 CSV"
            headerCols.any { it.contains("交易分类") || it.contains("收/付款方式") } -> "支付宝官方账单 CSV"
            headerCols.any { it.contains("日期时间") && it.contains("采集来源") } -> "默记 / iCost 标准 CSV"
            else -> "iCost / 钱迹通用账单表格"
        }

        val results = mutableListOf<TransactionEntity>()
        val sourceWalletNameByIndex = mutableMapOf<Int, String>()
        val targetWalletNameByIndex = mutableMapOf<Int, String>()
        var skipped = 0

        for (i in (billHeaderIdx + 1) until rawLines.size) {
            val line = rawLines[i]
            if (line.startsWith("#") || line.startsWith("[")) continue
            val row = splitCsvOrTsvLine(line)
            if (row.size < 3) {
                skipped++
                continue
            }
            val colMap = headerCols.mapIndexed { idx, name -> name to row.getOrNull(idx)?.trim().orEmpty() }.toMap()

            // 支持 iCost 拆分的「日期」+「时间」两列，或合并的「日期时间/交易时间/记录时间/日期」
            val datePart = colMap["日期"] ?: ""
            val timePart = colMap["时间"] ?: ""
            val timeStr = when {
                colMap["交易时间"]?.isNotBlank() == true -> colMap["交易时间"]!!
                colMap["日期时间"]?.isNotBlank() == true -> colMap["日期时间"]!!
                colMap["记录时间"]?.isNotBlank() == true -> colMap["记录时间"]!!
                datePart.isNotBlank() && timePart.isNotBlank() -> "$datePart $timePart"
                datePart.isNotBlank() -> datePart
                else -> timePart
            }

            // iCost 导出的 .xlsx 中，支出金额为负数（如 -8、-20.99），这里统一取绝对值
            val rawAmt = parseCleanMoney(colMap["金额(元)"] ?: colMap["金额"] ?: "")
            val cleanAmt = rawAmt?.let { kotlin.math.abs(it) }
            if (cleanAmt == null || cleanAmt <= 0.0) {
                skipped++
                continue
            }
            val amountCents = (cleanAmt * 100.0).roundToLong()

            val statusStr = colMap["当前状态"] ?: colMap["交易状态"] ?: ""
            if (statusStr.contains("已全额退款") || statusStr.contains("交易关闭") || statusStr.contains("解冻成功")) {
                skipped++
                continue
            }

            val direction = colMap["收/支"] ?: colMap["类型"] ?: "支出"
            val merchant = (colMap["交易对方"] ?: colMap["商户"] ?: "").trim()
            val itemDesc = (colMap["商品"] ?: colMap["商品说明"] ?: colMap["备注"] ?: "").trim()

            // 支持 iCost「一级分类」+「二级分类」自动拼合为「餐饮-三餐」格式
            val cat1 = (colMap["一级分类"] ?: colMap["分类"] ?: colMap["交易分类"] ?: "").trim()
            val cat2 = (colMap["二级分类"] ?: colMap["子分类"] ?: "").trim()
            val rawCategory = when {
                cat1.isNotBlank() && cat2.isNotBlank() && cat1 != cat2 && !cat1.contains("-") -> "$cat1-$cat2"
                else -> cat1
            }

            val payMethod = (
                colMap["账户1"] ?: colMap["付款/转出账户"] ?: colMap["转出账户"] ?: colMap["账户"]
                    ?: colMap["支付方式"] ?: colMap["收/付款方式"] ?: ""
                ).trim()
            val targetMethod = (
                colMap["账户2"] ?: colMap["收款/转入账户"] ?: colMap["转入账户"] ?: ""
                ).trim()

            val currencyStr = normalizeCurrencyCode(colMap["币种"] ?: colMap["货币"] ?: "CNY")
            val rate = CurrencyRateManager.getEffectiveRateToCny(currencyStr)
            val amountCnyCents = (amountCents * rate).roundToLong()

            val discountCents = (((parseCleanMoney(colMap["优惠(元)"] ?: colMap["优惠"] ?: colMap["优惠金额"] ?: "0") ?: 0.0).let { kotlin.math.abs(it) }) * 100.0)
                .roundToLong().coerceAtLeast(0L)
            val feeCents = (((parseCleanMoney(colMap["手续费(元)"] ?: colMap["手续费"] ?: "0") ?: 0.0).let { kotlin.math.abs(it) }) * 100.0)
                .roundToLong().coerceAtLeast(0L)
            val refundedCents = (((parseCleanMoney(colMap["退款(元)"] ?: colMap["退款"] ?: "0") ?: 0.0).let { kotlin.math.abs(it) }) * 100.0)
                .roundToLong().coerceIn(0L, amountCnyCents)
            val rawTags = (colMap["标签"] ?: "").trim().removePrefix("#")
            val reimbRaw = (colMap["报销状态"] ?: colMap["报销"] ?: "").trim()
            val reimbursed = reimbRaw in setOf("已报销", "报销完成")
            val reimbursable = reimbursed || reimbRaw in setOf("待报销", "可报销", "是", "true", "True", "1")

            val ledgerCell = (colMap["账本"] ?: colMap["所属账本"] ?: "").trim()
            val finalLedger = ledgerCell.ifBlank { defaultLedger }
            discoveredLedgerSet.add(finalLedger)

            val combinedContext = "$merchant $itemDesc $rawCategory $payMethod $statusStr"

            val txType = if (isICostNative) {
                when {
                    direction.contains("退款") -> TransactionType.REFUND_OFFSET
                    direction.contains("还款") -> TransactionType.REPAYMENT
                    direction.contains("转账") -> TransactionType.TRANSFER
                    direction.contains("借入") -> TransactionType.LOAN_IN
                    direction.contains("借出") -> TransactionType.LOAN_OUT
                    direction.contains("收入") -> TransactionType.INCOME
                    else -> TransactionType.EXPENSE
                }
            } else {
                when {
                    direction.contains("退款") || combinedContext.contains("退款") -> TransactionType.REFUND_OFFSET
                    direction.contains("还款") || combinedContext.contains("信用卡还款") || combinedContext.contains("还花呗") -> TransactionType.REPAYMENT
                    direction.contains("转账") || (direction.contains("不计收支") && targetMethod.isNotBlank()) ||
                        direction.contains("中性") || combinedContext.contains("提现") ||
                        combinedContext.contains("转入余额宝") || combinedContext.contains("零钱通") -> TransactionType.TRANSFER
                    direction.contains("借入") -> TransactionType.LOAN_IN
                    direction.contains("借出") -> TransactionType.LOAN_OUT
                    direction.contains("收入") -> TransactionType.INCOME
                    else -> TransactionType.EXPENSE
                }
            }

            // 匹配或自动注册新账户（对 iCost/钱迹/默记导入中的未知账户自动建卡）
            val strictSourceMatch = if (payMethod.isNotBlank()) {
                matchOrRegisterCsvWallet(
                    rawHint = payMethod,
                    currency = currencyStr,
                    existingWallets = wallets,
                    newWalletsByKey = newWalletsByKey,
                    allowCreateNew = isICostNative || !isNonWalletPaymentNoise(payMethod)
                )
            } else null

            val strictTargetMatch = if (targetMethod.isNotBlank()) {
                matchOrRegisterCsvWallet(
                    rawHint = targetMethod,
                    currency = currencyStr,
                    existingWallets = wallets,
                    newWalletsByKey = newWalletsByKey,
                    allowCreateNew = isICostNative || !isNonWalletPaymentNoise(targetMethod)
                )
            } else null

            val fallbackWallet = if (payMethod.isBlank() && !isICostNative) {
                matchWalletFromCsvHint(formatName, wallets, txType)
            } else null

            val resolvedSourceWallet = strictSourceMatch?.first ?: fallbackWallet
            val resolvedTargetWallet = strictTargetMatch?.first

            // 识别 iCost 自动生成的「修改余额/余额调整」流水（分类为 其他收入/其他支出 且无备注、绑定了具体账户）
            val isICostBalanceAdjustment = isICostNative &&
                (rawCategory == "其他收入" || rawCategory == "其他支出") &&
                itemDesc.isBlank() &&
                merchant.isBlank() &&
                payMethod.isNotBlank()

            val memoryHit = merchantMemoryManager?.matchMemory(combinedContext, merchant)
            val (weightedCat, _) = WeightedCategoryClassifier.classifyExpense(combinedContext, merchant)
            val finalCategory = when {
                isICostBalanceAdjustment -> "平账"
                txType == TransactionType.REFUND_OFFSET -> if (isICostNative && rawCategory.isNotBlank()) rawCategory else "退款入账"
                txType == TransactionType.REPAYMENT -> "信用还款"
                txType == TransactionType.TRANSFER -> "内部转账"
                memoryHit != null -> memoryHit.category
                rawCategory.isNotBlank() && rawCategory !in listOf("商户消费", "其他", "其他支出", "日常消费") -> rawCategory
                txType == TransactionType.INCOME -> if (combinedContext.contains("工资")) "工资" else (rawCategory.ifBlank { "其他收入" })
                else -> weightedCat
            }

            val parsedTimestamp = parseFlexibleTimestamp(timeStr)
            val txIdx = results.size

            // 记录原始账户归一化 Key，以便入库新建账户后绑定真实 walletId
            if (txType == TransactionType.INCOME || txType == TransactionType.REFUND_OFFSET) {
                val targetKey = strictTargetMatch?.second ?: strictSourceMatch?.second
                if (targetKey != null) targetWalletNameByIndex[txIdx] = targetKey
            } else {
                strictSourceMatch?.second?.let { sourceWalletNameByIndex[txIdx] = it }
                strictTargetMatch?.second?.let { targetWalletNameByIndex[txIdx] = it }
            }

            val finalMerchant = when {
                isICostBalanceAdjustment -> "${resolvedSourceWallet?.name ?: payMethod} 余额调整"
                merchant.isNotBlank() -> merchant
                itemDesc.isNotBlank() -> itemDesc.take(22)
                txType == TransactionType.REPAYMENT && targetMethod.isNotBlank() -> "偿还 $targetMethod"
                txType == TransactionType.TRANSFER && targetMethod.isNotBlank() -> "${payMethod.ifBlank { "转出" }} → $targetMethod"
                else -> finalCategory
            }

            val finalNote = if (merchant.isBlank() && itemDesc.length <= 22) "" else itemDesc

            results.add(
                TransactionEntity(
                    ledgerName = finalLedger,
                    type = txType,
                    amountCents = amountCents,
                    currency = currencyStr,
                    exchangeRateToCny = rate,
                    amountCnyCents = amountCnyCents,
                    discountCents = discountCents,
                    feeCents = feeCents,
                    refundedAmountCents = refundedCents,
                    category = finalCategory,
                    merchant = finalMerchant,
                    note = finalNote,
                    sourceWalletId = if (txType == TransactionType.INCOME || txType == TransactionType.REFUND_OFFSET) {
                        null
                    } else {
                        resolvedSourceWallet?.id?.takeIf { it > 0L } ?: memoryHit?.preferredWalletId
                    },
                    targetWalletId = when (txType) {
                        TransactionType.INCOME, TransactionType.REFUND_OFFSET ->
                            (resolvedTargetWallet ?: resolvedSourceWallet)?.id?.takeIf { it > 0L }
                        TransactionType.TRANSFER, TransactionType.REPAYMENT ->
                            resolvedTargetWallet?.id?.takeIf { it > 0L }
                        else -> null
                    },
                    reimbursable = reimbursable,
                    reimbursed = reimbursed,
                    tags = if (isICostBalanceAdjustment && rawTags.isBlank()) "平账" else rawTags,
                    paymentMethodRaw = payMethod.ifBlank { targetMethod },
                    timestamp = parsedTimestamp,
                    captureSource = if (isICostBalanceAdjustment) "BALANCE_ANCHOR" else "CSV_IMPORT",
                    rawCapturedText = "[$formatName] $finalMerchant $itemDesc ¥$cleanAmt (${payMethod.ifBlank { targetMethod }})"
                )
            )
        }

        val discoveredLedgers = discoveredLedgerSet.toList()
        val newLedgers = discoveredLedgers.filter { it !in existingLedgerSet }

        return CsvParsePreviewResult(
            detectedSourceFormat = formatName,
            parsedTransactions = results,
            skippedNonFinancialRows = skipped,
            discoveredLedgers = discoveredLedgers,
            newLedgers = newLedgers,
            newWalletsToCreate = newWalletsByKey.values.toList(),
            existingWalletsToUpdate = updatedWalletsById.values.toList(),
            sourceWalletNameByIndex = sourceWalletNameByIndex,
            targetWalletNameByIndex = targetWalletNameByIndex
        )
    }

    private fun parseCleanMoney(raw: String): Double? {
        val cleaned = raw
            .replace("JP¥", "")
            .replace("HK$", "")
            .replace("NT$", "")
            .replace("S$", "")
            .replace("A$", "")
            .replace("C$", "")
            .replace("¥", "")
            .replace("￥", "")
            .replace("$", "")
            .replace("€", "")
            .replace("£", "")
            .replace("₩", "")
            .replace("฿", "")
            .replace(",", "")
            .replace("元", "")
            .trim()
        return cleaned.toDoubleOrNull()
    }

    private fun normalizeCurrencyCode(raw: String): String {
        val u = raw.trim().uppercase(Locale.ROOT)
        return when {
            u in setOf("CNY", "RMB", "人民币", "¥", "￥") || u.isBlank() -> "CNY"
            u in setOf("USD", "美元", "$") -> "USD"
            u in setOf("HKD", "港币", "港元", "HK$") -> "HKD"
            u in setOf("EUR", "欧元", "€") -> "EUR"
            u in setOf("JPY", "日元", "日币", "JP¥") -> "JPY"
            u in setOf("GBP", "英镑", "£") -> "GBP"
            u in setOf("KRW", "韩元", "韩币", "₩") -> "KRW"
            u in setOf("TWD", "新台币", "台币", "NT$") -> "TWD"
            u in setOf("SGD", "新加坡元", "新币", "S$") -> "SGD"
            u in setOf("AUD", "澳元", "澳币", "A$") -> "AUD"
            u in setOf("CAD", "加元", "加币", "C$") -> "CAD"
            u in setOf("THB", "泰铢", "฿") -> "THB"
            else -> CurrencyCode.fromCode(u).name
        }
    }

    private fun isNonWalletPaymentNoise(raw: String): Boolean {
        val s = raw.trim()
        if (s.isBlank() || s == "/" || s == "-" || s == "无") return true
        return s in setOf("商户消费", "扫二维码付款", "二维码收款", "免密支付", "自动扣款", "在线支付")
    }

    private fun extractAccountNameAndLast4(rawAccountName: String, explicitLast4: String? = null): Pair<String, String?> {
        val trimmed = rawAccountName.trim()
        val parenOrKeywordMatch = Regex("""(?:[（(]\s*(?:尾号)?(\d{4})\s*[)）]|尾号\s*(\d{4}))""").find(trimmed)
        val suffixMatch = Regex("""(?:\s+|(?<=[一-龥A-Za-z]))(\d{4})$""").find(trimmed)
        val extractedLast4 = explicitLast4
            ?: parenOrKeywordMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
            ?: suffixMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }

        val cleanName = trimmed
            .replace(Regex("""[（(]\s*(?:尾号)?\d{4}\s*[)）]"""), "")
            .replace(Regex("""尾号\s*\d{4}"""), "")
            .replace(Regex("""(?:\s+|(?<=[一-龥A-Za-z]))\d{4}$"""), "")
            .trim()
            .ifBlank { trimmed }
        return cleanName to extractedLast4
    }

    fun normalizeAccountKey(name: String, cardLast4: String?): String {
        val (cleanName, extractedTail) = extractAccountNameAndLast4(name, cardLast4)
        val normName = cleanName.lowercase(Locale.ROOT)
        val tail = extractedTail?.trim().orEmpty()
        return if (tail.isNotEmpty()) "${normName}#$tail" else normName
    }

    private fun findStrictExistingWallet(
        rawName: String,
        cardLast4: String?,
        wallets: List<WalletEntity>
    ): WalletEntity? {
        val (cleanName, resolvedLast4) = extractAccountNameAndLast4(rawName, cardLast4)
        if (!resolvedLast4.isNullOrBlank()) {
            wallets.firstOrNull { it.cardLast4 == resolvedLast4 }?.let { return it }
            // 若导入账户带有明确的 4 位尾号（如 5021），仅允许匹配无尾号或同尾号的同名账户，绝不误绑到尾号 8848 的另一张卡
            wallets.firstOrNull {
                (it.name.equals(cleanName, ignoreCase = true) || it.name.equals(rawName, ignoreCase = true)) &&
                    it.cardLast4.isNullOrBlank()
            }?.let { return it }
            return null
        }
        wallets.firstOrNull { it.name.equals(cleanName, ignoreCase = true) || it.name.equals(rawName, ignoreCase = true) }?.let { return it }
        for (w in wallets) {
            val aliases = w.aliases.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
            if (aliases.any { it.equals(cleanName, ignoreCase = true) || it.equals(rawName, ignoreCase = true) }) {
                return w
            }
        }
        if ((cleanName == "微信" || cleanName == "微信支付" || cleanName == "零钱" || cleanName == "微信零钱")) {
            wallets.firstOrNull { it.name.contains("微信零钱") || it.brandCode == "WECHAT" }?.let { return it }
        }
        if ((cleanName == "支付宝" || cleanName == "账户余额" || cleanName == "支付宝余额")) {
            wallets.firstOrNull { it.name.contains("账户余额") || it.name.contains("支付宝") || it.brandCode == "ALIPAY" }?.let { return it }
        }
        if (cleanName == "花呗" || cleanName == "蚂蚁花呗") {
            wallets.firstOrNull { it.name.contains("花呗") || it.brandCode == "HUABEI" }?.let { return it }
        }
        if (cleanName == "京东白条" || cleanName == "白条") {
            wallets.firstOrNull { it.name.contains("白条") || it.brandCode == "JD" }?.let { return it }
        }
        return null
    }

    private fun matchOrRegisterCsvWallet(
        rawHint: String,
        currency: String,
        existingWallets: List<WalletEntity>,
        newWalletsByKey: MutableMap<String, WalletEntity>,
        allowCreateNew: Boolean
    ): Pair<WalletEntity?, String?> {
        val synthesized = inferWalletEntityFromName(rawHint, currency)
        val existing = findStrictExistingWallet(synthesized.name, synthesized.cardLast4, existingWallets)
        if (existing != null) {
            return existing to normalizeAccountKey(existing.name, existing.cardLast4)
        }
        val normKey = normalizeAccountKey(synthesized.name, synthesized.cardLast4)
        newWalletsByKey[normKey]?.let { alreadyQueued ->
            return alreadyQueued to normKey
        }
        if (allowCreateNew) {
            newWalletsByKey[normKey] = synthesized
            return synthesized to normKey
        }
        return null to null
    }

    /**
     * 根据 iCost 账户名称（如「招商银行储蓄卡 5021」「民生银行储蓄卡3625」「建设银行储蓄卡(6688)」「度小满」「借呗」）
     * 自动推断品牌图标、账户类型、尾号、信用额度与子标签。
     */
    fun inferWalletEntityFromName(
        rawAccountName: String,
        currency: String = "CNY",
        explicitTypeHint: String = "",
        balanceCents: Long = 0L,
        creditLimitCents: Long? = null,
        explicitLast4: String? = null,
        repaymentDay: Int? = null,
        includeInNetWorth: Boolean = true
    ): WalletEntity {
        val (cleanName, extractedLast4) = extractAccountNameAndLast4(rawAccountName, explicitLast4)

        val combined = "$cleanName $explicitTypeHint"
        val isCredit = combined.contains("信用卡") || combined.contains("贷记卡") ||
            combined.contains("e 招贷") || combined.contains("e招贷") ||
            combined.contains("花呗") || combined.contains("借呗") ||
            combined.contains("白条") || combined.contains("金条") ||
            combined.contains("微粒贷") || combined.contains("月付") ||
            combined.contains("度小满") || combined.contains("分期乐") ||
            combined.contains("招联") || combined.contains("放心借") ||
            combined.contains("省呗") || combined.contains("安逸花") ||
            combined.contains("信用账户") || combined.contains("负债")

        val (brandCode, defaultSubLabel, defaultWalletType) = when {
            cleanName.contains("余额宝") -> Triple("YUEBAO", "余额宝", WalletType.DIGITAL_WALLET)
            cleanName.contains("花呗") -> Triple("HUABEI", "蚂蚁花呗", WalletType.CREDIT)
            cleanName.contains("借呗") -> Triple("JIEBEI", "借呗", WalletType.CREDIT)
            cleanName.contains("度小满") -> Triple("OTHER_CREDIT", "其他信用", WalletType.CREDIT)
            cleanName.contains("白条") -> Triple("JD", "京东白条", WalletType.CREDIT)
            cleanName.contains("金条") -> Triple("JINTIAO", "京东金条", WalletType.CREDIT)
            cleanName.contains("微粒贷") -> Triple("WEILIDAI", "微粒贷", WalletType.CREDIT)
            cleanName.contains("e 招贷") || cleanName.contains("e招贷") -> Triple("CMB", "信用卡", WalletType.CREDIT)
            cleanName.contains("美团月付") -> Triple("MEITUAN_MONTHLY", "美团月付", WalletType.CREDIT)
            cleanName.contains("美团") -> Triple("MEITUAN", "美团支付", WalletType.DIGITAL_WALLET)
            cleanName.contains("零钱通") -> Triple("WECHAT_LQT", "零钱通", WalletType.DIGITAL_WALLET)
            cleanName.contains("微信") || cleanName.contains("零钱") -> Triple("WECHAT", "微信钱包", WalletType.DIGITAL_WALLET)
            cleanName.contains("支付宝") || cleanName.contains("账户余额") -> Triple("ALIPAY", "支付宝", WalletType.DIGITAL_WALLET)
            cleanName.contains("云闪付") -> Triple("UNIONPAY", "云闪付", WalletType.DIGITAL_WALLET)
            cleanName.contains("抖音") -> Triple("DOUYIN", "抖音支付", WalletType.DIGITAL_WALLET)
            cleanName.contains("QQ") -> Triple("QQ", "QQ钱包", WalletType.DIGITAL_WALLET)
            cleanName.contains("招商") || cleanName.contains("招行") -> Triple("CMB", if (isCredit) "信用卡" else "储蓄卡", if (isCredit) WalletType.CREDIT else WalletType.DEBIT_BANK)
            cleanName.contains("工商") || cleanName.contains("工行") -> Triple("ICBC", if (isCredit) "信用卡" else "储蓄卡", if (isCredit) WalletType.CREDIT else WalletType.DEBIT_BANK)
            cleanName.contains("建设") || cleanName.contains("建行") -> Triple("CCB", if (isCredit) "信用卡" else "储蓄卡", if (isCredit) WalletType.CREDIT else WalletType.DEBIT_BANK)
            cleanName.contains("农业") || cleanName.contains("农行") -> Triple("ABC", if (isCredit) "信用卡" else "储蓄卡", if (isCredit) WalletType.CREDIT else WalletType.DEBIT_BANK)
            cleanName.contains("中国银行") || cleanName.contains("中行") -> Triple("BOC", if (isCredit) "信用卡" else "储蓄卡", if (isCredit) WalletType.CREDIT else WalletType.DEBIT_BANK)
            cleanName.contains("交通") || cleanName.contains("交行") -> Triple("BOCOM", if (isCredit) "信用卡" else "储蓄卡", if (isCredit) WalletType.CREDIT else WalletType.DEBIT_BANK)
            cleanName.contains("民生") -> Triple("CMBC", if (isCredit) "信用卡" else "储蓄卡", if (isCredit) WalletType.CREDIT else WalletType.DEBIT_BANK)
            cleanName.contains("邮储") || cleanName.contains("邮政") -> Triple("PSBC", if (isCredit) "信用卡" else "储蓄卡", if (isCredit) WalletType.CREDIT else WalletType.DEBIT_BANK)
            cleanName.contains("浦发") -> Triple("SPDB", if (isCredit) "信用卡" else "储蓄卡", if (isCredit) WalletType.CREDIT else WalletType.DEBIT_BANK)
            cleanName.contains("兴业") -> Triple("CIB", if (isCredit) "信用卡" else "储蓄卡", if (isCredit) WalletType.CREDIT else WalletType.DEBIT_BANK)
            cleanName.contains("中信") -> Triple("CITIC", if (isCredit) "信用卡" else "储蓄卡", if (isCredit) WalletType.CREDIT else WalletType.DEBIT_BANK)
            cleanName.contains("光大") -> Triple("CEB", if (isCredit) "信用卡" else "储蓄卡", if (isCredit) WalletType.CREDIT else WalletType.DEBIT_BANK)
            cleanName.contains("平安") -> Triple("PAB", if (isCredit) "信用卡" else "储蓄卡", if (isCredit) WalletType.CREDIT else WalletType.DEBIT_BANK)
            cleanName.contains("广发") -> Triple("CGB", if (isCredit) "信用卡" else "储蓄卡", if (isCredit) WalletType.CREDIT else WalletType.DEBIT_BANK)
            cleanName.contains("公积金") -> Triple("PROVIDENT", "住房公积金", WalletType.CASH_OTHER)
            cleanName.contains("医保") -> Triple("MEDICAL_CARD", "医保账户", WalletType.CASH_OTHER)
            cleanName.contains("公交") || cleanName.contains("地铁") || cleanName.contains("交通卡") -> Triple("TRANSIT_CARD", "交通卡", WalletType.CASH_OTHER)
            cleanName.contains("饭卡") || cleanName.contains("校园卡") || cleanName.contains("餐卡") -> Triple("MEAL_CARD", "校园/餐卡", WalletType.CASH_OTHER)
            cleanName.contains("会员") || cleanName.contains("储值") -> Triple("MEMBER_CARD", "会员卡", WalletType.CASH_OTHER)
            cleanName.contains("基金") -> Triple("FUND", "基金理财", WalletType.CASH_OTHER)
            cleanName.contains("股票") || cleanName.contains("证券") -> Triple("STOCK", "股票证券", WalletType.CASH_OTHER)
            cleanName.contains("存折") || cleanName.contains("定期") -> Triple("SAVING", "定期存款", WalletType.DEBIT_BANK)
            cleanName.contains("现金") -> Triple("CASH", "现金", WalletType.CASH_OTHER)
            isCredit -> Triple("CREDIT_CARD", "信用卡", WalletType.CREDIT)
            combined.contains("银行") || combined.contains("储蓄卡") || combined.contains("借记卡") -> Triple("DEBIT_CARD", "储蓄卡", WalletType.DEBIT_BANK)
            else -> Triple("ALIPAY", "资金账户", WalletType.DIGITAL_WALLET)
        }

        val finalType = when {
            isCredit -> WalletType.CREDIT
            explicitTypeHint.contains("储蓄") || explicitTypeHint.contains("银行") -> WalletType.DEBIT_BANK
            explicitTypeHint.contains("现金") || explicitTypeHint.contains("理财") -> WalletType.CASH_OTHER
            else -> defaultWalletType
        }

        return WalletEntity(
            id = 0L,
            name = cleanName,
            type = finalType,
            subTypeLabel = explicitTypeHint.ifBlank { defaultSubLabel },
            brandCode = brandCode,
            currency = currency,
            balanceCents = balanceCents,
            creditLimitCents = if (finalType == WalletType.CREDIT) (creditLimitCents ?: 20_000_00L) else null,
            cardLast4 = extractedLast4,
            aliases = listOfNotNull(cleanName, extractedLast4).distinct().joinToString(","),
            repaymentDay = if (finalType == WalletType.CREDIT) (repaymentDay ?: 15) else null,
            includeInNetWorth = includeInNetWorth
        )
    }

    /**
     * 自动识别 `.xlsx` (Excel OpenXML ZIP) 或 `.csv` / `.tsv` 字节流（UTF-8 / GB18030）并转换为标准 CSV 文本。
     * 解决 iCost 默认导出 `.xlsx` 文件直接读取时出现 PK 二进制乱码的问题。
     */
    fun extractTextOrXlsxToCsv(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return ""
        // 1. 检测是否为 .xlsx (PK\x03\x04 ZIP 归档)
        if (bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() &&
            bytes[3] == 0x04.toByte()
        ) {
            return parseXlsxBytesToCsv(bytes)
        }
        // 2. 普通文本 CSV / TSV：自动识别 UTF-8 BOM / UTF-8 / GB18030
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val utf8Decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val utf8Attempt = runCatching {
            utf8Decoder.decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        return utf8Attempt ?: String(bytes, Charset.forName("GB18030"))
    }

    private fun parseXlsxBytesToCsv(bytes: ByteArray): String? {
        return runCatching {
            val zipEntries = mutableMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name
                        if (name == "xl/sharedStrings.xml" ||
                            (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"))
                        ) {
                            zipEntries[name] = zis.readBytes()
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            val sharedStrings = mutableListOf<String>()
            zipEntries["xl/sharedStrings.xml"]?.let { ssBytes ->
                val parser = Xml.newPullParser()
                parser.setInput(ByteArrayInputStream(ssBytes), "UTF-8")
                var event = parser.eventType
                var inSi = false
                val currentSi = StringBuilder()
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            val tag = parser.name
                            if (tag == "si" || tag.endsWith(":si")) {
                                inSi = true
                                currentSi.clear()
                            } else if (inSi && (tag == "t" || tag.endsWith(":t"))) {
                                currentSi.append(parser.nextText())
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            val tag = parser.name
                            if (tag == "si" || tag.endsWith(":si")) {
                                sharedStrings.add(currentSi.toString())
                                inSi = false
                            }
                        }
                    }
                    event = parser.next()
                }
            }

            val sheetNames = zipEntries.keys
                .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
                .sorted()
            if (sheetNames.isEmpty()) return null

            val csvBuilder = StringBuilder()
            for (sheetName in sheetNames) {
                val sheetBytes = zipEntries[sheetName] ?: continue
                val parser = Xml.newPullParser()
                parser.setInput(ByteArrayInputStream(sheetBytes), "UTF-8")
                var event = parser.eventType
                var currentRowCells = mutableMapOf<Int, String>()
                var maxColIdx = -1
                var currentColIdx = 0
                var currentCellType = ""
                val cellText = StringBuilder()
                var inCell = false

                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            val tag = parser.name
                            when {
                                tag == "row" || tag.endsWith(":row") -> {
                                    currentRowCells = mutableMapOf()
                                    maxColIdx = -1
                                    currentColIdx = 0
                                }
                                tag == "c" || tag.endsWith(":c") -> {
                                    inCell = true
                                    cellText.clear()
                                    val ref = parser.getAttributeValue(null, "r").orEmpty()
                                    currentCellType = parser.getAttributeValue(null, "t").orEmpty()
                                    val parsedCol = parseExcelColIndex(ref)
                                    if (parsedCol >= 0) {
                                        currentColIdx = parsedCol
                                    }
                                }
                                inCell && (tag == "v" || tag.endsWith(":v") || tag == "t" || tag.endsWith(":t")) -> {
                                    cellText.append(parser.nextText())
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            val tag = parser.name
                            when {
                                tag == "c" || tag.endsWith(":c") -> {
                                    val rawVal = cellText.toString()
                                    val resolvedVal = if (currentCellType == "s") {
                                        val idx = rawVal.trim().toIntOrNull()
                                        if (idx != null && idx in sharedStrings.indices) sharedStrings[idx] else rawVal
                                    } else {
                                        rawVal
                                    }
                                    if (resolvedVal.isNotEmpty()) {
                                        currentRowCells[currentColIdx] = resolvedVal
                                        if (currentColIdx > maxColIdx) maxColIdx = currentColIdx
                                    }
                                    currentColIdx++
                                    inCell = false
                                }
                                tag == "row" || tag.endsWith(":row") -> {
                                    if (maxColIdx >= 0 && currentRowCells.values.any { it.isNotBlank() }) {
                                        val lineCols = (0..maxColIdx).map { col ->
                                            escapeCsvField(currentRowCells[col].orEmpty())
                                        }
                                        csvBuilder.appendLine(lineCols.joinToString(","))
                                    }
                                }
                            }
                        }
                    }
                    event = parser.next()
                }
                csvBuilder.appendLine()
            }
            csvBuilder.toString().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun parseExcelColIndex(cellRef: String): Int {
        var col = 0
        var hasLetters = false
        for (ch in cellRef) {
            if (ch in 'A'..'Z') {
                col = col * 26 + (ch - 'A' + 1)
                hasLetters = true
            } else if (ch in 'a'..'z') {
                col = col * 26 + (ch - 'a' + 1)
                hasLetters = true
            } else {
                break
            }
        }
        return if (hasLetters) col - 1 else -1
    }

    private fun matchWalletFromCsvHint(
        hint: String,
        wallets: List<WalletEntity>,
        txType: TransactionType
    ): WalletEntity? {
        for (w in wallets) {
            if (!w.cardLast4.isNullOrBlank() && hint.contains(w.cardLast4)) return w
        }
        for (w in wallets) {
            val aliases = listOf(w.name) + w.aliases.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
            if (aliases.any { hint.contains(it, ignoreCase = true) }) return w
        }
        if (hint.contains("微信") || hint.contains("零钱")) {
            return wallets.firstOrNull { it.name.contains("微信") }
        }
        if (hint.contains("支付宝") || hint.contains("余额") || hint.contains("花呗")) {
            if (hint.contains("花呗")) return wallets.firstOrNull { it.name.contains("花呗") }
            return wallets.firstOrNull { it.name.contains("余额") || it.name.contains("支付宝") }
        }
        return wallets.firstOrNull { !it.type.isLiability } ?: wallets.firstOrNull()
    }

    private fun parseFlexibleTimestamp(timeStr: String): Long {
        val cleaned = timeStr.trim()
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy.MM.dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm",
            "yyyy.MM.dd HH:mm",
            "yyyy年MM月dd日 HH:mm:ss",
            "yyyy年MM月dd日 HH:mm",
            "yyyy-MM-dd",
            "yyyy/MM/dd",
            "yyyy.MM.dd",
            "yyyy年MM月dd日"
        )
        for (p in patterns) {
            runCatching {
                val sdf = SimpleDateFormat(p, Locale.CHINA)
                sdf.parse(cleaned)?.time?.let { return it }
            }
        }
        return System.currentTimeMillis()
    }

    private fun escapeCsvField(value: String): String {
        val cleaned = value.replace("\n", " ").replace("\r", " ")
        return if (cleaned.contains(",") || cleaned.contains("\"")) {
            "\"${cleaned.replace("\"", "\"\"")}\""
        } else {
            cleaned
        }
    }

    private fun splitCsvOrTsvLine(line: String): List<String> {
        val delimiter = if (!line.contains(",") && line.contains("\t")) '\t' else ','
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == delimiter && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    const val SAMPLE_ICOST_FULL_CSV = """# iCost 资产账户与多账本全量迁移示例
账户名称,账户类型,当前余额,币种,信用额度,卡号后四位,每月还款日,计入净资产
建设银行储蓄卡(6688),储蓄卡,28650.00,CNY,,6688,,是
中信银行信用卡(8821),信用卡,3420.50,CNY,50000.00,8821,18,是
余额宝,网络账户,15200.00,CNY,,,,是

日期,时间,类型,金额,货币,一级分类,二级分类,账户1,账户2,备注,标签,账本,报销,优惠,手续费
2026-11-06,08:30:00,支出,16.50,CNY,餐饮,早餐,微信零钱,,Manner冰美式+可颂,早咖,默认账本,,3.50,0.00
2026-11-06,12:20:00,支出,186.00,CNY,购物,超市,建设银行储蓄卡(6688),,盒马鲜生家庭周采购,生鲜,家庭生活账本,,15.00,0.00
2026-11-06,15:40:00,支出,1280.00,CNY,旅行,酒店,中信银行信用卡(8821),,京都四条河原町酒店定金,关西行,旅行账本,待报销,120.00,0.00
2026-11-06,19:00:00,转账,2000.00,CNY,转账,内部划转,建设银行储蓄卡(6688),余额宝,每月定投转入余额宝,理财,默认账本,,0.00,0.00"""

    const val SAMPLE_WECHAT_CSV = """微信支付账单明细
交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
2026-11-06 08:35:12,商户消费,瑞幸咖啡,生椰拿铁大杯,支出,¥9.90,微信零钱,支付成功,42000021,M001,/
2026-11-06 12:18:40,商户消费,美团,美团外卖-老乡鸡套餐,支出,¥28.50,招商银行信用卡(3354),支付成功,42000022,M002,/
2026-11-06 18:45:05,商户消费,滴滴出行,快车行程费,支出,¥21.60,微信零钱,支付成功,42000023,M003,/"""

    const val SAMPLE_ALIPAY_CSV = """支付宝交易记录明细查询
交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号
2026-11-06 10:15:00,日用百货,盒马鲜生,hema@alipay.com,盒马鲜生生鲜配送,支出,46.80,账户余额,交易成功,2026110601
2026-11-06 15:20:30,医疗健康,美团买药,meituan@alipay.com,999感冒灵颗粒,支出,19.80,花呗,交易成功,2026110602"""

    const val SAMPLE_QIANJI_CSV = """时间,分类,类型,金额,账户1,账户2,备注,商户,账本
2026-11-06 09:10:00,餐饮-奶茶,支出,18.00,微信零钱,,霸王茶姬伯牙绝弦,霸王茶姬,默认账本
2026-11-06 20:00:00,购物-超市,支出,168.00,招商银行信用卡(3354),,山姆采购牛排与水果,山姆会员商店,家庭生活账本"""
}
