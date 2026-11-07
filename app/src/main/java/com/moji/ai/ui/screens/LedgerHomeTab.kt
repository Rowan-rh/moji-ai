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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.TransactionType
import com.moji.ai.ui.QuickBookkeepingTemplate
import com.moji.ai.ui.components.ICostConfirmDialog
import com.moji.ai.ui.components.parseYuanToCents
import com.moji.ai.ui.components.sanitizeMoneyInput
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
import com.moji.ai.ui.theme.ICostFormField
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * @param onOpenAiSandbox 已不再使用：首页不再展示识账测试入口（已移至「智能记账」页）。保留参数以兼容旧调用。
 */
@Suppress("UNUSED_PARAMETER")
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
    // 以下界面状态在切换底部 Tab 后保留
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showQuickTemplatesBar by rememberSaveable { mutableStateOf(false) }
    var collapsedDayKeys by rememberSaveable { mutableStateOf(listOf<String>()) }

    var showLedgerSwitcher by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showBudgetEditDialog by remember { mutableStateOf(false) }
    var refundingTx by remember { mutableStateOf<TransactionEntity?>(null) }
    var deletingTx by remember { mutableStateOf<TransactionEntity?>(null) }
    var settlingTx by remember { mutableStateOf<TransactionEntity?>(null) }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })

    // 当前账本 + 当月全部账单（汇总卡片、日历都基于它，不受搜索影响）
    val allMonthTransactions = remember(transactions, currentLedger, selectedYear, selectedMonth) {
        val cal = Calendar.getInstance()
        transactions.filter { tx ->
            cal.timeInMillis = tx.timestamp
            tx.ledgerName == currentLedger &&
                cal.get(Calendar.YEAR) == selectedYear &&
                (cal.get(Calendar.MONTH) + 1) == selectedMonth
        }
    }
    val trimmedQuery = searchQuery.trim()
    // 列表展示用：再叠加搜索词（支持搜分类、商户、备注、标签、交易单号、商户单号、支付方式）
    val monthTransactions = remember(allMonthTransactions, trimmedQuery) {
        if (trimmedQuery.isEmpty()) allMonthTransactions else allMonthTransactions.filter { tx ->
            tx.category.contains(trimmedQuery, ignoreCase = true) ||
                tx.merchant.contains(trimmedQuery, ignoreCase = true) ||
                tx.note.contains(trimmedQuery, ignoreCase = true) ||
                tx.tags.contains(trimmedQuery, ignoreCase = true) ||
                tx.transactionNo.contains(trimmedQuery, ignoreCase = true) ||
                tx.merchantOrderNo.contains(trimmedQuery, ignoreCase = true) ||
                tx.paymentMethodRaw.contains(trimmedQuery, ignoreCase = true)
        }
    }

    val monthExpenseCents = remember(allMonthTransactions) {
        allMonthTransactions
            .filter { it.type == TransactionType.EXPENSE }
            .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }
    }

    val monthIncomeCents = remember(allMonthTransactions) {
        allMonthTransactions
            .filter { it.type == TransactionType.INCOME }
            .sumOf { it.amountCnyCents }
    }

    val monthBalanceCents = monthIncomeCents - monthExpenseCents

    // 报销统计
    val pendingReimburseList = remember(allMonthTransactions) {
        allMonthTransactions.filter { it.reimbursable && !it.reimbursed }
    }
    val pendingReimburseCents = remember(pendingReimburseList) {
        pendingReimburseList.sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }
    }
    val settledReimburseCents = remember(allMonthTransactions) {
        allMonthTransactions
            .filter { it.reimbursable && it.reimbursed }
            .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }
    }

    // 分组 key 带年份，避免跨年同月同日的折叠状态串用；标题单独展示
    val groupedByDay = remember(monthTransactions) {
        val keyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        monthTransactions.groupBy { tx -> keyFmt.format(Date(tx.timestamp)) }
    }
    val dayHeaderFmt = remember { SimpleDateFormat("MM/dd EEEE", Locale.CHINA) }

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
                        contentDescription = "切换账本",
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
                                    val anyOpen = groupedByDay.keys.any { it !in collapsedDayKeys }
                                    collapsedDayKeys = if (anyOpen) {
                                        (collapsedDayKeys + groupedByDay.keys).distinct()
                                    } else {
                                        collapsedDayKeys - groupedByDay.keys
                                    }
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
                                text = { Text("设置", fontSize = 14.sp) },
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
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ICostAssetImage(
                            assetFile = "detail_template.webp",
                            tint = if (showQuickTemplatesBar) Color(0xFFF59E0B) else Color(0xFF666666),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = when {
                                showQuickTemplatesBar -> "收起模板"
                                templates.isEmpty() -> "快捷模板"
                                else -> "快捷模板 (${templates.size})"
                            },
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
                visible = showQuickTemplatesBar,
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
                                text = "快捷模板（点一下直接记账）",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostTextSecondary
                            )
                            Text(
                                text = "左滑账单可编辑、删除",
                                fontSize = 11.sp,
                                color = ICostTextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        if (templates.isEmpty()) {
                            Text(
                                text = "还没有模板。在账单详情点「存为模板」即可添加",
                                fontSize = 13.sp,
                                color = ICostTextSecondary,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(templates, key = { it.id }) { tpl ->
                                val tplType = runCatching { TransactionType.valueOf(tpl.typeName) }
                                    .getOrDefault(TransactionType.EXPENSE)
                                val tplIsIncome = tplType == TransactionType.INCOME
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
                                            type = tplType,
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
                                                text = (if (tplIsIncome) "+" else "-") +
                                                    formatMoneyCents(tpl.amountCents, tpl.currency),
                                                fontFamily = ICDinFontFamily,
                                                fontSize = 12.sp,
                                                color = if (tplIsIncome) ICostGreenIncome else ICostCoralRed
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
                    .padding(start = 14.dp, end = 0.dp),
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
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable { searchQuery = "" },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "清除搜索",
                                tint = ICostTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
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
                    Spacer(modifier = Modifier.width(4.dp))
                    MonthArrowButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "上个月",
                        onClick = onPrevMonth
                    )
                    MonthArrowButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "下个月",
                        onClick = onNextMonth
                    )
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFEBEBEB),
                    modifier = Modifier.iCostPressScale(pressedScale = 0.92f) {
                        onOpenCalendar(allMonthTransactions)
                    }
                ) {
                    Text(
                        text = "收支日历",
                        fontSize = 12.sp,
                        color = Color(0xFF666666),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }

        // 5. 月度概览三页左右滑动卡片（对齐 01_ledger_home.png / 02_home_card_page2.png / 03_home_card_page3.png）
        item {
            // 左右滑动切换三页，底部圆点指示当前页（不再整卡点击轮播，避免与预算设置冲突）
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp)),
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
                            // Page 0: 总支出 / 总收入 / 月结余
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
                                            text = "总支出",
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
                                val hasBudget = monthlyBudgetCents > 0L
                                val remainCents = monthlyBudgetCents - monthExpenseCents
                                val isOverspent = hasBudget && remainCents < 0L
                                // 剩余日均：当月按今天起算；未来月份按整月天数；已过去的月份不再计算
                                val now = Calendar.getInstance()
                                val nowKey = now.get(Calendar.YEAR) * 12 + now.get(Calendar.MONTH)
                                val selectedKey = selectedYear * 12 + (selectedMonth - 1)
                                val selectedMonthDays = Calendar.getInstance().apply {
                                    clear()
                                    set(selectedYear, selectedMonth - 1, 1)
                                }.getActualMaximum(Calendar.DAY_OF_MONTH)
                                val dailyText = when {
                                    selectedKey < nowKey -> "本月已结束"
                                    else -> {
                                        val remainDays = if (selectedKey == nowKey) {
                                            (selectedMonthDays - now.get(Calendar.DAY_OF_MONTH) + 1).coerceAtLeast(1)
                                        } else {
                                            selectedMonthDays
                                        }
                                        val daily = if (remainCents > 0L) remainCents / remainDays else 0L
                                        "剩余日均 ${formatMoneyCents(daily)}"
                                    }
                                }

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
                                        Spacer(modifier = Modifier.weight(1f))
                                        // 明确的预算设置入口
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = ICostCellGray,
                                            modifier = Modifier
                                                .iCostPressScale(pressedScale = 0.92f) { showBudgetEditDialog = true }
                                        ) {
                                            Text(
                                                text = if (hasBudget) "修改预算" else "设置预算",
                                                fontSize = 12.sp,
                                                color = ICostBlueAccent,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    if (hasBudget) {
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
                                                text = dailyText,
                                                fontSize = 13.sp,
                                                color = ICostTextSecondary
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = "未设置预算",
                                            fontSize = 30.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ICostTextSecondary,
                                            modifier = Modifier
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null
                                                ) { showBudgetEditDialog = true }
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Text(
                                            text = "设置每月预算后，这里会显示剩余额度和每日可用",
                                            fontSize = 13.sp,
                                            color = ICostTextSecondary
                                        )
                                    }
                                }
                            }

                            // Page 2: 待报销 / 已报销 / 待报销笔数
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
                                            text = "待报销 ${pendingReimburseList.size} 笔",
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
                val isSearchEmpty = trimmedQuery.isNotEmpty() && allMonthTransactions.isNotEmpty()
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
                        Text(
                            text = if (isSearchEmpty) "没有匹配「$trimmedQuery」的账单" else "本月还没有账单",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = ICostTextPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isSearchEmpty) "换个关键词试试，可搜索分类、备注或标签" else "点右下角 + 记一笔",
                            fontSize = 13.sp,
                            color = ICostTextSecondary,
                            textAlign = TextAlign.Center
                        )
                        if (isSearchEmpty) {
                            Spacer(modifier = Modifier.height(10.dp))
                            TextButton(onClick = { searchQuery = "" }) {
                                Text("清除搜索", color = ICostBlueAccent, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        } else {
            groupedByDay.forEach { (dayKey, dayList) ->
                val dayHeader = dayHeaderFmt.format(Date(dayList.first().timestamp))
                val dayNetExpenseCents = dayList
                    .filter { it.type == TransactionType.EXPENSE }
                    .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }
                val isCollapsed = dayKey in collapsedDayKeys

                item(key = "day_$dayKey") {
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
                                .iCostPressScale(pressedScale = 0.98f) {
                                    collapsedDayKeys = if (isCollapsed) collapsedDayKeys - dayKey else collapsedDayKeys + dayKey
                                }
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
                                        val pendingReimburse = tx.type == TransactionType.EXPENSE &&
                                            tx.reimbursable && !tx.reimbursed
                                        // 最多 3 个操作：编辑 / (报销入账 | 退款 | 复制) / 删除
                                        val swipeActions = buildList {
                                            add(
                                                SwipeActionItem(
                                                    label = "编辑",
                                                    iconAsset = "detail_edit.webp",
                                                    backgroundColor = ICostBlueAccent,
                                                    onClick = { onEditTransaction(tx) }
                                                )
                                            )
                                            when {
                                                pendingReimburse -> add(
                                                    SwipeActionItem(
                                                        label = "报销",
                                                        iconAsset = "detail_claim.webp",
                                                        backgroundColor = ICostGreenIncome,
                                                        onClick = { settlingTx = tx }
                                                    )
                                                )
                                                canRefund -> add(
                                                    SwipeActionItem(
                                                        label = "退款",
                                                        iconAsset = "detail_refund.webp",
                                                        backgroundColor = ICostAmberGold,
                                                        onClick = { refundingTx = tx }
                                                    )
                                                )
                                                else -> add(
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
                                                    onClick = { deletingTx = tx }
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

    // 侧滑全额退款确认弹窗
    refundingTx?.let { targetTx ->
        val remainCents = (targetTx.amountCnyCents - targetTx.refundedAmountCents).coerceAtLeast(0L)
        val walletName = formatWalletDisplayName(targetTx.sourceWalletId?.let { walletMap[it] }, "原账户")
        ICostConfirmDialog(
            title = "全额退款？",
            message = "「${targetTx.merchant.ifBlank { targetTx.category }}」可退 ${formatMoneyCents(remainCents)}，" +
                "将退回到「$walletName」。如需部分退款，请在账单详情中操作。",
            confirmText = "退款",
            onConfirm = {
                onQuickRefund(targetTx)
                refundingTx = null
            },
            onDismiss = { refundingTx = null }
        )
    }

    // 侧滑报销入账确认弹窗
    settlingTx?.let { targetTx ->
        val amountCents = (targetTx.amountCnyCents - targetTx.refundedAmountCents).coerceAtLeast(0L)
        val walletName = formatWalletDisplayName(targetTx.sourceWalletId?.let { walletMap[it] }, "原账户")
        ICostConfirmDialog(
            title = "确认报销到账？",
            message = "报销款 ${formatMoneyCents(amountCents)} 将记入「$walletName」，这笔账单会标记为已报销。",
            confirmText = "确认到账",
            destructive = false,
            onConfirm = {
                onToggleReimburse(targetTx)
                settlingTx = null
            },
            onDismiss = { settlingTx = null }
        )
    }

    // 侧滑删除确认弹窗
    deletingTx?.let { targetTx ->
        ICostConfirmDialog(
            title = "删除这笔账单？",
            message = "「${targetTx.merchant.ifBlank { targetTx.category }}」${formatMoneyCents(targetTx.amountCents, targetTx.currency)}" +
                " 删除后，相关账户余额会同步恢复。",
            confirmText = "删除",
            onConfirm = {
                onDeleteTransaction(targetTx.id)
                deletingTx = null
            },
            onDismiss = { deletingTx = null }
        )
    }

    // 修改预算弹窗（留空或 0 表示不设预算）
    if (showBudgetEditDialog) {
        var budgetText by remember(monthlyBudgetCents) {
            mutableStateOf(
                if (monthlyBudgetCents > 0L) {
                    String.format(Locale.US, "%.2f", monthlyBudgetCents / 100.0).trimEnd('0').trimEnd('.')
                } else ""
            )
        }
        val parsedCents = if (budgetText.isBlank()) 0L else parseYuanToCents(budgetText)
        val isInvalid = parsedCents == null
        AlertDialog(
            onDismissRequest = { showBudgetEditDialog = false },
            containerColor = ICostCardWhite,
            title = { Text("设置月预算", fontWeight = FontWeight.Bold) },
            text = {
                ICostFormField(
                    value = budgetText,
                    onValueChange = { budgetText = sanitizeMoneyInput(it) },
                    label = "每月预算（元）",
                    placeholder = "0.00",
                    supportingText = if (isInvalid) "请输入正确的金额" else "留空或填 0 表示不设预算",
                    isError = isInvalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isInvalid,
                    onClick = {
                        onUpdateBudgetYuan((parsedCents ?: 0L) / 100.0)
                        showBudgetEditDialog = false
                    }
                ) {
                    Text("保存", fontWeight = FontWeight.Bold, color = if (isInvalid) ICostTextSecondary else ICostBlueAccent)
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
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
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
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    ICostFormField(
                        value = newLedgerName,
                        onValueChange = { newLedgerName = it },
                        label = "新建账本名称",
                        placeholder = "如：家庭账本、旅行账本",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                if (newLedgerName.isNotBlank()) {
                    TextButton(onClick = {
                        onCreateLedger(newLedgerName.trim())
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

/** 月份切换箭头：可视 28dp 白色圆，触控区 40dp */
@Composable
private fun MonthArrowButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .iCostPressScale(pressedScale = 0.88f, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(ICostCardWhite),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = ICostTextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
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
        TransactionType.TRANSFER -> "${sourceWallet?.name ?: "转出"} → ${targetWallet?.name ?: "转入"}"
        TransactionType.REPAYMENT -> "${sourceWallet?.name ?: "储蓄卡"} → ${targetWallet?.name ?: "信用卡"}"
    }

    val titleText = when (tx.type) {
        TransactionType.REFUND_OFFSET -> if (tx.category.isNotBlank() && tx.category != "退款") tx.category else "退款入账"
        TransactionType.REPAYMENT -> "转至${targetWallet?.name ?: "信用账户"}"
        TransactionType.TRANSFER -> "转至${targetWallet?.name ?: "目标账户"}"
        else -> tx.category
    }

    val rawGoods = tx.note.substringAfter("| ", "").trim()
    val cleanedGoods = rawGoods
        .let { g ->
            if (tx.paymentMethodRaw.isNotBlank() && g.endsWith(" · ${tx.paymentMethodRaw}")) {
                g.removeSuffix(" · ${tx.paymentMethodRaw}").trim()
            } else {
                g.replace(Regex("""\s*·\s*(?:账户余额|微信零钱|零钱|支付宝|微信支付|花呗|京东白条|[^·]*(?:信用卡|储蓄卡|银行卡))(?:\([^)]*\))?\s*$"""), "").trim()
            }
        }
        .let { g ->
            if (tx.merchant.isNotBlank() && g.startsWith("${tx.merchant} ")) {
                g.removePrefix("${tx.merchant} ").trim()
            } else {
                g
            }
        }

    val subtitleText = buildString {
        append(timeStr)
        if (tx.merchant.isNotBlank()) {
            append(" · ")
            append(tx.merchant)
        }
        if (cleanedGoods.isNotBlank() && cleanedGoods != tx.merchant) {
            append(" · ")
            append(cleanedGoods)
        } else if (tx.merchant.isBlank() && tx.note.isNotBlank() && !tx.note.startsWith("原价¥")) {
            append(" · ")
            append(tx.note)
        }
    }

    val isRefundedExpense = tx.type == TransactionType.EXPENSE && tx.refundedAmountCents > 0L
    val isRefundOffset = tx.type == TransactionType.REFUND_OFFSET
    val netExpenseCents = (tx.amountCnyCents - tx.refundedAmountCents).coerceAtLeast(0L)
    val hasDiscountBreakdown = (tx.type == TransactionType.EXPENSE || isRefundOffset) && tx.discountCents > 0L
    val hasTagsRow = hasDiscountBreakdown || tx.duplicateSuspected || isRefundedExpense || isRefundOffset || tx.reimbursable

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = if (hasTagsRow) Alignment.Top else Alignment.CenterVertically
    ) {
        ICostCategoryBadge(
            category = tx.category,
            type = tx.type,
            size = 40.dp
        )

        Spacer(modifier = Modifier.width(12.dp))

        // 右侧主内容列：第一行（分类标题 <-> 金额）、第二行（时间·商户·商品 <-> 账户名）、第三行（标签胶囊）
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = titleText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = ICostTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )

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
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = subtitleText,
                    fontSize = 12.sp,
                    color = ICostTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )

                Text(
                    text = walletLabel,
                    fontSize = 11.sp,
                    color = ICostTextSecondary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (hasTagsRow) {
                Spacer(modifier = Modifier.height(5.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasDiscountBreakdown) {
                        val origCents = tx.amountCents + tx.discountCents
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFFFF4EC),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Text(
                                text = "原价${formatMoneyCents(origCents)} · 优惠${formatMoneyCents(tx.discountCents)}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFD96B27),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (tx.duplicateSuspected) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFFFEFEA)
                        ) {
                            Text(
                                text = "⚠️ 疑似重复",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostCoralRed,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (isRefundedExpense || isRefundOffset) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFF0F0F0)
                        ) {
                            Text(
                                text = "已退",
                                fontSize = 10.sp,
                                color = ICostTextSecondary,
                                maxLines = 1,
                                softWrap = false,
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
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
