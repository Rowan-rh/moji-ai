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
3. **iCost 级丝滑交互与全能资产账本**：
   - **账本首页**（[`LedgerHomeTab.kt`](app/src/main/java/com/moji/ai/ui/screens/LedgerHomeTab.kt)）：多账本切换、关键词实时搜索、月度收支/预算/AI 概览三合一卡片、收支日历视图、按日可折叠卡片组、全额/部分退款划线冲销展示。
   - **记账计算器面板**（[`ICostCalculatorSheet.kt`](app/src/main/java/com/moji/ai/ui/components/ICostCalculatorSheet.kt)）：支出/收入/转账/还款四模式切换、二级子分类自动展开网格、账户/报销/优惠/标签快捷胶囊、支持加减实时运算的 `4×4` 圆角键盘。
   - **全能资产管理**（[`AssetsTab.kt`](app/src/main/java/com/moji/ai/ui/screens/AssetsTab.kt)）：净资产一键显隐 (`👁️`)、总借入/总借出联动、可折叠信用账户（信用卡/花呗/白条还款日倒计时与可用额度）与资金账户、账户独立流水与一键还款/提现。
   - **多维统计面板**（[`StatisticsTab.kt`](app/src/main/java/com/moji/ai/ui/screens/StatisticsTab.kt)）：周/月/年/全部维度切换，内置收支统计、报销统计、流转统计三大宫格卡片及支出分类占比排行。
4. **纯后台静默入账 + 通知栏一键撤销**：
   - [`SilentBookkeepingNotifier`](app/src/main/java/com/moji/ai/notification/SilentBookkeepingNotifier.kt)：后台自动入账后推送系统通知，点击通知栏上的 **「撤销入账」** 即可原子化回滚账单、账户余额与退款冲销额度。

## 构建与安装

```bash
./gradlew assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

详细产品需求与架构设计参见 [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md)。
