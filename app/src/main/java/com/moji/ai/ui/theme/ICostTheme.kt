package com.moji.ai.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.TransactionType
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

val ICostBgGray = Color(0xFFF6F6F6)
val ICostCardWhite = Color(0xFFFFFFFF)
val ICostCellGray = Color(0xFFF7F7F8)
val ICostCoralRed = Color(0xFFE65540)
val ICostAmberGold = Color(0xFFF5B400)
val ICostBlueAccent = Color(0xFF1677FF)
val ICostGreenIncome = Color(0xFF22B573)
val ICostTextPrimary = Color(0xFF141414)
val ICostTextSecondary = Color(0xFF8C8C8C)
val ICostSearchBg = Color(0xFFEDEDED)
val ICostDivider = Color(0xFFF1F1F1)

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

fun formatWalletDisplayName(wallet: WalletEntity?, fallback: String = "账户余额"): String {
    if (wallet == null) return fallback
    return if (!wallet.cardLast4.isNullOrBlank()) {
        "${wallet.name} ${wallet.cardLast4}"
    } else {
        wallet.name
    }
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

@Composable
fun WalletBrandAvatar(wallet: WalletEntity, size: Dp = 42.dp) {
    val brand = wallet.brandCode.uppercase()
    val name = wallet.name

    when {
        brand == "JD" || name.contains("白条") || name.contains("京东") -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(Color(0xFFE1251B)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "京东\n白条",
                    color = Color.White,
                    fontSize = 10.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
            }
        }

        brand == "CMB" || name.contains("招商") || name.contains("招行") -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(Color(0xFFFDF2F2))
                    .border(1.dp, Color(0xFFF5D0D0), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(size * 0.72f)
                        .clip(CircleShape)
                        .background(Color(0xFFD92B2B)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("M", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black)
                }
            }
        }

        brand == "HUABEI" || name.contains("花呗") || name.contains("借呗") -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(Color(0xFFEFF8FF))
                    .border(1.dp, Color(0xFFD6EEFF), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(size * 0.72f)
                        .clip(CircleShape)
                        .background(Color(0xFF00A0E9)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (name.contains("借呗")) "借" else "花",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        brand == "WECHAT" || name.contains("微信") || name.contains("零钱") -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(Color(0xFF07C160)),
                contentAlignment = Alignment.Center
            ) {
                Text("微", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            }
        }

        brand == "ALIPAY" || name.contains("支付宝") || name.contains("余额") -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(Color(0xFF1677FF)),
                contentAlignment = Alignment.Center
            ) {
                Text("支", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            }
        }

        else -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(Color(wallet.colorHex)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (wallet.type.isLiability) Icons.Default.CreditCard else Icons.Default.AccountBalanceWallet,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.5f)
                )
            }
        }
    }
}

fun resolveCategoryIcon(category: String, type: TransactionType = TransactionType.EXPENSE): ImageVector {
    if (type == TransactionType.REFUND_OFFSET) return Icons.Default.CurrencyExchange
    if (type == TransactionType.TRANSFER) return Icons.Default.SwapHoriz
    if (type == TransactionType.REPAYMENT) return Icons.Default.CreditCard
    if (type == TransactionType.LOAN_IN || type == TransactionType.LOAN_OUT) return Icons.Default.Savings
    if (type == TransactionType.INCOME) {
        return when {
            category.contains("工资") -> Icons.Default.Work
            category.contains("理财") -> Icons.Default.TrendingUp
            category.contains("红包") -> Icons.Default.CardGiftcard
            else -> Icons.Default.Payments
        }
    }

    return when {
        category.contains("零食") || category.contains("甜品") || category.contains("水果") -> Icons.Default.Cake
        category.contains("咖啡") || category.contains("奶茶") || category.contains("茶") -> Icons.Default.LocalCafe
        category.contains("餐") || category.contains("饭") || category.contains("菜") || category.contains("外卖") -> Icons.Default.Restaurant
        category.contains("购物") || category.contains("超市") || category.contains("网购") -> Icons.Default.ShoppingCart
        category.contains("服饰") || category.contains("衣") || category.contains("鞋") -> Icons.Default.Checkroom
        category.contains("日用") || category.contains("清洁") -> Icons.Default.CleaningServices
        category.contains("数码") || category.contains("手机") || category.contains("电脑") -> Icons.Default.PhoneIphone
        category.contains("美妆") -> Icons.Default.Face
        category.contains("护肤") -> Icons.Default.Spa
        category.contains("软件") || category.contains("应用") || category.contains("会员") || category.contains("订阅") -> Icons.Default.Apps
        category.contains("住房") || category.contains("房") || category.contains("水电") -> Icons.Default.Home
        category.contains("交通") || category.contains("公交") || category.contains("地铁") || category.contains("打车") -> Icons.Default.DirectionsBus
        category.contains("汽车") || category.contains("加油") || category.contains("停车") -> Icons.Default.DirectionsCar
        category.contains("娱乐") || category.contains("游戏") || category.contains("电影") -> Icons.Default.SportsEsports
        category.contains("医疗") || category.contains("药") -> Icons.Default.LocalHospital
        category.contains("通讯") || category.contains("话费") -> Icons.Default.Call
        category.contains("学习") || category.contains("书") -> Icons.Default.MenuBook
        else -> Icons.Default.AutoAwesome
    }
}

fun resolveTransactionBadgeColor(type: TransactionType): Color = when (type) {
    TransactionType.EXPENSE -> ICostCoralRed
    TransactionType.REFUND_OFFSET -> ICostAmberGold
    TransactionType.INCOME -> ICostGreenIncome
    TransactionType.TRANSFER -> ICostBlueAccent
    TransactionType.REPAYMENT -> Color(0xFF7C3AED)
    TransactionType.LOAN_IN -> Color(0xFF4B5563)
    TransactionType.LOAN_OUT -> Color(0xFF10B981)
}
