# 默记 MoJi

> **默默记好每一笔账，不打扰你的生活。**

一款融合 **iCost 极简卡片交互体验** 与 **AI 全自动静默识账引擎** 的安卓原生开源记账应用（Kotlin + Jetpack Compose + Room），彻底告别传统自动记账软件“正则表达式规则维护地狱”与繁琐弹窗打扰。

## 核心亮点

1. **AI 三模无感采集（通知栏 + 微信/支付宝/拼多多/抖音/京东等全场景无障碍识账 + 免 Root 端侧 OCR 兜底）+ 30s 跨通道事后证据融合 (`AI_FUSED`)**：
   - [`PaymentNotificationListenerService`](app/src/main/java/com/moji/ai/service/PaymentNotificationListenerService.kt)：实时捕获微信支付、支付宝、云闪付、银行 App 及扣款短信通知。
   - [`SelectToSpeakService`](app/src/main/java/com/google/android/accessibility/selecttospeak/SelectToSpeakService.kt) & [`PaymentAccessibilityService`](app/src/main/java/com/moji/ai/service/PaymentAccessibilityService.kt)：参考 AutoAccounting 与花有谱账本，通过 `SelectToSpeakService` 兼容入口 + `FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY` 突破微信 WebView/LiteApp 节点屏蔽；采用多窗口包名隔离、4 阶延迟探测（`150/450/1000/1800ms`）与单笔详情锚点过滤，全面支持 **微信支付、支付宝、云闪付、拼多多、抖音/抖音支付、京东、淘宝、天猫、美团/美团外卖、饿了么、唯品会、小红书** 的「支付成功 / 拼单成功 / 账单详情 / 订单详情」页面；遇到自绘页面或点击控制中心「默记识屏记账」磁贴（[`OcrTileService`](app/src/main/java/com/moji/ai/service/OcrTileService.kt)）时，调用 Android 11+ `takeScreenshot` + 端侧离线中文 OCR 兜底。
   - [`CapturePipelineCoordinator`](app/src/main/java/com/moji/ai/service/CapturePipelineCoordinator.kt)、[`BillScreenshotStorage`](app/src/main/java/com/moji/ai/service/BillScreenshotStorage.kt) & [`RecognitionBanner`](app/src/main/java/com/moji/ai/service/RecognitionBanner.kt)：
     - **iCost 模板「原价 · 优惠 · 实扣」三元组自动解析与记录**：针对拼多多、抖音支付、京东、淘宝、美团及微信/支付宝账单，智能合并跨 `TextView` 拆分金额（如 `¥ | 29 | .90`），优先锁定 `实付款 / 实扣金额` 作为入账实扣金额，同时提取 `商品总价 / 原价` 与 `店铺优惠 / 平台优惠 / 百亿补贴 / 多多支付立减 / 抖音支付优惠 / 京豆 / 红包` 优惠金额，以 **`原价¥X.XX · 优惠¥Y.YY · 实扣¥Z.ZZ | 商品名称 · 支付方式`** 模板格式完整记录并在流体云胶囊、首页账单行及账单详情页直观展示；
     - **核心四要素留存、无明确时间强制拦截、同笔账单详情自动补全、跨应用同单融合与防重机制（默认不录入 + 弹窗点“录入”才入账）**：提取并留存 **订单唯一 Key (`orderKey`，订单号/交易单号/商户单号)**、**实际购物发生时间 (`timestamp`，非截图时间)**、**交易金额 (`amountCents`)** 与 **商品数量 (`itemQuantity` / `quantityDesc`)**；若采集页面缺失明确的支付/下单时间（如仅展示“支付成功 + 商户 + 金额 + 完成”的过渡页），直接拦截不予录入；严格执行同笔/重复判定规则：① 订单唯一 Key / 交易单号 / 商户单号交叉一致或跨端内嵌引用（如微信账单内含京东订单编号）即认定为同一笔；② 无论双方是否有订单号，只要「（实际支付时间 OR 采集登记时间）+ 金额」一致即认定为同一笔；③ **跨应用同笔订单融合（拼多多/京东/淘宝/抖音订单详情 ↔ 支付宝/微信支付账单详情）**：当用户在电商平台与支付工具分别扫到同一笔购物时，自动将电商侧的**具体店铺名、商品标题、原价与优惠明细、电商订单号**与支付侧的**真实扣款银行卡/花呗账户、支付流水号与商户单号**双向互补融合为一笔完整账单并同步校准扣款钱包，流体云提示 **「已补全账单详情」**；若原账单细节早已齐全无新增字段，则判定为重复扫描，**默认绝不录入数据库**，在流体云胶囊提示 **「检测到疑似重复 · 未录入」**，仅当用户主动点击 **「录入」** 按钮时才写入账本；
     - **识账原图保存与生命周期自动清理**：识屏（无障碍详情页与端侧 OCR）自动将账单截图压缩为 WebP 保存在 `files/bill_screenshots/`，支持在账单详情页异步解码点开查看原图；撤销入账或删除账单时自动清理无引用的本地截图文件；
     - **支付账号精准匹配**：银行卡按后 4 位尾号（如 `招商银行信用卡(3354)`、`储蓄卡(8848)`）匹配，花呗、白条、零钱、账户余额按汉字关键词匹配并自动扣减对应账户余额或增加待还欠款；
     - **一加流体云（Fluid Cloud）120Hz 挖孔延伸胶囊（点击直达当前账单详情）**：设置 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` 切入顶部挖孔区，展开时从前置摄像头挖孔向下拉出水滴连接颈并丝滑撑开为曜石黑实况胶囊（自绘抗锯齿发光图标 + 凝缩金额 + 正常入账「撤销」/ 疑似重复「录入」大热区按钮），点击胶囊主体直接打开当前记录的「账单详情」页，退出账单页、超时或上滑时平滑回吸缩进摄像头孔内，内置看门狗杜绝悬浮窗残留。
2. **通用大模型兼容 + Agent 账单与截图分类梳理（`待梳理` 免分类记账）+ 商户记忆自学习 + 可视化条件规则 (`When -> Then`) + 带排除词权重的离线语义引擎**：
   - [`AiAccountingEngine`](app/src/main/java/com/moji/ai/ai/AiAccountingEngine.kt)、[`AgentBillTriageEngine.kt`](app/src/main/java/com/moji/ai/ai/AgentBillTriageEngine.kt)、[`AgentTriageBottomSheet.kt`](app/src/main/java/com/moji/ai/ui/components/AgentTriageBottomSheet.kt)、[`MerchantMemoryAndWeightedClassifier.kt`](app/src/main/java/com/moji/ai/ai/MerchantMemoryAndWeightedClassifier.kt) & [`SmartRuleEngine.kt`](app/src/main/java/com/moji/ai/ai/SmartRuleEngine.kt)：支持任意 OpenAI 兼容接口与 Google Gemini 官方接口；支持 **「待梳理（交由 Agent）」免分类极速记账**（记账计算器可一键切入或再次点击已选分类取消选择为 `待梳理` 直接保存金额，静默识账遇低置信度未知账单亦自动落入 `待梳理`）；提供 **「✨ Agent 梳理」四入口交互闭环**（账本首页顶部待梳理提示胶囊、记账计算器顶部、分类管理顶栏、智能记账分类规则区），结合账单截图 (`screenshotPath`)、原始文本 (`rawCapturedText`)、交易时段与金额，一键完成 **待梳理账单批量重分类、建议新增细分一二级分类（自动匹配 81 枚 `.webp` 图标）以及 `When -> Then` 条件规则与商户记忆自动提炼**；内置 **商户记忆自学习引擎 (`MerchantMemoryManager`)**、**带排除词一票否决权重的离线分类器 (`WeightedCategoryClassifier`)** 以及 **可视化条件规则引擎 (`SmartRuleEngine`)**。
3. **1:1 iCost 级视觉、模块侧滑扩展功能与丝滑弹簧动效体系**：
   - **「墨册朱签」自适应品牌图标、原生字体、弹簧按压与模块侧滑扩展容器**（[`ic_launcher_foreground.xml`](app/src/main/res/drawable/ic_launcher_foreground.xml)、[`ic_launcher_monochrome.xml`](app/src/main/res/drawable/ic_launcher_monochrome.xml)、[`ICostTheme.kt`](app/src/main/java/com/moji/ai/ui/theme/ICostTheme.kt)）：配备宣白暖石基底 + 玄墨精装账册 + 朱砂燕尾书签全套 Adaptive 矢量图标、Android 13+ Material You 莫奈单色主题图标与 24dp 状态栏通知矢量；内置 `ICDIN-Bold` 凝缩数字字体与 200 枚线性分类/银行品牌圆形图标，全端搭载 `iCostPressScale` 弹簧按压回弹 + 轻触觉震动反馈（`LocalHapticFeedback`）、`AnimatedMoneyText` 数字滚动过渡，以及 `ICostSwipeActionRow` 双向阻尼侧滑扩展功能容器与 `iCostHorizontalSwipeTrigger` 手势切换修饰符。
   - **账本首页与单笔分拆账单**（[`LedgerHomeTab.kt`](app/src/main/java/com/moji/ai/ui/screens/LedgerHomeTab.kt)、[`TransactionDetailAndCalendarSheets.kt`](app/src/main/java/com/moji/ai/ui/components/TransactionDetailAndCalendarSheets.kt)）：多账本切换、标题栏右滑/点击展开 **快捷记账模板抽屉**（点击模板一键秒记）、关键词实时搜索、`HorizontalPager` 三页左右滑动月度概览卡片、年月栏左右滑动切月、全屏收支日历页、按日弹簧收折卡片组、**单笔账单行右滑/左滑呼出「编辑 / 复制 / 报销 / 退款 / 删除」扩展胶囊**，以及账单详情页 **「单笔分拆为多个分类 (Split Transaction)」**。
   - **记账计算器面板、全屏分类管理、全链路标签管理、全能记账模板管理、多币种汇率管理、应用隐私密码保护与多时段定时记账提醒**（[`ICostCalculatorSheet.kt`](app/src/main/java/com/moji/ai/ui/components/ICostCalculatorSheet.kt)、[`CategoryManagementScreen.kt`](app/src/main/java/com/moji/ai/ui/screens/CategoryManagementScreen.kt)、[`CategoryCatalog.kt`](app/src/main/java/com/moji/ai/data/model/CategoryCatalog.kt)、[`TagManagementScreen.kt`](app/src/main/java/com/moji/ai/ui/screens/TagManagementScreen.kt)、[`TagCatalog.kt`](app/src/main/java/com/moji/ai/data/model/TagCatalog.kt)、[`TemplateManagementScreen.kt`](app/src/main/java/com/moji/ai/ui/screens/TemplateManagementScreen.kt)、[`CurrencyManagementScreen.kt`](app/src/main/java/com/moji/ai/ui/screens/CurrencyManagementScreen.kt)、[`CurrencyRateManager.kt`](app/src/main/java/com/moji/ai/data/model/CurrencyRateManager.kt)、[`PasswordProtectionScreen.kt`](app/src/main/java/com/moji/ai/ui/screens/PasswordProtectionScreen.kt)、[`AppLockOverlayScreen.kt`](app/src/main/java/com/moji/ai/ui/screens/AppLockOverlayScreen.kt)、[`AppLockManager.kt`](app/src/main/java/com/moji/ai/data/model/AppLockManager.kt)、[`ReminderManagementScreen.kt`](app/src/main/java/com/moji/ai/ui/screens/ReminderManagementScreen.kt)、[`ReminderConfigManager.kt`](app/src/main/java/com/moji/ai/data/model/ReminderConfigManager.kt)、[`DailyReminderScheduler.kt`](app/src/main/java/com/moji/ai/notification/DailyReminderScheduler.kt)）：支出/收入/转账三态手势滑动与点击平滑过渡、5 列线性分类网格（选中弹簧放大 + 二级子分类盒平滑展开 + 末尾「管理」与「+ 新建」快捷入口）、转账账户 `180°` 旋转互换动效、支持 `+ - × ÷` 连续运算的 `4×4` 触觉圆角键盘；支持「设置 › 更多功能 › 分类管理」与记账计算器双入口管理支出/收入二级分类树（新增/编辑/上下移动排序/恢复默认）、内置 81 枚线性分类图标分组选择器，以及分类重命名/删除时跨历史账单、周期规则、快捷模板、分期计划、条件规则与商户记忆库的 6 处原子级联迁移；支持 **「设置 › 更多功能 › 标签管理」与记账计算器多标签快捷选择抽屉双入口** 管理 10 色莫奈彩色标签库与 4 大场景分组（日常 / 差旅报销 / 生活事件 / 自定义），支持标签新增、编辑、上下排序、合并、安全删除保护及跨历史账单、快捷模板与条件规则的精确 Token 级联迁移、点击标签卡片展开关联账单下钻明细、统计页「标签排行统计」与 `.mojibackup` 备份同步；支持 **「设置 › 更多功能 › 记账模板」、首页顶部快捷模板抽屉与记账计算器「模板」胶囊三入口** 管理支出/收入/转账三态模板与 `0` 元「待填金额」动态模板，支持一键秒记（含 8 秒撤销与信用卡还款自动识别）、填入计算器预填草稿、8 个常用预设模板按需一键导入、复制/上下移动排序；支持 **「设置 › 更多功能 › 货币和汇率」、记账计算器币种弹窗「汇率设置」与资产页新建/编辑外币账户三入口** 管理 12 种常见货币（`CNY / USD / HKD / EUR / JPY / GBP / KRW / TWD / SGD / AUD / CAD / THB`），内置实时基准金额汇率换算预览器、单币种/全量自定义汇率编辑与恢复默认、跨净资产/总资产/总负债/统计趋势与新记账单的响应式生效汇率联动（严格保持历史已入账流水汇率快照不变），以及 `.mojibackup` 全量快照与 WebDAV 云同步；支持 **「设置 › 更多功能 › 密码保护」** 全屏隐私锁管理，采用 4 位 PIN 码 `SHA-256 + 随机 Salt` 安全摘要存储、两步式密码设置与原密码验证修改/关闭、连续输错 5 次触发 30 秒防暴力破解冷却锁、密码提示语防泄露校验（严禁包含当前 PIN 本身）、三档前后台自动锁定（`立即锁定 / 离开 1 分钟后 / 离开 5 分钟后`）、一键「立即锁定测试」全屏数字键盘遮罩层（拦截系统返回键）及多任务模糊防截屏（`FLAG_SECURE`）开关；支持 **「设置 › 更多功能 › 定时提醒」** 对标 iCost 的多时段记账提醒管理，支持无需特殊精确闹钟权限的安全 `AlarmManager` 调度、自定义多时段（快捷时间芯片 + 周一至周日任意重复周期 + 自定义标题与内容）、「今日已记账则免打扰」智能静默跳过、开机/时间变更自动重注册、点击提醒通知 Deep-Link 直达首页记账计算器面板、一键「立即发送测试提醒」验证及 `.mojibackup` 备份恢复同步。
   - **全能资产管理与余额锚点平账**（[`AssetsTab.kt`](app/src/main/java/com/moji/ai/ui/screens/AssetsTab.kt)）：净资产双页可滑动大卡（第 1 页净资产/总资产/总负债，右滑/左滑切换第 2 页 **存钱计划进度与信用卡分期管理概览**）、总借入/总借出双卡、**单个信用/资金账户行右滑/左滑呼出「还款(转账) / 记一笔 / 调平 / 删除」扩展胶囊**、以及账户详情页 **「校准真实余额（自动计算差额并生成 `BALANCE_ANCHOR` 平账流水）」**。
   - **多维统计、批量报销结算、iCost/微信/支付宝导入导出、WebDAV 云同步与私有仓库 OTA 自动更新**（[`StatisticsTab.kt`](app/src/main/java/com/moji/ai/ui/screens/StatisticsTab.kt)、[`BackupAndCsvTransferEngine.kt`](app/src/main/java/com/moji/ai/data/backup/BackupAndCsvTransferEngine.kt)、[`WebDavSyncClient.kt`](app/src/main/java/com/moji/ai/data/backup/WebDavSyncClient.kt)、[`AppUpdateManager.kt`](app/src/main/java/com/moji/ai/update/AppUpdateManager.kt)）：周/月/年/全部四段切换（按当前账本统计，净资产趋势由真实账单倒推）、**批量报销核销中心 (`batchSettleReimbursements`)**、**微信支付官方 CSV / 支付宝官方 CSV / 钱迹 / iCost CSV 与 `.xlsx` 自动识别预览、自动建账本/建资产账户并防重导入 (`importExternalBatch`)**、带 `BackupSchemaGuard` 兼容守卫的 `.mojibackup` 全量 JSON 快照备份恢复、支持坚果云 / Nextcloud / 群晖的 **WebDAV 云备份上传与恢复**，以及支持 **GitHub 私有仓库（Fine-grained PAT + `Accept: application/octet-stream` Release Asset API）与公开仓库的应用内 OTA 自动检查更新与一键覆盖安装**。
4. **纯后台静默入账 + 通知栏三键快捷操作 + 国产 ROM 保活向导**：
   - [`SilentBookkeepingNotifier`](app/src/main/java/com/moji/ai/notification/SilentBookkeepingNotifier.kt) & [`RomKeepAliveGuideHelper`](app/src/main/java/com/moji/ai/service/RomKeepAliveGuideHelper.kt)：后台自动入账后推送系统通知，通知栏提供 **「撤销入账」「切换分类（联动写入商户记忆库）」「标为待报销」** 三大快捷按钮；内置针对 ColorOS / HyperOS / OriginOS / HarmonyOS 的 **后台保活与 Android 13+ 受限设置一键解除诊断向导**。

## 构建与安装

### 常规 Debug 包（`com.moji.ai`）

```bash
./gradlew assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

### QA 独立测试包（`com.moji.ai.qa`，与正式包共存）

用于在已安装正式版 `com.moji.ai` 的手机上安全执行真机测试，拥有独立的青蓝 QA 图标、独立数据库与 `FileProvider`、独立通知渠道、独立 WebDAV 默认路径（`/dav/MoJi-QA/moji_qa_latest.mojibackup`），并默认禁用正式版 OTA 更新：

```bash
./gradlew assembleQa
adb install -r -g app/build/outputs/apk/qa/app-qa.apk
```

## 发布正式版（公开下载与应用内更新）

仓库公开时，每次发布 GitHub Release 并附上 `moji.apk`，就同时提供了公开下载链接和应用内 OTA 更新（无需 Token）：

- 最新版直链：`https://github.com/Rowan-rh/moji-ai/releases/latest/download/moji.apk`
- 发布页：`https://github.com/Rowan-rh/moji-ai/releases`

**一次性准备：正式签名证书**。所有版本必须用同一张证书签名，证书丢失后老用户无法覆盖升级，请离线备份证书与密码：

```bash
keytool -genkey -v -keystore ~/keys/moji-release.jks -alias moji -keyalg RSA -keysize 2048 -validity 36500
```

在 `local.properties`（不入库）中配置，也可用同名环境变量：

```properties
MOJI_RELEASE_STORE_FILE=/Users/你/keys/moji-release.jks
MOJI_RELEASE_STORE_PASSWORD=******
MOJI_RELEASE_KEY_ALIAS=moji
MOJI_RELEASE_KEY_PASSWORD=******
# 可选：首次 dry-run 输出的证书指纹，之后发布会校验，防止误用其他证书
MOJI_RELEASE_CERT_SHA256=
```

`local.properties` 中不要配置 `GITHUB_UPDATE_TOKEN`：它会编译进 APK，公开分发时任何人都能提取。

**每次发布**：

1. 在功能 PR 中把 `app/build.gradle.kts` 的 `versionCode` 加 1，并提升 `versionName`（Tag 即 `v<versionName>`，两者须一致，否则应用会反复提示更新）。
2. `develop` → `main` 提 `[release]` 发布 PR 并以 merge commit 合并。
3. 在与 `origin/main` 一致的干净 `main` 上运行：

```bash
scripts/release.sh --dry-run   # 可在任意分支先验证构建与签名
scripts/release.sh             # 校验版本 → 构建正式签名包 → 打 Tag → 创建 Release 并上传 moji.apk
```

## 文档导航

- **产品需求与技术规格**：[`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md)
- **现有功能与架构全景梳理**：[`docs/CURRENT_FEATURES.md`](docs/CURRENT_FEATURES.md)
- **42 个开源项目深度对标与可借鉴特性演进蓝图**：[`docs/OPEN_SOURCE_BENCHMARK_AND_ROADMAP.md`](docs/OPEN_SOURCE_BENCHMARK_AND_ROADMAP.md)

