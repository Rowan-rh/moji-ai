package com.moji.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CategoryCatalog
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.TransactionType
import com.moji.ai.ui.theme.ICostAmberGold
import com.moji.ai.ui.theme.ICostBgGray
import com.moji.ai.ui.theme.ICostBlueAccent
import com.moji.ai.ui.theme.ICostCardWhite
import com.moji.ai.ui.theme.ICostCoralRed
import com.moji.ai.ui.theme.ICostGreenIncome
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.WalletBrandAvatar
import com.moji.ai.ui.theme.formatMoneyCents
import com.moji.ai.ui.theme.formatWalletDisplayName
import com.moji.ai.ui.theme.resolveCategoryIcon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ICostCalculatorSheet(
    wallets: List<WalletEntity>,
    currentLedger: String,
    editingTransaction: TransactionEntity? = null,
    initialType: TransactionType = TransactionType.EXPENSE,
    initialSourceWalletId: Long? = null,
    initialTargetWalletId: Long? = null,
    onDismiss: () -> Unit,
    onSaveTransaction: (TransactionEntity, keepOpen: Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var txType by remember {
        mutableStateOf(editingTransaction?.type ?: initialType)
    }

    // 解析初始主分类与子分类
    val initFullCategory = editingTransaction?.category ?: "餐饮"
    val initParts = initFullCategory.split("-")
    var selectedMainCategory by remember { mutableStateOf(initParts.firstOrNull() ?: "餐饮") }
    var selectedSubCategory by remember { mutableStateOf(initParts.getOrNull(1) ?: "") }

    var expr by remember {
        mutableStateOf(
            if (editingTransaction != null) {
                String.format(Locale.US, "%.2f", editingTransaction.amountCents / 100.0).trimEnd('0').trimEnd('.')
            } else ""
        )
    }

    var currency by remember { mutableStateOf(editingTransaction?.currency ?: "CNY") }
    var noteText by remember {
        mutableStateOf(
            editingTransaction?.merchant?.ifBlank { editingTransaction.note } ?: ""
        )
    }
    var reimbursable by remember { mutableStateOf(editingTransaction?.reimbursable ?: false) }
    var discountYuanStr by remember {
        mutableStateOf(
            if ((editingTransaction?.discountCents ?: 0L) > 0L) {
                String.format(Locale.US, "%.2f", editingTransaction!!.discountCents / 100.0)
            } else ""
        )
    }
    var tagText by remember { mutableStateOf(editingTransaction?.tags ?: "") }

    val defaultAssetWallet = wallets.firstOrNull { !it.type.isLiability } ?: wallets.firstOrNull()
    val defaultCreditWallet = wallets.firstOrNull { it.type.isLiability } ?: wallets.lastOrNull()

    var sourceWalletId by remember {
        mutableStateOf(
            editingTransaction?.sourceWalletId
                ?: initialSourceWalletId
                ?: defaultAssetWallet?.id
        )
    }
    var targetWalletId by remember {
        mutableStateOf(
            editingTransaction?.targetWalletId
                ?: initialTargetWalletId
                ?: if (txType == TransactionType.REPAYMENT) defaultCreditWallet?.id else wallets.lastOrNull { it.id != sourceWalletId }?.id
        )
    }

    var showWalletPickerForTarget by remember { mutableStateOf<Boolean?>(null) } // false = source, true = target
    var showDiscountDialog by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }

    val timeNowStr = remember(editingTransaction) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(
            Date(editingTransaction?.timestamp ?: System.currentTimeMillis())
        )
    }

    val hasPendingOperator = expr.drop(1).any { it == '+' || it == '-' }
    val sym = CurrencyCode.fromCode(currency).symbol

    fun evaluateExpr(raw: String): Double {
        if (raw.isBlank()) return 0.0
        var total = 0.0
        var currentSign = 1.0
        var token = StringBuilder()
        for ((idx, ch) in raw.withIndex()) {
            if ((ch == '+' || ch == '-') && idx > 0) {
                total += currentSign * (token.toString().toDoubleOrNull() ?: 0.0)
                token = StringBuilder()
                currentSign = if (ch == '+') 1.0 else -1.0
            } else {
                token.append(ch)
            }
        }
        if (token.isNotEmpty()) {
            total += currentSign * (token.toString().toDoubleOrNull() ?: 0.0)
        }
        return total.coerceAtLeast(0.0)
    }

    fun buildAndSubmit(keepOpen: Boolean) {
        val evaluated = evaluateExpr(expr)
        if (evaluated <= 0.0) return
        val cents = (evaluated * 100.0).roundToLong()
        val rate = CurrencyCode.fromCode(currency).defaultRateToCny
        val cnyCents = (cents * rate).roundToLong()
        val discountCents = ((discountYuanStr.toDoubleOrNull() ?: 0.0) * 100.0).roundToLong()

        val finalCategory = when (txType) {
            TransactionType.TRANSFER -> "内部转账"
            TransactionType.REPAYMENT -> "信用还款"
            TransactionType.LOAN_IN -> "借入"
            TransactionType.LOAN_OUT -> "借出"
            TransactionType.REFUND_OFFSET -> "退款入账"
            else -> if (selectedSubCategory.isNotBlank()) "$selectedMainCategory-$selectedSubCategory" else selectedMainCategory
        }

        val entity = TransactionEntity(
            id = editingTransaction?.id ?: 0L,
            ledgerName = editingTransaction?.ledgerName ?: currentLedger,
            type = txType,
            amountCents = cents,
            currency = currency,
            exchangeRateToCny = rate,
            amountCnyCents = cnyCents,
            discountCents = discountCents,
            category = finalCategory,
            merchant = noteText.trim(),
            note = noteText.trim(),
            sourceWalletId = if (txType == TransactionType.INCOME) null else sourceWalletId,
            targetWalletId = when (txType) {
                TransactionType.INCOME -> sourceWalletId
                TransactionType.TRANSFER, TransactionType.REPAYMENT -> targetWalletId
                else -> null
            },
            reimbursable = if (txType == TransactionType.EXPENSE) reimbursable else false,
            reimbursed = editingTransaction?.reimbursed ?: false,
            tags = tagText.trim(),
            timestamp = editingTransaction?.timestamp ?: System.currentTimeMillis(),
            captureSource = editingTransaction?.captureSource ?: "MANUAL"
        )
        onSaveTransaction(entity, keepOpen)
        if (keepOpen) {
            expr = ""
            noteText = ""
            discountYuanStr = ""
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = ICostBgGray,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
        ) {
            // 1. 顶部栏：关闭 + [支出 | 收入 | 转账 | 还款] + 币种切换
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = ICostTextPrimary)
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFFE8E8EA)
                ) {
                    Row(modifier = Modifier.padding(3.dp)) {
                        val modes = listOf(
                            TransactionType.EXPENSE to "支出",
                            TransactionType.INCOME to "收入",
                            TransactionType.TRANSFER to "转账",
                            TransactionType.REPAYMENT to "还款"
                        )
                        modes.forEach { (mode, label) ->
                            val selected = txType == mode
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(17.dp))
                                    .background(if (selected) ICostCardWhite else Color.Transparent)
                                    .clickable {
                                        txType = mode
                                        if (mode == TransactionType.INCOME) {
                                            selectedMainCategory = CategoryCatalog.incomeCategories.first().name
                                            selectedSubCategory = ""
                                        } else if (mode == TransactionType.EXPENSE) {
                                            selectedMainCategory = CategoryCatalog.expenseCategories.first().name
                                            selectedSubCategory = ""
                                        } else if (mode == TransactionType.REPAYMENT) {
                                            sourceWalletId = defaultAssetWallet?.id
                                            targetWalletId = defaultCreditWallet?.id
                                        }
                                    }
                                    .padding(horizontal = 15.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 14.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) ICostTextPrimary else ICostTextSecondary
                                )
                            }
                        }
                    }
                }

                // 币种胶囊切换
                Surface(
                    onClick = {
                        val codes = CurrencyCode.entries
                        val nextIdx = (codes.indexOfFirst { it.name == currency } + 1) % codes.size
                        currency = codes[nextIdx].name
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = ICostCardWhite
                ) {
                    Text(
                        text = currency,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // 2. 中部：分类图标网格（含 iCost 二级子分类展开面板） 或 转账/还款双账户选择器
            Box(modifier = Modifier.weight(1f)) {
                if (txType == TransactionType.EXPENSE || txType == TransactionType.INCOME) {
                    val categories = if (txType == TransactionType.EXPENSE) {
                        CategoryCatalog.expenseCategories
                    } else {
                        CategoryCatalog.incomeCategories
                    }
                    val activeColor = if (txType == TransactionType.EXPENSE) ICostCoralRed else ICostGreenIncome
                    val rows = remember(categories) { categories.chunked(5) }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rows.forEach { rowItems ->
                            item {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        rowItems.forEach { cat ->
                                            val isSelected = selectedMainCategory == cat.name
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable {
                                                        selectedMainCategory = cat.name
                                                        selectedSubCategory = ""
                                                    },
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .clip(CircleShape)
                                                        .background(if (isSelected) activeColor else Color(0xFFECECEE)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = resolveCategoryIcon(cat.name, txType),
                                                        contentDescription = cat.name,
                                                        tint = if (isSelected) Color.White else Color(0xFF4A4A4A),
                                                        modifier = Modifier.size(23.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(5.dp))
                                                Text(
                                                    text = cat.name,
                                                    fontSize = 12.sp,
                                                    color = if (isSelected) activeColor else ICostTextPrimary,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                        repeat(5 - rowItems.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }

                                    // 如果本行包含当前选中的主分类，且它有二级子分类，1:1 展开浅灰圆角子分类盒！
                                    val expandedCat = rowItems.firstOrNull { it.name == selectedMainCategory && it.subCategories.isNotEmpty() }
                                    if (expandedCat != null) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Surface(
                                            shape = RoundedCornerShape(18.dp),
                                            color = Color(0xFFEEEEF0),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            LazyRow(
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                                            ) {
                                                items(expandedCat.subCategories) { sub ->
                                                    val subSelected = selectedSubCategory == sub
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        modifier = Modifier.clickable {
                                                            selectedSubCategory = if (subSelected) "" else sub
                                                        }
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(38.dp)
                                                                .clip(CircleShape)
                                                                .background(if (subSelected) activeColor else Color(0xFFE2E2E5)),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Icon(
                                                                imageVector = resolveCategoryIcon(sub, txType),
                                                                contentDescription = sub,
                                                                tint = if (subSelected) Color.White else Color(0xFF555555),
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        Text(
                                                            text = sub,
                                                            fontSize = 11.sp,
                                                            color = if (subSelected) activeColor else Color(0xFF555555),
                                                            fontWeight = if (subSelected) FontWeight.Bold else FontWeight.Normal
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
                } else {
                    // 转账 / 信用还款 双账户流转卡片
                    val fromWallet = wallets.firstOrNull { it.id == sourceWalletId }
                    val toWallet = wallets.firstOrNull { it.id == targetWalletId }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = if (txType == TransactionType.REPAYMENT) "选择还款付款账户与待还信用账户" else "选择转出账户与转入账户",
                            fontSize = 14.sp,
                            color = ICostTextSecondary
                        )

                        Surface(
                            onClick = { showWalletPickerForTarget = false },
                            shape = RoundedCornerShape(20.dp),
                            color = ICostCardWhite,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (fromWallet != null) {
                                    WalletBrandAvatar(fromWallet, size = 40.dp)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("付款 / 转出账户", fontSize = 12.sp, color = ICostTextSecondary)
                                    Text(
                                        text = formatWalletDisplayName(fromWallet, "点击选择账户"),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                if (fromWallet != null) {
                                    Text(
                                        text = formatMoneyCents(fromWallet.balanceCents, fromWallet.currency),
                                        fontSize = 14.sp,
                                        color = ICostTextSecondary
                                    )
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.ArrowForward, contentDescription = null, tint = ICostTextSecondary)
                        }

                        Surface(
                            onClick = { showWalletPickerForTarget = true },
                            shape = RoundedCornerShape(20.dp),
                            color = ICostCardWhite,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (toWallet != null) {
                                    WalletBrandAvatar(toWallet, size = 40.dp)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        if (txType == TransactionType.REPAYMENT) "偿还目标信用账户" else "转入目标账户",
                                        fontSize = 12.sp,
                                        color = ICostTextSecondary
                                    )
                                    Text(
                                        text = formatWalletDisplayName(toWallet, "点击选择目标账户"),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                if (toWallet != null) {
                                    Text(
                                        text = if (toWallet.type.isLiability) {
                                            "待还 ${formatMoneyCents(toWallet.balanceCents, toWallet.currency)}"
                                        } else {
                                            formatMoneyCents(toWallet.balanceCents, toWallet.currency)
                                        },
                                        fontSize = 14.sp,
                                        color = if (toWallet.type.isLiability) ICostCoralRed else ICostTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. 快捷属性胶囊栏（选择账户 | 报销 | 优惠 | 标签）
            val currentWallet = wallets.firstOrNull { it.id == sourceWalletId }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (txType == TransactionType.EXPENSE || txType == TransactionType.INCOME) {
                    item {
                        QuickPillChip(
                            icon = Icons.Default.AccountBalanceWallet,
                            iconTint = ICostAmberGold,
                            label = formatWalletDisplayName(currentWallet, "选择账户"),
                            active = currentWallet != null,
                            onClick = { showWalletPickerForTarget = false }
                        )
                    }
                }
                if (txType == TransactionType.EXPENSE) {
                    item {
                        QuickPillChip(
                            icon = if (reimbursable) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            iconTint = if (reimbursable) ICostBlueAccent else ICostTextSecondary,
                            label = if (reimbursable) "待报销" else "报销",
                            active = reimbursable,
                            onClick = { reimbursable = !reimbursable }
                        )
                    }
                    item {
                        val hasDisc = (discountYuanStr.toDoubleOrNull() ?: 0.0) > 0.0
                        QuickPillChip(
                            icon = Icons.Default.CardGiftcard,
                            iconTint = ICostGreenIncome,
                            label = if (hasDisc) "优惠 ¥$discountYuanStr" else "优惠",
                            active = hasDisc,
                            onClick = { showDiscountDialog = true }
                        )
                    }
                }
                item {
                    QuickPillChip(
                        icon = Icons.Default.LocalOffer,
                        iconTint = ICostBlueAccent,
                        label = if (tagText.isNotBlank()) "#$tagText" else "标签",
                        active = tagText.isNotBlank(),
                        onClick = { showTagDialog = true }
                    )
                }
            }

            // 4. 白色大圆角金额与备注输入卡
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = ICostCardWhite,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    val amountColor = when (txType) {
                        TransactionType.EXPENSE -> ICostCoralRed
                        TransactionType.INCOME -> ICostGreenIncome
                        else -> ICostTextPrimary
                    }
                    val displayExpr = if (expr.isEmpty()) "${sym}0.00" else "$sym$expr"
                    Text(
                        text = displayExpr,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = amountColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFF0F0F0))
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFF5F5F7)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.AccessTime,
                                    contentDescription = null,
                                    tint = ICostTextPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(timeNowStr, fontSize = 12.sp, color = ICostTextPrimary, fontWeight = FontWeight.Medium)
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Box(modifier = Modifier.weight(1f)) {
                            if (noteText.isEmpty()) {
                                Text("点击填写备注或商户名", fontSize = 14.sp, color = Color(0xFFB8B8B8))
                            }
                            BasicTextField(
                                value = noteText,
                                onValueChange = { noteText = it },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 14.sp, color = ICostTextPrimary),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // 5. 4x4 圆角白色数字计算器键盘（1:1 复刻 iCost 手感）
            val keyRows = listOf(
                listOf("1", "2", "3", "BACK"),
                listOf("4", "5", "6", "+"),
                listOf("7", "8", "9", "-"),
                listOf(".", "0", "AGAIN", "DONE")
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                keyRows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { key ->
                            val isDoneKey = key == "DONE"
                            val bgColor = if (isDoneKey) {
                                if (txType == TransactionType.INCOME) ICostGreenIncome else ICostCoralRed
                            } else {
                                ICostCardWhite
                            }

                            Surface(
                                onClick = {
                                    when (key) {
                                        "BACK" -> if (expr.isNotEmpty()) expr = expr.dropLast(1)
                                        "+" -> if (expr.isNotEmpty() && expr.last() != '+' && expr.last() != '-') expr += "+"
                                        "-" -> if (expr.isNotEmpty() && expr.last() != '+' && expr.last() != '-') expr += "-"
                                        "." -> {
                                            val lastPart = expr.split('+', '-').lastOrNull() ?: ""
                                            if (!lastPart.contains('.')) {
                                                expr = if (lastPart.isEmpty()) "${expr}0." else "$expr."
                                            }
                                        }
                                        "AGAIN" -> buildAndSubmit(keepOpen = true)
                                        "DONE" -> {
                                            if (hasPendingOperator) {
                                                val res = evaluateExpr(expr)
                                                expr = String.format(Locale.US, "%.2f", res).trimEnd('0').trimEnd('.')
                                            } else {
                                                buildAndSubmit(keepOpen = false)
                                            }
                                        }
                                        else -> {
                                            val lastPart = expr.split('+', '-').lastOrNull() ?: ""
                                            val decimals = lastPart.substringAfter('.', missingDelimiterValue = "")
                                            if (!lastPart.contains('.') || decimals.length < 2) {
                                                expr += key
                                            }
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(16.dp),
                                color = bgColor,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    when (key) {
                                        "BACK" -> Icon(
                                            Icons.AutoMirrored.Filled.Backspace,
                                            contentDescription = "退格",
                                            tint = ICostTextPrimary,
                                            modifier = Modifier.size(21.dp)
                                        )
                                        "AGAIN" -> Text(
                                            "保存再记",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ICostTextPrimary
                                        )
                                        "DONE" -> Text(
                                            if (hasPendingOperator) "=" else "完成",
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        else -> Text(
                                            key,
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ICostTextPrimary
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

    // 账户选择弹窗
    if (showWalletPickerForTarget != null) {
        val pickingTarget = showWalletPickerForTarget == true
        AlertDialog(
            onDismissRequest = { showWalletPickerForTarget = null },
            containerColor = ICostCardWhite,
            title = { Text(if (pickingTarget) "选择目标账户" else "选择账户", fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(wallets) { w ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (pickingTarget) targetWalletId = w.id else sourceWalletId = w.id
                                    showWalletPickerForTarget = null
                                }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            WalletBrandAvatar(w, size = 36.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    formatWalletDisplayName(w),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    w.subTypeLabel.ifBlank { w.type.displayName },
                                    fontSize = 12.sp,
                                    color = ICostTextSecondary
                                )
                            }
                            Text(
                                text = if (w.type.isLiability) {
                                    "-${formatMoneyCents(w.balanceCents, w.currency)}"
                                } else {
                                    formatMoneyCents(w.balanceCents, w.currency)
                                },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWalletPickerForTarget = null }) { Text("关闭") }
            }
        )
    }

    if (showDiscountDialog) {
        AlertDialog(
            onDismissRequest = { showDiscountDialog = false },
            containerColor = ICostCardWhite,
            title = { Text("设置满减/立减优惠", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = discountYuanStr,
                    onValueChange = { discountYuanStr = it },
                    label = { Text("优惠金额（元），计入统计页「优惠」") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = { showDiscountDialog = false }) { Text("确定") }
            }
        )
    }

    if (showTagDialog) {
        AlertDialog(
            onDismissRequest = { showTagDialog = false },
            containerColor = ICostCardWhite,
            title = { Text("添加标签", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = tagText,
                    onValueChange = { tagText = it },
                    label = { Text("如：聚餐、出差、固定开支") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = { showTagDialog = false }) { Text("确定") }
            }
        )
    }
}

@Composable
private fun QuickPillChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (active) Color(0xFFEAF2FF) else ICostCardWhite
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                fontSize = 13.sp,
                color = ICostTextPrimary,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}
