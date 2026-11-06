# 默记 MoJi

> **默默记好每一笔账，不打扰你的生活。**

一款融合 **iCost 极简卡片交互体验** 与 **AI 全自动静默识账引擎** 的安卓原生开源记账应用（Kotlin + Jetpack Compose + Room），彻底告别传统自动记账软件“正则表达式规则维护地狱”与繁琐弹窗打扰。

## 核心亮点

1. **AI 双通道无感采集 + 智能防重合并**：
   - [`PaymentNotificationListenerService`](app/src/main/java/com/moji/ai/service/PaymentNotificationListenerService.kt)：实时捕获微信支付、支付宝、云闪付、银行 App 及扣款短信通知。
   - [`PaymentAccessibilityService`](app/src/main/java/com/moji/ai/service/PaymentAccessibilityService.kt)：轻量级提取支付完成页、转账详情页文本节点树（无需截屏 OCR，极速省电）。
   - [`CapturePipelineCoordinator`](app/src/main/java/com/moji/ai/service/CapturePipelineCoordinator.kt)：1.1 秒滑动聚合窗口，将同一笔交易的“页面商户文本”与“银行卡扣款通知”合并后统一交给 AI 解析，杜绝重复入账。
2. **通用大模型兼容 + 离线高智商语义兜底**：
   - [`AiAccountingEngine`](app/src/main/java/com/moji/ai/ai/AiAccountingEngine.kt)：支持任意 OpenAI 兼容接口（DeepSeek、通义千问、硅基流动、OpenAI、本地 Ollama）及 Google Gemini 官方接口，未填 Key 或离线时自动启用本地语义引擎兜底。
3. **1:1 iCost 级视觉与交互体系**：
   - **原生字体与图标资产**（[`ICostTheme.kt`](app/src/main/java/com/moji/ai/ui/theme/ICostTheme.kt)）：内置 `ICDIN-Bold` 凝缩数字字体与 200 枚线性分类/银行品牌圆形图标（招行、工行、建行、白条、花呗、微粒贷、支付宝、微信等），搭配半透明毛玻璃悬浮胶囊底栏。
   - **账本首页**（[`LedgerHomeTab.kt`](app/src/main/java/com/moji/ai/ui/screens/LedgerHomeTab.kt)）：多账本切换、关键词实时搜索、`HorizontalPager` 三页左右滑动月度概览卡片（总支出 / 剩余月预算 / 待报销）、全屏收支日历页、按日可折叠卡片组、全额/部分退款划线冲销展示与全屏账单详情页。
   - **记账计算器面板**（[`ICostCalculatorSheet.kt`](app/src/main/java/com/moji/ai/ui/components/ICostCalculatorSheet.kt)）：支出/收入/转账三态切换（转账至信用卡自动识别为还款）、5 列线性分类网格与二级子分类盒展开、账户/报销/优惠/手续费/图片/标签快捷胶囊、支持 `+ - × ÷` 连续运算的 `4×4` 圆角键盘。
   - **全能资产管理**（[`AssetsTab.kt`](app/src/main/java/com/moji/ai/ui/screens/AssetsTab.kt)）：净资产一键显隐、总借入/总借出双卡、整卡收折式信用账户与资金账户、全屏账户详情页（银联水印 + 独立月度流水 + 底部悬浮还款/记一笔胶囊）及全屏账户类型选择页。
   - **多维统计与设置中心**（[`StatisticsTab.kt`](app/src/main/java/com/moji/ai/ui/screens/StatisticsTab.kt)、[`MoJiMainScreen.kt`](app/src/main/java/com/moji/ai/ui/MoJiMainScreen.kt)）：周/月/年/全部/范围五段切换，收支/报销/流转三大 Bento 宫格卡片，外加每日收支柱状图、净资产平滑趋势图、多彩分类环形图及全屏设置/智能记账中心。
4. **纯后台静默入账 + 通知栏一键撤销**：
   - [`SilentBookkeepingNotifier`](app/src/main/java/com/moji/ai/notification/SilentBookkeepingNotifier.kt)：后台自动入账后推送系统通知，点击通知栏上的 **「撤销入账」** 即可原子化回滚账单、账户余额与退款冲销额度。

## 构建与安装

```bash
./gradlew assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

## 文档导航

- **产品需求与技术规格**：[`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md)
- **现有功能与架构全景梳理**：[`docs/CURRENT_FEATURES.md`](docs/CURRENT_FEATURES.md)
- **42 个开源项目深度对标与可借鉴特性演进蓝图**：[`docs/OPEN_SOURCE_BENCHMARK_AND_ROADMAP.md`](docs/OPEN_SOURCE_BENCHMARK_AND_ROADMAP.md)

