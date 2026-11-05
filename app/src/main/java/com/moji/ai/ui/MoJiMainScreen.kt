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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.ai.AiConfig
import com.moji.ai.ai.AiProviderType
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.model.TransactionType
import com.moji.ai.service.PaymentAccessibilityService
import com.moji.ai.ui.components.ICostCalculatorSheet
import com.moji.ai.ui.components.MonthlyCalendarSheet
import com.moji.ai.ui.components.TransactionDetailSheet
import com.moji.ai.ui.screens.ICostAssetsScreen
import com.moji.ai.ui.screens.ICostLedgerHomeScreen
import com.moji.ai.ui.screens.ICostStatisticsScreen
import com.moji.ai.ui.theme.ICostAmberGold
import com.moji.ai.ui.theme.ICostBgGray
import com.moji.ai.ui.theme.ICostBlueAccent
import com.moji.ai.ui.theme.ICostCardWhite
import com.moji.ai.ui.theme.ICostCoralRed
import com.moji.ai.ui.theme.ICostGreenIncome
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoJiMainScreen(viewModel: MainViewModel) {
    val wallets by viewModel.wallets.collectAsState()
    val transactions by viewModel.transactions.collectAsState()
    val recurringRules by viewModel.recurringRules.collectAsState()
    val ledgers by viewModel.ledgers.collectAsState()
    val currentLedger by viewModel.currentLedger.collectAsState()
    val hideAssetAmounts by viewModel.hideAssetAmounts.collectAsState()
    val aiConfig by viewModel.aiConfig.collectAsState()
    val sandboxStatus by viewModel.sandboxStatus.collectAsState()
    val isParsing by viewModel.isParsing.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) } // 0: 账本, 1: 资产, 2: 统计

    val nowCal = remember { Calendar.getInstance() }
    var selectedYear by remember { mutableIntStateOf(nowCal.get(Calendar.YEAR)) }
    var selectedMonth by remember { mutableIntStateOf(nowCal.get(Calendar.MONTH) + 1) }

    // 记账计算器弹窗状态
    var showCalculatorSheet by remember { mutableStateOf(false) }
    var editingTx by remember { mutableStateOf<TransactionEntity?>(null) }
    var calcInitialType by remember { mutableStateOf(TransactionType.EXPENSE) }
    var calcInitialSourceWalletId by remember { mutableStateOf<Long?>(null) }
    var calcInitialTargetWalletId by remember { mutableStateOf<Long?>(null) }

    // 账单详情与收支日历弹窗状态
    var inspectingTx by remember { mutableStateOf<TransactionEntity?>(null) }
    var calendarMonthTxs by remember { mutableStateOf<List<TransactionEntity>?>(null) }

    // AI 沙盒与权限设置弹窗状态
    var showAiSandboxSheet by remember { mutableStateOf(false) }
    var showAiSettingsSheet by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ICostBgGray)
            .statusBarsPadding()
    ) {
        when (selectedTab) {
            0 -> ICostLedgerHomeScreen(
                currentLedger = currentLedger,
                ledgers = ledgers,
                onSelectLedger = { viewModel.selectLedger(it) },
                onCreateLedger = { viewModel.createLedger(it) },
                wallets = wallets,
                transactions = transactions,
                selectedYear = selectedYear,
                selectedMonth = selectedMonth,
                onPrevMonth = {
                    if (selectedMonth == 1) { selectedMonth = 12; selectedYear -= 1 } else selectedMonth -= 1
                },
                onNextMonth = {
                    if (selectedMonth == 12) { selectedMonth = 1; selectedYear += 1 } else selectedMonth += 1
                },
                onOpenCalendar = { list -> calendarMonthTxs = list },
                onOpenAiSandbox = { showAiSandboxSheet = true },
                onOpenAiSettings = { showAiSettingsSheet = true },
                onClickTransaction = { tx -> inspectingTx = tx }
            )

            1 -> ICostAssetsScreen(
                wallets = wallets,
                transactions = transactions,
                recurringRules = recurringRules,
                hideAmounts = hideAssetAmounts,
                onToggleHideAmounts = { viewModel.toggleHideAssetAmounts() },
                onSaveWallet = { viewModel.saveOrUpdateWallet(it) },
                onDeleteWallet = { viewModel.deleteWallet(it) },
                onQuickActionWithWallet = { type, srcId, dstId ->
                    editingTx = null
                    calcInitialType = type
                    calcInitialSourceWalletId = srcId
                    calcInitialTargetWalletId = dstId
                    showCalculatorSheet = true
                },
                onSelectTransaction = { tx -> inspectingTx = tx },
                onTriggerRuleNow = { viewModel.triggerRecurringRuleNow(it) }
            )

            2 -> ICostStatisticsScreen(
                transactions = transactions,
                initialYear = selectedYear,
                initialMonth = selectedMonth,
                onOpenAiSandbox = { showAiSandboxSheet = true },
                onOpenAiSettings = { showAiSettingsSheet = true }
            )
        }

        // 底部悬浮胶囊导航栏 + 右下角独立白色大圆 "+" 记账按钮
        ICostFloatingBottomBar(
            selectedTab = selectedTab,
            onSelectTab = { selectedTab = it },
            onPlusClick = {
                editingTx = null
                calcInitialType = TransactionType.EXPENSE
                calcInitialSourceWalletId = null
                calcInitialTargetWalletId = null
                showCalculatorSheet = true
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 12.dp)
        )
    }

    // 1:1 iCost 分类+计算器记账面板
    if (showCalculatorSheet && wallets.isNotEmpty()) {
        ICostCalculatorSheet(
            wallets = wallets,
            currentLedger = currentLedger,
            editingTransaction = editingTx,
            initialType = calcInitialType,
            initialSourceWalletId = calcInitialSourceWalletId,
            initialTargetWalletId = calcInitialTargetWalletId,
            onDismiss = {
                showCalculatorSheet = false
                editingTx = null
            },
            onSaveTransaction = { tx, keepOpen ->
                viewModel.saveOrUpdateTransaction(tx)
                if (!keepOpen) {
                    showCalculatorSheet = false
                    editingTx = null
                }
            }
        )
    }

    // 账单详情面板（支持退款、报销入账、编辑、删除）
    val activeTx = inspectingTx?.let { target -> transactions.firstOrNull { it.id == target.id } ?: target }
    if (activeTx != null) {
        TransactionDetailSheet(
            tx = activeTx,
            wallets = wallets,
            onDismiss = { inspectingTx = null },
            onEdit = { toEdit ->
                editingTx = toEdit
                showCalculatorSheet = true
            },
            onRefund = { expId, yuan, recvId, note ->
                viewModel.refundExpense(expId, yuan, recvId, note)
            },
            onSettleReimburse = { expId, recvId ->
                viewModel.settleReimbursement(expId, recvId)
            },
            onDelete = { txId ->
                viewModel.deleteTransaction(txId)
            }
        )
    }

    // 收支日历面板
    if (calendarMonthTxs != null) {
        MonthlyCalendarSheet(
            year = selectedYear,
            month = selectedMonth,
            monthTransactions = calendarMonthTxs!!,
            onDismiss = { calendarMonthTxs = null },
            onSelectTransaction = { tx ->
                calendarMonthTxs = null
                inspectingTx = tx
            }
        )
    }

    // AI 静默识账沙盒测试面板
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

    // AI 与系统权限配置面板
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
                    contentDescription = "记账",
                    tint = ICostTextPrimary,
                    modifier = Modifier.size(30.dp)
                )
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
        mutableStateOf("【支付宝】付款成功：向商户【盒马】付款 32.80元，付款方式：账户余额")
    }

    val presetScenarios = listOf(
        "盒马买菜 ¥32.80" to "【支付宝】付款成功：向商户【盒马】付款 32.80元，付款方式：账户余额",
        "京东购物(招行信用卡)" to "【招商银行】您尾号3354的招商银行信用卡消费人民币69.00元，商户：京东商城",
        "唐久零食退款 ¥5.00" to "【微信支付】退款入账通知：商户【唐久(环普产业园店)】已退回 5.00元 至您的账户余额",
        "招行储蓄卡还信用卡" to "【招商银行】还款成功：您已使用招商银行储蓄卡(8848)成功偿还招商银行信用卡(3354)账单 500.00元",
        "招行卡充值到微信零钱" to "【招商银行】您尾号8848的储蓄卡充值到微信零钱人民币1000.00元"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("AI 静默识账测试沙盒", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = ICostTextPrimary)
        Text(
            "点击任意标签测试：AI 解析通知/页面文本 ➔ 自动匹配信用卡/零钱/转账/全额退款 ➔ 静默推送通知栏：",
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
                Text("立即静默入账并推送通知提醒", fontWeight = FontWeight.Bold)
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
        Spacer(modifier = Modifier.height(10.dp))
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
            Text("AI 自动识账与权限中心", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("断网或未填 Key 时启用本地语义引擎", fontSize = 14.sp)
                Switch(checked = enableFallback, onCheckedChange = { enableFallback = it })
            }
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
