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
    version = 3,
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

        private fun defaultWallets(): List<WalletEntity> = listOf(
            WalletEntity(
                id = 1,
                name = "招商银行信用卡",
                type = WalletType.CREDIT,
                subTypeLabel = "信用卡",
                brandCode = "CMB",
                currency = "CNY",
                balanceCents = 396526L,       // 待还 3,965.26
                creditLimitCents = 1000000L,  // 总额度 10,000.00 -> 可用 6,034.74
                cardLast4 = "3354",
                aliases = "招商银行信用卡,掌上生活,招行信用卡,3354",
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
                creditLimitCents = 3410000L,  // 总额度 34,100.00
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
                balanceCents = 296164L,       // 待还 2,961.64
                creditLimitCents = 1000000L,  // 总额度 10,000.00 -> 可用 7,038.36
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
                balanceCents = 352000L,       // 3,520.00
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
                balanceCents = 124922L,       // 1,249.22
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
                balanceCents = 230000L,       // 2,300.00
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
                    merchant = "京东-订单编号3673486013948720",
                    sourceWalletId = 1L,
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
                    targetWalletId = 4L,
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
                    sourceWalletId = 4L,
                    timestamp = now - 1200_000L,
                    captureSource = "AI_NOTIFICATION"
                )
            )
        }
    }
}
