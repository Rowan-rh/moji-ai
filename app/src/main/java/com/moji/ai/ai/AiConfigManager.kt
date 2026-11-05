package com.moji.ai.ai

import android.content.Context
import android.content.SharedPreferences

enum class AiProviderType(val displayName: String, val defaultBaseUrl: String, val defaultModel: String) {
    OPENAI_COMPATIBLE(
        "OpenAI 兼容接口 (DeepSeek / 通义千问 / 硅基流动 / Ollama)",
        "https://api.deepseek.com/v1",
        "deepseek-chat"
    ),
    GEMINI_OFFICIAL(
        "Google Gemini 官方接口",
        "https://generativelanguage.googleapis.com/v1beta",
        "gemini-2.5-flash"
    )
}

data class AiConfig(
    val provider: AiProviderType,
    val baseUrl: String,
    val apiKey: String,
    val modelName: String,
    val enableAccessibilityCapture: Boolean,
    val enableNotificationCapture: Boolean,
    val enableHeuristicFallback: Boolean
)

class AiConfigManager(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("moji_ai_config", Context.MODE_PRIVATE)

    fun getConfig(): AiConfig {
        val providerName = prefs.getString("provider", AiProviderType.OPENAI_COMPATIBLE.name)
            ?: AiProviderType.OPENAI_COMPATIBLE.name
        val provider = runCatching { AiProviderType.valueOf(providerName) }
            .getOrDefault(AiProviderType.OPENAI_COMPATIBLE)

        return AiConfig(
            provider = provider,
            baseUrl = prefs.getString("base_url", provider.defaultBaseUrl) ?: provider.defaultBaseUrl,
            apiKey = prefs.getString("api_key", "") ?: "",
            modelName = prefs.getString("model_name", provider.defaultModel) ?: provider.defaultModel,
            enableAccessibilityCapture = prefs.getBoolean("enable_accessibility", true),
            enableNotificationCapture = prefs.getBoolean("enable_notification", true),
            enableHeuristicFallback = prefs.getBoolean("enable_heuristic_fallback", true)
        )
    }

    fun saveConfig(config: AiConfig) {
        prefs.edit()
            .putString("provider", config.provider.name)
            .putString("base_url", config.baseUrl.trim())
            .putString("api_key", config.apiKey.trim())
            .putString("model_name", config.modelName.trim())
            .putBoolean("enable_accessibility", config.enableAccessibilityCapture)
            .putBoolean("enable_notification", config.enableNotificationCapture)
            .putBoolean("enable_heuristic_fallback", config.enableHeuristicFallback)
            .apply()
    }
}
