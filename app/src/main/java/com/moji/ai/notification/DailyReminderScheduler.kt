package com.moji.ai.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.moji.ai.MoJiApp
import com.moji.ai.data.model.ReminderConfigManager
import com.moji.ai.data.model.ReminderSettingsConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object DailyReminderScheduler {
    const val ACTION_TRIGGER_DAILY_REMINDER = "com.moji.ai.ACTION_TRIGGER_DAILY_REMINDER"
    const val EXTRA_REMINDER_SLOT_ID = "extra_reminder_slot_id"
    private const val KEY_REGISTERED_SLOT_IDS = "registered_reminder_slot_ids_v1"

    private val SUPPORTED_SYSTEM_ACTIONS = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED
    )

    fun isSupportedAction(action: String?): Boolean =
        action == ACTION_TRIGGER_DAILY_REMINDER || action in SUPPORTED_SYSTEM_ACTIONS

    fun requestCodeForSlot(slotId: String): Int =
        70_000 + ((slotId.hashCode() and 0x7FFF_FFFF) % 20_000)

    private fun buildTriggerIntent(context: Context, slotId: String): Intent =
        Intent(context, DailyReminderReceiver::class.java).apply {
            setPackage(context.packageName)
            action = ACTION_TRIGGER_DAILY_REMINDER
            putExtra(EXTRA_REMINDER_SLOT_ID, slotId)
        }

    @Synchronized
    fun rescheduleAll(
        context: Context,
        config: ReminderSettingsConfig = ReminderConfigManager.configFlow.value
    ) {
        val appCtx = context.applicationContext
        val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val prefs = appCtx.getSharedPreferences(ReminderConfigManager.PREFS_NAME, Context.MODE_PRIVATE)

        val previouslyRegistered = prefs.getStringSet(KEY_REGISTERED_SLOT_IDS, emptySet()).orEmpty()
        val allKnownSlotIds = (previouslyRegistered + config.items.map { it.id } + ReminderConfigManager.DEFAULT_SLOT_ID)
            .filter { it.isNotBlank() }
            .toSet()

        for (slotId in allKnownSlotIds) {
            runCatching {
                val existingPi = PendingIntent.getBroadcast(
                    appCtx,
                    requestCodeForSlot(slotId),
                    buildTriggerIntent(appCtx, slotId),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (existingPi != null) {
                    alarmManager.cancel(existingPi)
                    existingPi.cancel()
                }
            }
        }

        if (!config.masterEnabled) {
            prefs.edit().putStringSet(KEY_REGISTERED_SLOT_IDS, emptySet()).apply()
            return
        }

        val nowMillis = System.currentTimeMillis()
        val newlyRegistered = mutableSetOf<String>()
        for (slot in config.items) {
            if (!slot.enabled) continue
            val nextTriggerMillis = ReminderConfigManager.computeNextTriggerEpochMillis(
                item = slot,
                nowEpochMillis = nowMillis
            ) ?: continue

            val pendingIntent = PendingIntent.getBroadcast(
                appCtx,
                requestCodeForSlot(slot.id),
                buildTriggerIntent(appCtx, slot.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val scheduled = runCatching {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextTriggerMillis,
                    pendingIntent
                )
            }.recoverCatching {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    nextTriggerMillis,
                    pendingIntent
                )
            }.isSuccess

            if (scheduled) {
                newlyRegistered.add(slot.id)
            }
        }

        prefs.edit().putStringSet(KEY_REGISTERED_SLOT_IDS, newlyRegistered).apply()
    }
}

class DailyReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (!DailyReminderScheduler.isSupportedAction(action)) return

        val appCtx = context.applicationContext
        ReminderConfigManager.init(appCtx)

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            DailyReminderScheduler.rescheduleAll(appCtx)
            return
        }

        if (action == DailyReminderScheduler.ACTION_TRIGGER_DAILY_REMINDER) {
            val slotId = intent.getStringExtra(DailyReminderScheduler.EXTRA_REMINDER_SLOT_ID).orEmpty()
            val config = ReminderConfigManager.configFlow.value
            // 先顺延注册下一次闹钟，确保后续周期持续生效
            DailyReminderScheduler.rescheduleAll(appCtx, config)
            if (!config.masterEnabled) return

            val targetSlot = config.items.firstOrNull { it.id == slotId }
                ?: config.items.firstOrNull { it.enabled }
                ?: return
            if (!targetSlot.enabled) return

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val app = appCtx as? MoJiApp
                    if (config.skipIfRecordedToday && app?.repository?.hasRecordedTransactionToday() == true) {
                        return@launch
                    }
                    val notifier = app?.notifier ?: SilentBookkeepingNotifier(appCtx)
                    notifier.sendDailyReminderNotification(
                        title = targetSlot.title,
                        message = targetSlot.message,
                        slotId = targetSlot.id,
                        isTest = false
                    )
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
