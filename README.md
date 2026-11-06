# 默记 MoJi

> **默默记好每一笔账，不打扰你的生活。**

一款融合 **iCost 极简卡片交互体验** 与 **AI 全自动静默识账引擎** 的安卓原生开源记账应用（Kotlin + Jetpack Compose + Room），彻底告别传统自动记账软件“正则表达式规则维护地狱”与繁琐弹窗打扰。

## 核心亮点

1. **AI 三模无感采集（通知栏 + 微信/支付宝无障碍兼容入口 + 免 Root 端侧 OCR 兜底）+ 30s 跨通道事后证据融合 (`AI_FUSED`)**：
   - [`PaymentNotificationListenerService`](app/src/main/java/com/moji/ai/service/PaymentNotificationListenerService.kt)：实时捕获微信支付、支付宝、云闪付、银行 App 及扣款短信通知。
   - [`SelectToSpeakService`](app/src/main/java/com/google/android/accessibility/selecttospeak/SelectToSpeakService.kt) & [`PaymentAccessibilityService`](app/src/main/java/com/moji/ai/service/PaymentAccessibilityService.kt)：参考 AutoAccounting 与花有谱账本，通过 `SelectToSpeakService` 兼容入口 + `FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY` 突破微信 WebView/LiteApp 节点屏蔽；采用多窗口包名隔离、4 阶延迟探测（`150/450/1000/1800ms`）与单笔详情锚点过滤，精准识别微信/支付宝「支付成功」与「账单详情」页；遇到自绘页面或点击控制中心「默记识屏记账」磁贴（[`OcrTileService`](app/src/main/java/com/moji/ai/service/OcrTileService.kt)）时，调用 Android 11+ `takeScreenshot` + 端侧离线中文 OCR 兜底。
   - [`CapturePipelineCoordinator`](app/src/main/java/com/moji/ai/service/CapturePipelineCoordinator.kt) & [`RecognitionBanner`](app/src/main/java/com/moji/ai/service/RecognitionBanner.kt)：支持交易单号/支付时间级幂等去重、滑动窗口聚合与 **30 秒跨通道事后证据融合（Evidence Fusion Patch）**；入账瞬间通过免悬浮窗权限的 `TYPE_ACCESSIBILITY_OVERLAY` 在屏幕顶部弹出胶囊提示（如 `✓ 已自动记账 -¥7.51`）。
2. **通用大模型兼容 + 商户记忆自学习 + 可视化条件规则 (`When -> Then`) + 带排除词权重的离线语义引擎**：
   - [`AiAccountingEngine`](app/src/main/java/com/moji/ai/ai/AiAccountingEngine.kt)、[`MerchantMemoryAndWeightedClassifier.kt`](app/src/main/java/com/moji/ai/ai/MerchantMemoryAndWeightedClassifier.kt) & [`SmartRuleEngine.kt`](app/src/main/java/com/moji/ai/ai/SmartRuleEngine.kt)：支持任意 OpenAI 兼容接口与 Google Gemini 官方接口；内置 **商户记忆自学习引擎 (`MerchantMemoryManager`)**、**带排除词一票否决权重的离线分类器 (`WeightedCategoryClassifier`)** 以及 **可视化条件规则引擎 (`SmartRuleEngine`)**（支持按关键词、金额区间、24 小时时段组合自动覆盖分类/账本/标签、自动标记待报销或静默拦截验证码通知）。
3. **1:1 iCost 级视觉、模块侧滑扩展功能与丝滑弹簧动效体系**：
   - **原生字体、图标、弹簧按压与模块侧滑扩展容器**（[`ICostTheme.kt`](app/src/main/java/com/moji/ai/ui/theme/ICostTheme.kt)）：内置 `ICDIN-Bold` 凝缩数字字体与 200 枚线性分类/银行品牌圆形图标，全端搭载 `iCostPressScale` 弹簧按压回弹 + 轻触觉震动反馈（`LocalHapticFeedback`）、`AnimatedMoneyText` 数字滚动过渡，以及 `ICostSwipeActionRow` 双向阻尼侧滑扩展功能容器与 `iCostHorizontalSwipeTrigger` 手势切换修饰符。
   - **账本首页与单笔分拆账单**（[`LedgerHomeTab.kt`](app/src/main/java/com/moji/ai/ui/screens/LedgerHomeTab.kt)、[`TransactionDetailAndCalendarSheets.kt`](app/src/main/java/com/moji/ai/ui/components/TransactionDetailAndCalendarSheets.kt)）：多账本切换、标题栏右滑/点击展开 **快捷记账模板抽屉**（点击模板一键秒记）、关键词实时搜索、`HorizontalPager` 三页左右滑动月度概览卡片、年月栏左右滑动切月、全屏收支日历页、按日弹簧收折卡片组、**单笔账单行右滑/左滑呼出「编辑 / 复制 / 报销 / 退款 / 删除」扩展胶囊**，以及账单详情页 **「单笔分拆为多个分类 (Split Transaction)」**。
   - **记账计算器面板**（[`ICostCalculatorSheet.kt`](app/src/main/java/com/moji/ai/ui/components/ICostCalculatorSheet.kt)）：支出/收入/转账三态手势滑动与点击平滑过渡、5 列线性分类网格（选中弹簧放大 + 二级子分类盒平滑展开）、转账账户 `180°` 旋转互换动效、支持 `+ - × ÷` 连续运算的 `4×4` 触觉圆角键盘。
   - **全能资产管理与余额锚点平账**（[`AssetsTab.kt`](app/src/main/java/com/moji/ai/ui/screens/AssetsTab.kt)）：净资产双页可滑动大卡（第 1 页净资产/总资产/总负债，右滑/左滑切换第 2 页 **存钱计划进度与信用卡分期管理概览**）、总借入/总借出双卡、**单个信用/资金账户行右滑/左滑呼出「还款(转账) / 记一笔 / 调平 / 删除」扩展胶囊**、以及账户详情页 **「校准真实余额（自动计算差额并生成 `BALANCE_ANCHOR` 平账流水）」**。
   - **多维统计、批量报销结算、CSV 导入导出与 WebDAV 自托管云同步**（[`StatisticsTab.kt`](app/src/main/java/com/moji/ai/ui/screens/StatisticsTab.kt)、[`BackupAndCsvTransferEngine.kt`](app/src/main/java/com/moji/ai/data/backup/BackupAndCsvTransferEngine.kt)、[`WebDavSyncClient.kt`](app/src/main/java/com/moji/ai/data/backup/WebDavSyncClient.kt)）：周/月/年/全部四段切换（按当前账本统计，净资产趋势由真实账单倒推）、**批量报销核销中心 (`batchSettleReimbursements`)**、**微信支付官方 CSV / 支付宝官方 CSV / 钱迹 / iCost CSV 自动识别预览与防重导入**、带 `BackupSchemaGuard` 兼容守卫的 `.mojibackup` 全量 JSON 快照备份恢复，以及支持坚果云 / Nextcloud / 群晖的 **WebDAV 云备份上传与恢复**。
4. **纯后台静默入账 + 通知栏三键快捷操作 + 国产 ROM 保活向导**：
   - [`SilentBookkeepingNotifier`](app/src/main/java/com/moji/ai/notification/SilentBookkeepingNotifier.kt) & [`RomKeepAliveGuideHelper`](app/src/main/java/com/moji/ai/service/RomKeepAliveGuideHelper.kt)：后台自动入账后推送系统通知，通知栏提供 **「撤销入账」「切换分类（联动写入商户记忆库）」「标为待报销」** 三大快捷按钮；内置针对 ColorOS / HyperOS / OriginOS / HarmonyOS 的 **后台保活与 Android 13+ 受限设置一键解除诊断向导**。

## 构建与安装

```bash
./gradlew assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

## 文档导航

- **产品需求与技术规格**：[`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md)
- **现有功能与架构全景梳理**：[`docs/CURRENT_FEATURES.md`](docs/CURRENT_FEATURES.md)
- **42 个开源项目深度对标与可借鉴特性演进蓝图**：[`docs/OPEN_SOURCE_BENCHMARK_AND_ROADMAP.md`](docs/OPEN_SOURCE_BENCHMARK_AND_ROADMAP.md)

