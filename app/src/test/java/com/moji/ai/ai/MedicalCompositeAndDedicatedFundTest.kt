package com.moji.ai.ai

import android.content.SharedPreferences
import com.moji.ai.data.backup.BackupAndCsvTransferEngine
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.DedicatedFundKind
import com.moji.ai.data.model.DedicatedFundManager
import com.moji.ai.data.model.DedicatedFundPlanConfig
import com.moji.ai.data.model.MedicalExpenseInsightsCalculator
import com.moji.ai.data.model.PayslipRecognitionDraft
import com.moji.ai.data.model.TagCatalog
import com.moji.ai.data.model.TagDef
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType
import com.moji.ai.data.repository.LedgerRepository
import com.moji.ai.data.repository.LedgerTransactionStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MedicalCompositeAndDedicatedFundTest {

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

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private class InMemoryLedgerStore(initialWallets: List<WalletEntity>) : LedgerTransactionStore {
        private val wallets = LinkedHashMap<Long, WalletEntity>().apply {
            initialWallets.forEach { put(it.id, it) }
        }
        private val transactions = LinkedHashMap<Long, TransactionEntity>()
        private var nextTxId = 100L

        override suspend fun getWalletById(id: Long): WalletEntity? = wallets[id]
        override suspend fun getAllWallets(): List<WalletEntity> = wallets.values.toList()
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

    private lateinit var engine: AiAccountingEngine
    private lateinit var huabeiWallet: WalletEntity
    private lateinit var cmbPayrollWallet: WalletEntity
    private lateinit var personalMedicalWallet: WalletEntity
    private lateinit var providentWallet: WalletEntity

    @Before
    fun setUp() {
        DedicatedFundManager.resetForTesting()
        TagCatalog.initWithPrefs(FakeSharedPreferences())
        engine = AiAccountingEngine(configManager = null, merchantMemoryManager = null)

        huabeiWallet = WalletEntity(
            id = 1L,
            name = "花呗",
            type = WalletType.CREDIT,
            subTypeLabel = "蚂蚁花呗",
            brandCode = "HUABEI",
            balanceCents = 10_000L, // 初始欠款 ¥100.00
            aliases = "花呗,蚂蚁花呗",
            includeInNetWorth = true
        )
        cmbPayrollWallet = WalletEntity(
            id = 2L,
            name = "招商银行工资卡",
            type = WalletType.DEBIT_BANK,
            subTypeLabel = "储蓄卡",
            brandCode = "CMB",
            cardLast4 = "8866",
            balanceCents = 500_000L, // 初始余额 ¥5,000.00
            aliases = "招商银行,工资卡,8866",
            includeInNetWorth = true
        )
        personalMedicalWallet = WalletEntity(
            id = 88L,
            name = "个人医保账户",
            type = WalletType.CASH_OTHER,
            subTypeLabel = "医保账户",
            brandCode = "MEDICAL_CARD",
            balanceCents = 200_000L, // 初始余额 ¥2,000.00
            aliases = DedicatedFundKind.MEDICAL_INSURANCE.defaultAliases,
            includeInNetWorth = false
        )
        providentWallet = WalletEntity(
            id = 89L,
            name = "住房公积金",
            type = WalletType.CASH_OTHER,
            subTypeLabel = "住房公积金",
            brandCode = "PROVIDENT",
            balanceCents = 3_000_000L,
            aliases = DedicatedFundKind.HOUSING_PROVIDENT.defaultAliases,
            includeInNetWorth = false
        )
    }

    @After
    fun tearDown() {
        DedicatedFundManager.resetForTesting()
        TagCatalog.initWithPrefs(FakeSharedPreferences())
    }

    /**
     * 正例 1：混合医保 + 个人医保账户原子双扣、金额守恒与四维透视准确性
     * 原价 ¥100.00 = 统筹 ¥50.00 + 个账 ¥30.00 + 花呗自付 ¥20.00
     */
    @Test
    fun pos1_compositeMedicalWithPersonalWallet_atomicDualDeductAndConservation() = runBlocking {
        val wallets = listOf(huabeiWallet, cmbPayrollWallet, personalMedicalWallet)
        val store = InMemoryLedgerStore(wallets)
        val rawBill = "某三甲医院门诊收费 | -20.00 | 支付成功 | 订单金额 | 100.00 | 医保统筹 | -50.00 | 医保个账 | -30.00 | 付款方式 | 花呗 | 创建时间 | 2026-11-19 10:15:30 | 订单号 | 2026111922001438091426688001"

        val breakdown = AiAccountingEngine.extractMedicalBreakdown(rawBill)
        assertEquals(5_000L, breakdown.poolSubsidyCents)
        assertEquals(3_000L, breakdown.personalAccountCents)
        assertEquals(0L, breakdown.unspecifiedMedicalCents)

        val parsedMain = engine.parseCapturedText(
            rawText = rawBill,
            sourceAppPackage = "com.eg.android.AlipayGphone",
            captureMode = "AI_ACCESSIBILITY",
            wallets = wallets,
            recentExpenses = emptyList()
        )
        assertNotNull(parsedMain)
        assertEquals(2_000L, parsedMain!!.amountCents)
        assertEquals(1L, parsedMain.sourceWalletId) // 花呗

        val compositeRes = LedgerRepository.recordCompositeMedicalOnStore(
            store = store,
            mainTx = parsedMain,
            poolSubsidyCents = breakdown.poolSubsidyCents,
            personalAccountCents = breakdown.personalAccountCents,
            unspecifiedMedicalCents = breakdown.unspecifiedMedicalCents,
            commercialDiscountCents = 0L
        )

        val savedMain = compositeRes.mainTransaction
        val savedChild = compositeRes.childPersonalTransaction
        assertNotNull("建有个人医保账户且个账>0时必须拆出 #MED_PERSONAL 关联子单", savedChild)

        // 1. 严格金额守恒：主单实付(2000) + 子单实付(3000) + 主单优惠(5000) == 医疗原价(10000)
        assertEquals(2_000L, savedMain.amountCents)
        assertEquals(5_000L, savedMain.discountCents) // 个账 3000 已拆入子单，主单 discountCents 仅保留统筹 5000
        assertEquals(3_000L, savedChild!!.amountCents)
        assertEquals(0L, savedChild.discountCents)
        assertEquals(10_000L, savedMain.amountCents + savedChild.amountCents + savedMain.discountCents)

        // 2. 父子关联关系与账户双扣校验
        assertEquals(savedMain.id, savedChild.relatedTransactionId)
        assertEquals(88L, savedChild.sourceWalletId)
        assertTrue(savedChild.orderKey.endsWith(DedicatedFundManager.COMPOSITE_MEDICAL_PERSONAL_SUFFIX))
        assertTrue(TagCatalog.containsTag(savedMain.tags, "医保"))
        assertTrue(TagCatalog.containsTag(savedMain.tags, "组合支付"))
        assertTrue(TagCatalog.containsTag(savedChild.tags, "医保个账"))

        // 花呗欠款 +2000L -> 12000L；个人医保卡余额 -3000L -> 197000L
        assertEquals(12_000L, store.getWalletById(1L)!!.balanceCents)
        assertEquals(197_000L, store.getWalletById(88L)!!.balanceCents)

        // 3. 四维医疗透视汇总不双计且笔数合并为 1 笔
        val insights = MedicalExpenseInsightsCalculator.compute(store.getAllTransactions(), store.getAllWallets())
        assertTrue(insights.hasData)
        assertEquals(1, insights.medicalBillCount)
        assertEquals(10_000L, insights.totalMedicalCostCents)
        assertEquals(5_000L, insights.poolReimbursedCents)
        assertEquals(3_000L, insights.personalAccountPaidCents)
        assertEquals(2_000L, insights.cashOutOfPocketNetCents)
    }

    /**
     * 正例 2 + 反例 2：30s 跨通道多帧融合升级组合双扣 + 重复打开同一详情页幂等防双扣
     */
    @Test
    fun pos2_and_neg2_multiFrameFusionUpgradesToCompositeAndRemainsIdempotentOnReopen() = runBlocking {
        val wallets = listOf(huabeiWallet, cmbPayrollWallet, personalMedicalWallet)
        val store = InMemoryLedgerStore(wallets)
        val nowMillis = 1_763_530_000_000L

        // 第一帧：系统通知栏仅到达花呗扣款 ¥20.00
        val initialMain = TransactionEntity(
            type = TransactionType.EXPENSE,
            amountCents = 2_000L,
            amountCnyCents = 2_000L,
            category = "医疗-门诊",
            merchant = "市第一人民医院",
            note = "门诊缴费 · 花呗",
            sourceWalletId = 1L,
            timestamp = nowMillis,
            capturedAtMillis = nowMillis,
            captureSource = "AI_NOTIFICATION",
            dedupFingerprint = "fp_hosp_2000"
        )
        val mainId = LedgerRepository.recordSingleTransactionOnStore(store, initialMain)
        assertEquals(12_000L, store.getWalletById(1L)!!.balanceCents)
        assertEquals(200_000L, store.getWalletById(88L)!!.balanceCents)

        // 第二帧（5秒后）：点开支付宝账单详情页，出现统筹 ¥50.00 + 个账 ¥30.00 + 订单号
        val detailCandidate = initialMain.copy(
            id = mainId,
            discountCents = 8_000L,
            orderKey = "2026111922001438091426688002",
            tags = "医保",
            note = "原价¥100.00 · 医保统筹¥50.00 · 医保个账¥30.00 · 自费实扣¥20.00 | 门诊缴费 · 花呗"
        )
        val firstFusion = LedgerRepository.enrichCompositeMedicalOnStore(
            store = store,
            existingMainId = mainId,
            enrichedCandidateMain = detailCandidate,
            baseEnrichedFields = listOf("订单Key"),
            poolSubsidyCents = 5_000L,
            personalAccountCents = 3_000L
        )

        assertTrue(firstFusion.wasModified)
        assertNotNull(firstFusion.createdChildTransaction)
        assertEquals(5_000L, firstFusion.updatedMainTransaction.discountCents)
        assertEquals(12_000L, store.getWalletById(1L)!!.balanceCents)
        assertEquals(197_000L, store.getWalletById(88L)!!.balanceCents)
        assertEquals(2, store.getAllTransactions().size)

        // 反例 2：用户再次打开同一账单详情页，重复触发融合不得二次扣款或新增子单
        val secondFusion = LedgerRepository.enrichCompositeMedicalOnStore(
            store = store,
            existingMainId = mainId,
            enrichedCandidateMain = detailCandidate,
            baseEnrichedFields = emptyList(),
            poolSubsidyCents = 5_000L,
            personalAccountCents = 3_000L
        )
        assertFalse("重复打开同一组合详情页时 wasModified 必须为 false", secondFusion.wasModified)
        assertNull(secondFusion.createdChildTransaction)
        assertEquals(12_000L, store.getWalletById(1L)!!.balanceCents)
        assertEquals(197_000L, store.getWalletById(88L)!!.balanceCents)
        assertEquals(2, store.getAllTransactions().size)
    }

    /**
     * 正例 3：组合支付主单删除/撤销时，联动回滚 `#MED_PERSONAL` 子单并双双恢复账户余额
     */
    @Test
    fun pos3_deletingCompositeMainTransaction_rollsBackBothWalletsAndDeletesChildAtomically() = runBlocking {
        val wallets = listOf(huabeiWallet, cmbPayrollWallet, personalMedicalWallet)
        val store = InMemoryLedgerStore(wallets)

        val mainDraft = TransactionEntity(
            type = TransactionType.EXPENSE,
            amountCents = 2_000L,
            amountCnyCents = 2_000L,
            category = "医疗-买药",
            merchant = "益丰大药房",
            sourceWalletId = 1L,
            orderKey = "202611190003",
            dedupFingerprint = "fp_yifeng_003"
        )
        val recorded = LedgerRepository.recordCompositeMedicalOnStore(
            store = store,
            mainTx = mainDraft,
            poolSubsidyCents = 5_000L,
            personalAccountCents = 3_000L
        )
        assertEquals(2, store.getAllTransactions().size)
        assertEquals(12_000L, store.getWalletById(1L)!!.balanceCents)
        assertEquals(197_000L, store.getWalletById(88L)!!.balanceCents)

        val (deleted, _) = LedgerRepository.undoAndDeleteTransactionOnStore(store, recorded.mainTransaction.id)
        assertTrue(deleted)
        assertTrue("主单与关联医保个账子单应一并删除", store.getAllTransactions().isEmpty())
        assertEquals("花呗欠款应恢复初始 10000L", 10_000L, store.getWalletById(1L)!!.balanceCents)
        assertEquals("个人医保账户余额应恢复初始 200000L", 200_000L, store.getWalletById(88L)!!.balanceCents)
    }

    /**
     * 正例 4：专项保障账户默认不计入日常净资产 + 双边月缴存持久化 + 工资条三路分账路由契约
     */
    @Test
    fun pos4_dedicatedFundNetWorthIsolationAndPayslipThreeWayRoutingContract() {
        val sp = FakeSharedPreferences()
        DedicatedFundManager.initWithPrefs(sp)

        // 1. 新建预设时 MEDICAL_CARD 与 PROVIDENT 默认 includeInNetWorth = false；普通账户默认 true
        assertFalse(DedicatedFundManager.defaultIncludeInNetWorthForBrand("MEDICAL_CARD"))
        assertFalse(DedicatedFundManager.defaultIncludeInNetWorthForBrand("PROVIDENT"))
        assertTrue(DedicatedFundManager.defaultIncludeInNetWorthForBrand("ALIPAY"))
        assertTrue(DedicatedFundManager.defaultIncludeInNetWorthForBrand("CMB"))

        // 2. 配置月度双边缴存并关联代发工资储蓄卡
        DedicatedFundManager.updatePlan(
            DedicatedFundKind.MEDICAL_INSURANCE,
            DedicatedFundPlanConfig(
                enabled = true,
                employeeMonthlyCents = 30_000L, // ¥300
                employerMonthlyCents = 25_000L, // ¥250
                depositDayOfMonth = 15,
                linkedPayrollWalletId = cmbPayrollWallet.id,
                autoCreateMonthlyRule = true
            )
        )
        DedicatedFundManager.updatePlan(
            DedicatedFundKind.HOUSING_PROVIDENT,
            DedicatedFundPlanConfig(
                enabled = true,
                employeeMonthlyCents = 120_000L, // ¥1,200
                employerMonthlyCents = 120_000L, // ¥1,200
                depositDayOfMonth = 15,
                linkedPayrollWalletId = cmbPayrollWallet.id,
                autoCreateMonthlyRule = true
            )
        )

        // 模拟冷启动重新加载
        DedicatedFundManager.initWithPrefs(sp)
        assertEquals(55_000L, DedicatedFundManager.currentSettings.medicalPlan.totalMonthlyDepositCents)
        assertEquals(240_000L, DedicatedFundManager.currentSettings.providentPlan.totalMonthlyDepositCents)

        // 3. 验证工资条标准三路路由契约 PayslipRecognitionDraft -> resolvePayslipTargetWallets
        val draft = PayslipRecognitionDraft(
            grossSalaryCents = 2_000_000L,
            incomeTaxCents = 80_000L,
            pensionAndUnemploymentDeductionCents = 170_000L,
            personalMedicalDeductionCents = 40_000L,
            medicalPersonalAccountDepositCents = 60_000L,
            personalProvidentDeductionCents = 120_000L,
            employerProvidentMatchCents = 120_000L,
            netTakeHomePayCents = 1_590_000L,
            employerName = "示例科技有限公司"
        )
        val wallets = listOf(huabeiWallet, cmbPayrollWallet, personalMedicalWallet, providentWallet)
        val routing = DedicatedFundManager.resolvePayslipTargetWallets(wallets, draft)

        assertEquals(cmbPayrollWallet.id, routing.payrollDebitWallet?.id)
        assertEquals(personalMedicalWallet.id, routing.personalMedicalWallet?.id)
        assertEquals(providentWallet.id, routing.housingProvidentWallet?.id)
        assertEquals(1_590_000L, routing.netPayDepositCents)
        assertEquals(60_000L, routing.medicalAccountDepositCents) // 400 + 200
        assertEquals(240_000L, routing.providentAccountDepositCents) // 1200 + 1200
    }

    /**
     * 正例 5（CR 修复 #2）：老用户从 TagCatalog v1 升级到 v2 时自动增量追加 #医保 与 #公积金，同时尊重用户手动静音列表
     */
    @Test
    fun pos5_tagCatalogV2IncrementalUpgrade_appendsMedicalAndProvidentWhileRespectingMutedTags() {
        val json = Json { encodeDefaults = true }
        val legacySp = FakeSharedPreferences()
        val legacyTags = listOf(
            TagDef(name = "家人", colorHex = 0xFFE65540, groupName = "日常"),
            TagDef(name = "自定义露营", colorHex = 0xFF10B981, groupName = "自定义")
        )
        legacySp.edit()
            .putString("custom_tags_json", json.encodeToString(legacyTags))
            // 不写 preset_tag_catalog_version，模拟老版本默认 version = 1
            .putString("muted_discovered_tags_json", json.encodeToString(listOf("公积金")))
            .apply()

        TagCatalog.initWithPrefs(legacySp)
        val namesAfterUpgrade = TagCatalog.tags.map { it.name }
        assertTrue("老用户自定义标签必须保留", "自定义露营" in namesAfterUpgrade)
        assertTrue("升级 v2 后应自动增量补齐未静音的 #医保", "医保" in namesAfterUpgrade)
        assertFalse("已被用户手动静音/删除的 #公积金 不得强行复活", "公积金" in namesAfterUpgrade)
        assertEquals(2, legacySp.getInt("preset_tag_catalog_version", 1))
    }

    /**
     * 反例 1：未建个人医保账户时的混合医保单不双扣，但结构化保留统筹/个账明细且四维透视依然准确
     */
    @Test
    fun neg1_mixedMedicalWithoutPersonalMedicalWallet_recordsSingleLegAndPreserves4DInsights() = runBlocking {
        val walletsWithoutMedical = listOf(huabeiWallet, cmbPayrollWallet)
        val store = InMemoryLedgerStore(walletsWithoutMedical)
        val rawBill = "某三甲医院门诊收费 | -20.00 | 支付成功 | 订单金额 | 100.00 | 医保统筹 | -50.00 | 医保个账 | -30.00 | 付款方式 | 花呗 | 创建时间 | 2026-11-19 11:00:00 | 订单号 | 202611190091"

        val breakdown = AiAccountingEngine.extractMedicalBreakdown(rawBill)
        val parsed = engine.parseCapturedText(
            rawText = rawBill,
            sourceAppPackage = "com.eg.android.AlipayGphone",
            captureMode = "AI_ACCESSIBILITY",
            wallets = walletsWithoutMedical,
            recentExpenses = emptyList()
        )!!

        val res = LedgerRepository.recordCompositeMedicalOnStore(
            store = store,
            mainTx = parsed,
            poolSubsidyCents = breakdown.poolSubsidyCents,
            personalAccountCents = breakdown.personalAccountCents
        )
        assertNull("未建个人医保账户时不得生成子单", res.childPersonalTransaction)
        assertEquals(1, store.getAllTransactions().size)
        assertEquals(2_000L, res.mainTransaction.amountCents)
        assertEquals(8_000L, res.mainTransaction.discountCents)
        assertTrue(res.mainTransaction.note.contains("医保统筹¥50.00"))
        assertTrue(res.mainTransaction.note.contains("医保个账¥30.00"))

        val insights = MedicalExpenseInsightsCalculator.compute(store.getAllTransactions(), store.getAllWallets())
        assertEquals(10_000L, insights.totalMedicalCostCents)
        assertEquals(5_000L, insights.poolReimbursedCents)
        assertEquals(3_000L, insights.personalAccountPaidCents)
        assertEquals(2_000L, insights.cashOutOfPocketNetCents)
        assertEquals(1, insights.medicalBillCount)
    }

    /**
     * 反例 3：账单同时出现汇总项「医保支付 80.00」与子项「医保统筹 50.00 + 医保个账 30.00」时严禁翻倍成 160.00
     */
    @Test
    fun neg3_summaryAndSubItemsBothPresent_deduplicatesWithoutDoubling() {
        val raw = "市中心医院 | -20.00 | 订单金额 | 100.00 | 医保支付 | -80.00 | 医保统筹 | -50.00 | 医保个账 | -30.00 | 付款方式 | 花呗"
        val breakdown = AiAccountingEngine.extractMedicalBreakdown(raw)
        assertEquals(5_000L, breakdown.poolSubsidyCents)
        assertEquals(3_000L, breakdown.personalAccountCents)
        assertEquals(0L, breakdown.unspecifiedMedicalCents)
        assertEquals(8_000L, breakdown.totalMedicalCents)
    }

    /**
     * 反例 4：100% 医保统筹全额报销（无个账扣款、无现金自付），即使建有个人医保账户也强制 sourceWalletId = null
     */
    @Test
    fun neg4_purePooledReimbursementOnly_forcesNullSourceWalletEvenWhenMedicalWalletExists() = runBlocking {
        val walletsWithMedical = listOf(huabeiWallet, cmbPayrollWallet, personalMedicalWallet)

        val rawPurePooled = "社区卫生服务中心 | -0.00 | 支付成功 | 医保统筹 | -120.00 | 创建时间 | 2026-11-19 09:20:00 | 订单号 | 202611190094"
        val tx1 = engine.parseCapturedText(
            rawText = rawPurePooled,
            sourceAppPackage = "com.eg.android.AlipayGphone",
            captureMode = "AI_ACCESSIBILITY",
            wallets = walletsWithMedical,
            recentExpenses = emptyList()
        )
        assertNotNull(tx1)
        assertEquals(12_000L, tx1!!.amountCents)
        assertNull("100% 统筹全额报销不得扣减个人医保卡，sourceWalletId 必须为 null", tx1.sourceWalletId)

        // 同时出现汇总「医保支付 120.00」与「医保统筹 120.00」时同样识别为 100% 统筹全额报销
        val rawSummaryPlusPooled = "社区卫生服务中心 | -0.00 | 支付成功 | 医保支付 | -120.00 | 医保统筹 | -120.00 | 创建时间 | 2026-11-19 09:21:00 | 订单号 | 202611190095"
        val tx2 = engine.parseCapturedText(
            rawText = rawSummaryPlusPooled,
            sourceAppPackage = "com.eg.android.AlipayGphone",
            captureMode = "AI_ACCESSIBILITY",
            wallets = walletsWithMedical,
            recentExpenses = emptyList()
        )
        assertNotNull(tx2)
        assertEquals(12_000L, tx2!!.amountCents)
        assertNull("汇总项等于统筹项时仍为 100% 统筹，不得扣个人医保卡", tx2.sourceWalletId)

        val insights = MedicalExpenseInsightsCalculator.compute(listOf(tx1.copy(id = 1L)), walletsWithMedical)
        assertEquals(12_000L, insights.totalMedicalCostCents)
        assertEquals(12_000L, insights.poolReimbursedCents)
        assertEquals(0L, insights.personalAccountPaidCents)
        assertEquals(0L, insights.cashOutOfPocketNetCents)
    }

    /**
     * 反例 5（CR 修复 #1）：混合医保自费单未标注现金支付方式时，严禁主单误匹配个人医保卡（含商户记忆兜底隔离）
     */
    @Test
    fun neg5_mixedSelfPayWithUnknownPaymentMethod_neverMatchesPersonalMedicalWalletForMainLeg() = runBlocking {
        val walletsWithMedical = listOf(huabeiWallet, cmbPayrollWallet, personalMedicalWallet)
        val memoryPointingToMedicalCard = MerchantMemoryManager(
            initialEntries = listOf(
                MerchantMemoryEntry(
                    merchantKeyword = "老百姓大药房",
                    category = "医疗-买药",
                    preferredWalletId = personalMedicalWallet.id // 恶意/历史记忆指向个人医保卡
                )
            )
        )
        val engineWithMedMemory = AiAccountingEngine(
            configManager = null,
            merchantMemoryManager = memoryPointingToMedicalCard
        )

        val rawUnknownPayMethod = "老百姓大药房 | -20.00 | 支付成功 | 订单金额 | 50.00 | 医保个账 | -30.00 | 创建时间 | 2026-11-19 12:10:00 | 订单号 | 202611190096"
        val parsedMain = engineWithMedMemory.parseCapturedText(
            rawText = rawUnknownPayMethod,
            sourceAppPackage = "com.eg.android.AlipayGphone",
            captureMode = "AI_ACCESSIBILITY",
            wallets = walletsWithMedical,
            recentExpenses = emptyList()
        )
        assertNotNull(parsedMain)
        assertEquals(2_000L, parsedMain!!.amountCents)
        assertNull("自费主单在未知支付方式时严禁误绑个人医保账户，必须挂空 null", parsedMain.sourceWalletId)

        // 走组合落库后：主单保持不关联账户(null)，子单正常扣个人医保卡 -3000L
        val store = InMemoryLedgerStore(walletsWithMedical)
        val res = LedgerRepository.recordCompositeMedicalOnStore(
            store = store,
            mainTx = parsedMain,
            poolSubsidyCents = 0L,
            personalAccountCents = 3_000L
        )
        assertNull(res.mainTransaction.sourceWalletId)
        assertEquals(88L, res.childPersonalTransaction?.sourceWalletId)
        assertEquals(197_000L, store.getWalletById(88L)!!.balanceCents)
    }

    /**
     * 反例 6：旧版无 dedicatedFundSettingsJson 字段的全量备份 JSON 反序列化安全兼容 & 新版备份完整往返
     */
    @Test
    fun neg6_legacyBackupWithoutDedicatedFundField_parsesCleanlyAndRoundTripsNewField() {
        val legacyJson = """
            {
              "app": "MoJi",
              "schemaVersion": 3,
              "exportedAtMillis": 1763500000000,
              "ledgers": ["默认账本"],
              "monthlyBudgetCents": 300000,
              "wallets": [],
              "transactions": []
            }
        """.trimIndent()

        val parsedLegacy = BackupAndCsvTransferEngine.parseFullBackupJson(legacyJson)
        assertNotNull("缺少 dedicatedFundSettingsJson 的旧版备份必须正常反序列化", parsedLegacy)
        assertEquals("", parsedLegacy!!.third.dedicatedFundSettingsJson)

        val sp = FakeSharedPreferences()
        DedicatedFundManager.initWithPrefs(sp)
        DedicatedFundManager.updatePlan(
            DedicatedFundKind.HOUSING_PROVIDENT,
            DedicatedFundPlanConfig(
                enabled = true,
                employeeMonthlyCents = 150_000L,
                employerMonthlyCents = 150_000L,
                depositDayOfMonth = 20
            )
        )
        val exported = BackupAndCsvTransferEngine.exportFullBackupJson(
            ledgers = listOf("默认账本"),
            monthlyBudgetCents = 0L,
            wallets = listOf(providentWallet),
            transactions = emptyList(),
            recurringRules = emptyList(),
            merchantMemories = emptyList(),
            dedicatedFundSettingsJson = DedicatedFundManager.exportConfigJson()
        )
        val parsedNew = BackupAndCsvTransferEngine.parseFullBackupJson(exported)
        assertNotNull(parsedNew)
        assertTrue(parsedNew!!.third.dedicatedFundSettingsJson.isNotBlank())

        DedicatedFundManager.resetForTesting()
        DedicatedFundManager.initWithPrefs(FakeSharedPreferences())
        assertTrue(DedicatedFundManager.restoreFromBackupJson(parsedNew.third.dedicatedFundSettingsJson))
        assertEquals(300_000L, DedicatedFundManager.currentSettings.providentPlan.totalMonthlyDepositCents)
        assertEquals(20, DedicatedFundManager.currentSettings.providentPlan.depositDayOfMonth)
    }

    /**
     * 正例 7（QA 回归）：账本列表行医疗结构化胶囊口径与详情页/统计页统一，不漏计已拆出子单的医保个账
     */
    @Test
    fun pos7_ledgerMedicalBadgeSummary_matchesDetailFourDimAndSkipsChildOrPlainDiscount() = runBlocking {
        val store = InMemoryLedgerStore(listOf(huabeiWallet, personalMedicalWallet))
        val manualMain = TransactionEntity(
            type = TransactionType.EXPENSE,
            amountCents = 2_000L,
            amountCnyCents = 2_000L,
            category = "医疗",
            sourceWalletId = huabeiWallet.id,
            captureSource = "MANUAL"
        )
        val recorded = LedgerRepository.recordCompositeMedicalOnStore(
            store = store,
            mainTx = manualMain,
            poolSubsidyCents = 5_000L,
            personalAccountCents = 3_000L
        )
        val mainBadge = DedicatedFundManager.buildLedgerMedicalBadgeSummary(
            tx = recorded.mainTransaction,
            childPersonalSumCents = recorded.childPersonalTransaction!!.amountCents
        )
        assertEquals("总费¥100.00 · 统筹¥50.00 · 个账¥30.00", mainBadge)

        // 子单自身不重复挂总费胶囊
        val childBadge = DedicatedFundManager.buildLedgerMedicalBadgeSummary(
            tx = recorded.childPersonalTransaction!!
        )
        assertNull(childBadge)

        // 无统筹仅「自费 + 医保个账」（discountCents == 0）同样正确展示总费与个账胶囊
        val noPoolRecorded = LedgerRepository.recordCompositeMedicalOnStore(
            store = store,
            mainTx = manualMain,
            poolSubsidyCents = 0L,
            personalAccountCents = 3_000L
        )
        assertEquals(
            "总费¥50.00 · 个账¥30.00",
            DedicatedFundManager.buildLedgerMedicalBadgeSummary(
                tx = noPoolRecorded.mainTransaction,
                childPersonalSumCents = noPoolRecorded.childPersonalTransaction!!.amountCents
            )
        )

        // 普通商业满减账单返回 null，保留列表原有「原价 · 优惠」胶囊逻辑
        val plainRetailTx = TransactionEntity(
            type = TransactionType.EXPENSE,
            amountCents = 2_500L,
            amountCnyCents = 2_500L,
            discountCents = 800L,
            category = "餐饮",
            merchant = "美团外卖",
            note = "原价¥33.00 · 优惠¥8.00 | 麻辣香锅",
            sourceWalletId = huabeiWallet.id
        )
        assertNull(DedicatedFundManager.buildLedgerMedicalBadgeSummary(plainRetailTx))
    }
}
