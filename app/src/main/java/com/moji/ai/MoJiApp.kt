package com.moji.ai

import android.app.Application
import com.moji.ai.ai.AiAccountingEngine
import com.moji.ai.ai.AiConfigManager
import com.moji.ai.ai.MerchantMemoryManager
import com.moji.ai.ai.SmartRuleEngine
import com.moji.ai.data.local.AppDatabase
import com.moji.ai.data.repository.LedgerRepository
import com.moji.ai.notification.SilentBookkeepingNotifier
import com.moji.ai.service.CapturePipelineCoordinator
import com.moji.ai.update.AppUpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MoJiApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        repository = LedgerRepository(database)
        aiConfigManager = AiConfigManager(this)
        merchantMemoryManager = MerchantMemoryManager(this)
        smartRuleEngine = SmartRuleEngine(this)
        aiEngine = AiAccountingEngine(aiConfigManager, merchantMemoryManager)
        notifier = SilentBookkeepingNotifier(this)
        appUpdateManager = AppUpdateManager(this)
        captureCoordinator = CapturePipelineCoordinator(
            repository = repository,
            aiEngine = aiEngine,
            notifier = notifier,
            smartRuleEngine = smartRuleEngine
        )

        // 启动时检查一次性历史电商记录校准（仅按版本号执行一次，避免每次冷启动全表扫描）及到期的周期性固定收支
        appScope.launch {
            val prefs = getSharedPreferences("moji_maintenance_prefs", MODE_PRIVATE)
            if (!prefs.getBoolean("repaired_legacy_ecommerce_v20261108_3", false)) {
                repository.syncICostAllAccounts()
                captureCoordinator.repairLegacyEcommerceRecordsIfNeeded()
                prefs.edit().putBoolean("repaired_legacy_ecommerce_v20261108_3", true).apply()
            }
            val createdList = repository.processDueRecurringRules()
            if (createdList.isNotEmpty()) {
                val wallets = repository.getAllWallets()
                createdList.forEach { tx ->
                    notifier.sendSilentRecordedNotification(tx, wallets)
                }
            }
            if (appUpdateManager.configFlow.value.autoCheckOnLaunch) {
                appUpdateManager.checkForUpdate(isManual = false)
            }
        }
    }
}
