package com.moji.ai.data.model

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@Serializable
data class ReminderSlotItem(
    val id: String = "default_evening",
    val title: String = ReminderConfigManager.DEFAULT_REMINDER_TITLE,
    val message: String = ReminderConfigManager.DEFAULT_REMINDER_MESSAGE,
    val hour: Int = 21,
    val minute: Int = 0,
    val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    val enabled: Boolean = true
)

@Serializable
data class ReminderSettingsConfig(
    val masterEnabled: Boolean = false,
    val skipIfRecordedToday: Boolean = true,
    val items: List<ReminderSlotItem> = listOf(ReminderConfigManager.defaultEveningSlot())
) {
    val settingsTrailingText: String get() = ReminderConfigManager.settingsTrailingSummary(this)
    val settingsTrailingSummary: String get() = settingsTrailingText
}

object ReminderConfigManager {
    const val PREFS_NAME = "moji_reminder_prefs"
    const val KEY_REMINDER_SETTINGS_JSON = "reminder_settings_json_v1"
    const val DEFAULT_SLOT_ID = "slot_default_2100"
    const val DEFAULT_REMINDER_TITLE = "记账提醒"
    const val DEFAULT_EVENING_TITLE = "晚间记账提醒"
    const val DEFAULT_REMINDER_MESSAGE = "今天的花销都记下了吗？花 10 秒随手记一笔吧～"
    const val MAX_TITLE_LENGTH = 24
    const val MAX_MESSAGE_LENGTH = 60

    private val ALL_DAYS = setOf(1, 2, 3, 4, 5, 6, 7)
    private val WORKDAYS = setOf(1, 2, 3, 4, 5)
    private val WEEKEND_DAYS = setOf(6, 7)
    private val DAY_NAMES = mapOf(1 to "周一", 2 to "周二", 3 to "周三", 4 to "周四", 5 to "周五", 6 to "周六", 7 to "周日")
    private val jsonCodec = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    @Volatile private var prefs: SharedPreferences? = null
    private val _configFlow = MutableStateFlow(ReminderSettingsConfig())
    val configFlow: StateFlow<ReminderSettingsConfig> = _configFlow.asStateFlow()

    fun defaultEveningSlot(): ReminderSlotItem = ReminderSlotItem(
        id = DEFAULT_SLOT_ID, title = DEFAULT_EVENING_TITLE, message = DEFAULT_REMINDER_MESSAGE,
        hour = 21, minute = 0, daysOfWeek = ALL_DAYS, enabled = true
    )

    @Synchronized
    fun init(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        val raw = sp.getString(KEY_REMINDER_SETTINGS_JSON, null)
        if (raw.isNullOrBlank()) {
            val initial = ReminderSettingsConfig()
            _configFlow.value = initial
            persist(initial)
            return
        }
        val decoded = runCatching { jsonCodec.decodeFromString<ReminderSettingsConfig>(raw) }.getOrNull()
        val sanitized = if (decoded != null) {
            sanitizeConfig(decoded, fallbackToDefaultWhenEmpty = decoded.items.isEmpty())
        } else {
            ReminderSettingsConfig()
        }
        _configFlow.value = sanitized
    }

    private fun sanitizeSlot(slot: ReminderSlotItem, index: Int): ReminderSlotItem {
        val cleanTitle = slot.title.trim().take(MAX_TITLE_LENGTH).ifEmpty { DEFAULT_REMINDER_TITLE }
        val cleanMessage = slot.message.trim().take(MAX_MESSAGE_LENGTH).ifEmpty { DEFAULT_REMINDER_MESSAGE }
        val validDays = slot.daysOfWeek.filter { it in 1..7 }.toSortedSet().ifEmpty { ALL_DAYS }
        return slot.copy(
            id = slot.id.trim().ifEmpty { "slot_${index}_${slot.hour}_${slot.minute}" },
            title = cleanTitle,
            message = cleanMessage,
            hour = slot.hour.coerceIn(0, 23),
            minute = slot.minute.coerceIn(0, 59),
            daysOfWeek = validDays
        )
    }

    private fun sanitizeConfig(
        raw: ReminderSettingsConfig,
        fallbackToDefaultWhenEmpty: Boolean
    ): ReminderSettingsConfig {
        val cleanedItems = raw.items
            .mapIndexed { idx, item -> sanitizeSlot(item, idx) }
            .distinctBy { it.id }
            .sortedWith(compareBy<ReminderSlotItem> { it.hour * 60 + it.minute }.thenBy { it.id })
        val finalItems = if (cleanedItems.isEmpty() && fallbackToDefaultWhenEmpty) {
            listOf(defaultEveningSlot())
        } else {
            cleanedItems
        }
        return raw.copy(items = finalItems)
    }

    @Synchronized
    private fun persist(config: ReminderSettingsConfig) {
        _configFlow.value = config
        prefs?.edit()?.putString(KEY_REMINDER_SETTINGS_JSON, jsonCodec.encodeToString(config))?.apply()
    }

    fun formatTimeHm(hour: Int, minute: Int): String =
        String.format(Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))

    fun formatRepeatDaysLabel(daysOfWeek: Set<Int>): String {
        val valid = daysOfWeek.filter { it in 1..7 }.sorted().toSet()
        if (valid.isEmpty()) return "未选择"
        if (valid.size == 7) return "每天"
        if (valid == WORKDAYS) return "工作日"
        if (valid == WEEKEND_DAYS) return "周末"
        return valid.mapNotNull { DAY_NAMES[it] }.joinToString("、")
    }

    fun computeNextTriggerEpochMillis(
        item: ReminderSlotItem,
        nowEpochMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long? {
        val validDays = item.daysOfWeek.intersect(ALL_DAYS)
        if (!item.enabled || validDays.isEmpty()) return null
        val safeHour = item.hour.coerceIn(0, 23)
        val safeMinute = item.minute.coerceIn(0, 59)
        for (dayOffset in 0..7) {
            val cal = Calendar.getInstance(timeZone).apply {
                timeInMillis = nowEpochMillis
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, safeHour)
                set(Calendar.MINUTE, safeMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val calDow = cal.get(Calendar.DAY_OF_WEEK)
            val dayOfWeek = if (calDow == Calendar.SUNDAY) 7 else calDow - 1
            val candidateMillis = cal.timeInMillis
            if (dayOfWeek in validDays && candidateMillis > nowEpochMillis) {
                return candidateMillis
            }
        }
        return null
    }

    fun settingsTrailingSummary(config: ReminderSettingsConfig): String {
        val enabledItems = config.items
            .filter { it.enabled && it.daysOfWeek.any { d -> d in 1..7 } }
            .sortedWith(compareBy<ReminderSlotItem> { it.hour * 60 + it.minute }.thenBy { it.id })
        if (!config.masterEnabled || enabledItems.isEmpty()) return "未开启"
        val first = enabledItems.first()
        return if (enabledItems.size == 1) {
            "${formatRepeatDaysLabel(first.daysOfWeek)} ${formatTimeHm(first.hour, first.minute)}"
        } else {
            "已开启 ${enabledItems.size} 个 · ${formatTimeHm(first.hour, first.minute)}"
        }
    }

    fun validateSlotInput(
        title: String,
        message: String,
        hour: Int,
        minute: Int,
        daysOfWeek: Set<Int>,
        id: String = "",
        enabled: Boolean = true
    ): Result<ReminderSlotItem> {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return Result.failure(IllegalArgumentException("提醒标题不能为空"))
        if (cleanTitle.length > MAX_TITLE_LENGTH) return Result.failure(IllegalArgumentException("提醒标题不能超过 $MAX_TITLE_LENGTH 个字"))
        val cleanMessage = message.trim()
        if (cleanMessage.isEmpty()) return Result.failure(IllegalArgumentException("提醒内容不能为空"))
        if (cleanMessage.length > MAX_MESSAGE_LENGTH) return Result.failure(IllegalArgumentException("提醒内容不能超过 $MAX_MESSAGE_LENGTH 个字"))
        if (hour !in 0..23 || minute !in 0..59) return Result.failure(IllegalArgumentException("提醒时间范围无效（需为 00:00 ~ 23:59）"))
        val validDays = daysOfWeek.filter { it in 1..7 }.toSortedSet()
        if (validDays.isEmpty()) return Result.failure(IllegalArgumentException("请至少选择 1 天重复周期"))
        val finalId = id.trim().ifEmpty { "slot_${hour * 60 + minute}_${System.currentTimeMillis()}" }
        return Result.success(ReminderSlotItem(finalId, cleanTitle, cleanMessage, hour, minute, validDays, enabled))
    }

    @Synchronized
    fun setMasterEnabled(enabled: Boolean): ReminderSettingsConfig =
        _configFlow.value.copy(masterEnabled = enabled).also { persist(it) }

    @Synchronized
    fun setSkipIfRecordedToday(skip: Boolean): ReminderSettingsConfig =
        _configFlow.value.copy(skipIfRecordedToday = skip).also { persist(it) }

    @Synchronized
    fun upsertSlot(item: ReminderSlotItem): Result<ReminderSettingsConfig> {
        val validated = validateSlotInput(
            item.title, item.message, item.hour, item.minute, item.daysOfWeek, item.id, item.enabled
        ).getOrElse { return Result.failure(it) }
        val merged = (_configFlow.value.items.filterNot { it.id == validated.id } + validated)
            .sortedWith(compareBy<ReminderSlotItem> { it.hour * 60 + it.minute }.thenBy { it.id })
        return Result.success(_configFlow.value.copy(items = merged).also { persist(it) })
    }

    @Synchronized
    fun toggleSlotEnabled(slotId: String, enabled: Boolean): ReminderSettingsConfig =
        _configFlow.value.copy(items = _configFlow.value.items.map { if (it.id == slotId) it.copy(enabled = enabled) else it }).also { persist(it) }

    @Synchronized
    fun deleteSlot(slotId: String): ReminderSettingsConfig =
        _configFlow.value.copy(items = _configFlow.value.items.filterNot { it.id == slotId }).also { persist(it) }

    @Synchronized
    fun exportConfigJson(): String = jsonCodec.encodeToString(_configFlow.value)

    @Synchronized
    fun restoreFromBackupJson(rawJson: String?): Boolean {
        if (rawJson.isNullOrBlank()) return false
        val decoded = runCatching { jsonCodec.decodeFromString<ReminderSettingsConfig>(rawJson.trim()) }.getOrNull()
            ?: return false
        val sanitized = sanitizeConfig(decoded, fallbackToDefaultWhenEmpty = true)
        persist(sanitized)
        return true
    }

    @Synchronized
    fun resetForTesting(config: ReminderSettingsConfig = ReminderSettingsConfig()) {
        persist(config)
    }
}
