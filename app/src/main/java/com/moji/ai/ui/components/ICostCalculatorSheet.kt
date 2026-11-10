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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.collectAsState
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
import com.moji.ai.data.model.AppearanceConfigManager
import com.moji.ai.data.model.CategoryCatalog
import com.moji.ai.data.model.CategoryDef
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.CurrencyRateManager
import com.moji.ai.data.model.SubCategoryDef
import com.moji.ai.data.model.TagCatalog
import com.moji.ai.data.model.TransactionType
import com.moji.ai.ui.QuickBookkeepingTemplate
import com.moji.ai.ui.screens.CategoryEditorBottomSheet
import com.moji.ai.ui.screens.ICostColorTagChip
import com.moji.ai.ui.screens.TagEditorBottomSheet
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
import com.moji.ai.ui.theme.ICostExpenseColor
import com.moji.ai.ui.theme.ICostFormField
import com.moji.ai.ui.theme.ICostGreenIncome
import com.moji.ai.ui.theme.ICostIncomeColor
import com.moji.ai.ui.theme.ICostPillGray
import com.moji.ai.ui.theme.ICostSheetHeader
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
    suspendedBySubpage: Boolean = false,
    editingTransaction: TransactionEntity? = null,
    initialType: TransactionType = TransactionType.EXPENSE,
    initialSourceWalletId: Long? = null,
    initialTargetWalletId: Long? = null,
    onDismiss: () -> Unit,
    onSaveTransaction: (TransactionEntity, keepOpen: Boolean) -> Unit,
    onOpenAiSandbox: () -> Unit = {},
    onOpenCategoryManagement: (isExpense: Boolean) -> Unit = {},
    onOpenTagManagement: () -> Unit = {},
    templates: List<QuickBookkeepingTemplate> = emptyList(),
    templateDraft: QuickBookkeepingTemplate? = null,
    onConsumeTemplateDraft: () -> Unit = {},
    onOpenTemplateManagement: () -> Unit = {},
    onLoadPresetTemplates: () -> Unit = {},
    onOpenCurrencyManagement: () -> Unit = {},
    onOpenAgentTriage: () -> Unit = {}
) {
    val isEditing = editingTransaction != null
    val expenseCategories by CategoryCatalog.expenseCategoriesFlow.collectAsState()
    val incomeCategories by CategoryCatalog.incomeCategoriesFlow.collectAsState()
    val catalogTags by TagCatalog.tagsFlow.collectAsState()
    val tagRemapHistory by TagCatalog.remapHistoryFlow.collectAsState()
    val customCurrencyRates by CurrencyRateManager.customRatesFlow.collectAsState()
    var quickAddSubParent by remember { mutableStateOf<Pair<Boolean, CategoryDef>?>(null) }
    var showQuickAddTagSheet by remember { mutableStateOf(false) }
    // 1:1 iCost 顶部三态：支出 | 收入 | 转账（REPAYMENT/LOAN_IN/LOAN_OUT/REFUND_OFFSET 自动归入对应 Tab，未切换 Tab 时保持原账务类型）
    fun mapToCalculatorTab(raw: TransactionType): TransactionType = when (raw) {
        TransactionType.REPAYMENT -> TransactionType.TRANSFER
        TransactionType.LOAN_IN, TransactionType.REFUND_OFFSET -> TransactionType.INCOME
        TransactionType.LOAN_OUT -> TransactionType.EXPENSE
        else -> raw
    }
    var preservedSpecialType by remember {
        val raw = editingTransaction?.type ?: initialType
        mutableStateOf(
            raw.takeIf {
                it == TransactionType.LOAN_IN ||
                    it == TransactionType.LOAN_OUT ||
                    it == TransactionType.REFUND_OFFSET
            }
        )
    }
    var txType by remember {
        val raw = editingTransaction?.type ?: initialType
        mutableStateOf(mapToCalculatorTab(raw))
    }

    // 解析初始主分类与子分类
    val defaultExpName = expenseCategories.firstOrNull()?.name ?: "餐饮"
    val initFullCategory = editingTransaction?.category ?: defaultExpName
    val initParts = initFullCategory.split("-")
    var selectedMainCategory by remember { mutableStateOf(initParts.firstOrNull() ?: defaultExpName) }
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

    // 非转账模式下 sourceWalletId 表示「这笔账的账户」；收入类账单把账户存在 targetWalletId
    var sourceWalletId by remember {
        mutableStateOf(
            if (editingTransaction != null) editingAccountWalletId(editingTransaction) else initialSourceWalletId
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

    fun applyTemplateToDraft(tpl: QuickBookkeepingTemplate, resetZeroAmount: Boolean) {
        if (editingTransaction?.type == TransactionType.REFUND_OFFSET && (editingTransaction.relatedTransactionId ?: 0L) > 0L) {
            inlineError = "关联退款单不支持套用模板，请删除后重新退款"
            return
        }
        val resolvedType = tpl.resolvedType()
        preservedSpecialType = null
        txType = resolvedType
        if (resolvedType == TransactionType.EXPENSE || resolvedType == TransactionType.INCOME) {
            val activeList = if (resolvedType == TransactionType.EXPENSE) {
                CategoryCatalog.expenseCategories
            } else {
                CategoryCatalog.incomeCategories
            }
            val parts = tpl.category.split("-")
            val mainName = parts.firstOrNull().orEmpty()
            val subName = parts.getOrNull(1).orEmpty()
            val matchedMain = activeList.firstOrNull { it.name == mainName } ?: activeList.firstOrNull()
            selectedMainCategory = matchedMain?.name ?: if (resolvedType == TransactionType.EXPENSE) "餐饮" else "工资"
            selectedSubCategory = if (matchedMain != null && matchedMain.subCategories.any { it.name == subName }) {
                subName
            } else {
                ""
            }
            val fallbackW = wallets.firstOrNull { !it.type.isLiability } ?: wallets.firstOrNull()
            sourceWalletId = (wallets.firstOrNull { it.id == tpl.walletId } ?: fallbackW)?.id
            targetWalletId = null
        } else {
            sourceWalletId = wallets.firstOrNull { it.id == tpl.walletId }?.id
            targetWalletId = wallets.firstOrNull { it.id == tpl.targetWalletId }?.id?.takeIf { it != sourceWalletId }
        }
        if (tpl.amountCents > 0L) {
            expr = String.format(Locale.US, "%.2f", tpl.amountCents / 100.0).trimEnd('0').trimEnd('.')
        } else if (resetZeroAmount) {
            expr = ""
        }
        currency = CurrencyCode.fromCode(tpl.currency).name
        tagText = TagCatalog.joinTags(TagCatalog.parseTags(tpl.tags))
        noteText = tpl.note.ifBlank { tpl.title }
        reimbursable = resolvedType == TransactionType.EXPENSE && tpl.reimbursable
        inlineError = null
    }

    // 每次唤起面板时同步重置表单状态，配合 keepWarm 预热实现 0ms 首帧唤醒
    LaunchedEffect(visible, editingTransaction, initialType, initialSourceWalletId, initialTargetWalletId) {
        if (visible) {
            val raw = editingTransaction?.type ?: initialType
            preservedSpecialType = raw.takeIf {
                it == TransactionType.LOAN_IN ||
                    it == TransactionType.LOAN_OUT ||
                    it == TransactionType.REFUND_OFFSET
            }
            txType = mapToCalculatorTab(raw)
            val fallbackExpName = CategoryCatalog.expenseCategories.firstOrNull()?.name ?: "餐饮"
            val fallbackIncName = CategoryCatalog.incomeCategories.firstOrNull()?.name ?: "工资"
            val fullCat = editingTransaction?.category ?: if (txType == TransactionType.INCOME) {
                fallbackIncName
            } else {
                fallbackExpName
            }
            val parts = fullCat.split("-")
            selectedMainCategory = parts.firstOrNull() ?: fallbackExpName
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
            sourceWalletId = if (editingTransaction != null) {
                editingAccountWalletId(editingTransaction)
            } else {
                initialSourceWalletId
            }
            targetWalletId = editingTransaction?.targetWalletId ?: initialTargetWalletId
            entryTimestamp = editingTransaction?.timestamp ?: System.currentTimeMillis()
            timeManuallySet = false
            targetLedger = editingTransaction?.ledgerName ?: currentLedger
            isSubmitting = false
            inlineError = null
            showDiscardConfirm = false
            if (templateDraft != null && editingTransaction == null) {
                applyTemplateToDraft(templateDraft, resetZeroAmount = true)
                onConsumeTemplateDraft()
            }
        }
    }

    LaunchedEffect(visible, templateDraft) {
        if (visible && templateDraft != null && editingTransaction == null) {
            applyTemplateToDraft(templateDraft, resetZeroAmount = true)
            onConsumeTemplateDraft()
        }
    }

    // 当用户在「分类管理」二级页中重命名或删除了当前选中的分类时，返回计算器后自动修正选中项
    LaunchedEffect(expenseCategories, incomeCategories, txType) {
        if ((txType == TransactionType.EXPENSE || txType == TransactionType.INCOME) &&
            selectedMainCategory != CategoryCatalog.PENDING_TRIAGE_CATEGORY
        ) {
            val activeList = if (txType == TransactionType.EXPENSE) expenseCategories else incomeCategories
            val matchedMain = activeList.firstOrNull { it.name == selectedMainCategory }
            if (matchedMain == null) {
                selectedMainCategory = activeList.firstOrNull()?.name ?: "餐饮"
                selectedSubCategory = ""
            } else if (selectedSubCategory.isNotBlank() && matchedMain.subCategories.none { it.name == selectedSubCategory }) {
                selectedSubCategory = ""
            }
        }
    }

    // 当用户在「标签管理」二级页中重命名、合并或删除了草稿中的标签时，自动同步修正当前草稿的 tagText
    var lastHandledTagRemapSeq by remember {
        mutableLongStateOf(TagCatalog.remapHistoryFlow.value.lastOrNull()?.seq ?: 0L)
    }
    LaunchedEffect(tagRemapHistory) {
        for (record in tagRemapHistory) {
            if (record.seq > lastHandledTagRemapSeq) {
                val mapped = TagCatalog.remapTagsString(tagText, record.oldTag, record.newTag)
                if (mapped != null) {
                    tagText = mapped
                }
                lastHandledTagRemapSeq = record.seq
            }
        }
    }

    var showWalletPickerForTarget by remember { mutableStateOf<Boolean?>(null) } // false = source, true = target
    var showDiscountDialog by remember { mutableStateOf(false) }
    var showFeeDialog by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var showTemplatePickerSheet by remember { mutableStateOf(false) }
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
        if (
            editingTransaction?.type == TransactionType.REFUND_OFFSET &&
            (editingTransaction.relatedTransactionId ?: 0L) > 0L &&
            (cents != editingTransaction.amountCents || !editingTransaction.currency.equals(currency, ignoreCase = true) || preservedSpecialType != TransactionType.REFUND_OFFSET)
        ) {
            flashError("关联退款单不支持直接改金额或类型，请删除该退款后在原账单重新退款")
            return
        }
        val rate = if (
            editingTransaction != null &&
            editingTransaction.currency.equals(currency, ignoreCase = true) &&
            editingTransaction.exchangeRateToCny > 0.0
        ) {
            editingTransaction.exchangeRateToCny
        } else {
            CurrencyRateManager.getEffectiveRateToCny(currency)
        }
        val cnyCents = (cents * rate).roundToLong()
        val discountCents = if (txType == TransactionType.EXPENSE) parseYuanToCents(discountYuanStr) ?: 0L else 0L
        // 非转账账单没有手续费输入框：编辑时保留原有手续费（如自动识别出的手续费），避免余额被悄悄改动
        val feeCents = if (txType == TransactionType.TRANSFER) {
            parseYuanToCents(feeYuanStr) ?: 0L
        } else {
            editingTransaction?.feeCents ?: 0L
        }

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
            // 编辑原本未关联账户的账单时保持不关联，不擅自记到默认账户
            resolvedSourceId = sourceWalletId ?: if (editingTransaction != null) null else defaultWallet?.id
            resolvedTargetId = null
        }
        val resolvedSourceWallet = wallets.firstOrNull { it.id == resolvedSourceId }
        val resolvedTargetWallet = wallets.firstOrNull { it.id == resolvedTargetId }

        // 当用户未切换 Tab 时保留借入/借出/退款冲销原类型；处在「转账」模式且由资产转入信用账户时自动记录为 REPAYMENT
        val actualType = when {
            preservedSpecialType != null -> preservedSpecialType!!
            txType == TransactionType.TRANSFER &&
                resolvedSourceWallet?.type?.isLiability == false &&
                resolvedTargetWallet?.type?.isLiability == true -> TransactionType.REPAYMENT
            else -> txType
        }

        val finalCategory = when (actualType) {
            TransactionType.TRANSFER -> "内部转账"
            TransactionType.REPAYMENT -> "信用还款"
            TransactionType.LOAN_IN -> editingTransaction?.category?.takeIf { it.isNotBlank() && editingTransaction.type == TransactionType.LOAN_IN } ?: "借入"
            TransactionType.LOAN_OUT -> editingTransaction?.category?.takeIf { it.isNotBlank() && editingTransaction.type == TransactionType.LOAN_OUT } ?: "借出"
            TransactionType.REFUND_OFFSET -> editingTransaction?.category?.takeIf { it.isNotBlank() && editingTransaction.type == TransactionType.REFUND_OFFSET } ?: "退款入账"
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

        val isIncomeLike = actualType == TransactionType.INCOME ||
            actualType == TransactionType.LOAN_IN ||
            actualType == TransactionType.REFUND_OFFSET
        val finalSourceId = if (isIncomeLike) null else resolvedSourceId
        val finalTargetId = when (actualType) {
            TransactionType.INCOME, TransactionType.LOAN_IN, TransactionType.REFUND_OFFSET -> resolvedSourceId
            TransactionType.TRANSFER, TransactionType.REPAYMENT -> resolvedTargetId
            else -> null
        }
        val finalReimbursable = if (actualType == TransactionType.EXPENSE) reimbursable else false
        val finalTimestamp = if (editingTransaction == null && !timeManuallySet) {
            System.currentTimeMillis()
        } else {
            entryTimestamp
        }

        val normalizedTags = TagCatalog.joinTags(TagCatalog.parseTags(tagText))

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
            tags = normalizedTags,
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
            tags = normalizedTags,
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

    // 新记一笔且已输入金额时，关闭前确认，避免误触丢失（X、返回键、下拉、点遮罩都会拦截）
    val hasUnsavedInput = !isEditing && !isSubmitting && expr.isNotEmpty()
    val sheetVisible = visible && !suspendedBySubpage
    ICostSmoothBottomSheet(
        onDismissRequest = onDismiss,
        visible = sheetVisible,
        keepWarm = true,
        containerColor = ICostBgGray,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        showDragHandle = false,
        confirmDismiss = {
            if (hasUnsavedInput) {
                showDiscardConfirm = true
                false
            } else true
        }
    ) { requestDismiss ->
        val requestCloseWithConfirm: () -> Unit = {
            if (hasUnsavedInput) showDiscardConfirm = true else requestDismiss()
        }
        // 晚于抽屉自身注册，优先拦截系统返回键
        // 只在面板可见时注册：BackHandler 按注册先后决定优先级，常驻预热的面板若一直注册，
        // 会被之后打开的全屏页（钱包详情、日历）抢先处理返回键
        if (sheetVisible) {
            BackHandler(enabled = hasUnsavedInput) { showDiscardConfirm = true }
        }

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
                .fillMaxHeight(0.94f)
                .navigationBarsPadding()
        ) {
            // 1. 顶部栏：左侧 40dp 白色圆形 `✕` + 中间白色胶囊 [支出 | 收入 | 转账] + 右侧蓝色账本名
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
                                targetValue = if (selected) ICostPillGray else Color.Transparent,
                                animationSpec = tween(200),
                                label = "CalcModePillBg"
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(19.dp))
                                    .background(modeBgColor)
                                    .iCostPressScale(pressedScale = 0.92f) {
                                        if (txType == mode) return@iCostPressScale
                                        if (editingTransaction?.type == TransactionType.REFUND_OFFSET && (editingTransaction.relatedTransactionId ?: 0L) > 0L) {
                                            flashError("关联退款单不可切换收支类型")
                                            return@iCostPressScale
                                        }
                                        preservedSpecialType = null
                                        txType = mode
                                        inlineError = null
                                        if (mode == TransactionType.INCOME) {
                                            selectedMainCategory = CategoryCatalog.incomeCategories.firstOrNull()?.name ?: "工资"
                                            selectedSubCategory = ""
                                        } else if (mode == TransactionType.EXPENSE) {
                                            selectedMainCategory = CategoryCatalog.expenseCategories.firstOrNull()?.name ?: "餐饮"
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
                        expenseCategories
                    } else {
                        incomeCategories
                    }
                    val activeColor = if (currentMode == TransactionType.EXPENSE) ICostExpenseColor else ICostIncomeColor
                    val gridCells: List<CategoryDef?> = remember(categories) { categories + listOf(null) }
                    val rows = remember(gridCells) { gridCells.chunked(5) }
                    val categoryScrollState = rememberScrollState()

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(categoryScrollState)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 顶部「待梳理（交由 Agent）」免分类记账胶囊 + 「✨ Agent 梳理」主入口
                        val isPendingSelected = selectedMainCategory == CategoryCatalog.PENDING_TRIAGE_CATEGORY
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isPendingSelected) Color(0xFF6366F1) else ICostPillGray,
                                modifier = Modifier.iCostPressScale(pressedScale = 0.93f) {
                                    if (isPendingSelected) {
                                        selectedMainCategory = categories.firstOrNull()?.name ?: "餐饮"
                                        selectedSubCategory = ""
                                    } else {
                                        selectedMainCategory = CategoryCatalog.PENDING_TRIAGE_CATEGORY
                                        selectedSubCategory = ""
                                    }
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ICostAssetImage(
                                        assetFile = "setting_auto.webp",
                                        tint = if (isPendingSelected) Color.White else Color(0xFF6366F1),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = if (isPendingSelected) "✓ 待梳理（交由 Agent）" else "待梳理（交由 Agent）",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isPendingSelected) Color.White else ICostTextPrimary
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFFEEF2FF),
                                modifier = Modifier.iCostPressScale(pressedScale = 0.93f) {
                                    onOpenAgentTriage()
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "✨ Agent 梳理",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4F46E5)
                                    )
                                }
                            }
                        }

                        rows.forEach { rowItems ->
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    rowItems.forEach { cat ->
                                        if (cat != null) {
                                            val isSelected = selectedMainCategory == cat.name
                                            val circleBg = if (isSelected) activeColor else ICostPillGray
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .iCostPressScale(pressedScale = 0.88f) {
                                                        if (isSelected && selectedSubCategory.isEmpty()) {
                                                            // 再次轻点已选中的主分类可取消选择，进入「待梳理」状态
                                                            selectedMainCategory = CategoryCatalog.PENDING_TRIAGE_CATEGORY
                                                            selectedSubCategory = ""
                                                        } else {
                                                            selectedMainCategory = cat.name
                                                            selectedSubCategory = ""
                                                        }
                                                    },
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Box(
                                                    modifier = Modifier.size(48.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(44.dp)
                                                            .clip(CircleShape)
                                                            .background(circleBg),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        ICostAssetImage(
                                                            assetFile = cat.assetFile,
                                                            tint = if (isSelected) Color.White else ICostSlateIcon,
                                                            modifier = Modifier.size(24.dp)
                                                        )
                                                    }
                                                    // 拥有二级分类的类别在右上角显示 14dp 浅灰 `•••` 小圆角标
                                                    if (cat.subCategories.isNotEmpty()) {
                                                        Box(
                                                            modifier = Modifier
                                                                .align(Alignment.TopEnd)
                                                                .size(14.dp)
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
                                                Spacer(modifier = Modifier.height(3.dp))
                                                Text(
                                                    text = cat.name,
                                                    fontSize = 12.sp,
                                                    color = ICostTextPrimary,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    maxLines = 1
                                                )
                                            }
                                        } else {
                                            // 一级分类网格末尾「管理」快捷入口
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .iCostPressScale(pressedScale = 0.88f) {
                                                        onOpenCategoryManagement(currentMode == TransactionType.EXPENSE)
                                                    },
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Box(
                                                    modifier = Modifier.size(48.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(44.dp)
                                                            .clip(CircleShape)
                                                            .background(ICostPillGray),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        ICostAssetImage(
                                                            assetFile = "setting_category.webp",
                                                            tint = ICostSlateIcon,
                                                            modifier = Modifier.size(22.dp)
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(3.dp))
                                                Text(
                                                    text = "管理",
                                                    fontSize = 12.sp,
                                                    color = ICostTextSecondary,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                    repeat(5 - rowItems.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }

                                // 1:1 展开当前选中主分类的 5 列浅灰圆角二级分类盒（高响应临界阻尼弹簧过渡）
                                val expandedCat = rowItems.filterNotNull().firstOrNull {
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
                                            Spacer(modifier = Modifier.height(5.dp))
                                            Surface(
                                                shape = RoundedCornerShape(16.dp),
                                                color = ICostPillGray,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                val subCells: List<SubCategoryDef?> = remember(expandedCat.subCategories) {
                                                    expandedCat.subCategories + listOf(null)
                                                }
                                                val subRows = remember(subCells) { subCells.chunked(5) }
                                                Column(
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    subRows.forEach { subRow ->
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            subRow.forEach { sub ->
                                                                if (sub != null) {
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
                                                                                .size(30.dp)
                                                                                .clip(CircleShape)
                                                                                .background(if (subSelected) activeColor else Color.Transparent),
                                                                            contentAlignment = Alignment.Center
                                                                        ) {
                                                                            ICostAssetImage(
                                                                                assetFile = sub.assetFile,
                                                                                tint = if (subSelected) Color.White else ICostSlateIcon,
                                                                                modifier = Modifier.size(18.dp)
                                                                            )
                                                                        }
                                                                        Spacer(modifier = Modifier.height(1.dp))
                                                                        Text(
                                                                            text = sub.name,
                                                                            fontSize = 11.sp,
                                                                            color = if (subSelected) activeColor else ICostTextPrimary,
                                                                            fontWeight = if (subSelected) FontWeight.Bold else FontWeight.Normal,
                                                                            maxLines = 1
                                                                        )
                                                                    }
                                                                } else {
                                                                    // 展开二级分类盒末尾「+ 新建」快捷胶囊
                                                                    Column(
                                                                        modifier = Modifier
                                                                            .weight(1f)
                                                                            .iCostPressScale(pressedScale = 0.88f) {
                                                                                quickAddSubParent = (currentMode == TransactionType.EXPENSE) to expandedCat
                                                                            },
                                                                        horizontalAlignment = Alignment.CenterHorizontally
                                                                    ) {
                                                                        Box(
                                                                            modifier = Modifier
                                                                                .size(30.dp)
                                                                                .clip(CircleShape)
                                                                                .background(Color(0xFFE0E0E4)),
                                                                            contentAlignment = Alignment.Center
                                                                        ) {
                                                                            Icon(
                                                                                imageVector = Icons.Default.Add,
                                                                                contentDescription = "新建二级分类",
                                                                                tint = ICostSlateIcon,
                                                                                modifier = Modifier.size(16.dp)
                                                                            )
                                                                        }
                                                                        Spacer(modifier = Modifier.height(1.dp))
                                                                        Text(
                                                                            text = "+ 新建",
                                                                            fontSize = 11.sp,
                                                                            color = ICostTextSecondary,
                                                                            fontWeight = FontWeight.Medium,
                                                                            maxLines = 1
                                                                        )
                                                                    }
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
                ?: if (isEditing) null else (wallets.firstOrNull { !it.type.isLiability } ?: wallets.firstOrNull())
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
                        val parsedTags = remember(tagText) { TagCatalog.parseTags(tagText) }
                        val hasSelectedTags = parsedTags.isNotEmpty()
                        val primaryTagColor = if (hasSelectedTags) {
                            Color(TagCatalog.resolveTagColorHex(parsedTags.first()))
                        } else {
                            ICostBlueAccent
                        }
                        ICostOptionPill(
                            assetFile = "opt_tag.webp",
                            label = if (hasSelectedTags) {
                                parsedTags.joinToString(" · ") { "#$it" }
                            } else {
                                "标签"
                            },
                            active = hasSelectedTags,
                            activeBg = primaryTagColor.copy(alpha = 0.16f),
                            activeTextColor = primaryTagColor,
                            onClick = { showTagDialog = true }
                        )
                    }
                    if (!isEditing) {
                        item {
                            ICostOptionPill(
                                assetFile = "setting_template.webp",
                                label = if (templates.isEmpty()) "模板" else "模板 (${templates.size})",
                                active = false,
                                onClick = { showTemplatePickerSheet = true }
                            )
                        }
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
                    .padding(horizontal = 14.dp, vertical = 3.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    val amountColor = when (txType) {
                        TransactionType.EXPENSE -> ICostExpenseColor
                        TransactionType.INCOME -> ICostIncomeColor
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
                    if (currency != "CNY") {
                        val effectiveRate = remember(currency, customCurrencyRates) {
                            CurrencyRateManager.getEffectiveRateToCny(currency)
                        }
                        val isCustom = customCurrencyRates.containsKey(currency)
                        val previewVal = evaluateExpr(expr)?.takeIf { it > 0.0 } ?: 0.0
                        val previewCny = String.format(Locale.US, "%.2f", previewVal * effectiveRate)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "≈ ¥$previewCny CNY · 1 $currency = ¥${CurrencyRateManager.formatRate(effectiveRate)}" +
                                    if (isCustom) " (自定义)" else "",
                                fontFamily = ICDinFontFamily,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostBlueAccent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "汇率设置",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostBlueAccent,
                                modifier = Modifier
                                    .clickable { onOpenCurrencyManagement() }
                                    .padding(start = 8.dp, top = 2.dp, bottom = 2.dp)
                            )
                        }
                    }
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

                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider(thickness = 0.5.dp, color = ICostDivider)
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (timeManuallySet) Color(0xFFEAF2FF) else ICostPillGray,
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
                                    TransactionType.EXPENSE -> ICostExpenseColor
                                    TransactionType.INCOME -> ICostIncomeColor
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
        val currentExprValue = evaluateExpr(expr)?.takeIf { it > 0.0 } ?: 0.0
        AlertDialog(
            onDismissRequest = { showCurrencyPicker = false },
            containerColor = ICostCardWhite,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("切换记账币种", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ICostBlueAccent.copy(alpha = 0.12f),
                        modifier = Modifier.clickable {
                            showCurrencyPicker = false
                            onOpenCurrencyManagement()
                        }
                    ) {
                        Text(
                            text = "汇率设置",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = ICostBlueAccent,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CurrencyCode.entries.forEach { item ->
                        val isSelected = currency == item.name
                        val rate = customCurrencyRates[item.name] ?: item.defaultRateToCny
                        val isCustom = item != CurrencyCode.CNY && customCurrencyRates.containsKey(item.name)
                        Surface(
                            onClick = {
                                currency = item.name
                                showCurrencyPicker = false
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) Color(0xFFEAF2FF) else ICostCellGray,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${item.displayName} (${item.symbol})",
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) ICostBlueAccent else ICostTextPrimary,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (item == CurrencyCode.CNY) {
                                        Text(
                                            text = "本位币",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ICostTextSecondary
                                        )
                                    } else {
                                        Text(
                                            text = "1 ${item.name} = ¥${CurrencyRateManager.formatRate(rate)}" +
                                                if (isCustom) " (自定义)" else "",
                                            fontFamily = ICDinFontFamily,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isCustom || isSelected) ICostBlueAccent else ICostTextSecondary
                                        )
                                    }
                                }
                                if (item != CurrencyCode.CNY && currentExprValue > 0.0) {
                                    val cnyPreview = String.format(Locale.US, "%.2f", currentExprValue * rate)
                                    Text(
                                        text = "当次 ${item.symbol}${String.format(Locale.US, "%.2f", currentExprValue)} ≈ ¥$cnyPreview CNY",
                                        fontFamily = ICDinFontFamily,
                                        fontSize = 11.sp,
                                        color = ICostTextSecondary,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
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
        val selectedTags = remember(tagText) { TagCatalog.parseTags(tagText) }
        ICostSmoothBottomSheet(
            onDismissRequest = { showTagDialog = false },
            containerColor = ICostBgGray,
            showDragHandle = false
        ) { requestDismiss ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                ICostSheetHeader(
                    title = "选择标签",
                    subtitle = if (selectedTags.isEmpty()) {
                        "可多选标签，用于跨分类归集出差、聚餐、分期等场景账单"
                    } else {
                        "已选 ${selectedTags.size} 个：${selectedTags.joinToString(" · ") { "#$it" }}"
                    },
                    onClose = requestDismiss
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 顶部快捷操作栏：+ 快速新建标签 | 管理标签 | 清空已选
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = ICostBlueAccent,
                            modifier = Modifier.iCostPressScale(pressedScale = 0.94f) {
                                showQuickAddTagSheet = true
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "快速新建标签",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = ICostCardWhite,
                            modifier = Modifier.iCostPressScale(pressedScale = 0.94f) {
                                showTagDialog = false
                                onOpenTagManagement()
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ICostAssetImage(
                                    assetFile = "setting_tag.webp",
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "管理标签",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ICostTextPrimary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        if (selectedTags.isNotEmpty()) {
                            TextButton(
                                onClick = { tagText = "" },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "清空已选",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ICostCoralRed
                                )
                            }
                        }
                    }

                    // 按分组展示全部标签彩色胶囊（支持多选点选切换）
                    val groupedTags = remember(catalogTags) {
                        val order = TagCatalog.presetGroups
                        catalogTags
                            .groupBy { it.groupName.ifBlank { "日常" } }
                            .entries
                            .sortedBy { (grp, _) ->
                                val idx = order.indexOf(grp)
                                if (idx >= 0) idx else order.size
                            }
                    }

                    groupedTags.forEach { (groupName, groupItems) ->
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = ICostCardWhite,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = groupName,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ICostTextSecondary
                                )

                                @OptIn(ExperimentalLayoutApi::class)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    groupItems.forEach { tagDef ->
                                        val isSelected = tagDef.name in selectedTags
                                        val baseColor = Color(tagDef.colorHex)
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (isSelected) baseColor else baseColor.copy(alpha = 0.12f),
                                            modifier = Modifier.iCostPressScale(pressedScale = 0.93f) {
                                                val updated = if (isSelected) {
                                                    selectedTags.filterNot { it == tagDef.name }
                                                } else {
                                                    selectedTags + tagDef.name
                                                }
                                                tagText = TagCatalog.joinTags(updated)
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = Color.White,
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                } else {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(7.dp)
                                                            .clip(CircleShape)
                                                            .background(baseColor)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(5.dp))
                                                Text(
                                                    text = "#${tagDef.name}",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isSelected) Color.White else baseColor
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Button(
                        onClick = requestDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp)
                            .height(46.dp)
                    ) {
                        Text(
                            text = if (selectedTags.isEmpty()) "完成" else "完成（已选 ${selectedTags.size} 个）",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }

    if (showQuickAddTagSheet) {
        TagEditorBottomSheet(
            existingTag = null,
            existingTagNames = catalogTags.map { it.name },
            initialGroup = "日常",
            onDismiss = { showQuickAddTagSheet = false },
            onSave = { newTagName, newColorHex, newGroupName, requestDismiss ->
                val res = TagCatalog.addTag(
                    name = newTagName,
                    colorHex = newColorHex,
                    groupName = newGroupName
                )
                if (res.isSuccess) {
                    val created = res.getOrThrow()
                    val currentList = TagCatalog.parseTags(tagText)
                    tagText = TagCatalog.joinTags(currentList + created.name)
                    requestDismiss()
                }
            }
        )
    }

    quickAddSubParent?.let { (isExp, parentCat) ->
        CategoryEditorBottomSheet(
            isExpense = isExp,
            isSubCategory = true,
            parentName = parentCat.name,
            existingName = null,
            initialIconAsset = parentCat.assetFile,
            siblingNames = parentCat.subCategories.map { it.name },
            onDismiss = { quickAddSubParent = null },
            onSave = { newSubName, newAsset, requestDismiss ->
                val res = CategoryCatalog.addSubCategory(
                    isExpense = isExp,
                    parentName = parentCat.name,
                    subName = newSubName,
                    assetFile = newAsset
                )
                if (res.isSuccess) {
                    selectedMainCategory = parentCat.name
                    selectedSubCategory = newSubName
                    requestDismiss()
                }
            }
        )
    }

    if (showTemplatePickerSheet) {
        CalculatorTemplatePickerSheet(
            templates = templates,
            onDismiss = { showTemplatePickerSheet = false },
            onSelectTemplate = { tpl, requestDismiss ->
                applyTemplateToDraft(tpl, resetZeroAmount = false)
                requestDismiss()
            },
            onOpenTemplateManagement = {
                showTemplatePickerSheet = false
                onOpenTemplateManagement()
            },
            onLoadPresetTemplates = onLoadPresetTemplates
        )
    }
}

@Composable
private fun CalculatorTemplatePickerSheet(
    templates: List<QuickBookkeepingTemplate>,
    onDismiss: () -> Unit,
    onSelectTemplate: (QuickBookkeepingTemplate, requestDismiss: () -> Unit) -> Unit,
    onOpenTemplateManagement: () -> Unit,
    onLoadPresetTemplates: () -> Unit
) {
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
                title = "选择记账模板",
                subtitle = "点选模板一键预填分类、账户、标签、备注与预设金额",
                onClose = requestDismiss
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = ICostCardWhite,
                        modifier = Modifier.iCostPressScale(pressedScale = 0.94f) {
                            onOpenTemplateManagement()
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ICostAssetImage(
                                assetFile = "setting_template.webp",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "管理模板",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ICostTextPrimary
                            )
                        }
                    }
                    if (templates.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFFFFF3E0),
                            modifier = Modifier.iCostPressScale(pressedScale = 0.94f) {
                                onLoadPresetTemplates()
                            }
                        ) {
                            Text(
                                text = "一键加载常用预设",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD97706),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                if (templates.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = ICostCardWhite,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "暂无记账模板，点击上方「一键加载常用预设」或「管理模板」新建",
                            fontSize = 13.sp,
                            color = ICostTextSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                        )
                    }
                } else {
                    templates.forEach { tpl ->
                        val resolvedType = tpl.resolvedType()
                        val amtColor = when (resolvedType) {
                            TransactionType.INCOME -> ICostIncomeColor
                            TransactionType.TRANSFER -> ICostAmberGold
                            else -> ICostExpenseColor
                        }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = ICostCardWhite,
                            modifier = Modifier
                                .fillMaxWidth()
                                .iCostPressScale(pressedScale = 0.96f) {
                                    onSelectTemplate(tpl, requestDismiss)
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ICostCategoryBadge(
                                    category = if (resolvedType == TransactionType.TRANSFER) "内部转账" else tpl.category,
                                    type = resolvedType,
                                    size = 34.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = tpl.title,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ICostTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = buildString {
                                            append(if (resolvedType == TransactionType.TRANSFER) "内部转账" else tpl.category.replace("-", " · "))
                                            if (tpl.reimbursable && resolvedType == TransactionType.EXPENSE) append(" · 待报销")
                                            if (tpl.tags.isNotBlank()) append(" · #${TagCatalog.parseTags(tpl.tags).joinToString(" #")}")
                                        },
                                        fontSize = 11.sp,
                                        color = ICostTextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (tpl.amountCents > 0L) {
                                        formatMoneyCents(tpl.amountCents, tpl.currency)
                                    } else {
                                        "待填金额"
                                    },
                                    fontFamily = ICDinFontFamily,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (tpl.amountCents > 0L) amtColor else Color(0xFFD97706)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
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
            ICostFormField(
                value = text,
                onValueChange = { text = sanitizeMoneyInput(it) },
                label = label,
                placeholder = "0.00",
                supportingText = if (isError) "请输入正确的金额" else supporting,
                isError = isError,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
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
    activeBg: Color = if (AppearanceConfigManager.isDarkThemeActive) ICostBlueAccent.copy(alpha = 0.22f) else Color(0xFFEAF2FF),
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

/**
 * 编辑账单时「账户」胶囊对应的钱包：收入、借入、退款入账把入账账户存在 targetWalletId，其余存在 sourceWalletId
 */
private fun editingAccountWalletId(tx: TransactionEntity): Long? = when (tx.type) {
    TransactionType.INCOME, TransactionType.LOAN_IN, TransactionType.REFUND_OFFSET ->
        tx.targetWalletId ?: tx.sourceWalletId
    else -> tx.sourceWalletId
}
