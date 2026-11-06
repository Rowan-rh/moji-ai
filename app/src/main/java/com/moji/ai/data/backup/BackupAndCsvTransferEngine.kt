package com.moji.ai.data.backup

import com.moji.ai.ai.MerchantMemoryEntry
import com.moji.ai.ai.MerchantMemoryManager
import com.moji.ai.ai.WeightedCategoryClassifier
import com.moji.ai.data.local.RecurringRuleEntity
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.RecurrenceFrequency
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/**
 * BackupSchemaGuard 全字段默认值约束（对标 pennywiseai-tracker）：
 * 所有序列化 DTO 字段均声明安全默认值，确保旧版本备份文件在任何新版本客户端均可无损恢复。
 */
@Serializable
data class BackupWalletDto(
    val id: Long = 0L,
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
    val colorHex: Long = 0xFFE65540
)

@Serializable
data class BackupTransactionDto(
    val id: Long = 0L,
    val ledgerName: String = "默认账本",
    val type: String = "EXPENSE",
    val amountCents: Long = 0L,
    val currency: String = "CNY",
    val exchangeRateToCny: Double = 1.0,
    val amountCnyCents: Long = 0L,
    val discountCents: Long = 0L,
    val feeCents: Long = 0L,
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
    val captureSource: String = "MANUAL",
    val rawCapturedText: String = "",
    val aiConfidence: Float = 1.0f
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
    val merchantMemories: List<MerchantMemoryEntry> = emptyList()
)

data class CsvParsePreviewResult(
    val detectedSourceFormat: String,
    val parsedTransactions: List<TransactionEntity>,
    val skippedNonFinancialRows: Int
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
        merchantMemories: List<MerchantMemoryEntry>
    ): String {
        val snapshot = MoJiFullBackupSnapshot(
            schemaVersion = 1,
            exportedAtMillis = System.currentTimeMillis(),
            ledgers = ledgers.ifEmpty { listOf("默认账本") },
            monthlyBudgetCents = monthlyBudgetCents,
            wallets = wallets.map { w ->
                BackupWalletDto(
                    id = w.id,
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
                    colorHex = w.colorHex
                )
            },
            transactions = transactions.map { tx ->
                BackupTransactionDto(
                    id = tx.id,
                    ledgerName = tx.ledgerName,
                    type = tx.type.name,
                    amountCents = tx.amountCents,
                    currency = tx.currency,
                    exchangeRateToCny = tx.exchangeRateToCny,
                    amountCnyCents = tx.amountCnyCents,
                    discountCents = tx.discountCents,
                    feeCents = tx.feeCents,
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
                    captureSource = tx.captureSource,
                    rawCapturedText = tx.rawCapturedText,
                    aiConfidence = tx.aiConfidence
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
            merchantMemories = merchantMemories
        )
        return backupJson.encodeToString(snapshot)
    }

    fun parseFullBackupJson(rawJson: String): Triple<List<WalletEntity>, List<TransactionEntity>, MoJiFullBackupSnapshot>? {
        val snapshot = runCatching { backupJson.decodeFromString<MoJiFullBackupSnapshot>(rawJson.trim()) }.getOrNull()
            ?: return null
        val wallets = snapshot.wallets.map { dto ->
            WalletEntity(
                id = dto.id,
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
                colorHex = dto.colorHex
            )
        }
        val transactions = snapshot.transactions.map { dto ->
            TransactionEntity(
                id = dto.id,
                ledgerName = dto.ledgerName,
                type = runCatching { TransactionType.valueOf(dto.type) }.getOrDefault(TransactionType.EXPENSE),
                amountCents = dto.amountCents,
                currency = dto.currency,
                exchangeRateToCny = dto.exchangeRateToCny,
                amountCnyCents = if (dto.amountCnyCents > 0L) dto.amountCnyCents else dto.amountCents,
                discountCents = dto.discountCents,
                feeCents = dto.feeCents,
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
                captureSource = dto.captureSource,
                rawCapturedText = dto.rawCapturedText,
                aiConfidence = dto.aiConfidence
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
     * 导出兼容 iCost / 钱迹 / Excel 的标准 UTF-8 CSV
     */
    fun exportTransactionsToCsv(
        transactions: List<TransactionEntity>,
        wallets: List<WalletEntity>
    ): String {
        val walletMap = wallets.associateBy { it.id }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        val sb = StringBuilder()
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
     * 自动识别并解析【微信支付官方 CSV】、【支付宝官方 CSV】、【钱迹 / iCost CSV】与【默记标准 CSV】
     */
    fun parseExternalCsv(
        rawCsv: String,
        defaultLedger: String,
        wallets: List<WalletEntity>,
        merchantMemoryManager: MerchantMemoryManager?
    ): CsvParsePreviewResult {
        val lines = rawCsv.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return CsvParsePreviewResult("未知格式", emptyList(), 0)

        // 寻找表头行（微信/支付宝 CSV 前几行通常是账号说明信息）
        val headerIdx = lines.indexOfFirst { line ->
            (line.contains("交易时间") && (line.contains("交易对方") || line.contains("商品"))) ||
                (line.contains("日期时间") && line.contains("分类") && line.contains("金额")) ||
                (line.contains("时间") && line.contains("分类") && line.contains("金额"))
        }

        if (headerIdx < 0 || headerIdx >= lines.lastIndex) {
            return CsvParsePreviewResult("未识别到有效表头", emptyList(), lines.size)
        }

        val headerCols = splitCsvLine(lines[headerIdx]).map { it.trim().removePrefix("﻿") }
        val formatName = when {
            headerCols.any { it.contains("当前状态") } && headerCols.any { it.contains("支付方式") } -> "微信支付官方账单 CSV"
            headerCols.any { it.contains("交易分类") || it.contains("收/付款方式") } -> "支付宝官方账单 CSV"
            headerCols.any { it.contains("日期时间") && it.contains("采集来源") } -> "默记 / iCost 标准 CSV"
            else -> "钱迹 / iCost 通用账单 CSV"
        }

        val results = mutableListOf<TransactionEntity>()
        var skipped = 0

        for (i in (headerIdx + 1) until lines.size) {
            val row = splitCsvLine(lines[i])
            if (row.size < 4) {
                skipped++
                continue
            }
            val colMap = headerCols.mapIndexed { idx, name -> name to row.getOrNull(idx)?.trim().orEmpty() }.toMap()

            val timeStr = colMap["交易时间"] ?: colMap["日期时间"] ?: colMap["时间"] ?: colMap["日期"] ?: ""
            val rawAmtStr = colMap["金额(元)"] ?: colMap["金额"] ?: ""
            val cleanAmt = rawAmtStr.replace("¥", "").replace("￥", "").replace(",", "").trim().toDoubleOrNull()
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
            val rawCategory = (colMap["分类"] ?: colMap["交易分类"] ?: "").trim()
            val payMethod = (colMap["支付方式"] ?: colMap["收/付款方式"] ?: colMap["付款/转出账户"] ?: colMap["账户1"] ?: "").trim()
            val targetMethod = (colMap["收款/转入账户"] ?: colMap["账户2"] ?: "").trim()

            val combinedContext = "$merchant $itemDesc $rawCategory $payMethod $statusStr"

            val txType = when {
                direction.contains("退款") || combinedContext.contains("退款") -> TransactionType.REFUND_OFFSET
                direction.contains("还款") || combinedContext.contains("信用卡还款") || combinedContext.contains("还花呗") -> TransactionType.REPAYMENT
                direction.contains("转账") || direction.contains("不计收支") || direction.contains("中性") ||
                    combinedContext.contains("提现") || combinedContext.contains("转入余额宝") || combinedContext.contains("零钱通") -> TransactionType.TRANSFER
                direction.contains("收入") -> TransactionType.INCOME
                else -> TransactionType.EXPENSE
            }

            val matchedWallet = matchWalletFromCsvHint(payMethod.ifBlank { formatName }, wallets, txType)
            val matchedTargetWallet = if (targetMethod.isNotBlank()) {
                matchWalletFromCsvHint(targetMethod, wallets, txType)
            } else null

            val memoryHit = merchantMemoryManager?.matchMemory(combinedContext, merchant)
            val (weightedCat, _) = WeightedCategoryClassifier.classifyExpense(combinedContext, merchant)
            val finalCategory = when {
                txType == TransactionType.REFUND_OFFSET -> "退款入账"
                txType == TransactionType.REPAYMENT -> "信用还款"
                txType == TransactionType.TRANSFER -> "内部转账"
                memoryHit != null -> memoryHit.category
                rawCategory.isNotBlank() && rawCategory !in listOf("商户消费", "其他", "日常消费") -> rawCategory
                txType == TransactionType.INCOME -> if (combinedContext.contains("工资")) "工资" else "其他收入"
                else -> weightedCat
            }

            val parsedTimestamp = parseFlexibleTimestamp(timeStr)

            results.add(
                TransactionEntity(
                    ledgerName = colMap["账本"]?.ifBlank { defaultLedger } ?: defaultLedger,
                    type = txType,
                    amountCents = amountCents,
                    currency = colMap["币种"]?.ifBlank { "CNY" } ?: "CNY",
                    exchangeRateToCny = CurrencyCode.fromCode(colMap["币种"] ?: "CNY").defaultRateToCny,
                    amountCnyCents = amountCents,
                    category = finalCategory,
                    merchant = merchant.ifBlank { itemDesc.take(16).ifBlank { finalCategory } },
                    note = itemDesc,
                    sourceWalletId = if (txType == TransactionType.INCOME || txType == TransactionType.REFUND_OFFSET) null else (matchedWallet?.id ?: memoryHit?.preferredWalletId),
                    targetWalletId = when (txType) {
                        TransactionType.INCOME, TransactionType.REFUND_OFFSET -> matchedTargetWallet?.id ?: matchedWallet?.id
                        TransactionType.TRANSFER, TransactionType.REPAYMENT -> matchedTargetWallet?.id
                        else -> null
                    },
                    timestamp = parsedTimestamp,
                    captureSource = "CSV_IMPORT",
                    rawCapturedText = "[$formatName] $merchant $itemDesc ¥$cleanAmt ($payMethod)"
                )
            )
        }

        return CsvParsePreviewResult(
            detectedSourceFormat = formatName,
            parsedTransactions = results,
            skippedNonFinancialRows = skipped
        )
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
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm",
            "yyyy-MM-dd"
        )
        for (p in patterns) {
            runCatching {
                val sdf = SimpleDateFormat(p, Locale.CHINA)
                sdf.parse(timeStr.trim())?.time?.let { return it }
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

    private fun splitCsvLine(line: String): List<String> {
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
                c == ',' && !inQuotes -> {
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

    const val SAMPLE_WECHAT_CSV = """微信支付账单明细
交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
2026-11-06 08:35:12,商户消费,瑞幸咖啡,生椰拿铁大杯,支出,¥9.90,微信零钱,支付成功,42000021,M001,/
2026-11-06 12:18:40,商户消费,美团,美团外卖-老乡鸡套餐,支出,¥28.50,招商银行信用卡(3354),支付成功,42000022,M002,/
2026-11-06 18:45:05,商户消费,滴滴出行,快车行程费,支出,¥21.60,微信零钱,支付成功,42000023,M003,/"""

    const val SAMPLE_ALIPAY_CSV = """支付宝交易记录明细查询
交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号
2026-11-06 10:15:00,日用百货,盒马鲜生,hema@alipay.com,盒马鲜生生鲜配送,支出,46.80,账户余额,交易成功,2026110601
2026-11-06 15:20:30,医疗健康,美团买药,meituan@alipay.com,999感冒灵颗粒,支出,19.80,花呗,交易成功,2026110602"""

    const val SAMPLE_QIANJI_CSV = """时间,分类,类型,金额,账户1,账户2,备注,商户
2026-11-06 09:10:00,餐饮-奶茶,支出,18.00,微信零钱,,霸王茶姬伯牙绝弦,霸王茶姬
2026-11-06 20:00:00,购物-超市,支出,168.00,招商银行信用卡(3354),,山姆采购牛排与水果,山姆会员商店"""
}
