package com.moji.ai.service

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * 快捷设置磁贴「默记识屏记账」（参考 AutoAccounting OcrTileService）：
 * 在微信/支付宝任意账单或付款完成页面下拉控制中心点击此磁贴，
 * 自动收起下拉通知栏并执行无障碍节点提取 + Android 11+ 免 Root 截屏 OCR 兜底识账。
 */
class OcrTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (PaymentAccessibilityService.instance != null) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val service = PaymentAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "请先开启「默记 · 支付页面无感识账」无障碍服务", Toast.LENGTH_SHORT).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }

        service.triggerManualScreenCapture(collapseShadeFirst = true)
    }
}
