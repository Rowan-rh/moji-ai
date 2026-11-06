# 默记 MoJi

> **默默记好每一笔账，不打扰你的生活。**

一款融合 **iCost 极简卡片交互体验** 与 **AI 全自动静默识账引擎** 的安卓原生开源记账应用（Kotlin + Jetpack Compose + Room），彻底告别传统自动记账软件“正则表达式规则维护地狱”与繁琐弹窗打扰。

## 核心亮点

1. **AI 双通道无感采集 + 智能防重合并**：
   - [`PaymentNotificationListenerService`](app/src/main/java/com/moji/ai/service/PaymentNotificationListenerService.kt)：实时捕获微信支付、支付宝、云闪付、银行 App 及扣款短信通知。
   - [`PaymentAccessibilityService`](app/src/main/java/com/moji/ai/service/PaymentAccessibilityService.kt)：包名白名单前置过滤 + 350ms 节流机制，轻量级提取支付完成页、转账详情页文本节点树（无需截屏 OCR，极速省电且绝不拖慢宿主应用滚动帧率）。
   - [`CapturePipelineCoordinator`](app/src/main/java/com/moji/ai/service/CapturePipelineCoordinator.kt)：1.1 秒滑动聚合窗口，将同一笔交易的“页面商户文本”与“银行卡扣款通知”合并后统一交给 AI 解析，杜绝重复入账。
2. **通用大模型兼容 + 离线高智商语义兜底**：
   - [`AiAccountingEngine`](app/src/main/java/com/moji/ai/ai/AiAccountingEngine.kt)：支持任意 OpenAI 兼容接口（DeepSeek、通义千问、硅基流动、OpenAI、本地 Ollama）及 Google Gemini 官方接口，未填 Key 或离线时自动启用本地语义引擎兜底。
3. **1:1 iCost 级视觉、丝滑弹簧动效与触觉交互体系**：
   - **原生字体、图标与弹簧按压反馈**（[`ICostTheme.kt`](app/src/main/java/com/moji/ai/ui/theme/ICostTheme.kt)）：内置 `ICDIN-Bold` 凝缩数字字体与 200 枚线性分类/银行品牌圆形图标，全端搭载 `iCostPressScale` 弹簧按压回弹 + 轻触觉震动反馈（`LocalHapticFeedback`）与 `AnimatedMoneyText` 数字滚动过渡。
   - **账本首页**（[`LedgerHomeTab.kt`](app/src/main/java/com/moji/ai/ui/screens/LedgerHomeTab.kt)）：多账本切换、关键词实时搜索、`HorizontalPager` 三页左右滑动月度概览卡片（带动态胶囊指示器）、全屏收支日历页、按日弹簧收折卡片组（带箭头旋转与列表项位移过渡）、全额/部分退款划线冲销展示与全屏账单详情页。
   - **记账计算器面板**（[`ICostCalculatorSheet.kt`](app/src/main/java/com/moji/ai/ui/components/ICostCalculatorSheet.kt)）：支出/收入/转账三态平滑过渡、5 列线性分类网格（选中弹簧放大 + 二级子分类盒平滑展开）、转账账户 `180°` 旋转互换动效、支持 `+ - × ÷` 连续运算的 `4×4` 触觉圆角键盘。
   - **全能资产管理**（[`AssetsTab.kt`](app/src/main/java/com/moji/ai/ui/screens/AssetsTab.kt)）：净资产动态滚动与一键显隐、总借入/总借出双卡、弹簧收折式信用账户与资金账户、全屏水平推入式账户详情页及账户类型选择页。
   - **多维统计与设置中心**（[`StatisticsTab.kt`](app/src/main/java/com/moji/ai/ui/screens/StatisticsTab.kt)、[`MoJiMainScreen.kt`](app/src/main/java/com/moji/ai/ui/MoJiMainScreen.kt)）：周/月/年/全部/范围五段切换，收支/报销/流转三大可收折 Bento 宫格卡片，外加带渐进生长/铺展动效的每日收支柱状图、净资产平滑趋势图、多彩分类环形图，以及底部主 Tab 方向感知滑动切换与全屏二级页水平推入动效。
4. **纯后台静默入账 + 通知栏一键撤销**：
   - [`SilentBookkeepingNotifier`](app/src/main/java/com/moji/ai/notification/SilentBookkeepingNotifier.kt)：后台自动入账后推送系统通知，点击通知栏上的 **「撤销入账」** 即可原子化回滚账单、账户余额与退款冲销额度。

## 构建与安装

```bash
./gradlew assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

详细产品需求与架构设计参见 [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md)。
