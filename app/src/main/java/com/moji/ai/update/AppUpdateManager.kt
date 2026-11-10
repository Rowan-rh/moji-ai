package com.moji.ai.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.moji.ai.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppUpdateConfig(
    val repoFullName: String = BuildConfig.GITHUB_UPDATE_REPO.ifBlank { "Rowan-rh/moji-ai" },
    val customGithubToken: String = "",
    val autoCheckOnLaunch: Boolean = !BuildConfig.IS_QA_BUILD,
    val includePrerelease: Boolean = false,
    val ignoredTag: String = ""
) {
    val effectiveToken: String
        get() = customGithubToken.trim().ifBlank { BuildConfig.GITHUB_UPDATE_TOKEN.trim() }

    val hasTokenConfigured: Boolean
        get() = effectiveToken.isNotBlank()
}

data class ReleaseUpdateInfo(
    val tagName: String,
    val title: String,
    val releaseNotes: String,
    val publishedAt: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val assetApiUrl: String,
    val browserDownloadUrl: String,
    val htmlUrl: String,
    val isPrerelease: Boolean,
    val repoIsPrivate: Boolean,
    val sha256Digest: String = ""
)

sealed class UpdateCheckState {
    data object Idle : UpdateCheckState()
    data object Checking : UpdateCheckState()
    data class UpToDate(
        val currentVersion: String,
        val repoFullName: String,
        val repoIsPrivate: Boolean,
        val statusNote: String
    ) : UpdateCheckState()

    data class UpdateAvailable(
        val currentVersion: String,
        val release: ReleaseUpdateInfo
    ) : UpdateCheckState()

    data class Downloading(
        val release: ReleaseUpdateInfo,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val progressPercent: Int
    ) : UpdateCheckState()

    data class ReadyToInstall(
        val release: ReleaseUpdateInfo,
        val apkFilePath: String,
        val needsInstallPermission: Boolean = false
    ) : UpdateCheckState()

    data class Error(
        val message: String,
        val needTokenConfig: Boolean = false
    ) : UpdateCheckState()
}

class AppUpdateManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val noRedirectHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val _configFlow = MutableStateFlow(loadConfig())
    val configFlow: StateFlow<AppUpdateConfig> = _configFlow.asStateFlow()

    private val _stateFlow = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val stateFlow: StateFlow<UpdateCheckState> = _stateFlow.asStateFlow()

    private fun loadConfig(): AppUpdateConfig {
        val defaultRepo = BuildConfig.GITHUB_UPDATE_REPO.ifBlank { "Rowan-rh/moji-ai" }
        return AppUpdateConfig(
            repoFullName = prefs.getString(KEY_REPO, defaultRepo)?.trim().takeUnless { it.isNullOrBlank() } ?: defaultRepo,
            customGithubToken = prefs.getString(KEY_TOKEN, "")?.trim().orEmpty(),
            autoCheckOnLaunch = !BuildConfig.IS_QA_BUILD && prefs.getBoolean(KEY_AUTO_CHECK, true),
            includePrerelease = prefs.getBoolean(KEY_INCLUDE_PRERELEASE, false),
            ignoredTag = prefs.getString(KEY_IGNORED_TAG, "")?.trim().orEmpty()
        )
    }

    fun updateConfig(newConfig: AppUpdateConfig) {
        val candidateRepo = newConfig.repoFullName.trim()
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .trim('/')
            .ifBlank { "Rowan-rh/moji-ai" }
        val normalizedRepo = if (REPO_FULL_NAME_REGEX.matches(candidateRepo)) {
            candidateRepo
        } else {
            BuildConfig.GITHUB_UPDATE_REPO.ifBlank { "Rowan-rh/moji-ai" }
        }
        val sanitized = newConfig.copy(
            repoFullName = normalizedRepo,
            customGithubToken = newConfig.customGithubToken.trim()
        )
        prefs.edit()
            .putString(KEY_REPO, sanitized.repoFullName)
            .putString(KEY_TOKEN, sanitized.customGithubToken)
            .putBoolean(KEY_AUTO_CHECK, sanitized.autoCheckOnLaunch)
            .putBoolean(KEY_INCLUDE_PRERELEASE, sanitized.includePrerelease)
            .putString(KEY_IGNORED_TAG, sanitized.ignoredTag)
            .apply()
        _configFlow.value = sanitized
    }

    fun ignoreReleaseTag(tag: String) {
        updateConfig(_configFlow.value.copy(ignoredTag = tag.trim()))
        _stateFlow.value = UpdateCheckState.Idle
    }

    fun resetState() {
        _stateFlow.value = UpdateCheckState.Idle
    }

    /**
     * 检查 GitHub 仓库（支持 Private 私有仓库与 Public 公开仓库）最新 Release
     */
    suspend fun checkForUpdate(isManual: Boolean = true): UpdateCheckState = withContext(Dispatchers.IO) {
        val cfg = _configFlow.value
        val repo = cfg.repoFullName
        val token = cfg.effectiveToken
        val currentVersion = BuildConfig.VERSION_NAME

        if (BuildConfig.IS_QA_BUILD) {
            val qaUpToDate = UpdateCheckState.UpToDate(
                currentVersion = currentVersion,
                repoFullName = repo,
                repoIsPrivate = false,
                statusNote = "当前为 QA 独立测试包（com.moji.ai.qa），已禁用正式版 OTA 更新"
            )
            if (isManual) {
                _stateFlow.value = qaUpToDate
            }
            return@withContext qaUpToDate
        }

        if (!REPO_FULL_NAME_REGEX.matches(repo)) {
            val invalidErr = UpdateCheckState.Error("GitHub 仓库名称格式无效（应为 owner/repo）", needTokenConfig = false)
            if (isManual) _stateFlow.value = invalidErr
            return@withContext invalidErr
        }

        if (isManual) {
            _stateFlow.value = UpdateCheckState.Checking
        }

        runCatching {
            // 1. 先请求仓库元信息或直接请求 releases，判断私有状态与鉴权有效性
            val repoUrl = "https://api.github.com/repos/$repo"
            val repoReqBuilder = Request.Builder()
                .url(repoUrl)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "MoJi-Android/${BuildConfig.VERSION_NAME}")
            if (token.isNotBlank()) {
                repoReqBuilder.header("Authorization", "Bearer $token")
            }

            var repoIsPrivate = false
            httpClient.newCall(repoReqBuilder.build()).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403 || resp.code == 404) {
                    val errMsg = if (token.isBlank()) {
                        "仓库 $repo 为私有或需鉴权访问，请先配置 GitHub 只读 Token（Contents: Read-only）"
                    } else {
                        "GitHub Token 无法访问私有仓库 $repo（HTTP ${resp.code}），请确认 Token 未过期且已授权该仓库的 Contents: Read-only 权限"
                    }
                    val errState = UpdateCheckState.Error(errMsg, needTokenConfig = true)
                    if (isManual) _stateFlow.value = errState
                    return@withContext errState
                }
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string().orEmpty()
                    val obj = runCatching { json.parseToJsonElement(bodyStr).jsonObject }.getOrNull()
                    repoIsPrivate = obj?.get("private")?.jsonPrimitive?.booleanOrNull ?: false
                }
            }

            // 2. 获取最新 Release（若不勾选预发布优先走 /releases/latest，404 时回退查 /releases?per_page=5）
            val releaseObj: JsonObject? = fetchTargetReleaseJson(repo, token, cfg.includePrerelease)
            if (releaseObj == null) {
                val upToDate = UpdateCheckState.UpToDate(
                    currentVersion = currentVersion,
                    repoFullName = repo,
                    repoIsPrivate = repoIsPrivate,
                    statusNote = if (repoIsPrivate) "私有仓库连接正常 · 暂无已发布的 Release 安装包" else "仓库连接正常 · 暂无已发布的 Release 安装包"
                )
                if (isManual) _stateFlow.value = upToDate
                return@withContext upToDate
            }

            val releaseInfo = parseReleaseInfo(releaseObj, repoIsPrivate)
            if (releaseInfo == null) {
                val upToDate = UpdateCheckState.UpToDate(
                    currentVersion = currentVersion,
                    repoFullName = repo,
                    repoIsPrivate = repoIsPrivate,
                    statusNote = "最新 Release 未包含可安装的 .apk 附件"
                )
                if (isManual) _stateFlow.value = upToDate
                return@withContext upToDate
            }

            val hasNewer = isNewerVersion(releaseInfo.tagName, currentVersion)
            if (!hasNewer) {
                val upToDate = UpdateCheckState.UpToDate(
                    currentVersion = currentVersion,
                    repoFullName = repo,
                    repoIsPrivate = repoIsPrivate,
                    statusNote = "当前已是最新版本（远端最新：${releaseInfo.tagName}）"
                )
                if (isManual) _stateFlow.value = upToDate
                return@withContext upToDate
            }

            if (!isManual && cfg.ignoredTag.isNotBlank() && cfg.ignoredTag.equals(releaseInfo.tagName, ignoreCase = true)) {
                return@withContext UpdateCheckState.Idle
            }

            val available = UpdateCheckState.UpdateAvailable(
                currentVersion = currentVersion,
                release = releaseInfo
            )
            _stateFlow.value = available
            available
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            val err = UpdateCheckState.Error(
                message = "检查更新失败：${e.localizedMessage ?: "网络连接异常"}",
                needTokenConfig = false
            )
            if (isManual) _stateFlow.value = err
            err
        }
    }

    private fun fetchTargetReleaseJson(
        repo: String,
        token: String,
        includePrerelease: Boolean
    ): JsonObject? {
        if (!includePrerelease) {
            val latestUrl = "https://api.github.com/repos/$repo/releases/latest"
            val req = buildGitHubJsonRequest(latestUrl, token)
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val str = resp.body?.string().orEmpty()
                    return runCatching { json.parseToJsonElement(str).jsonObject }.getOrNull()
                }
            }
        }

        val listUrl = "https://api.github.com/repos/$repo/releases?per_page=10"
        val listReq = buildGitHubJsonRequest(listUrl, token)
        httpClient.newCall(listReq).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val str = resp.body?.string().orEmpty()
            val arr: JsonArray = runCatching { json.parseToJsonElement(str).jsonArray }.getOrNull() ?: return null
            return arr.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                .firstOrNull { obj ->
                    val draft = obj["draft"]?.jsonPrimitive?.booleanOrNull ?: false
                    val prerelease = obj["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false
                    !draft && (includePrerelease || !prerelease)
                }
        }
    }

    private fun buildGitHubJsonRequest(url: String, token: String): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "MoJi-Android/${BuildConfig.VERSION_NAME}")
        if (token.isNotBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder.build()
    }

    private fun parseReleaseInfo(obj: JsonObject, repoIsPrivate: Boolean): ReleaseUpdateInfo? {
        val tagName = obj["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (tagName.isBlank()) return null

        val title = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().takeUnless { it.isNullOrBlank() } ?: tagName
        val body = obj["body"]?.jsonPrimitive?.contentOrNull?.trim().takeUnless { it.isNullOrBlank() } ?: "包含体验优化与问题修复。"
        val publishedAt = obj["published_at"]?.jsonPrimitive?.contentOrNull?.take(10).orEmpty()
        val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val isPrerelease = obj["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false

        val assets = obj["assets"]?.jsonArray?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }.orEmpty()
        val apkAsset = assets.firstOrNull { asset ->
            val name = asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            name.endsWith(".apk", ignoreCase = true)
        } ?: return null

        val apkName = apkAsset["name"]?.jsonPrimitive?.contentOrNull ?: "moji-${tagName}.apk"
        val apkSize = apkAsset["size"]?.jsonPrimitive?.longOrNull ?: 0L
        val assetApiUrl = apkAsset["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val browserUrl = apkAsset["browser_download_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val rawDigest = apkAsset["digest"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val sha256Digest = if (rawDigest.startsWith("sha256:", ignoreCase = true)) {
            rawDigest.substringAfter(':').trim().takeIf { it.length == 64 } ?: ""
        } else {
            ""
        }
        if (assetApiUrl.isBlank() && browserUrl.isBlank()) return null

        return ReleaseUpdateInfo(
            tagName = tagName,
            title = title,
            releaseNotes = body,
            publishedAt = publishedAt,
            apkFileName = apkName,
            apkSizeBytes = apkSize,
            assetApiUrl = assetApiUrl,
            browserDownloadUrl = browserUrl,
            htmlUrl = htmlUrl,
            isPrerelease = isPrerelease,
            repoIsPrivate = repoIsPrivate,
            sha256Digest = sha256Digest
        )
    }

    /**
     * 下载 Release APK（兼容私有仓库 API Asset URL + `Accept: application/octet-stream` 鉴权流式下载，
     * 并在跨域重定向时剥离 Authorization 头、校验文件字节数、SHA-256 摘要以及 APK 包名与签名证书一致性）
     */
    suspend fun downloadReleaseApk(release: ReleaseUpdateInfo): UpdateCheckState = withContext(Dispatchers.IO) {
        val token = _configFlow.value.effectiveToken
        val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        val safeFileName = release.apkFileName.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
        val targetFile = File(updatesDir, safeFileName)
        val tempFile = File(updatesDir, "$safeFileName.tmp")

        // 下载前清理历史残留的旧版 .apk / .tmp 文件，避免缓存累积
        runCatching {
            updatesDir.listFiles()?.forEach { f ->
                if (f.isFile && (f.name.endsWith(".apk", ignoreCase = true) || f.name.endsWith(".tmp", ignoreCase = true))) {
                    f.delete()
                }
            }
        }

        _stateFlow.value = UpdateCheckState.Downloading(
            release = release,
            downloadedBytes = 0L,
            totalBytes = release.apkSizeBytes,
            progressPercent = 0
        )

        runCatching {
            val initialUrlStr = if (release.assetApiUrl.isNotBlank() && (release.repoIsPrivate || token.isNotBlank())) {
                release.assetApiUrl
            } else {
                release.browserDownloadUrl.ifBlank { release.assetApiUrl }
            }
            val parsedInitialUrl = initialUrlStr.toHttpUrlOrNull()
            if (parsedInitialUrl == null || !parsedInitialUrl.isHttps || !isTrustedGitHubHost(parsedInitialUrl.host)) {
                val err = UpdateCheckState.Error("下载地址不受信任或非 HTTPS 安全连接，已拦截下载")
                _stateFlow.value = err
                return@withContext err
            }

            // 仅当目标域名严格为 api.github.com 时才附加 Bearer Token；若收到 301/302 重定向至对象存储则不携带 Token 请求直链
            val resolvedStreamResponse = if (parsedInitialUrl.host.equals("api.github.com", ignoreCase = true) && token.isNotBlank()) {
                val apiReq = Request.Builder()
                    .url(parsedInitialUrl)
                    .header("Accept", "application/octet-stream")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "MoJi-Android/${BuildConfig.VERSION_NAME}")
                    .header("Authorization", "Bearer $token")
                    .build()
                val firstResp = noRedirectHttpClient.newCall(apiReq).execute()
                if (firstResp.code in listOf(301, 302, 303, 307, 308)) {
                    val redirectLoc = firstResp.header("Location").orEmpty()
                    firstResp.close()
                    val redirectUrl = redirectLoc.toHttpUrlOrNull()
                    if (redirectUrl == null || !redirectUrl.isHttps) {
                        val err = UpdateCheckState.Error("下载重定向地址非 HTTPS 安全连接，已拦截下载")
                        _stateFlow.value = err
                        return@withContext err
                    }
                    val cdnReq = Request.Builder()
                        .url(redirectUrl)
                        .header("User-Agent", "MoJi-Android/${BuildConfig.VERSION_NAME}")
                        .build()
                    httpClient.newCall(cdnReq).execute()
                } else {
                    firstResp
                }
            } else {
                val publicReq = Request.Builder()
                    .url(parsedInitialUrl)
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "MoJi-Android/${BuildConfig.VERSION_NAME}")
                    .build()
                httpClient.newCall(publicReq).execute()
            }

            var computedSha256Hex = ""
            resolvedStreamResponse.use { response ->
                if (!response.isSuccessful) {
                    val err = UpdateCheckState.Error(
                        message = "下载安装包失败（HTTP ${response.code}），请检查网络或 Token 权限",
                        needTokenConfig = response.code == 401 || response.code == 403 || response.code == 404
                    )
                    _stateFlow.value = err
                    return@withContext err
                }

                val body = response.body ?: run {
                    val err = UpdateCheckState.Error("下载响应为空")
                    _stateFlow.value = err
                    return@withContext err
                }

                val contentLength = body.contentLength().takeIf { it > 0L } ?: release.apkSizeBytes
                var downloaded = 0L
                var lastEmitTime = 0L
                val sha256Digest = MessageDigest.getInstance("SHA-256")

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            sha256Digest.update(buffer, 0, read)
                            downloaded += read
                            if (downloaded > MAX_APK_DOWNLOAD_BYTES) {
                                throw IllegalStateException("安装包体积超过安全上限")
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime >= 100L || downloaded == contentLength) {
                                lastEmitTime = now
                                val pct = if (contentLength > 0L) {
                                    ((downloaded * 100L) / contentLength).toInt().coerceIn(0, 100)
                                } else 0
                                _stateFlow.value = UpdateCheckState.Downloading(
                                    release = release,
                                    downloadedBytes = downloaded,
                                    totalBytes = contentLength,
                                    progressPercent = pct
                                )
                            }
                        }
                        output.flush()
                    }
                }
                if (release.apkSizeBytes > 0L && downloaded != release.apkSizeBytes) {
                    tempFile.delete()
                    val err = UpdateCheckState.Error("安装包下载不完整（已下载 $downloaded / 预期 ${release.apkSizeBytes} 字节），请重试")
                    _stateFlow.value = err
                    return@withContext err
                }
                computedSha256Hex = sha256Digest.digest().joinToString("") { "%02x".format(it) }
            }

            if (release.sha256Digest.isNotBlank() &&
                !computedSha256Hex.equals(release.sha256Digest, ignoreCase = true)
            ) {
                tempFile.delete()
                val err = UpdateCheckState.Error("安装包 SHA-256 完整性校验未通过，已拒绝安装")
                _stateFlow.value = err
                return@withContext err
            }

            // 校验 APK 文件头（ZIP PK\x03\x04）及包名、签名证书一致性
            if (!isValidApkFile(tempFile)) {
                tempFile.delete()
                val err = UpdateCheckState.Error("下载的文件不是有效的 APK 安装包，请检查 Release 附件")
                _stateFlow.value = err
                return@withContext err
            }
            val archiveVerifyErr = verifyDownloadedApkPackageAndSignature(tempFile)
            if (archiveVerifyErr != null) {
                tempFile.delete()
                val err = UpdateCheckState.Error(archiveVerifyErr)
                _stateFlow.value = err
                return@withContext err
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            val needsPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !appContext.packageManager.canRequestPackageInstalls()

            val readyState = UpdateCheckState.ReadyToInstall(
                release = release,
                apkFilePath = targetFile.absolutePath,
                needsInstallPermission = needsPerm
            )
            _stateFlow.value = readyState
            readyState
        }.getOrElse { e ->
            tempFile.delete()
            if (e is CancellationException) throw e
            val err = UpdateCheckState.Error("下载失败：${e.localizedMessage ?: "网络中断"}")
            _stateFlow.value = err
            err
        }
    }

    /**
     * 调起系统安装器安装已下载的 APK；若尚未授予“安装未知应用”权限则引导跳转授权页
     */
    fun triggerInstallApk(context: Context, apkFilePath: String): Boolean {
        if (BuildConfig.IS_QA_BUILD) {
            _stateFlow.value = UpdateCheckState.Error("当前为 QA 独立测试包（com.moji.ai.qa），禁止覆盖安装正式版")
            return false
        }
        val apkFile = File(apkFilePath)
        if (!apkFile.exists()) {
            _stateFlow.value = UpdateCheckState.Error("安装包文件不存在，请重新下载")
            return false
        }
        val archiveVerifyErr = verifyDownloadedApkPackageAndSignature(apkFile)
        if (archiveVerifyErr != null) {
            _stateFlow.value = UpdateCheckState.Error(archiveVerifyErr)
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val opened = runCatching { context.startActivity(permIntent) }.isSuccess
            if (!opened) {
                _stateFlow.value = UpdateCheckState.Error("无法打开系统安装未知应用授权页面，请在系统设置中手动开启")
                return false
            }
            val current = _stateFlow.value
            if (current is UpdateCheckState.ReadyToInstall) {
                _stateFlow.value = current.copy(needsInstallPermission = true)
            }
            return false
        }

        return runCatching {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            true
        }.getOrElse { e ->
            _stateFlow.value = UpdateCheckState.Error("无法调起系统安装器：${e.localizedMessage ?: "未找到安装组件"}")
            false
        }
    }

    private fun verifyDownloadedApkPackageAndSignature(apkFile: File): String? {
        return runCatching {
            val pm = appContext.packageManager
            val archiveInfo: PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
            }
            if (archiveInfo == null) {
                return "无法解析 APK 包信息，安装包可能已损坏"
            }
            if (archiveInfo.packageName != appContext.packageName) {
                return "安装包应用包名（${archiveInfo.packageName}）与当前应用不一致，已拦截安装"
            }
            val archiveCertDigests = extractCertSha256Set(archiveInfo)
            if (archiveCertDigests.isEmpty()) {
                return "安装包缺少有效签名证书，已拒绝安装"
            }
            val installedInfo: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    appContext.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNATURES)
            }
            val installedCertDigests = extractCertSha256Set(installedInfo)
            if (installedCertDigests.isNotEmpty() && archiveCertDigests.intersect(installedCertDigests).isEmpty()) {
                return "安装包签名证书与当前已安装版本不一致，无法覆盖安装"
            }
            null
        }.getOrElse {
            "校验安装包签名时发生异常，已中止安装"
        }
    }

    private fun extractCertSha256Set(pkgInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        } ?: return emptySet()
        return signatures.mapNotNull { sig ->
            runCatching {
                MessageDigest.getInstance("SHA-256")
                    .digest(sig.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }.toSet()
    }

    private fun isValidApkFile(file: File): Boolean {
        if (!file.exists() || file.length() < 1024L) return false
        return runCatching {
            file.inputStream().use { ins ->
                val header = ByteArray(4)
                val read = ins.read(header)
                read == 4 &&
                    header[0] == 0x50.toByte() &&
                    header[1] == 0x4B.toByte() &&
                    header[2] == 0x03.toByte() &&
                    header[3] == 0x04.toByte()
            }
        }.getOrDefault(false)
    }

    companion object {
        private const val PREFS_NAME = "moji_app_update_prefs"
        private const val KEY_REPO = "github_repo_full_name"
        private const val KEY_TOKEN = "github_custom_token"
        private const val KEY_AUTO_CHECK = "auto_check_on_launch"
        private const val KEY_INCLUDE_PRERELEASE = "include_prerelease"
        private const val KEY_IGNORED_TAG = "ignored_release_tag"
        private const val MAX_APK_DOWNLOAD_BYTES = 256L * 1024L * 1024L

        private val REPO_FULL_NAME_REGEX = Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""")
        private val TRUSTED_GITHUB_HOSTS = setOf(
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com"
        )

        fun isTrustedGitHubHost(host: String): Boolean {
            val lower = host.lowercase()
            return lower in TRUSTED_GITHUB_HOSTS || lower.endsWith(".githubusercontent.com")
        }

        /**
         * 对比远端 Release Tag（如 `v1.1.1`、`v1.1.1-rc1`、`v1.0.0-20261108`）与当前 `versionName`（如 `1.1.1`）：
         * 严格遵循 SemVer 规则：相同主版本核心号下，正式版高于预发布后缀（如 `1.1.1 > 1.1.1-rc1`），纯日期构建号后缀则按数值递增比较。
         */
        fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
            val cleanRemote = remoteTag.trim().removePrefix("v").removePrefix("V").substringBefore('+')
            val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V").substringBefore('+')
            if (cleanRemote.isBlank() || cleanCurrent.isBlank()) return false
            if (cleanRemote.equals(cleanCurrent, ignoreCase = true)) return false

            val remoteCoreStr = cleanRemote.substringBefore('-')
            val remoteSuffix = cleanRemote.substringAfter('-', "")
            val currentCoreStr = cleanCurrent.substringBefore('-')
            val currentSuffix = cleanCurrent.substringAfter('-', "")

            val remoteCore = remoteCoreStr.split('.').map { it.toLongOrNull() ?: 0L }
            val currentCore = currentCoreStr.split('.').map { it.toLongOrNull() ?: 0L }
            val maxCoreLen = maxOf(remoteCore.size, currentCore.size)
            for (i in 0 until maxCoreLen) {
                val r = remoteCore.getOrElse(i) { 0L }
                val c = currentCore.getOrElse(i) { 0L }
                if (r != c) return r > c
            }

            if (remoteSuffix.isEmpty() && currentSuffix.isEmpty()) return false
            val remoteIsNumericBuild = remoteSuffix.isNotEmpty() && remoteSuffix.all { it.isDigit() && remoteSuffix.length >= 6 }
            val currentIsNumericBuild = currentSuffix.isNotEmpty() && currentSuffix.all { it.isDigit() && currentSuffix.length >= 6 }

            // 若后缀是纯日期时间戳（如 1.0.0-20261108），则高于无后缀同版本号；若包含字母（如 alpha/beta/rc/qa），则为预发布版，低于正式版
            if (remoteSuffix.isEmpty() && currentSuffix.isNotEmpty()) {
                return !currentIsNumericBuild
            }
            if (remoteSuffix.isNotEmpty() && currentSuffix.isEmpty()) {
                return remoteIsNumericBuild
            }

            val rTokens = remoteSuffix.split('.', '-', '_')
            val cTokens = currentSuffix.split('.', '-', '_')
            val maxTok = maxOf(rTokens.size, cTokens.size)
            for (i in 0 until maxTok) {
                val rt = rTokens.getOrNull(i) ?: return false
                val ct = cTokens.getOrNull(i) ?: return true
                val rn = rt.toLongOrNull()
                val cn = ct.toLongOrNull()
                if (rn != null && cn != null) {
                    if (rn != cn) return rn > cn
                } else {
                    val cmp = rt.compareTo(ct, ignoreCase = true)
                    if (cmp != 0) return cmp > 0
                }
            }
            return false
        }
    }
}
