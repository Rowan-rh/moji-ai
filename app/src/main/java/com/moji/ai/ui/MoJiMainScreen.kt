package com.moji.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.ai.AiConfig
import com.moji.ai.ai.AiProviderType
import com.moji.ai.ai.MerchantMemoryEntry
import com.moji.ai.ai.SmartConditionRule
import com.moji.ai.data.backup.BackupAndCsvTransferEngine
import com.moji.ai.data.backup.CsvParsePreviewResult
import com.moji.ai.data.backup.WebDavConfig
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.TransactionType
import com.moji.ai.service.CaptureEventOutcome
import com.moji.ai.service.CaptureObservabilityLogEntry
import com.moji.ai.service.PaymentAccessibilityService
import com.moji.ai.service.RomKeepAliveGuideHelper
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
import com.moji.ai.ui.theme.ICostSmoothBottomSheet
import com.moji.ai.ui.theme.ICostTextMuted
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.formatMoneyCents
import com.moji.ai.ui.theme.iCostHorizontalSwipeTrigger
import com.moji.ai.ui.theme.iCostPressScale
import com.moji.ai.ui.theme.preloadAllICostAssets
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoJiMainScreen(viewModel: MainViewModel) {
    val appContext = LocalContext.current
    LaunchedEffect(Unit) {
        preloadAllICostAssets(appContext)
    }
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
    val templates by viewModel.templates.collectAsState()
    val savingPlans by viewModel.savingPlans.collectAsState()
    val installmentPlans by viewModel.installmentPlans.collectAsState()
    val merchantMemories by viewModel.merchantMemories.collectAsState()
    val smartRules by viewModel.smartRules.collectAsState()
    val observabilityLogs by viewModel.observabilityLogs.collectAsState()
    val webDavConfig by viewModel.webDavConfig.collectAsState()

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
    var showBackupTransferSheet by remember { mutableStateOf(false) }
    var backupInitialTab by remember { mutableIntStateOf(0) } // 0: 导入CSV, 1: 导出CSV, 2: 全量备份恢复, 3: WebDAV云同步
    var showKeepAliveGuideSheet by remember { mutableStateOf(false) }
    var showBatchReimburseSheet by remember { mutableStateOf(false) }

    // 缓存最后一次打开的全屏日历与账单详情数据，确保退出动画期间内容不闪空
    var cachedCalendarMonthTxs by remember { mutableStateOf<List<TransactionEntity>>(emptyList()) }
    if (calendarMonthTxs != null) {
        cachedCalendarMonthTxs = calendarMonthTxs!!
    }
    val activeTx = inspectingTx?.let { target -> transactions.firstOrNull { it.id == target.id } ?: target }
    var cachedInspectingTx by remember { mutableStateOf<TransactionEntity?>(null) }
    if (activeTx != null) {
        cachedInspectingTx = activeTx
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ICostBgGray)
            .statusBarsPadding()
    ) {
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                val direction = if (targetState >= initialState) 1 else -1
                (slideInHorizontally(
                    initialOffsetX = { fullWidth -> direction * (fullWidth / 7) },
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(durationMillis = 220))) togetherWith
                    (slideOutHorizontally(
                        targetOffsetX = { fullWidth -> -direction * (fullWidth / 7) },
                        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
                    ) + fadeOut(animationSpec = tween(durationMillis = 160)))
            },
            label = "MainTabTransition"
        ) { tabIndex ->
            when (tabIndex) {
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
                    onClickTransaction = { tx -> inspectingTx = tx },
                    templates = templates,
                    onApplyTemplate = { tpl -> viewModel.applyQuickTemplateNow(tpl) },
                    onEditTransaction = { toEdit ->
                        editingTx = toEdit
                        showCalculatorSheet = true
                    },
                    onDuplicateTransaction = { tx -> viewModel.duplicateTransactionNow(tx) },
                    onToggleReimburse = { tx -> viewModel.toggleOrSettleReimbursement(tx) },
                    onQuickRefund = { tx ->
                        val remainYuan = ((tx.amountCnyCents - tx.refundedAmountCents).coerceAtLeast(0L)) / 100.0
                        if (remainYuan > 0.0) {
                            viewModel.refundExpense(tx.id, remainYuan, tx.sourceWalletId, "侧滑快捷全额退款")
                        }
                    },
                    onDeleteTransaction = { txId -> viewModel.deleteTransaction(txId) }
                )

                1 -> ICostAssetsScreen(
                    wallets = wallets,
                    transactions = transactions,
                    recurringRules = recurringRules,
                    hideAmounts = hideAssetAmounts,
                    onToggleHideAmounts = { viewModel.toggleHideAssetAmounts() },
                    onSaveWallet = { viewModel.saveOrUpdateWallet(it) },
                    onDeleteWallet = { viewModel.deleteWallet(it) },
                    onReconcileWallet = { walletId, actualYuan, recordTx, note ->
                        viewModel.reconcileWalletBalance(walletId, actualYuan, recordTx, note)
                    },
                    onQuickActionWithWallet = { type, srcId, dstId ->
                        editingTx = null
                        calcInitialType = type
                        calcInitialSourceWalletId = srcId
                        calcInitialTargetWalletId = dstId
                        showCalculatorSheet = true
                    },
                    onSelectTransaction = { tx -> inspectingTx = tx },
                    onTriggerRuleNow = { viewModel.triggerRecurringRuleNow(it) },
                    onDeleteRecurringRule = { viewModel.deleteRecurringRule(it) },
                    savingPlans = savingPlans,
                    installmentPlans = installmentPlans,
                    onDepositSavingPlan = { planId, yuan -> viewModel.depositToSavingPlan(planId, yuan) },
                    onPayInstallmentPeriod = { inst -> viewModel.payNextInstallmentPeriod(inst) },
                    onOpenSettingsPage = { showSettingsPage = true },
                    onSubpageActiveChange = { isAssetSubpageOpen = it }
                )

                else -> ICostStatisticsScreen(
                    transactions = transactions,
                    wallets = wallets,
                    initialYear = selectedYear,
                    initialMonth = selectedMonth,
                    onOpenAiSandbox = { showAiSandboxSheet = true },
                    onOpenSettingsPage = { showSettingsPage = true }
                )
            }
        }

        // 1:1 iCost 底部半透明毛玻璃悬浮胶囊导航栏 + 右下角独立白色大圆 "+" 记账按钮（进出全屏二级页时平滑升降隐藏）
        AnimatedVisibility(
            visible = !hasFullScreenOverlay,
            enter = slideInVertically(
                initialOffsetY = { it * 2 },
                animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
            ) + fadeIn(tween(200)),
            exit = slideOutVertically(
                targetOffsetY = { it * 2 },
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) + fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            ICostFloatingBottomBar(
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
                onPlusClick = {
                    editingTx = null
                    calcInitialType = TransactionType.EXPENSE
                    calcInitialSourceWalletId = null
                    calcInitialTargetWalletId = null
                    showCalculatorSheet = true
                }
            )
        }

        // 全屏收支日历页面（06_calendar_full.png）—— 120Hz 高响应弹簧上浮转场
        AnimatedVisibility(
            visible = calendarMonthTxs != null,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.94f, stiffness = 580f)
            ) + fadeIn(tween(150)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.96f, stiffness = 680f)
            ) + fadeOut(tween(130))
        ) {
            MonthlyCalendarSheet(
                year = selectedYear,
                month = selectedMonth,
                monthTransactions = cachedCalendarMonthTxs,
                onDismiss = { calendarMonthTxs = null },
                onSelectTransaction = { tx ->
                    inspectingTx = tx
                }
            )
        }

        // 全屏账单详情页面（05_detail_full.png）—— 丝滑侧滑推入转场
        AnimatedVisibility(
            visible = activeTx != null,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(280, easing = FastOutSlowInEasing)
            ) + fadeIn(tween(200)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(240, easing = FastOutSlowInEasing)
            ) + fadeOut(tween(180))
        ) {
            cachedInspectingTx?.let { detailTx ->
                TransactionDetailSheet(
                    tx = detailTx,
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
                    },
                    onSaveAsTemplate = { tplTx ->
                        viewModel.saveTemplateFromTransaction(tplTx)
                    },
                    onSplitTransaction = { expId, catA, yuanA, catB, noteB ->
                        viewModel.splitExpenseTransaction(expId, catA, yuanA, catB, noteB)
                    }
                )
            }
        }

        // 全屏「设置」页面（09_settings_full.png）—— 丝滑侧滑推入转场
        AnimatedVisibility(
            visible = showSettingsPage,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(280, easing = FastOutSlowInEasing)
            ) + fadeIn(tween(200)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(240, easing = FastOutSlowInEasing)
            ) + fadeOut(tween(180))
        ) {
            ICostSettingsFullScreen(
                currentLedger = currentLedger,
                monthlyBudgetCents = monthlyBudgetCents,
                onBack = { showSettingsPage = false },
                onOpenSmartBookkeeping = { showSmartBookkeepingPage = true },
                onOpenBudgetDialog = { showBudgetDialog = true },
                onOpenAiModelConfig = { showAiSettingsSheet = true },
                onOpenAiSandbox = { showAiSandboxSheet = true },
                onOpenCsvImport = {
                    backupInitialTab = 0
                    showBackupTransferSheet = true
                },
                onOpenCsvExport = {
                    backupInitialTab = 1
                    showBackupTransferSheet = true
                },
                onOpenBackupRestore = {
                    backupInitialTab = 2
                    showBackupTransferSheet = true
                },
                onOpenWebDavSync = {
                    backupInitialTab = 3
                    showBackupTransferSheet = true
                },
                onOpenBatchReimburse = {
                    showBatchReimburseSheet = true
                }
            )
        }

        // 全屏「智能记账」页面（09_smart_bookkeeping.png）—— 二级侧滑推入转场
        AnimatedVisibility(
            visible = showSmartBookkeepingPage,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(280, easing = FastOutSlowInEasing)
            ) + fadeIn(tween(200)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(240, easing = FastOutSlowInEasing)
            ) + fadeOut(tween(180))
        ) {
            ICostSmartBookkeepingFullScreen(
                aiConfig = aiConfig,
                onUpdateAiConfig = { viewModel.updateAiConfig(it) },
                onOpenAiModelConfig = { showAiSettingsSheet = true },
                onOpenAiSandbox = { showAiSandboxSheet = true },
                onOpenKeepAliveGuide = { showKeepAliveGuideSheet = true },
                smartRules = smartRules,
                onToggleSmartRule = { id, enabled -> viewModel.toggleSmartRule(id, enabled) },
                onAddSmartRule = { rule -> viewModel.upsertSmartRule(rule) },
                onDeleteSmartRule = { id -> viewModel.deleteSmartRule(id) },
                merchantMemories = merchantMemories,
                onRemoveMerchantMemory = { viewModel.deleteMerchantMemory(it) },
                observabilityLogs = observabilityLogs,
                onReplayLog = { entry -> viewModel.simulateCapturedEvent(entry.rawText, entry.packageName) },
                onClearLogs = { viewModel.clearObservabilityLogs() },
                onBack = { showSmartBookkeepingPage = false }
            )
        }

        // 1:1 iCost 分类+计算器记账面板（04_add_record_sheet.png，后台常驻 keepWarm 预热，点击「+」0ms 首帧 120Hz 上滑）
        if (wallets.isNotEmpty()) {
            ICostCalculatorSheet(
                wallets = wallets,
                currentLedger = currentLedger,
                ledgers = ledgers,
                onSelectLedger = { viewModel.selectLedger(it) },
                visible = showCalculatorSheet,
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
        // AI 静默识账沙盒测试面板
        if (showAiSandboxSheet) {
            ICostSmoothBottomSheet(
                onDismissRequest = { showAiSandboxSheet = false },
                containerColor = ICostCardWhite
            ) {
                AiSandboxSheetContent(
                    isParsing = isParsing,
                    statusMessage = sandboxStatus,
                    observabilityLogs = observabilityLogs,
                    merchantMemories = merchantMemories,
                    onRunSimulation = { text, pkg -> viewModel.simulateCapturedEvent(text, pkg) },
                    onClearLogs = { viewModel.clearObservabilityLogs() }
                )
            }
        }

        // 账单 CSV 导入导出与全量快照备份恢复面板
        if (showBackupTransferSheet) {
            ICostSmoothBottomSheet(
                onDismissRequest = { showBackupTransferSheet = false },
                containerColor = ICostCardWhite
            ) {
                BackupAndCsvTransferSheet(
                    initialTab = backupInitialTab,
                    statusMessage = sandboxStatus,
                    webDavConfig = webDavConfig,
                    onPreviewCsv = { csv -> viewModel.previewCsvImport(csv) },
                    onCommitCsvImport = { csv, applyBalance, onDone ->
                        viewModel.commitCsvImport(csv, applyBalance, onDone)
                    },
                    onExportCsv = { viewModel.generateCsvExportString() },
                    onExportFullBackup = { viewModel.generateFullBackupJson() },
                    onRestoreFullBackup = { json, onDone ->
                        viewModel.restoreFromBackupJson(json, onDone)
                    },
                    onUploadWebDav = { cfg, onDone ->
                        viewModel.uploadBackupToWebDav(cfg, onDone)
                    },
                    onRestoreWebDav = { cfg, onDone ->
                        viewModel.restoreBackupFromWebDav(cfg, onDone)
                    }
                )
            }
        }

        // 国产 ROM 保活与 Android 13+ 受限设置一站式诊断向导面板
        if (showKeepAliveGuideSheet) {
            ICostSmoothBottomSheet(
                onDismissRequest = { showKeepAliveGuideSheet = false },
                containerColor = ICostCardWhite
            ) {
                RomKeepAliveGuideSheet()
            }
        }

        // 批量报销核销中心面板
        if (showBatchReimburseSheet) {
            ICostSmoothBottomSheet(
                onDismissRequest = { showBatchReimburseSheet = false },
                containerColor = ICostCardWhite
            ) {
                BatchReimbursementSheet(
                    transactions = transactions,
                    wallets = wallets,
                    onBatchSettle = { ids, recvWalletId, onDone ->
                        viewModel.batchSettleReimbursements(ids, recvWalletId, onDone)
                    }
                )
            }
        }

        // AI 大模型与系统权限配置面板
        if (showAiSettingsSheet) {
            ICostSmoothBottomSheet(
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
                .iCostHorizontalSwipeTrigger(
                    thresholdDp = 36.dp,
                    onSwipeRight = { onSelectTab((selectedTab + 1) % 3) },
                    onSwipeLeft = { onSelectTab((selectedTab + 2) % 3) }
                )
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
                    val pillColor by animateColorAsState(
                        targetValue = if (isSelected) Color(0xFFE9E9EC) else Color.Transparent,
                        animationSpec = tween(durationMillis = 220),
                        label = "BottomTabPillColor"
                    )
                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) ICostBlueAccent else ICostTextPrimary,
                        animationSpec = tween(durationMillis = 220),
                        label = "BottomTabContentColor"
                    )
                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.08f else 1.0f,
                        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
                        label = "BottomTabIconScale"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(25.dp))
                            .background(pillColor)
                            .iCostPressScale(pressedScale = 0.92f) { onSelectTab(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ICostAssetImage(
                                assetFile = assetFile,
                                tint = contentColor,
                                modifier = Modifier
                                    .size(21.dp)
                                    .graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                    }
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = contentColor
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .size(58.dp)
                .iCostPressScale(pressedScale = 0.88f, onClick = onPlusClick)
                .shadow(
                    elevation = 10.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.08f),
                    spotColor = Color.Black.copy(alpha = 0.14f)
                )
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.90f))
                .border(0.8.dp, Color.White.copy(alpha = 0.96f), CircleShape),
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
    onOpenAiSandbox: () -> Unit,
    onOpenCsvImport: () -> Unit = {},
    onOpenCsvExport: () -> Unit = {},
    onOpenBackupRestore: () -> Unit = {},
    onOpenWebDavSync: () -> Unit = {},
    onOpenBatchReimburse: () -> Unit = {}
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
                        SettingRowDef("setting_backup.webp", "全量快照与 WebDAV 云同步", "备份/恢复", onClick = onOpenWebDavSync)
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
                        SettingRowDef("setting_reimbursement.webp", "报销管理 (批量核销)", "一键结算", onClick = onOpenBatchReimburse),
                        SettingRowDef("setting_refund.webp", "退款管理"),
                        SettingRowDef("setting_currency.webp", "货币和汇率", "人民币 (CNY)"),
                        SettingRowDef("setting_template.webp", "记账模板"),
                        SettingRowDef("setting_asset.webp", "AI 模型与权限中心", "配置", onClick = onOpenAiModelConfig),
                        SettingRowDef("setting_statistics.webp", "统计设置"),
                        SettingRowDef("setting_bill_image.webp", "AI 静默识账测试沙盒", "立即测试", onClick = onOpenAiSandbox)
                    )
                )
            }

            // 第三组：账单导入、账单导出、密码保护、定时提醒、外观显示、个性化
            item {
                SettingsGroupCard(
                    rows = listOf(
                        SettingRowDef("setting_import.webp", "账单导入", "微信/支付宝/钱迹 CSV", onClick = onOpenCsvImport),
                        SettingRowDef("setting_export.webp", "账单导出", "标准 CSV 报表", onClick = onOpenCsvExport),
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
    onOpenKeepAliveGuide: () -> Unit = {},
    smartRules: List<SmartConditionRule> = emptyList(),
    onToggleSmartRule: (Long, Boolean) -> Unit = { _, _ -> },
    onAddSmartRule: (SmartConditionRule) -> Unit = {},
    onDeleteSmartRule: (Long) -> Unit = {},
    merchantMemories: List<MerchantMemoryEntry> = emptyList(),
    onRemoveMerchantMemory: (String) -> Unit = {},
    observabilityLogs: List<CaptureObservabilityLogEntry> = emptyList(),
    onReplayLog: (CaptureObservabilityLogEntry) -> Unit = {},
    onClearLogs: () -> Unit = {},
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
    var showAddSmartRuleDialog by remember { mutableStateOf(false) }

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
                            title = "国产 ROM 保活与 Android 13+ 受限设置诊断向导",
                            trailing = "一键诊断",
                            onClick = onOpenKeepAliveGuide
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

            // 可视化条件规则引擎 When -> Then（对标 pennywiseai-tracker Smart Rules Engine）
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "可视化条件规则引擎 When ➔ Then (${smartRules.count { it.enabled }}/${smartRules.size} 启用)",
                            fontSize = 13.sp,
                            color = ICostTextSecondary
                        )
                        Text(
                            text = "+ 新增规则",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = ICostBlueAccent,
                            modifier = Modifier.clickable { showAddSmartRuleDialog = true }
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = ICostCardWhite,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            smartRules.forEachIndexed { idx, rule ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(
                                            text = rule.name,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ICostTextPrimary
                                        )
                                        Text(
                                            text = "当：${rule.conditionSummary()}",
                                            fontSize = 11.sp,
                                            color = ICostTextSecondary
                                        )
                                        Text(
                                            text = "则：${rule.actionSummary()}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (rule.interceptIgnore) ICostCoralRed else ICostBlueAccent
                                        )
                                    }
                                    Text(
                                        text = "删除",
                                        fontSize = 11.sp,
                                        color = ICostCoralRed,
                                        modifier = Modifier
                                            .clickable { onDeleteSmartRule(rule.id) }
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                    Switch(
                                        checked = rule.enabled,
                                        onCheckedChange = { onToggleSmartRule(rule.id, it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = ICostBlueAccent
                                        )
                                    )
                                }
                                if (idx < smartRules.lastIndex) {
                                    HorizontalDivider(color = ICostDivider)
                                }
                            }
                        }
                    }
                }
            }

            // 商户记忆自学习引擎（对标 Cashew 关联修正 + AutoAccounting AI 学习注入）
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "商户记忆自学习规则库（已记住 ${merchantMemories.size} 个商户，保存/编辑账单时自动更新）",
                        fontSize = 13.sp,
                        color = ICostTextSecondary,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = ICostCardWhite,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            if (merchantMemories.isEmpty()) {
                                Text(
                                    text = "暂无记忆规则，手动新增或修正任意账单分类后将自动记住该商户",
                                    fontSize = 13.sp,
                                    color = ICostTextSecondary,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            } else {
                                merchantMemories.take(10).forEachIndexed { idx, entry ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${entry.merchantKeyword}  ➔  ${entry.category}",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = ICostTextPrimary
                                            )
                                            Text(
                                                text = "累计命中/修正 ${entry.hitCount} 次 · 优先于离线通用词库与大模型",
                                                fontSize = 11.sp,
                                                color = ICostTextSecondary
                                            )
                                        }
                                        Text(
                                            text = "移除",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ICostCoralRed,
                                            modifier = Modifier
                                                .clickable { onRemoveMerchantMemory(entry.merchantKeyword) }
                                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                        )
                                    }
                                    if (idx < merchantMemories.take(10).lastIndex) {
                                        HorizontalDivider(color = ICostDivider)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 30 秒跨通道事后证据融合 + 采集可观测性日志面板
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "采集可观测日志 & 30s 跨通道证据融合 (${observabilityLogs.size})",
                            fontSize = 13.sp,
                            color = ICostTextSecondary
                        )
                        if (observabilityLogs.isNotEmpty()) {
                            Text(
                                text = "清空日志",
                                fontSize = 12.sp,
                                color = ICostBlueAccent,
                                modifier = Modifier.clickable { onClearLogs() }
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = ICostCardWhite,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            if (observabilityLogs.isEmpty()) {
                                Text(
                                    text = "暂无实时采集事件。可在「AI 静默识账测试沙盒」中模拟通知或跨通道补全事件。",
                                    fontSize = 13.sp,
                                    color = ICostTextSecondary,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            } else {
                                observabilityLogs.take(8).forEachIndexed { idx, log ->
                                    val badgeColor = when (log.outcome) {
                                        CaptureEventOutcome.RECORDED -> ICostGreenIncome
                                        CaptureEventOutcome.FUSED_PATCH -> ICostBlueAccent
                                        CaptureEventOutcome.DEDUP_BLOCKED -> ICostAmberGold
                                        CaptureEventOutcome.NOISE_IGNORED -> ICostTextSecondary
                                    }
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "[${log.outcome.badgeLabel}] ${log.captureSource}",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = badgeColor
                                            )
                                            Text(
                                                text = "一键重放",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = ICostBlueAccent,
                                                modifier = Modifier.clickable { onReplayLog(log) }
                                            )
                                        }
                                        Text(
                                            text = log.detailSummary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ICostTextPrimary
                                        )
                                        Text(
                                            text = log.rawText.replace("\n", " ").take(80),
                                            fontSize = 11.sp,
                                            color = ICostTextSecondary
                                        )
                                    }
                                    if (idx < observabilityLogs.take(8).lastIndex) {
                                        HorizontalDivider(color = ICostDivider)
                                    }
                                }
                            }
                        }
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

    if (showAddSmartRuleDialog) {
        var ruleName by remember { mutableStateOf("") }
        var keywordStr by remember { mutableStateOf("") }
        var minYuanStr by remember { mutableStateOf("") }
        var maxYuanStr by remember { mutableStateOf("") }
        var targetCat by remember { mutableStateOf("餐饮-三餐") }
        var markReimburse by remember { mutableStateOf(false) }
        var tagStr by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddSmartRuleDialog = false },
            containerColor = ICostCardWhite,
            title = { Text("新增条件规则 (When ➔ Then)", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ruleName,
                        onValueChange = { ruleName = it },
                        label = { Text("规则名称 (如：公司园区晚餐报销)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = keywordStr,
                        onValueChange = { keywordStr = it },
                        label = { Text("当包含关键词 (逗号分隔，如：美团,饿了么)") },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = minYuanStr,
                            onValueChange = { minYuanStr = it },
                            label = { Text("最低金额(选填)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = maxYuanStr,
                            onValueChange = { maxYuanStr = it },
                            label = { Text("最高金额(选填)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    OutlinedTextField(
                        value = targetCat,
                        onValueChange = { targetCat = it },
                        label = { Text("则自动归入分类 (如：餐饮-三餐)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = tagStr,
                        onValueChange = { tagStr = it },
                        label = { Text("自动附加标签 (选填)") },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("自动标记为「待报销」", fontSize = 13.sp, color = ICostTextPrimary)
                        Switch(checked = markReimburse, onCheckedChange = { markReimburse = it })
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = ruleName.isNotBlank() && (keywordStr.isNotBlank() || minYuanStr.isNotBlank() || maxYuanStr.isNotBlank()),
                    onClick = {
                        onAddSmartRule(
                            SmartConditionRule(
                                name = ruleName.trim(),
                                enabled = true,
                                keywordContains = keywordStr.trim(),
                                minAmountYuan = minYuanStr.toDoubleOrNull(),
                                maxAmountYuan = maxYuanStr.toDoubleOrNull(),
                                targetCategory = targetCat.trim(),
                                markReimbursable = markReimburse,
                                appendTag = tagStr.trim()
                            )
                        )
                        showAddSmartRuleDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent)
                ) {
                    Text("保存规则")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddSmartRuleDialog = false }) { Text("取消") }
            }
        )
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
    observabilityLogs: List<CaptureObservabilityLogEntry> = emptyList(),
    merchantMemories: List<MerchantMemoryEntry> = emptyList(),
    onRunSimulation: (String, String) -> Unit,
    onClearLogs: () -> Unit = {}
) {
    var customInput by remember {
        mutableStateOf("【支付宝】付款成功：向商户【盒马】付款 32.80元，付款方式：账户余额")
    }

    val presetScenarios = listOf(
        "盒马买菜 ¥32.80" to "【支付宝】付款成功：向商户【盒马】付款 32.80元，付款方式：账户余额",
        "排除词分类: 美团外卖 ¥26.5" to "【微信支付】美团外卖 支付成功 26.50元，商品：隆江猪脚饭外卖，零钱支付",
        "融合步骤1: 仅招行卡扣款 ¥88" to "【招商银行】您尾号3354的信用卡消费人民币88.00元",
        "融合步骤2: 30s内补全山姆商户" to "【微信支付】向商户【山姆会员商店】付款88.00元",
        "京东购物(招行信用卡)" to "【招商银行】您尾号3354的招商银行信用卡消费人民币69.00元，商户：京东商城",
        "唐久零食退款 ¥5.00" to "【微信支付】退款入账通知：商户【唐久(环普产业园店)】已退回 5.00元 至您的账户余额",
        "招行储蓄卡还信用卡" to "【招商银行】还款成功：您已使用招商银行储蓄卡(8848)成功偿还招商银行信用卡(3354)账单 500.00元"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Text("AI 静默识账测试沙盒", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = ICostTextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "支持测试：排除词加权分类（如美团外卖自动排除旅游/购物归入餐饮-三餐）、商户记忆优先匹配（已记住 ${merchantMemories.size} 个商户）、30 秒跨通道事后证据融合补全：",
                fontSize = 12.sp,
                color = ICostTextSecondary
            )
        }

        item {
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
        }

        item {
            OutlinedTextField(
                value = customInput,
                onValueChange = { customInput = it },
                label = { Text("模拟通知栏 / 无障碍屏幕抓取文本") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
        }

        item {
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
        }

        if (!statusMessage.isNullOrBlank()) {
            item {
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
        }

        if (observabilityLogs.isNotEmpty()) {
            item {
                Surface(
                    color = ICostBgGray,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "最近采集/融合可观测日志 (${observabilityLogs.size})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostTextPrimary
                            )
                            Text(
                                text = "清空",
                                fontSize = 11.sp,
                                color = ICostBlueAccent,
                                modifier = Modifier.clickable { onClearLogs() }
                            )
                        }
                        observabilityLogs.take(4).forEach { log ->
                            Text(
                                text = "• [${log.outcome.badgeLabel}] ${log.detailSummary}",
                                fontSize = 11.sp,
                                color = ICostTextSecondary
                            )
                        }
                    }
                }
            }
        }
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

/**
 * 账单 CSV 多源导入导出 + 全量快照备份恢复面板（对标 ezBookkeeping & AutoAccounting Schema Guard）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackupAndCsvTransferSheet(
    initialTab: Int,
    statusMessage: String?,
    webDavConfig: WebDavConfig = WebDavConfig(),
    onPreviewCsv: (String) -> CsvParsePreviewResult,
    onCommitCsvImport: (String, Boolean, (String) -> Unit) -> Unit,
    onExportCsv: () -> String,
    onExportFullBackup: () -> String,
    onRestoreFullBackup: (String, (String) -> Unit) -> Unit,
    onUploadWebDav: (WebDavConfig, (String) -> Unit) -> Unit = { _, _ -> },
    onRestoreWebDav: (WebDavConfig, (String) -> Unit) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var currentTab by remember(initialTab) { mutableIntStateOf(initialTab) }
    var csvText by remember { mutableStateOf(BackupAndCsvTransferEngine.SAMPLE_WECHAT_CSV) }
    var previewResult by remember { mutableStateOf<CsvParsePreviewResult?>(null) }
    var applyImportToWalletBalance by remember { mutableStateOf(true) }
    var exportedCsvText by remember { mutableStateOf("") }
    var backupJsonText by remember { mutableStateOf("") }
    var localBanner by remember { mutableStateOf<String?>(null) }

    var davUrl by remember(webDavConfig) { mutableStateOf(webDavConfig.serverUrl) }
    var davUser by remember(webDavConfig) { mutableStateOf(webDavConfig.username) }
    var davPassword by remember(webDavConfig) { mutableStateOf(webDavConfig.appPassword) }
    var davFileName by remember(webDavConfig) { mutableStateOf(webDavConfig.remoteFileName) }

    val clipboard = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text(
                text = "账单迁移、全量备份与 WebDAV 云同步",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ICostTextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val tabs = listOf(
                    0 to "导入 CSV",
                    1 to "导出 CSV",
                    2 to "全量快照 (.mojibackup)",
                    3 to "WebDAV 云同步"
                )
                tabs.forEach { (idx, title) ->
                    FilterChip(
                        selected = currentTab == idx,
                        onClick = {
                            currentTab = idx
                            localBanner = null
                        },
                        label = { Text(title, fontSize = 12.sp) }
                    )
                }
            }
        }

        if (currentTab == 0) {
            item {
                Text(
                    text = "自动识别「微信支付官方账单 CSV」「支付宝官方账单 CSV」「钱迹 / iCost 导出 CSV」，支持导入前预览、商户记忆匹配与重复流水自动跳过：",
                    fontSize = 12.sp,
                    color = ICostTextSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = {
                            csvText = BackupAndCsvTransferEngine.SAMPLE_WECHAT_CSV
                            previewResult = onPreviewCsv(csvText)
                        },
                        label = { Text("载入微信账单样例", fontSize = 12.sp) }
                    )
                    AssistChip(
                        onClick = {
                            csvText = BackupAndCsvTransferEngine.SAMPLE_ALIPAY_CSV
                            previewResult = onPreviewCsv(csvText)
                        },
                        label = { Text("载入支付宝账单样例", fontSize = 12.sp) }
                    )
                    AssistChip(
                        onClick = {
                            csvText = BackupAndCsvTransferEngine.SAMPLE_QIANJI_CSV
                            previewResult = onPreviewCsv(csvText)
                        },
                        label = { Text("载入钱迹/iCost样例", fontSize = 12.sp) }
                    )
                    AssistChip(
                        onClick = {
                            val clipText = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                            if (!clipText.isNullOrBlank()) {
                                csvText = clipText
                                previewResult = onPreviewCsv(csvText)
                                localBanner = "已从剪贴板粘贴并解析 CSV"
                            } else {
                                localBanner = "剪贴板为空"
                            }
                        },
                        label = { Text("从剪贴板粘贴 CSV", fontSize = 12.sp) }
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = csvText,
                    onValueChange = {
                        csvText = it
                        previewResult = null
                    },
                    label = { Text("微信 / 支付宝 / 钱迹 / iCost CSV 文本内容") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 7
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("导入流水时同步增减对应账户余额", fontSize = 13.sp, color = ICostTextPrimary)
                    Switch(
                        checked = applyImportToWalletBalance,
                        onCheckedChange = { applyImportToWalletBalance = it }
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { previewResult = onPreviewCsv(csvText) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text("1. 解析预览", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            onCommitCsvImport(csvText, applyImportToWalletBalance) { msg ->
                                localBanner = msg
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text("2. 确认导入并防重入账", fontWeight = FontWeight.Bold)
                    }
                }
            }

            previewResult?.let { preview ->
                item {
                    Surface(
                        color = ICostBgGray,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "解析结果：识别为【${preview.detectedSourceFormat}】，共 ${preview.parsedTransactions.size} 条有效账单（忽略噪声行 ${preview.skippedNonFinancialRows} 条）",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostBlueAccent
                            )
                            preview.parsedTransactions.take(5).forEach { tx ->
                                Text(
                                    text = "• [${tx.type.displayName}] ${tx.category} · ${tx.merchant} · ${formatMoneyCents(tx.amountCnyCents, tx.currency)}",
                                    fontSize = 12.sp,
                                    color = ICostTextPrimary
                                )
                            }
                        }
                    }
                }
            }
        } else if (currentTab == 1) {
            item {
                Text(
                    text = "将当前所有账单导出为兼容 iCost / 钱迹 / Excel 的标准 UTF-8 CSV 表格文本：",
                    fontSize = 12.sp,
                    color = ICostTextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val csv = onExportCsv()
                            exportedCsvText = csv
                            localBanner = "已生成 ${(csv.lines().size - 1).coerceAtLeast(0)} 条账单 CSV 报表"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text("生成 CSV 导出报表", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            if (exportedCsvText.isNotBlank()) {
                                clipboard?.setPrimaryClip(ClipData.newPlainText("MoJi_CSV", exportedCsvText))
                                localBanner = "✅ 已复制 CSV 到系统剪贴板"
                            }
                        },
                        enabled = exportedCsvText.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text("复制 CSV 到剪贴板", fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (exportedCsvText.isNotBlank()) {
                item {
                    OutlinedTextField(
                        value = exportedCsvText,
                        onValueChange = { exportedCsvText = it },
                        label = { Text("导出的标准 CSV 内容预览") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 5,
                        maxLines = 9
                    )
                }
            }
        } else if (currentTab == 2) {
            item {
                Text(
                    text = "全量备份包含：资产/负债钱包、全部历史账单、周期记账规则、商户记忆自学习库、多账本与预算设置（内置 BackupSchemaGuard 版本防崩保护）：",
                    fontSize = 12.sp,
                    color = ICostTextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val json = onExportFullBackup()
                            backupJsonText = json
                            clipboard?.setPrimaryClip(ClipData.newPlainText("MoJi_Backup", json))
                            localBanner = "✅ 已生成全量备份快照并复制到剪贴板"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text("生成全量备份 JSON", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            val clipText = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                            if (!clipText.isNullOrBlank()) {
                                backupJsonText = clipText
                                localBanner = "已从剪贴板载入备份 JSON"
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text("从剪贴板粘贴备份", fontWeight = FontWeight.Bold)
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = backupJsonText,
                    onValueChange = { backupJsonText = it },
                    label = { Text(".mojibackup 全量 JSON 快照内容") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 8
                )
            }

            item {
                Button(
                    onClick = {
                        onRestoreFullBackup(backupJsonText) { msg ->
                            localBanner = msg
                        }
                    },
                    enabled = backupJsonText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = ICostCoralRed),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text("立即从 JSON 快照恢复全量数据", fontWeight = FontWeight.Bold)
                }
            }
        } else {
            item {
                Text(
                    text = "支持坚果云 (https://dav.jianguoyun.com/dav/MoJi/)、Nextcloud、群晖 Synology、Alist 等任意标准 WebDAV 服务自托管云端备份：",
                    fontSize = 12.sp,
                    color = ICostTextSecondary
                )
            }
            item {
                OutlinedTextField(
                    value = davUrl,
                    onValueChange = { davUrl = it },
                    label = { Text("WebDAV 目录地址 (URL)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = davUser,
                    onValueChange = { davUser = it },
                    label = { Text("WebDAV 账号 / 邮箱") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = davPassword,
                    onValueChange = { davPassword = it },
                    label = { Text("WebDAV 应用专用密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = davFileName,
                    onValueChange = { davFileName = it },
                    label = { Text("远端备份文件名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            item {
                val activeCfg = WebDavConfig(
                    serverUrl = davUrl.trim(),
                    username = davUser.trim(),
                    appPassword = davPassword.trim(),
                    remoteFileName = davFileName.trim().ifBlank { "moji_latest.mojibackup" },
                    lastSyncTimeMillis = webDavConfig.lastSyncTimeMillis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            localBanner = "正在上传全量备份至 WebDAV..."
                            onUploadWebDav(activeCfg) { msg -> localBanner = msg }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text("上传备份至云端", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            localBanner = "正在从 WebDAV 拉取并恢复备份..."
                            onRestoreWebDav(activeCfg) { msg -> localBanner = msg }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text("从云端下载恢复", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        val bannerToShow = localBanner ?: statusMessage
        if (!bannerToShow.isNullOrBlank()) {
            item {
                Surface(
                    color = ICostBgGray,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = bannerToShow,
                        modifier = Modifier.padding(14.dp),
                        fontSize = 13.sp,
                        color = ICostTextPrimary
                    )
                }
            }
        }
    }
}

/**
 * 国产 ROM 后台静默运行保活与 Android 13+ 受限设置一站式诊断向导面板
 */
@Composable
private fun RomKeepAliveGuideSheet() {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    val diag = remember(refreshTick) { RomKeepAliveGuideHelper.inspectCurrentDevice(context) }
    var actionFeedback by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text(
                text = "后台保活与受限设置诊断 (${diag.passedCount}/${diag.totalCoreChecks})",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ICostTextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "当前设备：${diag.romDisplayName} · 型号 ${diag.model} (Android SDK ${diag.sdkInt})",
                fontSize = 12.sp,
                color = ICostBlueAccent,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Surface(color = ICostBgGray, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 1. 通知监听状态 + 主动重连
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (diag.isNotificationListenerEnabled) "✅ 通知监听服务已开启" else "⚠️ 通知监听服务未开启",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (diag.isNotificationListenerEnabled) ICostGreenIncome else ICostAmberGold
                            )
                            Text("若通知监听被系统断连，可点击右侧「重连服务」", fontSize = 11.sp, color = ICostTextSecondary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = {
                                val ok = RomKeepAliveGuideHelper.requestNotificationListenerRebind(context)
                                actionFeedback = if (ok) "✅ 已向系统发起 NotificationListener 重新绑定请求" else "⚠️ 请先开启通知使用权"
                                refreshTick++
                            }) {
                                Text("重连服务", fontSize = 11.sp)
                            }
                            if (!diag.isNotificationListenerEnabled) {
                                Button(
                                    onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                                    colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent)
                                ) {
                                    Text("去开启", fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = ICostDivider)

                    // 2. 无障碍服务状态 + Android 13+ 受限设置一键解除跳转
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (diag.isAccessibilityEnabled) "✅ 无障碍支付页监听已开启" else "⚠️ 无障碍支付页监听未开启",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (diag.isAccessibilityEnabled) ICostGreenIncome else ICostAmberGold
                            )
                            Text("Android 13+ 侧载安装需先在「应用详情 ➔ ⋮」允许受限设置", fontSize = 11.sp, color = ICostTextSecondary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = {
                                RomKeepAliveGuideHelper.openAppDetailsSettings(context)
                            }) {
                                Text("解除受限", fontSize = 11.sp)
                            }
                            Button(
                                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                                colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent)
                            ) {
                                Text(if (diag.isAccessibilityEnabled) "已开启" else "去开启", fontSize = 11.sp)
                            }
                        }
                    }

                    HorizontalDivider(color = ICostDivider)

                    // 3. 电池优化忽略状态
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (diag.isIgnoringBatteryOptimizations) "✅ 已加入电池后台白名单" else "⚠️ 尚未忽略后台电池优化",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (diag.isIgnoringBatteryOptimizations) ICostGreenIncome else ICostAmberGold
                            )
                            Text("防止锁屏后微信/银行通知延迟入账", fontSize = 11.sp, color = ICostTextSecondary)
                        }
                        OutlinedButton(onClick = {
                            RomKeepAliveGuideHelper.openBatteryOptimizationSettings(context)
                            refreshTick++
                        }) {
                            Text(if (diag.isIgnoringBatteryOptimizations) "已白名单" else "去设置", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        item {
            Surface(color = ICostBgGray, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "${diag.romDisplayName} 专属防杀后台操作步骤：",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextPrimary
                    )
                    diag.romKeepAliveSteps.forEach { step ->
                        Text(text = step, fontSize = 12.sp, color = ICostTextSecondary)
                    }
                }
            }
        }

        if (!actionFeedback.isNullOrBlank()) {
            item {
                Text(text = actionFeedback!!, fontSize = 12.sp, color = ICostGreenIncome, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * 批量报销核销中心面板（对标 Cashew 借贷/报销分批核销闭环）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchReimbursementSheet(
    transactions: List<TransactionEntity>,
    wallets: List<WalletEntity>,
    onBatchSettle: (List<Long>, Long?, (String) -> Unit) -> Unit
) {
    val pendingList = remember(transactions) {
        transactions.filter { it.reimbursable && !it.reimbursed }
    }
    var selectedIds by remember(pendingList) {
        mutableStateOf(pendingList.map { it.id }.toSet())
    }
    val assetWallets = remember(wallets) {
        wallets.filter { !it.type.isLiability }.ifEmpty { wallets }
    }
    var receiveWalletId by remember(assetWallets) {
        mutableStateOf(assetWallets.firstOrNull()?.id)
    }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    val selectedTotalCents = remember(pendingList, selectedIds) {
        pendingList
            .filter { it.id in selectedIds }
            .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text(
                text = "批量报销核销中心",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ICostTextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "当前共有 ${pendingList.size} 笔待报销账单，已勾选 ${selectedIds.size} 笔 · 合计待入账 ${formatMoneyCents(selectedTotalCents)}：",
                fontSize = 13.sp,
                color = ICostTextSecondary
            )
        }

        if (assetWallets.isNotEmpty()) {
            item {
                Text("选择报销款入账账户：", fontSize = 12.sp, color = ICostTextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    assetWallets.forEach { w ->
                        FilterChip(
                            selected = receiveWalletId == w.id,
                            onClick = { receiveWalletId = w.id },
                            label = { Text(w.name, fontSize = 12.sp) }
                        )
                    }
                }
            }
        }

        if (pendingList.isEmpty()) {
            item {
                Surface(color = ICostBgGray, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "🎉 当前所有待报销账单均已结清！可在记账面板或通知栏快捷按钮中将差旅/垫付账单标为「待报销」。",
                        fontSize = 13.sp,
                        color = ICostTextSecondary,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            item {
                Surface(color = ICostBgGray, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pendingList.forEach { tx ->
                            val isChecked = tx.id in selectedIds
                            val netCents = (tx.amountCnyCents - tx.refundedAmountCents).coerceAtLeast(0L)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedIds = if (isChecked) selectedIds - tx.id else selectedIds + tx.id
                                    }
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${if (isChecked) "☑️" else "⬜"} ${tx.category} · ${tx.merchant.ifBlank { "日常消费" }}",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ICostTextPrimary
                                    )
                                    if (tx.tags.isNotBlank()) {
                                        Text("#${tx.tags}", fontSize = 11.sp, color = ICostBlueAccent)
                                    }
                                }
                                Text(
                                    text = formatMoneyCents(netCents, tx.currency),
                                    fontFamily = ICDinFontFamily,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ICostCoralRed
                                )
                            }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        onBatchSettle(selectedIds.toList(), receiveWalletId) { msg ->
                            statusBanner = msg
                        }
                    },
                    enabled = selectedIds.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = "一键批量报销入账 (${selectedIds.size} 笔 · ${formatMoneyCents(selectedTotalCents)})",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (!statusBanner.isNullOrBlank()) {
            item {
                Text(text = statusBanner!!, fontSize = 13.sp, color = ICostGreenIncome, fontWeight = FontWeight.Bold)
            }
        }
    }
}
