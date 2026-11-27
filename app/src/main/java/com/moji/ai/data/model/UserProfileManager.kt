package com.moji.ai.data.model

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 8 款莫兰迪色系精致插画预设头像：
 * 默认首项 `MINT_CLASSIC` 1:1 保持原有 iCost 薄荷绿插画头像风格不变。
 */
@Serializable
enum class PresetAvatarStyle(
    val label: String,
    val bgHex: Long,
    val hairHex: Long,
    val collarHex: Long,
    val skinHex: Long,
    val smileHex: Long,
    val hairVariant: Int,
    val hasGlasses: Boolean = false,
    val hasBlush: Boolean = false,
    val hasHairpin: Boolean = false
) {
    MINT_CLASSIC(
        label = "薄荷初晴",
        bgHex = 0xFFC6F3EA,
        hairHex = 0xFF1B1F26,
        collarHex = 0xFF1F2430,
        skinHex = 0xFFFFEFE6,
        smileHex = 0xFFE07A68,
        hairVariant = 0
    ),
    MIST_BLUE(
        label = "雾霭晴蓝",
        bgHex = 0xFFD6E4F0,
        hairHex = 0xFF283646,
        collarHex = 0xFF3E5C76,
        skinHex = 0xFFFFF1E8,
        smileHex = 0xFFD97B6C,
        hairVariant = 1,
        hasGlasses = true
    ),
    ROSE_QUARTZ(
        label = "豆沙玫瑰",
        bgHex = 0xFFF2DCE0,
        hairHex = 0xFF3D2C2E,
        collarHex = 0xFF7A4E56,
        skinHex = 0xFFFFF2EC,
        smileHex = 0xFFE06D62,
        hairVariant = 2,
        hasBlush = true
    ),
    SAGE_OLIVE(
        label = "鼠尾草绿",
        bgHex = 0xFFDCE8D6,
        hairHex = 0xFF273328,
        collarHex = 0xFF4A634E,
        skinHex = 0xFFFFEFE5,
        smileHex = 0xFFD87A61,
        hairVariant = 3
    ),
    WARM_APRICOT(
        label = "暖杏奶咖",
        bgHex = 0xFFF5E4D3,
        hairHex = 0xFF433024,
        collarHex = 0xFF7D5A44,
        skinHex = 0xFFFFF3EB,
        smileHex = 0xFFE0745B,
        hairVariant = 0,
        hasBlush = true
    ),
    MIST_LAVENDER(
        label = "烟紫丁香",
        bgHex = 0xFFE4DCF2,
        hairHex = 0xFF2E293D,
        collarHex = 0xFF5A4E7C,
        skinHex = 0xFFFFF1EC,
        smileHex = 0xFFD97278,
        hairVariant = 2,
        hasHairpin = true
    ),
    OAT_SAND(
        label = "燕麦暖灰",
        bgHex = 0xFFEBE5DA,
        hairHex = 0xFF33302C,
        collarHex = 0xFF5C5549,
        skinHex = 0xFFFFEFE6,
        smileHex = 0xFFD47664,
        hairVariant = 1
    ),
    SLATE_TEAL(
        label = "青瓷烟灰",
        bgHex = 0xFFD3E6E8,
        hairHex = 0xFF1F2D30,
        collarHex = 0xFF385A61,
        skinHex = 0xFFFFF0E8,
        smileHex = 0xFFDE7868,
        hairVariant = 3,
        hasGlasses = true
    );

    companion object {
        fun fromNameOrDefault(name: String?): PresetAvatarStyle {
            if (name.isNullOrBlank()) return MINT_CLASSIC
            return entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: MINT_CLASSIC
        }
    }
}

@Serializable
data class UserProfileConfig(
    val nickname: String = UserProfileManager.DEFAULT_NICKNAME,
    val presetAvatar: PresetAvatarStyle = PresetAvatarStyle.MINT_CLASSIC,
    val customAvatarPath: String = "",
    val avatarUpdatedAtMillis: Long = 0L
) {
    val hasCustomAvatarConfigured: Boolean
        get() = customAvatarPath.isNotBlank()
}

/**
 * 随 `.mojibackup` 与默记云快照导出的轻量个人资料 DTO：
 * 仅非破坏性携带昵称与预设头像标识（绝不携带设备私有绝对路径）。
 */
@Serializable
data class UserProfileBackupDto(
    val nickname: String = UserProfileManager.DEFAULT_NICKNAME,
    val presetAvatarName: String = PresetAvatarStyle.MINT_CLASSIC.name,
    val hadCustomAvatar: Boolean = false
)

object UserProfileManager {
    const val PREFS_NAME = "moji_user_profile_prefs"
    const val KEY_USER_PROFILE_JSON = "user_profile_json_v1"
    const val PROFILE_DIR_NAME = "profile"
    const val DEFAULT_NICKNAME = "默记用户"
    const val MAX_NICKNAME_LENGTH = 16
    const val MAX_CLOUD_AVATAR_BYTES = 512 * 1024

    private const val MAX_SOURCE_IMAGE_BYTES = 16 * 1024 * 1024
    private const val MAX_AVATAR_FILE_BYTES = 4L * 1024L * 1024L
    internal const val AVATAR_TARGET_PX = 384
    internal const val MAX_DECODE_LONG_SIDE_PX = 4096

    private val jsonCodec = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var profileDirCanonicalPrefix: String? = null

    private var composeConfigState by mutableStateOf(UserProfileConfig())

    private val _configFlow = MutableStateFlow(UserProfileConfig())
    val configFlow: StateFlow<UserProfileConfig> = _configFlow.asStateFlow()

    val currentConfig: UserProfileConfig
        get() = composeConfigState

    @Synchronized
    fun init(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            val dir = File(appContext.filesDir, PROFILE_DIR_NAME)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            profileDirCanonicalPrefix = dir.canonicalPath.replace('\\', '/').trimEnd('/') + "/"
        }
        val sp = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        val raw = sp.getString(KEY_USER_PROFILE_JSON, null)
        if (raw.isNullOrBlank()) {
            val initial = UserProfileConfig()
            _configFlow.value = initial
            composeConfigState = initial
            persist(initial)
            return
        }
        val decoded = runCatching {
            jsonCodec.decodeFromString<UserProfileConfig>(raw)
        }.getOrNull()
        val sanitized = sanitizeConfig(decoded ?: UserProfileConfig(), appContext)
        _configFlow.value = sanitized
        composeConfigState = sanitized
        if (decoded == null || decoded != sanitized) {
            persist(sanitized)
        }
    }

    @Synchronized
    private fun persist(config: UserProfileConfig) {
        _configFlow.value = config
        composeConfigState = config
        prefs?.edit()?.putString(KEY_USER_PROFILE_JSON, jsonCodec.encodeToString(config))?.apply()
    }

    /**
     * 规范化用户昵称：剔除控制字符并折叠空白，纯空格/空串回退 `默记用户`，超长按 Unicode 码点安全裁剪至 16 字。
     */
    fun normalizeNickname(raw: String?): String {
        if (raw == null) return DEFAULT_NICKNAME
        val cleaned = raw
            .replace(Regex("[\\p{Cntrl}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isEmpty()) return DEFAULT_NICKNAME
        val codePointCount = cleaned.codePointCount(0, cleaned.length)
        val truncated = if (codePointCount > MAX_NICKNAME_LENGTH) {
            val endIndex = cleaned.offsetByCodePoints(0, MAX_NICKNAME_LENGTH)
            cleaned.substring(0, endIndex).trim()
        } else {
            cleaned
        }
        return truncated.ifEmpty { DEFAULT_NICKNAME }
    }

    private fun sanitizeConfig(config: UserProfileConfig, context: Context? = null): UserProfileConfig {
        val validCustomPath = if (config.customAvatarPath.isNotBlank()) {
            resolveValidCustomAvatarFile(config.customAvatarPath, context)?.absolutePath.orEmpty()
        } else {
            ""
        }
        return config.copy(
            nickname = normalizeNickname(config.nickname),
            customAvatarPath = validCustomPath
        )
    }

    @Synchronized
    fun updateNickname(rawNickname: String): UserProfileConfig {
        val normalized = normalizeNickname(rawNickname)
        val updated = _configFlow.value.copy(nickname = normalized)
        persist(updated)
        return updated
    }

    @Synchronized
    fun selectPresetAvatar(
        preset: PresetAvatarStyle,
        context: Context? = null
    ): UserProfileConfig {
        val previousPath = _configFlow.value.customAvatarPath
        val updated = _configFlow.value.copy(
            presetAvatar = preset,
            customAvatarPath = "",
            avatarUpdatedAtMillis = System.currentTimeMillis()
        )
        persist(updated)
        if (previousPath.isNotBlank()) {
            deleteCustomAvatarFileIfSafe(previousPath, context)
        }
        return updated
    }

    @Synchronized
    fun clearCustomAvatar(context: Context? = null): UserProfileConfig {
        return selectPresetAvatar(_configFlow.value.presetAvatar, context)
    }

    /**
     * 通过系统 Photo Picker 免权限返回的 `Uri` 解码、方向校正、居中裁剪并压缩保存至 `files/profile/`。
     */
    fun saveCustomAvatarFromUri(context: Context, sourceUri: Uri): Result<UserProfileConfig> {
        val appContext = context.applicationContext
        val rawBytes = runCatching {
            appContext.contentResolver.openInputStream(sourceUri)?.use { input ->
                val bos = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    total += read
                    if (total > MAX_SOURCE_IMAGE_BYTES) {
                        throw IllegalArgumentException("所选图片体积过大（上限 16MB），请选择常规图片")
                    }
                    bos.write(buf, 0, read)
                }
                bos.toByteArray()
            }
        }.getOrElse { err ->
            return Result.failure(IllegalArgumentException(err.message ?: "读取所选图片失败"))
        } ?: return Result.failure(IllegalArgumentException("无法打开所选相册图片"))

        if (rawBytes.isEmpty()) {
            return Result.failure(IllegalArgumentException("所选图片内容为空"))
        }
        return saveCustomAvatarFromEncodedImageBytes(appContext, rawBytes)
    }

    /**
     * 计算头像解码 `inSampleSize`：
     * 常规比例图片保证短边不低于 `targetPx`（384px）以维持清晰度；
     * 同时约束长边上限 `maxLongSidePx`（4096px），防止窄而极长的滚动长截图在 `ARGB_8888` 解码时瞬时分配数十 MB 内存。
     */
    internal fun computeAvatarDecodeSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        targetPx: Int = AVATAR_TARGET_PX,
        maxLongSidePx: Int = MAX_DECODE_LONG_SIDE_PX
    ): Int {
        if (srcWidth <= 0 || srcHeight <= 0) return 1
        val safeTargetPx = targetPx.coerceAtLeast(1)
        val safeMaxLongSidePx = maxLongSidePx.coerceAtLeast(safeTargetPx)
        var sampleSize = 1
        val minSide = minOf(srcWidth, srcHeight)
        val maxSide = maxOf(srcWidth, srcHeight)
        while (
            (minSide / (sampleSize * 2) >= safeTargetPx || maxSide / sampleSize > safeMaxLongSidePx) &&
            sampleSize < (1 shl 29)
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    fun saveCustomAvatarFromEncodedImageBytes(
        context: Context,
        encodedImageBytes: ByteArray
    ): Result<UserProfileConfig> {
        val appContext = context.applicationContext
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(encodedImageBytes, 0, encodedImageBytes.size, boundsOpts)
            val srcW = boundsOpts.outWidth
            val srcH = boundsOpts.outHeight
            if (srcW <= 0 || srcH <= 0) {
                throw IllegalArgumentException("图片格式不受支持或文件已损坏")
            }

            val sampleSize = computeAvatarDecodeSampleSize(srcW, srcH)

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var decodedBitmap: Bitmap? = null
            var orientedBitmap: Bitmap? = null
            var squareBitmap: Bitmap? = null
            val outBytes = try {
                val rawBitmap = BitmapFactory.decodeByteArray(
                    encodedImageBytes,
                    0,
                    encodedImageBytes.size,
                    decodeOpts
                ) ?: throw IllegalArgumentException("无法解码所选图片")
                decodedBitmap = rawBitmap

                val oriented = applyExifOrientationIfNeeded(rawBitmap, encodedImageBytes)
                orientedBitmap = oriented
                if (oriented !== rawBitmap) {
                    rawBitmap.recycle()
                }
                val cropped = centerCropAndScaleSquare(oriented, AVATAR_TARGET_PX)
                squareBitmap = cropped
                if (cropped !== oriented) {
                    oriented.recycle()
                }

                encodeSquareBitmapToWebpUnderLimit(cropped)
            } finally {
                squareBitmap?.takeIf { !it.isRecycled }?.recycle()
                orientedBitmap?.takeIf { !it.isRecycled }?.recycle()
                decodedBitmap?.takeIf { !it.isRecycled }?.recycle()
            }
            if (!isValidWebpAvatarBytes(outBytes)) {
                throw IllegalStateException("头像压缩编码失败")
            }

            val updated = writeValidatedAvatarBytesToSandbox(appContext, outBytes)
            Result.success(updated)
        } catch (oom: OutOfMemoryError) {
            Result.failure(IllegalArgumentException("图片尺寸过大导致内存不足，请选择常规尺寸图片"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 校验并返回安全可用的自定义头像文件；若未设置、路径越界、文件缺失或字节头损坏则返回 `null`，
     * 调用侧自动降级渲染预设莫兰迪插画头像。
     */
    fun resolveValidCustomAvatarFile(
        config: UserProfileConfig = composeConfigState,
        context: Context? = null
    ): File? = resolveValidCustomAvatarFile(config.customAvatarPath, context)

    fun resolveValidCustomAvatarFile(
        rawPath: String?,
        context: Context? = null
    ): File? {
        if (rawPath.isNullOrBlank()) return null
        val file = File(rawPath.trim())
        if (!file.exists() || !file.isFile) return null
        val len = file.length()
        if (len !in 16L..MAX_AVATAR_FILE_BYTES) return null

        val isInsideSandbox = runCatching {
            val canonical = file.canonicalPath.replace('\\', '/')
            val expectedPrefix = context?.let {
                File(it.applicationContext.filesDir, PROFILE_DIR_NAME)
                    .canonicalPath.replace('\\', '/').trimEnd('/') + "/"
            } ?: profileDirCanonicalPrefix
            if (expectedPrefix != null) {
                canonical.startsWith(expectedPrefix) &&
                    !canonical.removePrefix(expectedPrefix).contains('/')
            } else {
                Regex(""".*/files/profile/[^/]+\.(webp|png|jpg|jpeg)$""", RegexOption.IGNORE_CASE)
                    .matches(canonical)
            }
        }.getOrDefault(false)
        if (!isInsideSandbox) return null

        val hasValidMagic = runCatching {
            file.inputStream().use { ins ->
                val header = ByteArray(12)
                val read = ins.read(header)
                read >= 12 && isSupportedImageHeader(header, read)
            }
        }.getOrDefault(false)
        return if (hasValidMagic) file else null
    }

    fun isValidAvatarImageBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 12 || bytes.size.toLong() > MAX_AVATAR_FILE_BYTES) return false
        return isSupportedImageHeader(bytes, bytes.size)
    }

    /**
     * 严格校验云端同步专用 WebP 头像字节（`RIFF....WEBP` 魔数且体积在 `16B ..= 512KB` 之间）。
     */
    fun isValidWebpAvatarBytes(bytes: ByteArray): Boolean {
        if (bytes.size !in 16..MAX_CLOUD_AVATAR_BYTES) return false
        return bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
    }

    /**
     * 读取本机当前有效的自定义头像 WebP 字节供云同步上传；若当前使用预设头像则返回 `null`。
     */
    fun readCustomAvatarWebpBytes(context: Context): ByteArray? {
        val appContext = context.applicationContext
        val current = _configFlow.value
        val file = resolveValidCustomAvatarFile(current, appContext) ?: return null
        val raw = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (isValidWebpAvatarBytes(raw)) {
            return raw
        }
        // 兼容极早期非 WebP 或超 512KB 头像文件：在后台自动重编码为合规 WebP
        return runCatching {
            val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
            try {
                val square = centerCropAndScaleSquare(decoded, AVATAR_TARGET_PX)
                try {
                    encodeSquareBitmapToWebpUnderLimit(square).takeIf { isValidWebpAvatarBytes(it) }
                } finally {
                    if (square !== decoded && !square.isRecycled) square.recycle()
                }
            } finally {
                if (!decoded.isRecycled) decoded.recycle()
            }
        }.getOrNull()
    }

    /**
     * 将云端下载的合法 WebP 自定义头像原子写入应用沙盒并更新配置。
     */
    fun applyCloudAvatarBytes(context: Context, webpBytes: ByteArray): Result<UserProfileConfig> {
        if (!isValidWebpAvatarBytes(webpBytes)) {
            return Result.failure(IllegalArgumentException("云端自定义头像非合法 WebP 格式或超出 512KB 上限"))
        }
        return runCatching {
            writeValidatedAvatarBytesToSandbox(context.applicationContext, webpBytes)
        }
    }

    private fun encodeSquareBitmapToWebpUnderLimit(cropped: Bitmap): ByteArray {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        var lastEncoded = ByteArray(0)
        for (quality in intArrayOf(86, 74, 60, 45)) {
            val encoded = ByteArrayOutputStream().use { bos ->
                cropped.compress(format, quality, bos)
                bos.toByteArray()
            }
            lastEncoded = encoded
            if (encoded.size in 16..MAX_CLOUD_AVATAR_BYTES) {
                return encoded
            }
        }
        return lastEncoded
    }

    private fun writeValidatedAvatarBytesToSandbox(
        appContext: Context,
        webpBytes: ByteArray
    ): UserProfileConfig {
        val targetFile = writeWebpFileToProfileDir(appContext, webpBytes)
        val now = System.currentTimeMillis()
        return synchronized(this) {
            val next = _configFlow.value.copy(
                customAvatarPath = targetFile.absolutePath,
                avatarUpdatedAtMillis = now
            )
            persist(next)
            next
        }
    }

    private fun writeWebpFileToProfileDir(appContext: Context, webpBytes: ByteArray): File {
        val profileDir = File(appContext.filesDir, PROFILE_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
        profileDirCanonicalPrefix = profileDir.canonicalPath.replace('\\', '/').trimEnd('/') + "/"
        val now = System.currentTimeMillis()
        val targetFile = File(profileDir, "avatar_${now}_${System.nanoTime() and 0xFFFF}.webp")
        val tmpFile = File(profileDir, ".tmp_avatar_${System.nanoTime()}.webp")
        try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(webpBytes)
                fos.fd.sync()
            }
            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
        profileDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name != targetFile.name && !f.name.startsWith(".tmp_")) {
                f.delete()
            }
        }
        return targetFile
    }

    private fun isSupportedImageHeader(bytes: ByteArray, length: Int): Boolean {
        if (length < 12) return false
        val isWebp = bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        if (isWebp) return true

        val isPng = bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        if (isPng) return true

        val isJpeg = bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        return isJpeg
    }

    private fun deleteCustomAvatarFileIfSafe(path: String, context: Context? = null) {
        runCatching {
            val file = File(path)
            val canonical = file.canonicalPath.replace('\\', '/')
            val expectedPrefix = context?.let {
                File(it.applicationContext.filesDir, PROFILE_DIR_NAME)
                    .canonicalPath.replace('\\', '/').trimEnd('/') + "/"
            } ?: profileDirCanonicalPrefix
            if (expectedPrefix != null &&
                canonical.startsWith(expectedPrefix) &&
                !canonical.removePrefix(expectedPrefix).contains('/')
            ) {
                file.delete()
            }
        }
    }

    private fun applyExifOrientationIfNeeded(bitmap: Bitmap, rawBytes: ByteArray): Bitmap {
        return runCatching {
            val exif = ExifInterface(ByteArrayInputStream(rawBytes))
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                else -> return@runCatching bitmap
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) {
                bitmap.recycle()
            }
            rotated
        }.getOrDefault(bitmap)
    }

    private fun centerCropAndScaleSquare(src: Bitmap, targetSizePx: Int): Bitmap {
        val side = minOf(src.width, src.height).coerceAtLeast(1)
        val x = ((src.width - side) / 2).coerceAtLeast(0)
        val y = ((src.height - side) / 2).coerceAtLeast(0)
        val cropped = if (x == 0 && y == 0 && src.width == side && src.height == side) {
            src
        } else {
            Bitmap.createBitmap(src, x, y, side, side).also {
                if (it !== src) src.recycle()
            }
        }
        return if (cropped.width <= targetSizePx) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, targetSizePx, targetSizePx, true).also {
                if (it !== cropped) cropped.recycle()
            }
        }
    }

    @Synchronized
    fun exportBackupJson(): String {
        val cur = _configFlow.value
        val dto = UserProfileBackupDto(
            nickname = normalizeNickname(cur.nickname),
            presetAvatarName = cur.presetAvatar.name,
            hadCustomAvatar = cur.customAvatarPath.isNotBlank()
        )
        return jsonCodec.encodeToString(dto)
    }

    /**
     * 从快照恢复个人资料：
     * - 当旧版快照无 `userProfileJson`（空串）时返回 `false`，绝不破坏或清空当前资料；
     * - 当快照携带资料时恢复昵称与预设头像；若快照导出时使用自定义头像且本机当前自定义头像文件有效则保留，否则优雅回退预设头像。
     */
    @Synchronized
    fun restoreFromBackupJson(
        rawJson: String?,
        context: Context? = null,
        cloudAvatarWebpBytes: ByteArray? = null
    ): Boolean {
        if (rawJson.isNullOrBlank()) {
            if (cloudAvatarWebpBytes != null && context != null && isValidWebpAvatarBytes(cloudAvatarWebpBytes)) {
                val savedFile = runCatching {
                    writeWebpFileToProfileDir(context.applicationContext, cloudAvatarWebpBytes)
                }.getOrNull() ?: return false
                val updated = _configFlow.value.copy(
                    customAvatarPath = savedFile.absolutePath,
                    avatarUpdatedAtMillis = System.currentTimeMillis()
                )
                persist(updated)
                return true
            }
            return false
        }
        val dto = runCatching {
            jsonCodec.decodeFromString<UserProfileBackupDto>(rawJson.trim())
        }.getOrNull() ?: return false
        val current = _configFlow.value
        val resolvedCustomPath = when {
            cloudAvatarWebpBytes != null && context != null && isValidWebpAvatarBytes(cloudAvatarWebpBytes) -> {
                runCatching {
                    writeWebpFileToProfileDir(context.applicationContext, cloudAvatarWebpBytes).absolutePath
                }.getOrDefault("")
            }
            dto.hadCustomAvatar && current.customAvatarPath.isNotBlank() -> {
                resolveValidCustomAvatarFile(current.customAvatarPath, context)?.absolutePath.orEmpty()
            }
            else -> {
                if (current.customAvatarPath.isNotBlank()) {
                    deleteCustomAvatarFileIfSafe(current.customAvatarPath, context)
                }
                ""
            }
        }
        val restored = UserProfileConfig(
            nickname = normalizeNickname(dto.nickname),
            presetAvatar = PresetAvatarStyle.fromNameOrDefault(dto.presetAvatarName),
            customAvatarPath = resolvedCustomPath,
            avatarUpdatedAtMillis = System.currentTimeMillis()
        )
        persist(restored)
        return true
    }

    @Synchronized
    fun resetForTesting(
        config: UserProfileConfig = UserProfileConfig(),
        sandboxDirPrefix: String? = null
    ) {
        profileDirCanonicalPrefix = sandboxDirPrefix?.let {
            it.replace('\\', '/').trimEnd('/') + "/"
        }
        val sanitized = config.copy(nickname = normalizeNickname(config.nickname))
        persist(sanitized)
    }
}
