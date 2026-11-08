package com.moji.ai.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.moji.ai.data.model.CategoryCatalog
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
    val syncDirty: Boolean = true
) {
    fun isSystemBalanceAnchor(): Boolean =
        captureSource == "BALANCE_ANCHOR" || category == CategoryCatalog.SYSTEM_BALANCE_ANCHOR_CATEGORY
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
