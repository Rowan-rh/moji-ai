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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.TransactionType
import com.moji.ai.ui.theme.ICostAmberGold
import com.moji.ai.ui.theme.ICostBlueAccent
import com.moji.ai.ui.theme.ICostCardWhite
import com.moji.ai.ui.theme.ICostCellGray
import com.moji.ai.ui.theme.ICostCoralRed
import com.moji.ai.ui.theme.ICostDivider
import com.moji.ai.ui.theme.ICostGreenIncome
import com.moji.ai.ui.theme.ICostSearchBg
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.formatMoneyCents
import com.moji.ai.ui.theme.formatWalletDisplayName
import com.moji.ai.ui.theme.resolveCategoryIcon
import com.moji.ai.ui.theme.resolveTransactionBadgeColor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ICostLedgerHomeScreen(
    currentLedger: String,
    ledgers: List<String>,
    onSelectLedger: (String) -> Unit,
    onCreateLedger: (String) -> Unit,
    wallets: List<WalletEntity>,
    transactions: List<TransactionEntity>,
    selectedYear: Int,
    selectedMonth: Int,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onOpenCalendar: (List<TransactionEntity>) -> Unit,
    onOpenAiSandbox: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onClickTransaction: (TransactionEntity) -> Unit
) {
    val walletMap = remember(wallets) { wallets.associateBy { it.id } }
    var searchQuery by remember { mutableStateOf("") }
    var summaryCardPage by remember { mutableIntStateOf(0) } // 0: 总支出, 1: 月预算, 2: AI识账概览
    var monthlyBudgetYuan by remember { mutableIntStateOf(3000) }
    var showLedgerSwitcher by remember { mutableStateOf(false) }

    val collapsedDays = remember { mutableStateMapOf<String, Boolean>() }

    // 过滤当前账本、当月及搜索词
    val monthTransactions = remember(transactions, currentLedger, selectedYear, selectedMonth, searchQuery) {
        val cal = Calendar.getInstance()
        transactions.filter { tx ->
            cal.timeInMillis = tx.timestamp
            val matchLedger = tx.ledgerName == currentLedger
            val matchMonth = cal.get(Calendar.YEAR) == selectedYear && (cal.get(Calendar.MONTH) + 1) == selectedMonth
            val matchQuery = if (searchQuery.isBlank()) true else {
                tx.category.contains(searchQuery, ignoreCase = true) ||
                    tx.merchant.contains(searchQuery, ignoreCase = true) ||
                    tx.note.contains(searchQuery, ignoreCase = true) ||
                    tx.tags.contains(searchQuery, ignoreCase = true)
            }
            matchLedger && matchMonth && matchQuery
        }
    }

    val monthExpenseCents = monthTransactions
        .filter { it.type == TransactionType.EXPENSE }
        .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }

    val monthIncomeCents = monthTransactions
        .filter { it.type == TransactionType.INCOME }
        .sumOf { it.amountCnyCents }

    val monthBalanceCents = monthIncomeCents - monthExpenseCents

    val groupedByDay = remember(monthTransactions) {
        val dayFmt = SimpleDateFormat("MM/dd EEEE", Locale.CHINA)
        monthTransactions.groupBy { tx -> dayFmt.format(Date(tx.timestamp)) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 顶部操作栏：左账本切换 `⇄` ，右设置 `•••` + AI 快捷图标
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = { showLedgerSwitcher = true },
                    shape = CircleShape,
                    color = ICostCardWhite,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = "切换账本",
                            tint = ICostTextPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        onClick = onOpenAiSettings,
                        shape = CircleShape,
                        color = ICostCardWhite,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.MoreHoriz,
                                contentDescription = "AI与权限设置",
                                tint = ICostTextPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = onOpenAiSandbox,
                        shape = CircleShape,
                        color = Color(0xFFDFF4EA),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = "AI 识账沙盒",
                                tint = Color(0xFF0F766E),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. 超大加粗账本标题（点击也可切换账本）
        item {
            Text(
                text = currentLedger,
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ICostTextPrimary,
                modifier = Modifier
                    .clickable { showLedgerSwitcher = true }
                    .padding(top = 2.dp)
            )
        }

        // 3. 圆角浅灰搜索框「输入关键词」
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(ICostSearchBg)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = ICostTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text("输入关键词", color = ICostTextSecondary, fontSize = 15.sp)
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 15.sp, color = ICostTextPrimary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "清除",
                            tint = ICostTextSecondary,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { searchQuery = "" }
                        )
                    }
                }
            }
        }

        // 4. 年月选择器 + 1:1 真实可用的「收支日历」按钮
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${selectedYear}年${selectedMonth}月",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextPrimary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Surface(
                        onClick = onPrevMonth,
                        shape = CircleShape,
                        color = ICostCardWhite,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "上个月",
                                tint = ICostTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        onClick = onNextMonth,
                        shape = CircleShape,
                        color = ICostCardWhite,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "下个月",
                                tint = ICostTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Surface(
                    onClick = { onOpenCalendar(monthTransactions) },
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFEDEDED)
                ) {
                    Text(
                        text = "收支日历",
                        fontSize = 13.sp,
                        color = Color(0xFF595959),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }
        }

        // 5. 月度概览三页切换白卡片（点击切换：总支出 ⇄ 月度预算 ⇄ AI 识账统计）
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { summaryCardPage = (summaryCardPage + 1) % 3 },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                ) {
                    when (summaryCardPage) {
                        0 -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(ICostCoralRed),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("¥", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "总支出 ⇄",
                                    color = ICostCoralRed,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = formatMoneyCents(monthExpenseCents),
                                fontSize = 38.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = ICostTextPrimary,
                                letterSpacing = (-0.8).sp,
                                maxLines = 1,
                                softWrap = false
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                Text(
                                    text = "总收入  ${formatMoneyCents(monthIncomeCents)}",
                                    fontSize = 14.sp,
                                    color = ICostTextSecondary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = "月结余  ${formatMoneyCents(monthBalanceCents)}",
                                    fontSize = 14.sp,
                                    color = ICostTextSecondary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        1 -> {
                            val budgetCents = monthlyBudgetYuan * 100L
                            val remainCents = budgetCents - monthExpenseCents
                            val progress = (monthExpenseCents.toFloat() / budgetCents.coerceAtLeast(1L)).coerceIn(0f, 1f)

                            Text("本月剩余预算 ⇄", color = ICostBlueAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = formatMoneyCents(remainCents),
                                fontSize = 36.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = ICostTextPrimary,
                                maxLines = 1,
                                softWrap = false
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                color = if (remainCents < 0) ICostCoralRed else ICostBlueAccent,
                                trackColor = ICostCellGray,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "月预算 ¥$monthlyBudgetYuan · 已用 ${(progress * 100).toInt()}%",
                                fontSize = 13.sp,
                                color = ICostTextSecondary
                            )
                        }

                        else -> {
                            val aiCount = monthTransactions.count { it.captureSource.startsWith("AI") }
                            Text("AI 静默识账概览 ⇄", color = Color(0xFF0F766E), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "已自动记 $aiCount 笔",
                                fontSize = 34.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = ICostTextPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("点击右上角星星可进入 AI 识账沙盒测试", fontSize = 13.sp, color = ICostTextSecondary)
                                Text(
                                    "去测试 >",
                                    fontSize = 13.sp,
                                    color = ICostCoralRed,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { onOpenAiSandbox() }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 三圆点指示器
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(3) { idx ->
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (summaryCardPage == idx) ICostAmberGold else Color(0xFFE0E0E0))
                            )
                            if (idx < 2) Spacer(modifier = Modifier.width(6.dp))
                        }
                    }
                }
            }
        }

        // 6. 按日分组展示（点击每日头部可收起/展开该日流水，点击单条流水打开账单详情页！）
        if (groupedByDay.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("本月暂无账单记录", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ICostTextPrimary)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "点击右下角「+」可调出 iCost 同款计算器键盘记账，或点击下方体验 AI 静默自动识账：",
                            fontSize = 13.sp,
                            color = ICostTextSecondary
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = onOpenAiSandbox,
                            colors = ButtonDefaults.buttonColors(containerColor = ICostCoralRed),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("打开 AI 识账沙盒测试")
                        }
                    }
                }
            }
        } else {
            groupedByDay.forEach { (dayHeader, dayList) ->
                val dayNetExpenseCents = dayList
                    .filter { it.type == TransactionType.EXPENSE }
                    .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }
                val isCollapsed = collapsedDays[dayHeader] == true

                item(key = "day_$dayHeader") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { collapsedDays[dayHeader] = !isCollapsed }
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = dayHeader,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = ICostTextSecondary
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "支出: ${formatMoneyCents(dayNetExpenseCents)}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = ICostTextSecondary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Icon(
                                    imageVector = if (isCollapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = ICostTextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        AnimatedVisibility(visible = !isCollapsed) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(22.dp),
                                colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    dayList.forEachIndexed { index, tx ->
                                        ICostLedgerRow(
                                            tx = tx,
                                            walletMap = walletMap,
                                            onClick = { onClickTransaction(tx) }
                                        )
                                        if (index < dayList.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(start = 70.dp, end = 16.dp),
                                                thickness = 0.6.dp,
                                                color = ICostDivider
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

    // 切换/新建账本弹窗
    if (showLedgerSwitcher) {
        var newLedgerName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showLedgerSwitcher = false },
            containerColor = ICostCardWhite,
            title = { Text("切换或新建账本", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ledgers.forEach { name ->
                        Surface(
                            onClick = {
                                onSelectLedger(name)
                                showLedgerSwitcher = false
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (name == currentLedger) Color(0xFFEAF2FF) else ICostCellGray,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (name == currentLedger) "✓  $name" else name,
                                fontWeight = if (name == currentLedger) FontWeight.Bold else FontWeight.Medium,
                                color = if (name == currentLedger) ICostBlueAccent else ICostTextPrimary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = newLedgerName,
                        onValueChange = { newLedgerName = it },
                        label = { Text("新建账本名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                if (newLedgerName.isNotBlank()) {
                    TextButton(onClick = {
                        onCreateLedger(newLedgerName)
                        showLedgerSwitcher = false
                    }) {
                        Text("创建并切换", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showLedgerSwitcher = false }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun ICostLedgerRow(
    tx: TransactionEntity,
    walletMap: Map<Long, WalletEntity>,
    onClick: () -> Unit
) {
    val timeStr = remember(tx.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(tx.timestamp))
    }
    val sym = CurrencyCode.fromCode(tx.currency).symbol
    val sourceWallet = tx.sourceWalletId?.let { walletMap[it] }
    val targetWallet = tx.targetWalletId?.let { walletMap[it] }

    val walletLabel = when (tx.type) {
        TransactionType.EXPENSE, TransactionType.LOAN_OUT -> formatWalletDisplayName(sourceWallet, "账户余额")
        TransactionType.INCOME, TransactionType.REFUND_OFFSET, TransactionType.LOAN_IN ->
            formatWalletDisplayName(targetWallet ?: sourceWallet, "账户余额")
        TransactionType.TRANSFER -> "${sourceWallet?.name ?: "转出"} ➔ ${targetWallet?.name ?: "转入"}"
        TransactionType.REPAYMENT -> "${sourceWallet?.name ?: "储蓄卡"} ➔ ${targetWallet?.name ?: "信用卡"}"
    }

    val badgeColor = resolveTransactionBadgeColor(tx.type)
    val iconVector = resolveCategoryIcon(tx.category, tx.type)

    val titleText = when (tx.type) {
        TransactionType.REFUND_OFFSET -> "退款入账"
        TransactionType.REPAYMENT -> "信用还款"
        TransactionType.TRANSFER -> "内部转账"
        else -> tx.category
    }

    val subtitleText = buildString {
        append(timeStr)
        if (tx.merchant.isNotBlank()) {
            append(" · ")
            append(tx.merchant)
        } else if (tx.note.isNotBlank()) {
            append(" · ")
            append(tx.note)
        }
    }

    val isRefundedExpense = tx.type == TransactionType.EXPENSE && tx.refundedAmountCents > 0L
    val isFullRefund = isRefundedExpense && tx.refundedAmountCents >= tx.amountCnyCents
    val netExpenseCents = (tx.amountCnyCents - tx.refundedAmountCents).coerceAtLeast(0L)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(badgeColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = iconVector,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 中间标题与副标题（严格单行省略，不挤压右侧）
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp)
        ) {
            Text(
                text = titleText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = ICostTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitleText,
                fontSize = 13.sp,
                color = ICostTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (isRefundedExpense || tx.reimbursable) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isRefundedExpense) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFF2F2F2)
                        ) {
                            Text(
                                text = if (isFullRefund) "全额退款" else "已退 ${formatMoneyCents(tx.refundedAmountCents)}",
                                fontSize = 11.sp,
                                color = ICostTextSecondary,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (tx.reimbursable) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (tx.reimbursed) Color(0xFFE6F7EF) else Color(0xFFEAF2FF)
                        ) {
                            Text(
                                text = if (tx.reimbursed) "已报销" else "待报销",
                                fontSize = 11.sp,
                                color = if (tx.reimbursed) ICostGreenIncome else ICostBlueAccent,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // 右侧金额与账户名（严格 softWrap = false，绝不竖向折行！）
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRefundedExpense) {
                    Text(
                        text = String.format(Locale.US, "%s%,.2f", sym, tx.amountCents / 100.0),
                        fontSize = 13.sp,
                        color = ICostTextSecondary,
                        textDecoration = TextDecoration.LineThrough,
                        maxLines = 1,
                        softWrap = false
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = String.format(Locale.US, "-¥%,.2f", netExpenseCents / 100.0),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ICostCoralRed,
                        maxLines = 1,
                        softWrap = false
                    )
                } else {
                    val amountText = when (tx.type) {
                        TransactionType.EXPENSE, TransactionType.LOAN_OUT ->
                            String.format(Locale.US, "-%s%,.2f", sym, tx.amountCents / 100.0)
                        TransactionType.REFUND_OFFSET ->
                            String.format(Locale.US, "%s%,.2f", sym, tx.amountCents / 100.0)
                        TransactionType.INCOME, TransactionType.LOAN_IN ->
                            String.format(Locale.US, "+%s%,.2f", sym, tx.amountCents / 100.0)
                        TransactionType.TRANSFER, TransactionType.REPAYMENT ->
                            String.format(Locale.US, "%s%,.2f", sym, tx.amountCents / 100.0)
                    }
                    val amountColor = when (tx.type) {
                        TransactionType.EXPENSE, TransactionType.LOAN_OUT -> ICostCoralRed
                        TransactionType.REFUND_OFFSET -> ICostAmberGold
                        TransactionType.INCOME, TransactionType.LOAN_IN -> ICostGreenIncome
                        TransactionType.TRANSFER, TransactionType.REPAYMENT -> ICostTextPrimary
                    }
                    Text(
                        text = amountText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = amountColor,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = walletLabel,
                fontSize = 12.sp,
                color = ICostTextSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
