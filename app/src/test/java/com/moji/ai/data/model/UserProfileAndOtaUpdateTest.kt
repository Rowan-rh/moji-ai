package com.moji.ai.data.model

import com.moji.ai.data.backup.BackupAndCsvTransferEngine
import com.moji.ai.data.backup.MoJiCloudConfig
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

    @Test
    fun cloudRestoreAndNicknameSync_preservesRestoredNicknameOverDeviceModelAndProtectsCustomDeviceLabel() {
        val defaultDevice = "Google Pixel 8"
        val serverUrl = "https://cloud.example.com"
        val syncKey = "MOJI-ABCD-EFGH-JKLM-9Z8Y"
        val targetSig = MoJiCloudSyncClient.computeTargetSignature(serverUrl, syncKey)

        val baseBoundConfig = MoJiCloudConfig(
            serverUrl = serverUrl,
            syncKey = syncKey,
            userId = "u_1001",
            boundTargetSignature = targetSig,
            memberLabel = defaultDevice
        )

        // 1. 新机恢复快照：本地 memberLabel 为默认机型名时，自动同步为快照昵称，且 commitPulledConfigAfterRestore 不将其覆盖回默认机型名
        val syncedFromRestore = MoJiCloudSyncClient.resolveSyncedMemberLabel(
            current = baseBoundConfig,
            newNickname = "Rowan",
            previousNickname = "默记用户",
            fromSnapshotRestore = true,
            defaultDeviceLabel = defaultDevice
        )
        assertEquals("Rowan", syncedFromRestore)

        val latestAfterSnapshotApply = baseBoundConfig.copy(memberLabel = syncedFromRestore!!)
        val pulledBeforeApply = baseBoundConfig.copy(
            memberLabel = defaultDevice,
            lastSyncedRevision = 12L,
            remoteRevision = 12L
        )
        val mergedNewDevice = MoJiCloudSyncClient.mergePulledConfigAfterRestore(
            latest = latestAfterSnapshotApply,
            pulledConfig = pulledBeforeApply,
            defaultDeviceLabel = defaultDevice,
            preRestoreMemberLabel = defaultDevice
        )
        assertEquals("Rowan", mergedNewDevice.memberLabel)
        assertEquals(12L, mergedNewDevice.lastSyncedRevision)

        // 2. 存量设备曾自动同步过旧昵称 "OldRowan"，云端快照更新为 "NewRowan" 时，拉取恢复后完整保留新昵称
        val oldNickConfig = baseBoundConfig.copy(memberLabel = "OldRowan")
        val syncedUpdatedNick = MoJiCloudSyncClient.resolveSyncedMemberLabel(
            current = oldNickConfig,
            newNickname = "NewRowan",
            previousNickname = "OldRowan",
            fromSnapshotRestore = true,
            defaultDeviceLabel = defaultDevice
        )
        assertEquals("NewRowan", syncedUpdatedNick)
        val mergedUpdatedNick = MoJiCloudSyncClient.mergePulledConfigAfterRestore(
            latest = oldNickConfig.copy(memberLabel = syncedUpdatedNick!!),
            pulledConfig = oldNickConfig.copy(lastSyncedRevision = 15L, remoteRevision = 15L),
            defaultDeviceLabel = defaultDevice,
            preRestoreMemberLabel = "OldRowan"
        )
        assertEquals("NewRowan", mergedUpdatedNick.memberLabel)

        // 3. 家庭共享场景下某台设备曾手动设置专属备注（如 "家人iPad"）：快照恢复不覆盖自定义备注，而用户主动改昵称仍可联动
        val customDeviceConfig = baseBoundConfig.copy(memberLabel = "家人iPad")
        val restoreOnCustomDevice = MoJiCloudSyncClient.resolveSyncedMemberLabel(
            current = customDeviceConfig,
            newNickname = "Rowan",
            previousNickname = "默记用户",
            fromSnapshotRestore = true,
            defaultDeviceLabel = defaultDevice
        )
        assertNull(restoreOnCustomDevice)

        val mergedCustomDevice = MoJiCloudSyncClient.mergePulledConfigAfterRestore(
            latest = customDeviceConfig,
            pulledConfig = customDeviceConfig.copy(lastSyncedRevision = 18L, remoteRevision = 18L),
            defaultDeviceLabel = defaultDevice,
            preRestoreMemberLabel = "家人iPad"
        )
        assertEquals("家人iPad", mergedCustomDevice.memberLabel)

        val activeRenameOnBoundDevice = MoJiCloudSyncClient.resolveSyncedMemberLabel(
            current = customDeviceConfig,
            newNickname = "阿浩的主力机",
            previousNickname = "默记用户",
            fromSnapshotRestore = false,
            defaultDeviceLabel = defaultDevice
        )
        assertEquals("阿浩的主力机", activeRenameOnBoundDevice)
    }

    @Test
    fun computeAvatarDecodeSampleSize_constrainsBothShortSideAndExtremeLongScreenshot() {
        // 常规比例照片：保持短边 >= 384px 的清晰度
        assertEquals(1, UserProfileManager.computeAvatarDecodeSampleSize(384, 384))
        assertEquals(1, UserProfileManager.computeAvatarDecodeSampleSize(600, 720))
        assertEquals(2, UserProfileManager.computeAvatarDecodeSampleSize(1080, 1440))
        assertEquals(4, UserProfileManager.computeAvatarDecodeSampleSize(3024, 4032))

        // 窄而极长的滚动截图（如 400x24000 / 384x30000）：受长边 4096px 上限约束继续降采样，避免瞬时分配数十 MB 内存
        val sampleNarrowLong = UserProfileManager.computeAvatarDecodeSampleSize(400, 24000)
        assertEquals(8, sampleNarrowLong)
        assertTrue(24000 / sampleNarrowLong <= UserProfileManager.MAX_DECODE_LONG_SIDE_PX)

        val sampleUltraLong = UserProfileManager.computeAvatarDecodeSampleSize(384, 30000)
        assertEquals(8, sampleUltraLong)
        assertTrue(30000 / sampleUltraLong <= UserProfileManager.MAX_DECODE_LONG_SIDE_PX)

        // 非法或极端大整数边界安全兜底
        assertEquals(1, UserProfileManager.computeAvatarDecodeSampleSize(0, 0))
        assertEquals(1, UserProfileManager.computeAvatarDecodeSampleSize(-10, 500))
        val sampleMaxInt = UserProfileManager.computeAvatarDecodeSampleSize(100, Int.MAX_VALUE)
        assertTrue(sampleMaxInt >= 524288)
        assertTrue(Int.MAX_VALUE / sampleMaxInt <= UserProfileManager.MAX_DECODE_LONG_SIDE_PX)
    }

    @Test
    fun isValidWebpAvatarBytes_enforcesMagicHeaderAndCloudSizeBounds() {
        val validWebp = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0x20, 0x00, 0x00, 0x00,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
            'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), 'L'.code.toByte(),
            0x01, 0x02, 0x03, 0x04
        )
        assertTrue(UserProfileManager.isValidWebpAvatarBytes(validWebp))
        assertFalse(UserProfileManager.isValidWebpAvatarBytes(ByteArray(0)))
        assertFalse(UserProfileManager.isValidWebpAvatarBytes(ByteArray(12)))
        assertFalse(UserProfileManager.isValidWebpAvatarBytes("PNG_FAKE_HEADER_12345678".toByteArray()))

        val oversizedWebp = ByteArray(UserProfileManager.MAX_CLOUD_AVATAR_BYTES + 1).apply {
            validWebp.copyInto(this, 0, 0, 16)
        }
        assertFalse(UserProfileManager.isValidWebpAvatarBytes(oversizedWebp))
    }
}
