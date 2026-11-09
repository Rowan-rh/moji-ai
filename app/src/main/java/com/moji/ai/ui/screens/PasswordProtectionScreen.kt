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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.data.model.AppLockManager
import com.moji.ai.data.model.PasswordProtectionConfig
import com.moji.ai.data.model.PinVerifyResult
import com.moji.ai.ui.theme.ICostAssetImage
import com.moji.ai.ui.theme.ICostBgGray
import com.moji.ai.ui.theme.ICostBlueAccent
import com.moji.ai.ui.theme.ICostCardWhite
import com.moji.ai.ui.theme.ICostCircleIconButton
import com.moji.ai.ui.theme.ICostCoralRed
import com.moji.ai.ui.theme.ICostFormCard
import com.moji.ai.ui.theme.ICostFormField
import com.moji.ai.ui.theme.ICostGreenIncome
import com.moji.ai.ui.theme.ICostSheetHeader
import com.moji.ai.ui.theme.ICostSmoothBottomSheet
import com.moji.ai.ui.theme.ICostTextMuted
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.iCostPressScale
import com.moji.ai.ui.theme.iCostUnifiedSwitchColors

@Composable
fun PasswordProtectionScreen(
    config: PasswordProtectionConfig,
    onBack: () -> Unit,
    onVerifyPin: (String) -> PinVerifyResult,
    onEnableProtection: (newPin: String, confirmPin: String) -> Result<PasswordProtectionConfig>,
    onChangePin: (oldPin: String, newPin: String, confirmPin: String) -> Result<PasswordProtectionConfig>,
    onDisableProtection: (currentPin: String) -> Result<PasswordProtectionConfig>,
    onUpdateLockTimeout: (Int) -> Unit,
    onUpdateHideRecentTaskPreview: (Boolean) -> Unit,
    onUpdatePinHint: (String) -> Result<PasswordProtectionConfig>,
    onLockNow: () -> Unit
) {
    var activePinFlowMode by rememberSaveable { mutableStateOf<PinFlowMode?>(null) }
    var showHintEditorSheet by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = activePinFlowMode == null && !showHintEditorSheet, onBack = onBack)

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
                if (config.enabled) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = ICostCardWhite,
                        modifier = Modifier
                            .semantics { contentDescription = "立即锁定测试" }
                            .iCostPressScale(pressedScale = 0.94f, onClick = onLockNow)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = ICostBlueAccent,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "立即锁定测试",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostBlueAccent
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "密码保护",
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ICostTextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (config.enabled) {
                    "已开启 4 位数字安全锁 · ${config.timeoutDisplayLabel} · 本机加盐哈希加密保护"
                } else {
                    "设置 4 位数字安全密码，保护账本隐私与资产数据安全"
                },
                fontSize = 13.sp,
                color = ICostTextSecondary
            )

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 48.dp)
            ) {
                item {
                    PasswordStatusHeroCard(
                        config = config,
                        onStartEnable = { activePinFlowMode = PinFlowMode.ENABLE },
                        onStartChange = { activePinFlowMode = PinFlowMode.CHANGE },
                        onStartDisable = { activePinFlowMode = PinFlowMode.DISABLE },
                        onLockNow = onLockNow
                    )
                }

                item {
                    AutoLockTimeoutCard(
                        config = config,
                        onSelectTimeout = onUpdateLockTimeout
                    )
                }

                item {
                    PinHintCard(
                        config = config,
                        onEditHint = { showHintEditorSheet = true }
                    )
                }

                item {
                    RecentTaskPrivacyCard(
                        config = config,
                        onToggleHideRecent = onUpdateHideRecentTaskPreview
                    )
                }

                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = ICostCardWhite,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "安全与备份边界说明",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostTextPrimary
                            )
                            Text(
                                text = "• 密码采用 SHA-256 随机盐摘要存储于本机，不保存任何明文密码。\n• 连续输错 5 次将自动触发 30 秒防暴力破解冷却保护。\n• 应用锁凭据仅作用于当前设备，不随 .mojibackup 备份文件导出或恢复覆盖。",
                                fontSize = 12.sp,
                                color = ICostTextSecondary,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }

        if (showHintEditorSheet) {
            PinHintEditorBottomSheet(
                currentHint = config.pinHint,
                onDismiss = { showHintEditorSheet = false },
                onSaveHint = { rawHint, requestDismiss ->
                    onUpdatePinHint(rawHint).onSuccess {
                        requestDismiss()
                    }
                }
            )
        }

        activePinFlowMode?.let { mode ->
            AppLockPinFlowOverlay(
                mode = mode,
                pinHint = config.pinHint,
                onDismiss = { activePinFlowMode = null },
                onVerifyOldPin = onVerifyPin,
                onEnablePin = { newPin, confirmPin ->
                    onEnableProtection(newPin, confirmPin).map { }
                },
                onChangePin = { oldPin, newPin, confirmPin ->
                    onChangePin(oldPin, newPin, confirmPin).map { }
                },
                onDisableProtection = { currentPin ->
                    onDisableProtection(currentPin).map { }
                }
            )
        }
    }
}

@Composable
private fun PasswordStatusHeroCard(
    config: PasswordProtectionConfig,
    onStartEnable: () -> Unit,
    onStartChange: () -> Unit,
    onStartDisable: () -> Unit,
    onLockNow: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = ICostCardWhite,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (config.enabled) {
                        ICostGreenIncome.copy(alpha = 0.14f)
                    } else {
                        ICostBlueAccent.copy(alpha = 0.12f)
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        ICostAssetImage(
                            assetFile = "setting_password.webp",
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (config.enabled) "已开启密码保护" else "未开启密码保护",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = ICostTextPrimary,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (config.enabled) {
                            "4 位数字 PIN 加盐加密 · ${config.timeoutDisplayLabel}"
                        } else {
                            "开启后进入默记或后台超时返回时需验证 4 位安全密码"
                        },
                        fontSize = 12.sp,
                        color = ICostTextSecondary,
                        maxLines = 2
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (config.enabled) {
                        ICostGreenIncome.copy(alpha = 0.12f)
                    } else {
                        Color(0xFFECECEE)
                    }
                ) {
                    Text(
                        text = if (config.enabled) "已保护" else "未开启",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (config.enabled) ICostGreenIncome else ICostTextSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            if (!config.enabled) {
                Button(
                    onClick = onStartEnable,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = "开启密码保护",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = ICostBlueAccent.copy(alpha = 0.10f),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .iCostPressScale(pressedScale = 0.95f, onClick = onStartChange)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "修改密码",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostBlueAccent,
                                maxLines = 1
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = ICostCoralRed.copy(alpha = 0.10f),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .iCostPressScale(pressedScale = 0.95f, onClick = onStartDisable)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "关闭密码保护",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = ICostCoralRed,
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
private fun AutoLockTimeoutCard(
    config: PasswordProtectionConfig,
    onSelectTimeout: (Int) -> Unit
) {
    val options = remember {
        listOf(
            0 to "立即锁定",
            60 to "离开 1 分钟后",
            300 to "离开 5 分钟后"
        )
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ICostCardWhite,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "自动锁定时间",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = ICostTextPrimary
                )
                Text(
                    text = config.timeoutDisplayLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ICostBlueAccent
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { (seconds, label) ->
                    val selected = config.lockTimeoutSeconds == seconds
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) ICostBlueAccent else Color(0xFFF3F4F6),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .iCostPressScale(pressedScale = 0.95f) {
                                onSelectTimeout(seconds)
                            }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) Color.White else ICostTextPrimary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Text(
                text = when (config.lockTimeoutSeconds) {
                    60 -> "切换到后台超过 1 分钟后再返回默记时，自动弹出安全锁屏"
                    300 -> "切换到后台超过 5 分钟后再返回默记时，自动弹出安全锁屏"
                    else -> "切换到后台或锁屏后立即加锁，再次回到默记需验证密码"
                },
                fontSize = 12.sp,
                color = ICostTextSecondary
            )
        }
    }
}

@Composable
private fun PinHintCard(
    config: PasswordProtectionConfig,
    onEditHint: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ICostCardWhite,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEditHint)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "密码提示语",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = ICostTextPrimary
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = if (config.pinHint.isNotBlank()) {
                        "已设置：${config.pinHint}"
                    } else {
                        "未设置（选填，最多 20 字，不可包含密码本身）"
                    },
                    fontSize = 12.sp,
                    color = if (config.pinHint.isNotBlank()) ICostTextPrimary else ICostTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = ICostBlueAccent.copy(alpha = 0.10f),
                modifier = Modifier.iCostPressScale(pressedScale = 0.94f, onClick = onEditHint)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = ICostBlueAccent,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (config.pinHint.isNotBlank()) "修改提示语" else "设置提示语",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = ICostBlueAccent
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentTaskPrivacyCard(
    config: PasswordProtectionConfig,
    onToggleHideRecent: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ICostCardWhite,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "多任务模糊与防截屏保护",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = ICostTextPrimary
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "开启后在系统多任务切换界面隐藏账本预览并禁止截屏（默认关闭，兼顾日常截屏识账）",
                    fontSize = 12.sp,
                    color = ICostTextSecondary,
                    lineHeight = 17.sp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = config.hideRecentTaskPreview,
                onCheckedChange = onToggleHideRecent,
                colors = iCostUnifiedSwitchColors()
            )
        }
    }
}

@Composable
private fun PinHintEditorBottomSheet(
    currentHint: String,
    onDismiss: () -> Unit,
    onSaveHint: (rawHint: String, requestDismiss: () -> Unit) -> Unit
) {
    var hintInput by remember(currentHint) { mutableStateOf(currentHint) }
    val validation = remember(hintInput) {
        AppLockManager.validatePinHint(hintInput)
    }
    val errorText = validation.exceptionOrNull()?.message

    ICostSmoothBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ICostBgGray,
        showDragHandle = false
    ) { requestDismiss ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 18.dp)
        ) {
            ICostSheetHeader(
                title = "设置密码提示语",
                subtitle = "防遗忘辅助提示 · 严禁直接包含 4 位密码本身",
                onClose = requestDismiss
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ICostFormCard {
                    ICostFormField(
                        value = hintInput,
                        onValueChange = { hintInput = it },
                        label = "密码提示语（选填，最多 20 字）",
                        placeholder = "例如：常用四位数字 / 生日月日",
                        isError = errorText != null,
                        supportingText = errorText ?: "当前已输入 ${hintInput.trim().length}/20 字（留空表示不显示提示入口）"
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (currentHint.isNotBlank() || hintInput.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = ICostCardWhite,
                            modifier = Modifier
                                .weight(0.42f)
                                .height(46.dp)
                                .iCostPressScale(pressedScale = 0.95f) {
                                    onSaveHint("", requestDismiss)
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "清空提示语",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ICostCoralRed
                                )
                            }
                        }
                    }
                    Button(
                        enabled = errorText == null,
                        onClick = { onSaveHint(hintInput, requestDismiss) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ICostBlueAccent),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text(
                            text = "保存提示语",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}


