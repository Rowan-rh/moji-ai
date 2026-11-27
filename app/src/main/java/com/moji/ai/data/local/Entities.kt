package com.moji.ai.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.moji.ai.data.model.CategoryCatalog
import com.moji.ai.data.model.DedicatedFundManager
import com.moji.ai.data.model.RecurrenceFrequency
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType
import java.util.UUID

/**
 * 钱包实体（支持多币种、储蓄卡/信用卡后4位匹配、汉字账户名/别名匹配、云同步 UUID 与增量时间戳）
 */
@Entity(
    tableName = "wallets",
    indices = [
        Index(value = ["syncUuid"], unique = false),
        Index(value = ["cardLast4"], unique = false)
    ]
)
data class WalletEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val syncUuid: String = UUID.randomUUID().toString(),
    val name: String,
    val type: WalletType,
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
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val deletedAtMillis: Long? = null,
    val syncDirty: Boolean = true
)

/**
 * 账单交易流水实体：
 *
 * 订单唯一 Key、实际购物时间、金额与数量核心语义：
 * - [orderKey]：**订单唯一 Key**——优先取支付平台订单号/流水号 (`transactionNo`) 或商户单号 (`merchantOrderNo`)；作为一级唯一判重依据；
 * - [timestamp]：**实际购物/交易发生时间（Occurred Time）**——从账单「支付时间 / 创建时间 / 转账时间」提取，绝不与截图时间混淆；无订单 Key 时，「实际发生时间 + 金额」作为二级唯一判重依据；
 * - [capturedAtMillis]：**截图/识账采集时间（Captured Time）**——无障碍或截屏 OCR 实际抓取该页面的系统时间；
 * - [amountCents]：**交易实付金额（分）**；
 * - [itemQuantity] & [quantityDesc]：**购物商品数量**（如 `1` / `"1件"`、`2` / `"多件"`），从商品说明中提取留存。
 */
@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["orderKey"]),
        Index(value = ["transactionNo"]),
        Index(value = ["merchantOrderNo"]),
        Index(value = ["dedupFingerprint"]),
        Index(value = ["syncUuid"])
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val syncUuid: String = UUID.randomUUID().toString(),
    val ledgerName: String = "默认账本",
    val type: TransactionType,
    val amountCents: Long,                  // 实付原币种金额（分）
    val currency: String = "CNY",
    val exchangeRateToCny: Double = 1.0,
    val amountCnyCents: Long,               // 折算本位币实付金额（分）

    val discountCents: Long = 0L,           // 优惠/立减金额（分）
    val feeCents: Long = 0L,                // 手续费（分）

    val itemQuantity: Int = 1,              // 购物商品数量（默认 1 件）
    val quantityDesc: String = "1件",       // 购物数量描述（如 "1件"、"2件"、"多件"）

    val category: String,
    val merchant: String = "",
    val note: String = "",

    val sourceWalletId: Long?,
    val targetWalletId: Long? = null,

    val relatedTransactionId: Long? = null,
    val refundedAmountCents: Long = 0L,

    val reimbursable: Boolean = false,
    val reimbursed: Boolean = false,
    val tags: String = "",

    /** 实际购物/交易发生时间（毫秒），从账单「支付时间/创建时间」解析 */
    val timestamp: Long = System.currentTimeMillis(),
    /** 截图/无障碍/通知实际采集时间（毫秒），与实际购物发生时间严格区分 */
    val capturedAtMillis: Long = System.currentTimeMillis(),

    /** 订单唯一 Key（优先取平台订单号 transactionNo，其次取商户单号 merchantOrderNo） */
    val orderKey: String = "",
    /** 支付平台流水号 / 交易单号 / 订单号 / 转账单号 */
    val transactionNo: String = "",
    /** 商户单号 / 商家订单号 */
    val merchantOrderNo: String = "",
    /** 账单上显示的原始付款/收款方式文案（如 "招商银行信用卡(3354)"、"花呗"） */
    val paymentMethodRaw: String = "",
    /** 识账截图本地保存路径（files/bill_screenshots/bill_xxx.webp） */
    val screenshotPath: String = "",
    /** 识账截图 SHA-256 哈希 */
    val screenshotSha256: String = "",
    /** 幂等防重指纹 */
    val dedupFingerprint: String = "",
    /** 用户在“检测到疑似重复”弹窗中主动点击“录入”强制入账的标记 */
    val duplicateSuspected: Boolean = false,

    val captureSource: String = "MANUAL",
    val rawCapturedText: String = "",
    val aiConfidence: Float = 1.0f,

    val updatedAtMillis: Long = System.currentTimeMillis(),
    val deletedAtMillis: Long? = null,
    val syncRevision: Long = 1L,
    val syncDirty: Boolean = true,
    /** 源钱包历史实际扣减变动快照（源钱包原币种分值，null 表示 v5 及更早旧数据） */
    val sourceWalletDeltaCents: Long? = null,
    /** 目标/收款/报销入账钱包历史实际变动快照（目标钱包原币种分值，null 表示 v5 及更早旧数据） */
    val targetWalletDeltaCents: Long? = null,
    /** 已报销核销入账时间戳（毫秒，用于净资产趋势将报销回流归入真实核销时间桶） */
    val reimbursedAtMillis: Long? = null,
    /** 是否同步共享至家庭账本（仅日常 EXPENSE / INCOME 出入账允许共享；默认 false 保守隔离） */
    val syncToFamily: Boolean = false
) {
    fun isSystemBalanceAnchor(): Boolean =
        captureSource == "BALANCE_ANCHOR" || category == CategoryCatalog.SYSTEM_BALANCE_ANCHOR_CATEGORY

    /**
     * 判断当前账单是否满足进入「家庭账本」的日常出入账条件（AC-F2）：
     * 仅限日常 `EXPENSE` / `INCOME`，自动排除转账、还款、借入/借出、退款冲抵子单、平账流水、
     * 医保个账组合拆单子单（`#MED_PERSONAL`）及工资条医保/公积金专款划入子单。
     */
    fun isEligibleForFamilySync(): Boolean =
        (type == TransactionType.EXPENSE || type == TransactionType.INCOME) &&
            !isSystemBalanceAnchor() &&
            !DedicatedFundManager.isCompositeMedicalPersonalChild(this) &&
            !orderKey.endsWith(DedicatedFundManager.PAYSLIP_MEDICAL_CHILD_SUFFIX) &&
            !orderKey.endsWith(DedicatedFundManager.PAYSLIP_PROVIDENT_CHILD_SUFFIX)
}

/**
 * 账单采集原始证据与截图归档表
 */
@Entity(
    tableName = "capture_evidences",
    indices = [
        Index(value = ["transactionId"]),
        Index(value = ["transactionNo"]),
        Index(value = ["syncUuid"])
    ]
)
data class CaptureEvidenceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val syncUuid: String = UUID.randomUUID().toString(),
    val transactionId: Long,
    val captureSource: String,
    val sourcePackage: String,
    val occurredAtMillis: Long,
    val capturedAtMillis: Long,
    val amountCents: Long,
    val transactionNo: String = "",
    val merchantOrderNo: String = "",
    val paymentMethodRaw: String = "",
    val screenshotPath: String = "",
    val screenshotSha256: String = "",
    val rawText: String = ""
)

/**
 * 周期性固定收支规则
 */
@Entity(tableName = "recurring_rules")
data class RecurringRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val syncUuid: String = UUID.randomUUID().toString(),
    val title: String,
    val type: TransactionType = TransactionType.EXPENSE,
    val amountCents: Long,
    val currency: String = "CNY",
    val category: String,
    val walletId: Long,
    val targetWalletId: Long? = null,
    val frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
    val dayOfMonthOrWeek: Int = 1,
    val nextTriggerTimeMillis: Long,
    val enabled: Boolean = true,
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val syncDirty: Boolean = true
)

/**
 * 家庭共享账单只读镜像缓存表（AC-F1）：
 * - 仅存储云端其他家庭成员（`ownerUserId != currentUserId`）的脱敏出入账（`EXPENSE` / `INCOME`）及墓碑记录；
 * - 与本地个人 `transactions`、`wallets`、AI 防重、退款冲抵匹配、记账提醒及 `.mojibackup` 100% 物理隔离；
 * - 展示时映射为负 `id` 的只读视图模型，绝不扣减本机钱包余额、绝不双扣本人账单。
 */
@Entity(
    tableName = "family_shared_transactions",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["ownerUserId"]),
        Index(value = ["seq"])
    ]
)
data class FamilySharedTransactionEntity(
    @PrimaryKey
    val txUuid: String,
    val seq: Long,
    val ownerUserId: String,
    val type: TransactionType,
    val currency: String = "CNY",
    val amountCents: Long,
    val amountCnyCents: Long,
    val refundedAmountCents: Long = 0L,
    val category: String,
    val merchant: String = "",
    val note: String = "",
    val tags: String = "",
    val timestamp: Long,
    val updatedAtMillis: Long,
    val deletedAtMillis: Long? = null
) {
    fun toNegativeDisplayId(): Long {
        var hash = -3750763034362895579L
        for (ch in txUuid) {
            hash = (hash xor ch.code.toLong()) * 1099511628211L
        }
        val positiveMagnitude = (hash and 0x3FFF_FFFF_FFFF_FFFFL).coerceAtLeast(1L)
        return -positiveMagnitude
    }

    fun toReadOnlyDisplayTransaction(familyLedgerName: String = "家庭账本"): TransactionEntity =
        TransactionEntity(
            id = toNegativeDisplayId(),
            syncUuid = txUuid,
            ledgerName = familyLedgerName,
            type = type,
            amountCents = amountCents,
            currency = currency,
            exchangeRateToCny = if (amountCents > 0L) amountCnyCents.toDouble() / amountCents.toDouble() else 1.0,
            amountCnyCents = amountCnyCents,
            refundedAmountCents = refundedAmountCents,
            category = category,
            merchant = merchant,
            note = note,
            sourceWalletId = null,
            targetWalletId = null,
            tags = tags,
            timestamp = timestamp,
            capturedAtMillis = timestamp,
            captureSource = "FAMILY_SHARED",
            updatedAtMillis = updatedAtMillis,
            deletedAtMillis = deletedAtMillis,
            syncDirty = false,
            syncToFamily = true
        )
}
