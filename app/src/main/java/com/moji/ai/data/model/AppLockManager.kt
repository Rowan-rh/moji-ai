package com.moji.ai.data.model

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.ceil

data class PasswordProtectionConfig(
    val enabled: Boolean = false,
    val lockTimeoutSeconds: Int = 0,
    val hideRecentTaskPreview: Boolean = false,
    val pinHint: String = ""
) {
    val timeoutDisplayLabel: String
        get() = when (lockTimeoutSeconds) {
            60 -> "1 分钟后锁定"
            300 -> "5 分钟后锁定"
            else -> "立即锁定"
        }

    val settingsTrailingText: String
        get() = if (enabled) "已开启 · $timeoutDisplayLabel" else "未开启"
}

sealed class PinVerifyResult {
    data object Success : PinVerifyResult()
    data class InvalidFormat(val message: String) : PinVerifyResult()
    data class Mismatch(val remainingAttempts: Int, val message: String) : PinVerifyResult()
    data class CooldownLocked(val remainingSeconds: Int, val message: String) : PinVerifyResult()
}

object AppLockManager {
    const val PREFS_NAME = "moji_app_lock_prefs"
    private const val KEY_ENABLED = "app_lock_enabled"
    private const val KEY_PIN_SALT = "app_lock_pin_salt"
    private const val KEY_PIN_HASH = "app_lock_pin_hash"
    private const val KEY_LOCK_TIMEOUT = "app_lock_timeout_seconds"
    private const val KEY_HIDE_RECENT = "app_lock_hide_recent_preview"
    private const val KEY_PIN_HINT = "app_lock_pin_hint"

    const val MAX_FAILED_ATTEMPTS = 5
    const val COOLDOWN_DURATION_MILLIS = 30_000L
    const val MAX_PIN_HINT_LENGTH = 20
    val SUPPORTED_TIMEOUT_SECONDS = listOf(0, 60, 300)

    private val secureRandom = SecureRandom()
    @Volatile private var prefs: SharedPreferences? = null
    private var pinSalt: String = ""
    private var pinHash: String = ""
    private var failedAttempts: Int = 0
    private var cooldownUntilMillis: Long = 0L
    private var lastBackgroundedAtMillis: Long = 0L

    private val _configFlow = MutableStateFlow(PasswordProtectionConfig())
    val configFlow: StateFlow<PasswordProtectionConfig> = _configFlow.asStateFlow()

    private val _isSessionLockedFlow = MutableStateFlow(false)
    val isSessionLockedFlow: StateFlow<Boolean> = _isSessionLockedFlow.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        pinSalt = sp.getString(KEY_PIN_SALT, "") ?: ""
        pinHash = sp.getString(KEY_PIN_HASH, "") ?: ""
        val enabled = sp.getBoolean(KEY_ENABLED, false) && pinSalt.isNotBlank() && pinHash.isNotBlank()
        _configFlow.value = PasswordProtectionConfig(
            enabled = enabled,
            lockTimeoutSeconds = normalizeTimeout(sp.getInt(KEY_LOCK_TIMEOUT, 0)),
            hideRecentTaskPreview = sp.getBoolean(KEY_HIDE_RECENT, false),
            pinHint = (sp.getString(KEY_PIN_HINT, "") ?: "").take(MAX_PIN_HINT_LENGTH)
        )
        failedAttempts = 0; cooldownUntilMillis = 0L; lastBackgroundedAtMillis = 0L
        _isSessionLockedFlow.value = enabled
    }

    fun normalizeTimeout(seconds: Int): Int = if (seconds == 60 || seconds == 300) seconds else 0

    fun isValidPinFormat(pin: String): Boolean = pin.length == 4 && pin.all { it in '0'..'9' }

    fun generateSaltHex(): String = ByteArray(16).also { secureRandom.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun hashPin(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Synchronized
    fun validatePinHint(rawHint: String, candidatePin: String? = null): Result<String> {
        val trimmed = rawHint.trim()
        if (trimmed.length > MAX_PIN_HINT_LENGTH) {
            return Result.failure(IllegalArgumentException("密码提示语不能超过 $MAX_PIN_HINT_LENGTH 个字符"))
        }
        if (!candidatePin.isNullOrBlank() && trimmed.contains(candidatePin)) {
            return Result.failure(IllegalArgumentException("密码提示语不能包含当前密码本身"))
        }
        if (pinSalt.isNotBlank() && pinHash.isNotBlank() && trimmed.length >= 4) {
            for (i in 0..(trimmed.length - 4)) {
                val sub = trimmed.substring(i, i + 4)
                if (isValidPinFormat(sub) && hashPin(sub, pinSalt) == pinHash) {
                    return Result.failure(IllegalArgumentException("密码提示语不能包含当前密码本身"))
                }
            }
        }
        return Result.success(trimmed)
    }

    @Synchronized
    fun getRemainingCooldownSeconds(nowMillis: Long = System.currentTimeMillis()): Int =
        if (nowMillis >= cooldownUntilMillis) 0 else ceil((cooldownUntilMillis - nowMillis) / 1000.0).toInt().coerceAtLeast(1)

    @Synchronized
    fun verifyPin(pin: String, unlockSessionOnSuccess: Boolean = true, nowMillis: Long = System.currentTimeMillis()): PinVerifyResult {
        val remainingCooldown = getRemainingCooldownSeconds(nowMillis)
        if (remainingCooldown > 0) return PinVerifyResult.CooldownLocked(remainingCooldown, "错误次数过多，请 $remainingCooldown 秒后再试")
        if (!isValidPinFormat(pin)) return PinVerifyResult.InvalidFormat("请输入 4 位数字密码")
        if (pinSalt.isNotBlank() && pinHash.isNotBlank() && hashPin(pin, pinSalt) == pinHash) {
            failedAttempts = 0; cooldownUntilMillis = 0L
            if (unlockSessionOnSuccess) _isSessionLockedFlow.value = false
            return PinVerifyResult.Success
        }
        failedAttempts += 1
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            cooldownUntilMillis = nowMillis + COOLDOWN_DURATION_MILLIS; failedAttempts = 0
            return PinVerifyResult.CooldownLocked(30, "连续输错 5 次，已锁定 30 秒")
        }
        val remaining = MAX_FAILED_ATTEMPTS - failedAttempts
        return PinVerifyResult.Mismatch(remaining, "密码错误，还可尝试 $remaining 次")
    }

    @Synchronized
    private fun persist(config: PasswordProtectionConfig) {
        _configFlow.value = config
        prefs?.edit()?.putBoolean(KEY_ENABLED, config.enabled)?.putString(KEY_PIN_SALT, pinSalt)
            ?.putString(KEY_PIN_HASH, pinHash)?.putInt(KEY_LOCK_TIMEOUT, config.lockTimeoutSeconds)
            ?.putBoolean(KEY_HIDE_RECENT, config.hideRecentTaskPreview)?.putString(KEY_PIN_HINT, config.pinHint)?.apply()
    }

    @Synchronized
    fun enablePasswordProtection(newPin: String, confirmPin: String, hint: String = ""): Result<PasswordProtectionConfig> {
        if (!isValidPinFormat(newPin)) return Result.failure(IllegalArgumentException("请输入 4 位数字密码"))
        if (newPin != confirmPin) return Result.failure(IllegalArgumentException("两次输入的密码不一致，请重新输入"))
        val cleanHint = validatePinHint(hint, candidatePin = newPin).getOrElse { return Result.failure(it) }
        pinSalt = generateSaltHex(); pinHash = hashPin(newPin, pinSalt)
        failedAttempts = 0; cooldownUntilMillis = 0L; lastBackgroundedAtMillis = 0L; _isSessionLockedFlow.value = false
        val next = _configFlow.value.copy(enabled = true, pinHint = cleanHint)
        persist(next); return Result.success(next)
    }

    @Synchronized
    fun changePin(oldPin: String, newPin: String, confirmPin: String, nowMillis: Long = System.currentTimeMillis()): Result<PasswordProtectionConfig> {
        when (val v = verifyPin(oldPin, unlockSessionOnSuccess = false, nowMillis = nowMillis)) {
            is PinVerifyResult.Success -> Unit
            is PinVerifyResult.InvalidFormat -> return Result.failure(IllegalArgumentException(v.message))
            is PinVerifyResult.Mismatch -> return Result.failure(IllegalArgumentException(v.message))
            is PinVerifyResult.CooldownLocked -> return Result.failure(IllegalArgumentException(v.message))
        }
        if (!isValidPinFormat(newPin)) return Result.failure(IllegalArgumentException("请输入 4 位数字密码"))
        if (newPin != confirmPin) return Result.failure(IllegalArgumentException("两次输入的密码不一致，请重新输入"))
        if (_configFlow.value.pinHint.isNotBlank() && _configFlow.value.pinHint.contains(newPin)) {
            return Result.failure(IllegalArgumentException("新密码不能包含在当前密码提示语中，请更换密码或先修改提示语"))
        }
        pinSalt = generateSaltHex(); pinHash = hashPin(newPin, pinSalt)
        persist(_configFlow.value); return Result.success(_configFlow.value)
    }

    @Synchronized
    fun disablePasswordProtection(currentPin: String, nowMillis: Long = System.currentTimeMillis()): Result<PasswordProtectionConfig> {
        when (val v = verifyPin(currentPin, unlockSessionOnSuccess = false, nowMillis = nowMillis)) {
            is PinVerifyResult.Success -> Unit
            is PinVerifyResult.InvalidFormat -> return Result.failure(IllegalArgumentException(v.message))
            is PinVerifyResult.Mismatch -> return Result.failure(IllegalArgumentException(v.message))
            is PinVerifyResult.CooldownLocked -> return Result.failure(IllegalArgumentException(v.message))
        }
        pinSalt = ""; pinHash = ""; failedAttempts = 0; cooldownUntilMillis = 0L; lastBackgroundedAtMillis = 0L
        _isSessionLockedFlow.value = false
        val next = _configFlow.value.copy(enabled = false, pinHint = "")
        persist(next); return Result.success(next)
    }

    @Synchronized
    fun updateLockTimeout(timeoutSeconds: Int): PasswordProtectionConfig =
        _configFlow.value.copy(lockTimeoutSeconds = normalizeTimeout(timeoutSeconds)).also { persist(it) }

    @Synchronized
    fun updateHideRecentTaskPreview(hide: Boolean): PasswordProtectionConfig =
        _configFlow.value.copy(hideRecentTaskPreview = hide).also { persist(it) }

    @Synchronized
    fun updatePinHint(rawHint: String): Result<PasswordProtectionConfig> {
        val clean = validatePinHint(rawHint).getOrElse { return Result.failure(it) }
        return Result.success(_configFlow.value.copy(pinHint = clean).also { persist(it) })
    }

    @Synchronized
    fun onAppBackgrounded(nowMillis: Long = System.currentTimeMillis()) {
        if (!_configFlow.value.enabled) return
        lastBackgroundedAtMillis = nowMillis
        if (_configFlow.value.lockTimeoutSeconds == 0) _isSessionLockedFlow.value = true
    }

    @Synchronized
    fun onAppForegrounded(nowMillis: Long = System.currentTimeMillis()) {
        if (!_configFlow.value.enabled || lastBackgroundedAtMillis <= 0L) return
        if (nowMillis - lastBackgroundedAtMillis >= _configFlow.value.lockTimeoutSeconds * 1000L) _isSessionLockedFlow.value = true
        lastBackgroundedAtMillis = 0L
    }

    @Synchronized
    fun lockNow() { if (_configFlow.value.enabled) _isSessionLockedFlow.value = true }

    @Synchronized
    fun resetForTesting() {
        pinSalt = ""; pinHash = ""; failedAttempts = 0; cooldownUntilMillis = 0L; lastBackgroundedAtMillis = 0L
        _isSessionLockedFlow.value = false; persist(PasswordProtectionConfig())
    }
    @Synchronized
    fun currentPinCredentialPairForTesting(): Pair<String, String> = pinSalt to pinHash
}