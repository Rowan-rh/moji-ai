package com.moji.ai.data.repository

import android.content.SharedPreferences
import androidx.sqlite.db.SupportSQLiteDatabase
import com.moji.ai.ai.AiAccountingEngine
import com.moji.ai.ai.MerchantMemoryEntry
import com.moji.ai.ai.MerchantMemoryManager
import com.moji.ai.data.backup.BackupAndCsvTransferEngine
import com.moji.ai.data.local.AppDatabase
import com.moji.ai.data.local.TransactionEntity
import com.moji.ai.data.local.WalletEntity
import com.moji.ai.data.model.CurrencyRateManager
import com.moji.ai.data.model.DedicatedFundKind
import com.moji.ai.data.model.DedicatedFundManager
import com.moji.ai.data.model.TagCatalog
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType
import com.moji.ai.ui.screens.netWorthImpactEvents
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class Nere150AuditFixesRegressionTest {

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

    @Before
    fun setUp() {
        CurrencyRateManager.resetAllCustomRates()
        DedicatedFundManager.resetForTesting()
        TagCatalog.initWithPrefs(FakeSharedPreferences())
    }

    @After
    fun tearDown() {
        CurrencyRateManager.resetAllCustomRates()
        DedicatedFundManager.resetForTesting()
        TagCatalog.initWithPrefs(FakeSharedPreferences())
    }

    /**
     * AC-1（B1）：已报销支出单新增/删除关联 REFUND_OFFSET 时报销钱包与付款钱包双向守恒，
     * 且拦截将已部分退款账单金额改小至低于 refundedAmountCents 或篡改交易类型。
     */
    @Test
    fun ac1_reimbursedExpensePartialRefundAndDeleteRefund_conservesWalletsAndBlocksInvalidEdit() = runBlocking {
        val creditWallet = WalletEntity(
            id = 1L,
            name = "招商信用卡",
            type = WalletType.CREDIT,
            balanceCents = 0L, // 初始欠款 0
            syncDirty = false,
            updatedAtMillis = 100L
        )
        val debitWallet = WalletEntity(
            id = 2L,
            name = "工资储蓄卡",
            type = WalletType.DEBIT_BANK,
            balanceCents = 50_000L, // 初始余额 ¥500.00
            syncDirty = false,
            updatedAtMillis = 100L
        )
        val store = InMemoryLedgerStore(listOf(creditWallet, debitWallet))

        // 1. 记录 ¥100.00 待报销差旅支出（信用卡欠款 0 -> 10,000）
        val expenseId = LedgerRepository.recordSingleTransactionOnStore(
            store = store,
            tx = TransactionEntity(
                type = TransactionType.EXPENSE,
                amountCents = 10_000L,
                amountCnyCents = 10_000L,
                category = "交通-高铁",
                merchant = "铁路12306",
                sourceWalletId = 1L,
                reimbursable = true,
                timestamp = 1_763_000_000_000L
            ),
            nowMillis = 1_763_000_000_000L
        )
        assertEquals(10_000L, store.getWalletById(1L)!!.balanceCents)
        assertEquals(50_000L, store.getWalletById(2L)!!.balanceCents)

        // 2. 核销报销入账至储蓄卡（储蓄卡余额 50,000 -> 60,000）
        val settledAt = 1_763_200_000_000L
        assertTrue(LedgerRepository.settleReimbursementOnStore(store, expenseId, receiveWalletId = 2L, nowMillis = settledAt))
        assertEquals(60_000L, store.getWalletById(2L)!!.balanceCents)
        val settledParent = store.getTransactionById(expenseId)!!
        assertTrue(settledParent.reimbursed)
        assertEquals(10_000L, settledParent.targetWalletDeltaCents)
        assertEquals(settledAt, settledParent.reimbursedAtMillis)

        // 3. 发生改签退差价 ¥30.00 原路退回信用卡：
        //    信用卡欠款 10,000 -> 7,000；储蓄卡自动扣回超额报销的 3,000（60,000 -> 57,000）
        val refundId = LedgerRepository.refundExpenseOnStore(
            store = store,
            expenseId = expenseId,
            refundAmountCents = 3_000L,
            receiveWalletId = 1L,
            refundNote = "改签退差价",
            nowMillis = 1_763_300_000_000L
        )
        assertNotNull(refundId)
        assertEquals(7_000L, store.getWalletById(1L)!!.balanceCents)
        assertEquals(57_000L, store.getWalletById(2L)!!.balanceCents)
        val afterRefundParent = store.getTransactionById(expenseId)!!
        assertEquals(3_000L, afterRefundParent.refundedAmountCents)
        assertEquals(7_000L, afterRefundParent.targetWalletDeltaCents)

        // 4. AC-1 ③：拒绝把已退 ¥30.00 的支出账单改为 ¥20.00（< refundedAmountCents）或改为 INCOME
        val underRefundEditOk = LedgerRepository.updateExistingTransactionOnStore(
            store = store,
            updatedTx = afterRefundParent.copy(amountCents = 2_000L, amountCnyCents = 2_000L)
        )
        assertFalse("折算金额小于已退款金额时必须拦截", underRefundEditOk)
        val typeChangeOk = LedgerRepository.updateExistingTransactionOnStore(
            store = store,
            updatedTx = afterRefundParent.copy(type = TransactionType.INCOME)
        )
        assertFalse("已发生退款的支出单禁止篡改交易类型", typeChangeOk)
        assertEquals(7_000L, store.getWalletById(1L)!!.balanceCents)
        assertEquals(57_000L, store.getWalletById(2L)!!.balanceCents)

        // 5. 删除该笔关联退款单：信用卡欠款恢复 10,000，储蓄卡自动补回 3,000 恢复 60,000
        val (refundDeleted, _) = LedgerRepository.undoAndDeleteTransactionOnStore(store, refundId!!)
        assertTrue(refundDeleted)
        assertEquals(10_000L, store.getWalletById(1L)!!.balanceCents)
        assertEquals(60_000L, store.getWalletById(2L)!!.balanceCents)
        val restoredParent = store.getTransactionById(expenseId)!!
        assertEquals(0L, restoredParent.refundedAmountCents)
        assertEquals(10_000L, restoredParent.targetWalletDeltaCents)

        // 6. 再删除原报销支出单：两张卡双双精准回到记账前初始余额（信用卡 0，储蓄卡 50,000）
        val (parentDeleted, _) = LedgerRepository.undoAndDeleteTransactionOnStore(store, expenseId)
        assertTrue(parentDeleted)
        assertEquals(0L, store.getWalletById(1L)!!.balanceCents)
        assertEquals(50_000L, store.getWalletById(2L)!!.balanceCents)
    }

    /**
     * AC-2（B2 & B13）：MIGRATION_5_6 无损加列 SQL 校验、修改自定义汇率后历史跨币种账单回滚零漂移，
     * 以及 .mojibackup 新旧快照双向兼容。
     */
    @Test
    fun ac2_migration5To6Sql_crossCurrencySnapshotZeroDrift_and_backupDtoCompat() = runBlocking {
        // 1. 验证 MIGRATION_5_6 纯 ADD COLUMN 无损迁移 SQL
        assertEquals(5, AppDatabase.MIGRATION_5_6.startVersion)
        assertEquals(6, AppDatabase.MIGRATION_5_6.endVersion)
        val executedSql = mutableListOf<String>()
        val proxyDb = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && !args.isNullOrEmpty()) {
                executedSql.add(args[0] as String)
            }
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as SupportSQLiteDatabase
        AppDatabase.MIGRATION_5_6.migrate(proxyDb)
        assertEquals(3, executedSql.size)
        assertTrue(executedSql.any { it.contains("ADD COLUMN sourceWalletDeltaCents INTEGER") })
        assertTrue(executedSql.any { it.contains("ADD COLUMN targetWalletDeltaCents INTEGER") })
        assertTrue(executedSql.any { it.contains("ADD COLUMN reimbursedAtMillis INTEGER") })
        assertTrue(executedSql.none { it.uppercase().contains("DROP") })

        // 2. 验证跨币种账单在修改自定义汇率后编辑/删除零余额漂移
        assertTrue(CurrencyRateManager.setCustomRate("USD", "7.10").isSuccess)
        assertTrue(CurrencyRateManager.setCustomRate("HKD", "0.92").isSuccess)

        val cnyCard = WalletEntity(id = 2L, name = "招商储蓄卡", type = WalletType.DEBIT_BANK, currency = "CNY", balanceCents = 100_000L)
        val hkdWallet = WalletEntity(id = 3L, name = "八达通HKD", type = WalletType.DIGITAL_WALLET, currency = "HKD", balanceCents = 50_000L)
        val store = InMemoryLedgerStore(listOf(cnyCard, hkdWallet))

        // 2a. 按旧汇率 USD=7.10 记录一笔 $10.00 海淘消费（扣 CNY 储蓄卡 ¥71.00 -> 92,900L），并按 HKD=0.92 从 CNY 转入 HKD
        val usdExpId = LedgerRepository.recordSingleTransactionOnStore(
            store = store,
            tx = TransactionEntity(
                type = TransactionType.EXPENSE,
                amountCents = 1_000L, // $10.00
                currency = "USD",
                exchangeRateToCny = 7.10,
                amountCnyCents = 7_100L,
                category = "数码",
                merchant = "App Store",
                sourceWalletId = 2L
            )
        )
        val transferId = LedgerRepository.recordSingleTransactionOnStore(
            store = store,
            tx = TransactionEntity(
                type = TransactionType.TRANSFER,
                amountCents = 9_200L, // ¥92.00 -> HK$100.00 (10,000 cents at 0.92)
                currency = "CNY",
                exchangeRateToCny = 1.0,
                amountCnyCents = 9_200L,
                category = "内部转账",
                sourceWalletId = 2L,
                targetWalletId = 3L
            )
        )
        assertEquals(83_700L, store.getWalletById(2L)!!.balanceCents)
        assertEquals(60_000L, store.getWalletById(3L)!!.balanceCents)

        // 2b. 用户后续在「货币和汇率」修改自定义汇率：USD -> 7.45，HKD -> 0.98
        assertTrue(CurrencyRateManager.setCustomRate("USD", "7.45").isSuccess)
        assertTrue(CurrencyRateManager.setCustomRate("HKD", "0.98").isSuccess)

        // 2c. 编辑第一笔 USD 账单为 $20.00（新汇率 7.45 -> ¥149.00），验证旧扣款 ¥71.00 被精准回滚后再扣新金额
        val oldUsdTx = store.getTransactionById(usdExpId)!!
        assertTrue(
            LedgerRepository.updateExistingTransactionOnStore(
                store = store,
                updatedTx = oldUsdTx.copy(
                    amountCents = 2_000L,
                    exchangeRateToCny = 7.45,
                    amountCnyCents = 14_900L
                )
            )
        )
        // 83,700 + 7,100 - 14,900 = 75,900L
        assertEquals(75_900L, store.getWalletById(2L)!!.balanceCents)

        // 2d. 删除这两笔跨币种流水，CNY 与 HKD 两个钱包必须 100% 回到初始 100,000L 与 50,000L（零漂移）
        assertTrue(LedgerRepository.undoAndDeleteTransactionOnStore(store, usdExpId).first)
        assertTrue(LedgerRepository.undoAndDeleteTransactionOnStore(store, transferId).first)
        assertEquals(100_000L, store.getWalletById(2L)!!.balanceCents)
        assertEquals(50_000L, store.getWalletById(3L)!!.balanceCents)

        // 3. 验证 .mojibackup 新列序列化往返与旧版缺省 null 兼容
        val snapshotTx = TransactionEntity(
            id = 77L,
            type = TransactionType.EXPENSE,
            amountCents = 1_000L,
            currency = "USD",
            exchangeRateToCny = 7.10,
            amountCnyCents = 7_100L,
            category = "购物",
            sourceWalletId = 2L,
            targetWalletId = 2L,
            reimbursable = true,
            reimbursed = true,
            sourceWalletDeltaCents = 7_100L,
            targetWalletDeltaCents = 7_100L,
            reimbursedAtMillis = 1_763_500_000_000L,
            timestamp = 1_763_400_000_000L
        )
        val exportedJson = BackupAndCsvTransferEngine.exportFullBackupJson(
            ledgers = listOf("默认账本"),
            monthlyBudgetCents = 0L,
            wallets = listOf(cnyCard),
            transactions = listOf(snapshotTx),
            recurringRules = emptyList(),
            merchantMemories = emptyList()
        )
        val restored = BackupAndCsvTransferEngine.parseFullBackupJson(exportedJson)!!
        val restoredTx = restored.second.single()
        assertEquals(7_100L, restoredTx.sourceWalletDeltaCents)
        assertEquals(7_100L, restoredTx.targetWalletDeltaCents)
        assertEquals(1_763_500_000_000L, restoredTx.reimbursedAtMillis)
    }

    /**
     * AC-3（B3）：外部 CSV 导入——零钱通/余额宝日常消费映射为 EXPENSE、信用账户负欠款溢缴款正负号保留、LOAN_IN 绑定 targetWalletId。
     */
    @Test
    fun ac3_csvImport_decouplesLingqiantongAndYuebaoExpenses_preservesOverpayment_and_bindsLoanInTarget() {
        val existingDebit = WalletEntity(
            id = 2L,
            name = "招商储蓄卡",
            type = WalletType.DEBIT_BANK,
            balanceCents = 10_000L
        )

        // 1. 微信官方 CSV：使用「零钱通」消费记为 EXPENSE，显式「零钱提现」仍记为 TRANSFER
        val wechatCsv = """
            交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
            2026-11-20 12:15:00,商户消费,美团外卖,工作午餐,支出,¥28.50,零钱通,支付成功,WX001,M001,/
            2026-11-20 18:00:00,零钱提现,招商银行,提现,中性交易,¥200.00,零钱,提现已到账,WX002,M002,/
        """.trimIndent()
        val wxPreview = BackupAndCsvTransferEngine.parseExternalCsv(
            rawCsv = wechatCsv,
            defaultLedger = "默认账本",
            wallets = listOf(existingDebit),
            merchantMemoryManager = null
        )
        assertEquals(2, wxPreview.parsedTransactions.size)
        assertEquals("零钱通消费必须识别为 EXPENSE", TransactionType.EXPENSE, wxPreview.parsedTransactions[0].type)
        assertEquals(2_850L, wxPreview.parsedTransactions[0].amountCents)
        assertEquals("显式零钱提现保持 TRANSFER", TransactionType.TRANSFER, wxPreview.parsedTransactions[1].type)

        // 2. 支付宝官方 CSV：使用「余额宝」消费记为 EXPENSE
        val alipayCsv = """
            交易时间,交易分类,交易对方,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注
            2026-11-20 09:05:00,餐饮美食,瑞幸咖啡,生椰拿铁,支出,16.90,余额宝,交易成功,ALI001,AM001,
        """.trimIndent()
        val aliPreview = BackupAndCsvTransferEngine.parseExternalCsv(
            rawCsv = alipayCsv,
            defaultLedger = "默认账本",
            wallets = listOf(existingDebit),
            merchantMemoryManager = null
        )
        assertEquals(1, aliPreview.parsedTransactions.size)
        assertEquals("余额宝消费必须识别为 EXPENSE", TransactionType.EXPENSE, aliPreview.parsedTransactions.single().type)

        // 3. 资产表信用溢缴款（-50.00 元负欠款）保留负号，且借入（LOAN_IN）绑定至 targetWalletId（sourceWalletId = null）
        val combinedAssetAndLoanCsv = """
            账户名称,账户类型,币种,当前欠款,信用额度
            招行全币种信用卡,信用卡,CNY,-50.00,50000.00

            日期,时间,类型,金额,货币,一级分类,二级分类,账户1,账户2,备注,标签,账本
            2026-11-20,20:00:00,借入,1000.00,CNY,借入,,招商储蓄卡,,朋友周转,,默认账本
        """.trimIndent()
        val combinedPreview = BackupAndCsvTransferEngine.parseExternalCsv(
            rawCsv = combinedAssetAndLoanCsv,
            defaultLedger = "默认账本",
            wallets = listOf(existingDebit),
            merchantMemoryManager = null
        )
        val createdCredit = combinedPreview.newWalletsToCreate.single { it.name.contains("招行全币种信用卡") }
        assertEquals(WalletType.CREDIT, createdCredit.type)
        assertEquals("信用账户负值溢缴款严禁被 abs 翻转为正欠款", -5_000L, createdCredit.balanceCents)

        val loanInTx = combinedPreview.parsedTransactions.single()
        assertEquals(TransactionType.LOAN_IN, loanInTx.type)
        assertNull("LOAN_IN 的 sourceWalletId 必须为 null", loanInTx.sourceWalletId)
        assertEquals("LOAN_IN 必须将收款账户绑定至 targetWalletId", 2L, loanInTx.targetWalletId)
    }

    /**
     * AC-5 & AC-7（B5 & B7）：新装预设账户零余额无私人尾号，外币分批全额退款零尾差。
     */
    @Test
    fun ac5_and_ac7_defaultWalletsDeprivatized_and_foreignRefundZeroTailResidue() = runBlocking {
        val defaults = AppDatabase.defaultWallets()
        assertEquals(5, defaults.size)
        assertEquals(listOf("现金", "微信零钱", "支付宝", "储蓄卡", "信用卡"), defaults.map { it.name })
        assertTrue(defaults.all { it.balanceCents == 0L && it.cardLast4 == null && it.creditLimitCents == null })

        val usdCard = WalletEntity(id = 5L, name = "外币卡", type = WalletType.CREDIT, currency = "USD", balanceCents = 0L)
        val store = InMemoryLedgerStore(listOf(usdCard))
        val expId = LedgerRepository.recordSingleTransactionOnStore(
            store = store,
            tx = TransactionEntity(
                type = TransactionType.EXPENSE,
                amountCents = 1_000L, // $10.00
                currency = "USD",
                exchangeRateToCny = 7.13,
                amountCnyCents = 7_130L,
                category = "购物",
                merchant = "Amazon",
                sourceWalletId = 5L
            )
        )
        assertEquals(1_000L, LedgerRepository.computeRemainingRefundableOrigCents(store.getTransactionById(expId)!!))

        // 先退 $3.33，再退剩余 $6.67，全额退完后本位币与原币种可退余额均归零无尾差
        assertNotNull(LedgerRepository.refundExpenseOnStore(store, expId, refundAmountCents = 333L, receiveWalletId = 5L))
        val afterFirst = store.getTransactionById(expId)!!
        assertEquals(667L, LedgerRepository.computeRemainingRefundableOrigCents(afterFirst))

        assertNotNull(LedgerRepository.refundExpenseOnStore(store, expId, refundAmountCents = 667L, receiveWalletId = 5L))
        val afterFull = store.getTransactionById(expId)!!
        assertEquals(7_130L, afterFull.refundedAmountCents)
        assertEquals(0L, LedgerRepository.computeRemainingRefundableOrigCents(afterFull))
        assertEquals(0L, store.getWalletById(5L)!!.balanceCents)
    }

    /**
     * AC-8（B9 & B10-b）：医保多帧融合子单金额变化时回滚重算/清零删除，且 USD 账单优先按尾号匹配信用卡。
     */
    @Test
    fun ac8_medicalMultiFrameChildRecalibration_and_usdTail4PriorityMatching() = runBlocking {
        val credit3354 = WalletEntity(
            id = 5L,
            name = "招商银行信用卡",
            type = WalletType.CREDIT,
            cardLast4 = "3354",
            currency = "CNY",
            balanceCents = 0L
        )
        val genericUsdWallet = WalletEntity(
            id = 9L,
            name = "美元钱包",
            type = WalletType.DIGITAL_WALLET,
            currency = "USD",
            balanceCents = 10_000L
        )
        val medicalWallet = WalletEntity(
            id = 88L,
            name = "个人医保账户",
            type = WalletType.CASH_OTHER,
            subTypeLabel = "医保账户",
            brandCode = "MEDICAL_CARD",
            balanceCents = 200_000L,
            aliases = DedicatedFundKind.MEDICAL_INSURANCE.defaultAliases,
            includeInNetWorth = false
        )

        // 1. B10-b：USD 账单携带尾号 3354 时，必须优先命中招商银行信用卡(3354)，而非跳过尾号回退美元钱包
        val engine = AiAccountingEngine(configManager = null, merchantMemoryManager = null)
        val usdParsed = engine.localSmartFallbackParse(
            rawText = "Apple Services | USD 9.99 | 支付成功 | 付款方式 | 招商银行信用卡(3354) | 支付时间 | 2026-11-20 12:30:00 | 交易单号 | TXUSD001",
            sourceAppPackage = "com.tencent.mm",
            wallets = listOf(genericUsdWallet, credit3354, medicalWallet),
            recentExpenses = emptyList()
        )
        assertNotNull(usdParsed)
        assertEquals("USD", usdParsed!!.currency)
        assertEquals(credit3354.id, usdParsed.sourceWalletId)

        // 2. B9：医保多帧融合个账金额从 ¥50.00 校准为 ¥20.00，再校准为 ¥0.00 时，个人医保账户余额严格守恒
        val store = InMemoryLedgerStore(listOf(credit3354, medicalWallet))
        val initialRes = LedgerRepository.recordCompositeMedicalOnStore(
            store = store,
            mainTx = TransactionEntity(
                type = TransactionType.EXPENSE,
                amountCents = 3_000L,
                amountCnyCents = 3_000L,
                category = "医疗-门诊",
                merchant = "省人民医院",
                sourceWalletId = credit3354.id,
                orderKey = "MED20261120"
            ),
            poolSubsidyCents = 4_000L,
            personalAccountCents = 5_000L
        )
        val mainId = initialRes.mainTransaction.id
        assertEquals(195_000L, store.getWalletById(88L)!!.balanceCents)

        // 第二帧校准个账为 ¥20.00（2_000L）-> 医保卡回滚 5_000L 再扣 2_000L = 198_000L
        val recalibrated = LedgerRepository.enrichCompositeMedicalOnStore(
            store = store,
            existingMainId = mainId,
            enrichedCandidateMain = store.getTransactionById(mainId)!!,
            baseEnrichedFields = emptyList(),
            poolSubsidyCents = 7_000L,
            personalAccountCents = 2_000L
        )
        assertTrue(recalibrated.wasModified)
        assertEquals(198_000L, store.getWalletById(88L)!!.balanceCents)
        assertEquals(2_000L, recalibrated.createdChildTransaction?.amountCents)

        // 第三帧确认全部为统筹 ¥90.00、个账 ¥0.00 -> 删除旧个账子单并恢复医保卡初始余额 200_000L
        val clearedChild = LedgerRepository.enrichCompositeMedicalOnStore(
            store = store,
            existingMainId = mainId,
            enrichedCandidateMain = store.getTransactionById(mainId)!!,
            baseEnrichedFields = emptyList(),
            poolSubsidyCents = 9_000L,
            personalAccountCents = 0L
        )
        assertTrue(clearedChild.wasModified)
        assertEquals(200_000L, store.getWalletById(88L)!!.balanceCents)
        assertEquals(1, store.getAllTransactions().size)
    }

    /**
     * AC-10（B12/B13/B15）：删父单清理悬空退款 relatedTransactionId 与刷新钱包 syncDirty、
     * 统计净资产趋势按 reimbursedAtMillis 分桶、MerchantMemoryManager 2,000 条保留上限。
     */
    @Test
    fun ac10_danglingRefundCleanup_walletSyncDirty_reimbursedNetWorthEventBucket_and_memoryCap() = runBlocking {
        val wallet = WalletEntity(
            id = 2L,
            name = "微信零钱",
            type = WalletType.DIGITAL_WALLET,
            balanceCents = 50_000L,
            updatedAtMillis = 10L,
            syncDirty = false,
            includeInNetWorth = true
        )
        val store = InMemoryLedgerStore(listOf(wallet))

        val expId = LedgerRepository.recordSingleTransactionOnStore(
            store = store,
            tx = TransactionEntity(
                type = TransactionType.EXPENSE,
                amountCents = 8_000L,
                amountCnyCents = 8_000L,
                category = "购物",
                merchant = "京东自营",
                sourceWalletId = 2L
            ),
            nowMillis = 5_000L
        )
        assertTrue(store.getWalletById(2L)!!.syncDirty)
        assertEquals(5_000L, store.getWalletById(2L)!!.updatedAtMillis)

        val refundId = LedgerRepository.refundExpenseOnStore(
            store = store,
            expenseId = expId,
            refundAmountCents = 3_000L,
            receiveWalletId = 2L,
            nowMillis = 6_000L
        )!!
        assertEquals(expId, store.getTransactionById(refundId)!!.relatedTransactionId)

        // 删除原支出单后，保留的退款单应清理悬空 relatedTransactionId = null
        assertTrue(LedgerRepository.undoAndDeleteTransactionOnStore(store, expId).first)
        assertNull(store.getTransactionById(refundId)!!.relatedTransactionId)

        // B13：已报销账单净资产变动拆分为消费时刻流出与 reimbursedAtMillis 核销时刻流入
        val reimbTx = TransactionEntity(
            id = 500L,
            type = TransactionType.EXPENSE,
            amountCents = 12_000L,
            amountCnyCents = 12_000L,
            category = "交通-打车",
            sourceWalletId = 2L,
            targetWalletId = 2L,
            sourceWalletDeltaCents = 12_000L,
            targetWalletDeltaCents = 12_000L,
            reimbursable = true,
            reimbursed = true,
            timestamp = 1_000_000L,
            reimbursedAtMillis = 9_000_000L
        )
        val events = netWorthImpactEvents(reimbTx, mapOf(2L to wallet))
        assertEquals(2, events.size)
        assertEquals(1_000_000L to (-12_000L to 0L), events[0])
        assertEquals(9_000_000L to (12_000L to 0L), events[1])

        // B15：MerchantMemoryManager 最多保留最近更新的 2,000 条记录
        val prefs = FakeSharedPreferences()
        val seedEntries = (1..2_049).map { idx ->
            MerchantMemoryEntry(
                merchantKeyword = "测试商户_$idx",
                category = "餐饮",
                updatedAtMillis = idx.toLong()
            )
        }
        val memoryManager = MerchantMemoryManager(prefs = prefs, initialEntries = seedEntries)
        memoryManager.upsertMemory("最新高频咖啡店", "餐饮-咖啡", preferredWalletId = 2L)
        val retained = memoryManager.memoriesFlow.value
        assertEquals(2_000, retained.size)
        assertEquals("最新高频咖啡店", retained.first().merchantKeyword)
        assertFalse("最早期的超限旧记忆应被淘汰", retained.any { it.merchantKeyword == "测试商户_1" })
    }
}
