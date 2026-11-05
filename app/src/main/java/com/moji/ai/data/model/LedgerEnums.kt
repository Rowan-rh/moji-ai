package com.moji.ai.data.model

import kotlinx.serialization.Serializable

/**
 * 钱包（资产/负债账户）类型：
 * 1. DEBIT_BANK: 储蓄卡/借记卡（如：招商银行储蓄卡(8848)）
 * 2. DIGITAL_WALLET: 网络数字钱包（如：微信零钱、零钱通、支付宝余额、余额宝）
 * 3. CREDIT: 信用透支账户（如：招商银行信用卡、蚂蚁花呗、京东白条、美团月付）
 * 4. CASH_OTHER: 现金、饭卡、外币钱包等其他资产
 */
@Serializable
enum class WalletType(val displayName: String, val isLiability: Boolean) {
    DEBIT_BANK("银行储蓄卡", false),
    DIGITAL_WALLET("数字钱包(微信/支付宝)", false),
    CREDIT("信用账户(信用卡/花呗/白条)", true),
    CASH_OTHER("现金/预付卡/其他", false)
}

/**
 * 5大核心交易形态，彻底解决“乱七八糟”的转账、还款与退款AA问题：
 * 1. EXPENSE: 日常消费支出（扣减资产余额，或增加信用账户已用负债）
 * 2. INCOME: 日常收入（增加资产余额）
 * 3. TRANSFER: 资产内部转账（如：招行卡提现/充值到微信零钱；不计入收支报表）
 * 4. REPAYMENT: 信用卡/花呗/白条还款（从储蓄卡/零钱转入信用账户恢复额度；不计入重复支出）
 * 5. REFUND_OFFSET: 退款 / 报销 / 朋友AA转钱冲销（增加收款钱包余额，并自动冲抵原消费支出）
 */
@Serializable
enum class TransactionType(val displayName: String) {
    EXPENSE("支出"),
    INCOME("收入"),
    TRANSFER("内部转账"),
    REPAYMENT("信用还款"),
    REFUND_OFFSET("退款/AA冲销")
}

/**
 * 周期性固定收支频率（房租、订阅会员、固定工资等）
 */
@Serializable
enum class RecurrenceFrequency(val displayName: String, val daysInterval: Int) {
    DAILY("每天", 1),
    WEEKLY("每周", 7),
    MONTHLY("每月", 30),
    YEARLY("每年", 365)
}

/**
 * 内置多币种支持与默认对人民币 (CNY) 参考汇率（用户可在设置或由 AI 实时更新）
 */
@Serializable
enum class CurrencyCode(
    val symbol: String,
    val displayName: String,
    val defaultRateToCny: Double
) {
    CNY("¥", "人民币 (CNY)", 1.0),
    USD("$", "美元 (USD)", 7.22),
    HKD("HK$", "港币 (HKD)", 0.93),
    EUR("€", "欧元 (EUR)", 7.75),
    JPY("JP¥", "日元 (JPY)", 0.048),
    GBP("£", "英镑 (GBP)", 9.20);

    companion object {
        fun fromCode(code: String?): CurrencyCode {
            if (code.isNullOrBlank()) return CNY
            return entries.firstOrNull { it.name.equals(code.trim(), ignoreCase = true) } ?: CNY
        }
    }
}
