package com.moji.ai

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import com.moji.ai.ai.AiAccountingEngine
import com.moji.ai.ai.AiConfigManager
import com.moji.ai.ai.MerchantMemoryManager
import com.moji.ai.ai.SmartRuleEngine
import com.moji.ai.data.backup.CloudSyncConflictException
import com.moji.ai.data.backup.MoJiCloudSyncClient
import com.moji.ai.data.local.AppDatabase
import com.moji.ai.data.model.AppLockManager
import com.moji.ai.data.model.AppearanceConfigManager
import com.moji.ai.data.model.CategoryCatalog
import com.moji.ai.data.model.CurrencyRateManager
import com.moji.ai.data.model.ReminderConfigManager
import com.moji.ai.data.model.TagCatalog
import com.moji.ai.data.repository.LedgerRepository
import com.moji.ai.notification.DailyReminderScheduler
import com.moji.ai.notification.SilentBookkeepingNotifier
import com.moji.ai.service.BillScreenshotStorage
import com.moji.ai.service.CapturePipelineCoordinator
import com.moji.ai.update.AppUpdateManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class MoJiApp : Application() {

    private val appExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("MoJiApp", "Unhandled appScope exception", throwable)
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + appExceptionHandler)

    lateinit var database: AppDatabase
        private set

    lateinit var repository: LedgerRepository
        private set

    lateinit var aiConfigManager: AiConfigManager
        private set

    lateinit var merchantMemoryManager: MerchantMemoryManager
        private set

    lateinit var smartRuleEngine: SmartRuleEngine
        private set

    lateinit var aiEngine: AiAccountingEngine
        private set

    lateinit var notifier: SilentBookkeepingNotifier
        private set

    lateinit var captureCoordinator: CapturePipelineCoordinator
        private set

    lateinit var appUpdateManager: AppUpdateManager
        private set

    @Volatile
    private var startupMaintenanceCompleted: Boolean = false

    @Volatile
    private var autoPushRetryJob: Job? = null

    private val syncedPrefsRefs = ArrayList<SharedPreferences>()

    private val uiPrefsRef: SharedPreferences by lazy {
        getSharedPreferences("moji_icost_ui_prefs", MODE_PRIVATE)
    }

    private val syncedPrefsChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            if (!startupMaintenanceCompleted || MoJiCloudSyncClient.shouldSuppressAutoSync()) {
                return@OnSharedPreferenceChangeListener
            }
            if (sp === uiPrefsRef && key != null && key !in SYNCED_UI_PREF_KEYS) {
                return@OnSharedPreferenceChangeListener
            }
            MoJiCloudSyncClient.markPendingAutoPush(this, notifySignal = true)
        }

    companion object {
        private val SYNCED_UI_PREF_KEYS = setOf(
            "saved_ledgers",
            "monthly_budget_cents",
            "saved_templates_json",
            "saved_saving_plans_json",
            "saved_installments_json"
        )
    }

    override fun onCreate() {
        super.onCreate()
        CategoryCatalog.init(this)
        TagCatalog.init(this)
        CurrencyRateManager.init(this)
        AppLockManager.init(this)
        ReminderConfigManager.init(this)
        AppearanceConfigManager.init(this)
        BillScreenshotStorage.init(this)
        MoJiCloudSyncClient.init(this)
        database = AppDatabase.getInstance(this)
        repository = LedgerRepository(database)
        aiConfigManager = AiConfigManager(this)
        merchantMemoryManager = MerchantMemoryManager(this)
        smartRuleEngine = SmartRuleEngine(this)
        aiEngine = AiAccountingEngine(aiConfigManager, merchantMemoryManager)
        notifier = SilentBookkeepingNotifier(this)
        DailyReminderScheduler.rescheduleAll(this)
        appUpdateManager = AppUpdateManager(this)
        captureCoordinator = CapturePipelineCoordinator(
            repository = repository,
            aiEngine = aiEngine,
            notifier = notifier,
            smartRuleEngine = smartRuleEngine
        )
        registerSyncedPreferenceListeners()

        // 启动时检查一次性历史电商记录校准（仅按版本号执行一次，避免每次冷启动全表扫描）及到期的周期性固定收支
        appScope.launch {
            val prefs = getSharedPreferences("moji_maintenance_prefs", MODE_PRIVATE)
            if (!prefs.getBoolean("repaired_legacy_ecommerce_v20261108_3", false)) {
                repository.syncICostAllAccounts()
                captureCoordinator.repairLegacyEcommerceRecordsIfNeeded()
                prefs.edit().putBoolean("repaired_legacy_ecommerce_v20261108_3", true).apply()
            }
            val existingTagStrings = repository.getAllNonEmptyTagStrings() +
                smartRuleEngine.rules.value.map { it.appendTag }
            TagCatalog.syncDiscoveredTagsFromHistory(existingTagStrings)
            startupMaintenanceCompleted = true

            val createdList = repository.processDueRecurringRules()
            if (createdList.isNotEmpty()) {
                MoJiCloudSyncClient.markPendingAutoPush(this@MoJiApp, notifySignal = true)
                val wallets = repository.getAllWallets()
                createdList.forEach { tx ->
                    notifier.sendSilentRecordedNotification(tx, wallets)
                }
            }
            if (!BuildConfig.IS_QA_BUILD && appUpdateManager.configFlow.value.autoCheckOnLaunch) {
                appUpdateManager.checkForUpdate(isManual = false)
            }
        }

        startBackgroundCloudAutoSync()
    }

    private fun registerSyncedPreferenceListeners() {
        val prefNames = listOf(
            "moji_icost_ui_prefs",
            "moji_smart_rules_prefs",
            "moji_merchant_memory_prefs",
            AppearanceConfigManager.PREFS_NAME,
            ReminderConfigManager.PREFS_NAME,
            "moji_tag_catalog_prefs",
            "moji_currency_rate_prefs",
            "moji_category_catalog_prefs"
        )
        syncedPrefsRefs.clear()
        for (name in prefNames) {
            val sp = if (name == "moji_icost_ui_prefs") {
                uiPrefsRef
            } else {
                getSharedPreferences(name, MODE_PRIVATE)
            }
            syncedPrefsRefs.add(sp)
            sp.registerOnSharedPreferenceChangeListener(syncedPrefsChangeListener)
        }
    }

    @OptIn(FlowPreview::class)
    private fun startBackgroundCloudAutoSync() {
        appScope.launch {
            delay(5_000L)
            if (!MoJiCloudSyncClient.shouldSuppressAutoSync() &&
                MoJiCloudSyncClient.hasPendingAutoPush(this@MoJiApp) &&
                MoJiCloudSyncClient.configFlow.value.canAutoPushSafely
            ) {
                executeAutoPushWithSingleRetry()
            }
        }

        appScope.launch {
            combine(
                repository.walletsFlow,
                repository.transactionsFlow,
                repository.recurringRulesFlow,
                MoJiCloudSyncClient.localMutationSignal
            ) { wallets, txs, rules, mutationSeq ->
                MoJiCloudSyncClient.computeRoomSignature(wallets, txs, rules) to mutationSeq
            }
                .distinctUntilChanged()
                .drop(1)
                .filter { (roomSig, _) ->
                    !MoJiCloudSyncClient.isRestoringNow() &&
                        !MoJiCloudSyncClient.consumeIfMatchesRestoredSignature(roomSig)
                }
                .onEach {
                    MoJiCloudSyncClient.markPendingAutoPush(this@MoJiApp, notifySignal = false)
                }
                .debounce(6_000L)
                .collect {
                    val waitMs = MoJiCloudSyncClient.remainingSuppressionMillis()
                    if (waitMs > 0L) {
                        delay(waitMs)
                    }
                    if (MoJiCloudSyncClient.isRestoringNow()) return@collect
                    executeAutoPushWithSingleRetry()
                }
        }
    }

    private suspend fun executeAutoPushWithSingleRetry() {
        autoPushRetryJob?.cancel()
        MoJiCloudSyncClient.autoPushFromBackground(this@MoJiApp).onFailure { err ->
            Log.w("MoJiCloudSync", "后台静默云同步暂缓: ${err.message}")
            if (err !is CloudSyncConflictException &&
                MoJiCloudSyncClient.hasPendingAutoPush(this@MoJiApp)
            ) {
                autoPushRetryJob = appScope.launch {
                    delay(25_000L)
                    if (!MoJiCloudSyncClient.shouldSuppressAutoSync() &&
                        MoJiCloudSyncClient.hasPendingAutoPush(this@MoJiApp) &&
                        MoJiCloudSyncClient.configFlow.value.canAutoPushSafely
                    ) {
                        MoJiCloudSyncClient.autoPushFromBackground(this@MoJiApp).onFailure { retryErr ->
                            Log.w("MoJiCloudSync", "后台静默云同步延迟重试仍失败: ${retryErr.message}")
                        }
                    }
                }
            }
        }
    }
}
