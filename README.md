# 默记 MoJi

> **默默记好每一笔账，不打扰你的生活。**

一款基于 **AI 语义理解** 的全自动静默记账安卓原生应用（Kotlin + Jetpack Compose + Room），彻底告别传统自动记账软件“正则表达式规则频繁失效”的维护痛点。

## 核心特性

1. **双通道无感采集 + 智能防抖合并**：
   - [`PaymentNotificationListenerService`](app/src/main/java/com/moji/ai/service/PaymentNotificationListenerService.kt)：自动监听微信、支付宝、云闪付、银行 App 及扣款短信通知。
   - [`PaymentAccessibilityService`](app/src/main/java/com/moji/ai/service/PaymentAccessibilityService.kt)：轻量级提取支付成功页、转账详情页文本节点树（无需截屏 OCR，极速省电）。
   - [`CapturePipelineCoordinator`](app/src/main/java/com/moji/ai/service/CapturePipelineCoordinator.kt)：1.1 秒滑动聚合窗口，将同一笔交易的“页面商户文本”与“银行卡扣款通知”合并后统一交给 AI 分析，杜绝重复记账。
2. **通用 AI 引擎 + 本地高智商语义兜底**：
   - [`AiAccountingEngine`](app/src/main/java/com/moji/ai/ai/AiAccountingEngine.kt)：支持任意 OpenAI 兼容接口（DeepSeek、通义千问、硅基流动、OpenAI、本地 Ollama）与 Google Gemini 官方接口，并内置离线语义解析兜底。
3. **5 大真实交易形态与全能钱包**：
   - 支持 **日常支出 (`EXPENSE`)**、**日常收入 (`INCOME`)**、**资产内部转账 (`TRANSFER`)**、**信用卡/花呗还款 (`REPAYMENT`)**、**退款/报销/朋友 AA 收款自动冲销原支出 (`REFUND_OFFSET`)**。
   - 支持多币种（`CNY`, `USD`, `HKD`, `EUR`, `JPY`, `GBP`）自动汇率折算与周期性固定账单（房租/订阅）自动入账。
4. **纯后台静默入账 + 通知栏一键撤销**：
   - [`SilentBookkeepingNotifier`](app/src/main/java/com/moji/ai/notification/SilentBookkeepingNotifier.kt)：后台自动入账后推送通知提醒，点击通知栏上的 **「撤销入账」** 即可原子化回滚账单、钱包余额与冲销额度。

## 构建与运行

使用 Android Studio 打开本目录 `/Users/huangruohao/.gemini/antigravity/scratch/moji-ai` 即可直接同步 Gradle 并编译安装到安卓设备。
