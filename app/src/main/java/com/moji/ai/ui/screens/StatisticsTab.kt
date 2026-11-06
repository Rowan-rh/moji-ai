package com.moji.ai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

enum class StatsRangeMode(val label: String) {
    WEEK("周"),
    MONTH("月"),
    YEAR("年"),
    ALL("全部"),
    CUSTOM("范围")
}

@Composable
fun ICostStatisticsScreen(
    transactions: List<TransactionEntity>,
    wallets: List<WalletEntity> = emptyList(),
    initialYear: Int,
    initialMonth: Int,
    onOpenAiSandbox: () -> Unit,
    onOpenSettingsPage: () -> Unit
) {
    var rangeMode by remember { mutableStateOf(StatsRangeMode.MONTH) }
    var year by remember { mutableIntStateOf(initialYear) }
    var month by remember { mutableIntStateOf(initialMonth) }
    var weekOffset by remember { mutableIntStateOf(0) }
    var filterWalletId by remember { mutableStateOf<Long?>(null) }
    var showFilterMenu by remember { mutableStateOf(false) }

    var overviewExpanded by remember { mutableStateOf(true) }
    var reimburseExpanded by remember { mutableStateOf(true) }
    var transferExpanded by remember { mutableStateOf(true) }
    var barChartExpanded by remember { mutableStateOf(true) }
    var trendChartExpanded by remember { mutableStateOf(true) }
    var donutChartExpanded by remember { mutableStateOf(true) }

    var barChartMetric by remember { mutableIntStateOf(0) } // 0: 支出, 1: 收入, 2: 结余
    var trendMetric by remember { mutableIntStateOf(0) }    // 0: 净资产, 1: 总资产, 2: 总负债
    var donutMetric by remember { mutableIntStateOf(0) }    // 0: 支出, 1: 收入

    // 根据维度与筛选账户过滤账单
    val filteredTxs = remember(transactions, rangeMode, year, month, weekOffset, filterWalletId) {
        val cal = Calendar.getInstance()
        val timeFiltered = when (rangeMode) {
            StatsRangeMode.ALL, StatsRangeMode.CUSTOM -> transactions
            StatsRangeMode.YEAR -> transactions.filter {
                cal.timeInMillis = it.timestamp
                cal.get(Calendar.YEAR) == year
            }
            StatsRangeMode.MONTH -> transactions.filter {
                cal.timeInMillis = it.timestamp
                cal.get(Calendar.YEAR) == year && (cal.get(Calendar.MONTH) + 1) == month
            }
            StatsRangeMode.WEEK -> {
                val targetCal = Calendar.getInstance().apply { add(Calendar.WEEK_OF_YEAR, weekOffset) }
                val targetWeek = targetCal.get(Calendar.WEEK_OF_YEAR)
                val targetYear = targetCal.get(Calendar.YEAR)
                transactions.filter {
                    cal.timeInMillis = it.timestamp
                    cal.get(Calendar.YEAR) == targetYear && cal.get(Calendar.WEEK_OF_YEAR) == targetWeek
                }
            }
        }
        if (filterWalletId == null) {
            timeFiltered
        } else {
            timeFiltered.filter { it.sourceWalletId == filterWalletId || it.targetWalletId == filterWalletId }
        }
    }

    val periodLabel = when (rangeMode) {
        StatsRangeMode.MONTH -> "${year}年${month}月"
        StatsRangeMode.YEAR -> "${year}年"
        StatsRangeMode.WEEK -> if (weekOffset == 0) "本周" else "${weekOffset}周"
        StatsRangeMode.ALL -> "全部账单"
        StatsRangeMode.CUSTOM -> "${year}年1月 - ${year}年${month}月"
    }

    // 核心统计指标计算
    val netExpenseCents = filteredTxs
        .filter { it.type == TransactionType.EXPENSE }
        .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }

    val totalIncomeCents = filteredTxs
        .filter { it.type == TransactionType.INCOME }
        .sumOf { it.amountCnyCents }

    val balanceCents = totalIncomeCents - netExpenseCents

    val daysDivisor = remember(rangeMode, year, month) {
        val now = Calendar.getInstance()
        when (rangeMode) {
            StatsRangeMode.WEEK -> 7
            StatsRangeMode.MONTH -> {
                if (now.get(Calendar.YEAR) == year && (now.get(Calendar.MONTH) + 1) == month) {
                    now.get(Calendar.DAY_OF_MONTH).coerceAtLeast(1)
                } else 30
            }
            StatsRangeMode.YEAR -> 365
            StatsRangeMode.ALL, StatsRangeMode.CUSTOM -> 30
        }
    }
    val dailyAvgExpenseCents = netExpenseCents / daysDivisor

    val totalRefundCents = filteredTxs
        .filter { it.type == TransactionType.REFUND_OFFSET }
        .sumOf { it.amountCnyCents }

    val totalDiscountCents = filteredTxs.sumOf { it.discountCents }
    val totalFeeCents = filteredTxs.sumOf { it.feeCents }

    val pendingReimburseCents = filteredTxs
        .filter { it.reimbursable && !it.reimbursed }
        .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }
    val settledReimburseCents = filteredTxs
        .filter { it.reimbursable && it.reimbursed }
        .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }

    val repaymentCents = filteredTxs.filter { it.type == TransactionType.REPAYMENT }.sumOf { it.amountCnyCents }
    val transferCents = filteredTxs.filter { it.type == TransactionType.TRANSFER }.sumOf { it.amountCnyCents }
    val loanInCents = filteredTxs.filter { it.type == TransactionType.LOAN_IN }.sumOf { it.amountCnyCents }
    val loanOutCents = filteredTxs.filter { it.type == TransactionType.LOAN_OUT }.sumOf { it.amountCnyCents }

    // 每日柱状图数据（30 天或 12 个月）
    val daysInPeriod = remember(year, month) {
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    val dailyBars = remember(filteredTxs, barChartMetric, daysInPeriod) {
        val c = Calendar.getInstance()
        (1..daysInPeriod).map { d ->
            val dayTxs = filteredTxs.filter {
                c.timeInMillis = it.timestamp
                c.get(Calendar.DAY_OF_MONTH) == d
            }
            val exp = dayTxs.filter { it.type == TransactionType.EXPENSE }
                .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }
            val inc = dayTxs.filter { it.type == TransactionType.INCOME }
                .sumOf { it.amountCnyCents }
            when (barChartMetric) {
                0 -> exp
                1 -> inc
                else -> (inc - exp).coerceAtLeast(0L)
            }
        }
    }

    // 净资产趋势曲线点（7个采样节点）
    val currentAssetsCents = wallets.filter { !it.type.isLiability && it.includeInNetWorth }.sumOf {
        (it.balanceCents * CurrencyCode.fromCode(it.currency).defaultRateToCny).roundToLong()
    }
    val currentLiabCents = wallets.filter { it.type.isLiability && it.includeInNetWorth }.sumOf {
        (it.balanceCents * CurrencyCode.fromCode(it.currency).defaultRateToCny).roundToLong()
    }
    val baseTrendTarget = when (trendMetric) {
        0 -> currentAssetsCents - currentLiabCents
        1 -> currentAssetsCents
        else -> currentLiabCents
    }

    val categoryBreakdown = remember(filteredTxs, donutMetric) {
        val targetType = if (donutMetric == 0) TransactionType.EXPENSE else TransactionType.INCOME
        filteredTxs
            .filter { it.type == targetType }
            .groupBy { it.category.substringBefore("-") }
            .mapValues { (_, list) ->
                val sum = if (targetType == TransactionType.EXPENSE) {
                    list.sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }
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
                        shape = RoundedCornerShape(18.dp),
                        color = ICostCardWhite
                    ) {
                        val activeWalletName = wallets.firstOrNull { it.id == filterWalletId }?.name
                        Text(
                            text = activeWalletName ?: "筛选",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (filterWalletId != null) Color(0xFF1677FF) else ICostTextPrimary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showFilterMenu,
                        onDismissRequest = { showFilterMenu = false },
                        containerColor = ICostCardWhite
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部账户", fontSize = 14.sp) },
                            onClick = {
                                filterWalletId = null
                                showFilterMenu = false
                            }
                        )
                        wallets.forEach { w ->
                            DropdownMenuItem(
                                text = { Text(w.name, fontSize = 14.sp) },
                                onClick = {
                                    filterWalletId = w.id
                                    showFilterMenu = false
                                }
                            )
                        }
                        HorizontalDivider(color = ICostDivider)
                        DropdownMenuItem(
                            text = { Text("AI 识账沙盒测试", fontSize = 14.sp, color = ICostCoralRed) },
                            onClick = {
                                showFilterMenu = false
                                onOpenAiSandbox()
                            }
                        )
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

        // 3. 1:1 复刻 iCost 五段白色胶囊切换器 (周 | 月 | 年 | 全部 | 范围)
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = ICostCardWhite,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatsRangeMode.entries.forEach { mode ->
                        val selected = rangeMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(19.dp))
                                .background(if (selected) Color(0xFFECECEE) else Color.Transparent)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { rangeMode = mode },
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

        // 4. 1:1 复刻蓝色左右圆按钮时间选择器（chart_date_left.webp / chart_date_right.webp）
        if (rangeMode != StatsRangeMode.ALL) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .clickable {
                                when (rangeMode) {
                                    StatsRangeMode.MONTH, StatsRangeMode.CUSTOM ->
                                        if (month == 1) { month = 12; year -= 1 } else month -= 1
                                    StatsRangeMode.YEAR -> year -= 1
                                    StatsRangeMode.WEEK -> weekOffset -= 1
                                    StatsRangeMode.ALL -> {}
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        ICostAssetImage(
                            assetFile = "chart_date_left.webp",
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
                            .size(30.dp)
                            .clip(CircleShape)
                            .clickable {
                                when (rangeMode) {
                                    StatsRangeMode.MONTH, StatsRangeMode.CUSTOM ->
                                        if (month == 12) { month = 1; year += 1 } else month += 1
                                    StatsRangeMode.YEAR -> year += 1
                                    StatsRangeMode.WEEK -> weekOffset += 1
                                    StatsRangeMode.ALL -> {}
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        ICostAssetImage(
                            assetFile = "chart_date_right.webp",
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

        // 6. 卡片二：报销统计 (chart_budget_red.webp + 2x2 Bento Grid)
        item {
            ICostBentoStatsCard(
                title = "报销统计",
                badgeAsset = "chart_budget_red.webp",
                expanded = reimburseExpanded,
                onToggle = { reimburseExpanded = !reimburseExpanded },
                cells = listOf(
                    "待报销" to formatMoneyCents(pendingReimburseCents),
                    "已报销" to formatMoneyCents(settledReimburseCents),
                    "报销入账" to formatMoneyCents(settledReimburseCents),
                    "报销差额" to formatMoneyCents(0L)
                )
            )
        }

        // 7. 卡片三：流转统计 (chart_transfer.webp + 2x3 Bento Grid，对齐 08_stats_mid.png)
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
                    "收款" to formatMoneyCents(0L),
                    "分期还款" to formatMoneyCents(0L)
                )
            )
        }

        // 8. 卡片四：支出统计图（1:1 对齐 08_stats_mid.png：30 日柱状图 + 平均值虚线 + 顶部峰值气泡 + 支出|收入|结余 胶囊）
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
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

                    AnimatedVisibility(visible = barChartExpanded) {
                        Column(modifier = Modifier.padding(top = 14.dp)) {
                            val maxVal = (dailyBars.maxOrNull() ?: 0L).coerceAtLeast(1000L)
                            val maxIdx = dailyBars.indexOfFirst { it == dailyBars.maxOrNull() }.coerceAtLeast(0)
                            val barColor = when (barChartMetric) {
                                0 -> ICostCoralRed
                                1 -> ICostGreenIncome
                                else -> Color(0xFF3B82F6)
                            }

                            // 峰值提示胶囊
                            val peakCents = dailyBars.getOrElse(maxIdx) { 0L }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = formatMoneyCents(maxVal),
                                    fontSize = 10.sp,
                                    color = ICostTextMuted
                                )
                                if (peakCents > 0L) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = barColor.copy(alpha = 0.12f)
                                    ) {
                                        Text(
                                            text = "${month}/${maxIdx + 1} Peak: ${formatMoneyCents(peakCents)}",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = barColor,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(145.dp)
                            ) {
                                val w = size.width
                                val h = size.height
                                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)

                                // 3 条水平参考虚线
                                listOf(0.15f, 0.55f, 0.95f).forEach { ratioY ->
                                    drawLine(
                                        color = Color(0xFFEEEEEE),
                                        start = Offset(0f, h * ratioY),
                                        end = Offset(w, h * ratioY),
                                        strokeWidth = 1.dp.toPx(),
                                        pathEffect = dashEffect
                                    )
                                }

                                val count = dailyBars.size.coerceAtLeast(1)
                                val slotW = w / count
                                val barW = (slotW * 0.56f).coerceAtLeast(3.dp.toPx())
                                val usableH = h * 0.85f
                                val baseY = h * 0.95f

                                dailyBars.forEachIndexed { i, v ->
                                    val bh = if (v <= 0L) {
                                        3.dp.toPx()
                                    } else {
                                        (v.toFloat() / maxVal.toFloat() * usableH).coerceAtLeast(5.dp.toPx())
                                    }
                                    val x = i * slotW + (slotW - barW) / 2f
                                    drawRoundRect(
                                        color = if (v > 0L) barColor else Color(0xFFF0F0F2),
                                        topLeft = Offset(x, baseY - bh),
                                        size = Size(barW, bh),
                                        cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${month}/01", fontSize = 10.sp, color = ICostTextMuted)
                                Text("${month}/10", fontSize = 10.sp, color = ICostTextMuted)
                                Text("${month}/20", fontSize = 10.sp, color = ICostTextMuted)
                                Text("${month}/$daysInPeriod", fontSize = 10.sp, color = ICostTextMuted)
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

        // 9. 卡片五：净资产趋势图（1:1 对齐 08_stats_bottom.png：平滑琥珀金曲线 + 渐变面积填充 + 净资产|总资产|总负债）
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
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

                    AnimatedVisibility(visible = trendChartExpanded) {
                        Column(modifier = Modifier.padding(top = 14.dp)) {
                            val lineColor = when (trendMetric) {
                                0 -> ICostAmberGold
                                1 -> Color(0xFF3B82F6)
                                else -> ICostCoralRed
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("当前", fontSize = 11.sp, color = ICostTextSecondary)
                                Text(
                                    text = formatMoneyCents(baseTrendTarget),
                                    fontFamily = ICDinFontFamily,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ICostTextPrimary
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            val points = remember(baseTrendTarget, netExpenseCents, totalIncomeCents) {
                                val end = baseTrendTarget.toFloat()
                                val delta = (totalIncomeCents - netExpenseCents).toFloat()
                                val start = end - delta
                                listOf(
                                    start,
                                    start + delta * 0.18f,
                                    start + delta * 0.32f,
                                    start + delta * 0.55f,
                                    start + delta * 0.72f,
                                    start + delta * 0.88f,
                                    end
                                )
                            }

                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(135.dp)
                            ) {
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

                                val minP = points.minOrNull() ?: 0f
                                val maxP = points.maxOrNull() ?: 1f
                                val span = (maxP - minP).takeIf { abs(it) > 1f } ?: 100f

                                val coords = points.mapIndexed { idx, v ->
                                    val x = w * (idx.toFloat() / (points.size - 1))
                                    val norm = (v - minP) / span
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
                                    lineTo(w, h * 0.92f)
                                    lineTo(0f, h * 0.92f)
                                    close()
                                }

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
                                coords.lastOrNull()?.let { lastPt ->
                                    drawCircle(color = Color.White, radius = 5.dp.toPx(), center = lastPt)
                                    drawCircle(color = lineColor, radius = 3.5.dp.toPx(), center = lastPt)
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${month}/01", fontSize = 10.sp, color = ICostTextMuted)
                                Text("${month}/10", fontSize = 10.sp, color = ICostTextMuted)
                                Text("${month}/20", fontSize = 10.sp, color = ICostTextMuted)
                                Text("${month}/$daysInPeriod", fontSize = 10.sp, color = ICostTextMuted)
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

        // 10. 卡片六：支出分类详情（1:1 对齐 08_stats_bottom.png：多彩环形图 + 外引折线百分比 + 居中总额 + 进度排行）
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
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

                    AnimatedVisibility(visible = donutChartExpanded) {
                        Column(modifier = Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            val totalTargetCents = if (donutMetric == 0) netExpenseCents else totalIncomeCents

                            if (categoryBreakdown.isEmpty() || totalTargetCents <= 0L) {
                                Text(
                                    text = "当前时间段暂无分类记录",
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
                                        val strokePx = 24.dp.toPx()
                                        val diameter = size.minDimension - strokePx
                                        val topLeft = Offset(strokePx / 2f, strokePx / 2f)
                                        var startDeg = -90f

                                        categoryBreakdown.forEachIndexed { idx, (_, pair) ->
                                            val sweep = (pair.first.toFloat() / totalTargetCents.toFloat()) * 360f
                                            val sliceColor = ICostChartPalette[idx % ICostChartPalette.size]
                                            drawArc(
                                                color = sliceColor,
                                                startAngle = startDeg,
                                                sweepAngle = (sweep - 1.5f).coerceAtLeast(1f),
                                                useCenter = false,
                                                topLeft = topLeft,
                                                size = Size(diameter, diameter),
                                                style = Stroke(width = strokePx, cap = StrokeCap.Butt)
                                            )

                                            // 绘制引出短指示线与外圆点
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
                                                    Icon(
                                                        imageVector = Icons.Default.ChevronRight,
                                                        contentDescription = null,
                                                        tint = ICostTextMuted,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            LinearProgressIndicator(
                                                progress = { ratio },
                                                color = itemColor,
                                                trackColor = ICostCellGray,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(5.dp)
                                                    .clip(RoundedCornerShape(3.dp))
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

@Composable
private fun StatsCardHeader(
    badgeAsset: String,
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle
            )
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
            imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = ICostTextSecondary,
            modifier = Modifier.size(18.dp)
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
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFF2F2F4)
        ) {
            Row(modifier = Modifier.padding(3.dp)) {
                options.forEachIndexed { idx, text ->
                    val sel = selectedIndex == idx
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(13.dp))
                            .background(if (sel) ICostCardWhite else Color.Transparent)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSelect(idx) }
                            .padding(horizontal = 16.dp, vertical = 5.dp),
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

            AnimatedVisibility(visible = expanded) {
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
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(label, fontSize = 12.sp, color = ICostTextSecondary)
                                            Icon(
                                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                contentDescription = null,
                                                tint = ICostTextSecondary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
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
