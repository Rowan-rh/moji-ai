package com.moji.ai.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.moji.ai.ui.theme.ICDinFontFamily
import com.moji.ai.ui.theme.ICostAmberGold
import com.moji.ai.ui.theme.ICostAssetImage
import com.moji.ai.ui.theme.ICostAvatarButton
import com.moji.ai.ui.theme.ICostBgGray
import com.moji.ai.ui.theme.ICostBlueAccent
import com.moji.ai.ui.theme.ICostCardWhite
import com.moji.ai.ui.theme.ICostCircleIconButton
import com.moji.ai.ui.theme.ICostCoralRed
import com.moji.ai.ui.theme.ICostDivider
import com.moji.ai.ui.theme.ICostGreenIncome
import com.moji.ai.ui.theme.ICostTextMuted
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.formatMoneyCents
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
    val monthlyBudgetCents by viewModel.monthlyBudgetCents.collectAsState()
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

    // 全屏二级页面状态：账单详情、收支日历、设置页、智能记账页、资产子页面
    var inspectingTx by remember { mutableStateOf<TransactionEntity?>(null) }
    var calendarMonthTxs by remember { mutableStateOf<List<TransactionEntity>?>(null) }
    var showSettingsPage by remember { mutableStateOf(false) }
    var showSmartBookkeepingPage by remember { mutableStateOf(false) }
    var isAssetSubpageOpen by remember { mutableStateOf(false) }

    val hasFullScreenOverlay = inspectingTx != null ||
        calendarMonthTxs != null ||
        showSettingsPage ||
        showSmartBookkeepingPage ||
        isAssetSubpageOpen

    // AI 沙盒与模型配置弹窗状态
    var showAiSandboxSheet by remember { mutableStateOf(false) }
    var showAiSettingsSheet by remember { mutableStateOf(false) }
    var showBudgetDialog by remember { mutableStateOf(false) }

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
                monthlyBudgetCents = monthlyBudgetCents,
                onUpdateBudgetYuan = { viewModel.updateMonthlyBudgetYuan(it) },
                onPrevMonth = {
                    if (selectedMonth == 1) { selectedMonth = 12; selectedYear -= 1 } else selectedMonth -= 1
                },
                onNextMonth = {
                    if (selectedMonth == 12) { selectedMonth = 1; selectedYear += 1 } else selectedMonth += 1
                },
                onOpenCalendar = { list -> calendarMonthTxs = list },
                onOpenAiSandbox = { showAiSandboxSheet = true },
                onOpenSettingsPage = { showSettingsPage = true },
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
                onTriggerRuleNow = { viewModel.triggerRecurringRuleNow(it) },
                onOpenSettingsPage = { showSettingsPage = true },
                onSubpageActiveChange = { isAssetSubpageOpen = it }
            )

            2 -> ICostStatisticsScreen(
                transactions = transactions,
                wallets = wallets,
                initialYear = selectedYear,
                initialMonth = selectedMonth,
                onOpenAiSandbox = { showAiSandboxSheet = true },
                onOpenSettingsPage = { showSettingsPage = true }
            )
        }

        // 1:1 iCost 底部半透明毛玻璃悬浮胶囊导航栏 + 右下角独立白色大圆 "+" 记账按钮（全屏二级页打开时自动隐藏）
        if (!hasFullScreenOverlay) {
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
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }

        // 全屏收支日历页面（06_calendar_full.png）
        if (calendarMonthTxs != null) {
            MonthlyCalendarSheet(
                year = selectedYear,
                month = selectedMonth,
                monthTransactions = calendarMonthTxs!!,
                onDismiss = { calendarMonthTxs = null },
                onSelectTransaction = { tx ->
                    inspectingTx = tx
                }
            )
        }

        // 全屏账单详情页面（05_detail_full.png）
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
                    inspectingTx = null
                }
            )
        }

        // 全屏「设置」页面（09_settings_full.png）
        if (showSettingsPage) {
            ICostSettingsFullScreen(
                currentLedger = currentLedger,
                monthlyBudgetCents = monthlyBudgetCents,
                onBack = { showSettingsPage = false },
                onOpenSmartBookkeeping = { showSmartBookkeepingPage = true },
                onOpenBudgetDialog = { showBudgetDialog = true },
                onOpenAiModelConfig = { showAiSettingsSheet = true },
                onOpenAiSandbox = { showAiSandboxSheet = true }
            )
        }

        // 全屏「智能记账」页面（09_smart_bookkeeping.png）
        if (showSmartBookkeepingPage) {
            ICostSmartBookkeepingFullScreen(
                aiConfig = aiConfig,
                onUpdateAiConfig = { viewModel.updateAiConfig(it) },
                onOpenAiModelConfig = { showAiSettingsSheet = true },
                onOpenAiSandbox = { showAiSandboxSheet = true },
                onBack = { showSmartBookkeepingPage = false }
            )
        }

        // 1:1 iCost 分类+计算器记账面板（04_add_record_sheet.png）
        if (showCalculatorSheet && wallets.isNotEmpty()) {
            ICostCalculatorSheet(
                wallets = wallets,
                currentLedger = currentLedger,
                ledgers = ledgers,
                onSelectLedger = { viewModel.selectLedger(it) },
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
                },
                onOpenAiSandbox = { showAiSandboxSheet = true }
            )
        }
    }

    if (showBudgetDialog) {
        var budgetInput by remember(monthlyBudgetCents) {
            mutableStateOf(String.format("%.0f", monthlyBudgetCents / 100.0))
        }
        AlertDialog(
            onDismissRequest = { showBudgetDialog = false },
            containerColor = ICostCardWhite,
            title = { Text("设置月度预算", fontWeight = FontWeight.ExtraBold) },
            text = {
                OutlinedTextField(
                    value = budgetInput,
                    onValueChange = { budgetInput = it },
                    label = { Text("每月预算金额 (¥)") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        budgetInput.toDoubleOrNull()?.let { viewModel.updateMonthlyBudgetYuan(it) }
                        showBudgetDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent)
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBudgetDialog = false }) { Text("取消") }
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

    // AI 大模型与系统权限配置面板
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
 * 1:1 iCost 底部半透明毛玻璃悬浮胶囊导航栏 + 右下角独立白色大圆 "+" 记账按钮
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
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(58.dp)
                .shadow(
                    elevation = 10.dp,
                    shape = RoundedCornerShape(29.dp),
                    ambientColor = Color.Black.copy(alpha = 0.08f),
                    spotColor = Color.Black.copy(alpha = 0.14f)
                )
                .clip(RoundedCornerShape(29.dp))
                .background(Color.White.copy(alpha = 0.90f))
                .border(0.8.dp, Color.White.copy(alpha = 0.96f), RoundedCornerShape(29.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 5.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val tabs = listOf(
                    Triple(0, "账本", "tab_record.webp"),
                    Triple(1, "资产", "tab_asset.webp"),
                    Triple(2, "统计", "tab_statistics.webp")
                )
                tabs.forEach { (index, label, assetFile) ->
                    val isSelected = selectedTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(25.dp))
                            .background(if (isSelected) Color(0xFFE9E9EC) else Color.Transparent)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSelectTab(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ICostAssetImage(
                                assetFile = assetFile,
                                tint = if (isSelected) ICostBlueAccent else ICostTextPrimary,
                                modifier = Modifier.size(21.dp)
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

        Box(
            modifier = Modifier
                .size(58.dp)
                .shadow(
                    elevation = 10.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.08f),
                    spotColor = Color.Black.copy(alpha = 0.14f)
                )
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.90f))
                .border(0.8.dp, Color.White.copy(alpha = 0.96f), CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onPlusClick
                ),
            contentAlignment = Alignment.Center
        ) {
            ICostAssetImage(
                assetFile = "tab_add.webp",
                tint = ICostTextPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

private data class SettingRowDef(
    val iconAsset: String,
    val title: String,
    val trailingText: String = "",
    val onClick: () -> Unit = {}
)

/**
 * 1:1 iCost 全屏「设置」页（对齐 09_settings_full.png）
 */
@Composable
private fun ICostSettingsFullScreen(
    currentLedger: String,
    monthlyBudgetCents: Long,
    onBack: () -> Unit,
    onOpenSmartBookkeeping: () -> Unit,
    onOpenBudgetDialog: () -> Unit,
    onOpenAiModelConfig: () -> Unit,
    onOpenAiSandbox: () -> Unit
) {
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ICostBgGray)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        ICostCircleIconButton(onClick = onBack, size = 42.dp) {
            ICostAssetImage(
                assetFile = "record_back_icon.webp",
                tint = ICostTextPrimary,
                modifier = Modifier.size(16.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "设置",
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            color = ICostTextPrimary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            // 用户头像与 VIP 状态卡片
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ICostCardWhite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ICostAvatarButton(onClick = {}, size = 46.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "MoJi 默记",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostTextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = ICostTextMuted
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = ICostDivider
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenSmartBookkeeping() },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ICostAssetImage(
                                assetFile = "setting_vip.webp",
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "已解锁完整功能",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFD97706),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "终身会员",
                                fontSize = 13.sp,
                                color = ICostTextSecondary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = ICostTextMuted
                            )
                        }
                    }
                }
            }

            // 第一组：多账本、智能记账、云备份
            item {
                SettingsGroupCard(
                    rows = listOf(
                        SettingRowDef("setting_multi_book.webp", "多账本", currentLedger),
                        SettingRowDef("setting_auto.webp", "智能记账", "去开启", onClick = onOpenSmartBookkeeping),
                        SettingRowDef("setting_backup.webp", "AI 模型与权限中心", "配置", onClick = onOpenAiModelConfig)
                    )
                )
            }

            // 第二组：分类管理、标签管理、预算管理、周期记账、分期管理、存钱计划、报销管理、退款管理、货币和汇率、记账模板、资产设置、统计设置、账单图片
            item {
                SettingsGroupCard(
                    rows = listOf(
                        SettingRowDef("setting_category.webp", "分类管理"),
                        SettingRowDef("setting_tag.webp", "标签管理"),
                        SettingRowDef(
                            "setting_budget.webp",
                            "预算管理",
                            formatMoneyCents(monthlyBudgetCents),
                            onClick = onOpenBudgetDialog
                        ),
                        SettingRowDef("setting_period.webp", "周期记账"),
                        SettingRowDef("setting_installment.webp", "分期管理"),
                        SettingRowDef("setting_save_money.webp", "存钱计划"),
                        SettingRowDef("setting_reimbursement.webp", "报销管理"),
                        SettingRowDef("setting_refund.webp", "退款管理"),
                        SettingRowDef("setting_currency.webp", "货币和汇率", "人民币 (CNY)"),
                        SettingRowDef("setting_template.webp", "记账模板"),
                        SettingRowDef("setting_asset.webp", "资产设置"),
                        SettingRowDef("setting_statistics.webp", "统计设置"),
                        SettingRowDef("setting_bill_image.webp", "AI 静默识账测试沙盒", "立即测试", onClick = onOpenAiSandbox)
                    )
                )
            }

            // 第三组：账单导入、账单导出、密码保护、定时提醒、外观显示、个性化
            item {
                SettingsGroupCard(
                    rows = listOf(
                        SettingRowDef("setting_import.webp", "账单导入"),
                        SettingRowDef("setting_export.webp", "账单导出"),
                        SettingRowDef("setting_password.webp", "密码保护"),
                        SettingRowDef("setting_time.webp", "定时提醒"),
                        SettingRowDef("setting_appearance.webp", "外观显示"),
                        SettingRowDef("setting_personalization.webp", "个性化")
                    )
                )
            }
        }
    }
}

@Composable
private fun SettingsGroupCard(
    rows: List<SettingRowDef>
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ICostCardWhite,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            rows.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { row.onClick() }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ICostAssetImage(
                        assetFile = row.iconAsset,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = row.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = ICostTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    if (row.trailingText.isNotBlank()) {
                        Text(
                            text = row.trailingText,
                            fontSize = 13.sp,
                            color = ICostTextSecondary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = ICostTextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (index < rows.lastIndex) {
                    HorizontalDivider(color = ICostDivider)
                }
            }
        }
    }
}

/**
 * 1:1 iCost 全屏「智能记账」页面（对齐 09_smart_bookkeeping.png）
 */
@Composable
private fun ICostSmartBookkeepingFullScreen(
    aiConfig: AiConfig,
    onUpdateAiConfig: (AiConfig) -> Unit,
    onOpenAiModelConfig: () -> Unit,
    onOpenAiSandbox: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val isNotifGranted = isNotificationListenerEnabled(context)
    val isAccessGranted = isAccessibilityServiceEnabled(context)

    var autoConfirm by remember { mutableStateOf(true) }
    var useDefaultLedger by remember { mutableStateOf(true) }
    var autoMatchCategory by remember { mutableStateOf(true) }
    var autoMatchWallet by remember { mutableStateOf(true) }
    var mergeRuleEnabled by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ICostBgGray)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        ICostCircleIconButton(onClick = onBack, size = 42.dp) {
            ICostAssetImage(
                assetFile = "record_back_icon.webp",
                tint = ICostTextPrimary,
                modifier = Modifier.size(16.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "智能记账",
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            color = ICostTextPrimary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            // 权限与开关卡片
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ICostCardWhite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        SmartSwitchRow(
                            title = "无障碍自动识账",
                            subtitle = if (isAccessGranted) "无障碍支付页监听运行中" else "开启无障碍权限以实时识别微信/支付宝账单",
                            checked = aiConfig.enableAccessibilityCapture,
                            onCheckedChange = {
                                onUpdateAiConfig(aiConfig.copy(enableAccessibilityCapture = it))
                                if (it && !isAccessGranted) {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            }
                        )
                        HorizontalDivider(color = ICostDivider)
                        SmartSwitchRow(
                            title = "通知栏静默记账",
                            subtitle = if (isNotifGranted) "通知监听服务运行中" else "开启通知使用权以捕获银行及支付通知",
                            checked = aiConfig.enableNotificationCapture,
                            onCheckedChange = {
                                onUpdateAiConfig(aiConfig.copy(enableNotificationCapture = it))
                                if (it && !isNotifGranted) {
                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                }
                            }
                        )
                        HorizontalDivider(color = ICostDivider)
                        SmartArrowRow(
                            title = "保活设置",
                            trailing = "已优化",
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        )
                    }
                }
            }

            // 记账自动化选项卡片
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ICostCardWhite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        SmartSwitchRow(
                            title = "自动确认入账",
                            subtitle = "开启后静默入账并推送通知栏提醒（支持一键原子撤销）",
                            checked = autoConfirm,
                            onCheckedChange = { autoConfirm = it }
                        )
                        HorizontalDivider(color = ICostDivider)
                        SmartSwitchRow(
                            title = "使用默认账本",
                            subtitle = null,
                            checked = useDefaultLedger,
                            onCheckedChange = { useDefaultLedger = it }
                        )
                        HorizontalDivider(color = ICostDivider)
                        SmartSwitchRow(
                            title = "自动匹配分类",
                            subtitle = "根据交易商户或商品信息，自动匹配对应分类",
                            checked = autoMatchCategory,
                            onCheckedChange = { autoMatchCategory = it }
                        )
                        HorizontalDivider(color = ICostDivider)
                        SmartSwitchRow(
                            title = "自动匹配账户",
                            subtitle = "根据支付方式与银行卡尾号，自动匹配对应资产或信用卡",
                            checked = autoMatchWallet,
                            onCheckedChange = { autoMatchWallet = it }
                        )
                        HorizontalDivider(color = ICostDivider)
                        SmartSwitchRow(
                            title = "1.1秒滑动窗口防重合并",
                            subtitle = "自动将同一笔交易的页面文本与银行扣款通知合并",
                            checked = mergeRuleEnabled,
                            onCheckedChange = { mergeRuleEnabled = it }
                        )
                        HorizontalDivider(color = ICostDivider)
                        SmartArrowRow(
                            title = "AI 大模型接口配置",
                            trailing = aiConfig.modelName,
                            onClick = onOpenAiModelConfig
                        )
                        HorizontalDivider(color = ICostDivider)
                        SmartArrowRow(
                            title = "AI 静默识账测试沙盒",
                            trailing = "立即模拟",
                            onClick = onOpenAiSandbox
                        )
                    }
                }
            }

            // 已支持的场景列表（对齐 09_smart_bookkeeping.png）
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "已支持的场景",
                        fontSize = 13.sp,
                        color = ICostTextSecondary,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = ICostCardWhite,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val scenes = listOf(
                            "微信支付与账单详情页",
                            "微信红包详情页",
                            "微信转账详情页",
                            "支付宝支付与账单详情页",
                            "云闪付交易详情页",
                            "京东订单详情页",
                            "拼多多订单详情页",
                            "美团账单详情页",
                            "招商银行 / 掌上生活扣款与还款通知"
                        )
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            scenes.forEachIndexed { idx, scene ->
                                SmartArrowRow(
                                    title = scene,
                                    trailing = "已启用",
                                    onClick = onOpenAiSandbox
                                )
                                if (idx < scenes.lastIndex) {
                                    HorizontalDivider(color = ICostDivider)
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
private fun SmartSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ICostTextPrimary)
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = subtitle, fontSize = 12.sp, color = ICostTextSecondary)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = ICostBlueAccent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFE0E0E5)
            )
        )
    }
}

@Composable
private fun SmartArrowRow(
    title: String,
    trailing: String = "",
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = ICostTextPrimary,
            modifier = Modifier.weight(1f)
        )
        if (trailing.isNotBlank()) {
            Text(text = trailing, fontSize = 13.sp, color = ICostTextSecondary)
            Spacer(modifier = Modifier.width(4.dp))
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = ICostTextMuted,
            modifier = Modifier.size(18.dp)
        )
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
