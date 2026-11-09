package com.moji.ai.data.model

import com.moji.ai.ai.SmartConditionRule
import com.moji.ai.data.backup.BackupAndCsvTransferEngine
import com.moji.ai.notification.SilentBookkeepingNotifier
import com.moji.ai.ui.InstallmentPlanItem
import com.moji.ai.ui.SavingPlanItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class AppLockManagerTest {

    @Before
    fun setUp() {
        AppLockManager.resetForTesting()
    }

    @After
    fun tearDown() {
        AppLockManager.resetForTesting()
    }

    @Test
    fun pinFormatAndSaltedSha256Hash_neverStoresPlaintext() {
        assertFalse(AppLockManager.isValidPinFormat(""))
        assertFalse(AppLockManager.isValidPinFormat("123"))
        assertFalse(AppLockManager.isValidPinFormat("12345"))
        assertFalse(AppLockManager.isValidPinFormat("12a4"))
        assertTrue(AppLockManager.isValidPinFormat("2580"))
        assertTrue(AppLockManager.isValidPinFormat("0000"))

        val enableRes = AppLockManager.enablePasswordProtection("2580", "2580", "常用四位数字")
        assertTrue(enableRes.isSuccess)
        val (salt, hash) = AppLockManager.currentPinCredentialPairForTesting()
        assertEquals(32, salt.length)
        assertEquals(64, hash.length)
        assertFalse(hash.contains("2580"))
        assertEquals(AppLockManager.hashPin("2580", salt), hash)

        val secondSalt = AppLockManager.generateSaltHex()
        assertNotEquals(salt, secondSalt)
        assertNotEquals(hash, AppLockManager.hashPin("2580", secondSalt))
    }

    @Test
    fun enableChangeAndDisableLifecycle_validatesConfirmMismatchAndOldPin() {
        assertEquals("未开启", AppLockManager.configFlow.value.settingsTrailingText)

        // 首次设置两次不一致应拦截
        val mismatchEnable = AppLockManager.enablePasswordProtection("2580", "2581")
        assertTrue(mismatchEnable.isFailure)
        assertEquals("两次输入的密码不一致，请重新输入", mismatchEnable.exceptionOrNull()?.message)
        assertFalse(AppLockManager.configFlow.value.enabled)

        // 首次开启成功
        val enableOk = AppLockManager.enablePasswordProtection("2580", "2580", "常用四位数字")
        assertTrue(enableOk.isSuccess)
        assertTrue(AppLockManager.configFlow.value.enabled)
        assertEquals("已开启 · 立即锁定", AppLockManager.configFlow.value.settingsTrailingText)

        // 修改密码：原密码错误应拦截
        val wrongOldChange = AppLockManager.changePin(oldPin = "9999", newPin = "1357", confirmPin = "1357")
        assertTrue(wrongOldChange.isFailure)

        // 修改密码：两次新密码不一致应拦截
        val mismatchChange = AppLockManager.changePin(oldPin = "2580", newPin = "1357", confirmPin = "1358")
        assertTrue(mismatchChange.isFailure)
        assertEquals("两次输入的密码不一致，请重新输入", mismatchChange.exceptionOrNull()?.message)

        // 修改密码成功
        val changeOk = AppLockManager.changePin(oldPin = "2580", newPin = "1357", confirmPin = "1357")
        assertTrue(changeOk.isSuccess)
        assertTrue(AppLockManager.verifyPin("2580") is PinVerifyResult.Mismatch)
        assertEquals(PinVerifyResult.Success, AppLockManager.verifyPin("1357"))

        // 关闭密码保护：原密码错误应拦截
        val wrongDisable = AppLockManager.disablePasswordProtection("2580")
        assertTrue(wrongDisable.isFailure)
        assertTrue(AppLockManager.configFlow.value.enabled)

        // 验证正确密码后关闭成功
        val disableOk = AppLockManager.disablePasswordProtection("1357")
        assertTrue(disableOk.isSuccess)
        assertFalse(AppLockManager.configFlow.value.enabled)
        assertEquals("", AppLockManager.configFlow.value.pinHint)
        assertEquals("未开启", AppLockManager.configFlow.value.settingsTrailingText)
    }

    @Test
    fun pinHintValidation_rejectsPinLeakAndOverlongInput() {
        AppLockManager.enablePasswordProtection("2580", "2580")

        // 包含当前 4 位 PIN 本身应被拒绝
        val exactMatch = AppLockManager.updatePinHint("2580")
        assertTrue(exactMatch.isFailure)
        assertEquals("密码提示语不能包含当前密码本身", exactMatch.exceptionOrNull()?.message)

        val embeddedMatch = AppLockManager.updatePinHint("提示是2580别忘")
        assertTrue(embeddedMatch.isFailure)
        assertEquals("密码提示语不能包含当前密码本身", embeddedMatch.exceptionOrNull()?.message)

        // 超过 20 个字符应被拒绝
        val tooLong = AppLockManager.updatePinHint("一二三四五六七八九十一二三四五六七八九十甲")
        assertTrue(tooLong.isFailure)
        assertEquals("密码提示语不能超过 20 个字符", tooLong.exceptionOrNull()?.message)

        // 合法提示语保存成功
        val validHint = AppLockManager.updatePinHint("常用四位数字或生日8899")
        assertTrue(validHint.isSuccess)
        assertEquals("常用四位数字或生日8899", AppLockManager.configFlow.value.pinHint)

        // 修改新密码时若与已有提示语中的数字冲突也应被拦截
        val conflictChange = AppLockManager.changePin("2580", "8899", "8899")
        assertTrue(conflictChange.isFailure)
    }

    @Test
    fun fiveConsecutiveFailures_triggersThirtySecondsCooldownLock() {
        AppLockManager.enablePasswordProtection("2580", "2580")
        AppLockManager.lockNow()
        assertTrue(AppLockManager.isSessionLockedFlow.value)

        val t0 = 1_000_000L
        for (attempt in 1..4) {
            val res = AppLockManager.verifyPin("0000", nowMillis = t0 + attempt * 100L)
            assertTrue(res is PinVerifyResult.Mismatch)
            assertEquals(5 - attempt, (res as PinVerifyResult.Mismatch).remainingAttempts)
        }

        // 第 5 次输错触发 30 秒冷却锁
        val fifth = AppLockManager.verifyPin("0000", nowMillis = t0 + 500L)
        assertTrue(fifth is PinVerifyResult.CooldownLocked)
        assertEquals(30, (fifth as PinVerifyResult.CooldownLocked).remainingSeconds)

        // 冷却期间（如 +10.2 秒后）即便输入正确密码也拒绝校验，返回剩余 20 秒
        val duringCooldown = AppLockManager.verifyPin("2580", nowMillis = t0 + 10_700L)
        assertTrue(duringCooldown is PinVerifyResult.CooldownLocked)
        assertEquals(20, (duringCooldown as PinVerifyResult.CooldownLocked).remainingSeconds)
        assertTrue(AppLockManager.isSessionLockedFlow.value)

        // 冷却期结束后（+30.5 秒）输入正确密码立即解锁
        val afterCooldown = AppLockManager.verifyPin("2580", nowMillis = t0 + 30_600L)
        assertEquals(PinVerifyResult.Success, afterCooldown)
        assertFalse(AppLockManager.isSessionLockedFlow.value)
    }

    @Test
    fun backgroundTimeoutAndLockNow_locksSessionAccordingToThreshold() {
        AppLockManager.enablePasswordProtection("2580", "2580")
        assertFalse(AppLockManager.isSessionLockedFlow.value)

        // 0 秒档位：切后台立即锁定
        AppLockManager.onAppBackgrounded(nowMillis = 10_000L)
        assertTrue(AppLockManager.isSessionLockedFlow.value)
        AppLockManager.verifyPin("2580")
        assertFalse(AppLockManager.isSessionLockedFlow.value)

        // 60 秒档位：离开 30 秒不锁定，离开 61 秒自动锁定
        AppLockManager.updateLockTimeout(60)
        assertEquals("已开启 · 1 分钟后锁定", AppLockManager.configFlow.value.settingsTrailingText)
        AppLockManager.onAppBackgrounded(nowMillis = 20_000L)
        AppLockManager.onAppForegrounded(nowMillis = 50_000L)
        assertFalse(AppLockManager.isSessionLockedFlow.value)

        AppLockManager.onAppBackgrounded(nowMillis = 100_000L)
        AppLockManager.onAppForegrounded(nowMillis = 161_000L)
        assertTrue(AppLockManager.isSessionLockedFlow.value)
    }

    @Test
    fun notificationIdForTransaction_assignsDistinctIdsForFirstThousandTransactions() {
        val ids = (1L..1000L).map { SilentBookkeepingNotifier.notificationIdForTransaction(it) }.toSet()
        assertEquals(1000, ids.size)
        assertTrue(ids.all { it in 1001..9999 })
    }

    @Test
    fun recurrenceFrequency_computesCalendarMonthAndYearWithoutFixedDayDrift() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 15, 9, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val baseMillis = cal.timeInMillis

        val nextMonthly = RecurrenceFrequency.MONTHLY.computeNextTriggerMillis(baseMillis, baseMillis)
        val monthlyCal = Calendar.getInstance().apply { timeInMillis = nextMonthly }
        assertEquals(2026, monthlyCal.get(Calendar.YEAR))
        assertEquals(Calendar.FEBRUARY, monthlyCal.get(Calendar.MONTH))
        assertEquals(15, monthlyCal.get(Calendar.DAY_OF_MONTH))

        val nextYearly = RecurrenceFrequency.YEARLY.computeNextTriggerMillis(baseMillis, baseMillis)
        val yearlyCal = Calendar.getInstance().apply { timeInMillis = nextYearly }
        assertEquals(2027, yearlyCal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, yearlyCal.get(Calendar.MONTH))
        assertEquals(15, yearlyCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun fullBackupSnapshot_preservesSmartRulesSavingPlansAndInstallmentPlans() {
        val rules = listOf(
            SmartConditionRule(
                id = 101L,
                name = "大额打车自动报销",
                keywordContains = "滴滴",
                minAmountYuan = 50.0,
                targetCategory = "交通-打车",
                markReimbursable = true,
                appendTag = "差旅"
            )
        )
        val savingPlans = listOf(
            SavingPlanItem(
                id = 201L,
                title = "应急备用金",
                targetCents = 5_000_000L,
                savedCents = 2_000_000L,
                deadlineLabel = "2026-12-31"
            )
        )
        val installmentPlans = listOf(
            InstallmentPlanItem(
                id = 301L,
                title = "MacBook Pro 12期",
                walletId = 1L,
                totalCents = 1_499_900L,
                feePerPeriodCents = 0L,
                totalPeriods = 12,
                paidPeriods = 4,
                category = "数码"
            )
        )

        val json = BackupAndCsvTransferEngine.exportFullBackupJson(
            ledgers = listOf("默认账本"),
            monthlyBudgetCents = 300_000L,
            wallets = emptyList(),
            transactions = emptyList(),
            recurringRules = emptyList(),
            merchantMemories = emptyList(),
            customExpenseCategories = emptyList(),
            customIncomeCategories = emptyList(),
            customTags = emptyList(),
            quickTemplates = emptyList(),
            smartRules = rules,
            savingPlans = savingPlans,
            installmentPlans = installmentPlans,
            customCurrencyRates = mapOf("USD" to 7.25),
            reminderSettingsJson = "",
            appearanceSettingsJson = ""
        )

        val parsed = BackupAndCsvTransferEngine.parseFullBackupJson(json)
        assertNotNull(parsed)
        val snapshot = parsed!!.third
        assertEquals(1, snapshot.smartRules.size)
        assertEquals("大额打车自动报销", snapshot.smartRules.first().name)
        assertEquals(1, snapshot.savingPlans.size)
        assertEquals("应急备用金", snapshot.savingPlans.first().title)
        assertEquals(1, snapshot.installmentPlans.size)
        assertEquals("MacBook Pro 12期", snapshot.installmentPlans.first().title)
    }
}
