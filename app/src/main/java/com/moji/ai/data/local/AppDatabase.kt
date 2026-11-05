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
    @Query("SELECT * FROM wallets ORDER BY id ASC")
    fun observeAllWallets(): Flow<List<WalletEntity>>

    @Query("SELECT * FROM wallets ORDER BY id ASC")
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
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun observeAllTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE type = 'EXPENSE' AND (amountCents - refundedAmountCents) > 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentRefundableExpenses(limit: Int = 15): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getTransactionById(id: Long): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(tx: TransactionEntity): Long

    @Update
    suspend fun updateTransaction(tx: TransactionEntity)

    @Delete
    suspend fun deleteTransaction(tx: TransactionEntity)
}

@Dao
interface RecurringRuleDao {
    @Query("SELECT * FROM recurring_rules ORDER BY nextTriggerTimeMillis ASC")
    fun observeAllRules(): Flow<List<RecurringRuleEntity>>

    @Query("SELECT * FROM recurring_rules WHERE enabled = 1 AND nextTriggerTimeMillis <= :nowMillis")
    suspend fun getDueRules(nowMillis: Long): List<RecurringRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: RecurringRuleEntity): Long

    @Update
    suspend fun updateRule(rule: RecurringRuleEntity)

    @Delete
    suspend fun deleteRule(rule: RecurringRuleEntity)
}

@Database(
    entities = [WalletEntity::class, TransactionEntity::class, RecurringRuleEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(LedgerTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun walletDao(): WalletDao
    abstract fun transactionDao(): TransactionDao
    abstract fun recurringRuleDao(): RecurringRuleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "moji_ai.db"
                )
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
                    note = "全额退款冲销演示",
                    sourceWalletId = 2L,
                    refundedAmountCents = 500L, // 已全额退款
                    timestamp = now - 7200_000L,
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
                    sourceWalletId = 2L,
                    timestamp = now - 7100_000L,
                    captureSource = "AI_NOTIFICATION"
                ),
                TransactionEntity(
                    id = 3,
                    type = TransactionType.EXPENSE,
                    amountCents = 2739L,
                    currency = "CNY",
                    exchangeRateToCny = 1.0,
                    amountCnyCents = 2739L,
                    category = "购物",
                    merchant = "京东-订单编号3673486013948720",
                    sourceWalletId = 5L, // 招商银行信用卡
                    timestamp = now - 5400_000L,
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
                    targetWalletId = 2L,
                    relatedTransactionId = 1L,
                    timestamp = now - 3600_000L,
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
                    sourceWalletId = 2L,
                    timestamp = now - 1200_000L,
                    captureSource = "AI_NOTIFICATION"
                )
            )
        }

        private fun defaultWallets(): List<WalletEntity> = listOf(
            WalletEntity(
                name = "微信零钱",
                type = WalletType.DIGITAL_WALLET,
                currency = "CNY",
                balanceCents = 128550L, // 1,285.50元
                aliases = "微信支付,零钱,零钱通,WeChat Pay",
                colorHex = 0xFF07C160
            ),
            WalletEntity(
                name = "支付宝余额宝",
                type = WalletType.DIGITAL_WALLET,
                currency = "CNY",
                balanceCents = 542000L, // 5,420.00元
                aliases = "支付宝,余额宝,账户余额,Alipay",
                colorHex = 0xFF1677FF
            ),
            WalletEntity(
                name = "招商银行储蓄卡",
                type = WalletType.DEBIT_BANK,
                currency = "CNY",
                balanceCents = 2680000L, // 26,800.00元
                cardLast4 = "8848",
                aliases = "招行卡,一卡通,工资卡,招商银行(8848)",
                colorHex = 0xFFDC2626
            ),
            WalletEntity(
                name = "蚂蚁花呗",
                type = WalletType.CREDIT,
                currency = "CNY",
                balanceCents = 86400L, // 当前待还 864.00元
                creditLimitCents = 1500000L, // 总额度 15,000.00元
                aliases = "花呗,信用购",
                billingDay = 1,
                repaymentDay = 10,
                colorHex = 0xFF0284C7
            ),
            WalletEntity(
                name = "招行全币种信用卡",
                type = WalletType.CREDIT,
                currency = "CNY",
                balanceCents = 231000L, // 当前待还 2,310.00元
                creditLimitCents = 5000000L, // 总额度 50,000.00元
                cardLast4 = "6612",
                aliases = "招商银行信用卡,掌上生活,信用卡(6612)",
                billingDay = 15,
                repaymentDay = 3,
                colorHex = 0xFF7C3AED
            ),
            WalletEntity(
                name = "美元外币卡 (USD)",
                type = WalletType.DEBIT_BANK,
                currency = "USD",
                balanceCents = 85000L, // $850.00 USD
                cardLast4 = "9011",
                aliases = "Visa,MasterCard,美元卡,海淘卡",
                colorHex = 0xFF0D9488
            )
        )

        private fun defaultRecurringRule(): RecurringRuleEntity = RecurringRuleEntity(
            title = "房租自动扣款",
            type = TransactionType.EXPENSE,
            amountCents = 280000L, // 2800.00元
            currency = "CNY",
            category = "居住-房租物业",
            walletId = 3L, // 招商银行储蓄卡
            frequency = RecurrenceFrequency.MONTHLY,
            dayOfMonthOrWeek = 5,
            nextTriggerTimeMillis = System.currentTimeMillis() + 7 * 86400_000L,
            enabled = true
        )
    }
}
