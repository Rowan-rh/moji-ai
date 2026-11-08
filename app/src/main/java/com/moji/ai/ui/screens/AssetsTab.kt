package com.moji.ai.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.data.local.RecurringRuleEntity
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CategoryCatalog
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.RecurrenceFrequency
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType
import com.moji.ai.ui.InstallmentPlanItem
import com.moji.ai.ui.SavingPlanItem
import com.moji.ai.ui.theme.AccountBrandOption
import com.moji.ai.ui.theme.AnimatedMoneyText
import com.moji.ai.ui.theme.ICDinFontFamily
import com.moji.ai.ui.theme.ICostAccountBrandCatalog
import com.moji.ai.ui.theme.ICostAmberGold
import com.moji.ai.ui.theme.ICostAssetImage
import com.moji.ai.ui.theme.ICostAvatarButton
import com.moji.ai.ui.theme.ICostBgGray
import com.moji.ai.ui.theme.ICostBlueAccent
import com.moji.ai.ui.theme.ICostCardWhite
import com.moji.ai.ui.theme.ICostCategoryBadge
import com.moji.ai.ui.theme.ICostCellGray
import com.moji.ai.ui.theme.ICostCircleIconButton
import com.moji.ai.ui.theme.ICostCoralRed
import com.moji.ai.ui.theme.ICostDivider
import com.moji.ai.ui.theme.ICostFormCard
import com.moji.ai.ui.theme.ICostFormField
import com.moji.ai.ui.theme.ICostGreenIncome
import com.moji.ai.ui.theme.ICostSheetHeader
import com.moji.ai.ui.theme.ICostSmoothBottomSheet
import com.moji.ai.ui.theme.ICostSwipeActionRow
import com.moji.ai.ui.theme.ICostTextMuted
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.SwipeActionItem
import com.moji.ai.ui.theme.WalletBrandAvatar
import com.moji.ai.ui.theme.daysUntilRepaymentText
import com.moji.ai.ui.theme.formatMoneyCents
import com.moji.ai.ui.theme.formatWalletDisplayName
import com.moji.ai.ui.theme.iCostPressScale
import com.moji.ai.ui.theme.iCostUnifiedSwitchColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ICostAssetsScreen(
    wallets: List<WalletEntity>,
    transactions: List<TransactionEntity>,
    recurringRules: List<RecurringRuleEntity>,
    hideAmounts: Boolean,
    onToggleHideAmounts: () -> Unit,
    onSaveWallet: (WalletEntity) -> Unit,
    onDeleteWallet: (WalletEntity) -> Unit,
    onQuickActionWithWallet: (TransactionType, sourceId: Long?, targetId: Long?) -> Unit,
    onSelectTransaction: (TransactionEntity) -> Unit,
    onTriggerRuleNow: (RecurringRuleEntity) -> Unit,
    onDeleteRecurringRule: (RecurringRuleEntity) -> Unit = {},
    savingPlans: List<SavingPlanItem> = emptyList(),
    installmentPlans: List<InstallmentPlanItem> = emptyList(),
    onDepositSavingPlan: (Long, Double) -> Unit = { _, _ -> },
    onPayInstallmentPeriod: (InstallmentPlanItem) -> Unit = {},
    onReconcileWallet: (Long, Double, Boolean, String) -> Unit = { _, _, _, _ -> },
    onOpenSettingsPage: () -> Unit = {},
    onSubpageActiveChange: (Boolean) -> Unit = {},
    onAddSavingPlan: (title: String, targetYuan: Double, savedYuan: Double, deadlineLabel: String) -> Unit = { _, _, _, _ -> },
    onDeleteSavingPlan: (planId: Long) -> Unit = {},
    onAddInstallmentPlan: (title: String, walletId: Long, totalYuan: Double, feePerPeriodYuan: Double, totalPeriods: Int) -> Unit = { _, _, _, _, _ -> },
    onDeleteInstallmentPlan: (planId: Long) -> Unit = {},
    onAddRecurringRule: (title: String, amountYuan: Double, currency: String, category: String, walletId: Long, frequency: RecurrenceFrequency) -> Unit = { _, _, _, _, _, _ -> }
) {
    val assetPagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val totalAssetsCnyCents = remember(wallets) {
        wallets.filter { !it.type.isLiability && it.includeInNetWorth }.sumOf { w ->
            (w.balanceCents * CurrencyCode.fromCode(w.currency).defaultRateToCny).roundToLong()
        }
    }
    val totalLiabilityCnyCents = remember(wallets) {
        wallets.filter { it.type.isLiability && it.includeInNetWorth }.sumOf { w ->
            (w.balanceCents * CurrencyCode.fromCode(w.currency).defaultRateToCny).roundToLong()
        }
    }
    val netWorthCnyCents = totalAssetsCnyCents - totalLiabilityCnyCents

    val totalLoanInCents = remember(transactions) {
        transactions.filter { it.type == TransactionType.LOAN_IN }.sumOf { it.amountCnyCents }
    }
    val totalLoanOutCents = remember(transactions) {
        transactions.filter { it.type == TransactionType.LOAN_OUT }.sumOf { it.amountCnyCents }
    }

    val creditWallets = remember(wallets) { wallets.filter { it.type.isLiability } }
    val assetWallets = remember(wallets) { wallets.filter { !it.type.isLiability } }
    // 分组标题按组内全部账户汇总（含不计入净资产的账户），与列表内容一致
    val creditGroupDebtCnyCents = remember(creditWallets) {
        creditWallets.sumOf { w ->
            (w.balanceCents.coerceAtLeast(0L) * CurrencyCode.fromCode(w.currency).defaultRateToCny).roundToLong()
        }
    }
    val assetGroupBalanceCnyCents = remember(assetWallets) {
        assetWallets.sumOf { w ->
            (w.balanceCents * CurrencyCode.fromCode(w.currency).defaultRateToCny).roundToLong()
        }
    }

    var creditExpanded by rememberSaveable { mutableStateOf(true) }
    var assetExpanded by rememberSaveable { mutableStateOf(true) }
    var plansExpanded by rememberSaveable { mutableStateOf(true) }
    var recurringExpanded by rememberSaveable { mutableStateOf(false) }
    var showTopMoreMenu by remember { mutableStateOf(false) }

    var inspectingWallet by remember { mutableStateOf<WalletEntity?>(null) }
    var showSelectTypeFullScreen by remember { mutableStateOf(false) }
    var selectedPresetForEditor by remember { mutableStateOf<AccountBrandOption?>(null) }
    var editingWallet by remember { mutableStateOf<WalletEntity?>(null) }
    var reconcilingWallet by remember { mutableStateOf<WalletEntity?>(null) }

    // 删除确认与新增表单弹窗状态
    var pendingDeleteWallet by remember { mutableStateOf<WalletEntity?>(null) }
    var pendingDeleteRule by remember { mutableStateOf<RecurringRuleEntity?>(null) }
    var pendingDeleteSavingPlan by remember { mutableStateOf<SavingPlanItem?>(null) }
    var pendingDeleteInstallment by remember { mutableStateOf<InstallmentPlanItem?>(null) }
    var depositingPlan by remember { mutableStateOf<SavingPlanItem?>(null) }
    var showAddSavingPlan by remember { mutableStateOf(false) }
    var showAddInstallment by remember { mutableStateOf(false) }
    var showAddRecurringRule by remember { mutableStateOf(false) }

    val hasActiveBottomSheet = selectedPresetForEditor != null ||
        editingWallet != null ||
        reconcilingWallet != null ||
        showAddSavingPlan ||
        showAddInstallment ||
        showAddRecurringRule
    val subpageActive = inspectingWallet != null || showSelectTypeFullScreen || hasActiveBottomSheet
    androidx.compose.runtime.LaunchedEffect(subpageActive) {
        onSubpageActiveChange(subpageActive)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onSubpageActiveChange(false) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 148.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. 顶部栏：右侧 `+` 白色圆钮 + `•••` 白色圆钮 + 薄荷绿插画头像（对齐 07_assets_tab.png）
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ICostCircleIconButton(
                        onClick = { showSelectTypeFullScreen = true },
                        size = 42.dp
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "添加账户",
                            tint = ICostTextPrimary,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Box {
                        ICostCircleIconButton(
                            onClick = { showTopMoreMenu = true },
                            size = 42.dp
                        ) {
                            Icon(
                                Icons.Default.MoreHoriz,
                                contentDescription = "更多",
                                tint = ICostTextPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showTopMoreMenu,
                            onDismissRequest = { showTopMoreMenu = false },
                            containerColor = ICostCardWhite
                        ) {
                            DropdownMenuItem(
                                text = { Text("添加账户", fontSize = 14.sp) },
                                onClick = {
                                    showTopMoreMenu = false
                                    showSelectTypeFullScreen = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (hideAmounts) "显示资产金额" else "隐藏资产金额", fontSize = 14.sp) },
                                onClick = {
                                    showTopMoreMenu = false
                                    onToggleHideAmounts()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when {
                                            recurringExpanded -> "收起周期账单"
                                            recurringRules.isEmpty() -> "周期账单"
                                            else -> "周期账单（${recurringRules.size}）"
                                        },
                                        fontSize = 14.sp
                                    )
                                },
                                onClick = {
                                    showTopMoreMenu = false
                                    recurringExpanded = !recurringExpanded
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    ICostAvatarButton(
                        onClick = onOpenSettingsPage,
                        size = 42.dp
                    )
                }
            }

            // 2. 超大加粗「资产」标题
            item {
                Text(
                    text = "资产",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = ICostTextPrimary
                )
            }

            // 3. 净资产双页可滑动大卡片（Page 0: 净资产/总资产/总负债；左右滑动切换 Page 1: 存钱计划与分期概览）
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp)),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        HorizontalPager(
                            state = assetPagerState,
                            modifier = Modifier.fillMaxWidth()
                        ) { page ->
                            if (page == 0) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("净资产", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ICostTextSecondary)
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .iCostPressScale(pressedScale = 0.85f, onClick = onToggleHideAmounts),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            ICostAssetImage(
                                                assetFile = if (hideAmounts) "money_eye_close.webp" else "money_eye_open.webp",
                                                contentDescription = if (hideAmounts) "显示金额" else "隐藏金额",
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))

                                    AnimatedMoneyText(
                                        cents = netWorthCnyCents,
                                        currency = "CNY",
                                        hideAmounts = hideAmounts,
                                        fontSize = 36.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ICostTextPrimary
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                                    ) {
                                        Text(
                                            text = "总资产 ${formatMoneyCents(totalAssetsCnyCents, "CNY", hideAmounts)}",
                                            fontSize = 13.sp,
                                            color = ICostTextSecondary,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        Text(
                                            text = "总负债 ${formatMoneyCents(totalLiabilityCnyCents, "CNY", hideAmounts)}",
                                            fontSize = 13.sp,
                                            color = ICostTextSecondary,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            } else {
                                // Page 1: 存钱计划与分期概览
                                val totalSaved = savingPlans.sumOf { it.savedCents }
                                val totalTarget = savingPlans.sumOf { it.targetCents }
                                val savePct = if (totalTarget > 0L) {
                                    (totalSaved.toFloat() / totalTarget.toFloat()).coerceIn(0f, 1f)
                                } else 0f
                                val remainInstallmentCents = installmentPlans.sumOf { installmentRemainCents(it) }

                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            ICostAssetImage(
                                                assetFile = "tab_save_money.webp",
                                                tint = ICostAmberGold,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "存钱与分期",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = ICostAmberGold
                                            )
                                        }
                                        if (totalTarget > 0L) {
                                            Text(
                                                text = "已完成 ${(savePct * 100).toInt()}%",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = ICostGreenIncome
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    AnimatedMoneyText(
                                        cents = totalSaved,
                                        currency = "CNY",
                                        hideAmounts = hideAmounts,
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ICostTextPrimary
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(ICostCellGray)
                                    ) {
                                        if (savePct > 0f) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth(savePct.coerceIn(0.02f, 1f))
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(ICostAmberGold)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "目标总额 ${formatMoneyCents(totalTarget, "CNY", hideAmounts)}",
                                            fontSize = 12.sp,
                                            color = ICostTextSecondary
                                        )
                                        Text(
                                            text = "分期未还 ${formatMoneyCents(remainInstallmentCents, "CNY", hideAmounts)}",
                                            fontSize = 12.sp,
                                            color = ICostCoralRed
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            repeat(2) { idx ->
                                val isSel = assetPagerState.currentPage == idx
                                val dotW by animateDpAsState(
                                    targetValue = if (isSel) 16.dp else 6.dp,
                                    animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
                                    label = "AssetPagerDotW"
                                )
                                val dotCol by animateColorAsState(
                                    targetValue = if (isSel) ICostAmberGold else Color(0xFFE0E0E0),
                                    animationSpec = tween(220),
                                    label = "AssetPagerDotCol"
                                )
                                Box(
                                    modifier = Modifier
                                        .height(6.dp)
                                        .width(dotW)
                                        .clip(CircleShape)
                                        .background(dotCol)
                                )
                                if (idx == 0) Spacer(modifier = Modifier.width(6.dp))
                            }
                        }
                    }
                }
            }

            // 4. 并排双卡片：「累计借入」(total_borrowing.webp) 与「累计借出」(total_lending.webp)，仅展示历史累计金额
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = ICostCardWhite,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("累计借入", fontSize = 13.sp, color = ICostTextSecondary)
                                Spacer(modifier = Modifier.height(3.dp))
                                AnimatedMoneyText(
                                    cents = totalLoanInCents,
                                    currency = "CNY",
                                    hideAmounts = hideAmounts,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ICostTextPrimary
                                )
                            }
                            ICostAssetImage(
                                assetFile = "total_borrowing.webp",
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = ICostCardWhite,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("累计借出", fontSize = 13.sp, color = ICostTextSecondary)
                                Spacer(modifier = Modifier.height(3.dp))
                                AnimatedMoneyText(
                                    cents = totalLoanOutCents,
                                    currency = "CNY",
                                    hideAmounts = hideAmounts,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ICostTextPrimary
                                )
                            }
                            ICostAssetImage(
                                assetFile = "total_lending.webp",
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                }
            }

            // 4.5 没有任何账户时的空状态
            if (wallets.isEmpty()) {
                item {
                    AssetsEmptyStateCard(
                        title = "还没有账户",
                        message = "添加银行卡、微信、支付宝或信用卡后，就能在这里看到余额和欠款。",
                        actionLabel = "添加账户",
                        onAction = { showSelectTypeFullScreen = true }
                    )
                }
            }

            // 5. 白色整卡容器：信用账户 (3)   欠款: ¥6,926.90 ⌄（对齐 07_assets_tab.png）
            if (creditWallets.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            CollapsibleGroupHeaderInsideCard(
                                title = "信用账户",
                                count = creditWallets.size,
                                rightSummary = "欠款 ${formatMoneyCents(creditGroupDebtCnyCents, "CNY", hideAmounts)}",
                                expanded = creditExpanded,
                                onToggle = { creditExpanded = !creditExpanded }
                            )

                            AnimatedVisibility(
                                visible = creditExpanded,
                                enter = expandVertically(
                                    animationSpec = spring(dampingRatio = 0.94f, stiffness = 600f)
                                ) + fadeIn(tween(140)),
                                exit = shrinkVertically(
                                    animationSpec = spring(dampingRatio = 0.96f, stiffness = 680f)
                                ) + fadeOut(tween(110))
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    creditWallets.forEachIndexed { idx, w ->
                                        val creditSwipeActions = listOf(
                                            SwipeActionItem(
                                                label = "还款",
                                                iconAsset = "transfer_swift.webp",
                                                backgroundColor = ICostBlueAccent,
                                                onClick = {
                                                    onQuickActionWithWallet(
                                                        TransactionType.REPAYMENT,
                                                        assetWallets.firstOrNull()?.id,
                                                        w.id
                                                    )
                                                }
                                            ),
                                            SwipeActionItem(
                                                label = "核对",
                                                iconAsset = "asset_pen.webp",
                                                backgroundColor = ICostGreenIncome,
                                                onClick = { reconcilingWallet = w }
                                            ),
                                            SwipeActionItem(
                                                label = "删除",
                                                iconAsset = "detail_delete.webp",
                                                backgroundColor = ICostCoralRed,
                                                onClick = { pendingDeleteWallet = w }
                                            )
                                        )
                                        ICostSwipeActionRow(
                                            actions = creditSwipeActions,
                                            actionWidth = 44.dp,
                                            actionHeight = 42.dp
                                        ) {
                                            ICostAccountItemRow(
                                                wallet = w,
                                                hideAmounts = hideAmounts,
                                                onClick = { inspectingWallet = w }
                                            )
                                        }
                                        if (idx < creditWallets.lastIndex) {
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

            // 6. 白色整卡容器：资金账户   余额: ¥7,069.22 ⌄
            if (assetWallets.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            CollapsibleGroupHeaderInsideCard(
                                title = "资金账户",
                                count = assetWallets.size,
                                rightSummary = "余额 ${formatMoneyCents(assetGroupBalanceCnyCents, "CNY", hideAmounts)}",
                                expanded = assetExpanded,
                                onToggle = { assetExpanded = !assetExpanded }
                            )

                            AnimatedVisibility(
                                visible = assetExpanded,
                                enter = expandVertically(
                                    animationSpec = spring(dampingRatio = 0.94f, stiffness = 600f)
                                ) + fadeIn(tween(140)),
                                exit = shrinkVertically(
                                    animationSpec = spring(dampingRatio = 0.96f, stiffness = 680f)
                                ) + fadeOut(tween(110))
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    assetWallets.forEachIndexed { idx, w ->
                                        val assetSwipeActions = listOf(
                                            SwipeActionItem(
                                                label = "转账",
                                                iconAsset = "transfer_swift.webp",
                                                backgroundColor = ICostBlueAccent,
                                                onClick = {
                                                    onQuickActionWithWallet(
                                                        TransactionType.TRANSFER,
                                                        w.id,
                                                        assetWallets.firstOrNull { it.id != w.id }?.id
                                                    )
                                                }
                                            ),
                                            SwipeActionItem(
                                                label = "核对",
                                                iconAsset = "asset_pen.webp",
                                                backgroundColor = ICostGreenIncome,
                                                onClick = { reconcilingWallet = w }
                                            ),
                                            SwipeActionItem(
                                                label = "删除",
                                                iconAsset = "detail_delete.webp",
                                                backgroundColor = ICostCoralRed,
                                                onClick = { pendingDeleteWallet = w }
                                            )
                                        )
                                        ICostSwipeActionRow(
                                            actions = assetSwipeActions,
                                            actionWidth = 44.dp,
                                            actionHeight = 42.dp
                                        ) {
                                            ICostAccountItemRow(
                                                wallet = w,
                                                hideAmounts = hideAmounts,
                                                onClick = { inspectingWallet = w }
                                            )
                                        }
                                        if (idx < assetWallets.lastIndex) {
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

            // 6.5 存钱计划与分期：常驻可折叠卡片（空状态 + 新增）
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        CollapsibleGroupHeaderInsideCard(
                            title = "存钱与分期",
                            count = savingPlans.size + installmentPlans.size,
                            rightSummary = if (plansExpanded) "收起" else "展开",
                            expanded = plansExpanded,
                            onToggle = { plansExpanded = !plansExpanded }
                        )

                        AnimatedVisibility(
                            visible = plansExpanded,
                            enter = expandVertically(
                                animationSpec = spring(dampingRatio = 0.94f, stiffness = 600f)
                            ) + fadeIn(tween(140)),
                            exit = shrinkVertically(
                                animationSpec = spring(dampingRatio = 0.96f, stiffness = 680f)
                            ) + fadeOut(tween(110))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                            ) {
                                PlanSubsectionHeader(
                                    title = "存钱计划",
                                    addLabel = "新增",
                                    addDescription = "新增存钱计划",
                                    onAdd = { showAddSavingPlan = true }
                                )
                                if (savingPlans.isEmpty()) {
                                    Text(
                                        text = "还没有存钱计划",
                                        fontSize = 13.sp,
                                        color = ICostTextMuted,
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    )
                                }
                                savingPlans.forEach { plan ->
                                    val pct = if (plan.targetCents > 0L) {
                                        (plan.savedCents.toFloat() / plan.targetCents.toFloat()).coerceIn(0f, 1f)
                                    } else 0f
                                    ICostSwipeActionRow(
                                        actions = listOf(
                                            SwipeActionItem(
                                                label = "存入",
                                                iconAsset = "tab_save_money.webp",
                                                backgroundColor = ICostGreenIncome,
                                                onClick = { depositingPlan = plan }
                                            ),
                                            SwipeActionItem(
                                                label = "删除",
                                                iconAsset = "detail_delete.webp",
                                                backgroundColor = ICostCoralRed,
                                                onClick = { pendingDeleteSavingPlan = plan }
                                            )
                                        ),
                                        actionWidth = 52.dp,
                                        actionHeight = 44.dp
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(ICostCardWhite)
                                                .padding(vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(
                                                    plan.title,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = ICostTextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    "已存 ${formatMoneyCents(plan.savedCents, "CNY", hideAmounts)} / 目标 ${formatMoneyCents(plan.targetCents, "CNY", hideAmounts)}",
                                                    fontSize = 12.sp,
                                                    color = ICostTextSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.height(5.dp))
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Box(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .height(4.dp)
                                                            .clip(RoundedCornerShape(2.dp))
                                                            .background(ICostCellGray)
                                                    ) {
                                                        if (pct > 0f) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .fillMaxWidth(pct.coerceIn(0.02f, 1f))
                                                                    .height(4.dp)
                                                                    .clip(RoundedCornerShape(2.dp))
                                                                    .background(ICostAmberGold)
                                                            )
                                                        }
                                                    }
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = "${(pct * 100).toInt()}%",
                                                        fontSize = 11.sp,
                                                        color = ICostTextSecondary
                                                    )
                                                }
                                                if (plan.deadlineLabel.isNotBlank()) {
                                                    Text(
                                                        text = "截止 ${plan.deadlineLabel}",
                                                        fontSize = 11.sp,
                                                        color = ICostTextMuted
                                                    )
                                                }
                                            }
                                            PlanActionChip(
                                                label = "存入",
                                                enabled = true,
                                                activeBackground = Color(0xFFE6F7EF),
                                                activeColor = ICostGreenIncome,
                                                onClick = { depositingPlan = plan }
                                            )
                                        }
                                    }
                                }

                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    thickness = 0.5.dp,
                                    color = ICostDivider
                                )

                                PlanSubsectionHeader(
                                    title = "分期",
                                    addLabel = "新增",
                                    addDescription = "新增分期",
                                    onAdd = { showAddInstallment = true }
                                )
                                if (installmentPlans.isEmpty()) {
                                    Text(
                                        text = "还没有分期",
                                        fontSize = 13.sp,
                                        color = ICostTextMuted,
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    )
                                }
                                installmentPlans.forEach { inst ->
                                    val perPeriodCents = inst.totalCents / inst.totalPeriods.coerceAtLeast(1) + inst.feePerPeriodCents
                                    val settled = inst.paidPeriods >= inst.totalPeriods
                                    val walletName = wallets.firstOrNull { it.id == inst.walletId }?.name
                                    val instActions = buildList {
                                        if (!settled) {
                                            add(
                                                SwipeActionItem(
                                                    label = "还一期",
                                                    iconAsset = "detail_installment.webp",
                                                    backgroundColor = ICostBlueAccent,
                                                    onClick = { onPayInstallmentPeriod(inst) }
                                                )
                                            )
                                        }
                                        add(
                                            SwipeActionItem(
                                                label = "删除",
                                                iconAsset = "detail_delete.webp",
                                                backgroundColor = ICostCoralRed,
                                                onClick = { pendingDeleteInstallment = inst }
                                            )
                                        )
                                    }
                                    ICostSwipeActionRow(
                                        actions = instActions,
                                        actionWidth = 56.dp,
                                        actionHeight = 44.dp
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(ICostCardWhite)
                                                .padding(vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(
                                                    inst.title,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = ICostTextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    "已还 ${inst.paidPeriods}/${inst.totalPeriods} 期 · 每期 ${formatMoneyCents(perPeriodCents, "CNY", hideAmounts)}",
                                                    fontSize = 12.sp,
                                                    color = ICostTextSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (walletName != null) {
                                                    Text(
                                                        text = walletName,
                                                        fontSize = 11.sp,
                                                        color = ICostTextMuted,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            PlanActionChip(
                                                label = if (settled) "已结清" else "还一期",
                                                enabled = !settled,
                                                activeBackground = Color(0xFFFDECE9),
                                                activeColor = ICostCoralRed,
                                                onClick = { onPayInstallmentPeriod(inst) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 7. 周期账单折叠卡片（从右上角菜单打开，空状态可直接添加）
            if (recurringExpanded) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            CollapsibleGroupHeaderInsideCard(
                                title = "周期账单",
                                count = recurringRules.size,
                                rightSummary = "收起",
                                expanded = true,
                                onToggle = { recurringExpanded = false }
                            )
                            if (recurringRules.isEmpty()) {
                                Text(
                                    text = "还没有周期账单。房租、会员订阅这类固定支出，添加后会按时自动记账。",
                                    fontSize = 13.sp,
                                    color = ICostTextSecondary,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                            recurringRules.forEach { rule ->
                                ICostSwipeActionRow(
                                    actions = listOf(
                                        SwipeActionItem(
                                            label = "执行",
                                            iconAsset = "setting_period.webp",
                                            backgroundColor = ICostBlueAccent,
                                            onClick = { onTriggerRuleNow(rule) }
                                        ),
                                        SwipeActionItem(
                                            label = "删除",
                                            iconAsset = "detail_delete.webp",
                                            backgroundColor = ICostCoralRed,
                                            onClick = { pendingDeleteRule = rule }
                                        )
                                    ),
                                    actionWidth = 54.dp,
                                    actionHeight = 44.dp
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(ICostCardWhite)
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "${rule.title}（${rule.frequency.displayName}）",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                                color = ICostTextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                "${formatMoneyCents(rule.amountCents, rule.currency, hideAmounts)} · ${rule.category}",
                                                fontSize = 12.sp,
                                                color = ICostTextSecondary
                                            )
                                        }
                                        TextButton(onClick = { onTriggerRuleNow(rule) }) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Text("执行一次")
                                        }
                                    }
                                }
                            }
                            TextButton(
                                onClick = { showAddRecurringRule = true },
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .heightIn(min = 44.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = ICostBlueAccent, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("添加周期账单", color = ICostBlueAccent, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // 全屏二级页面：账户详情（对齐 10_asset_detail_real.png，带平滑水平推入/退出过渡）
        val currentInspecting = inspectingWallet?.let { target -> wallets.firstOrNull { it.id == target.id } ?: target }
        var cachedInspectingWallet by remember { mutableStateOf<WalletEntity?>(null) }
        if (currentInspecting != null) {
            cachedInspectingWallet = currentInspecting
        }
        AnimatedVisibility(
            visible = currentInspecting != null,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(290, easing = FastOutSlowInEasing)
            ) + fadeIn(tween(200)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(240, easing = FastOutSlowInEasing)
            ) + fadeOut(tween(180))
        ) {
            val activeWallet = currentInspecting ?: cachedInspectingWallet
            if (activeWallet != null) {
                ICostWalletDetailFullScreen(
                    wallet = activeWallet,
                    allWallets = wallets,
                    walletTransactions = transactions.filter {
                        it.sourceWalletId == activeWallet.id || it.targetWalletId == activeWallet.id
                    },
                    onBack = { inspectingWallet = null },
                    onEditWallet = {
                        editingWallet = activeWallet
                    },
                    onReconcileBalance = {
                        reconcilingWallet = activeWallet
                    },
                    onRepayOrTransfer = {
                        val wid = activeWallet.id
                        if (activeWallet.type.isLiability) {
                            onQuickActionWithWallet(TransactionType.REPAYMENT, assetWallets.firstOrNull()?.id, wid)
                        } else {
                            onQuickActionWithWallet(
                                TransactionType.TRANSFER,
                                wid,
                                assetWallets.firstOrNull { it.id != wid }?.id
                            )
                        }
                    },
                    onAddRecordWithWallet = {
                        onQuickActionWithWallet(TransactionType.EXPENSE, activeWallet.id, null)
                    },
                    onDeleteWallet = {
                        pendingDeleteWallet = activeWallet
                    },
                    onClickTransaction = onSelectTransaction,
                    hideAmounts = hideAmounts
                )
            }
        }

        // 全屏二级页面：选择类型（对齐 11_add_asset_real.png，带平滑水平推入/退出过渡）
        AnimatedVisibility(
            visible = showSelectTypeFullScreen,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(290, easing = FastOutSlowInEasing)
            ) + fadeIn(tween(200)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(240, easing = FastOutSlowInEasing)
            ) + fadeOut(tween(180))
        ) {
            ICostSelectAccountTypeFullScreen(
                onBack = { showSelectTypeFullScreen = false },
                onSelectPreset = { option ->
                    selectedPresetForEditor = option
                }
            )
        }

        // 账户信息表单抽屉（新建或编辑）
        if (selectedPresetForEditor != null || editingWallet != null) {
            WalletEditorSheet(
                existing = editingWallet,
                preset = selectedPresetForEditor,
                onDismiss = {
                    selectedPresetForEditor = null
                    editingWallet = null
                },
                onConfirm = { saved ->
                    onSaveWallet(saved)
                    selectedPresetForEditor = null
                    editingWallet = null
                    showSelectTypeFullScreen = false
                }
            )
        }

        // 核对余额抽屉（可补记差额账单）
        val targetReconcile = reconcilingWallet
        if (targetReconcile != null) {
            WalletBalanceReconcileSheet(
                wallet = targetReconcile,
                onDismiss = { reconcilingWallet = null },
                onConfirmReconcile = { actualYuan, recordAdjustmentTx, note ->
                    onReconcileWallet(targetReconcile.id, actualYuan, recordAdjustmentTx, note)
                    reconcilingWallet = null
                }
            )
        }

        if (showAddSavingPlan) {
            AddSavingPlanSheet(
                onDismiss = { showAddSavingPlan = false },
                onConfirm = { title, targetYuan, savedYuan, deadline ->
                    showAddSavingPlan = false
                    onAddSavingPlan(title, targetYuan, savedYuan, deadline)
                }
            )
        }

        if (showAddInstallment) {
            AddInstallmentSheet(
                wallets = wallets,
                onDismiss = { showAddInstallment = false },
                onConfirm = { title, walletId, totalYuan, feeYuan, periods ->
                    showAddInstallment = false
                    onAddInstallmentPlan(title, walletId, totalYuan, feeYuan, periods)
                }
            )
        }

        if (showAddRecurringRule) {
            AddRecurringRuleSheet(
                wallets = wallets,
                onDismiss = { showAddRecurringRule = false },
                onConfirm = { title, amountYuan, currency, category, walletId, frequency ->
                    showAddRecurringRule = false
                    onAddRecurringRule(title, amountYuan, currency, category, walletId, frequency)
                }
            )
        }
    }

    // 删除账户确认
    pendingDeleteWallet?.let { target ->
        val refCount = remember(target.id, transactions) {
            transactions.count { it.sourceWalletId == target.id || it.targetWalletId == target.id }
        }
        ICostConfirmDeleteDialog(
            title = "删除账户「${target.name}」？",
            message = if (refCount > 0) {
                "有 $refCount 笔账单用到了这个账户。删除账户后，这些账单仍会保留在记录中。"
            } else {
                "删除后无法恢复。"
            },
            onConfirm = {
                pendingDeleteWallet = null
                if (inspectingWallet?.id == target.id) inspectingWallet = null
                onDeleteWallet(target)
            },
            onDismiss = { pendingDeleteWallet = null }
        )
    }

    // 删除周期账单确认
    pendingDeleteRule?.let { rule ->
        ICostConfirmDeleteDialog(
            title = "删除周期账单「${rule.title}」？",
            message = "删除后不会再自动记账，已经记下的账单不受影响。",
            onConfirm = {
                pendingDeleteRule = null
                onDeleteRecurringRule(rule)
            },
            onDismiss = { pendingDeleteRule = null }
        )
    }

    // 删除存钱计划确认
    pendingDeleteSavingPlan?.let { plan ->
        ICostConfirmDeleteDialog(
            title = "删除存钱计划「${plan.title}」？",
            message = "删除后计划进度无法恢复。",
            onConfirm = {
                pendingDeleteSavingPlan = null
                onDeleteSavingPlan(plan.id)
            },
            onDismiss = { pendingDeleteSavingPlan = null }
        )
    }

    // 删除分期确认
    pendingDeleteInstallment?.let { inst ->
        ICostConfirmDeleteDialog(
            title = "删除分期「${inst.title}」？",
            message = "只删除这条分期记录，已经记下的还款账单不受影响。",
            onConfirm = {
                pendingDeleteInstallment = null
                onDeleteInstallmentPlan(inst.id)
            },
            onDismiss = { pendingDeleteInstallment = null }
        )
    }

    // 存入金额弹窗
    depositingPlan?.let { plan ->
        SavingPlanDepositDialog(
            plan = plan,
            onDismiss = { depositingPlan = null },
            onConfirm = { yuan ->
                depositingPlan = null
                onDepositSavingPlan(plan.id, yuan)
            }
        )
    }
}

/** 余额类输入解析：允许空格、逗号千分位，无法解析返回 null。 */
private fun parseYuanInput(raw: String): Double? {
    val cleaned = raw.trim().replace(",", "").replace("，", "")
    if (cleaned.isEmpty()) return null
    val v = cleaned.toDoubleOrNull() ?: return null
    return if (v.isNaN() || v.isInfinite()) null else v
}

/** 金额输入过滤：只保留数字、一个小数点（可选负号），最多两位小数。 */
private fun sanitizeAmountInput(raw: String, allowNegative: Boolean = false): String {
    val sb = StringBuilder()
    var dotSeen = false
    var decimals = 0
    raw.forEachIndexed { idx, ch ->
        when {
            ch.isDigit() -> {
                if (dotSeen) {
                    if (decimals < 2) { sb.append(ch); decimals++ }
                } else sb.append(ch)
            }
            (ch == '.' || ch == '。') && !dotSeen -> { sb.append('.'); dotSeen = true }
            ch == '-' && allowNegative && idx == 0 -> sb.append('-')
        }
    }
    return sb.toString()
}

private fun installmentRemainCents(plan: InstallmentPlanItem): Long {
    val periods = plan.totalPeriods.coerceAtLeast(1)
    val remainPeriods = (periods - plan.paidPeriods).coerceIn(0, periods)
    if (remainPeriods == 0) return 0L
    // 本金整除余数计入最后一期（与 MainViewModel.payNextInstallmentPeriod 一致）
    val principalPaid = plan.totalCents / periods * (periods - remainPeriods)
    return (plan.totalCents - principalPaid) + plan.feePerPeriodCents * remainPeriods
}

/** 把本位币（CNY）分转换为指定币种的分，用于与账户币种保持一致展示。 */
private fun cnyCentsToCurrency(cnyCents: Long, currency: String): Long {
    val rate = CurrencyCode.fromCode(currency).defaultRateToCny
    return if (rate <= 0.0) cnyCents else (cnyCents / rate).roundToLong()
}

@Composable
private fun ICostConfirmDeleteDialog(
    title: String,
    message: String,
    confirmLabel: String = "删除",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ICostCardWhite,
        title = { Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) },
        text = { Text(message, fontSize = 14.sp, color = ICostTextSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, fontWeight = FontWeight.Bold, color = ICostCoralRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = ICostTextSecondary) }
        }
    )
}

@Composable
private fun AssetsEmptyStateCard(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ICostTextPrimary)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                message,
                fontSize = 13.sp,
                color = ICostTextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.height(44.dp)
            ) {
                Text(actionLabel, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PlanSubsectionHeader(
    title: String,
    addLabel: String,
    addDescription: String,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ICostTextSecondary)
        Row(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = addDescription, onClick = onAdd)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = ICostBlueAccent, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(2.dp))
            Text(addLabel, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ICostBlueAccent)
        }
    }
}

@Composable
private fun PlanActionChip(
    label: String,
    enabled: Boolean,
    activeBackground: Color,
    activeColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .then(if (enabled) Modifier.iCostPressScale(pressedScale = 0.90f, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (enabled) activeBackground else ICostCellGray
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) activeColor else ICostTextMuted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/** 通用小胶囊单选项（币种、频率、账户、分类）。 */
@Composable
private fun ICostChoicePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compactHorizontalPadding: Boolean = false
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) ICostBlueAccent else ICostCellGray,
        modifier = modifier.heightIn(min = 38.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = if (compactHorizontalPadding) 2.dp else 12.dp)
        ) {
            Text(
                text = label,
                fontSize = if (compactHorizontalPadding) 12.sp else 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.White else ICostTextPrimary,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun PillChooserRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = ICostTextSecondary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 2.dp)
    )
}

@Composable
private fun SavingPlanDepositDialog(
    plan: SavingPlanItem,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var input by remember(plan.id) { mutableStateOf("") }
    val parsed = parseYuanInput(input)
    val error = when {
        input.isBlank() -> null
        parsed == null || parsed <= 0.0 -> "请输入大于 0 的金额"
        else -> null
    }
    val valid = parsed != null && parsed > 0.0
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ICostCardWhite,
        title = { Text("存入「${plan.title}」", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) },
        text = {
            ICostFormField(
                value = input,
                onValueChange = { input = sanitizeAmountInput(it) },
                label = "存入金额（元）",
                placeholder = "请输入存入金额",
                singleLine = true,
                isError = error != null,
                supportingText = error,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = valid) {
                Text("存入", fontWeight = FontWeight.Bold, color = if (valid) ICostBlueAccent else ICostTextMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = ICostTextSecondary) }
        }
    )
}

@Composable
private fun AddSavingPlanSheet(
    onDismiss: () -> Unit,
    onConfirm: (title: String, targetYuan: Double, savedYuan: Double, deadlineLabel: String) -> Unit
) {
    val defaultDeadline = remember { "${Calendar.getInstance().get(Calendar.YEAR)}-12-31" }
    var title by remember { mutableStateOf("") }
    var targetStr by remember { mutableStateOf("") }
    var savedStr by remember { mutableStateOf("") }
    var deadline by remember { mutableStateOf(defaultDeadline) }
    var titleTouched by remember { mutableStateOf(false) }
    var targetTouched by remember { mutableStateOf(false) }

    val target = parseYuanInput(targetStr)
    val saved = if (savedStr.isBlank()) 0.0 else parseYuanInput(savedStr)
    val deadlineValid = remember(deadline) { isValidDateLabel(deadline) }

    val titleError = if (title.isBlank() && titleTouched) "请输入计划名称" else null
    val targetError = when {
        targetStr.isBlank() -> if (targetTouched) "请输入目标金额" else null
        target == null || target < 1.0 -> "目标金额至少 1 元"
        else -> null
    }
    val savedError = if (saved == null || saved < 0.0) "请输入有效金额" else null
    val deadlineError = if (!deadlineValid) "日期格式如 $defaultDeadline" else null
    val valid = title.isNotBlank() && target != null && target >= 1.0 && saved != null && saved >= 0.0 && deadlineValid

    ICostSmoothBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ICostBgGray,
        showDragHandle = false
    ) { requestDismiss ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            ICostSheetHeader(
                title = "新增存钱计划",
                onClose = requestDismiss
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ICostFormCard {
                    ICostFormField(
                        value = title,
                        onValueChange = { title = it; titleTouched = true },
                        label = "计划名称",
                        placeholder = "如：旅行基金、备用金",
                        singleLine = true,
                        isError = titleError != null,
                        supportingText = titleError,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ICostFormField(
                            value = targetStr,
                            onValueChange = { targetStr = sanitizeAmountInput(it); targetTouched = true },
                            label = "目标金额（元）",
                            placeholder = "0.00",
                            singleLine = true,
                            isError = targetError != null,
                            supportingText = targetError,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        ICostFormField(
                            value = savedStr,
                            onValueChange = { savedStr = sanitizeAmountInput(it) },
                            label = "已存金额（选填）",
                            placeholder = "0.00",
                            singleLine = true,
                            isError = savedError != null,
                            supportingText = savedError,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    ICostFormField(
                        value = deadline,
                        onValueChange = { deadline = it.take(10) },
                        label = "截止日期",
                        placeholder = defaultDeadline,
                        singleLine = true,
                        isError = deadlineError != null,
                        supportingText = deadlineError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Button(
                    onClick = {
                        if (valid) {
                            onConfirm(title.trim(), target!!, saved!!, deadline.trim())
                            requestDismiss()
                        }
                    },
                    enabled = valid,
                    colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("保存", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

private fun isValidDateLabel(raw: String): Boolean {
    val s = raw.trim()
    if (!Regex("""\d{4}-\d{1,2}-\d{1,2}""").matches(s)) return false
    return try {
        val fmt = SimpleDateFormat("yyyy-M-d", Locale.CHINA).apply { isLenient = false }
        fmt.parse(s) != null
    } catch (_: Exception) {
        false
    }
}

@Composable
private fun AddInstallmentSheet(
    wallets: List<WalletEntity>,
    onDismiss: () -> Unit,
    onConfirm: (title: String, walletId: Long, totalYuan: Double, feePerPeriodYuan: Double, totalPeriods: Int) -> Unit
) {
    val orderedWallets = remember(wallets) { wallets.sortedByDescending { it.type.isLiability } }
    var title by remember { mutableStateOf("") }
    var walletId by remember { mutableStateOf(orderedWallets.firstOrNull()?.id) }
    var totalStr by remember { mutableStateOf("") }
    var feeStr by remember { mutableStateOf("") }
    var periodsStr by remember { mutableStateOf("12") }
    var titleTouched by remember { mutableStateOf(false) }
    var totalTouched by remember { mutableStateOf(false) }

    val total = parseYuanInput(totalStr)
    val fee = if (feeStr.isBlank()) 0.0 else parseYuanInput(feeStr)
    val periods = periodsStr.toIntOrNull()

    val titleError = if (title.isBlank() && titleTouched) "请输入分期名称" else null
    val totalError = when {
        totalStr.isBlank() -> if (totalTouched) "请输入分期总额" else null
        total == null || total < 1.0 -> "分期总额至少 1 元"
        else -> null
    }
    val feeError = if (fee == null || fee < 0.0) "请输入有效金额" else null
    val periodsError = if (periods == null || periods !in 2..60) "期数为 2 到 60 期" else null
    val valid = title.isNotBlank() && walletId != null && total != null && total >= 1.0 &&
        fee != null && fee >= 0.0 && periods != null && periods in 2..60

    ICostSmoothBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ICostBgGray,
        showDragHandle = false
    ) { requestDismiss ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            ICostSheetHeader(
                title = "新增分期",
                onClose = requestDismiss
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ICostFormCard {
                    ICostFormField(
                        value = title,
                        onValueChange = { title = it; titleTouched = true },
                        label = "分期名称",
                        placeholder = "如：手机分期、电脑分期",
                        singleLine = true,
                        isError = titleError != null,
                        supportingText = titleError,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FieldLabel("还款账户")
                        if (orderedWallets.isEmpty()) {
                            Text("请先添加账户", fontSize = 13.sp, color = ICostCoralRed)
                        } else {
                            PillChooserRow {
                                orderedWallets.forEach { w ->
                                    ICostChoicePill(label = w.name, selected = walletId == w.id, onClick = { walletId = w.id })
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ICostFormField(
                            value = totalStr,
                            onValueChange = { totalStr = sanitizeAmountInput(it); totalTouched = true },
                            label = "分期总额（元）",
                            placeholder = "0.00",
                            singleLine = true,
                            isError = totalError != null,
                            supportingText = totalError,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        ICostFormField(
                            value = feeStr,
                            onValueChange = { feeStr = sanitizeAmountInput(it) },
                            label = "每期手续费（选填）",
                            placeholder = "0.00",
                            singleLine = true,
                            isError = feeError != null,
                            supportingText = feeError,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FieldLabel("分期期数")
                        PillChooserRow {
                            listOf(3, 6, 12, 24).forEach { p ->
                                ICostChoicePill(label = "${p} 期", selected = periods == p, onClick = { periodsStr = p.toString() })
                            }
                        }
                        ICostFormField(
                            value = periodsStr,
                            onValueChange = { v -> periodsStr = v.filter { it.isDigit() }.take(2) },
                            label = "自定义期数（2–60 期）",
                            placeholder = "12",
                            singleLine = true,
                            isError = periodsError != null,
                            supportingText = periodsError,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Button(
                    onClick = {
                        if (valid) {
                            onConfirm(title.trim(), walletId!!, total!!, fee!!, periods!!)
                            requestDismiss()
                        }
                    },
                    enabled = valid,
                    colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("保存", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun AddRecurringRuleSheet(
    wallets: List<WalletEntity>,
    onDismiss: () -> Unit,
    onConfirm: (title: String, amountYuan: Double, currency: String, category: String, walletId: Long, frequency: RecurrenceFrequency) -> Unit
) {
    val categories = remember { CategoryCatalog.expenseCategories.map { it.name } }
    var title by remember { mutableStateOf("") }
    var amountStr by remember { mutableStateOf("") }
    var walletId by remember { mutableStateOf(wallets.firstOrNull()?.id) }
    var currency by remember { mutableStateOf(wallets.firstOrNull()?.currency?.let { CurrencyCode.fromCode(it).name } ?: "CNY") }
    var category by remember { mutableStateOf(categories.firstOrNull() ?: "其他") }
    var frequency by remember { mutableStateOf(RecurrenceFrequency.MONTHLY) }
    var titleTouched by remember { mutableStateOf(false) }
    var amountTouched by remember { mutableStateOf(false) }

    val amount = parseYuanInput(amountStr)
    val titleError = if (title.isBlank() && titleTouched) "请输入名称" else null
    val amountError = when {
        amountStr.isBlank() -> if (amountTouched) "请输入金额" else null
        amount == null || amount <= 0.0 -> "请输入大于 0 的金额"
        else -> null
    }
    val valid = title.isNotBlank() && amount != null && amount > 0.0 && walletId != null

    ICostSmoothBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ICostBgGray,
        showDragHandle = false
    ) { requestDismiss ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            ICostSheetHeader(
                title = "添加周期账单",
                onClose = requestDismiss
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    ICostFormCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ICostFormField(
                                value = title,
                                onValueChange = { title = it; titleTouched = true },
                                label = "账单名称",
                                placeholder = "如：房租、会员订阅",
                                singleLine = true,
                                isError = titleError != null,
                                supportingText = titleError,
                                modifier = Modifier.weight(1f)
                            )
                            ICostFormField(
                                value = amountStr,
                                onValueChange = { amountStr = sanitizeAmountInput(it); amountTouched = true },
                                label = "每次金额",
                                placeholder = "0.00",
                                singleLine = true,
                                isError = amountError != null,
                                supportingText = amountError,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FieldLabel("币种")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CurrencyCode.entries.forEach { c ->
                                    ICostChoicePill(
                                        label = c.name,
                                        selected = currency == c.name,
                                        onClick = { currency = c.name },
                                        modifier = Modifier.weight(1f),
                                        compactHorizontalPadding = true
                                    )
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FieldLabel("重复周期")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                RecurrenceFrequency.entries.forEach { f ->
                                    ICostChoicePill(
                                        label = f.displayName,
                                        selected = frequency == f,
                                        onClick = { frequency = f },
                                        modifier = Modifier.weight(1f),
                                        compactHorizontalPadding = true
                                    )
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FieldLabel("记账分类")
                            PillChooserRow {
                                categories.forEach { name ->
                                    ICostChoicePill(label = name, selected = category == name, onClick = { category = name })
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FieldLabel("扣款账户")
                            if (wallets.isEmpty()) {
                                Text("请先添加账户", fontSize = 13.sp, color = ICostCoralRed)
                            } else {
                                PillChooserRow {
                                    wallets.forEach { w ->
                                        ICostChoicePill(
                                            label = w.name,
                                            selected = walletId == w.id,
                                            onClick = {
                                                walletId = w.id
                                                currency = CurrencyCode.fromCode(w.currency).name
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            if (valid) {
                                onConfirm(title.trim(), amount!!, currency, category, walletId!!, frequency)
                                requestDismiss()
                            }
                        },
                        enabled = valid,
                        colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("保存", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleGroupHeaderInsideCard(
    title: String,
    count: Int,
    rightSummary: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val chevronRot by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
        label = "AssetGroupChevronRot"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .iCostPressScale(pressedScale = 0.98f, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = ICostTextSecondary
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "($count)",
                fontSize = 13.sp,
                color = ICostTextSecondary
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = rightSummary,
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
                    .graphicsLayer { rotationZ = chevronRot }
            )
        }
    }
}

@Composable
private fun ICostAccountItemRow(
    wallet: WalletEntity,
    hideAmounts: Boolean,
    onClick: () -> Unit
) {
    val fullTitle = wallet.name

    val leftSubtitle = remember(wallet) {
        if (wallet.type.isLiability) {
            val sub = wallet.subTypeLabel.ifBlank { wallet.type.displayName }
            if (wallet.repaymentDay != null && wallet.repaymentDay in 1..31) {
                "[$sub] ${daysUntilRepaymentText(wallet.repaymentDay)}"
            } else {
                sub
            }
        } else {
            wallet.subTypeLabel.ifBlank { wallet.type.displayName }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WalletBrandAvatar(wallet = wallet, size = 38.dp)

        Spacer(modifier = Modifier.width(10.dp))

        // 对齐 iCost：上下两行独立 Row，首行「账户名 ↔ 余额/欠款」，次行「[子类型] X天后还款 ↔ 可用额度」，互不挤压截断
        val primaryRightText = if (wallet.type.isLiability) {
            val debt = wallet.balanceCents.coerceAtLeast(0L)
            formatMoneyCents(if (debt > 0L) -debt else 0L, wallet.currency, hideAmounts)
        } else {
            formatMoneyCents(wallet.balanceCents, wallet.currency, hideAmounts)
        }
        val secondaryRightText: String? = when {
            wallet.type.isLiability && wallet.creditLimitCents != null -> {
                val availableCents = (wallet.creditLimitCents - wallet.balanceCents).coerceAtLeast(0L)
                "可用额度: ${formatMoneyCents(availableCents, wallet.currency, hideAmounts)}"
            }
            wallet.currency != "CNY" -> {
                val cnyCents = (wallet.balanceCents * CurrencyCode.fromCode(wallet.currency).defaultRateToCny).roundToLong()
                "约 ${formatMoneyCents(cnyCents, "CNY", hideAmounts)}"
            }
            else -> null
        }

        if (secondaryRightText != null) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = fullTitle,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 6.dp)
                    )
                    Text(
                        text = primaryRightText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ICostTextPrimary,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Spacer(modifier = Modifier.height(3.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = leftSubtitle,
                        fontSize = 11.sp,
                        letterSpacing = (-0.2).sp,
                        color = ICostTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 4.dp)
                    )
                    Text(
                        text = secondaryRightText,
                        fontSize = 11.sp,
                        letterSpacing = (-0.2).sp,
                        color = ICostTextSecondary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        } else {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = fullTitle,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = ICostTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = leftSubtitle,
                    fontSize = 11.sp,
                    color = ICostTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = primaryRightText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = ICostTextPrimary,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * 1:1 iCost 全屏「账户详情」页面（对齐 10_asset_detail_real.png）
 */
@Composable
private fun ICostWalletDetailFullScreen(
    wallet: WalletEntity,
    allWallets: List<WalletEntity>,
    walletTransactions: List<TransactionEntity>,
    onBack: () -> Unit,
    onEditWallet: () -> Unit,
    onReconcileBalance: () -> Unit = {},
    onRepayOrTransfer: () -> Unit,
    onAddRecordWithWallet: () -> Unit,
    onDeleteWallet: () -> Unit,
    onClickTransaction: (TransactionEntity) -> Unit,
    hideAmounts: Boolean = false
) {
    BackHandler(onBack = onBack)

    val walletMap = remember(allWallets) { allWallets.associateBy { it.id } }
    val nowCal = remember { Calendar.getInstance() }
    var selectedYear by remember { mutableIntStateOf(nowCal.get(Calendar.YEAR)) }
    var selectedMonth by remember { mutableIntStateOf(nowCal.get(Calendar.MONTH) + 1) }
    var showMoreMenu by remember { mutableStateOf(false) }

    val monthList = remember(walletTransactions, selectedYear, selectedMonth) {
        val c = Calendar.getInstance()
        walletTransactions.filter { tx ->
            c.timeInMillis = tx.timestamp
            c.get(Calendar.YEAR) == selectedYear && (c.get(Calendar.MONTH) + 1) == selectedMonth
        }
    }

    val groupedByDay = remember(monthList) {
        val fmt = SimpleDateFormat("MM/dd EEEE", Locale.CHINA)
        monthList.groupBy { fmt.format(Date(it.timestamp)) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ICostBgGray)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 顶部栏：`<` 返回 + 居中「账户详情」 + 右侧 `•••`
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ICostCircleIconButton(onClick = onBack, size = 42.dp) {
                        ICostAssetImage(
                            assetFile = "record_back_icon.webp",
                            contentDescription = "返回",
                            tint = ICostTextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = "账户详情",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextPrimary
                    )
                    Box {
                        ICostCircleIconButton(onClick = { showMoreMenu = true }, size = 42.dp) {
                            Icon(Icons.Default.MoreHoriz, contentDescription = "更多", tint = ICostTextPrimary)
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            containerColor = ICostCardWhite
                        ) {
                            DropdownMenuItem(
                                text = { Text("核对余额", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                                onClick = {
                                    showMoreMenu = false
                                    onReconcileBalance()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("编辑账户信息", fontSize = 14.sp) },
                                onClick = {
                                    showMoreMenu = false
                                    onEditWallet()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("删除账户", fontSize = 14.sp, color = ICostCoralRed) },
                                onClick = {
                                    showMoreMenu = false
                                    onDeleteWallet()
                                }
                            )
                        }
                    }
                }
            }

            // 主账户白卡
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = ICostCardWhite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    WalletBrandAvatar(wallet, size = 34.dp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = formatWalletDisplayName(wallet),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ICostTextPrimary
                                    )
                                }
                                if (wallet.type.isLiability) {
                                    Text(
                                        text = daysUntilRepaymentText(wallet.repaymentDay),
                                        fontSize = 12.sp,
                                        color = ICostTextSecondary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (wallet.type.isLiability) "当前欠款" else "账户余额",
                                    fontSize = 12.sp,
                                    color = ICostTextSecondary
                                )
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFFF3F4F6),
                                    modifier = Modifier
                                        .heightIn(min = 32.dp)
                                        .iCostPressScale(pressedScale = 0.92f) {
                                            onReconcileBalance()
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ICostAssetImage(
                                            assetFile = "asset_pen.webp",
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "核对余额",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ICostTextPrimary
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = formatMoneyCents(
                                    if (wallet.type.isLiability) wallet.balanceCents.coerceAtLeast(0L) else wallet.balanceCents,
                                    wallet.currency,
                                    hideAmounts
                                ),
                                fontFamily = ICDinFontFamily,
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostTextPrimary
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            if (wallet.type.isLiability && wallet.creditLimitCents != null) {
                                val avail = (wallet.creditLimitCents - wallet.balanceCents).coerceAtLeast(0L)
                                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    Text(
                                        text = "总额度 ${formatMoneyCents(wallet.creditLimitCents, wallet.currency, hideAmounts)}",
                                        fontSize = 13.sp,
                                        color = ICostTextSecondary
                                    )
                                    Text(
                                        text = "可用额度 ${formatMoneyCents(avail, wallet.currency, hideAmounts)}",
                                        fontSize = 13.sp,
                                        color = ICostTextSecondary
                                    )
                                }
                            } else {
                                Text(
                                    text = if (wallet.includeInNetWorth) "计入净资产统计 · ${wallet.currency}" else "不计入净资产 · ${wallet.currency}",
                                    fontSize = 13.sp,
                                    color = ICostTextSecondary
                                )
                            }
                        }
                    }
                }
            }

            // 年月选择栏
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selectedYear}年${selectedMonth}月",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextPrimary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    MonthStepButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        description = "上个月",
                        onClick = {
                            if (selectedMonth == 1) { selectedMonth = 12; selectedYear -= 1 } else selectedMonth -= 1
                        }
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    MonthStepButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        description = "下个月",
                        onClick = {
                            if (selectedMonth == 12) { selectedMonth = 1; selectedYear += 1 } else selectedMonth += 1
                        }
                    )
                }
            }

            // 按日分组流水列表
            if (groupedByDay.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = ICostCardWhite,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "这个月还没有账单",
                            fontSize = 14.sp,
                            color = ICostTextSecondary,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }
            } else {
                groupedByDay.forEach { (dayLabel, dayItems) ->
                    // 当日支出按账户币种汇总，与下方每笔金额的币种保持一致
                    val dayExpWalletCents = cnyCentsToCurrency(
                        dayItems
                            .filter { it.type == TransactionType.EXPENSE && it.sourceWalletId == wallet.id }
                            .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) },
                        wallet.currency
                    )
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(dayLabel, fontSize = 12.sp, color = ICostTextSecondary)
                                Text("支出 ${formatMoneyCents(dayExpWalletCents, wallet.currency, hideAmounts)}", fontSize = 12.sp, color = ICostTextSecondary)
                            }

                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = ICostCardWhite,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column {
                                    dayItems.forEachIndexed { idx, tx ->
                                        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(tx.timestamp))
                                        val isRefunded = tx.type == TransactionType.EXPENSE && tx.refundedAmountCents > 0L
                                        // 退款后净支出按交易原币种展示（refundedAmountCents 为本位币）
                                        val netExpTxCents = if (tx.exchangeRateToCny > 0.0 && tx.currency != "CNY") {
                                            (tx.amountCents - (tx.refundedAmountCents / tx.exchangeRateToCny).roundToLong()).coerceAtLeast(0L)
                                        } else {
                                            (tx.amountCents - tx.refundedAmountCents).coerceAtLeast(0L)
                                        }
                                        val otherWallet = if (tx.sourceWalletId == wallet.id) {
                                            tx.targetWalletId?.let { walletMap[it] }
                                        } else {
                                            tx.sourceWalletId?.let { walletMap[it] }
                                        }

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onClickTransaction(tx) }
                                                .padding(horizontal = 16.dp, vertical = 13.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            ICostCategoryBadge(
                                                category = tx.category,
                                                type = tx.type,
                                                size = 38.dp
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                val rowTitle = when (tx.type) {
                                                    TransactionType.REPAYMENT, TransactionType.TRANSFER ->
                                                        if (tx.targetWalletId == wallet.id) "从${otherWallet?.name ?: "外部账户"}转入"
                                                        else "转至${otherWallet?.name ?: "目标账户"}"
                                                    TransactionType.REFUND_OFFSET -> "退款入账"
                                                    else -> tx.category
                                                }
                                                Text(rowTitle, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ICostTextPrimary)
                                                Text(
                                                    text = if (tx.merchant.isNotBlank()) "$timeStr · ${tx.merchant}" else timeStr,
                                                    fontSize = 12.sp,
                                                    color = ICostTextSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            if (isRefunded) {
                                                Text(
                                                    text = formatMoneyCents(tx.amountCents, tx.currency, hideAmounts),
                                                    fontSize = 12.sp,
                                                    color = ICostTextSecondary,
                                                    textDecoration = TextDecoration.LineThrough
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "-${formatMoneyCents(netExpTxCents, tx.currency, hideAmounts)}",
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = ICostCoralRed
                                                )
                                            } else {
                                                val sign = when (tx.type) {
                                                    TransactionType.EXPENSE, TransactionType.LOAN_OUT -> "-"
                                                    TransactionType.INCOME -> "+"
                                                    else -> ""
                                                }
                                                val col = when (tx.type) {
                                                    TransactionType.EXPENSE, TransactionType.LOAN_OUT -> ICostCoralRed
                                                    TransactionType.INCOME -> ICostGreenIncome
                                                    TransactionType.REFUND_OFFSET -> ICostAmberGold
                                                    else -> ICostTextPrimary
                                                }
                                                Text(
                                                    text = "$sign${formatMoneyCents(tx.amountCents, tx.currency, hideAmounts)}",
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = col
                                                )
                                            }
                                        }
                                        if (idx < dayItems.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(start = 66.dp, end = 16.dp),
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

        // 1:1 底部悬浮操作胶囊（对齐 10_asset_detail_real.png：「还款 / 记一笔」或「转账 / 记一笔」）
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = ICostCardWhite,
            shadowElevation = 8.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 14.dp)
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable { onRepayOrTransfer() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (wallet.type.isLiability) "还款" else "转账",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostBlueAccent
                    )
                }
                Box(
                    modifier = Modifier
                        .width(0.8.dp)
                        .height(22.dp)
                        .background(ICostDivider)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable { onAddRecordWithWallet() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "记一笔",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostBlueAccent
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthStepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = CircleShape, color = ICostCardWhite, modifier = Modifier.size(30.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = description, tint = ICostTextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * 1:1 iCost 全屏「选择类型」页面（对齐 11_add_asset_real.png）
 */
@Composable
private fun ICostSelectAccountTypeFullScreen(
    onBack: () -> Unit,
    onSelectPreset: (AccountBrandOption) -> Unit
) {
    BackHandler(onBack = onBack)

    val sections = remember {
        listOf(
            "资金账户" to ICostAccountBrandCatalog.filter {
                it.code in setOf("CASH", "DEBIT_CARD", "ALIPAY", "YUEBAO", "WECHAT", "WECHAT_LQT", "UNIONPAY", "DOUYIN", "MEITUAN", "QQ")
            },
            "信用账户" to ICostAccountBrandCatalog.filter {
                it.defaultType == WalletType.CREDIT
            },
            "热门银行" to ICostAccountBrandCatalog.filter {
                it.assetFile.startsWith("bank_")
            },
            "理财与卡券账户" to ICostAccountBrandCatalog.filter {
                it.code in setOf("SAVING", "FUND", "STOCK", "PROVIDENT", "MEDICAL_CARD", "TRANSIT_CARD", "MEAL_CARD", "MEMBER_CARD")
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ICostBgGray)
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ICostCircleIconButton(onClick = onBack, size = 42.dp) {
                ICostAssetImage(
                    assetFile = "record_back_icon.webp",
                    contentDescription = "返回",
                    tint = ICostTextPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        item {
            Text(
                text = "选择类型",
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ICostTextPrimary
            )
        }

        sections.forEach { (sectionTitle, options) ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = sectionTitle,
                        fontSize = 13.sp,
                        color = ICostTextSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = ICostCardWhite,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            options.forEachIndexed { idx, opt ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectPreset(opt) }
                                        .padding(vertical = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ICostAssetImage(
                                        assetFile = opt.assetFile,
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = opt.label,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = ICostTextPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = ICostTextMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                if (idx < options.lastIndex) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletEditorSheet(
    existing: WalletEntity?,
    preset: AccountBrandOption?,
    onDismiss: () -> Unit,
    onConfirm: (WalletEntity) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: preset?.label?.substringBefore(" / ") ?: "") }
    var isCreditAccount by remember {
        mutableStateOf((existing?.type ?: preset?.defaultType ?: WalletType.DEBIT_BANK).isLiability)
    }
    var subTypeLabel by remember { mutableStateOf(existing?.subTypeLabel ?: preset?.subTypeLabel ?: "储蓄卡") }
    val brandCode = existing?.brandCode ?: preset?.code ?: "ALIPAY"
    var currency by remember { mutableStateOf(CurrencyCode.fromCode(existing?.currency).name) }
    var balanceStr by remember {
        mutableStateOf(existing?.let { String.format(Locale.US, "%.2f", it.balanceCents / 100.0) } ?: "0.00")
    }
    var limitStr by remember {
        mutableStateOf(existing?.creditLimitCents?.let { String.format(Locale.US, "%.2f", it / 100.0) } ?: "20000")
    }
    var repaymentDayStr by remember { mutableStateOf((existing?.repaymentDay ?: 15).toString()) }
    var last4 by remember { mutableStateOf(existing?.cardLast4 ?: "") }
    var aliases by remember { mutableStateOf(existing?.aliases ?: "") }
    var includeInNetWorth by remember { mutableStateOf(existing?.includeInNetWorth ?: true) }

    // 输入校验：不合法时给出提示并禁用保存，不再静默按 0 处理
    val balanceValue = parseYuanInput(balanceStr)
    val limitValue = parseYuanInput(limitStr)
    val repaymentDay = repaymentDayStr.toIntOrNull()
    val nameError = if (name.isBlank()) "请输入账户名称" else null
    val balanceError = when {
        balanceValue == null -> "请输入有效金额"
        isCreditAccount && balanceValue < 0.0 -> "欠款不能小于 0"
        else -> null
    }
    val limitError = if (isCreditAccount && (limitValue == null || limitValue < 0.0)) "请输入有效额度" else null
    val repaymentDayError = if (isCreditAccount && (repaymentDay == null || repaymentDay !in 1..31)) "1 到 31" else null
    val last4Error = if (last4.isNotEmpty() && last4.length != 4) "需要 4 位数字" else null
    val canSave = nameError == null && balanceError == null && limitError == null &&
        repaymentDayError == null && last4Error == null

    ICostSmoothBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ICostBgGray,
        showDragHandle = false
    ) { requestDismiss ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            ICostSheetHeader(
                title = if (existing == null) "新建账户" else "编辑账户",
                subtitle = subTypeLabel,
                onClose = requestDismiss
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    ICostFormCard {
                        if (preset?.assetFile?.startsWith("bank_") == true) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("信用卡账户", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ICostTextPrimary)
                                Switch(
                                    checked = isCreditAccount,
                                    onCheckedChange = {
                                        isCreditAccount = it
                                        subTypeLabel = if (it) "信用卡" else "储蓄卡"
                                    },
                                    colors = iCostUnifiedSwitchColors()
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ICostFormField(
                                value = name,
                                onValueChange = { name = it },
                                label = "账户名称",
                                placeholder = "如：支付宝、招商银行",
                                isError = nameError != null,
                                supportingText = nameError,
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            ICostFormField(
                                value = balanceStr,
                                onValueChange = { balanceStr = sanitizeAmountInput(it, allowNegative = !isCreditAccount) },
                                label = if (isCreditAccount) "当前欠款（元）" else "当前余额（元）",
                                placeholder = "0.00",
                                isError = balanceError != null,
                                supportingText = balanceError,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FieldLabel("币种")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CurrencyCode.entries.forEach { c ->
                                    ICostChoicePill(
                                        label = c.name,
                                        selected = currency == c.name,
                                        onClick = { currency = c.name },
                                        modifier = Modifier.weight(1f),
                                        compactHorizontalPadding = true
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    ICostFormCard {
                        if (isCreditAccount) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ICostFormField(
                                    value = limitStr,
                                    onValueChange = { limitStr = sanitizeAmountInput(it) },
                                    label = "信用额度（元）",
                                    placeholder = "20000",
                                    isError = limitError != null,
                                    supportingText = limitError,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                ICostFormField(
                                    value = repaymentDayStr,
                                    onValueChange = { v -> repaymentDayStr = v.filter { it.isDigit() }.take(2) },
                                    label = "每月还款日",
                                    placeholder = "1–31",
                                    isError = repaymentDayError != null,
                                    supportingText = repaymentDayError,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ICostFormField(
                                value = last4,
                                onValueChange = { v -> last4 = v.filter { it.isDigit() }.take(4) },
                                label = "卡号后四位（选填）",
                                placeholder = "如 3354",
                                isError = last4Error != null,
                                supportingText = last4Error,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            ICostFormField(
                                value = aliases,
                                onValueChange = { aliases = it },
                                label = "识别关键词（选填）",
                                placeholder = "多个用逗号分隔",
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }

                        HorizontalDivider(color = ICostDivider)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("计入净资产", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ICostTextPrimary)
                                Text("开启后该账户余额或欠款会汇总到首页净资产", fontSize = 11.sp, color = ICostTextSecondary)
                            }
                            Switch(
                                checked = includeInNetWorth,
                                onCheckedChange = { includeInNetWorth = it },
                                colors = iCostUnifiedSwitchColors()
                            )
                        }
                    }
                }

                item {
                    Button(
                        onClick = {
                            if (!canSave || balanceValue == null) return@Button
                            val finalType = if (isCreditAccount) {
                                WalletType.CREDIT
                            } else {
                                val base = preset?.defaultType ?: existing?.type ?: WalletType.DEBIT_BANK
                                if (base.isLiability) WalletType.DEBIT_BANK else base
                            }
                            val balCents = (balanceValue * 100.0).roundToLong()
                            val limCents = if (isCreditAccount) ((limitValue ?: 0.0) * 100.0).roundToLong() else null
                            onConfirm(
                                WalletEntity(
                                    id = existing?.id ?: 0L,
                                    name = name.trim(),
                                    type = finalType,
                                    subTypeLabel = subTypeLabel,
                                    brandCode = brandCode,
                                    currency = currency,
                                    balanceCents = balCents,
                                    creditLimitCents = limCents,
                                    cardLast4 = last4.trim().takeIf { it.isNotEmpty() },
                                    aliases = aliases.trim(),
                                    repaymentDay = if (isCreditAccount) repaymentDay else existing?.repaymentDay,
                                    includeInNetWorth = includeInNetWorth,
                                    colorHex = existing?.colorHex ?: 0xFFE65540
                                )
                            )
                            requestDismiss()
                        },
                        enabled = canSave,
                        colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("保存", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

/**
 * 核对余额：输入真实余额（或欠款），可选择补记一笔差额账单，或只修改账面余额。
 */
@Composable
private fun WalletBalanceReconcileSheet(
    wallet: WalletEntity,
    onDismiss: () -> Unit,
    onConfirmReconcile: (actualYuan: Double, recordAdjustmentTx: Boolean, note: String) -> Unit
) {
    val bookYuan = wallet.balanceCents / 100.0
    var actualInput by remember(wallet) {
        mutableStateOf(String.format(Locale.US, "%.2f", bookYuan))
    }
    var recordAdjustmentTx by remember { mutableStateOf(true) }
    var customNote by remember { mutableStateOf("") }

    // 账务引擎只接受不小于 0 的余额，这里提前提示，而不是静默改成 0
    val parsedActualYuan = parseYuanInput(actualInput)
    val inputError = when {
        parsedActualYuan == null -> "请输入有效金额"
        parsedActualYuan < 0.0 -> "金额不能小于 0"
        else -> null
    }
    val actualCents = parsedActualYuan?.let { (it * 100.0).roundToLong() } ?: wallet.balanceCents
    val diffCents = if (inputError == null) actualCents - wallet.balanceCents else 0L
    val absDiffText = formatMoneyCents(kotlin.math.abs(diffCents), wallet.currency)

    val diffSummary = when {
        inputError != null -> "请先输入正确的金额"
        diffCents == 0L -> "和账面一致，不需要调整"
        !wallet.type.isLiability && diffCents < 0L ->
            if (recordAdjustmentTx) "比账面少 $absDiffText，将补记一笔支出" else "比账面少 $absDiffText，将直接修改余额"
        !wallet.type.isLiability && diffCents > 0L ->
            if (recordAdjustmentTx) "比账面多 $absDiffText，将补记一笔收入" else "比账面多 $absDiffText，将直接修改余额"
        wallet.type.isLiability && diffCents > 0L ->
            if (recordAdjustmentTx) "欠款比账面多 $absDiffText，将补记一笔消费" else "欠款比账面多 $absDiffText，将直接修改欠款"
        else ->
            if (recordAdjustmentTx) "欠款比账面少 $absDiffText，将补记一笔欠款减少" else "欠款比账面少 $absDiffText，将直接修改欠款"
    }

    ICostSmoothBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ICostBgGray,
        showDragHandle = false
    ) { requestDismiss ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            ICostSheetHeader(
                title = "核对余额",
                subtitle = "${wallet.name} · 账面${if (wallet.type.isLiability) "欠款" else "余额"} ${formatMoneyCents(wallet.balanceCents, wallet.currency)}",
                onClose = requestDismiss
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ICostFormCard {
                    ICostFormField(
                        value = actualInput,
                        onValueChange = { actualInput = sanitizeAmountInput(it) },
                        label = if (wallet.type.isLiability) "实际欠款（元）" else "实际余额（元）",
                        placeholder = "0.00",
                        isError = inputError != null,
                        supportingText = inputError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (diffCents == 0L) ICostBgGray else Color(0xFFEEF4FF),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = diffSummary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (diffCents == 0L) ICostTextSecondary else ICostBlueAccent,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }

                    HorizontalDivider(color = ICostDivider)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                            Text("补记差额账单", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ICostTextPrimary)
                            Text("开启后会记一笔差额账单，方便日后查账；关闭则只修改账面金额", fontSize = 11.sp, color = ICostTextSecondary)
                        }
                        Switch(
                            checked = recordAdjustmentTx,
                            onCheckedChange = { recordAdjustmentTx = it },
                            colors = iCostUnifiedSwitchColors()
                        )
                    }

                    if (recordAdjustmentTx && diffCents != 0L) {
                        ICostFormField(
                            value = customNote,
                            onValueChange = { customNote = it },
                            label = "备注（选填）",
                            placeholder = "如：月底对账平账",
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }

                Button(
                    onClick = {
                        parsedActualYuan?.let {
                            onConfirmReconcile(it, recordAdjustmentTx, customNote.trim())
                            requestDismiss()
                        }
                    },
                    enabled = inputError == null && diffCents != 0L,
                    colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = if (recordAdjustmentTx) "确认并补记账单" else "只修改账面金额",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}
