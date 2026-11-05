package com.moji.ai.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.moji.ai.MoJiApp

/**
 * 通知栏监听服务：
 * 自动捕获微信支付、支付宝、云闪付、美团、京东、各大手机银行及 955xx 银行扣款短信通知。
 * 无需针对每个银行写死正则表达式，提取标题+正文后交由 CapturePipelineCoordinator 聚合喂给 AI。
 */
class PaymentNotificationListenerService : NotificationListenerService() {

    private val ignoredPackages = setOf(
        "com.moji.ai",
        "com.android.systemui",
        "android"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg in ignoredPackages) return

        val app = applicationContext as? MoJiApp ?: return
        if (!app.aiConfigManager.getConfig().enableNotificationCapture) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()

        val content = if (bigText.length > text.length) bigText else text
        if (title.isEmpty() && content.isEmpty()) return

        val combined = "$title | $content"
        app.captureCoordinator.onRawFinancialEventCaptured(
            rawText = combined,
            packageName = pkg,
            captureSource = "AI_NOTIFICATION"
        )
    }
}
