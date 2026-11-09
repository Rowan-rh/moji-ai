package com.moji.ai.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CategoryDef
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.TagCatalog
import com.moji.ai.data.model.TagDef
import com.moji.ai.data.model.TransactionType
import com.moji.ai.ui.QuickBookkeepingTemplate
import com.moji.ai.ui.QuickTemplateHelper
import com.moji.ai.ui.components.ICostConfirmDialog
import com.moji.ai.ui.components.sanitizeMoneyInput
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
import com.moji.ai.ui.theme.ICostFormCard
import com.moji.ai.ui.theme.ICostFormField
import com.moji.ai.ui.theme.ICostGreenIncome
import com.moji.ai.ui.theme.ICostSearchBg
import com.moji.ai.ui.theme.ICostSheetHeader
import com.moji.ai.ui.theme.ICostSmoothBottomSheet
import com.moji.ai.ui.theme.ICostTextMuted
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.formatMoneyCents
import com.moji.ai.ui.theme.iCostPressScale
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplateManagementScreen(
    templates: List<QuickBookkeepingTemplate>,
    wallets: List<WalletEntity>,
    expenseCategories: List<CategoryDef>,
    incomeCategories: List<CategoryDef>,
    tags: List<TagDef>,
    autoOpenCreateOnEnter: Boolean = false,
    onConsumeAutoOpenCreate: () -> Unit = {},
    onBack: () -> Unit,
    onApplyTemplateNow: (QuickBookkeepingTemplate) -> Unit,
    onFillCalculator: (QuickBookkeepingTemplate) -> Unit,
    onSaveTemplate: (QuickBookkeepingTemplate, onDone: (Result<QuickBookkeepingTemplate>) -> Unit) -> Unit,
    onDuplicateTemplate: (Long) -> Unit,
    onMoveTemplate: (index: Int, delta: Int) -> Unit,
    onDeleteTemplate: (Long) -> Unit,
    onClearAllTemplates: () -> Unit,
    onLoadPresetTemplates: () -> Unit
) {
    BackHandler(onBack = onBack)

    var selectedTypeFilter by rememberSaveable { mutableStateOf("ALL") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showCreateSheet by remember { mutableStateOf(false) }
    var editingTemplate by remember { mutableStateOf<QuickBookkeepingTemplate?>(null) }
    var deletingTemplate by remember { mutableStateOf<QuickBookkeepingTemplate?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(autoOpenCreateOnEnter) {
        if (autoOpenCreateOnEnter) {
            showCreateSheet = true
            onConsumeAutoOpenCreate()
        }
    }

    val walletMap = remember(wallets) { wallets.associateBy { it.id } }
    val defaultWallet = remember(wallets) {
        wallets.firstOrNull { !it.type.isLiability } ?: wallets.firstOrNull()
    }
    val trimmedQuery = searchQuery.trim()
    val filteredWithGlobalIndex = remember(templates, selectedTypeFilter, trimmedQuery) {
        templates.mapIndexedNotNull { globalIdx, tpl ->
            val typeMatch = when (selectedTypeFilter) {
                "EXPENSE" -> tpl.resolvedType() == TransactionType.EXPENSE
                "INCOME" -> tpl.resolvedType() == TransactionType.INCOME
                "TRANSFER" -> tpl.resolvedType() == TransactionType.TRANSFER
                else -> true
            }
            val queryMatch = trimmedQuery.isEmpty() ||
                tpl.title.contains(trimmedQuery, ignoreCase = true) ||
                tpl.category.contains(trimmedQuery, ignoreCase = true) ||
                tpl.note.contains(trimmedQuery, ignoreCase = true) ||
                tpl.tags.contains(trimmedQuery, ignoreCase = true)
            if (typeMatch && queryMatch) globalIdx to tpl else null
        }
    }

    TemplateManagementContent(
        templates = templates,
        filteredWithGlobalIndex = filteredWithGlobalIndex,
        walletMap = walletMap,
        defaultWallet = defaultWallet,
        selectedTypeFilter = selectedTypeFilter,
        onSelectTypeFilter = { selectedTypeFilter = it },
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        showMoreMenu = showMoreMenu,
        onShowMoreMenuChange = { showMoreMenu = it },
        onBack = onBack,
        onOpenCreate = { showCreateSheet = true },
        onLoadPresetTemplates = onLoadPresetTemplates,
        onRequestClearAll = { showClearAllConfirm = true },
        onApplyTemplateNow = onApplyTemplateNow,
        onFillCalculator = onFillCalculator,
        onMoveTemplate = onMoveTemplate,
        onDuplicateTemplate = onDuplicateTemplate,
        onEditTemplate = { editingTemplate = it },
        onRequestDelete = { deletingTemplate = it }
    )

    if (showCreateSheet || editingTemplate != null) {
        TemplateEditorBottomSheet(
            existingTemplate = editingTemplate,
            wallets = wallets,
            expenseCategories = expenseCategories,
            incomeCategories = incomeCategories,
            tags = tags,
            onDismiss = {
                showCreateSheet = false
                editingTemplate = null
            },
            onSave = { draft, requestDismiss ->
                onSaveTemplate(draft) { res ->
                    if (res.isSuccess) requestDismiss()
                }
            }
        )
    }

    deletingTemplate?.let { target ->
        ICostConfirmDialog(
            title = "删除记账模板",
            message = "确定删除模板「${target.title}」吗？删除模板不会影响已记入账本的历史流水。",
            confirmText = "删除",
            onConfirm = {
                onDeleteTemplate(target.id)
                deletingTemplate = null
            },
            onDismiss = { deletingTemplate = null }
        )
    }

    if (showClearAllConfirm) {
        ICostConfirmDialog(
            title = "清空全部模板",
            message = "确定清空当前全部 ${templates.size} 个记账模板吗？可随时重新加载常用预设模板。",
            confirmText = "确认清空",
            onConfirm = {
                showClearAllConfirm = false
                onClearAllTemplates()
            },
            onDismiss = { showClearAllConfirm = false }
        )
    }
}

@Composable
private fun TemplateManagementContent(
    templates: List<QuickBookkeepingTemplate>,
    filteredWithGlobalIndex: List<Pair<Int, QuickBookkeepingTemplate>>,
    walletMap: Map<Long, WalletEntity>,
    defaultWallet: WalletEntity?,
    selectedTypeFilter: String,
    onSelectTypeFilter: (String) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    showMoreMenu: Boolean,
    onShowMoreMenuChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenCreate: () -> Unit,
    onLoadPresetTemplates: () -> Unit,
    onRequestClearAll: () -> Unit,
    onApplyTemplateNow: (QuickBookkeepingTemplate) -> Unit,
    onFillCalculator: (QuickBookkeepingTemplate) -> Unit,
    onMoveTemplate: (index: Int, delta: Int) -> Unit,
    onDuplicateTemplate: (Long) -> Unit,
    onEditTemplate: (QuickBookkeepingTemplate) -> Unit,
    onRequestDelete: (QuickBookkeepingTemplate) -> Unit
) {
    val expCount = remember(templates) { templates.count { it.resolvedType() == TransactionType.EXPENSE } }
    val incCount = remember(templates) { templates.count { it.resolvedType() == TransactionType.INCOME } }
    val trfCount = remember(templates) { templates.count { it.resolvedType() == TransactionType.TRANSFER } }
    val filterTabs = remember(templates.size, expCount, incCount, trfCount) {
        listOf(
            "ALL" to "全部 (${templates.size})",
            "EXPENSE" to "支出 ($expCount)",
            "INCOME" to "收入 ($incCount)",
            "TRANSFER" to "转账 ($trfCount)"
        )
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
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ICostCircleIconButton(onClick = onBack, size = 42.dp) {
                ICostAssetImage(
                    assetFile = "record_back_icon.webp",
                    tint = ICostTextPrimary,
                    contentDescription = "返回",
                    modifier = Modifier.size(16.dp)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    ICostCircleIconButton(
                        onClick = { onShowMoreMenuChange(true) },
                        size = 40.dp
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreHoriz,
                            contentDescription = "更多操作",
                            tint = ICostTextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { onShowMoreMenuChange(false) },
                        containerColor = ICostCardWhite
                    ) {
                        DropdownMenuItem(
                            text = { Text("添加常用预设模板", fontSize = 14.sp) },
                            onClick = {
                                onShowMoreMenuChange(false)
                                onLoadPresetTemplates()
                            }
                        )
                        if (templates.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("清空全部模板", fontSize = 14.sp, color = ICostCoralRed) },
                                onClick = {
                                    onShowMoreMenuChange(false)
                                    onRequestClearAll()
                                }
                            )
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = ICostBlueAccent,
                    modifier = Modifier
                        .semantics { contentDescription = "新建模板" }
                        .iCostPressScale(pressedScale = 0.94f, onClick = onOpenCreate)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "新建模板",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = "记账模板",
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ICostTextPrimary
            )
            Text(
                text = "共 ${templates.size} 个模板 · 支持一键秒记与预填",
                fontSize = 12.sp,
                color = ICostTextSecondary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filterTabs, key = { it.first }) { (key, label) ->
                val isSelected = selectedTypeFilter == key
                val bg by animateColorAsState(
                    targetValue = if (isSelected) ICostBlueAccent else ICostCardWhite,
                    animationSpec = tween(180),
                    label = "TplFilterBg"
                )
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = bg,
                    modifier = Modifier.iCostPressScale(pressedScale = 0.94f) {
                        onSelectTypeFilter(key)
                    }
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.White else ICostTextPrimary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        TemplateSearchBar(searchQuery = searchQuery, onSearchQueryChange = onSearchQueryChange)
        Spacer(modifier = Modifier.height(12.dp))

        if (filteredWithGlobalIndex.isEmpty()) {
            TemplateEmptyCard(
                isFiltered = templates.isNotEmpty(),
                onOpenCreate = onOpenCreate,
                onLoadPresetTemplates = onLoadPresetTemplates
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 56.dp)
            ) {
                itemsIndexed(
                    items = filteredWithGlobalIndex,
                    key = { _, pair -> pair.second.id }
                ) { _, (globalIdx, tpl) ->
                    TemplateManagementCard(
                        tpl = tpl,
                        globalIndex = globalIdx,
                        totalSize = templates.size,
                        walletMap = walletMap,
                        defaultWallet = defaultWallet,
                        onApplyNow = { onApplyTemplateNow(tpl) },
                        onFillCalculator = { onFillCalculator(tpl) },
                        onMoveUp = { onMoveTemplate(globalIdx, -1) },
                        onMoveDown = { onMoveTemplate(globalIdx, 1) },
                        onDuplicate = { onDuplicateTemplate(tpl.id) },
                        onEdit = { onEditTemplate(tpl) },
                        onDelete = { onRequestDelete(tpl) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateSearchBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(ICostSearchBg)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = Color(0xFF9E9E9E),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (searchQuery.isEmpty()) {
                    Text(
                        text = "搜索模板标题、分类、标签或备注",
                        fontSize = 13.sp,
                        color = Color(0xFF9E9E9E),
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = ICostTextPrimary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (searchQuery.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "清除搜索",
                    tint = Color(0xFF9E9E9E),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onSearchQueryChange("") }
                )
            }
        }
    }
}

@Composable
private fun TemplateEmptyCard(
    isFiltered: Boolean,
    onOpenCreate: () -> Unit,
    onLoadPresetTemplates: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ICostCardWhite,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isFiltered) "未找到匹配的记账模板" else "还没有记账模板",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = ICostTextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isFiltered) {
                    "请尝试切换上方类型筛选或清除搜索关键词"
                } else {
                    "将高频收支或转账保存为模板，支持固定金额一键秒记与 0 元待填金额计算器预填"
                },
                fontSize = 12.sp,
                color = ICostTextSecondary
            )
            if (!isFiltered) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = ICostBlueAccent,
                        modifier = Modifier.iCostPressScale(pressedScale = 0.94f, onClick = onOpenCreate)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "新建模板",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFFFF3E0),
                        modifier = Modifier.iCostPressScale(pressedScale = 0.94f, onClick = onLoadPresetTemplates)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color(0xFFD97706),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "一键添加常用模板预设",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD97706)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateManagementCard(
    tpl: QuickBookkeepingTemplate,
    globalIndex: Int,
    totalSize: Int,
    walletMap: Map<Long, WalletEntity>,
    defaultWallet: WalletEntity?,
    onApplyNow: () -> Unit,
    onFillCalculator: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDuplicate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val resolvedType = tpl.resolvedType()
    val (typeLabel, typeColor) = when (resolvedType) {
        TransactionType.INCOME -> "收入" to ICostGreenIncome
        TransactionType.TRANSFER -> "转账" to ICostAmberGold
        else -> "支出" to ICostCoralRed
    }
    val categoryDisplay = if (resolvedType == TransactionType.TRANSFER) {
        "内部转账"
    } else {
        tpl.category.replace("-", " · ")
    }
    val accountLabel = when (resolvedType) {
        TransactionType.TRANSFER -> {
            val fromW = tpl.walletId?.let { walletMap[it] }
            val toW = tpl.targetWalletId?.let { walletMap[it] }
            if (fromW != null && toW != null && fromW.id != toW.id) {
                "${fromW.name} → ${toW.name}"
            } else {
                "待确认账户"
            }
        }
        else -> {
            val boundW = tpl.walletId?.let { walletMap[it] } ?: defaultWallet
            boundW?.name ?: "默认账户"
        }
    }
    val parsedTags = remember(tpl.tags) { TagCatalog.parseTags(tpl.tags) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ICostCardWhite,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp)
        ) {
            // 第 1 行（核心识别区）：左侧 ICostCategoryBadge(38dp) + 中间标题与类型胶囊 + 右侧预设金额/待填金额
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ICostCategoryBadge(
                    category = if (resolvedType == TransactionType.TRANSFER) "内部转账" else tpl.category,
                    type = resolvedType,
                    size = 38.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = tpl.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = typeColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = typeLabel,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = typeColor,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (tpl.amountCents > 0L) {
                    val prefix = when (resolvedType) {
                        TransactionType.INCOME -> "+"
                        TransactionType.TRANSFER -> "⇄ "
                        else -> "-"
                    }
                    Text(
                        text = "$prefix${formatMoneyCents(tpl.amountCents, tpl.currency)}",
                        fontFamily = ICDinFontFamily,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = typeColor,
                        maxLines = 1,
                        softWrap = false
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFFFF3E0)
                    ) {
                        Text(
                            text = "待填金额",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD97706),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 第 2 行（分类、账户与属性区，使用 FlowRow 自适应换行，绝不单行挤压折行）
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TemplateMetaChip(text = categoryDisplay, bg = ICostCellGray, color = ICostTextSecondary)
                TemplateMetaChip(
                    text = accountLabel,
                    bg = if (accountLabel == "待确认账户") ICostCoralRed.copy(alpha = 0.12f) else ICostCellGray,
                    color = if (accountLabel == "待确认账户") ICostCoralRed else ICostTextSecondary
                )
                if (resolvedType == TransactionType.EXPENSE && tpl.reimbursable) {
                    TemplateMetaChip(
                        text = "待报销",
                        bg = ICostBlueAccent.copy(alpha = 0.12f),
                        color = ICostBlueAccent
                    )
                }
                parsedTags.forEach { tagName ->
                    ICostColorTagChip(
                        tagName = tagName,
                        fontSize = 10.sp,
                        horizontalPadding = 7.dp,
                        verticalPadding = 2.dp
                    )
                }
                if (tpl.note.isNotBlank() && tpl.note != tpl.title) {
                    TemplateMetaChip(
                        text = "备注：${tpl.note}",
                        bg = ICostCellGray,
                        color = ICostTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(thickness = 0.5.dp, color = ICostDivider)
            Spacer(modifier = Modifier.height(8.dp))

            // 第 3 行（独立操作栏）：左侧「记一笔」「填入计算器」；右侧 上移 / 下移 / 复制 / 编辑 / 删除
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ICostBlueAccent,
                        modifier = Modifier.iCostPressScale(pressedScale = 0.93f, onClick = onApplyNow)
                    ) {
                        Text(
                            text = if (tpl.amountCents > 0L) "记一笔" else "输入金额记账",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ICostCellGray,
                        modifier = Modifier.iCostPressScale(pressedScale = 0.93f, onClick = onFillCalculator)
                    ) {
                        Text(
                            text = "填入计算器",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ICostTextPrimary,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TemplateSmallIconBtn(
                        icon = Icons.Default.KeyboardArrowUp,
                        contentDescription = "上移${tpl.title}",
                        enabled = globalIndex > 0,
                        onClick = onMoveUp
                    )
                    TemplateSmallIconBtn(
                        icon = Icons.Default.KeyboardArrowDown,
                        contentDescription = "下移${tpl.title}",
                        enabled = globalIndex < totalSize - 1,
                        onClick = onMoveDown
                    )
                    TemplateSmallIconBtn(
                        icon = Icons.Default.ContentCopy,
                        contentDescription = "复制${tpl.title}",
                        enabled = true,
                        tint = ICostAmberGold,
                        onClick = onDuplicate
                    )
                    TemplateSmallIconBtn(
                        icon = Icons.Default.Edit,
                        contentDescription = "编辑${tpl.title}",
                        enabled = true,
                        tint = ICostBlueAccent,
                        onClick = onEdit
                    )
                    TemplateSmallIconBtn(
                        icon = Icons.Default.DeleteOutline,
                        contentDescription = "删除${tpl.title}",
                        enabled = true,
                        tint = ICostCoralRed,
                        onClick = onDelete
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateMetaChip(
    text: String,
    bg: Color,
    color: Color
) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = bg
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun TemplateSmallIconBtn(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    tint: Color = ICostTextSecondary,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .let { mod ->
                if (enabled) mod.iCostPressScale(pressedScale = 0.86f, onClick = onClick) else mod
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) tint else ICostTextMuted.copy(alpha = 0.35f),
            modifier = Modifier.size(17.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplateEditorBottomSheet(
    existingTemplate: QuickBookkeepingTemplate?,
    wallets: List<WalletEntity>,
    expenseCategories: List<CategoryDef>,
    incomeCategories: List<CategoryDef>,
    tags: List<TagDef>,
    onDismiss: () -> Unit,
    onSave: (draft: QuickBookkeepingTemplate, requestDismiss: () -> Unit) -> Unit
) {
    val isEditing = existingTemplate != null
    var titleInput by remember(existingTemplate) {
        mutableStateOf(existingTemplate?.title.orEmpty())
    }
    var txType by remember(existingTemplate) {
        mutableStateOf(existingTemplate?.resolvedType() ?: TransactionType.EXPENSE)
    }
    var amountInput by remember(existingTemplate) {
        mutableStateOf(
            if (existingTemplate != null && existingTemplate.amountCents > 0L) {
                String.format(Locale.US, "%.2f", existingTemplate.amountCents / 100.0)
                    .trimEnd('0')
                    .trimEnd('.')
            } else {
                ""
            }
        )
    }
    val initCatParts = remember(existingTemplate) {
        (existingTemplate?.category ?: "餐饮").split("-")
    }
    var selectedMainCat by remember(existingTemplate) {
        mutableStateOf(initCatParts.firstOrNull() ?: "餐饮")
    }
    var selectedSubCat by remember(existingTemplate) {
        mutableStateOf(initCatParts.getOrNull(1).orEmpty())
    }
    val defaultWalletId = remember(wallets) {
        (wallets.firstOrNull { !it.type.isLiability } ?: wallets.firstOrNull())?.id
    }
    var sourceWalletId by remember(existingTemplate) {
        mutableStateOf(
            if (existingTemplate != null) {
                wallets.firstOrNull { it.id == existingTemplate.walletId }?.id ?: defaultWalletId
            } else {
                defaultWalletId
            }
        )
    }
    var targetWalletId by remember(existingTemplate) {
        mutableStateOf(
            if (existingTemplate != null && existingTemplate.resolvedType() == TransactionType.TRANSFER) {
                wallets.firstOrNull { it.id == existingTemplate.targetWalletId }?.id
            } else {
                wallets.firstOrNull { it.id != sourceWalletId }?.id
            }
        )
    }
    var selectedTags by remember(existingTemplate) {
        mutableStateOf(TagCatalog.parseTags(existingTemplate?.tags.orEmpty()))
    }
    var noteInput by remember(existingTemplate) {
        mutableStateOf(existingTemplate?.note.orEmpty())
    }
    var reimbursable by remember(existingTemplate) {
        mutableStateOf(existingTemplate?.reimbursable ?: false)
    }

    LaunchedEffect(txType, expenseCategories, incomeCategories) {
        if (txType == TransactionType.EXPENSE || txType == TransactionType.INCOME) {
            val activeList = if (txType == TransactionType.EXPENSE) expenseCategories else incomeCategories
            val matched = activeList.firstOrNull { it.name == selectedMainCat }
            if (matched == null) {
                selectedMainCat = activeList.firstOrNull()?.name ?: if (txType == TransactionType.EXPENSE) "餐饮" else "工资"
                selectedSubCat = ""
            } else if (selectedSubCat.isNotBlank() && matched.subCategories.none { it.name == selectedSubCat }) {
                selectedSubCat = ""
            }
        }
    }

    val validationResult = remember(titleInput, txType, amountInput, sourceWalletId, targetWalletId) {
        QuickTemplateHelper.validateTemplateInput(
            title = titleInput,
            type = txType,
            amountInput = amountInput,
            walletId = sourceWalletId,
            targetWalletId = targetWalletId
        )
    }
    val errorText = validationResult.exceptionOrNull()?.message

    TemplateEditorSheetBody(
        isEditing = isEditing,
        existingId = existingTemplate?.id ?: 0L,
        existingCurrency = existingTemplate?.currency ?: "CNY",
        titleInput = titleInput,
        onTitleChange = { titleInput = it },
        txType = txType,
        onTypeChange = { txType = it },
        amountInput = amountInput,
        onAmountChange = { amountInput = sanitizeMoneyInput(it) },
        selectedMainCat = selectedMainCat,
        selectedSubCat = selectedSubCat,
        onSelectCategory = { main, sub ->
            selectedMainCat = main
            selectedSubCat = sub
        },
        wallets = wallets,
        sourceWalletId = sourceWalletId,
        onSelectSourceWallet = { sourceWalletId = it },
        targetWalletId = targetWalletId,
        onSelectTargetWallet = { targetWalletId = it },
        expenseCategories = expenseCategories,
        incomeCategories = incomeCategories,
        tags = tags,
        selectedTags = selectedTags,
        onToggleTag = { tagName ->
            selectedTags = if (tagName in selectedTags) {
                selectedTags.filterNot { it == tagName }
            } else {
                selectedTags + tagName
            }
        },
        noteInput = noteInput,
        onNoteChange = { noteInput = it.take(60) },
        reimbursable = reimbursable,
        onReimbursableChange = { reimbursable = it },
        validationCents = validationResult.getOrNull(),
        errorText = errorText,
        onDismiss = onDismiss,
        onSave = onSave
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateEditorSheetBody(
    isEditing: Boolean,
    existingId: Long,
    existingCurrency: String,
    titleInput: String,
    onTitleChange: (String) -> Unit,
    txType: TransactionType,
    onTypeChange: (TransactionType) -> Unit,
    amountInput: String,
    onAmountChange: (String) -> Unit,
    selectedMainCat: String,
    selectedSubCat: String,
    onSelectCategory: (String, String) -> Unit,
    wallets: List<WalletEntity>,
    sourceWalletId: Long?,
    onSelectSourceWallet: (Long?) -> Unit,
    targetWalletId: Long?,
    onSelectTargetWallet: (Long?) -> Unit,
    expenseCategories: List<CategoryDef>,
    incomeCategories: List<CategoryDef>,
    tags: List<TagDef>,
    selectedTags: List<String>,
    onToggleTag: (String) -> Unit,
    noteInput: String,
    onNoteChange: (String) -> Unit,
    reimbursable: Boolean,
    onReimbursableChange: (Boolean) -> Unit,
    validationCents: Long?,
    errorText: String?,
    onDismiss: () -> Unit,
    onSave: (draft: QuickBookkeepingTemplate, requestDismiss: () -> Unit) -> Unit
) {
    val activeCategories = if (txType == TransactionType.EXPENSE) expenseCategories else incomeCategories
    val currentMainDef = activeCategories.firstOrNull { it.name == selectedMainCat }

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
                title = if (isEditing) "编辑记账模板" else "新建记账模板",
                subtitle = "支持固定金额一键秒记，或填 0/留空作为每次输入金额的动态模板",
                onClose = requestDismiss
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ICostFormCard {
                    Text("收支类型", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ICostTextSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val modes = listOf(
                            TransactionType.EXPENSE to "支出",
                            TransactionType.INCOME to "收入",
                            TransactionType.TRANSFER to "转账"
                        )
                        modes.forEach { (mode, label) ->
                            val selected = txType == mode
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (selected) ICostBlueAccent else ICostCellGray,
                                modifier = Modifier
                                    .weight(1f)
                                    .iCostPressScale(pressedScale = 0.94f) { onTypeChange(mode) }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 9.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) Color.White else ICostTextPrimary
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    ICostFormField(
                        value = titleInput,
                        onValueChange = onTitleChange,
                        label = "模板名称（1~20 字）",
                        placeholder = "如：早餐、通勤地铁、每月房租",
                        singleLine = true,
                        isError = titleInput.isNotEmpty() && titleInput.trim().length > QuickTemplateHelper.MAX_TEMPLATE_TITLE_LENGTH,
                        supportingText = "${titleInput.trim().length}/${QuickTemplateHelper.MAX_TEMPLATE_TITLE_LENGTH}",
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    ICostFormField(
                        value = amountInput,
                        onValueChange = onAmountChange,
                        label = "预设金额（元，留空或填 0 代表每次手动输入金额）",
                        placeholder = "0.00（待填金额动态模板）",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    ICostFormField(
                        value = noteInput,
                        onValueChange = onNoteChange,
                        label = "默认备注（可选）",
                        placeholder = "留空则默认使用模板名称",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (txType == TransactionType.EXPENSE || txType == TransactionType.INCOME) {
                    ICostFormCard {
                        Text("关联分类", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ICostTextSecondary)
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            activeCategories.forEach { cat ->
                                val selected = selectedMainCat == cat.name
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selected) ICostBlueAccent else ICostCellGray,
                                    modifier = Modifier.iCostPressScale(pressedScale = 0.94f) {
                                        onSelectCategory(cat.name, "")
                                    }
                                ) {
                                    Text(
                                        text = cat.name,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) Color.White else ICostTextPrimary,
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                        if (currentMainDef != null && currentMainDef.subCategories.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("二级分类（可选）", fontSize = 11.sp, color = ICostTextSecondary)
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                currentMainDef.subCategories.forEach { sub ->
                                    val subSelected = selectedSubCat == sub.name
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (subSelected) ICostBlueAccent.copy(alpha = 0.14f) else ICostCellGray,
                                        modifier = Modifier.iCostPressScale(pressedScale = 0.94f) {
                                            onSelectCategory(selectedMainCat, if (subSelected) "" else sub.name)
                                        }
                                    ) {
                                        Text(
                                            text = sub.name,
                                            fontSize = 11.sp,
                                            fontWeight = if (subSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (subSelected) ICostBlueAccent else ICostTextPrimary,
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                TemplateEditorWalletAndTagsSection(
                    txType = txType,
                    wallets = wallets,
                    sourceWalletId = sourceWalletId,
                    onSelectSourceWallet = onSelectSourceWallet,
                    targetWalletId = targetWalletId,
                    onSelectTargetWallet = onSelectTargetWallet,
                    tags = tags,
                    selectedTags = selectedTags,
                    onToggleTag = onToggleTag,
                    reimbursable = reimbursable,
                    onReimbursableChange = onReimbursableChange
                )

                if (!errorText.isNullOrBlank()) {
                    Text(
                        text = errorText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostCoralRed,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                Button(
                    enabled = validationCents != null,
                    onClick = {
                        val cents = validationCents ?: return@Button
                        val fullCategory = when (txType) {
                            TransactionType.TRANSFER -> "内部转账"
                            else -> if (selectedSubCat.isNotBlank()) "$selectedMainCat-$selectedSubCat" else selectedMainCat
                        }
                        val draft = QuickBookkeepingTemplate(
                            id = existingId,
                            title = titleInput.trim(),
                            typeName = txType.name,
                            category = fullCategory,
                            amountCents = cents,
                            currency = existingCurrency,
                            walletId = sourceWalletId,
                            targetWalletId = if (txType == TransactionType.TRANSFER) targetWalletId else null,
                            note = noteInput.trim().ifBlank { titleInput.trim() },
                            tags = TagCatalog.joinTags(selectedTags),
                            reimbursable = txType == TransactionType.EXPENSE && reimbursable
                        )
                        onSave(draft, requestDismiss)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .height(48.dp)
                ) {
                    Text(
                        text = if (isEditing) "保存修改" else "创建模板",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateEditorWalletAndTagsSection(
    txType: TransactionType,
    wallets: List<WalletEntity>,
    sourceWalletId: Long?,
    onSelectSourceWallet: (Long?) -> Unit,
    targetWalletId: Long?,
    onSelectTargetWallet: (Long?) -> Unit,
    tags: List<TagDef>,
    selectedTags: List<String>,
    onToggleTag: (String) -> Unit,
    reimbursable: Boolean,
    onReimbursableChange: (Boolean) -> Unit
) {
    ICostFormCard {
        Text(
            text = if (txType == TransactionType.TRANSFER) "转出账户" else "关联资金账户",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = ICostTextSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            wallets.forEach { w ->
                val selected = sourceWalletId == w.id
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) ICostBlueAccent else ICostCellGray,
                    modifier = Modifier.iCostPressScale(pressedScale = 0.94f) {
                        onSelectSourceWallet(w.id)
                    }
                ) {
                    Text(
                        text = w.name,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) Color.White else ICostTextPrimary,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                    )
                }
            }
        }

        if (txType == TransactionType.TRANSFER) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "转入账户（不可与转出账户相同）",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = ICostTextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                wallets.forEach { w ->
                    val selected = targetWalletId == w.id
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) ICostAmberGold else ICostCellGray,
                        modifier = Modifier.iCostPressScale(pressedScale = 0.94f) {
                            onSelectTargetWallet(w.id)
                        }
                    ) {
                        Text(
                            text = w.name,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) Color.White else ICostTextPrimary,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        if (tags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "默认标签（可多选）",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = ICostTextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tags.forEach { tagDef ->
                    val isSelected = tagDef.name in selectedTags
                    val baseColor = Color(tagDef.colorHex)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) baseColor else baseColor.copy(alpha = 0.12f),
                        modifier = Modifier.iCostPressScale(pressedScale = 0.93f) {
                            onToggleTag(tagDef.name)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                            }
                            Text(
                                text = "#${tagDef.name}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.White else baseColor
                            )
                        }
                    }
                }
            }
        }

        if (txType == TransactionType.EXPENSE) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("默认标记待报销", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ICostTextPrimary)
                    Text("记账时自动开启待报销，进入报销核销中心", fontSize = 11.sp, color = ICostTextSecondary)
                }
                Switch(
                    checked = reimbursable,
                    onCheckedChange = onReimbursableChange,
                    colors = SwitchDefaults.colors(checkedTrackColor = ICostBlueAccent)
                )
            }
        }
    }
}
