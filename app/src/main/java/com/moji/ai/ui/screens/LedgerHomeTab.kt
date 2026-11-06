package com.moji.ai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.moji.ai.ui.QuickBookkeepingTemplate
import com.moji.ai.ui.theme.AnimatedMoneyText
import com.moji.ai.ui.theme.ICDinFontFamily
import com.moji.ai.ui.theme.ICostAmberGold
import com.moji.ai.ui.theme.ICostAssetImage
import com.moji.ai.ui.theme.ICostAvatarButton
import com.moji.ai.ui.theme.ICostBlueAccent
import com.moji.ai.ui.theme.ICostCardWhite
import com.moji.ai.ui.theme.ICostCategoryBadge
import com.moji.ai.ui.theme.ICostCellGray
import com.moji.ai.ui.theme.ICostCircleIconButton
import com.moji.ai.ui.theme.ICostCoralRed
import com.moji.ai.ui.theme.ICostDivider
import com.moji.ai.ui.theme.ICostGreenIncome
import com.moji.ai.ui.theme.ICostSearchBg
import com.moji.ai.ui.theme.ICostSwipeActionRow
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.SwipeActionItem
import com.moji.ai.ui.theme.formatMoneyCents
import com.moji.ai.ui.theme.formatWalletDisplayName
import com.moji.ai.ui.theme.iCostHorizontalSwipeTrigger
import com.moji.ai.ui.theme.iCostPressScale
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
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
    monthlyBudgetCents: Long,
    onUpdateBudgetYuan: (Double) -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onOpenCalendar: (List<TransactionEntity>) -> Unit,
    onOpenAiSandbox: () -> Unit,
    onOpenSettingsPage: () -> Unit,
    onClickTransaction: (TransactionEntity) -> Unit,
    templates: List<QuickBookkeepingTemplate> = emptyList(),
    onApplyTemplate: (QuickBookkeepingTemplate) -> Unit = {},
    onEditTransaction: (TransactionEntity) -> Unit = {},
    onDuplicateTransaction: (TransactionEntity) -> Unit = {},
    onToggleReimburse: (TransactionEntity) -> Unit = {},
    onQuickRefund: (TransactionEntity) -> Unit = {},
    onDeleteTransaction: (Long) -> Unit = {}
) {
    val walletMap = remember(wallets) { wallets.associateBy { it.id } }
    var searchQuery by remember { mutableStateOf("") }
    var showLedgerSwitcher by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showBudgetEditDialog by remember { mutableStateOf(false) }
    var showQuickTemplatesBar by remember { mutableStateOf(false) }
    var refundingTx by remember { mutableStateOf<TransactionEntity?>(null) }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val scope = rememberCoroutineScope()

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

    // 报销统计
    val pendingReimburseCents = monthTransactions
        .filter { it.reimbursable && !it.reimbursed }
        .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }
    val settledReimburseCents = monthTransactions
        .filter { it.reimbursable && it.reimbursed }
        .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }

    val groupedByDay = remember(monthTransactions) {
        val dayFmt = SimpleDateFormat("MM/dd EEEE", Locale.CHINA)
        monthTransactions.groupBy { tx -> dayFmt.format(Date(tx.timestamp)) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. 顶部操作栏：左账本切换圆形按钮 `main_page_left_switch.webp` ，右 `•••` + 薄荷绿插画头像
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ICostCircleIconButton(
                    onClick = { showLedgerSwitcher = true },
                    size = 42.dp
                ) {
                    ICostAssetImage(
                        assetFile = "main_page_left_switch.webp",
                        tint = ICostTextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        ICostCircleIconButton(
                            onClick = { showMoreMenu = true },
                            size = 42.dp
                        ) {
                            Icon(
                                Icons.Default.MoreHoriz,
                                contentDescription = "更多操作",
                                tint = ICostTextPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            containerColor = ICostCardWhite
                        ) {
                            DropdownMenuItem(
                                text = { Text("全部展开/收起", fontSize = 14.sp) },
                                onClick = {
                                    showMoreMenu = false
                                    val anyOpen = groupedByDay.keys.any { collapsedDays[it] != true }
                                    groupedByDay.keys.forEach { collapsedDays[it] = anyOpen }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("设置月度预算", fontSize = 14.sp) },
                                onClick = {
                                    showMoreMenu = false
                                    showBudgetEditDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("AI 静默识账测试沙盒", fontSize = 14.sp) },
                                onClick = {
                                    showMoreMenu = false
                                    onOpenAiSandbox()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("打开设置中心", fontSize = 14.sp) },
                                onClick = {
                                    showMoreMenu = false
                                    onOpenSettingsPage()
                                }
                            )
                        }
                    }

                    ICostAvatarButton(
                        onClick = onOpenSettingsPage,
                        size = 42.dp
                    )
                }
            }
        }

        // 2. 超大加粗账本标题（点击可切换账本，右滑可展开快捷模板抽屉）
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .iCostHorizontalSwipeTrigger(
                        onSwipeRight = { showQuickTemplatesBar = true },
                        onSwipeLeft = { showQuickTemplatesBar = false }
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentLedger,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = ICostTextPrimary,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { showLedgerSwitcher = true }
                        .padding(top = 2.dp)
                )

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (showQuickTemplatesBar) Color(0xFFFFF3E0) else Color(0xFFEBEBEB),
                    modifier = Modifier.iCostPressScale(pressedScale = 0.92f) {
                        showQuickTemplatesBar = !showQuickTemplatesBar
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ICostAssetImage(
                            assetFile = "detail_template.webp",
                            tint = if (showQuickTemplatesBar) Color(0xFFF59E0B) else Color(0xFF666666),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (showQuickTemplatesBar) "收起模板" else "快捷模板 (${templates.size})",
                            fontSize = 12.sp,
                            color = if (showQuickTemplatesBar) Color(0xFFD97706) else Color(0xFF666666),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // 2.5 右滑或点击呼出的「快捷记账模板」横向滑动模块（点击即刻入账）
        item {
            AnimatedVisibility(
                visible = showQuickTemplatesBar && templates.isNotEmpty(),
                enter = expandVertically(
                    animationSpec = spring(dampingRatio = 0.94f, stiffness = 600f)
                ) + fadeIn(tween(140)),
                exit = shrinkVertically(
                    animationSpec = spring(dampingRatio = 0.96f, stiffness = 680f)
                ) + fadeOut(tween(110))
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ICostCardWhite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "快捷秒记模板（点击一键入账）",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostTextSecondary
                            )
                            Text(
                                text = "账单行右滑/左滑可呼出扩展操作 ⇄",
                                fontSize = 11.sp,
                                color = ICostBlueAccent
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(templates, key = { it.id }) { tpl ->
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = ICostCellGray,
                                    modifier = Modifier.iCostPressScale(pressedScale = 0.92f) {
                                        onApplyTemplate(tpl)
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ICostCategoryBadge(
                                            category = tpl.category,
                                            type = TransactionType.EXPENSE,
                                            size = 28.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = tpl.title,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = ICostTextPrimary
                                            )
                                            Text(
                                                text = "-${formatMoneyCents(tpl.amountCents, tpl.currency)}",
                                                fontFamily = ICDinFontFamily,
                                                fontSize = 12.sp,
                                                color = ICostCoralRed
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

        // 3. 圆角浅灰搜索框「输入关键词」（40dp 紧凑高度，对齐 01_ledger_home.png）
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(ICostSearchBg)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = Color(0xFF9E9E9E),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text("输入关键词", color = Color(0xFF9E9E9E), fontSize = 14.sp)
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 14.sp, color = ICostTextPrimary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "清除",
                            tint = ICostTextSecondary,
                            modifier = Modifier
                                .size(17.dp)
                                .clickable { searchQuery = "" }
                        )
                    }
                }
            }
        }

        // 4. 年月选择器（支持右滑切上月 / 左滑切下月） + 1:1「收支日历」胶囊
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .iCostHorizontalSwipeTrigger(
                        onSwipeRight = onPrevMonth,
                        onSwipeLeft = onNextMonth
                    ),
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
                        shape = CircleShape,
                        color = ICostCardWhite,
                        modifier = Modifier
                            .size(28.dp)
                            .iCostPressScale(pressedScale = 0.88f, onClick = onPrevMonth)
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
                        shape = CircleShape,
                        color = ICostCardWhite,
                        modifier = Modifier
                            .size(28.dp)
                            .iCostPressScale(pressedScale = 0.88f, onClick = onNextMonth)
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
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFEBEBEB),
                    modifier = Modifier.iCostPressScale(pressedScale = 0.92f) {
                        onOpenCalendar(monthTransactions)
                    }
                ) {
                    Text(
                        text = "收支日历",
                        fontSize = 12.sp,
                        color = Color(0xFF666666),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }
        }

        // 5. 月度概览三页左右滑动卡片（对齐 01_ledger_home.png / 02_home_card_page2.png / 03_home_card_page3.png）
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .iCostPressScale(pressedScale = 0.985f) {
                        scope.launch {
                            pagerState.animateScrollToPage((pagerState.currentPage + 1) % 3)
                        }
                    },
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth()
                    ) { page ->
                        when (page) {
                            // Page 0: 总支出 ⇄ / 总收入 / 月结余
                            0 -> {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(ICostCoralRed),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            ICostAssetImage(
                                                assetFile = "card_outcome_icon.webp",
                                                tint = Color.White,
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "总支出 ⇄",
                                            color = ICostCoralRed,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    AnimatedMoneyText(
                                        cents = monthExpenseCents,
                                        fontSize = 36.sp,
                                        color = ICostTextPrimary
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("总收入 ", fontSize = 13.sp, color = ICostTextSecondary)
                                            Text(
                                                text = formatMoneyCents(monthIncomeCents),
                                                fontSize = 13.sp,
                                                color = ICostTextSecondary,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("月结余 ", fontSize = 13.sp, color = ICostTextSecondary)
                                            Text(
                                                text = formatMoneyCents(monthBalanceCents),
                                                fontSize = 13.sp,
                                                color = ICostTextSecondary,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }

                            // Page 1: 剩余月预算 + 超支徽章 + 总预算 / 剩余日均
                            1 -> {
                                val remainCents = monthlyBudgetCents - monthExpenseCents
                                val isOverspent = remainCents < 0L
                                val cal = Calendar.getInstance()
                                val maxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                                val today = cal.get(Calendar.DAY_OF_MONTH)
                                val remainDays = (maxDays - today + 1).coerceAtLeast(1)
                                val dailyRemainCents = if (remainCents > 0L) remainCents / remainDays else 0L

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showBudgetEditDialog = true }
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(ICostCoralRed),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            ICostAssetImage(
                                                assetFile = "card_budget_icon.webp",
                                                tint = Color.White,
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "剩余月预算",
                                            color = ICostCoralRed,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (isOverspent) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = ICostCoralRed
                                            ) {
                                                Text(
                                                    text = "超支",
                                                    color = Color.White,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    AnimatedMoneyText(
                                        cents = remainCents,
                                        fontSize = 36.sp,
                                        color = ICostTextPrimary
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text(
                                            text = "总预算 ${formatMoneyCents(monthlyBudgetCents)}",
                                            fontSize = 13.sp,
                                            color = ICostTextSecondary
                                        )
                                        Text(
                                            text = "剩余日均 ${formatMoneyCents(dailyRemainCents)}",
                                            fontSize = 13.sp,
                                            color = ICostTextSecondary
                                        )
                                    }
                                }
                            }

                            // Page 2: 待报销 / 已报销 / 报销入账
                            else -> {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(ICostCoralRed),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            ICostAssetImage(
                                                assetFile = "card_claim_icon.webp",
                                                tint = Color.White,
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "待报销",
                                            color = ICostCoralRed,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    AnimatedMoneyText(
                                        cents = pendingReimburseCents,
                                        fontSize = 36.sp,
                                        color = ICostTextPrimary
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text(
                                            text = "已报销 ${formatMoneyCents(settledReimburseCents)}",
                                            fontSize = 13.sp,
                                            color = ICostTextSecondary
                                        )
                                        Text(
                                            text = "报销入账 ${formatMoneyCents(settledReimburseCents)}",
                                            fontSize = 13.sp,
                                            color = ICostTextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 三圆点动态胶囊指示器（激活态平滑拉伸为 16dp 琥珀金胶囊）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(3) { idx ->
                            val isSelected = pagerState.currentPage == idx
                            val dotWidth by animateDpAsState(
                                targetValue = if (isSelected) 16.dp else 6.dp,
                                animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
                                label = "PagerDotWidth"
                            )
                            val dotColor by animateColorAsState(
                                targetValue = if (isSelected) ICostAmberGold else Color(0xFFE0E0E0),
                                animationSpec = tween(220),
                                label = "PagerDotColor"
                            )
                            Box(
                                modifier = Modifier
                                    .height(6.dp)
                                    .width(dotWidth)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            if (idx < 2) Spacer(modifier = Modifier.width(6.dp))
                        }
                    }
                }
            }
        }

        // 6. 按日分组展示（点击每日头部可收起/展开该日流水，点击单条流水打开全屏账单详情页）
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
                            "点击右下角「+」调出记账键盘，或打开 AI 静默识账测试沙盒体验自动记账：",
                            fontSize = 13.sp,
                            color = ICostTextSecondary
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = onOpenAiSandbox,
                            colors = ButtonDefaults.buttonColors(containerColor = ICostCoralRed),
                            shape = RoundedCornerShape(18.dp)
                        ) {
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
                    val arrowRotation by animateFloatAsState(
                        targetValue = if (isCollapsed) -90f else 0f,
                        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
                        label = "DayCollapseArrow"
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .iCostPressScale(pressedScale = 0.98f) { collapsedDays[dayHeader] = !isCollapsed }
                                .padding(horizontal = 4.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = dayHeader,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.normalOrMedium(),
                                color = ICostTextSecondary
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "支出: ${formatMoneyCents(dayNetExpenseCents)}",
                                    fontSize = 13.sp,
                                    color = ICostTextSecondary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = ICostTextSecondary,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .graphicsLayer { rotationZ = arrowRotation }
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = !isCollapsed,
                            enter = expandVertically(
                                animationSpec = spring(dampingRatio = 0.94f, stiffness = 600f)
                            ) + fadeIn(tween(140)),
                            exit = shrinkVertically(
                                animationSpec = spring(dampingRatio = 0.96f, stiffness = 680f)
                            ) + fadeOut(tween(110))
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(22.dp),
                                colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    dayList.forEachIndexed { index, tx ->
                                        val canRefund = tx.type == TransactionType.EXPENSE &&
                                            (tx.amountCnyCents - tx.refundedAmountCents) > 0L
                                        val swipeActions = buildList {
                                            add(
                                                SwipeActionItem(
                                                    label = "编辑",
                                                    iconAsset = "detail_edit.webp",
                                                    backgroundColor = ICostBlueAccent,
                                                    onClick = { onEditTransaction(tx) }
                                                )
                                            )
                                            if (tx.type == TransactionType.EXPENSE && !tx.reimbursed) {
                                                add(
                                                    SwipeActionItem(
                                                        label = if (tx.reimbursable) "结报销" else "报销",
                                                        iconAsset = "detail_claim.webp",
                                                        backgroundColor = ICostGreenIncome,
                                                        onClick = { onToggleReimburse(tx) }
                                                    )
                                                )
                                            } else {
                                                add(
                                                    SwipeActionItem(
                                                        label = "复制",
                                                        iconAsset = "detail_template.webp",
                                                        backgroundColor = Color(0xFFF59E0B),
                                                        onClick = { onDuplicateTransaction(tx) }
                                                    )
                                                )
                                            }
                                            add(
                                                SwipeActionItem(
                                                    label = "删除",
                                                    iconAsset = "detail_delete.webp",
                                                    backgroundColor = ICostCoralRed,
                                                    onClick = { onDeleteTransaction(tx.id) }
                                                )
                                            )
                                        }

                                        ICostSwipeActionRow(
                                            actions = swipeActions,
                                            actionWidth = 44.dp,
                                            actionHeight = 42.dp
                                        ) {
                                            ICostLedgerRow(
                                                tx = tx,
                                                walletMap = walletMap,
                                                onClick = { onClickTransaction(tx) }
                                            )
                                        }
                                        if (index < dayList.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(start = 68.dp, end = 16.dp),
                                                thickness = 0.5.dp,
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

    // 侧滑快捷全额退款确认弹窗
    refundingTx?.let { targetTx ->
        val remainCents = (targetTx.amountCnyCents - targetTx.refundedAmountCents).coerceAtLeast(0L)
        AlertDialog(
            onDismissRequest = { refundingTx = null },
            containerColor = ICostCardWhite,
            title = { Text("快捷全额退款", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "确认将「${targetTx.merchant.ifBlank { targetTx.category }}」的可退金额 ${formatMoneyCents(remainCents)} 全额退回原账户并冲销净支出吗？",
                    fontSize = 14.sp,
                    color = ICostTextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onQuickRefund(targetTx)
                    refundingTx = null
                }) {
                    Text("确认全额退款", fontWeight = FontWeight.Bold, color = ICostCoralRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { refundingTx = null }) { Text("取消") }
            }
        )
    }

    // 修改预算弹窗
    if (showBudgetEditDialog) {
        var budgetText by remember(monthlyBudgetCents) {
            mutableStateOf(String.format(Locale.US, "%.0f", monthlyBudgetCents / 100.0))
        }
        AlertDialog(
            onDismissRequest = { showBudgetEditDialog = false },
            containerColor = ICostCardWhite,
            title = { Text("设置月预算", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = budgetText,
                    onValueChange = { budgetText = it },
                    label = { Text("月度总预算 (元)") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    budgetText.toDoubleOrNull()?.let { onUpdateBudgetYuan(it) }
                    showBudgetEditDialog = false
                }) {
                    Text("保存", fontWeight = FontWeight.Bold, color = ICostBlueAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBudgetEditDialog = false }) { Text("取消") }
            }
        )
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

private fun FontWeight.Companion.normalOrMedium(): FontWeight = FontWeight.Normal

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

    val titleText = when (tx.type) {
        TransactionType.REFUND_OFFSET -> "退款入账"
        TransactionType.REPAYMENT -> "转至${targetWallet?.name ?: "信用账户"}"
        TransactionType.TRANSFER -> "转至${targetWallet?.name ?: "目标账户"}"
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
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.Top
    ) {
        ICostCategoryBadge(
            category = tx.category,
            type = tx.type,
            size = 40.dp
        )

        Spacer(modifier = Modifier.width(12.dp))

        // 中间标题与副标题（严格单行省略，不挤压右侧）
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp)
        ) {
            Text(
                text = titleText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = ICostTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitleText,
                fontSize = 12.sp,
                color = ICostTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (isRefundedExpense || tx.reimbursable) {
                Spacer(modifier = Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isRefundedExpense) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFF0F0F0)
                        ) {
                            Text(
                                text = if (isFullRefund) "全额退款" else "已退 ${formatMoneyCents(tx.refundedAmountCents)}",
                                fontSize = 10.sp,
                                color = ICostTextSecondary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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
                                fontSize = 10.sp,
                                color = if (tx.reimbursed) ICostGreenIncome else ICostBlueAccent,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // 右侧金额与账户名（严格 softWrap = false，绝不竖向折行）
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRefundedExpense) {
                    Text(
                        text = String.format(Locale.US, "%s%,.2f", sym, tx.amountCents / 100.0),
                        fontSize = 12.sp,
                        color = ICostTextSecondary,
                        textDecoration = TextDecoration.LineThrough,
                        maxLines = 1,
                        softWrap = false
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = String.format(Locale.US, "-¥%,.2f", netExpenseCents / 100.0),
                        fontSize = 16.sp,
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
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = amountColor,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = walletLabel,
                fontSize = 11.sp,
                color = ICostTextSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
