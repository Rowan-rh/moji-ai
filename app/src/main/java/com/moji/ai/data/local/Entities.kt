package com.moji.ai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.moji.ai.data.model.RecurrenceFrequency
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType

/**
 * 钱包实体（支持多币种、储蓄卡尾号匹配、信用账户额度与账单日/还款日、品牌标识）
 */
@Entity(tableName = "wallets")
data class WalletEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,                       // 例如："招商银行信用卡"、"花呗"、"微信零钱"
    val type: WalletType,                   // 账户大类
    val subTypeLabel: String = "",          // 如 "信用卡"、"蚂蚁花呗"、"京东白条"、"储蓄卡"、"数字钱包"
    val brandCode: String = "DEFAULT",      // "CMB", "WECHAT", "ALIPAY", "HUABEI", "JD", "CASH", "VISA"
    val currency: String = "CNY",           // 币种代码，如 CNY, USD, HKD
    /**
     * 对于资产账户 (isLiability = false)：balanceCents 表示当前可用余额（分）
     * 对于信用账户 (isLiability = true)：balanceCents 表示当前【待还欠款金额】（正数代表欠款，0 代表无欠款）
     */
    val balanceCents: Long = 0L,
    val creditLimitCents: Long? = null,     // 信用账户授信总额度（分）
    val cardLast4: String? = null,          // 银行卡后四位（如 "3354"）
    val aliases: String = "",               // 供 AI 语义识别的内部匹配词（不在列表副标题直接裸露）
    val billingDay: Int? = null,            // 账单日 (1..28)
    val repaymentDay: Int? = null,          // 还款日 (1..28)
    val includeInNetWorth: Boolean = true,  // 是否计入净资产统计
    val colorHex: Long = 0xFFE65540
)

/**
 * 账单交易流水实体（全面对齐 iCost 功能字段 + AI 自动记账溯源）
 */
@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val ledgerName: String = "默认账本",    // 支持多账本切换
    val type: TransactionType,
    val amountCents: Long,                  // 实付原币种金额（分）
    val currency: String = "CNY",           // 交易原币种
    val exchangeRateToCny: Double = 1.0,    // 汇率
    val amountCnyCents: Long,               // 折算本位币实付金额（分）

    val discountCents: Long = 0L,           // 优惠/立减金额（分），统计页「优惠」自动汇总
    val feeCents: Long = 0L,                // 手续费（分），统计页「手续费」自动汇总

    val category: String,                   // 分类名称（如 "餐饮-零食", "购物", "数码"）
    val merchant: String = "",              // 商户或备注主标题（如 "盒马", "唐久(环普产业园店)"）
    val note: String = "",                  // 详细备注

    val sourceWalletId: Long?,              // 支出钱包 / 转出钱包 / 还款付款钱包 ID
    val targetWalletId: Long? = null,       // 收入钱包 / 转入钱包 / 被还款信用钱包 / 退款收款钱包 ID

    val relatedTransactionId: Long? = null, // 关联的原支出账单 ID（退款/AA冲销用）
    val refundedAmountCents: Long = 0L,     // 该笔支出已被退款/冲销的金额（分）

    val reimbursable: Boolean = false,      // 是否标记为「待报销」
    val reimbursed: Boolean = false,        // 是否已完成「报销入账」
    val tags: String = "",                  // 标签（如 "聚餐,出差"）

    val timestamp: Long = System.currentTimeMillis(),
    val captureSource: String = "MANUAL",   // "AI_NOTIFICATION", "AI_ACCESSIBILITY", "AI_MERGED", "RECURRING", "MANUAL"
    val rawCapturedText: String = "",       // 原始通知/页面文本留档
    val aiConfidence: Float = 1.0f
)

/**
 * 周期性固定收支规则
 */
@Entity(tableName = "recurring_rules")
data class RecurringRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
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
    val enabled: Boolean = true
)
