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
            if (f.exists() && f.isFile) {
                f.delete()
            }
        }
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
