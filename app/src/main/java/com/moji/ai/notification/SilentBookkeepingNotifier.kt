package com.moji.ai.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.moji.ai.MainActivity
import com.moji.ai.MoJiApp
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.TransactionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class SilentBookkeepingNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "moji_silent_bookkeeping"
        const val ACTION_UNDO_TRANSACTION = "com.moji.ai.ACTION_UNDO_TRANSACTION"
        const val EXTRA_TRANSACTION_ID = "extra_transaction_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    init {
        createChannelIfNeeded()
    }

    private fun createChannelIfNeeded() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AI 自动静默入账提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "当检测到付款、转账、还款或退款时，后台自动记账并发送通知提醒（支持一键撤销）"
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    fun sendSilentRecordedNotification(
        tx: TransactionEntity,
        wallets: List<WalletEntity>
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val walletMap = wallets.associateBy { it.id }
        val currencySymbol = CurrencyCode.fromCode(tx.currency).symbol
        val amountStr = String.format(Locale.US, "%s%.2f", currencySymbol, tx.amountCents / 100.0)
        val cnyHint = if (tx.currency != "CNY") {
            String.format(Locale.US, " (约 ¥%.2f)", tx.amountCnyCents / 100.0)
        } else ""

        val title = when (tx.type) {
            TransactionType.EXPENSE -> "已自动记支出 -$amountStr$cnyHint · ${tx.category}"
            TransactionType.INCOME -> "已自动记收入 +$amountStr$cnyHint · ${tx.category}"
            TransactionType.TRANSFER -> "已自动记内部转账 $amountStr"
            TransactionType.REPAYMENT -> "已自动记信用还款 $amountStr"
            TransactionType.REFUND_OFFSET -> "已自动冲销原支出 +$amountStr"
        }

        val sourceName = tx.sourceWalletId?.let { walletMap[it]?.name }
        val targetName = tx.targetWalletId?.let { walletMap[it]?.name }
        val walletRoute = when (tx.type) {
            TransactionType.EXPENSE -> "付款账户：${sourceName ?: "未指定"}"
            TransactionType.INCOME -> "入账账户：${targetName ?: sourceName ?: "未指定"}"
            TransactionType.TRANSFER -> "${sourceName ?: "转出"} ➔ ${targetName ?: "转入"}"
            TransactionType.REPAYMENT -> "${sourceName ?: "储蓄账户"} ➔ 偿还 ${targetName ?: "信用账户"}"
            TransactionType.REFUND_OFFSET -> "退回至：${targetName ?: sourceName ?: "原账户"}（已自动扣减原消费净支出）"
        }

        val bodyText = buildString {
            if (tx.merchant.isNotBlank()) append("商户/对象：${tx.merchant} | ")
            append(walletRoute)
        }

        val notifId = (tx.id % Int.MAX_VALUE).toInt().coerceAtLeast(1001)

        // 点击通知打开主界面
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            notifId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 通知栏“撤销入账”按钮
        val undoIntent = Intent(context, TransactionActionReceiver::class.java).apply {
            action = ACTION_UNDO_TRANSACTION
            putExtra(EXTRA_TRANSACTION_ID, tx.id)
            putExtra(EXTRA_NOTIFICATION_ID, notifId)
        }
        val undoPending = PendingIntent.getBroadcast(
            context,
            notifId + 10000,
            undoIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$bodyText\n备注：${tx.note}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_revert, "撤销入账", undoPending)
            .build()

        NotificationManagerCompat.from(context).notify(notifId, notification)
    }
}

class TransactionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SilentBookkeepingNotifier.ACTION_UNDO_TRANSACTION) {
            val txId = intent.getLongExtra(SilentBookkeepingNotifier.EXTRA_TRANSACTION_ID, -1L)
            val notifId = intent.getIntExtra(SilentBookkeepingNotifier.EXTRA_NOTIFICATION_ID, -1)
            if (txId <= 0L) return

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val app = context.applicationContext as MoJiApp
                    app.repository.undoAndDeleteTransaction(txId)
                    if (notifId > 0) {
                        NotificationManagerCompat.from(context).cancel(notifId)
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
