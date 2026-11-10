package com.moji.ai.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.data.model.AmountColorScheme
import com.moji.ai.data.model.AppFontScalePreset
import com.moji.ai.data.model.AppThemeMode
import com.moji.ai.data.model.AppearanceConfigManager
import com.moji.ai.data.model.AppearanceSettingsConfig
import com.moji.ai.data.model.TransactionType
import com.moji.ai.ui.theme.ICDinFontFamily
import com.moji.ai.ui.theme.ICostAssetImage
import com.moji.ai.ui.theme.ICostBgGray
import com.moji.ai.ui.theme.ICostBlueAccent
import com.moji.ai.ui.theme.ICostCardWhite
import com.moji.ai.ui.theme.ICostCategoryBadge
import com.moji.ai.ui.theme.ICostCellGray
import com.moji.ai.ui.theme.ICostCircleIconButton
import com.moji.ai.ui.theme.ICostDivider
import com.moji.ai.ui.theme.ICostExpenseColor
import com.moji.ai.ui.theme.ICostIncomeColor
import com.moji.ai.ui.theme.ICostPillGray
import com.moji.ai.ui.theme.ICostTextMuted
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.iCostPressScale
import com.moji.ai.ui.theme.iCostUnifiedSwitchColors

@Composable
fun AppearanceManagementScreen(
    config: AppearanceSettingsConfig,
    onSelectThemeMode: (AppThemeMode) -> Unit,
    onSelectAmountColorScheme: (AmountColorScheme) -> Unit,
    onSelectFontScalePreset: (AppFontScalePreset) -> Unit,
    onToggleShowThousandSeparator: (Boolean) -> Unit,
    onToggleHideTrailingZeroDecimals: (Boolean) -> Unit,
    onResetDefaults: () -> Unit,
    onBack: () -> Unit,
    listState: LazyListState = rememberLazyListState()
) {
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ICostBgGray)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 顶部导航栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 10.dp, start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ICostCircleIconButton(onClick = onBack, size = 40.dp) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "返回",
                    tint = ICostTextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "外观显示",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = ICostTextPrimary
                )
                Text(
                    text = config.settingsTrailingText,
                    fontSize = 11.sp,
                    color = ICostTextSecondary
                )
            }

            Surface(
                shape = RoundedCornerShape(999.dp),
                color = ICostCardWhite,
                shadowElevation = 1.dp,
                modifier = Modifier.iCostPressScale(onClick = onResetDefaults)
            ) {
                Text(
                    text = "恢复默认",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ICostBlueAccent,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                AppearanceLivePreviewCard(config = config)
            }
            item {
                ThemeModeSelectorCard(
                    selectedMode = config.themeMode,
                    onSelectMode = onSelectThemeMode
                )
            }
            item {
                AmountColorSchemeSelectorCard(
                    selectedScheme = config.amountColorScheme,
                    onSelectScheme = onSelectAmountColorScheme
                )
            }
            item {
                FontScaleSelectorCard(
                    selectedPreset = config.fontScalePreset,
                    onSelectPreset = onSelectFontScalePreset
                )
            }
            item {
                AmountFormatSwitchCard(
                    showThousandSeparator = config.showThousandSeparator,
                    hideTrailingZeroDecimals = config.hideTrailingZeroDecimals,
                    onToggleThousandSeparator = onToggleShowThousandSeparator,
                    onToggleHideTrailingZero = onToggleHideTrailingZeroDecimals
                )
            }
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun AppearanceLivePreviewCard(config: AppearanceSettingsConfig) {
    val expenseFormatted = AppearanceConfigManager.formatDisplayAmount(1280.00, config)
    val incomeFormatted = AppearanceConfigManager.formatDisplayAmount(8500.00, config)
    val sampleCentsFormatted = AppearanceConfigManager.formatDisplayAmount(1280.50, config)

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = ICostCardWhite,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ICostAssetImage(
                        assetFile = "setting_appearance.webp",
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "实时效果预览",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextPrimary
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(ICostPillGray)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${config.themeMode.label} · ${config.fontScalePreset.label}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = ICostTextSecondary
                    )
                }
            }

            // 今日收支概览小横条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(ICostCellGray)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "今日收支概览",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = ICostTextSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "支 ¥$expenseFormatted",
                        fontFamily = ICDinFontFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostExpenseColor
                    )
                    Text(
                        text = "收 ¥$incomeFormatted",
                        fontFamily = ICDinFontFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostIncomeColor
                    )
                }
            }

            // 示例 1：餐饮支出
            PreviewLedgerRow(
                category = "餐饮",
                type = TransactionType.EXPENSE,
                title = "餐饮 · 招牌牛肉面",
                subtitle = "微信零钱 · 12:18 · 含角分示例 ¥$sampleCentsFormatted",
                amountText = "-¥$expenseFormatted",
                amountColor = ICostExpenseColor
            )

            HorizontalDivider(thickness = 0.5.dp, color = ICostDivider)

            // 示例 2：工资收入
            PreviewLedgerRow(
                category = "工资",
                type = TransactionType.INCOME,
                title = "工资 · 月度薪资",
                subtitle = "招商银行 · 09:30 · 自动入账",
                amountText = "+¥$incomeFormatted",
                amountColor = ICostIncomeColor
            )
        }
    }
}

@Composable
private fun PreviewLedgerRow(
    category: String,
    type: TransactionType,
    title: String,
    subtitle: String,
    amountText: String,
    amountColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            ICostCategoryBadge(
                category = category,
                type = type,
                size = 38.dp,
                badgeColor = amountColor
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ICostTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = ICostTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = amountText,
            fontFamily = ICDinFontFamily,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = amountColor
        )
    }
}

@Composable
private fun ThemeModeSelectorCard(
    selectedMode: AppThemeMode,
    onSelectMode: (AppThemeMode) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = ICostCardWhite,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column {
                Text(
                    text = "主题外观",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = ICostTextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "切换全应用深浅色外观，0 重启即时生效",
                    fontSize = 11.sp,
                    color = ICostTextSecondary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppThemeMode.entries.forEach { mode ->
                    val isSelected = selectedMode == mode
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(ICostCellGray)
                            .border(
                                width = if (isSelected) 1.8.dp else 0.8.dp,
                                color = if (isSelected) ICostBlueAccent else ICostDivider,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable { onSelectMode(mode) }
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 迷你色板预览块
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(0.6.dp, ICostDivider, RoundedCornerShape(10.dp))
                        ) {
                            when (mode) {
                                AppThemeMode.LIGHT -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color(0xFFF6F6F6))
                                            .padding(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color.White)
                                        )
                                    }
                                }
                                AppThemeMode.DARK -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color(0xFF111318))
                                            .padding(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFF1C1F26))
                                        )
                                    }
                                }
                                AppThemeMode.FOLLOW_SYSTEM -> {
                                    Row(modifier = Modifier.fillMaxSize()) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .background(Color(0xFFF6F6F6))
                                                .padding(5.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(Color.White)
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .background(Color(0xFF111318))
                                                .padding(5.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(Color(0xFF1C1F26))
                                            )
                                        }
                                    }
                                }
                            }
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(ICostBlueAccent),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                            }
                        }

                        Text(
                            text = mode.label,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) ICostBlueAccent else ICostTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = mode.subtitle,
                            fontSize = 10.sp,
                            color = ICostTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AmountColorSchemeSelectorCard(
    selectedScheme: AmountColorScheme,
    onSelectScheme: (AmountColorScheme) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = ICostCardWhite,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column {
                Text(
                    text = "收支颜色方案",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = ICostTextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "联动账单列表、统计图表与记账计算器（删除与报错仍保持警示红）",
                    fontSize = 11.sp,
                    color = ICostTextSecondary
                )
            }

            AmountColorScheme.entries.forEach { scheme ->
                val isSelected = selectedScheme == scheme
                val expenseColor = if (AppearanceConfigManager.isDarkThemeActive && scheme == AmountColorScheme.MONOCHROME) {
                    Color(0xFF94A3B8)
                } else {
                    Color(scheme.expenseHex)
                }
                val incomeColor = Color(scheme.incomeHex)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ICostCellGray)
                        .border(
                            width = if (isSelected) 1.5.dp else 0.6.dp,
                            color = if (isSelected) ICostBlueAccent else ICostDivider,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onSelectScheme(scheme) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = scheme.label,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSelected) ICostBlueAccent else ICostTextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = scheme.subtitle,
                            fontSize = 11.sp,
                            color = ICostTextSecondary
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ColorBadgePill(label = "支", color = expenseColor)
                        ColorBadgePill(label = "收", color = incomeColor)
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(ICostBlueAccent),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "已选中",
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .border(1.2.dp, ICostTextMuted, CircleShape)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorBadgePill(label: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun FontScaleSelectorCard(
    selectedPreset: AppFontScalePreset,
    onSelectPreset: (AppFontScalePreset) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = ICostCardWhite,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column {
                Text(
                    text = "字号大小",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = ICostTextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "全局字号比例缩放，安全范围不破坏卡片布局",
                    fontSize = 11.sp,
                    color = ICostTextSecondary
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(ICostCellGray)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AppFontScalePreset.entries.forEach { preset ->
                    val isSelected = selectedPreset == preset
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (isSelected) ICostCardWhite else Color.Transparent)
                            .then(
                                if (isSelected) {
                                    Modifier.border(1.2.dp, ICostBlueAccent, RoundedCornerShape(11.dp))
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { onSelectPreset(preset) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = preset.label,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) ICostBlueAccent else ICostTextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = preset.percentLabel,
                                fontFamily = ICDinFontFamily,
                                fontSize = 11.sp,
                                color = if (isSelected) ICostBlueAccent else ICostTextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AmountFormatSwitchCard(
    showThousandSeparator: Boolean,
    hideTrailingZeroDecimals: Boolean,
    onToggleThousandSeparator: (Boolean) -> Unit,
    onToggleHideTrailingZero: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = ICostCardWhite,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "金额显示格式",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = ICostTextPrimary
            )

            FormatSwitchRow(
                title = "显示千分位分隔符",
                subtitle = "开启后显示 ¥1,280.50，关闭显示 ¥1280.50",
                checked = showThousandSeparator,
                onCheckedChange = onToggleThousandSeparator
            )

            HorizontalDivider(thickness = 0.5.dp, color = ICostDivider)

            FormatSwitchRow(
                title = "整数金额隐藏 .00 小数尾缀",
                subtitle = "开启后整数金额显示 ¥1,280，有角分时仍显示 ¥1,280.50",
                checked = hideTrailingZeroDecimals,
                onCheckedChange = onToggleHideTrailingZero
            )
        }
    }
}

@Composable
private fun FormatSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = ICostTextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = ICostTextSecondary
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = iCostUnifiedSwitchColors()
        )
    }
}
