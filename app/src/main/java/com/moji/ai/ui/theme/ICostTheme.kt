package com.moji.ai.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.R
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CategoryCatalog
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

// 1:1 iCost 字体家族（ICDIN-Bold 用于所有核心金额数字，Quicksand 用于英文几何辅助）
val ICDinFontFamily = FontFamily(
    Font(R.font.icdin_bold, FontWeight.Normal),
    Font(R.font.icdin_bold, FontWeight.Medium),
    Font(R.font.icdin_bold, FontWeight.SemiBold),
    Font(R.font.icdin_bold, FontWeight.Bold),
    Font(R.font.icdin_bold, FontWeight.ExtraBold)
)

val QuicksandFontFamily = FontFamily(
    Font(R.font.quicksand_bold, FontWeight.Bold)
)

// 1:1 iCost 标准色彩体系
val ICostBgGray = Color(0xFFF6F6F6)
val ICostCardWhite = Color(0xFFFFFFFF)
val ICostCellGray = Color(0xFFF7F7F8)
val ICostCoralRed = Color(0xFFE65540)
val ICostAmberGold = Color(0xFFF5B400)
val ICostBlueAccent = Color(0xFF1677FF)
val ICostGreenIncome = Color(0xFF22B573)
val ICostTextPrimary = Color(0xFF141414)
val ICostTextSecondary = Color(0xFF8C8C8C)
val ICostTextMuted = Color(0xFFAEAEAE)
val ICostSearchBg = Color(0xFFEBEBEB)
val ICostPillGray = Color(0xFFECECEE)
val ICostDivider = Color(0xFFF1F1F1)
val ICostSlateIcon = Color(0xFF434957)

private val assetBitmapCache = ConcurrentHashMap<String, ImageBitmap>()
@Volatile
private var assetsPreloaded = false

/**
 * 启动时在后台 IO 线程一次性预解码全部 iCost 图标资源至内存缓存，
 * 彻底消除打开底部记账抽屉或展开二级分类时的主线程磁盘 I/O 与 Bitmap 解码掉帧。
 */
suspend fun preloadAllICostAssets(context: Context) {
    if (assetsPreloaded) return
    withContext(Dispatchers.IO) {
        runCatching {
            val files = context.assets.list("icost").orEmpty()
            for (fileName in files) {
                if (!fileName.endsWith(".webp") && !fileName.endsWith(".png")) continue
                if (assetBitmapCache.containsKey(fileName)) continue
                runCatching {
                    context.assets.open("icost/$fileName").use { stream ->
                        BitmapFactory.decodeStream(stream)?.asImageBitmap()?.let { bmp ->
                            assetBitmapCache.putIfAbsent(fileName, bmp)
                        }
                    }
                }
            }
            assetsPreloaded = true
        }
    }
}

@Composable
fun rememberICostAssetBitmap(assetFile: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(assetFile) {
        assetBitmapCache.getOrPut(assetFile) {
            runCatching {
                context.assets.open("icost/$assetFile").use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }.getOrNull() ?: return@remember null
        }
    }
}

/**
 * 120Hz 窗口内 GPU 硬件加速底部抽屉（替代会新建独立 Dialog Window 导致首帧卡顿的 ModalBottomSheet）：
 * 1. 直接复用主 Activity 的 120Hz 硬件加速 Surface，0 窗口创建耗时；
 * 2. 支持 `keepWarm = true` 后台常驻预热节点树，点击「+」唤起时 0 重组、0 首次测量，首帧即达 120fps；
 * 3. 全程通过 `graphicsLayer { translationY = ... }` 在 GPU RenderNode 层执行上滑、下拉跟手与收起动画；
 * 4. 支持手指按住抽屉向下 1:1 跟手拖拽与快速轻拂关闭。
 */
@Composable
fun ICostSmoothBottomSheet(
    onDismissRequest: () -> Unit,
    visible: Boolean = true,
    keepWarm: Boolean = false,
    containerColor: Color = ICostBgGray,
    shape: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    showDragHandle: Boolean = false,
    /**
     * 用户通过下拉或点击遮罩关闭前的拦截：返回 false 时抽屉弹回原位不关闭
     * （例如记账面板已输入金额时先弹出「放弃本次记账？」确认）
     */
    confirmDismiss: (() -> Boolean)? = null,
    content: @Composable (requestDismiss: () -> Unit) -> Unit
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val fallbackHeightPx = with(density) { configuration.screenHeightDp.dp.toPx().coerceAtLeast(1800f) }
    var sheetHeightPx by remember { mutableFloatStateOf(fallbackHeightPx) }
    var offsetYPx by remember { mutableFloatStateOf(fallbackHeightPx) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var isDismissing by remember { mutableStateOf(false) }
    var isAnimatingOut by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(visible) {
        if (visible) {
            isDismissing = false
            isAnimatingOut = false
            settleJob?.cancel()
            val startY = if (offsetYPx >= sheetHeightPx * 0.9f) sheetHeightPx else offsetYPx
            offsetYPx = startY
            settleJob = launch {
                animate(
                    initialValue = startY,
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = 0.94f,
                        stiffness = 620f
                    )
                ) { value, _ ->
                    offsetYPx = value
                }
            }
        } else if (offsetYPx < sheetHeightPx - 2f) {
            isAnimatingOut = true
            settleJob?.cancel()
            val targetY = sheetHeightPx.coerceAtLeast(fallbackHeightPx * 0.7f)
            settleJob = launch {
                animate(
                    initialValue = offsetYPx,
                    targetValue = targetY,
                    animationSpec = spring(
                        dampingRatio = 0.96f,
                        stiffness = 680f
                    )
                ) { value, _ ->
                    offsetYPx = value
                }
                isAnimatingOut = false
            }
        } else {
            offsetYPx = sheetHeightPx
            isAnimatingOut = false
        }
    }

    val triggerAnimatedDismiss: (Float) -> Unit = remember(scope, onDismissRequest) {
        { initialVel ->
            if (!isDismissing) {
                isDismissing = true
                isAnimatingOut = true
                settleJob?.cancel()
                val targetY = sheetHeightPx.coerceAtLeast(fallbackHeightPx * 0.7f)
                settleJob = scope.launch {
                    animate(
                        initialValue = offsetYPx,
                        targetValue = targetY,
                        initialVelocity = initialVel.coerceIn(0f, 4500f),
                        animationSpec = spring(
                            dampingRatio = 0.96f,
                            stiffness = 680f
                        )
                    ) { value, _ ->
                        offsetYPx = value
                    }
                    isAnimatingOut = false
                    onDismissRequest()
                }
            }
        }
    }

    val isActiveOnScreen = visible || isAnimatingOut || offsetYPx < sheetHeightPx - 4f
    if (!isActiveOnScreen && !keepWarm) {
        return
    }

    // 仅在可见时注册（而不是常驻注册再切换 enabled），保证每次打开都排在最新、优先于其下方页面
    if (visible) {
        BackHandler(enabled = !isDismissing) {
            triggerAnimatedDismiss(0f)
        }
    }

    val verticalDragState = rememberDraggableState { delta ->
        if (visible && !isDismissing) {
            settleJob?.cancel()
            val raw = offsetYPx + delta
            offsetYPx = if (raw < 0f) raw * 0.14f else raw
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset {
                // keepWarm 预热态下移出屏幕触控区，完全不拦截底层任何点击或滑动手势
                IntOffset(0, if (isActiveOnScreen) 0 else 100000)
            }
    ) {
        if (isActiveOnScreen) {
            // 半透明渐变遮罩层：仅在 GPU Draw 阶段更新 alpha
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val denom = sheetHeightPx.coerceAtLeast(1f)
                        val progress = (1f - (offsetYPx.coerceAtLeast(0f) / denom)).coerceIn(0f, 1f)
                        alpha = progress * 0.44f
                    }
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (confirmDismiss?.invoke() != false) triggerAnimatedDismiss(0f)
                        }
                    )
            )
        }

        // 底部上浮抽屉主体：100% GPU translationY 驱动 + 垂直 1:1 跟手拖拽
        Surface(
            color = containerColor,
            shape = shape,
            shadowElevation = if (isActiveOnScreen) 16.dp else 0.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { sz ->
                    if (sz.height > 0) {
                        val newH = sz.height.toFloat()
                        sheetHeightPx = newH
                        if (!visible && !isAnimatingOut) {
                            offsetYPx = newH
                        }
                    }
                }
                .graphicsLayer {
                    translationY = if (isActiveOnScreen) offsetYPx else sheetHeightPx
                    alpha = if (isActiveOnScreen) 1f else 0f
                }
                .draggable(
                    state = verticalDragState,
                    orientation = Orientation.Vertical,
                    enabled = isActiveOnScreen,
                    onDragStarted = {
                        settleJob?.cancel()
                    },
                    onDragStopped = { velocity ->
                        if (visible && !isDismissing) {
                            val closeThreshold = sheetHeightPx * 0.22f
                            val wantsClose = velocity > 420f || offsetYPx > closeThreshold
                            if (wantsClose && confirmDismiss?.invoke() != false) {
                                triggerAnimatedDismiss(velocity.coerceAtLeast(0f))
                            } else {
                                settleJob?.cancel()
                                settleJob = scope.launch {
                                    animate(
                                        initialValue = offsetYPx,
                                        targetValue = 0f,
                                        initialVelocity = velocity.coerceIn(-3200f, 3200f),
                                        animationSpec = spring(
                                            dampingRatio = 0.94f,
                                            stiffness = 620f
                                        )
                                    ) { value, _ ->
                                        offsetYPx = value
                                    }
                                }
                            }
                        }
                    }
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = isActiveOnScreen,
                    onClick = {} // 拦截抽屉内部空白处点击，避免穿透至遮罩层
                )
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (showDragHandle) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFD4D4D8))
                        )
                    }
                }
                content { triggerAnimatedDismiss(0f) }
            }
        }
    }
}

@Composable
fun ICostAssetImage(
    assetFile: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Fit
) {
    val bitmap = rememberICostAssetBitmap(assetFile)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            colorFilter = tint?.let { ColorFilter.tint(it) }
        )
    }
}

fun formatMoneyCents(
    cents: Long,
    currency: String = "CNY",
    hide: Boolean = false
): String {
    if (hide) return "****"
    val sym = CurrencyCode.fromCode(currency).symbol
    val sign = if (cents < 0) "-" else ""
    return String.format(Locale.US, "%s%s%,.2f", sign, sym, abs(cents) / 100.0)
}

fun formatSignedExpenseCents(
    cents: Long,
    currency: String = "CNY"
): String {
    val sym = CurrencyCode.fromCode(currency).symbol
    return String.format(Locale.US, "-%s%,.2f", sym, abs(cents) / 100.0)
}

fun formatWalletDisplayName(wallet: WalletEntity?, fallback: String = "账户余额"): String {
    if (wallet == null) return fallback
    return wallet.name
}

fun daysUntilRepaymentText(repaymentDay: Int?): String {
    if (repaymentDay == null || repaymentDay !in 1..31) return "按期还款"
    val cal = Calendar.getInstance()
    val today = cal.get(Calendar.DAY_OF_MONTH)
    return when {
        repaymentDay == today -> "今日还款"
        repaymentDay > today -> "${repaymentDay - today}天后还款"
        else -> {
            val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            "${(maxDay - today) + repaymentDay}天后还款"
        }
    }
}

/**
 * iCost 级丝滑按压微缩回弹修饰符（自带阻尼弹簧缩放 + 细腻触觉反馈）
 */
fun Modifier.iCostPressScale(
    pressedScale: Float = 0.93f,
    enableHaptic: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = 0.62f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "iCostPressScale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = {
                if (enableHaptic) {
                    runCatching { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                }
                onClick()
            }
        )
}

/**
 * 核心大金额数字平滑滚动过渡组件（切换月份或新增账单时数字平滑增减）
 */
@Composable
fun AnimatedMoneyText(
    cents: Long,
    currency: String = "CNY",
    hideAmounts: Boolean = false,
    fontSize: TextUnit = 36.sp,
    fontWeight: FontWeight = FontWeight.Bold,
    color: Color = ICostTextPrimary,
    modifier: Modifier = Modifier
) {
    val animatedCents by animateFloatAsState(
        targetValue = cents.toFloat(),
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "AnimatedMoneyCents"
    )
    Text(
        text = formatMoneyCents(animatedCents.toLong(), currency, hideAmounts),
        fontFamily = ICDinFontFamily,
        fontSize = fontSize,
        fontWeight = fontWeight,
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
    )
}

/**
 * 水平滑动手势触发修饰符（支持模块右滑/左滑快捷切换或展开扩展面板，内置速度感应与方向锁定）
 */
fun Modifier.iCostHorizontalSwipeTrigger(
    thresholdDp: Dp = 36.dp,
    onSwipeRight: (() -> Unit)? = null,
    onSwipeLeft: (() -> Unit)? = null
): Modifier = composed {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val thresholdPx = with(density) { thresholdDp.toPx() }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    val draggableState = rememberDraggableState { delta ->
        dragAccumulator += delta
    }

    this.draggable(
        state = draggableState,
        orientation = Orientation.Horizontal,
        onDragStarted = { dragAccumulator = 0f },
        onDragStopped = { velocity ->
            when {
                (dragAccumulator >= thresholdPx || velocity > 420f) && dragAccumulator > 8f && onSwipeRight != null -> {
                    runCatching { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    onSwipeRight()
                }
                (dragAccumulator <= -thresholdPx || velocity < -420f) && dragAccumulator < -8f && onSwipeLeft != null -> {
                    runCatching { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    onSwipeLeft()
                }
            }
            dragAccumulator = 0f
        }
    )
}

/**
 * 全局侧滑抽屉互斥协调器：确保同一时刻最多只有一行处于展开状态，滑开新行时自动丝滑收起上一行
 */
private object ActiveSwipeRowCoordinator {
    private var activeRowKey: Any? = null
    private var activeCloseCallback: (() -> Unit)? = null

    fun notifyRowActive(rowKey: Any, closeCallback: () -> Unit) {
        if (activeRowKey != null && activeRowKey != rowKey) {
            activeCloseCallback?.invoke()
        }
        activeRowKey = rowKey
        activeCloseCallback = closeCallback
    }

    fun clearIfMatch(rowKey: Any) {
        if (activeRowKey == rowKey) {
            activeRowKey = null
            activeCloseCallback = null
        }
    }
}

/**
 * 模块双向侧滑（右滑 `→` 或左滑 `←`）呼出的扩展功能按钮定义
 */
data class SwipeActionItem(
    val label: String,
    val iconAsset: String? = null,
    val backgroundColor: Color,
    val contentColor: Color = Color.White,
    val onClick: () -> Unit
)

/**
 * 1:1 iCost 120Hz 原生级丝滑侧滑扩展功能容器：
 * 1. 拖拽期间直接同步写入 `currentOffsetPx`（0 `scope.launch` 协程派发、0 `MutatorMutex` 锁竞争、0 重组），彻底消除 1 帧输入延迟；
 * 2. 抽屉胶囊跟随卡片右缘同步联动滑入 (`translationX`)，消除静态按钮被卡片边缘生硬切割的割裂感；
 * 3. 松手采用高响应临界阻尼物理弹簧 (`dampingRatio = 0.92f, stiffness = 650f`) 衔接手指真实初速度 `initialVelocity`；
 * 4. 展开后在行内任意区域向右轻拨（>15%）或轻点左侧卡片即刻丝滑归位。
 */
@Composable
fun ICostSwipeActionRow(
    actions: List<SwipeActionItem>,
    modifier: Modifier = Modifier,
    actionWidth: Dp = 44.dp,
    actionHeight: Dp = 42.dp,
    enableRightSwipe: Boolean = false,
    content: @Composable () -> Unit
) {
    if (actions.isEmpty()) {
        Box(modifier = modifier) { content() }
        return
    }

    val compactActions = remember(actions) { actions.take(3) }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var currentOffsetPx by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val rowKey = remember { Any() }
    var hapticTriggeredInDrag by remember { mutableStateOf(false) }
    var openOffsetAtDragStart by remember { mutableFloatStateOf(0f) }

    val compactWidthDp = actionWidth.coerceAtMost(46.dp)
    val compactHeightDp = actionHeight.coerceAtMost(44.dp)
    val totalRevealWidthDp = (compactWidthDp * compactActions.size) +
        (5.dp * (compactActions.size - 1).coerceAtLeast(0)) + 12.dp
    val maxRevealPx = with(density) { totalRevealWidthDp.toPx() }
    val openThresholdPx = maxRevealPx * 0.20f
    val closeReturnThresholdPx = maxRevealPx * 0.86f

    val animateToTarget: (Float, Float) -> Unit = remember(scope, rowKey) {
        { target, initialVel ->
            settleJob?.cancel()
            val startVal = currentOffsetPx
            if (abs(startVal - target) < 0.5f && abs(initialVel) < 10f) {
                currentOffsetPx = target
            } else {
                settleJob = scope.launch {
                    animate(
                        initialValue = startVal,
                        targetValue = target,
                        initialVelocity = initialVel.coerceIn(-3200f, 3200f),
                        animationSpec = spring(
                            dampingRatio = 0.92f,
                            stiffness = 650f
                        )
                    ) { value, _ ->
                        currentOffsetPx = value
                    }
                }
            }
        }
    }

    val animateClose: () -> Unit = remember(rowKey, animateToTarget) {
        {
            ActiveSwipeRowCoordinator.clearIfMatch(rowKey)
            animateToTarget(0f, 0f)
        }
    }

    DisposableEffect(rowKey) {
        onDispose {
            settleJob?.cancel()
            ActiveSwipeRowCoordinator.clearIfMatch(rowKey)
        }
    }

    val draggableState = rememberDraggableState { delta ->
        // 同步直接更新位移，不经过任何协程队列或互斥锁，实现 0 帧延迟 1:1 贴手跟滑
        settleJob?.cancel()
        val rawNext = currentOffsetPx + delta
        val clamped = if (!enableRightSwipe) {
            when {
                rawNext > 0f -> 0f
                rawNext < -maxRevealPx -> -maxRevealPx + (rawNext + maxRevealPx) * 0.22f
                else -> rawNext
            }
        } else {
            when {
                openOffsetAtDragStart < -6f && rawNext > 0f -> 0f
                openOffsetAtDragStart > 6f && rawNext < 0f -> 0f
                rawNext > maxRevealPx -> maxRevealPx + (rawNext - maxRevealPx) * 0.22f
                rawNext < -maxRevealPx -> -maxRevealPx + (rawNext + maxRevealPx) * 0.22f
                else -> rawNext
            }
        }
        if (!hapticTriggeredInDrag && abs(clamped - openOffsetAtDragStart) >= openThresholdPx) {
            hapticTriggeredInDrag = true
            runCatching { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        }
        currentOffsetPx = clamped
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                onDragStarted = {
                    settleJob?.cancel()
                    hapticTriggeredInDrag = false
                    openOffsetAtDragStart = currentOffsetPx
                    ActiveSwipeRowCoordinator.notifyRowActive(rowKey, animateClose)
                },
                onDragStopped = { velocity ->
                    val cur = currentOffsetPx
                    val wasOpenLeft = openOffsetAtDragStart < -8f
                    val wasOpenRight = openOffsetAtDragStart > 8f
                    val target = when {
                        wasOpenLeft -> {
                            if (velocity > 100f || cur > -closeReturnThresholdPx) 0f else -maxRevealPx
                        }
                        wasOpenRight -> {
                            if (velocity < -100f || cur < closeReturnThresholdPx) 0f else maxRevealPx
                        }
                        else -> {
                            when {
                                velocity < -220f && cur < -3f -> -maxRevealPx
                                velocity > 180f && cur <= 0f -> 0f
                                enableRightSwipe && velocity > 220f && cur > 3f -> maxRevealPx
                                cur <= -openThresholdPx -> -maxRevealPx
                                enableRightSwipe && cur >= openThresholdPx -> maxRevealPx
                                else -> 0f
                            }
                        }
                    }
                    if (target == 0f) {
                        ActiveSwipeRowCoordinator.clearIfMatch(rowKey)
                    } else {
                        ActiveSwipeRowCoordinator.notifyRowActive(rowKey, animateClose)
                    }
                    animateToTarget(target, velocity)
                }
            )
    ) {
        // 背景紧凑胶囊抽屉：跟随前景卡片右缘渐进联动滑入，避免静态按钮被卡片生硬切割
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(horizontal = 6.dp)
                .graphicsLayer {
                    val cur = currentOffsetPx
                    val revealProgress = (-cur / maxRevealPx).coerceIn(0f, 1f)
                    alpha = (revealProgress * 1.45f).coerceIn(0f, 1f)
                    // 跟随卡片右缘从右侧联动滑出
                    translationX = (1f - revealProgress) * (maxRevealPx * 0.62f)
                    val s = 0.88f + 0.12f * revealProgress
                    scaleX = s
                    scaleY = s
                },
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            compactActions.forEachIndexed { idx, action ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = action.backgroundColor,
                    modifier = Modifier
                        .width(compactWidthDp)
                        .height(compactHeightDp)
                        .graphicsLayer {
                            val cur = currentOffsetPx
                            val revealProgress = (-cur / maxRevealPx).coerceIn(0f, 1f)
                            // 多枚胶囊阶梯式微视差展开，呈现原生 iOS/iCost 抽屉层叠感
                            translationX = (1f - revealProgress) * (compactActions.size - 1 - idx) * 10f
                        }
                        .iCostPressScale(pressedScale = 0.90f) {
                            if (abs(currentOffsetPx) > maxRevealPx * 0.25f) {
                                animateClose()
                                action.onClick()
                            }
                        }
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 3.dp)
                    ) {
                        if (action.iconAsset != null) {
                            ICostAssetImage(
                                assetFile = action.iconAsset,
                                tint = action.contentColor,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.height(1.dp))
                        }
                        Text(
                            text = action.label,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = action.contentColor,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // 前景可滑动模块主体：同步读取 currentOffsetPx，展开态下轻点卡片直接收起菜单
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(currentOffsetPx.roundToInt(), 0) }
                .background(ICostCardWhite)
                .pointerInput(rowKey) {
                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
                        if (abs(currentOffsetPx) > 6f) {
                            val touchSlop = viewConfiguration.touchSlop
                            var dragged = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) {
                                    if (!dragged) {
                                        change.consume()
                                        animateClose()
                                    }
                                    break
                                }
                                if ((change.position - down.position).getDistance() > touchSlop) {
                                    dragged = true
                                    break
                                }
                            }
                        }
                    }
                }
        ) {
            content()
        }
    }
}

/**
 * 顶部圆形白底图标按钮（用于 `<` 返回、账本切换、`•••` 更多等，自带弹簧按压回弹）
 */
@Composable
fun ICostCircleIconButton(
    onClick: () -> Unit,
    size: Dp = 42.dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .size(size)
            .iCostPressScale(pressedScale = 0.90f, onClick = onClick)
            .shadow(
                elevation = 2.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.04f),
                spotColor = Color.Black.copy(alpha = 0.06f)
            ),
        shape = CircleShape,
        color = ICostCardWhite
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

/**
 * 1:1 iCost 右上角薄荷绿插画头像按钮（点击进入全屏「设置」，自带弹簧按压回弹）
 */
@Composable
fun ICostAvatarButton(
    onClick: () -> Unit,
    size: Dp = 42.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .iCostPressScale(pressedScale = 0.90f, onClick = onClick)
            .shadow(
                elevation = 3.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.05f),
                spotColor = Color.Black.copy(alpha = 0.08f)
            )
            .clip(CircleShape)
            .background(Color(0xFFC6F3EA))
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size * 0.78f)) {
            val w = this.size.width
            val h = this.size.height
            // 衣领肩膀
            drawRoundRect(
                color = Color(0xFF1F2430),
                topLeft = Offset(w * 0.20f, h * 0.72f),
                size = Size(w * 0.60f, h * 0.34f),
                cornerRadius = CornerRadius(w * 0.24f, w * 0.24f)
            )
            // 脸部
            drawCircle(
                color = Color(0xFFFFEFE6),
                radius = w * 0.26f,
                center = Offset(w * 0.50f, h * 0.49f)
            )
            // 刘海与头发轮廓
            val hairPath = Path().apply {
                moveTo(w * 0.22f, h * 0.46f)
                cubicTo(w * 0.22f, h * 0.16f, w * 0.78f, h * 0.16f, w * 0.78f, h * 0.46f)
                cubicTo(w * 0.68f, h * 0.35f, w * 0.56f, h * 0.34f, w * 0.48f, h * 0.40f)
                cubicTo(w * 0.38f, h * 0.34f, w * 0.28f, h * 0.38f, w * 0.22f, h * 0.46f)
                close()
            }
            drawPath(hairPath, color = Color(0xFF1B1F26))
            // 双眼与微笑
            drawCircle(Color(0xFF1B1F26), radius = w * 0.032f, center = Offset(w * 0.41f, h * 0.50f))
            drawCircle(Color(0xFF1B1F26), radius = w * 0.032f, center = Offset(w * 0.59f, h * 0.50f))
            drawArc(
                color = Color(0xFFE07A68),
                startAngle = 20f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(w * 0.43f, h * 0.53f),
                size = Size(w * 0.14f, h * 0.09f),
                style = Stroke(width = w * 0.032f, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * 默记（MoJi）「墨册朱签」品牌应用图标徽章（1:1 矢量渲染 Adaptive Icon 可视区）
 */
@Composable
fun MoJiBrandIconBadge(
    onClick: (() -> Unit)? = null,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    val cornerRadius = size * 0.24f
    val shape = RoundedCornerShape(cornerRadius)
    val pressModifier = if (onClick != null) {
        Modifier.iCostPressScale(pressedScale = 0.92f, onClick = onClick)
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .size(size)
            .then(pressModifier)
            .shadow(
                elevation = 2.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.10f)
            )
            .clip(shape)
            .border(0.6.dp, Color.Black.copy(alpha = 0.07f), shape),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_background),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.5f
                    scaleY = 1.5f
                }
        )
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "默记图标",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.5f
                    scaleY = 1.5f
                }
        )
    }
}

data class AccountBrandOption(
    val code: String,
    val label: String,
    val assetFile: String,
    val defaultType: WalletType,
    val subTypeLabel: String,
    val hasWhiteBg: Boolean = false
)

val ICostAccountBrandCatalog: List<AccountBrandOption> = listOf(
    // 网络与数字钱包
    AccountBrandOption("ALIPAY", "支付宝 / 账户余额", "acc_alipay.webp", WalletType.DIGITAL_WALLET, "支付宝"),
    AccountBrandOption("YUEBAO", "余额宝", "acc_yuebao.webp", WalletType.DIGITAL_WALLET, "余额宝"),
    AccountBrandOption("WECHAT", "微信零钱", "acc_wechat.webp", WalletType.DIGITAL_WALLET, "微信支付"),
    AccountBrandOption("WECHAT_LQT", "零钱通", "acc_wechat_lqt.webp", WalletType.DIGITAL_WALLET, "零钱通"),
    AccountBrandOption("QQ", "QQ钱包", "acc_qq.webp", WalletType.DIGITAL_WALLET, "QQ钱包"),
    AccountBrandOption("DOUYIN", "抖音钱包", "acc_douyin.webp", WalletType.DIGITAL_WALLET, "抖音支付"),
    AccountBrandOption("MEITUAN", "美团钱包", "acc_meituan.webp", WalletType.DIGITAL_WALLET, "美团支付"),
    AccountBrandOption("UNIONPAY", "云闪付", "acc_unionpay.webp", WalletType.DIGITAL_WALLET, "云闪付"),
    // 信用账户
    AccountBrandOption("HUABEI", "花呗", "acc_huabei.webp", WalletType.CREDIT, "蚂蚁花呗"),
    AccountBrandOption("JIEBEI", "借呗", "acc_jiebei.webp", WalletType.CREDIT, "借呗"),
    AccountBrandOption("JD", "京东白条", "acc_baitiao.webp", WalletType.CREDIT, "京东白条"),
    AccountBrandOption("JINTIAO", "京东金条", "acc_jintiao.webp", WalletType.CREDIT, "京东金条"),
    AccountBrandOption("WEILIDAI", "微粒贷", "acc_weilidai.webp", WalletType.CREDIT, "微粒贷"),
    AccountBrandOption("OTHER_CREDIT", "度小满 / 其他信用", "acc_other_credit.webp", WalletType.CREDIT, "其他信用"),
    AccountBrandOption("MEITUAN_MONTHLY", "美团月付", "acc_meituan_monthly.webp", WalletType.CREDIT, "美团月付"),
    AccountBrandOption("CREDIT_CARD", "通用信用卡", "acc_credit_card.webp", WalletType.CREDIT, "信用卡"),
    // 银行卡（储蓄卡/信用卡皆可绑定）
    AccountBrandOption("CMB", "招商银行", "bank_cmb.webp", WalletType.DEBIT_BANK, "招商银行", hasWhiteBg = true),
    AccountBrandOption("ICBC", "工商银行", "bank_icbc.webp", WalletType.DEBIT_BANK, "工商银行", hasWhiteBg = true),
    AccountBrandOption("CCB", "建设银行", "bank_ccb.webp", WalletType.DEBIT_BANK, "建设银行", hasWhiteBg = true),
    AccountBrandOption("ABC", "农业银行", "bank_abc.webp", WalletType.DEBIT_BANK, "农业银行", hasWhiteBg = true),
    AccountBrandOption("BOC", "中国银行", "bank_boc.webp", WalletType.DEBIT_BANK, "中国银行", hasWhiteBg = true),
    AccountBrandOption("BOCOM", "交通银行", "bank_bocom.webp", WalletType.DEBIT_BANK, "交通银行", hasWhiteBg = true),
    AccountBrandOption("CMBC", "民生银行", "bank_cmbc.webp", WalletType.DEBIT_BANK, "民生银行", hasWhiteBg = true),
    AccountBrandOption("PSBC", "邮储银行", "bank_psbc.webp", WalletType.DEBIT_BANK, "邮储银行", hasWhiteBg = true),
    AccountBrandOption("SPDB", "浦发银行", "bank_spdb.webp", WalletType.DEBIT_BANK, "浦发银行", hasWhiteBg = true),
    AccountBrandOption("CIB", "兴业银行", "bank_cib.webp", WalletType.DEBIT_BANK, "兴业银行", hasWhiteBg = true),
    AccountBrandOption("CITIC", "中信银行", "bank_citic.webp", WalletType.DEBIT_BANK, "中信银行", hasWhiteBg = true),
    AccountBrandOption("CEB", "光大银行", "bank_ceb.webp", WalletType.DEBIT_BANK, "光大银行", hasWhiteBg = true),
    AccountBrandOption("PAB", "平安银行", "bank_pab.webp", WalletType.DEBIT_BANK, "平安银行", hasWhiteBg = true),
    AccountBrandOption("CGB", "广发银行", "bank_cgb.webp", WalletType.DEBIT_BANK, "广发银行", hasWhiteBg = true),
    // 现金、卡券与理财
    AccountBrandOption("CASH", "现金钱包", "acc_cash.webp", WalletType.CASH_OTHER, "现金"),
    AccountBrandOption("DEBIT_CARD", "储蓄卡", "acc_debit_card.webp", WalletType.DEBIT_BANK, "储蓄卡"),
    AccountBrandOption("SAVING", "存折/定期", "acc_saving.webp", WalletType.DEBIT_BANK, "定期存款"),
    AccountBrandOption("FUND", "基金账户", "acc_fund.webp", WalletType.CASH_OTHER, "基金理财"),
    AccountBrandOption("STOCK", "股票账户", "acc_stock.webp", WalletType.CASH_OTHER, "股票证券"),
    AccountBrandOption("PROVIDENT", "公积金", "acc_provident.webp", WalletType.CASH_OTHER, "住房公积金"),
    AccountBrandOption("MEDICAL_CARD", "医保卡", "acc_medical_card.webp", WalletType.CASH_OTHER, "医保账户"),
    AccountBrandOption("TRANSIT_CARD", "公交卡", "acc_transit_card.webp", WalletType.CASH_OTHER, "交通卡"),
    AccountBrandOption("MEAL_CARD", "饭卡/校园卡", "acc_meal_card.webp", WalletType.CASH_OTHER, "校园/餐卡"),
    AccountBrandOption("MEMBER_CARD", "会员储值卡", "acc_member_card.webp", WalletType.CASH_OTHER, "会员卡")
)

fun resolveWalletAssetFile(wallet: WalletEntity): Pair<String, Boolean> {
    val brand = wallet.brandCode.uppercase(Locale.ROOT)
    val name = wallet.name

    ICostAccountBrandCatalog.firstOrNull { it.code == brand }?.let {
        return it.assetFile to it.hasWhiteBg
    }

    return when {
        name.contains("白条") -> "acc_baitiao.webp" to false
        name.contains("金条") -> "acc_jintiao.webp" to false
        name.contains("花呗") -> "acc_huabei.webp" to false
        name.contains("借呗") -> "acc_jiebei.webp" to false
        name.contains("度小满") -> "acc_other_credit.webp" to false
        name.contains("微粒贷") -> "acc_weilidai.webp" to false
        name.contains("美团月付") -> "acc_meituan_monthly.webp" to false
        name.contains("美团") -> "acc_meituan.webp" to false
        name.contains("零钱通") -> "acc_wechat_lqt.webp" to false
        name.contains("微信") || name.contains("零钱") -> "acc_wechat.webp" to false
        name.contains("余额宝") -> "acc_yuebao.webp" to false
        name.contains("支付宝") || name.contains("账户余额") || name.contains("余额") -> "acc_alipay.webp" to false
        name.contains("云闪付") -> "acc_unionpay.webp" to false
        name.contains("抖音") -> "acc_douyin.webp" to false
        name.contains("招商") || name.contains("招行") -> "bank_cmb.webp" to true
        name.contains("工商") || name.contains("工行") -> "bank_icbc.webp" to true
        name.contains("建设") || name.contains("建行") -> "bank_ccb.webp" to true
        name.contains("农业") || name.contains("农行") -> "bank_abc.webp" to true
        name.contains("中国银行") || name.contains("中行") -> "bank_boc.webp" to true
        name.contains("交通银行") || name.contains("交行") -> "bank_bocom.webp" to true
        name.contains("民生") -> "bank_cmbc.webp" to true
        name.contains("邮储") || name.contains("邮政") -> "bank_psbc.webp" to true
        name.contains("浦发") -> "bank_spdb.webp" to true
        name.contains("兴业") -> "bank_cib.webp" to true
        name.contains("中信") -> "bank_citic.webp" to true
        name.contains("光大") -> "bank_ceb.webp" to true
        name.contains("平安") -> "bank_pab.webp" to true
        name.contains("广发") -> "bank_cgb.webp" to true
        name.contains("公积金") -> "acc_provident.webp" to false
        name.contains("医保") -> "acc_medical_card.webp" to false
        name.contains("公交") || name.contains("地铁") -> "acc_transit_card.webp" to false
        name.contains("基金") -> "acc_fund.webp" to false
        name.contains("股票") || name.contains("证券") -> "acc_stock.webp" to false
        name.contains("现金") -> "acc_cash.webp" to false
        wallet.type == WalletType.CREDIT -> "acc_credit_card.webp" to false
        wallet.type == WalletType.DEBIT_BANK -> "acc_debit_card.webp" to false
        wallet.type == WalletType.DIGITAL_WALLET -> "acc_alipay.webp" to false
        else -> "acc_other_asset.webp" to false
    }
}

private fun resolveGlyphCircleBgColor(assetFile: String): Color? = when (assetFile) {
    "acc_other_credit.webp", "acc_cash.webp" -> Color(0xFFF5B400)
    "acc_credit_card.webp", "acc_fund.webp", "acc_stock.webp" -> Color(0xFFE65540)
    "acc_debit_card.webp", "acc_saving.webp" -> Color(0xFF22B573)
    "acc_provident.webp", "acc_medical_card.webp", "acc_transit_card.webp" -> Color(0xFF1677FF)
    "acc_meal_card.webp", "acc_member_card.webp", "acc_other_asset.webp" -> Color(0xFF8E74F1)
    else -> null
}

/**
 * 1:1 iCost 真实品牌圆形图标（招行葵花标、京东白条红底标、花呗蓝标、度小满黄底星章、支付宝、微信等）
 */
@Composable
fun WalletBrandAvatar(wallet: WalletEntity, size: Dp = 40.dp) {
    val (assetFile, hasWhiteBg) = resolveWalletAssetFile(wallet)
    val glyphBgColor = resolveGlyphCircleBgColor(assetFile)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .then(
                when {
                    glyphBgColor != null -> Modifier.background(glyphBgColor)
                    hasWhiteBg -> Modifier
                        .background(Color.White)
                        .border(0.8.dp, Color(0xFFEDEDED), CircleShape)
                    else -> Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (glyphBgColor != null) {
            ICostAssetImage(
                assetFile = assetFile,
                modifier = Modifier.size(size * 0.54f),
                contentScale = ContentScale.Fit
            )
        } else {
            ICostAssetImage(
                assetFile = assetFile,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }
    }
}

/**
 * 解析交易分类对应的 iCost 原生线性图标文件名（支持二级分类如 "餐饮-零食"）
 */
fun resolveCategoryIconAsset(
    category: String,
    type: TransactionType = TransactionType.EXPENSE
): String = resolveCategoryAssetFile(category, type)

fun resolveCategoryAssetFile(
    category: String,
    type: TransactionType = TransactionType.EXPENSE
): String {
    if (CategoryCatalog.isPendingTriage(category)) return "setting_auto.webp"
    if (type == TransactionType.REFUND_OFFSET) return "cat_refund.webp"
    if (type == TransactionType.TRANSFER) return "transfer_swift.webp"
    if (type == TransactionType.REPAYMENT) return "card_outcome_icon.webp"
    if (type == TransactionType.LOAN_IN || type == TransactionType.LOAN_OUT) return "cat_relationship.webp"

    val parts = category.split("-", "·", "/").map { it.trim() }.filter { it.isNotEmpty() }
    val mainPart = parts.firstOrNull() ?: category
    val subPart = parts.getOrNull(1)

    val primaryList = if (type == TransactionType.INCOME) {
        CategoryCatalog.incomeCategories
    } else {
        CategoryCatalog.expenseCategories
    }
    val secondaryList = if (type == TransactionType.INCOME) {
        CategoryCatalog.expenseCategories
    } else {
        CategoryCatalog.incomeCategories
    }

    fun matchFromList(cats: List<com.moji.ai.data.model.CategoryDef>): String? {
        if (!subPart.isNullOrBlank()) {
            // 1. 优先精确匹配「主分类名 + 子分类名」
            cats.firstOrNull { it.name == mainPart }
                ?.subCategories
                ?.firstOrNull { it.name == subPart }
                ?.let { return it.assetFile }
            // 2. 次优匹配任意一级分类下的同名二级分类
            cats.forEach { cat ->
                cat.subCategories.firstOrNull { it.name == subPart }?.let { return it.assetFile }
            }
        }
        // 3. 精确匹配一级分类名
        cats.firstOrNull { it.name == category || it.name == mainPart }?.let { return it.assetFile }
        // 4. 精确匹配二级分类名
        cats.forEach { cat ->
            cat.subCategories.firstOrNull { it.name == category || it.name == mainPart }?.let {
                return it.assetFile
            }
        }
        return null
    }

    matchFromList(primaryList)?.let { return it }
    matchFromList(secondaryList)?.let { return it }

    // 语义模糊兜底
    return when {
        category.contains("退款") -> "cat_refund.webp"
        category.contains("零食") || category.contains("甜品") -> "sub_snacks.webp"
        category.contains("水果") -> "sub_fruit.webp"
        category.contains("蔬菜") || category.contains("买菜") -> "sub_vegetable.webp"
        category.contains("咖啡") -> "sub_coffee.webp"
        category.contains("奶茶") -> "sub_milktea.webp"
        category.contains("餐") || category.contains("饭") || category.contains("外卖") -> "cat_dining.webp"
        category.contains("淘宝") -> "sub_taobao.webp"
        category.contains("京东") -> "sub_jd.webp"
        category.contains("拼多多") -> "sub_pdd.webp"
        category.contains("超市") -> "sub_supermarket.webp"
        category.contains("购物") || category.contains("网购") -> "cat_shopping.webp"
        category.contains("服饰") || category.contains("衣") || category.contains("鞋") -> "cat_dress.webp"
        category.contains("日用") -> "cat_daily.webp"
        category.contains("手机") -> "sub_phone.webp"
        category.contains("电脑") -> "sub_laptop.webp"
        category.contains("耳机") -> "sub_earphone.webp"
        category.contains("数码") -> "cat_digital.webp"
        category.contains("美妆") -> "cat_makeup.webp"
        category.contains("护肤") -> "cat_skincare.webp"
        category.contains("软件") || category.contains("应用") || category.contains("会员") || category.contains("订阅") -> "cat_app_software.webp"
        category.contains("房租") -> "sub_rent.webp"
        category.contains("水电") -> "sub_utilities.webp"
        category.contains("物业") -> "sub_property_fee.webp"
        category.contains("住房") || category.contains("房") -> "cat_housing.webp"
        category.contains("公交") -> "sub_bus.webp"
        category.contains("地铁") -> "sub_subway.webp"
        category.contains("打车") -> "sub_taxi.webp"
        category.contains("火车") || category.contains("高铁") -> "sub_train.webp"
        category.contains("飞机") || category.contains("机票") -> "sub_flight.webp"
        category.contains("交通") -> "cat_transit.webp"
        category.contains("娱乐") || category.contains("游戏") || category.contains("电影") -> "cat_entertainment.webp"
        category.contains("药") -> "sub_medicine.webp"
        category.contains("医疗") || category.contains("医院") -> "cat_medical.webp"
        category.contains("通讯") || category.contains("话费") -> "cat_telecom.webp"
        category.contains("加油") -> "sub_fuel.webp"
        category.contains("停车") -> "sub_parking.webp"
        category.contains("汽车") -> "cat_car.webp"
        category.contains("书") -> "sub_books.webp"
        category.contains("学习") || category.contains("课") -> "cat_study.webp"
        category.contains("办公") -> "cat_office.webp"
        category.contains("运动") || category.contains("健身") -> "cat_sports.webp"
        category.contains("社交") -> "cat_social.webp"
        category.contains("红包") -> "cat_red_packet.webp"
        category.contains("礼金") -> "cat_cash_gift.webp"
        category.contains("礼物") -> "cat_gift.webp"
        category.contains("人情") -> "cat_relationship.webp"
        category.contains("育儿") || category.contains("母婴") -> "cat_baby.webp"
        category.contains("旅行") || category.contains("酒店") -> "cat_travel.webp"
        category.contains("宠物") -> "cat_pet.webp"
        category.contains("烟") || category.contains("酒") -> "cat_tobacco_alcohol.webp"
        category.contains("彩票") -> "cat_lottery.webp"
        category.contains("工资") || category.contains("薪") -> "cat_salary.webp"
        category.contains("奖金") -> "cat_bonus.webp"
        category.contains("兼职") -> "cat_parttime.webp"
        category.contains("理财") || category.contains("利息") -> "cat_investment.webp"
        category.contains("报销") -> "cat_claim.webp"
        category.contains("福利") -> "cat_welfare.webp"
        else -> "cat_other.webp"
    }
}

fun resolveTransactionBadgeColor(type: TransactionType): Color = when (type) {
    TransactionType.EXPENSE -> ICostCoralRed
    TransactionType.REFUND_OFFSET -> ICostAmberGold
    TransactionType.INCOME -> ICostGreenIncome
    TransactionType.TRANSFER -> ICostBlueAccent
    TransactionType.REPAYMENT -> ICostBlueAccent
    TransactionType.LOAN_IN -> Color(0xFF4B5563)
    TransactionType.LOAN_OUT -> Color(0xFF10B981)
}

/**
 * 1:1 iCost 彩色实心圆 + 纯白线性分类图标徽章（订阅 CategoryCatalog.versionFlow，改图标后全端实时重组）
 */
@Composable
fun ICostCategoryBadge(
    category: String,
    type: TransactionType,
    size: Dp = 42.dp,
    badgeColor: Color = if (CategoryCatalog.isPendingTriage(category)) Color(0xFF6366F1) else resolveTransactionBadgeColor(type),
    iconRatio: Float = 0.56f
) {
    val catalogVersion by CategoryCatalog.versionFlow.collectAsState()
    val assetFile = remember(category, type, catalogVersion) {
        resolveCategoryAssetFile(category, type)
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(badgeColor),
        contentAlignment = Alignment.Center
    ) {
        ICostAssetImage(
            assetFile = assetFile,
            tint = Color.White,
            modifier = Modifier.size(size * iconRatio)
        )
    }
}

/**
 * 统计页环形图与排行使用的 iCost 多彩配色序列
 */
val ICostChartPalette: List<Color> = listOf(
    Color(0xFFE65540),
    Color(0xFFF59E0B),
    Color(0xFF3B82F6),
    Color(0xFF22B573),
    Color(0xFF8B5CF6),
    Color(0xFFEC4899),
    Color(0xFF06B6D4),
    Color(0xFF6366F1)
)

/**
 * 1:1 iCost 统一底部抽屉顶栏：左侧 38dp 白色圆形 `✕` 关闭按钮 + 居中主副标题 + 右侧对称占位或操作区
 */
@Composable
fun ICostSheetHeader(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ICostCircleIconButton(onClick = onClose, size = 38.dp) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭",
                tint = ICostTextPrimary,
                modifier = Modifier.size(18.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = ICostTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = ICostTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Box(
            modifier = Modifier.size(38.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            if (trailingContent != null) {
                trailingContent()
            }
        }
    }
}

/**
 * 1:1 iCost 底部抽屉内分组白卡容器（置于 ICostBgGray 背景上）
 */
@Composable
fun ICostFormCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ICostCardWhite,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
        }
    }
}

/**
 * 1:1 iCost 无黑框圆角表单输入框（替代生硬且双列易折行的 Material3 OutlinedTextField）：
 * 顶部单行清晰小标题 + 柔和灰底圆角输入胶囊（聚焦时蓝色细描边，报错时珊瑚红细描边）+ 底部提示/报错信息
 */
@Composable
fun ICostFormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    supportingText: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    containerColor: Color = Color(0xFFF3F4F6),
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingContent: (@Composable () -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val borderColor = when {
        isError -> ICostCoralRed
        isFocused -> ICostBlueAccent
        else -> Color.Transparent
    }
    val bgColor = when {
        isError -> Color(0xFFFFF5F3)
        isFocused -> Color(0xFFEFF5FF)
        else -> containerColor
    }

    Column(modifier = modifier) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isError) ICostCoralRed else ICostTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = bgColor,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (borderColor != Color.Transparent) {
                        Modifier.border(1.2.dp, borderColor, RoundedCornerShape(14.dp))
                    } else {
                        Modifier
                    }
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (minLines > 1) (minLines * 22 + 20).dp else 46.dp)
                    .padding(horizontal = 14.dp, vertical = if (minLines > 1) 10.dp else 11.dp),
                verticalAlignment = if (minLines > 1) Alignment.Top else Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            fontSize = 14.sp,
                            color = ICostTextMuted,
                            maxLines = if (singleLine) 1 else maxLines,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        readOnly = readOnly,
                        singleLine = singleLine,
                        minLines = minLines,
                        maxLines = maxLines,
                        keyboardOptions = keyboardOptions,
                        visualTransformation = visualTransformation,
                        cursorBrush = SolidColor(ICostBlueAccent),
                        textStyle = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = ICostTextPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isFocused = it.isFocused }
                    )
                }
                if (trailingContent != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    trailingContent()
                }
            }
        }

        if (!supportingText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = supportingText,
                fontSize = 11.sp,
                color = if (isError) ICostCoralRed else ICostTextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun iCostUnifiedSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = ICostBlueAccent,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = Color(0xFFE0E0E5),
    uncheckedBorderColor = Color.Transparent
)


