package com.moji.ai.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.moji.ai.MainActivity
import com.moji.ai.MoJiApp
import com.moji.ai.R
import com.moji.ai.data.local.AppDatabase
import com.moji.ai.data.model.AppLockManager
import com.moji.ai.data.model.AppearanceConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

enum class WidgetPinRequestResult {
    SYSTEM_PROMPT_REQUESTED,
    UNSUPPORTED_BY_LAUNCHER,
    FAILED
}

/**
 * 桌面小组件刷新与桌面固定（Pin）统一入口：
 * - 桌面上未添加任何小组件时，`enqueueUpdateAll` 通过 `appWidgetIds.isEmpty()` 直接 O(1) 短路返回，零数据库查询；
 * - 所有显式 Intent 与广播均严格绑定 `context.packageName`，确保 `com.moji.ai.qa` 与正式包 `com.moji.ai` 100% 物理隔离。
 */
object MoJiLedgerWidgetUpdater {

    private const val TAG = "MoJiLedgerWidget"
    private const val UI_PREFS_NAME = "moji_icost_ui_prefs"
    private const val KEY_CURRENT_LEDGER = "current_ledger"
    private const val KEY_MONTHLY_BUDGET_CENTS = "monthly_budget_cents"
    private const val KEY_HIDE_ASSET_AMOUNTS = "hide_asset_amounts"

    private const val REQUEST_CODE_OPEN_HOME_BASE = 7100
    private const val REQUEST_CODE_OPEN_BOOKKEEPING_BASE = 8100

    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshDirty = AtomicBoolean(false)
    private val refreshRunning = AtomicBoolean(false)

    fun shouldShortCircuitUpdate(appWidgetIds: IntArray): Boolean = appWidgetIds.isEmpty()

    fun getAddedWidgetIds(context: Context): IntArray {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext) ?: return IntArray(0)
        return runCatching {
            manager.getAppWidgetIds(ComponentName(appContext, MoJiLedgerWidgetProvider::class.java))
        }.getOrElse { IntArray(0) }
    }

    fun hasAddedWidgets(context: Context): Boolean =
        !shouldShortCircuitUpdate(getAddedWidgetIds(context))

    /**
     * 触发所有已添加桌面小组件异步刷新；若桌面上未放置小组件则 O(1) 直接返回。
     */
    fun enqueueUpdateAll(context: Context) {
        val appContext = context.applicationContext
        val appWidgetIds = getAddedWidgetIds(appContext)
        if (shouldShortCircuitUpdate(appWidgetIds)) return
        scheduleCoalescedRefresh(appContext)
    }

    private fun scheduleCoalescedRefresh(appContext: Context) {
        refreshDirty.set(true)
        if (!refreshRunning.compareAndSet(false, true)) return
        val scope = (appContext as? MoJiApp)?.appScope ?: fallbackScope
        scope.launch(Dispatchers.IO) {
            try {
                while (refreshDirty.compareAndSet(true, false)) {
                    val ids = getAddedWidgetIds(appContext)
                    if (shouldShortCircuitUpdate(ids)) break
                    val manager = AppWidgetManager.getInstance(appContext) ?: break
                    refreshWidgetsSuspend(appContext, manager, ids)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "桌面小组件后台刷新异常: ${t.message}")
            } finally {
                refreshRunning.set(false)
                if (refreshDirty.get()) {
                    scheduleCoalescedRefresh(appContext)
                }
            }
        }
    }

    internal suspend fun refreshWidgetsSuspend(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (shouldShortCircuitUpdate(appWidgetIds)) return
        val appContext = context.applicationContext
        val snapshot = loadCurrentSnapshot(appContext)
        for (appWidgetId in appWidgetIds) {
            val options = runCatching {
                appWidgetManager.getAppWidgetOptions(appWidgetId)
            }.getOrNull()
            val views = buildRemoteViews(
                appContext = appContext,
                appWidgetId = appWidgetId,
                options = options,
                snapshot = snapshot
            )
            runCatching {
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }.onFailure { err ->
                Log.w(TAG, "updateAppWidget($appWidgetId) 失败: ${err.message}")
            }
        }
    }

    private suspend fun loadCurrentSnapshot(appContext: Context): LedgerWidgetSnapshot {
        val uiPrefs = appContext.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
        val currentLedger = uiPrefs.getString(
            KEY_CURRENT_LEDGER,
            LedgerWidgetSnapshotCalculator.DEFAULT_LEDGER_NAME
        ) ?: LedgerWidgetSnapshotCalculator.DEFAULT_LEDGER_NAME
        val monthlyBudgetCents = uiPrefs.getLong(KEY_MONTHLY_BUDGET_CENTS, 0L)
        val hideAssetAmounts = uiPrefs.getBoolean(KEY_HIDE_ASSET_AMOUNTS, false)

        val appLockConfig = AppLockManager.configFlow.value
        val appearanceConfig = AppearanceConfigManager.configFlow.value
        val privacyMasked = LedgerWidgetSnapshotCalculator.shouldMaskPrivacy(
            hideAssetAmounts = hideAssetAmounts,
            appLockConfig = appLockConfig
        )

        val allTransactions = AppDatabase.getInstance(appContext)
            .transactionDao()
            .getAllTransactions()

        return LedgerWidgetSnapshotCalculator.calculate(
            transactions = allTransactions,
            currentLedger = currentLedger,
            monthlyBudgetCents = monthlyBudgetCents,
            privacyMasked = privacyMasked,
            appearanceConfig = appearanceConfig
        )
    }

    internal fun buildRemoteViews(
        appContext: Context,
        appWidgetId: Int,
        options: Bundle?,
        snapshot: LedgerWidgetSnapshot
    ): RemoteViews {
        val minWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        val useCompact = LedgerWidgetSnapshotCalculator.shouldUseCompactLayout(minWidthDp)
        val layoutRes = if (useCompact) {
            R.layout.widget_moji_ledger_compact
        } else {
            R.layout.widget_moji_ledger_medium
        }

        val views = RemoteViews(appContext.packageName, layoutRes)
        val overBudgetColor = ContextCompat.getColor(appContext, R.color.widget_over_budget_red)
        val accentBlueColor = ContextCompat.getColor(appContext, R.color.widget_accent_blue)
        val highlightOverBudget = snapshot.isOverBudget && !snapshot.privacyMasked

        views.setTextViewText(R.id.widget_ledger_name, snapshot.ledgerName)
        views.setTextViewText(R.id.widget_primary_title, snapshot.primaryTitle)
        views.setTextViewText(R.id.widget_primary_amount, snapshot.primaryAmountText)
        if (highlightOverBudget) {
            views.setTextColor(R.id.widget_primary_amount, overBudgetColor)
        }

        if (snapshot.hasBudget) {
            views.setViewVisibility(R.id.widget_budget_progress, View.VISIBLE)
            if (highlightOverBudget) {
                views.setProgressBar(R.id.widget_budget_progress, 100, 0, false)
                views.setInt(R.id.widget_budget_progress, "setSecondaryProgress", 100)
            } else {
                views.setProgressBar(R.id.widget_budget_progress, 100, snapshot.progressPercent, false)
                views.setInt(R.id.widget_budget_progress, "setSecondaryProgress", 0)
            }
        } else {
            views.setViewVisibility(R.id.widget_budget_progress, View.GONE)
        }

        if (useCompact) {
            views.setTextViewText(R.id.widget_compact_today, snapshot.compactTodayText)
            views.setTextViewText(R.id.widget_compact_secondary, snapshot.compactSecondaryText)
            if (highlightOverBudget) {
                views.setTextColor(R.id.widget_compact_secondary, overBudgetColor)
            }
        } else {
            views.setTextViewText(R.id.widget_date_badge, snapshot.dateBadgeText)
            views.setTextViewText(R.id.widget_status_subtitle, snapshot.statusSubtitle)
            if (highlightOverBudget) {
                views.setTextColor(R.id.widget_status_subtitle, overBudgetColor)
            }
            if (snapshot.progressPercentText.isNotEmpty()) {
                views.setViewVisibility(R.id.widget_progress_percent, View.VISIBLE)
                views.setTextViewText(R.id.widget_progress_percent, snapshot.progressPercentText)
                views.setTextColor(
                    R.id.widget_progress_percent,
                    if (highlightOverBudget) overBudgetColor else accentBlueColor
                )
            } else {
                views.setViewVisibility(R.id.widget_progress_percent, View.GONE)
            }
            views.setTextViewText(R.id.widget_today_label, snapshot.todayLabelText)
            views.setTextViewText(R.id.widget_today_amount, snapshot.todayAmountText)
            views.setTextViewText(R.id.widget_month_label, snapshot.monthLabelText)
            views.setTextViewText(R.id.widget_month_amount, snapshot.monthAmountText)
        }

        // 1. 点击卡片主体 -> 显式打开本包 MainActivity 账本首页
        val openHomeIntent = Intent(appContext, MainActivity::class.java).apply {
            setPackage(appContext.packageName)
            action = "${appContext.packageName}.ACTION_OPEN_HOME_FROM_WIDGET"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openHomePendingIntent = PendingIntent.getActivity(
            appContext,
            REQUEST_CODE_OPEN_HOME_BASE + appWidgetId,
            openHomeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openHomePendingIntent)

        // 2. 点击右上角「+」按钮 -> 显式拉起本包 MainActivity 并自动弹出 ICostCalculatorSheet 记账面板
        val openBookkeepingIntent = Intent(appContext, MainActivity::class.java).apply {
            setPackage(appContext.packageName)
            action = "${appContext.packageName}.ACTION_OPEN_BOOKKEEPING_FROM_WIDGET"
            putExtra(MainActivity.EXTRA_OPEN_BOOKKEEPING_SHEET, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openBookkeepingPendingIntent = PendingIntent.getActivity(
            appContext,
            REQUEST_CODE_OPEN_BOOKKEEPING_BASE + appWidgetId,
            openBookkeepingIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_btn_add, openBookkeepingPendingIntent)

        return views
    }

    /**
     * 调起系统「添加到主屏幕」固定桌面小组件弹窗。
     */
    fun requestPinWidget(context: Context): WidgetPinRequestResult {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
            ?: return WidgetPinRequestResult.FAILED
        if (!manager.isRequestPinAppWidgetSupported) {
            return WidgetPinRequestResult.UNSUPPORTED_BY_LAUNCHER
        }
        val provider = ComponentName(appContext, MoJiLedgerWidgetProvider::class.java)
        return runCatching {
            val requested = manager.requestPinAppWidget(provider, null, null)
            if (requested) {
                WidgetPinRequestResult.SYSTEM_PROMPT_REQUESTED
            } else {
                WidgetPinRequestResult.UNSUPPORTED_BY_LAUNCHER
            }
        }.getOrElse { err ->
            Log.w(TAG, "requestPinAppWidget 异常: ${err.message}")
            WidgetPinRequestResult.FAILED
        }
    }
}

/**
 * 默记 iCost 风格预算与今日支出桌面小组件（4×2 标准卡片 / 2×2 紧凑卡片自适应）。
 */
class MoJiLedgerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (MoJiLedgerWidgetUpdater.shouldShortCircuitUpdate(appWidgetIds)) return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        val scope = (appContext as? MoJiApp)?.appScope
            ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch(Dispatchers.IO) {
            try {
                MoJiLedgerWidgetUpdater.refreshWidgetsSuspend(
                    context = appContext,
                    appWidgetManager = appWidgetManager,
                    appWidgetIds = appWidgetIds
                )
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        val scope = (appContext as? MoJiApp)?.appScope
            ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch(Dispatchers.IO) {
            try {
                MoJiLedgerWidgetUpdater.refreshWidgetsSuspend(
                    context = appContext,
                    appWidgetManager = appWidgetManager,
                    appWidgetIds = intArrayOf(appWidgetId)
                )
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        super.onReceive(context, intent)
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_LOCALE_CHANGED -> {
                val appContext = context.applicationContext
                val ids = MoJiLedgerWidgetUpdater.getAddedWidgetIds(appContext)
                if (MoJiLedgerWidgetUpdater.shouldShortCircuitUpdate(ids)) return
                val manager = AppWidgetManager.getInstance(appContext) ?: return
                val pendingResult = goAsync()
                val scope = (appContext as? MoJiApp)?.appScope
                    ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
                scope.launch(Dispatchers.IO) {
                    try {
                        MoJiLedgerWidgetUpdater.refreshWidgetsSuspend(
                            context = appContext,
                            appWidgetManager = manager,
                            appWidgetIds = ids
                        )
                    } finally {
                        runCatching { pendingResult.finish() }
                    }
                }
            }
        }
    }
}
