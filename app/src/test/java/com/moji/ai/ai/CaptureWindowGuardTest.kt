package com.moji.ai.ai

import android.view.accessibility.AccessibilityWindowInfo
import com.moji.ai.data.backup.BackupAndCsvTransferEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class CaptureWindowGuardTest {

    private val nowMillis: Long = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        .parse("2026-11-18 16:00:00")!!.time

    @Before
    fun setUp() {
        CaptureAppWhitelistManager.resetForTesting()
    }

    @After
    fun tearDown() {
        CaptureAppWhitelistManager.resetForTesting()
    }

    @Test
    fun selectTopApplicationWindow_ignoresPipAndPicksHighestFocusedAppWindow() {
        val windows = listOf(
            WindowCandidate(
                id = 1,
                type = AccessibilityWindowInfo.TYPE_APPLICATION,
                isActive = true,
                isFocused = false,
                layer = 10,
                isInPictureInPictureMode = false,
                packageName = "com.tencent.mm"
            ),
            WindowCandidate(
                id = 2,
                type = AccessibilityWindowInfo.TYPE_APPLICATION,
                isActive = true,
                isFocused = true,
                layer = 30,
                isInPictureInPictureMode = true, // 画中画小窗必须忽略
                packageName = "tv.danmaku.bili"
            ),
            WindowCandidate(
                id = 3,
                type = AccessibilityWindowInfo.TYPE_SYSTEM,
                isActive = true,
                isFocused = true,
                layer = 50,
                isInPictureInPictureMode = false,
                packageName = "com.android.systemui"
            ),
            WindowCandidate(
                id = 4,
                type = AccessibilityWindowInfo.TYPE_APPLICATION,
                isActive = true,
                isFocused = true,
                layer = 20,
                isInPictureInPictureMode = false,
                packageName = "com.eg.android.AlipayGphone"
            )
        )

        val selected = CaptureWindowGuard.selectTopApplicationWindow(windows)
        assertNotNull(selected)
        assertEquals(4, selected!!.id)
        assertEquals("com.eg.android.AlipayGphone", selected.packageName)
    }

    @Test
    fun activityContainerClassification_distinguishesViewsBlockedChatGalleryAndDedicatedPayPages() {
        // 普通 View / Dialog / BottomSheet 绝不能被当成 Activity
        assertTrue(CaptureWindowGuard.isLikelyViewOrDialogClass("android.widget.FrameLayout"))
        assertTrue(CaptureWindowGuard.isLikelyViewOrDialogClass("com.tencent.mm.ui.widget.dialog.MMBottomSheet"))
        assertTrue(CaptureWindowGuard.isLikelyViewOrDialogClass("androidx.compose.ui.window.PopupLayout"))
        assertFalse(CaptureWindowGuard.isLikelyViewOrDialogClass("com.tencent.mm.ui.LauncherUI"))
        assertFalse(CaptureWindowGuard.isLikelyViewOrDialogClass("com.eg.android.AlipayGphone.AlipayLogin"))

        // 微信聊天主界面、朋友圈、相册选图、扫一扫 -> 直接硬拦截
        assertEquals(
            WindowContainerRole.NON_TRANSACTION_BLOCKED,
            CaptureWindowGuard.classifyActivityContainer("com.tencent.mm", "com.tencent.mm.ui.LauncherUI")
        )
        assertEquals(
            WindowContainerRole.NON_TRANSACTION_BLOCKED,
            CaptureWindowGuard.classifyActivityContainer("com.tencent.mm", "com.tencent.mm.plugin.sns.ui.SnsTimeLineUI")
        )
        assertEquals(
            WindowContainerRole.NON_TRANSACTION_BLOCKED,
            CaptureWindowGuard.classifyActivityContainer("com.tencent.mm", "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI")
        )
        assertEquals(
            WindowContainerRole.NON_TRANSACTION_BLOCKED,
            CaptureWindowGuard.classifyActivityContainer("com.xunmeng.pinduoduo", "com.xunmeng.pinduoduo.activity.GoodsDetailActivity")
        )

        // 密码核身页 -> 隐私硬拦截
        assertEquals(
            WindowContainerRole.SENSITIVE_AUTH_BLOCKED,
            CaptureWindowGuard.classifyActivityContainer("com.eg.android.AlipayGphone", "com.alipay.mobile.verifyidentity.PasswordVerifyActivity")
        )

        // 微信/支付宝/京东专用支付与账单详情页 -> 专用高置信容器
        assertEquals(
            WindowContainerRole.DEDICATED_PAYMENT_OR_BILL,
            CaptureWindowGuard.classifyActivityContainer("com.tencent.mm", "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI")
        )
        assertEquals(
            WindowContainerRole.DEDICATED_PAYMENT_OR_BILL,
            CaptureWindowGuard.classifyActivityContainer("com.tencent.mm", "com.tencent.mm.plugin.lite.ui.WxaLiteAppTransparentLiteUI")
        )
        assertEquals(
            WindowContainerRole.DEDICATED_PAYMENT_OR_BILL,
            CaptureWindowGuard.classifyActivityContainer("com.eg.android.AlipayGphone", "com.alipay.android.msp.ui.views.MspUniRenderActivity")
        )
        assertEquals(
            WindowContainerRole.DEDICATED_PAYMENT_OR_BILL,
            CaptureWindowGuard.classifyActivityContainer("com.jingdong.app.mall", "com.jd.lib.ordercenter.taro.OrderDetailActivityTaro")
        )

        // AlipayLogin / WebViewUI -> 保留放行但走严格结构校验
        assertEquals(
            WindowContainerRole.SHARED_OR_WEB_STRICT,
            CaptureWindowGuard.classifyActivityContainer("com.eg.android.AlipayGphone", "com.eg.android.AlipayGphone.AlipayLogin")
        )
        assertEquals(
            WindowContainerRole.SHARED_OR_WEB_STRICT,
            CaptureWindowGuard.classifyActivityContainer("com.tencent.mm", "com.tencent.mm.plugin.webview.ui.tools.WebViewUI")
        )
    }

    @Test
    fun structureFingerprint_isInvariantToDynamicMerchantAndAmountText_andSupportsBackupSync() {
        val luckinBillSkeleton = SkeletonNode(
            className = "android.widget.FrameLayout",
            viewIdResourceName = "com.tencent.mm:id/root_container",
            children = listOf(
                SkeletonNode(
                    className = "android.widget.LinearLayout",
                    viewIdResourceName = "com.tencent.mm:id/header_bar",
                    children = listOf(
                        SkeletonNode(
                            className = "android.widget.ImageView",
                            contentDescription = "返回"
                        ),
                        SkeletonNode(
                            className = "android.widget.TextView",
                            text = "瑞幸咖啡",
                            contentDescription = "瑞幸咖啡" // WebView/Compose 常把动态文本镜像进 contentDescription
                        )
                    )
                ),
                SkeletonNode(
                    className = "android.widget.TextView",
                    viewIdResourceName = "com.tencent.mm:id/amount_tv",
                    text = "-9.90",
                    contentDescription = "-9.90"
                )
            )
        )

        val mcdonaldsBillSkeleton = SkeletonNode(
            className = "android.widget.FrameLayout",
            viewIdResourceName = "com.tencent.mm:id/root_container",
            children = listOf(
                SkeletonNode(
                    className = "android.widget.LinearLayout",
                    viewIdResourceName = "com.tencent.mm:id/header_bar",
                    children = listOf(
                        SkeletonNode(
                            className = "android.widget.ImageView",
                            contentDescription = "返回"
                        ),
                        SkeletonNode(
                            className = "android.widget.TextView",
                            text = "麦当劳北京三里屯餐厅",
                            contentDescription = "麦当劳北京三里屯餐厅"
                        )
                    )
                ),
                SkeletonNode(
                    className = "android.widget.TextView",
                    viewIdResourceName = "com.tencent.mm:id/amount_tv",
                    text = "-42.50",
                    contentDescription = "-42.50"
                )
            )
        )

        val promoGameSkeleton = SkeletonNode(
            className = "android.widget.FrameLayout",
            viewIdResourceName = "com.tencent.mm:id/game_root",
            children = listOf(
                SkeletonNode(className = "android.webkit.WebView"),
                SkeletonNode(className = "android.widget.Button", contentDescription = "关闭")
            )
        )

        val fpLuckin = CaptureWindowGuard.computeStructureFingerprint(luckinBillSkeleton)
        val fpMcd = CaptureWindowGuard.computeStructureFingerprint(mcdonaldsBillSkeleton)
        val fpPromo = CaptureWindowGuard.computeStructureFingerprint(promoGameSkeleton)

        assertEquals(16, fpLuckin.length)
        assertEquals("同一页面模板不同商户与金额应生成完全一致的骨架指纹", fpLuckin, fpMcd)
        assertNotEquals("不同控件拓扑结构应生成不同指纹", fpLuckin, fpPromo)

        // 用户一键拉黑误触页面结构指纹后，自动识屏立即拦截，但不影响其他指纹与手动磁贴
        val blockedSig = WindowPageSignature(
            packageName = "com.tencent.mm",
            activityName = "com.tencent.mm.plugin.webview.ui.tools.WebViewUI",
            structureFingerprint = fpPromo,
            appName = "微信",
            sampleSummary = "活动领券页面",
            createdAtMillis = nowMillis
        )
        assertTrue(CaptureAppWhitelistManager.blockWindowSignature(blockedSig))
        assertTrue(
            CaptureAppWhitelistManager.isWindowSignatureBlocked(
                "com.tencent.mm",
                "com.tencent.mm.plugin.webview.ui.tools.WebViewUI",
                fpPromo
            )
        )
        assertFalse(
            CaptureAppWhitelistManager.isWindowSignatureBlocked(
                "com.tencent.mm",
                "com.tencent.mm.plugin.webview.ui.tools.WebViewUI",
                fpLuckin
            )
        )

        // 全量备份与恢复完整保留已屏蔽窗口签名
        val backupJson = BackupAndCsvTransferEngine.exportFullBackupJson(
            ledgers = listOf("默认账本"),
            monthlyBudgetCents = 0L,
            wallets = emptyList(),
            transactions = emptyList(),
            recurringRules = emptyList(),
            merchantMemories = emptyList()
        )
        val snapshot = BackupAndCsvTransferEngine.parseFullBackupJson(backupJson)!!.third
        CaptureAppWhitelistManager.resetForTesting()
        assertFalse(
            CaptureAppWhitelistManager.isWindowSignatureBlocked(
                "com.tencent.mm",
                "com.tencent.mm.plugin.webview.ui.tools.WebViewUI",
                fpPromo
            )
        )
        assertTrue(CaptureAppWhitelistManager.restoreFromBackupJson(snapshot.captureWhitelistJson))
        assertTrue(
            CaptureAppWhitelistManager.isWindowSignatureBlocked(
                "com.tencent.mm",
                "com.tencent.mm.plugin.webview.ui.tools.WebViewUI",
                fpPromo
            )
        )
    }

    @Test
    fun evaluateBillWindow_rejectsWeirdFalsePositivesAcrossApps() {
        // 1. 商品详情浏览页（曾经因为“百亿补贴 + 退换无忧 + 配送服务 + ¥29.9 + 评价日期”凑够锚点而误记账！）
        val pddProductPage = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.xunmeng.pinduoduo",
                verifiedActivity = "com.xunmeng.pinduoduo.activity.NewPageActivity",
                rawPageText = "百亿补贴 | 退换无忧 | 配送服务 全场包邮 | 实付 ¥29.90 | 商品评价(2000+) | 11-18 14:20 很好用 | 客服 | 店铺 | 发起拼单",
                nowMillis = nowMillis
            )
        )
        assertFalse(pddProductPage.accepted)
        assertEquals(WindowRejectReason.PRODUCT_BROWSING_PAGE, pddProductPage.reason)

        // 2. 聊天窗口转发文案（带底部可编辑聊天框 + 模糊聊天时间“昨天 21:18”）
        val chatWindow = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.eg.android.AlipayGphone",
                verifiedActivity = "com.eg.android.AlipayGphone.AlipayLogin",
                rawPageText = "好友小明 | 昨天 21:18 | 我刚买了一单 实付款 ¥39.90 支付成功 给你看看",
                hasVisibleEditableNode = true,
                nowMillis = nowMillis
            )
        )
        assertFalse(chatWindow.accepted)
        assertEquals(WindowRejectReason.VISIBLE_EDITABLE_INPUT, chatWindow.reason)

        // 3. 已关闭 / 已取消 / 支付失败的订单详情页（带完整订单编号与创建时间，但未发生真实扣款）
        val closedOrder = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.taobao.taobao",
                verifiedActivity = "com.taobao.android.order.bundle.TBOrderDetailActivity",
                rawPageText = "订单详情 | 交易关闭 | 实付款 ¥88.00 | 订单编号 2839481029384756 | 创建时间 2026-11-18 12:00:00",
                nowMillis = nowMillis
            )
        )
        assertFalse(closedOrder.accepted)
        assertEquals(WindowRejectReason.CLOSED_FAILED_OR_REFUND_PENDING, closedOrder.reason)

        // 4. 微信零钱 / 余额宝等资产余额浏览页
        val walletBalancePage = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.tencent.mm",
                verifiedActivity = "com.tencent.mm.plugin.wallet.balance.ui.WalletBalanceManagerUI",
                rawPageText = "我的零钱 | ¥1280.50 | 充值 | 提现 | 零钱通 昨日收益 +0.42 | 2026-11-18 08:00",
                nowMillis = nowMillis
            )
        )
        assertFalse(walletBalancePage.accepted)
        assertEquals(WindowRejectReason.BALANCE_OR_ASSET_PAGE, walletBalancePage.reason)

        // 5. 公众号推文/群聊点开的旧无单号付款成功截图（时间早于当前 15 分钟以上）
        val staleScreenshotInArticle = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.tencent.mm",
                verifiedActivity = "com.tencent.mm.plugin.webview.ui.tools.WebViewUI",
                rawPageText = "晒晒昨日省钱攻略 | 支付成功 | 瑞幸咖啡 | ¥9.90 | 支付时间 2026-11-17 09:30:00 | 完成",
                nowMillis = nowMillis
            )
        )
        assertFalse(staleScreenshotInArticle.accepted)
        assertEquals(WindowRejectReason.STALE_UNNUMBERED_PAGE, staleScreenshotInArticle.reason)

        // 6. 密码核身弹窗：即使手动点击快捷磁贴识屏，也必须强制阻断截屏与读屏
        val passwordPrompt = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.tencent.mm",
                verifiedActivity = "com.tencent.mm.plugin.wallet_core.ui.WalletPwdConfirmUI",
                rawPageText = "请输入支付密码 | 瑞幸咖啡 | ¥9.90 | 2026-11-18 15:59:00",
                hasVisiblePasswordNode = true,
                forceManual = true,
                nowMillis = nowMillis
            )
        )
        assertFalse(passwordPrompt.accepted)
        assertEquals(WindowRejectReason.SENSITIVE_AUTH_PAGE, passwordPrompt.reason)
    }

    @Test
    fun evaluateBillWindow_acceptsGenuineWechatAlipayAndEcommerceBillPages() {
        // 1. 微信扫码付款单笔交易详情页
        val wechatBillDetail = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.tencent.mm",
                verifiedActivity = "com.tencent.mm.plugin.webview.ui.tools.WebViewUI",
                rawPageText = "返回 | 蔬菜李丨未央店 | 交易详情 | -1.30 | 当前状态 | 支付成功 | 支付方式 | 零钱 | 转账时间 | 2026年11月05日 17:45:31 | 转账单号 | 10001073012026110500993151520117 | 对订单有疑惑",
                nowMillis = nowMillis
            )
        )
        assertTrue(wechatBillDetail.accepted)
        assertTrue(wechatBillDetail.hasExplicitOrderNo)

        // 2. 支付宝账单详情页（含右上角“全部账单”入口，绝不能误杀）
        val alipayBillDetail = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.eg.android.AlipayGphone",
                verifiedActivity = "com.eg.android.AlipayGphone.AlipayLogin",
                rawPageText = "返回 | 账单详情 | 全部账单 | 瑞幸咖啡 | -9.90 | 交易成功 | 付款方式 | 招商银行信用卡(3354) | 创建时间 | 2026-11-18 09:15:22 | 订单号 | 2026111822001483920192837465 | 账单分类 | 餐饮美食",
                nowMillis = nowMillis
            )
        )
        assertTrue(alipayBillDetail.accepted)
        assertTrue(alipayBillDetail.hasExplicitOrderNo)

        // 3. 微信实时转账完成页（15 分钟时效窗口内 + 完成按钮 + 明确转账时间）
        val liveWechatTransfer = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.tencent.mm",
                verifiedActivity = "com.tencent.mm.plugin.remittance.ui.RemittanceResultNewUI",
                rawPageText = "支付成功 | 待心始终如一确认收款 | ￥203.85 | 转账时间 | 2026-11-18 15:58:10 | 完成",
                nowMillis = nowMillis
            )
        )
        assertTrue(liveWechatTransfer.accepted)

        // 4. 京东单笔订单详情页（专用 OrderDetailActivityTaro 首屏与带单号完整页）
        val jdOrderDetail = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.jingdong.app.mall",
                verifiedActivity = "com.jd.lib.ordercenter.taro.OrderDetailActivityTaro",
                rawPageText = "订单详情 | 已签收 | 京东自营旗舰店 | 实付款 ¥59.90 | 订单编号 29918273645510 | 下单时间 2026-11-17 20:11:05 | 申请售后 | 查看发票",
                nowMillis = nowMillis
            )
        )
        assertTrue(jdOrderDetail.accepted)
    }

    @Test
    fun evaluateBillWindow_acceptsDedicatedLiveCashierCompletionWithoutTimestamp_andEmbeddedAlipaySdk() {
        // 1. 拼多多跳转支付宝独立收银台（MspUniRenderActivity / FlyBirdWindowActivity）无时间戳完成页 + 划线原价与百次立减
        val alipayCashierDiscount = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.eg.android.AlipayGphone",
                verifiedActivity = "com.alipay.android.msp.ui.views.MspUniRenderActivity",
                rawPageText = "支付成功 | ¥0.08 | 羊驼老头 | ¥0.10 | 百次立减 | -¥0.02 | 付款方式 | 招商银行储蓄卡(8473) | 点击给商家对账 | 完成",
                nowMillis = nowMillis
            )
        )
        assertTrue(alipayCashierDiscount.accepted)
        assertTrue(alipayCashierDiscount.isLiveCashierFallback)
        assertEquals(WindowContainerRole.DEDICATED_PAYMENT_OR_BILL, alipayCashierDiscount.containerRole)
        assertEquals(nowMillis, alipayCashierDiscount.occurredAtMillis)

        // 2. 支付宝 FlyBirdWindowActivity 完成页（含“返回商家”按钮）
        val flyBirdCashier = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.eg.android.AlipayGphone",
                verifiedActivity = "com.alipay.android.app.flybird.ui.window.FlyBirdWindowActivity",
                rawPageText = "支付成功 | 拼多多平台商户 | ¥19.90 | 付款方式 | 花呗 | 返回商家",
                nowMillis = nowMillis
            )
        )
        assertTrue(flyBirdCashier.accepted)
        assertTrue(flyBirdCashier.isLiveCashierFallback)
        assertEquals(nowMillis, flyBirdCashier.occurredAtMillis)

        // 3. 拼多多/淘宝宿主内嵌支付宝 SDK 收银台（com.alipay.sdk.app.H5PayResultActivity）
        assertTrue(CaptureWindowGuard.isBuiltinEcommerceHostPackage("com.xunmeng.pinduoduo"))
        assertTrue(CaptureWindowGuard.isEmbeddedAlipayCashierActivity("com.alipay.sdk.app.H5PayResultActivity"))
        assertFalse(CaptureWindowGuard.isEmbeddedAlipayCashierActivity("com.xunmeng.pinduoduo.activity.NewPageActivity"))

        val embeddedAlipaySdk = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.xunmeng.pinduoduo",
                verifiedActivity = "com.alipay.sdk.app.H5PayResultActivity",
                rawPageText = "付款成功 | 拼多多平台商户 | ¥15.80 | 付款方式 | 余额宝 | 完成",
                nowMillis = nowMillis
            )
        )
        assertTrue(embeddedAlipaySdk.accepted)
        assertTrue(embeddedAlipaySdk.isLiveCashierFallback)
        assertEquals(WindowContainerRole.DEDICATED_PAYMENT_OR_BILL, embeddedAlipaySdk.containerRole)

        // 4. 微信专用支付结果容器与云闪付 UPPayActivity 无时间戳完成页
        val wechatDedicatedCashier = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.tencent.mm",
                verifiedActivity = "com.tencent.mm.plugin.wallet_core.ui.WalletOrderInfoNewUI",
                rawPageText = "支付成功 | 美团平台商户 | ￥26.50 | 支付方式 | 零钱 | 返回商家",
                nowMillis = nowMillis
            )
        )
        assertTrue(wechatDedicatedCashier.accepted)
        assertTrue(wechatDedicatedCashier.isLiveCashierFallback)

        val unionPayCashier = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.unionpay",
                verifiedActivity = "com.unionpay.uppay.PayActivity",
                rawPageText = "支付成功 | 京东商城 | ¥88.00 | 付款方式 | 工商银行储蓄卡(6612) | 完成",
                nowMillis = nowMillis
            )
        )
        assertTrue(unionPayCashier.accepted)
        assertTrue(unionPayCashier.isLiveCashierFallback)

        // 5. OP-3=是：控制中心手动磁贴（forceManual = true）亦支持无时间戳实时付款完成页
        val manualTileLiveCompletion = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.xunmeng.pinduoduo",
                verifiedActivity = "com.xunmeng.pinduoduo.activity.NewPageActivity",
                rawPageText = "支付成功 | 拼多多平台商户 | 实付 ¥12.60 | 完成",
                forceManual = true,
                nowMillis = nowMillis
            )
        )
        assertTrue(manualTileLiveCompletion.accepted)
        assertTrue(manualTileLiveCompletion.isLiveCashierFallback)
        assertEquals(nowMillis, manualTileLiveCompletion.occurredAtMillis)
    }

    @Test
    fun evaluateBillWindow_strictlyBlocksSharedWebStrictImagePreviewAndAmbiguousCashier() {
        // 1. OP-1=方案A：自动模式下严禁放开 SHARED_OR_WEB_STRICT（拼多多 NewPageActivity 无论是“拼单成功”还是“支付成功”无时间一律拦截）
        val pddGroupBuySuccess = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.xunmeng.pinduoduo",
                verifiedActivity = "com.xunmeng.pinduoduo.activity.NewPageActivity",
                rawPageText = "拼单成功 | 羊驼老头 | ¥0.08 | 邀请好友拼单 | 完成",
                nowMillis = nowMillis
            )
        )
        assertFalse(pddGroupBuySuccess.accepted)

        val pddSharedWebPaySuccessWithoutTime = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.xunmeng.pinduoduo",
                verifiedActivity = "com.xunmeng.pinduoduo.activity.NewPageActivity",
                rawPageText = "支付成功 | 羊驼老头 | ¥0.08 | 付款方式 | 招商银行储蓄卡(8473) | 完成",
                nowMillis = nowMillis
            )
        )
        assertFalse(pddSharedWebPaySuccessWithoutTime.accepted)
        assertEquals(WindowContainerRole.SHARED_OR_WEB_STRICT, pddSharedWebPaySuccessWithoutTime.containerRole)
        assertEquals(WindowRejectReason.MISSING_OCCURRED_TIME, pddSharedWebPaySuccessWithoutTime.reason)

        // 2. 自动模式下微信 WebViewUI / 支付宝 AlipayLogin 共享容器无时间戳页面绝不走收银台兜底（防公众号晒单推文误记）
        val wechatArticleFakeSuccess = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.tencent.mm",
                verifiedActivity = "com.tencent.mm.plugin.webview.ui.tools.WebViewUI",
                rawPageText = "支付成功 | 瑞幸咖啡 | ¥9.90 | 完成",
                nowMillis = nowMillis
            )
        )
        assertFalse(wechatArticleFakeSuccess.accepted)
        assertEquals(WindowRejectReason.MISSING_OCCURRED_TIME, wechatArticleFakeSuccess.reason)

        // 3. AC-3：内嵌看图预览特征词（保存图片/查看原图/发送给朋友/转发截图/识别图中二维码/提取图中文字）在自动与手动模式下均 100% 拦截
        for (marker in listOf("保存图片", "查看原图", "发送给朋友", "转发截图", "识别图中二维码", "提取图中文字")) {
            val autoPreview = CaptureWindowGuard.evaluateBillWindow(
                WindowInspectionInput(
                    packageName = "com.eg.android.AlipayGphone",
                    verifiedActivity = "com.alipay.android.msp.ui.views.MspUniRenderActivity",
                    rawPageText = "支付成功 | 瑞幸咖啡 | ¥9.90 | 完成 | $marker",
                    nowMillis = nowMillis
                )
            )
            assertFalse("Auto mode must reject image viewer marker: $marker", autoPreview.accepted)
            assertEquals(WindowRejectReason.NON_TRANSACTION_CONTAINER, autoPreview.reason)

            val manualPreview = CaptureWindowGuard.evaluateBillWindow(
                WindowInspectionInput(
                    packageName = "com.tencent.mm",
                    verifiedActivity = "com.tencent.mm.plugin.webview.ui.tools.WebViewUI",
                    rawPageText = "支付成功 | 瑞幸咖啡 | ¥9.90 | 支付时间 2026-11-18 15:58:00 | 完成 | $marker",
                    forceManual = true,
                    nowMillis = nowMillis
                )
            )
            assertFalse("Manual mode must also reject image viewer marker: $marker", manualPreview.accepted)
            assertEquals(WindowRejectReason.NON_TRANSACTION_CONTAINER, manualPreview.reason)
        }

        // 4. 缺少完成/返回商家控制词的半截过渡页拦截
        val missingControlWord = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.eg.android.AlipayGphone",
                verifiedActivity = "com.alipay.android.msp.ui.views.MspUniRenderActivity",
                rawPageText = "支付成功 | 瑞幸咖啡 | ¥9.90 | 付款方式 | 花呗",
                nowMillis = nowMillis
            )
        )
        assertFalse(missingControlWord.accepted)
        assertEquals(WindowRejectReason.MISSING_OCCURRED_TIME, missingControlWord.reason)

        // 5. 多笔无实付标签的红包/权益金额干扰且不满足原价减优惠守恒时拒绝盲猜
        val ambiguousPromoCashier = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.eg.android.AlipayGphone",
                verifiedActivity = "com.alipay.android.msp.ui.views.MspUniRenderActivity",
                rawPageText = "支付成功 | 瑞幸咖啡 | ¥9.90 | ¥5.00 | 消费金 ¥8.88 | 完成",
                nowMillis = nowMillis
            )
        )
        assertFalse(ambiguousPromoCashier.accepted)
        assertEquals(WindowRejectReason.AMBIGUOUS_PROMO_AMOUNTS, ambiguousPromoCashier.reason)

        // 6. 付款前待支付收银台（确认付款 / 立即支付）严禁提前误记
        val unpaidCashier = CaptureWindowGuard.evaluateBillWindow(
            WindowInspectionInput(
                packageName = "com.eg.android.AlipayGphone",
                verifiedActivity = "com.alipay.android.msp.ui.views.MspContainerActivity",
                rawPageText = "确认付款 | 拼多多平台商户 | ¥19.90 | 付款方式 | 招商银行信用卡(3354) | 立即付款",
                forceManual = true,
                nowMillis = nowMillis
            )
        )
        assertFalse(unpaidCashier.accepted)
        assertEquals(WindowRejectReason.CASHIER_OR_UNPAID_STATE, unpaidCashier.reason)
    }
}
