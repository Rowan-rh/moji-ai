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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Snackbar
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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

    var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0: 账本, 1: 资产, 2: 统计
    // 每个 Tab 独立保存 rememberSaveable 状态（搜索词、折叠状态、统计周期等），切换 Tab 回来不丢失
    val tabStateHolder = rememberSaveableStateHolder()

    val nowCal = remember { Calendar.getInstance() }
    var selectedYear by rememberSaveable { mutableIntStateOf(nowCal.get(Calendar.YEAR)) }
    var selectedMonth by rememberSaveable { mutableIntStateOf(nowCal.get(Calendar.MONTH) + 1) }

    // 全局操作反馈：删除、一键记账、退款等完成后弹出 Snackbar，可撤销的操作附带「撤销」按钮
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.uiMessages.collect { msg ->
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = msg.text,
                actionLabel = if (msg.undoTransactionId != null) "撤销" else null,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                msg.undoTransactionId?.let { viewModel.undoCreatedTransaction(it) }
            }
        }
    }

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
            tabStateHolder.SaveableStateProvider(tabIndex) {
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
        }

        // 非首页 Tab 按返回键先回到「账本」，而不是直接退出应用（二级页面与弹窗各自的返回处理优先级更高）
        BackHandler(enabled = selectedTab != 0 && !hasFullScreenOverlay && !showCalculatorSheet) {
            selectedTab = 0
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
                onOpenCsvImport = {
                    backupInitialTab = 0
                    showBackupTransferSheet = true
                },
                onOpenCsvExport = {
                    backupInitialTab = 1
                    showBackupTransferSheet = true
                },
                onOpenWebDavSync = {
                    backupInitialTab = 2
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

        // 全局 Snackbar：位于所有页面之上；底部导航可见时上移，避免遮挡「+」按钮
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = if (!hasFullScreenOverlay && !showCalculatorSheet) 84.dp else 16.dp
                )
        ) { data ->
            Snackbar(
                snackbarData = data,
                shape = RoundedCornerShape(16.dp),
                containerColor = Color(0xFF262628),
                contentColor = Color.White,
                actionColor = Color(0xFF6AA8FF)
            )
        }
    }

    if (showBudgetDialog) {
        var budgetInput by remember(monthlyBudgetCents) {
            mutableStateOf(if (monthlyBudgetCents > 0) String.format("%.0f", monthlyBudgetCents / 100.0) else "")
        }
        val parsedBudget = budgetInput.toDoubleOrNull()
        val budgetError = budgetInput.isNotBlank() && (parsedBudget == null || parsedBudget < 0)
        AlertDialog(
            onDismissRequest = { showBudgetDialog = false },
            containerColor = ICostCardWhite,
            title = { Text("月度预算", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = budgetInput,
                        onValueChange = { input -> budgetInput = sanitizeAmountInput(input) },
                        label = { Text("每月预算 (¥)") },
                        placeholder = { Text("留空表示不设预算") },
                        singleLine = true,
                        isError = budgetError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    if (budgetError) {
                        Text("请输入有效金额", fontSize = 12.sp, color = ICostCoralRed)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !budgetError,
                    onClick = {
                        viewModel.updateMonthlyBudgetYuan(parsedBudget ?: 0.0)
                        showBudgetDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent)
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBudgetDialog = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 金额输入过滤：只保留数字与一个小数点，小数最多两位
 */
private fun sanitizeAmountInput(raw: String): String {
    val sb = StringBuilder()
    var dotSeen = false
    var decimals = 0
    for (c in raw) {
        when {
            c.isDigit() -> {
                if (dotSeen) {
                    if (decimals >= 2) continue
                    decimals++
                }
                sb.append(c)
            }
            c == '.' && !dotSeen -> {
                dotSeen = true
                sb.append(c)
            }
        }
    }
    return sb.toString().take(12)
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
                    onSwipeRight = { if (selectedTab < 2) onSelectTab(selectedTab + 1) },
                    onSwipeLeft = { if (selectedTab > 0) onSelectTab(selectedTab - 1) }
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
                modifier = Modifier
                    .size(22.dp)
                    .semantics { contentDescription = "记一笔" }
            )
        }
    }
}

private data class SettingRowDef(
    val iconAsset: String,
    val title: String,
    val trailingText: String = "",
    /** 为 null 表示功能尚未开放：行内显示「敬请期待」且不可点击，避免用户点了没反应 */
    val onClick: (() -> Unit)? = null
)

/**
 * 页面重新回到前台（ON_RESUME）时自增的计数器：用于从系统设置页返回后刷新权限状态
 */
@Composable
private fun rememberResumeTick(): Int {
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return tick
}

/**
 * 二级全屏页统一顶栏：返回按钮 + 大标题
 */
@Composable
private fun SubpageHeader(title: String, onBack: () -> Unit) {
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
        text = title,
        fontSize = 30.sp,
        fontWeight = FontWeight.ExtraBold,
        color = ICostTextPrimary,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
    Spacer(modifier = Modifier.height(14.dp))
}

@Composable
private fun SettingsSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = ICostTextSecondary,
        modifier = modifier.padding(start = 6.dp, bottom = 8.dp)
    )
}

/**
 * 1:1 iCost 全屏「设置」页（对齐 09_settings_full.png）
 *
 * 分组按使用频率排列：记账 → 数据 → 尚未开放；尚未开放的条目统一置灰并标注「敬请期待」，
 * 不再出现点了没反应的行，也不再展示虚构的会员状态。
 */
@Composable
private fun ICostSettingsFullScreen(
    currentLedger: String,
    monthlyBudgetCents: Long,
    onBack: () -> Unit,
    onOpenSmartBookkeeping: () -> Unit,
    onOpenBudgetDialog: () -> Unit,
    onOpenCsvImport: () -> Unit = {},
    onOpenCsvExport: () -> Unit = {},
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
        SubpageHeader(title = "设置", onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            // 应用信息卡片
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ICostCardWhite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ICostAvatarButton(onClick = {}, size = 46.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "MoJi 默记",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostTextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "当前账本：$currentLedger",
                                fontSize = 13.sp,
                                color = ICostTextSecondary
                            )
                        }
                    }
                }
            }

            item {
                SettingsSectionTitle("记账")
                SettingsGroupCard(
                    rows = listOf(
                        SettingRowDef("setting_auto.webp", "智能记账", "自动识账与规则", onClick = onOpenSmartBookkeeping),
                        SettingRowDef(
                            "setting_budget.webp",
                            "预算管理",
                            if (monthlyBudgetCents > 0) formatMoneyCents(monthlyBudgetCents) else "未设置",
                            onClick = onOpenBudgetDialog
                        ),
                        SettingRowDef("setting_reimbursement.webp", "报销管理", "批量核销", onClick = onOpenBatchReimburse),
                        SettingRowDef("setting_multi_book.webp", "多账本", "在首页顶部切换")
                    )
                )
            }

            item {
                SettingsSectionTitle("数据")
                SettingsGroupCard(
                    rows = listOf(
                        SettingRowDef("setting_import.webp", "账单导入", "微信 / 支付宝 / 钱迹", onClick = onOpenCsvImport),
                        SettingRowDef("setting_export.webp", "账单导出", "CSV", onClick = onOpenCsvExport),
                        SettingRowDef("setting_backup.webp", "备份与云同步", "快照 / WebDAV", onClick = onOpenWebDavSync)
                    )
                )
            }

            item {
                SettingsSectionTitle("更多功能")
                SettingsGroupCard(
                    rows = listOf(
                        SettingRowDef("setting_category.webp", "分类管理"),
                        SettingRowDef("setting_tag.webp", "标签管理"),
                        SettingRowDef("setting_template.webp", "记账模板"),
                        SettingRowDef("setting_currency.webp", "货币和汇率"),
                        SettingRowDef("setting_password.webp", "密码保护"),
                        SettingRowDef("setting_time.webp", "定时提醒"),
                        SettingRowDef("setting_appearance.webp", "外观显示")
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
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            rows.forEachIndexed { index, row ->
                val action = row.onClick
                val isComingSoon = action == null && row.trailingText.isBlank()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (action != null) Modifier.clickable(onClick = action) else Modifier)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ICostAssetImage(
                        assetFile = row.iconAsset,
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer { alpha = if (isComingSoon) 0.45f else 1f }
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = row.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isComingSoon) ICostTextMuted else ICostTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    val trailing = if (isComingSoon) "敬请期待" else row.trailingText
                    if (trailing.isNotBlank()) {
                        Text(
                            text = trailing,
                            fontSize = 13.sp,
                            color = if (action != null) ICostTextSecondary else ICostTextMuted
                        )
                    }
                    if (action != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = ICostTextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (index < rows.lastIndex) {
                    HorizontalDivider(
                        color = ICostDivider,
                        modifier = Modifier.padding(start = 52.dp, end = 16.dp)
                    )
                }
            }
        }
    }
}

/**
 * 1:1 iCost 全屏「智能记账」页面（对齐 09_smart_bookkeeping.png）
 *
 * - 权限状态在每次回到前台时刷新，从系统设置返回后无需重进页面；
 * - 去掉了不落盘、不生效的「假开关」，改为只读说明，避免用户误以为可以关闭；
 * - 删除规则需二次确认；场景列表不再伪装成可点击的行。
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val resumeTick = rememberResumeTick()
    val isNotifGranted = remember(resumeTick) { isNotificationListenerEnabled(context) }
    val isAccessGranted = remember(resumeTick) { isAccessibilityServiceEnabled(context) }

    var showAddSmartRuleDialog by remember { mutableStateOf(false) }
    var pendingDeleteRule by remember { mutableStateOf<SmartConditionRule?>(null) }

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
        SubpageHeader(title = "智能记账", onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            // 识账来源：开关 + 实时授权状态
            item {
                SettingsSectionTitle("识账来源")
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ICostCardWhite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        SmartSwitchRow(
                            title = "支付页自动识账",
                            subtitle = when {
                                !aiConfig.enableAccessibilityCapture -> "读取微信 / 支付宝支付完成页，需要无障碍权限"
                                isAccessGranted -> "运行中"
                                else -> "未授权无障碍权限，点此前往开启"
                            },
                            subtitleIsWarning = aiConfig.enableAccessibilityCapture && !isAccessGranted,
                            onSubtitleClick = if (aiConfig.enableAccessibilityCapture && !isAccessGranted) {
                                { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                            } else null,
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
                            title = "通知栏自动识账",
                            subtitle = when {
                                !aiConfig.enableNotificationCapture -> "读取银行与支付通知，需要通知使用权"
                                isNotifGranted -> "运行中"
                                else -> "未授权通知使用权，点此前往开启"
                            },
                            subtitleIsWarning = aiConfig.enableNotificationCapture && !isNotifGranted,
                            onSubtitleClick = if (aiConfig.enableNotificationCapture && !isNotifGranted) {
                                { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                            } else null,
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
                            title = "后台保活诊断",
                            trailing = "防止漏记",
                            onClick = onOpenKeepAliveGuide
                        )
                    }
                }
            }

            // AI 识别
            item {
                SettingsSectionTitle("AI 识别")
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ICostCardWhite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        SmartArrowRow(
                            title = "大模型接口",
                            trailing = if (aiConfig.apiKey.isBlank()) "未配置 · 使用本地引擎" else aiConfig.modelName,
                            onClick = onOpenAiModelConfig
                        )
                        HorizontalDivider(color = ICostDivider)
                        SmartArrowRow(
                            title = "识账测试",
                            trailing = "粘贴文本试一试",
                            onClick = onOpenAiSandbox
                        )
                    }
                }
            }

            // 自动处理说明（只读，这些行为目前始终开启）
            item {
                SettingsSectionTitle("自动处理")
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ICostCardWhite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        listOf(
                            "识别后直接入账，并推送可一键撤销的通知",
                            "按商户与商品自动匹配分类，按卡号尾号匹配账户",
                            "同一笔交易的支付页与银行通知自动合并，不重复记账"
                        ).forEach { line ->
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = ICostGreenIncome,
                                    modifier = Modifier
                                        .padding(top = 1.dp)
                                        .size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = line, fontSize = 13.sp, color = ICostTextPrimary)
                            }
                        }
                    }
                }
            }

            // 条件规则 When -> Then
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsSectionTitle(
                        text = if (smartRules.isEmpty()) "分类规则" else "分类规则 · ${smartRules.count { it.enabled }}/${smartRules.size} 启用",
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showAddSmartRuleDialog = true }) {
                        Text("新增规则", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ICostBlueAccent)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ICostCardWhite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        if (smartRules.isEmpty()) {
                            Text(
                                text = "还没有规则。例如：包含「美团」且金额低于 50 元时，归入「餐饮-三餐」。",
                                fontSize = 13.sp,
                                color = ICostTextSecondary,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                        smartRules.forEachIndexed { idx, rule ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = rule.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (rule.enabled) ICostTextPrimary else ICostTextMuted
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "当 ${rule.conditionSummary()}",
                                        fontSize = 12.sp,
                                        color = ICostTextSecondary
                                    )
                                    Text(
                                        text = "则 ${rule.actionSummary()}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (rule.interceptIgnore) ICostCoralRed else ICostBlueAccent
                                    )
                                }
                                TextButton(onClick = { pendingDeleteRule = rule }) {
                                    Text("删除", fontSize = 13.sp, color = ICostCoralRed)
                                }
                                Switch(
                                    checked = rule.enabled,
                                    onCheckedChange = { onToggleSmartRule(rule.id, it) },
                                    colors = iCostSwitchColors()
                                )
                            }
                            if (idx < smartRules.lastIndex) {
                                HorizontalDivider(color = ICostDivider)
                            }
                        }
                    }
                }
            }

            // 商户记忆
            item {
                SettingsSectionTitle(
                    if (merchantMemories.isEmpty()) "商户记忆" else "商户记忆 · 已记住 ${merchantMemories.size} 个"
                )
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ICostCardWhite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        if (merchantMemories.isEmpty()) {
                            Text(
                                text = "手动记账或修改账单分类后，会自动记住该商户，下次直接套用。",
                                fontSize = 13.sp,
                                color = ICostTextSecondary,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            val visibleMemories = merchantMemories.take(10)
                            visibleMemories.forEachIndexed { idx, entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${entry.merchantKeyword} → ${entry.category}",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ICostTextPrimary
                                        )
                                        Text(
                                            text = "已使用 ${entry.hitCount} 次",
                                            fontSize = 12.sp,
                                            color = ICostTextSecondary
                                        )
                                    }
                                    TextButton(onClick = { onRemoveMerchantMemory(entry.merchantKeyword) }) {
                                        Text("移除", fontSize = 13.sp, color = ICostCoralRed)
                                    }
                                }
                                if (idx < visibleMemories.lastIndex) {
                                    HorizontalDivider(color = ICostDivider)
                                }
                            }
                        }
                    }
                }
            }

            // 识别日志
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsSectionTitle(
                        text = if (observabilityLogs.isEmpty()) "识别日志" else "识别日志 · ${observabilityLogs.size} 条",
                        modifier = Modifier.weight(1f)
                    )
                    if (observabilityLogs.isNotEmpty()) {
                        TextButton(onClick = onClearLogs) {
                            Text("清空", fontSize = 13.sp, color = ICostBlueAccent)
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ICostCardWhite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        if (observabilityLogs.isEmpty()) {
                            Text(
                                text = "暂无识别记录。收到支付通知或打开支付完成页后会显示在这里。",
                                fontSize = 13.sp,
                                color = ICostTextSecondary,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            val visibleLogs = observabilityLogs.take(8)
                            visibleLogs.forEachIndexed { idx, log ->
                                val badgeColor = when (log.outcome) {
                                    CaptureEventOutcome.RECORDED -> ICostGreenIncome
                                    CaptureEventOutcome.FUSED_PATCH -> ICostBlueAccent
                                    CaptureEventOutcome.DEDUP_BLOCKED -> ICostAmberGold
                                    CaptureEventOutcome.NOISE_IGNORED -> ICostTextSecondary
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text(
                                            text = "${log.outcome.badgeLabel} · ${log.captureSource}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = badgeColor
                                        )
                                        Text(
                                            text = log.detailSummary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ICostTextPrimary
                                        )
                                        Text(
                                            text = log.rawText.replace("\n", " ").take(80),
                                            fontSize = 11.sp,
                                            color = ICostTextSecondary,
                                            maxLines = 2
                                        )
                                    }
                                    TextButton(onClick = { onReplayLog(log) }) {
                                        Text("重新识别", fontSize = 12.sp, color = ICostBlueAccent)
                                    }
                                }
                                if (idx < visibleLogs.lastIndex) {
                                    HorizontalDivider(color = ICostDivider)
                                }
                            }
                        }
                    }
                }
            }

            // 已支持的场景（纯展示）
            item {
                SettingsSectionTitle("已支持的场景")
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ICostCardWhite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FlowRow(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "微信支付", "微信红包", "微信转账", "支付宝", "云闪付",
                            "京东", "拼多多", "美团", "招商银行 / 掌上生活"
                        ).forEach { scene ->
                            Text(
                                text = scene,
                                fontSize = 12.sp,
                                color = ICostTextPrimary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(ICostBgGray)
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDeleteRule?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDeleteRule = null },
            containerColor = ICostCardWhite,
            title = { Text("删除规则？", fontWeight = FontWeight.ExtraBold) },
            text = { Text("「${rule.name}」删除后不可恢复，已入账的账单不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSmartRule(rule.id)
                    pendingDeleteRule = null
                }) { Text("删除", color = ICostCoralRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteRule = null }) { Text("取消") }
            }
        )
    }

    if (showAddSmartRuleDialog) {
        var ruleName by remember { mutableStateOf("") }
        var keywordStr by remember { mutableStateOf("") }
        var minYuanStr by remember { mutableStateOf("") }
        var maxYuanStr by remember { mutableStateOf("") }
        var targetCat by remember { mutableStateOf("餐饮-三餐") }
        var markReimburse by remember { mutableStateOf(false) }
        var tagStr by remember { mutableStateOf("") }

        val minYuan = minYuanStr.toDoubleOrNull()
        val maxYuan = maxYuanStr.toDoubleOrNull()
        val amountError = when {
            minYuanStr.isNotBlank() && minYuan == null -> "最低金额格式不正确"
            maxYuanStr.isNotBlank() && maxYuan == null -> "最高金额格式不正确"
            minYuan != null && maxYuan != null && minYuan > maxYuan -> "最低金额不能大于最高金额"
            else -> null
        }
        val hasCondition = keywordStr.isNotBlank() || minYuan != null || maxYuan != null

        AlertDialog(
            onDismissRequest = { showAddSmartRuleDialog = false },
            containerColor = ICostCardWhite,
            title = { Text("新增分类规则", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = ruleName,
                        onValueChange = { ruleName = it },
                        label = { Text("规则名称") },
                        placeholder = { Text("如：园区晚餐报销") },
                        singleLine = true
                    )
                    Text("当满足（至少填一项）", fontSize = 12.sp, color = ICostTextSecondary)
                    OutlinedTextField(
                        value = keywordStr,
                        onValueChange = { keywordStr = it },
                        label = { Text("包含关键词") },
                        placeholder = { Text("多个用逗号分隔，如：美团,饿了么") },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = minYuanStr,
                            onValueChange = { minYuanStr = it },
                            label = { Text("最低金额") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = maxYuanStr,
                            onValueChange = { maxYuanStr = it },
                            label = { Text("最高金额") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true
                        )
                    }
                    if (amountError != null) {
                        Text(amountError, fontSize = 12.sp, color = ICostCoralRed)
                    }
                    Text("则执行", fontSize = 12.sp, color = ICostTextSecondary)
                    OutlinedTextField(
                        value = targetCat,
                        onValueChange = { targetCat = it },
                        label = { Text("归入分类") },
                        placeholder = { Text("如：餐饮-三餐") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = tagStr,
                        onValueChange = { tagStr = it },
                        label = { Text("附加标签（选填）") },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("标记为待报销", fontSize = 14.sp, color = ICostTextPrimary)
                        Switch(
                            checked = markReimburse,
                            onCheckedChange = { markReimburse = it },
                            colors = iCostSwitchColors()
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = ruleName.isNotBlank() && hasCondition && amountError == null && targetCat.isNotBlank(),
                    onClick = {
                        onAddSmartRule(
                            SmartConditionRule(
                                name = ruleName.trim(),
                                enabled = true,
                                keywordContains = keywordStr.trim(),
                                minAmountYuan = minYuan,
                                maxAmountYuan = maxYuan,
                                targetCategory = targetCat.trim(),
                                markReimbursable = markReimburse,
                                appendTag = tagStr.trim()
                            )
                        )
                        showAddSmartRuleDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent)
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddSmartRuleDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun iCostSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = ICostBlueAccent,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = Color(0xFFE0E0E5),
    uncheckedBorderColor = Color.Transparent
)

@Composable
private fun SmartSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitleIsWarning: Boolean = false,
    onSubtitleClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ICostTextPrimary)
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = if (subtitleIsWarning) ICostAmberGold else ICostTextSecondary,
                    fontWeight = if (subtitleIsWarning) FontWeight.Medium else FontWeight.Normal,
                    modifier = if (onSubtitleClick != null) Modifier.clickable(onClick = onSubtitleClick) else Modifier
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = iCostSwitchColors()
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
        "支付宝付款" to "【支付宝】付款成功：向商户【盒马】付款 32.80元，付款方式：账户余额",
        "微信外卖" to "【微信支付】美团外卖 支付成功 26.50元，商品：隆江猪脚饭外卖，零钱支付",
        "银行卡扣款" to "【招商银行】您尾号3354的信用卡消费人民币88.00元",
        "补全商户" to "【微信支付】向商户【山姆会员商店】付款88.00元",
        "信用卡消费" to "【招商银行】您尾号3354的招商银行信用卡消费人民币69.00元，商户：京东商城",
        "退款" to "【微信支付】退款入账通知：商户【唐久(环普产业园店)】已退回 5.00元 至您的账户余额",
        "信用卡还款" to "【招商银行】还款成功：您已使用招商银行储蓄卡(8848)成功偿还招商银行信用卡(3354)账单 500.00元"
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
            Text("识账测试", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = ICostTextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "粘贴一条支付通知或账单文本，看看会被识别成什么。识别成功会真实记入当前账本，可在首页左滑删除。",
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
                    // 点击示例只填入文本，由用户确认后再识别，避免误触直接记账
                    FilterChip(
                        selected = customInput == sampleText,
                        onClick = { customInput = sampleText },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = customInput,
                onValueChange = { customInput = it },
                label = { Text("通知或账单文本") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
        }

        item {
            Button(
                onClick = { onRunSimulation(customInput, "com.tencent.mm") },
                enabled = !isParsing && customInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                if (isParsing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("正在识别…")
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("识别并记账", fontWeight = FontWeight.Bold)
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
                                text = "最近识别记录 (${observabilityLogs.size})",
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
                                text = "${log.outcome.badgeLabel} · ${log.detailSummary}",
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
    var showApiKey by remember { mutableStateOf(false) }
    val hasUnsavedChanges = provider != config.provider || baseUrl != config.baseUrl ||
        apiKey != config.apiKey || modelName != config.modelName || enableFallback != config.enableHeuristicFallback

    val resumeTick = rememberResumeTick()
    val isNotifGranted = remember(resumeTick) { isNotificationListenerEnabled(context) }
    val isAccessGranted = remember(resumeTick) { isAccessibilityServiceEnabled(context) }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text("大模型与权限", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "不填 API Key 也能用：会使用内置的本地识别引擎。",
                fontSize = 12.sp,
                color = ICostTextSecondary
            )
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
                            Text("通知使用权", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                            Text("无障碍权限", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                label = { Text("接口地址") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it },
                label = { Text("模型名称") },
                placeholder = { Text("如 deepseek-chat / gemini-2.5-flash") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key（选填）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showApiKey = !showApiKey }) {
                        Text(if (showApiKey) "隐藏" else "显示", fontSize = 12.sp)
                    }
                }
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("本地引擎兜底", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("断网或接口出错时改用本地识别", fontSize = 12.sp, color = ICostTextSecondary)
                }
                Switch(checked = enableFallback, onCheckedChange = { enableFallback = it }, colors = iCostSwitchColors())
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
                },
                enabled = hasUnsavedChanges,
                colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(if (hasUnsavedChanges) "保存" else "已保存", fontWeight = FontWeight.Bold)
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
    // 默认留空：之前预填的是示例账单，用户直接点「导入」会把假数据写进账本
    var csvText by remember { mutableStateOf("") }
    var showDavPassword by remember { mutableStateOf(false) }
    // 恢复会覆盖现有数据，先二次确认：0 = 无，1 = 从快照恢复，2 = 从 WebDAV 恢复
    var pendingRestoreKind by remember { mutableIntStateOf(0) }
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
                text = "导入、导出与备份",
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
                    2 to "备份快照",
                    3 to "WebDAV 同步"
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
                    text = "支持微信、支付宝官方账单和钱迹 / iCost 导出的 CSV。先预览再导入，重复的账单会自动跳过。",
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
                            val clipText = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                            if (!clipText.isNullOrBlank()) {
                                csvText = clipText
                                previewResult = onPreviewCsv(csvText)
                                localBanner = "已从剪贴板粘贴并解析 CSV"
                            } else {
                                localBanner = "剪贴板为空"
                            }
                        },
                        label = { Text("从剪贴板粘贴", fontSize = 12.sp) }
                    )
                    AssistChip(
                        onClick = {
                            csvText = BackupAndCsvTransferEngine.SAMPLE_WECHAT_CSV
                            previewResult = onPreviewCsv(csvText)
                            localBanner = "已填入示例，仅供查看格式，请勿直接导入"
                        },
                        label = { Text("查看示例格式", fontSize = 12.sp) }
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
                    label = { Text("CSV 内容") },
                    placeholder = { Text("把导出的 CSV 文本粘贴到这里") },
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
                    Text(
                        "导入时同步调整账户余额",
                        fontSize = 13.sp,
                        color = ICostTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = applyImportToWalletBalance,
                        onCheckedChange = { applyImportToWalletBalance = it },
                        colors = iCostSwitchColors()
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
                        enabled = csvText.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text("预览", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            onCommitCsvImport(csvText, applyImportToWalletBalance) { msg ->
                                localBanner = msg
                            }
                        },
                        // 先预览、确认识别结果后才能导入
                        enabled = previewResult?.parsedTransactions?.isNotEmpty() == true,
                        colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text(
                            previewResult?.parsedTransactions?.size?.takeIf { it > 0 }?.let { "导入 $it 笔" } ?: "导入",
                            fontWeight = FontWeight.Bold
                        )
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
                                text = "识别为「${preview.detectedSourceFormat}」，共 ${preview.parsedTransactions.size} 笔账单" +
                                    if (preview.skippedNonFinancialRows > 0) "，跳过 ${preview.skippedNonFinancialRows} 行无关内容" else "",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostBlueAccent
                            )
                            preview.parsedTransactions.take(5).forEach { tx ->
                                Text(
                                    text = "${tx.type.displayName} · ${tx.category} · ${tx.merchant} · ${formatMoneyCents(tx.amountCnyCents, tx.currency)}",
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
                    text = "导出全部账单为 CSV，可用 Excel、钱迹或 iCost 打开。",
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
                        Text("生成 CSV", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            if (exportedCsvText.isNotBlank()) {
                                clipboard?.setPrimaryClip(ClipData.newPlainText("MoJi_CSV", exportedCsvText))
                                localBanner = "已复制到剪贴板"
                            }
                        },
                        enabled = exportedCsvText.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text("复制", fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (exportedCsvText.isNotBlank()) {
                item {
                    OutlinedTextField(
                        value = exportedCsvText,
                        onValueChange = {},
                        label = { Text("CSV 预览") },
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 5,
                        maxLines = 9
                    )
                }
            }
        } else if (currentTab == 2) {
            item {
                Text(
                    text = "备份包含账户、全部账单、周期账单、商户记忆、账本与预算。生成后会复制到剪贴板，请保存到安全的地方。",
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
                            localBanner = "备份已生成并复制到剪贴板"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text("生成备份", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            val clipText = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                            if (!clipText.isNullOrBlank()) {
                                backupJsonText = clipText
                                localBanner = "已从剪贴板载入备份"
                            } else {
                                localBanner = "剪贴板为空"
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text("粘贴备份", fontWeight = FontWeight.Bold)
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = backupJsonText,
                    onValueChange = { backupJsonText = it },
                    label = { Text("备份内容") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 8
                )
            }

            item {
                Button(
                    onClick = {
                        pendingRestoreKind = 1
                    },
                    enabled = backupJsonText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = ICostCoralRed),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text("从备份恢复", fontWeight = FontWeight.Bold)
                }
            }
        } else {
            item {
                Text(
                    text = "支持坚果云、Nextcloud、群晖、Alist 等标准 WebDAV 服务。坚果云地址示例：https://dav.jianguoyun.com/dav/MoJi/",
                    fontSize = 12.sp,
                    color = ICostTextSecondary
                )
            }
            item {
                OutlinedTextField(
                    value = davUrl,
                    onValueChange = { davUrl = it },
                    label = { Text("服务器地址") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = davUser,
                    onValueChange = { davUser = it },
                    label = { Text("账号") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = davPassword,
                    onValueChange = { davPassword = it },
                    label = { Text("应用密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showDavPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showDavPassword = !showDavPassword }) {
                            Text(if (showDavPassword) "隐藏" else "显示", fontSize = 12.sp)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = davFileName,
                    onValueChange = { davFileName = it },
                    label = { Text("备份文件名") },
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
                    val davReady = davUrl.isNotBlank() && davUser.isNotBlank() && davPassword.isNotBlank()
                    Button(
                        enabled = davReady,
                        onClick = {
                            localBanner = "正在上传…"
                            onUploadWebDav(activeCfg) { msg -> localBanner = msg }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text("上传备份", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            pendingRestoreKind = 2
                        },
                        enabled = davReady,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text("从云端恢复", fontWeight = FontWeight.Bold)
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

    if (pendingRestoreKind != 0) {
        val fromCloud = pendingRestoreKind == 2
        AlertDialog(
            onDismissRequest = { pendingRestoreKind = 0 },
            containerColor = ICostCardWhite,
            title = { Text("覆盖现有数据？", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text(
                    if (fromCloud) "将从云端下载备份并恢复，当前设备上的账户和账单会被备份内容替换。"
                    else "当前设备上的账户和账单会被备份内容替换。建议先生成一份当前数据的备份。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestoreKind = 0
                    if (fromCloud) {
                        localBanner = "正在从云端恢复…"
                        onRestoreWebDav(
                            WebDavConfig(
                                serverUrl = davUrl.trim(),
                                username = davUser.trim(),
                                appPassword = davPassword.trim(),
                                remoteFileName = davFileName.trim().ifBlank { "moji_latest.mojibackup" },
                                lastSyncTimeMillis = webDavConfig.lastSyncTimeMillis
                            )
                        ) { msg -> localBanner = msg }
                    } else {
                        onRestoreFullBackup(backupJsonText) { msg -> localBanner = msg }
                    }
                }) { Text("恢复", color = ICostCoralRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreKind = 0 }) { Text("取消") }
            }
        )
    }
}

/**
 * 国产 ROM 后台静默运行保活与 Android 13+ 受限设置一站式诊断向导面板
 */
@Composable
private fun RomKeepAliveGuideSheet() {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    // 从系统设置页返回时自动重新检测
    val resumeTick = rememberResumeTick()
    val diag = remember(refreshTick, resumeTick) { RomKeepAliveGuideHelper.inspectCurrentDevice(context) }
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
                text = "后台保活诊断 · ${diag.passedCount}/${diag.totalCoreChecks} 项通过",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ICostTextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${diag.romDisplayName} · ${diag.model} · Android SDK ${diag.sdkInt}",
                fontSize = 12.sp,
                color = ICostTextSecondary
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
                                text = if (diag.isNotificationListenerEnabled) "通知使用权已开启" else "通知使用权未开启",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (diag.isNotificationListenerEnabled) ICostGreenIncome else ICostAmberGold
                            )
                            Text("通知不再被识别时，可尝试重连", fontSize = 12.sp, color = ICostTextSecondary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = {
                                val ok = RomKeepAliveGuideHelper.requestNotificationListenerRebind(context)
                                actionFeedback = if (ok) "已请求系统重新连接通知服务" else "请先开启通知使用权"
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
                                text = if (diag.isAccessibilityEnabled) "无障碍权限已开启" else "无障碍权限未开启",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (diag.isAccessibilityEnabled) ICostGreenIncome else ICostAmberGold
                            )
                            Text("无法开启时，先到「应用详情 › 右上角菜单」允许受限设置", fontSize = 12.sp, color = ICostTextSecondary)
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
                                text = if (diag.isIgnoringBatteryOptimizations) "已允许后台运行" else "未关闭电池优化",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (diag.isIgnoringBatteryOptimizations) ICostGreenIncome else ICostAmberGold
                            )
                            Text("避免锁屏后漏记或延迟入账", fontSize = 12.sp, color = ICostTextSecondary)
                        }
                        OutlinedButton(onClick = {
                            RomKeepAliveGuideHelper.openBatteryOptimizationSettings(context)
                            refreshTick++
                        }) {
                            Text(if (diag.isIgnoringBatteryOptimizations) "已允许" else "去设置", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        item {
            Surface(color = ICostBgGray, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "${diag.romDisplayName} 设置步骤",
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
                Text(text = actionFeedback!!, fontSize = 12.sp, color = ICostTextSecondary)
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
                text = "批量报销",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ICostTextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (pendingList.isEmpty()) "没有待报销的账单" else "共 ${pendingList.size} 笔待报销，已选 ${selectedIds.size} 笔，合计 ${formatMoneyCents(selectedTotalCents)}",
                fontSize = 13.sp,
                color = ICostTextSecondary
            )
        }

        if (assetWallets.isNotEmpty() && pendingList.isNotEmpty()) {
            item {
                Text("报销款存入", fontSize = 12.sp, color = ICostTextSecondary)
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
                        text = "记账时打开「待报销」，或在首页左滑账单点「报销」，账单就会出现在这里。",
                        fontSize = 13.sp,
                        color = ICostTextSecondary,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            item {
                Surface(color = ICostBgGray, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        if (pendingList.size > 1) {
                            val allSelected = selectedIds.size == pendingList.size
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedIds = if (allSelected) emptySet() else pendingList.map { it.id }.toSet()
                                    }
                                    .padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = allSelected,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(checkedColor = ICostBlueAccent)
                                )
                                Text("全选", fontSize = 14.sp, color = ICostTextPrimary)
                            }
                            HorizontalDivider(color = ICostDivider, modifier = Modifier.padding(horizontal = 12.dp))
                        }
                        pendingList.forEach { tx ->
                            val isChecked = tx.id in selectedIds
                            val netCents = (tx.amountCnyCents - tx.refundedAmountCents).coerceAtLeast(0L)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedIds = if (isChecked) selectedIds - tx.id else selectedIds + tx.id
                                    }
                                    .padding(start = 6.dp, end = 14.dp, top = 2.dp, bottom = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(checkedColor = ICostBlueAccent)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${tx.category} · ${tx.merchant.ifBlank { "日常消费" }}",
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
                        text = if (selectedIds.isEmpty()) "请选择账单" else "确认报销 ${selectedIds.size} 笔 · ${formatMoneyCents(selectedTotalCents)}",
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
