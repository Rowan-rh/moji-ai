package com.moji.ai.widget

import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.model.AppearanceConfigManager
import com.moji.ai.data.model.AppearanceSettingsConfig
import com.moji.ai.data.model.PasswordProtectionConfig
import com.moji.ai.data.model.TransactionType
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * 桌面小组件快照数据模型（纯值对象，供 RemoteViews 渲染与单元测试直接断言）。
 */
data class LedgerWidgetSnapshot(
    val ledgerName: String,
    val dateBadgeText: String,
    val todayExpenseCents: Long,
    val todayTxCount: Int,
    val monthExpenseCents: Long,
    val monthlyBudgetCents: Long,
    val hasBudget: Boolean,
    val isOverBudget: Boolean,
    val remainingBudgetCents: Long,
    val overBudgetCents: Long,
    val progressPercent: Int,
    val dailyAllowanceCents: Long,
    val privacyMasked: Boolean,
    val primaryTitle: String,
    val primaryAmountText: String,
    val statusSubtitle: String,
    val progressPercentText: String,
    val todayLabelText: String,
    val todayAmountText: String,
    val monthLabelText: String,
    val monthAmountText: String,
    val compactTodayText: String,
    val compactSecondaryText: String
)

/**
 * iCost 风格桌面小组件汇总计算器：
 * 统计口径与 [com.moji.ai.ui.screens.ICostLedgerHomeScreen] 保持 100% 一致：
 * - 仅聚合 `ledgerName == currentLedger && deletedAtMillis == null && type == EXPENSE && category != "平账"`；
 * - 单笔净支出 = `(amountCnyCents - refundedAmountCents).coerceAtLeast(0L)`；
 * - 支持月预算剩余、超支高亮、未设预算安全降级，以及金额隐私脱敏（`¥****`）。
 */
object LedgerWidgetSnapshotCalculator {

    const val DEFAULT_LEDGER_NAME = "默认账本"
    const val FAMILY_LEDGER_NAME = "家庭账本"
    const val SYSTEM_RECONCILE_CATEGORY = "平账"
    const val MASKED_AMOUNT_TEXT = "¥****"
    const val COMPACT_WIDTH_THRESHOLD_DP = 180

    fun shouldUseCompactLayout(minWidthDp: Int): Boolean =
        minWidthDp in 1 until COMPACT_WIDTH_THRESHOLD_DP

    fun shouldMaskPrivacy(
        hideAssetAmounts: Boolean,
        appLockConfig: PasswordProtectionConfig
    ): Boolean = hideAssetAmounts || (appLockConfig.enabled && appLockConfig.hideRecentTaskPreview)

    fun calculate(
        transactions: List<TransactionEntity>,
        currentLedger: String,
        monthlyBudgetCents: Long,
        privacyMasked: Boolean = false,
        appearanceConfig: AppearanceSettingsConfig = AppearanceSettingsConfig(),
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
        familySharedTransactions: List<TransactionEntity> = emptyList()
    ): LedgerWidgetSnapshot {
        val resolvedLedger = currentLedger.trim().ifEmpty { DEFAULT_LEDGER_NAME }

        val nowCal = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
        }
        val year = nowCal.get(Calendar.YEAR)
        val monthZeroBased = nowCal.get(Calendar.MONTH)
        val monthNumber = monthZeroBased + 1
        val dayOfMonth = nowCal.get(Calendar.DAY_OF_MONTH)
        val daysInMonth = nowCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val remainingDaysInMonth = (daysInMonth - dayOfMonth + 1).coerceAtLeast(1)

        val startOfTodayMillis = Calendar.getInstance(timeZone).apply {
            clear()
            set(year, monthZeroBased, dayOfMonth, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val endOfTodayMillis = Calendar.getInstance(timeZone).apply {
            clear()
            set(year, monthZeroBased, dayOfMonth, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        val startOfMonthMillis = Calendar.getInstance(timeZone).apply {
            clear()
            set(year, monthZeroBased, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val endOfMonthMillis = Calendar.getInstance(timeZone).apply {
            clear()
            set(year, monthZeroBased, daysInMonth, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        var todayExpenseCents = 0L
        var todayTxCount = 0
        var monthExpenseCents = 0L

        val isFamilyScope = resolvedLedger == FAMILY_LEDGER_NAME
        val sourceItems = if (isFamilyScope) {
            transactions.filter { (it.syncToFamily && it.isEligibleForFamilySync()) || it.ledgerName == FAMILY_LEDGER_NAME } +
                familySharedTransactions
        } else {
            transactions.filter { it.ledgerName == resolvedLedger }
        }

        for (tx in sourceItems) {
            if (tx.deletedAtMillis != null) continue
            if (tx.type != TransactionType.EXPENSE) continue
            if (tx.category == SYSTEM_RECONCILE_CATEGORY) continue

            val netExpenseCents = (tx.amountCnyCents - tx.refundedAmountCents).coerceAtLeast(0L)
            if (tx.timestamp in startOfMonthMillis..endOfMonthMillis) {
                monthExpenseCents += netExpenseCents
            }
            if (tx.timestamp in startOfTodayMillis..endOfTodayMillis) {
                todayExpenseCents += netExpenseCents
                if (netExpenseCents > 0L || tx.refundedAmountCents == 0L) {
                    todayTxCount += 1
                }
            }
        }

        val safeBudgetCents = monthlyBudgetCents.coerceAtLeast(0L)
        val hasBudget = safeBudgetCents > 0L
        val isOverBudget = hasBudget && monthExpenseCents > safeBudgetCents
        val remainingBudgetCents = if (hasBudget && !isOverBudget) {
            safeBudgetCents - monthExpenseCents
        } else {
            0L
        }
        val overBudgetCents = if (isOverBudget) {
            monthExpenseCents - safeBudgetCents
        } else {
            0L
        }

        val rawProgressPercent = if (hasBudget) {
            if (isOverBudget) {
                100
            } else {
                ((monthExpenseCents.toDouble() * 100.0) / safeBudgetCents.toDouble())
                    .roundToInt()
                    .coerceIn(0, 100)
            }
        } else {
            0
        }
        val progressPercent = if (privacyMasked) 0 else rawProgressPercent
        val dailyAllowanceCents = if (hasBudget && !isOverBudget) {
            remainingBudgetCents / remainingDaysInMonth
        } else {
            0L
        }

        fun formatYuan(cents: Long): String {
            if (privacyMasked) return MASKED_AMOUNT_TEXT
            val formatted = AppearanceConfigManager.formatDisplayAmount(cents / 100.0, appearanceConfig)
            return "¥$formatted"
        }

        val primaryTitle = when {
            !hasBudget -> "本月支出"
            privacyMasked -> "本月剩余预算"
            isOverBudget -> "本月已超支"
            else -> "本月剩余预算"
        }

        val primaryAmountText = when {
            privacyMasked -> MASKED_AMOUNT_TEXT
            !hasBudget -> formatYuan(monthExpenseCents)
            isOverBudget -> formatYuan(overBudgetCents)
            else -> formatYuan(remainingBudgetCents)
        }

        val statusSubtitle = when {
            privacyMasked -> "已开启金额隐私保护"
            !hasBudget -> "未设置月预算"
            isOverBudget -> "已超支 ${formatYuan(overBudgetCents)} · 预算 ${formatYuan(safeBudgetCents)}"
            else -> "日均可花 ${formatYuan(dailyAllowanceCents)} · 预算 ${formatYuan(safeBudgetCents)}"
        }

        val progressPercentText = when {
            privacyMasked || !hasBudget -> ""
            isOverBudget -> "已超支"
            else -> "已用 ${rawProgressPercent}%"
        }

        val todayLabelText = when {
            privacyMasked -> "今日已花"
            todayTxCount > 0 -> "今日已花 · ${todayTxCount}笔"
            else -> "今日已花"
        }
        val todayAmountText = formatYuan(todayExpenseCents)
        val monthLabelText = "${monthNumber}月支出"
        val monthAmountText = formatYuan(monthExpenseCents)

        val compactTodayText = "今日 ${formatYuan(todayExpenseCents)}"
        val compactSecondaryText = when {
            privacyMasked -> "隐私保护中"
            !hasBudget -> "未设置月预算"
            isOverBudget -> "已超支 ${formatYuan(overBudgetCents)}"
            else -> "月支 ${formatYuan(monthExpenseCents)}"
        }

        return LedgerWidgetSnapshot(
            ledgerName = resolvedLedger,
            dateBadgeText = "${monthNumber}月${dayOfMonth}日",
            todayExpenseCents = todayExpenseCents,
            todayTxCount = todayTxCount,
            monthExpenseCents = monthExpenseCents,
            monthlyBudgetCents = safeBudgetCents,
            hasBudget = hasBudget,
            isOverBudget = isOverBudget,
            remainingBudgetCents = remainingBudgetCents,
            overBudgetCents = overBudgetCents,
            progressPercent = progressPercent,
            dailyAllowanceCents = dailyAllowanceCents,
            privacyMasked = privacyMasked,
            primaryTitle = primaryTitle,
            primaryAmountText = primaryAmountText,
            statusSubtitle = statusSubtitle,
            progressPercentText = progressPercentText,
            todayLabelText = todayLabelText,
            todayAmountText = todayAmountText,
            monthLabelText = monthLabelText,
            monthAmountText = monthAmountText,
            compactTodayText = compactTodayText,
            compactSecondaryText = compactSecondaryText
        )
    }
}
