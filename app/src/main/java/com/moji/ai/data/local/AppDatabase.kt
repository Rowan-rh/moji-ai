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
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.moji.ai.data.backup.MoJiCloudSyncClient
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
    fun toWalletType(value: String): WalletType =
        runCatching { WalletType.valueOf(value) }.getOrDefault(WalletType.DIGITAL_WALLET)

    @TypeConverter
    fun fromTransactionType(value: TransactionType): String = value.name

    @TypeConverter
    fun toTransactionType(value: String): TransactionType =
        runCatching { TransactionType.valueOf(value) }.getOrDefault(TransactionType.EXPENSE)

    @TypeConverter
    fun fromRecurrenceFrequency(value: RecurrenceFrequency): String = value.name

    @TypeConverter
    fun toRecurrenceFrequency(value: String): RecurrenceFrequency =
        runCatching { RecurrenceFrequency.valueOf(value) }.getOrDefault(RecurrenceFrequency.MONTHLY)
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

    @Query("DELETE FROM wallets")
    suspend fun deleteAllWallets()
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

    @Query(
        """
        SELECT * FROM transactions
        WHERE deletedAtMillis IS NULL
          AND amountCents = :amountCents
          AND (
              ABS(timestamp - :occurredAtMillis) <= :toleranceMillis
              OR (capturedAtMillis > 0 AND ABS(capturedAtMillis - :capturedAtMillis) <= :toleranceMillis)
          )
        ORDER BY id DESC
        """
    )
    suspend fun findByAmountAndTimeOrCapturedWindow(
        amountCents: Long,
        occurredAtMillis: Long,
        capturedAtMillis: Long,
        toleranceMillis: Long = 65_000L
    ): List<TransactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(tx: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllTransactions(items: List<TransactionEntity>)

    @Update
    suspend fun updateTransaction(tx: TransactionEntity)

    @Query(
        """
        UPDATE transactions
        SET category = :newCategory,
            updatedAtMillis = :nowMillis,
            syncRevision = syncRevision + 1,
            syncDirty = 1
        WHERE id = :id
        """
    )
    suspend fun updateTransactionCategoryById(
        id: Long,
        newCategory: String,
        nowMillis: Long
    )

    @Query(
        """
        UPDATE transactions
        SET tags = :newTags,
            updatedAtMillis = :nowMillis,
            syncRevision = syncRevision + 1,
            syncDirty = 1
        WHERE id = :id
        """
    )
    suspend fun updateTransactionTagsById(
        id: Long,
        newTags: String,
        nowMillis: Long
    )

    @Query(
        """
        SELECT * FROM transactions
        WHERE deletedAtMillis IS NULL
          AND type = 'EXPENSE'
          AND relatedTransactionId = :parentId
        ORDER BY id ASC
        """
    )
    suspend fun getSplitChildrenOfExpense(parentId: Long): List<TransactionEntity>

    @Query(
        """
        UPDATE transactions
        SET screenshotSha256 = :sha256
        WHERE id = :id AND screenshotSha256 != :sha256
        """
    )
    suspend fun updateScreenshotSha256ById(id: Long, sha256: String)

    @Query(
        """
        UPDATE transactions
        SET screenshotPath = :screenshotPath,
            screenshotSha256 = :screenshotSha256,
            updatedAtMillis = :nowMillis,
            syncRevision = syncRevision + 1,
            syncDirty = 1
        WHERE id = :id
          AND deletedAtMillis IS NULL
          AND screenshotPath = ''
        """
    )
    suspend fun attachScreenshotIfBlank(
        id: Long,
        screenshotPath: String,
        screenshotSha256: String,
        nowMillis: Long
    ): Int

    @Delete
    suspend fun deleteTransaction(tx: TransactionEntity)

    @Query("DELETE FROM transactions")
    suspend fun deleteAllTransactions()

    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE deletedAtMillis IS NULL
          AND timestamp BETWEEN :startMillis AND :endMillis
          AND captureSource != 'BALANCE_ANCHOR'
          AND category != '平账'
        """
    )
    suspend fun countNonAnchorTransactionsInRange(startMillis: Long, endMillis: Long): Int

    @Query("SELECT tags FROM transactions WHERE deletedAtMillis IS NULL AND tags != ''")
    suspend fun getAllNonEmptyTagStrings(): List<String>
}

@Dao
interface CaptureEvidenceDao {
    @Query("SELECT * FROM capture_evidences ORDER BY capturedAtMillis DESC")
    suspend fun getAllEvidences(): List<CaptureEvidenceEntity>

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

    @Query(
        """
        UPDATE recurring_rules
        SET category = :newCategory,
            updatedAtMillis = :nowMillis,
            syncDirty = 1
        WHERE id = :id
        """
    )
    suspend fun updateRuleCategoryById(
        id: Long,
        newCategory: String,
        nowMillis: Long
    )

    @Delete
    suspend fun deleteRule(rule: RecurringRuleEntity)

    @Query("DELETE FROM recurring_rules")
    suspend fun deleteAllRules()
}

@Database(
    entities = [
        WalletEntity::class,
        TransactionEntity::class,
        CaptureEvidenceEntity::class,
        RecurringRuleEntity::class
    ],
    version = 6,
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
                db.execSQL("UPDATE transactions SET orderKey = transactionNo WHERE orderKey = '' AND transactionNo != ''")
                db.execSQL("UPDATE transactions SET orderKey = merchantOrderNo WHERE orderKey = '' AND merchantOrderNo != ''")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN sourceWalletDeltaCents INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN targetWalletDeltaCents INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN reimbursedAtMillis INTEGER")
            }
        }

        private const val SEED_PREFS_NAME = "moji_db_seed_prefs"
        private const val KEY_INITIAL_WALLETS_SEEDED = "initial_wallets_seeded_v1"

        fun markInitialWalletsSeeded(context: Context) {
            context.applicationContext
                .getSharedPreferences(SEED_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_INITIAL_WALLETS_SEEDED, true)
                .apply()
        }

        private suspend fun seedDefaultWalletsIfUntouched(appContext: Context) {
            if (MoJiCloudSyncClient.shouldSuppressAutoSync()) return
            val seedPrefs = appContext.getSharedPreferences(SEED_PREFS_NAME, Context.MODE_PRIVATE)
            if (seedPrefs.getBoolean(KEY_INITIAL_WALLETS_SEEDED, false)) return
            val database = getInstance(appContext)
            database.withTransaction {
                if (MoJiCloudSyncClient.shouldSuppressAutoSync()) return@withTransaction
                if (seedPrefs.getBoolean(KEY_INITIAL_WALLETS_SEEDED, false)) return@withTransaction
                val existingWallets = database.walletDao().getAllWallets()
                val existingTxs = database.transactionDao().getAllTransactions()
                if (existingWallets.isEmpty() && existingTxs.isEmpty()) {
                    database.walletDao().insertAll(defaultWallets())
                }
                seedPrefs.edit().putBoolean(KEY_INITIAL_WALLETS_SEEDED, true).apply()
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val appContext = context.applicationContext
                val builder = Room.databaseBuilder(
                    appContext,
                    AppDatabase::class.java,
                    "moji_ai.db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                seedDefaultWalletsIfUntouched(appContext)
                            }
                        }

                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                seedDefaultWalletsIfUntouched(appContext)
                            }
                        }
                    })
                if (com.moji.ai.BuildConfig.IS_QA_BUILD) {
                    @Suppress("DEPRECATION")
                    builder.fallbackToDestructiveMigration()
                }
                val instance = builder.build()
                INSTANCE = instance
                instance
            }
        }

        fun defaultWallets(): List<WalletEntity> = listOf(
            WalletEntity(
                id = 1,
                name = "现金",
                type = WalletType.CASH_OTHER,
                subTypeLabel = "现金",
                brandCode = "CASH",
                currency = "CNY",
                balanceCents = 0L,
                cardLast4 = null,
                aliases = "现金,Cash",
                colorHex = 0xFFF5B400
            ),
            WalletEntity(
                id = 2,
                name = "微信零钱",
                type = WalletType.DIGITAL_WALLET,
                subTypeLabel = "微信钱包",
                brandCode = "WECHAT",
                currency = "CNY",
                balanceCents = 0L,
                cardLast4 = null,
                aliases = "微信,微信支付,零钱,零钱通,微信零钱,WeChat",
                colorHex = 0xFF07C160
            ),
            WalletEntity(
                id = 3,
                name = "支付宝",
                type = WalletType.DIGITAL_WALLET,
                subTypeLabel = "支付宝",
                brandCode = "ALIPAY",
                currency = "CNY",
                balanceCents = 0L,
                cardLast4 = null,
                aliases = "支付宝,余额宝,账户余额,Alipay",
                colorHex = 0xFF1677FF
            ),
            WalletEntity(
                id = 4,
                name = "储蓄卡",
                type = WalletType.DEBIT_BANK,
                subTypeLabel = "储蓄卡",
                brandCode = "DEBIT_CARD",
                currency = "CNY",
                balanceCents = 0L,
                cardLast4 = null,
                aliases = "储蓄卡,借记卡,银行卡",
                colorHex = 0xFF22B573
            ),
            WalletEntity(
                id = 5,
                name = "信用卡",
                type = WalletType.CREDIT,
                subTypeLabel = "信用卡",
                brandCode = "CREDIT_CARD",
                currency = "CNY",
                balanceCents = 0L,
                creditLimitCents = null,
                cardLast4 = null,
                billingDay = null,
                repaymentDay = null,
                aliases = "信用卡,贷记卡",
                colorHex = 0xFFE65540
            )
        )
    }
}
