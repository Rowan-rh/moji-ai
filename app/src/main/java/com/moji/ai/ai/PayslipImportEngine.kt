package com.moji.ai.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.moji.ai.data.backup.BackupAndCsvTransferEngine
import com.moji.ai.data.model.DedicatedFundSettings
import com.moji.ai.data.model.PayslipRecognitionDraft
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 端侧 OCR 识别出的单个文本行框（纯 Kotlin 坐标结构，支持纯 JVM 单测验证同行纵向聚类与左右列排序）
 */
data class OcrTextBox(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val centerY: Float
        get() = (top + bottom) * 0.5f

    val width: Float
        get() = (right - left).coerceAtLeast(1f)

    val height: Float
        get() = (bottom - top).coerceAtLeast(1f)
}

/**
 * 工资条解析输出契约：包含标准 `PayslipRecognitionDraft`、探测到的排版格式、重组后的原始可读文本及辅助说明
 */
data class PayslipParseResult(
    val draft: PayslipRecognitionDraft,
    val detectedFormat: String,
    val normalizedText: String,
    val isNetPayDerived: Boolean = false,
    val isEmployerProvidentFromPlanFallback: Boolean = false,
    val ignoredEmployerSocialInsuranceCents: Long = 0L,
    val hints: List<String> = emptyList()
)

@Serializable
private data class CloudPayslipCalibrationDto(
    val isValidPayslip: Boolean = true,
    val grossSalaryYuan: Double = 0.0,
    val incomeTaxYuan: Double = 0.0,
    val pensionAndUnemploymentDeductionYuan: Double = 0.0,
    val personalMedicalDeductionYuan: Double = 0.0,
    val medicalPersonalAccountDepositYuan: Double = 0.0,
    val personalProvidentDeductionYuan: Double = 0.0,
    val employerProvidentMatchYuan: Double = 0.0,
    val hasExplicitEmployerProvident: Boolean = false,
    val netTakeHomePayYuan: Double = 0.0,
    val paydayDate: String = "",
    val employerName: String = ""
)

/**
 * 工资条截图端侧离线 OCR / CSV·TSV 薪资表导入多源解析引擎（零新增第三方依赖）：
 * 1. 支持 UTF-8 BOM / 严格 UTF-8 / GBK·GB18030 自动探测（兼容轻量 `.xlsx` 转换）；
 * 2. 同时支持「横向表头行 + 数据行」与「纵向项目-金额两列 / 同行左右双栏键值」排版；
 * 3. 严格区分「个人扣缴」与「单位缴纳」：绝不把单位养老/单位医疗计入个人扣款，无单位公积金列时绝不将个人公积金盲目翻倍；
 * 4. 复用内置 `mlkit-text-recognition-chinese` 实现大图安全降采样与按 `centerY` 纵向同行聚类重组；
 * 5. 若已配置云端 AI（OpenAI 兼容 / Gemini），可选支持一键 AI 二次结构化校准。
 */
object PayslipImportEngine {

    private const val DEFAULT_MAX_IMAGE_EDGE_PX = 4096
    private const val DEFAULT_MAX_IMAGE_PIXELS = 6_000_000L
    private const val MAX_FILE_BYTES = 12 * 1024 * 1024

    val SAMPLE_HORIZONTAL_CSV: String = """
        发薪单位,发薪日期,应发工资,养老保险个人,失业保险个人,医疗保险个人,医保个账划入,住房公积金个人,住房公积金单位,单位养老保险,单位医疗保险,个人所得税,实发工资
        星辰智造科技有限公司,2026-11-15,"20,000.00",1600.00,100.00,400.00,400.00,1200.00,1200.00,3200.00,1600.00,590.00,"16,110.00"
    """.trimIndent()

    val SAMPLE_VERTICAL_TEXT: String = """
        发薪单位：星辰智造科技有限公司
        发薪日期：2026年11月15日
        应发合计        20,000.00
        养老保险(个人)   -1,600.00    养老保险(单位)   3,200.00
        失业保险(个人)   -100.00      失业保险(单位)   160.00
        医疗保险(个人)   -400.00      医疗保险(单位)   1,600.00
        医保个账划入     400.00
        住房公积金个人   -1,200.00    住房公积金单位   1,200.00
        个人所得税       -590.00
        实发到手工资     16,110.00
    """.trimIndent()

    private val jsonCodec = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private enum class SectionContext {
        NEUTRAL,
        PERSONAL_DEDUCTION,
        EMPLOYER_CONTRIBUTION
    }

    private enum class FieldSlot {
        PAYDAY,
        EMPLOYER_NAME,
        GROSS_TOTAL,
        EARNING_COMPONENT,
        INCOME_TAX,
        PERSONAL_PENSION_UNEMPLOYMENT,
        PERSONAL_MEDICAL_DEDUCTION,
        MEDICAL_ACCOUNT_DEPOSIT,
        PERSONAL_PROVIDENT,
        EMPLOYER_PROVIDENT,
        TOTAL_BILATERAL_PROVIDENT,
        PERSONAL_SOCIAL_TOTAL,
        PERSONAL_OTHER_DEDUCTION,
        NET_TAKE_HOME,
        EMPLOYER_SOCIAL_IGNORED,
        IGNORED
    }

    private data class MutablePayslipAccumulator(
        var paydayMillis: Long? = null,
        var employerName: String = "",
        var explicitGrossCents: Long = 0L,
        var earningComponentsSumCents: Long = 0L,
        var incomeTaxCents: Long = 0L,
        var pensionAndUnemploymentCents: Long = 0L,
        var personalMedicalCents: Long = 0L,
        var medicalAccountDepositCents: Long = 0L,
        var personalProvidentCents: Long = 0L,
        var employerProvidentCents: Long = 0L,
        var hasExplicitEmployerProvident: Boolean = false,
        var totalBilateralProvidentCents: Long = 0L,
        var personalSocialTotalCents: Long = 0L,
        var otherPersonalDeductionCents: Long = 0L,
        var explicitNetPayCents: Long = 0L,
        var ignoredEmployerSocialCents: Long = 0L,
        var matchedSalaryFieldCount: Int = 0
    )

    /**
     * 解码外部 CSV / TSV / 文本或轻量 `.xlsx` 字节流（自动探测 UTF-8 BOM / 严格 UTF-8 / GB18030·GBK）
     */
    fun decodePayslipBytes(
        bytes: ByteArray,
        fileNameHint: String = ""
    ): Result<String> {
        if (bytes.isEmpty()) {
            return Result.failure(IllegalArgumentException("文件内容为空，请选择有效的工资条 CSV / TSV 或文本文件"))
        }
        if (bytes.size > MAX_FILE_BYTES) {
            return Result.failure(IllegalArgumentException("文件体积超过 12MB 上限，请导出当月薪资表 CSV/TSV 后重试"))
        }

        // 兼容 ZIP 魔数的 .xlsx 文件（复用内置轻量 XML 提取器，零新增第三方依赖）
        val isZipHeader = bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() &&
            bytes[3] == 0x04.toByte()
        if (isZipHeader || fileNameHint.endsWith(".xlsx", ignoreCase = true)) {
            val extracted = runCatching { BackupAndCsvTransferEngine.extractTextOrXlsxToCsv(bytes) }
                .getOrElse { err ->
                    return Result.failure(
                        IllegalArgumentException("无法读取该表格文件：${err.message ?: "请另存为 CSV 或 TSV 格式后导入"}")
                    )
                }
                .orEmpty()
                .trim()
            if (extracted.isBlank()) {
                return Result.failure(IllegalArgumentException("表格内容为空，未检测到薪资数据"))
            }
            return Result.success(extracted)
        }

        val text = decodeTextBytesWithCharsetAutoDetect(bytes).trim()
        if (text.isBlank()) {
            return Result.failure(IllegalArgumentException("文件内容为空白，未检测到可识别的工资条数据"))
        }
        return Result.success(text)
    }

    internal fun decodeTextBytesWithCharsetAutoDetect(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // 1. UTF-8 BOM
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        // 2. UTF-16 LE / BE BOM
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        // 3. 严格校验 UTF-8；若遇到非法多字节序列则自动回退 GB18030（超集兼容 GBK / GB2312）
        val strictUtf8 = runCatching {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        if (strictUtf8 != null) {
            return strictUtf8.removePrefix("\uFEFF")
        }
        val gbCharset = runCatching { Charset.forName("GB18030") }
            .recoverCatching { Charset.forName("GBK") }
            .getOrDefault(Charsets.UTF_8)
        return String(bytes, gbCharset).removePrefix("\uFEFF")
    }

    /**
     * 计算大图安全降采样倍率 `inSampleSize`（2 的整数次幂），防止 4K 原图或超长工资条截图引发 Bitmap OOM
     */
    fun calculateInSampleSize(
        rawWidth: Int,
        rawHeight: Int,
        maxEdgePx: Int = DEFAULT_MAX_IMAGE_EDGE_PX,
        maxTotalPixels: Long = DEFAULT_MAX_IMAGE_PIXELS
    ): Int {
        if (rawWidth <= 0 || rawHeight <= 0) return 1
        var sampleSize = 1
        while (
            (rawWidth / sampleSize) > maxEdgePx ||
            (rawHeight / sampleSize) > maxEdgePx ||
            ((rawWidth.toLong() / sampleSize) * (rawHeight.toLong() / sampleSize)) > maxTotalPixels
        ) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    /**
     * 端侧 OCR 同行坐标聚类重组（`AC-B2` 核心算法）：
     * - 按纵向 `centerY` 升序扫描，结合行高容差将同一水平行的左列标签与右列金额归入同一行簇；
     * - 若新文本框与当前行簇内已有框在水平 `X` 轴大面积重叠（紧邻的上下两行），拒绝合并以防上下串行；
     * - 同一行内按 `left` 从左到右排序并以制表符拼接。
     */
    fun clusterOcrBlocksIntoRows(boxes: List<OcrTextBox>): List<String> {
        val cleanBoxes = boxes
            .map { it.copy(text = it.text.trim().replace(Regex("""\s+"""), " ")) }
            .filter { it.text.isNotEmpty() }
            .sortedWith(compareBy<OcrTextBox> { it.centerY }.thenBy { it.left })

        if (cleanBoxes.isEmpty()) return emptyList()

        class RowCluster(first: OcrTextBox) {
            val items = mutableListOf(first)
            var sumCenterY = first.centerY.toDouble()
            var sumHeight = first.height.toDouble()

            val avgCenterY: Float get() = (sumCenterY / items.size).toFloat()
            val avgHeight: Float get() = (sumHeight / items.size).toFloat()

            fun canAccept(candidate: OcrTextBox): Boolean {
                val dy = abs(candidate.centerY - avgCenterY)
                val tolerance = (max(avgHeight, candidate.height) * 0.56f).coerceAtLeast(6f)
                if (dy > tolerance) return false

                // 防止行距极近的上下两行同列文字错误合并
                val hasHeavyHorizontalOverlap = items.any { existing ->
                    val overlapLeft = max(existing.left, candidate.left)
                    val overlapRight = min(existing.right, candidate.right)
                    val overlapWidth = overlapRight - overlapLeft
                    val minBoxWidth = min(existing.width, candidate.width)
                    overlapWidth > minBoxWidth * 0.45f && dy > min(existing.height, candidate.height) * 0.38f
                }
                return !hasHeavyHorizontalOverlap
            }

            fun add(candidate: OcrTextBox) {
                items.add(candidate)
                sumCenterY += candidate.centerY.toDouble()
                sumHeight += candidate.height.toDouble()
            }
        }

        val clusters = mutableListOf<RowCluster>()
        for (box in cleanBoxes) {
            val bestCluster = clusters
                .filter { it.canAccept(box) }
                .minByOrNull { abs(it.avgCenterY - box.centerY) }
            if (bestCluster != null) {
                bestCluster.add(box)
            } else {
                clusters.add(RowCluster(box))
            }
        }

        return clusters
            .sortedBy { it.avgCenterY }
            .map { cluster ->
                cluster.items
                    .sortedBy { it.left }
                    .joinToString("\t") { it.text }
            }
            .filter { it.isNotBlank() }
    }

    /**
     * 从相册 `Uri` 安全降采样解码工资条图片，执行端侧离线中文 OCR 并解析为工资条草稿
     */
    suspend fun recognizePayslipImageUri(
        context: Context,
        imageUri: Uri,
        defaultSettings: DedicatedFundSettings = DedicatedFundSettings(),
        nowMillis: Long = System.currentTimeMillis()
    ): Result<PayslipParseResult> = withContext(Dispatchers.IO) {
        val bitmap = decodeDownsampledBitmapFromUri(context, imageUri).getOrElse { err ->
            return@withContext Result.failure(err)
        }
        try {
            val visionText = runLocalChineseOcr(bitmap)
            val ocrBoxes = extractOcrTextBoxes(visionText)
            val clusteredRows = clusterOcrBlocksIntoRows(ocrBoxes)
            val mergedText = if (clusteredRows.isNotEmpty()) {
                clusteredRows.joinToString("\n")
            } else {
                visionText.text.trim()
            }
            if (mergedText.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException("图片中未识别到文字，请选择清晰的工资条截图")
                )
            }
            parsePayslipText(
                rawText = mergedText,
                defaultSettings = defaultSettings,
                nowMillis = nowMillis,
                sourceFormatOverride = "工资条截图离线 OCR"
            )
        } catch (err: Exception) {
            Result.failure(
                IllegalArgumentException(err.message ?: "工资条图片 OCR 识别失败，请检查图片是否清晰")
            )
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun decodeDownsampledBitmapFromUri(context: Context, uri: Uri): Result<Bitmap> {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        } ?: return Result.failure(IllegalArgumentException("无法读取所选图片文件"))

        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
            return Result.failure(IllegalArgumentException("所选文件不是有效的图片格式"))
        }

        val rotationDegrees = runCatching {
            context.contentResolver.openInputStream(uri)?.use { exifStream ->
                val exif = ExifInterface(exifStream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)

        val sampleSize = calculateInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: return Result.failure(IllegalArgumentException("图片解码失败，请重试"))

        if (rotationDegrees == 0) {
            return Result.success(decoded)
        }
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = runCatching {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }.getOrDefault(decoded)
        if (rotated !== decoded && !decoded.isRecycled) {
            decoded.recycle()
        }
        return Result.success(rotated)
    }

    private suspend fun runLocalChineseOcr(bitmap: Bitmap): Text =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            cont.invokeOnCancellation { runCatching { recognizer.close() } }
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(inputImage)
                .addOnSuccessListener { result ->
                    runCatching { recognizer.close() }
                    if (cont.isActive) cont.resume(result)
                }
                .addOnFailureListener { e ->
                    runCatching { recognizer.close() }
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }

    private fun extractOcrTextBoxes(visionText: Text): List<OcrTextBox> {
        val boxes = mutableListOf<OcrTextBox>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val rect = line.boundingBox
                val content = line.text.trim()
                if (content.isNotEmpty() && rect != null) {
                    boxes.add(
                        OcrTextBox(
                            text = content,
                            left = rect.left.toFloat(),
                            top = rect.top.toFloat(),
                            right = rect.right.toFloat(),
                            bottom = rect.bottom.toFloat()
                        )
                    )
                }
            }
        }
        return boxes
    }

    /**
     * 核心薪资表/工资条文本解析入口（`AC-B1` & `AC-B2`）：
     * 自动探测「横向表头 CSV/TSV」与「纵向键值 / 同行双栏工资条」排版，并执行严格反混淆校验。
     */
    fun parsePayslipText(
        rawText: String,
        defaultSettings: DedicatedFundSettings = DedicatedFundSettings(),
        nowMillis: Long = System.currentTimeMillis(),
        sourceFormatOverride: String? = null
    ): Result<PayslipParseResult> {
        val cleaned = rawText
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (cleaned.isBlank()) {
            return Result.failure(IllegalArgumentException("输入内容为空，请粘贴或选择有效的工资条数据"))
        }

        val nonEmptyLines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (nonEmptyLines.isEmpty()) {
            return Result.failure(IllegalArgumentException("输入内容为空，请粘贴或选择有效的工资条数据"))
        }

        val horizontalAttempt = tryParseHorizontalTable(nonEmptyLines, defaultSettings, nowMillis)
        val verticalAttempt = tryParseVerticalOrTwoColumnText(nonEmptyLines, defaultSettings, nowMillis)

        val chosenPair = selectBetterAttempt(horizontalAttempt, verticalAttempt)
            ?: return Result.failure(
                IllegalArgumentException("未识别到有效的薪资与五险一金字段，请确认导入内容为工资条或薪资表（而非普通消费账单）")
            )

        val (acc, layoutLabel, extraHints) = chosenPair
        return finalizeAccumulatorToResult(
            acc = acc,
            detectedFormat = sourceFormatOverride ?: layoutLabel,
            normalizedText = cleaned,
            defaultSettings = defaultSettings,
            nowMillis = nowMillis,
            extraHints = extraHints
        )
    }

    private fun selectBetterAttempt(
        horizontal: Triple<MutablePayslipAccumulator, String, List<String>>?,
        vertical: Triple<MutablePayslipAccumulator, String, List<String>>?
    ): Triple<MutablePayslipAccumulator, String, List<String>>? {
        fun score(triple: Triple<MutablePayslipAccumulator, String, List<String>>?): Int {
            val a = triple?.first ?: return -1
            var s = a.matchedSalaryFieldCount * 10
            if (a.explicitGrossCents > 0L || a.earningComponentsSumCents > 0L) s += 25
            if (a.explicitNetPayCents > 0L) s += 30
            if (a.personalProvidentCents > 0L || a.personalMedicalCents > 0L || a.incomeTaxCents > 0L) s += 20
            return s
        }
        val hScore = score(horizontal)
        val vScore = score(vertical)
        if (hScore < 0 && vScore < 0) return null
        return if (hScore >= vScore) horizontal else vertical
    }

    private fun tryParseHorizontalTable(
        lines: List<String>,
        defaultSettings: DedicatedFundSettings,
        nowMillis: Long
    ): Triple<MutablePayslipAccumulator, String, List<String>>? {
        if (lines.size < 2) return null

        val delimiter = detectTableDelimiter(lines)
        val parsedRows = lines.map { splitDelimitedRow(it, delimiter) }

        // 查找命中薪资表头字段最多的表头行（至少 >= 3 列且 >= 2 个薪资专用列头）
        var headerRowIdx = -1
        var bestHeaderHits = 0
        for (idx in 0 until min(parsedRows.size - 1, 10)) {
            val row = parsedRows[idx]
            if (row.size < 3) continue
            val hits = row.count { cell ->
                val slot = classifyPayslipField(cell, SectionContext.NEUTRAL)
                slot != FieldSlot.IGNORED
            }
            // 排除本身全是数字的数据行冒充表头
            val numericCells = row.count { parseSignedMoneyCents(it) != null }
            if (hits >= 2 && numericCells <= row.size / 3 && hits > bestHeaderHits) {
                bestHeaderHits = hits
                headerRowIdx = idx
            }
        }
        if (headerRowIdx < 0) return null

        val rawHeaders = parsedRows[headerRowIdx]
        // 支持双层表头（上一行写「个人扣缴 / 单位承担」，当前行写「养老 / 医疗 / 公积金」）
        val groupRow = if (headerRowIdx > 0) parsedRows[headerRowIdx - 1] else emptyList()
        val effectiveHeaders = rawHeaders.mapIndexed { colIdx, colName ->
            val groupHint = resolveGroupHeaderPrefix(groupRow, colIdx)
            if (groupHint.isNotBlank() && !hasExplicitPartyQualifier(colName)) {
                "$groupHint$colName"
            } else {
                colName
            }
        }

        val candidateDataRows = mutableListOf<List<String>>()
        for (rIdx in (headerRowIdx + 1) until parsedRows.size) {
            val row = parsedRows[rIdx]
            if (row.all { it.isBlank() }) continue
            val joinedRow = row.joinToString(" ")
            if (SUMMARY_ROW_KEYWORDS.any { kw ->
                    row.firstOrNull()?.trim()?.equals(kw, ignoreCase = true) == true ||
                        joinedRow.startsWith(kw)
                }
            ) {
                continue
            }
            val anyAmount = row.any { parseSignedMoneyCents(it) != null }
            if (anyAmount) {
                candidateDataRows.add(row)
            }
        }
        if (candidateDataRows.isEmpty()) return null

        // 若薪资表包含多月记录，优先选择发薪日期最新的一行（否则取最后一行有效记录）
        val defaultDepositDay = defaultSettings.providentPlan.depositDayOfMonth
        val chosenRow = candidateDataRows.maxByOrNull { row ->
            var rowTs = -1L
            for (cIdx in effectiveHeaders.indices) {
                val cellVal = row.getOrNull(cIdx).orEmpty().trim()
                if (cellVal.isEmpty()) continue
                val slot = classifyPayslipField(effectiveHeaders[cIdx], SectionContext.NEUTRAL)
                if (slot == FieldSlot.PAYDAY) {
                    parsePayslipDateMillis(cellVal, defaultDepositDay, nowMillis)?.let { rowTs = it }
                }
            }
            rowTs
        } ?: candidateDataRows.last()

        val acc = MutablePayslipAccumulator()
        for (cIdx in effectiveHeaders.indices) {
            val header = effectiveHeaders[cIdx].trim()
            val value = chosenRow.getOrNull(cIdx)?.trim().orEmpty()
            if (header.isEmpty() || value.isEmpty()) continue
            applyFieldToAccumulator(
                acc = acc,
                label = header,
                rawValue = value,
                sectionContext = SectionContext.NEUTRAL,
                defaultDepositDay = defaultDepositDay,
                nowMillis = nowMillis
            )
        }

        if (acc.matchedSalaryFieldCount < 2) return null
        val hints = mutableListOf<String>()
        if (candidateDataRows.size > 1) {
            hints.add("检测到 ${candidateDataRows.size} 行薪资记录，已载入最新一期明细")
        }
        val fmtLabel = if (delimiter == '\t') "横向表头 TSV 薪资表" else "横向表头 CSV 薪资表"
        return Triple(acc, fmtLabel, hints)
    }

    private fun resolveGroupHeaderPrefix(groupRow: List<String>, colIdx: Int): String {
        if (groupRow.isEmpty()) return ""
        val directCell = groupRow.getOrNull(colIdx)?.trim().orEmpty()
        val targetCell = if (directCell.isNotEmpty()) {
            directCell
        } else {
            var fallback = ""
            for (idx in min(colIdx, groupRow.lastIndex) downTo 0) {
                val c = groupRow[idx].trim()
                if (c.isNotEmpty()) {
                    fallback = c
                    break
                }
            }
            fallback
        }
        if (targetCell.isEmpty()) return ""
        if (EMPLOYER_NAME_KEYWORDS.any { targetCell.contains(it) } ||
            PAYDAY_KEYWORDS.any { targetCell.contains(it) }
        ) {
            return ""
        }
        return when {
            EMPLOYER_PARTY_KEYWORDS.any { targetCell.contains(it) } -> "单位"
            PERSONAL_PARTY_KEYWORDS.any { targetCell.contains(it) } -> "个人"
            else -> ""
        }
    }

    private fun detectTableDelimiter(lines: List<String>): Char {
        val sample = lines.take(6)
        val tabCount = sample.sumOf { line -> line.count { it == '\t' } }
        val commaCount = sample.sumOf { line -> countCsvFieldCommas(line) }
        val semicolonCount = sample.sumOf { line -> countUnquotedChar(line, ';') }
        return when {
            tabCount >= 2 && tabCount >= commaCount -> '\t'
            commaCount >= 2 -> ','
            semicolonCount >= 2 -> ';'
            else -> ','
        }
    }

    /**
     * 统计行内真正的 CSV 字段分隔逗号（自动剔除双引号内逗号，以及无引号金额中的千分位逗号如 `18,000.00`）
     */
    private fun countCsvFieldCommas(line: String): Int {
        val strippedThousands = UNQUOTED_THOUSANDS_COMMA_REGEX.replace(line, "$1$2")
        return countUnquotedChar(strippedThousands, ',')
    }

    private fun countUnquotedChar(line: String, target: Char): Int {
        var inQuotes = false
        var count = 0
        for (ch in line) {
            if (ch == '"') {
                inQuotes = !inQuotes
            } else if (!inQuotes && ch == target) {
                count++
            }
        }
        return count
    }

    internal fun splitDelimitedRow(line: String, delimiter: Char): List<String> {
        if (delimiter == ' ') {
            return line.trim().split(Regex("""\s{2,}|\t""")).map { it.trim() }
        }
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == delimiter && !inQuotes -> {
                    result.add(sb.toString().trim())
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
            i++
        }
        result.add(sb.toString().trim())
        return result
    }

    private fun tryParseVerticalOrTwoColumnText(
        lines: List<String>,
        defaultSettings: DedicatedFundSettings,
        nowMillis: Long
    ): Triple<MutablePayslipAccumulator, String, List<String>>? {
        val acc = MutablePayslipAccumulator()
        val defaultDepositDay = defaultSettings.providentPlan.depositDayOfMonth
        var section = SectionContext.NEUTRAL

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            // 识别独立段落标题行（如「【单位缴纳明细】」「个人代扣部分」）
            val sectionSwitch = detectSectionHeading(line)
            if (sectionSwitch != null) {
                section = sectionSwitch
                continue
            }
            // 若遇到「实发工资 / 应发工资」行，重置单位段落上下文，防止后续实发被吞
            if (line.contains("实发") || line.contains("应发") || line.contains("到手")) {
                if (section == SectionContext.EMPLOYER_CONTRIBUTION && !hasExplicitEmployerMarker(line)) {
                    section = SectionContext.NEUTRAL
                }
            }

            // 1. 先检查单行键值文本型元数据（如 `发薪单位：星辰科技`、`发薪月份,2026-11-15`）
            extractMetadataPair(line)?.let { (metaKey, metaVal) ->
                val metaSlot = classifyPayslipField(metaKey, section)
                if (metaSlot == FieldSlot.EMPLOYER_NAME || metaSlot == FieldSlot.PAYDAY) {
                    applyFieldToAccumulator(acc, metaKey, metaVal, section, defaultDepositDay, nowMillis)
                    if (parseSignedMoneyCents(metaVal) == null) {
                        return@let
                    }
                }
            }

            // 2. 若当前行为标准 CSV/TSV 两列或偶数对键值列（兼容带双引号千分位如 `"20,000.00"`，并防止无引号千分位 `18,000.00` 误拆列），优先按分隔符精确拆列
            val delimitedCols = when {
                '\t' in line -> splitDelimitedRow(line, '\t')
                countCsvFieldCommas(line) >= 1 && !line.contains('：') -> splitDelimitedRow(line, ',')
                else -> emptyList()
            }
            if (delimitedCols.size == 2 &&
                !TRAILING_TRUNCATED_NUMBER_IN_LABEL_REGEX.containsMatchIn(delimitedCols[0]) &&
                parseSignedMoneyCents(delimitedCols[1]) != null
            ) {
                applyFieldToAccumulator(acc, delimitedCols[0], delimitedCols[1], section, defaultDepositDay, nowMillis)
                continue
            } else if (delimitedCols.size >= 4 && delimitedCols.size % 2 == 0) {
                val validLabelValuePairs = delimitedCols.chunked(2).all { pair ->
                    !TRAILING_TRUNCATED_NUMBER_IN_LABEL_REGEX.containsMatchIn(pair[0]) &&
                        parseSignedMoneyCents(pair[1]) != null
                }
                if (validLabelValuePairs) {
                    var idx = 0
                    while (idx + 1 < delimitedCols.size) {
                        applyFieldToAccumulator(acc, delimitedCols[idx], delimitedCols[idx + 1], section, defaultDepositDay, nowMillis)
                        idx += 2
                    }
                    continue
                }
            }

            // 3. 支持同行存在 1~N 对「项目名 + 金额」（例如 OCR 左右双栏拼合后：`养老个人 -1600.00   养老单位 3200.00`）
            val inlinePairs = extractLabelAmountPairsInLine(line)
            if (inlinePairs.isNotEmpty()) {
                for ((label, amountToken) in inlinePairs) {
                    applyFieldToAccumulator(acc, label, amountToken, section, defaultDepositDay, nowMillis)
                }
                continue
            }
        }

        if (acc.matchedSalaryFieldCount == 0) return null
        return Triple(acc, "纵向键值 / 双栏工资条", emptyList())
    }

    private fun detectSectionHeading(line: String): SectionContext? {
        if (parseSignedMoneyCents(line) != null || INLINE_AMOUNT_TOKEN_REGEX.containsMatchIn(line)) {
            return null
        }
        val normalized = line.replace(Regex("""[\s\[\]【】()（）:：\-—=*#]"""), "")
        if (normalized.length !in 2..16) return null
        return when {
            EMPLOYER_SECTION_TITLES.any { normalized.contains(it) } -> SectionContext.EMPLOYER_CONTRIBUTION
            PERSONAL_SECTION_TITLES.any { normalized.contains(it) } -> SectionContext.PERSONAL_DEDUCTION
            else -> null
        }
    }

    private fun extractMetadataPair(line: String): Pair<String, String>? {
        val colonIdx = line.indexOfFirst { it == ':' || it == '：' }
        if (colonIdx in 1 until line.lastIndex) {
            val k = line.substring(0, colonIdx).trim()
            val v = line.substring(colonIdx + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) return k to v
        }
        val delim = when {
            '\t' in line -> '\t'
            ',' in line -> ','
            else -> null
        }
        if (delim != null) {
            val parts = splitDelimitedRow(line, delim)
            if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                return parts[0] to parts[1]
            }
        }
        return null
    }

    /**
     * 提取单行内的全部 `(字段标签, 金额字符串)` 对：
     * 兼容 `养老保险(个人) -1,600.00   养老保险(单位) 3,200.00`、`实发工资：¥16,110.00元` 等所有常见样式，
     * 并自动跳过类似 `2026-11-15` 的日期片段避免将年份/日期误认为金额。
     */
    private fun extractLabelAmountPairsInLine(line: String): List<Pair<String, String>> {
        // 先剔除行内的标准日期（先不让日期里的数字干扰金额提取）
        val sanitizedLine = DATE_IN_LINE_REGEX.replace(line) { " ".repeat(it.value.length) }
        val matches = INLINE_LABEL_AMOUNT_PAIR_REGEX.findAll(sanitizedLine).toList()
        if (matches.isEmpty()) return emptyList()
        val pairs = mutableListOf<Pair<String, String>>()
        for (m in matches) {
            val rawLabel = m.groupValues[1]
                .trim()
                .trim('"', '\'', ',', ';', '，', '；', '|', '｜', ':', '：', '-', '—', '·')
                .trim()
            val rawAmt = m.groupValues[2].trim()
            if (rawLabel.isNotEmpty() && parseSignedMoneyCents(rawAmt) != null) {
                pairs.add(rawLabel to rawAmt)
            }
        }
        return pairs
    }

    private fun applyFieldToAccumulator(
        acc: MutablePayslipAccumulator,
        label: String,
        rawValue: String,
        sectionContext: SectionContext,
        defaultDepositDay: Int,
        nowMillis: Long
    ) {
        val slot = classifyPayslipField(label, sectionContext)
        if (slot == FieldSlot.IGNORED) return

        when (slot) {
            FieldSlot.PAYDAY -> {
                parsePayslipDateMillis(rawValue, defaultDepositDay, nowMillis)?.let { ts ->
                    acc.paydayMillis = ts
                }
            }
            FieldSlot.EMPLOYER_NAME -> {
                val cleanedName = rawValue.trim().trim('"', '\'')
                if (cleanedName.length in 2..48 && parseSignedMoneyCents(cleanedName) == null) {
                    acc.employerName = cleanedName
                }
            }
            else -> {
                val signedCents = parseSignedMoneyCents(rawValue) ?: return
                val absCents = abs(signedCents)
                if (absCents == 0L) {
                    // 识别到了合法薪资科目名称但金额为 0（如个税 0.00），计入字段命中数，后续仍受“全 0 草稿拦截”保护
                    acc.matchedSalaryFieldCount++
                    return
                }
                acc.matchedSalaryFieldCount++
                when (slot) {
                    FieldSlot.GROSS_TOTAL -> acc.explicitGrossCents = max(acc.explicitGrossCents, absCents)
                    FieldSlot.EARNING_COMPONENT -> acc.earningComponentsSumCents += absCents
                    FieldSlot.INCOME_TAX -> acc.incomeTaxCents = max(acc.incomeTaxCents, absCents)
                    FieldSlot.PERSONAL_PENSION_UNEMPLOYMENT -> acc.pensionAndUnemploymentCents += absCents
                    FieldSlot.PERSONAL_MEDICAL_DEDUCTION -> acc.personalMedicalCents = max(acc.personalMedicalCents, absCents)
                    FieldSlot.MEDICAL_ACCOUNT_DEPOSIT -> acc.medicalAccountDepositCents = max(acc.medicalAccountDepositCents, absCents)
                    FieldSlot.PERSONAL_PROVIDENT -> acc.personalProvidentCents = max(acc.personalProvidentCents, absCents)
                    FieldSlot.EMPLOYER_PROVIDENT -> {
                        acc.employerProvidentCents = max(acc.employerProvidentCents, absCents)
                        acc.hasExplicitEmployerProvident = true
                    }
                    FieldSlot.TOTAL_BILATERAL_PROVIDENT -> acc.totalBilateralProvidentCents = max(acc.totalBilateralProvidentCents, absCents)
                    FieldSlot.PERSONAL_SOCIAL_TOTAL -> acc.personalSocialTotalCents = max(acc.personalSocialTotalCents, absCents)
                    FieldSlot.PERSONAL_OTHER_DEDUCTION -> acc.otherPersonalDeductionCents += absCents
                    FieldSlot.NET_TAKE_HOME -> acc.explicitNetPayCents = max(acc.explicitNetPayCents, absCents)
                    FieldSlot.EMPLOYER_SOCIAL_IGNORED -> acc.ignoredEmployerSocialCents += absCents
                    else -> Unit
                }
            }
        }
    }

    private fun hasExplicitEmployerMarker(label: String): Boolean =
        EMPLOYER_PARTY_KEYWORDS.any { label.contains(it) }

    private fun hasExplicitPartyQualifier(label: String): Boolean =
        EMPLOYER_PARTY_KEYWORDS.any { label.contains(it) } ||
            PERSONAL_PARTY_KEYWORDS.any { label.contains(it) }

    private fun classifyPayslipField(rawLabel: String, sectionContext: SectionContext): FieldSlot {
        val clean = rawLabel
            .trim()
            .trim('"', '\'')
            .replace(Regex("""\s+"""), "")
            .lowercase(Locale.ROOT)
        if (clean.isEmpty()) return FieldSlot.IGNORED

        // 过滤基数与比例列（如「公积金缴存基数」「养老个人比例 8%」「工号」「序号」）
        if (NON_AMOUNT_META_KEYWORDS.any { clean.contains(it) }) {
            return FieldSlot.IGNORED
        }

        // 1. 发薪日期 / 薪资所属期
        if (PAYDAY_KEYWORDS.any { clean.contains(it) }) {
            return FieldSlot.PAYDAY
        }

        // 2. 发薪单位名称（注意必须排除「单位公积金」「单位养老」「单位缴纳」「单位社保」等金额项！）
        val mentionsInsuranceOrFund = clean.contains("公积金") ||
            clean.contains("养老") ||
            clean.contains("医疗") ||
            clean.contains("医保") ||
            clean.contains("失业") ||
            clean.contains("工伤") ||
            clean.contains("生育") ||
            clean.contains("社保") ||
            clean.contains("五险") ||
            clean.contains("配缴") ||
            clean.contains("缴纳") ||
            clean.contains("承担") ||
            clean.contains("部分") ||
            clean.contains("合计")
        if (!mentionsInsuranceOrFund &&
            (clean in EXACT_SHORT_EMPLOYER_HEADERS || EMPLOYER_NAME_KEYWORDS.any { clean == it || clean.contains(it) })
        ) {
            return FieldSlot.EMPLOYER_NAME
        }

        // 3. 医保个人账户划入（包括「医保个账划入」「划入个人医保账户」「单位划入个账」）
        if (MEDICAL_DEPOSIT_KEYWORDS.any { clean.contains(it) }) {
            return FieldSlot.MEDICAL_ACCOUNT_DEPOSIT
        }

        // 4. 判定是否属于「单位/企业配缴」口径
        val isEmployerScoped = hasExplicitEmployerMarker(clean) ||
            (sectionContext == SectionContext.EMPLOYER_CONTRIBUTION && !PERSONAL_PARTY_KEYWORDS.any { clean.contains(it) })

        if (isEmployerScoped) {
            return if (clean.contains("公积金")) {
                FieldSlot.EMPLOYER_PROVIDENT
            } else {
                // 单位养老 / 单位医疗 / 单位失业 / 单位工伤 / 单位生育 / 企业社保合计 -> 严格隔离，绝不计入个人扣款！
                FieldSlot.EMPLOYER_SOCIAL_IGNORED
            }
        }

        // 5. 实发到手工资
        if (NET_PAY_KEYWORDS.any { clean.contains(it) }) {
            return FieldSlot.NET_TAKE_HOME
        }

        // 6. 应发薪资总额
        if (GROSS_PAY_KEYWORDS.any { clean.contains(it) }) {
            return FieldSlot.GROSS_TOTAL
        }

        // 7. 个人所得税
        if (INCOME_TAX_KEYWORDS.any { clean.contains(it) }) {
            return FieldSlot.INCOME_TAX
        }

        // 8. 住房公积金（双边合计 vs 个人扣缴）
        if (clean.contains("公积金")) {
            return if (clean.contains("双边") || clean.contains("总计") || clean.contains("合计缴存") || clean.contains("月缴存额")) {
                FieldSlot.TOTAL_BILATERAL_PROVIDENT
            } else {
                FieldSlot.PERSONAL_PROVIDENT
            }
        }

        // 9. 医疗保险个人扣款（排除补充商业险公司部分）
        if (clean.contains("医疗") || clean.contains("医保") || clean.contains("大病")) {
            return FieldSlot.PERSONAL_MEDICAL_DEDUCTION
        }

        // 10. 养老与失业保险个人扣款
        if (clean.contains("养老") || clean.contains("失业")) {
            return FieldSlot.PERSONAL_PENSION_UNEMPLOYMENT
        }

        // 11. 未拆细的「个人社保合计 / 个人五险合计」
        if (clean.contains("社保") || clean.contains("五险")) {
            return FieldSlot.PERSONAL_SOCIAL_TOTAL
        }

        // 12. 其他明确的工资组成正项（未给应发总计时用于汇总应发）
        if (EARNING_COMPONENT_KEYWORDS.any { clean.contains(it) }) {
            return FieldSlot.EARNING_COMPONENT
        }

        // 13. 其他个人考勤/工会等扣款（用于推导实发参考值）
        if (OTHER_DEDUCTION_KEYWORDS.any { clean.contains(it) }) {
            return FieldSlot.PERSONAL_OTHER_DEDUCTION
        }

        return FieldSlot.IGNORED
    }

    private fun finalizeAccumulatorToResult(
        acc: MutablePayslipAccumulator,
        detectedFormat: String,
        normalizedText: String,
        defaultSettings: DedicatedFundSettings,
        nowMillis: Long,
        extraHints: List<String>
    ): Result<PayslipParseResult> {
        val hints = extraHints.toMutableList()

        val grossCents = if (acc.explicitGrossCents > 0L) {
            acc.explicitGrossCents
        } else {
            acc.earningComponentsSumCents
        }

        // 若工资条仅给出了「个人社保合计」而未拆出养老/医疗明细，将差额补充到养老失业扣款以便实发推导闭合
        var pensionAndUnempCents = acc.pensionAndUnemploymentCents
        if (acc.personalSocialTotalCents > 0L &&
            pensionAndUnempCents == 0L &&
            acc.personalSocialTotalCents > acc.personalMedicalCents
        ) {
            pensionAndUnempCents = (acc.personalSocialTotalCents - acc.personalMedicalCents).coerceAtLeast(0L)
        }

        // 公积金单位配缴规则（AC-B1 反例铁律：无单位公积金列时严禁将个人公积金盲目翻倍！）
        val personalProvidentCents = acc.personalProvidentCents
        var employerProvidentCents = acc.employerProvidentCents
        var employerFromPlanFallback = false
        if (!acc.hasExplicitEmployerProvident) {
            if (acc.totalBilateralProvidentCents > personalProvidentCents && personalProvidentCents > 0L) {
                employerProvidentCents = acc.totalBilateralProvidentCents - personalProvidentCents
            } else if (personalProvidentCents > 0L && defaultSettings.providentPlan.employerMonthlyCents > 0L) {
                employerProvidentCents = defaultSettings.providentPlan.employerMonthlyCents
                employerFromPlanFallback = true
                hints.add("工资条未单列单位公积金，已按专项账户预设带出单位月配缴额")
            } else {
                employerProvidentCents = 0L
            }
        }

        // 医保个账划入：若工资条显式列出了「医保个账划入」则优先采用；否则若专项配置有月划入额则采用配置，否则回退个人医保扣缴额
        val medicalDepositCents = when {
            acc.medicalAccountDepositCents > 0L -> acc.medicalAccountDepositCents
            defaultSettings.medicalPlan.totalMonthlyDepositCents > 0L && acc.personalMedicalCents > 0L ->
                defaultSettings.medicalPlan.totalMonthlyDepositCents
            else -> acc.personalMedicalCents
        }

        // 实发工资：优先采用显式实发；若仅给出应发与各项个人扣款，则自动推导实发参考值
        var isNetDerived = false
        val totalPersonalDeductions = acc.incomeTaxCents +
            pensionAndUnempCents +
            acc.personalMedicalCents +
            personalProvidentCents +
            acc.otherPersonalDeductionCents
        val netPayCents = when {
            acc.explicitNetPayCents > 0L -> acc.explicitNetPayCents
            grossCents > 0L && totalPersonalDeductions > 0L && grossCents > totalPersonalDeductions -> {
                isNetDerived = true
                hints.add("未检测到显式「实发工资」行，已按「应发 - 个人五险一金与个税」自动推导参考实发额")
                grossCents - totalPersonalDeductions
            }
            grossCents > 0L && totalPersonalDeductions == 0L -> {
                isNetDerived = true
                grossCents
            }
            else -> 0L
        }

        if (acc.ignoredEmployerSocialCents > 0L) {
            hints.add("已自动排除单位养老/医疗等企业社保成本，不计入个人扣款")
        }

        // AC-B1 反例红线：若核心入账金额全部 <= 0，拒绝生成 0.00 脏草稿！
        val hasAnyValidAmount = grossCents > 0L ||
            netPayCents > 0L ||
            medicalDepositCents > 0L ||
            personalProvidentCents > 0L ||
            employerProvidentCents > 0L
        if (!hasAnyValidAmount) {
            return Result.failure(
                IllegalArgumentException("未能提取到大于 0 的有效薪资或五险一金金额，请检查工资条内容")
            )
        }

        val resolvedPayday = acc.paydayMillis ?: nowMillis
        val draft = PayslipRecognitionDraft(
            grossSalaryCents = grossCents,
            incomeTaxCents = acc.incomeTaxCents,
            pensionAndUnemploymentDeductionCents = pensionAndUnempCents,
            personalMedicalDeductionCents = acc.personalMedicalCents,
            medicalPersonalAccountDepositCents = medicalDepositCents,
            personalProvidentDeductionCents = personalProvidentCents,
            employerProvidentMatchCents = employerProvidentCents,
            netTakeHomePayCents = netPayCents,
            paydayTimestampMillis = resolvedPayday,
            employerName = acc.employerName
        )

        return Result.success(
            PayslipParseResult(
                draft = draft,
                detectedFormat = detectedFormat,
                normalizedText = normalizedText,
                isNetPayDerived = isNetDerived,
                isEmployerProvidentFromPlanFallback = employerFromPlanFallback,
                ignoredEmployerSocialInsuranceCents = acc.ignoredEmployerSocialCents,
                hints = hints
            )
        )
    }

    /**
     * 可选云端 AI 结构化二次校准（仅在用户已配置 API Key 或本地免密网关且主动点击「AI 智能校准」时调用）
     */
    suspend fun calibrateWithCloudAi(
        rawText: String,
        config: AiConfig,
        defaultSettings: DedicatedFundSettings = DedicatedFundSettings(),
        nowMillis: Long = System.currentTimeMillis()
    ): Result<PayslipParseResult> = withContext(Dispatchers.IO) {
        if (rawText.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("暂无可校准的工资条原始文本"))
        }
        if (config.apiKey.isBlank() && !config.allowsEmptyApiKey()) {
            return@withContext Result.failure(IllegalArgumentException("尚未配置云端 AI API Key，当前可直接使用端侧离线解析结果"))
        }
        if (config.modelName.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("请先在智能记账设置中填写模型名称"))
        }

        val systemPrompt = """
            你是专业的中国企业薪资单与五险一金结构化提取引擎。请从用户提供的工资条 OCR 或表格文本中提取各项金额（单位：人民币元，一律取正数），严格输出 JSON 对象：
            {
              "isValidPayslip": true,
              "grossSalaryYuan": 20000.00,
              "incomeTaxYuan": 590.00,
              "pensionAndUnemploymentDeductionYuan": 1700.00,
              "personalMedicalDeductionYuan": 400.00,
              "medicalPersonalAccountDepositYuan": 400.00,
              "personalProvidentDeductionYuan": 1200.00,
              "employerProvidentMatchYuan": 1200.00,
              "hasExplicitEmployerProvident": true,
              "netTakeHomePayYuan": 16110.00,
              "paydayDate": "2026-11-15",
              "employerName": "公司名称"
            }
            铁律：
            1. 严禁把「单位养老/单位医疗/单位失业/单位工伤/企业社保」计入个人扣款字段；
            2. 若原文未明确列出「单位公积金」，必须将 `employerProvidentMatchYuan` 设为 0 且 `hasExplicitEmployerProvident` 设为 false，严禁把个人公积金翻倍冒充单位配缴；
            3. 若内容根本不是工资条/薪资单，返回 {"isValidPayslip": false}。
        """.trimIndent()

        val dto = runCatching {
            when (config.provider) {
                AiProviderType.OPENAI_COMPATIBLE -> callOpenAiForPayslip(config, systemPrompt, rawText)
                AiProviderType.GEMINI_OFFICIAL -> callGeminiForPayslip(config, systemPrompt, rawText)
            }
        }.getOrElse { err ->
            return@withContext Result.failure(
                IllegalArgumentException("云端 AI 校准请求失败：${err.message ?: err.javaClass.simpleName}")
            )
        } ?: return@withContext Result.failure(IllegalArgumentException("云端 AI 未返回有效结果"))

        if (!dto.isValidPayslip) {
            return@withContext Result.failure(IllegalArgumentException("云端 AI 判定该内容不是有效的工资条记录"))
        }

        fun yuanToCents(v: Double): Long =
            if (v.isFinite() && v > 0.0 && v <= 10_000_000.0) (v * 100.0).roundToLong() else 0L

        val acc = MutablePayslipAccumulator(
            paydayMillis = parsePayslipDateMillis(dto.paydayDate, defaultSettings.providentPlan.depositDayOfMonth, nowMillis),
            employerName = dto.employerName.trim().take(48),
            explicitGrossCents = yuanToCents(dto.grossSalaryYuan),
            incomeTaxCents = yuanToCents(dto.incomeTaxYuan),
            pensionAndUnemploymentCents = yuanToCents(dto.pensionAndUnemploymentDeductionYuan),
            personalMedicalCents = yuanToCents(dto.personalMedicalDeductionYuan),
            medicalAccountDepositCents = yuanToCents(dto.medicalPersonalAccountDepositYuan),
            personalProvidentCents = yuanToCents(dto.personalProvidentDeductionYuan),
            employerProvidentCents = if (dto.hasExplicitEmployerProvident) yuanToCents(dto.employerProvidentMatchYuan) else 0L,
            hasExplicitEmployerProvident = dto.hasExplicitEmployerProvident && dto.employerProvidentMatchYuan > 0.0,
            explicitNetPayCents = yuanToCents(dto.netTakeHomePayYuan),
            matchedSalaryFieldCount = 3
        )
        finalizeAccumulatorToResult(
            acc = acc,
            detectedFormat = "云端 AI 结构化校准",
            normalizedText = rawText,
            defaultSettings = defaultSettings,
            nowMillis = nowMillis,
            extraHints = listOf("已完成云端 AI 二次校准，请核对下方金额后入账")
        )
    }

    private fun callOpenAiForPayslip(
        config: AiConfig,
        systemPrompt: String,
        rawText: String
    ): CloudPayslipCalibrationDto? {
        val endpoint = config.baseUrl.trimEnd('/') + "/chat/completions"
        val requestJson = buildJsonObject {
            put("model", config.modelName)
            put("temperature", 0.1)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", rawText.take(6000))
                    })
                }
            )
        }
        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (config.apiKey.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer ${config.apiKey}")
        }
        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val root = jsonCodec.parseToJsonElement(body).jsonObject
            val content = root["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: return null
            return decodeCleanPayslipDto(content)
        }
    }

    private fun callGeminiForPayslip(
        config: AiConfig,
        systemPrompt: String,
        rawText: String
    ): CloudPayslipCalibrationDto? {
        val base = config.baseUrl.trimEnd('/').ifBlank { "https://generativelanguage.googleapis.com/v1beta" }
        val safeModel = java.net.URLEncoder.encode(config.modelName.trim(), "UTF-8")
        val endpoint = "$base/models/$safeModel:generateContent"
        val requestJson = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", systemPrompt) })
                })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", rawText.take(6000)) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", 0.1)
                put("responseMimeType", "application/json")
            })
        }
        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (config.apiKey.isNotBlank()) {
            reqBuilder.addHeader("x-goog-api-key", config.apiKey)
        }
        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val root = jsonCodec.parseToJsonElement(body).jsonObject
            val content = root["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: return null
            return decodeCleanPayslipDto(content)
        }
    }

    private fun decodeCleanPayslipDto(rawContent: String): CloudPayslipCalibrationDto? {
        val stripped = rawContent
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = stripped.indexOf('{')
        val end = stripped.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            jsonCodec.decodeFromString<CloudPayslipCalibrationDto>(stripped.substring(start, end + 1))
        }.getOrNull()
    }

    internal fun parseSignedMoneyCents(rawToken: String): Long? {
        val trimmed = rawToken
            .trim()
            .trim('"', '\'')
            .replace("人民币", "")
            .replace("RMB", "", ignoreCase = true)
            .replace("CNY", "", ignoreCase = true)
            .replace("元", "")
            .replace("¥", "")
            .replace("￥", "")
            .replace(",", "")
            .replace("，", "")
            .replace("−", "-")
            .trim()
        if (trimmed.isEmpty() || trimmed.endsWith("%")) return null
        // 排除 6~8 位纯整数日期（如 202611 或 20261115）被误当成几十万金额
        if (!trimmed.contains('.') && trimmed.length in 6..8 && (trimmed.startsWith("202") || trimmed.startsWith("203"))) {
            return null
        }
        val m = STRICT_NUMBER_REGEX.matchEntire(trimmed) ?: return null
        val value = m.value.toDoubleOrNull() ?: return null
        if (!value.isFinite() || abs(value) > 10_000_000.0) return null
        return (value * 100.0).roundToLong()
    }

    fun parsePayslipDateMillis(
        rawDate: String,
        defaultDayOfMonth: Int = 15,
        fallbackNowMillis: Long = System.currentTimeMillis()
    ): Long? {
        val s = rawDate.trim().trim('"', '\'')
        if (s.isEmpty()) return null

        // 1. 完整年月日：2026-11-15 / 2026/11/15 / 2026.11.15 / 2026年11月15日
        FULL_DATE_REGEX.find(s)?.let { m ->
            val year = m.groupValues[1].toIntOrNull() ?: return@let
            val month = m.groupValues[2].toIntOrNull() ?: return@let
            val day = m.groupValues[3].toIntOrNull() ?: return@let
            if (year in 2000..2100 && month in 1..12 && day in 1..31) {
                return buildNoonCalendarMillis(year, month, day)
            }
        }

        // 2. 紧凑 8 位日期：20261115
        COMPACT_FULL_DATE_REGEX.matchEntire(s)?.let { m ->
            val year = m.groupValues[1].toIntOrNull() ?: return@let
            val month = m.groupValues[2].toIntOrNull() ?: return@let
            val day = m.groupValues[3].toIntOrNull() ?: return@let
            if (year in 2000..2100 && month in 1..12 && day in 1..31) {
                return buildNoonCalendarMillis(year, month, day)
            }
        }

        // 3. 仅年月：2026-11 / 2026年11月 / 202611
        YEAR_MONTH_REGEX.find(s)?.let { m ->
            val year = m.groupValues[1].toIntOrNull() ?: return@let
            val month = m.groupValues[2].toIntOrNull() ?: return@let
            if (year in 2000..2100 && month in 1..12) {
                return buildNoonCalendarMillis(year, month, defaultDayOfMonth.coerceIn(1, 28))
            }
        }

        // 4. 仅月日：11月15日（取 fallbackNow 所在年份）
        MONTH_DAY_CN_REGEX.find(s)?.let { m ->
            val fallbackCal = Calendar.getInstance().apply { timeInMillis = fallbackNowMillis }
            val year = fallbackCal.get(Calendar.YEAR)
            val month = m.groupValues[1].toIntOrNull() ?: return@let
            val day = m.groupValues[2].toIntOrNull() ?: return@let
            if (month in 1..12 && day in 1..31) {
                return buildNoonCalendarMillis(year, month, day)
            }
        }

        return null
    }

    fun formatDateYmd(timestampMillis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return fmt.format(Date(timestampMillis))
    }

    private fun buildNoonCalendarMillis(year: Int, month1Based: Int, day: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, (month1Based - 1).coerceIn(0, 11))
        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        cal.set(Calendar.DAY_OF_MONTH, day.coerceIn(1, maxDay))
        cal.set(Calendar.HOUR_OF_DAY, 12)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private val STRICT_NUMBER_REGEX = Regex("""^[+-]?\d{1,8}(?:\.\d{1,2})?$""")
    private val UNQUOTED_THOUSANDS_COMMA_REGEX = Regex("""((?:^|[\s:：¥￥+-−])\d{1,3}),(\d{3}(?:\.\d{1,2})?(?=\s*元?(?:\s|$)))""")
    private val TRAILING_TRUNCATED_NUMBER_IN_LABEL_REGEX = Regex("""[\s:：¥￥+-−]\d{1,3}$""")
    private val EXACT_SHORT_EMPLOYER_HEADERS = setOf("公司", "单位", "企业", "雇主")
    private val DATE_IN_LINE_REGEX = Regex("""20\d{2}\s*[-/.年]\s*\d{1,2}(?:\s*[-/.月]\s*\d{1,2}\s*日?)?""")
    private val FULL_DATE_REGEX = Regex("""(20\d{2})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})\s*日?""")
    private val COMPACT_FULL_DATE_REGEX = Regex("""^(20\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])$""")
    private val YEAR_MONTH_REGEX = Regex("""(20\d{2})\s*(?:[-/.年]\s*)?(0[1-9]|1[0-2])(?:\s*月)?(?!\d)""")
    private val MONTH_DAY_CN_REGEX = Regex("""(\d{1,2})\s*月\s*(\d{1,2})\s*日""")

    private val INLINE_AMOUNT_TOKEN_REGEX = Regex("""[+-−]?\s*[¥￥]?\s*\d{1,3}(?:,\d{3})*(?:\.\d{1,2})?""")
    private val INLINE_LABEL_AMOUNT_PAIR_REGEX = Regex(
        """([^\s\d¥￥+-−,;:：|｜"'][^\t,;:：|｜¥￥"]*?)[\t ,;:：|｜]+\s*"?([+-−]?\s*[¥￥]?\s*(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,2})?\s*元?)"?(?!%)"""
    )

    private val SUMMARY_ROW_KEYWORDS = listOf("合计", "总计", "年度合计", "汇总", "小计", "平均", "月均")

    private val EMPLOYER_PARTY_KEYWORDS = listOf(
        "单位", "公司", "企业", "雇主", "用人单位", "统筹部分", "单位部分", "公司部分", "企业部分", "单位缴", "公司缴", "企业缴", "单位承担", "企业承担"
    )
    private val PERSONAL_PARTY_KEYWORDS = listOf(
        "个人", "员工", "本人", "代扣", "个人部分", "个人扣"
    )
    private val EMPLOYER_SECTION_TITLES = listOf(
        "单位缴纳", "单位部分", "企业缴纳", "企业承担", "公司缴纳", "单位五险一金", "雇主承担"
    )
    private val PERSONAL_SECTION_TITLES = listOf(
        "个人扣款", "个人缴纳", "个人代扣", "员工扣款", "个人部分", "五险一金个人"
    )

    private val NON_AMOUNT_META_KEYWORDS = listOf(
        "基数", "比例", "费率", "工号", "员工编号", "身份证", "银行卡号", "账号", "序号", "出勤天数", "计薪天数"
    )
    private val PAYDAY_KEYWORDS = listOf(
        "发薪日", "发放日", "发薪月", "薪资月", "所属期", "计薪月", "工资月", "年月", "日期", "月份"
    )
    private val EMPLOYER_NAME_KEYWORDS = listOf(
        "发薪单位", "单位名称", "公司名称", "企业名称", "所属公司", "所属单位", "用人单位", "任职公司"
    )
    private val MEDICAL_DEPOSIT_KEYWORDS = listOf(
        "个账划入", "医保划入", "划入个账", "划入个人", "医保个账", "个人医保划入", "个人账户划入", "医保返还", "医疗返还"
    )
    private val NET_PAY_KEYWORDS = listOf(
        "实发", "到手", "打卡工资", "银行代发", "代发金额", "实际发放", "净发"
    )
    private val GROSS_PAY_KEYWORDS = listOf(
        "应发", "税前工资", "税前合计", "税前总计", "薪资总额", "工资总额", "收入合计"
    )
    private val INCOME_TAX_KEYWORDS = listOf(
        "个人所得税", "个税", "代扣个税", "预扣个税", "薪资个税", "扣税"
    )
    private val EARNING_COMPONENT_KEYWORDS = listOf(
        "基本工资", "岗位工资", "绩效", "津贴", "补贴", "全勤", "加班费", "提成", "奖金", "餐补", "交通补", "通讯补"
    )
    private val OTHER_DEDUCTION_KEYWORDS = listOf(
        "迟到", "早退", "事假", "病假", "旷工", "工会", "其他扣款", "考勤扣款", "餐费扣款", "住宿扣款"
    )
}
