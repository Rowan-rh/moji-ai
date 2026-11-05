package com.moji.ai.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.ai.AiConfig
import com.moji.ai.ai.AiProviderType
import com.moji.ai.data.local.RecurringRuleEntity
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType
import com.moji.ai.service.PaymentAccessibilityService
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

// iCost 标志性配色定义
private val ICostBgGray = Color(0xFFF6F6F6)
private val ICostCardWhite = Color(0xFFFFFFFF)
private val ICostCoralRed = Color(0xFFE65540)
private val ICostAmberGold = Color(0xFFF5B400)
private val ICostBlueAccent = Color(0xFF007AFF)
private val ICostGreenIncome = Color(0xFF10B981)
private val ICostTextPrimary = Color(0xFF141414)
private val ICostTextSecondary = Color(0xFF8C8C8C)
private val ICostSearchBg = Color(0xFFEDEDED)
private val ICostDivider = Color(0xFFF2F2F2)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoJiMainScreen(viewModel: MainViewModel) {
    val wallets by viewModel.wallets.collectAsState()
    val transactions by viewModel.transactions.collectAsState()
    val recurringRules by viewModel.recurringRules.collectAsState()
    val aiConfig by viewModel.aiConfig.collectAsState()
    val sandboxStatus by viewModel.sandboxStatus.collectAsState()
    val isParsing by viewModel.isParsing.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) } // 0: 账本, 1: 资产, 2: 统计
    var searchQuery by remember { mutableStateOf("") }
    var showAddTxDialog by remember { mutableStateOf(false) }
    var showAddWalletDialog by remember { mutableStateOf(false) }
    var showAiSettingsSheet by remember { mutableStateOf(false) }
    var showAiSandboxSheet by remember { mutableStateOf(false) }

    // 月份切换状态
    val nowCal = remember { Calendar.getInstance() }
    var selectedYear by remember { mutableIntStateOf(nowCal.get(Calendar.YEAR)) }
    var selectedMonth by remember { mutableIntStateOf(nowCal.get(Calendar.MONTH) + 1) } // 1..12

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ICostBgGray)
            .statusBarsPadding()
    ) {
        when (selectedTab) {
            0 -> ICostLedgerHomeTab(
                wallets = wallets,
                transactions = transactions,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                selectedYear = selectedYear,
                selectedMonth = selectedMonth,
                onPrevMonth = {
                    if (selectedMonth == 1) {
                        selectedMonth = 12
                        selectedYear -= 1
                    } else {
                        selectedMonth -= 1
                    }
                },
                onNextMonth = {
                    if (selectedMonth == 12) {
                        selectedMonth = 1
                        selectedYear += 1
                    } else {
                        selectedMonth += 1
                    }
                },
                onOpenAiSandbox = { showAiSandboxSheet = true },
                onOpenAiSettings = { showAiSettingsSheet = true },
                onUndoTx = { viewModel.undoTransaction(it) }
            )

            1 -> ICostAssetsTab(
                wallets = wallets,
                recurringRules = recurringRules,
                onAddWallet = { showAddWalletDialog = true },
                onTriggerRuleNow = { viewModel.triggerRecurringRuleNow(it) },
                onOpenAiSettings = { showAiSettingsSheet = true }
            )

            2 -> ICostStatisticsTab(
                transactions = transactions,
                selectedYear = selectedYear,
                selectedMonth = selectedMonth,
                onOpenAiSandbox = { showAiSandboxSheet = true },
                onOpenAiSettings = { showAiSettingsSheet = true }
            )
        }

        // iCost 标志性底部悬浮胶囊导航栏 + 右下角独立白色圆按钮 "+"
        ICostFloatingBottomBar(
            selectedTab = selectedTab,
            onSelectTab = { selectedTab = it },
            onPlusClick = {
                if (selectedTab == 1) {
                    showAddWalletDialog = true
                } else {
                    showAddTxDialog = true
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 12.dp)
        )
    }

    if (showAddTxDialog && wallets.isNotEmpty()) {
        ManualTransactionDialog(
            wallets = wallets,
            onDismiss = { showAddTxDialog = false },
            onConfirm = { type, amount, currency, category, merchant, note, fromId, toId ->
                viewModel.addManualTransaction(type, amount, currency, category, merchant, note, fromId, toId)
                showAddTxDialog = false
            }
        )
    }

    if (showAddWalletDialog) {
        AddWalletDialog(
            onDismiss = { showAddWalletDialog = false },
            onConfirm = { name, type, currency, balance, limit, last4, aliases ->
                viewModel.addManualWallet(name, type, currency, balance, limit, last4, aliases)
                showAddWalletDialog = false
            }
        )
    }

    if (showAiSandboxSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAiSandboxSheet = false },
            containerColor = ICostCardWhite
        ) {
            AiSandboxSheetContent(
                isParsing = isParsing,
                statusMessage = sandboxStatus,
                onRunSimulation = { text, pkg -> viewModel.simulateCapturedEvent(text, pkg) }
            )
        }
    }

    if (showAiSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAiSettingsSheet = false },
            containerColor = ICostCardWhite
        ) {
            AiAndPermissionsSheetContent(
                config = aiConfig,
                onSaveConfig = { viewModel.updateAiConfig(it) }
            )
        }
    }
}

/**
 * 1:1 复刻 iCost 首页账本风格：
 * - 顶部左右圆形按钮（切换/AI沙盒、更多设置/AI引擎）
 * - 超大加粗「默认账本」标题
 * - 圆角浅灰搜索框「输入关键词」
 * - 年月切换器 `<` `>` + 「AI 识账沙盒」快捷胶囊
 * - 白色大圆角月度统计卡片（珊瑚红「总支出 ⇄」、超大紧凑黑体金额、总收入与月结余、底部三圆点指示器）
 * - 按日期分组的白色大圆角卡片组，左侧彩色圆形分类图标，右侧珊瑚红/金黄金额 + 账户名称 + 全额退款冲销胶囊与原价划线
 */
@Composable
private fun ICostLedgerHomeTab(
    wallets: List<WalletEntity>,
    transactions: List<TransactionEntity>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedYear: Int,
    selectedMonth: Int,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onOpenAiSandbox: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onUndoTx: (Long) -> Unit
) {
    val walletMap = remember(wallets) { wallets.associateBy { it.id } }

    // 过滤当月及搜索词
    val monthTransactions = remember(transactions, selectedYear, selectedMonth, searchQuery) {
        val cal = Calendar.getInstance()
        transactions.filter { tx ->
            cal.timeInMillis = tx.timestamp
            val matchMonth = cal.get(Calendar.YEAR) == selectedYear && (cal.get(Calendar.MONTH) + 1) == selectedMonth
            val matchQuery = if (searchQuery.isBlank()) true else {
                tx.category.contains(searchQuery, ignoreCase = true) ||
                    tx.merchant.contains(searchQuery, ignoreCase = true) ||
                    tx.note.contains(searchQuery, ignoreCase = true)
            }
            matchMonth && matchQuery
        }
    }

    // 计算月总支出（已扣减被退款/AA冲销的部分）、总收入、月结余
    val monthExpenseCents = monthTransactions
        .filter { it.type == TransactionType.EXPENSE }
        .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }

    val monthIncomeCents = monthTransactions
        .filter { it.type == TransactionType.INCOME }
        .sumOf { it.amountCnyCents }

    val monthBalanceCents = monthIncomeCents - monthExpenseCents

    // 按日期 ("MM/dd 星期X") 分组
    val groupedByDay = remember(monthTransactions) {
        val dayFmt = SimpleDateFormat("MM/dd EEEE", Locale.CHINA)
        monthTransactions.groupBy { tx -> dayFmt.format(Date(tx.timestamp)) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 顶部圆形按钮栏 (iCost 标志性左切换、右更多 + AI头像)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onOpenAiSandbox,
                    shape = CircleShape,
                    color = ICostCardWhite,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = "AI 识账测试",
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

                    // 右上角 AI 灵动徽章头像（点按打开 AI 识账沙盒）
                    Surface(
                        onClick = onOpenAiSandbox,
                        shape = CircleShape,
                        color = Color(0xFFD9F2E6),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = "AI 识账沙盒",
                                tint = Color(0xFF0F766E),
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }
                }
            }
        }

        // 超大加粗「默认账本」标题
        item {
            Text(
                text = "默认账本",
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ICostTextPrimary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // 圆角浅灰搜索框「输入关键词」
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
                            onValueChange = onSearchChange,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // 年月选择器 + 右侧胶囊按钮
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
                    onClick = onOpenAiSandbox,
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFEFEFEF)
                ) {
                    Text(
                        text = "AI 识账沙盒",
                        fontSize = 13.sp,
                        color = Color(0xFF595959),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }
        }

        // iCost 标志性月度总支出纯白大圆角卡片
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                ) {
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
                        text = String.format(Locale.US, "¥%.2f", monthExpenseCents / 100.0),
                        fontSize = 38.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.SansSerif,
                        color = ICostTextPrimary,
                        letterSpacing = (-1).sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = String.format(Locale.US, "总收入  ¥%.2f", monthIncomeCents / 100.0),
                            fontSize = 14.sp,
                            color = ICostTextSecondary
                        )
                        val balPrefix = if (monthBalanceCents < 0) "-¥" else "¥"
                        Text(
                            text = String.format(
                                Locale.US,
                                "月结余  %s%.2f",
                                balPrefix,
                                kotlin.math.abs(monthBalanceCents) / 100.0
                            ),
                            fontSize = 14.sp,
                            color = ICostTextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 底部三圆点指示器 (复刻 iCost 橙黄活跃圆点)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(ICostAmberGold)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE0E0E0))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE0E0E0))
                        )
                    }
                }
            }
        }

        // 如果当月还没有账单，提供一键生成示例或打开沙盒入口
        if (groupedByDay.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = ICostCardWhite)
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
                            "付款时「默记」会在后台自动静默入账。你也可以点击下方按钮模拟微信/支付宝/信用卡付款与退款冲销：",
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
                            Text("体验 AI 自动识账沙盒")
                        }
                    }
                }
            }
        } else {
            // 按日分组展示（1:1 复刻 iCost 的日期头 + 圆角白卡片内分割线列表）
            groupedByDay.forEach { (dayHeader, dayList) ->
                val dayNetExpenseCents = dayList
                    .filter { it.type == TransactionType.EXPENSE }
                    .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }

                item(key = "header_$dayHeader") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // 日期头部：左侧 "11/05 星期四"，右侧 "支出: ¥48.23 ⌄"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
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
                                    text = String.format(Locale.US, "支出: ¥%.2f", dayNetExpenseCents / 100.0),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = ICostTextSecondary
                                )
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = ICostTextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // 当日所有流水包裹在同一个纯白大圆角卡片中
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                dayList.forEachIndexed { index, tx ->
                                    ICostTransactionRow(
                                        tx = tx,
                                        walletMap = walletMap,
                                        onUndo = { onUndoTx(tx.id) }
                                    )
                                    if (index < dayList.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 68.dp, end = 16.dp),
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

/**
 * 1:1 复刻 iCost 的单行账单样式：
 * - 左侧 42dp 圆形饱和色块 + 居中纯白图标（支出为珊瑚红圆底，退款入账为琥珀金黄圆底，转账/还款为蓝紫圆底）
 * - 中间：主标题（分类名，如 "餐饮-零食" 或 "退款入账"），副标题（"15:22 · 唐久(环普产业园店)"）
 * - 若该笔支出发生了退款/AA冲销：底部显示浅灰圆角小标签 `全额退款` 或 `已冲销 ¥xx`，右侧原金额浅灰删除线 + 真实净支出 `-¥0.00`
 * - 右侧：第一行金额（珊瑚红 `-¥15.84` 或金黄 `¥5.00`），第二行账户名（如 `招商银行信用卡 3354` 或 `账户余额`）
 */
@Composable
private fun ICostTransactionRow(
    tx: TransactionEntity,
    walletMap: Map<Long, WalletEntity>,
    onUndo: () -> Unit
) {
    val timeStr = remember(tx.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(tx.timestamp))
    }
    val sym = CurrencyCode.fromCode(tx.currency).symbol

    val sourceWallet = tx.sourceWalletId?.let { walletMap[it] }
    val targetWallet = tx.targetWalletId?.let { walletMap[it] }

    val walletLabel = when (tx.type) {
        TransactionType.EXPENSE -> formatWalletLabel(sourceWallet, "账户余额")
        TransactionType.INCOME -> formatWalletLabel(targetWallet ?: sourceWallet, "账户余额")
        TransactionType.REFUND_OFFSET -> formatWalletLabel(targetWallet ?: sourceWallet, "账户余额")
        TransactionType.TRANSFER -> "${sourceWallet?.name ?: "转出"} ➔ ${targetWallet?.name ?: "转入"}"
        TransactionType.REPAYMENT -> "${sourceWallet?.name ?: "储蓄卡"} ➔ ${targetWallet?.name ?: "信用卡"}"
    }

    val (circleColor, iconVector) = remember(tx.type, tx.category) {
         resolveICostIconAndColor(tx.type, tx.category)
    }

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
            .clickable { onUndo() } // 点击可快速撤销测试或管理
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 左侧圆形图标
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(circleColor),
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

        // 中间标题 + 时间商户 + 退款胶囊
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = titleText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = ICostTextPrimary
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitleText,
                fontSize = 13.sp,
                color = ICostTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (isRefundedExpense) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFF2F2F2)
                ) {
                    Text(
                        text = if (isFullRefund) "全额退款" else String.format(Locale.US, "已退 ¥%.2f", tx.refundedAmountCents / 100.0),
                        fontSize = 11.sp,
                        color = ICostTextSecondary,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 右侧金额 + 账户名称（完全对齐 iCost 的原价划线 + 净额展示）
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRefundedExpense) {
                    Text(
                        text = String.format(Locale.US, "%s%.2f", sym, tx.amountCents / 100.0),
                        fontSize = 13.sp,
                        color = ICostTextSecondary,
                        textDecoration = TextDecoration.LineThrough
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = String.format(Locale.US, "-¥%.2f", netExpenseCents / 100.0),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ICostCoralRed
                    )
                } else {
                    val amountText = when (tx.type) {
                        TransactionType.EXPENSE -> String.format(Locale.US, "-%s%.2f", sym, tx.amountCents / 100.0)
                        TransactionType.REFUND_OFFSET -> String.format(Locale.US, "%s%.2f", sym, tx.amountCents / 100.0)
                        TransactionType.INCOME -> String.format(Locale.US, "+%s%.2f", sym, tx.amountCents / 100.0)
                        TransactionType.TRANSFER, TransactionType.REPAYMENT -> String.format(Locale.US, "%s%.2f", sym, tx.amountCents / 100.0)
                    }
                    val amountColor = when (tx.type) {
                        TransactionType.EXPENSE -> ICostCoralRed
                        TransactionType.REFUND_OFFSET -> ICostAmberGold
                        TransactionType.INCOME -> ICostGreenIncome
                        TransactionType.TRANSFER, TransactionType.REPAYMENT -> ICostTextPrimary
                    }
                    Text(
                        text = amountText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = amountColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = walletLabel,
                fontSize = 12.sp,
                color = ICostTextSecondary
            )
        }
    }
}

private fun formatWalletLabel(wallet: WalletEntity?, fallback: String): String {
    if (wallet == null) return fallback
    return if (!wallet.cardLast4.isNullOrBlank()) {
        "${wallet.name} ${wallet.cardLast4}"
    } else {
        wallet.name
    }
}

private fun resolveICostIconAndColor(
    type: TransactionType,
    category: String
): Pair<Color, ImageVector> {
    if (type == TransactionType.REFUND_OFFSET) {
        return ICostAmberGold to Icons.Default.CurrencyExchange
    }
    if (type == TransactionType.TRANSFER) {
        return Color(0xFF3B82F6) to Icons.Default.SwapHoriz
    }
    if (type == TransactionType.REPAYMENT) {
        return Color(0xFF8B5CF6) to Icons.Default.CreditCard
    }
    if (type == TransactionType.INCOME) {
        return ICostGreenIncome to Icons.Default.Payments
    }

    val icon = when {
        category.contains("零食") || category.contains("甜品") -> Icons.Default.Cake
        category.contains("咖啡") || category.contains("茶") -> Icons.Default.LocalCafe
        category.contains("餐") || category.contains("饭") -> Icons.Default.Restaurant
        category.contains("购") || category.contains("百货") || category.contains("超市") -> Icons.Default.ShoppingCart
        category.contains("交通") || category.contains("车") || category.contains("出行") -> Icons.Default.DirectionsCar
        category.contains("数码") || category.contains("娱乐") || category.contains("订阅") -> Icons.Default.SportsEsports
        category.contains("居住") || category.contains("房") -> Icons.Default.Home
        else -> Icons.Default.Restaurant
    }
    return ICostCoralRed to icon
}

/**
 * 1:1 复刻 iCost 底部悬浮胶囊导航栏 (`账本` | `资产` | `统计`) + 右侧独立圆形 `+` 按钮
 */
@Composable
private fun ICostFloatingBottomBar(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onPlusClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧三合一白色圆角胶囊导航栏
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(64.dp)
                .shadow(elevation = 12.dp, shape = RoundedCornerShape(32.dp), spotColor = Color.Black.copy(alpha = 0.12f)),
            shape = RoundedCornerShape(32.dp),
            color = ICostCardWhite
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val tabs = listOf(
                    Triple(0, "账本", Icons.Default.Book),
                    Triple(1, "资产", Icons.Default.Layers),
                    Triple(2, "统计", Icons.Default.BarChart)
                )
                tabs.forEach { (index, label, icon) ->
                    val isSelected = selectedTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(28.dp))
                            .background(if (isSelected) Color(0xFFF0F2F5) else Color.Transparent)
                            .clickable { onSelectTab(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = if (isSelected) ICostBlueAccent else ICostTextPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) ICostBlueAccent else ICostTextPrimary
                            )
                        }
                    }
                }
            }
        }

        // 右侧独立白色圆形 "+" 记账按钮
        Surface(
            onClick = onPlusClick,
            modifier = Modifier
                .size(64.dp)
                .shadow(elevation = 12.dp, shape = CircleShape, spotColor = Color.Black.copy(alpha = 0.12f)),
            shape = CircleShape,
            color = ICostCardWhite
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "记一笔",
                    tint = ICostTextPrimary,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    }
}

/**
 * 资产页（iCost 极简卡片风格展示净资产、资金账户、信用账户与周期性固定账单）
 */
@Composable
private fun ICostAssetsTab(
    wallets: List<WalletEntity>,
    recurringRules: List<RecurringRuleEntity>,
    onAddWallet: () -> Unit,
    onTriggerRuleNow: (RecurringRuleEntity) -> Unit,
    onOpenAiSettings: () -> Unit
) {
    val totalAssetsCnyCents = wallets.filter { !it.type.isLiability && it.includeInNetWorth }.sumOf { w ->
        val rate = CurrencyCode.fromCode(w.currency).defaultRateToCny
        (w.balanceCents * rate).roundToLong()
    }
    val totalLiabilityCnyCents = wallets.filter { it.type.isLiability && it.includeInNetWorth }.sumOf { w ->
        val rate = CurrencyCode.fromCode(w.currency).defaultRateToCny
        (w.balanceCents * rate).roundToLong()
    }
    val netWorthCnyCents = totalAssetsCnyCents - totalLiabilityCnyCents

    val assetWallets = wallets.filter { !it.type.isLiability }
    val creditWallets = wallets.filter { it.type.isLiability }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "资产管理",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = ICostTextPrimary
                )
                Surface(
                    onClick = onOpenAiSettings,
                    shape = CircleShape,
                    color = ICostCardWhite,
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Tune, contentDescription = "AI配置", tint = ICostTextPrimary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // 净资产总览白卡片
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = ICostCardWhite)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("净资产 (CNY)", fontSize = 14.sp, color = ICostTextSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = String.format(Locale.US, "¥%.2f", netWorthCnyCents / 100.0),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = ICostTextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text(
                            text = String.format(Locale.US, "总资产  ¥%.2f", totalAssetsCnyCents / 100.0),
                            fontSize = 14.sp,
                            color = ICostTextSecondary
                        )
                        Text(
                            text = String.format(Locale.US, "总负债  -¥%.2f", totalLiabilityCnyCents / 100.0),
                            fontSize = 14.sp,
                            color = ICostCoralRed
                        )
                    }
                }
            }
        }

        // 资金账户分组
        item {
            Text("资金账户 (${assetWallets.size})", fontSize = 14.sp, color = ICostTextSecondary, modifier = Modifier.padding(start = 4.dp))
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = ICostCardWhite)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    assetWallets.forEachIndexed { idx, w ->
                        ICostWalletRow(w)
                        if (idx < assetWallets.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(start = 66.dp, end = 16.dp), thickness = 0.6.dp, color = ICostDivider)
                        }
                    }
                }
            }
        }

        // 信用/负债账户分组
        item {
            Text("信用账户 (${creditWallets.size})", fontSize = 14.sp, color = ICostTextSecondary, modifier = Modifier.padding(start = 4.dp))
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = ICostCardWhite)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    creditWallets.forEachIndexed { idx, w ->
                        ICostWalletRow(w)
                        if (idx < creditWallets.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(start = 66.dp, end = 16.dp), thickness = 0.6.dp, color = ICostDivider)
                        }
                    }
                }
            }
        }

        // 周期性固定收支
        if (recurringRules.isNotEmpty()) {
            item {
                Text("周期性自动入账 (${recurringRules.size})", fontSize = 14.sp, color = ICostTextSecondary, modifier = Modifier.padding(start = 4.dp))
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = ICostCardWhite)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        recurringRules.forEach { rule ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${rule.title} · ${rule.frequency.displayName}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text(
                                        String.format(Locale.US, "¥%.2f · %s", rule.amountCents / 100.0, rule.category),
                                        fontSize = 13.sp,
                                        color = ICostTextSecondary
                                    )
                                }
                                TextButton(onClick = { onTriggerRuleNow(rule) }) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text("立即执行")
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
private fun ICostWalletRow(wallet: WalletEntity) {
    val sym = CurrencyCode.fromCode(wallet.currency).symbol
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(wallet.colorHex)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (wallet.type.isLiability) Icons.Default.CreditCard else Icons.Default.AccountBalance,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(wallet.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ICostTextPrimary)
                if (!wallet.cardLast4.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(wallet.cardLast4, fontSize = 13.sp, color = ICostTextSecondary)
                }
            }
            if (wallet.type.isLiability && wallet.creditLimitCents != null) {
                val remain = (wallet.creditLimitCents - wallet.balanceCents).coerceAtLeast(0L) / 100.0
                Text(
                    String.format(Locale.US, "可用额度 %s%.2f · 还款日 %d日", sym, remain, wallet.repaymentDay ?: 10),
                    fontSize = 12.sp,
                    color = ICostTextSecondary
                )
            } else {
                Text(
                    wallet.aliases.ifBlank { wallet.type.displayName },
                    fontSize = 12.sp,
                    color = ICostTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = if (wallet.type.isLiability) {
                String.format(Locale.US, "-%s%.2f", sym, wallet.balanceCents / 100.0)
            } else {
                String.format(Locale.US, "%s%.2f", sym, wallet.balanceCents / 100.0)
            },
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (wallet.type.isLiability) ICostCoralRed else ICostTextPrimary
        )
    }
}

/**
 * 统计页（iCost 极简卡片分类排行 + AI 识账与系统权限快捷面板）
 */
@Composable
private fun ICostStatisticsTab(
    transactions: List<TransactionEntity>,
    selectedYear: Int,
    selectedMonth: Int,
    onOpenAiSandbox: () -> Unit,
    onOpenAiSettings: () -> Unit
) {
    val expenses = transactions.filter { it.type == TransactionType.EXPENSE }
    val totalExpenseCents = expenses.sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }.coerceAtLeast(1L)
    val byCategory = expenses
        .groupBy { it.category }
        .mapValues { (_, list) -> list.sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) } }
        .entries
        .filter { it.value > 0 }
        .sortedByDescending { it.value }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "统计与 AI",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = ICostTextPrimary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        onClick = onOpenAiSandbox,
                        shape = RoundedCornerShape(16.dp),
                        color = ICostCardWhite
                    ) {
                        Text(
                            "AI 沙盒",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = ICostCoralRed,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                    Surface(
                        onClick = onOpenAiSettings,
                        shape = RoundedCornerShape(16.dp),
                        color = ICostCardWhite
                    ) {
                        Text(
                            "AI 与权限",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = ICostBlueAccent,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = ICostCardWhite)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("支出分类构成（已自动剔除退款与AA冲销）", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (byCategory.isEmpty()) {
                        Text("暂无支出数据，可点击右上角「AI 沙盒」一键生成测试账单。", fontSize = 13.sp, color = ICostTextSecondary)
                    } else {
                        byCategory.forEach { (cat, cents) ->
                            val ratio = (cents.toFloat() / totalExpenseCents.toFloat()).coerceIn(0f, 1f)
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("$cat  ${(ratio * 100).roundToLong()}%", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(String.format(Locale.US, "¥%.2f", cents / 100.0), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { ratio },
                                    color = ICostCoralRed,
                                    trackColor = Color(0xFFF2F2F2),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(7.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiSandboxSheetContent(
    isParsing: Boolean,
    statusMessage: String?,
    onRunSimulation: (String, String) -> Unit
) {
    var customInput by remember {
        mutableStateOf("【盒马】支付成功！向商户【盒马】付款 15.84元，支付方式：账户余额")
    }

    // 结合用户真实 iCost 截图中的常见场景 + 复杂转账/还款/冲销场景
    val presetScenarios = listOf(
        "盒马买菜 ¥15.84" to "【支付宝】付款成功：向商户【盒马】付款 15.84元，付款方式：账户余额",
        "京东购物(招行信用卡)" to "【招商银行】您尾号6612的招商银行信用卡消费人民币27.39元，商户：京东-订单编号3673486013948720",
        "唐久零食 ¥5.00" to "【微信支付】向商户【唐久(环普产业园店)】付款 5.00元，商品：餐饮-零食",
        "唐久全额退款入账 ¥5.00" to "【微信支付】退款入账通知：商户【唐久(环普产业园店)】已退回 5.00元 至您的微信零钱",
        "招行卡还花呗(信用还款)" to "【支付宝】还款成功：您已使用招商银行储蓄卡(8848)成功偿还蚂蚁花呗账单 864.00元",
        "招行卡转入零钱(内部转账)" to "【招商银行】您尾号8848的储蓄卡充值到微信零钱人民币1000.00元"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("AI 静默识账沙盒测试台", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = ICostTextPrimary)
        Text(
            "点击下方任意场景，立即模拟通知栏/无障碍捕获 ➔ AI 语义解析 ➔ 自动联动账本与全额退款冲销：",
            fontSize = 13.sp,
            color = ICostTextSecondary
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presetScenarios.forEach { (label, sampleText) ->
                AssistChip(
                    onClick = {
                        customInput = sampleText
                        onRunSimulation(sampleText, "com.tencent.mm")
                    },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }

        OutlinedTextField(
            value = customInput,
            onValueChange = { customInput = it },
            label = { Text("模拟通知栏 / 无障碍屏幕抓取文本") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )

        Button(
            onClick = { onRunSimulation(customInput, "com.tencent.mm") },
            enabled = !isParsing && customInput.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = ICostCoralRed),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            if (isParsing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("AI 正在静默入账...")
            } else {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("立即静默入账并推送通知栏提醒", fontWeight = FontWeight.Bold)
            }
        }

        if (!statusMessage.isNullOrBlank()) {
            Surface(
                color = ICostBgGray,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = statusMessage,
                    modifier = Modifier.padding(14.dp),
                    fontSize = 13.sp,
                    color = ICostTextPrimary
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun AiAndPermissionsSheetContent(
    config: AiConfig,
    onSaveConfig: (AiConfig) -> Unit
) {
    val context = LocalContext.current
    var provider by remember(config) { mutableStateOf(config.provider) }
    var baseUrl by remember(config) { mutableStateOf(config.baseUrl) }
    var apiKey by remember(config) { mutableStateOf(config.apiKey) }
    var modelName by remember(config) { mutableStateOf(config.modelName) }
    var enableAccessibility by remember(config) { mutableStateOf(config.enableAccessibilityCapture) }
    var enableNotification by remember(config) { mutableStateOf(config.enableNotificationCapture) }
    var enableFallback by remember(config) { mutableStateOf(config.enableHeuristicFallback) }
    var savedBanner by remember { mutableStateOf(false) }

    val isNotifGranted = isNotificationListenerEnabled(context)
    val isAccessGranted = isAccessibilityServiceEnabled(context)

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text("AI 识账引擎与系统权限", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }

        item {
            Surface(color = ICostBgGray, shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                if (isNotifGranted) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = if (isNotifGranted) ICostGreenIncome else ICostAmberGold,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("通知栏静默识账权限", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) {
                            Text(if (isNotifGranted) "已开启" else "去开启", fontSize = 12.sp)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                if (isAccessGranted) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = if (isAccessGranted) ICostGreenIncome else ICostAmberGold,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("无障碍支付页识账权限", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) {
                            Text(if (isAccessGranted) "已开启" else "去开启", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiProviderType.entries.forEach { item ->
                    FilterChip(
                        selected = provider == item,
                        onClick = {
                            provider = item
                            baseUrl = item.defaultBaseUrl
                            modelName = item.defaultModel
                        },
                        label = { Text(if (item == AiProviderType.OPENAI_COMPATIBLE) "OpenAI 兼容接口" else "Gemini 官方接口") }
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("API Base URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it },
                label = { Text("模型名称 (如 deepseek-chat / gemini-2.5-flash)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key (留空则使用内置本地语义引擎)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        item {
            Button(
                onClick = {
                    onSaveConfig(
                        AiConfig(
                            provider = provider,
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            modelName = modelName,
                            enableAccessibilityCapture = enableAccessibility,
                            enableNotificationCapture = enableNotification,
                            enableHeuristicFallback = enableFallback
                        )
                    )
                    savedBanner = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("保存配置", fontWeight = FontWeight.Bold)
            }
            if (savedBanner) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("✅ 已保存生效！", color = ICostGreenIncome, fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualTransactionDialog(
    wallets: List<WalletEntity>,
    onDismiss: () -> Unit,
    onConfirm: (
        type: TransactionType,
        amount: Double,
        currency: String,
        category: String,
        merchant: String,
        note: String,
        fromWalletId: Long?,
        toWalletId: Long?
    ) -> Unit
) {
    var selectedType by remember { mutableStateOf(TransactionType.EXPENSE) }
    var amountStr by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("CNY") }
    var category by remember { mutableStateOf("餐饮") }
    var merchant by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var fromWalletId by remember { mutableStateOf<Long?>(wallets.firstOrNull()?.id) }
    var toWalletId by remember { mutableStateOf<Long?>(wallets.lastOrNull()?.id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记一笔", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TransactionType.entries.forEach { t ->
                        FilterChip(
                            selected = selectedType == t,
                            onClick = { selectedType = t },
                            label = { Text(t.displayName) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = amountStr,
                        onValueChange = { amountStr = it },
                        label = { Text("金额") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = currency,
                        onValueChange = { currency = it.uppercase() },
                        label = { Text("币种") },
                        modifier = Modifier.width(100.dp),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("分类 (如：餐饮-零食 / 购物 / 数码)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("商户 / 备注描述 (如：盒马 / 唐久)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text("选择账户：", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    wallets.forEach { w ->
                        FilterChip(
                            selected = fromWalletId == w.id,
                            onClick = { fromWalletId = w.id },
                            label = { Text(w.name) }
                        )
                    }
                }
                if (selectedType == TransactionType.TRANSFER || selectedType == TransactionType.REPAYMENT) {
                    Text("选择目标账户：", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        wallets.forEach { w ->
                            FilterChip(
                                selected = toWalletId == w.id,
                                onClick = { toWalletId = w.id },
                                label = { Text(w.name) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val amt = amountStr.toDoubleOrNull() ?: return@TextButton
                onConfirm(
                    selectedType,
                    amt,
                    currency,
                    category,
                    merchant,
                    note,
                    fromWalletId,
                    if (selectedType == TransactionType.INCOME || selectedType == TransactionType.REFUND_OFFSET) fromWalletId else toWalletId
                )
            }) {
                Text("保存", color = ICostCoralRed, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddWalletDialog(
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        type: WalletType,
        currency: String,
        balance: Double,
        creditLimit: Double?,
        last4: String?,
        aliases: String
    ) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(WalletType.DEBIT_BANK) }
    var currency by remember { mutableStateOf("CNY") }
    var balanceStr by remember { mutableStateOf("0") }
    var limitStr by remember { mutableStateOf("20000") }
    var last4 by remember { mutableStateOf("") }
    var aliases by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增资产账户", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WalletType.entries.forEach { wt ->
                        FilterChip(
                            selected = type == wt,
                            onClick = { type = wt },
                            label = { Text(wt.displayName) }
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("账户名称 (如：招商银行信用卡)") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = balanceStr,
                        onValueChange = { balanceStr = it },
                        label = { Text(if (type.isLiability) "当前待还" else "当前余额") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = currency,
                        onValueChange = { currency = it.uppercase() },
                        label = { Text("币种") },
                        modifier = Modifier.width(95.dp),
                        singleLine = true
                    )
                }
                if (type.isLiability) {
                    OutlinedTextField(
                        value = limitStr,
                        onValueChange = { limitStr = it },
                        label = { Text("信用额度") },
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = last4,
                    onValueChange = { last4 = it },
                    label = { Text("卡号后四位 (如 3354)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = aliases,
                    onValueChange = { aliases = it },
                    label = { Text("AI 匹配别名 (逗号分隔)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) return@TextButton
                onConfirm(
                    name.trim(),
                    type,
                    currency.trim().ifEmpty { "CNY" },
                    balanceStr.toDoubleOrNull() ?: 0.0,
                    if (type.isLiability) limitStr.toDoubleOrNull() else null,
                    last4.trim(),
                    aliases.trim()
                )
            }) {
                Text("保存", color = ICostBlueAccent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    return flat.contains(context.packageName)
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, PaymentAccessibilityService::class.java).flattenToString()
    val flat = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return flat.contains(expected, ignoreCase = true)
}
