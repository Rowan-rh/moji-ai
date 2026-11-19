package com.moji.ai.ai

import com.moji.ai.data.backup.BackupAndCsvTransferEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CaptureAppWhitelistManagerTest {

    @Before
    fun setUp() {
        CaptureAppWhitelistManager.resetForTesting()
    }

    @After
    fun tearDown() {
        CaptureAppWhitelistManager.resetForTesting()
    }

    @Test
    fun defaultPresets_enforceThreeTierDualChannelPolicy() {
        // 第一梯队：核心支付、短信、主流银行 -> 通知与无障碍双开
        assertTrue(CaptureAppWhitelistManager.isNotificationAllowed("com.tencent.mm"))
        assertTrue(CaptureAppWhitelistManager.isAccessibilityAllowed("com.tencent.mm"))
        assertTrue(CaptureAppWhitelistManager.isNotificationAllowed("com.eg.android.AlipayGphone"))
        assertTrue(CaptureAppWhitelistManager.isAccessibilityAllowed("com.eg.android.AlipayGphone"))
        assertTrue(CaptureAppWhitelistManager.isNotificationAllowed("com.unionpay"))
        assertTrue(CaptureAppWhitelistManager.isAccessibilityAllowed("com.unionpay"))
        assertTrue(CaptureAppWhitelistManager.isNotificationAllowed("cmb.pb"))
        assertTrue(CaptureAppWhitelistManager.isAccessibilityAllowed("cmb.pb"))

        // 系统短信代表项自动联动常见国产 ROM 短信包名
        assertTrue(CaptureAppWhitelistManager.isNotificationAllowed("com.android.mms"))
        assertTrue(CaptureAppWhitelistManager.isNotificationAllowed("com.oplus.mms"))
        assertTrue(CaptureAppWhitelistManager.isAccessibilityAllowed("com.google.android.apps.messaging"))

        // 第二梯队：电商与外卖 -> 默认仅开通知，关闭无障碍自动扫屏防误触
        listOf(
            "com.jingdong.app.mall",
            "com.xunmeng.pinduoduo",
            "com.taobao.taobao",
            "com.tmall.wireless",
            "com.sankuai.meituan",
            "com.sankuai.meituan.takeoutnew",
            "me.ele"
        ).forEach { pkg ->
            assertTrue("$pkg notification should default to true", CaptureAppWhitelistManager.isNotificationAllowed(pkg))
            assertFalse("$pkg accessibility should default to false", CaptureAppWhitelistManager.isAccessibilityAllowed(pkg))
        }

        // 第三梯队：短视频、图文社区与特卖 -> 默认双通道关闭
        listOf(
            "com.ss.android.ugc.aweme",
            "com.ss.android.ugc.aweme.lite",
            "com.ss.android.ugc.live",
            "com.xingin.xhs",
            "com.achievo.vipshop"
        ).forEach { pkg ->
            assertFalse("$pkg notification should default to false", CaptureAppWhitelistManager.isNotificationAllowed(pkg))
            assertFalse("$pkg accessibility should default to false", CaptureAppWhitelistManager.isAccessibilityAllowed(pkg))
        }

        // 未在白名单内的任意第三方应用与自身/系统包 -> 严格拦截
        assertFalse(CaptureAppWhitelistManager.isNotificationAllowed("tv.danmaku.bili"))
        assertFalse(CaptureAppWhitelistManager.isAccessibilityAllowed("tv.danmaku.bili"))
        assertFalse(CaptureAppWhitelistManager.isNotificationAllowed("com.moji.ai"))
        assertFalse(CaptureAppWhitelistManager.isAccessibilityAllowed("com.moji.ai.qa"))
        assertFalse(CaptureAppWhitelistManager.isNotificationAllowed("com.android.systemui"))
    }

    @Test
    fun toggleChannelsAndSmsRepresentative_updatesHotPathImmediately() {
        // 开启京东无障碍识屏，关闭微信通知监听
        CaptureAppWhitelistManager.setChannelEnabled("com.jingdong.app.mall", enableAccessibility = true)
        CaptureAppWhitelistManager.setChannelEnabled("com.tencent.mm", enableNotification = false)
        assertTrue(CaptureAppWhitelistManager.isAccessibilityAllowed("com.jingdong.app.mall"))
        assertFalse(CaptureAppWhitelistManager.isNotificationAllowed("com.tencent.mm"))
        assertTrue(CaptureAppWhitelistManager.isAccessibilityAllowed("com.tencent.mm"))

        // 关闭系统短信代表项后，各 ROM 短信包名同步停止监听
        CaptureAppWhitelistManager.setChannelEnabled(
            CaptureAppWhitelistManager.SMS_REPRESENTATIVE_PACKAGE,
            enableNotification = false,
            enableAccessibility = false
        )
        assertFalse(CaptureAppWhitelistManager.isNotificationAllowed("com.oplus.mms"))
        assertFalse(CaptureAppWhitelistManager.isAccessibilityAllowed("com.huawei.message"))

        // 恢复默认
        CaptureAppWhitelistManager.resetToDefaults()
        assertFalse(CaptureAppWhitelistManager.isAccessibilityAllowed("com.jingdong.app.mall"))
        assertTrue(CaptureAppWhitelistManager.isNotificationAllowed("com.tencent.mm"))
        assertTrue(CaptureAppWhitelistManager.isNotificationAllowed("com.oplus.mms"))
    }

    @Test
    fun addAndRemoveCustomApp_validatesFormatAndBlocksSelfOrDuplicates() {
        val added = CaptureAppWhitelistManager.addCustomApp(
            rawPackageName = " com.bankofbeijing.mobilebanking ",
            rawAppName = " 北京银行 ",
            enableNotification = true,
            enableAccessibility = true
        )
        assertTrue(added.isSuccess)
        assertEquals("北京银行", added.getOrNull()?.appName)
        assertTrue(CaptureAppWhitelistManager.isNotificationAllowed("com.bankofbeijing.mobilebanking"))
        assertTrue(CaptureAppWhitelistManager.isAccessibilityAllowed("com.bankofbeijing.mobilebanking"))

        // 空名称自动使用包名末段兜底
        val fallbackAdded = CaptureAppWhitelistManager.addCustomApp(
            rawPackageName = "cn.com.njcb.android.mobilebank",
            rawAppName = "   ",
            enableNotification = true,
            enableAccessibility = false
        )
        assertTrue(fallbackAdded.isSuccess)
        assertEquals("mobilebank", fallbackAdded.getOrNull()?.appName)
        assertTrue(CaptureAppWhitelistManager.isNotificationAllowed("cn.com.njcb.android.mobilebank"))
        assertFalse(CaptureAppWhitelistManager.isAccessibilityAllowed("cn.com.njcb.android.mobilebank"))

        // 拒绝非法包名、自身包名、系统组件与重复包名
        assertTrue(CaptureAppWhitelistManager.addCustomApp("invalid_pkg", "测试").isFailure)
        assertTrue(CaptureAppWhitelistManager.addCustomApp("123.bad.pkg", "测试").isFailure)
        assertTrue(CaptureAppWhitelistManager.addCustomApp("com..double.dot", "测试").isFailure)
        assertTrue(CaptureAppWhitelistManager.addCustomApp("com.moji.ai", "默记").isFailure)
        assertTrue(CaptureAppWhitelistManager.addCustomApp("com.moji.ai.qa", "默记QA").isFailure)
        assertTrue(CaptureAppWhitelistManager.addCustomApp("com.moji.ai.debug", "默记Debug").isFailure)
        assertTrue(CaptureAppWhitelistManager.addCustomApp("com.android.systemui", "系统UI").isFailure)
        assertTrue(CaptureAppWhitelistManager.addCustomApp("com.oplus.mms", "OPPO短信重复覆盖").isFailure)
        assertTrue(CaptureAppWhitelistManager.addCustomApp("com.tencent.mm", "微信重复").isFailure)
        assertTrue(CaptureAppWhitelistManager.addCustomApp("com.bankofbeijing.mobilebanking", "北京银行重复").isFailure)

        // 内置预设不可通过 removeCustomApp 删除，自定义应用可正常移除
        assertFalse(CaptureAppWhitelistManager.removeCustomApp("com.tencent.mm"))
        assertTrue(CaptureAppWhitelistManager.removeCustomApp("com.bankofbeijing.mobilebanking"))
        assertFalse(CaptureAppWhitelistManager.isNotificationAllowed("com.bankofbeijing.mobilebanking"))
        assertFalse(CaptureAppWhitelistManager.isAccessibilityAllowed("com.bankofbeijing.mobilebanking"))
    }

    @Test
    fun backupExportRestoreAndForwardMerge_preservesUserChoicesAndFiltersUnsafeEntries() {
        CaptureAppWhitelistManager.setChannelEnabled("com.ss.android.ugc.aweme", enableNotification = true, enableAccessibility = true)
        CaptureAppWhitelistManager.setChannelEnabled("com.eg.android.AlipayGphone", enableAccessibility = false)
        CaptureAppWhitelistManager.addCustomApp("com.hzbank.perbank", "杭州银行", enableNotification = true, enableAccessibility = false)

        val fullBackupJson = BackupAndCsvTransferEngine.exportFullBackupJson(
            ledgers = listOf("默认账本"),
            monthlyBudgetCents = 0L,
            wallets = emptyList(),
            transactions = emptyList(),
            recurringRules = emptyList(),
            merchantMemories = emptyList()
        )
        val parsed = BackupAndCsvTransferEngine.parseFullBackupJson(fullBackupJson)
        assertNotNull(parsed)
        val snapshot = parsed!!.third
        assertTrue(snapshot.captureWhitelistJson.isNotBlank())

        CaptureAppWhitelistManager.resetForTesting()
        assertFalse(CaptureAppWhitelistManager.isNotificationAllowed("com.ss.android.ugc.aweme"))

        assertTrue(CaptureAppWhitelistManager.restoreFromBackupJson(snapshot.captureWhitelistJson))
        assertTrue(CaptureAppWhitelistManager.isNotificationAllowed("com.ss.android.ugc.aweme"))
        assertTrue(CaptureAppWhitelistManager.isAccessibilityAllowed("com.ss.android.ugc.aweme"))
        assertFalse(CaptureAppWhitelistManager.isAccessibilityAllowed("com.eg.android.AlipayGphone"))
        assertTrue(CaptureAppWhitelistManager.isNotificationAllowed("com.hzbank.perbank"))
        assertFalse(CaptureAppWhitelistManager.isAccessibilityAllowed("com.hzbank.perbank"))

        // 旧版部分列表 + 混入脏包名：恢复后自动补齐缺失的内置预设并剔除受阻断系统包
        val partialAndDirtyJson = """
            [
              {"packageName":"com.tencent.mm","appName":"微信","group":"CORE_PAYMENT_AND_BANK","enableNotification":false,"enableAccessibility":true,"isCustom":false},
              {"packageName":"com.moji.ai","appName":"伪造自身","group":"CUSTOM","enableNotification":true,"enableAccessibility":true,"isCustom":true},
              {"packageName":"com.shbank.eyun","appName":"上海银行","group":"CUSTOM","enableNotification":true,"enableAccessibility":true,"isCustom":true}
            ]
        """.trimIndent()
        assertTrue(CaptureAppWhitelistManager.restoreFromBackupJson(partialAndDirtyJson))
        assertFalse(CaptureAppWhitelistManager.isNotificationAllowed("com.tencent.mm"))
        assertTrue(CaptureAppWhitelistManager.isNotificationAllowed("com.eg.android.AlipayGphone")) // 缺失内置项自动补齐默认
        assertTrue(CaptureAppWhitelistManager.isNotificationAllowed("com.shbank.eyun"))
        assertFalse(CaptureAppWhitelistManager.isNotificationAllowed("com.moji.ai"))

        // 旧版无 captureWhitelistJson 字段的备份兼容解析
        val legacySnapshotJson = """{"schemaVersion":1,"appName":"MoJi","ledgers":["默认账本"]}"""
        val legacyParsed = BackupAndCsvTransferEngine.parseFullBackupJson(legacySnapshotJson)
        assertNotNull(legacyParsed)
        assertEquals("", legacyParsed!!.third.captureWhitelistJson)
    }
}
