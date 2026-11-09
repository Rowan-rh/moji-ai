package com.moji.ai.data.model

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * 响应式多币种汇率中心（对标 iCost 货币和汇率管理体系）：
 * - 本位币固定为人民币 (`CNY`, 汇率 `1.0`)；
 * - 支持用户针对任意外币自定义对人民币 (`CNY`) 参考汇率并持久化至 `SharedPreferences`（Key: `custom_currency_rates_v1`）；
 * - 暴露 `customRatesFlow` 供资产页净资产折算、统计页趋势折算、记账计算器与货币管理页实时响应；
 * - 严格保持已入账历史流水的 `exchangeRateToCny` 与 `amountCnyCents` 快照不变性。
 */
object CurrencyRateManager {
    private const val PREFS_NAME = "moji_currency_rate_prefs"
    const val KEY_CUSTOM_CURRENCY_RATES_V1 = "custom_currency_rates_v1"
    const val MAX_CUSTOM_RATE = 1000.0
    const val MAX_DECIMAL_PLACES = 6

    private val jsonCodec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _customRatesFlow = MutableStateFlow<Map<String, Double>>(emptyMap())
    val customRatesFlow: StateFlow<Map<String, Double>> = _customRatesFlow.asStateFlow()

    val customRates: Map<String, Double>
        get() = _customRatesFlow.value

    @Synchronized
    fun init(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        val rawJson = sp.getString(KEY_CUSTOM_CURRENCY_RATES_V1, null)
        if (!rawJson.isNullOrBlank()) {
            runCatching {
                sanitizeRatesMap(jsonCodec.decodeFromString<Map<String, Double>>(rawJson))
            }.getOrNull()?.let { sanitized ->
                _customRatesFlow.value = sanitized
            }
        }
    }

    @Synchronized
    private fun persist(map: Map<String, Double>) {
        val clean = sanitizeRatesMap(map)
        _customRatesFlow.value = clean
        prefs?.edit()
            ?.putString(KEY_CUSTOM_CURRENCY_RATES_V1, jsonCodec.encodeToString(clean))
            ?.apply()
    }

    fun sanitizeRatesMap(rawMap: Map<String, Double>): Map<String, Double> {
        if (rawMap.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, Double>()
        for ((key, value) in rawMap) {
            val trimmed = key.trim().uppercase(Locale.ROOT)
            if (trimmed.isEmpty() || trimmed == CurrencyCode.CNY.name) continue
            val matched = CurrencyCode.entries.firstOrNull { it.name == trimmed } ?: continue
            if (!value.isFinite() || value <= 0.0 || value > MAX_CUSTOM_RATE) continue
            val rounded = roundRate(value)
            if (rounded > 0.0) {
                result[matched.name] = rounded
            }
        }
        return result
    }

    private fun roundRate(value: Double): Double {
        return BigDecimal.valueOf(value)
            .setScale(MAX_DECIMAL_PLACES, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toDouble()
    }

    /**
     * 校验用户输入的自定义对人民币汇率字符串：
     * - 不能为空或非有限数字；
     * - 必须 `> 0.0` 且 `<= 1000.0`；
     * - 最多保留 6 位小数。
     */
    fun validateCustomRateInput(rawInput: String): Result<Double> {
        val trimmed = rawInput.trim().replace(",", "")
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("请输入大于 0 的有效汇率"))
        }
        val parsed = trimmed.toDoubleOrNull()
        if (parsed == null || !parsed.isFinite()) {
            return Result.failure(IllegalArgumentException("请输入大于 0 的有效汇率"))
        }
        if (parsed <= 0.0) {
            return Result.failure(IllegalArgumentException("请输入大于 0 的有效汇率"))
        }
        if (parsed > MAX_CUSTOM_RATE) {
            return Result.failure(IllegalArgumentException("汇率不能超过 ${MAX_CUSTOM_RATE.toInt()}"))
        }
        val rounded = roundRate(parsed)
        if (rounded <= 0.0) {
            return Result.failure(IllegalArgumentException("请输入大于 0 的有效汇率（最多支持 $MAX_DECIMAL_PLACES 位小数）"))
        }
        return Result.success(rounded)
    }

    fun getEffectiveRateToCny(currencyCode: String?): Double {
        val code = CurrencyCode.fromCode(currencyCode)
        return getEffectiveRateToCny(code)
    }

    fun getEffectiveRateToCny(code: CurrencyCode): Double {
        if (code == CurrencyCode.CNY) return 1.0
        val custom = _customRatesFlow.value[code.name]
        return if (custom != null && custom.isFinite() && custom > 0.0 && custom <= MAX_CUSTOM_RATE) {
            custom
        } else {
            code.defaultRateToCny
        }
    }

    fun isCustomRate(currencyCode: String?): Boolean {
        val code = CurrencyCode.fromCode(currencyCode)
        if (code == CurrencyCode.CNY) return false
        return _customRatesFlow.value.containsKey(code.name)
    }

    @Synchronized
    fun setCustomRate(currencyCode: String, rawInput: String): Result<Double> {
        val code = CurrencyCode.entries.firstOrNull {
            it.name.equals(currencyCode.trim(), ignoreCase = true)
        } ?: return Result.failure(IllegalArgumentException("不支持的币种代码：$currencyCode"))
        if (code == CurrencyCode.CNY) {
            return Result.failure(IllegalArgumentException("人民币 (CNY) 为本位币，汇率固定为 1.0"))
        }
        val validated = validateCustomRateInput(rawInput).getOrElse { err ->
            return Result.failure(err)
        }
        return setCustomRate(code.name, validated)
    }

    @Synchronized
    fun setCustomRate(currencyCode: String, rate: Double): Result<Double> {
        val code = CurrencyCode.entries.firstOrNull {
            it.name.equals(currencyCode.trim(), ignoreCase = true)
        } ?: return Result.failure(IllegalArgumentException("不支持的币种代码：$currencyCode"))
        if (code == CurrencyCode.CNY) {
            return Result.failure(IllegalArgumentException("人民币 (CNY) 为本位币，汇率固定为 1.0"))
        }
        if (!rate.isFinite() || rate <= 0.0) {
            return Result.failure(IllegalArgumentException("请输入大于 0 的有效汇率"))
        }
        if (rate > MAX_CUSTOM_RATE) {
            return Result.failure(IllegalArgumentException("汇率不能超过 ${MAX_CUSTOM_RATE.toInt()}"))
        }
        val rounded = roundRate(rate)
        if (rounded <= 0.0) {
            return Result.failure(IllegalArgumentException("请输入大于 0 的有效汇率"))
        }
        val next = _customRatesFlow.value.toMutableMap()
        next[code.name] = rounded
        persist(next)
        return Result.success(rounded)
    }

    @Synchronized
    fun resetCurrencyRate(currencyCode: String) {
        val code = CurrencyCode.fromCode(currencyCode)
        if (code == CurrencyCode.CNY) return
        val next = _customRatesFlow.value.toMutableMap()
        if (next.remove(code.name) != null) {
            persist(next)
        }
    }

    @Synchronized
    fun resetAllCustomRates() {
        if (_customRatesFlow.value.isNotEmpty()) {
            persist(emptyMap())
        } else {
            _customRatesFlow.value = emptyMap()
        }
    }

    @Synchronized
    fun replaceAllCustomRates(rawMap: Map<String, Double>) {
        persist(rawMap)
    }

    fun formatRate(rate: Double): String {
        if (!rate.isFinite() || rate <= 0.0) return "0.00"
        val bd = BigDecimal.valueOf(rate).setScale(MAX_DECIMAL_PLACES, RoundingMode.HALF_UP).stripTrailingZeros()
        val plain = bd.toPlainString()
        return if (!plain.contains('.')) {
            "$plain.00"
        } else {
            val decimals = plain.substringAfter('.')
            if (decimals.length < 2) "${plain}0" else plain
        }
    }
}
