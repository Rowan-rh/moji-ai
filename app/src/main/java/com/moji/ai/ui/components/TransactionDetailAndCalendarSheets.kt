package com.moji.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.TransactionType
import com.moji.ai.ui.theme.ICostAmberGold
import com.moji.ai.ui.theme.ICostBgGray
import com.moji.ai.ui.theme.ICostBlueAccent
import com.moji.ai.ui.theme.ICostCardWhite
import com.moji.ai.ui.theme.ICostCoralRed
import com.moji.ai.ui.theme.ICostDivider
import com.moji.ai.ui.theme.ICostGreenIncome
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.formatMoneyCents
import com.moji.ai.ui.theme.formatWalletDisplayName
import com.moji.ai.ui.theme.resolveCategoryIcon
import com.moji.ai.ui.theme.resolveTransactionBadgeColor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailSheet(
    tx: TransactionEntity,
    wallets: List<WalletEntity>,
    onDismiss: () -> Unit,
    onEdit: (TransactionEntity) -> Unit,
    onRefund: (expenseId: Long, refundYuan: Double, receiveWalletId: Long?, note: String) -> Unit,
    onSettleReimburse: (expenseId: Long, receiveWalletId: Long?) -> Unit,
    onDelete: (Long) -> Unit
) {
    val walletMap = remember(wallets) { wallets.associateBy { it.id } }
    val sourceWallet = tx.sourceWalletId?.let { walletMap[it] }
    val targetWallet = tx.targetWalletId?.let { walletMap[it] }
    val badgeColor = resolveTransactionBadgeColor(tx.type)

    val fullTimeStr = remember(tx.timestamp) {
        SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA).format(Date(tx.timestamp))
    }

    val remainingRefundableCents = (tx.amountCnyCents - tx.refundedAmountCents).coerceAtLeast(0L)
    var showRefundDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ICostBgGray,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 顶部主卡片
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = ICostCardWhite,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(badgeColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = resolveCategoryIcon(tx.category, tx.type),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(tx.category, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ICostTextPrimary)
                    Spacer(modifier = Modifier.height(4.dp))
                    val prefix = when (tx.type) {
                        TransactionType.EXPENSE, TransactionType.LOAN_OUT -> "-"
                        TransactionType.INCOME, TransactionType.LOAN_IN -> "+"
                        else -> ""
                    }
                    Text(
                        text = "$prefix${formatMoneyCents(tx.amountCents, tx.currency)}",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = badgeColor
                    )
                    if (tx.refundedAmountCents > 0L) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "已退款冲销 ${formatMoneyCents(tx.refundedAmountCents)} · 实际净支出 ${formatMoneyCents(remainingRefundableCents)}",
                            fontSize = 12.sp,
                            color = ICostAmberGold,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = ICostDivider)
                    Spacer(modifier = Modifier.height(12.dp))

                    DetailKeyValueRow("记录时间", fullTimeStr)
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
                    if (tx.merchant.isNotBlank()) {
                        DetailKeyValueRow("商户/摘要", tx.merchant)
                    }
                    if (tx.discountCents > 0L) {
                        DetailKeyValueRow("优惠立减", formatMoneyCents(tx.discountCents))
                    }
                    if (tx.reimbursable) {
                        DetailKeyValueRow("报销状态", if (tx.reimbursed) "已完成报销入账" else "待报销")
                    }
                    if (tx.tags.isNotBlank()) {
                        DetailKeyValueRow("标签", "#${tx.tags}")
                    }
                    DetailKeyValueRow("所属账本", tx.ledgerName)
                    DetailKeyValueRow(
                        "记账方式",
                        when {
                            tx.captureSource.startsWith("AI") -> "AI 自动静默识账 (${tx.captureSource.removePrefix("AI_")})"
                            tx.captureSource == "RECURRING" -> "周期性自动入账"
                            else -> "手动记账"
                        }
                    )
                    if (tx.rawCapturedText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = ICostBgGray,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "原始凭证：${tx.rawCapturedText}",
                                fontSize = 11.sp,
                                color = ICostTextSecondary,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }

            // 操作按钮栏：退款 / 报销 / 编辑 / 删除
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (tx.type == TransactionType.EXPENSE && remainingRefundableCents > 0L) {
                    OutlinedButton(
                        onClick = { showRefundDialog = true },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CurrencyExchange, contentDescription = null, tint = ICostAmberGold, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("退款", color = ICostTextPrimary, fontWeight = FontWeight.Bold)
                    }
                }

                if (tx.reimbursable && !tx.reimbursed) {
                    OutlinedButton(
                        onClick = {
                            onSettleReimburse(tx.id, tx.sourceWalletId)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.TaskAlt, contentDescription = null, tint = ICostGreenIncome, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("报销入账", color = ICostTextPrimary, fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = {
                        onDismiss()
                        onEdit(tx)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("编辑", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        onDelete(tx.id)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = ICostCoralRed, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除", color = ICostCoralRed, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
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
            title = { Text("发起退款 / AA分摊冲销", fontWeight = FontWeight.Bold) },
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
                        label = { Text("退款/冲销金额 (元)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = refundNote,
                        onValueChange = { refundNote = it },
                        label = { Text("备注（选填，如：退货退款 / 同事AA）") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val yuan = refundYuanStr.toDoubleOrNull() ?: return@TextButton
                    onRefund(tx.id, yuan, tx.sourceWalletId, refundNote)
                    showRefundDialog = false
                    onDismiss()
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
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = ICostTextSecondary)
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            value,
            fontSize = 13.sp,
            color = ICostTextPrimary,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 收支日历面板（点击首页「收支日历」弹出）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthlyCalendarSheet(
    year: Int,
    month: Int,
    monthTransactions: List<TransactionEntity>,
    onDismiss: () -> Unit,
    onSelectTransaction: (TransactionEntity) -> Unit
) {
    val cal = remember(year, month) {
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
    }
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    // 将周日=1..周六=7 转换为 周一=0..周日=6
    val firstDayOffset = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7

    var selectedDay by remember {
        mutableIntStateOf(Calendar.getInstance().get(Calendar.DAY_OF_MONTH).coerceAtMost(daysInMonth))
    }

    val byDay = remember(monthTransactions) {
        val c = Calendar.getInstance()
        monthTransactions.groupBy { tx ->
            c.timeInMillis = tx.timestamp
            c.get(Calendar.DAY_OF_MONTH)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ICostBgGray,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "${year}年${month}月 收支日历",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ICostTextPrimary
            )

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = ICostCardWhite,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // 星期头
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
                    Spacer(modifier = Modifier.height(8.dp))

                    val totalCells = firstDayOffset + daysInMonth
                    val rows = (totalCells + 6) / 7
                    for (r in 0 until rows) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            for (c in 0 until 7) {
                                val cellIndex = r * 7 + c
                                val dayNum = cellIndex - firstDayOffset + 1
                                if (dayNum in 1..daysInMonth) {
                                    val dayTxs = byDay[dayNum].orEmpty()
                                    val dayExp = dayTxs.filter { it.type == TransactionType.EXPENSE }
                                        .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }
                                    val isSelected = selectedDay == dayNum

                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isSelected) Color(0xFFFFEFEA) else Color.Transparent)
                                            .clickable { selectedDay = dayNum },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = dayNum.toString(),
                                            fontSize = 14.sp,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                            color = if (isSelected) ICostCoralRed else ICostTextPrimary
                                        )
                                        if (dayExp > 0L) {
                                            Text(
                                                text = String.format(Locale.US, "-%.0f", dayExp / 100.0),
                                                fontSize = 9.sp,
                                                color = ICostCoralRed,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            // 选中日期的账单列表
            val selectedList = byDay[selectedDay].orEmpty()
            Text(
                text = "${month}月${selectedDay}日 明细 (${selectedList.size}笔)",
                fontSize = 13.sp,
                color = ICostTextSecondary
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selectedList.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = ICostCardWhite,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "当日无收支记录",
                                fontSize = 13.sp,
                                color = ICostTextSecondary,
                                modifier = Modifier.padding(18.dp)
                            )
                        }
                    }
                } else {
                    items(selectedList) { item ->
                        Surface(
                            onClick = { onSelectTransaction(item) },
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
                                    Text(item.category, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    if (item.merchant.isNotBlank()) {
                                        Text(item.merchant, fontSize = 12.sp, color = ICostTextSecondary)
                                    }
                                }
                                Text(
                                    formatMoneyCents(item.amountCents, item.currency),
                                    fontWeight = FontWeight.Bold,
                                    color = resolveTransactionBadgeColor(item.type)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
