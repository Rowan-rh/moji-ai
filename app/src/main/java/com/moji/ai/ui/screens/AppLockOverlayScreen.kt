package com.moji.ai.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moji.ai.data.model.AppLockManager
import com.moji.ai.data.model.PinVerifyResult
import com.moji.ai.ui.theme.ICDinFontFamily
import com.moji.ai.ui.theme.ICostAssetImage
import com.moji.ai.ui.theme.ICostBgGray
import com.moji.ai.ui.theme.ICostBlueAccent
import com.moji.ai.ui.theme.ICostCardWhite
import com.moji.ai.ui.theme.ICostCircleIconButton
import com.moji.ai.ui.theme.ICostCoralRed
import com.moji.ai.ui.theme.ICostTextMuted
import com.moji.ai.ui.theme.ICostTextPrimary
import com.moji.ai.ui.theme.ICostTextSecondary
import com.moji.ai.ui.theme.iCostPressScale
import kotlinx.coroutines.delay

enum class PinFlowMode {
    ENABLE,
    CHANGE,
    DISABLE
}

private enum class PinStep {
    VERIFY_OLD,
    ENTER_NEW,
    CONFIRM_NEW
}

/**
 * 触点 C：全屏数字键盘解锁遮罩层（当 `isSessionLocked == true` 时在顶层全屏挂载并拦截系统返回键）
 */
@Composable
fun AppLockUnlockOverlay(
    pinHint: String,
    onVerifyPin: (String) -> PinVerifyResult
) {
    // 拦截系统返回键，禁止绕过安全锁
    BackHandler(enabled = true) {}

    var enteredPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var cooldownSeconds by remember { mutableIntStateOf(AppLockManager.getRemainingCooldownSeconds()) }
    var showHintExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(cooldownSeconds > 0) {
        while (cooldownSeconds > 0) {
            delay(250L)
            val next = AppLockManager.getRemainingCooldownSeconds()
            cooldownSeconds = next
            if (next > 0) {
                errorMessage = "错误次数过多，请 $next 秒后再试"
            } else {
                errorMessage = null
            }
        }
    }

    val handleDigit: (String) -> Unit = { digit ->
        val latestCooldown = AppLockManager.getRemainingCooldownSeconds()
        if (latestCooldown > 0) {
            cooldownSeconds = latestCooldown
            errorMessage = "错误次数过多，请 $latestCooldown 秒后再试"
        } else if (enteredPin.length < 4) {
            val nextPin = enteredPin + digit
            enteredPin = nextPin
            if (nextPin.length < 4) {
                errorMessage = null
            } else {
                when (val result = onVerifyPin(nextPin)) {
                    is PinVerifyResult.Success -> {
                        enteredPin = ""
                        errorMessage = null
                    }
                    is PinVerifyResult.InvalidFormat -> {
                        enteredPin = ""
                        errorMessage = result.message
                    }
                    is PinVerifyResult.Mismatch -> {
                        enteredPin = ""
                        errorMessage = result.message
                    }
                    is PinVerifyResult.CooldownLocked -> {
                        enteredPin = ""
                        cooldownSeconds = result.remainingSeconds
                        errorMessage = result.message
                    }
                }
            }
        }
    }

    // 使用独立窗口级不可关闭 Dialog 挂载解锁层，彻底隔离底层 Activity 的无障碍焦点树、软键盘输入焦点与预测性返回手势
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler(enabled = true) {}
        AppLockKeypadScaffold(
            title = "请输入默记安全密码",
            subtitle = "输入 4 位数字安全密码以解锁账本",
            enteredLength = enteredPin.length,
            errorMessage = errorMessage,
            isCooldownLocked = cooldownSeconds > 0,
            pinHint = pinHint,
            showHintExpanded = showHintExpanded,
            onToggleHint = { showHintExpanded = !showHintExpanded },
            topLeftButton = null,
            secondaryActionText = null,
            onSecondaryAction = null,
            onDigitClick = handleDigit,
            onClearClick = {
                enteredPin = ""
                if (cooldownSeconds <= 0) errorMessage = null
            },
            onDeleteClick = {
                if (enteredPin.isNotEmpty()) {
                    enteredPin = enteredPin.dropLast(1)
                }
            }
        )
    }
}

/**
 * 首次开启 / 修改密码 / 关闭密码保护的多步数字键盘遮罩流程
 */
@Composable
fun AppLockPinFlowOverlay(
    mode: PinFlowMode,
    pinHint: String = "",
    onDismiss: () -> Unit,
    onVerifyOldPin: (String) -> PinVerifyResult,
    onEnablePin: (newPin: String, confirmPin: String) -> Result<Unit>,
    onChangePin: (oldPin: String, newPin: String, confirmPin: String) -> Result<Unit>,
    onDisableProtection: (currentPin: String) -> Result<Unit>
) {
    val initialStep = remember(mode) {
        when (mode) {
            PinFlowMode.ENABLE -> PinStep.ENTER_NEW
            PinFlowMode.CHANGE, PinFlowMode.DISABLE -> PinStep.VERIFY_OLD
        }
    }
    var currentStep by remember(mode) { mutableStateOf(initialStep) }
    var verifiedOldPin by remember(mode) { mutableStateOf("") }
    var firstEnteredNewPin by remember(mode) { mutableStateOf("") }
    var enteredPin by remember(mode) { mutableStateOf("") }
    var errorMessage by remember(mode) { mutableStateOf<String?>(null) }
    var cooldownSeconds by remember(mode) { mutableIntStateOf(AppLockManager.getRemainingCooldownSeconds()) }
    var showHintExpanded by remember(mode) { mutableStateOf(false) }

    val handleBackOrClose: () -> Unit = {
        if (currentStep == PinStep.CONFIRM_NEW) {
            currentStep = PinStep.ENTER_NEW
            firstEnteredNewPin = ""
            enteredPin = ""
            errorMessage = null
        } else {
            onDismiss()
        }
    }

    BackHandler(onBack = handleBackOrClose)

    LaunchedEffect(cooldownSeconds > 0) {
        while (cooldownSeconds > 0) {
            delay(250L)
            val next = AppLockManager.getRemainingCooldownSeconds()
            cooldownSeconds = next
            if (next > 0) {
                errorMessage = "错误次数过多，请 $next 秒后再试"
            } else {
                errorMessage = null
            }
        }
    }

    val title = when (currentStep) {
        PinStep.VERIFY_OLD -> if (mode == PinFlowMode.DISABLE) "验证安全密码" else "验证当前密码"
        PinStep.ENTER_NEW -> if (mode == PinFlowMode.CHANGE) "输入新密码" else "设置安全密码"
        PinStep.CONFIRM_NEW -> if (mode == PinFlowMode.CHANGE) "确认新密码" else "确认安全密码"
    }

    val subtitle = when (currentStep) {
        PinStep.VERIFY_OLD -> if (mode == PinFlowMode.DISABLE) {
            "关闭密码保护前请先验证当前 4 位密码"
        } else {
            "修改密码前请先输入当前 4 位安全密码"
        }
        PinStep.ENTER_NEW -> "请输入 4 位数字作为默记安全密码"
        PinStep.CONFIRM_NEW -> "请再次输入刚才设置的 4 位数字密码"
    }

    val handleDigit: (String) -> Unit = { digit ->
        val latestCooldown = if (currentStep == PinStep.VERIFY_OLD) {
            AppLockManager.getRemainingCooldownSeconds()
        } else 0
        if (latestCooldown > 0) {
            cooldownSeconds = latestCooldown
            errorMessage = "错误次数过多，请 $latestCooldown 秒后再试"
        } else if (enteredPin.length < 4) {
            val nextPin = enteredPin + digit
            enteredPin = nextPin
            if (nextPin.length < 4) {
                errorMessage = null
            } else {
                when (currentStep) {
                    PinStep.VERIFY_OLD -> {
                        if (mode == PinFlowMode.DISABLE) {
                            onDisableProtection(nextPin).fold(
                                onSuccess = {
                                    enteredPin = ""
                                    errorMessage = null
                                    onDismiss()
                                },
                                onFailure = { err ->
                                    enteredPin = ""
                                    cooldownSeconds = AppLockManager.getRemainingCooldownSeconds()
                                    errorMessage = err.message ?: "密码验证失败"
                                }
                            )
                        } else {
                            when (val verify = onVerifyOldPin(nextPin)) {
                                is PinVerifyResult.Success -> {
                                    verifiedOldPin = nextPin
                                    enteredPin = ""
                                    errorMessage = null
                                    currentStep = PinStep.ENTER_NEW
                                }
                                is PinVerifyResult.InvalidFormat -> {
                                    enteredPin = ""
                                    errorMessage = verify.message
                                }
                                is PinVerifyResult.Mismatch -> {
                                    enteredPin = ""
                                    errorMessage = verify.message
                                }
                                is PinVerifyResult.CooldownLocked -> {
                                    enteredPin = ""
                                    cooldownSeconds = verify.remainingSeconds
                                    errorMessage = verify.message
                                }
                            }
                        }
                    }
                    PinStep.ENTER_NEW -> {
                        firstEnteredNewPin = nextPin
                        enteredPin = ""
                        errorMessage = null
                        currentStep = PinStep.CONFIRM_NEW
                    }
                    PinStep.CONFIRM_NEW -> {
                        if (nextPin != firstEnteredNewPin) {
                            enteredPin = ""
                            errorMessage = "两次输入的密码不一致，请重新输入"
                        } else {
                            val opResult = if (mode == PinFlowMode.ENABLE) {
                                onEnablePin(firstEnteredNewPin, nextPin)
                            } else {
                                onChangePin(verifiedOldPin, firstEnteredNewPin, nextPin)
                            }
                            opResult.fold(
                                onSuccess = {
                                    enteredPin = ""
                                    errorMessage = null
                                    onDismiss()
                                },
                                onFailure = { err ->
                                    enteredPin = ""
                                    firstEnteredNewPin = ""
                                    currentStep = PinStep.ENTER_NEW
                                    errorMessage = err.message ?: "保存密码失败"
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    AppLockKeypadScaffold(
        title = title,
        subtitle = subtitle,
        enteredLength = enteredPin.length,
        errorMessage = errorMessage,
        isCooldownLocked = currentStep == PinStep.VERIFY_OLD && cooldownSeconds > 0,
        pinHint = if (currentStep == PinStep.VERIFY_OLD) pinHint else "",
        showHintExpanded = showHintExpanded,
        onToggleHint = { showHintExpanded = !showHintExpanded },
        topLeftButton = {
            ICostCircleIconButton(onClick = handleBackOrClose, size = 40.dp) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = ICostTextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        secondaryActionText = if (currentStep == PinStep.CONFIRM_NEW) "重新输入第一步" else null,
        onSecondaryAction = if (currentStep == PinStep.CONFIRM_NEW) {
            {
                firstEnteredNewPin = ""
                enteredPin = ""
                errorMessage = null
                currentStep = PinStep.ENTER_NEW
            }
        } else null,
        onDigitClick = handleDigit,
        onClearClick = {
            enteredPin = ""
            if (cooldownSeconds <= 0) errorMessage = null
        },
        onDeleteClick = {
            if (enteredPin.isNotEmpty()) {
                enteredPin = enteredPin.dropLast(1)
            }
        }
    )
}

@Composable
private fun AppLockKeypadScaffold(
    title: String,
    subtitle: String,
    enteredLength: Int,
    errorMessage: String?,
    isCooldownLocked: Boolean,
    pinHint: String,
    showHintExpanded: Boolean,
    onToggleHint: () -> Unit,
    topLeftButton: (@Composable () -> Unit)?,
    secondaryActionText: String?,
    onSecondaryAction: (() -> Unit)?,
    onDigitClick: (String) -> Unit,
    onClearClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ICostBgGray)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (topLeftButton != null) {
                topLeftButton()
            }
        }

        Spacer(modifier = Modifier.weight(0.55f))

        Surface(
            shape = CircleShape,
            color = ICostBlueAccent.copy(alpha = 0.12f),
            modifier = Modifier.size(68.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                ICostAssetImage(
                    assetFile = "setting_password.webp",
                    modifier = Modifier.size(34.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = title,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color = ICostTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = subtitle,
            fontSize = 13.sp,
            color = ICostTextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2
        )

        Spacer(modifier = Modifier.height(26.dp))

        // 4 位圆点指示器
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(4) { idx ->
                val filled = idx < enteredLength
                val dotColor = when {
                    !errorMessage.isNullOrBlank() && !filled -> ICostCoralRed.copy(alpha = 0.25f)
                    filled -> ICostBlueAccent
                    else -> Color(0xFFDADCE2)
                }
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (filled) dotColor else Color.Transparent)
                        .border(
                            width = if (filled) 0.dp else 2.dp,
                            color = if (!errorMessage.isNullOrBlank()) ICostCoralRed.copy(alpha = 0.65f) else dotColor,
                            shape = CircleShape
                        )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            contentAlignment = Alignment.Center
        ) {
            if (!errorMessage.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ICostCoralRed.copy(alpha = 0.10f)
                ) {
                    Text(
                        text = errorMessage,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ICostCoralRed,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            } else if (secondaryActionText != null && onSecondaryAction != null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = ICostCardWhite,
                    modifier = Modifier.iCostPressScale(pressedScale = 0.94f, onClick = onSecondaryAction)
                ) {
                    Text(
                        text = secondaryActionText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ICostBlueAccent,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }

        if (pinHint.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ICostCardWhite,
                modifier = Modifier
                    .semantics { contentDescription = "查看密码提示" }
                    .iCostPressScale(pressedScale = 0.95f, onClick = onToggleHint)
            ) {
                Text(
                    text = if (showHintExpanded) "密码提示：$pinHint" else "查看密码提示",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (showHintExpanded) ICostTextPrimary else ICostBlueAccent,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        ICostPinNumberPad(
            enabled = !isCooldownLocked,
            onDigitClick = onDigitClick,
            onClearClick = onClearClick,
            onDeleteClick = onDeleteClick
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun ICostPinNumberPad(
    enabled: Boolean,
    onDigitClick: (String) -> Unit,
    onClearClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val rows = remember {
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("清空", "0", "⌫")
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        rows.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowItems.forEach { keyLabel ->
                    val isActionKey = keyLabel == "清空" || keyLabel == "⌫"
                    val a11yLabel = when (keyLabel) {
                        "⌫" -> "删除"
                        "清空" -> "清空"
                        else -> "数字$keyLabel"
                    }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isActionKey) Color(0xFFECECEE) else ICostCardWhite,
                        modifier = Modifier
                            .weight(1f)
                            .height(62.dp)
                            .semantics { contentDescription = a11yLabel }
                            .then(
                                if (enabled) {
                                    Modifier.iCostPressScale(pressedScale = 0.93f) {
                                        when (keyLabel) {
                                            "清空" -> onClearClick()
                                            "⌫" -> onDeleteClick()
                                            else -> onDigitClick(keyLabel)
                                        }
                                    }
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = keyLabel,
                                fontFamily = if (isActionKey) null else ICDinFontFamily,
                                fontSize = if (isActionKey) 16.sp else 26.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (!enabled) {
                                    ICostTextMuted
                                } else if (keyLabel == "清空") {
                                    ICostTextSecondary
                                } else {
                                    ICostTextPrimary
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

