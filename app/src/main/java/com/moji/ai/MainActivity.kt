package com.moji.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import com.moji.ai.data.model.AppLockManager
import com.moji.ai.data.model.AppearanceConfigManager
import com.moji.ai.ui.MainViewModel
import com.moji.ai.ui.MoJiMainScreen
import com.moji.ai.ui.theme.ICostBgGray
import com.moji.ai.ui.theme.ICostBlueAccent
import com.moji.ai.ui.theme.ICostCardWhite

private val MoJiLightColorScheme = lightColorScheme(
    primary = ICostBlueAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6F0FF),
    onPrimaryContainer = Color(0xFF003A8C),
    secondary = Color(0xFFE65540),
    secondaryContainer = Color(0xFFFFECE8),
    onSecondaryContainer = Color(0xFF7A1C10),
    background = Color(0xFFF6F6F6),
    surface = Color.White
)

private val MoJiDarkColorScheme = darkColorScheme(
    primary = ICostBlueAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1A2B4C),
    onPrimaryContainer = Color(0xFFD6E6FF),
    secondary = Color(0xFFE65540),
    secondaryContainer = Color(0xFF3B1F1B),
    onSecondaryContainer = Color(0xFFFFDAD4),
    background = Color(0xFF111318),
    surface = Color(0xFF1C1F26)
)

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_TRANSACTION_ID = "extra_open_transaction_id"
        const val EXTRA_OPEN_BOOKKEEPING_SHEET = "extra_open_bookkeeping_sheet"
    }

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 用户授权后可接收静默记账通知提醒 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableHighRefreshRateMode()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        requestPostNotificationPermissionIfNeeded()
        handleLaunchIntent(intent)

        setContent {
            val appLockConfig by viewModel.passwordProtectionConfig.collectAsState()
            val appearanceConfig by viewModel.appearanceSettingsConfig.collectAsState()
            val systemDark = isSystemInDarkTheme()
            AppearanceConfigManager.updateSystemDarkState(systemDark)
            val isDark = AppearanceConfigManager.resolveIsDarkTheme(appearanceConfig, systemDark)

            LaunchedEffect(appLockConfig.enabled, appLockConfig.hideRecentTaskPreview) {
                if (appLockConfig.enabled && appLockConfig.hideRecentTaskPreview) {
                    window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            LaunchedEffect(isDark) {
                val barStyle = if (isDark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    )
                }
                enableEdgeToEdge(
                    statusBarStyle = barStyle,
                    navigationBarStyle = barStyle
                )
            }

            val baseDensity = LocalDensity.current
            val scaledDensity = Density(
                density = baseDensity.density,
                fontScale = baseDensity.fontScale * appearanceConfig.fontScalePreset.scale
            )
            CompositionLocalProvider(LocalDensity provides scaledDensity) {
                MaterialTheme(colorScheme = if (isDark) MoJiDarkColorScheme else MoJiLightColorScheme) {
                    key(appearanceConfig, isDark) {
                        Surface(color = ICostBgGray) {
                            MoJiMainScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        val openTxId = intent?.getLongExtra(EXTRA_OPEN_TRANSACTION_ID, -1L) ?: -1L
        if (openTxId > 0L) {
            viewModel.requestOpenTransactionDetail(openTxId)
            intent?.removeExtra(EXTRA_OPEN_TRANSACTION_ID)
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_BOOKKEEPING_SHEET, false) == true) {
            viewModel.requestOpenBookkeepingSheet()
            intent.removeExtra(EXTRA_OPEN_BOOKKEEPING_SHEET)
        }
    }

    override fun onStart() {
        super.onStart()
        AppLockManager.onAppForegrounded()
    }

    override fun onResume() {
        super.onResume()
        AppLockManager.onAppForegrounded()
        enableHighRefreshRateMode()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            AppLockManager.onAppBackgrounded()
        }
    }

    private fun enableHighRefreshRateMode() {
        runCatching {
            val win = window ?: return
            val params = win.attributes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val disp = display ?: win.decorView.display
                val modes = disp?.supportedModes.orEmpty()
                val maxWidth = modes.maxOfOrNull { it.physicalWidth } ?: 0
                val bestMode = modes
                    .filter { it.physicalWidth == maxWidth && it.refreshRate >= 119f }
                    .minByOrNull { kotlin.math.abs(it.refreshRate - 120f) }
                    ?: modes.maxByOrNull { it.refreshRate }
                if (bestMode != null) {
                    params.preferredDisplayModeId = bestMode.modeId
                    params.preferredRefreshRate = bestMode.refreshRate
                }
            } else {
                params.preferredRefreshRate = 120f
            }
            win.attributes = params
        }
    }

    private fun requestPostNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
