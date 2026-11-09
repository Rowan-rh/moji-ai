package com.moji.ai.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.moji.ai.data.model.ReminderConfigManager
import com.moji.ai.data.model.ReminderSettingsConfig
import com.moji.ai.data.model.ReminderSlotItem
import com.moji.ai.ui.theme.ICDinFontFamily
import com.moji.ai.ui.theme.ICostAssetImage
import com.moji.ai.ui.theme.ICostBgGray
import com.moji.ai.ui.theme.ICostBlueAccent
import com.moji.ai.ui.theme.ICostCardWhite
import com.moji.ai.ui.theme.ICostCircleIconButton
import com.moji.ai.ui.theme.ICostCoralRed
import com.moji.ai.ui.theme.ICostDivider
import com.moji.ai.ui.theme.ICostFormCard
import com.moji.ai.ui.theme.ICostFormField
import com.moji.ai.ui.theme.ICostSheetHeader
import com.moji.ai.ui.theme.ICostSmoothBottomSheet
import com.moji.ai.ui.theme.ICostTextMuted
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.iCostPressScale
import com.moji.ai.ui.theme.iCostUnifiedSwitchColors

private fun isPostNotificationGranted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
    }
    return NotificationManagerCompat.from(context).areNotificationsEnabled()
}

private fun openAppNotificationSettings(context: Context) {
    runCatching {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        runCatching {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }
}

@Composable
fun ReminderManagementScreen(
    config: ReminderSettingsConfig,
    onBack: () -> Unit,
    onSetMasterEnabled: (Boolean) -> Unit,
    onSetSkipIfRecordedToday: (Boolean) -> Unit,
    onUpsertSlot: (ReminderSlotItem, (Result<ReminderSettingsConfig>) -> Unit) -> Unit,
    onToggleSlotEnabled: (String, Boolean) -> Unit,
    onDeleteSlot: (String) -> Unit,
    onSendTestReminder: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val hasNotificationPermission = remember(resumeTick) { isPostNotificationGranted(context) }

    var showSlotEditor by rememberSaveable { mutableStateOf(false) }
    var editingSlotId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingSlot = remember(editingSlotId, config.items) {
        editingSlotId?.let { id -> config.items.firstOrNull { it.id == id } }
    }

    BackHandler(enabled = !showSlotEditor, onBack = onBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ICostBgGray)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        ReminderMainContent(
            config = config,
            hasNotificationPermission = hasNotificationPermission,
            onBack = onBack,
            onOpenAddSlot = {
                editingSlotId = null
                showSlotEditor = true
            },
            onOpenEditSlot = { slot ->
                editingSlotId = slot.id
                showSlotEditor = true
            },
            onOpenNotificationSettings = { openAppNotificationSettings(context) },
            onSetMasterEnabled = onSetMasterEnabled,
            onSetSkipIfRecordedToday = onSetSkipIfRecordedToday,
            onToggleSlotEnabled = onToggleSlotEnabled,
            onSendTestReminder = onSendTestReminder
        )
    }

    if (showSlotEditor) {
        ReminderSlotEditorSheet(
            initialSlot = editingSlot,
            onDismiss = {
                showSlotEditor = false
                editingSlotId = null
            },
            onSave = { slot, onDone ->
                onUpsertSlot(slot) { res ->
                    onDone(res)
                    if (res.isSuccess) {
                        showSlotEditor = false
                        editingSlotId = null
                    }
                }
            },
            onDelete = editingSlot?.let { target ->
                {
                    onDeleteSlot(target.id)
                    showSlotEditor = false
                    editingSlotId = null
                }
            }
        )
    }
}

@Composable
private fun ReminderMainContent(
    config: ReminderSettingsConfig,
    hasNotificationPermission: Boolean,
    onBack: () -> Unit,
    onOpenAddSlot: () -> Unit,
    onOpenEditSlot: (ReminderSlotItem) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onSetMasterEnabled: (Boolean) -> Unit,
    onSetSkipIfRecordedToday: (Boolean) -> Unit,
    onToggleSlotEnabled: (String, Boolean) -> Unit,
    onSendTestReminder: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
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
                color = ICostCardWhite,
                modifier = Modifier
                    .semantics { contentDescription = "新增提醒" }
                    .iCostPressScale(pressedScale = 0.94f, onClick = onOpenAddSlot)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = ICostBlueAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "新增",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostBlueAccent
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "定时提醒",
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            color = ICostTextPrimary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "自定义多时段记账提醒，开启今日已记账免打扰，点击通知直达记账面板",
            fontSize = 13.sp,
            color = ICostTextSecondary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            if (!hasNotificationPermission) {
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = Color(0xFFFFF4E5),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = Color(0xFFD97706),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "系统通知权限未开启，定时提醒可能无法送达",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF92400E)
                                )
                                Text(
                                    text = "请开启默记的系统通知权限以确保按时收到记账提醒",
                                    fontSize = 12.sp,
                                    color = Color(0xFFB45309)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFFD97706),
                                modifier = Modifier.clickable(onClick = onOpenNotificationSettings)
                            ) {
                                Text(
                                    text = "去开启",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            item {
                ReminderMasterCard(
                    config = config,
                    onSetMasterEnabled = onSetMasterEnabled,
                    onSetSkipIfRecordedToday = onSetSkipIfRecordedToday
                )
            }

            item {
                ReminderSlotsCard(
                    config = config,
                    onOpenAddSlot = onOpenAddSlot,
                    onOpenEditSlot = onOpenEditSlot,
                    onToggleSlotEnabled = onToggleSlotEnabled
                )
            }

            item {
                ReminderTestAndGuideCard(onSendTestReminder = onSendTestReminder)
            }
        }
    }
}

@Composable
private fun ReminderMasterCard(
    config: ReminderSettingsConfig,
    onSetMasterEnabled: (Boolean) -> Unit,
    onSetSkipIfRecordedToday: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ICostCardWhite,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (config.masterEnabled) {
                        ICostBlueAccent.copy(alpha = 0.12f)
                    } else {
                        Color(0xFFF2F3F7)
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        ICostAssetImage(
                            assetFile = "setting_time.webp",
                            tint = if (config.masterEnabled) ICostBlueAccent else ICostTextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "开启定时记账提醒",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextPrimary
                    )
                    Text(
                        text = if (config.masterEnabled) {
                            "当前状态：${config.settingsTrailingText}"
                        } else {
                            "按设定时间发送系统通知，点击直达记账面板"
                        },
                        fontSize = 12.sp,
                        color = if (config.masterEnabled) ICostBlueAccent else ICostTextSecondary
                    )
                }
                Switch(
                    checked = config.masterEnabled,
                    onCheckedChange = onSetMasterEnabled,
                    colors = iCostUnifiedSwitchColors(),
                    modifier = Modifier.semantics { contentDescription = "开启定时记账提醒开关" }
                )
            }

            HorizontalDivider(
                color = ICostDivider,
                thickness = 0.6.dp,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "今日已记账则免打扰",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ICostTextPrimary
                    )
                    Text(
                        text = "当天已有记账记录时，自动跳过当晚提醒不打扰",
                        fontSize = 12.sp,
                        color = ICostTextSecondary
                    )
                }
                Switch(
                    checked = config.skipIfRecordedToday,
                    onCheckedChange = onSetSkipIfRecordedToday,
                    colors = iCostUnifiedSwitchColors(),
                    modifier = Modifier.semantics { contentDescription = "今日已记账则免打扰开关" }
                )
            }
        }
    }
}

@Composable
private fun ReminderSlotsCard(
    config: ReminderSettingsConfig,
    onOpenAddSlot: () -> Unit,
    onOpenEditSlot: (ReminderSlotItem) -> Unit,
    onToggleSlotEnabled: (String, Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "提醒时段（${config.items.size}）",
                fontSize = 13.sp,
                color = ICostTextSecondary
            )
            Text(
                text = "点击时段可编辑或删除",
                fontSize = 12.sp,
                color = ICostTextMuted
            )
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = ICostCardWhite,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (config.items.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "暂无提醒时段",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostTextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "点击右上角「+ 新增」添加你的第一个记账提醒时段",
                        fontSize = 12.sp,
                        color = ICostTextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = onOpenAddSlot, shape = RoundedCornerShape(14.dp)) {
                        Text("+ 新增提醒时段", color = ICostBlueAccent, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    config.items.forEachIndexed { idx, slot ->
                        val timeText = ReminderConfigManager.formatTimeHm(slot.hour, slot.minute)
                        val repeatLabel = ReminderConfigManager.formatRepeatDaysLabel(slot.daysOfWeek)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenEditSlot(slot) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = timeText,
                                        fontFamily = ICDinFontFamily,
                                        fontSize = 26.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (slot.enabled && config.masterEnabled) ICostTextPrimary else ICostTextMuted
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (slot.enabled) ICostBlueAccent.copy(alpha = 0.10f) else Color(0xFFF2F3F7)
                                    ) {
                                        Text(
                                            text = "${slot.title} · $repeatLabel",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (slot.enabled) ICostBlueAccent else ICostTextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = slot.message,
                                    fontSize = 12.sp,
                                    color = ICostTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Switch(
                                checked = slot.enabled,
                                onCheckedChange = { enabled -> onToggleSlotEnabled(slot.id, enabled) },
                                colors = iCostUnifiedSwitchColors(),
                                modifier = Modifier.semantics { contentDescription = "${slot.title}开关" }
                            )
                        }
                        if (idx < config.items.lastIndex) {
                            HorizontalDivider(
                                color = ICostDivider,
                                thickness = 0.6.dp,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReminderTestAndGuideCard(onSendTestReminder: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ICostCardWhite,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = ICostBlueAccent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "即时验证与秒记闭环",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = ICostTextPrimary
                )
            }
            Text(
                text = "点击下方按钮可立即向系统通知栏发送一条真实记账提醒；点击通知栏中的提醒卡片，将自动返回账本首页并拉起记账计算器面板。",
                fontSize = 12.sp,
                color = ICostTextSecondary,
                lineHeight = 18.sp
            )
            Button(
                onClick = onSendTestReminder,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ICostBlueAccent.copy(alpha = 0.12f),
                    contentColor = ICostBlueAccent
                ),
                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .semantics { contentDescription = "立即发送测试提醒" }
            ) {
                Text(
                    text = "立即发送测试提醒",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private data class TimePresetChip(val hour: Int, val minute: Int, val label: String)

private val TIME_PRESETS = listOf(
    TimePresetChip(12, 30, "12:30 午间"),
    TimePresetChip(18, 30, "18:30 晚餐"),
    TimePresetChip(21, 0, "21:00 睡前"),
    TimePresetChip(22, 0, "22:00 夜宵")
)

private val WEEKDAY_ITEMS = listOf(
    1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReminderSlotEditorSheet(
    initialSlot: ReminderSlotItem?,
    onDismiss: () -> Unit,
    onSave: (ReminderSlotItem, (Result<ReminderSettingsConfig>) -> Unit) -> Unit,
    onDelete: (() -> Unit)?
) {
    var hour by rememberSaveable(initialSlot?.id) { mutableIntStateOf(initialSlot?.hour ?: 21) }
    var minute by rememberSaveable(initialSlot?.id) { mutableIntStateOf(initialSlot?.minute ?: 0) }
    var selectedDaysList by rememberSaveable(initialSlot?.id) {
        mutableStateOf((initialSlot?.daysOfWeek ?: setOf(1, 2, 3, 4, 5, 6, 7)).sorted())
    }
    var title by rememberSaveable(initialSlot?.id) {
        mutableStateOf(initialSlot?.title ?: ReminderConfigManager.DEFAULT_REMINDER_TITLE)
    }
    var message by rememberSaveable(initialSlot?.id) {
        mutableStateOf(initialSlot?.message ?: ReminderConfigManager.DEFAULT_REMINDER_MESSAGE)
    }
    var submitError by remember { mutableStateOf<String?>(null) }

    val selectedDays = selectedDaysList.toSet()
    val cleanTitle = title.trim()
    val cleanMessage = message.trim()
    val titleError = when {
        cleanTitle.isEmpty() -> "提醒标题不能为空"
        cleanTitle.length > ReminderConfigManager.MAX_TITLE_LENGTH ->
            "提醒标题不能超过 ${ReminderConfigManager.MAX_TITLE_LENGTH} 个字（当前 ${cleanTitle.length} 字）"
        else -> null
    }
    val messageError = when {
        cleanMessage.isEmpty() -> "提醒内容不能为空"
        cleanMessage.length > ReminderConfigManager.MAX_MESSAGE_LENGTH ->
            "提醒内容不能超过 ${ReminderConfigManager.MAX_MESSAGE_LENGTH} 个字（当前 ${cleanMessage.length} 字）"
        else -> null
    }
    val daysError = if (selectedDays.isEmpty()) "请至少选择 1 天重复周期" else null
    val canSave = titleError == null && messageError == null && daysError == null

    ICostSmoothBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ICostBgGray,
        showDragHandle = false
    ) { requestDismiss ->
        ReminderSlotEditorBody(
            isEditing = initialSlot != null,
            hour = hour,
            minute = minute,
            selectedDays = selectedDays,
            title = title,
            message = message,
            titleError = titleError,
            messageError = messageError,
            daysError = daysError,
            submitError = submitError,
            canSave = canSave,
            onChangeTime = { h, m ->
                hour = (h + 24) % 24
                minute = (m + 60) % 60
                submitError = null
            },
            onChangeDays = { newDays ->
                selectedDaysList = newDays.sorted()
                submitError = null
            },
            onChangeTitle = {
                title = it
                submitError = null
            },
            onChangeMessage = {
                message = it
                submitError = null
            },
            onClose = requestDismiss,
            onDelete = onDelete,
            onConfirmSave = {
                val item = ReminderSlotItem(
                    id = initialSlot?.id ?: "slot_${hour * 60 + minute}_${System.currentTimeMillis()}",
                    title = cleanTitle,
                    message = cleanMessage,
                    hour = hour,
                    minute = minute,
                    daysOfWeek = selectedDays,
                    enabled = initialSlot?.enabled ?: true
                )
                onSave(item) { res ->
                    res.onFailure { err -> submitError = err.message ?: "保存失败" }
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReminderSlotEditorBody(
    isEditing: Boolean,
    hour: Int,
    minute: Int,
    selectedDays: Set<Int>,
    title: String,
    message: String,
    titleError: String?,
    messageError: String?,
    daysError: String?,
    submitError: String?,
    canSave: Boolean,
    onChangeTime: (Int, Int) -> Unit,
    onChangeDays: (Set<Int>) -> Unit,
    onChangeTitle: (String) -> Unit,
    onChangeMessage: (String) -> Unit,
    onClose: () -> Unit,
    onDelete: (() -> Unit)?,
    onConfirmSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ICostSheetHeader(
            title = if (isEditing) "编辑定时提醒" else "新增定时提醒",
            subtitle = "设置提醒时间、重复周期与通知内容",
            onClose = onClose
        )

        ICostFormCard {
            Text(text = "提醒时间", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ICostTextSecondary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TimeStepperBox(
                    label = "时",
                    value = hour,
                    onDec = { onChangeTime(hour - 1, minute) },
                    onInc = { onChangeTime(hour + 1, minute) }
                )
                Text(
                    text = ":",
                    fontFamily = ICDinFontFamily,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = ICostTextPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp)
                )
                TimeStepperBox(
                    label = "分",
                    value = minute,
                    onDec = { onChangeTime(hour, minute - 15) },
                    onInc = { onChangeTime(hour, minute + 15) }
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TIME_PRESETS.forEach { preset ->
                    val selected = hour == preset.hour && minute == preset.minute
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) ICostBlueAccent else Color(0xFFF2F3F7),
                        modifier = Modifier.clickable { onChangeTime(preset.hour, preset.minute) }
                    ) {
                        Text(
                            text = preset.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) Color.White else ICostTextPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }

        ICostFormCard {
            Text(text = "重复周期", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ICostTextSecondary)
            val quickRepeatModes = listOf(
                "每天" to setOf(1, 2, 3, 4, 5, 6, 7),
                "工作日" to setOf(1, 2, 3, 4, 5),
                "周末" to setOf(6, 7)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quickRepeatModes.forEach { (label, targetSet) ->
                    val active = selectedDays == targetSet
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (active) ICostBlueAccent else Color(0xFFF2F3F7),
                        modifier = Modifier.clickable { onChangeDays(targetSet) }
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (active) Color.White else ICostTextPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                WEEKDAY_ITEMS.forEach { (dayNum, dayLabel) ->
                    val isChecked = dayNum in selectedDays
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (isChecked) ICostBlueAccent else Color(0xFFF2F3F7))
                            .clickable {
                                val next = if (isChecked) selectedDays - dayNum else selectedDays + dayNum
                                onChangeDays(next)
                            }
                            .semantics { contentDescription = "周$dayLabel" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = dayLabel,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isChecked) Color.White else ICostTextPrimary
                        )
                    }
                }
            }
            if (daysError != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = daysError, fontSize = 12.sp, color = ICostCoralRed, fontWeight = FontWeight.SemiBold)
            }
        }

        ReminderSlotTextAndActions(
            isEditing = isEditing,
            title = title,
            message = message,
            titleError = titleError,
            messageError = messageError,
            submitError = submitError,
            canSave = canSave,
            onChangeTitle = onChangeTitle,
            onChangeMessage = onChangeMessage,
            onDelete = onDelete,
            onConfirmSave = onConfirmSave
        )
    }
}

@Composable
private fun TimeStepperBox(
    label: String,
    value: Int,
    onDec: () -> Unit,
    onInc: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = CircleShape,
            color = Color(0xFFF2F3F7),
            modifier = Modifier
                .size(34.dp)
                .clickable(onClick = onDec)
                .semantics { contentDescription = "减少$label" }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("-", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ICostTextPrimary)
            }
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFFF2F3F7)
        ) {
            Text(
                text = String.format( java.util.Locale.US, "%02d", value),
                fontFamily = ICDinFontFamily,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = ICostTextPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        Surface(
            shape = CircleShape,
            color = Color(0xFFF2F3F7),
            modifier = Modifier
                .size(34.dp)
                .clickable(onClick = onInc)
                .semantics { contentDescription = "增加$label" }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ICostTextPrimary)
            }
        }
    }
}

@Composable
private fun ReminderSlotTextAndActions(
    isEditing: Boolean,
    title: String,
    message: String,
    titleError: String?,
    messageError: String?,
    submitError: String?,
    canSave: Boolean,
    onChangeTitle: (String) -> Unit,
    onChangeMessage: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onConfirmSave: () -> Unit
) {
    ICostFormCard {
        Text(text = "通知文案", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ICostTextSecondary)
        ICostFormField(
            value = title,
            onValueChange = onChangeTitle,
            label = "提醒标题（1~24 字）",
            placeholder = ReminderConfigManager.DEFAULT_REMINDER_TITLE,
            singleLine = true,
            isError = titleError != null,
            supportingText = titleError ?: "${title.trim().length}/${ReminderConfigManager.MAX_TITLE_LENGTH} 字",
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        ICostFormField(
            value = message,
            onValueChange = onChangeMessage,
            label = "提醒内容（1~60 字）",
            placeholder = ReminderConfigManager.DEFAULT_REMINDER_MESSAGE,
            singleLine = false,
            isError = messageError != null,
            supportingText = messageError ?: "${message.trim().length}/${ReminderConfigManager.MAX_MESSAGE_LENGTH} 字",
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (!submitError.isNullOrBlank()) {
        Text(
            text = submitError,
            fontSize = 12.sp,
            color = ICostCoralRed,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isEditing && onDelete != null) {
            OutlinedButton(
                onClick = onDelete,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .semantics { contentDescription = "删除提醒" }
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = ICostCoralRed,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("删除提醒", color = ICostCoralRed, fontWeight = FontWeight.Bold)
            }
        }
        Button(
            onClick = onConfirmSave,
            enabled = canSave,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
            modifier = Modifier
                .weight(if (isEditing && onDelete != null) 1.4f else 1f)
                .height(48.dp)
                .semantics { contentDescription = "保存提醒" }
        ) {
            Text("保存", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

