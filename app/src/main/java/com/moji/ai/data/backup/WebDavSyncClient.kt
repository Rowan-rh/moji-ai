package com.moji.ai.data.backup

import android.content.Context
import com.moji.ai.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

@Serializable
data class WebDavConfig(
    val serverUrl: String = WebDavSyncClient.DEFAULT_SERVER_URL,
    val username: String = "",
    val appPassword: String = "",
    val remoteFileName: String = WebDavSyncClient.DEFAULT_REMOTE_FILE_NAME,
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

    val DEFAULT_SERVER_URL: String
        get() = if (BuildConfig.IS_QA_BUILD) {
            "https://dav.jianguoyun.com/dav/MoJi-QA/"
        } else {
            "https://dav.jianguoyun.com/dav/MoJi/"
        }

    val DEFAULT_REMOTE_FILE_NAME: String
        get() = if (BuildConfig.IS_QA_BUILD) {
            "moji_qa_latest.mojibackup"
        } else {
            "moji_latest.mojibackup"
        }

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

    private const val MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024

    private fun validateAndBuildTargetUrls(config: WebDavConfig): Result<Triple<String, String, String>> {
        val rawServer = config.serverUrl.trim()
        val dirUrlStr = if (rawServer.endsWith("/")) rawServer else "$rawServer/"
        val parsedDir = dirUrlStr.toHttpUrlOrNull()
            ?: return Result.failure(IllegalArgumentException("WebDAV 服务器地址格式无效，请输入完整的 https:// 地址"))

        val isLocalDevHost = parsedDir.host.equals("localhost", ignoreCase = true) ||
            parsedDir.host == "127.0.0.1"
        if (!parsedDir.isHttps && !isLocalDevHost) {
            return Result.failure(IllegalArgumentException("为保障云同步账号与财务数据安全，WebDAV 必须使用 HTTPS 加密连接"))
        }

        val fileName = config.remoteFileName.trim().ifBlank { DEFAULT_REMOTE_FILE_NAME }
        if (fileName.length > 128 ||
            fileName == "." ||
            fileName.contains("..") ||
            fileName.any { it == '/' || it == '\\' || it == '?' || it == '#' || it.code < 32 }
        ) {
            return Result.failure(IllegalArgumentException("备份文件名不合法（禁止包含路径分隔符、.. 或特殊控制字符）"))
        }

        val resolvedFileUrl = parsedDir.newBuilder().addPathSegment(fileName).build()
        val normalizedDirPath = resolvedFileUrl.encodedPath.substringBeforeLast('/') + "/"
        if (BuildConfig.IS_QA_BUILD) {
            val pointsToProdDir = normalizedDirPath.contains("/moji/", ignoreCase = true) &&
                !normalizedDirPath.contains("qa", ignoreCase = true)
            val isProdFileName = fileName.equals("moji_latest.mojibackup", ignoreCase = true)
            if (isProdFileName || pointsToProdDir) {
                return Result.failure(
                    IllegalArgumentException("QA 独立测试包禁止读写正式云备份（请使用含 QA 的目录如 /dav/MoJi-QA/ 及文件名 moji_qa_latest.mojibackup）")
                )
            }
        }
        return Result.success(Triple(parsedDir.toString(), resolvedFileUrl.toString(), fileName))
    }

    suspend fun uploadSnapshot(
        config: WebDavConfig,
        backupJson: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext Result.failure(IllegalArgumentException("请先填写完整的 WebDAV 服务器地址、账号与应用密码"))
        }
        val (dirUrl, fileUrl, resolvedFileName) = validateAndBuildTargetUrls(config)
            .getOrElse { return@withContext Result.failure(it) }

        try {
            val authHeader = Credentials.basic(config.username.trim(), config.appPassword.trim())

            // 1. 尝试 MKCOL 创建目录（若已存在会返回 405 Method Not Allowed，忽略即可）
            val mkcolReq = Request.Builder()
                .url(dirUrl)
                .header("Authorization", authHeader)
                .method("MKCOL", null)
                .build()
            runCatching { httpClient.newCall(mkcolReq).execute().close() }

            // 2. 优先写入临时文件并通过 WebDAV MOVE 原子替换，防止网络中断损坏远端唯一正式快照
            val tempUrl = dirUrl.toHttpUrlOrNull()!!
                .newBuilder()
                .addPathSegment("$resolvedFileName.tmp")
                .build()
                .toString()
            val putBodyBytes = backupJson.toByteArray(Charsets.UTF_8)
            val mediaType = "application/json; charset=utf-8".toMediaType()

            val tempPutReq = Request.Builder()
                .url(tempUrl)
                .header("Authorization", authHeader)
                .put(putBodyBytes.toRequestBody(mediaType))
                .build()

            httpClient.newCall(tempPutReq).execute().use { tempResp ->
                if (!tempResp.isSuccessful && tempResp.code != 201 && tempResp.code != 204) {
                    return@withContext Result.failure(
                        IllegalStateException("WebDAV 上传返回 HTTP ${tempResp.code}: ${tempResp.message}")
                    )
                }
            }

            val moveReq = Request.Builder()
                .url(tempUrl)
                .header("Authorization", authHeader)
                .header("Destination", fileUrl)
                .header("Overwrite", "T")
                .method("MOVE", null)
                .build()

            httpClient.newCall(moveReq).execute().use { moveResp ->
                if (moveResp.isSuccessful || moveResp.code == 201 || moveResp.code == 204) {
                    return@withContext Result.success(
                        "✅ WebDAV 云备份上传成功：$fileUrl (${String.format("%.1f", putBodyBytes.size / 1024.0)} KB)"
                    )
                }
                // 部分精简 WebDAV 服务不支持 MOVE（返回 405/501），回退为直接覆盖 PUT 并清理 .tmp
                if (moveResp.code != 405 && moveResp.code != 501) {
                    runCatching {
                        val delReq = Request.Builder().url(tempUrl).header("Authorization", authHeader).delete().build()
                        httpClient.newCall(delReq).execute().close()
                    }
                    return@withContext Result.failure(
                        IllegalStateException("WebDAV 原子提交返回 HTTP ${moveResp.code}: ${moveResp.message}")
                    )
                }
            }

            val directPutReq = Request.Builder()
                .url(fileUrl)
                .header("Authorization", authHeader)
                .put(putBodyBytes.toRequestBody(mediaType))
                .build()
            httpClient.newCall(directPutReq).execute().use { resp ->
                runCatching {
                    val delReq = Request.Builder().url(tempUrl).header("Authorization", authHeader).delete().build()
                    httpClient.newCall(delReq).execute().close()
                }
                if (resp.isSuccessful || resp.code == 201 || resp.code == 204) {
                    Result.success("✅ WebDAV 云备份上传成功：$fileUrl (${String.format("%.1f", putBodyBytes.size / 1024.0)} KB)")
                } else {
                    Result.failure(IllegalStateException("WebDAV 上传返回 HTTP ${resp.code}: ${resp.message}"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadSnapshot(config: WebDavConfig): Result<String> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext Result.failure(IllegalArgumentException("请先填写完整的 WebDAV 服务器地址、账号与应用密码"))
        }
        val (_, fileUrl, _) = validateAndBuildTargetUrls(config)
            .getOrElse { return@withContext Result.failure(it) }

        try {
            val authHeader = Credentials.basic(config.username.trim(), config.appPassword.trim())

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
                val stream = resp.body?.byteStream()
                    ?: return@withContext Result.failure(IllegalStateException("WebDAV 远端备份文件为空"))
                val bos = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                var n: Int
                stream.use { ins ->
                    while (ins.read(buf).also { n = it } != -1) {
                        total += n
                        if (total > MAX_SNAPSHOT_BYTES) {
                            return@withContext Result.failure(
                                IllegalStateException("远端备份文件超过 64MB 安全上限，已中止下载")
                            )
                        }
                        bos.write(buf, 0, n)
                    }
                }
                val body = bos.toByteArray().toString(Charsets.UTF_8)
                if (body.isBlank()) {
                    Result.failure(IllegalStateException("WebDAV 远端备份文件为空"))
                } else {
                    Result.success(body)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
