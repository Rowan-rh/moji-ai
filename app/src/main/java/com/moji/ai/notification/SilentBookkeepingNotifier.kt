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
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.moji.ai.BuildConfig
import com.moji.ai.MainActivity
import com.moji.ai.MoJiApp
import com.moji.ai.R
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CurrencyCode
import com.moji.ai.data.model.TransactionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SilentBookkeepingNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "moji_silent_bookkeeping_v2"
        const val FLUID_CLOUD_CHANNEL_ID = "moji_fluid_cloud_silent_v2"
        const val QA_CHANNEL_ID = "moji_silent_bookkeeping_qa_v2"
        const val QA_FLUID_CLOUD_CHANNEL_ID = "moji_fluid_cloud_silent_qa_v2"
        const val DAILY_REMINDER_CHANNEL_ID = "moji_daily_reminder_channel"
        const val QA_DAILY_REMINDER_CHANNEL_ID = "moji_daily_reminder_qa_channel"
        const val QA_ANTI_CAPTURE_MARKER = "[QA测试通知·确认付款免采集]"
        const val ACTION_UNDO_TRANSACTION = "com.moji.ai.ACTION_UNDO_TRANSACTION"
        const val ACTION_CYCLE_CATEGORY = "com.moji.ai.ACTION_CYCLE_CATEGORY"
        const val ACTION_MARK_REIMBURSABLE = "com.moji.ai.ACTION_MARK_REIMBURSABLE"
        const val ACTION_FORCE_RECORD_DUPLICATE = "com.moji.ai.ACTION_FORCE_RECORD_DUPLICATE"
        const val ACTION_DISMISS_DUPLICATE = "com.moji.ai.ACTION_DISMISS_DUPLICATE"
        const val ACTION_CLEAR_ALL_TRANSACTIONS = "com.moji.ai.ACTION_CLEAR_ALL_TRANSACTIONS"
        const val EXTRA_TRANSACTION_ID = "extra_transaction_id"
        const val EXTRA_CANDIDATE_ID = "extra_candidate_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

        val QUICK_CYCLE_CATEGORIES = listOf(
            "餐饮-三餐",
            "餐饮-咖啡",
            "购物",
            "日用",
            "交通-打车",
            "娱乐"
        )

        /**
         * 检测当前设备是否为一加（OnePlus）或搭载 ColorOS / OxygenOS（支持流体云 Fluid Cloud）的机型
         */
        fun isOnePlusFluidCloudSupported(): Boolean {
            val brand = "${Build.BRAND} ${Build.MANUFACTURER}".lowercase(Locale.ROOT)
            if (brand.contains("oneplus") || brand.contains("oppo") || brand.contains("oplus") || brand.contains("realme")) {
                return true
            }
            val oplusRom = runCatching {
                val clazz = Class.forName("android.os.SystemProperties")
                val getMethod = clazz.getMethod("get", String::class.java, String::class.java)
                getMethod.invoke(null, "ro.build.version.oplusrom", "") as? String
            }.getOrNull().orEmpty()
            return oplusRom.isNotBlank()
        }
    }

    private val standardChannelId: String
        get() = if (BuildConfig.IS_QA_BUILD) QA_CHANNEL_ID else CHANNEL_ID

    private val fluidChannelId: String
        get() = if (BuildConfig.IS_QA_BUILD) QA_FLUID_CLOUD_CHANNEL_ID else FLUID_CLOUD_CHANNEL_ID

    private val dailyReminderChannelId: String
        get() = if (BuildConfig.IS_QA_BUILD) QA_DAILY_REMINDER_CHANNEL_ID else DAILY_REMINDER_CHANNEL_ID

    private fun activeChannelId(isOnePlus: Boolean): String =
        if (isOnePlus) fluidChannelId else standardChannelId

    init {
        createChannelIfNeeded()
    }

    private fun createChannelIfNeeded() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 清理旧版 IMPORTANCE_HIGH 通道，防止 SystemUI 弹出系统横幅与顶部 120Hz 流体云胶囊争抢 SurfaceFlinger 帧率
        runCatching {
            nm.deleteNotificationChannel("moji_silent_bookkeeping")
            nm.deleteNotificationChannel("moji_fluid_cloud_live")
        }

        val standardChannelName = if (BuildConfig.IS_QA_BUILD) {
            "【QA测试】AI 自动静默入账提醒"
        } else {
            "AI 自动静默入账提醒"
        }
        val standardChannel = NotificationChannel(
            standardChannelId,
            standardChannelName,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "当检测到付款、转账、还款或退款时，后台自动记账并在通知栏提供撤销与分类切换"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(standardChannel)

        val fluidChannelName = if (BuildConfig.IS_QA_BUILD) {
            "【QA测试】一加流体云 · 自动记账通知中心卡片"
        } else {
            "一加流体云 · 自动记账通知中心卡片"
        }
        val fluidCloudChannel = NotificationChannel(
            fluidChannelId,
            fluidChannelName,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "在通知中心保留自动记账详情与疑似重复确认按钮，不与顶部 120Hz 流体云实况胶囊冲突"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(true)
        }
        nm.createNotificationChannel(fluidCloudChannel)

        val reminderChannelName = if (BuildConfig.IS_QA_BUILD) {
            "【QA测试】每日记账提醒"
        } else {
            "每日记账提醒"
        }
        val reminderChannel = NotificationChannel(
            dailyReminderChannelId,
            reminderChannelName,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "按设定的定时提醒时段推送记账提醒，点击通知直达记账计算器面板"
            setShowBadge(true)
        }
        nm.createNotificationChannel(reminderChannel)
    }

    /**
     * 发送已成功入账的流体云实况通知
     */
    fun sendSilentRecordedNotification(
        tx: TransactionEntity,
        wallets: List<WalletEntity>
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val isOnePlus = isOnePlusFluidCloudSupported()
        val activeChannelId = activeChannelId(isOnePlus)

        val walletMap = wallets.associateBy { it.id }
        val currencySymbol = CurrencyCode.fromCode(tx.currency).symbol
        val amountStr = String.format(Locale.US, "%s%.2f", currencySymbol, tx.amountCents / 100.0)
        val cnyHint = if (tx.currency != "CNY") {
            String.format(Locale.US, " (约 ¥%.2f)", tx.amountCnyCents / 100.0)
        } else ""

        val shortSign = when (tx.type) {
            TransactionType.EXPENSE, TransactionType.LOAN_OUT -> "-"
            TransactionType.INCOME, TransactionType.REFUND_OFFSET, TransactionType.LOAN_IN -> "+"
            else -> ""
        }
        val shortCapsuleText = "$shortSign$amountStr"

        val title = when (tx.type) {
            TransactionType.EXPENSE -> "已自动记账 -$amountStr$cnyHint · ${tx.category}"
            TransactionType.INCOME -> "已自动记收入 +$amountStr$cnyHint · ${tx.category}"
            TransactionType.TRANSFER -> "已自动记内部转账 $amountStr"
            TransactionType.REPAYMENT -> "已自动记信用还款 $amountStr"
            TransactionType.REFUND_OFFSET -> "已自动冲销原支出 +$amountStr"
            TransactionType.LOAN_IN -> "已自动记借入 +$amountStr"
            TransactionType.LOAN_OUT -> "已自动记借出 -$amountStr"
        }

        fun formatWalletWithTail(w: WalletEntity?): String? {
            if (w == null) return null
            return if (!w.cardLast4.isNullOrBlank()) "${w.name}(${w.cardLast4})" else w.name
        }

        val sourceName = formatWalletWithTail(tx.sourceWalletId?.let { walletMap[it] })
        val targetName = formatWalletWithTail(tx.targetWalletId?.let { walletMap[it] })
        val walletRoute = when (tx.type) {
            TransactionType.EXPENSE -> "账户：${sourceName ?: "未指定"}"
            TransactionType.INCOME -> "入账：${targetName ?: sourceName ?: "未指定"}"
            TransactionType.TRANSFER -> "${sourceName ?: "转出"} ➔ ${targetName ?: "转入"}"
            TransactionType.REPAYMENT -> "${sourceName ?: "储蓄账户"} ➔ 偿还 ${targetName ?: "信用账户"}"
            TransactionType.REFUND_OFFSET -> "退回至：${targetName ?: sourceName ?: "原账户"}"
            TransactionType.LOAN_IN -> "借入至：${targetName ?: sourceName ?: "未指定"}"
            TransactionType.LOAN_OUT -> "借出自：${sourceName ?: "未指定"}"
        }

        val bodyText = buildString {
            if (tx.merchant.isNotBlank()) append("${tx.merchant} · ")
            if (tx.quantityDesc.isNotBlank()) append("${tx.quantityDesc} · ")
            append(walletRoute)
        }

        val occurredTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(tx.timestamp))
        val expandedDetails = buildString {
            append(bodyText)
            append("\n实际购物时间：").append(occurredTimeFmt)
            if (tx.orderKey.isNotBlank()) {
                append("\n订单唯一Key：").append(tx.orderKey)
            }
            if (tx.note.isNotBlank()) {
                append("\n备注：").append(tx.note)
            }
            if (BuildConfig.IS_QA_BUILD) {
                append("\n").append(QA_ANTI_CAPTURE_MARKER)
            }
        }

        val notifId = (tx.id % Int.MAX_VALUE).toInt().coerceAtLeast(1001)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TRANSACTION_ID, tx.id)
        }
        val openPending = PendingIntent.getActivity(
            context,
            notifId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val undoIntent = Intent(context, TransactionActionReceiver::class.java).apply {
            setPackage(context.packageName)
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

        val cycleCatIntent = Intent(context, TransactionActionReceiver::class.java).apply {
            setPackage(context.packageName)
            action = ACTION_CYCLE_CATEGORY
            putExtra(EXTRA_TRANSACTION_ID, tx.id)
            putExtra(EXTRA_NOTIFICATION_ID, notifId)
        }
        val cycleCatPending = PendingIntent.getBroadcast(
            context,
            notifId + 20000,
            cycleCatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val reimburseIntent = Intent(context, TransactionActionReceiver::class.java).apply {
            setPackage(context.packageName)
            action = ACTION_MARK_REIMBURSABLE
            putExtra(EXTRA_TRANSACTION_ID, tx.id)
            putExtra(EXTRA_NOTIFICATION_ID, notifId)
        }
        val reimbursePending = PendingIntent.getBroadcast(
            context,
            notifId + 30000,
            reimburseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fluidExtras = Bundle().apply {
            putBoolean("android.requestPromotedOngoing", false)
            putString("android.shortCriticalText", shortCapsuleText)
            if (isOnePlus) {
                putBoolean("oplus_smallicon_use_app_icon", true)
                putBoolean("oplus.notification.fluid_cloud", true)
                putInt("oplus.notification.fluid_cloud.type", 1)
                putString("oplus.fluid.capsule.title", shortCapsuleText)
                putString(
                    "oplus.fluid.capsule.content",
                    "${tx.category} · ${tx.merchant.ifBlank { sourceName ?: "默记" }}"
                )
                putString("oplus.fluid.status", "BOOKKEEPING_SUCCESS")
            }
        }

        val builder = NotificationCompat.Builder(context, activeChannelId)
            .setSmallIcon(R.drawable.ic_fluid_cloud_moji)
            .setColor(0xFF0EA5E9.toInt())
            .setSubText(
                if (BuildConfig.IS_QA_BUILD) {
                    "默记 QA"
                } else if (isOnePlus) {
                    "流体云 · 默记"
                } else {
                    "默记 AI"
                }
            )
            .setContentTitle(title)
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedDetails))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .addExtras(fluidExtras)
            .addAction(0, "撤销入账", undoPending)
            .addAction(0, "切换分类", cycleCatPending)

        if (tx.type == TransactionType.EXPENSE && !tx.reimbursable) {
            builder.addAction(0, "标为待报销", reimbursePending)
        }

        NotificationManagerCompat.from(context).notify(notifId, builder.build())
    }

    /**
     * 发送「检测到疑似重复（默认未录入）」流体云通知，提供一键「录入」按钮
     */
    fun sendSuspectedDuplicateNotification(
        candidateId: Long,
        tx: TransactionEntity,
        wallets: List<WalletEntity>,
        duplicateReason: String,
        matchedTransactionId: Long? = null
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val isOnePlus = isOnePlusFluidCloudSupported()
        val activeChannelId = activeChannelId(isOnePlus)
        val walletMap = wallets.associateBy { it.id }
        val sourceWallet = tx.sourceWalletId?.let { walletMap[it] }
        val walletLabel = sourceWallet?.let { w ->
            if (!w.cardLast4.isNullOrBlank()) "${w.name}(${w.cardLast4})" else w.name
        } ?: "默认账户"

        val currencySymbol = CurrencyCode.fromCode(tx.currency).symbol
        val amountStr = String.format(Locale.US, "%s%.2f", currencySymbol, tx.amountCents / 100.0)
        val occurredTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(tx.timestamp))

        val notifId = (candidateId % Int.MAX_VALUE).toInt().coerceAtLeast(50001)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (matchedTransactionId != null && matchedTransactionId > 0L) {
                putExtra(MainActivity.EXTRA_OPEN_TRANSACTION_ID, matchedTransactionId)
            }
        }
        val openPending = PendingIntent.getActivity(
            context,
            notifId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val forceRecordIntent = Intent(context, TransactionActionReceiver::class.java).apply {
            setPackage(context.packageName)
            action = ACTION_FORCE_RECORD_DUPLICATE
            putExtra(EXTRA_CANDIDATE_ID, candidateId)
            putExtra(EXTRA_NOTIFICATION_ID, notifId)
        }
        val forceRecordPending = PendingIntent.getBroadcast(
            context,
            notifId + 40000,
            forceRecordIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(context, TransactionActionReceiver::class.java).apply {
            setPackage(context.packageName)
            action = ACTION_DISMISS_DUPLICATE
            putExtra(EXTRA_CANDIDATE_ID, candidateId)
            putExtra(EXTRA_NOTIFICATION_ID, notifId)
        }
        val dismissPending = PendingIntent.getBroadcast(
            context,
            notifId + 45000,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "检测到疑似重复 · 默认未录入 ($amountStr)"
        val body = "${tx.merchant.ifBlank { tx.category }} · $duplicateReason · 如需记账请点「录入」"
        val bigText = buildString {
            append("商户：").append(tx.merchant.ifBlank { tx.category })
            append(" | 金额：").append(amountStr)
            append(" | 数量：").append(tx.quantityDesc)
            append("\n账户：").append(walletLabel)
            append("\n实际购物时间：").append(occurredTimeFmt)
            if (tx.orderKey.isNotBlank()) {
                append("\n订单唯一Key：").append(tx.orderKey)
            }
            append("\n判定原因：").append(duplicateReason).append("（默认不重复录入，点击下方「录入」可强制入账）")
            if (BuildConfig.IS_QA_BUILD) {
                append("\n").append(QA_ANTI_CAPTURE_MARKER)
            }
        }

        val fluidExtras = Bundle().apply {
            putBoolean("android.requestPromotedOngoing", false)
            putString("android.shortCriticalText", "疑似重复 $amountStr")
            if (isOnePlus) {
                putBoolean("oplus_smallicon_use_app_icon", true)
                putBoolean("oplus.notification.fluid_cloud", true)
                putInt("oplus.notification.fluid_cloud.type", 1)
                putString("oplus.fluid.capsule.title", "疑似重复 $amountStr")
                putString("oplus.fluid.capsule.content", "默认未录入 · 点此或点「录入」")
                putString("oplus.fluid.status", "DUPLICATE_SKIPPED")
            }
        }

        val builder = NotificationCompat.Builder(context, activeChannelId)
            .setSmallIcon(R.drawable.ic_fluid_cloud_moji)
            .setColor(0xFFF59E0B.toInt())
            .setSubText(
                if (BuildConfig.IS_QA_BUILD) {
                    "默记 QA"
                } else if (isOnePlus) {
                    "流体云 · 防重保护"
                } else {
                    "默记防重"
                }
            )
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .addExtras(fluidExtras)
            .addAction(0, "录入", forceRecordPending)
            .addAction(0, "忽略", dismissPending)

        NotificationManagerCompat.from(context).notify(notifId, builder.build())
    }

    /**
     * 发送每日定时记账提醒通知（点击后打开 MainActivity 并自动拉起记账计算器面板）
     */
    fun sendDailyReminderNotification(
        title: String,
        message: String,
        slotId: String = "test",
        isTest: Boolean = false
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        val nmCompat = NotificationManagerCompat.from(context)
        if (!nmCompat.areNotificationsEnabled()) {
            return false
        }

        val cleanTitle = title.trim().ifEmpty { "记账提醒" }
        val cleanMessage = message.trim().ifEmpty { "今天的花销都记下了吗？花 10 秒随手记一笔吧～" }
        val notifId = if (isTest) 88001 else (88000 + (slotId.hashCode() and 0x7FFF))

        val openIntent = Intent(context, MainActivity::class.java).apply {
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_BOOKKEEPING_SHEET, true)
        }
        val openPending = PendingIntent.getActivity(
            context,
            notifId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val expandedText = buildString {
            append(cleanMessage)
            append("\n点击通知即可直接打开记账面板快速记一笔")
            if (BuildConfig.IS_QA_BUILD) {
                append("\n").append(QA_ANTI_CAPTURE_MARKER)
            }
        }

        val builder = NotificationCompat.Builder(context, dailyReminderChannelId)
            .setSmallIcon(R.drawable.ic_fluid_cloud_moji)
            .setColor(0xFF0EA5E9.toInt())
            .setSubText(if (BuildConfig.IS_QA_BUILD) "默记 QA · 定时提醒" else "默记 · 定时提醒")
            .setContentTitle(cleanTitle)
            .setContentText(cleanMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openPending)

        nmCompat.notify(notifId, builder.build())
        return true
    }

    fun sendTestReminderNotification(
        title: String = "记账提醒",
        message: String = "今天的花销都记下了吗？花 10 秒随手记一笔吧～"
    ): Boolean = sendDailyReminderNotification(title = title, message = message, slotId = "test", isTest = true)
}

class TransactionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val txId = intent.getLongExtra(SilentBookkeepingNotifier.EXTRA_TRANSACTION_ID, -1L)
        val candidateId = intent.getLongExtra(SilentBookkeepingNotifier.EXTRA_CANDIDATE_ID, -1L)
        val notifId = intent.getIntExtra(SilentBookkeepingNotifier.EXTRA_NOTIFICATION_ID, -1)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as MoJiApp
                when (intent.action) {
                    SilentBookkeepingNotifier.ACTION_FORCE_RECORD_DUPLICATE -> {
                        if (notifId > 0) {
                            NotificationManagerCompat.from(context).cancel(notifId)
                        }
                        if (candidateId > 0L) {
                            app.captureCoordinator.forceRecordDuplicateCandidate(candidateId)
                        }
                    }

                    SilentBookkeepingNotifier.ACTION_DISMISS_DUPLICATE -> {
                        if (notifId > 0) {
                            NotificationManagerCompat.from(context).cancel(notifId)
                        }
                    }

                    SilentBookkeepingNotifier.ACTION_UNDO_TRANSACTION -> {
                        if (txId > 0L) {
                            app.repository.undoAndDeleteTransaction(txId)
                        }
                        if (notifId > 0) {
                            NotificationManagerCompat.from(context).cancel(notifId)
                        }
                    }

                    SilentBookkeepingNotifier.ACTION_CYCLE_CATEGORY -> {
                        if (txId <= 0L) return@launch
                        val existing = app.database.transactionDao().getTransactionById(txId) ?: return@launch
                        val cycleList = SilentBookkeepingNotifier.QUICK_CYCLE_CATEGORIES
                        val curIdx = cycleList.indexOf(existing.category)
                        val nextCat = cycleList[(curIdx + 1) % cycleList.size]
                        val updated = existing.copy(category = nextCat)
                        app.repository.updateExistingTransaction(updated)
                        app.merchantMemoryManager.learnFromTransaction(updated)
                        val wallets = app.repository.getAllWallets()
                        app.notifier.sendSilentRecordedNotification(updated, wallets)
                    }

                    SilentBookkeepingNotifier.ACTION_MARK_REIMBURSABLE -> {
                        if (txId <= 0L) return@launch
                        val existing = app.database.transactionDao().getTransactionById(txId) ?: return@launch
                        val updated = existing.copy(reimbursable = true)
                        app.repository.updateExistingTransaction(updated)
                        val wallets = app.repository.getAllWallets()
                        app.notifier.sendSilentRecordedNotification(updated, wallets)
                    }

                    SilentBookkeepingNotifier.ACTION_CLEAR_ALL_TRANSACTIONS -> {
                        app.captureCoordinator.clearAllTransactionsAndResetState(context)
                        NotificationManagerCompat.from(context).cancelAll()
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
