package com.moji.ai.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.data.local.RecurringRuleEntity
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType
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
import com.moji.ai.ui.theme.ICostCircleIconButton
import com.moji.ai.ui.theme.ICostCoralRed
import com.moji.ai.ui.theme.ICostDivider
import com.moji.ai.ui.theme.ICostGreenIncome
import com.moji.ai.ui.theme.ICostTextMuted
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.WalletBrandAvatar
import com.moji.ai.ui.theme.daysUntilRepaymentText
import com.moji.ai.ui.theme.formatMoneyCents
import com.moji.ai.ui.theme.formatWalletDisplayName
import com.moji.ai.ui.theme.iCostPressScale
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

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
    onOpenSettingsPage: () -> Unit = {},
    onSubpageActiveChange: (Boolean) -> Unit = {}
) {
    val totalAssetsCnyCents = wallets.filter { !it.type.isLiability && it.includeInNetWorth }.sumOf { w ->
        (w.balanceCents * CurrencyCode.fromCode(w.currency).defaultRateToCny).roundToLong()
    }
    val totalLiabilityCnyCents = wallets.filter { it.type.isLiability && it.includeInNetWorth }.sumOf { w ->
        (w.balanceCents * CurrencyCode.fromCode(w.currency).defaultRateToCny).roundToLong()
    }
    val netWorthCnyCents = totalAssetsCnyCents - totalLiabilityCnyCents

    val totalLoanInCents = transactions.filter { it.type == TransactionType.LOAN_IN }.sumOf { it.amountCnyCents }
    val totalLoanOutCents = transactions.filter { it.type == TransactionType.LOAN_OUT }.sumOf { it.amountCnyCents }

    val creditWallets = wallets.filter { it.type.isLiability }
    val assetWallets = wallets.filter { !it.type.isLiability }

    var creditExpanded by remember { mutableStateOf(true) }
    var assetExpanded by remember { mutableStateOf(true) }
    var recurringExpanded by remember { mutableStateOf(false) }
    var showTopMoreMenu by remember { mutableStateOf(false) }

    var inspectingWallet by remember { mutableStateOf<WalletEntity?>(null) }
    var showSelectTypeFullScreen by remember { mutableStateOf(false) }
    var selectedPresetForEditor by remember { mutableStateOf<AccountBrandOption?>(null) }
    var editingWallet by remember { mutableStateOf<WalletEntity?>(null) }

    val subpageActive = inspectingWallet != null || showSelectTypeFullScreen
    androidx.compose.runtime.LaunchedEffect(subpageActive) {
        onSubpageActiveChange(subpageActive)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onSubpageActiveChange(false) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 110.dp),
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
                                text = { Text("添加资产或信用账户", fontSize = 14.sp) },
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
                                text = { Text(if (recurringExpanded) "隐藏周期账单" else "查看周期账单 (${recurringRules.size})", fontSize = 14.sp) },
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

            // 3. 净资产大卡片（ICDIN 36sp 金额 + 原生眼睛显隐图标 money_eye_open.webp）
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("净资产", fontSize = 14.sp, color = ICostTextSecondary)
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .iCostPressScale(pressedScale = 0.85f, onClick = onToggleHideAmounts),
                                contentAlignment = Alignment.Center
                            ) {
                                ICostAssetImage(
                                    assetFile = if (hideAmounts) "money_eye_close.webp" else "money_eye_open.webp",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

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
                                text = "总负债 ${formatMoneyCents(-totalLiabilityCnyCents, "CNY", hideAmounts)}",
                                fontSize = 13.sp,
                                color = ICostTextSecondary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            // 4. 1:1 复刻 iCost 并排双卡片：「总借入」(total_borrowing.webp) 与「总借出」(total_lending.webp)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = ICostCardWhite,
                        modifier = Modifier
                            .weight(1f)
                            .iCostPressScale(pressedScale = 0.96f) {
                                onQuickActionWithWallet(TransactionType.LOAN_IN, assetWallets.firstOrNull()?.id, null)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("总借入", fontSize = 13.sp, color = ICostTextSecondary)
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
                        modifier = Modifier
                            .weight(1f)
                            .iCostPressScale(pressedScale = 0.96f) {
                                onQuickActionWithWallet(TransactionType.LOAN_OUT, assetWallets.firstOrNull()?.id, null)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("总借出", fontSize = 13.sp, color = ICostTextSecondary)
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
                                rightSummary = "欠款: ${formatMoneyCents(totalLiabilityCnyCents, "CNY", hideAmounts)}",
                                expanded = creditExpanded,
                                onToggle = { creditExpanded = !creditExpanded }
                            )

                            AnimatedVisibility(
                                visible = creditExpanded,
                                enter = expandVertically(
                                    animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
                                ) + fadeIn(tween(180)),
                                exit = shrinkVertically(tween(180)) + fadeOut(tween(130))
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    creditWallets.forEachIndexed { idx, w ->
                                        ICostAccountItemRow(
                                            wallet = w,
                                            hideAmounts = hideAmounts,
                                            onClick = { inspectingWallet = w }
                                        )
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
                                rightSummary = "余额: ${formatMoneyCents(totalAssetsCnyCents, "CNY", hideAmounts)}",
                                expanded = assetExpanded,
                                onToggle = { assetExpanded = !assetExpanded }
                            )

                            AnimatedVisibility(
                                visible = assetExpanded,
                                enter = expandVertically(
                                    animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
                                ) + fadeIn(tween(180)),
                                exit = shrinkVertically(tween(180)) + fadeOut(tween(130))
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    assetWallets.forEachIndexed { idx, w ->
                                        ICostAccountItemRow(
                                            wallet = w,
                                            hideAmounts = hideAmounts,
                                            onClick = { inspectingWallet = w }
                                        )
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

            // 7. 周期账单折叠卡片
            if (recurringExpanded && recurringRules.isNotEmpty()) {
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
                            recurringRules.forEach { rule ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "${rule.title} (${rule.frequency.displayName})",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = ICostTextPrimary
                                        )
                                        Text(
                                            "${formatMoneyCents(rule.amountCents, rule.currency)} · ${rule.category}",
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
                        onDeleteWallet(activeWallet)
                        inspectingWallet = null
                    },
                    onClickTransaction = onSelectTransaction
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
                    showSelectTypeFullScreen = false
                    selectedPresetForEditor = option
                }
            )
        }
    }

    // 账户信息表单弹窗（新建或编辑）
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
            }
        )
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
            daysUntilRepaymentText(wallet.repaymentDay)
        } else {
            wallet.subTypeLabel.ifBlank { wallet.type.displayName }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .iCostPressScale(pressedScale = 0.98f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WalletBrandAvatar(wallet = wallet, size = 40.dp)

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp)
        ) {
            Text(
                text = fullTitle,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = ICostTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = leftSubtitle,
                fontSize = 12.sp,
                color = ICostTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            val primaryRightText = if (wallet.type.isLiability) {
                if (wallet.balanceCents > 0L) {
                    formatMoneyCents(-wallet.balanceCents, wallet.currency, hideAmounts)
                } else {
                    formatMoneyCents(0L, wallet.currency, hideAmounts)
                }
            } else {
                formatMoneyCents(wallet.balanceCents, wallet.currency, hideAmounts)
            }

            Text(
                text = primaryRightText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = ICostTextPrimary,
                maxLines = 1,
                softWrap = false
            )

            if (wallet.type.isLiability && wallet.creditLimitCents != null) {
                Spacer(modifier = Modifier.height(2.dp))
                val availableCents = (wallet.creditLimitCents - wallet.balanceCents).coerceAtLeast(0L)
                Text(
                    text = "可用额度: ${formatMoneyCents(availableCents, wallet.currency, hideAmounts)}",
                    fontSize = 11.sp,
                    color = ICostTextSecondary,
                    maxLines = 1,
                    softWrap = false
                )
            } else if (wallet.currency != "CNY") {
                Spacer(modifier = Modifier.height(2.dp))
                val cnyCents = (wallet.balanceCents * CurrencyCode.fromCode(wallet.currency).defaultRateToCny).roundToLong()
                Text(
                    text = "约 ${formatMoneyCents(cnyCents, "CNY", hideAmounts)}",
                    fontSize = 11.sp,
                    color = ICostTextSecondary,
                    maxLines = 1,
                    softWrap = false
                )
            }
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
    onRepayOrTransfer: () -> Unit,
    onAddRecordWithWallet: () -> Unit,
    onDeleteWallet: () -> Unit,
    onClickTransaction: (TransactionEntity) -> Unit
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
                                text = { Text("编辑账户信息", fontSize = 14.sp) },
                                onClick = {
                                    showMoreMenu = false
                                    onEditWallet()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("删除该账户", fontSize = 14.sp, color = ICostCoralRed) },
                                onClick = {
                                    showMoreMenu = false
                                    onDeleteWallet()
                                }
                            )
                        }
                    }
                }
            }

            // 主账户白卡（含右下方淡银联水印 asset_bill_bank.webp）
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = ICostCardWhite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        ICostAssetImage(
                            assetFile = "asset_bill_bank.webp",
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 12.dp, bottom = 8.dp)
                                .size(76.dp)
                        )

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

                            Text(
                                text = if (wallet.type.isLiability) "当前欠款" else "账户余额",
                                fontSize = 12.sp,
                                color = ICostTextSecondary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = formatMoneyCents(wallet.balanceCents, wallet.currency),
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
                                        text = "总额度 ${formatMoneyCents(wallet.creditLimitCents, wallet.currency)}",
                                        fontSize = 13.sp,
                                        color = ICostTextSecondary
                                    )
                                    Text(
                                        text = "可用额度 ${formatMoneyCents(avail, wallet.currency)}",
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
                    Surface(
                        onClick = {
                            if (selectedMonth == 1) { selectedMonth = 12; selectedYear -= 1 } else selectedMonth -= 1
                        },
                        shape = CircleShape,
                        color = ICostCardWhite,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = ICostTextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        onClick = {
                            if (selectedMonth == 12) { selectedMonth = 1; selectedYear += 1 } else selectedMonth += 1
                        },
                        shape = CircleShape,
                        color = ICostCardWhite,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = ICostTextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
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
                            text = "当月暂无该账户的交易流水",
                            fontSize = 14.sp,
                            color = ICostTextSecondary,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }
            } else {
                groupedByDay.forEach { (dayLabel, dayItems) ->
                    val dayExp = dayItems
                        .filter { it.type == TransactionType.EXPENSE }
                        .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(dayLabel, fontSize = 12.sp, color = ICostTextSecondary)
                                Text("支出: ${formatMoneyCents(dayExp)}", fontSize = 12.sp, color = ICostTextSecondary)
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
                                        val netExp = (tx.amountCnyCents - tx.refundedAmountCents).coerceAtLeast(0L)
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
                                                    text = formatMoneyCents(tx.amountCents, tx.currency),
                                                    fontSize = 12.sp,
                                                    color = ICostTextSecondary,
                                                    textDecoration = TextDecoration.LineThrough
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "-${formatMoneyCents(netExp)}",
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
                                                    text = "$sign${formatMoneyCents(tx.amountCents, tx.currency)}",
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
    var currency by remember { mutableStateOf(existing?.currency ?: "CNY") }
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ICostCardWhite,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = if (existing == null) "新建账户" else "编辑账户",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = ICostTextPrimary
                )
            }

            if (preset?.assetFile?.startsWith("bank_") == true) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("设为信用卡账户（勾选为信用卡，关闭为储蓄卡）", fontSize = 13.sp, color = ICostTextSecondary)
                        Switch(
                            checked = isCreditAccount,
                            onCheckedChange = {
                                isCreditAccount = it
                                subTypeLabel = if (it) "信用卡" else "储蓄卡"
                            }
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("账户名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = balanceStr,
                        onValueChange = { balanceStr = it },
                        label = { Text(if (isCreditAccount) "当前待还欠款 (元)" else "当前余额 (元)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = currency,
                        onValueChange = { currency = it.uppercase() },
                        label = { Text("币种") },
                        modifier = Modifier.width(96.dp),
                        singleLine = true
                    )
                }
            }

            if (isCreditAccount) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = limitStr,
                            onValueChange = { limitStr = it },
                            label = { Text("授信总额度 (元)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = repaymentDayStr,
                            onValueChange = { repaymentDayStr = it },
                            label = { Text("每月还款日") },
                            modifier = Modifier.width(110.dp),
                            singleLine = true
                        )
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = last4,
                        onValueChange = { last4 = it },
                        label = { Text("卡号后四位 (选填)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = aliases,
                        onValueChange = { aliases = it },
                        label = { Text("AI匹配词 (选填)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("计入净资产统计", fontSize = 14.sp)
                    Switch(checked = includeInNetWorth, onCheckedChange = { includeInNetWorth = it })
                }
            }

            item {
                Button(
                    onClick = {
                        if (name.isBlank()) return@Button
                        val finalType = if (isCreditAccount) {
                            WalletType.CREDIT
                        } else {
                            preset?.defaultType ?: existing?.type ?: WalletType.DEBIT_BANK
                        }
                        val balCents = ((balanceStr.toDoubleOrNull() ?: 0.0) * 100.0).roundToLong()
                        val limCents = if (isCreditAccount) ((limitStr.toDoubleOrNull() ?: 0.0) * 100.0).roundToLong() else null
                        onConfirm(
                            WalletEntity(
                                id = existing?.id ?: 0L,
                                name = name.trim(),
                                type = finalType,
                                subTypeLabel = subTypeLabel,
                                brandCode = brandCode,
                                currency = currency.trim().ifEmpty { "CNY" },
                                balanceCents = balCents,
                                creditLimitCents = limCents,
                                cardLast4 = last4.trim().takeIf { it.isNotEmpty() },
                                aliases = aliases.trim(),
                                repaymentDay = repaymentDayStr.toIntOrNull()?.coerceIn(1, 31),
                                includeInNetWorth = includeInNetWorth,
                                colorHex = existing?.colorHex ?: 0xFFE65540
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("保存账户", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
