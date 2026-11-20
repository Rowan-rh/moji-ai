package com.moji.ai.data.model

import com.moji.ai.data.backup.BackupAndCsvTransferEngine
import com.moji.ai.data.backup.MoJiCloudSyncClient
import com.moji.ai.update.AppUpdateConfig
import com.moji.ai.update.AppUpdateManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UserProfileAndOtaUpdateTest {

    @Before
    fun setUp() {
        UserProfileManager.resetForTesting()
    }

    @After
    fun tearDown() {
        UserProfileManager.resetForTesting()
    }

    @Test
    fun defaultUserProfile_hasDefaultNicknameAndEightMorandiPresets() {
        val cfg = UserProfileManager.configFlow.value
        assertEquals("默记用户", cfg.nickname)
        assertEquals(PresetAvatarStyle.MINT_CLASSIC, cfg.presetAvatar)
        assertFalse(cfg.hasCustomAvatarConfigured)
        assertEquals(8, PresetAvatarStyle.entries.size)
        assertEquals(
            PresetAvatarStyle.MIST_LAVENDER,
            PresetAvatarStyle.fromNameOrDefault("mist_lavender")
        )
        assertEquals(
            PresetAvatarStyle.MINT_CLASSIC,
            PresetAvatarStyle.fromNameOrDefault("UNKNOWN_STYLE")
        )
    }

    @Test
    fun normalizeNickname_handlesBlankWhitespaceControlCharsAndSixteenCodePointLimit() {
        assertEquals("默记用户", UserProfileManager.normalizeNickname(null))
        assertEquals("默记用户", UserProfileManager.normalizeNickname(""))
        assertEquals("默记用户", UserProfileManager.normalizeNickname("   \t\n  "))
        assertEquals("小默 记账", UserProfileManager.normalizeNickname("  小默\n\t  记账  "))

        val longText = "一二三四五六七八九十甲乙丙丁戊己庚辛壬癸"
        val normalizedLong = UserProfileManager.normalizeNickname(longText)
        assertEquals(16, normalizedLong.codePointCount(0, normalizedLong.length))
        assertEquals("一二三四五六七八九十甲乙丙丁戊己", normalizedLong)

        // 包含代理对 Emoji 时按码点安全截断，不产生半个孤立代理字符
        val emojiNickname = "🐱🐶🐰🦊🐻🐼🐨🐯🦁🐮🐷🐸🐵🐔🐧🐦🐤🦆"
        val normalizedEmoji = UserProfileManager.normalizeNickname(emojiNickname)
        assertEquals(16, normalizedEmoji.codePointCount(0, normalizedEmoji.length))
        assertFalse(normalizedEmoji.any { it.isSurrogate() && !Character.isDefined(normalizedEmoji.codePointAt(0)) })
    }

    @Test
    fun resolveValidCustomAvatarFile_enforcesSandboxAndImageMagicHeaderWithGracefulFallback() {
        val tempRoot = Files.createTempDirectory("moji_profile_test").toFile()
        try {
            val profileDir = File(tempRoot, "files/profile").apply { mkdirs() }
            val outsideDir = File(tempRoot, "outside").apply { mkdirs() }
            UserProfileManager.resetForTesting(sandboxDirPrefix = profileDir.canonicalPath)

            val validWebpBytes = byteArrayOf(
                'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
                0x24, 0x00, 0x00, 0x00,
                'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
                'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), ' '.code.toByte(),
                0x10, 0x20, 0x30, 0x40
            )
            val validFile = File(profileDir, "avatar_valid.webp").apply {
                writeBytes(validWebpBytes)
            }
            val corruptFile = File(profileDir, "avatar_corrupt.webp").apply {
                writeBytes("not-an-image-header-at-all".toByteArray())
            }
            val outsideFile = File(outsideDir, "avatar_outside.webp").apply {
                writeBytes(validWebpBytes)
            }

            assertNotNull(UserProfileManager.resolveValidCustomAvatarFile(validFile.absolutePath))
            assertNull(UserProfileManager.resolveValidCustomAvatarFile(corruptFile.absolutePath))
            assertNull(UserProfileManager.resolveValidCustomAvatarFile(outsideFile.absolutePath))
            assertNull(UserProfileManager.resolveValidCustomAvatarFile(File(profileDir, "missing.webp").absolutePath))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun fullBackupSnapshot_carriesNicknameAndPresetNonDestructivelyAndStaysCompatibleWithLegacyJson() {
        UserProfileManager.resetForTesting(
            UserProfileConfig(
                nickname = "阿浩的账本",
                presetAvatar = PresetAvatarStyle.SLATE_TEAL,
                customAvatarPath = "/data/user/0/com.moji.ai/files/profile/avatar_123.webp"
            )
        )
        val exportedProfileJson = UserProfileManager.exportBackupJson()
        // 备份快照中绝不泄露本机私有绝对路径
        assertFalse(exportedProfileJson.contains("/data/user/"))

        val fullSnapshotJson = BackupAndCsvTransferEngine.exportFullBackupJson(
            ledgers = listOf("默认账本"),
            monthlyBudgetCents = 0L,
            wallets = emptyList(),
            transactions = emptyList(),
            recurringRules = emptyList(),
            merchantMemories = emptyList(),
            userProfileJson = exportedProfileJson
        )
        val parsed = BackupAndCsvTransferEngine.parseFullBackupJson(fullSnapshotJson)
        assertNotNull(parsed)
        assertEquals(exportedProfileJson, parsed!!.third.userProfileJson)

        // 模拟换新机恢复：自定义头像本地文件不存在时自动降级为预设头像，昵称与预设完整还原
        UserProfileManager.resetForTesting()
        assertTrue(UserProfileManager.restoreFromBackupJson(parsed.third.userProfileJson))
        val restoredCfg = UserProfileManager.configFlow.value
        assertEquals("阿浩的账本", restoredCfg.nickname)
        assertEquals(PresetAvatarStyle.SLATE_TEAL, restoredCfg.presetAvatar)
        assertEquals("", restoredCfg.customAvatarPath)

        // 旧版 `.mojibackup` 缺失 `userProfileJson` 字段时非破坏性跳过，不清空用户现有资料
        val legacySnapshotJson = """
            {
              "schemaVersion": 3,
              "ledgers": ["默认账本"],
              "wallets": [],
              "transactions": []
            }
        """.trimIndent()
        val parsedLegacy = BackupAndCsvTransferEngine.parseFullBackupJson(legacySnapshotJson)
        assertNotNull(parsedLegacy)
        assertEquals("", parsedLegacy!!.third.userProfileJson)
        assertFalse(UserProfileManager.restoreFromBackupJson(parsedLegacy.third.userProfileJson))
        assertEquals("阿浩的账本", UserProfileManager.configFlow.value.nickname)
    }

    @Test
    fun maskedSyncKeyHint_masksMiddleSegmentsWithoutExposingFullSecret() {
        assertEquals(
            "MOJI-****-9Z8Y",
            MoJiCloudSyncClient.maskedSyncKeyHint("MOJI-ABCD-EFGH-JKLM-9Z8Y")
        )
        assertEquals(
            "MOJIQA-****-9Z8Y",
            MoJiCloudSyncClient.maskedSyncKeyHint("MOJIQA-ABCD-EFGH-JKLM-9Z8Y")
        )
        assertEquals("", MoJiCloudSyncClient.maskedSyncKeyHint("   "))
    }

    @Test
    fun publicRepoOta_parsesReleaseSha256AndEnforcesHttpsTrustedGitHubHosts() {
        assertEquals("Rowan-rh/moji-ai", AppUpdateManager.OFFICIAL_GITHUB_REPO)
        val defaultUpdateCfg = AppUpdateConfig(autoCheckOnLaunch = true, includePrerelease = false)
        assertTrue(defaultUpdateCfg.autoCheckOnLaunch)
        assertFalse(defaultUpdateCfg.includePrerelease)

        val expectedSha = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0"
        val sampleReleaseJson = """
            {
              "tag_name": "v1.2.0",
              "name": "MoJi v1.2.0 正式版",
              "body": "1. 优化快捷模板三端排版\n2. 新增个人资料卡片",
              "published_at": "2026-11-20T08:00:00Z",
              "html_url": "https://github.com/Rowan-rh/moji-ai/releases/tag/v1.2.0",
              "prerelease": false,
              "assets": [
                {
                  "name": "moji.apk",
                  "size": 18874368,
                  "url": "https://api.github.com/repos/Rowan-rh/moji-ai/releases/assets/9988",
                  "browser_download_url": "https://github.com/Rowan-rh/moji-ai/releases/download/v1.2.0/moji.apk",
                  "digest": "sha256:$expectedSha"
                }
              ]
            }
        """.trimIndent()
        val parsedObj = Json.parseToJsonElement(sampleReleaseJson).jsonObject
        val info = AppUpdateManager.parseReleaseInfo(parsedObj)
        assertNotNull(info)
        assertEquals("v1.2.0", info!!.tagName)
        assertEquals("moji.apk", info.apkFileName)
        assertEquals(expectedSha, info.sha256Digest)

        // 仅信任 HTTPS + GitHub 官方及对象存储域名
        assertTrue(AppUpdateManager.isValidTrustedHttpsDownloadUrl(info.browserDownloadUrl))
        assertTrue(AppUpdateManager.isValidTrustedHttpsDownloadUrl("https://objects.githubusercontent.com/github-production-release-asset/apk"))
        assertTrue(AppUpdateManager.isValidTrustedHttpsDownloadUrl("https://release-assets.githubusercontent.com/moji.apk"))
        assertFalse(AppUpdateManager.isValidTrustedHttpsDownloadUrl("http://github.com/Rowan-rh/moji-ai/releases/download/v1.2.0/moji.apk"))
        assertFalse(AppUpdateManager.isValidTrustedHttpsDownloadUrl("https://evil-github.com/moji.apk"))
        assertFalse(AppUpdateManager.isValidTrustedHttpsDownloadUrl("https://githubusercontent.com.attacker.example/moji.apk"))

        // ZIP 魔数与最小文件长度校验
        val tempDir = Files.createTempDirectory("moji_apk_check").toFile()
        try {
            val validApk = File(tempDir, "valid.apk").apply {
                val bytes = ByteArray(1200)
                bytes[0] = 0x50
                bytes[1] = 0x4B
                bytes[2] = 0x03
                bytes[3] = 0x04
                writeBytes(bytes)
            }
            val htmlErrorPage = File(tempDir, "error.apk").apply {
                writeBytes("<html>Not Found</html>".repeat(80).toByteArray())
            }
            assertTrue(AppUpdateManager.isValidApkFile(validApk))
            assertFalse(AppUpdateManager.isValidApkFile(htmlErrorPage))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
