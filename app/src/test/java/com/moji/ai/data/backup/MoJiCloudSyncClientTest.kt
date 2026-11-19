package com.moji.ai.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoJiCloudSyncClientTest {

    @Test
    fun `generated sync key has high entropy format and passes validation`() {
        val key1 = MoJiCloudSyncClient.generateSyncKey()
        val key2 = MoJiCloudSyncClient.generateSyncKey()
        assertNotEquals(key1, key2)
        val validated = MoJiCloudSyncClient.validateSyncKey(key1)
        assertTrue(validated.isSuccess)
        assertEquals(key1, validated.getOrNull())
        // 确保不含易混淆字符 0, O, 1, I（除前缀中的 O/I 外）
        val suffix = key1.removePrefix(MoJiCloudSyncClient.expectedKeyPrefix)
        assertFalse(suffix.contains('0'))
        assertFalse(suffix.contains('O'))
        assertFalse(suffix.contains('1'))
        assertFalse(suffix.contains('I'))
    }

    @Test
    fun `family pairing token encodes url and access code without leaking personal sync key`() {
        val personalKey = MoJiCloudSyncClient.generateSyncKey()
        val encoded = MoJiCloudSyncClient.encodeFamilyPairingText(
            serverUrl = "https://sync.moji-family.example.com/",
            serverAccessCode = "Home#2026!Secret"
        )
        assertTrue(encoded.isSuccess)
        val pairStr = encoded.getOrThrow()
        assertFalse(pairStr.contains(personalKey))

        val decoded = MoJiCloudSyncClient.decodeFamilyPairingText(pairStr)
        assertTrue(decoded.isSuccess)
        val (url, code) = decoded.getOrThrow()
        assertEquals("https://sync.moji-family.example.com", url)
        assertEquals("Home#2026!Secret", code)
    }

    @Test
    fun `family pairing decoder rejects personal sync key and cleartext public http`() {
        val personalKey = MoJiCloudSyncClient.generateSyncKey()
        val keyAttempt = MoJiCloudSyncClient.decodeFamilyPairingText(personalKey)
        assertTrue(keyAttempt.isFailure)
        assertTrue(keyAttempt.exceptionOrNull()!!.message!!.contains("个人专属同步密钥"))

        val insecureUrl = MoJiCloudSyncClient.encodeFamilyPairingText(
            serverUrl = "http://8.130.1.2:8787",
            serverAccessCode = "1234"
        )
        assertTrue(insecureUrl.isFailure)
        assertTrue(insecureUrl.exceptionOrNull()!!.message!!.contains("HTTPS"))
    }

    @Test
    fun `auto push safety guard blocks unbound or out-of-sync devices`() {
        val key = MoJiCloudSyncClient.generateSyncKey()
        val url = "https://sync.example.com"
        val sig = MoJiCloudSyncClient.computeTargetSignature(url, key)

        // 新手机刚绑上已有云端版本（remoteRevision=5, lastSyncedRevision=0），严禁自动推送覆盖
        val unpulledNewPhone = MoJiCloudConfig(
            serverUrl = url,
            syncKey = key,
            autoSyncEnabled = true,
            userId = "eb8f5605-4193-4e20-b60f-1c41684369a0",
            boundTargetSignature = sig,
            lastSyncedRevision = 0L,
            remoteRevision = 5L
        )
        assertFalse(unpulledNewPhone.canAutoPushSafely)

        // 拉取对齐版本后允许安全自动同步
        val alignedPhone = unpulledNewPhone.copy(lastSyncedRevision = 5L)
        assertTrue(alignedPhone.canAutoPushSafely)

        // 修改了密钥文本但尚未重新绑定时，立即阻断自动同步且重置有效修订号为 0
        val changedKeyDraft = alignedPhone.copy(syncKey = MoJiCloudSyncClient.generateSyncKey())
        assertFalse(changedKeyDraft.isTargetBound)
        assertFalse(changedKeyDraft.canAutoPushSafely)
        assertEquals(0L, changedKeyDraft.effectiveLastSyncedRevision)
    }

    @Test
    fun `validateSyncKey strictly rejects non-ASCII letters and fullwidth digits`() {
        val prefix = MoJiCloudSyncClient.expectedKeyPrefix
        assertTrue(MoJiCloudSyncClient.validateSyncKey("${prefix}测试账本-1234-ABCD-EFGH").isFailure)
        assertTrue(MoJiCloudSyncClient.validateSyncKey("${prefix}１２３４-ABCD-EFGH-2345").isFailure)
        assertTrue(MoJiCloudSyncClient.validateSyncKey("${prefix}ABCD-EFGH-2345-6789").isSuccess)
    }

    @Test
    fun `restore suppression window blocks auto sync during and immediately after restore`() {
        MoJiCloudSyncClient.endRestoreSuppression(cooldownMs = 0L)
        Thread.sleep(5L)
        assertFalse(MoJiCloudSyncClient.shouldSuppressAutoSync())

        MoJiCloudSyncClient.beginRestoreSuppression()
        assertTrue(MoJiCloudSyncClient.shouldSuppressAutoSync())

        MoJiCloudSyncClient.endRestoreSuppression(cooldownMs = 80L)
        assertTrue(MoJiCloudSyncClient.shouldSuppressAutoSync())

        Thread.sleep(100L)
        assertFalse(MoJiCloudSyncClient.shouldSuppressAutoSync())
    }

    @Test
    fun `restored state suppresses late restore echo without dropping prefs or metadata edits`() {
        val wallets = emptyList<com.moji.ai.data.local.WalletEntity>()
        val rules = emptyList<com.moji.ai.data.local.RecurringRuleEntity>()
        val tx1 = com.moji.ai.data.local.TransactionEntity(
            id = 1L,
            ledgerName = "默认账本",
            type = com.moji.ai.data.model.TransactionType.EXPENSE,
            amountCents = 1800L,
            amountCnyCents = 1800L,
            category = "餐饮",
            merchant = "瑞幸咖啡",
            sourceWalletId = null,
            timestamp = 1_762_574_400_000L,
            updatedAtMillis = 1_762_574_400_000L
        )
        val restoredSig = MoJiCloudSyncClient.computeRoomSignature(wallets, listOf(tx1), rules)

        // 场景 1：迟到的 Room 恢复回声（roomSig 与 mutationSeq 均未变）精确消费一次并拦截
        MoJiCloudSyncClient.recordRestoredState(restoredSig, mutationSeq = 10L)
        assertTrue(MoJiCloudSyncClient.consumeIfMatchesRestoredState(restoredSig, mutationSeq = 10L))
        assertFalse(MoJiCloudSyncClient.consumeIfMatchesRestoredState(restoredSig, mutationSeq = 10L))

        // 场景 2：Room 在恢复期间已被上游抑制，随后用户仅修改了 SharedPreferences（mutationSeq 递增但 roomSig 不变）
        // 绝不能把用户的真实偏好修改误吞为恢复回声！
        MoJiCloudSyncClient.recordRestoredState(restoredSig, mutationSeq = 10L)
        assertFalse(MoJiCloudSyncClient.consumeIfMatchesRestoredState(restoredSig, mutationSeq = 11L))

        // 场景 3：仅修改备注/分类（即使 updatedAtMillis 未刷新），Room 签名必须变化；而内部回填 screenshotSha256 不变签名
        val noteEditedSig = MoJiCloudSyncClient.computeRoomSignature(
            wallets,
            listOf(tx1.copy(note = "补充发票号")),
            rules
        )
        assertNotEquals(restoredSig, noteEditedSig)

        val shaBackfilledSig = MoJiCloudSyncClient.computeRoomSignature(
            wallets,
            listOf(tx1.copy(screenshotSha256 = "a".repeat(64))),
            rules
        )
        assertEquals(restoredSig, shaBackfilledSig)
    }
}
