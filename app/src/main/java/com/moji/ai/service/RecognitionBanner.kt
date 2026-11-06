package com.moji.ai.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.moji.ai.MainActivity

/**
 * 免悬浮窗权限的顶部无障碍胶囊横幅（TYPE_ACCESSIBILITY_OVERLAY）：
 * 当微信/支付宝账单在后台被无障碍或截屏 OCR 静默识别入账后，在屏幕顶部弹出轻量级胶囊提示，
 * 无需申请 SYSTEM_ALERT_WINDOW 权限，3.2 秒后自动渐隐消失；截屏前自动同步移除以防被截入。
 */
object RecognitionBanner {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentView: View? = null
    private var currentWm: WindowManager? = null
    private var dismissRunnable: Runnable? = null

    fun show(
        title: String,
        subtitle: String,
        isIncome: Boolean = false
    ) {
        mainHandler.post {
            val service = PaymentAccessibilityService.instance ?: return@post
            dismissImmediateOnMain()

            val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as? WindowManager
                ?: return@post
            val dp = service.resources.displayMetrics.density

            val card = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    (16 * dp).toInt(),
                    (10 * dp).toInt(),
                    (18 * dp).toInt(),
                    (10 * dp).toInt()
                )
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 28 * dp
                    setColor(Color.parseColor("#F21C1C1E"))
                    setStroke((1 * dp).toInt(), Color.parseColor("#33FFFFFF"))
                }
                elevation = 12 * dp
                setOnClickListener {
                    dismissImmediateOnMain()
                    runCatching {
                        val intent = Intent(service, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        service.startActivity(intent)
                    }
                }
            }

            val badgeView = TextView(service).apply {
                text = "✓"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val size = (24 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    rightMargin = (10 * dp).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(
                        if (isIncome) Color.parseColor("#24B36B") else Color.parseColor("#3B82F6")
                    )
                }
            }

            val textCol = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val titleView = TextView(service).apply {
                text = title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            val subView = TextView(service).apply {
                text = subtitle
                setTextColor(Color.parseColor("#CCFFFFFF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            textCol.addView(titleView)
            if (subtitle.isNotBlank()) {
                textCol.addView(subView)
            }
            card.addView(badgeView)
            card.addView(textCol)

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (52 * dp).toInt()
            }

            runCatching {
                card.alpha = 0f
                card.translationY = -20 * dp
                wm.addView(card, lp)
                currentView = card
                currentWm = wm
                card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(200L)
                    .start()

                val hideTask = Runnable {
                    card.animate()
                        .alpha(0f)
                        .translationY(-16 * dp)
                        .setDuration(180L)
                        .withEndAction { dismissImmediateOnMain() }
                        .start()
                }
                dismissRunnable = hideTask
                mainHandler.postDelayed(hideTask, 3200L)
            }
        }
    }

    fun dismissImmediate() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dismissImmediateOnMain()
        } else {
            mainHandler.post { dismissImmediateOnMain() }
        }
    }

    private fun dismissImmediateOnMain() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        val view = currentView
        val wm = currentWm
        currentView = null
        currentWm = null
        if (view != null && wm != null) {
            runCatching { wm.removeViewImmediate(view) }
        }
    }
}
