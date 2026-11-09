package com.moji.ai.data.model

import com.moji.ai.data.backup.BackupAndCsvTransferEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ReminderConfigManagerTest {

    private val testTimeZone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    @Before
    fun setUp() {
        ReminderConfigManager.resetForTesting()
    }

    @After
    fun tearDown() {
        ReminderConfigManager.resetForTesting()
    }

    @Test
    fun defaultConfig_hasDefaultEveningSlotAndDisabledSummary() {
        val cfg = ReminderConfigManager.configFlow.value
        assertFalse(cfg.masterEnabled)
        assertTrue(cfg.skipIfRecordedToday)
        assertEquals(1, cfg.items.size)
        val slot = cfg.items.first()
        assertEquals("slot_default_2100", slot.id)
        assertEquals("晚间记账提醒", slot.title)
        assertEquals(21, slot.hour)
        assertEquals(0, slot.minute)
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7), slot.daysOfWeek)
        assertTrue(slot.enabled)
        assertEquals("未开启", cfg.settingsTrailingText)
    }

    @Test
    fun formatTimeAndRepeatDays_handlesAllPresetsAndCustomOrder() {
        assertEquals("08:05", ReminderConfigManager.formatTimeHm(8, 5))
        assertEquals("21:00", ReminderConfigManager.formatTimeHm(21, 0))
        assertEquals("每天", ReminderConfigManager.formatRepeatDaysLabel(setOf(7, 1, 2, 3, 4, 5, 6)))
        assertEquals("工作日", ReminderConfigManager.formatRepeatDaysLabel(setOf(5, 3, 1, 2, 4)))
        assertEquals("周末", ReminderConfigManager.formatRepeatDaysLabel(setOf(7, 6)))
        assertEquals("周一、周三、周五", ReminderConfigManager.formatRepeatDaysLabel(setOf(5, 1, 3)))
    }

    @Test
    fun computeNextTriggerEpochMillis_handlesSameDayNextDayAndCrossWeekend() {
        // 2026-11-09 是周一
        val monMorning = buildEpochMillis(2026, Calendar.NOVEMBER, 9, 10, 0, 0)
        val slot2100 = ReminderConfigManager.defaultEveningSlot()

        // 1. 当天尚未到 21:00 -> 返回当天 21:00
        val nextSameDay = ReminderConfigManager.computeNextTriggerEpochMillis(slot2100, monMorning, testTimeZone)
        assertEquals(buildEpochMillis(2026, Calendar.NOVEMBER, 9, 21, 0, 0), nextSameDay)

        // 2. 当天已过 21:00 (21:00:01) -> 顺延到周二 21:00
        val monAfter2100 = buildEpochMillis(2026, Calendar.NOVEMBER, 9, 21, 0, 1)
        val nextDay = ReminderConfigManager.computeNextTriggerEpochMillis(slot2100, monAfter2100, testTimeZone)
        assertEquals(buildEpochMillis(2026, Calendar.NOVEMBER, 10, 21, 0, 0), nextDay)

        // 3. 工作日提醒（周一~周五 12:30）：在周五 13:00 计算 -> 跨过周六周日，命中下周一 (2026-11-16) 12:30
        val workdayNoonSlot = ReminderSlotItem(
            id = "workday_1230",
            title = "午间记账提醒",
            hour = 12,
            minute = 30,
            daysOfWeek = setOf(1, 2, 3, 4, 5),
            enabled = true
        )
        val friAfternoon = buildEpochMillis(2026, Calendar.NOVEMBER, 13, 13, 0, 0)
        val nextMonNoon = ReminderConfigManager.computeNextTriggerEpochMillis(workdayNoonSlot, friAfternoon, testTimeZone)
        assertEquals(buildEpochMillis(2026, Calendar.NOVEMBER, 16, 12, 30, 0), nextMonNoon)

        // 4. 关闭状态或无合法星期返回 null
        assertNull(ReminderConfigManager.computeNextTriggerEpochMillis(slot2100.copy(enabled = false), monMorning, testTimeZone))
        assertNull(ReminderConfigManager.computeNextTriggerEpochMillis(slot2100.copy(daysOfWeek = emptySet()), monMorning, testTimeZone))
    }

    @Test
    fun crudAndSettingsTrailingSummary_updatesInRealTimeAndSortsByTime() {
        // 开启总开关 -> 单条默认提醒显示 "每天 21:00"
        val enabledCfg = ReminderConfigManager.setMasterEnabled(true)
        assertEquals("每天 21:00", enabledCfg.settingsTrailingText)

        // 新增 12:30 工作日提醒 -> 列表按时间升序排列，摘要变为 "已开启 2 个 · 12:30"
        val noonSlot = ReminderSlotItem(
            id = "slot_noon_1230",
            title = "午间记账提醒",
            message = "午饭记一笔",
            hour = 12,
            minute = 30,
            daysOfWeek = setOf(1, 2, 3, 4, 5),
            enabled = true
        )
        val afterAdd = ReminderConfigManager.upsertSlot(noonSlot).getOrThrow()
        assertEquals(2, afterAdd.items.size)
        assertEquals("slot_noon_1230", afterAdd.items[0].id)
        assertEquals("已开启 2 个 · 12:30", afterAdd.settingsTrailingText)

        // 单独关闭 12:30 提醒 -> 仅剩 21:00 启用，摘要恢复 "每天 21:00"
        val afterDisableNoon = ReminderConfigManager.toggleSlotEnabled("slot_noon_1230", false)
        assertEquals("每天 21:00", afterDisableNoon.settingsTrailingText)

        // 删除全部提醒 -> 摘要变为 "未开启"
        ReminderConfigManager.deleteSlot("slot_noon_1230")
        val afterDeleteAll = ReminderConfigManager.deleteSlot("slot_default_2100")
        assertTrue(afterDeleteAll.items.isEmpty())
        assertEquals("未开启", afterDeleteAll.settingsTrailingText)
    }

    @Test
    fun validateSlotInput_rejectsInvalidFieldsAndTrimsValidInput() {
        assertTrue(ReminderConfigManager.validateSlotInput("   ", "内容", 12, 0, setOf(1)).isFailure)
        assertTrue(ReminderConfigManager.validateSlotInput("一".repeat(25), "内容", 12, 0, setOf(1)).isFailure)
        assertTrue(ReminderConfigManager.validateSlotInput("标题", "   ", 12, 0, setOf(1)).isFailure)
        assertTrue(ReminderConfigManager.validateSlotInput("标题", "内".repeat(61), 12, 0, setOf(1)).isFailure)
        assertTrue(ReminderConfigManager.validateSlotInput("标题", "内容", 24, 0, setOf(1)).isFailure)
        assertTrue(ReminderConfigManager.validateSlotInput("标题", "内容", 12, 0, emptySet()).isFailure)

        val valid = ReminderConfigManager.validateSlotInput(" 午间记账 ", " 记得记午饭 ", 12, 30, setOf(1, 3, 5)).getOrNull()
        assertNotNull(valid)
        assertEquals("午间记账", valid?.title)
        assertEquals("记得记午饭", valid?.message)
        assertEquals(setOf(1, 3, 5), valid?.daysOfWeek)
    }

    @Test
    fun backupExportAndRestore_isBackwardCompatibleWithLegacySnapshot() {
        ReminderConfigManager.setMasterEnabled(true)
        ReminderConfigManager.setSkipIfRecordedToday(false)
        val exported = ReminderConfigManager.exportConfigJson()

        ReminderConfigManager.resetForTesting()
        assertFalse(ReminderConfigManager.configFlow.value.masterEnabled)

        // 空串或旧版空字段不报错、不破坏当前配置
        assertFalse(ReminderConfigManager.restoreFromBackupJson(""))
        assertFalse(ReminderConfigManager.restoreFromBackupJson(null))

        // 恢复合法备份 JSON
        assertTrue(ReminderConfigManager.restoreFromBackupJson(exported))
        assertTrue(ReminderConfigManager.configFlow.value.masterEnabled)
        assertFalse(ReminderConfigManager.configFlow.value.skipIfRecordedToday)

        // 验证旧版 .mojibackup（不含 reminderSettingsJson 字段）反序列化兼容
        val legacyBackupJson = """{"schemaVersion":1,"appName":"MoJi","ledgers":["默认账本"]}"""
        val parsed = BackupAndCsvTransferEngine.parseFullBackupJson(legacyBackupJson)
        assertNotNull(parsed)
        assertEquals("", parsed?.third?.reminderSettingsJson)
    }

    private fun buildEpochMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        return Calendar.getInstance(testTimeZone).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
