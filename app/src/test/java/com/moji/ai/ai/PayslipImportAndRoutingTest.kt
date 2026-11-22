package com.moji.ai.ai

import android.content.SharedPreferences
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.DedicatedFundManager
import com.moji.ai.data.model.DedicatedFundPlanConfig
import com.moji.ai.data.model.DedicatedFundSettings
import com.moji.ai.data.model.MedicalExpenseInsightsCalculator
import com.moji.ai.data.model.PayslipRecognitionDraft
import com.moji.ai.data.model.TagCatalog
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType
import com.moji.ai.data.repository.LedgerRepository
import com.moji.ai.data.repository.LedgerTransactionStore
import com.moji.ai.data.repository.PayslipCommitOutcome
import com.moji.ai.data.repository.PayslipCommitRequest
import java.nio.charset.Charset
import java.util.Calendar
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PayslipImportAndRoutingTest {

    private class FakeSharedPreferences : SharedPreferences {
        private val data = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)
        override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val staged = LinkedHashMap<String, Any?>()
            private val removals = HashSet<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = values?.toSet()
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) removals.add(key)
            }

            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) data.clear()
                removals.forEach { data.remove(it) }
                for ((k, v) in staged) {
                    if (v == null) data.remove(k) else data[k] = v
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }

    private class InMemoryPayslipStore(initialWallets: List<WalletEntity>) : LedgerTransactionStore {
        private val wallets = LinkedHashMap<Long, WalletEntity>().apply {
            initialWallets.forEach { put(it.id, it) }
        }
        private val transactions = LinkedHashMap<Long, TransactionEntity>()
        private var nextWalletId = (initialWallets.maxOfOrNull { it.id } ?: 0L) + 1L
        private var nextTxId = 100L

        override suspend fun getWalletById(id: Long): WalletEntity? = wallets[id]
        override suspend fun getAllWallets(): List<WalletEntity> = wallets.values.toList()

        override suspend fun insertWallet(wallet: WalletEntity): Long {
            val assignedId = if (wallet.id > 0L) wallet.id else nextWalletId++
            wallets[assignedId] = wallet.copy(id = assignedId)
            return assignedId
        }

        override suspend fun updateWallet(wallet: WalletEntity) {
            wallets[wallet.id] = wallet
        }

        override suspend fun getTransactionById(id: Long): TransactionEntity? = transactions[id]
        override suspend fun getAllTransactions(): List<TransactionEntity> =
            transactions.values.sortedByDescending { it.timestamp }

        override suspend fun findTransactionByOrderKey(orderKey: String): TransactionEntity? =
            if (orderKey.isBlank()) null else transactions.values.firstOrNull { it.orderKey == orderKey }

        override suspend fun getSplitChildrenOfExpense(parentId: Long): List<TransactionEntity> =
            transactions.values.filter { it.type == TransactionType.EXPENSE && it.relatedTransactionId == parentId }

        override suspend fun getRecentRefundableExpenses(limit: Int): List<TransactionEntity> =
            transactions.values.filter { it.type == TransactionType.EXPENSE }.take(limit)

        override suspend fun insertTransaction(tx: TransactionEntity): Long {
            val assignedId = if (tx.id > 0L) tx.id else nextTxId++
            transactions[assignedId] = tx.copy(id = assignedId)
            return assignedId
        }

        override suspend fun updateTransaction(tx: TransactionEntity) {
            transactions[tx.id] = tx
        }

        override suspend fun deleteTransaction(tx: TransactionEntity) {
            transactions.remove(tx.id)
        }
    }

    private lateinit var cmbPayrollWallet: WalletEntity

    @Before
    fun setUp() {
        DedicatedFundManager.resetForTesting()
        TagCatalog.initWithPrefs(FakeSharedPreferences())
        cmbPayrollWallet = WalletEntity(
            id = 1L,
            name = "招商银行工资卡",
            type = WalletType.DEBIT_BANK,
            subTypeLabel = "储蓄卡",
            brandCode = "CMB",
            cardLast4 = "8848",
            balanceCents = 10_000_00L,
            includeInNetWorth = true
        )
    }

    @After
    fun tearDown() {
        DedicatedFundManager.resetForTesting()
    }

    @Test
    fun `AC-B1 positive - parses UTF-8 BOM and GBK encoded horizontal CSV and TSV payslips`() {
        val utf8BomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            PayslipImportEngine.SAMPLE_HORIZONTAL_CSV.toByteArray(Charsets.UTF_8)
        val decodedUtf8 = PayslipImportEngine.decodePayslipBytes(utf8BomBytes, "salary_202611.csv")
        assertTrue(decodedUtf8.isSuccess)

        val parsedUtf8 = PayslipImportEngine.parsePayslipText(decodedUtf8.getOrThrow()).getOrThrow()
        assertEquals(20_000_00L, parsedUtf8.draft.grossSalaryCents)
        assertEquals(1_700_00L, parsedUtf8.draft.pensionAndUnemploymentDeductionCents)
        assertEquals(400_00L, parsedUtf8.draft.personalMedicalDeductionCents)
        assertEquals(400_00L, parsedUtf8.draft.medicalPersonalAccountDepositCents)
        assertEquals(1_200_00L, parsedUtf8.draft.personalProvidentDeductionCents)
        assertEquals(1_200_00L, parsedUtf8.draft.employerProvidentMatchCents)
        assertEquals(2_400_00L, parsedUtf8.draft.bilateralProvidentDepositCents)
        assertEquals(590_00L, parsedUtf8.draft.incomeTaxCents)
        assertEquals(16_110_00L, parsedUtf8.draft.netTakeHomePayCents)
        assertEquals("星辰智造科技有限公司", parsedUtf8.draft.employerName)
        assertEquals("202611", DedicatedFundManager.formatPayslipYearMonth(parsedUtf8.draft.paydayTimestampMillis))
        // 单位养老 3200 + 单位医疗 1600 应被自动隔离忽略，不计入个人扣款
        assertEquals(4_800_00L, parsedUtf8.ignoredEmployerSocialInsuranceCents)

        // GBK 编码 TSV 测试（含双行分组表头：第 1 行区分个人扣款与单位缴纳）
        val gbkTsv = """
            发薪月份	单位名称	收入合计	个人扣款	个人扣款	个人扣款	单位缴纳	单位缴纳	扣税	实发金额
            发薪日	公司	应发合计	养老+失业	医疗保险	住房公积金	医疗保险	住房公积金	个税	打卡工资
            2026/10/25	远航软件股份	25000.00	-2125.00	-500.00	-1500.00	2000.00	1500.00	-890.00	19985.00
        """.trimIndent()
        val gbkBytes = gbkTsv.toByteArray(Charset.forName("GBK"))
        val decodedGbk = PayslipImportEngine.decodePayslipBytes(gbkBytes, "oct_payslip.tsv")
        assertTrue(decodedGbk.isSuccess)

        val parsedGbk = PayslipImportEngine.parsePayslipText(decodedGbk.getOrThrow()).getOrThrow()
        assertEquals(25_000_00L, parsedGbk.draft.grossSalaryCents)
        assertEquals(2_125_00L, parsedGbk.draft.pensionAndUnemploymentDeductionCents)
        assertEquals(500_00L, parsedGbk.draft.personalMedicalDeductionCents)
        assertEquals(500_00L, parsedGbk.draft.medicalPersonalAccountDepositCents)
        assertEquals(1_500_00L, parsedGbk.draft.personalProvidentDeductionCents)
        assertEquals(1_500_00L, parsedGbk.draft.employerProvidentMatchCents)
        assertEquals(3_000_00L, parsedGbk.draft.bilateralProvidentDepositCents)
        assertEquals(890_00L, parsedGbk.draft.incomeTaxCents)
        assertEquals(19_985_00L, parsedGbk.draft.netTakeHomePayCents)
        assertEquals("远航软件股份", parsedGbk.draft.employerName)
        assertEquals("202610", DedicatedFundManager.formatPayslipYearMonth(parsedGbk.draft.paydayTimestampMillis))
    }

    @Test
    fun `AC-B1 positive - parses vertical payslip and derives net take-home pay when omitted`() {
        val verticalWithoutExplicitNet = """
            发薪单位：默记数科实验室
            薪资期间：2026年11月
            应发工资：18,000.00
            个人养老保险：-1,440.00
            个人失业保险：-90.00
            个人医疗保险：-360.00
            医保个人账户划入：420.00
            个人公积金：-1,080.00
            单位公积金：1,080.00
            个人所得税：-310.00
        """.trimIndent()

        val result = PayslipImportEngine.parsePayslipText(verticalWithoutExplicitNet).getOrThrow()
        assertTrue(result.isNetPayDerived)
        assertEquals(18_000_00L, result.draft.grossSalaryCents)
        assertEquals(1_530_00L, result.draft.pensionAndUnemploymentDeductionCents)
        assertEquals(360_00L, result.draft.personalMedicalDeductionCents)
        assertEquals(420_00L, result.draft.medicalPersonalAccountDepositCents)
        assertEquals(1_080_00L, result.draft.personalProvidentDeductionCents)
        assertEquals(1_080_00L, result.draft.employerProvidentMatchCents)
        assertEquals(310_00L, result.draft.incomeTaxCents)
        // 18000 - (1440 + 90 + 360 + 1080 + 310) = 14720.00
        assertEquals(14_720_00L, result.draft.netTakeHomePayCents)
    }

    @Test
    fun `AC-B1 negative - isolates employer social insurance, never doubles provident blindly, and rejects non-payslip text`() {
        val textWithoutEmployerProvident = """
            应发工资 15000.00
            【个人扣缴明细】
            养老保险 1200.00
            医疗保险 300.00
            住房公积金 900.00
            个人所得税 210.00
            【单位缴纳部分】
            养老保险 2400.00
            医疗保险 1200.00
            工伤生育保险 150.00
            实发工资 12390.00
        """.trimIndent()

        // 未配置公积金计划时：缺失单位公积金列，绝不能把个人 900 盲目翻倍成单位 900
        val parsedNoPlan = PayslipImportEngine.parsePayslipText(
            rawText = textWithoutEmployerProvident,
            defaultSettings = DedicatedFundSettings()
        ).getOrThrow()
        assertEquals(1_200_00L, parsedNoPlan.draft.pensionAndUnemploymentDeductionCents)
        assertEquals(300_00L, parsedNoPlan.draft.personalMedicalDeductionCents)
        assertEquals(900_00L, parsedNoPlan.draft.personalProvidentDeductionCents)
        assertEquals(0L, parsedNoPlan.draft.employerProvidentMatchCents)
        assertEquals(900_00L, parsedNoPlan.draft.bilateralProvidentDepositCents)
        assertFalse(parsedNoPlan.isEmployerProvidentFromPlanFallback)
        assertEquals(3_750_00L, parsedNoPlan.ignoredEmployerSocialInsuranceCents)

        // 配置了公积金月度单位参考额时：缺失单位公积金列允许回退已配置的 employerMonthlyCents
        val configuredSettings = DedicatedFundSettings(
            providentPlan = DedicatedFundPlanConfig(
                enabled = true,
                employeeMonthlyCents = 900_00L,
                employerMonthlyCents = 900_00L
            )
        )
        val parsedWithPlan = PayslipImportEngine.parsePayslipText(
            rawText = textWithoutEmployerProvident,
            defaultSettings = configuredSettings
        ).getOrThrow()
        assertEquals(900_00L, parsedWithPlan.draft.personalProvidentDeductionCents)
        assertEquals(900_00L, parsedWithPlan.draft.employerProvidentMatchCents)
        assertEquals(1_800_00L, parsedWithPlan.draft.bilateralProvidentDepositCents)
        assertTrue(parsedWithPlan.isEmployerProvidentFromPlanFallback)

        // 反例：空文本、日常餐饮账单、全 0 废表均应拒绝，禁止生成 0.00 脏草稿
        assertTrue(PayslipImportEngine.parsePayslipText("   \n\t ").isFailure)
        assertTrue(
            PayslipImportEngine.parsePayslipText(
                "瑞幸咖啡（科技园店）\n生椰拿铁 x1\n实付款 ¥9.90\n支付方式：招商银行储蓄卡(8848)"
            ).isFailure
        )
        assertTrue(
            PayslipImportEngine.parsePayslipText(
                "应发工资,个人所得税,实发工资\n0.00,0.00,0.00"
            ).isFailure
        )
    }

    @Test
    fun `AC-B2 positive - clusters out-of-order two-column OCR boxes by centerY and guards large image downsampling`() {
        // 模拟端侧 OCR 按左栏整块、右栏整块乱序吐出文本框，且左右列存在轻微 Y 轴倾斜抖动
        val scrambledBoxes = listOf(
            OcrTextBox("¥20,000.00", left = 620f, top = 163f, right = 880f, bottom = 195f),
            OcrTextBox("-¥1,600.00", left = 620f, top = 218f, right = 880f, bottom = 250f),
            OcrTextBox("-¥400.00", left = 620f, top = 274f, right = 880f, bottom = 306f),
            OcrTextBox("-¥1,200.00", left = 620f, top = 330f, right = 880f, bottom = 362f),
            OcrTextBox("¥1,200.00", left = 620f, top = 386f, right = 880f, bottom = 418f),
            OcrTextBox("-¥590.00", left = 620f, top = 442f, right = 880f, bottom = 474f),
            OcrTextBox("¥16,210.00", left = 620f, top = 498f, right = 880f, bottom = 530f),
            OcrTextBox("星辰智造科技有限公司", left = 60f, top = 98f, right = 520f, bottom = 132f),
            OcrTextBox("2026-11-15", left = 620f, top = 100f, right = 860f, bottom = 130f),
            OcrTextBox("应发工资", left = 60f, top = 160f, right = 280f, bottom = 192f),
            OcrTextBox("个人养老保险", left = 60f, top = 216f, right = 320f, bottom = 248f),
            OcrTextBox("个人医疗保险", left = 60f, top = 272f, right = 320f, bottom = 304f),
            OcrTextBox("住房公积金(个人)", left = 60f, top = 328f, right = 380f, bottom = 360f),
            OcrTextBox("住房公积金(单位)", left = 60f, top = 384f, right = 380f, bottom = 416f),
            OcrTextBox("个人所得税", left = 60f, top = 440f, right = 300f, bottom = 472f),
            OcrTextBox("实发工资", left = 60f, top = 496f, right = 280f, bottom = 528f)
        )

        val clusteredRows = PayslipImportEngine.clusterOcrBlocksIntoRows(scrambledBoxes)
        assertEquals(8, clusteredRows.size)
        assertEquals("应发工资\t¥20,000.00", clusteredRows[1])
        assertEquals("实发工资\t¥16,210.00", clusteredRows[7])

        val parsedFromOcr = PayslipImportEngine.parsePayslipText(clusteredRows.joinToString("\n")).getOrThrow()
        assertEquals(20_000_00L, parsedFromOcr.draft.grossSalaryCents)
        assertEquals(400_00L, parsedFromOcr.draft.medicalPersonalAccountDepositCents)
        assertEquals(2_400_00L, parsedFromOcr.draft.bilateralProvidentDepositCents)
        assertEquals(16_210_00L, parsedFromOcr.draft.netTakeHomePayCents)

        // 紧邻上下两行同列文字（X 轴大面积重叠）不得误合并为同一行
        val tightVerticalBoxes = listOf(
            OcrTextBox("应发工资 10000.00", left = 60f, top = 100f, right = 420f, bottom = 130f),
            OcrTextBox("实发工资 8500.00", left = 62f, top = 114f, right = 418f, bottom = 144f)
        )
        assertEquals(2, PayslipImportEngine.clusterOcrBlocksIntoRows(tightVerticalBoxes).size)

        // 大图安全降采样防 OOM 校验
        assertEquals(1, PayslipImportEngine.calculateInSampleSize(1080, 2400))
        val sample4k = PayslipImportEngine.calculateInSampleSize(4000, 6000)
        assertTrue(sample4k >= 2)
        assertTrue((4000L / sample4k) * (6000L / sample4k) <= 6_000_000L)
        val sampleLongShot = PayslipImportEngine.calculateInSampleSize(1440, 12800)
        assertTrue(sampleLongShot >= 4)
        assertTrue((12800 / sampleLongShot) <= 4096)
    }

    @Test
    fun `AC-B2 regression - image stream decode pipeline succeeds when bounds pass returns null and handles invalid or missing streams`() {
        data class FakeDecodedImage(
            val width: Int,
            val height: Int,
            val sampleSize: Int,
            val rotatedDegrees: Int = 0
        )

        fun buildFakePngHeaderBytes(width: Int, height: Int): ByteArray {
            val buf = java.nio.ByteBuffer.allocate(24)
            // PNG 8-byte signature
            buf.put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            // IHDR length (13) + "IHDR"
            buf.putInt(13)
            buf.put("IHDR".toByteArray(Charsets.US_ASCII))
            buf.putInt(width)
            buf.putInt(height)
            return buf.array()
        }

        fun createSimulatingAndroidAdapter(exifRotation: Int = 0) =
            object : PayslipImportEngine.ImageStreamDecodeAdapter<FakeDecodedImage> {
                override fun decodeBounds(stream: java.io.InputStream): Pair<Int, Int> {
                    val header = stream.readBytes()
                    // 模拟 Android BitmapFactory.decodeStream(..., inJustDecodeBounds = true)：
                    // 合法 PNG 仅解析出 outWidth / outHeight，非法流返回 -1 to -1
                    val isPng = header.size >= 24 &&
                        header[0] == 0x89.toByte() &&
                        header[1] == 0x50.toByte() &&
                        header[2] == 0x4E.toByte() &&
                        header[3] == 0x47.toByte()
                    if (!isPng) return -1 to -1
                    val bb = java.nio.ByteBuffer.wrap(header)
                    return bb.getInt(16) to bb.getInt(20)
                }

                override fun readExifRotationDegrees(stream: java.io.InputStream): Int {
                    stream.readBytes()
                    return exifRotation
                }

                override fun decodeSampled(stream: java.io.InputStream, inSampleSize: Int): FakeDecodedImage? {
                    val (w, h) = decodeBounds(stream)
                    if (w <= 0 || h <= 0) return null
                    return FakeDecodedImage(
                        width = (w / inSampleSize).coerceAtLeast(1),
                        height = (h / inSampleSize).coerceAtLeast(1),
                        sampleSize = inSampleSize
                    )
                }

                override fun rotateIfNeeded(image: FakeDecodedImage, degrees: Int): FakeDecodedImage {
                    return if (degrees == 90 || degrees == 270) {
                        image.copy(width = image.height, height = image.width, rotatedDegrees = degrees)
                    } else {
                        image.copy(rotatedDegrees = degrees)
                    }
                }
            }

        // 1. 正常清晰工资条 PNG 截图（1200x2600）：必须成功返回图像，绝不误报「无法读取所选图片文件」
        val validPngBytes = buildFakePngHeaderBytes(1200, 2600)
        val normalDecoded = PayslipImportEngine.decodeDownsampledImageWithAdapter(
            openStream = { java.io.ByteArrayInputStream(validPngBytes) },
            adapter = createSimulatingAndroidAdapter(exifRotation = 0)
        )
        assertTrue(normalDecoded.isSuccess)
        assertEquals(1, normalDecoded.getOrThrow().sampleSize)
        assertEquals(1200, normalDecoded.getOrThrow().width)
        assertEquals(2600, normalDecoded.getOrThrow().height)

        // 2. 4K 原图（4000x6000）+ EXIF 90° 旋转：自动降采样 inSampleSize >= 2 且完成宽高旋转
        val largePngBytes = buildFakePngHeaderBytes(4000, 6000)
        val rotatedDecoded = PayslipImportEngine.decodeDownsampledImageWithAdapter(
            openStream = { java.io.ByteArrayInputStream(largePngBytes) },
            adapter = createSimulatingAndroidAdapter(exifRotation = 90)
        ).getOrThrow()
        assertTrue(rotatedDecoded.sampleSize >= 2)
        assertEquals(90, rotatedDecoded.rotatedDegrees)
        assertEquals(6000 / rotatedDecoded.sampleSize, rotatedDecoded.width)
        assertEquals(4000 / rotatedDecoded.sampleSize, rotatedDecoded.height)

        // 3. ContentResolver.openInputStream(uri) 返回 null：报「无法读取所选图片文件」
        val missingStreamResult = PayslipImportEngine.decodeDownsampledImageWithAdapter(
            openStream = { null },
            adapter = createSimulatingAndroidAdapter()
        )
        assertTrue(missingStreamResult.isFailure)
        assertTrue(missingStreamResult.exceptionOrNull()!!.message!!.contains("无法读取所选图片文件"))

        // 4. 选择了非图片乱码文件（outWidth <= 0）：报「所选文件不是有效的图片格式」
        val invalidFileResult = PayslipImportEngine.decodeDownsampledImageWithAdapter(
            openStream = { java.io.ByteArrayInputStream("not-an-image".toByteArray()) },
            adapter = createSimulatingAndroidAdapter()
        )
        assertTrue(invalidFileResult.isFailure)
        assertTrue(invalidFileResult.exceptionOrNull()!!.message!!.contains("有效的图片格式"))
    }

    @Test
    fun `AC-B3 and AC-B4 positive - auto-creates missing dedicated wallets with includeInNetWorth false, records 3-way atomically, and cascades main undo`() = runBlocking {
        // 初始仅有一张代发工资卡（无医保卡、无公积金钱包）
        val store = InMemoryPayslipStore(listOf(cmbPayrollWallet))
        val paydayMillis = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 15, 10, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val draft = PayslipRecognitionDraft(
            grossSalaryCents = 20_000_00L,
            incomeTaxCents = 590_00L,
            pensionAndUnemploymentDeductionCents = 1_700_00L,
            personalMedicalDeductionCents = 400_00L,
            medicalPersonalAccountDepositCents = 400_00L,
            personalProvidentDeductionCents = 1_200_00L,
            employerProvidentMatchCents = 1_200_00L,
            netTakeHomePayCents = 16_110_00L,
            paydayTimestampMillis = paydayMillis,
            employerName = "星辰智造科技"
        )

        val outcome = LedgerRepository.recordPayslipOnStore(
            store = store,
            request = PayslipCommitRequest(
                ledgerName = "日常账本",
                draft = draft,
                enableNetPay = true,
                payrollWalletId = cmbPayrollWallet.id,
                enableMedicalDeposit = true,
                medicalWalletId = null,
                autoCreateMedicalWallet = true,
                enableProvidentDeposit = true,
                providentWalletId = null,
                autoCreateProvidentWallet = true,
                nowMillis = paydayMillis
            )
        )
        assertTrue(outcome is PayslipCommitOutcome.Success)
        val success = outcome as PayslipCommitOutcome.Success

        // 1. 校验自动创建的两个专项账户默认 includeInNetWorth = false
        val createdMed = success.createdMedicalWallet
        val createdProv = success.createdProvidentWallet
        assertNotNull(createdMed)
        assertNotNull(createdProv)
        assertFalse(createdMed!!.includeInNetWorth)
        assertFalse(createdProv!!.includeInNetWorth)

        // 2. 校验三笔交易与父子关联引用、标签、orderKey
        val mainTx = success.mainTransaction!!
        val medTx = success.medicalChildTransaction!!
        val provTx = success.providentChildTransaction!!
        assertEquals("PAYSLIP:202611:1611000:2000000", mainTx.orderKey)
        assertEquals("工资", mainTx.category)
        assertTrue(TagCatalog.parseTags(mainTx.tags).contains("工资"))

        assertEquals(mainTx.id, medTx.relatedTransactionId)
        assertEquals("PAYSLIP:202611:1611000:2000000#PAYSLIP_MED", medTx.orderKey)
        assertTrue(TagCatalog.parseTags(medTx.tags).containsAll(listOf("医保", "工资条")))

        assertEquals(mainTx.id, provTx.relatedTransactionId)
        assertEquals("PAYSLIP:202611:1611000:2000000#PAYSLIP_PROV", provTx.orderKey)
        assertTrue(TagCatalog.parseTags(provTx.tags).containsAll(listOf("公积金", "工资条")))

        // 3. 校验三路账户余额与日常可支配净资产（净资产仅增加实发工资 16,110.00 元）
        assertEquals(26_110_00L, store.getWalletById(cmbPayrollWallet.id)!!.balanceCents)
        assertEquals(400_00L, store.getWalletById(createdMed.id)!!.balanceCents)
        assertEquals(2_400_00L, store.getWalletById(createdProv.id)!!.balanceCents)

        val disposableNetWorthCents = store.getAllWallets()
            .filter { !it.type.isLiability && it.includeInNetWorth }
            .sumOf { it.balanceCents }
        assertEquals(10_000_00L + 16_110_00L, disposableNetWorthCents)

        // 校验工资条 #医保 收入子单绝不污染医疗消费四维统计卡片
        val medicalStats = MedicalExpenseInsightsCalculator.compute(
            transactions = store.getAllTransactions(),
            wallets = store.getAllWallets()
        )
        assertEquals(0L, medicalStats.totalMedicalCostCents)
        assertEquals(0, medicalStats.medicalBillCount)

        // 4. 删除/撤销主工资单：必须联动回滚并删除两条专项子单，三账户余额 100% 恢复初始值
        val deletedMain = LedgerRepository.undoAndDeleteTransactionOnStore(store, mainTx.id)
        assertNotNull(deletedMain)
        assertTrue(store.getAllTransactions().isEmpty())
        assertEquals(10_000_00L, store.getWalletById(cmbPayrollWallet.id)!!.balanceCents)
        assertEquals(0L, store.getWalletById(createdMed.id)!!.balanceCents)
        assertEquals(0L, store.getWalletById(createdProv.id)!!.balanceCents)
    }

    @Test
    fun `AC-B4 negative - deleting a single dedicated child transaction rolls back only that child wallet`() = runBlocking {
        val medWallet = WalletEntity(
            id = 2L,
            name = "个人医保账户",
            type = WalletType.CASH_OTHER,
            subTypeLabel = "医保账户",
            brandCode = "MEDICAL_CARD",
            balanceCents = 1_000_00L,
            includeInNetWorth = false
        )
        val provWallet = WalletEntity(
            id = 3L,
            name = "住房公积金",
            type = WalletType.CASH_OTHER,
            subTypeLabel = "住房公积金",
            brandCode = "PROVIDENT",
            balanceCents = 5_000_00L,
            includeInNetWorth = false
        )
        val store = InMemoryPayslipStore(listOf(cmbPayrollWallet, medWallet, provWallet))
        val parsed = PayslipImportEngine.parsePayslipText(PayslipImportEngine.SAMPLE_VERTICAL_TEXT).getOrThrow()

        val outcome = LedgerRepository.recordPayslipOnStore(
            store = store,
            request = PayslipCommitRequest(
                draft = parsed.draft,
                payrollWalletId = cmbPayrollWallet.id,
                medicalWalletId = medWallet.id,
                providentWalletId = provWallet.id
            )
        ) as PayslipCommitOutcome.Success

        assertEquals(3, store.getAllTransactions().size)
        assertEquals(26_110_00L, store.getWalletById(cmbPayrollWallet.id)!!.balanceCents)
        assertEquals(1_400_00L, store.getWalletById(medWallet.id)!!.balanceCents)
        assertEquals(7_400_00L, store.getWalletById(provWallet.id)!!.balanceCents)

        // 仅单独删除 #PAYSLIP_MED 医保划入子单
        val deletedMed = LedgerRepository.undoAndDeleteTransactionOnStore(
            store = store,
            transactionId = outcome.medicalChildTransaction!!.id
        )
        assertNotNull(deletedMed)

        // 医保卡余额精准回退至 1,000.00，主工资单与公积金子单及各自余额保持原样不变
        assertEquals(2, store.getAllTransactions().size)
        assertNull(store.getTransactionById(outcome.medicalChildTransaction!!.id))
        assertNotNull(store.getTransactionById(outcome.mainTransaction!!.id))
        assertNotNull(store.getTransactionById(outcome.providentChildTransaction!!.id))
        assertEquals(26_110_00L, store.getWalletById(cmbPayrollWallet.id)!!.balanceCents)
        assertEquals(1_000_00L, store.getWalletById(medWallet.id)!!.balanceCents)
        assertEquals(7_400_00L, store.getWalletById(provWallet.id)!!.balanceCents)
    }

    @Test
    fun `AC-B5 positive and negative - intercepts duplicate same-month payslip unless forceDuplicate is true`() = runBlocking {
        val store = InMemoryPayslipStore(listOf(cmbPayrollWallet))
        val parsed = PayslipImportEngine.parsePayslipText(PayslipImportEngine.SAMPLE_HORIZONTAL_CSV).getOrThrow()

        val firstOutcome = LedgerRepository.recordPayslipOnStore(
            store = store,
            request = PayslipCommitRequest(
                draft = parsed.draft,
                payrollWalletId = cmbPayrollWallet.id,
                nowMillis = 1_763_200_000_000L
            )
        )
        assertTrue(firstOutcome is PayslipCommitOutcome.Success)
        assertEquals(3, store.getAllTransactions().size)
        assertEquals(26_110_00L, store.getWalletById(cmbPayrollWallet.id)!!.balanceCents)

        // 同月同实发同应发再次提交（forceDuplicate = false）必须命中防重拦截，严禁重复入账
        val duplicateOutcome = LedgerRepository.recordPayslipOnStore(
            store = store,
            request = PayslipCommitRequest(
                draft = parsed.draft,
                payrollWalletId = cmbPayrollWallet.id,
                forceDuplicate = false,
                nowMillis = 1_763_200_050_000L
            )
        )
        assertTrue(duplicateOutcome is PayslipCommitOutcome.DuplicateDetected)
        assertEquals(
            "PAYSLIP:202611:1611000:2000000",
            (duplicateOutcome as PayslipCommitOutcome.DuplicateDetected).orderKey
        )
        assertEquals(3, store.getAllTransactions().size)
        assertEquals(26_110_00L, store.getWalletById(cmbPayrollWallet.id)!!.balanceCents)

        // 用户在弹窗中点击「仍要导入」（forceDuplicate = true）后放行二次入账
        val forcedOutcome = LedgerRepository.recordPayslipOnStore(
            store = store,
            request = PayslipCommitRequest(
                draft = parsed.draft,
                payrollWalletId = cmbPayrollWallet.id,
                forceDuplicate = true,
                nowMillis = 1_763_200_099_000L
            )
        )
        assertTrue(forcedOutcome is PayslipCommitOutcome.Success)
        assertEquals(6, store.getAllTransactions().size)
        assertEquals(42_220_00L, store.getWalletById(cmbPayrollWallet.id)!!.balanceCents)
    }
}
