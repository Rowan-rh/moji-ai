package com.moji.ai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.moji.ai.ui.MainViewModel
import com.moji.ai.ui.MoJiMainScreen
import com.moji.ai.ui.theme.ICostBgGray
import com.moji.ai.ui.theme.ICostBlueAccent

private val MoJiColorScheme = lightColorScheme(
    primary = ICostBlueAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6F0FF),
    onPrimaryContainer = Color(0xFF003A8C),
    secondary = Color(0xFFE65540),
    secondaryContainer = Color(0xFFFFECE8),
    onSecondaryContainer = Color(0xFF7A1C10),
    background = ICostBgGray,
    surface = Color.White
)

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 用户授权后可接收静默记账通知提醒 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        setContent {
            MaterialTheme(colorScheme = MoJiColorScheme) {
                Surface(color = ICostBgGray) {
                    MoJiMainScreen(viewModel = viewModel)
                }
            }
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
