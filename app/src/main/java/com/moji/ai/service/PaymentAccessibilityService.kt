package com.moji.ai.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.moji.ai.MoJiApp

/**
 * 无障碍页面节点提取服务：
 * 当用户在微信、支付宝、云闪付、美团、拼多多、银行 App 处于“支付成功 / 转账详情 / 还款成功 / 账单详情”页面时，
 * 轻量级遍历提取可见文本节点（比截屏 OCR 快 5 倍、极度省电且不占内存），送入防抖合并通道交由 AI 理解。
 */
class PaymentAccessibilityService : AccessibilityService() {

    private val targetFinancialPackages = setOf(
        "com.tencent.mm",                   // 微信
        "com.eg.android.AlipayGphone",      // 支付宝
        "com.unionpay",                     // 云闪付
        "com.sankuai.meituan",              // 美团
        "com.xunmeng.pinduoduo",            // 拼多多
        "com.jingdong.app.mall",            // 京东
        "cmb.pb",                           // 招商银行
        "com.chinamworld.main",             // 建设银行
        "com.icbc",                         // 工商银行
        "com.android.mms"                   // 短信
    )

    private val pageTriggerKeywords = listOf(
        "支付成功", "付款成功", "交易成功", "转账成功", "收款成功",
        "已收钱", "已存入零钱", "还款成功", "提现已发起", "退款成功", "账单详情"
    )

    private var lastInspectedPackage: String = ""
    private var lastInspectTimeMillis: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val pkg = event.packageName?.toString() ?: return
        if (pkg !in targetFinancialPackages) return

        // 对高频内容变更事件做 350ms 节流，彻底避免微信/支付宝列表滚动掉帧
        val now = System.currentTimeMillis()
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            pkg == lastInspectedPackage &&
            (now - lastInspectTimeMillis) < 350L
        ) {
            return
        }
        lastInspectedPackage = pkg
        lastInspectTimeMillis = now

        val app = applicationContext as? MoJiApp ?: return
        if (!app.aiConfigManager.getConfig().enableAccessibilityCapture) return

        // 仅在目标支付/银行应用内轻量级提取当前窗口文本节点
        val rootNode = rootInActiveWindow ?: return
        val snippets = mutableListOf<String>()
        try {
            collectNodeTextRecursive(rootNode, snippets, depth = 0)
        } catch (_: Throwable) {
            return
        }

        if (snippets.isEmpty()) return
        val fullPageText = snippets.distinct().joinToString(" | ")

        // 只有当页面文本包含“支付成功/转账成功/还款成功/退款”等完成态关键词时才触发
        val hasCompletionSignal = pageTriggerKeywords.any { fullPageText.contains(it) }
        if (!hasCompletionSignal) return

        app.captureCoordinator.onRawFinancialEventCaptured(
            rawText = fullPageText,
            packageName = pkg,
            captureSource = "AI_ACCESSIBILITY"
        )
    }

    private fun collectNodeTextRecursive(
        node: AccessibilityNodeInfo?,
        outList: MutableList<String>,
        depth: Int
    ) {
        if (node == null || depth > 18 || outList.size >= 45) return

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        if (!text.isNullOrEmpty() && text.length <= 80) {
            outList.add(text)
        } else if (!desc.isNullOrEmpty() && desc.length <= 80) {
            outList.add(desc)
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            collectNodeTextRecursive(node.getChild(i), outList, depth + 1)
        }
    }

    override fun onInterrupt() {
        // No-op
    }
}
