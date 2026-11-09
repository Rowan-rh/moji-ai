package com.moji.ai.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.TagCatalog
import com.moji.ai.data.model.TagDef
import com.moji.ai.data.model.TransactionType
import com.moji.ai.ui.components.ICostConfirmDialog
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
import com.moji.ai.ui.theme.ICostSheetHeader
import com.moji.ai.ui.theme.ICostSmoothBottomSheet
import com.moji.ai.ui.theme.ICostTextMuted
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.formatMoneyCents
import com.moji.ai.ui.theme.formatWalletDisplayName
import com.moji.ai.ui.theme.iCostPressScale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class TagStatsSummary(
    val transactions: List<TransactionEntity>,
    val netExpenseCents: Long,
    val totalIncomeCents: Long
)

/**
 * 通用 iCost 莫奈柔和色 `#标签` 胶囊组件（供首页流水行、详情页、计算器与标签管理页共用）
 */
@Composable
fun ICostColorTagChip(
    tagName: String,
    colorHex: Long = TagCatalog.resolveTagColorHex(tagName),
    fontSize: TextUnit = 10.sp,
    horizontalPadding: androidx.compose.ui.unit.Dp = 7.dp,
    verticalPadding: androidx.compose.ui.unit.Dp = 2.dp,
    showDot: Boolean = false,
    modifier: Modifier = Modifier
) {
    val baseColor = Color(colorHex)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = baseColor.copy(alpha = 0.13f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showDot) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(baseColor)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = "#$tagName",
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                color = baseColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 1:1 iCost 全屏「标签管理」二级页面（对齐 setting_tag.webp）：
 *
 * - 顶栏（返回 + 恢复默认菜单 + 新增标签按钮）、大标题「标签管理」、分组筛选胶囊（全部 / 日常 / 差旅报销 / 生活事件 / 自定义）与统计概览；
 * - 标签列表卡片展示莫奈色点、`#标签名` 彩色胶囊、分组、关联有效账单笔数、累计净支出与总收入，以及 上移 / 下移 / 合并 / 编辑 / 删除 操作按钮；
 * - 点击任意标签卡片平滑展开该标签下的关联账单明细列表（按时间倒序），点击单笔明细可直接打开 `TransactionDetailSheet`；
 * - 包含 `TagEditorBottomSheet`（实时反例校验 + 10 色莫奈色卡 + 分组选择）、`TagMergeDialog`、`TagSafeDeleteDialog`。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagManagementScreen(
    tags: List<TagDef>,
    transactions: List<TransactionEntity>,
    wallets: List<WalletEntity>,
    onBack: () -> Unit,
    onAddTag: (name: String, colorHex: Long, groupName: String, onDone: (Result<TagDef>) -> Unit) -> Unit,
    onUpdateTag: (oldName: String, newName: String, colorHex: Long, groupName: String, onDone: (Result<TagDef>) -> Unit) -> Unit,
    onMoveTag: (index: Int, delta: Int) -> Unit,
    onMergeTag: (sourceName: String, targetName: String, onDone: (Result<TagDef>) -> Unit) -> Unit,
    onDeleteTag: (tagName: String, cascadeRemoveFromHistory: Boolean, replacementTagName: String?, onDone: (Result<Unit>) -> Unit) -> Unit,
    onRestoreDefaults: (mergeOnly: Boolean) -> Unit,
    onCountTagTransactions: suspend (tagName: String) -> Int,
    onInspectTransaction: (TransactionEntity) -> Unit
) {
    BackHandler(onBack = onBack)

    val groupTabs = remember { listOf("全部") + TagCatalog.presetGroups }
    var selectedGroup by rememberSaveable { mutableStateOf("全部") }
    var expandedTagName by rememberSaveable { mutableStateOf<String?>(null) }

    var editingTarget by remember { mutableStateOf<TagDef?>(null) }
    var showCreateSheet by remember { mutableStateOf(false) }
    var mergingSourceTag by remember { mutableStateOf<TagDef?>(null) }
    var deletingTargetTag by remember { mutableStateOf<TagDef?>(null) }
    var showRestoreMenu by remember { mutableStateOf(false) }
    var showFullResetConfirm by remember { mutableStateOf(false) }

    val walletMap = remember(wallets) { wallets.associateBy { it.id } }

    // 统计每个标签关联的有效账单明细（排除系统平账流水，按时间倒序）
    val validTransactions = remember(transactions) {
        transactions
            .filterNot { it.isSystemBalanceAnchor() }
            .sortedByDescending { it.timestamp }
    }

    val statsByTag = remember(tags, validTransactions) {
        val map = mutableMapOf<String, MutableList<TransactionEntity>>()
        for (tx in validTransactions) {
            for (token in TagCatalog.parseTags(tx.tags)) {
                map.getOrPut(token) { mutableListOf() }.add(tx)
            }
        }
        map.mapValues { (_, list) ->
            val netExp = list
                .filter { it.type == TransactionType.EXPENSE }
                .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }
            val totalInc = list
                .filter { it.type == TransactionType.INCOME }
                .sumOf { it.amountCnyCents }
            TagStatsSummary(
                transactions = list,
                netExpenseCents = netExp,
                totalIncomeCents = totalInc
            )
        }
    }

    val filteredTagsWithGlobalIndex = remember(tags, selectedGroup) {
        tags.mapIndexedNotNull { globalIdx, tag ->
            if (selectedGroup == "全部" || tag.groupName == selectedGroup) {
                globalIdx to tag
            } else {
                null
            }
        }
    }

    val taggedTxTotalCount = remember(validTransactions) {
        validTransactions.count { TagCatalog.parseTags(it.tags).isNotEmpty() }
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

        // 顶部栏：返回按钮 + 恢复默认菜单 + 新增标签按钮
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
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = ICostCardWhite,
                        modifier = Modifier.iCostPressScale(pressedScale = 0.94f) {
                            showRestoreMenu = true
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Restore,
                                contentDescription = null,
                                tint = ICostTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "恢复默认",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = ICostTextPrimary
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = showRestoreMenu,
                        onDismissRequest = { showRestoreMenu = false },
                        containerColor = ICostCardWhite
                    ) {
                        DropdownMenuItem(
                            text = { Text("补全缺失的默认标签（保留自定义）", fontSize = 14.sp) },
                            onClick = {
                                showRestoreMenu = false
                                onRestoreDefaults(true)
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "重置为出厂默认标签列表",
                                    fontSize = 14.sp,
                                    color = ICostCoralRed
                                )
                            },
                            onClick = {
                                showRestoreMenu = false
                                showFullResetConfirm = true
                            }
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = ICostBlueAccent,
                    modifier = Modifier
                        .semantics { contentDescription = "新增标签" }
                        .iCostPressScale(pressedScale = 0.94f) {
                            showCreateSheet = true
                        }
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
                            text = "新增标签",
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = "标签管理",
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ICostTextPrimary
            )
            Text(
                text = "共 ${tags.size} 个标签 · 关联 $taggedTxTotalCount 笔账单",
                fontSize = 12.sp,
                color = ICostTextSecondary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 分组筛选胶囊栏（全部 / 日常 / 差旅报销 / 生活事件 / 自定义）
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(groupTabs) { groupName ->
                val isSelected = selectedGroup == groupName
                val countInGroup = if (groupName == "全部") {
                    tags.size
                } else {
                    tags.count { it.groupName == groupName }
                }
                val bg by animateColorAsState(
                    targetValue = if (isSelected) ICostBlueAccent else ICostCardWhite,
                    animationSpec = tween(180),
                    label = "TagGroupTabBg"
                )
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = bg,
                    modifier = Modifier.iCostPressScale(pressedScale = 0.94f) {
                        selectedGroup = groupName
                    }
                ) {
                    Text(
                        text = "$groupName ($countInGroup)",
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.White else ICostTextPrimary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (filteredTagsWithGlobalIndex.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = ICostCardWhite,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp, horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (selectedGroup == "全部") "暂无标签" else "「$selectedGroup」分组下暂无标签",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "点击右上角「新增标签」创建莫奈彩色标签，用于跨分类归集出差、聚餐、分期等场景账单",
                        fontSize = 12.sp,
                        color = ICostTextSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 56.dp)
            ) {
                itemsIndexed(
                    items = filteredTagsWithGlobalIndex,
                    key = { _, pair -> pair.second.name }
                ) { _, (globalIndex, tag) ->
                    val summary = statsByTag[tag.name]
                    val txCount = summary?.transactions?.size ?: 0
                    val netExpenseCents = summary?.netExpenseCents ?: 0L
                    val totalIncomeCents = summary?.totalIncomeCents ?: 0L
                    val isExpanded = expandedTagName == tag.name
                    val tagColor = Color(tag.colorHex)
                    val canMerge = tags.size > 1

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = ICostCardWhite,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedTagName = if (isExpanded) null else tag.name
                                }
                                .padding(horizontal = 14.dp, vertical = 13.dp)
                        ) {
                            // 第一行：莫奈色圆点徽标 + #标签名胶囊 + 所属分组胶囊（预留充裕水平空间，严防分组胶囊折行或被挤压）
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(tagColor.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(tagColor)
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ICostColorTagChip(
                                        tagName = tag.name,
                                        colorHex = tag.colorHex,
                                        fontSize = 13.sp,
                                        horizontalPadding = 9.dp,
                                        verticalPadding = 3.dp,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = ICostCellGray
                                    ) {
                                        Text(
                                            text = tag.groupName,
                                            fontSize = 11.sp,
                                            color = ICostTextSecondary,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // 第二行：左侧关联账单收支统计 + 右侧 5 个快捷操作按钮
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = buildString {
                                        append("${txCount} 笔账单")
                                        if (netExpenseCents > 0L) {
                                            append(" · 支出 ${formatMoneyCents(netExpenseCents)}")
                                        }
                                        if (totalIncomeCents > 0L) {
                                            append(" · 收入 ${formatMoneyCents(totalIncomeCents)}")
                                        }
                                        if (txCount > 0) {
                                            append(if (isExpanded) " · 收起明细" else " · 展开明细")
                                        }
                                    },
                                    fontSize = 11.sp,
                                    color = ICostTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 6.dp)
                                )

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    // 上移
                                    TagSmallActionBtn(
                                        icon = Icons.Default.KeyboardArrowUp,
                                        contentDescription = "上移${tag.name}",
                                        enabled = globalIndex > 0,
                                        onClick = { onMoveTag(globalIndex, -1) }
                                    )
                                    // 下移
                                    TagSmallActionBtn(
                                        icon = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "下移${tag.name}",
                                        enabled = globalIndex < tags.lastIndex,
                                        onClick = { onMoveTag(globalIndex, 1) }
                                    )
                                    // 合并标签
                                    TagSmallActionBtn(
                                        icon = Icons.AutoMirrored.Filled.CallMerge,
                                        contentDescription = "合并${tag.name}",
                                        enabled = canMerge,
                                        tint = ICostAmberGold,
                                        onClick = {
                                            if (canMerge) {
                                                mergingSourceTag = tag
                                            }
                                        }
                                    )
                                    // 编辑标签
                                    TagSmallActionBtn(
                                        icon = Icons.Default.Edit,
                                        contentDescription = "编辑${tag.name}",
                                        enabled = true,
                                        tint = ICostBlueAccent,
                                        onClick = { editingTarget = tag }
                                    )
                                    // 删除标签
                                    TagSmallActionBtn(
                                        icon = Icons.Default.DeleteOutline,
                                        contentDescription = "删除${tag.name}",
                                        enabled = true,
                                        tint = ICostCoralRed,
                                        onClick = { deletingTargetTag = tag }
                                    )
                                }
                            }

                            // 点击展开该标签下的关联账单明细列表（按时间倒序）
                            AnimatedVisibility(
                                visible = isExpanded,
                                enter = expandVertically(
                                    animationSpec = spring(dampingRatio = 0.92f, stiffness = Spring.StiffnessMedium)
                                ) + fadeIn(tween(150)),
                                exit = shrinkVertically(tween(150)) + fadeOut(tween(110))
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    HorizontalDivider(thickness = 0.5.dp, color = ICostDivider)
                                    Spacer(modifier = Modifier.height(8.dp))

                                    val txs = summary?.transactions.orEmpty()
                                    if (txs.isEmpty()) {
                                        Text(
                                            text = "该标签下暂无关联账单明细",
                                            fontSize = 12.sp,
                                            color = ICostTextSecondary,
                                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                                        )
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            txs.forEach { tx ->
                                                TagTransactionDrilldownRow(
                                                    tx = tx,
                                                    walletMap = walletMap,
                                                    onClick = { onInspectTransaction(tx) }
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
        }
    }

    // 新增标签抽屉
    if (showCreateSheet) {
        TagEditorBottomSheet(
            existingTag = null,
            existingTagNames = tags.map { it.name },
            initialGroup = if (selectedGroup != "全部") selectedGroup else "日常",
            onDismiss = { showCreateSheet = false },
            onSave = { name, colorHex, groupName, requestDismiss ->
                onAddTag(name, colorHex, groupName) { res ->
                    if (res.isSuccess) requestDismiss()
                }
            }
        )
    }

    // 编辑标签抽屉
    editingTarget?.let { target ->
        TagEditorBottomSheet(
            existingTag = target,
            existingTagNames = tags.map { it.name },
            initialGroup = target.groupName,
            onDismiss = { editingTarget = null },
            onSave = { newName, colorHex, groupName, requestDismiss ->
                onUpdateTag(target.name, newName, colorHex, groupName) { res ->
                    if (res.isSuccess) {
                        if (expandedTagName == target.name) {
                            expandedTagName = newName
                        }
                        requestDismiss()
                    }
                }
            }
        )
    }

    // 合并标签弹窗（将标签 A 合并入标签 B）
    mergingSourceTag?.let { sourceTag ->
        TagMergeDialog(
            sourceTag = sourceTag,
            initialTxCount = statsByTag[sourceTag.name]?.transactions?.size ?: 0,
            candidateTargets = tags.filter { it.name != sourceTag.name },
            onCountTransactions = onCountTagTransactions,
            onDismiss = { mergingSourceTag = null },
            onConfirmMerge = { targetTagName ->
                onMergeTag(sourceTag.name, targetTagName) { res ->
                    if (res.isSuccess) {
                        if (expandedTagName == sourceTag.name) {
                            expandedTagName = targetTagName
                        }
                        mergingSourceTag = null
                    }
                }
            }
        )
    }

    // 安全删除标签保护弹窗
    deletingTargetTag?.let { target ->
        TagSafeDeleteDialog(
            targetTag = target,
            initialTxCount = statsByTag[target.name]?.transactions?.size ?: 0,
            otherTags = tags.filter { it.name != target.name },
            onCountTransactions = onCountTagTransactions,
            onDismiss = { deletingTargetTag = null },
            onConfirmDelete = { cascadeRemoveFromHistory, replacementTagName ->
                onDeleteTag(target.name, cascadeRemoveFromHistory, replacementTagName) {
                    if (expandedTagName == target.name) {
                        expandedTagName = null
                    }
                    deletingTargetTag = null
                }
            }
        )
    }

    if (showFullResetConfirm) {
        ICostConfirmDialog(
            title = "重置为出厂默认标签？",
            message = "重置将恢复初始预设标签列表，已录入的历史账单不会被删除。若只想补回误删的预设标签并保留自定义标签，请使用「补全缺失的默认标签」。",
            confirmText = "确认重置",
            onConfirm = {
                showFullResetConfirm = false
                onRestoreDefaults(false)
            },
            onDismiss = { showFullResetConfirm = false }
        )
    }
}

@Composable
internal fun TagTransactionDrilldownRow(
    tx: TransactionEntity,
    walletMap: Map<Long, WalletEntity>,
    hideAmounts: Boolean = false,
    onClick: () -> Unit
) {
    val dateStr = remember(tx.timestamp) {
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(tx.timestamp))
    }
    val walletName = when (tx.type) {
        TransactionType.INCOME, TransactionType.REFUND_OFFSET ->
            formatWalletDisplayName(tx.targetWalletId?.let { walletMap[it] } ?: tx.sourceWalletId?.let { walletMap[it] })
        else -> formatWalletDisplayName(tx.sourceWalletId?.let { walletMap[it] })
    }
    val netExp = (tx.amountCnyCents - tx.refundedAmountCents).coerceAtLeast(0L)
    val amountText = if (hideAmounts) {
        formatMoneyCents(netExp, "CNY", true)
    } else {
        when (tx.type) {
            TransactionType.EXPENSE -> "-${formatMoneyCents(netExp)}"
            TransactionType.INCOME -> "+${formatMoneyCents(tx.amountCnyCents)}"
            else -> formatMoneyCents(tx.amountCnyCents)
        }
    }
    val amountColor = when (tx.type) {
        TransactionType.EXPENSE -> ICostCoralRed
        TransactionType.INCOME -> ICostGreenIncome
        else -> ICostTextPrimary
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = ICostCellGray,
        modifier = Modifier
            .fillMaxWidth()
            .iCostPressScale(pressedScale = 0.98f, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ICostCategoryBadge(
                category = tx.category,
                type = tx.type,
                size = 32.dp
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tx.merchant.ifBlank { tx.note.ifBlank { tx.category } },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = ICostTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$dateStr · ${tx.category} · $walletName",
                    fontSize = 11.sp,
                    color = ICostTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = amountText,
                fontFamily = ICDinFontFamily,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = amountColor
            )
        }
    }
}

@Composable
private fun TagSmallActionBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    tint: Color = ICostTextSecondary,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .then(
                if (enabled) Modifier.clickable(onClick = onClick) else Modifier
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) tint else ICostTextMuted.copy(alpha = 0.4f),
            modifier = Modifier.size(17.dp)
        )
    }
}

/**
 * 新增 / 编辑标签抽屉（包含实时表单反例校验、10 种 iCost 莫奈柔和色卡与分组选择）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditorBottomSheet(
    existingTag: TagDef?,
    existingTagNames: List<String>,
    initialGroup: String = "日常",
    onDismiss: () -> Unit,
    onSave: (name: String, colorHex: Long, groupName: String, requestDismiss: () -> Unit) -> Unit
) {
    var nameInput by remember(existingTag) { mutableStateOf(existingTag?.name.orEmpty()) }
    var selectedColorHex by remember(existingTag) {
        mutableLongStateOf(existingTag?.colorHex ?: TagCatalog.presetColors.first().colorHex)
    }
    var selectedGroup by remember(existingTag, initialGroup) {
        mutableStateOf(existingTag?.groupName ?: initialGroup.ifBlank { "日常" })
    }
    var nameTouched by remember(existingTag) { mutableStateOf(false) }
    var isSaving by remember(existingTag) { mutableStateOf(false) }

    val validationError = remember(nameInput, existingTagNames, existingTag?.name, isSaving) {
        if (isSaving) {
            null
        } else {
            TagCatalog.validateTagName(
                rawName = nameInput,
                existingNames = existingTagNames,
                ignoreName = existingTag?.name
            )
        }
    }
    val canSubmit = !isSaving && validationError == null

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
                title = if (existingTag == null) "新增标签" else "编辑标签「#${existingTag.name}」",
                subtitle = if (existingTag != null) {
                    "修改标签名将自动在事务内精确更新关联历史账单、快捷模板与条件规则"
                } else {
                    "配置标签名称、莫奈主题色与所属场景分组"
                },
                onClose = requestDismiss
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 实时彩色胶囊预览 + 标签名输入（实时反例校验）
                ICostFormCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ICostColorTagChip(
                            tagName = nameInput.trim().ifEmpty { "预览标签" },
                            colorHex = selectedColorHex,
                            fontSize = 14.sp,
                            horizontalPadding = 12.dp,
                            verticalPadding = 6.dp,
                            showDot = true
                        )

                        ICostFormField(
                            value = nameInput,
                            onValueChange = {
                                nameInput = it
                                nameTouched = true
                            },
                            label = "标签名称",
                            placeholder = "如：出差、夜宵、人情往来",
                            singleLine = true,
                            isError = nameTouched && validationError != null,
                            supportingText = if (nameTouched && validationError != null) {
                                validationError
                            } else {
                                "不可为空，不可包含 , 、，或 # 符号，最多 12 个字"
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 10 种 iCost 莫奈柔和色卡选择器
                ICostFormCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "莫奈主题色",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = ICostTextSecondary
                        )
                        val colorRows = remember { TagCatalog.presetColors.chunked(5) }
                        colorRows.forEach { rowColors ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                rowColors.forEach { option ->
                                    val isSelected = selectedColorHex == option.colorHex
                                    val c = Color(option.colorHex)
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .semantics { contentDescription = "颜色 ${option.label}" }
                                            .iCostPressScale(pressedScale = 0.90f) {
                                                selectedColorHex = option.colorHex
                                            }
                                            .padding(vertical = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(CircleShape)
                                                .background(c)
                                                .then(
                                                    if (isSelected) {
                                                        Modifier.border(
                                                            width = 3.dp,
                                                            color = ICostTextPrimary.copy(alpha = 0.25f),
                                                            shape = CircleShape
                                                        )
                                                    } else Modifier
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = option.label,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) c else ICostTextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 分组选择
                ICostFormCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "所属分组",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = ICostTextSecondary
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TagCatalog.presetGroups.forEach { grp ->
                                val sel = selectedGroup == grp
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (sel) ICostBlueAccent else ICostCellGray,
                                    modifier = Modifier.iCostPressScale(pressedScale = 0.94f) {
                                        selectedGroup = grp
                                    }
                                ) {
                                    Text(
                                        text = grp,
                                        fontSize = 13.sp,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                        color = if (sel) Color.White else ICostTextPrimary,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        nameTouched = true
                        if (canSubmit) {
                            onSave(nameInput.trim(), selectedColorHex, selectedGroup) {
                                isSaving = true
                                requestDismiss()
                            }
                        }
                    },
                    enabled = canSubmit,
                    colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .height(48.dp)
                ) {
                    Text(
                        text = if (existingTag == null) "确认创建" else "保存修改",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

/**
 * 合并标签弹窗（将源标签 A 合并入目标标签 B，自动对同时拥有 A 与 B 的账单去重）
 */
@Composable
private fun TagMergeDialog(
    sourceTag: TagDef,
    initialTxCount: Int,
    candidateTargets: List<TagDef>,
    onCountTransactions: suspend (tagName: String) -> Int,
    onDismiss: () -> Unit,
    onConfirmMerge: (targetTagName: String) -> Unit
) {
    var txCount by remember(sourceTag) { mutableIntStateOf(initialTxCount) }
    LaunchedEffect(sourceTag) {
        txCount = onCountTransactions(sourceTag.name)
    }

    var selectedTargetName by remember(candidateTargets) {
        mutableStateOf(candidateTargets.firstOrNull()?.name.orEmpty())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ICostCardWhite,
        title = {
            Text(
                text = "合并标签「#${sourceTag.name}」",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFEAF2FF)
                ) {
                    Text(
                        text = "将「#${sourceTag.name}」（关联 $txCount 笔账单）合并入以下目标标签后，「#${sourceTag.name}」将从标签列表移除，原账单、快捷模板与规则中的该标签将精确替换为目标标签并自动去重：",
                        fontSize = 13.sp,
                        color = ICostBlueAccent,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    candidateTargets.forEach { candidate ->
                        val isSelected = selectedTargetName == candidate.name
                        Surface(
                            onClick = { selectedTargetName = candidate.name },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) Color(0xFFEAF2FF) else ICostCellGray,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ICostColorTagChip(
                                        tagName = candidate.name,
                                        colorHex = candidate.colorHex,
                                        fontSize = 13.sp,
                                        showDot = true
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = candidate.groupName,
                                        fontSize = 11.sp,
                                        color = ICostTextSecondary
                                    )
                                }
                                if (isSelected) {
                                    Text(
                                        text = "合并至此",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ICostBlueAccent
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedTargetName.isNotBlank()) {
                        onConfirmMerge(selectedTargetName)
                    }
                },
                enabled = selectedTargetName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("确认合并", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = ICostTextSecondary)
            }
        }
    )
}

/**
 * 删除标签安全保护弹窗：
 * - 当 N == 0 时，二次确认后从标签库移除；
 * - 当 N > 0 时，明确提示「共有 N 笔账单使用了该标签」并提供两个清晰单选选项：
 *   ① 仅从标签列表中移除（保留历史账单上的标签记录）
 *   ② 同时从 N 笔历史账单与规则中移除该标签（或替换为其他标签）
 */
@Composable
private fun TagSafeDeleteDialog(
    targetTag: TagDef,
    initialTxCount: Int,
    otherTags: List<TagDef>,
    onCountTransactions: suspend (tagName: String) -> Int,
    onDismiss: () -> Unit,
    onConfirmDelete: (cascadeRemoveFromHistory: Boolean, replacementTagName: String?) -> Unit
) {
    var txCount by remember(targetTag) { mutableIntStateOf(initialTxCount) }
    LaunchedEffect(targetTag) {
        txCount = onCountTransactions(targetTag.name)
    }

    // false = 选项① 仅从标签列表中移除（保留历史账单上的标签记录）
    // true  = 选项② 同时从 N 笔历史账单与规则中移除该标签（或替换为其他标签）
    var cascadeFromHistory by remember(targetTag) { mutableStateOf(false) }
    // 在选项②下，null 表示直接移除该标签，非 null 表示替换为指定其他标签
    var replacementTag by remember(targetTag) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ICostCardWhite,
        title = {
            Text(
                text = "删除标签「#${targetTag.name}」？",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (txCount <= 0) {
                    Text(
                        text = "当前没有历史账单使用「#${targetTag.name}」，从标签列表中移除后不会影响任何账单数据。",
                        fontSize = 14.sp,
                        color = ICostTextSecondary
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFFF4EC)
                    ) {
                        Text(
                            text = "共有 $txCount 笔账单使用了该标签。删除标签绝不会删除账单本身，请选择处理方式：",
                            fontSize = 13.sp,
                            color = Color(0xFFD96B27),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }

                    // 选项 ①：仅从标签列表中移除（保留历史账单上的标签记录）
                    Surface(
                        onClick = { cascadeFromHistory = false },
                        shape = RoundedCornerShape(14.dp),
                        color = if (!cascadeFromHistory) Color(0xFFEAF2FF) else ICostCellGray,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = !cascadeFromHistory,
                                onClick = { cascadeFromHistory = false },
                                colors = RadioButtonDefaults.colors(selectedColor = ICostBlueAccent)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "仅从标签列表中移除（保留历史账单上的标签记录）",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ICostTextPrimary
                                )
                                Text(
                                    text = "历史账单仍展示 #${targetTag.name}，后续记账面板不再显示该标签",
                                    fontSize = 11.sp,
                                    color = ICostTextSecondary
                                )
                            }
                        }
                    }

                    // 选项 ②：同时从 N 笔历史账单与规则中移除该标签（或替换为其他标签）
                    Surface(
                        onClick = { cascadeFromHistory = true },
                        shape = RoundedCornerShape(14.dp),
                        color = if (cascadeFromHistory) Color(0xFFFFEFEA) else ICostCellGray,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = cascadeFromHistory,
                                    onClick = { cascadeFromHistory = true },
                                    colors = RadioButtonDefaults.colors(selectedColor = ICostCoralRed)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "同时从 $txCount 笔历史账单与规则中移除该标签（或替换为其他标签）",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ICostTextPrimary
                                    )
                                    Text(
                                        text = "精确按标签分词清理或替换，不影响同一账单上的其他标签",
                                        fontSize = 11.sp,
                                        color = ICostTextSecondary
                                    )
                                }
                            }

                            if (cascadeFromHistory && otherTags.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "可选：直接移除，或替换为其他标签",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ICostTextSecondary,
                                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                                )
                                LazyRow(
                                    modifier = Modifier.padding(start = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    item {
                                        val isDirectRemove = replacementTag == null
                                        Surface(
                                            onClick = { replacementTag = null },
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isDirectRemove) ICostCoralRed else ICostCardWhite
                                        ) {
                                            Text(
                                                text = "直接移除",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isDirectRemove) Color.White else ICostTextPrimary,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                            )
                                        }
                                    }
                                    items(otherTags) { candidate ->
                                        val isSelected = replacementTag == candidate.name
                                        Surface(
                                            onClick = { replacementTag = candidate.name },
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSelected) ICostBlueAccent else ICostCardWhite
                                        ) {
                                            Text(
                                                text = "替换为 #${candidate.name}",
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) Color.White else ICostTextPrimary,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cascade = if (txCount <= 0) true else cascadeFromHistory
                    onConfirmDelete(cascade, if (cascade) replacementTag else null)
                },
                enabled = txCount >= 0,
                colors = ButtonDefaults.buttonColors(containerColor = ICostCoralRed),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = when {
                        txCount <= 0 -> "确认删除"
                        !cascadeFromHistory -> "仅从列表移除"
                        replacementTag != null -> "删除并替换"
                        else -> "删除并清理标签"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = ICostTextSecondary)
            }
        }
    )
}
