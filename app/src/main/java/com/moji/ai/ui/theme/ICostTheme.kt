package com.moji.ai.ui.theme

import android.graphics.BitmapFactory
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.R
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CategoryCatalog
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

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
    AccountBrandOption("JIEBEI", "借呗", "acc_jiebei.webp", WalletType.CREDIT, "蚂蚁借呗"),
    AccountBrandOption("JD", "京东白条", "acc_baitiao.webp", WalletType.CREDIT, "京东白条"),
    AccountBrandOption("JINTIAO", "京东金条", "acc_jintiao.webp", WalletType.CREDIT, "京东金条"),
    AccountBrandOption("WEILIDAI", "微粒贷", "acc_weilidai.webp", WalletType.CREDIT, "微粒贷"),
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

/**
 * 1:1 iCost 真实品牌圆形图标（招行葵花标、京东白条红底标、花呗蓝标、支付宝、微信等）
 */
@Composable
fun WalletBrandAvatar(wallet: WalletEntity, size: Dp = 40.dp) {
    val (assetFile, hasWhiteBg) = resolveWalletAssetFile(wallet)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (hasWhiteBg) {
                    Modifier
                        .background(Color.White)
                        .border(0.8.dp, Color(0xFFEDEDED), CircleShape)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        ICostAssetImage(
            assetFile = assetFile,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    }
}

/**
 * 解析交易分类对应的 iCost 原生线性图标文件名（支持二级分类如 "餐饮-零食"）
 */
fun resolveCategoryAssetFile(
    category: String,
    type: TransactionType = TransactionType.EXPENSE
): String {
    if (type == TransactionType.REFUND_OFFSET) return "cat_refund.webp"
    if (type == TransactionType.TRANSFER) return "transfer_swift.webp"
    if (type == TransactionType.REPAYMENT) return "card_outcome_icon.webp"
    if (type == TransactionType.LOAN_IN || type == TransactionType.LOAN_OUT) return "cat_relationship.webp"

    val parts = category.split("-", "·", "/").map { it.trim() }.filter { it.isNotEmpty() }
    val mainPart = parts.firstOrNull() ?: category
    val subPart = parts.getOrNull(1)

    val allCats = CategoryCatalog.expenseCategories + CategoryCatalog.incomeCategories

    // 优先精确匹配二级分类图标（如 零食 -> sub_snacks.webp）
    if (!subPart.isNullOrBlank()) {
        allCats.forEach { cat ->
            cat.subCategories.firstOrNull { it.name == subPart }?.let { return it.assetFile }
        }
    }

    // 再匹配整个名称是否直接命中二级分类或一级分类
    allCats.forEach { cat ->
        if (cat.name == category || cat.name == mainPart) {
            return cat.assetFile
        }
        cat.subCategories.firstOrNull { it.name == category || it.name == mainPart }?.let {
            return it.assetFile
        }
    }

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
 * 1:1 iCost 彩色实心圆 + 纯白线性分类图标徽章
 */
@Composable
fun ICostCategoryBadge(
    category: String,
    type: TransactionType,
    size: Dp = 42.dp,
    badgeColor: Color = resolveTransactionBadgeColor(type),
    iconRatio: Float = 0.56f
) {
    val assetFile = resolveCategoryAssetFile(category, type)
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
