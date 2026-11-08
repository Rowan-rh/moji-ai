package com.moji.ai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.TransactionType
import com.moji.ai.ui.theme.ICDinFontFamily
import com.moji.ai.ui.theme.ICostAmberGold
import com.moji.ai.ui.theme.ICostAssetImage
import com.moji.ai.ui.theme.ICostAvatarButton
import com.moji.ai.ui.theme.ICostCardWhite
import com.moji.ai.ui.theme.ICostCategoryBadge
import com.moji.ai.ui.theme.ICostCellGray
import com.moji.ai.ui.theme.ICostChartPalette
import com.moji.ai.ui.theme.ICostCoralRed
import com.moji.ai.ui.theme.ICostDivider
import com.moji.ai.ui.theme.ICostGreenIncome
import com.moji.ai.ui.theme.ICostTextMuted
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.formatMoneyCents
import com.moji.ai.ui.theme.iCostHorizontalSwipeTrigger
import com.moji.ai.ui.theme.iCostPressScale
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sin

enum class StatsRangeMode(val label: String) {
    WEEK("周"),
    MONTH("月"),
    YEAR("年"),
    ALL("全部")
}

/** 统计图表的一个时间分桶：[startMillis, endMillis)。 */
private data class StatsBucket(
    val startMillis: Long,
    val endMillis: Long,
    val axisLabel: String,
    val fullLabel: String
)

private const val DAY_MILLIS = 86_400_000L

private fun startOfDay(millis: Long): Calendar = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}

/** 以周一为一周第一天，返回「本周 + offset」的周一 0 点。 */
private fun weekStartMillis(weekOffset: Int): Long {
    val c = startOfDay(System.currentTimeMillis())
    val dow = c.get(Calendar.DAY_OF_WEEK) // 周日 = 1，周一 = 2
    val daysSinceMonday = (dow + 5) % 7
    c.add(Calendar.DAY_OF_MONTH, -daysSinceMonday)
    c.add(Calendar.DAY_OF_MONTH, weekOffset * 7)
    return c.timeInMillis
}

/** 计算选中周期的时间范围 [start, end)。ALL 模式以最早一笔账单所在日为起点。 */
private fun periodRange(
    mode: StatsRangeMode,
    year: Int,
    month: Int,
    weekOffset: Int,
    earliestMillis: Long?,
    latestMillis: Long?
): Pair<Long, Long>? {
    return when (mode) {
        StatsRangeMode.WEEK -> {
            val start = weekStartMillis(weekOffset)
            val end = Calendar.getInstance().apply { timeInMillis = start; add(Calendar.DAY_OF_MONTH, 7) }.timeInMillis
            start to end
        }
        StatsRangeMode.MONTH -> {
            val c = startOfDay(System.currentTimeMillis()).apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month - 1)
                set(Calendar.DAY_OF_MONTH, 1)
            }
            val start = c.timeInMillis
            c.add(Calendar.MONTH, 1)
            start to c.timeInMillis
        }
        StatsRangeMode.YEAR -> {
            val c = startOfDay(System.currentTimeMillis()).apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 1)
            }
            val start = c.timeInMillis
            c.add(Calendar.YEAR, 1)
            start to c.timeInMillis
        }
        StatsRangeMode.ALL -> {
            val earliest = earliestMillis ?: return null
            val start = startOfDay(earliest).timeInMillis
            // 截止到今天（若有预记的未来账单则延伸到最后一笔）
            val lastDay = max(System.currentTimeMillis(), latestMillis ?: 0L)
            val end = startOfDay(lastDay).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
            start to max(end, start + DAY_MILLIS)
        }
    }
}

/** 按维度生成图表分桶：周 7 天、月按天、年 12 个月、全部按月（跨度超过 3 年按年）。 */
private fun buildBuckets(mode: StatsRangeMode, range: Pair<Long, Long>): List<StatsBucket> {
    val (start, end) = range
    val buckets = mutableListOf<StatsBucket>()
    val c = Calendar.getInstance().apply { timeInMillis = start }
    val weekNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    when (mode) {
        StatsRangeMode.WEEK, StatsRangeMode.MONTH -> {
            var idx = 0
            while (c.timeInMillis < end && idx < 400) {
                val s = c.timeInMillis
                val m = c.get(Calendar.MONTH) + 1
                val d = c.get(Calendar.DAY_OF_MONTH)
                c.add(Calendar.DAY_OF_MONTH, 1)
                val axis = if (mode == StatsRangeMode.WEEK) weekNames[idx % 7] else "$m/$d"
                buckets += StatsBucket(s, c.timeInMillis, axis, "${m}月${d}日")
                idx++
            }
        }
        StatsRangeMode.YEAR -> {
            c.set(Calendar.DAY_OF_MONTH, 1)
            while (c.timeInMillis < end) {
                val s = c.timeInMillis
                val m = c.get(Calendar.MONTH) + 1
                c.add(Calendar.MONTH, 1)
                buckets += StatsBucket(s, c.timeInMillis, "${m}月", "${m}月")
            }
        }
        StatsRangeMode.ALL -> {
            val endCal = Calendar.getInstance().apply { timeInMillis = end - 1 }
            val monthSpan = (endCal.get(Calendar.YEAR) - c.get(Calendar.YEAR)) * 12 +
                (endCal.get(Calendar.MONTH) - c.get(Calendar.MONTH)) + 1
            c.set(Calendar.DAY_OF_MONTH, 1)
            if (monthSpan > 36) {
                c.set(Calendar.MONTH, Calendar.JANUARY)
                while (c.timeInMillis < end) {
                    val s = c.timeInMillis
                    val y = c.get(Calendar.YEAR)
                    c.add(Calendar.YEAR, 1)
                    buckets += StatsBucket(s, c.timeInMillis, "$y", "${y}年")
                }
            } else {
                while (c.timeInMillis < end) {
                    val s = c.timeInMillis
                    val y = c.get(Calendar.YEAR)
                    val m = c.get(Calendar.MONTH) + 1
                    c.add(Calendar.MONTH, 1)
                    buckets += StatsBucket(s, c.timeInMillis, "${y % 100}/$m", "${y}年${m}月")
                }
            }
        }
    }
    return buckets
}

/** 选择坐标轴上要显示的刻度下标，避免标签拥挤。 */
private fun axisLabelIndices(mode: StatsRangeMode, count: Int): Set<Int> {
    if (count <= 0) return emptySet()
    if (mode == StatsRangeMode.WEEK || count <= 7) return (0 until count).toSet()
    if (mode == StatsRangeMode.MONTH) return setOf(0, 9, 19, count - 1).filter { it in 0 until count }.toSet()
    if (mode == StatsRangeMode.YEAR) return setOf(0, 3, 6, 9, count - 1).filter { it in 0 until count }.toSet()
    return setOf(0, (count - 1) / 3, (count - 1) * 2 / 3, count - 1)
}

private fun bucketIndexOf(buckets: List<StatsBucket>, ts: Long): Int {
    var lo = 0
    var hi = buckets.lastIndex
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val b = buckets[mid]
        when {
            ts < b.startMillis -> hi = mid - 1
            ts >= b.endMillis -> lo = mid + 1
            else -> return mid
        }
    }
    return -1
}

private fun netExpenseOf(tx: TransactionEntity): Long = (tx.amountCnyCents - tx.refundedAmountCents).coerceAtLeast(0L)

/**
 * 单笔账单对「计入净资产」账户的资产 / 负债影响（本位币分），与账务引擎记账时的余额变动方向一致。
 * 返回 (资产变化, 负债变化)。账户已删除或不计入净资产时无影响。
 */
private fun netWorthImpact(tx: TransactionEntity, walletMap: Map<Long, WalletEntity>): Pair<Long, Long> {
    val rate = if (tx.exchangeRateToCny > 0.0) tx.exchangeRateToCny else 1.0
    val amount = tx.amountCnyCents
    val fee = (tx.feeCents * rate).roundToLong()
    var dA = 0L
    var dL = 0L
    fun included(id: Long?): WalletEntity? = id?.let { walletMap[it] }?.takeIf { it.includeInNetWorth }
    // 资金流出：资产减少；信用账户欠款增加
    fun outflow(w: WalletEntity?, cents: Long) {
        if (w == null) return
        if (w.type.isLiability) dL += cents else dA -= cents
    }
    // 资金流入：资产增加；信用账户欠款减少
    fun inflow(w: WalletEntity?, cents: Long) {
        if (w == null) return
        if (w.type.isLiability) dL -= cents else dA += cents
    }
    when (tx.type) {
        TransactionType.EXPENSE, TransactionType.LOAN_OUT -> outflow(included(tx.sourceWalletId), amount + fee)
        TransactionType.INCOME, TransactionType.LOAN_IN -> inflow(included(tx.targetWalletId ?: tx.sourceWalletId), amount - fee)
        TransactionType.TRANSFER -> {
            outflow(included(tx.sourceWalletId), amount + fee)
            inflow(included(tx.targetWalletId), amount)
        }
        TransactionType.REPAYMENT -> {
            // 账务引擎对付款账户一律扣减余额，对被还款账户一律减少余额（欠款）
            included(tx.sourceWalletId)?.let { w -> if (w.type.isLiability) dL -= amount + fee else dA -= amount + fee }
            included(tx.targetWalletId)?.let { w -> if (w.type.isLiability) dL -= amount else dA -= amount }
        }
        TransactionType.REFUND_OFFSET -> inflow(included(tx.targetWalletId ?: tx.sourceWalletId), amount)
    }
    return dA to dL
}

@Composable
fun ICostStatisticsScreen(
    transactions: List<TransactionEntity>,
    wallets: List<WalletEntity> = emptyList(),
    initialYear: Int,
    initialMonth: Int,
    @Suppress("UNUSED_PARAMETER") onOpenAiSandbox: () -> Unit = {},
    onOpenSettingsPage: () -> Unit,
    currentLedger: String? = null,
    hideAmounts: Boolean = false
) {
    var rangeMode by rememberSaveable { mutableStateOf(StatsRangeMode.MONTH) }
    var year by rememberSaveable { mutableIntStateOf(initialYear) }
    var month by rememberSaveable { mutableIntStateOf(initialMonth) }
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    var filterWalletId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showFilterMenu by remember { mutableStateOf(false) }

    var overviewExpanded by rememberSaveable { mutableStateOf(true) }
    var reimburseExpanded by rememberSaveable { mutableStateOf(true) }
    var transferExpanded by rememberSaveable { mutableStateOf(true) }
    var barChartExpanded by rememberSaveable { mutableStateOf(true) }
    var trendChartExpanded by rememberSaveable { mutableStateOf(true) }
    var donutChartExpanded by rememberSaveable { mutableStateOf(true) }

    var barChartMetric by rememberSaveable { mutableIntStateOf(0) } // 0: 支出, 1: 收入, 2: 结余
    var trendMetric by rememberSaveable { mutableIntStateOf(0) }    // 0: 净资产, 1: 总资产, 2: 总负债
    var donutMetric by rememberSaveable { mutableIntStateOf(0) }    // 0: 支出, 1: 收入

    // 筛选的账户被删除后自动回到「全部账户」
    LaunchedEffect(wallets, filterWalletId) {
        if (filterWalletId != null && wallets.isNotEmpty() && wallets.none { it.id == filterWalletId }) filterWalletId = null
    }

    // 三大图表入场/切换动效进度（柱状拔高 / 趋势曲线铺展 / 环形展开）
    val barProgress = remember { Animatable(0f) }
    LaunchedEffect(rangeMode, year, month, weekOffset, filterWalletId, barChartMetric, currentLedger) {
        barProgress.snapTo(0f)
        barProgress.animateTo(1f, tween(durationMillis = 520, easing = FastOutSlowInEasing))
    }

    val trendProgress = remember { Animatable(0f) }
    LaunchedEffect(rangeMode, year, month, weekOffset, trendMetric) {
        trendProgress.snapTo(0f)
        trendProgress.animateTo(1f, tween(durationMillis = 600, easing = FastOutSlowInEasing))
    }

    val donutProgress = remember { Animatable(0f) }
    LaunchedEffect(rangeMode, year, month, weekOffset, filterWalletId, donutMetric, currentLedger) {
        donutProgress.snapTo(0f)
        donutProgress.animateTo(1f, tween(durationMillis = 560, easing = FastOutSlowInEasing))
    }

    // 当前账本 + 筛选账户（不含时间）
    val scopedTxs = remember(transactions, currentLedger, filterWalletId) {
        transactions.filter { tx ->
            (currentLedger == null || tx.ledgerName == currentLedger) &&
                (filterWalletId == null || tx.sourceWalletId == filterWalletId || tx.targetWalletId == filterWalletId)
        }
    }
    val earliestMillis = remember(scopedTxs) { scopedTxs.minOfOrNull { it.timestamp } }
    val latestMillis = remember(scopedTxs) { scopedTxs.maxOfOrNull { it.timestamp } }

    val range = remember(rangeMode, year, month, weekOffset, earliestMillis, latestMillis) {
        periodRange(rangeMode, year, month, weekOffset, earliestMillis, latestMillis)
    }

    // 根据维度过滤账单（按起止时间判断，周以周一为第一天，跨年周也准确）
    val filteredTxs = remember(scopedTxs, range) {
        if (range == null) emptyList()
        else scopedTxs.filter { it.timestamp >= range.first && it.timestamp < range.second }
    }

    val periodLabel = when (rangeMode) {
        StatsRangeMode.MONTH -> "${year}年${month}月"
        StatsRangeMode.YEAR -> "${year}年"
        StatsRangeMode.WEEK -> when (weekOffset) {
            0 -> "本周"
            -1 -> "上周"
            1 -> "下周"
            else -> {
                val start = Calendar.getInstance().apply { timeInMillis = weekStartMillis(weekOffset) }
                val end = Calendar.getInstance().apply { timeInMillis = start.timeInMillis; add(Calendar.DAY_OF_MONTH, 6) }
                val thisYear = Calendar.getInstance().get(Calendar.YEAR)
                val prefix = if (start.get(Calendar.YEAR) != thisYear) "${start.get(Calendar.YEAR)}年" else ""
                "$prefix${start.get(Calendar.MONTH) + 1}月${start.get(Calendar.DAY_OF_MONTH)}日 - " +
                    "${end.get(Calendar.MONTH) + 1}月${end.get(Calendar.DAY_OF_MONTH)}日"
            }
        }
        StatsRangeMode.ALL -> "全部账单"
    }

    // 核心统计指标计算（remember 缓存，避免图表动画期间重复遍历账单列表；平账校准流水不计入日常收支统计）
    val netExpenseCents = remember(filteredTxs) {
        filteredTxs.filter { it.type == TransactionType.EXPENSE && it.category != "平账" }.sumOf { netExpenseOf(it) }
    }
    val totalIncomeCents = remember(filteredTxs) {
        filteredTxs.filter { it.type == TransactionType.INCOME && it.category != "平账" }.sumOf { it.amountCnyCents }
    }
    val balanceCents = totalIncomeCents - netExpenseCents

    // 日均支出除数：当前周期按已过天数，过去周期按整段天数，全部按首笔账单至今天数
    val daysDivisor = remember(range) {
        if (range == null) 1L
        else {
            val todayEnd = startOfDay(System.currentTimeMillis()).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
            val effectiveEnd = if (todayEnd in (range.first + 1)..range.second) todayEnd else range.second
            ((effectiveEnd - range.first + DAY_MILLIS / 2) / DAY_MILLIS).coerceAtLeast(1L)
        }
    }
    val dailyAvgExpenseCents = netExpenseCents / daysDivisor

    val totalRefundCents = remember(filteredTxs) {
        filteredTxs.filter { it.type == TransactionType.REFUND_OFFSET }.sumOf { it.amountCnyCents }
    }
    val totalDiscountCents = remember(filteredTxs) { filteredTxs.sumOf { it.discountCents } }
    val totalFeeCents = remember(filteredTxs) {
        filteredTxs.sumOf { (it.feeCents * (if (it.exchangeRateToCny > 0.0) it.exchangeRateToCny else 1.0)).roundToLong() }
    }

    val pendingReimburseCents = remember(filteredTxs) {
        filteredTxs.filter { it.reimbursable && !it.reimbursed }.sumOf { netExpenseOf(it) }
    }
    val settledReimburseCents = remember(filteredTxs) {
        filteredTxs.filter { it.reimbursable && it.reimbursed }.sumOf { netExpenseOf(it) }
    }

    val repaymentCents = remember(filteredTxs) {
        filteredTxs.filter { it.type == TransactionType.REPAYMENT }.sumOf { it.amountCnyCents }
    }
    val transferCents = remember(filteredTxs) {
        filteredTxs.filter { it.type == TransactionType.TRANSFER }.sumOf { it.amountCnyCents }
    }
    val loanInCents = remember(filteredTxs) {
        filteredTxs.filter { it.type == TransactionType.LOAN_IN }.sumOf { it.amountCnyCents }
    }
    val loanOutCents = remember(filteredTxs) {
        filteredTxs.filter { it.type == TransactionType.LOAN_OUT }.sumOf { it.amountCnyCents }
    }
    val installmentPayCents = remember(filteredTxs) {
        filteredTxs.filter { tx ->
            tx.type == TransactionType.EXPENSE &&
                (tx.captureSource == "INSTALLMENT" || tx.tags.split(",", "，").any { it.trim() == "分期" })
        }.sumOf { netExpenseOf(it) }
    }

    // 图表分桶（柱状图与趋势图共用，刻度与峰值标签来自同一组分桶）
    val buckets = remember(rangeMode, range) {
        if (range == null) emptyList() else buildBuckets(rangeMode, range)
    }
    val axisIndices = remember(rangeMode, buckets.size) { axisLabelIndices(rangeMode, buckets.size) }

    val bars = remember(filteredTxs, barChartMetric, buckets) {
        val exp = LongArray(buckets.size)
        val inc = LongArray(buckets.size)
        filteredTxs.forEach { tx ->
            val i = bucketIndexOf(buckets, tx.timestamp)
            if (i >= 0) {
                when (tx.type) {
                    TransactionType.EXPENSE -> exp[i] += netExpenseOf(tx)
                    TransactionType.INCOME -> inc[i] += tx.amountCnyCents
                    else -> {}
                }
            }
        }
        List(buckets.size) { i ->
            when (barChartMetric) {
                0 -> exp[i]
                1 -> inc[i]
                else -> inc[i] - exp[i]
            }
        }
    }

    // 净资产趋势：从当前余额出发，逐笔倒推每个分桶结束时的资产与负债（仅计入净资产的账户，折算人民币）
    val walletMap = remember(wallets) { wallets.associateBy { it.id } }
    val currentAssetsCents = wallets.filter { !it.type.isLiability && it.includeInNetWorth }.sumOf {
        (it.balanceCents * CurrencyCode.fromCode(it.currency).defaultRateToCny).roundToLong()
    }
    val currentLiabCents = wallets.filter { it.type.isLiability && it.includeInNetWorth }.sumOf {
        (it.balanceCents * CurrencyCode.fromCode(it.currency).defaultRateToCny).roundToLong()
    }
    val currentTrendValue = when (trendMetric) {
        0 -> currentAssetsCents - currentLiabCents
        1 -> currentAssetsCents
        else -> currentLiabCents
    }
    val trendPoints: List<Long> = remember(transactions, walletMap, buckets, trendMetric, currentAssetsCents, currentLiabCents) {
        if (walletMap.isEmpty() || buckets.isEmpty()) return@remember emptyList()
        val now = System.currentTimeMillis()
        val visible = buckets.filter { it.startMillis <= now }
        if (visible.isEmpty()) return@remember emptyList()
        val impacts = transactions.map { it.timestamp to netWorthImpact(it, walletMap) }
        visible.map { b ->
            var a = currentAssetsCents
            var l = currentLiabCents
            if (b.endMillis <= now) {
                impacts.forEach { (ts, impact) ->
                    if (ts >= b.endMillis) {
                        a -= impact.first
                        l -= impact.second
                    }
                }
            }
            when (trendMetric) {
                0 -> a - l
                1 -> a
                else -> l
            }
        }
    }

    val categoryBreakdown = remember(filteredTxs, donutMetric) {
        val targetType = if (donutMetric == 0) TransactionType.EXPENSE else TransactionType.INCOME
        filteredTxs
            .filter { it.type == targetType }
            .groupBy { it.category.substringBefore("-") }
            .mapValues { (_, list) ->
                val sum = if (targetType == TransactionType.EXPENSE) {
                    list.sumOf { netExpenseOf(it) }
                } else {
                    list.sumOf { it.amountCnyCents }
                }
                sum to list.size
            }
            .entries
            .filter { it.value.first > 0L }
            .sortedByDescending { it.value.first }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 115.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 顶部栏：右侧「筛选」白色胶囊 + 薄荷绿插画头像（对齐 08_stats_top.png）
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    Surface(
                        onClick = { showFilterMenu = true },
                        shape = RoundedCornerShape(20.dp),
                        color = ICostCardWhite,
                        modifier = Modifier.heightIn(min = 40.dp)
                    ) {
                        val activeWalletName = wallets.firstOrNull { it.id == filterWalletId }?.name
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
                            Text(
                                text = activeWalletName ?: "筛选",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (filterWalletId != null) Color(0xFF1677FF) else ICostTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showFilterMenu,
                        onDismissRequest = { showFilterMenu = false },
                        containerColor = ICostCardWhite
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "全部账户",
                                    fontSize = 14.sp,
                                    fontWeight = if (filterWalletId == null) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                filterWalletId = null
                                showFilterMenu = false
                            }
                        )
                        if (wallets.isNotEmpty()) HorizontalDivider(color = ICostDivider)
                        wallets.forEach { w ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        w.name,
                                        fontSize = 14.sp,
                                        fontWeight = if (filterWalletId == w.id) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    filterWalletId = w.id
                                    showFilterMenu = false
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                ICostAvatarButton(
                    onClick = onOpenSettingsPage,
                    size = 42.dp
                )
            }
        }

        // 2. 超大加粗「统计」标题
        item {
            Text(
                text = "统计",
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ICostTextPrimary
            )
        }

        // 3. 四段白色胶囊切换器 (周 | 月 | 年 | 全部)
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = ICostCardWhite,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatsRangeMode.entries.forEach { mode ->
                        val selected = rangeMode == mode
                        val segBg by animateColorAsState(
                            targetValue = if (selected) Color(0xFFECECEE) else Color.Transparent,
                            animationSpec = tween(180),
                            label = "StatsRangeSegBg"
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(19.dp))
                                .background(segBg)
                                .iCostPressScale(pressedScale = 0.92f) { rangeMode = mode },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = mode.label,
                                fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = ICostTextPrimary
                            )
                        }
                    }
                }
            }
        }

        // 4. 蓝色左右圆按钮时间选择器（支持右滑切上一周期 / 左滑切下一周期）
        if (rangeMode != StatsRangeMode.ALL) {
            item {
                val goPrevPeriod = {
                    when (rangeMode) {
                        StatsRangeMode.MONTH -> if (month == 1) { month = 12; year -= 1 } else month -= 1
                        StatsRangeMode.YEAR -> year -= 1
                        StatsRangeMode.WEEK -> weekOffset -= 1
                        StatsRangeMode.ALL -> {}
                    }
                }
                val goNextPeriod = {
                    when (rangeMode) {
                        StatsRangeMode.MONTH -> if (month == 12) { month = 1; year += 1 } else month += 1
                        StatsRangeMode.YEAR -> year += 1
                        StatsRangeMode.WEEK -> weekOffset += 1
                        StatsRangeMode.ALL -> {}
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .iCostHorizontalSwipeTrigger(
                            onSwipeRight = goPrevPeriod,
                            onSwipeLeft = goNextPeriod
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .iCostPressScale(pressedScale = 0.85f, onClick = goPrevPeriod),
                        contentAlignment = Alignment.Center
                    ) {
                        ICostAssetImage(
                            assetFile = "chart_date_left.webp",
                            contentDescription = "上一期",
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Text(
                        text = periodLabel,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextPrimary
                    )

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .iCostPressScale(pressedScale = 0.85f, onClick = goNextPeriod),
                        contentAlignment = Alignment.Center
                    ) {
                        ICostAssetImage(
                            assetFile = "chart_date_right.webp",
                            contentDescription = "下一期",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }

        // 5. 卡片一：收支统计 (chart_money_red.webp + 2x4 Bento Grid)
        item {
            ICostBentoStatsCard(
                title = "收支统计",
                badgeAsset = "chart_money_red.webp",
                expanded = overviewExpanded,
                onToggle = { overviewExpanded = !overviewExpanded },
                cells = listOf(
                    "支出" to formatMoneyCents(netExpenseCents),
                    "收入" to formatMoneyCents(totalIncomeCents),
                    "结余" to formatMoneyCents(balanceCents),
                    "日均支出" to formatMoneyCents(dailyAvgExpenseCents),
                    "退款" to formatMoneyCents(totalRefundCents),
                    "优惠" to formatMoneyCents(totalDiscountCents),
                    "手续费" to formatMoneyCents(totalFeeCents),
                    "账单数" to "${filteredTxs.size}"
                )
            )
        }

        // 6. 卡片二：报销统计
        item {
            ICostBentoStatsCard(
                title = "报销统计",
                badgeAsset = "chart_budget_red.webp",
                expanded = reimburseExpanded,
                onToggle = { reimburseExpanded = !reimburseExpanded },
                cells = listOf(
                    "待报销" to formatMoneyCents(pendingReimburseCents),
                    "已报销" to formatMoneyCents(settledReimburseCents)
                )
            )
        }

        // 7. 卡片三：流转统计
        item {
            ICostBentoStatsCard(
                title = "流转统计",
                badgeAsset = "chart_transfer.webp",
                expanded = transferExpanded,
                onToggle = { transferExpanded = !transferExpanded },
                cells = listOf(
                    "还款" to formatMoneyCents(repaymentCents),
                    "转账" to formatMoneyCents(transferCents),
                    "借入" to formatMoneyCents(loanInCents),
                    "借出" to formatMoneyCents(loanOutCents),
                    "分期还款" to formatMoneyCents(installmentPayCents)
                )
            )
        }

        // 8. 卡片四：收支柱状图（左右滑动切换 支出 / 收入 / 结余）
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .iCostHorizontalSwipeTrigger(
                        onSwipeRight = { barChartMetric = (barChartMetric + 2) % 3 },
                        onSwipeLeft = { barChartMetric = (barChartMetric + 1) % 3 }
                    ),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    StatsCardHeader(
                        badgeAsset = "chart_money_red.webp",
                        title = when (barChartMetric) {
                            0 -> "支出统计图"
                            1 -> "收入统计图"
                            else -> "结余统计图"
                        },
                        expanded = barChartExpanded,
                        onToggle = { barChartExpanded = !barChartExpanded }
                    )

                    AnimatedVisibility(
                        visible = barChartExpanded,
                        enter = expandVertically(
                            animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
                        ) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(180)) + fadeOut(tween(130))
                    ) {
                        Column(modifier = Modifier.padding(top = 14.dp)) {
                            val hasData = bars.any { it != 0L }
                            val barColor = when (barChartMetric) {
                                0 -> ICostCoralRed
                                1 -> ICostGreenIncome
                                else -> Color(0xFF3B82F6)
                            }

                            if (!hasData) {
                                StatsEmptyChart(height = 165.dp)
                            } else {
                                val maxPos = bars.maxOf { it }.coerceAtLeast(0L)
                                val maxNeg = (-bars.minOf { it }).coerceAtLeast(0L)
                                val peakIdx = if (barChartMetric == 2) {
                                    bars.indices.maxByOrNull { abs(bars[it]) } ?: 0
                                } else {
                                    bars.indices.maxByOrNull { bars[it] } ?: 0
                                }
                                val peakCents = bars.getOrElse(peakIdx) { 0L }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = formatMoneyCents(max(maxPos, maxNeg)),
                                        fontSize = 10.sp,
                                        color = ICostTextMuted
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = barColor.copy(alpha = 0.12f)
                                    ) {
                                        Text(
                                            text = "峰值 ${buckets.getOrNull(peakIdx)?.fullLabel.orEmpty()} ${formatMoneyCents(peakCents)}",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = barColor,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Canvas(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(145.dp)
                                ) {
                                    val animFrac = barProgress.value
                                    val w = size.width
                                    val h = size.height
                                    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                                    val topY = h * 0.06f
                                    val bottomY = h * 0.96f
                                    val usableH = bottomY - topY
                                    val total = (maxPos + maxNeg).coerceAtLeast(1L).toFloat()
                                    // 有负值（结余为负）时基线上移，负值柱向下绘制
                                    val baseY = topY + usableH * (maxPos.toFloat() / total)

                                    listOf(topY, (topY + bottomY) / 2f, bottomY).forEach { y ->
                                        drawLine(
                                            color = Color(0xFFEEEEEE),
                                            start = Offset(0f, y),
                                            end = Offset(w, y),
                                            strokeWidth = 1.dp.toPx(),
                                            pathEffect = dashEffect
                                        )
                                    }
                                    if (maxNeg > 0L) {
                                        drawLine(
                                            color = Color(0xFFDDDDDD),
                                            start = Offset(0f, baseY),
                                            end = Offset(w, baseY),
                                            strokeWidth = 1.dp.toPx()
                                        )
                                    }

                                    val count = bars.size.coerceAtLeast(1)
                                    val slotW = w / count
                                    val barW = (slotW * 0.56f).coerceIn(3.dp.toPx(), 22.dp.toPx())

                                    bars.forEachIndexed { i, v ->
                                        val x = i * slotW + (slotW - barW) / 2f
                                        if (v == 0L) {
                                            drawRoundRect(
                                                color = Color(0xFFF0F0F2),
                                                topLeft = Offset(x, baseY - 3.dp.toPx()),
                                                size = Size(barW, 3.dp.toPx()),
                                                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                                            )
                                        } else {
                                            val bh = ((abs(v).toFloat() / total * usableH) * animFrac)
                                                .coerceAtLeast(4.dp.toPx())
                                            val top = if (v > 0L) baseY - bh else baseY
                                            drawRoundRect(
                                                color = if (v > 0L) barColor else ICostCoralRed,
                                                topLeft = Offset(x, top),
                                                size = Size(barW, bh),
                                                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                BucketAxisLabels(buckets = buckets, visibleIndices = axisIndices)
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            ChartMiniSegmentToggle(
                                options = listOf("支出", "收入", "结余"),
                                selectedIndex = barChartMetric,
                                onSelect = { barChartMetric = it }
                            )
                        }
                    }
                }
            }
        }

        // 9. 卡片五：资产趋势图（按账户余额与账单倒推，左右滑动切换 净资产 / 总资产 / 总负债）
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .iCostHorizontalSwipeTrigger(
                        onSwipeRight = { trendMetric = (trendMetric + 2) % 3 },
                        onSwipeLeft = { trendMetric = (trendMetric + 1) % 3 }
                    ),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    StatsCardHeader(
                        badgeAsset = "chart_trend.webp",
                        title = when (trendMetric) {
                            0 -> "净资产趋势图"
                            1 -> "总资产趋势图"
                            else -> "总负债趋势图"
                        },
                        expanded = trendChartExpanded,
                        onToggle = { trendChartExpanded = !trendChartExpanded }
                    )

                    AnimatedVisibility(
                        visible = trendChartExpanded,
                        enter = expandVertically(
                            animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
                        ) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(180)) + fadeOut(tween(130))
                    ) {
                        Column(modifier = Modifier.padding(top = 14.dp)) {
                            val lineColor = when (trendMetric) {
                                0 -> ICostAmberGold
                                1 -> Color(0xFF3B82F6)
                                else -> ICostCoralRed
                            }
                            val hasTrend = trendPoints.isNotEmpty() && trendPoints.any { it != 0L }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("当前", fontSize = 11.sp, color = ICostTextSecondary)
                                Text(
                                    text = formatMoneyCents(currentTrendValue, "CNY", hideAmounts),
                                    fontFamily = ICDinFontFamily,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ICostTextPrimary
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (!hasTrend) {
                                StatsEmptyChart(height = 135.dp)
                            } else {
                                val points = if (trendPoints.size == 1) listOf(trendPoints[0], trendPoints[0]) else trendPoints

                                Canvas(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(135.dp)
                                ) {
                                    val trendFrac = trendProgress.value
                                    val w = size.width
                                    val h = size.height
                                    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                                    listOf(0.15f, 0.52f, 0.88f).forEach { ry ->
                                        drawLine(
                                            color = Color(0xFFEEEEEE),
                                            start = Offset(0f, h * ry),
                                            end = Offset(w, h * ry),
                                            strokeWidth = 1.dp.toPx(),
                                            pathEffect = dash
                                        )
                                    }

                                    val minP = points.min().toFloat()
                                    val maxP = points.max().toFloat()
                                    val span = (maxP - minP).takeIf { abs(it) > 1f }
                                    // 已绘制分桶数占全部分桶的比例，当前周期未到的部分留白
                                    val drawnWidth = if (buckets.size > 1 && trendPoints.size > 1) {
                                        w * ((trendPoints.size - 1).toFloat() / (buckets.size - 1).toFloat())
                                    } else w

                                    val coords = points.mapIndexed { idx, v ->
                                        val x = drawnWidth * (idx.toFloat() / (points.size - 1))
                                        val norm = if (span == null) 0.5f else (v.toFloat() - minP) / span
                                        val y = h * 0.82f - norm * (h * 0.60f)
                                        Offset(x, y)
                                    }

                                    val linePath = Path().apply {
                                        coords.forEachIndexed { idx, pt ->
                                            if (idx == 0) moveTo(pt.x, pt.y)
                                            else {
                                                val prev = coords[idx - 1]
                                                val cx = (prev.x + pt.x) / 2f
                                                cubicTo(cx, prev.y, cx, pt.y, pt.x, pt.y)
                                            }
                                        }
                                    }

                                    val fillPath = Path().apply {
                                        addPath(linePath)
                                        lineTo(coords.last().x, h * 0.92f)
                                        lineTo(0f, h * 0.92f)
                                        close()
                                    }

                                    clipRect(right = w * trendFrac) {
                                        drawPath(
                                            path = fillPath,
                                            brush = Brush.verticalGradient(
                                                colors = listOf(lineColor.copy(alpha = 0.24f), lineColor.copy(alpha = 0.0f)),
                                                startY = 0f,
                                                endY = h
                                            )
                                        )
                                        drawPath(
                                            path = linePath,
                                            color = lineColor,
                                            style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
                                        )
                                    }
                                    if (trendFrac > 0.92f) {
                                        coords.lastOrNull()?.let { lastPt ->
                                            drawCircle(color = Color.White, radius = 5.dp.toPx(), center = lastPt)
                                            drawCircle(color = lineColor, radius = 3.5.dp.toPx(), center = lastPt)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                BucketAxisLabels(buckets = buckets, visibleIndices = axisIndices)
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            ChartMiniSegmentToggle(
                                options = listOf("净资产", "总资产", "总负债"),
                                selectedIndex = trendMetric,
                                onSelect = { trendMetric = it }
                            )
                        }
                    }
                }
            }
        }

        // 10. 卡片六：分类详情（左右滑动切换 支出分类 / 收入分类）
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .iCostHorizontalSwipeTrigger(
                        onSwipeRight = { donutMetric = (donutMetric + 1) % 2 },
                        onSwipeLeft = { donutMetric = (donutMetric + 1) % 2 }
                    ),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    StatsCardHeader(
                        badgeAsset = "chart_classification.webp",
                        title = if (donutMetric == 0) "支出分类详情" else "收入分类详情",
                        expanded = donutChartExpanded,
                        onToggle = { donutChartExpanded = !donutChartExpanded }
                    )

                    AnimatedVisibility(
                        visible = donutChartExpanded,
                        enter = expandVertically(
                            animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
                        ) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(180)) + fadeOut(tween(130))
                    ) {
                        Column(modifier = Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            val totalTargetCents = if (donutMetric == 0) netExpenseCents else totalIncomeCents

                            if (categoryBreakdown.isEmpty() || totalTargetCents <= 0L) {
                                Text(
                                    text = "暂无数据",
                                    fontSize = 13.sp,
                                    color = ICostTextSecondary,
                                    modifier = Modifier.padding(vertical = 20.dp)
                                )
                            } else {
                                // 多彩环形图与中心汇总金额
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(190.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Canvas(modifier = Modifier.size(152.dp)) {
                                        val donutFrac = donutProgress.value
                                        val strokePx = 24.dp.toPx()
                                        val diameter = size.minDimension - strokePx
                                        val topLeft = Offset(strokePx / 2f, strokePx / 2f)
                                        var startDeg = -90f

                                        categoryBreakdown.forEachIndexed { idx, (_, pair) ->
                                            val sweep = (pair.first.toFloat() / totalTargetCents.toFloat()) * 360f * donutFrac
                                            val sliceColor = ICostChartPalette[idx % ICostChartPalette.size]
                                            drawArc(
                                                color = sliceColor,
                                                startAngle = startDeg,
                                                sweepAngle = (sweep - 1.5f).coerceAtLeast(0.5f),
                                                useCenter = false,
                                                topLeft = topLeft,
                                                size = Size(diameter, diameter),
                                                style = Stroke(width = strokePx, cap = StrokeCap.Butt)
                                            )

                                            // 仅当扇区角度足够宽（>= 15°）时绘制引出短指示线与外圆点，防止极小占比扇区在顶部重叠碰撞
                                            if (donutFrac > 0.65f && sweep >= 15f) {
                                                val midRad = Math.toRadians((startDeg + sweep / 2f).toDouble())
                                                val outerR = diameter / 2f + strokePx / 2f + 4.dp.toPx()
                                                val tipR = outerR + 10.dp.toPx()
                                                val cx = size.width / 2f
                                                val cy = size.height / 2f
                                                val p1 = Offset(
                                                    cx + (outerR * cos(midRad)).toFloat(),
                                                    cy + (outerR * sin(midRad)).toFloat()
                                                )
                                                val p2 = Offset(
                                                    cx + (tipR * cos(midRad)).toFloat(),
                                                    cy + (tipR * sin(midRad)).toFloat()
                                                )
                                                drawLine(sliceColor, p1, p2, strokeWidth = 1.5.dp.toPx())
                                                drawCircle(sliceColor, radius = 2.5.dp.toPx(), center = p2)
                                            }

                                            startDeg += sweep
                                        }
                                    }

                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = if (donutMetric == 0) "总支出" else "总收入",
                                            fontSize = 11.sp,
                                            color = ICostTextSecondary
                                        )
                                        Text(
                                            text = formatMoneyCents(totalTargetCents),
                                            fontFamily = ICDinFontFamily,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ICostTextPrimary
                                        )
                                    }
                                }

                                // 图例胶囊条
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    categoryBreakdown.take(4).forEachIndexed { idx, (cat, pair) ->
                                        val pct = (pair.first * 100.0 / totalTargetCents)
                                        val col = ICostChartPalette[idx % ICostChartPalette.size]
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(col)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "$cat ${String.format(Locale.US, "%.1f%%", pct)}",
                                                fontSize = 11.sp,
                                                color = ICostTextSecondary
                                            )
                                        }
                                    }
                                }

                                // 支出 | 收入 切换胶囊
                                ChartMiniSegmentToggle(
                                    options = listOf("支出", "收入"),
                                    selectedIndex = donutMetric,
                                    onSelect = { donutMetric = it }
                                )

                                HorizontalDivider(color = ICostDivider)

                                // 分类排行列表（对齐 08_stats_bottom.png）
                                categoryBreakdown.forEachIndexed { idx, (cat, pair) ->
                                    val (cents, count) = pair
                                    val ratio = (cents.toFloat() / totalTargetCents.toFloat()).coerceIn(0f, 1f)
                                    val pctStr = String.format(Locale.US, "%.2f%%", ratio * 100f)
                                    val itemColor = ICostChartPalette[idx % ICostChartPalette.size]

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ICostCategoryBadge(
                                            category = cat,
                                            type = if (donutMetric == 0) TransactionType.EXPENSE else TransactionType.INCOME,
                                            size = 38.dp,
                                            badgeColor = itemColor
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = cat,
                                                        fontSize = 15.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = ICostTextPrimary
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = pctStr,
                                                        fontSize = 12.sp,
                                                        color = ICostTextSecondary
                                                    )
                                                }
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = formatMoneyCents(cents),
                                                        fontFamily = ICDinFontFamily,
                                                        fontSize = 16.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = ICostTextPrimary
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = "${count}笔",
                                                        fontSize = 11.sp,
                                                        color = ICostTextSecondary
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            val barFrac = (ratio * donutProgress.value).coerceIn(0f, 1f)
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(5.dp)
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(ICostCellGray)
                                            ) {
                                                if (barFrac > 0f) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth(barFrac.coerceIn(0.025f, 1f))
                                                            .height(5.dp)
                                                            .clip(RoundedCornerShape(3.dp))
                                                            .background(itemColor)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCardHeader(
    badgeAsset: String,
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val chevronRot by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
        label = "StatsHeaderChevron"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .iCostPressScale(pressedScale = 0.98f, onClick = onToggle)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ICostAssetImage(
                assetFile = badgeAsset,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ICostTextPrimary)
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = ICostTextSecondary,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = chevronRot }
        )
    }
}

@Composable
private fun ChartMiniSegmentToggle(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFFF2F2F4)
        ) {
            Row(modifier = Modifier.padding(3.dp)) {
                options.forEachIndexed { idx, text ->
                    val sel = selectedIndex == idx
                    val pillBg by animateColorAsState(
                        targetValue = if (sel) ICostCardWhite else Color.Transparent,
                        animationSpec = tween(180),
                        label = "MiniSegBg"
                    )
                    Box(
                        modifier = Modifier
                            .heightIn(min = 34.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(pillBg)
                            .iCostPressScale(pressedScale = 0.92f) { onSelect(idx) }
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = text,
                            fontSize = 12.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                            color = ICostTextPrimary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ICostBentoStatsCard(
    title: String,
    badgeAsset: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    cells: List<Pair<String, String>>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            StatsCardHeader(
                badgeAsset = badgeAsset,
                title = title,
                expanded = expanded,
                onToggle = onToggle
            )

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
                ) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(130))
            ) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    cells.chunked(2).forEach { pairRow ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            pairRow.forEach { (label, valStr) ->
                                Surface(
                                    shape = RoundedCornerShape(15.dp),
                                    color = ICostCellGray,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                                    ) {
                                        Text(label, fontSize = 12.sp, color = ICostTextSecondary)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = valStr,
                                            fontFamily = ICDinFontFamily,
                                            fontSize = 19.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ICostTextPrimary,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            if (pairRow.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsEmptyChart(height: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(15.dp))
            .background(ICostCellGray),
        contentAlignment = Alignment.Center
    ) {
        Text("暂无数据", fontSize = 13.sp, color = ICostTextSecondary)
    }
}

/** 与柱状图分桶一一对齐的坐标轴标签：每个分桶等宽，只显示选中的刻度。 */
@Composable
private fun BucketAxisLabels(buckets: List<StatsBucket>, visibleIndices: Set<Int>) {
    Row(modifier = Modifier.fillMaxWidth()) {
        buckets.forEachIndexed { idx, b ->
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (idx in visibleIndices) {
                    Text(
                        text = b.axisLabel,
                        fontSize = 10.sp,
                        color = ICostTextMuted,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.wrapContentWidth(unbounded = true)
                    )
                }
            }
        }
    }
}
