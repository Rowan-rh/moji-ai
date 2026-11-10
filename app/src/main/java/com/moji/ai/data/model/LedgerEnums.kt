package com.moji.ai.data.model

import kotlinx.serialization.Serializable
import java.util.Calendar

/**
 * 钱包（资产/负债/借贷账户）类型
 */
@Serializable
enum class WalletType(
    val displayName: String,
    val shortTag: String,
    val isLiability: Boolean
) {
    DEBIT_BANK("储蓄卡", "储蓄卡", false),
    DIGITAL_WALLET("数字钱包", "网络账户", false),
    CREDIT("信用账户", "信用卡", true),
    CASH_OTHER("现金/其他", "现金", false)
}

/**
 * 7大核心交易形态（覆盖日常收支、内部转账、信用还款、退款冲销、借入借出）
 */
@Serializable
enum class TransactionType(val displayName: String) {
    EXPENSE("支出"),
    INCOME("收入"),
    TRANSFER("转账"),
    REPAYMENT("还款"),
    REFUND_OFFSET("退款"),
    LOAN_IN("借入"),
    LOAN_OUT("借出")
}

/**
 * 周期性固定收支频率
 */
@Serializable
enum class RecurrenceFrequency(val displayName: String, val daysInterval: Int) {
    DAILY("每天", 1),
    WEEKLY("每周", 7),
    MONTHLY("每月", 30),
    YEARLY("每年", 365);

    /**
     * 按自然日历（月/年/周/天）递推下一次触发时间，避免固定 30 天 / 365 天产生长期漂移。
     * 针对每月触发且指定了 anchorDay（如 31 号）的规则，在 2 月等短月收敛至月末后，后续大月自动恢复到 anchorDay。
     */
    fun computeNextTriggerMillis(
        baseMillis: Long,
        nowMillis: Long = baseMillis,
        anchorDay: Int? = null
    ): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = if (baseMillis > 0L) baseMillis else nowMillis
        }
        do {
            when (this) {
                DAILY -> cal.add(Calendar.DAY_OF_YEAR, 1)
                WEEKLY -> cal.add(Calendar.DAY_OF_YEAR, 7)
                MONTHLY -> {
                    cal.add(Calendar.MONTH, 1)
                    if (anchorDay != null && anchorDay in 1..31) {
                        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                        cal.set(Calendar.DAY_OF_MONTH, anchorDay.coerceAtMost(maxDay))
                    }
                }
                YEARLY -> cal.add(Calendar.YEAR, 1)
            }
        } while (cal.timeInMillis <= nowMillis)
        return cal.timeInMillis
    }
}

/**
 * 多币种支持与默认对人民币 (CNY) 参考汇率
 */
@Serializable
enum class CurrencyCode(
    val symbol: String,
    val displayName: String,
    val defaultRateToCny: Double,
    val isCommonForeign: Boolean = false
) {
    CNY("¥", "人民币 (CNY)", 1.0, isCommonForeign = false),
    USD("$", "美元 (USD)", 7.22, isCommonForeign = true),
    HKD("HK$", "港币 (HKD)", 0.93, isCommonForeign = true),
    EUR("€", "欧元 (EUR)", 7.75, isCommonForeign = true),
    JPY("JP¥", "日元 (JPY)", 0.048, isCommonForeign = true),
    GBP("£", "英镑 (GBP)", 9.20, isCommonForeign = true),
    KRW("₩", "韩元 (KRW)", 0.0052, isCommonForeign = true),
    TWD("NT$", "新台币 (TWD)", 0.225, isCommonForeign = false),
    SGD("S$", "新加坡元 (SGD)", 5.38, isCommonForeign = true),
    AUD("A$", "澳元 (AUD)", 4.72, isCommonForeign = false),
    CAD("C$", "加元 (CAD)", 5.20, isCommonForeign = false),
    THB("฿", "泰铢 (THB)", 0.21, isCommonForeign = true);

    companion object {
        val foreignEntries: List<CurrencyCode> = entries.filter { it != CNY }

        fun fromCode(code: String?): CurrencyCode {
            if (code.isNullOrBlank()) return CNY
            return entries.firstOrNull { it.name.equals(code.trim(), ignoreCase = true) } ?: CNY
        }
    }
}
