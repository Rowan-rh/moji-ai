package com.moji.ai

import android.app.Application
import com.moji.ai.ai.AiAccountingEngine
import com.moji.ai.ai.AiConfigManager
import com.moji.ai.data.local.AppDatabase
import com.moji.ai.data.repository.LedgerRepository
import com.moji.ai.notification.SilentBookkeepingNotifier
import com.moji.ai.service.CapturePipelineCoordinator
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

    lateinit var aiEngine: AiAccountingEngine
        private set

    lateinit var notifier: SilentBookkeepingNotifier
        private set

    lateinit var captureCoordinator: CapturePipelineCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        repository = LedgerRepository(database)
        aiConfigManager = AiConfigManager(this)
        aiEngine = AiAccountingEngine(aiConfigManager)
        notifier = SilentBookkeepingNotifier(this)
        captureCoordinator = CapturePipelineCoordinator(
            repository = repository,
            aiEngine = aiEngine,
            notifier = notifier
        )

        // 启动时自动检查是否有到期的周期性固定收支（如房租、订阅）并静默入账
        appScope.launch {
            val createdList = repository.processDueRecurringRules()
            if (createdList.isNotEmpty()) {
                val wallets = repository.getAllWallets()
                createdList.forEach { tx ->
                    notifier.sendSilentRecordedNotification(tx, wallets)
                }
            }
        }
    }
}
