package com.moji.ai.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.data.local.RecurringRuleEntity
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType
import com.moji.ai.ui.theme.ICostBgGray
import com.moji.ai.ui.theme.ICostBlueAccent
import com.moji.ai.ui.theme.ICostCardWhite
import com.moji.ai.ui.theme.ICostCoralRed
import com.moji.ai.ui.theme.ICostDivider
import com.moji.ai.ui.theme.ICostGreenIncome
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.WalletBrandAvatar
import com.moji.ai.ui.theme.daysUntilRepaymentText
import com.moji.ai.ui.theme.formatMoneyCents
import com.moji.ai.ui.theme.formatWalletDisplayName
import com.moji.ai.ui.theme.resolveTransactionBadgeColor
import java.text.SimpleDateFormat
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
    onTriggerRuleNow: (RecurringRuleEntity) -> Unit
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

    var inspectingWallet by remember { mutableStateOf<WalletEntity?>(null) }
    var editingWallet by remember { mutableStateOf<WalletEntity?>(null) }
    var showCreateWalletSheet by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. 顶部标题栏：资产 + 右侧新建账户圆形按钮
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "资产",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = ICostTextPrimary
                )
                Surface(
                    onClick = { showCreateWalletSheet = true },
                    shape = CircleShape,
                    color = ICostCardWhite,
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Add, contentDescription = "添加账户", tint = ICostTextPrimary, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }

        // 2. 净资产大卡片（带小眼睛隐藏/显示切换）
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("净资产", fontSize = 15.sp, color = ICostTextSecondary, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = onToggleHideAmounts,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = if (hideAmounts) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "隐藏或显示金额",
                                tint = ICostTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = formatMoneyCents(netWorthCnyCents, "CNY", hideAmounts),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = ICostTextPrimary,
                        maxLines = 1,
                        softWrap = false
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Text(
                            text = "总资产  ${formatMoneyCents(totalAssetsCnyCents, "CNY", hideAmounts)}",
                            fontSize = 14.sp,
                            color = ICostTextSecondary,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = "总负债  ${formatMoneyCents(totalLiabilityCnyCents, "CNY", hideAmounts)}",
                            fontSize = 14.sp,
                            color = ICostTextSecondary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // 3. 1:1 复刻 iCost 并排双卡片：「总借入」与「总借出」
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    onClick = {
                        onQuickActionWithWallet(TransactionType.LOAN_IN, assetWallets.firstOrNull()?.id, null)
                    },
                    shape = RoundedCornerShape(20.dp),
                    color = ICostCardWhite,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF52525B)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("总借入", fontSize = 13.sp, color = ICostTextSecondary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                formatMoneyCents(totalLoanInCents, "CNY", hideAmounts),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostTextPrimary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                Surface(
                    onClick = {
                        onQuickActionWithWallet(TransactionType.LOAN_OUT, assetWallets.firstOrNull()?.id, null)
                    },
                    shape = RoundedCornerShape(20.dp),
                    color = ICostCardWhite,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF34C759)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("总借出", fontSize = 13.sp, color = ICostTextSecondary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                formatMoneyCents(totalLoanOutCents, "CNY", hideAmounts),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostTextPrimary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }

        // 4. 可折叠分组一：信用账户 (信用卡 / 花呗 / 京东白条)
        if (creditWallets.isNotEmpty()) {
            item {
                CollapsibleGroupHeader(
                    title = "信用账户",
                    count = creditWallets.size,
                    rightSummary = "欠款: ${formatMoneyCents(totalLiabilityCnyCents, "CNY", hideAmounts)}",
                    expanded = creditExpanded,
                    onToggle = { creditExpanded = !creditExpanded }
                )
            }

            item {
                AnimatedVisibility(visible = creditExpanded) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
                                        modifier = Modifier.padding(start = 70.dp, end = 16.dp),
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

        // 5. 可折叠分组二：资金账户 (微信零钱 / 支付宝余额宝 / 储蓄卡)
        if (assetWallets.isNotEmpty()) {
            item {
                CollapsibleGroupHeader(
                    title = "资金账户",
                    count = assetWallets.size,
                    rightSummary = "余额: ${formatMoneyCents(totalAssetsCnyCents, "CNY", hideAmounts)}",
                    expanded = assetExpanded,
                    onToggle = { assetExpanded = !assetExpanded }
                )
            }

            item {
                AnimatedVisibility(visible = assetExpanded) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
                                        modifier = Modifier.padding(start = 70.dp, end = 16.dp),
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

        // 6. 可折叠分组三：周期性自动入账（房租/订阅）
        if (recurringRules.isNotEmpty()) {
            item {
                CollapsibleGroupHeader(
                    title = "周期账单",
                    count = recurringRules.size,
                    rightSummary = if (recurringExpanded) "收起" else "展开",
                    expanded = recurringExpanded,
                    onToggle = { recurringExpanded = !recurringExpanded }
                )
            }

            item {
                AnimatedVisibility(visible = recurringExpanded) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = ICostCardWhite),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            recurringRules.forEach { rule ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
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
    }

    // 账户详情与流水管理弹窗（点击任意账户行弹出）
    val currentInspecting = inspectingWallet?.let { target -> wallets.firstOrNull { it.id == target.id } ?: target }
    if (currentInspecting != null) {
        WalletDetailBottomSheet(
            wallet = currentInspecting,
            walletTransactions = transactions.filter {
                it.sourceWalletId == currentInspecting.id || it.targetWalletId == currentInspecting.id
            },
            onDismiss = { inspectingWallet = null },
            onEditWallet = {
                inspectingWallet = null
                editingWallet = currentInspecting
            },
            onRepayCredit = {
                val creditId = currentInspecting.id
                inspectingWallet = null
                onQuickActionWithWallet(TransactionType.REPAYMENT, assetWallets.firstOrNull()?.id, creditId)
            },
            onTransferFrom = {
                val fromId = currentInspecting.id
                inspectingWallet = null
                onQuickActionWithWallet(TransactionType.TRANSFER, fromId, assetWallets.firstOrNull { it.id != fromId }?.id)
            },
            onAddExpenseWithWallet = {
                val wid = currentInspecting.id
                inspectingWallet = null
                onQuickActionWithWallet(TransactionType.EXPENSE, wid, null)
            },
            onDeleteWallet = {
                onDeleteWallet(currentInspecting)
                inspectingWallet = null
            },
            onClickTransaction = { tx ->
                inspectingWallet = null
                onSelectTransaction(tx)
            }
        )
    }

    // 新建或编辑账户弹窗
    if (showCreateWalletSheet || editingWallet != null) {
        WalletEditorSheet(
            existing = editingWallet,
            onDismiss = {
                showCreateWalletSheet = false
                editingWallet = null
            },
            onConfirm = { saved ->
                onSaveWallet(saved)
                showCreateWalletSheet = false
                editingWallet = null
            }
        )
    }
}

@Composable
private fun CollapsibleGroupHeader(
    title: String,
    count: Int,
    rightSummary: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onToggle() }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = ICostTextPrimary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "($count)",
                fontSize = 14.sp,
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
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = ICostTextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 1:1 复刻 iCost 的账户行布局（彻底杜绝文字竖排折叠换行 Bug！）
 */
@Composable
private fun ICostAccountItemRow(
    wallet: WalletEntity,
    hideAmounts: Boolean,
    onClick: () -> Unit
) {
    val fullTitle = remember(wallet.name, wallet.cardLast4) {
        formatWalletDisplayName(wallet)
    }

    val leftSubtitle = remember(wallet) {
        val tag = wallet.subTypeLabel.ifBlank { wallet.type.shortTag }
        if (wallet.type.isLiability) {
            "[$tag] ${daysUntilRepaymentText(wallet.repaymentDay)}"
        } else {
            "[$tag] ${if (wallet.includeInNetWorth) "计入净资产" else "不计入净资产"}"
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WalletBrandAvatar(wallet = wallet, size = 42.dp)

        Spacer(modifier = Modifier.width(12.dp))

        // 左侧标题与副标题：严格单行省略，绝不挤压右侧金额！
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp)
        ) {
            Text(
                text = fullTitle,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = ICostTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = leftSubtitle,
                fontSize = 12.sp,
                color = ICostTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 右侧金额与可用额度：严格 softWrap = false，绝不竖向折行！
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
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = ICostTextPrimary,
                maxLines = 1,
                softWrap = false
            )

            if (wallet.type.isLiability && wallet.creditLimitCents != null) {
                Spacer(modifier = Modifier.height(3.dp))
                val availableCents = (wallet.creditLimitCents - wallet.balanceCents).coerceAtLeast(0L)
                Text(
                    text = "可用额度: ${formatMoneyCents(availableCents, wallet.currency, hideAmounts)}",
                    fontSize = 12.sp,
                    color = ICostTextSecondary,
                    maxLines = 1,
                    softWrap = false
                )
            } else if (wallet.currency != "CNY") {
                Spacer(modifier = Modifier.height(3.dp))
                val cnyCents = (wallet.balanceCents * CurrencyCode.fromCode(wallet.currency).defaultRateToCny).roundToLong()
                Text(
                    text = "约 ${formatMoneyCents(cnyCents, "CNY", hideAmounts)}",
                    fontSize = 12.sp,
                    color = ICostTextSecondary,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletDetailBottomSheet(
    wallet: WalletEntity,
    walletTransactions: List<TransactionEntity>,
    onDismiss: () -> Unit,
    onEditWallet: () -> Unit,
    onRepayCredit: () -> Unit,
    onTransferFrom: () -> Unit,
    onAddExpenseWithWallet: () -> Unit,
    onDeleteWallet: () -> Unit,
    onClickTransaction: (TransactionEntity) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ICostBgGray,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 账户信息头卡
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = ICostCardWhite,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WalletBrandAvatar(wallet, size = 40.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(formatWalletDisplayName(wallet), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    wallet.subTypeLabel.ifBlank { wallet.type.displayName },
                                    fontSize = 12.sp,
                                    color = ICostTextSecondary
                                )
                            }
                        }
                        IconButton(onClick = onEditWallet) {
                            Icon(Icons.Default.Edit, contentDescription = "编辑账户", tint = ICostTextPrimary)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = if (wallet.type.isLiability) "当前待还欠款" else "当前账户余额",
                        fontSize = 13.sp,
                        color = ICostTextSecondary
                    )
                    Text(
                        text = if (wallet.type.isLiability && wallet.balanceCents > 0L) {
                            formatMoneyCents(-wallet.balanceCents, wallet.currency)
                        } else {
                            formatMoneyCents(wallet.balanceCents, wallet.currency)
                        },
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = ICostTextPrimary
                    )

                    if (wallet.type.isLiability && wallet.creditLimitCents != null) {
                        val avail = (wallet.creditLimitCents - wallet.balanceCents).coerceAtLeast(0L)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "总额度 ${formatMoneyCents(wallet.creditLimitCents, wallet.currency)}  ·  可用额度 ${formatMoneyCents(avail, wallet.currency)}",
                            fontSize = 13.sp,
                            color = ICostTextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 快捷操作按钮行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (wallet.type.isLiability) {
                            Button(
                                onClick = onRepayCredit,
                                colors = ButtonDefaults.buttonColors(containerColor = ICostCoralRed),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.CreditCard, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("立即还款", fontWeight = FontWeight.Bold)
                            }
                        } else {
                            OutlinedButton(
                                onClick = onTransferFrom,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("转账/提现", fontWeight = FontWeight.Bold)
                            }
                        }

                        Button(
                            onClick = onAddExpenseWithWallet,
                            colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("记一笔", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 该账户关联流水
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("账户收支明细 (${walletTransactions.size}笔)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = onDeleteWallet) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = ICostCoralRed, modifier = Modifier.size(16.dp))
                    Text("删除账户", color = ICostCoralRed, fontSize = 12.sp)
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp),
                contentPadding = PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (walletTransactions.isEmpty()) {
                    item {
                        Surface(shape = RoundedCornerShape(16.dp), color = ICostCardWhite, modifier = Modifier.fillMaxWidth()) {
                            Text("暂无该账户的交易记录", fontSize = 13.sp, color = ICostTextSecondary, modifier = Modifier.padding(18.dp))
                        }
                    }
                } else {
                    items(walletTransactions) { tx ->
                        val dateStr = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(tx.timestamp))
                        Surface(
                            onClick = { onClickTransaction(tx) },
                            shape = RoundedCornerShape(16.dp),
                            color = ICostCardWhite,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(tx.category, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(
                                        "$dateStr${if (tx.merchant.isNotBlank()) " · ${tx.merchant}" else ""}",
                                        fontSize = 12.sp,
                                        color = ICostTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = formatMoneyCents(tx.amountCents, tx.currency),
                                    fontWeight = FontWeight.Bold,
                                    color = resolveTransactionBadgeColor(tx.type)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WalletEditorSheet(
    existing: WalletEntity?,
    onDismiss: () -> Unit,
    onConfirm: (WalletEntity) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: WalletType.CREDIT) }
    var subTypeLabel by remember { mutableStateOf(existing?.subTypeLabel ?: "信用卡") }
    var brandCode by remember { mutableStateOf(existing?.brandCode ?: "CMB") }
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    if (existing == null) "添加资产或信用账户" else "编辑账户信息",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            item {
                Text("账户类型：", fontSize = 13.sp, color = ICostTextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val presets = listOf(
                        Triple(WalletType.CREDIT, "信用卡", "CMB"),
                        Triple(WalletType.CREDIT, "蚂蚁花呗", "HUABEI"),
                        Triple(WalletType.CREDIT, "京东白条", "JD"),
                        Triple(WalletType.DIGITAL_WALLET, "微信支付", "WECHAT"),
                        Triple(WalletType.DIGITAL_WALLET, "支付宝", "ALIPAY"),
                        Triple(WalletType.DEBIT_BANK, "储蓄卡", "CMB"),
                        Triple(WalletType.CASH_OTHER, "现金/外币", "DEFAULT")
                    )
                    presets.forEach { (wt, label, brand) ->
                        FilterChip(
                            selected = subTypeLabel == label,
                            onClick = {
                                type = wt
                                subTypeLabel = label
                                brandCode = brand
                                if (existing == null && name.isBlank()) {
                                    name = label
                                }
                            },
                            label = { Text(label) }
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("账户名称 (如：招商银行信用卡 / 微粒贷)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = balanceStr,
                        onValueChange = { balanceStr = it },
                        label = { Text(if (type.isLiability) "当前待还欠款 (元)" else "当前可用余额 (元)") },
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

            if (type.isLiability) {
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
                        label = { Text("银行卡后四位 (选填)") },
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
                        val balCents = ((balanceStr.toDoubleOrNull() ?: 0.0) * 100.0).roundToLong()
                        val limCents = if (type.isLiability) ((limitStr.toDoubleOrNull() ?: 0.0) * 100.0).roundToLong() else null
                        onConfirm(
                            WalletEntity(
                                id = existing?.id ?: 0L,
                                name = name.trim(),
                                type = type,
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
