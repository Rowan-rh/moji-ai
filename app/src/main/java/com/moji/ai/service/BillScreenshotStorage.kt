package com.moji.ai.service

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class SavedScreenshotInfo(
    val absolutePath: String,
    val sha256: String
)

/**
 * 账单识屏截图本地持久化与 SHA-256 指纹计算器：
 * - 将识别到的微信/支付宝账单截图压缩为高清晰度 WebP 保存在应用沙盒 `files/bill_screenshots/` 目录；
 * - 计算图片内容的 SHA-256 哈希，既用于防止同一张截图重复导入，也作为未来云端对象存储（OSS/S3）的内容寻址 Key。
 */
object BillScreenshotStorage {

    private const val SCREENSHOT_DIR_NAME = "bill_screenshots"
    private const val MAX_SAFE_SCREENSHOT_BYTES = 8L * 1024L * 1024L

    @Volatile
    private var screenshotDirCanonicalPrefix: String? = null

    fun init(context: Context) {
        runCatching {
            val dir = File(context.applicationContext.filesDir, SCREENSHOT_DIR_NAME)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            screenshotDirCanonicalPrefix = dir.canonicalPath.replace('\\', '/').trimEnd('/') + "/"
        }
    }

    fun isSafeScreenshotFile(file: File, context: Context? = null): Boolean {
        if (!file.exists() || !file.isFile || !file.extension.equals("webp", ignoreCase = true)) {
            return false
        }
        return runCatching {
            val canonical = file.canonicalPath.replace('\\', '/')
            val expectedPrefix = context?.let {
                File(it.applicationContext.filesDir, SCREENSHOT_DIR_NAME)
                    .canonicalPath.replace('\\', '/').trimEnd('/') + "/"
            } ?: screenshotDirCanonicalPrefix
            if (expectedPrefix != null) {
                canonical.startsWith(expectedPrefix) &&
                    !canonical.removePrefix(expectedPrefix).contains('/')
            } else {
                Regex(""".*/files/bill_screenshots/[^/]+\.webp$""", RegexOption.IGNORE_CASE).matches(canonical)
            }
        }.getOrDefault(false)
    }

    fun saveBitmap(
        context: Context,
        bitmap: Bitmap,
        capturedAtMillis: Long = System.currentTimeMillis()
    ): SavedScreenshotInfo? {
        return runCatching {
            val dir = File(context.filesDir, SCREENSHOT_DIR_NAME)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            if (screenshotDirCanonicalPrefix == null) {
                screenshotDirCanonicalPrefix = dir.canonicalPath.replace('\\', '/').trimEnd('/') + "/"
            }

            val bos = ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            bitmap.compress(format, 82, bos)
            val bytes = bos.toByteArray()
            if (bytes.isEmpty()) return null

            val sha256 = sha256Hex(bytes)
            val shortHash = sha256.take(10)
            val targetFile = File(dir, "bill_${capturedAtMillis}_$shortHash.webp")
            if (!targetFile.exists()) {
                FileOutputStream(targetFile).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                }
            }
            SavedScreenshotInfo(
                absolutePath = targetFile.absolutePath,
                sha256 = sha256
            )
        }.getOrNull()
    }

    fun deleteIfExists(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            val f = File(path)
            if (isSafeScreenshotFile(f)) {
                f.delete()
            }
        }
    }

    /**
     * 查找或保存从云端拉取的 WebP 账单原图（严格校验 SHA-256 与 RIFF/WEBP 头，防止损坏或篡改数据落盘），
     * 返回当前设备上可直接访问的安全沙盒绝对路径。
     */
    fun resolveOrSaveCloudScreenshot(
        context: Context,
        expectedSha256Raw: String,
        existingPathHint: String? = null,
        downloadedWebpBytes: ByteArray? = null
    ): String? {
        val expectedSha = expectedSha256Raw.trim().lowercase()
        if (expectedSha.length != 64 || !expectedSha.all { it in '0'..'9' || it in 'a'..'f' }) {
            return null
        }
        return runCatching {
            val dir = File(context.applicationContext.filesDir, SCREENSHOT_DIR_NAME)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            if (screenshotDirCanonicalPrefix == null) {
                screenshotDirCanonicalPrefix = dir.canonicalPath.replace('\\', '/').trimEnd('/') + "/"
            }

            // 1. 若原路径已在本机存在且 SHA-256 完全匹配，直接复用
            if (!existingPathHint.isNullOrBlank()) {
                val hintFile = File(existingPathHint)
                if (isSafeScreenshotFile(hintFile, context) && hintFile.length() in 1L..MAX_SAFE_SCREENSHOT_BYTES) {
                    val hintBytes = hintFile.readBytes()
                    if (sha256Hex(hintBytes) == expectedSha) {
                        return@runCatching hintFile.absolutePath
                    }
                }
            }

            // 2. 检查本机固定内容寻址缓存文件是否已存在
            val cloudTarget = File(dir, "bill_cloud_${expectedSha.take(16)}.webp")
            if (cloudTarget.exists() && cloudTarget.isFile && cloudTarget.length() in 1L..MAX_SAFE_SCREENSHOT_BYTES) {
                val existingBytes = cloudTarget.readBytes()
                if (sha256Hex(existingBytes) == expectedSha) {
                    return@runCatching cloudTarget.absolutePath
                }
            }

            // 3. 若传入了云端下载字节流，强校验 WebP 魔数与 SHA-256 后原子落盘
            val bytes = downloadedWebpBytes ?: return@runCatching null
            if (bytes.size < 16 || bytes.size.toLong() > MAX_SAFE_SCREENSHOT_BYTES) return@runCatching null
            val isWebp = bytes[0] == 'R'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() &&
                bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() &&
                bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() &&
                bytes[11] == 'P'.code.toByte()
            if (!isWebp || sha256Hex(bytes) != expectedSha) {
                return@runCatching null
            }

            val tmpFile = File(dir, ".tmp_${expectedSha.take(16)}_${System.nanoTime()}.webp")
            try {
                FileOutputStream(tmpFile).use { fos ->
                    fos.write(bytes)
                    fos.fd.sync()
                }
                if (!tmpFile.renameTo(cloudTarget)) {
                    try {
                        FileOutputStream(cloudTarget).use { fos ->
                            fos.write(bytes)
                            fos.fd.sync()
                        }
                    } catch (writeErr: Exception) {
                        cloudTarget.delete()
                        throw writeErr
                    }
                }
            } finally {
                if (tmpFile.exists()) {
                    tmpFile.delete()
                }
            }
            cloudTarget.absolutePath
        }.getOrNull()
    }

    fun computeFileSha256IfSafe(file: File, context: Context? = null): String? {
        if (!isSafeScreenshotFile(file, context)) return null
        if (file.length() !in 1L..MAX_SAFE_SCREENSHOT_BYTES) return null
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.isEmpty()) null else sha256Hex(bytes)
        }.getOrNull()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
