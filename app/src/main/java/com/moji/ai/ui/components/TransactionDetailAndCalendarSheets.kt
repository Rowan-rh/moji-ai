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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.TransactionType
import com.moji.ai.ui.theme.ICDinFontFamily
import com.moji.ai.ui.theme.ICostAmberGold
import com.moji.ai.ui.theme.ICostAssetImage
import com.moji.ai.ui.theme.ICostBgGray
import com.moji.ai.ui.theme.ICostBlueAccent
import com.moji.ai.ui.theme.ICostCardWhite
import com.moji.ai.ui.theme.ICostCategoryBadge
import com.moji.ai.ui.theme.ICostCircleIconButton
import com.moji.ai.ui.theme.ICostCoralRed
import com.moji.ai.ui.theme.ICostDivider
import com.moji.ai.ui.theme.ICostGreenIncome
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
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
 * 1:1 iCost 全屏「账单详情」页面（对齐 05_detail_full.png）
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
    onSaveAsTemplate: (TransactionEntity) -> Unit = {}
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
    var showRefundDialog by remember { mutableStateOf(false) }
    var templateSavedBanner by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ICostBgGray)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .padding(horizontal = 18.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        ICostCircleIconButton(onClick = onDismiss, size = 42.dp) {
            ICostAssetImage(
                assetFile = "record_back_icon.webp",
                tint = ICostTextPrimary,
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
                        text = "已退款 ${formatMoneyCents(tx.refundedAmountCents)} · 实际净支出 ${formatMoneyCents(remainingRefundableCents)}",
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
                            "${formatWalletDisplayName(sourceWallet, "转出")} ➔ ${formatWalletDisplayName(targetWallet, "转入")}"
                        TransactionType.INCOME, TransactionType.REFUND_OFFSET ->
                            formatWalletDisplayName(targetWallet ?: sourceWallet)
                        else -> formatWalletDisplayName(sourceWallet)
                    }
                )
                DetailKeyValueRow("优惠金额", formatMoneyCents(tx.discountCents))
                if (tx.feeCents > 0L) {
                    DetailKeyValueRow("手续费", formatMoneyCents(tx.feeCents))
                }
                if (tx.reimbursable) {
                    DetailKeyValueRow("报销状态", if (tx.reimbursed) "已报销入账" else "待报销")
                }
                if (tx.tags.isNotBlank()) {
                    DetailKeyValueRow("标签", "#${tx.tags}")
                }
                if (tx.merchant.isNotBlank()) {
                    DetailKeyValueRow("备注", tx.merchant)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 1:1 iCost 双卡操作行：左「编辑 / 删除」，右「退款 / 设模板」（对齐 05_detail_full.png）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = ICostCardWhite,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .iCostPressScale(pressedScale = 0.92f) {
                                onDismiss()
                                onEdit(tx)
                            }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ICostAssetImage(
                            assetFile = "detail_edit.webp",
                            tint = ICostBlueAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "编辑",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = ICostBlueAccent
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
                            .iCostPressScale(pressedScale = 0.92f) { onDelete(tx.id) }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ICostAssetImage(
                            assetFile = "detail_delete.webp",
                            tint = ICostCoralRed,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "删除",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = ICostCoralRed
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = ICostCardWhite,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val canRefund = tx.type == TransactionType.EXPENSE && remainingRefundableCents > 0L
                    val canReimburse = tx.reimbursable && !tx.reimbursed

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .let { mod ->
                                if (canRefund || canReimburse) {
                                    mod.iCostPressScale(pressedScale = 0.92f) {
                                        if (canReimburse) {
                                            onSettleReimburse(tx.id, tx.sourceWalletId)
                                        } else if (canRefund) {
                                            showRefundDialog = true
                                        }
                                    }
                                } else {
                                    mod
                                }
                            }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ICostAssetImage(
                            assetFile = "detail_refund.webp",
                            tint = if (canRefund || canReimburse) ICostBlueAccent else ICostTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (canReimburse) "报销" else "退款",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (canRefund || canReimburse) ICostBlueAccent else ICostTextSecondary
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
                            .iCostPressScale(pressedScale = 0.92f) {
                                onSaveAsTemplate(tx)
                                templateSavedBanner = true
                            }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ICostAssetImage(
                            assetFile = "detail_template.webp",
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "设模板",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFF59E0B)
                        )
                    }
                }
            }
        }

        if (templateSavedBanner) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ICostCardWhite,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "✓ 已将该笔账单存为快捷记账模板",
                    fontSize = 13.sp,
                    color = ICostGreenIncome,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
    }

    if (showRefundDialog) {
        var refundYuanStr by remember {
            mutableStateOf(String.format(Locale.US, "%.2f", remainingRefundableCents / 100.0))
        }
        var refundNote by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showRefundDialog = false },
            containerColor = ICostCardWhite,
            title = { Text("发起退款", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "退回金额将自动恢复原账户余额，并扣减本笔账单的净支出（全额退回则标记为「全额退款」）：",
                        fontSize = 13.sp,
                        color = ICostTextSecondary
                    )
                    OutlinedTextField(
                        value = refundYuanStr,
                        onValueChange = { refundYuanStr = it },
                        label = { Text("退款金额 (元)") },
                        singleLine = true
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
                TextButton(onClick = {
                    val yuan = refundYuanStr.toDoubleOrNull() ?: return@TextButton
                    onRefund(tx.id, yuan, tx.sourceWalletId, refundNote)
                    showRefundDialog = false
                }) {
                    Text("确认退款", color = ICostCoralRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRefundDialog = false }) { Text("取消") }
            }
        )
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
 */
@Composable
fun MonthlyCalendarSheet(
    year: Int,
    month: Int,
    monthTransactions: List<TransactionEntity>,
    onDismiss: () -> Unit,
    onSelectTransaction: (TransactionEntity) -> Unit
) {
    BackHandler(onBack = onDismiss)

    var displayYear by remember(year) { mutableIntStateOf(year) }
    var displayMonth by remember(month) { mutableIntStateOf(month) }

    val cal = remember(displayYear, displayMonth) {
        Calendar.getInstance().apply {
            set(Calendar.YEAR, displayYear)
            set(Calendar.MONTH, displayMonth - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
    }
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    // 周一=0..周日=6
    val firstDayOffset = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7

    val byDay = remember(monthTransactions, displayYear, displayMonth) {
        val c = Calendar.getInstance()
        monthTransactions.filter { tx ->
            c.timeInMillis = tx.timestamp
            c.get(Calendar.YEAR) == displayYear && (c.get(Calendar.MONTH) + 1) == displayMonth
        }.groupBy { tx ->
            c.timeInMillis = tx.timestamp
            c.get(Calendar.DAY_OF_MONTH)
        }
    }

    val defaultDay = remember(byDay, daysInMonth) {
        byDay.keys.maxOrNull() ?: Calendar.getInstance().get(Calendar.DAY_OF_MONTH).coerceAtMost(daysInMonth)
    }
    var selectedDay by remember(displayYear, displayMonth) { mutableIntStateOf(defaultDay) }

    val selectedList = byDay[selectedDay].orEmpty()
    val dayExpenseCents = selectedList
        .filter { it.type == TransactionType.EXPENSE }
        .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }
    val dayIncomeCents = selectedList
        .filter { it.type == TransactionType.INCOME }
        .sumOf { it.amountCnyCents }
    val dayRefundCents = selectedList
        .filter { it.type == TransactionType.REFUND_OFFSET }
        .sumOf { it.amountCnyCents }

    val selectedDateLabel = remember(displayYear, displayMonth, selectedDay) {
        val c = Calendar.getInstance().apply {
            set(displayYear, displayMonth - 1, selectedDay)
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
                    Spacer(modifier = Modifier.width(12.dp))
                    Surface(
                        onClick = {
                            if (displayMonth == 1) { displayMonth = 12; displayYear -= 1 } else displayMonth -= 1
                        },
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
                        onClick = {
                            if (displayMonth == 12) { displayMonth = 1; displayYear += 1 } else displayMonth += 1
                        },
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

                Text(
                    text = "今日",
                    fontSize = 13.sp,
                    color = ICostTextSecondary,
                    modifier = Modifier.clickable {
                        displayYear = year
                        displayMonth = month
                        selectedDay = defaultDay
                    }
                )
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
                        onSwipeRight = {
                            if (displayMonth == 1) { displayMonth = 12; displayYear -= 1 } else displayMonth -= 1
                        },
                        onSwipeLeft = {
                            if (displayMonth == 12) { displayMonth = 1; displayYear += 1 } else displayMonth += 1
                        }
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
                                        val isSelected = selectedDay == dayNum
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
                                                .height(50.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(cellBg)
                                                .iCostPressScale(pressedScale = 0.90f) { selectedDay = dayNum }
                                                .padding(vertical = 4.dp, horizontal = 2.dp),
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
                                                    text = String.format(Locale.US, "-%.2f", dayExp / 100.0),
                                                    fontSize = 8.sp,
                                                    color = if (isSelected) Color.White else ICostCoralRed,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            } else if (dayInc > 0L) {
                                                Text(
                                                    text = String.format(Locale.US, "+%.0f", dayInc / 100.0),
                                                    fontSize = 8.sp,
                                                    color = if (isSelected) Color.White else ICostGreenIncome,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            } else {
                                                Spacer(modifier = Modifier.height(8.dp))
                                            }
                                        }
                                    } else {
                                        Spacer(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(50.dp)
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
                        "当日暂无收支流水",
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
                                        color = ICostTextPrimary
                                    )
                                    Text(
                                        text = if (item.merchant.isNotBlank()) "$timeStr · ${item.merchant}" else timeStr,
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
                                    val prefix = if (item.type == TransactionType.EXPENSE) "-" else ""
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
