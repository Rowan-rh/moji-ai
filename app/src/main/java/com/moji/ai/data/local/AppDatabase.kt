package com.moji.ai.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.moji.ai.data.model.RecurrenceFrequency
import com.moji.ai.data.model.TransactionType
import com.moji.ai.data.model.WalletType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class LedgerTypeConverters {
    @TypeConverter
    fun fromWalletType(value: WalletType): String = value.name

    @TypeConverter
    fun toWalletType(value: String): WalletType = WalletType.valueOf(value)

    @TypeConverter
    fun fromTransactionType(value: TransactionType): String = value.name

    @TypeConverter
    fun toTransactionType(value: String): TransactionType = TransactionType.valueOf(value)

    @TypeConverter
    fun fromRecurrenceFrequency(value: RecurrenceFrequency): String = value.name

    @TypeConverter
    fun toRecurrenceFrequency(value: String): RecurrenceFrequency = RecurrenceFrequency.valueOf(value)
}

@Dao
interface WalletDao {
    @Query("SELECT * FROM wallets WHERE deletedAtMillis IS NULL ORDER BY id ASC")
    fun observeAllWallets(): Flow<List<WalletEntity>>

    @Query("SELECT * FROM wallets WHERE deletedAtMillis IS NULL ORDER BY id ASC")
    suspend fun getAllWallets(): List<WalletEntity>

    @Query("SELECT * FROM wallets WHERE id = :id LIMIT 1")
    suspend fun getWalletById(id: Long): WalletEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWallet(wallet: WalletEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(wallets: List<WalletEntity>)

    @Update
    suspend fun updateWallet(wallet: WalletEntity)

    @Delete
    suspend fun deleteWallet(wallet: WalletEntity)
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE deletedAtMillis IS NULL ORDER BY timestamp DESC, capturedAtMillis DESC")
    fun observeAllTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE deletedAtMillis IS NULL ORDER BY timestamp DESC, capturedAtMillis DESC")
    suspend fun getAllTransactions(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE deletedAtMillis IS NULL AND type = 'EXPENSE' AND (amountCnyCents - refundedAmountCents) > 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentRefundableExpenses(limit: Int = 15): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getTransactionById(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE deletedAtMillis IS NULL AND orderKey = :orderKey AND orderKey != '' LIMIT 1")
    suspend fun findByOrderKey(orderKey: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE deletedAtMillis IS NULL AND transactionNo = :txNo AND transactionNo != '' LIMIT 1")
    suspend fun findByTransactionNo(txNo: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE deletedAtMillis IS NULL AND merchantOrderNo = :orderNo AND merchantOrderNo != '' LIMIT 1")
    suspend fun findByMerchantOrderNo(orderNo: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE deletedAtMillis IS NULL AND screenshotSha256 = :sha256 AND screenshotSha256 != '' LIMIT 1")
    suspend fun findByScreenshotSha256(sha256: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE deletedAtMillis IS NULL AND amountCents = :amountCents AND ABS(timestamp - :occurredAtMillis) <= :toleranceMillis ORDER BY id DESC")
    suspend fun findByAmountAndOccurredWindow(
        amountCents: Long,
        occurredAtMillis: Long,
        toleranceMillis: Long = 60_000L
    ): List<TransactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(tx: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllTransactions(items: List<TransactionEntity>)

    @Update
    suspend fun updateTransaction(tx: TransactionEntity)

    @Delete
    suspend fun deleteTransaction(tx: TransactionEntity)

    @Query("DELETE FROM transactions")
    suspend fun deleteAllTransactions()
}

@Dao
interface CaptureEvidenceDao {
    @Query("SELECT * FROM capture_evidences WHERE transactionId = :txId ORDER BY capturedAtMillis DESC")
    suspend fun getEvidencesForTransaction(txId: Long): List<CaptureEvidenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvidence(evidence: CaptureEvidenceEntity): Long

    @Query("DELETE FROM capture_evidences")
    suspend fun deleteAllEvidences()
}

@Dao
interface RecurringRuleDao {
    @Query("SELECT * FROM recurring_rules ORDER BY nextTriggerTimeMillis ASC")
    fun observeAllRules(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rules ORDER BY id ASC")
    suspend fun getAllRules(): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rules WHERE enabled = 1 AND nextTriggerTimeMillis <= :nowMillis")
    suspend fun getDueRules(nowMillis: Long): List<RecurringRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: RecurringRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllRules(rules: List<RecurringRuleEntity>)

    @Update
    suspend fun updateRule(rule: RecurringRuleEntity)

    @Delete
    suspend fun deleteRule(rule: RecurringRuleEntity)
}

@Database(
    entities = [
        WalletEntity::class,
        TransactionEntity::class,
        CaptureEvidenceEntity::class,
        RecurringRuleEntity::class
    ],
    version = 5,
    exportSchema = false
)
@TypeConverters(LedgerTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun walletDao(): WalletDao
    abstract fun transactionDao(): TransactionDao
    abstract fun captureEvidenceDao(): CaptureEvidenceDao
    abstract fun recurringRuleDao(): RecurringRuleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wallets ADD COLUMN syncUuid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE wallets ADD COLUMN updatedAtMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE wallets ADD COLUMN deletedAtMillis INTEGER")
                db.execSQL("ALTER TABLE wallets ADD COLUMN syncDirty INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wallets_syncUuid ON wallets(syncUuid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wallets_cardLast4 ON wallets(cardLast4)")

                db.execSQL("ALTER TABLE transactions ADD COLUMN syncUuid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN capturedAtMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE transactions SET capturedAtMillis = timestamp WHERE capturedAtMillis = 0")
                db.execSQL("ALTER TABLE transactions ADD COLUMN transactionNo TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN merchantOrderNo TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN paymentMethodRaw TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN screenshotPath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN screenshotSha256 TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN dedupFingerprint TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN duplicateSuspected INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transactions ADD COLUMN updatedAtMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transactions ADD COLUMN deletedAtMillis INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN syncRevision INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE transactions ADD COLUMN syncDirty INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_timestamp ON transactions(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_transactionNo ON transactions(transactionNo)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_merchantOrderNo ON transactions(merchantOrderNo)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_dedupFingerprint ON transactions(dedupFingerprint)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_syncUuid ON transactions(syncUuid)")

                db.execSQL("ALTER TABLE recurring_rules ADD COLUMN syncUuid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE recurring_rules ADD COLUMN updatedAtMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE recurring_rules ADD COLUMN syncDirty INTEGER NOT NULL DEFAULT 1")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS capture_evidences (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        syncUuid TEXT NOT NULL,
                        transactionId INTEGER NOT NULL,
                        captureSource TEXT NOT NULL,
                        sourcePackage TEXT NOT NULL,
                        occurredAtMillis INTEGER NOT NULL,
                        capturedAtMillis INTEGER NOT NULL,
                        amountCents INTEGER NOT NULL,
                        transactionNo TEXT NOT NULL,
                        merchantOrderNo TEXT NOT NULL,
                        paymentMethodRaw TEXT NOT NULL,
                        screenshotPath TEXT NOT NULL,
                        screenshotSha256 TEXT NOT NULL,
                        rawText TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_capture_evidences_transactionId ON capture_evidences(transactionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_capture_evidences_transactionNo ON capture_evidences(transactionNo)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_capture_evidences_syncUuid ON capture_evidences(syncUuid)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN itemQuantity INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE transactions ADD COLUMN quantityDesc TEXT NOT NULL DEFAULT '1件'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN orderKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_orderKey ON transactions(orderKey)")

                // 清理之前因列表页/收银台及同时间同金额未拦截而重复录入的脏数据，并补全保留账单的唯一订单 Key
                db.execSQL("DELETE FROM transactions WHERE id IN (20, 21, 22, 23, 24, 25, 28, 29, 30, 32, 33, 34, 35, 36)")
                db.execSQL(
                    """
                    UPDATE transactions
                    SET transactionNo = '4200003210202611053385111851',
                        merchantOrderNo = '261105130824764996',
                        orderKey = '4200003210202611053385111851',
                        paymentMethodRaw = '招商银行信用卡(3354)',
                        timestamp = 1793874265000
                    WHERE id = 17
                    """.trimIndent()
                )
                db.execSQL("UPDATE transactions SET duplicateSuspected = 0, itemQuantity = 2, quantityDesc = '多件' WHERE id IN (27, 31)")
                db.execSQL("UPDATE transactions SET orderKey = transactionNo WHERE orderKey = '' AND transactionNo != ''")
                db.execSQL("UPDATE transactions SET orderKey = merchantOrderNo WHERE orderKey = '' AND merchantOrderNo != ''")

                // 校准被重复账单污染的三个钱包余额（花呗归零、账户余额与招行信用卡恢复真实值）
                db.execSQL("UPDATE wallets SET balanceCents = 0 WHERE id = 2")
                db.execSQL("UPDATE wallets SET balanceCents = 345734 WHERE id = 4")
                db.execSQL("UPDATE wallets SET balanceCents = 397467 WHERE id = 1")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "moji_ai.db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .addCallback(object : Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                val database = getInstance(context)
                                if (database.walletDao().getAllWallets().isEmpty()) {
                                    database.walletDao().insertAll(defaultWallets())
                                    database.recurringRuleDao().insertRule(defaultRecurringRule())
                                    defaultSampleTransactions().forEach { tx ->
                                        database.transactionDao().insertTransaction(tx)
                                    }
                                }
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private fun defaultWallets(): List<WalletEntity> = listOf(
            WalletEntity(
                id = 1,
                name = "招商银行信用卡",
                type = WalletType.CREDIT,
                subTypeLabel = "信用卡",
                brandCode = "CMB",
                currency = "CNY",
                balanceCents = 396526L,
                creditLimitCents = 1000000L,
                cardLast4 = "3354",
                aliases = "招商银行信用卡,掌上生活,招行信用卡,招商银行,3354",
                billingDay = 5,
                repaymentDay = 15,
                colorHex = 0xFFD92B2B
            ),
            WalletEntity(
                id = 2,
                name = "花呗",
                type = WalletType.CREDIT,
                subTypeLabel = "蚂蚁花呗",
                brandCode = "HUABEI",
                currency = "CNY",
                balanceCents = 0L,
                creditLimitCents = 3410000L,
                aliases = "花呗,蚂蚁花呗,信用购",
                billingDay = 1,
                repaymentDay = 15,
                colorHex = 0xFF00A0E9
            ),
            WalletEntity(
                id = 3,
                name = "京东白条",
                type = WalletType.CREDIT,
                subTypeLabel = "京东白条",
                brandCode = "JD",
                currency = "CNY",
                balanceCents = 296164L,
                creditLimitCents = 1000000L,
                aliases = "白条,京东白条,京东金融",
                billingDay = 10,
                repaymentDay = 25,
                colorHex = 0xFFE1251B
            ),
            WalletEntity(
                id = 4,
                name = "账户余额",
                type = WalletType.DIGITAL_WALLET,
                subTypeLabel = "支付宝余额/余额宝",
                brandCode = "ALIPAY",
                currency = "CNY",
                balanceCents = 352000L,
                aliases = "支付宝,余额宝,账户余额,Alipay",
                colorHex = 0xFF1677FF
            ),
            WalletEntity(
                id = 5,
                name = "微信零钱",
                type = WalletType.DIGITAL_WALLET,
                subTypeLabel = "微信支付",
                brandCode = "WECHAT",
                currency = "CNY",
                balanceCents = 124922L,
                aliases = "微信,微信支付,零钱,零钱通,WeChat",
                colorHex = 0xFF07C160
            ),
            WalletEntity(
                id = 6,
                name = "招商银行储蓄卡",
                type = WalletType.DEBIT_BANK,
                subTypeLabel = "储蓄卡",
                brandCode = "CMB",
                currency = "CNY",
                balanceCents = 230000L,
                cardLast4 = "8848",
                aliases = "招行卡,一卡通,工资卡,招商银行储蓄卡,8848",
                colorHex = 0xFFD92B2B
            )
        )

        private fun defaultRecurringRule(): RecurringRuleEntity = RecurringRuleEntity(
            title = "每月房租",
            type = TransactionType.EXPENSE,
            amountCents = 250000L,
            currency = "CNY",
            category = "住房",
            walletId = 6L,
            frequency = RecurrenceFrequency.MONTHLY,
            dayOfMonthOrWeek = 5,
            nextTriggerTimeMillis = System.currentTimeMillis() + 10 * 86400_000L,
            enabled = true
        )

        private fun defaultSampleTransactions(): List<TransactionEntity> {
            val now = System.currentTimeMillis()
            return listOf(
                TransactionEntity(
                    id = 1,
                    type = TransactionType.EXPENSE,
                    amountCents = 500L,
                    currency = "CNY",
                    exchangeRateToCny = 1.0,
                    amountCnyCents = 500L,
                    category = "餐饮-零食",
                    merchant = "唐久(环普产业园店)",
                    sourceWalletId = 4L,
                    refundedAmountCents = 500L,
                    timestamp = now - 7200_000L,
                    capturedAtMillis = now - 7200_000L,
                    captureSource = "AI_NOTIFICATION"
                ),
                TransactionEntity(
                    id = 2,
                    type = TransactionType.EXPENSE,
                    amountCents = 500L,
                    currency = "CNY",
                    exchangeRateToCny = 1.0,
                    amountCnyCents = 500L,
                    category = "餐饮-零食",
                    merchant = "唐久(环普产业园店)",
                    sourceWalletId = 4L,
                    timestamp = now - 7100_000L,
                    capturedAtMillis = now - 7100_000L,
                    captureSource = "AI_NOTIFICATION"
                ),
                TransactionEntity(
                    id = 3,
                    type = TransactionType.EXPENSE,
                    amountCents = 2739L,
                    currency = "CNY",
                    exchangeRateToCny = 1.0,
                    amountCnyCents = 2739L,
                    discountCents = 300L,
                    category = "购物",
                    merchant = "京东",
                    orderKey = "3673486013948720",
                    transactionNo = "3673486013948720",
                    paymentMethodRaw = "招商银行信用卡(3354)",
                    sourceWalletId = 1L,
                    timestamp = now - 5400_000L,
                    capturedAtMillis = now - 5400_000L,
                    captureSource = "AI_MERGED"
                ),
                TransactionEntity(
                    id = 4,
                    type = TransactionType.REFUND_OFFSET,
                    amountCents = 500L,
                    currency = "CNY",
                    exchangeRateToCny = 1.0,
                    amountCnyCents = 500L,
                    category = "退款入账",
                    merchant = "零食 · 唐久(环普产业园店)",
                    sourceWalletId = null,
                    targetWalletId = 4L,
                    relatedTransactionId = 1L,
                    timestamp = now - 3600_000L,
                    capturedAtMillis = now - 3600_000L,
                    captureSource = "AI_NOTIFICATION"
                ),
                TransactionEntity(
                    id = 5,
                    type = TransactionType.EXPENSE,
                    amountCents = 1584L,
                    currency = "CNY",
                    exchangeRateToCny = 1.0,
                    amountCnyCents = 1584L,
                    category = "餐饮",
                    merchant = "盒马",
                    sourceWalletId = 4L,
                    timestamp = now - 1200_000L,
                    capturedAtMillis = now - 1200_000L,
                    captureSource = "AI_NOTIFICATION"
                )
            )
        }
    }
}
