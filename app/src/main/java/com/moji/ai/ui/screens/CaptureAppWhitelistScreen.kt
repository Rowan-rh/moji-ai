package com.moji.ai.ui.screens

import android.content.Context
import android.provider.Telephony
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.ai.CaptureAppGroup
import com.moji.ai.ai.CaptureAppWhitelistEntry
import com.moji.ai.ai.CaptureAppWhitelistManager
import com.moji.ai.ai.WindowPageSignature
import com.moji.ai.data.model.AppearanceConfigManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.moji.ai.ui.components.ICostConfirmDialog
import com.moji.ai.ui.theme.ICDinFontFamily
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
import com.moji.ai.ui.theme.iCostUnifiedSwitchColors

@Composable
fun CaptureAppWhitelistScreen(
    entries: List<CaptureAppWhitelistEntry>,
    blockedWindows: List<WindowPageSignature> = emptyList(),
    recentWindows: List<WindowPageSignature> = emptyList(),
    onToggleChannel: (packageName: String, enableNotification: Boolean?, enableAccessibility: Boolean?) -> Unit,
    onAddCustomApp: (
        rawPackageName: String,
        rawAppName: String,
        enableNotification: Boolean,
        enableAccessibility: Boolean,
        onDone: (Result<CaptureAppWhitelistEntry>) -> Unit
    ) -> Unit,
    onRemoveCustomApp: (packageName: String) -> Unit,
    onBlockWindow: (WindowPageSignature) -> Unit = {},
    onUnblockWindow: (signatureKey: String) -> Unit = {},
    onClearRecentWindows: () -> Unit = {},
    onResetDefaults: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var selectedFilter by rememberSaveable { mutableStateOf("ALL") } // ALL, INSTALLED, ENABLED
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showAddCustomSheet by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var pendingDeleteCustom by remember { mutableStateOf<CaptureAppWhitelistEntry?>(null) }

    val installedPackages = remember(entries, context, resumeTick) {
        detectInstalledPackages(context, entries)
    }

    val (notifEnabledCount, accessEnabledCount) = remember(entries) {
        CaptureAppWhitelistManager.enabledCounts(entries)
    }
    val anyEnabledCount = remember(entries) {
        entries.count { it.enableNotification || it.enableAccessibility }
    }
    val installedCount = remember(entries, installedPackages) {
        entries.count { it.packageName in installedPackages }
    }

    val trimmedQuery = searchQuery.trim()
    val filteredEntries = remember(entries, installedPackages, selectedFilter, trimmedQuery) {
        entries.filter { item ->
            val matchFilter = when (selectedFilter) {
                "INSTALLED" -> item.packageName in installedPackages
                "ENABLED" -> item.enableNotification || item.enableAccessibility
                else -> true
            }
            val matchQuery = trimmedQuery.isEmpty() ||
                item.appName.contains(trimmedQuery, ignoreCase = true) ||
                item.packageName.contains(trimmedQuery, ignoreCase = true)
            matchFilter && matchQuery
        }
    }

    val groupedEntries = remember(filteredEntries) {
        CaptureAppGroup.entries.mapNotNull { group ->
            val list = filteredEntries.filter { it.group == group }
            if (list.isEmpty()) null else group to list
        }
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

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "监听应用白名单",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = ICostTextPrimary
                )
                Text(
                    text = "双通道按应用独立控制",
                    fontSize = 11.sp,
                    color = ICostTextSecondary
                )
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = ICostCardWhite,
                modifier = Modifier.iCostPressScale(
                    pressedScale = 0.94f,
                    onClick = { showResetConfirm = true }
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = ICostBlueAccent,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "默认",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ICostBlueAccent
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 44.dp)
        ) {
            // 1. 顶部总览与防误触说明卡片
            item {
                WhitelistSummaryBannerCard(
                    notifCount = notifEnabledCount,
                    accessCount = accessEnabledCount,
                    onAddCustomClick = { showAddCustomSheet = true }
                )
            }

            // 1.5 窗口级智能防误触门禁与页面结构指纹一键屏蔽卡片
            item {
                WindowSmartGuardSectionCard(
                    blockedWindows = blockedWindows,
                    recentWindows = recentWindows,
                    onBlockWindow = onBlockWindow,
                    onUnblockWindow = onUnblockWindow,
                    onClearRecentWindows = onClearRecentWindows
                )
            }

            // 2. 搜索框 + 三态筛选胶囊
            item {
                WhitelistSearchAndFilterBar(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    selectedFilter = selectedFilter,
                    onSelectFilter = { selectedFilter = it },
                    totalCount = entries.size,
                    installedCount = installedCount,
                    enabledCount = anyEnabledCount
                )
            }

            // 3. 分组应用列表
            if (groupedEntries.isEmpty()) {
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
                                text = "未找到匹配的应用",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostTextPrimary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "可切换筛选条件，或点击上方「添加自定义应用」手动录入包名",
                                fontSize = 12.sp,
                                color = ICostTextSecondary
                            )
                        }
                    }
                }
            } else {
                groupedEntries.forEach { (group, groupItems) ->
                    item(key = "group_${group.name}") {
                        WhitelistGroupSectionCard(
                            group = group,
                            items = groupItems,
                            installedPackages = installedPackages,
                            onToggleChannel = onToggleChannel,
                            onRequestDeleteCustom = { pendingDeleteCustom = it }
                        )
                    }
                }
            }
        }
    }

    if (showAddCustomSheet) {
        AddCustomCaptureAppBottomSheet(
            context = context,
            onDismiss = { showAddCustomSheet = false },
            onSave = { pkg, name, notif, access, requestDismiss, onError ->
                onAddCustomApp(pkg, name, notif, access) { res ->
                    res.fold(
                        onSuccess = { requestDismiss() },
                        onFailure = { err -> onError(err.message ?: "添加失败") }
                    )
                }
            }
        )
    }

    if (showResetConfirm) {
        ICostConfirmDialog(
            title = "恢复默认白名单",
            message = "确定恢复系统推荐的三梯队默认白名单配置吗？\n\n• 微信、支付宝、云闪付、短信及主流银行：通知 + 识屏全开\n• 京东、淘宝、拼多多、美团等：仅开通知，关闭无障碍自动扫屏\n• 抖音、小红书、唯品会：默认全关\n• 手动添加的自定义包名将被移除。",
            confirmText = "恢复默认",
            destructive = false,
            onConfirm = {
                showResetConfirm = false
                onResetDefaults()
            },
            onDismiss = { showResetConfirm = false }
        )
    }

    pendingDeleteCustom?.let { target ->
        ICostConfirmDialog(
            title = "移除自定义应用",
            message = "确定从识账白名单中移除「${target.appName}」（${target.packageName}）吗？",
            confirmText = "移除",
            destructive = true,
            onConfirm = {
                pendingDeleteCustom = null
                onRemoveCustomApp(target.packageName)
            },
            onDismiss = { pendingDeleteCustom = null }
        )
    }
}

@Composable
private fun WhitelistSummaryBannerCard(
    notifCount: Int,
    accessCount: Int,
    onAddCustomClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = ICostCardWhite,
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
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SummaryStatBox(
                    label = "通知栏监听已开",
                    count = notifCount,
                    accentColor = ICostBlueAccent,
                    modifier = Modifier.weight(1f)
                )
                SummaryStatBox(
                    label = "无障碍识屏已开",
                    count = accessCount,
                    accentColor = ICostGreenIncome,
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "• 关闭「识屏」后，默记绝不扫描该应用的页面节点或自动截屏，彻底避免刷视频、逛商品页误记。\n• 控制中心「默记识屏记账」快捷磁贴不受白名单限制，随时可手动点按识屏。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = ICostTextSecondary
            )

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (AppearanceConfigManager.isDarkThemeActive) Color(0xFF1E293B) else Color(0xFFEFF5FF),
                modifier = Modifier
                    .fillMaxWidth()
                    .iCostPressScale(pressedScale = 0.97f, onClick = onAddCustomClick)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 11.dp, horizontal = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = ICostBlueAccent,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "添加自定义应用包名（如地方银行）",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostBlueAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryStatBox(
    label: String,
    count: Int,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = ICostCellGray,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = count.toString(),
                    fontFamily = ICDinFontFamily,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "个应用",
                    fontSize = 11.sp,
                    color = ICostTextSecondary,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = ICostTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun WhitelistSearchAndFilterBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedFilter: String,
    onSelectFilter: (String) -> Unit,
    totalCount: Int,
    installedCount: Int,
    enabledCount: Int
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    tint = ICostTextMuted,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "搜索应用名称或包名（如 招商、taobao）",
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
                        cursorBrush = SolidColor(ICostBlueAccent),
                        textStyle = TextStyle(
                            fontSize = 14.sp,
                            color = ICostTextPrimary,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (searchQuery.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(ICostCellGray)
                            .clickable { onSearchQueryChange("") },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "清空搜索",
                            tint = ICostTextSecondary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }

        val tabs = listOf(
            "ALL" to "全部 ($totalCount)",
            "INSTALLED" to "本机已装 ($installedCount)",
            "ENABLED" to "已启用 ($enabledCount)"
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tabs.forEach { (key, title) ->
                val selected = selectedFilter == key
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (selected) ICostTextPrimary else ICostCardWhite,
                    modifier = Modifier
                        .weight(1f)
                        .iCostPressScale(pressedScale = 0.95f) {
                            onSelectFilter(key)
                        }
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) ICostCardWhite else ICostTextSecondary,
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
private fun WhitelistGroupSectionCard(
    group: CaptureAppGroup,
    items: List<CaptureAppWhitelistEntry>,
    installedPackages: Set<String>,
    onToggleChannel: (packageName: String, enableNotification: Boolean?, enableAccessibility: Boolean?) -> Unit,
    onRequestDeleteCustom: (CaptureAppWhitelistEntry) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                text = "${group.title} · ${items.size}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = ICostTextPrimary
            )
            Text(
                text = group.subtitle,
                fontSize = 11.sp,
                color = ICostTextSecondary
            )
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = ICostCardWhite,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                items.forEachIndexed { index, entry ->
                    val isInstalled = entry.packageName in installedPackages
                    WhitelistAppItemRow(
                        entry = entry,
                        isInstalled = isInstalled,
                        onToggleNotification = {
                            onToggleChannel(entry.packageName, !entry.enableNotification, null)
                        },
                        onToggleAccessibility = {
                            onToggleChannel(entry.packageName, null, !entry.enableAccessibility)
                        },
                        onDeleteCustom = if (entry.isCustom) {
                            { onRequestDeleteCustom(entry) }
                        } else null
                    )
                    if (index < items.lastIndex) {
                        HorizontalDivider(color = ICostDivider)
                    }
                }
            }
        }
    }
}

@Composable
private fun WhitelistAppItemRow(
    entry: CaptureAppWhitelistEntry,
    isInstalled: Boolean,
    onToggleNotification: () -> Unit,
    onToggleAccessibility: () -> Unit,
    onDeleteCustom: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = entry.appName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = ICostTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isInstalled) {
                    SmallTagBadge(
                        text = "已安装",
                        textColor = ICostGreenIncome,
                        bgColor = if (AppearanceConfigManager.isDarkThemeActive) {
                            Color(0xFF132E24)
                        } else {
                            Color(0xFFE9F9F1)
                        }
                    )
                }
                if (entry.isCustom) {
                    SmallTagBadge(
                        text = "自定义",
                        textColor = ICostBlueAccent,
                        bgColor = if (AppearanceConfigManager.isDarkThemeActive) {
                            Color(0xFF1A2740)
                        } else {
                            Color(0xFFEFF5FF)
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = entry.packageName,
                fontSize = 11.sp,
                color = ICostTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ChannelTogglePill(
                label = "通知",
                enabled = entry.enableNotification,
                activeColor = ICostBlueAccent,
                onClick = onToggleNotification
            )
            ChannelTogglePill(
                label = "识屏",
                enabled = entry.enableAccessibility,
                activeColor = ICostGreenIncome,
                onClick = onToggleAccessibility
            )
            if (onDeleteCustom != null) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .iCostPressScale(pressedScale = 0.9f, onClick = onDeleteCustom),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "删除自定义应用",
                        tint = ICostCoralRed,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallTagBadge(
    text: String,
    textColor: Color,
    bgColor: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 5.dp, vertical = 1.5.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun ChannelTogglePill(
    label: String,
    enabled: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    val isDark = AppearanceConfigManager.isDarkThemeActive
    val bgColor = when {
        enabled && isDark -> activeColor.copy(alpha = 0.22f)
        enabled -> activeColor.copy(alpha = 0.12f)
        else -> ICostCellGray
    }
    val borderColor = if (enabled) activeColor.copy(alpha = 0.55f) else Color.Transparent
    val contentColor = if (enabled) activeColor else ICostTextMuted

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .iCostPressScale(pressedScale = 0.93f, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        if (enabled) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(12.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(ICostTextMuted.copy(alpha = 0.5f))
            )
        }
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (enabled) FontWeight.Bold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun AddCustomCaptureAppBottomSheet(
    context: Context,
    onDismiss: () -> Unit,
    onSave: (
        rawPackageName: String,
        rawAppName: String,
        enableNotification: Boolean,
        enableAccessibility: Boolean,
        requestDismiss: () -> Unit,
        onError: (String) -> Unit
    ) -> Unit
) {
    var packageNameInput by rememberSaveable { mutableStateOf("") }
    var appNameInput by rememberSaveable { mutableStateOf("") }
    var enableNotif by rememberSaveable { mutableStateOf(true) }
    var enableAccess by rememberSaveable { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    ICostSmoothBottomSheet(onDismissRequest = onDismiss) { requestDismiss ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp)
        ) {
            ICostSheetHeader(
                title = "添加自定义应用",
                subtitle = "支持地方城商行、农商行或专用支付 App",
                onClose = requestDismiss
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ICostFormCard {
                    ICostFormField(
                        value = packageNameInput,
                        onValueChange = {
                            packageNameInput = it
                            errorMessage = null
                        },
                        label = "Android 应用包名（必填）",
                        placeholder = "如 com.bankofbeijing.mobilebanking",
                        supportingText = errorMessage ?: "可在手机「应用详情 › 应用信息」中查看包名",
                        isError = errorMessage != null,
                        singleLine = true
                    )

                    ICostFormField(
                        value = appNameInput,
                        onValueChange = { appNameInput = it },
                        label = "显示名称（选填）",
                        placeholder = "如 北京银行（留空自动生成）",
                        singleLine = true
                    )
                }

                ICostFormCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "通知栏自动识账",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostTextPrimary
                            )
                            Text(
                                text = "监听该应用推送的扣款/入账通知",
                                fontSize = 12.sp,
                                color = ICostTextSecondary
                            )
                        }
                        Switch(
                            checked = enableNotif,
                            onCheckedChange = { enableNotif = it },
                            colors = iCostUnifiedSwitchColors()
                        )
                    }

                    HorizontalDivider(color = ICostDivider)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "支付页无障碍识屏",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostTextPrimary
                            )
                            Text(
                                text = "自动识别该应用的账单详情页与支付成功页",
                                fontSize = 12.sp,
                                color = ICostTextSecondary
                            )
                        }
                        Switch(
                            checked = enableAccess,
                            onCheckedChange = { enableAccess = it },
                            colors = iCostUnifiedSwitchColors()
                        )
                    }
                }

                Button(
                    onClick = {
                        val resolvedLabel = appNameInput.trim().ifEmpty {
                            resolveInstalledLabelIfVisible(context, packageNameInput.trim())
                        }
                        onSave(
                            packageNameInput,
                            resolvedLabel,
                            enableNotif,
                            enableAccess,
                            requestDismiss
                        ) { err ->
                            errorMessage = err
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ICostBlueAccent,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "保存并加入白名单",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun detectInstalledPackages(
    context: Context,
    entries: List<CaptureAppWhitelistEntry>
): Set<String> {
    val pm = context.packageManager
    val result = HashSet<String>()
    val defaultSms = runCatching {
        Telephony.Sms.getDefaultSmsPackage(context.applicationContext).orEmpty()
    }.getOrDefault("")
    for (entry in entries) {
        val pkg = entry.packageName
        val installed = if (pkg == CaptureAppWhitelistManager.SMS_REPRESENTATIVE_PACKAGE) {
            defaultSms.isNotBlank() || CaptureAppWhitelistManager.KNOWN_OEM_SMS_PACKAGES.any { cand ->
                runCatching { pm.getPackageInfo(cand, 0) }.isSuccess
            }
        } else {
            runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        }
        if (installed) {
            result.add(pkg)
        }
    }
    return result
}

private fun resolveInstalledLabelIfVisible(context: Context, pkg: String): String {
    if (pkg.isBlank()) return ""
    return runCatching {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(appInfo)?.toString()?.trim().orEmpty()
    }.getOrDefault("")
}

@Composable
private fun WindowSmartGuardSectionCard(
    blockedWindows: List<WindowPageSignature>,
    recentWindows: List<WindowPageSignature>,
    onBlockWindow: (WindowPageSignature) -> Unit,
    onUnblockWindow: (signatureKey: String) -> Unit,
    onClearRecentWindows: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val blockedKeys = remember(blockedWindows) {
        blockedWindows.mapTo(HashSet(blockedWindows.size)) { it.signatureKey }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ICostCardWhite,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { expanded = !expanded }
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "窗口智能防误触",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = ICostTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ICostGreenIncome.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = "5 重门禁生效中",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ICostGreenIncome,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = buildString {
                            append("自动拦截聊天/朋友圈/相册/商品详情/带输入框与关闭态窗口")
                            if (blockedWindows.isNotEmpty()) {
                                append(" · 已手动屏蔽 ${blockedWindows.size} 类页面")
                            }
                        },
                        fontSize = 11.sp,
                        color = ICostTextSecondary,
                        lineHeight = 15.sp
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ICostCellGray
                ) {
                    Text(
                        text = if (expanded) "收起" else if (blockedWindows.isNotEmpty() || recentWindows.isNotEmpty()) {
                            "管理 (${blockedWindows.size}/${recentWindows.size})"
                        } else {
                            "详情"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ICostBlueAccent,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            if (expanded || blockedWindows.isNotEmpty()) {
                HorizontalDivider(color = ICostDivider, thickness = 0.5.dp)

                if (blockedWindows.isNotEmpty()) {
                    Text(
                        text = "已屏蔽的页面结构（同骨架页面不再自动识账）",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ICostTextSecondary
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        blockedWindows.forEach { sig ->
                            WindowSignatureRowItem(
                                sig = sig,
                                isBlocked = true,
                                onActionClick = { onUnblockWindow(sig.signatureKey) }
                            )
                        }
                    }
                }

                if (expanded) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "最近自动识屏页面（误触可一键屏蔽结构）",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ICostTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (recentWindows.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "清空记录",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = ICostTextMuted,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.clickable { onClearRecentWindows() }
                            )
                        }
                    }

                    if (recentWindows.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = ICostCellGray.copy(alpha = 0.65f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "暂无自动识屏记录。后续无障碍自动捕获账单窗口后，将在此展示窗口类名与 5 层视图骨架哈希，支持一键精准拉黑误触页面而不影响同应用真实支付页。",
                                fontSize = 11.sp,
                                color = ICostTextSecondary,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            recentWindows.forEach { sig ->
                                val alreadyBlocked = sig.signatureKey in blockedKeys
                                WindowSignatureRowItem(
                                    sig = sig,
                                    isBlocked = alreadyBlocked,
                                    isRecentRow = true,
                                    onActionClick = {
                                        if (alreadyBlocked) {
                                            onUnblockWindow(sig.signatureKey)
                                        } else {
                                            onBlockWindow(sig)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WindowSignatureRowItem(
    sig: WindowPageSignature,
    isBlocked: Boolean,
    isRecentRow: Boolean = false,
    onActionClick: () -> Unit
) {
    val timeText = remember(sig.createdAtMillis) {
        if (sig.createdAtMillis > 0L) {
            SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(sig.createdAtMillis))
        } else {
            ""
        }
    }
    val shortFp = sig.structureFingerprint.take(8)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = ICostCellGray.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${sig.appName.ifBlank { sig.packageName }} · ${sig.shortActivityName}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ICostTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append("骨架 #").append(shortFp)
                        if (timeText.isNotEmpty()) append(" · ").append(timeText)
                        if (sig.sampleSummary.isNotBlank()) append(" · ").append(sig.sampleSummary)
                    },
                    fontSize = 11.sp,
                    color = ICostTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            val btnBg = when {
                isBlocked && !isRecentRow -> ICostBlueAccent.copy(alpha = 0.12f)
                isBlocked -> ICostTextMuted.copy(alpha = 0.14f)
                else -> ICostCoralRed.copy(alpha = 0.12f)
            }
            val btnFg = when {
                isBlocked && !isRecentRow -> ICostBlueAccent
                isBlocked -> ICostTextSecondary
                else -> ICostCoralRed
            }
            val btnLabel = when {
                isBlocked && !isRecentRow -> "解除"
                isBlocked -> "已屏蔽"
                else -> "屏蔽此页面"
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = btnBg,
                modifier = Modifier.iCostPressScale(pressedScale = 0.94f, onClick = onActionClick)
            ) {
                Text(
                    text = btnLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = btnFg,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}
