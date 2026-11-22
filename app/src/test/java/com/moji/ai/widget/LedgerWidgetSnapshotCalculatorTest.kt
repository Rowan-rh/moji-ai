package com.moji.ai.widget

import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.model.AppearanceSettingsConfig
import com.moji.ai.data.model.PasswordProtectionConfig
import com.moji.ai.data.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class LedgerWidgetSnapshotCalculatorTest {

    private val shanghaiTz: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun epochMillis(
        year: Int,
        monthOneBased: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        millis: Int = 0
    ): Long = Calendar.getInstance(shanghaiTz).apply {
        clear()
        set(year, monthOneBased - 1, day, hour, minute, second)
        set(Calendar.MILLISECOND, millis)
    }.timeInMillis

    private fun buildTx(
        id: Long,
        ledgerName: String = "默认账本",
        type: TransactionType = TransactionType.EXPENSE,
        category: String = "餐饮",
        amountCnyCents: Long,
        refundedAmountCents: Long = 0L,
        timestamp: Long,
        deletedAtMillis: Long? = null
    ): TransactionEntity = TransactionEntity(
        id = id,
        ledgerName = ledgerName,
        type = type,
        amountCents = amountCnyCents,
        currency = "CNY",
        exchangeRateToCny = 1.0,
        amountCnyCents = amountCnyCents,
        category = category,
        merchant = "测试商户-$id",
        sourceWalletId = 1L,
        refundedAmountCents = refundedAmountCents,
        timestamp = timestamp,
        capturedAtMillis = timestamp,
        deletedAtMillis = deletedAtMillis
    )

    @Test
    fun calculate_withActiveBudget_matchesLedgerHomeAggregationAndRefundDeduction() {
        // 2026-11-22（11月共30天，含今日剩余 9 天）
        val now = epochMillis(2026, 11, 22, 14, 30, 0)
        val txs = listOf(
            // 今日零点边界支出 ¥15.00
            buildTx(
                id = 1L,
                amountCnyCents = 15_00L,
                timestamp = epochMillis(2026, 11, 22, 0, 0, 0, 0)
            ),
            // 今日 23:59:59.999 边界支出 ¥50.00，部分退款 ¥12.00 -> 净支出 ¥38.00
            buildTx(
                id = 2L,
                amountCnyCents = 50_00L,
                refundedAmountCents = 12_00L,
                timestamp = epochMillis(2026, 11, 22, 23, 59, 59, 999)
            ),
            // 本月较早支出 ¥667.00
            buildTx(
                id = 3L,
                amountCnyCents = 667_00L,
                timestamp = epochMillis(2026, 11, 5, 12, 0, 0)
            ),
            // 退款超原额防御 -> 单笔净支出钳制为 0，不出现负值抵扣其他账单
            buildTx(
                id = 4L,
                amountCnyCents = 20_00L,
                refundedAmountCents = 25_00L,
                timestamp = epochMillis(2026, 11, 10, 9, 0, 0)
            )
        )

        val snapshot = LedgerWidgetSnapshotCalculator.calculate(
            transactions = txs,
            currentLedger = "默认账本",
            monthlyBudgetCents = 3000_00L,
            privacyMasked = false,
            nowMillis = now,
            timeZone = shanghaiTz
        )

        assertEquals("默认账本", snapshot.ledgerName)
        assertEquals("11月22日", snapshot.dateBadgeText)
        assertEquals(53_00L, snapshot.todayExpenseCents)
        assertEquals(2, snapshot.todayTxCount)
        assertEquals(720_00L, snapshot.monthExpenseCents)
        assertTrue(snapshot.hasBudget)
        assertFalse(snapshot.isOverBudget)
        assertEquals(2280_00L, snapshot.remainingBudgetCents)
        assertEquals(0L, snapshot.overBudgetCents)
        assertEquals(24, snapshot.progressPercent)
        assertEquals(2280_00L / 9L, snapshot.dailyAllowanceCents)
        assertEquals("本月剩余预算", snapshot.primaryTitle)
        assertEquals("¥2,280.00", snapshot.primaryAmountText)
        assertEquals("已用 24%", snapshot.progressPercentText)
        assertEquals("今日已花 · 2笔", snapshot.todayLabelText)
        assertEquals("¥53.00", snapshot.todayAmountText)
        assertEquals("11月支出", snapshot.monthLabelText)
        assertEquals("¥720.00", snapshot.monthAmountText)
    }

    @Test
    fun calculate_excludesOtherLedgersDeletedNonExpenseReconcileAndOutOfMonthRows() {
        val now = epochMillis(2026, 11, 22, 10, 0, 0)
        val txs = listOf(
            buildTx(id = 1L, ledgerName = "旅行基金", amountCnyCents = 99_00L, timestamp = now),
            buildTx(id = 2L, amountCnyCents = 88_00L, timestamp = now, deletedAtMillis = now),
            buildTx(id = 3L, type = TransactionType.INCOME, amountCnyCents = 5000_00L, timestamp = now),
            buildTx(id = 4L, type = TransactionType.TRANSFER, amountCnyCents = 300_00L, timestamp = now),
            buildTx(id = 5L, type = TransactionType.REPAYMENT, amountCnyCents = 400_00L, timestamp = now),
            buildTx(id = 6L, type = TransactionType.REFUND_OFFSET, amountCnyCents = 50_00L, timestamp = now),
            buildTx(id = 7L, category = "平账", amountCnyCents = 600_00L, timestamp = now),
            buildTx(id = 8L, amountCnyCents = 120_00L, timestamp = epochMillis(2026, 10, 31, 23, 59, 59, 999)),
            buildTx(id = 9L, amountCnyCents = 130_00L, timestamp = epochMillis(2026, 12, 1, 0, 0, 0, 0)),
            // 唯一有效当月非今日支出
            buildTx(id = 10L, amountCnyCents = 42_50L, timestamp = epochMillis(2026, 11, 21, 23, 59, 59, 999))
        )

        val snapshot = LedgerWidgetSnapshotCalculator.calculate(
            transactions = txs,
            currentLedger = "默认账本",
            monthlyBudgetCents = 1000_00L,
            nowMillis = now,
            timeZone = shanghaiTz
        )

        assertEquals(0L, snapshot.todayExpenseCents)
        assertEquals(0, snapshot.todayTxCount)
        assertEquals(42_50L, snapshot.monthExpenseCents)
        assertEquals("今日已花", snapshot.todayLabelText)
        assertEquals("¥0.00", snapshot.todayAmountText)
        assertEquals("¥42.50", snapshot.monthAmountText)
    }

    @Test
    fun calculate_whenOverBudget_highlightsOverBudgetAmountAndCapsProgressAt100() {
        val now = epochMillis(2026, 11, 22, 18, 0, 0)
        val txs = listOf(
            buildTx(id = 1L, amountCnyCents = 1256_80L, timestamp = now)
        )

        val snapshot = LedgerWidgetSnapshotCalculator.calculate(
            transactions = txs,
            currentLedger = "默认账本",
            monthlyBudgetCents = 1000_00L,
            nowMillis = now,
            timeZone = shanghaiTz
        )

        assertTrue(snapshot.isOverBudget)
        assertEquals(0L, snapshot.remainingBudgetCents)
        assertEquals(256_80L, snapshot.overBudgetCents)
        assertEquals(100, snapshot.progressPercent)
        assertEquals(0L, snapshot.dailyAllowanceCents)
        assertEquals("本月已超支", snapshot.primaryTitle)
        assertEquals("¥256.80", snapshot.primaryAmountText)
        assertTrue(snapshot.statusSubtitle.startsWith("已超支 ¥256.80"))
        assertEquals("已超支", snapshot.progressPercentText)
        assertEquals("已超支 ¥256.80", snapshot.compactSecondaryText)
    }

    @Test
    fun calculate_whenBudgetUnsetOrNegative_degradesToMonthExpenseWithoutDivideByZero() {
        val now = epochMillis(2026, 11, 22, 9, 0, 0)
        val txs = listOf(
            buildTx(id = 1L, amountCnyCents = 88_50L, timestamp = now)
        )

        for (rawBudget in listOf(0L, -500_00L)) {
            val snapshot = LedgerWidgetSnapshotCalculator.calculate(
                transactions = txs,
                currentLedger = "默认账本",
                monthlyBudgetCents = rawBudget,
                nowMillis = now,
                timeZone = shanghaiTz
            )

            assertFalse(snapshot.hasBudget)
            assertFalse(snapshot.isOverBudget)
            assertEquals(0L, snapshot.monthlyBudgetCents)
            assertEquals(0L, snapshot.remainingBudgetCents)
            assertEquals(0, snapshot.progressPercent)
            assertEquals("", snapshot.progressPercentText)
            assertEquals("本月支出", snapshot.primaryTitle)
            assertEquals("¥88.50", snapshot.primaryAmountText)
            assertEquals("未设置月预算", snapshot.statusSubtitle)
            assertEquals("未设置月预算", snapshot.compactSecondaryText)
        }
    }

    @Test
    fun privacyMasking_hidesAllMonetaryValuesAndSensitivePercentUntilDisabled() {
        val now = epochMillis(2026, 11, 22, 12, 0, 0)
        val txs = listOf(
            buildTx(id = 1L, amountCnyCents = 1500_00L, timestamp = now)
        )

        assertTrue(
            LedgerWidgetSnapshotCalculator.shouldMaskPrivacy(
                hideAssetAmounts = true,
                appLockConfig = PasswordProtectionConfig(enabled = false, hideRecentTaskPreview = false)
            )
        )
        assertTrue(
            LedgerWidgetSnapshotCalculator.shouldMaskPrivacy(
                hideAssetAmounts = false,
                appLockConfig = PasswordProtectionConfig(enabled = true, hideRecentTaskPreview = true)
            )
        )
        assertFalse(
            LedgerWidgetSnapshotCalculator.shouldMaskPrivacy(
                hideAssetAmounts = false,
                appLockConfig = PasswordProtectionConfig(enabled = false, hideRecentTaskPreview = true)
            )
        )

        val masked = LedgerWidgetSnapshotCalculator.calculate(
            transactions = txs,
            currentLedger = "默认账本",
            monthlyBudgetCents = 1000_00L,
            privacyMasked = true,
            nowMillis = now,
            timeZone = shanghaiTz
        )

        assertEquals("¥****", masked.primaryAmountText)
        assertEquals("¥****", masked.todayAmountText)
        assertEquals("¥****", masked.monthAmountText)
        assertEquals("今日 ¥****", masked.compactTodayText)
        assertEquals(0, masked.progressPercent)
        assertEquals("", masked.progressPercentText)
        assertEquals("今日已花", masked.todayLabelText)

        val unmasked = LedgerWidgetSnapshotCalculator.calculate(
            transactions = txs,
            currentLedger = "默认账本",
            monthlyBudgetCents = 1000_00L,
            privacyMasked = false,
            appearanceConfig = AppearanceSettingsConfig(
                showThousandSeparator = false,
                hideTrailingZeroDecimals = true
            ),
            nowMillis = now,
            timeZone = shanghaiTz
        )
        assertEquals("¥500", unmasked.primaryAmountText)
        assertEquals("¥1500", unmasked.todayAmountText)
        assertEquals("¥1500", unmasked.monthAmountText)
    }

    @Test
    fun layoutThresholdAndEmptyWidgetShortCircuit_behaveAsExpected() {
        assertTrue(LedgerWidgetSnapshotCalculator.shouldUseCompactLayout(110))
        assertTrue(LedgerWidgetSnapshotCalculator.shouldUseCompactLayout(179))
        assertFalse(LedgerWidgetSnapshotCalculator.shouldUseCompactLayout(0))
        assertFalse(LedgerWidgetSnapshotCalculator.shouldUseCompactLayout(180))
        assertFalse(LedgerWidgetSnapshotCalculator.shouldUseCompactLayout(250))

        assertTrue(MoJiLedgerWidgetUpdater.shouldShortCircuitUpdate(intArrayOf()))
        assertFalse(MoJiLedgerWidgetUpdater.shouldShortCircuitUpdate(intArrayOf(101)))
    }
}
