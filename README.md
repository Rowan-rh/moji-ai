# 默记 MoJi

> **默默记好每一笔账，不打扰你的生活。**

一款融合 **iCost 极简卡片交互体验** 与 **AI 全自动静默识账引擎** 的安卓原生开源记账应用（Kotlin + Jetpack Compose + Room），彻底告别传统自动记账软件“正则表达式规则维护地狱”与繁琐弹窗打扰。

## 核心亮点

1. **AI 三模无感采集（通知栏 + 微信/支付宝/拼多多/抖音/京东等全场景无障碍识账 + 免 Root 端侧 OCR 兜底）+ 30s 跨通道事后证据融合 (`AI_FUSED`)**：
   - [`PaymentNotificationListenerService`](app/src/main/java/com/moji/ai/service/PaymentNotificationListenerService.kt)：实时捕获微信支付、支付宝、云闪付、银行 App 及扣款短信通知。
   - [`SelectToSpeakService`](app/src/main/java/com/google/android/accessibility/selecttospeak/SelectToSpeakService.kt) & [`PaymentAccessibilityService`](app/src/main/java/com/moji/ai/service/PaymentAccessibilityService.kt)：参考 AutoAccounting 与花有谱账本，通过 `SelectToSpeakService` 兼容入口 + `FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY` 突破微信 WebView/LiteApp 节点屏蔽；采用多窗口包名隔离、4 阶延迟探测（`150/450/1000/1800ms`）与单笔详情锚点过滤，全面支持 **微信支付、支付宝、云闪付、拼多多、抖音/抖音支付、京东、淘宝、天猫、美团/美团外卖、饿了么、唯品会、小红书** 的「支付成功 / 拼单成功 / 账单详情 / 订单详情」页面；遇到自绘页面或点击控制中心「默记识屏记账」磁贴（[`OcrTileService`](app/src/main/java/com/moji/ai/service/OcrTileService.kt)）时，调用 Android 11+ `takeScreenshot` + 端侧离线中文 OCR 兜底。
   - [`CapturePipelineCoordinator`](app/src/main/java/com/moji/ai/service/CapturePipelineCoordinator.kt)、[`BillScreenshotStorage`](app/src/main/java/com/moji/ai/service/BillScreenshotStorage.kt) & [`RecognitionBanner`](app/src/main/java/com/moji/ai/service/RecognitionBanner.kt)：
     - **iCost 模板「原价 · 优惠 · 实扣」三元组自动解析与记录**：针对拼多多、抖音支付、京东、淘宝、美团及微信/支付宝账单，智能合并跨 `TextView` 拆分金额（如 `¥ | 29 | .90`），优先锁定 `实付款 / 实扣金额` 作为入账实扣金额，同时提取 `商品总价 / 原价` 与 `店铺优惠 / 平台优惠 / 百亿补贴 / 多多支付立减 / 抖音支付优惠 / 京豆 / 红包` 优惠金额，以 **`原价¥X.XX · 优惠¥Y.YY · 实扣¥Z.ZZ | 商品名称 · 支付方式`** 模板格式完整记录并在流体云胶囊、首页账单行及账单详情页直观展示；
     - **核心四要素留存、同笔账单详情自动补全与双规则防重（默认不录入 + 弹窗点“录入”才入账）**：提取并留存 **订单唯一 Key (`orderKey`，订单号/交易单号/商户单号)**、**实际购物发生时间 (`timestamp`，非截图时间)**、**交易金额 (`amountCents`)** 与 **商品数量 (`itemQuantity` / `quantityDesc`)**；严格执行两条同笔/重复判定规则：① 订单唯一 Key 一致即认定为同一笔；② 任一方无订单 Key 时，只要「实际交易发生时间 + 金额」一致即认定为同一笔。当用户后续点开账单详情页或点开「更多」露出原记录缺失的细节（订单号、商户单号、支付方式、商品说明、具体商户、数量、优惠金额等）时，**自动将新细节增量合并回原账单并修正扣款账户**（流体云提示 **「已补全账单详情」**）；若原账单细节早已齐全无新增字段，则判定为重复扫描，**默认绝不录入数据库**，在流体云胶囊提示 **「检测到疑似重复 · 未录入」**，仅当用户主动点击 **「录入」** 按钮时才写入账本；
     - **识账原图保存**：识屏（无障碍详情页与端侧 OCR）自动将账单截图压缩为 WebP 保存在 `files/bill_screenshots/`，支持在账单详情页点开查看原图；
     - **支付账号精准匹配**：银行卡按后 4 位尾号（如 `招商银行信用卡(3354)`、`储蓄卡(8848)`）匹配，花呗、白条、零钱、账户余额按汉字关键词匹配并自动扣减对应账户余额或增加待还欠款；
     - **一加流体云（Fluid Cloud）120Hz 挖孔延伸胶囊（点击直达当前账单详情）**：设置 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` 切入顶部挖孔区，展开时从前置摄像头挖孔向下拉出水滴连接颈并丝滑撑开为曜石黑实况胶囊（自绘抗锯齿发光图标 + 凝缩金额 + 正常入账「撤销」/ 疑似重复「录入」大热区按钮），点击胶囊主体直接打开当前记录的「账单详情」页，退出账单页、超时或上滑时平滑回吸缩进摄像头孔内，内置看门狗杜绝悬浮窗残留。
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

