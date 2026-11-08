package com.moji.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.BuildConfig
import com.moji.ai.ai.AiConfig
import com.moji.ai.ai.AiProviderType
import com.moji.ai.ai.MerchantMemoryEntry
import com.moji.ai.ai.SmartConditionRule
import com.moji.ai.data.backup.BackupAndCsvTransferEngine
import com.moji.ai.data.backup.CsvParsePreviewResult
import com.moji.ai.data.backup.WebDavConfig
import com.moji.ai.data.backup.WebDavSyncClient
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.TransactionType
import com.moji.ai.service.CaptureEventOutcome
import com.moji.ai.service.CaptureObservabilityLogEntry
import com.moji.ai.service.PaymentAccessibilityService
import com.moji.ai.service.RomKeepAliveGuideHelper
import com.moji.ai.update.AppUpdateConfig
import com.moji.ai.update.ReleaseUpdateInfo
import com.moji.ai.update.UpdateCheckState
import com.moji.ai.ui.components.ICostCalculatorSheet
import com.moji.ai.ui.components.MonthlyCalendarSheet
import com.moji.ai.ui.components.TransactionDetailSheet
import com.moji.ai.ui.screens.CategoryManagementScreen
import com.moji.ai.ui.screens.ICostAssetsScreen
import com.moji.ai.ui.screens.ICostLedgerHomeScreen
import com.moji.ai.ui.screens.ICostStatisticsScreen
import com.moji.ai.ui.screens.TagManagementScreen
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
import com.moji.ai.ui.theme.ICostFormCard
import com.moji.ai.ui.theme.ICostFormField
import com.moji.ai.ui.theme.ICostGreenIncome
import com.moji.ai.ui.theme.ICostSheetHeader
import com.moji.ai.ui.theme.ICostSmoothBottomSheet
import com.moji.ai.ui.theme.ICostTextMuted
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.formatMoneyCents
import com.moji.ai.ui.theme.iCostHorizontalSwipeTrigger
import com.moji.ai.ui.theme.iCostPressScale
import com.moji.ai.ui.theme.preloadAllICostAssets
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest

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
    val appUpdateConfig by viewModel.appUpdateConfig.collectAsState()
    val appUpdateState by viewModel.appUpdateState.collectAsState()
    val pendingOpenTxId by viewModel.pendingOpenTransactionId.collectAsState()
    val expenseCategories by viewModel.expenseCategories.collectAsState()
    val incomeCategories by viewModel.incomeCategories.collectAsState()
    val tags by viewModel.tags.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0: 账本, 1: 资产, 2: 统计
    // 每个 Tab 独立保存 rememberSaveable 状态（搜索词、折叠状态、统计周期等），切换 Tab 回来不丢失
    val tabStateHolder = rememberSaveableStateHolder()

    val nowCal = remember { Calendar.getInstance() }
    var selectedYear by rememberSaveable { mutableIntStateOf(nowCal.get(Calendar.YEAR)) }
    var selectedMonth by rememberSaveable { mutableIntStateOf(nowCal.get(Calendar.MONTH) + 1) }

    // 全局操作反馈：删除、一键记账、退款等完成后弹出 Snackbar，可撤销的操作附带「撤销」按钮
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        // collectLatest：新消息到来时取消正在显示的旧提示，立即展示新的（含其「撤销」按钮），不排队等待
        viewModel.uiMessages.collectLatest { msg ->
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = msg.text,
                actionLabel = if (msg.onUndo != null) "撤销" else null,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                msg.onUndo?.invoke()
            }
        }
    }

    // 记账计算器弹窗状态
    var showCalculatorSheet by remember { mutableStateOf(false) }
    var editingTx by remember { mutableStateOf<TransactionEntity?>(null) }
    var calcInitialType by remember { mutableStateOf(TransactionType.EXPENSE) }
    var calcInitialSourceWalletId by remember { mutableStateOf<Long?>(null) }
    var calcInitialTargetWalletId by remember { mutableStateOf<Long?>(null) }

    // 全屏二级页面状态：账单详情、收支日历、设置页、智能记账页、分类管理页、标签管理页、资产子页面
    var inspectingTx by remember { mutableStateOf<TransactionEntity?>(null) }
    var calendarMonthTxs by remember { mutableStateOf<List<TransactionEntity>?>(null) }
    var showSettingsPage by remember { mutableStateOf(false) }
    var showSmartBookkeepingPage by remember { mutableStateOf(false) }
    var showCategoryManagementPage by remember { mutableStateOf(false) }
    var categoryManagementInitialExpense by remember { mutableStateOf(true) }
    var showTagManagementPage by remember { mutableStateOf(false) }
    var isAssetSubpageOpen by remember { mutableStateOf(false) }

    val hasFullScreenOverlay = inspectingTx != null ||
        calendarMonthTxs != null ||
        showSettingsPage ||
        showSmartBookkeepingPage ||
        showCategoryManagementPage ||
        showTagManagementPage ||
        isAssetSubpageOpen

    // AI 沙盒与模型配置弹窗状态
    var showAiSandboxSheet by remember { mutableStateOf(false) }
    var showAiSettingsSheet by remember { mutableStateOf(false) }
    var showBudgetDialog by remember { mutableStateOf(false) }
    var showBackupTransferSheet by remember { mutableStateOf(false) }
    var backupInitialTab by remember { mutableIntStateOf(0) } // 0: 导入CSV, 1: 导出CSV, 2: 全量备份恢复, 3: WebDAV云同步
    var showKeepAliveGuideSheet by remember { mutableStateOf(false) }
    var showBatchReimburseSheet by remember { mutableStateOf(false) }
    var showAppUpdateSheet by remember { mutableStateOf(false) }

    // 启动后台自动检查若发现新版本，自动弹出更新面板提示用户
    LaunchedEffect(appUpdateState) {
        if (appUpdateState is UpdateCheckState.UpdateAvailable) {
            showAppUpdateSheet = true
        }
    }

    // 缓存最后一次打开的账单详情数据，确保退出动画期间内容不闪空（日历直接读取实时账单列表）
    val activeTx = inspectingTx?.let { target -> transactions.firstOrNull { it.id == target.id } ?: target }
    var cachedInspectingTx by remember { mutableStateOf<TransactionEntity?>(null) }
    if (activeTx != null) {
        cachedInspectingTx = activeTx
    }

    // 响应点击顶部流体云胶囊或通知栏：直接定位并打开当前记录的账单详情页
    LaunchedEffect(pendingOpenTxId, transactions) {
        val targetId = pendingOpenTxId ?: return@LaunchedEffect
        val matchedTx = transactions.firstOrNull { it.id == targetId }
            ?: viewModel.getTransactionByIdDirect(targetId)
        if (matchedTx != null) {
            selectedTab = 0
            showSettingsPage = false
            showSmartBookkeepingPage = false
            showCategoryManagementPage = false
            showTagManagementPage = false
            calendarMonthTxs = null
            showCalculatorSheet = false
            editingTx = null
            if (matchedTx.ledgerName.isNotBlank() && matchedTx.ledgerName != currentLedger) {
                viewModel.selectLedger(matchedTx.ledgerName)
            }
            val txCal = Calendar.getInstance().apply { timeInMillis = matchedTx.timestamp }
            selectedYear = txCal.get(Calendar.YEAR)
            selectedMonth = txCal.get(Calendar.MONTH) + 1
            inspectingTx = matchedTx
            viewModel.consumePendingOpenTransactionDetail()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ICostBgGray)
            .statusBarsPadding()
    ) {
        // 120Hz 零重组常驻三栏容器：不在切换时销毁/重建 Compose 节点树，彻底消除“账本 / 资产 / 统计”切换卡顿
        val tabTransitionPos = remember { Animatable(selectedTab.toFloat()) }
        LaunchedEffect(selectedTab) {
            tabTransitionPos.animateTo(
                targetValue = selectedTab.toFloat(),
                animationSpec = spring(
                    dampingRatio = 0.88f,
                    stiffness = 620f
                )
            )
        }

        @Composable
        fun KeepAliveMainTabLayer(
            tabIndex: Int,
            content: @Composable () -> Unit
        ) {
            val isVisibleOrAnimating by remember(tabIndex) {
                derivedStateOf {
                    abs(tabTransitionPos.value - tabIndex.toFloat()) < 0.995f
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset {
                        val delta = tabIndex.toFloat() - tabTransitionPos.value
                        if (abs(delta) >= 0.995f) {
                            IntOffset(100_000, 0)
                        } else {
                            val slidePx = (delta * 26.dp.toPx()).roundToInt()
                            IntOffset(slidePx, 0)
                        }
                    }
                    .graphicsLayer {
                        val dist = abs(tabTransitionPos.value - tabIndex.toFloat())
                        alpha = (1f - dist * 1.15f).coerceIn(0f, 1f)
                    }
            ) {
                if (isVisibleOrAnimating || true) {
                    tabStateHolder.SaveableStateProvider(tabIndex) {
                        content()
                    }
                }
            }
        }

        KeepAliveMainTabLayer(tabIndex = 0) {
            ICostLedgerHomeScreen(
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
        }

        KeepAliveMainTabLayer(tabIndex = 1) {
            ICostAssetsScreen(
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
                    onSubpageActiveChange = { isAssetSubpageOpen = it },
                    onAddSavingPlan = { title, targetYuan, savedYuan, deadline ->
                        viewModel.addSavingPlan(title, targetYuan, savedYuan, deadline)
                    },
                    onDeleteSavingPlan = { viewModel.deleteSavingPlan(it) },
                    onAddInstallmentPlan = { title, walletId, totalYuan, feeYuan, periods ->
                        viewModel.addInstallmentPlan(title, walletId, totalYuan, feeYuan, periods)
                    },
                    onDeleteInstallmentPlan = { viewModel.deleteInstallmentPlan(it) },
                    onAddRecurringRule = { title, amountYuan, currency, category, walletId, frequency ->
                        viewModel.addRecurringRule(title, amountYuan, currency, category, walletId, frequency)
                    }
            )
        }

        KeepAliveMainTabLayer(tabIndex = 2) {
            ICostStatisticsScreen(
                    transactions = transactions,
                    wallets = wallets,
                    initialYear = selectedYear,
                    initialMonth = selectedMonth,
                    currentLedger = currentLedger,
                    hideAmounts = hideAssetAmounts,
                    onOpenSettingsPage = { showSettingsPage = true },
                    onSelectTransaction = { tx -> inspectingTx = tx }
            )
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
                allTransactions = transactions,
                currentLedger = currentLedger,
                onDismiss = { calendarMonthTxs = null },
                onSelectTransaction = { tx ->
                    inspectingTx = tx
                }
            )
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
                updateState = appUpdateState,
                updateConfig = appUpdateConfig,
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
                },
                onOpenAppUpdate = {
                    showAppUpdateSheet = true
                },
                expenseCategoryCount = expenseCategories.size,
                incomeCategoryCount = incomeCategories.size,
                onOpenCategoryManagement = {
                    categoryManagementInitialExpense = true
                    showCategoryManagementPage = true
                },
                tagCount = tags.size,
                onOpenTagManagement = {
                    showTagManagementPage = true
                }
            )
        }

        // 全屏「分类管理」页面（对齐 setting_category.webp）—— 二级侧滑推入转场
        AnimatedVisibility(
            visible = showCategoryManagementPage,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(280, easing = FastOutSlowInEasing)
            ) + fadeIn(tween(200)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(240, easing = FastOutSlowInEasing)
            ) + fadeOut(tween(180))
        ) {
            CategoryManagementScreen(
                expenseCategories = expenseCategories,
                incomeCategories = incomeCategories,
                initialIsExpense = categoryManagementInitialExpense,
                onBack = { showCategoryManagementPage = false },
                onAddPrimaryCategory = { isExp, name, asset, onDone ->
                    viewModel.addPrimaryCategory(isExp, name, asset, onDone)
                },
                onUpdatePrimaryCategory = { isExp, oldName, newName, asset, onDone ->
                    viewModel.updatePrimaryCategory(isExp, oldName, newName, asset, onDone)
                },
                onMovePrimaryCategory = { isExp, idx, delta ->
                    viewModel.movePrimaryCategory(isExp, idx, delta)
                },
                onDeletePrimaryCategory = { isExp, name, migrateTo, onDone ->
                    viewModel.deletePrimaryCategoryAndMigrate(isExp, name, migrateTo, onDone)
                },
                onAddSubCategory = { isExp, parent, sub, asset, onDone ->
                    viewModel.addSubCategory(isExp, parent, sub, asset, onDone)
                },
                onUpdateSubCategory = { isExp, parent, oldSub, newSub, asset, onDone ->
                    viewModel.updateSubCategory(isExp, parent, oldSub, newSub, asset, onDone)
                },
                onMoveSubCategory = { isExp, parent, idx, delta ->
                    viewModel.moveSubCategory(isExp, parent, idx, delta)
                },
                onDeleteSubCategory = { isExp, parent, sub, migrateTo, onDone ->
                    viewModel.deleteSubCategoryAndMigrate(isExp, parent, sub, migrateTo, onDone)
                },
                onRestoreDefaults = { isExp, mergeOnly ->
                    viewModel.restoreDefaultCategories(isExp, mergeOnly)
                },
                onCountCategoryTransactions = { cat, includeSub, isExp ->
                    viewModel.countTransactionsByCategory(cat, includeSub, isExp)
                }
            )
        }

        // 全屏「标签管理」页面（对齐 setting_tag.webp）—— 二级侧滑推入转场
        AnimatedVisibility(
            visible = showTagManagementPage,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(280, easing = FastOutSlowInEasing)
            ) + fadeIn(tween(200)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(240, easing = FastOutSlowInEasing)
            ) + fadeOut(tween(180))
        ) {
            TagManagementScreen(
                tags = tags,
                transactions = transactions,
                wallets = wallets,
                onBack = { showTagManagementPage = false },
                onAddTag = { name, colorHex, groupName, onDone ->
                    viewModel.addTag(name, colorHex, groupName, onDone)
                },
                onUpdateTag = { oldName, newName, colorHex, groupName, onDone ->
                    viewModel.updateTag(oldName, newName, colorHex, groupName, onDone)
                },
                onMoveTag = { index, delta ->
                    viewModel.moveTag(index, delta)
                },
                onMergeTag = { sourceName, targetName, onDone ->
                    viewModel.mergeTag(sourceName, targetName, onDone)
                },
                onDeleteTag = { tagName, cascadeRemoveFromHistory, replacementTagName, onDone ->
                    viewModel.deleteTag(tagName, cascadeRemoveFromHistory, replacementTagName, onDone)
                },
                onRestoreDefaults = { mergeOnly ->
                    viewModel.restoreDefaultTags(mergeOnly)
                },
                onCountTagTransactions = { tagName ->
                    viewModel.countTransactionsByTag(tagName)
                },
                onInspectTransaction = { tx ->
                    inspectingTx = tx
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

        // 全屏账单详情页面（05_detail_full.png）—— 位于二级页面之上，支持从标签管理/统计/日历/首页直接下钻查看
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
                        showCategoryManagementPage = false
                        showTagManagementPage = false
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
                    },
                    onDuplicate = { tx -> viewModel.duplicateTransactionNow(tx) }
                )
            }
        }

        // 1:1 iCost 分类+计算器记账面板（04_add_record_sheet.png，后台常驻 keepWarm 预热，点击「+」0ms 首帧 120Hz 上滑）
        if (wallets.isNotEmpty()) {
            ICostCalculatorSheet(
                wallets = wallets,
                currentLedger = currentLedger,
                ledgers = ledgers,
                onSelectLedger = { viewModel.selectLedger(it) },
                visible = showCalculatorSheet,
                suspendedBySubpage = showCategoryManagementPage || showTagManagementPage,
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
                onOpenAiSandbox = { showAiSandboxSheet = true },
                onOpenCategoryManagement = { isExpense ->
                    categoryManagementInitialExpense = isExpense
                    showCategoryManagementPage = true
                },
                onOpenTagManagement = {
                    showTagManagementPage = true
                }
            )
        }
        // AI 静默识账沙盒测试面板
        if (showAiSandboxSheet) {
            ICostSmoothBottomSheet(
                onDismissRequest = { showAiSandboxSheet = false },
                containerColor = ICostBgGray,
                showDragHandle = false
            ) { requestDismiss ->
                AiSandboxSheetContent(
                    isParsing = isParsing,
                    statusMessage = sandboxStatus,
                    observabilityLogs = observabilityLogs,
                    merchantMemories = merchantMemories,
                    onRunSimulation = { text, pkg -> viewModel.simulateCapturedEvent(text, pkg) },
                    onClearLogs = { viewModel.clearObservabilityLogs() },
                    onClose = requestDismiss
                )
            }
        }

        // 账单 CSV 导入导出与全量快照备份恢复面板
        if (showBackupTransferSheet) {
            ICostSmoothBottomSheet(
                onDismissRequest = { showBackupTransferSheet = false },
                containerColor = ICostBgGray,
                showDragHandle = false
            ) { requestDismiss ->
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
                    },
                    onClose = requestDismiss
                )
            }
        }

        // 国产 ROM 保活与 Android 13+ 受限设置一站式诊断向导面板
        if (showKeepAliveGuideSheet) {
            ICostSmoothBottomSheet(
                onDismissRequest = { showKeepAliveGuideSheet = false },
                containerColor = ICostBgGray,
                showDragHandle = false
            ) { requestDismiss ->
                RomKeepAliveGuideSheet(onClose = requestDismiss)
            }
        }

        // 批量报销核销中心面板
        if (showBatchReimburseSheet) {
            ICostSmoothBottomSheet(
                onDismissRequest = { showBatchReimburseSheet = false },
                containerColor = ICostBgGray,
                showDragHandle = false
            ) { requestDismiss ->
                BatchReimbursementSheet(
                    transactions = transactions,
                    wallets = wallets,
                    onBatchSettle = { ids, recvWalletId, onDone ->
                        viewModel.batchSettleReimbursements(ids, recvWalletId, onDone)
                    },
                    onClose = requestDismiss
                )
            }
        }

        // AI 大模型与系统权限配置面板
        if (showAiSettingsSheet) {
            ICostSmoothBottomSheet(
                onDismissRequest = { showAiSettingsSheet = false },
                containerColor = ICostBgGray,
                showDragHandle = false
            ) { requestDismiss ->
                AiAndPermissionsSheetContent(
                    config = aiConfig,
                    onSaveConfig = { viewModel.updateAiConfig(it) },
                    onClose = requestDismiss
                )
            }
        }

        // 版本与私有仓库 OTA 自动更新面板
        if (showAppUpdateSheet) {
            ICostSmoothBottomSheet(
                onDismissRequest = { showAppUpdateSheet = false },
                containerColor = ICostBgGray,
                showDragHandle = false
            ) { requestDismiss ->
                AppUpdateSheetContent(
                    config = appUpdateConfig,
                    state = appUpdateState,
                    onSaveConfig = { cfg -> viewModel.updateAppUpdateConfig(cfg) },
                    onCheckNow = { viewModel.checkForAppUpdate(isManual = true) },
                    onDownloadAndInstall = { rel -> viewModel.downloadAndInstallAppUpdate(appContext, rel) },
                    onTriggerInstall = { path -> viewModel.triggerInstallDownloadedApk(appContext, path) },
                    onIgnoreTag = { tag -> viewModel.ignoreAppUpdateTag(tag) },
                    onClose = requestDismiss
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
            mutableStateOf(
                if (monthlyBudgetCents > 0) {
                    String.format(Locale.US, "%.2f", monthlyBudgetCents / 100.0).trimEnd('0').trimEnd('.')
                } else ""
            )
        }
        val parsedBudget = budgetInput.toDoubleOrNull()
        val budgetError = budgetInput.isNotBlank() && (parsedBudget == null || parsedBudget < 0)
        AlertDialog(
            onDismissRequest = { showBudgetDialog = false },
            containerColor = ICostCardWhite,
            title = { Text("月度预算", fontWeight = FontWeight.ExtraBold) },
            text = {
                ICostFormField(
                    value = budgetInput,
                    onValueChange = { input -> budgetInput = sanitizeAmountInput(input) },
                    label = "每月预算（元）",
                    placeholder = "留空表示不设预算",
                    singleLine = true,
                    isError = budgetError,
                    supportingText = if (budgetError) "请输入有效金额" else "留空或填 0 表示不设预算",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
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
        val pillIndicatorPos by animateFloatAsState(
            targetValue = selectedTab.toFloat(),
            animationSpec = spring(dampingRatio = 0.86f, stiffness = 640f),
            label = "BottomBarPillPos"
        )
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
                    .padding(horizontal = 5.dp, vertical = 4.dp)
                    .drawBehind {
                        val slotWidth = size.width / 3f
                        val pillX = slotWidth * pillIndicatorPos.coerceIn(0f, 2f)
                        val r = 25.dp.toPx()
                        drawRoundRect(
                            color = Color(0xFFE9E9EC),
                            topLeft = androidx.compose.ui.geometry.Offset(pillX, 0f),
                            size = androidx.compose.ui.geometry.Size(slotWidth, size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
                        )
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                val tabs = remember {
                    listOf(
                        Triple(0, "账本", "tab_record.webp"),
                        Triple(1, "资产", "tab_asset.webp"),
                        Triple(2, "统计", "tab_statistics.webp")
                    )
                }
                tabs.forEach { (index, label, assetFile) ->
                    val isSelected = selectedTab == index
                    val contentColor = if (isSelected) ICostBlueAccent else ICostTextPrimary
                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.08f else 1.0f,
                        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
                        label = "BottomTabIconScale"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(25.dp))
                            .iCostPressScale(pressedScale = 0.92f) {
                                if (selectedTab != index) onSelectTab(index)
                            },
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
private fun SettingsSectionTitle(text: String, modifier: Modifier = Modifier, bottomPadding: Dp = 8.dp) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = ICostTextSecondary,
        modifier = modifier.padding(start = 6.dp, bottom = bottomPadding)
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
    updateState: UpdateCheckState = UpdateCheckState.Idle,
    updateConfig: AppUpdateConfig = AppUpdateConfig(),
    onBack: () -> Unit,
    onOpenSmartBookkeeping: () -> Unit,
    onOpenBudgetDialog: () -> Unit,
    onOpenCsvImport: () -> Unit = {},
    onOpenCsvExport: () -> Unit = {},
    onOpenWebDavSync: () -> Unit = {},
    onOpenBatchReimburse: () -> Unit = {},
    onOpenAppUpdate: () -> Unit = {},
    expenseCategoryCount: Int = 24,
    incomeCategoryCount: Int = 10,
    onOpenCategoryManagement: () -> Unit = {},
    tagCount: Int = 8,
    onOpenTagManagement: () -> Unit = {}
) {
    BackHandler(onBack = onBack)

    val updateRowTrailing = when (updateState) {
        is UpdateCheckState.UpdateAvailable -> "发现新版本 ${updateState.release.tagName}"
        is UpdateCheckState.Downloading -> "正在下载 ${updateState.progressPercent}%"
        is UpdateCheckState.ReadyToInstall -> "点击安装 ${updateState.release.tagName}"
        is UpdateCheckState.Checking -> "正在检查…"
        is UpdateCheckState.UpToDate -> "已是最新 v${BuildConfig.VERSION_NAME}"
        else -> if (updateConfig.hasTokenConfigured) {
            "v${BuildConfig.VERSION_NAME} · 私有源"
        } else {
            "v${BuildConfig.VERSION_NAME} · 检查更新"
        }
    }

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
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenAppUpdate)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        com.moji.ai.ui.theme.MoJiBrandIconBadge(onClick = onOpenAppUpdate, size = 48.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (BuildConfig.IS_QA_BUILD) {
                                    "默记 QA · v${BuildConfig.VERSION_NAME}"
                                } else {
                                    "MoJi 默记 · v${BuildConfig.VERSION_NAME}"
                                },
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
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (updateState is UpdateCheckState.UpdateAvailable || updateState is UpdateCheckState.ReadyToInstall) {
                                ICostCoralRed.copy(alpha = 0.12f)
                            } else {
                                ICostBlueAccent.copy(alpha = 0.10f)
                            }
                        ) {
                            Text(
                                text = if (updateState is UpdateCheckState.UpdateAvailable) {
                                    "新版本 ${updateState.release.tagName}"
                                } else {
                                    "检查更新"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (updateState is UpdateCheckState.UpdateAvailable || updateState is UpdateCheckState.ReadyToInstall) {
                                    ICostCoralRed
                                } else {
                                    ICostBlueAccent
                                },
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
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
                SettingsSectionTitle("数据与更新")
                SettingsGroupCard(
                    rows = listOf(
                        SettingRowDef("setting_import.webp", "数据导入", "iCost / 微信 / 支付宝", onClick = onOpenCsvImport),
                        SettingRowDef("setting_export.webp", "数据导出", "CSV", onClick = onOpenCsvExport),
                        SettingRowDef("setting_backup.webp", "备份与云同步", "快照 / WebDAV", onClick = onOpenWebDavSync),
                        SettingRowDef("setting_vip.webp", "版本与自动更新", updateRowTrailing, onClick = onOpenAppUpdate)
                    )
                )
            }

            item {
                SettingsSectionTitle("更多功能")
                SettingsGroupCard(
                    rows = listOf(
                        SettingRowDef(
                            "setting_category.webp",
                            "分类管理",
                            "${expenseCategoryCount + incomeCategoryCount} 个分类",
                            onClick = onOpenCategoryManagement
                        ),
                        SettingRowDef(
                            "setting_tag.webp",
                            "标签管理",
                            "$tagCount 个标签",
                            onClick = onOpenTagManagement
                        ),
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
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    val trailing = if (isComingSoon) "敬请期待" else row.trailingText
                    if (trailing.isNotBlank()) {
                        Text(
                            text = trailing,
                            fontSize = 13.sp,
                            maxLines = 1,
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
                                !aiConfig.enableAccessibilityCapture -> "读取微信 / 支付宝付款完成页与账单详情页，需无障碍权限"
                                isAccessGranted -> "运行中 · 支持微信/支付宝账单详情页与免Root截屏OCR兜底"
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
                            "打开微信/支付宝「账单详情」或完成支付时，自动记账并弹出顶部胶囊提示",
                            "遇到自绘页面自动触发 Android 11+ 免 Root 截屏 + 端侧中文 OCR 兜底（也可添加控制中心「默记识屏记账」磁贴手动触发）",
                            "按商户与商品说明自动匹配分类，按支付方式/卡号尾号匹配账户，30 秒内跨通道证据自动合并"
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsSectionTitle(
                        text = if (smartRules.isEmpty()) "分类规则" else "分类规则 · ${smartRules.count { it.enabled }}/${smartRules.size} 启用",
                        modifier = Modifier.weight(1f),
                        bottomPadding = 0.dp
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
                                    Text(
                                        text = "删除",
                                        fontSize = 13.sp,
                                        color = ICostCoralRed,
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { pendingDeleteRule = rule }
                                            .padding(vertical = 8.dp, horizontal = 2.dp)
                                    )
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsSectionTitle(
                        text = if (observabilityLogs.isEmpty()) "识别日志" else "识别日志 · ${observabilityLogs.size} 条",
                        modifier = Modifier.weight(1f),
                        bottomPadding = 0.dp
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
                                    CaptureEventOutcome.DUPLICATE_SUSPECTED -> ICostCoralRed
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
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ICostFormField(
                        value = ruleName,
                        onValueChange = { ruleName = it },
                        label = "规则名称",
                        placeholder = "如：园区晚餐报销",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("当满足（至少填一项）", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ICostTextSecondary)
                    ICostFormField(
                        value = keywordStr,
                        onValueChange = { keywordStr = it },
                        label = "包含关键词",
                        placeholder = "多个用逗号分隔，如：美团,饿了么",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ICostFormField(
                            value = minYuanStr,
                            onValueChange = { minYuanStr = it },
                            label = "最低金额",
                            placeholder = "0.00",
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true
                        )
                        ICostFormField(
                            value = maxYuanStr,
                            onValueChange = { maxYuanStr = it },
                            label = "最高金额",
                            placeholder = "不限",
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true
                        )
                    }
                    if (amountError != null) {
                        Text(amountError, fontSize = 12.sp, color = ICostCoralRed)
                    }
                    Text("则执行", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ICostTextSecondary)
                    ICostFormField(
                        value = targetCat,
                        onValueChange = { targetCat = it },
                        label = "归入分类",
                        placeholder = "如：餐饮-三餐",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ICostFormField(
                        value = tagStr,
                        onValueChange = { tagStr = it },
                        label = "附加标签（选填）",
                        placeholder = "如：固定开支",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
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
    onClearLogs: () -> Unit = {},
    onClose: () -> Unit = {}
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        ICostSheetHeader(
            title = "识账测试",
            subtitle = "模拟支付通知或账单文本自动入账",
            onClose = onClose
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            item {
                ICostFormCard {
                    Text(
                        "粘贴一条支付通知或账单文本，看看会被识别成什么。识别成功会真实记入当前账本，可在首页左滑删除。",
                        fontSize = 12.sp,
                        color = ICostTextSecondary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presetScenarios.forEach { (label, sampleText) ->
                            FilterChip(
                                selected = customInput == sampleText,
                                onClick = { customInput = sampleText },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }
                    ICostFormField(
                        value = customInput,
                        onValueChange = { customInput = it },
                        label = "通知或账单文本",
                        placeholder = "粘贴微信/支付宝通知或账单文本",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 3,
                        maxLines = 5
                    )
                }
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
                        Text("识别并记账", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }

            if (!statusMessage.isNullOrBlank()) {
                item {
                    Surface(
                        color = ICostCardWhite,
                        shape = RoundedCornerShape(16.dp),
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
                    ICostFormCard {
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
    onSaveConfig: (AiConfig) -> Unit,
    onClose: () -> Unit = {}
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        ICostSheetHeader(
            title = "大模型与权限",
            subtitle = "不填 API Key 默认使用内置本地识别引擎",
            onClose = onClose
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                ICostFormCard {
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

                    HorizontalDivider(color = ICostDivider)

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

            item {
                ICostFormCard {
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
                    ICostFormField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = "接口地址",
                        placeholder = "https://api.deepseek.com/v1",
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true
                    )
                    ICostFormField(
                        value = modelName,
                        onValueChange = { modelName = it },
                        label = "模型名称",
                        placeholder = "如 deepseek-chat / gemini-2.5-flash",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    ICostFormField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = "API Key（选填）",
                        placeholder = "sk-...",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingContent = {
                            Text(
                                text = if (showApiKey) "隐藏" else "显示",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostBlueAccent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showApiKey = !showApiKey }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    )
                    HorizontalDivider(color = ICostDivider)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("本地引擎兜底", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("断网或接口出错时改用本地识别", fontSize = 11.sp, color = ICostTextSecondary)
                        }
                        Switch(checked = enableFallback, onCheckedChange = { enableFallback = it }, colors = iCostSwitchColors())
                    }
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
                        onClose()
                    },
                    enabled = hasUnsavedChanges,
                    colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(if (hasUnsavedChanges) "保存" else "已保存", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    return flat.contains(context.packageName)
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    return RomKeepAliveGuideHelper.checkAccessibilityServiceEnabled(context)
}

private fun readUriTextWithAutoCharset(context: Context, uri: Uri): String? {
    return runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        BackupAndCsvTransferEngine.extractTextOrXlsxToCsv(bytes)
    }.getOrNull()
}

/**
 * 账单 Excel(.xlsx) / CSV 多源导入导出 + 全量快照备份恢复面板（支持 iCost 账本 + 资产账户 + 账单全量迁移）
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
    onRestoreWebDav: (WebDavConfig, (String) -> Unit) -> Unit = { _, _ -> },
    onClose: () -> Unit = {}
) {
    val context = LocalContext.current
    var currentTab by remember(initialTab) { mutableIntStateOf(initialTab) }
    var csvText by remember { mutableStateOf("") }
    var showDavPassword by remember { mutableStateOf(false) }
    var pendingRestoreKind by remember { mutableIntStateOf(0) }
    var previewResult by remember { mutableStateOf<CsvParsePreviewResult?>(null) }
    var applyImportToWalletBalance by remember { mutableStateOf(false) }
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

    val csvFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val loaded = readUriTextWithAutoCharset(context, uri)
            if (!loaded.isNullOrBlank()) {
                csvText = loaded
                val parsed = onPreviewCsv(loaded)
                previewResult = parsed
                localBanner = "已解析文件：${parsed.detectedSourceFormat}（共 ${parsed.parsedTransactions.size} 笔流水，新建 ${parsed.newWalletsToCreate.size} 个账户）"
            } else {
                localBanner = "文件无法解析，请选择有效的 iCost Excel(.xlsx) 或 CSV 文件"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        ICostSheetHeader(
            title = "导入、导出与备份",
            subtitle = "支持 iCost Excel(.xlsx) / CSV 全量迁移",
            onClose = onClose
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val tabs = listOf(
                        0 to "批量导入",
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
                    ICostFormCard {
                        Text(
                            text = "支持直接选择 iCost 导出的 Excel (.xlsx) 或微信、支付宝、钱迹 CSV 文件。自动提取并创建「多账本」与「资产/信用账户」，重复流水自动跳过。",
                            fontSize = 12.sp,
                            color = ICostTextSecondary
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AssistChip(
                                onClick = {
                                    csvFilePickerLauncher.launch(
                                        arrayOf(
                                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                            "application/vnd.ms-excel",
                                            "text/*",
                                            "application/csv",
                                            "*/*"
                                        )
                                    )
                                },
                                label = { Text("选择 Excel / CSV 文件", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
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
                                label = { Text("从剪贴板粘贴", fontSize = 12.sp) }
                            )
                            AssistChip(
                                onClick = {
                                    csvText = BackupAndCsvTransferEngine.SAMPLE_ICOST_FULL_CSV
                                    previewResult = onPreviewCsv(csvText)
                                    localBanner = "已载入 iCost「多账本 + 资产账户 + 账单」全量迁移示例"
                                },
                                label = { Text("iCost 账本+资产示例", fontSize = 12.sp) }
                            )
                            AssistChip(
                                onClick = {
                                    csvText = BackupAndCsvTransferEngine.SAMPLE_WECHAT_CSV
                                    previewResult = onPreviewCsv(csvText)
                                    localBanner = "已载入微信支付官方 CSV 示例"
                                },
                                label = { Text("微信账单示例", fontSize = 12.sp) }
                            )
                        }

                        val displayCsvPreview = remember(csvText) {
                            val lines = csvText.lines()
                            if (lines.size > 25) {
                                lines.take(25).joinToString("\n") + "\n...（共 ${lines.size} 行，完整内容已载入内存）"
                            } else {
                                csvText
                            }
                        }

                        ICostFormField(
                            value = displayCsvPreview,
                            onValueChange = {
                                csvText = it
                                previewResult = null
                            },
                            label = "表格内容（支持 .xlsx / CSV）",
                            placeholder = "点击上方「选择 Excel / CSV 文件」或粘贴 CSV 文本",
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            minLines = 4,
                            maxLines = 7
                        )

                        HorizontalDivider(color = ICostDivider)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    "历史流水联动增减当前账户余额",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = ICostTextPrimary
                                )
                                Text(
                                    "导入历史账单建议关闭（保持现有真实余额不变，仅新建缺失账户与导入流水）",
                                    fontSize = 11.sp,
                                    color = ICostTextSecondary
                                )
                            }
                            Switch(
                                checked = applyImportToWalletBalance,
                                onCheckedChange = { applyImportToWalletBalance = it },
                                colors = iCostSwitchColors()
                            )
                        }
                    }
                }

                item {
                    val canCommit = previewResult?.let {
                        it.parsedTransactions.isNotEmpty() ||
                            it.newWalletsToCreate.isNotEmpty() ||
                            it.existingWalletsToUpdate.isNotEmpty()
                    } == true
                    val commitLabel = previewResult?.let { p ->
                        val assetTotal = p.newWalletsToCreate.size + p.existingWalletsToUpdate.size
                        when {
                            assetTotal > 0 && p.parsedTransactions.isNotEmpty() ->
                                "导入 ${assetTotal}账户 + ${p.parsedTransactions.size}笔"
                            assetTotal > 0 -> "导入 ${assetTotal} 个账户"
                            p.parsedTransactions.isNotEmpty() -> "导入 ${p.parsedTransactions.size} 笔"
                            else -> "导入"
                        }
                    } ?: "导入"

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { previewResult = onPreviewCsv(csvText) },
                            enabled = csvText.isNotBlank(),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text("解析预览", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                onCommitCsvImport(csvText, applyImportToWalletBalance) { msg ->
                                    localBanner = msg
                                    previewResult = onPreviewCsv(csvText)
                                }
                            },
                            enabled = canCommit,
                            colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1.3f)
                                .height(48.dp)
                        ) {
                            Text(commitLabel, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                previewResult?.let { preview ->
                    item {
                        ICostFormCard {
                            Text(
                                text = "识别为「${preview.detectedSourceFormat}」" +
                                    if (preview.skippedNonFinancialRows > 0) "（跳过 ${preview.skippedNonFinancialRows} 行无关内容）" else "",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = ICostBlueAccent
                            )

                            if (preview.discoveredLedgers.isNotEmpty()) {
                                HorizontalDivider(color = ICostDivider)
                                Text(
                                    text = "包含账本（${preview.discoveredLedgers.size} 个" +
                                        (if (preview.newLedgers.isNotEmpty()) "，将自动新建 ${preview.newLedgers.size} 个" else "") + "）",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ICostTextSecondary
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    preview.discoveredLedgers.forEach { ledger ->
                                        val isNew = ledger in preview.newLedgers
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isNew) ICostBlueAccent.copy(alpha = 0.12f) else ICostBgGray
                                        ) {
                                            Text(
                                                text = if (isNew) "＋ 新建：$ledger" else "✓ 已有：$ledger",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isNew) ICostBlueAccent else ICostTextPrimary,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            val allAssetChanges = preview.newWalletsToCreate.map { true to it } +
                                preview.existingWalletsToUpdate.map { false to it }
                            if (allAssetChanges.isNotEmpty()) {
                                HorizontalDivider(color = ICostDivider)
                                Text(
                                    text = "资产与信用账户（新建 ${preview.newWalletsToCreate.size} 个 · 同步 ${preview.existingWalletsToUpdate.size} 个）",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ICostTextSecondary
                                )
                                allAssetChanges.forEach { (isNew, w) ->
                                    val tailBadge = w.cardLast4?.let { "($it)" }.orEmpty()
                                    val balLabel = if (w.type.isLiability) {
                                        val limitPart = w.creditLimitCents?.let { " / 额度 ${formatMoneyCents(it, w.currency)}" }.orEmpty()
                                        "待还 ${formatMoneyCents(w.balanceCents, w.currency)}$limitPart"
                                    } else {
                                        "余额 ${formatMoneyCents(w.balanceCents, w.currency)}"
                                    }
                                    Text(
                                        text = "${if (isNew) "＋ 新建" else "↻ 同步"} · ${w.name}$tailBadge [${w.subTypeLabel}] · $balLabel",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isNew) ICostBlueAccent else ICostTextPrimary
                                    )
                                }
                            }

                            if (preview.parsedTransactions.isNotEmpty()) {
                                HorizontalDivider(color = ICostDivider)
                                Text(
                                    text = "账单流水预览（共 ${preview.parsedTransactions.size} 笔）",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ICostTextSecondary
                                )
                                preview.parsedTransactions.take(6).forEach { tx ->
                                    val acctHint = tx.paymentMethodRaw.ifBlank { "默认账户" }
                                    Text(
                                        text = "[${tx.ledgerName}] ${tx.type.displayName} · ${tx.category} · ${tx.merchant} ($acctHint) · ${formatMoneyCents(tx.amountCnyCents, tx.currency)}",
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
                    ICostFormCard {
                        Text(
                            text = "导出全部账单为 CSV，可用 Excel、钱迹或 iCost 打开。",
                            fontSize = 12.sp,
                            color = ICostTextSecondary
                        )
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
                        if (exportedCsvText.isNotBlank()) {
                            ICostFormField(
                                value = exportedCsvText,
                                onValueChange = {},
                                label = "CSV 预览",
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = false,
                                minLines = 5,
                                maxLines = 9
                            )
                        }
                    }
                }
            } else if (currentTab == 2) {
                item {
                    ICostFormCard {
                        Text(
                            text = "备份包含账户、全部账单、周期账单、商户记忆、账本与预算。生成后会复制到剪贴板，请保存到安全的地方。",
                            fontSize = 12.sp,
                            color = ICostTextSecondary
                        )
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
                        ICostFormField(
                            value = backupJsonText,
                            onValueChange = { backupJsonText = it },
                            label = "备份内容",
                            placeholder = "点击上方「生成备份」或「粘贴备份」",
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            minLines = 5,
                            maxLines = 8
                        )
                    }
                }

                item {
                    Button(
                        onClick = {
                            pendingRestoreKind = 1
                        },
                        enabled = backupJsonText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = ICostCoralRed),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("从备份恢复", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            } else {
                item {
                    ICostFormCard {
                        Text(
                            text = "支持坚果云、Nextcloud、群晖、Alist 等标准 WebDAV 服务。坚果云地址示例：${WebDavSyncClient.DEFAULT_SERVER_URL}",
                            fontSize = 12.sp,
                            color = ICostTextSecondary
                        )
                        ICostFormField(
                            value = davUrl,
                            onValueChange = { davUrl = it },
                            label = "服务器地址",
                            placeholder = WebDavSyncClient.DEFAULT_SERVER_URL,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            singleLine = true
                        )
                        ICostFormField(
                            value = davUser,
                            onValueChange = { davUser = it },
                            label = "账号",
                            placeholder = "WebDAV 账号",
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        ICostFormField(
                            value = davPassword,
                            onValueChange = { davPassword = it },
                            label = "应用密码",
                            placeholder = "WebDAV 应用专用密码",
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (showDavPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingContent = {
                                Text(
                                    text = if (showDavPassword) "隐藏" else "显示",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ICostBlueAccent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { showDavPassword = !showDavPassword }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                        )
                        ICostFormField(
                            value = davFileName,
                            onValueChange = { davFileName = it },
                            label = "备份文件名",
                            placeholder = WebDavSyncClient.DEFAULT_REMOTE_FILE_NAME,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
                item {
                    val activeCfg = WebDavConfig(
                        serverUrl = davUrl.trim(),
                        username = davUser.trim(),
                        appPassword = davPassword.trim(),
                        remoteFileName = davFileName.trim().ifBlank { WebDavSyncClient.DEFAULT_REMOTE_FILE_NAME },
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
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text("上传备份", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = {
                                pendingRestoreKind = 2
                            },
                            enabled = davReady,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
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
                        color = ICostCardWhite,
                        shape = RoundedCornerShape(16.dp),
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
                                remoteFileName = davFileName.trim().ifBlank { WebDavSyncClient.DEFAULT_REMOTE_FILE_NAME },
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
private fun RomKeepAliveGuideSheet(
    onClose: () -> Unit = {}
) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    val resumeTick = rememberResumeTick()
    val diag = remember(refreshTick, resumeTick) { RomKeepAliveGuideHelper.inspectCurrentDevice(context) }
    var actionFeedback by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        ICostSheetHeader(
            title = "后台保活诊断",
            subtitle = "${diag.romDisplayName} · ${diag.passedCount}/${diag.totalCoreChecks} 项已通过",
            onClose = onClose
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                ICostFormCard {
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

            item {
                ICostFormCard {
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

            if (!actionFeedback.isNullOrBlank()) {
                item {
                    Text(text = actionFeedback!!, fontSize = 12.sp, color = ICostTextSecondary)
                }
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
    onBatchSettle: (List<Long>, Long?, (String) -> Unit) -> Unit,
    onClose: () -> Unit = {}
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        ICostSheetHeader(
            title = "批量报销",
            subtitle = if (pendingList.isEmpty()) "没有待报销的账单" else "共 ${pendingList.size} 笔待报销，已选 ${selectedIds.size} 笔 · ${formatMoneyCents(selectedTotalCents)}",
            onClose = onClose
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            if (assetWallets.isNotEmpty() && pendingList.isNotEmpty()) {
                item {
                    ICostFormCard {
                        Text("报销款存入账户", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ICostTextSecondary)
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
            }

            if (pendingList.isEmpty()) {
                item {
                    ICostFormCard {
                        Text(
                            text = "记账时打开「待报销」，或在首页左滑账单点「报销」，账单就会出现在这里。",
                            fontSize = 13.sp,
                            color = ICostTextSecondary
                        )
                    }
                }
            } else {
                item {
                    Surface(color = ICostCardWhite, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
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
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
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
}

/**
 * GitHub 私有仓库 / 公开仓库 OTA 自动检查更新与 APK 下载安装面板
 */
@Composable
private fun AppUpdateSheetContent(
    config: AppUpdateConfig,
    state: UpdateCheckState,
    onSaveConfig: (AppUpdateConfig) -> Unit,
    onCheckNow: () -> Unit,
    onDownloadAndInstall: (ReleaseUpdateInfo) -> Unit,
    onTriggerInstall: (String) -> Unit,
    onIgnoreTag: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var repoInput by remember(config.repoFullName) { mutableStateOf(config.repoFullName) }
    var tokenInput by remember(config.customGithubToken) { mutableStateOf(config.customGithubToken) }
    var autoCheck by remember(config.autoCheckOnLaunch) { mutableStateOf(config.autoCheckOnLaunch) }
    var includePre by remember(config.includePrerelease) { mutableStateOf(config.includePrerelease) }
    var showTokenPlain by remember { mutableStateOf(false) }
    var savedFeedback by remember { mutableStateOf<String?>(null) }

    val hasBuildConfigToken = remember { BuildConfig.GITHUB_UPDATE_TOKEN.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        ICostSheetHeader(
            title = "版本与自动更新",
            subtitle = "当前 v${BuildConfig.VERSION_NAME} · 支持 GitHub 私有仓库 OTA",
            onClose = onClose
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // 1. 实时状态与操作卡片
            item {
                ICostFormCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "当前已装版本：v${BuildConfig.VERSION_NAME}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostTextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "更新源：${repoInput.ifBlank { "Rowan-rh/moji-ai" }}",
                                fontSize = 12.sp,
                                color = ICostTextSecondary
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (tokenInput.isNotBlank() || hasBuildConfigToken) {
                                ICostGreenIncome.copy(alpha = 0.12f)
                            } else {
                                ICostAmberGold.copy(alpha = 0.14f)
                            }
                        ) {
                            Text(
                                text = if (tokenInput.isNotBlank() || hasBuildConfigToken) "私有鉴权已就绪" else "公开模式 / 未配Token",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (tokenInput.isNotBlank() || hasBuildConfigToken) ICostGreenIncome else ICostAmberGold,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }

                    when (state) {
                        is UpdateCheckState.Checking -> {
                            HorizontalDivider(color = ICostDivider)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.2.dp,
                                    color = ICostBlueAccent
                                )
                                Text("正在连接 GitHub 检查最新 Release…", fontSize = 13.sp, color = ICostTextSecondary)
                            }
                        }

                        is UpdateCheckState.UpToDate -> {
                            HorizontalDivider(color = ICostDivider)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = ICostGreenIncome,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = state.statusNote,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = ICostTextPrimary
                                )
                            }
                        }

                        is UpdateCheckState.UpdateAvailable -> {
                            val rel = state.release
                            val sizeMb = String.format(Locale.US, "%.2f MB", rel.apkSizeBytes / (1024.0 * 1024.0))
                            HorizontalDivider(color = ICostDivider)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "发现新版本：${rel.title}",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = ICostBlueAccent
                                    )
                                    Text(
                                        text = "${rel.publishedAt} · $sizeMb",
                                        fontSize = 12.sp,
                                        color = ICostTextSecondary
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = ICostBgGray,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = rel.releaseNotes,
                                        fontSize = 12.sp,
                                        color = ICostTextPrimary,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = { onDownloadAndInstall(rel) },
                                        colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                    ) {
                                        Text("立即下载并安装", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { onIgnoreTag(rel.tagName) },
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.height(44.dp)
                                    ) {
                                        Text("忽略此版", fontSize = 13.sp)
                                    }
                                }
                            }
                        }

                        is UpdateCheckState.Downloading -> {
                            val dlMb = String.format(Locale.US, "%.2f", state.downloadedBytes / (1024.0 * 1024.0))
                            val totalMb = String.format(Locale.US, "%.2f MB", state.totalBytes / (1024.0 * 1024.0))
                            HorizontalDivider(color = ICostDivider)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "正在下载 ${state.release.apkFileName}…",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ICostTextPrimary
                                    )
                                    Text(
                                        text = "${state.progressPercent}% ($dlMb / $totalMb)",
                                        fontSize = 12.sp,
                                        color = ICostBlueAccent,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { (state.progressPercent / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(7.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = ICostBlueAccent,
                                    trackColor = ICostBgGray
                                )
                            }
                        }

                        is UpdateCheckState.ReadyToInstall -> {
                            HorizontalDivider(color = ICostDivider)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = if (state.needsInstallPermission) {
                                        "APK 已下载完成，请先在系统设置中开启「允许安装未知应用」，返回后点击下方按钮即可覆盖安装"
                                    } else {
                                        "新版本 ${state.release.tagName} 安装包已下载并通过签名头校验"
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = ICostGreenIncome
                                )
                                Button(
                                    onClick = { onTriggerInstall(state.apkFilePath) },
                                    colors = ButtonDefaults.buttonColors(containerColor = ICostGreenIncome),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp)
                                ) {
                                    Text("调起系统安装器覆盖升级", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                            }
                        }

                        is UpdateCheckState.Error -> {
                            HorizontalDivider(color = ICostDivider)
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = ICostCoralRed,
                                    modifier = Modifier
                                        .padding(top = 1.dp)
                                        .size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = state.message,
                                    fontSize = 13.sp,
                                    color = ICostCoralRed
                                )
                            }
                        }

                        UpdateCheckState.Idle -> Unit
                    }

                    Button(
                        onClick = {
                            onSaveConfig(
                                config.copy(
                                    repoFullName = repoInput.trim(),
                                    customGithubToken = tokenInput.trim(),
                                    autoCheckOnLaunch = autoCheck,
                                    includePrerelease = includePre
                                )
                            )
                            onCheckNow()
                        },
                        enabled = state !is UpdateCheckState.Checking && state !is UpdateCheckState.Downloading,
                        colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    ) {
                        Text(
                            text = if (state is UpdateCheckState.Checking) "正在检查…" else "立即检查更新",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // 2. 私有仓库与 Token 配置卡片
            item {
                ICostFormCard {
                    Text(
                        text = "GitHub 私有仓库 OTA 配置",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextPrimary
                    )

                    ICostFormField(
                        value = repoInput,
                        onValueChange = { repoInput = it },
                        label = "GitHub 仓库（owner/repo）",
                        placeholder = "Rowan-rh/moji-ai",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    ICostFormField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = "私有仓库只读 Token（Fine-grained PAT）",
                        placeholder = if (hasBuildConfigToken) "已内置 local.properties Token，留空即生效" else "github_pat_xxx（仅需 Contents: Read-only）",
                        singleLine = true,
                        visualTransformation = if (showTokenPlain) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(end = 6.dp)
                            ) {
                                TextButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                                        if (clipText.isNotBlank()) {
                                            tokenInput = clipText
                                            savedFeedback = "已从剪贴板粘贴 Token"
                                        }
                                    }
                                ) {
                                    Text("粘贴", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ICostBlueAccent)
                                }
                                TextButton(onClick = { showTokenPlain = !showTokenPlain }) {
                                    Text(if (showTokenPlain) "隐藏" else "显示", fontSize = 12.sp, color = ICostTextSecondary)
                                }
                            }
                        },
                        supportingText = if (hasBuildConfigToken && tokenInput.isBlank()) {
                            "当前已使用编译期注入的 GITHUB_UPDATE_TOKEN；在此输入可覆盖"
                        } else {
                            "私有仓库下载 Release 附件会通过 API Asset + octet-stream 鉴权直连，Token 仅保存在本机"
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(color = ICostDivider)

                    SmartSwitchRow(
                        title = "启动 App 时自动检查更新",
                        subtitle = "冷启动后台静默检测，发现新 Release 时自动弹窗提醒",
                        checked = autoCheck,
                        onCheckedChange = {
                            autoCheck = it
                            onSaveConfig(
                                config.copy(
                                    repoFullName = repoInput.trim(),
                                    customGithubToken = tokenInput.trim(),
                                    autoCheckOnLaunch = it,
                                    includePrerelease = includePre
                                )
                            )
                        }
                    )

                    HorizontalDivider(color = ICostDivider)

                    SmartSwitchRow(
                        title = "接收预发布版本 (Pre-release)",
                        subtitle = "开启后可检测带体验版标记的 Release",
                        checked = includePre,
                        onCheckedChange = {
                            includePre = it
                            onSaveConfig(
                                config.copy(
                                    repoFullName = repoInput.trim(),
                                    customGithubToken = tokenInput.trim(),
                                    autoCheckOnLaunch = autoCheck,
                                    includePrerelease = it
                                )
                            )
                        }
                    )

                    Button(
                        onClick = {
                            onSaveConfig(
                                config.copy(
                                    repoFullName = repoInput.trim(),
                                    customGithubToken = tokenInput.trim(),
                                    autoCheckOnLaunch = autoCheck,
                                    includePrerelease = includePre,
                                    ignoredTag = ""
                                )
                            )
                            savedFeedback = "已保存私有仓库更新配置"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ICostTextPrimary),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Text("保存配置", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    if (!savedFeedback.isNullOrBlank()) {
                        Text(
                            text = savedFeedback!!,
                            fontSize = 12.sp,
                            color = ICostGreenIncome,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 3. 私有仓库发版与 Token 生成简明说明
            item {
                ICostFormCard {
                    Text(
                        text = "私有仓库 OTA 使用说明",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextPrimary
                    )
                    Text(
                        text = "1. 生成只读 Token：GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens，Repository 选择 Rowan-rh/moji-ai，Permissions 仅开启 Contents: Read-only。",
                        fontSize = 12.sp,
                        color = ICostTextSecondary
                    )
                    Text(
                        text = "2. 配置 Token：可填入上方输入框保存，或在本机 local.properties 中添加 GITHUB_UPDATE_TOKEN=github_pat_xxx。",
                        fontSize = 12.sp,
                        color = ICostTextSecondary
                    )
                    Text(
                        text = "3. 发布更新包：在电脑执行 gh release create v1.0.1 app/build/outputs/apk/debug/app-debug.apk --repo Rowan-rh/moji-ai，手机端即可自动收到更新并一键覆盖安装。",
                        fontSize = 12.sp,
                        color = ICostTextSecondary
                    )
                }
            }
        }
    }
}

