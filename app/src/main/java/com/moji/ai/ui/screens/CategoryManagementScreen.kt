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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.data.model.CategoryCatalog
import com.moji.ai.data.model.CategoryDef
import com.moji.ai.data.model.SubCategoryDef
import com.moji.ai.ui.theme.ICostAssetImage
import com.moji.ai.ui.theme.ICostBgGray
import com.moji.ai.ui.theme.ICostBlueAccent
import com.moji.ai.ui.theme.ICostCardWhite
import com.moji.ai.ui.theme.ICostCellGray
import com.moji.ai.ui.theme.ICostCircleIconButton
import com.moji.ai.ui.theme.ICostCoralRed
import com.moji.ai.ui.theme.ICostDivider
import com.moji.ai.ui.theme.ICostFormCard
import com.moji.ai.ui.theme.ICostFormField
import com.moji.ai.ui.theme.ICostGreenIncome
import com.moji.ai.ui.theme.ICostSheetHeader
import com.moji.ai.ui.theme.ICostSlateIcon
import com.moji.ai.ui.theme.ICostSmoothBottomSheet
import com.moji.ai.ui.theme.ICostTextMuted
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.iCostPressScale

private data class CategoryEditorTarget(
    val isExpense: Boolean,
    val isSubCategory: Boolean,
    /** 二级分类所属的一级分类名称 */
    val parentName: String? = null,
    /** 编辑模式下原分类名；新建模式下为 null */
    val existingName: String? = null,
    val initialIconAsset: String = "cat_dining.webp"
)

private data class CategoryDeleteTarget(
    val isExpense: Boolean,
    val isSubCategory: Boolean,
    val parentName: String? = null,
    val categoryName: String,
    val displayFullName: String
)

/**
 * 1:1 iCost 全屏「分类管理」二级页面（对齐 setting_category.webp）
 *
 * 支持支出/收入双 Tab 切换、一级与二级分类树新增/编辑/改图标/上下移动排序、
 * 81 枚内置线性图标选择器（CategoryIconPickerSheet）、历史账单级联重命名与带账单分类安全迁移删除保护。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryManagementScreen(
    expenseCategories: List<CategoryDef>,
    incomeCategories: List<CategoryDef>,
    initialIsExpense: Boolean = true,
    onBack: () -> Unit,
    onAddPrimaryCategory: (isExpense: Boolean, name: String, assetFile: String, onDone: (Result<CategoryDef>) -> Unit) -> Unit,
    onUpdatePrimaryCategory: (isExpense: Boolean, oldName: String, newName: String, assetFile: String, onDone: (Result<CategoryDef>) -> Unit) -> Unit,
    onMovePrimaryCategory: (isExpense: Boolean, index: Int, delta: Int) -> Unit,
    onDeletePrimaryCategory: (isExpense: Boolean, categoryName: String, migrateTargetCategory: String, onDone: (Result<Unit>) -> Unit) -> Unit,
    onAddSubCategory: (isExpense: Boolean, parentName: String, subName: String, assetFile: String, onDone: (Result<SubCategoryDef>) -> Unit) -> Unit,
    onUpdateSubCategory: (isExpense: Boolean, parentName: String, oldSubName: String, newSubName: String, assetFile: String, onDone: (Result<SubCategoryDef>) -> Unit) -> Unit,
    onMoveSubCategory: (isExpense: Boolean, parentName: String, subIndex: Int, delta: Int) -> Unit,
    onDeleteSubCategory: (isExpense: Boolean, parentName: String, subName: String, migrateTargetCategory: String, onDone: (Result<Unit>) -> Unit) -> Unit,
    onRestoreDefaults: (isExpense: Boolean?, mergeOnly: Boolean) -> Unit,
    onCountCategoryTransactions: suspend (category: String, includeSubPrefix: Boolean, isExpense: Boolean?) -> Int
) {
    BackHandler(onBack = onBack)

    var isExpenseTab by rememberSaveable(initialIsExpense) { mutableStateOf(initialIsExpense) }
    val activeCategories = if (isExpenseTab) expenseCategories else incomeCategories
    val activeAccentColor = if (isExpenseTab) ICostCoralRed else ICostGreenIncome

    var editorTarget by remember { mutableStateOf<CategoryEditorTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<CategoryDeleteTarget?>(null) }
    var activeSubActionMenu by remember { mutableStateOf<Pair<String, SubCategoryDef>?>(null) }
    var showRestoreMenu by remember { mutableStateOf(false) }
    var showFullResetConfirm by remember { mutableStateOf(false) }

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

        // 顶部栏：返回按钮 + 右侧「恢复默认」菜单 + 新增一级分类快捷按钮
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
                            text = { Text("补全缺失的默认分类（保留自定义）", fontSize = 14.sp) },
                            onClick = {
                                showRestoreMenu = false
                                onRestoreDefaults(null, true)
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "重置为出厂默认分类树",
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
                    color = activeAccentColor,
                    modifier = Modifier
                        .semantics { contentDescription = "新增一级分类" }
                        .iCostPressScale(pressedScale = 0.94f) {
                            editorTarget = CategoryEditorTarget(
                                isExpense = isExpenseTab,
                                isSubCategory = false,
                                parentName = null,
                                existingName = null,
                                initialIconAsset = if (isExpenseTab) "cat_dining.webp" else "cat_salary.webp"
                            )
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
                            text = "新增一级分类",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "分类管理",
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            color = ICostTextPrimary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 顶部「支出分类 | 收入分类」分段胶囊切换器 + 分类统计
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = ICostCardWhite
            ) {
                Row(modifier = Modifier.padding(4.dp)) {
                    val tabs = listOf(
                        true to "支出分类 (${expenseCategories.size})",
                        false to "收入分类 (${incomeCategories.size})"
                    )
                    tabs.forEach { (expMode, label) ->
                        val selected = isExpenseTab == expMode
                        val pillBg by animateColorAsState(
                            targetValue = if (selected) {
                                if (expMode) ICostCoralRed else ICostGreenIncome
                            } else Color.Transparent,
                            animationSpec = tween(180),
                            label = "CategoryTabPillBg"
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(pillBg)
                                .iCostPressScale(pressedScale = 0.94f) {
                                    isExpenseTab = expMode
                                }
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) Color.White else ICostTextPrimary
                            )
                        }
                    }
                }
            }

            val totalSubCount = activeCategories.sumOf { it.subCategories.size }
            Text(
                text = "共 $totalSubCount 个二级分类",
                fontSize = 12.sp,
                color = ICostTextSecondary,
                modifier = Modifier.padding(end = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 56.dp)
        ) {
            itemsIndexed(
                items = activeCategories,
                key = { _, item -> "${isExpenseTab}_${item.name}" }
            ) { index, cat ->
                val canDeletePrimary = activeCategories.size > 1
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
                        // 一级分类标题行：圆形徽章 + 分类名 + 子分类计数 + 操作按钮组（+二级 / 编辑 / 上移 / 下移 / 删除）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(activeAccentColor)
                                    .clickable {
                                        editorTarget = CategoryEditorTarget(
                                            isExpense = isExpenseTab,
                                            isSubCategory = false,
                                            parentName = null,
                                            existingName = cat.name,
                                            initialIconAsset = cat.assetFile
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                ICostAssetImage(
                                    assetFile = cat.assetFile,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(11.dp))

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        editorTarget = CategoryEditorTarget(
                                            isExpense = isExpenseTab,
                                            isSubCategory = false,
                                            parentName = null,
                                            existingName = cat.name,
                                            initialIconAsset = cat.assetFile
                                        )
                                    }
                            ) {
                                Text(
                                    text = cat.name,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ICostTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (cat.subCategories.isEmpty()) {
                                        "无二级分类 · 点击可改名或换图标"
                                    } else {
                                        "${cat.subCategories.size} 个二级分类"
                                    },
                                    fontSize = 11.sp,
                                    color = ICostTextSecondary
                                )
                            }

                            // 上移
                            CategorySmallIconBtn(
                                icon = Icons.Default.KeyboardArrowUp,
                                contentDescription = "上移${cat.name}",
                                enabled = index > 0,
                                onClick = { onMovePrimaryCategory(isExpenseTab, index, -1) }
                            )
                            // 下移
                            CategorySmallIconBtn(
                                icon = Icons.Default.KeyboardArrowDown,
                                contentDescription = "下移${cat.name}",
                                enabled = index < activeCategories.lastIndex,
                                onClick = { onMovePrimaryCategory(isExpenseTab, index, 1) }
                            )
                            // 编辑一级分类
                            CategorySmallIconBtn(
                                icon = Icons.Default.Edit,
                                contentDescription = "编辑${cat.name}",
                                enabled = true,
                                tint = ICostBlueAccent,
                                onClick = {
                                    editorTarget = CategoryEditorTarget(
                                        isExpense = isExpenseTab,
                                        isSubCategory = false,
                                        parentName = null,
                                        existingName = cat.name,
                                        initialIconAsset = cat.assetFile
                                    )
                                }
                            )
                            // 删除一级分类
                            CategorySmallIconBtn(
                                icon = Icons.Default.DeleteOutline,
                                contentDescription = "删除${cat.name}",
                                enabled = canDeletePrimary,
                                tint = if (canDeletePrimary) ICostCoralRed else ICostTextMuted,
                                onClick = {
                                    if (canDeletePrimary) {
                                        deleteTarget = CategoryDeleteTarget(
                                            isExpense = isExpenseTab,
                                            isSubCategory = false,
                                            parentName = null,
                                            categoryName = cat.name,
                                            displayFullName = cat.name
                                        )
                                    }
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(thickness = 0.5.dp, color = ICostDivider)
                        Spacer(modifier = Modifier.height(10.dp))

                        // 二级分类胶囊流式网格 + 末尾「+ 新建二级」胶囊
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            cat.subCategories.forEach { sub ->
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = ICostCellGray,
                                    modifier = Modifier
                                        .semantics { contentDescription = "二级分类 ${cat.name}-${sub.name}" }
                                        .iCostPressScale(pressedScale = 0.93f) {
                                            activeSubActionMenu = cat.name to sub
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(22.dp)
                                                .clip(CircleShape)
                                                .background(activeAccentColor.copy(alpha = 0.14f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            ICostAssetImage(
                                                assetFile = sub.assetFile,
                                                tint = activeAccentColor,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = sub.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ICostTextPrimary
                                        )
                                    }
                                }
                            }

                            // 「+ 新建二级」胶囊按钮
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = ICostBlueAccent.copy(alpha = 0.09f),
                                modifier = Modifier
                                    .semantics { contentDescription = "在${cat.name}下新建二级分类" }
                                    .iCostPressScale(pressedScale = 0.93f) {
                                        editorTarget = CategoryEditorTarget(
                                            isExpense = isExpenseTab,
                                            isSubCategory = true,
                                            parentName = cat.name,
                                            existingName = null,
                                            initialIconAsset = cat.assetFile
                                        )
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = ICostBlueAccent,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "新建二级",
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
        }
    }

    // 二级分类点击操作弹窗（支持编辑名称/图标、左移/右移排序、删除迁移）
    activeSubActionMenu?.let { (parentName, subDef) ->
        val parentCat = activeCategories.firstOrNull { it.name == parentName }
        val subIdx = parentCat?.subCategories?.indexOfFirst { it.name == subDef.name } ?: -1
        val subTotal = parentCat?.subCategories?.size ?: 0
        AlertDialog(
            onDismissRequest = { activeSubActionMenu = null },
            containerColor = ICostCardWhite,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(activeAccentColor),
                        contentAlignment = Alignment.Center
                    ) {
                        ICostAssetImage(
                            assetFile = subDef.assetFile,
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "$parentName · ${subDef.name}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = ICostTextPrimary
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "你可以修改二级分类名称与图标、调整排列顺序或删除该二级分类。",
                        fontSize = 13.sp,
                        color = ICostTextSecondary
                    )
                    if (subTotal > 1 && subIdx >= 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                onClick = {
                                    if (subIdx > 0) {
                                        onMoveSubCategory(isExpenseTab, parentName, subIdx, -1)
                                        activeSubActionMenu = null
                                    }
                                },
                                enabled = subIdx > 0,
                                shape = RoundedCornerShape(12.dp),
                                color = ICostCellGray,
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                        contentDescription = null,
                                        tint = if (subIdx > 0) ICostTextPrimary else ICostTextMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        "前移一位",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (subIdx > 0) ICostTextPrimary else ICostTextMuted
                                    )
                                }
                            }
                            Surface(
                                onClick = {
                                    if (subIdx < subTotal - 1) {
                                        onMoveSubCategory(isExpenseTab, parentName, subIdx, 1)
                                        activeSubActionMenu = null
                                    }
                                },
                                enabled = subIdx < subTotal - 1,
                                shape = RoundedCornerShape(12.dp),
                                color = ICostCellGray,
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "后移一位",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (subIdx < subTotal - 1) ICostTextPrimary else ICostTextMuted
                                    )
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = if (subIdx < subTotal - 1) ICostTextPrimary else ICostTextMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            activeSubActionMenu = null
                            deleteTarget = CategoryDeleteTarget(
                                isExpense = isExpenseTab,
                                isSubCategory = true,
                                parentName = parentName,
                                categoryName = subDef.name,
                                displayFullName = "$parentName-${subDef.name}"
                            )
                        }
                    ) {
                        Text("删除", color = ICostCoralRed, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            activeSubActionMenu = null
                            editorTarget = CategoryEditorTarget(
                                isExpense = isExpenseTab,
                                isSubCategory = true,
                                parentName = parentName,
                                existingName = subDef.name,
                                initialIconAsset = subDef.assetFile
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("编辑名称/图标", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { activeSubActionMenu = null }) {
                    Text("关闭", color = ICostTextSecondary)
                }
            }
        )
    }

    // 新增 / 编辑 一级或二级分类 + 内置 81 枚线性图标选择器 BottomSheet
    editorTarget?.let { target ->
        val siblingNames = remember(target, expenseCategories, incomeCategories) {
            val list = if (target.isExpense) expenseCategories else incomeCategories
            if (target.isSubCategory) {
                list.firstOrNull { it.name == target.parentName }?.subCategories?.map { it.name }.orEmpty()
            } else {
                list.map { it.name }
            }
        }
        CategoryEditorBottomSheet(
            target = target,
            siblingNames = siblingNames,
            onDismiss = { editorTarget = null },
            onSave = { finalName, finalAsset ->
                if (!target.isSubCategory) {
                    if (target.existingName == null) {
                        onAddPrimaryCategory(target.isExpense, finalName, finalAsset) { res ->
                            if (res.isSuccess) editorTarget = null
                        }
                    } else {
                        onUpdatePrimaryCategory(target.isExpense, target.existingName, finalName, finalAsset) { res ->
                            if (res.isSuccess) editorTarget = null
                        }
                    }
                } else {
                    val pName = target.parentName ?: return@CategoryEditorBottomSheet
                    if (target.existingName == null) {
                        onAddSubCategory(target.isExpense, pName, finalName, finalAsset) { res ->
                            if (res.isSuccess) editorTarget = null
                        }
                    } else {
                        onUpdateSubCategory(target.isExpense, pName, target.existingName, finalName, finalAsset) { res ->
                            if (res.isSuccess) editorTarget = null
                        }
                    }
                }
            }
        )
    }

    // 删除分类安全保护与历史账单迁移弹窗
    deleteTarget?.let { target ->
        CategorySafeDeleteMigrationDialog(
            target = target,
            availableCategories = if (target.isExpense) expenseCategories else incomeCategories,
            onCountTransactions = onCountCategoryTransactions,
            onDismiss = { deleteTarget = null },
            onConfirmDelete = { migrateTo ->
                if (!target.isSubCategory) {
                    onDeletePrimaryCategory(target.isExpense, target.categoryName, migrateTo) {
                        deleteTarget = null
                    }
                } else {
                    val pName = target.parentName ?: return@CategorySafeDeleteMigrationDialog
                    onDeleteSubCategory(target.isExpense, pName, target.categoryName, migrateTo) {
                        deleteTarget = null
                    }
                }
            }
        )
    }

    if (showFullResetConfirm) {
        AlertDialog(
            onDismissRequest = { showFullResetConfirm = false },
            containerColor = ICostCardWhite,
            title = { Text("重置为出厂默认分类？", fontWeight = FontWeight.ExtraBold) },
            text = {
                Text(
                    "重置将恢复初始的 24 个支出分类与 10 个收入分类，已录入的历史账单不会被删除。若只想找回误删的默认分类并保留自定义分类，请使用「补全缺失的默认分类」。",
                    fontSize = 14.sp,
                    color = ICostTextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFullResetConfirm = false
                        onRestoreDefaults(null, false)
                    }
                ) {
                    Text("确认重置", color = ICostCoralRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showFullResetConfirm = false }) {
                    Text("取消", color = ICostTextSecondary)
                }
            }
        )
    }
}

@Composable
private fun CategorySmallIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    tint: Color = ICostTextSecondary,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .then(
                if (enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) tint else ICostTextMuted.copy(alpha = 0.45f),
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 新增 / 编辑分类 + 内置 81 枚 iCost 线性图标选择器抽屉（CategoryIconPickerSheet）
 */
@Composable
fun CategoryEditorBottomSheet(
    isExpense: Boolean,
    isSubCategory: Boolean,
    parentName: String? = null,
    existingName: String? = null,
    initialIconAsset: String = "cat_dining.webp",
    siblingNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, assetFile: String) -> Unit
) {
    CategoryEditorBottomSheet(
        target = CategoryEditorTarget(
            isExpense = isExpense,
            isSubCategory = isSubCategory,
            parentName = parentName,
            existingName = existingName,
            initialIconAsset = initialIconAsset
        ),
        siblingNames = siblingNames,
        onDismiss = onDismiss,
        onSave = onSave
    )
}

@Composable
private fun CategoryEditorBottomSheet(
    target: CategoryEditorTarget,
    siblingNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, assetFile: String) -> Unit
) {
    var nameInput by remember(target) { mutableStateOf(target.existingName.orEmpty()) }
    var selectedIcon by remember(target) { mutableStateOf(target.initialIconAsset) }
    var nameTouched by remember(target) { mutableStateOf(false) }

    val validationError = remember(nameInput, siblingNames, target.existingName) {
        CategoryCatalog.validateCategoryName(
            rawName = nameInput,
            siblingNames = siblingNames,
            ignoreName = target.existingName
        )
    }
    val canSubmit = validationError == null
    val accentColor = if (target.isExpense) ICostCoralRed else ICostGreenIncome

    val sheetTitle = when {
        target.isSubCategory && target.existingName == null -> "在「${target.parentName}」下新建二级分类"
        target.isSubCategory -> "编辑二级分类「${target.parentName}-${target.existingName}」"
        target.existingName == null -> "新增${if (target.isExpense) "支出" else "收入"}一级分类"
        else -> "编辑一级分类「${target.existingName}」"
    }

    ICostSmoothBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ICostBgGray,
        showDragHandle = false
    ) { requestDismiss ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .navigationBarsPadding()
        ) {
            ICostSheetHeader(
                title = sheetTitle,
                subtitle = if (target.existingName != null) {
                    "修改名称后将自动同步更新关联的历史账单、模板、周期规则与商户记忆"
                } else {
                    "选择圆形线性图标并输入分类名称（最多 12 个字）"
                },
                onClose = requestDismiss
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 顶部：实时圆形徽章预览 + 分类名称输入框
                ICostFormCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(accentColor),
                                contentAlignment = Alignment.Center
                            ) {
                                ICostAssetImage(
                                    assetFile = selectedIcon,
                                    tint = Color.White,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = nameInput.trim().ifEmpty { "预览" },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        ICostFormField(
                            value = nameInput,
                            onValueChange = {
                                nameInput = it
                                nameTouched = true
                            },
                            label = if (target.isSubCategory) "二级分类名称" else "一级分类名称",
                            placeholder = if (target.isSubCategory) "如：火锅、羽毛球" else "如：露营、宠物",
                            singleLine = true,
                            isError = nameTouched && validationError != null,
                            supportingText = if (nameTouched && validationError != null) {
                                validationError
                            } else {
                                "不可包含 -、/ 或 · 分隔符，同级名称不可重复"
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 中部：内置 81 枚图标分组网格选择器
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = ICostCardWhite,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CategoryCatalog.iconGroups.forEach { group ->
                            item(key = group.title) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "${group.title} (${group.icons.size})",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ICostTextSecondary,
                                        modifier = Modifier.padding(start = 2.dp)
                                    )
                                    val iconRows = remember(group.icons) { group.icons.chunked(5) }
                                    iconRows.forEach { rowIcons ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            rowIcons.forEach { option ->
                                                val isSelected = selectedIcon == option.assetFile
                                                Column(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .semantics {
                                                            contentDescription = "图标 ${option.label}"
                                                        }
                                                        .iCostPressScale(pressedScale = 0.88f) {
                                                            selectedIcon = option.assetFile
                                                            if (nameInput.isBlank() && target.existingName == null) {
                                                                // 若用户尚未输入名称，保留空白让用户自行输入，不强制填入
                                                            }
                                                        }
                                                        .padding(vertical = 4.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(44.dp)
                                                            .clip(CircleShape)
                                                            .background(
                                                                if (isSelected) accentColor else Color(0xFFECECEE)
                                                            )
                                                            .then(
                                                                if (isSelected) {
                                                                    Modifier.border(
                                                                        width = 2.dp,
                                                                        color = accentColor.copy(alpha = 0.35f),
                                                                        shape = CircleShape
                                                                    )
                                                                } else Modifier
                                                            ),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        ICostAssetImage(
                                                            assetFile = option.assetFile,
                                                            tint = if (isSelected) Color.White else ICostSlateIcon,
                                                            modifier = Modifier.size(23.dp)
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(3.dp))
                                                    Text(
                                                        text = option.label,
                                                        fontSize = 11.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (isSelected) accentColor else ICostTextSecondary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            repeat(5 - rowIcons.size) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 底部保存按钮
                Button(
                    onClick = {
                        nameTouched = true
                        if (canSubmit) {
                            onSave(nameInput.trim(), selectedIcon)
                            requestDismiss()
                        }
                    },
                    enabled = canSubmit,
                    colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .height(48.dp)
                ) {
                    Text(
                        text = if (target.existingName == null) "确认创建" else "保存修改",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

/**
 * 删除分类安全保护与历史账单迁移弹窗（对齐规格 TC-05）
 */
@Composable
private fun CategorySafeDeleteMigrationDialog(
    target: CategoryDeleteTarget,
    availableCategories: List<CategoryDef>,
    onCountTransactions: suspend (category: String, includeSubPrefix: Boolean, isExpense: Boolean?) -> Int,
    onDismiss: () -> Unit,
    onConfirmDelete: (migrateTargetCategory: String) -> Unit
) {
    var txCount by remember(target) { mutableIntStateOf(-1) }
    LaunchedEffect(target) {
        txCount = onCountTransactions(
            target.displayFullName,
            !target.isSubCategory,
            target.isExpense
        )
    }

    // 候选迁移目标分类列表（排除自身）
    val candidateTargets = remember(target, availableCategories) {
        buildList {
            if (target.isSubCategory && !target.parentName.isNullOrBlank()) {
                // 删除二级分类时，首选回退到所属一级分类
                add(target.parentName)
                val parent = availableCategories.firstOrNull { it.name == target.parentName }
                parent?.subCategories
                    ?.filter { it.name != target.categoryName }
                    ?.forEach { add("${target.parentName}-${it.name}") }
            }
            availableCategories
                .filter { it.name != target.categoryName }
                .forEach { cat ->
                    if (cat.name !in this) add(cat.name)
                }
            if ("其他" !in this && target.categoryName != "其他") {
                add("其他")
            }
        }
    }

    val defaultMigrateTarget = remember(target, candidateTargets) {
        when {
            target.isSubCategory && !target.parentName.isNullOrBlank() -> target.parentName
            "其他" in candidateTargets -> "其他"
            else -> candidateTargets.firstOrNull() ?: "其他"
        }
    }
    var selectedMigrateTarget by remember(defaultMigrateTarget) { mutableStateOf(defaultMigrateTarget) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ICostCardWhite,
        title = {
            Text(
                text = if (txCount > 0) {
                    "迁移并删除「${target.displayFullName}」"
                } else {
                    "删除分类「${target.displayFullName}」？"
                },
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
                        text = if (target.isSubCategory) {
                            "该二级分类下没有关联的历史账单，删除后可随时重新创建。"
                        } else {
                            "该一级分类（及其二级分类）下没有关联的历史账单，删除后也可在顶部「恢复默认」中找回。"
                        },
                        fontSize = 14.sp,
                        color = ICostTextSecondary
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFFF4EC)
                    ) {
                        Text(
                            text = "该分类下共有 $txCount 笔历史账单。为防止账单变成无分类孤儿数据，删除前请选择迁移的目标分类：",
                            fontSize = 13.sp,
                            color = Color(0xFFD96B27),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }

                    Text(
                        text = "选择迁移目标分类",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextSecondary
                    )

                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        candidateTargets.forEach { option ->
                            val isSelected = selectedMigrateTarget == option
                            Surface(
                                onClick = { selectedMigrateTarget = option },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) ICostBlueAccent else ICostCellGray
                            ) {
                                Text(
                                    text = if (isSelected) "✓ $option" else option,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color.White else ICostTextPrimary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = txCount >= 0,
                onClick = { onConfirmDelete(selectedMigrateTarget) },
                colors = ButtonDefaults.buttonColors(containerColor = ICostCoralRed),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (txCount > 0) "迁移并删除" else "确认删除",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
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
