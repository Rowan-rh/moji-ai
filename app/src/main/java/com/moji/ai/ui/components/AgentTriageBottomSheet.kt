package com.moji.ai.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.ai.AgentTriageReport
import com.moji.ai.ai.ProposedCategoryItem
import com.moji.ai.ai.ProposedMemoryItem
import com.moji.ai.ai.ProposedRuleItem
import com.moji.ai.ai.TriageBillItem
import com.moji.ai.data.model.CategoryCatalog
import com.moji.ai.data.model.TransactionType
import com.moji.ai.ui.theme.ICDinFontFamily
import com.moji.ai.ui.theme.ICostAssetImage
import com.moji.ai.ui.theme.ICostBgGray
import com.moji.ai.ui.theme.ICostBlueAccent
import com.moji.ai.ui.theme.ICostCardWhite
import com.moji.ai.ui.theme.ICostCategoryBadge
import com.moji.ai.ui.theme.ICostCoralRed
import com.moji.ai.ui.theme.ICostDivider
import com.moji.ai.ui.theme.ICostGreenIncome
import com.moji.ai.ui.theme.ICostSheetHeader
import com.moji.ai.ui.theme.ICostSmoothBottomSheet
import com.moji.ai.ui.theme.ICostTextMuted
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.formatMoneyCents
import com.moji.ai.ui.theme.iCostPressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AgentTriageBottomSheet(
    report: AgentTriageReport?,
    isTriaging: Boolean,
    onDismiss: () -> Unit,
    onRescan: () -> Unit,
    onApplySelected: (
        bills: List<TriageBillItem>,
        newCategories: List<ProposedCategoryItem>,
        newRules: List<ProposedRuleItem>,
        newMemories: List<ProposedMemoryItem>
    ) -> Unit
) {
    var selectedBillIds by remember { mutableStateOf(setOf<Long>()) }
    var selectedCategoryIds by remember { mutableStateOf(setOf<String>()) }
    var selectedRuleIds by remember { mutableStateOf(setOf<String>()) }
    var selectedMemoryIds by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(report) {
        if (report != null) {
            selectedBillIds = report.reclassifyItems.map { it.transactionId }.toSet()
            selectedCategoryIds = report.proposedNewCategories.map { it.id }.toSet()
            selectedRuleIds = report.proposedRules.map { it.id }.toSet()
            selectedMemoryIds = report.proposedMemories.map { it.id }.toSet()
        }
    }

    val totalSelectedCount = selectedBillIds.size +
        selectedCategoryIds.size +
        selectedRuleIds.size +
        selectedMemoryIds.size

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
                title = "✨ Agent 梳理与规则建议",
                subtitle = "自动归档「待梳理」账单、从截图归纳细分新分类与 When→Then 规则",
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
                // 顶部概览横幅
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFFEEF2FF),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF6366F1)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isTriaging) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                ICostAssetImage(
                                    assetFile = "setting_auto.webp",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isTriaging) {
                                    "正在扫描待梳理账单与截图证据…"
                                } else {
                                    "已扫描 ${report?.scannedBillCount ?: 0} 笔账单 · 待梳理 ${report?.pendingTriageCount ?: 0} 笔"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF3730A3)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isTriaging) {
                                    "正在结合商户、时段、金额与账单截图生成归档方案"
                                } else {
                                    report?.summaryText?.ifBlank { "点击右侧可重新扫描当前账本" }
                                        ?: "点击右侧开始扫描当前账本"
                                },
                                fontSize = 11.sp,
                                color = Color(0xFF4F46E5)
                            )
                        }
                        if (!isTriaging) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .iCostPressScale(pressedScale = 0.92f) { onRescan() }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "重新扫描",
                                        tint = Color(0xFF4F46E5),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "重扫",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4F46E5)
                                    )
                                }
                            }
                        }
                    }
                }

                if (!isTriaging && (report == null || report.totalActionCount == 0)) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = ICostCardWhite,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "当前账本暂无待梳理账单或新增建议",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostTextPrimary
                            )
                            Text(
                                text = "记账时如果不确定选什么分类，可在记账计算器顶部点选「待梳理（交由 Agent）」直接保存金额；积累几笔或截图识账后，随时点此一键归档并自动提炼分类与规则。",
                                fontSize = 12.sp,
                                color = ICostTextSecondary
                            )
                        }
                    }
                }

                // 1. 建议新增分类（从截图/账单中归纳出的细分分类 + 匹配图标）
                val newCats = report?.proposedNewCategories.orEmpty()
                if (newCats.isNotEmpty()) {
                    TriageSectionCard(
                        title = "建议新增分类 (${newCats.count { it.id in selectedCategoryIds }}/${newCats.size})",
                        onToggleAll = {
                            selectedCategoryIds = if (selectedCategoryIds.size == newCats.size) {
                                emptySet()
                            } else {
                                newCats.map { it.id }.toSet()
                            }
                        },
                        allSelected = selectedCategoryIds.size == newCats.size
                    ) {
                        newCats.forEachIndexed { idx, cat ->
                            val checked = cat.id in selectedCategoryIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedCategoryIds = if (checked) selectedCategoryIds - cat.id else selectedCategoryIds + cat.id
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(if (cat.isExpense) ICostCoralRed else ICostGreenIncome),
                                    contentAlignment = Alignment.Center
                                ) {
                                    ICostAssetImage(
                                        assetFile = cat.iconAsset,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = cat.fullCategoryPath.replace("-", " · "),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ICostTextPrimary
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = Color(0xFFEEF2FF)
                                        ) {
                                            Text(
                                                text = if (cat.isSubCategory) "+新二级分类" else "+新一级分类",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF4F46E5),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = "${cat.reason}（关联 ${cat.matchedCount} 笔）",
                                        fontSize = 11.sp,
                                        color = ICostTextSecondary
                                    )
                                }
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        selectedCategoryIds = if (it) selectedCategoryIds + cat.id else selectedCategoryIds - cat.id
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = ICostBlueAccent)
                                )
                            }
                            if (idx < newCats.lastIndex) HorizontalDivider(color = ICostDivider)
                        }
                    }
                }

                // 2. 账单分类归档（含截图缩略图与证据）
                val bills = report?.reclassifyItems.orEmpty()
                if (bills.isNotEmpty()) {
                    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
                    TriageSectionCard(
                        title = "账单分类归档 (${bills.count { it.transactionId in selectedBillIds }}/${bills.size})",
                        onToggleAll = {
                            selectedBillIds = if (selectedBillIds.size == bills.size) {
                                emptySet()
                            } else {
                                bills.map { it.transactionId }.toSet()
                            }
                        },
                        allSelected = selectedBillIds.size == bills.size
                    ) {
                        bills.forEachIndexed { idx, item ->
                            val checked = item.transactionId in selectedBillIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedBillIds = if (checked) selectedBillIds - item.transactionId else selectedBillIds + item.transactionId
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TriageScreenshotOrBadge(item = item)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = item.merchant.ifBlank { "日常消费" },
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ICostTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = formatMoneyCents(item.amountCnyCents, item.currency),
                                            fontFamily = ICDinFontFamily,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (item.type == TransactionType.INCOME) ICostGreenIncome else ICostCoralRed
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (CategoryCatalog.isPendingTriage(item.currentCategory)) Color(0xFFEDE9FE) else ICostBgGray
                                        ) {
                                            Text(
                                                text = item.currentCategory,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (CategoryCatalog.isPendingTriage(item.currentCategory)) Color(0xFF6D28D9) else ICostTextSecondary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Text(
                                            text = " → ",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ICostBlueAccent
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = Color(0xFFEAF2FF)
                                        ) {
                                            Text(
                                                text = item.suggestedCategory + if (item.isNewCategoryProposal) " [新]" else "",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = ICostBlueAccent,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = timeFmt.format(Date(item.timestamp)),
                                            fontSize = 10.sp,
                                            color = ICostTextMuted
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = item.reason,
                                        fontSize = 11.sp,
                                        color = ICostTextSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        selectedBillIds = if (it) selectedBillIds + item.transactionId else selectedBillIds - item.transactionId
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = ICostBlueAccent)
                                )
                            }
                            if (idx < bills.lastIndex) HorizontalDivider(color = ICostDivider)
                        }
                    }
                }

                // 3. 建议新增规则与商户记忆
                val rules = report?.proposedRules.orEmpty()
                val memories = report?.proposedMemories.orEmpty()
                if (rules.isNotEmpty() || memories.isNotEmpty()) {
                    val totalRuleMem = rules.size + memories.size
                    val checkedRuleMem = rules.count { it.id in selectedRuleIds } +
                        memories.count { it.id in selectedMemoryIds }
                    TriageSectionCard(
                        title = "建议新增规则与记忆 ($checkedRuleMem/$totalRuleMem)",
                        onToggleAll = {
                            if (checkedRuleMem == totalRuleMem) {
                                selectedRuleIds = emptySet()
                                selectedMemoryIds = emptySet()
                            } else {
                                selectedRuleIds = rules.map { it.id }.toSet()
                                selectedMemoryIds = memories.map { it.id }.toSet()
                            }
                        },
                        allSelected = checkedRuleMem == totalRuleMem
                    ) {
                        rules.forEachIndexed { idx, rItem ->
                            val checked = rItem.id in selectedRuleIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedRuleIds = if (checked) selectedRuleIds - rItem.id else selectedRuleIds + rItem.id
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "规则 · ${rItem.rule.name}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ICostTextPrimary
                                    )
                                    Text(
                                        text = "当 ${rItem.rule.conditionSummary()} → 则 ${rItem.rule.actionSummary()}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = ICostBlueAccent
                                    )
                                    Text(
                                        text = rItem.reason,
                                        fontSize = 11.sp,
                                        color = ICostTextSecondary
                                    )
                                }
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        selectedRuleIds = if (it) selectedRuleIds + rItem.id else selectedRuleIds - rItem.id
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = ICostBlueAccent)
                                )
                            }
                            if (idx < rules.lastIndex || memories.isNotEmpty()) {
                                HorizontalDivider(color = ICostDivider)
                            }
                        }

                        memories.forEachIndexed { idx, mItem ->
                            val checked = mItem.id in selectedMemoryIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedMemoryIds = if (checked) selectedMemoryIds - mItem.id else selectedMemoryIds + mItem.id
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "商户记忆 · ${mItem.merchantKeyword} → ${mItem.targetCategory}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ICostTextPrimary
                                    )
                                    Text(
                                        text = mItem.reason,
                                        fontSize = 11.sp,
                                        color = ICostTextSecondary
                                    )
                                }
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        selectedMemoryIds = if (it) selectedMemoryIds + mItem.id else selectedMemoryIds - mItem.id
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = ICostBlueAccent)
                                )
                            }
                            if (idx < memories.lastIndex) HorizontalDivider(color = ICostDivider)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
            }

            // 底部一键采纳生效按钮栏
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Button(
                    onClick = {
                        if (report != null && totalSelectedCount > 0) {
                            onApplySelected(
                                report.reclassifyItems.filter { it.transactionId in selectedBillIds },
                                report.proposedNewCategories.filter { it.id in selectedCategoryIds },
                                report.proposedRules.filter { it.id in selectedRuleIds },
                                report.proposedMemories.filter { it.id in selectedMemoryIds }
                            )
                        }
                        requestDismiss()
                    },
                    enabled = !isTriaging,
                    colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (totalSelectedCount > 0) {
                            "一键采纳生效 (已选 $totalSelectedCount 项)"
                        } else {
                            "完成"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun TriageSectionCard(
    title: String,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = ICostTextSecondary
            )
            TextButton(
                onClick = onToggleAll,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(
                    text = if (allSelected) "取消全选" else "全选",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = ICostBlueAccent
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = ICostCardWhite,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun TriageScreenshotOrBadge(item: TriageBillItem) {
    val thumbBitmap by produceState<ImageBitmap?>(initialValue = null, item.screenshotPath) {
        val path = item.screenshotPath
        if (!path.isNullOrBlank()) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(path)
                    if (f.exists()) {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                        BitmapFactory.decodeFile(f.absolutePath, opts)?.asImageBitmap()
                    } else null
                }.getOrNull()
            }
        }
    }
    if (thumbBitmap != null) {
        Box(
            modifier = Modifier
                .size(width = 38.dp, height = 46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ICostBgGray)
        ) {
            Image(
                bitmap = thumbBitmap!!,
                contentDescription = "账单截图证据",
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
    } else {
        ICostCategoryBadge(
            category = item.suggestedCategory,
            type = item.type,
            size = 38.dp
        )
    }
}


