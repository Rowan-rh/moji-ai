package com.moji.ai.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.moji.ai.ai.AiConfig
import com.moji.ai.ai.AiProviderType
import com.moji.ai.data.local.RecurringRuleEntity
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.RecurrenceFrequency
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType
import com.moji.ai.service.PaymentAccessibilityService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoJiMainScreen(viewModel: MainViewModel) {
    val wallets by viewModel.wallets.collectAsState()
    val transactions by viewModel.transactions.collectAsState()
    val recurringRules by viewModel.recurringRules.collectAsState()
    val aiConfig by viewModel.aiConfig.collectAsState()
    val sandboxStatus by viewModel.sandboxStatus.collectAsState()
    val isParsing by viewModel.isParsing.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddTxDialog by remember { mutableStateOf(false) }
    var showAddWalletDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("默记 MoJi", fontWeight = FontWeight.Bold)
                        Text(
                            "无感静默识账 · 多钱包/转账/信用还款/退款冲销",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(onClick = { showAddTxDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "记一笔")
                }
            } else if (selectedTab == 1) {
                FloatingActionButton(onClick = { showAddWalletDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "新增钱包")
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.ReceiptLong, contentDescription = null) },
                    label = { Text("账单流水") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) },
                    label = { Text("全能钱包") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                    label = { Text("AI 识账沙盒") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("AI与权限") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> LedgerOverviewTab(
                    wallets = wallets,
                    transactions = transactions,
                    onUndoTx = { viewModel.undoTransaction(it) }
                )
                1 -> WalletsAndRecurringTab(
                    wallets = wallets,
                    recurringRules = recurringRules,
                    onTriggerRuleNow = { viewModel.triggerRecurringRuleNow(it) }
                )
                2 -> AiSandboxTab(
                    isParsing = isParsing,
                    statusMessage = sandboxStatus,
                    onRunSimulation = { text, pkg -> viewModel.simulateCapturedEvent(text, pkg) }
                )
                3 -> AiAndPermissionsSettingsTab(
                    config = aiConfig,
                    onSaveConfig = { viewModel.updateAiConfig(it) }
                )
            }
        }
    }

    if (showAddTxDialog && wallets.isNotEmpty()) {
        ManualTransactionDialog(
            wallets = wallets,
            onDismiss = { showAddTxDialog = false },
            onConfirm = { type, amount, currency, category, merchant, note, fromId, toId ->
                viewModel.addManualTransaction(type, amount, currency, category, merchant, note, fromId, toId)
                showAddTxDialog = false
            }
        )
    }

    if (showAddWalletDialog) {
        AddWalletDialog(
            onDismiss = { showAddWalletDialog = false },
            onConfirm = { name, type, currency, balance, limit, last4, aliases ->
                viewModel.addManualWallet(name, type, currency, balance, limit, last4, aliases)
                showAddWalletDialog = false
            }
        )
    }
}

@Composable
private fun LedgerOverviewTab(
    wallets: List<WalletEntity>,
    transactions: List<TransactionEntity>,
    onUndoTx: (Long) -> Unit
) {
    val walletMap = remember(wallets) { wallets.associateBy { it.id } }

    // 计算净资产、总资产、总负债（全部折算为本位币 CNY）
    val totalAssetsCnyCents = wallets.filter { !it.type.isLiability && it.includeInNetWorth }.sumOf { w ->
        val rate = CurrencyCode.fromCode(w.currency).defaultRateToCny
        (w.balanceCents * rate).roundToLong()
    }
    val totalLiabilityCnyCents = wallets.filter { it.type.isLiability && it.includeInNetWorth }.sumOf { w ->
        val rate = CurrencyCode.fromCode(w.currency).defaultRateToCny
        (w.balanceCents * rate).roundToLong()
    }
    val netWorthCnyCents = totalAssetsCnyCents - totalLiabilityCnyCents

    // 计算当月净支出（已自动扣除被退款/AA冲销的金额！）与当月总收入
    val netExpenseCnyCents = transactions
        .filter { it.type == TransactionType.EXPENSE }
        .sumOf { (it.amountCnyCents - it.refundedAmountCents).coerceAtLeast(0L) }

    val totalIncomeCnyCents = transactions
        .filter { it.type == TransactionType.INCOME }
        .sumOf { it.amountCnyCents }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "净资产折合 (CNY)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                    Text(
                        String.format(Locale.US, "¥ %,.2f", netWorthCnyCents / 100.0),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("总资产", style = MaterialTheme.typography.labelSmall)
                            Text(
                                String.format(Locale.US, "¥ %,.2f", totalAssetsCnyCents / 100.0),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Column {
                            Text("信用待还负债", style = MaterialTheme.typography.labelSmall)
                            Text(
                                String.format(Locale.US, "-¥ %,.2f", totalLiabilityCnyCents / 100.0),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFB91C1C)
                            )
                        }
                        Column {
                            Text("真实净支出(已扣冲销)", style = MaterialTheme.typography.labelSmall)
                            Text(
                                String.format(Locale.US, "¥ %,.2f", netExpenseCnyCents / 100.0),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        if (transactions.isEmpty()) {
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("暂无账单流水", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "你可以切换到下方【AI 识账沙盒】一键模拟微信付款、招行卡充值零钱、花呗还款、朋友AA收款冲销等真实场景！",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(transactions, key = { it.id }) { tx ->
                TransactionItemCard(
                    tx = tx,
                    walletMap = walletMap,
                    onUndo = { onUndoTx(tx.id) }
                )
            }
        }
    }
}

@Composable
private fun TransactionItemCard(
    tx: TransactionEntity,
    walletMap: Map<Long, WalletEntity>,
    onUndo: () -> Unit
) {
    val sym = CurrencyCode.fromCode(tx.currency).symbol
    val rawAmountStr = String.format(Locale.US, "%s%.2f", sym, tx.amountCents / 100.0)
    val sourceWalletName = tx.sourceWalletId?.let { walletMap[it]?.name }
    val targetWalletName = tx.targetWalletId?.let { walletMap[it]?.name }
    val timeStr = remember(tx.timestamp) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(tx.timestamp))
    }

    val badgeColor = when (tx.type) {
        TransactionType.EXPENSE -> Color(0xFFEF4444)
        TransactionType.INCOME -> Color(0xFF10B981)
        TransactionType.TRANSFER -> Color(0xFF3B82F6)
        TransactionType.REPAYMENT -> Color(0xFF8B5CF6)
        TransactionType.REFUND_OFFSET -> Color(0xFFF59E0B)
    }

    val walletDescription = when (tx.type) {
        TransactionType.EXPENSE -> sourceWalletName ?: "默认钱包"
        TransactionType.INCOME -> "存入 ${targetWalletName ?: sourceWalletName ?: "默认钱包"}"
        TransactionType.TRANSFER -> "${sourceWalletName ?: "转出账户"} ➔ ${targetWalletName ?: "转入账户"}"
        TransactionType.REPAYMENT -> "${sourceWalletName ?: "储蓄卡"} ➔ 偿还 ${targetWalletName ?: "信用账户"}"
        TransactionType.REFUND_OFFSET -> "退回至 ${targetWalletName ?: sourceWalletName ?: "钱包"} (冲抵消费)"
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(badgeColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    tx.type.displayName.take(2),
                    color = badgeColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (tx.merchant.isNotBlank()) "${tx.category} · ${tx.merchant}" else tx.category,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = tx.captureSource.removePrefix("AI_"),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$walletDescription · $timeStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (tx.note.isNotBlank()) {
                    Text(
                        text = tx.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }

                // 如果该笔消费发生了部分或全部退款/AA冲销，直观展示冲销后的真实净支出
                if (tx.type == TransactionType.EXPENSE && tx.refundedAmountCents > 0L) {
                    val netCents = (tx.amountCnyCents - tx.refundedAmountCents).coerceAtLeast(0L)
                    Text(
                        text = String.format(
                            Locale.US,
                            "已退款/AA冲抵 ¥%.2f，实际净支出 ¥%.2f",
                            tx.refundedAmountCents / 100.0,
                            netCents / 100.0
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFD97706),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                val prefix = when (tx.type) {
                    TransactionType.EXPENSE -> "-"
                    TransactionType.INCOME, TransactionType.REFUND_OFFSET -> "+"
                    TransactionType.TRANSFER, TransactionType.REPAYMENT -> ""
                }
                Text(
                    text = "$prefix$rawAmountStr",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor,
                    textDecoration = if (tx.type == TransactionType.EXPENSE && tx.refundedAmountCents >= tx.amountCnyCents) {
                        TextDecoration.LineThrough
                    } else null
                )
                if (tx.currency != "CNY") {
                    Text(
                        text = String.format(Locale.US, "≈ ¥%.2f", tx.amountCnyCents / 100.0),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onUndo, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "撤销并回滚余额",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WalletsAndRecurringTab(
    wallets: List<WalletEntity>,
    recurringRules: List<RecurringRuleEntity>,
    onTriggerRuleNow: (RecurringRuleEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "多类型资产与负债钱包",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "AI 会根据通知/页面上的银行卡尾号、别名、币种自动匹配对应钱包，并在消费、内部转账、还信用卡、退款时自动联动余额。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(wallets, key = { it.id }) { wallet ->
            val sym = CurrencyCode.fromCode(wallet.currency).symbol
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color(wallet.colorHex))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(wallet.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (!wallet.cardLast4.isNullOrBlank()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        "尾号 ${wallet.cardLast4}",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Text(
                            text = if (wallet.type.isLiability) {
                                String.format(Locale.US, "待还 %s%,.2f", sym, wallet.balanceCents / 100.0)
                            } else {
                                String.format(Locale.US, "%s%,.2f", sym, wallet.balanceCents / 100.0)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (wallet.type.isLiability) Color(0xFFDC2626) else MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "${wallet.type.displayName} · 币种: ${wallet.currency} · AI匹配词: ${wallet.aliases.ifBlank { wallet.name }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (wallet.type.isLiability && wallet.creditLimitCents != null && wallet.creditLimitCents > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val usedRatio = (wallet.balanceCents.toFloat() / wallet.creditLimitCents.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { usedRatio },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val remainYuan = (wallet.creditLimitCents - wallet.balanceCents).coerceAtLeast(0L) / 100.0
                        Text(
                            String.format(
                                Locale.US,
                                "总额度 %s%,.2f · 可用额度 %s%,.2f · 账单日 %d日 / 还款日 %d日",
                                sym,
                                wallet.creditLimitCents / 100.0,
                                sym,
                                remainYuan,
                                wallet.billingDay ?: 1,
                                wallet.repaymentDay ?: 10
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "周期性固定收支（房租 / 订阅会员 / 自动入账）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        items(recurringRules, key = { it.id }) { rule ->
            val sym = CurrencyCode.fromCode(rule.currency).symbol
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${rule.title} (${rule.frequency.displayName})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            String.format(Locale.US, "%s%.2f · 分类：%s", sym, rule.amountCents / 100.0, rule.category),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = { onTriggerRuleNow(rule) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("立即执行一次")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiSandboxTab(
    isParsing: Boolean,
    statusMessage: String?,
    onRunSimulation: (String, String) -> Unit
) {
    var customInput by remember {
        mutableStateOf("【微信支付】支付成功！向商户【瑞幸咖啡】付款 18.50元，支付方式：招商银行信用卡(6612)，备注：生椰拿铁大杯")
    }

    val presetScenarios = listOf(
        "☕ 信用卡买咖啡" to "【微信支付】支付成功！向商户【瑞幸咖啡】付款 18.50元，支付方式：招商银行信用卡(6612)，商品：生椰拿铁",
        "🔄 招行卡充值到微信零钱(内部转账)" to "【招商银行】您尾号8848的储蓄卡于11月05日充值到微信零钱人民币1000.00元",
        "💳 招行储蓄卡还花呗(信用还款)" to "【支付宝】还款成功：您已使用招商银行储蓄卡(8848)成功偿还蚂蚁花呗账单 864.00元",
        "🤝 同事AA微信转账冲销餐饮" to "【微信转账】收到同事小李的转账 9.25元，留言：瑞幸咖啡AA平摊",
        "🌍 海外美元消费自动换算" to "[Visa Alert] You spent $20.00 USD at OpenAI ChatGPT Plus using card ending in 9011"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "AI 静默识账全场景沙盒测试台",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "无需等待真实消费，点击下方预设标签或输入任意乱七八糟的通知/屏幕文字，即可立即体验：AI 结构化解析 ➔ 自动联动多钱包/信用卡/转账/AA冲销 ➔ 发送带「撤销入账」按钮的系统通知栏提醒。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Text("一键载入典型复杂场景：", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presetScenarios.forEach { (label, sampleText) ->
                    AssistChip(
                        onClick = {
                            customInput = sampleText
                            onRunSimulation(sampleText, "com.tencent.mm")
                        },
                        label = { Text(label) }
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = customInput,
                onValueChange = { customInput = it },
                label = { Text("模拟通知栏 / 无障碍屏幕抓取到的原始文本") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = { onRunSimulation(customInput, "com.tencent.mm") },
                enabled = !isParsing && customInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isParsing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("AI 正在解析并入账...")
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("立即触发 AI 静默入账 & 通知栏提醒")
                }
            }
        }

        if (!statusMessage.isNullOrBlank()) {
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = statusMessage,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun AiAndPermissionsSettingsTab(
    config: AiConfig,
    onSaveConfig: (AiConfig) -> Unit
) {
    val context = LocalContext.current
    var provider by remember(config) { mutableStateOf(config.provider) }
    var baseUrl by remember(config) { mutableStateOf(config.baseUrl) }
    var apiKey by remember(config) { mutableStateOf(config.apiKey) }
    var modelName by remember(config) { mutableStateOf(config.modelName) }
    var enableAccessibility by remember(config) { mutableStateOf(config.enableAccessibilityCapture) }
    var enableNotification by remember(config) { mutableStateOf(config.enableNotificationCapture) }
    var enableFallback by remember(config) { mutableStateOf(config.enableHeuristicFallback) }
    var savedBanner by remember { mutableStateOf(false) }

    val isNotifGranted = isNotificationListenerEnabled(context)
    val isAccessGranted = isAccessibilityServiceEnabled(context)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("1. 系统级无感采集权限状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (isNotifGranted) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = if (isNotifGranted) Color(0xFF10B981) else Color(0xFFF59E0B),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("通知栏使用权 (微信/支付宝/银行通知)", fontWeight = FontWeight.Bold)
                            }
                            Text(
                                if (isNotifGranted) "已授权运行中" else "未授权：点击右侧按钮前往系统开启",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) {
                            Text(if (isNotifGranted) "去管理" else "去开启")
                        }
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (isAccessGranted) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = if (isAccessGranted) Color(0xFF10B981) else Color(0xFFF59E0B),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("无障碍服务 (支付完成页/转账页识别)", fontWeight = FontWeight.Bold)
                            }
                            Text(
                                if (isAccessGranted) "已授权运行中" else "未授权：点击右侧按钮前往系统无障碍开启",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) {
                            Text(if (isAccessGranted) "去管理" else "去开启")
                        }
                    }
                }
            }
        }

        item {
            Text("2. AI 大模型接口配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "支持 DeepSeek、通义千问、硅基流动、OpenAI、本地 Ollama 或 Google Gemini 官方接口。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiProviderType.entries.forEach { item ->
                    FilterChip(
                        selected = provider == item,
                        onClick = {
                            provider = item
                            baseUrl = item.defaultBaseUrl
                            modelName = item.defaultModel
                        },
                        label = { Text(if (item == AiProviderType.OPENAI_COMPATIBLE) "OpenAI 兼容接口" else "Gemini 官方接口") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("API Base URL (如 https://api.deepseek.com/v1)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it },
                label = { Text("模型名称 (如 deepseek-chat / qwen-plus / gemini-2.5-flash)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key (留空则自动使用内置本地语义引擎)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("启用通知栏自动抓取")
                        Switch(checked = enableNotification, onCheckedChange = { enableNotification = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("启用无障碍支付页面抓取")
                        Switch(checked = enableAccessibility, onCheckedChange = { enableAccessibility = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("断网或未填 Key 时启用本地语义引擎兜底")
                        Switch(checked = enableFallback, onCheckedChange = { enableFallback = it })
                    }
                }
            }
        }

        item {
            Button(
                onClick = {
                    onSaveConfig(
                        AiConfig(
                            provider = provider,
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            modelName = modelName,
                            enableAccessibilityCapture = enableAccessibility,
                            enableNotificationCapture = enableNotification,
                            enableHeuristicFallback = enableFallback
                        )
                    )
                    savedBanner = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存 AI 与自动记账配置")
            }
            if (savedBanner) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("✅ 配置已保存并立即生效！", color = Color(0xFF10B981), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualTransactionDialog(
    wallets: List<WalletEntity>,
    onDismiss: () -> Unit,
    onConfirm: (
        type: TransactionType,
        amount: Double,
        currency: String,
        category: String,
        merchant: String,
        note: String,
        fromWalletId: Long?,
        toWalletId: Long?
    ) -> Unit
) {
    var selectedType by remember { mutableStateOf(TransactionType.EXPENSE) }
    var amountStr by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("CNY") }
    var category by remember { mutableStateOf("餐饮-正餐") }
    var merchant by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var fromWalletId by remember { mutableStateOf<Long?>(wallets.firstOrNull()?.id) }
    var toWalletId by remember { mutableStateOf<Long?>(wallets.lastOrNull()?.id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记一笔 / 内部转账 / 还款 / 冲销") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TransactionType.entries.forEach { t ->
                        FilterChip(
                            selected = selectedType == t,
                            onClick = { selectedType = t },
                            label = { Text(t.displayName) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = amountStr,
                        onValueChange = { amountStr = it },
                        label = { Text("金额") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = currency,
                        onValueChange = { currency = it.uppercase() },
                        label = { Text("币种(CNY/USD)") },
                        modifier = Modifier.width(120.dp),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("分类") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("商户 / 交易对方") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text("选择关联钱包：", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    wallets.forEach { w ->
                        FilterChip(
                            selected = fromWalletId == w.id,
                            onClick = { fromWalletId = w.id },
                            label = { Text(w.name) }
                        )
                    }
                }
                if (selectedType == TransactionType.TRANSFER || selectedType == TransactionType.REPAYMENT) {
                    Text("选择转入 / 被还款的目标钱包：", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        wallets.forEach { w ->
                            FilterChip(
                                selected = toWalletId == w.id,
                                onClick = { toWalletId = w.id },
                                label = { Text(w.name) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val amt = amountStr.toDoubleOrNull() ?: return@TextButton
                onConfirm(
                    selectedType,
                    amt,
                    currency,
                    category,
                    merchant,
                    note,
                    fromWalletId,
                    if (selectedType == TransactionType.INCOME || selectedType == TransactionType.REFUND_OFFSET) fromWalletId else toWalletId
                )
            }) {
                Text("确认入账")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddWalletDialog(
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        type: WalletType,
        currency: String,
        balance: Double,
        creditLimit: Double?,
        last4: String?,
        aliases: String
    ) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(WalletType.DEBIT_BANK) }
    var currency by remember { mutableStateOf("CNY") }
    var balanceStr by remember { mutableStateOf("0") }
    var limitStr by remember { mutableStateOf("20000") }
    var last4 by remember { mutableStateOf("") }
    var aliases by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增全能钱包账户") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WalletType.entries.forEach { wt ->
                        FilterChip(
                            selected = type == wt,
                            onClick = { type = wt },
                            label = { Text(wt.displayName) }
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("钱包名称 (如：京东白条 / 建行储蓄卡)") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = balanceStr,
                        onValueChange = { balanceStr = it },
                        label = { Text(if (type.isLiability) "当前待还欠款" else "当前余额") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = currency,
                        onValueChange = { currency = it.uppercase() },
                        label = { Text("币种") },
                        modifier = Modifier.width(95.dp),
                        singleLine = true
                    )
                }
                if (type.isLiability) {
                    OutlinedTextField(
                        value = limitStr,
                        onValueChange = { limitStr = it },
                        label = { Text("信用总额度") },
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = last4,
                    onValueChange = { last4 = it },
                    label = { Text("银行卡后四位 (选填，供AI精准匹配)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = aliases,
                    onValueChange = { aliases = it },
                    label = { Text("AI匹配别名 (逗号分隔，如：白条,京东金融)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) return@TextButton
                onConfirm(
                    name.trim(),
                    type,
                    currency.trim().ifEmpty { "CNY" },
                    balanceStr.toDoubleOrNull() ?: 0.0,
                    if (type.isLiability) limitStr.toDoubleOrNull() else null,
                    last4.trim(),
                    aliases.trim()
                )
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    return flat.contains(context.packageName)
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, PaymentAccessibilityService::class.java).flattenToString()
    val flat = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return flat.contains(expected, ignoreCase = true)
}
