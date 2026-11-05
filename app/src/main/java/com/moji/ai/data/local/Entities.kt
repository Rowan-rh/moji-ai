package com.moji.ai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.moji.ai.data.model.RecurrenceFrequency
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType

/**
 * 钱包实体（支持多币种、储蓄卡尾号匹配、信用账户额度与账单日/还款日）
 */
@Entity(tableName = "wallets")
data class WalletEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,                       // 例如："招商银行储蓄卡"、"微信零钱"、"花呗"
    val type: WalletType,                   // 账户类型
    val currency: String = "CNY",           // 币种代码，如 CNY, USD, HKD
    /**
     * 对于资产账户 (isLiability = false)：balance 表示当前可用余额（单位：分，避免浮点数精度问题）
     * 对于信用账户 (isLiability = true)：balance 表示当前【待还欠款金额】（正数代表欠款，例如 150000 表示已用/欠款 1500.00 元）
     */
    val balanceCents: Long = 0L,
    val creditLimitCents: Long? = null,     // 信用账户授信总额度（分），普通账户为 null
    val cardLast4: String? = null,          // 银行卡后四位（如 "8848"），供 AI 在通知栏/页面里精准匹配
    val aliases: String = "",               // 逗号分隔的别名（如 "招行卡,一卡通,工资卡"），辅助 AI 语义对齐
    val billingDay: Int? = null,            // 信用卡/花呗账单日 (1..28)
    val repaymentDay: Int? = null,          // 信用卡/花呗还款日 (1..28)
    val includeInNetWorth: Boolean = true,  // 是否计入净资产统计
    val iconName: String = "account_balance_wallet",
    val colorHex: Long = 0xFF2563EB
)

/**
 * 账单交易流水实体（涵盖日常收支、内部转账、信用卡/花呗还款、退款/AA冲销）
 */
@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: TransactionType,
    val amountCents: Long,                  // 原币种金额（单位：分）
    val currency: String = "CNY",           // 交易原币种
    val exchangeRateToCny: Double = 1.0,    // 交易发生时折算为本位币 (CNY) 的汇率
    val amountCnyCents: Long,               // 折算为本位币后的金额（单位：分），方便统一汇总报表

    val category: String,                   // 分类名称（如 "餐饮-正餐", "交通-打车", "内部转账", "信用还款"）
    val merchant: String = "",              // 商户或交易对方（如 "瑞幸咖啡", "美团", "张三(微信转账)"）
    val note: String = "",                  // AI 提取的商品详情或备注（如 "生椰拿铁大杯无糖"）

    val sourceWalletId: Long?,              // 支出钱包 / 转出钱包 / 还款付款钱包 ID
    val targetWalletId: Long? = null,       // 收入钱包 / 转入钱包 / 被还款信用钱包 / 退款收款钱包 ID

    /**
     * 退款 / 报销 / AA 收款冲销专属字段：
     * - 当本条记录是 EXPENSE 时，refundedAmountCents 记录已被后续退款或 AA 收款冲抵了多少分钱
     * - 当本条记录是 REFUND_OFFSET 时，relatedTransactionId 指向它所冲抵的那笔原消费 ID
     */
    val relatedTransactionId: Long? = null,
    val refundedAmountCents: Long = 0L,

    val timestamp: Long = System.currentTimeMillis(),
    val captureSource: String = "MANUAL",   // "AI_NOTIFICATION", "AI_ACCESSIBILITY", "AI_MERGED", "RECURRING", "MANUAL"
    val rawCapturedText: String = "",       // 原始通知/页面文本留档，方便用户核对为何这样记账
    val aiConfidence: Float = 1.0f          // AI 识别置信度 (0.0 ~ 1.0)
)

/**
 * 周期性固定收支规则（房租、物业费、Apple Music/Netflix 订阅、固定发薪等）
 */
@Entity(tableName = "recurring_rules")
data class RecurringRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,                      // 如 "每月房租", "iCloud+ 200GB 订阅"
    val type: TransactionType = TransactionType.EXPENSE,
    val amountCents: Long,
    val currency: String = "CNY",
    val category: String,
    val walletId: Long,
    val targetWalletId: Long? = null,       // 若是定期自动转账/定投/定期还房贷可用
    val frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
    val dayOfMonthOrWeek: Int = 1,          // 每月几号 (1..28) 或每周几 (1..7)
    val nextTriggerTimeMillis: Long,        // 下次自动静默入账时间戳
    val enabled: Boolean = true
)
