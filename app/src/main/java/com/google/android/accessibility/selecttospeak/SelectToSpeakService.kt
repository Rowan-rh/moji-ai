package com.google.android.accessibility.selecttospeak

import com.moji.ai.service.PaymentAccessibilityService

/**
 * 微信支付与账单详情无障碍兼容入口（参考 AutoAccounting 与花有谱账本）：
 * 微信支付与账单详情页（MMWebViewUI / WxaLiteAppLiteUI）对通用第三方无障碍类名存在节点屏蔽，
 * 通过 SelectToSpeakService 兼容入口结合 FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY 可正常提取节点。
 */
class SelectToSpeakService : PaymentAccessibilityService()
