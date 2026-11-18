package com.moji.ai.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import java.util.Locale

enum class AccessibilityHealthState {
    /** 无障碍服务已授权且实时绑定运行中 */
    ACTIVE,
    /** 系统设置中仍处于勾选状态，但服务在后台被系统省电强杀/断连（俗称“假开启/服务故障”） */
    INTERRUPTED_BY_SYSTEM,
    /** 无障碍总开关关闭，或默记无障碍开关未开启/被系统自动关闭 */
    DISABLED
}

data class RomKeepAliveDiagnosticState(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val sdkInt: Int,
    val romDisplayName: String,
    val isNotificationListenerEnabled: Boolean,
    val isAccessibilityEnabled: Boolean,
    val accessibilityHealth: AccessibilityHealthState = AccessibilityHealthState.DISABLED,
    val isIgnoringBatteryOptimizations: Boolean,
    val requiresAndroid13RestrictedUnlock: Boolean,
    val restrictedSettingSteps: List<String>,
    val romKeepAliveSteps: List<String>
) {
    val passedCount: Int
        get() = listOf(
            isNotificationListenerEnabled,
            isAccessibilityEnabled,
            isIgnoringBatteryOptimizations
        ).count { it }

    val totalCoreChecks: Int = 3
}

/**
 * 国产 ROM 后台静默运行保活与 Android 13+「受限设置（Restricted Settings）」一站式诊断向导
 * （对标 huayoupu-ledger 与 AutoAccountingOrg 保活指引体系）
 */
object RomKeepAliveGuideHelper {

    fun inspectCurrentDevice(context: Context): RomKeepAliveDiagnosticState {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val brand = Build.BRAND.orEmpty()
        val model = Build.MODEL.orEmpty()
        val sdkInt = Build.VERSION.SDK_INT
        val combined = "$manufacturer $brand".lowercase(Locale.ROOT)

        val isNotifGranted = checkNotificationListenerEnabled(context)
        val accessHealth = inspectAccessibilityHealth(context)
        val isAccessGranted = accessHealth == AccessibilityHealthState.ACTIVE
        val isBatteryIgnored = checkBatteryOptimizationIgnored(context)

        val (romName, steps) = when {
            combined.contains("oppo") || combined.contains("oneplus") || combined.contains("realme") -> {
                "OPPO / 一加 ColorOS" to listOf(
                    "1. 【解除无障碍受限设置】：点击下方「打开应用详情」➔ 点击右上角「⋮」➔ 选择「允许受限设置」后再开启无障碍。",
                    "2. 【允许后台高耗电与自启动】：应用详情 ➔ 耗电管理 ➔ 开启「允许完全后台行为」与「允许应用自启动」。",
                    "3. 【最近任务下拉加锁】：打开多任务卡片界面 ➔ 点击「默记」卡片右上角「⋮」➔ 点击「锁定」，防止系统夜间清理。"
                )
            }
            combined.contains("xiaomi") || combined.contains("redmi") || combined.contains("poco") -> {
                "小米 / Redmi澎湃 HyperOS (MIUI)" to listOf(
                    "1. 【解除 Android 13+ 受限设置】：应用详情页拉到底部或右上角「⋮」➔ 开启「允许受限设置」。",
                    "2. 【开启后台自启动与无限制省电】：应用详情 ➔ 省电策略设为「无限制」，并开启「后台自启动」。",
                    "3. 【多任务长按加锁】：进入最近任务界面 ➔ 长按「默记」卡片 ➔ 点击「小锁」图标锁定。"
                )
            }
            combined.contains("vivo") || combined.contains("iqoo") -> {
                "vivo / iQOO OriginOS" to listOf(
                    "1. 【解除受限设置】：应用信息 ➔ 右上角「⋮」➔ 点击「允许受限设置」。",
                    "2. 【后台高耗电与自启动】：应用信息 ➔ 权限 ➔ 自启动开启；电池 ➔ 勾选「允许后台高耗电」。",
                    "3. 【最近任务加锁】：多任务视图点击「默记」上方小箭头 ➔ 选择「锁定」。"
                )
            }
            combined.contains("huawei") || combined.contains("honor") -> {
                "华为 HarmonyOS / 荣耀 MagicOS" to listOf(
                    "1. 【关闭自动应用启动管理】：系统设置 ➔ 应用和服务 ➔ 应用启动管理 ➔ 找到「默记」关闭自动管理，手动勾选「允许自启动 / 允许后台活动」。",
                    "2. 【忽略电池优化】：点击下方「一键申请忽略电池优化」选择允许。",
                    "3. 【多任务下拉加锁】：进入多任务视图，将「默记」卡片向下拉出现小锁标志。"
                )
            }
            else -> {
                "Android (${manufacturer.ifBlank { "原生系统" }})" to listOf(
                    "1. 【Android 13+ 受限设置】：若开启无障碍提示「受限设置」，请先打开「应用详情」➔ 右上角「⋮」➔ 点击「允许受限设置」。",
                    "2. 【后台电池无限制】：在应用详情 ➔ 电池（耗电管理）中设为「无限制」。",
                    "3. 【最近任务锁定】：在最近任务列表中将「默记」加锁，防止通知监听服务被杀。"
                )
            }
        }

        val restrictedSteps = listOf(
            "第 1 步：点击「无障碍自动识账」去开启并尝试点一下开关（触发系统识别受限状态）",
            "第 2 步：点击下方「打开应用详情解除受限」按钮 ➔ 点击页面右上角三个点「⋮」➔ 点击「允许受限设置」",
            "第 3 步：返回「无障碍服务」列表，即可正常开启「默记 AI 支付页自动识账」"
        )

        return RomKeepAliveDiagnosticState(
            manufacturer = manufacturer,
            brand = brand,
            model = model,
            sdkInt = sdkInt,
            romDisplayName = romName,
            isNotificationListenerEnabled = isNotifGranted,
            isAccessibilityEnabled = isAccessGranted,
            accessibilityHealth = accessHealth,
            isIgnoringBatteryOptimizations = isBatteryIgnored,
            requiresAndroid13RestrictedUnlock = sdkInt >= 33 && accessHealth == AccessibilityHealthState.DISABLED,
            restrictedSettingSteps = restrictedSteps,
            romKeepAliveSteps = steps
        )
    }

    fun checkNotificationListenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val expected = ComponentName(context.packageName, PaymentNotificationListenerService::class.java.name)
        return flat.split(':').any { token ->
            val parsed = ComponentName.unflattenFromString(token.trim()) ?: return@any false
            parsed.packageName.equals(expected.packageName, ignoreCase = true) &&
                parsed.className == expected.className
        }
    }

    /**
     * 检查系统设置层是否勾选了默记的无障碍服务（同时核验 ACCESSIBILITY_ENABLED 总开关与精确服务组件名）
     */
    fun isAccessibilitySettingChecked(context: Context): Boolean {
        val masterEnabled = runCatching {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        }.getOrDefault(false)
        if (!masterEnabled) return false

        val flat = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = ComponentName(context.packageName, SelectToSpeakService::class.java.name)
        return flat.split(':').any { token ->
            val parsed = ComponentName.unflattenFromString(token.trim()) ?: return@any false
            parsed.packageName.equals(expected.packageName, ignoreCase = true) &&
                parsed.className == expected.className
        }
    }

    /**
     * 综合实时服务连接态与系统设置项，精准区分「正常运行」「被系统杀后台假开启断连」「未开启/已被系统关闭」
     */
    fun inspectAccessibilityHealth(context: Context): AccessibilityHealthState {
        if (PaymentAccessibilityService.instance != null) {
            return AccessibilityHealthState.ACTIVE
        }
        return if (isAccessibilitySettingChecked(context)) {
            AccessibilityHealthState.INTERRUPTED_BY_SYSTEM
        } else {
            AccessibilityHealthState.DISABLED
        }
    }

    fun checkAccessibilityServiceEnabled(context: Context): Boolean {
        return inspectAccessibilityHealth(context) == AccessibilityHealthState.ACTIVE
    }

    fun buildAccessibilitySettingsIntent(context: Context, highlightService: Boolean = true): Intent {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (highlightService) {
            val componentKey = ComponentName(
                context.packageName,
                SelectToSpeakService::class.java.name
            ).flattenToString()
            val bundle = Bundle().apply {
                putString(":settings:fragment_args_key", componentKey)
            }
            intent.putExtra(":settings:fragment_args_key", componentKey)
            intent.putExtra(":settings:show_fragment_args", bundle)
        }
        return intent
    }

    /**
     * 跳转系统无障碍设置并自动定位高亮「默记」服务项（兼容 AOSP / ColorOS / HyperOS / OneUI，不支持的机型平滑回退至无障碍列表）
     */
    fun openAccessibilitySettingsForMoJi(context: Context) {
        try {
            context.startActivity(buildAccessibilitySettingsIntent(context, highlightService = true))
        } catch (_: Exception) {
            runCatching {
                context.startActivity(buildAccessibilitySettingsIntent(context, highlightService = false))
            }
        }
    }

    fun checkBatteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 当系统杀后台或重装后导致通知监听断连时，主动请求系统重新绑定 NotificationListenerService
     */
    fun requestNotificationListenerRebind(context: Context): Boolean {
        return try {
            val cn = ComponentName(context, PaymentNotificationListenerService::class.java)
            NotificationListenerService.requestRebind(cn)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun openAppDetailsSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    fun openBatteryOptimizationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }
}
