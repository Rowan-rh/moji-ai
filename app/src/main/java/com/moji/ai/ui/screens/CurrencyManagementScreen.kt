package com.moji.ai.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.CurrencyRateManager
import com.moji.ai.ui.components.ICostConfirmDialog
import com.moji.ai.ui.theme.ICDinFontFamily
import com.moji.ai.ui.theme.ICostAmberGold
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
import com.moji.ai.ui.theme.ICostSearchBg
import com.moji.ai.ui.theme.ICostSheetHeader
import com.moji.ai.ui.theme.ICostSmoothBottomSheet
import com.moji.ai.ui.theme.ICostTextMuted
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.iCostPressScale
import java.util.Locale

@Composable
fun CurrencyManagementScreen(
    customRates: Map<String, Double>,
    wallets: List<WalletEntity> = emptyList(),
    onBack: () -> Unit,
    onSaveCustomRate: (currencyCode: String, rawRateInput: String, onDone: (Result<Double>) -> Unit) -> Unit,
    onResetCurrencyRate: (currencyCode: String) -> Unit,
    onResetAllCustomRates: () -> Unit
) {
    BackHandler(onBack = onBack)

    var selectedFilter by rememberSaveable { mutableStateOf("ALL") } // ALL, CUSTOM, COMMON
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var previewAmountInput by rememberSaveable { mutableStateOf("100") }
    var editingCurrency by remember { mutableStateOf<CurrencyCode?>(null) }
    var showResetAllConfirm by remember { mutableStateOf(false) }

    val previewBaseAmount = remember(previewAmountInput) {
        previewAmountInput.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: 100.0
    }

    val foreignCurrencies = remember { CurrencyCode.foreignEntries }
    val walletCountByCurrency = remember(wallets) {
        wallets.groupingBy { CurrencyCode.fromCode(it.currency).name }.eachCount()
    }

    val trimmedQuery = searchQuery.trim()
    val filteredCurrencies = remember(foreignCurrencies, customRates, selectedFilter, trimmedQuery) {
        foreignCurrencies.filter { code ->
            val filterMatch = when (selectedFilter) {
                "CUSTOM" -> customRates.containsKey(code.name)
                "COMMON" -> code.isCommonForeign
                else -> true
            }
            val queryMatch = trimmedQuery.isEmpty() ||
                code.name.contains(trimmedQuery, ignoreCase = true) ||
                code.displayName.contains(trimmedQuery, ignoreCase = true) ||
                code.symbol.contains(trimmedQuery, ignoreCase = true)
            filterMatch && queryMatch
        }
    }

    CurrencyManagementContent(
        foreignCurrencies = foreignCurrencies,
        filteredCurrencies = filteredCurrencies,
        customRates = customRates,
        walletCountByCurrency = walletCountByCurrency,
        selectedFilter = selectedFilter,
        onSelectFilter = { selectedFilter = it },
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        previewAmountInput = previewAmountInput,
        onPreviewAmountChange = { raw ->
            val cleaned = raw.filter { it.isDigit() || it == '.' }.take(10)
            if (cleaned.count { it == '.' } <= 1) {
                previewAmountInput = cleaned
            }
        },
        previewBaseAmount = previewBaseAmount,
        onBack = onBack,
        onRequestResetAll = { showResetAllConfirm = true },
        onEditCurrency = { editingCurrency = it },
        onResetSingleCurrency = { code -> onResetCurrencyRate(code.name) }
    )

    editingCurrency?.let { target ->
        val currentEffective = customRates[target.name] ?: target.defaultRateToCny
        val isCustom = customRates.containsKey(target.name)
        CurrencyRateEditorBottomSheet(
            currency = target,
            currentEffectiveRate = currentEffective,
            isCurrentlyCustom = isCustom,
            previewBaseAmount = if (previewBaseAmount > 0.0) previewBaseAmount else 100.0,
            onDismiss = { editingCurrency = null },
            onSave = { rawInput, requestDismiss ->
                onSaveCustomRate(target.name, rawInput) { res ->
                    if (res.isSuccess) requestDismiss()
                }
            },
            onResetDefault = { requestDismiss ->
                onResetCurrencyRate(target.name)
                requestDismiss()
            }
        )
    }

    if (showResetAllConfirm) {
        ICostConfirmDialog(
            title = "恢复默认汇率",
            message = "确定将全部外币恢复为系统默认参考汇率吗？\n\n已入账的历史账单汇率快照保持不变，仅对后续新记账单与外币账户净资产折算生效。",
            confirmText = "恢复默认",
            onConfirm = {
                showResetAllConfirm = false
                onResetAllCustomRates()
            },
            onDismiss = { showResetAllConfirm = false }
        )
    }
}

@Composable
private fun CurrencyManagementContent(
    foreignCurrencies: List<CurrencyCode>,
    filteredCurrencies: List<CurrencyCode>,
    customRates: Map<String, Double>,
    walletCountByCurrency: Map<String, Int>,
    selectedFilter: String,
    onSelectFilter: (String) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    previewAmountInput: String,
    onPreviewAmountChange: (String) -> Unit,
    previewBaseAmount: Double,
    onBack: () -> Unit,
    onRequestResetAll: () -> Unit,
    onEditCurrency: (CurrencyCode) -> Unit,
    onResetSingleCurrency: (CurrencyCode) -> Unit
) {
    val customCount = customRates.size
    val commonCount = remember(foreignCurrencies) { foreignCurrencies.count { it.isCommonForeign } }
    val filterTabs = remember(foreignCurrencies.size, customCount, commonCount) {
        listOf(
            "ALL" to "全部 (${foreignCurrencies.size})",
            "CUSTOM" to "已自定义 ($customCount)",
            "COMMON" to "常用外币 ($commonCount)"
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ICostBgGray)
            .navigationBarsPadding()
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
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (customCount > 0) ICostCardWhite else Color(0xFFECECEE),
                modifier = Modifier
                    .semantics { contentDescription = "恢复默认汇率" }
                    .then(
                        if (customCount > 0) {
                            Modifier.iCostPressScale(pressedScale = 0.94f, onClick = onRequestResetAll)
                        } else {
                            Modifier
                        }
                    )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = if (customCount > 0) ICostCoralRed else ICostTextMuted,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "恢复默认汇率",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (customCount > 0) ICostCoralRed else ICostTextMuted
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "货币和汇率",
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            color = ICostTextPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (customCount > 0) {
                "支持 ${CurrencyCode.entries.size} 种货币 · 已自定义 $customCount 项汇率 · 历史账单汇率快照保持不变"
            } else {
                "本位币人民币 (CNY) · 支持 ${CurrencyCode.entries.size} 种货币实时折算与自定义参考汇率"
            },
            fontSize = 13.sp,
            color = ICostTextSecondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 本位币与实时汇率换算预览卡片
        BaseCurrencyAndConverterCard(
            previewAmountInput = previewAmountInput,
            onPreviewAmountChange = onPreviewAmountChange
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 搜索框
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = ICostSearchBg,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = ICostTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "搜索币种名称、代码或符号（如 USD / 日元 / €）",
                            fontSize = 13.sp,
                            color = ICostTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 14.sp, color = ICostTextPrimary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "清除搜索",
                        tint = ICostTextSecondary,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onSearchQueryChange("") }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 三态分段筛选（全部 / 已自定义 / 常用外币）
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filterTabs, key = { it.first }) { (key, label) ->
                val selected = selectedFilter == key
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (selected) ICostTextPrimary else ICostCardWhite,
                    modifier = Modifier.iCostPressScale(pressedScale = 0.94f) {
                        onSelectFilter(key)
                    }
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) Color.White else ICostTextSecondary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            if (filteredCurrencies.isEmpty()) {
                item {
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
                                text = if (selectedFilter == "CUSTOM") "当前没有自定义汇率项" else "未找到匹配的货币",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostTextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (selectedFilter == "CUSTOM") {
                                    "点击任意外币卡片即可自定义对人民币参考汇率"
                                } else {
                                    "请尝试更换搜索词或切换筛选标签"
                                },
                                fontSize = 13.sp,
                                color = ICostTextSecondary
                            )
                        }
                    }
                }
            } else {
                items(filteredCurrencies, key = { it.name }) { code ->
                    val effectiveRate = customRates[code.name] ?: code.defaultRateToCny
                    val isCustom = customRates.containsKey(code.name)
                    val linkedWalletCount = walletCountByCurrency[code.name] ?: 0
                    ForeignCurrencyRateCard(
                        currency = code,
                        effectiveRate = effectiveRate,
                        isCustom = isCustom,
                        previewBaseAmount = previewBaseAmount,
                        linkedWalletCount = linkedWalletCount,
                        onEdit = { onEditCurrency(code) },
                        onResetDefault = { onResetSingleCurrency(code) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BaseCurrencyAndConverterCard(
    previewAmountInput: String,
    onPreviewAmountChange: (String) -> Unit
) {
    val quickAmounts = remember { listOf("100", "1000", "10000") }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ICostCardWhite,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(ICostCoralRed.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "¥",
                            fontFamily = ICDinFontFamily,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = ICostCoralRed
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "人民币 (CNY)",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostTextPrimary,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = ICostCoralRed.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = "本位币",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ICostCoralRed,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "外币账单与资产均按生效汇率折合 CNY 汇总",
                            fontSize = 11.sp,
                            color = ICostTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = "1.0000",
                    fontFamily = ICDinFontFamily,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = ICostTextPrimary,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = ICostDivider)
            Spacer(modifier = Modifier.height(10.dp))

            // 快捷汇率换算预览输入区
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "换算预览基准外币金额",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = ICostTextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ICostCellGray,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicTextField(
                                value = previewAmountInput,
                                onValueChange = onPreviewAmountChange,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                textStyle = TextStyle(
                                    fontFamily = ICDinFontFamily,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ICostBlueAccent
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                quickAmounts.forEach { preset ->
                                    val isSelected = previewAmountInput == preset
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) ICostBlueAccent else ICostCardWhite,
                                        modifier = Modifier.clickable { onPreviewAmountChange(preset) }
                                    ) {
                                        Text(
                                            text = preset,
                                            fontFamily = ICDinFontFamily,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color.White else ICostTextSecondary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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

@Composable
private fun ForeignCurrencyRateCard(
    currency: CurrencyCode,
    effectiveRate: Double,
    isCustom: Boolean,
    previewBaseAmount: Double,
    linkedWalletCount: Int,
    onEdit: () -> Unit,
    onResetDefault: () -> Unit
) {
    val convertedCny = previewBaseAmount * effectiveRate
    val formattedRate = CurrencyRateManager.formatRate(effectiveRate)
    val formattedDefaultRate = CurrencyRateManager.formatRate(currency.defaultRateToCny)
    val formattedPreviewBase = if (previewBaseAmount % 1.0 == 0.0) {
        String.format(Locale.US, "%.0f", previewBaseAmount)
    } else {
        String.format(Locale.US, "%.2f", previewBaseAmount)
    }
    val formattedConvertedCny = String.format(Locale.US, "%.2f", convertedCny)

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = ICostCardWhite,
        modifier = Modifier
            .fillMaxWidth()
            .iCostPressScale(pressedScale = 0.98f, onClick = onEdit)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp)
        ) {
            // 第 1 行：左侧圆形货币符号徽标 + 中间币种中文名与代码 + 状态胶囊 + 右侧 ICDIN 汇率
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            if (isCustom) ICostBlueAccent.copy(alpha = 0.12f) else ICostCellGray
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = currency.symbol,
                        fontFamily = ICDinFontFamily,
                        fontSize = if (currency.symbol.length >= 3) 12.sp else 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isCustom) ICostBlueAccent else ICostTextPrimary,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = currency.displayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isCustom) ICostBlueAccent.copy(alpha = 0.12f) else ICostCellGray
                    ) {
                        Text(
                            text = if (isCustom) "已自定义" else "默认",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isCustom) ICostBlueAccent else ICostTextSecondary,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = "1 ${currency.name} = ¥$formattedRate",
                    fontFamily = ICDinFontFamily,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isCustom) ICostBlueAccent else ICostTextPrimary,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = ICostDivider.copy(alpha = 0.7f))
            Spacer(modifier = Modifier.height(8.dp))

            // 第 2 行：左侧实时换算预览（附带默认对比/关联账户数）+ 右侧操作按钮（恢复默认 + 编辑汇率）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "$formattedPreviewBase ${currency.name} ≈ ¥$formattedConvertedCny CNY",
                        fontFamily = ICDinFontFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val metaParts = buildList {
                        if (isCustom) add("默认 $formattedDefaultRate")
                        if (linkedWalletCount > 0) add("关联 $linkedWalletCount 个账户")
                    }
                    if (metaParts.isNotEmpty()) {
                        Text(
                            text = metaParts.joinToString(" · "),
                            fontSize = 11.sp,
                            color = ICostTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isCustom) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = ICostCoralRed.copy(alpha = 0.10f),
                            modifier = Modifier.clickable(onClick = onResetDefault)
                        ) {
                            Text(
                                text = "恢复默认",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostCoralRed,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = ICostCellGray,
                        modifier = Modifier.clickable(onClick = onEdit)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                tint = ICostTextPrimary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "编辑汇率",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostTextPrimary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrencyRateEditorBottomSheet(
    currency: CurrencyCode,
    currentEffectiveRate: Double,
    isCurrentlyCustom: Boolean,
    previewBaseAmount: Double,
    onDismiss: () -> Unit,
    onSave: (rawRateInput: String, requestDismiss: () -> Unit) -> Unit,
    onResetDefault: (requestDismiss: () -> Unit) -> Unit
) {
    var rateInput by remember(currency, currentEffectiveRate) {
        mutableStateOf(CurrencyRateManager.formatRate(currentEffectiveRate))
    }

    val validationResult = remember(rateInput) {
        CurrencyRateManager.validateCustomRateInput(rateInput)
    }
    val validationError = validationResult.exceptionOrNull()?.message
    val parsedValidRate = validationResult.getOrNull()

    ICostSmoothBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ICostBgGray
    ) { requestSheetDismiss ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ICostSheetHeader(
                title = "编辑 ${currency.displayName} 汇率",
                onClose = requestSheetDismiss
            )

            ICostFormCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "系统默认参考汇率：1 ${currency.name} = ¥${CurrencyRateManager.formatRate(currency.defaultRateToCny)} CNY",
                            fontSize = 12.sp,
                            color = ICostTextSecondary
                        )
                        Text(
                            text = "支持 0 ~ 1000 之间的正数（最多保留 6 位小数）",
                            fontSize = 11.sp,
                            color = ICostTextMuted
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = ICostCellGray,
                        modifier = Modifier.clickable {
                            rateInput = CurrencyRateManager.formatRate(currency.defaultRateToCny)
                        }
                    ) {
                        Text(
                            text = "填入默认值",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = ICostBlueAccent,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                ICostFormField(
                    value = rateInput,
                    onValueChange = { raw ->
                        rateInput = raw.take(16)
                    },
                    label = "1 ${currency.name} (${currency.symbol}) 折合人民币 (CNY ¥)",
                    placeholder = CurrencyRateManager.formatRate(currency.defaultRateToCny),
                    singleLine = true,
                    isError = validationError != null,
                    supportingText = validationError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 实时折算预览与历史账单不变性说明卡
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = ICostCardWhite,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "实时折算预览",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextSecondary
                    )
                    if (parsedValidRate != null) {
                        val baseAmt = if (previewBaseAmount > 0.0) previewBaseAmount else 100.0
                        val convertedCny = baseAmt * parsedValidRate
                        val baseStr = if (baseAmt % 1.0 == 0.0) {
                            String.format(Locale.US, "%.0f", baseAmt)
                        } else {
                            String.format(Locale.US, "%.2f", baseAmt)
                        }
                        Text(
                            text = "1 ${currency.name} = ¥${CurrencyRateManager.formatRate(parsedValidRate)} CNY",
                            fontFamily = ICDinFontFamily,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = ICostBlueAccent
                        )
                        Text(
                            text = "$baseStr ${currency.name} ≈ ¥${String.format(Locale.US, "%.2f", convertedCny)} CNY",
                            fontFamily = ICDinFontFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = ICostTextPrimary
                        )
                    } else {
                        Text(
                            text = validationError ?: "请输入大于 0 的有效汇率",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = ICostCoralRed
                        )
                    }
                    Text(
                        text = "注：修改汇率仅对后续新记账单与外币钱包净资产折算生效，已入账历史账单的汇率快照保持不变。",
                        fontSize = 11.sp,
                        color = ICostTextMuted
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isCurrentlyCustom) {
                    Button(
                        onClick = { onResetDefault(requestSheetDismiss) },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ICostCardWhite,
                            contentColor = ICostCoralRed
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("恢复默认汇率", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Button(
                    onClick = {
                        if (parsedValidRate != null) {
                            onSave(rateInput, requestSheetDismiss)
                        }
                    },
                    enabled = parsedValidRate != null,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ICostBlueAccent,
                        disabledContainerColor = Color(0xFFDADCE0),
                        contentColor = Color.White,
                        disabledContentColor = ICostTextMuted
                    ),
                    modifier = Modifier
                        .weight(1.3f)
                        .height(48.dp)
                ) {
                    Text("保存生效汇率", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
