package com.moji.ai.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import com.moji.ai.ui.theme.ICostCellGray
import com.moji.ai.ui.theme.ICostCircleIconButton
import com.moji.ai.ui.theme.ICostCoralRed
import com.moji.ai.ui.theme.ICostGreenIncome
import com.moji.ai.ui.theme.ICostSlateIcon
import com.moji.ai.ui.theme.ICostSmoothBottomSheet
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.WalletBrandAvatar
import com.moji.ai.ui.theme.formatMoneyCents
import com.moji.ai.ui.theme.formatWalletDisplayName
import com.moji.ai.ui.theme.iCostHorizontalSwipeTrigger
import com.moji.ai.ui.theme.iCostPressScale
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToLong

/** 金额最多允许的整数位数（9 位 = 亿级），防止误触产生天文数字 */
internal const val MAX_AMOUNT_INTEGER_DIGITS = 9

/**
 * 金额输入过滤：仅保留数字与一个小数点，最多两位小数、最多 [MAX_AMOUNT_INTEGER_DIGITS] 位整数。
 * 供计算器内的优惠/手续费弹窗、首页预算弹窗、详情页退款/拆分弹窗共用。
 */
internal fun sanitizeMoneyInput(raw: String): String {
    val sb = StringBuilder()
    var seenDot = false
    var decimals = 0
    var integers = 0
    for (ch in raw) {
        when {
            ch in '0'..'9' && !seenDot -> {
                if (integers < MAX_AMOUNT_INTEGER_DIGITS) {
                    sb.append(ch)
                    integers++
                }
            }
            ch in '0'..'9' && seenDot -> {
                if (decimals < 2) {
                    sb.append(ch)
                    decimals++
                }
            }
            (ch == '.' || ch == '。') && !seenDot -> {
                if (sb.isEmpty()) sb.append('0')
                sb.append('.')
                seenDot = true
            }
        }
    }
    return sb.toString()
}

/** 元字符串转分；空串或非法返回 null */
internal fun parseYuanToCents(text: String): Long? {
    val v = text.trim().toDoubleOrNull() ?: return null
    if (v.isNaN() || v.isInfinite() || v < 0.0) return null
    return (v * 100.0).roundToLong()
}

/**
 * @param onSelectLedger 已不再使用：面板内选择账本只影响本次记账，不会切换首页账本。保留参数以兼容旧调用。
 * @param onOpenAiSandbox 已不再使用：记账面板不再提供 AI 测试入口。保留参数以兼容旧调用。
 */
@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ICostCalculatorSheet(
    wallets: List<WalletEntity>,
    currentLedger: String,
    ledgers: List<String> = listOf("默认账本"),
    onSelectLedger: (String) -> Unit = {},
    visible: Boolean = true,
    editingTransaction: TransactionEntity? = null,
    initialType: TransactionType = TransactionType.EXPENSE,
    initialSourceWalletId: Long? = null,
    initialTargetWalletId: Long? = null,
    onDismiss: () -> Unit,
    onSaveTransaction: (TransactionEntity, keepOpen: Boolean) -> Unit,
    onOpenAiSandbox: () -> Unit = {}
) {
    val isEditing = editingTransaction != null
    // 1:1 iCost 顶部三态：支出 | 收入 | 转账（若外部传入 REPAYMENT 则归在转账 tab 并在保存时自动判断目标账户）
    var txType by remember {
        val raw = editingTransaction?.type ?: initialType
        mutableStateOf(
            if (raw == TransactionType.REPAYMENT) TransactionType.TRANSFER else raw
        )
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
    var feeYuanStr by remember {
        mutableStateOf(
            if ((editingTransaction?.feeCents ?: 0L) > 0L) {
                String.format(Locale.US, "%.2f", editingTransaction!!.feeCents / 100.0)
            } else ""
        )
    }
    var tagText by remember { mutableStateOf(editingTransaction?.tags ?: "") }

    var sourceWalletId by remember {
        mutableStateOf(
            editingTransaction?.sourceWalletId ?: initialSourceWalletId
        )
    }
    var targetWalletId by remember {
        mutableStateOf(
            editingTransaction?.targetWalletId ?: initialTargetWalletId
        )
    }
    // 记账时间：每次打开面板时重置；用户可点时间胶囊补记过去的账
    var entryTimestamp by remember {
        mutableLongStateOf(editingTransaction?.timestamp ?: System.currentTimeMillis())
    }
    var timeManuallySet by remember { mutableStateOf(false) }
    // 本次记入的账本：仅作用于当前面板，不切换首页账本
    var targetLedger by remember { mutableStateOf(editingTransaction?.ledgerName ?: currentLedger) }
    // 防重复提交（「完成」后面板收起动画期间的连点）
    var isSubmitting by remember { mutableStateOf(false) }
    var inlineError by remember { mutableStateOf<String?>(null) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    // 每次唤起面板时同步重置表单状态，配合 keepWarm 预热实现 0ms 首帧唤醒
    LaunchedEffect(visible, editingTransaction, initialType, initialSourceWalletId, initialTargetWalletId) {
        if (visible) {
            val raw = editingTransaction?.type ?: initialType
            txType = if (raw == TransactionType.REPAYMENT) TransactionType.TRANSFER else raw
            val fullCat = editingTransaction?.category ?: if (raw == TransactionType.INCOME) {
                CategoryCatalog.incomeCategories.first().name
            } else {
                "餐饮"
            }
            val parts = fullCat.split("-")
            selectedMainCategory = parts.firstOrNull() ?: "餐饮"
            selectedSubCategory = parts.getOrNull(1) ?: ""
            expr = if (editingTransaction != null) {
                String.format(Locale.US, "%.2f", editingTransaction.amountCents / 100.0).trimEnd('0').trimEnd('.')
            } else ""
            currency = editingTransaction?.currency ?: "CNY"
            noteText = editingTransaction?.merchant?.ifBlank { editingTransaction.note } ?: ""
            reimbursable = editingTransaction?.reimbursable ?: false
            discountYuanStr = if ((editingTransaction?.discountCents ?: 0L) > 0L) {
                String.format(Locale.US, "%.2f", editingTransaction!!.discountCents / 100.0)
            } else ""
            feeYuanStr = if ((editingTransaction?.feeCents ?: 0L) > 0L) {
                String.format(Locale.US, "%.2f", editingTransaction!!.feeCents / 100.0)
            } else ""
            tagText = editingTransaction?.tags ?: ""
            sourceWalletId = editingTransaction?.sourceWalletId ?: initialSourceWalletId
            targetWalletId = editingTransaction?.targetWalletId ?: initialTargetWalletId
            entryTimestamp = editingTransaction?.timestamp ?: System.currentTimeMillis()
            timeManuallySet = false
            targetLedger = editingTransaction?.ledgerName ?: currentLedger
            isSubmitting = false
            inlineError = null
            showDiscardConfirm = false
        }
    }

    var showWalletPickerForTarget by remember { mutableStateOf<Boolean?>(null) } // false = source, true = target
    var showDiscountDialog by remember { mutableStateOf(false) }
    var showFeeDialog by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var showLedgerPicker by remember { mutableStateOf(false) }
    var showCurrencyPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val amountShake = remember { Animatable(0f) }
    fun flashError(message: String) {
        inlineError = message
        scope.launch {
            amountShake.snapTo(0f)
            for (dx in listOf(14f, -12f, 9f, -6f, 3f, 0f)) {
                amountShake.animateTo(dx, tween(45))
            }
        }
    }

    val timeLabel = remember(entryTimestamp) {
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = entryTimestamp }
        val sameDay = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
        val pattern = when {
            sameDay -> "HH:mm"
            now.get(Calendar.YEAR) == c.get(Calendar.YEAR) -> "M月d日 HH:mm"
            else -> "yyyy/M/d HH:mm"
        }
        SimpleDateFormat(pattern, Locale.CHINA).format(Date(entryTimestamp))
    }

    val operators = setOf('+', '-', '×', '÷')
    val hasPendingOperator = expr.drop(1).any { it in operators }
    val sym = CurrencyCode.fromCode(currency).symbol

    /** 计算表达式；除以 0 时返回 null */
    fun evaluateExpr(raw: String): Double? {
        if (raw.isBlank()) return 0.0
        val tokens = mutableListOf<String>()
        val numBuf = StringBuilder()
        for ((idx, ch) in raw.withIndex()) {
            if (ch in operators && idx > 0) {
                if (numBuf.isNotEmpty()) {
                    tokens.add(numBuf.toString())
                    numBuf.clear()
                }
                tokens.add(ch.toString())
            } else {
                numBuf.append(ch)
            }
        }
        if (numBuf.isNotEmpty()) tokens.add(numBuf.toString())
        if (tokens.isEmpty()) return 0.0

        // 先处理 × ÷
        val pass1 = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            if ((tok == "×" || tok == "÷") && pass1.isNotEmpty() && i + 1 < tokens.size) {
                val left = pass1.removeAt(pass1.lastIndex).toDoubleOrNull() ?: 0.0
                val right = tokens[i + 1].toDoubleOrNull() ?: 1.0
                if (tok == "÷" && right == 0.0) return null
                val res = if (tok == "×") left * right else left / right
                pass1.add(res.toString())
                i += 2
            } else {
                pass1.add(tok)
                i += 1
            }
        }

        // 再处理 + -
        var total = pass1.firstOrNull()?.toDoubleOrNull() ?: 0.0
        var j = 1
        while (j + 1 < pass1.size) {
            val op = pass1[j]
            val rhs = pass1[j + 1].toDoubleOrNull() ?: 0.0
            total = if (op == "+") total + rhs else total - rhs
            j += 2
        }
        return total
    }

    val maxAmount = Math.pow(10.0, MAX_AMOUNT_INTEGER_DIGITS.toDouble())

    /** 校验表达式结果；不合法时提示并返回 null */
    fun evaluateOrFlash(): Double? {
        val result = evaluateExpr(expr)
        return when {
            result == null -> { flashError("不能除以 0"); null }
            result >= maxAmount -> { flashError("金额过大，最多 $MAX_AMOUNT_INTEGER_DIGITS 位整数"); null }
            else -> result
        }
    }

    fun appendOperator(primary: Char, alternate: Char) {
        if (expr.isEmpty()) return
        val last = expr.last()
        expr = when {
            last == primary -> expr.dropLast(1) + alternate
            last == alternate -> expr.dropLast(1) + primary
            last in operators -> expr.dropLast(1) + primary
            else -> expr + primary
        }
    }

    fun appendDigit(key: String) {
        val lastPart = expr.split('+', '-', '×', '÷').lastOrNull() ?: ""
        if (lastPart.contains('.')) {
            val decimals = lastPart.substringAfter('.')
            if (decimals.length < 2) expr += key
            return
        }
        when {
            lastPart == "0" -> expr = expr.dropLast(1) + key // 去掉无意义的前导 0
            lastPart.length >= MAX_AMOUNT_INTEGER_DIGITS -> flashError("最多 $MAX_AMOUNT_INTEGER_DIGITS 位整数")
            else -> expr += key
        }
    }

    fun buildAndSubmit(keepOpen: Boolean, requestSheetDismiss: () -> Unit) {
        if (isSubmitting) return
        val evaluated = evaluateOrFlash() ?: return
        if (evaluated <= 0.0) {
            flashError(if (expr.isEmpty()) "请先输入金额" else "金额需大于 0")
            return
        }
        val cents = (evaluated * 100.0).roundToLong()
        if (cents <= 0L) {
            flashError("金额需大于 0")
            return
        }
        val rate = CurrencyCode.fromCode(currency).defaultRateToCny
        val cnyCents = (cents * rate).roundToLong()
        val discountCents = if (txType == TransactionType.EXPENSE) parseYuanToCents(discountYuanStr) ?: 0L else 0L
        val feeCents = if (txType == TransactionType.TRANSFER) parseYuanToCents(feeYuanStr) ?: 0L else 0L

        val resolvedSourceId: Long?
        val resolvedTargetId: Long?
        if (txType == TransactionType.TRANSFER) {
            // 转账必须由用户明确选择两个不同的账户，不再自动代选
            if (sourceWalletId == null || targetWalletId == null) {
                flashError(if (sourceWalletId == null) "请选择转出账户" else "请选择转入账户")
                return
            }
            if (sourceWalletId == targetWalletId) {
                flashError("转出和转入不能是同一个账户")
                return
            }
            resolvedSourceId = sourceWalletId
            resolvedTargetId = targetWalletId
        } else {
            val defaultWallet = wallets.firstOrNull { !it.type.isLiability } ?: wallets.firstOrNull()
            resolvedSourceId = sourceWalletId ?: defaultWallet?.id
            resolvedTargetId = null
        }
        val resolvedSourceWallet = wallets.firstOrNull { it.id == resolvedSourceId }
        val resolvedTargetWallet = wallets.firstOrNull { it.id == resolvedTargetId }

        // 当用户处在「转账」模式且由资产账户转入信用账户时，自动记录为 REPAYMENT
        val actualType = if (
            txType == TransactionType.TRANSFER &&
            resolvedSourceWallet?.type?.isLiability == false &&
            resolvedTargetWallet?.type?.isLiability == true
        ) {
            TransactionType.REPAYMENT
        } else {
            txType
        }

        val finalCategory = when (actualType) {
            TransactionType.TRANSFER -> "内部转账"
            TransactionType.REPAYMENT -> "信用还款"
            TransactionType.LOAN_IN -> "借入"
            TransactionType.LOAN_OUT -> "借出"
            TransactionType.REFUND_OFFSET -> "退款入账"
            else -> if (selectedSubCategory.isNotBlank()) "$selectedMainCategory-$selectedSubCategory" else selectedMainCategory
        }

        // 备注回写：编辑时只回写当初展示在输入框里的那个字段，避免覆盖另一份独立备注
        val typedText = noteText.trim()
        val (finalMerchant, finalNote) = if (editingTransaction == null) {
            typedText to typedText
        } else if (editingTransaction.merchant.isNotBlank()) {
            val noteWasMirror = editingTransaction.note.isBlank() ||
                editingTransaction.note == editingTransaction.merchant
            typedText to (if (noteWasMirror) typedText else editingTransaction.note)
        } else {
            editingTransaction.merchant to typedText
        }

        val finalSourceId = if (actualType == TransactionType.INCOME) null else resolvedSourceId
        val finalTargetId = when (actualType) {
            TransactionType.INCOME -> resolvedSourceId
            TransactionType.TRANSFER, TransactionType.REPAYMENT -> resolvedTargetId
            else -> null
        }
        val finalReimbursable = if (actualType == TransactionType.EXPENSE) reimbursable else false
        val finalTimestamp = if (editingTransaction == null && !timeManuallySet) {
            System.currentTimeMillis()
        } else {
            entryTimestamp
        }

        // 编辑时基于原账单 copy，保留退款金额、关联账单、原始采集文本、AI 置信度等字段
        val entity = editingTransaction?.copy(
            ledgerName = targetLedger,
            type = actualType,
            amountCents = cents,
            currency = currency,
            exchangeRateToCny = rate,
            amountCnyCents = cnyCents,
            discountCents = discountCents,
            feeCents = feeCents,
            category = finalCategory,
            merchant = finalMerchant,
            note = finalNote,
            sourceWalletId = finalSourceId,
            targetWalletId = finalTargetId,
            reimbursable = finalReimbursable,
            tags = tagText.trim(),
            timestamp = finalTimestamp
        ) ?: TransactionEntity(
            ledgerName = targetLedger,
            type = actualType,
            amountCents = cents,
            currency = currency,
            exchangeRateToCny = rate,
            amountCnyCents = cnyCents,
            discountCents = discountCents,
            feeCents = feeCents,
            category = finalCategory,
            merchant = finalMerchant,
            note = finalNote,
            sourceWalletId = finalSourceId,
            targetWalletId = finalTargetId,
            reimbursable = finalReimbursable,
            tags = tagText.trim(),
            timestamp = finalTimestamp,
            captureSource = "MANUAL"
        )
        inlineError = null
        if (keepOpen && editingTransaction == null) {
            onSaveTransaction(entity, true)
            expr = ""
            noteText = ""
            discountYuanStr = ""
            feeYuanStr = ""
        } else {
            // 先保存（keepOpen = true 让外层不立即隐藏），再走抽屉自身的收起动画
            isSubmitting = true
            onSaveTransaction(entity, true)
            requestSheetDismiss()
        }
    }

    ICostSmoothBottomSheet(
        onDismissRequest = onDismiss,
        visible = visible,
        keepWarm = true,
        containerColor = ICostBgGray,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        showDragHandle = false
    ) { requestDismiss ->
        // 新记一笔且已输入金额时，关闭前确认，避免误触丢失
        val hasUnsavedInput = !isEditing && !isSubmitting && expr.isNotEmpty()
        val requestCloseWithConfirm: () -> Unit = {
            if (hasUnsavedInput) showDiscardConfirm = true else requestDismiss()
        }
        // 晚于抽屉自身注册，优先拦截系统返回键
        BackHandler(enabled = visible && hasUnsavedInput) { showDiscardConfirm = true }

        if (showDiscardConfirm) {
            AlertDialog(
                onDismissRequest = { showDiscardConfirm = false },
                containerColor = ICostCardWhite,
                title = { Text("放弃本次记账？", fontWeight = FontWeight.ExtraBold) },
                text = { Text("已输入的金额和备注不会保存。", fontSize = 14.sp, color = ICostTextSecondary) },
                confirmButton = {
                    TextButton(onClick = {
                        showDiscardConfirm = false
                        requestDismiss()
                    }) {
                        Text("放弃", color = ICostCoralRed, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDiscardConfirm = false }) { Text("继续记账") }
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.93f)
                .navigationBarsPadding()
        ) {
            // 1. 顶部栏：左侧 40dp 白色圆形 `✕` + 中间白色胶囊 [支出 | 收入 | 转账] + 右侧蓝色账本名
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ICostCircleIconButton(onClick = requestCloseWithConfirm, size = 40.dp) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = ICostTextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = ICostCardWhite
                ) {
                    Row(modifier = Modifier.padding(3.dp)) {
                        val modes = listOf(
                            TransactionType.EXPENSE to "支出",
                            TransactionType.INCOME to "收入",
                            TransactionType.TRANSFER to "转账"
                        )
                        modes.forEach { (mode, label) ->
                            val selected = txType == mode
                            val modeBgColor by animateColorAsState(
                                targetValue = if (selected) Color(0xFFECECEE) else Color.Transparent,
                                animationSpec = tween(200),
                                label = "CalcModePillBg"
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(19.dp))
                                    .background(modeBgColor)
                                    .iCostPressScale(pressedScale = 0.92f) {
                                        if (txType == mode) return@iCostPressScale
                                        txType = mode
                                        inlineError = null
                                        if (mode == TransactionType.INCOME) {
                                            selectedMainCategory = CategoryCatalog.incomeCategories.first().name
                                            selectedSubCategory = ""
                                        } else if (mode == TransactionType.EXPENSE) {
                                            selectedMainCategory = CategoryCatalog.expenseCategories.first().name
                                            selectedSubCategory = ""
                                        }
                                    }
                                    .padding(horizontal = 18.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 14.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = ICostTextPrimary
                                )
                            }
                        }
                    }
                }

                Text(
                    text = targetLedger,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = ICostBlueAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 96.dp)
                        .iCostPressScale(pressedScale = 0.92f) { showLedgerPicker = true }
                        .padding(horizontal = 4.dp, vertical = 10.dp)
                )
            }

            // 2. 中部：5 列分类网格（含右上角 `•••` 子分类角标 + 展开式 5 列二级分类盒） 或 转账双账户卡片
            var swapRotCount by remember { mutableIntStateOf(0) }
            val swapAngle by animateFloatAsState(
                targetValue = swapRotCount * 180f,
                animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium),
                label = "TransferSwapAngle"
            )

            val modeOrder = listOf(TransactionType.EXPENSE, TransactionType.INCOME, TransactionType.TRANSFER)
            fun switchCalcModeByDelta(delta: Int) {
                val curIdx = modeOrder.indexOf(txType).coerceAtLeast(0)
                val nextIdx = curIdx + delta
                // 到两端即停，不再从「转账」绕回「支出」
                if (nextIdx !in modeOrder.indices) return
                val nextMode = modeOrder[nextIdx]
                txType = nextMode
                inlineError = null
                if (nextMode == TransactionType.INCOME) {
                    selectedMainCategory = CategoryCatalog.incomeCategories.first().name
                    selectedSubCategory = ""
                } else if (nextMode == TransactionType.EXPENSE) {
                    selectedMainCategory = CategoryCatalog.expenseCategories.first().name
                    selectedSubCategory = ""
                }
            }

            AnimatedContent(
                targetState = txType,
                transitionSpec = {
                    (slideInHorizontally(
                        initialOffsetX = { it / 8 },
                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                    ) + fadeIn(tween(180))) togetherWith
                        (slideOutHorizontally(
                            targetOffsetX = { -it / 8 },
                            animationSpec = tween(180, easing = FastOutSlowInEasing)
                        ) + fadeOut(tween(140)))
                },
                modifier = Modifier
                    .weight(1f)
                    .iCostHorizontalSwipeTrigger(
                        thresholdDp = 52.dp,
                        onSwipeRight = { switchCalcModeByDelta(-1) },
                        onSwipeLeft = { switchCalcModeByDelta(1) }
                    ),
                label = "CalculatorModeBodyTransition"
            ) { currentMode ->
                if (currentMode == TransactionType.EXPENSE || currentMode == TransactionType.INCOME) {
                    val categories = if (currentMode == TransactionType.EXPENSE) {
                        CategoryCatalog.expenseCategories
                    } else {
                        CategoryCatalog.incomeCategories
                    }
                    val activeColor = if (currentMode == TransactionType.EXPENSE) ICostCoralRed else ICostGreenIncome
                    val rows = remember(categories) { categories.chunked(5) }
                    val categoryScrollState = rememberScrollState()

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(categoryScrollState)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        rows.forEach { rowItems ->
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                        rowItems.forEach { cat ->
                                            val isSelected = selectedMainCategory == cat.name
                                            val circleBg = if (isSelected) activeColor else Color(0xFFECECEE)
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .iCostPressScale(pressedScale = 0.88f) {
                                                        selectedMainCategory = cat.name
                                                        selectedSubCategory = ""
                                                    },
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Box(
                                                    modifier = Modifier.size(52.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(48.dp)
                                                            .clip(CircleShape)
                                                            .background(circleBg),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        ICostAssetImage(
                                                            assetFile = cat.assetFile,
                                                            tint = if (isSelected) Color.White else ICostSlateIcon,
                                                            modifier = Modifier.size(26.dp)
                                                        )
                                                    }
                                                    // 拥有二级分类的类别在右上角显示 14dp 浅灰 `•••` 小圆角标
                                                    if (cat.subCategories.isNotEmpty()) {
                                                        Box(
                                                            modifier = Modifier
                                                                .align(Alignment.TopEnd)
                                                                .size(15.dp)
                                                                .clip(CircleShape)
                                                                .background(if (isSelected) activeColor else Color(0xFFE0E0E4)),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.MoreHoriz,
                                                                contentDescription = null,
                                                                tint = if (isSelected) Color.White else Color(0xFF7A7E87),
                                                                modifier = Modifier.size(10.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = cat.name,
                                                    fontSize = 12.sp,
                                                    color = ICostTextPrimary,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                        repeat(5 - rowItems.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }

                                    // 1:1 展开当前选中主分类的 5 列浅灰圆角二级分类盒（高响应临界阻尼弹簧过渡）
                                    val expandedCat = rowItems.firstOrNull {
                                        it.name == selectedMainCategory && it.subCategories.isNotEmpty()
                                    }
                                    AnimatedVisibility(
                                        visible = expandedCat != null,
                                        enter = expandVertically(
                                            animationSpec = spring(dampingRatio = 0.94f, stiffness = 620f)
                                        ) + fadeIn(tween(140)),
                                        exit = shrinkVertically(
                                            animationSpec = spring(dampingRatio = 0.96f, stiffness = 680f)
                                        ) + fadeOut(tween(110))
                                    ) {
                                        if (expandedCat != null) {
                                            Column {
                                                Spacer(modifier = Modifier.height(10.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(18.dp),
                                                    color = Color(0xFFECECEE),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    val subRows = expandedCat.subCategories.chunked(5)
                                                    Column(
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                                    ) {
                                                        subRows.forEach { subRow ->
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                horizontalArrangement = Arrangement.SpaceBetween
                                                            ) {
                                                                subRow.forEach { sub ->
                                                                    val subSelected = selectedSubCategory == sub.name
                                                                    Column(
                                                                        modifier = Modifier
                                                                            .weight(1f)
                                                                            .iCostPressScale(pressedScale = 0.88f) {
                                                                                selectedSubCategory = if (subSelected) "" else sub.name
                                                                            },
                                                                        horizontalAlignment = Alignment.CenterHorizontally
                                                                    ) {
                                                                        Box(
                                                                            modifier = Modifier
                                                                                .size(40.dp)
                                                                                .clip(CircleShape)
                                                                                .background(if (subSelected) activeColor else Color.Transparent),
                                                                            contentAlignment = Alignment.Center
                                                                        ) {
                                                                            ICostAssetImage(
                                                                                assetFile = sub.assetFile,
                                                                                tint = if (subSelected) Color.White else ICostSlateIcon,
                                                                                modifier = Modifier.size(23.dp)
                                                                            )
                                                                        }
                                                                        Spacer(modifier = Modifier.height(3.dp))
                                                                        Text(
                                                                            text = sub.name,
                                                                            fontSize = 11.sp,
                                                                            color = if (subSelected) activeColor else ICostTextPrimary,
                                                                            fontWeight = if (subSelected) FontWeight.Bold else FontWeight.Normal,
                                                                            maxLines = 1
                                                                        )
                                                                    }
                                                                }
                                                                repeat(5 - subRow.size) {
                                                                    Spacer(modifier = Modifier.weight(1f))
                                                            }
                                                        }
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
                    // 1:1 iCost 转账模式（对齐 12_transfer_mode.png）：两张白卡 + 中间 ⇅ 旋转互换按钮
                    val fromWallet = wallets.firstOrNull { it.id == sourceWalletId }
                    val toWallet = wallets.firstOrNull { it.id == targetWalletId }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = ICostCardWhite,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(66.dp)
                                .iCostPressScale(pressedScale = 0.97f) { showWalletPickerForTarget = false }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (fromWallet != null) {
                                    WalletBrandAvatar(fromWallet, size = 36.dp)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = formatWalletDisplayName(fromWallet),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ICostTextPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = formatMoneyCents(fromWallet.balanceCents, fromWallet.currency),
                                        fontSize = 14.sp,
                                        color = ICostTextSecondary
                                    )
                                } else {
                                    Text(
                                        text = "请选择转出账户",
                                        fontSize = 16.sp,
                                        color = ICostTextPrimary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(ICostCardWhite)
                                    .iCostPressScale(pressedScale = 0.85f) {
                                        swapRotCount += 1
                                        inlineError = null
                                        val tmp = sourceWalletId
                                        sourceWalletId = targetWalletId
                                        targetWalletId = tmp
                                    }
                                    .graphicsLayer { rotationZ = swapAngle },
                                contentAlignment = Alignment.Center
                            ) {
                                ICostAssetImage(
                                    assetFile = "transfer_swift.webp",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = ICostCardWhite,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(66.dp)
                                .iCostPressScale(pressedScale = 0.97f) { showWalletPickerForTarget = true }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (toWallet != null) {
                                    WalletBrandAvatar(toWallet, size = 36.dp)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = formatWalletDisplayName(toWallet),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ICostTextPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = if (toWallet.type.isLiability) {
                                            "待还 ${formatMoneyCents(toWallet.balanceCents, toWallet.currency)}"
                                        } else {
                                            formatMoneyCents(toWallet.balanceCents, toWallet.currency)
                                        },
                                        fontSize = 14.sp,
                                        color = if (toWallet.type.isLiability) ICostCoralRed else ICostTextSecondary
                                    )
                                } else {
                                    Text(
                                        text = "请选择转入账户",
                                        fontSize = 16.sp,
                                        color = ICostTextPrimary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. 快捷属性胶囊栏（选择账户 | 报销 | 优惠 | 标签 + 右侧币种胶囊）
            // 未手动选择时展示保存时实际会使用的默认账户，避免「显示未选、实际记入某账户」
            val currentWallet = wallets.firstOrNull { it.id == sourceWalletId }
                ?: wallets.firstOrNull { !it.type.isLiability }
                ?: wallets.firstOrNull()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (txType == TransactionType.EXPENSE || txType == TransactionType.INCOME) {
                        item {
                            ICostOptionPill(
                                assetFile = "opt_account.webp",
                                label = currentWallet?.name ?: "选择账户",
                                active = sourceWalletId != null,
                                onClick = { showWalletPickerForTarget = false }
                            )
                        }
                    }
                    if (txType == TransactionType.EXPENSE) {
                        item {
                            ICostOptionPill(
                                assetFile = if (reimbursable) "opt_claim_selected.webp" else "opt_claim_unselected.webp",
                                label = if (reimbursable) "待报销" else "报销",
                                active = reimbursable,
                                activeBg = ICostBlueAccent,
                                activeTextColor = Color.White,
                                onClick = { reimbursable = !reimbursable }
                            )
                        }
                        item {
                            val hasDisc = (discountYuanStr.toDoubleOrNull() ?: 0.0) > 0.0
                            ICostOptionPill(
                                assetFile = "opt_discount.webp",
                                label = if (hasDisc) "优惠 ¥$discountYuanStr" else "优惠",
                                active = hasDisc,
                                onClick = { showDiscountDialog = true }
                            )
                        }
                    }
                    if (txType == TransactionType.TRANSFER) {
                        item {
                            val hasFee = (feeYuanStr.toDoubleOrNull() ?: 0.0) > 0.0
                            ICostOptionPill(
                                assetFile = "opt_fee.webp",
                                label = if (hasFee) "手续费 ¥$feeYuanStr" else "手续费",
                                active = hasFee,
                                onClick = { showFeeDialog = true }
                            )
                        }
                    }
                    item {
                        ICostOptionPill(
                            assetFile = "opt_tag.webp",
                            label = if (tagText.isNotBlank()) "#$tagText" else "标签",
                            active = tagText.isNotBlank(),
                            onClick = { showTagDialog = true }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (currency != "CNY") Color(0xFFEAF2FF) else ICostCardWhite,
                    modifier = Modifier.iCostPressScale(pressedScale = 0.92f) { showCurrencyPicker = true }
                ) {
                    Text(
                        text = "$sym $currency",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextPrimary,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                    )
                }
            }

            // 4. 白色大圆角金额与备注输入卡（ICDIN 34sp 数字 + 🕒 HH:mm 胶囊 + 点击填写备注 + ^）
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
                        else -> Color(0xFFF5A623)
                    }
                    val displayExpr = if (expr.isEmpty()) "${sym}0.00" else "$sym$expr"
                    Text(
                        text = displayExpr,
                        fontFamily = ICDinFontFamily,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = amountColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer { translationX = amountShake.value * density }
                    )
                    AnimatedVisibility(
                        visible = inlineError != null,
                        enter = expandVertically(tween(160)) + fadeIn(tween(160)),
                        exit = shrinkVertically(tween(140)) + fadeOut(tween(120))
                    ) {
                        Text(
                            text = inlineError.orEmpty(),
                            fontSize = 12.sp,
                            color = ICostCoralRed,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFF0F0F0))
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (timeManuallySet) Color(0xFFEAF2FF) else Color(0xFFF3F3F5),
                            modifier = Modifier.iCostPressScale(pressedScale = 0.92f) { showDatePicker = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.AccessTime,
                                    contentDescription = "修改记账时间",
                                    tint = ICostTextPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = timeLabel,
                                    fontSize = 12.sp,
                                    color = ICostTextPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Box(modifier = Modifier.weight(1f)) {
                            if (noteText.isEmpty()) {
                                Text("点击填写备注", fontSize = 14.sp, color = Color(0xFFB8B8B8))
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

            // 5. 4x4 圆角白色数字计算器键盘（1:1 复刻 iCost `+ ×` 与 `- ÷` 复合键）
            // 编辑已有账单时隐藏「保存再记」，避免反复覆盖同一笔
            val keyRows = listOf(
                listOf("1", "2", "3", "BACK"),
                listOf("4", "5", "6", "+×"),
                listOf("7", "8", "9", "-÷"),
                if (isEditing) listOf(".", "0", "DONE") else listOf(".", "0", "AGAIN", "DONE")
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                keyRows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { key ->
                            val isDoneKey = key == "DONE"
                            val bgColor = if (isDoneKey) {
                                when (txType) {
                                    TransactionType.EXPENSE -> ICostCoralRed
                                    TransactionType.INCOME -> ICostGreenIncome
                                    else -> Color(0xFFF5A623)
                                }
                            } else {
                                ICostCardWhite
                            }

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = bgColor,
                                modifier = Modifier
                                    .weight(if (isEditing && isDoneKey) 2f else 1f)
                                    .height(48.dp)
                                    .iCostPressScale(pressedScale = 0.91f) {
                                        if (isSubmitting) return@iCostPressScale
                                        if (key != "AGAIN" && key != "DONE") inlineError = null
                                        when (key) {
                                            "BACK" -> if (expr.isNotEmpty()) expr = expr.dropLast(1)
                                            "+×" -> appendOperator('+', '×')
                                            "-÷" -> appendOperator('-', '÷')
                                            "." -> {
                                                val lastPart = expr.split('+', '-', '×', '÷').lastOrNull() ?: ""
                                                if (!lastPart.contains('.')) {
                                                    expr = if (lastPart.isEmpty()) "${expr}0." else "$expr."
                                                }
                                            }
                                            "AGAIN" -> buildAndSubmit(keepOpen = true, requestSheetDismiss = requestDismiss)
                                            "DONE" -> {
                                                if (hasPendingOperator) {
                                                    val res = evaluateOrFlash()
                                                    if (res != null) {
                                                        inlineError = null
                                                        expr = if (res <= 0.0) "" else {
                                                            String.format(Locale.US, "%.2f", res).trimEnd('0').trimEnd('.')
                                                        }
                                                        if (res < 0.0) flashError("结果小于 0，已清空")
                                                    }
                                                } else {
                                                    buildAndSubmit(keepOpen = false, requestSheetDismiss = requestDismiss)
                                                }
                                            }
                                            else -> appendDigit(key)
                                        }
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    when (key) {
                                        "BACK" -> Icon(
                                            Icons.AutoMirrored.Filled.Backspace,
                                            contentDescription = "退格",
                                            tint = ICostTextPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        "+×" -> Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("+", fontSize = 21.sp, fontWeight = FontWeight.Medium, color = ICostTextPrimary)
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("×", fontSize = 15.sp, color = ICostTextSecondary)
                                        }
                                        "-÷" -> Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("-", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = ICostTextPrimary)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("÷", fontSize = 15.sp, color = ICostTextSecondary)
                                        }
                                        "AGAIN" -> Text(
                                            "保存再记",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ICostTextPrimary
                                        )
                                        "DONE" -> Text(
                                            if (hasPendingOperator) "=" else "完成",
                                            fontSize = 16.sp,
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
            title = {
                Text(
                    when {
                        pickingTarget -> "选择转入账户"
                        txType == TransactionType.TRANSFER -> "选择转出账户"
                        else -> "选择账户"
                    },
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(wallets) { w ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (pickingTarget) targetWalletId = w.id else sourceWalletId = w.id
                                    inlineError = null
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
                                fontFamily = ICDinFontFamily,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
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

    if (showLedgerPicker) {
        AlertDialog(
            onDismissRequest = { showLedgerPicker = false },
            containerColor = ICostCardWhite,
            title = { Text("选择记入账本", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    ledgers.forEach { name ->
                        Surface(
                            onClick = {
                                targetLedger = name
                                showLedgerPicker = false
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (name == targetLedger) Color(0xFFEAF2FF) else ICostCellGray,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (name == targetLedger) "✓  $name" else name,
                                fontWeight = if (name == targetLedger) FontWeight.Bold else FontWeight.Medium,
                                color = if (name == targetLedger) ICostBlueAccent else ICostTextPrimary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLedgerPicker = false }) { Text("关闭") }
            }
        )
    }

    if (showCurrencyPicker) {
        AlertDialog(
            onDismissRequest = { showCurrencyPicker = false },
            containerColor = ICostCardWhite,
            title = { Text("切换记账币种", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CurrencyCode.entries.forEach { item ->
                        Surface(
                            onClick = {
                                currency = item.name
                                showCurrencyPicker = false
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (currency == item.name) Color(0xFFEAF2FF) else ICostCellGray,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${item.displayName} (${item.symbol})",
                                fontWeight = if (currency == item.name) FontWeight.Bold else FontWeight.Medium,
                                color = if (currency == item.name) ICostBlueAccent else ICostTextPrimary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCurrencyPicker = false }) { Text("关闭") }
            }
        )
    }

    if (showDiscountDialog) {
        MoneyInputDialog(
            title = "设置优惠金额",
            label = "优惠金额（元）",
            supporting = "留空表示没有优惠",
            initial = discountYuanStr,
            onDismiss = { showDiscountDialog = false },
            onConfirm = { value ->
                discountYuanStr = value
                showDiscountDialog = false
            }
        )
    }

    if (showFeeDialog) {
        MoneyInputDialog(
            title = "设置转账手续费",
            label = "手续费（元）",
            supporting = "留空表示没有手续费",
            initial = feeYuanStr,
            onDismiss = { showFeeDialog = false },
            onConfirm = { value ->
                feeYuanStr = value
                showFeeDialog = false
            }
        )
    }

    if (showDatePicker) {
        val initialUtcMillis = remember(entryTimestamp) {
            // DatePicker 以 UTC 零点表示日期：把本地日期换算到对应的 UTC 零点
            val local = Calendar.getInstance().apply { timeInMillis = entryTimestamp }
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
            }.timeInMillis
        }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialUtcMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val picked = datePickerState.selectedDateMillis
                    if (picked != null) {
                        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = picked }
                        val local = Calendar.getInstance().apply { timeInMillis = entryTimestamp }
                        local.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
                        entryTimestamp = local.timeInMillis
                        timeManuallySet = true
                    }
                    showDatePicker = false
                    showTimePicker = true
                }) { Text("下一步", fontWeight = FontWeight.Bold, color = ICostBlueAccent) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = datePickerState, showModeToggle = false)
        }
    }

    if (showTimePicker) {
        val local = remember { Calendar.getInstance().apply { timeInMillis = entryTimestamp } }
        val timePickerState = rememberTimePickerState(
            initialHour = local.get(Calendar.HOUR_OF_DAY),
            initialMinute = local.get(Calendar.MINUTE),
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            containerColor = ICostCardWhite,
            title = { Text("选择时间", fontWeight = FontWeight.Bold) },
            text = {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TimePicker(state = timePickerState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val c = Calendar.getInstance().apply { timeInMillis = entryTimestamp }
                    c.set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                    c.set(Calendar.MINUTE, timePickerState.minute)
                    c.set(Calendar.SECOND, 0)
                    c.set(Calendar.MILLISECOND, 0)
                    entryTimestamp = c.timeInMillis
                    timeManuallySet = true
                    showTimePicker = false
                }) { Text("确定", fontWeight = FontWeight.Bold, color = ICostBlueAccent) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("跳过") }
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

/**
 * 通用金额输入弹窗：数字键盘 + 两位小数过滤；留空表示清除。
 */
@Composable
private fun MoneyInputDialog(
    title: String,
    label: String,
    supporting: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val cents = parseYuanToCents(text)
    val isError = text.isNotBlank() && cents == null
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ICostCardWhite,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = sanitizeMoneyInput(it) },
                label = { Text(label) },
                supportingText = { Text(if (isError) "请输入正确的金额" else supporting) },
                isError = isError,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
        },
        confirmButton = {
            TextButton(
                enabled = !isError,
                onClick = {
                    onConfirm(if (cents == null || cents == 0L) "" else text.trimEnd('.'))
                }
            ) { Text("确定", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun ICostOptionPill(
    assetFile: String,
    label: String,
    active: Boolean,
    activeBg: Color = Color(0xFFEAF2FF),
    activeTextColor: Color = ICostTextPrimary,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (active) activeBg else ICostCardWhite,
        animationSpec = tween(180),
        label = "OptionPillBg"
    )
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bgColor,
        modifier = Modifier.iCostPressScale(pressedScale = 0.92f, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ICostAssetImage(
                assetFile = assetFile,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                color = if (active) activeTextColor else ICostTextPrimary,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}
