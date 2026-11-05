package com.moji.ai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.model.TransactionType
import com.moji.ai.ui.theme.ICostAmberGold
import com.moji.ai.ui.theme.ICostBlueAccent
import com.moji.ai.ui.theme.ICostCardWhite
import com.moji.ai.ui.theme.ICostCellGray
import com.moji.ai.ui.theme.ICostCoralRed
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.formatMoneyCents
import com.moji.ai.ui.theme.resolveCategoryIcon
import java.util.Calendar
import kotlin.math.roundToLong

enum class StatsRangeMode(val label: String) {
    WEEK("周"),
    MONTH("月"),
    YEAR("年"),
    ALL("全部")
}

@Composable
fun ICostStatisticsScreen(
    transactions: List<TransactionEntity>,
    initialYear: Int,
    initialMonth: Int,
    onOpenAiSandbox: () -> Unit,
    onOpenAiSettings: () -> Unit
) {
    var rangeMode by remember { mutableStateOf(StatsRangeMode.MONTH) }
    var year by remember { mutableIntStateOf(initialYear) }
    var month by remember { mutableIntStateOf(initialMonth) }
    var weekOffset by remember { mutableIntStateOf(0) }

    var overviewExpanded by remember { mutableStateOf(true) }
    var reimburseExpanded by remember { mutableStateOf(true) }
    var transferExpanded by remember { mutableStateOf(true) }

    // 根据维度过滤账单
    val filteredTxs = remember(transactions, rangeMode, year, month, weekOffset) {
        val cal = Calendar.getInstance()
        when (rangeMode) {
            StatsRangeMode.ALL -> transactions
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
    }

    val periodLabel = when (rangeMode) {
        StatsRangeMode.MONTH -> "${year}年${month}月"
        StatsRangeMode.YEAR -> "${year}年"
        StatsRangeMode.WEEK -> if (weekOffset == 0) "本周" else "${weekOffset}周"
        StatsRangeMode.ALL -> "全部账单"
    }

    // 各项统计指标计算
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
            StatsRangeMode.ALL -> 30
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

    val categoryBreakdown = remember(filteredTxs) {
        filteredTxs
            .filter { it.type == TransactionType.EXPENSE }
            .groupBy { it.category }
            .mapValues { (_, list) ->
                val sum = list.sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }
                sum to list.size
            }
            .entries
            .filter { it.value.first > 0L }
            .sortedByDescending { it.value.first }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 顶部标题栏
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "统计",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = ICostTextPrimary
                )
                Surface(
                    onClick = onOpenAiSandbox,
                    shape = RoundedCornerShape(16.dp),
                    color = ICostCardWhite
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = ICostCoralRed, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("AI 沙盒", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ICostTextPrimary)
                    }
                }
            }
        }

        // 2. 1:1 复刻 iCost 分段胶囊切换器 (周 | 月 | 年 | 全部)
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFFEBEBED),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatsRangeMode.entries.forEach { mode ->
                        val selected = rangeMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (selected) ICostCardWhite else Color.Transparent)
                                .clickable { rangeMode = mode },
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

        // 3. 1:1 复刻蓝色左右圆按钮时间选择器
        if (rangeMode != StatsRangeMode.ALL) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = {
                            when (rangeMode) {
                                StatsRangeMode.MONTH -> if (month == 1) { month = 12; year -= 1 } else month -= 1
                                StatsRangeMode.YEAR -> year -= 1
                                StatsRangeMode.WEEK -> weekOffset -= 1
                                StatsRangeMode.ALL -> {}
                            }
                        },
                        shape = CircleShape,
                        color = ICostBlueAccent,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一期", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }

                    Text(
                        text = periodLabel,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextPrimary
                    )

                    Surface(
                        onClick = {
                            when (rangeMode) {
                                StatsRangeMode.MONTH -> if (month == 12) { month = 1; year += 1 } else month += 1
                                StatsRangeMode.YEAR -> year += 1
                                StatsRangeMode.WEEK -> weekOffset += 1
                                StatsRangeMode.ALL -> {}
                            }
                        },
                        shape = CircleShape,
                        color = ICostBlueAccent,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "下一期", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        // 4. 卡片一：收支统计 (2x4 Bento Grid)
        item {
            ICostBentoStatsCard(
                title = "收支统计",
                badgeIcon = Icons.Default.AccountBalanceWallet,
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
                    "记账笔数" to "${filteredTxs.size}笔"
                )
            )
        }

        // 5. 卡片二：报销统计 (2x2 Bento Grid)
        item {
            ICostBentoStatsCard(
                title = "报销统计",
                badgeIcon = Icons.Default.TaskAlt,
                expanded = reimburseExpanded,
                onToggle = { reimburseExpanded = !reimburseExpanded },
                cells = listOf(
                    "待报销" to formatMoneyCents(pendingReimburseCents),
                    "已报销" to formatMoneyCents(settledReimburseCents),
                    "报销入账" to formatMoneyCents(settledReimburseCents),
                    "待报销笔数" to "${filteredTxs.count { it.reimbursable && !it.reimbursed }}笔"
                )
            )
        }

        // 6. 卡片三：流转统计 (2x2 Bento Grid)
        item {
            ICostBentoStatsCard(
                title = "流转统计",
                badgeIcon = Icons.Default.SwapHoriz,
                expanded = transferExpanded,
                onToggle = { transferExpanded = !transferExpanded },
                cells = listOf(
                    "还款" to formatMoneyCents(repaymentCents),
                    "转账" to formatMoneyCents(transferCents),
                    "借入" to formatMoneyCents(loanInCents),
                    "借出" to formatMoneyCents(loanOutCents)
                )
            )
        }

        // 7. 卡片四：支出分类排行
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(ICostCoralRed),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PieChart, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("支出分类排行", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ICostTextPrimary)
                    }

                    if (categoryBreakdown.isEmpty()) {
                        Text("当前时间段暂无支出记录", fontSize = 13.sp, color = ICostTextSecondary)
                    } else {
                        val denom = netExpenseCents.coerceAtLeast(1L)
                        categoryBreakdown.forEach { (cat, pair) ->
                            val (cents, count) = pair
                            val ratio = (cents.toFloat() / denom.toFloat()).coerceIn(0f, 1f)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(ICostCoralRed),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = resolveCategoryIcon(cat, TransactionType.EXPENSE),
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "$cat  ${(ratio * 100).roundToLong()}% ($count 笔)",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ICostTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = formatMoneyCents(cents),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ICostTextPrimary,
                                            softWrap = false,
                                            maxLines = 1
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { ratio },
                                        color = ICostCoralRed,
                                        trackColor = ICostCellGray,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
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

@Composable
private fun ICostBentoStatsCard(
    title: String,
    badgeIcon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    cells: List<Pair<String, String>>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(ICostAmberGold),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(badgeIcon, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ICostTextPrimary)
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = ICostTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    cells.chunked(2).forEach { pairRow ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            pairRow.forEach { (label, valStr) ->
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = ICostCellGray,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(label, fontSize = 13.sp, color = ICostTextSecondary)
                                            Icon(
                                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                contentDescription = null,
                                                tint = ICostTextSecondary,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(5.dp))
                                        Text(
                                            text = valStr,
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
