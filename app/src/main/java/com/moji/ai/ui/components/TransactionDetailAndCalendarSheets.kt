package com.moji.ai.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CategoryCatalog
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.TransactionType
import com.moji.ai.ui.theme.ICDinFontFamily
import com.moji.ai.ui.theme.ICostAmberGold
import com.moji.ai.ui.theme.ICostAssetImage
import com.moji.ai.ui.theme.ICostBgGray
import com.moji.ai.ui.theme.ICostBlueAccent
import com.moji.ai.ui.theme.ICostCardWhite
import com.moji.ai.ui.theme.ICostCategoryBadge
import com.moji.ai.ui.theme.ICostCellGray
import com.moji.ai.ui.theme.ICostCircleIconButton
import com.moji.ai.ui.theme.ICostCoralRed
import com.moji.ai.ui.theme.ICostDivider
import com.moji.ai.ui.theme.ICostGreenIncome
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.WalletBrandAvatar
import com.moji.ai.ui.theme.formatMoneyCents
import com.moji.ai.ui.theme.formatWalletDisplayName
import com.moji.ai.ui.theme.iCostHorizontalSwipeTrigger
import com.moji.ai.ui.theme.iCostPressScale
import com.moji.ai.ui.theme.resolveTransactionBadgeColor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 通用二次确认弹窗（删除、退款、报销入账等不可轻易撤回的操作）。
 */
@Composable
internal fun ICostConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    dismissText: String = "取消"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ICostCardWhite,
        title = { Text(title, fontWeight = FontWeight.ExtraBold) },
        text = { Text(message, fontSize = 14.sp, color = ICostTextSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmText,
                    color = if (destructive) ICostCoralRed else ICostBlueAccent,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissText) }
        }
    )
}

/** 拆分账单可选的支出分类（主分类及「主-子」二级分类），与记账面板保持同一份目录 */
private val splitCategoryOptions: List<String> by lazy {
    CategoryCatalog.expenseCategories.flatMap { cat ->
        listOf(cat.name) + cat.subCategories.map { "${cat.name}-${it.name}" }
    }
}

/**
 * 1:1 iCost 全屏「账单详情」页面（对齐 05_detail_full.png）
 *
 * @param onDuplicate 传入后在详情页显示「复制一笔」操作（首页左滑菜单空间有限，复制入口放在这里）
 */
@Composable
fun TransactionDetailSheet(
    tx: TransactionEntity,
    wallets: List<WalletEntity>,
    onDismiss: () -> Unit,
    onEdit: (TransactionEntity) -> Unit,
    onRefund: (expenseId: Long, refundYuan: Double, receiveWalletId: Long?, note: String) -> Unit,
    onSettleReimburse: (expenseId: Long, receiveWalletId: Long?) -> Unit,
    onDelete: (Long) -> Unit,
    onSaveAsTemplate: (TransactionEntity) -> Unit = {},
    onSplitTransaction: (expenseId: Long, categoryA: String, amountAYuan: Double, categoryB: String, noteB: String) -> Unit = { _, _, _, _, _ -> },
    onDuplicate: ((TransactionEntity) -> Unit)? = null
) {
    BackHandler(onBack = onDismiss)

    val walletMap = remember(wallets) { wallets.associateBy { it.id } }
    val sourceWallet = tx.sourceWalletId?.let { walletMap[it] }
    val targetWallet = tx.targetWalletId?.let { walletMap[it] }
    val badgeColor = resolveTransactionBadgeColor(tx.type)

    val fullTimeStr = remember(tx.timestamp) {
        SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA).format(Date(tx.timestamp))
    }

    val remainingRefundableCents = (tx.amountCnyCents - tx.refundedAmountCents).coerceAtLeast(0L)
    val canRefund = tx.type == TransactionType.EXPENSE && remainingRefundableCents > 0L
    val canReimburse = tx.type == TransactionType.EXPENSE && tx.reimbursable && !tx.reimbursed
    var showRefundDialog by remember { mutableStateOf(false) }
    var showSplitDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSettleDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ICostBgGray)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        ICostCircleIconButton(onClick = onDismiss, size = 42.dp) {
            ICostAssetImage(
                assetFile = "record_back_icon.webp",
                tint = ICostTextPrimary,
                contentDescription = "返回",
                modifier = Modifier.size(16.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "账单详情",
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            color = ICostTextPrimary,
            modifier = Modifier.padding(horizontal = 2.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 主详情白卡片
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = ICostCardWhite,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ICostCategoryBadge(
                    category = tx.category,
                    type = tx.type,
                    size = 46.dp,
                    badgeColor = badgeColor
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = when (tx.type) {
                        TransactionType.REFUND_OFFSET -> "退款入账"
                        TransactionType.REPAYMENT -> "转至${targetWallet?.name ?: "信用账户"}"
                        TransactionType.TRANSFER -> "转至${targetWallet?.name ?: "目标账户"}"
                        else -> tx.category
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = ICostTextPrimary
                )

                Spacer(modifier = Modifier.height(4.dp))

                val prefix = when (tx.type) {
                    TransactionType.EXPENSE, TransactionType.LOAN_OUT -> "-"
                    TransactionType.INCOME, TransactionType.LOAN_IN -> "+"
                    else -> ""
                }
                val amountColor = when (tx.type) {
                    TransactionType.EXPENSE, TransactionType.LOAN_OUT -> ICostCoralRed
                    TransactionType.REFUND_OFFSET -> ICostAmberGold
                    TransactionType.INCOME, TransactionType.LOAN_IN -> ICostGreenIncome
                    else -> ICostTextPrimary
                }
                Text(
                    text = "$prefix${formatMoneyCents(tx.amountCents, tx.currency)}",
                    fontFamily = ICDinFontFamily,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = amountColor
                )

                if (tx.refundedAmountCents > 0L) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "已退款 ${formatMoneyCents(tx.refundedAmountCents)} · 实际支出 ${formatMoneyCents(remainingRefundableCents)}",
                        fontSize = 12.sp,
                        color = ICostAmberGold,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))

                DetailKeyValueRow("记录时间", fullTimeStr)
                DetailKeyValueRow("账本", tx.ledgerName)
                DetailKeyValueRow(
                    "资金账户",
                    when (tx.type) {
                        TransactionType.TRANSFER, TransactionType.REPAYMENT ->
                            "${formatWalletDisplayName(sourceWallet, "转出")} → ${formatWalletDisplayName(targetWallet, "转入")}"
                        TransactionType.INCOME, TransactionType.REFUND_OFFSET ->
                            formatWalletDisplayName(targetWallet ?: sourceWallet)
                        else -> formatWalletDisplayName(sourceWallet)
                    }
                )
                if (tx.discountCents > 0L) {
                    DetailKeyValueRow("优惠金额", formatMoneyCents(tx.discountCents))
                }
                if (tx.feeCents > 0L) {
                    DetailKeyValueRow("手续费", formatMoneyCents(tx.feeCents))
                }
                if (tx.reimbursable) {
                    DetailKeyValueRow("报销状态", if (tx.reimbursed) "已报销" else "待报销")
                }
                if (tx.tags.isNotBlank()) {
                    DetailKeyValueRow("标签", "#${tx.tags}")
                }
                val merchant = tx.merchant.trim()
                val note = tx.note.trim()
                if (merchant.isNotEmpty() && note.isNotEmpty() && merchant != note) {
                    DetailKeyValueRow("商户", merchant)
                    DetailKeyValueRow("备注", note)
                } else if (merchant.isNotEmpty() || note.isNotEmpty()) {
                    DetailKeyValueRow("备注", merchant.ifEmpty { note })
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 1:1 iCost 双卡操作行：左「编辑 / 删除」，右「退款 / 存为模板」（对齐 05_detail_full.png）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DetailActionPairCard(
                modifier = Modifier.weight(1f),
                leftAsset = "detail_edit.webp",
                leftLabel = "编辑",
                leftColor = ICostBlueAccent,
                onLeft = {
                    onDismiss()
                    onEdit(tx)
                },
                rightAsset = "detail_delete.webp",
                rightLabel = "删除",
                rightColor = ICostCoralRed,
                onRight = { showDeleteConfirm = true }
            )
            DetailActionPairCard(
                modifier = Modifier.weight(1f),
                leftAsset = "detail_refund.webp",
                leftLabel = "退款",
                leftColor = if (canRefund) ICostBlueAccent else ICostTextSecondary,
                onLeft = if (canRefund) ({ showRefundDialog = true }) else null,
                rightAsset = "detail_template.webp",
                rightLabel = "存模板",
                rightColor = Color(0xFFF59E0B),
                onRight = { onSaveAsTemplate(tx) }
            )
        }

        // 待报销账单：单独的「报销到账」入口，与退款互不影响
        if (canReimburse) {
            Spacer(modifier = Modifier.height(10.dp))
            DetailWideActionButton(
                assetFile = "detail_claim.webp",
                label = "报销到账",
                onClick = { showSettleDialog = true }
            )
        }

        if (tx.type == TransactionType.EXPENSE && tx.amountCents >= 2L) {
            Spacer(modifier = Modifier.height(10.dp))
            DetailWideActionButton(
                assetFile = "setting_category.webp",
                label = "拆分账单",
                onClick = { showSplitDialog = true }
            )
        }

        if (onDuplicate != null) {
            Spacer(modifier = Modifier.height(10.dp))
            DetailWideActionButton(
                assetFile = "detail_template.webp",
                label = "再记一笔相同的",
                onClick = { onDuplicate(tx) }
            )
        }

        Spacer(modifier = Modifier.height(36.dp))
    }

    if (showDeleteConfirm) {
        ICostConfirmDialog(
            title = "删除这笔账单？",
            message = "删除后，相关账户余额会同步恢复。",
            confirmText = "删除",
            onConfirm = {
                showDeleteConfirm = false
                onDelete(tx.id)
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    if (showSettleDialog) {
        // 收款账户：默认原支付账户，可改选任一资产账户（非信用/负债账户）
        val receiveOptions = remember(wallets, tx.sourceWalletId) {
            (listOfNotNull(sourceWallet) + wallets.filter { !it.type.isLiability }).distinctBy { it.id }
        }
        var receiveWalletId by remember { mutableStateOf(tx.sourceWalletId ?: receiveOptions.firstOrNull()?.id) }
        val settleAmountCents = remainingRefundableCents
        AlertDialog(
            onDismissRequest = { showSettleDialog = false },
            containerColor = ICostCardWhite,
            title = { Text("确认报销到账？", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "报销款 ${formatMoneyCents(settleAmountCents)} 将记入下方选中的账户，这笔账单会标记为已报销。",
                        fontSize = 14.sp,
                        color = ICostTextSecondary
                    )
                    Column(
                        modifier = Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        receiveOptions.forEach { w ->
                            val selected = w.id == receiveWalletId
                            Surface(
                                onClick = { receiveWalletId = w.id },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) Color(0xFFEAF2FF) else ICostCellGray,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    WalletBrandAvatar(w, size = 28.dp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = formatWalletDisplayName(w),
                                        fontSize = 14.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) ICostBlueAccent else ICostTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (w.id == tx.sourceWalletId) {
                                        Text("原支付账户", fontSize = 11.sp, color = ICostTextSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSettleDialog = false
                    onSettleReimburse(tx.id, receiveWalletId)
                }) {
                    Text("确认到账", color = ICostBlueAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettleDialog = false }) { Text("取消") }
            }
        )
    }

    if (showRefundDialog) {
        var refundYuanStr by remember {
            mutableStateOf(
                String.format(Locale.US, "%.2f", remainingRefundableCents / 100.0)
            )
        }
        var refundNote by remember { mutableStateOf("") }
        val refundCents = parseYuanToCents(refundYuanStr)
        val refundError = when {
            refundYuanStr.isBlank() -> "请输入退款金额"
            refundCents == null -> "请输入正确的金额"
            refundCents <= 0L -> "退款金额需大于 0"
            refundCents > remainingRefundableCents -> "最多可退 ${formatMoneyCents(remainingRefundableCents)}"
            else -> null
        }
        val refundWalletName = formatWalletDisplayName(sourceWallet, "原账户")

        AlertDialog(
            onDismissRequest = { showRefundDialog = false },
            containerColor = ICostCardWhite,
            title = { Text("退款", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "退款将退回到「$refundWalletName」，并从这笔账单的支出中扣除。最多可退 ${formatMoneyCents(remainingRefundableCents)}。",
                        fontSize = 13.sp,
                        color = ICostTextSecondary
                    )
                    OutlinedTextField(
                        value = refundYuanStr,
                        onValueChange = { refundYuanStr = sanitizeMoneyInput(it) },
                        label = { Text("退款金额（元）") },
                        isError = refundError != null && refundYuanStr.isNotBlank(),
                        supportingText = refundError?.let { msg -> { Text(msg) } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    OutlinedTextField(
                        value = refundNote,
                        onValueChange = { refundNote = it },
                        label = { Text("备注（选填）") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = refundError == null,
                    onClick = {
                        val cents = refundCents ?: return@TextButton
                        onRefund(tx.id, cents / 100.0, tx.sourceWalletId, refundNote.trim())
                        showRefundDialog = false
                    }
                ) {
                    Text(
                        "确认退款",
                        color = if (refundError == null) ICostCoralRed else ICostTextSecondary,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showRefundDialog = false }) { Text("取消") }
            }
        )
    }

    if (showSplitDialog) {
        val totalCents = tx.amountCents
        var categoryA by remember(tx.id) { mutableStateOf(tx.category) }
        var amountAStr by remember(tx.id) {
            mutableStateOf(
                String.format(Locale.US, "%.2f", (totalCents / 2L).coerceAtLeast(1L) / 100.0)
            )
        }
        var categoryB by remember(tx.id) { mutableStateOf("日用") }
        var noteB by remember(tx.id) { mutableStateOf("") }

        val amountACents = parseYuanToCents(amountAStr)
        val remainBCents = if (amountACents != null) totalCents - amountACents else totalCents
        val splitError = when {
            amountAStr.isBlank() -> "请输入第一笔的金额"
            amountACents == null -> "请输入正确的金额"
            amountACents <= 0L -> "金额需大于 0"
            amountACents >= totalCents -> "需小于原账单金额 ${formatMoneyCents(totalCents, tx.currency)}"
            categoryA.isBlank() || categoryB.isBlank() -> "请选择分类"
            else -> null
        }

        AlertDialog(
            onDismissRequest = { showSplitDialog = false },
            containerColor = ICostCardWhite,
            title = { Text("拆分账单", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "把 ${formatMoneyCents(totalCents, tx.currency)} 拆成两笔不同分类的账单，总金额不变。",
                        fontSize = 12.sp,
                        color = ICostTextSecondary
                    )
                    Text("第一笔", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ICostTextPrimary)
                    SplitCategoryPicker(
                        label = "分类",
                        value = categoryA,
                        onValueChange = { categoryA = it }
                    )
                    OutlinedTextField(
                        value = amountAStr,
                        onValueChange = { amountAStr = sanitizeMoneyInput(it) },
                        label = { Text("金额（元）") },
                        isError = splitError != null && amountAStr.isNotBlank() && amountACents != null &&
                            (amountACents <= 0L || amountACents >= totalCents),
                        supportingText = splitError?.let { msg -> { Text(msg) } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    Text(
                        "第二笔 · 自动分配剩余 ${formatMoneyCents(remainBCents.coerceAtLeast(0L), tx.currency)}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextPrimary
                    )
                    SplitCategoryPicker(
                        label = "分类",
                        value = categoryB,
                        onValueChange = { categoryB = it }
                    )
                    OutlinedTextField(
                        value = noteB,
                        onValueChange = { noteB = it },
                        label = { Text("备注（选填）") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = splitError == null,
                    onClick = {
                        val cents = amountACents ?: return@TextButton
                        onSplitTransaction(tx.id, categoryA.trim(), cents / 100.0, categoryB.trim(), noteB.trim())
                        showSplitDialog = false
                    }
                ) {
                    Text(
                        "确认拆分",
                        color = if (splitError == null) ICostBlueAccent else ICostTextSecondary,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showSplitDialog = false }) { Text("取消") }
            }
        )
    }
}

/** 分类选择：点开下拉列表，从记账面板同一份分类目录中挑选 */
@Composable
private fun SplitCategoryPicker(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(10.dp),
            color = ICostCellGray,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, fontSize = 13.sp, color = ICostTextSecondary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = value.replace("-", " · ").ifBlank { "请选择" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = ICostTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "选择分类",
                    tint = ICostTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = ICostCardWhite,
            modifier = Modifier.heightIn(max = 320.dp)
        ) {
            val options = if (value.isNotBlank() && value !in splitCategoryOptions) {
                listOf(value) + splitCategoryOptions
            } else splitCategoryOptions
            options.forEach { option ->
                val isSub = option.contains('-')
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (isSub) "    ${option.substringAfter('-')}" else option,
                            fontSize = 14.sp,
                            fontWeight = if (option == value) FontWeight.Bold else if (isSub) FontWeight.Normal else FontWeight.Medium,
                            color = if (option == value) ICostBlueAccent else ICostTextPrimary
                        )
                    },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** 详情页白色双按钮卡片（中间细分隔线）；onLeft 为 null 时左侧按钮置灰不可点 */
@Composable
private fun DetailActionPairCard(
    modifier: Modifier,
    leftAsset: String,
    leftLabel: String,
    leftColor: Color,
    onLeft: (() -> Unit)?,
    rightAsset: String,
    rightLabel: String,
    rightColor: Color,
    onRight: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = ICostCardWhite,
        modifier = modifier.height(52.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .let { mod -> if (onLeft != null) mod.iCostPressScale(pressedScale = 0.92f, onClick = onLeft) else mod },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ICostAssetImage(
                    assetFile = leftAsset,
                    tint = leftColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = leftLabel,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    color = leftColor
                )
            }

            Box(
                modifier = Modifier
                    .width(0.8.dp)
                    .height(20.dp)
                    .background(ICostDivider)
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .iCostPressScale(pressedScale = 0.92f, onClick = onRight),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ICostAssetImage(
                    assetFile = rightAsset,
                    tint = rightColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = rightLabel,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    color = rightColor
                )
            }
        }
    }
}

@Composable
private fun DetailWideActionButton(
    assetFile: String,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = ICostCardWhite,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .iCostPressScale(pressedScale = 0.95f, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ICostAssetImage(
                assetFile = assetFile,
                tint = ICostBlueAccent,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = ICostBlueAccent
            )
        }
    }
}

@Composable
private fun DetailKeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = ICostTextSecondary)
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            value,
            fontSize = 14.sp,
            color = ICostTextPrimary,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 1:1 iCost 全屏「收支日历」页面（对齐 06_calendar_full.png）
 *
 * 推荐传入 [allTransactions]（实时全量账单）与 [currentLedger]，日历会按显示的年月与账本自行筛选，
 * 切换月份、编辑或删除账单后都能实时刷新。[monthTransactions] 仅为兼容旧调用保留（只含打开时的当月快照）。
 */
@Composable
fun MonthlyCalendarSheet(
    year: Int,
    month: Int,
    monthTransactions: List<TransactionEntity> = emptyList(),
    onDismiss: () -> Unit,
    onSelectTransaction: (TransactionEntity) -> Unit,
    allTransactions: List<TransactionEntity>? = null,
    currentLedger: String? = null
) {
    BackHandler(onBack = onDismiss)

    val sourceTransactions = remember(allTransactions, currentLedger, monthTransactions) {
        if (allTransactions != null) {
            if (currentLedger == null) allTransactions else allTransactions.filter { it.ledgerName == currentLedger }
        } else {
            monthTransactions
        }
    }

    fun daysWithTransactions(y: Int, m: Int): Set<Int> {
        val c = Calendar.getInstance()
        return sourceTransactions.mapNotNullTo(mutableSetOf()) { tx ->
            c.timeInMillis = tx.timestamp
            if (c.get(Calendar.YEAR) == y && (c.get(Calendar.MONTH) + 1) == m) c.get(Calendar.DAY_OF_MONTH) else null
        }
    }

    /** 切到某月时默认选中的日期：当月选今天，其他月份选最后一笔账单所在日，没有账单则选 1 号 */
    fun defaultDayFor(y: Int, m: Int): Int {
        val now = Calendar.getInstance()
        if (now.get(Calendar.YEAR) == y && now.get(Calendar.MONTH) + 1 == m) return now.get(Calendar.DAY_OF_MONTH)
        return daysWithTransactions(y, m).maxOrNull() ?: 1
    }

    var displayYear by remember(year) { mutableIntStateOf(year) }
    var displayMonth by remember(month) { mutableIntStateOf(month) }
    var selectedDay by remember(year, month) { mutableIntStateOf(defaultDayFor(year, month)) }

    fun goToMonth(y: Int, m: Int, day: Int? = null) {
        displayYear = y
        displayMonth = m
        selectedDay = day ?: defaultDayFor(y, m)
    }

    fun shiftMonth(delta: Int) {
        val total = displayYear * 12 + (displayMonth - 1) + delta
        goToMonth(total / 12, total % 12 + 1)
    }

    val cal = remember(displayYear, displayMonth) {
        Calendar.getInstance().apply {
            clear()
            set(displayYear, displayMonth - 1, 1)
        }
    }
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    // 周一=0..周日=6
    val firstDayOffset = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val safeSelectedDay = selectedDay.coerceIn(1, daysInMonth)

    val byDay = remember(sourceTransactions, displayYear, displayMonth) {
        val c = Calendar.getInstance()
        sourceTransactions.filter { tx ->
            c.timeInMillis = tx.timestamp
            c.get(Calendar.YEAR) == displayYear && (c.get(Calendar.MONTH) + 1) == displayMonth
        }.groupBy { tx ->
            c.timeInMillis = tx.timestamp
            c.get(Calendar.DAY_OF_MONTH)
        }
    }

    val selectedList = byDay[safeSelectedDay].orEmpty()
    val dayExpenseCents = selectedList
        .filter { it.type == TransactionType.EXPENSE }
        .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }
    val dayIncomeCents = selectedList
        .filter { it.type == TransactionType.INCOME }
        .sumOf { it.amountCnyCents }
    val dayRefundCents = selectedList
        .filter { it.type == TransactionType.REFUND_OFFSET }
        .sumOf { it.amountCnyCents }

    val selectedDateLabel = remember(displayYear, displayMonth, safeSelectedDay) {
        val c = Calendar.getInstance().apply {
            clear()
            set(displayYear, displayMonth - 1, safeSelectedDay)
        }
        SimpleDateFormat("MM/dd EEEE", Locale.CHINA).format(c.time)
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
            ICostCircleIconButton(onClick = onDismiss, size = 42.dp) {
                ICostAssetImage(
                    assetFile = "record_back_icon.webp",
                    tint = ICostTextPrimary,
                    contentDescription = "返回",
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        item {
            Text(
                text = "收支日历",
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ICostTextPrimary
            )
        }

        // 年月切换栏 + 今日
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${displayYear}年${displayMonth}月",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextPrimary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    CalendarArrowButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "上个月",
                        onClick = { shiftMonth(-1) }
                    )
                    CalendarArrowButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "下个月",
                        onClick = { shiftMonth(1) }
                    )
                }

                Surface(
                    onClick = {
                        val now = Calendar.getInstance()
                        goToMonth(
                            now.get(Calendar.YEAR),
                            now.get(Calendar.MONTH) + 1,
                            now.get(Calendar.DAY_OF_MONTH)
                        )
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFEBEBEB),
                    modifier = Modifier.heightIn(min = 40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 40.dp)) {
                        Text(
                            text = "今日",
                            fontSize = 13.sp,
                            color = Color(0xFF666666),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 14.dp)
                        )
                    }
                }
            }
        }

        // 1:1 iCost 星期表头 + 7 列圆角格子日历（支持右滑切上月 / 左滑切下月）
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = ICostCardWhite,
                modifier = Modifier
                    .fillMaxWidth()
                    .iCostHorizontalSwipeTrigger(
                        onSwipeRight = { shiftMonth(-1) },
                        onSwipeLeft = { shiftMonth(1) }
                    )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        listOf("一", "二", "三", "四", "五", "六", "日").forEach { w ->
                            Text(
                                text = w,
                                fontSize = 12.sp,
                                color = ICostTextSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    val totalCells = firstDayOffset + daysInMonth
                    val rows = (totalCells + 6) / 7
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (r in 0 until rows) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                for (c in 0 until 7) {
                                    val cellIndex = r * 7 + c
                                    val dayNum = cellIndex - firstDayOffset + 1
                                    if (dayNum in 1..daysInMonth) {
                                        val dayTxs = byDay[dayNum].orEmpty()
                                        val dayExp = dayTxs.filter { it.type == TransactionType.EXPENSE }
                                            .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }
                                        val dayInc = dayTxs.filter { it.type == TransactionType.INCOME }
                                            .sumOf { it.amountCnyCents }
                                        val isSelected = safeSelectedDay == dayNum
                                        val hasExp = dayExp > 0L

                                        val targetCellBg = when {
                                            isSelected -> ICostCoralRed
                                            hasExp -> Color(0xFFFDECE9)
                                            dayInc > 0L -> Color(0xFFE8F8F0)
                                            else -> Color(0xFFF7F7F8)
                                        }
                                        val cellBg by animateColorAsState(
                                            targetValue = targetCellBg,
                                            animationSpec = tween(180),
                                            label = "CalendarCellBg"
                                        )

                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(52.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(cellBg)
                                                .iCostPressScale(pressedScale = 0.90f) { selectedDay = dayNum }
                                                .padding(vertical = 4.dp, horizontal = 1.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = dayNum.toString(),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) Color.White else ICostTextPrimary
                                            )
                                            if (hasExp) {
                                                Text(
                                                    text = "-${formatCalendarCellAmount(dayExp)}",
                                                    fontSize = 10.sp,
                                                    color = if (isSelected) Color.White else ICostCoralRed,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            } else if (dayInc > 0L) {
                                                Text(
                                                    text = "+${formatCalendarCellAmount(dayInc)}",
                                                    fontSize = 10.sp,
                                                    color = if (isSelected) Color.White else ICostGreenIncome,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            } else {
                                                Spacer(modifier = Modifier.height(10.dp))
                                            }
                                        }
                                    } else {
                                        Spacer(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(52.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 当日收支三栏汇总卡（总支出 / 总收入 / 结余，外加退款说明）
        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = ICostCardWhite,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp, horizontal = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CalendarSummaryMetric("总支出", formatMoneyCents(dayExpenseCents), ICostCoralRed)
                    CalendarSummaryMetric("总收入", formatMoneyCents(dayIncomeCents), ICostGreenIncome)
                    if (dayRefundCents > 0L) {
                        CalendarSummaryMetric("退款", formatMoneyCents(dayRefundCents), ICostAmberGold)
                    } else {
                        CalendarSummaryMetric("结余", formatMoneyCents(dayIncomeCents - dayExpenseCents), ICostTextPrimary)
                    }
                }
            }
        }

        // 当日头部
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(selectedDateLabel, fontSize = 13.sp, color = ICostTextSecondary)
                Text("支出: ${formatMoneyCents(dayExpenseCents)}", fontSize = 13.sp, color = ICostTextSecondary)
            }
        }

        // 当日流水列表卡片
        if (selectedList.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ICostCardWhite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "这天没有账单",
                        fontSize = 14.sp,
                        color = ICostTextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 26.dp)
                    )
                }
            }
        } else {
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = ICostCardWhite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        selectedList.forEachIndexed { idx, item ->
                            val timeStr = remember(item.timestamp) {
                                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.timestamp))
                            }
                            val sym = CurrencyCode.fromCode(item.currency).symbol
                            val isRefunded = item.type == TransactionType.EXPENSE && item.refundedAmountCents > 0L
                            val netExp = (item.amountCnyCents - item.refundedAmountCents).coerceAtLeast(0L)
                            val subtitleExtra = item.merchant.ifBlank { item.note }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectTransaction(item) }
                                    .padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ICostCategoryBadge(
                                    category = item.category,
                                    type = item.type,
                                    size = 40.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (item.type == TransactionType.REFUND_OFFSET) "退款入账" else item.category,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = ICostTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (subtitleExtra.isNotBlank()) "$timeStr · $subtitleExtra" else timeStr,
                                        fontSize = 12.sp,
                                        color = ICostTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (isRefunded) {
                                    Text(
                                        text = String.format(Locale.US, "%s%,.2f", sym, item.amountCents / 100.0),
                                        fontSize = 12.sp,
                                        color = ICostTextSecondary,
                                        textDecoration = TextDecoration.LineThrough
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = String.format(Locale.US, "-¥%,.2f", netExp / 100.0),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = ICostCoralRed
                                    )
                                } else {
                                    val amtColor = resolveTransactionBadgeColor(item.type)
                                    val prefix = when (item.type) {
                                        TransactionType.EXPENSE, TransactionType.LOAN_OUT -> "-"
                                        TransactionType.INCOME, TransactionType.LOAN_IN -> "+"
                                        else -> ""
                                    }
                                    Text(
                                        text = "$prefix${formatMoneyCents(item.amountCents, item.currency)}",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = amtColor
                                    )
                                }
                            }
                            if (idx < selectedList.lastIndex) {
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

/**
 * 日历格子里的紧凑金额：1 万及以上用「万」；1000 及以上不显示小数；其余最多两位小数（去掉末尾 0）。
 */
private fun formatCalendarCellAmount(cents: Long): String {
    val yuan = cents / 100.0
    return when {
        yuan >= 10000.0 -> String.format(Locale.US, "%.1f", yuan / 10000.0).removeSuffix(".0") + "万"
        yuan >= 1000.0 -> String.format(Locale.US, "%.0f", yuan)
        else -> String.format(Locale.US, "%.2f", yuan).trimEnd('0').trimEnd('.')
    }
}

/** 日历月份切换箭头：可视 28dp 白色圆，触控区 40dp */
@Composable
private fun CalendarArrowButton(
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
private fun CalendarSummaryMetric(label: String, amountText: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = ICostTextSecondary)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = amountText,
            fontFamily = ICDinFontFamily,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}
