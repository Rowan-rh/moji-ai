package com.moji.ai.data.backup

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class WebDavConfig(
    val serverUrl: String = "https://dav.jianguoyun.com/dav/MoJi/",
    val username: String = "",
    val appPassword: String = "",
    val remoteFileName: String = "moji_latest.mojibackup",
    val lastSyncTimeMillis: Long = 0L
) {
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && appPassword.isNotBlank()
}

/**
 * WebDAV 自托管云端备份与恢复客户端（对标 BeeCount 与 inkqilin-ledger WebDAV 同步）
 * 支持坚果云、Nextcloud、OwnCloud、群晖 Synology、Alist 等任意标准 WebDAV 服务。
 */
object WebDavSyncClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonCodec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun loadConfig(context: Context): WebDavConfig {
        val prefs = context.getSharedPreferences("moji_webdav_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("webdav_config_json", null) ?: return WebDavConfig()
        return try {
            jsonCodec.decodeFromString<WebDavConfig>(raw)
        } catch (_: Exception) {
            WebDavConfig()
        }
    }

    fun saveConfig(context: Context, config: WebDavConfig) {
        val prefs = context.getSharedPreferences("moji_webdav_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("webdav_config_json", jsonCodec.encodeToString(config)).apply()
    }

    private fun buildNormalizedTargetUrl(config: WebDavConfig): Pair<String, String> {
        val dirUrl = if (config.serverUrl.trim().endsWith("/")) {
            config.serverUrl.trim()
        } else {
            "${config.serverUrl.trim()}/"
        }
        val fileName = config.remoteFileName.trim().ifBlank { "moji_latest.mojibackup" }
        return dirUrl to "$dirUrl$fileName"
    }

    suspend fun uploadSnapshot(
        config: WebDavConfig,
        backupJson: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext Result.failure(IllegalArgumentException("请先填写完整的 WebDAV 服务器地址、账号与应用密码"))
        }
        try {
            val authHeader = Credentials.basic(config.username.trim(), config.appPassword.trim())
            val (dirUrl, fileUrl) = buildNormalizedTargetUrl(config)

            // 1. 尝试 MKCOL 创建目录（若已存在会返回 405 Method Not Allowed，忽略即可）
            val mkcolReq = Request.Builder()
                .url(dirUrl)
                .header("Authorization", authHeader)
                .method("MKCOL", null)
                .build()
            runCatching { httpClient.newCall(mkcolReq).execute().close() }

            // 2. PUT 上传全量备份快照
            val putBody = backupJson.toRequestBody("application/json; charset=utf-8".toMediaType())
            val putReq = Request.Builder()
                .url(fileUrl)
                .header("Authorization", authHeader)
                .put(putBody)
                .build()

            httpClient.newCall(putReq).execute().use { resp ->
                if (resp.isSuccessful || resp.code == 201 || resp.code == 204) {
                    Result.success("✅ WebDAV 云备份上传成功：$fileUrl (${backupJson.length / 1024.0} KB)")
                } else {
                    Result.failure(IllegalStateException("WebDAV 上传返回 HTTP ${resp.code}: ${resp.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadSnapshot(config: WebDavConfig): Result<String> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext Result.failure(IllegalArgumentException("请先填写完整的 WebDAV 服务器地址、账号与应用密码"))
        }
        try {
            val authHeader = Credentials.basic(config.username.trim(), config.appPassword.trim())
            val (_, fileUrl) = buildNormalizedTargetUrl(config)

            val getReq = Request.Builder()
                .url(fileUrl)
                .header("Authorization", authHeader)
                .get()
                .build()

            httpClient.newCall(getReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("WebDAV 下载失败 HTTP ${resp.code}: ${resp.message}")
                    )
                }
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) {
                    Result.failure(IllegalStateException("WebDAV 远端备份文件为空"))
                } else {
                    Result.success(body)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
