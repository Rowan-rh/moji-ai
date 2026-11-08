# 默记 (MoJi) — 现有功能与技术架构全景梳理

> 基于 `develop` 分支最新代码梳理，完整呈现默记当前已落地的功能闭环、代码模块映射与现有实现边界，作为后续迭代的基线文档。

---

## 一、 系统架构总览

```text
┌────────────────────────────────────────────────────────────────────────────┐
│                           系统级无感采集层 (service/)                        │
│  ┌──────────────────────────────────┐  ┌─────────────────────────────────┐ │
│  │ PaymentNotificationListenerService│  │ PaymentAccessibilityService     │ │
│  │ 微信/支付宝/云闪付/银行/短信通知   │  │ 支付成功/转账/还款/退款节点树抓取│ │
│  └─────────────────┬────────────────┘  └────────────────┬────────────────┘ │
│                    └─────────────────┬──────────────────┘                  │
│                                      ▼                                     │
│                    ┌───────────────────────────────────┐                   │
│                    │ CapturePipelineCoordinator        │                   │
│                    │ 1.1s 滑动窗口聚合 + 双层幂等防重   │                   │
│                    └─────────────────┬─────────────────┘                   │
└──────────────────────────────────────┼─────────────────────────────────────┘
                                       ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                           AI 识账与语义解析层 (ai/)                          │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │ AiAccountingEngine                                                    │ │
│  │ • 动态上下文注入：当前钱包列表（尾号/别名/币种）+ 最近15笔可冲抵消费   │ │
│  │ • 云端协议：OpenAI 兼容接口 (DeepSeek/千问/硅基/Ollama) + Gemini 官方  │ │
│  │ • 离线兜底：localSmartFallbackParse（消费/转账/还款/退款/AA/外币）     │ │
│  └───────────────────────────────────┬───────────────────────────────────┘ │
└──────────────────────────────────────┼─────────────────────────────────────┘
                                       ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                        原子化账务引擎与持久化层 (data/)                      │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │ LedgerRepository + Room (AppDatabase v3)                              │ │
│  │ • 7 大交易形态联动：支出/收入/转账/信用还款/退款冲销/借入/借出         │ │
│  │ • 报销状态机 (reimbursable -> reimbursed) + 优惠/手续费核算            │ │
│  │ • 通知栏广播一键原子撤销 (SilentBookkeepingNotifier + UndoReceiver)    │ │
│  └───────────────────────────────────┬───────────────────────────────────┘ │
└──────────────────────────────────────┼─────────────────────────────────────┘
                                       ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                     1:1 iCost 风格 Jetpack Compose UI 层 (ui/)              │
│  ┌────────────────┐ ┌────────────────┐ ┌────────────────┐ ┌──────────────┐ │
│  │ 账本首页        │ │ 资产管理        │ │ 多维统计        │ │ 记账计算器    │ │
│  │ LedgerHomeTab  │ │ AssetsTab      │ │ StatisticsTab  │ │ Calculator   │ │
│  └────────────────┘ └────────────────┘ └────────────────┘ └──────────────┘ │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## 二、 核心已实现功能清单

### 1. 免 Root 双通道无感采集与智能防重 (`service/`)

| 子模块 | 源码位置 | 已实现能力 | 当前边界 / 待增强点 |
| :--- | :--- | :--- | :--- |
| **通知栏监听** | [PaymentNotificationListenerService.kt](../app/src/main/java/com/moji/ai/service/PaymentNotificationListenerService.kt) | 实时捕获状态栏通知的 `EXTRA_TITLE`、`EXTRA_TEXT` 与 `EXTRA_BIG_TEXT`，优先提取长文本，过滤系统与自身包名。 | 暂未记录“被过滤/未触发原因”的调试日志队列，用户在通知未入账时无法在 App 内直接排查原因。 |
| **无障碍页面节点抓取** | [PaymentAccessibilityService.kt](../app/src/main/java/com/moji/ai/service/PaymentAccessibilityService.kt) | 针对微信、支付宝、云闪付、美团、拼多多、京东、招行/建行/工行及短信，在检测到“支付成功/付款成功/转账成功/还款成功/退款成功/账单详情”等触发词时，递归提取深度 `<=18`、最多 `45` 个可见文本节点（无需截屏 OCR）。 | 部分小程序或自绘 Canvas/WebView 页面无文本节点时无法提取；缺少摇一摇/截图补录通道。 |
| **滑动时间窗合并、30s 跨通道证据融合与可观测日志** | [CapturePipelineCoordinator.kt](../app/src/main/java/com/moji/ai/service/CapturePipelineCoordinator.kt) | 1. **首层精确去重**：20 秒内相同 `packageName + textHash` 直接丢弃并记录日志；<br>2. **1.1 秒滑动聚合窗口**：将同一笔交易先后到达的“页面商户文本”与“银行卡扣款通知”合并为 `AI_MERGED` 上下文一次性送入 AI；<br>3. **30 秒跨通道事后证据融合 (`AI_FUSED`)**：若银行通知延迟 3~30 秒到达，自动以卡号尾号或更具体的商户名增量补全已入账流水并原子化调整新旧钱包余额；<br>4. **采集可观测队列 (`observabilityLogs`)**：保留最近 50 条采集事件的命中状态（`RECORDED` / `FUSED_PATCH` / `DEDUP_BLOCKED` / `NOISE_IGNORED`）与一键重放。 | 后续可扩展相册/系统分享截图 OCR 补录通道。 |

---

### 2. 多协议 AI 识账引擎、商户记忆自学习与带排除词离线分类 (`ai/`)

| 子模块 | 源码位置 | 已实现能力 | 当前边界 / 待增强点 |
| :--- | :--- | :--- | :--- |
| **多模型配置管理** | [AiConfigManager.kt](../app/src/main/java/com/moji/ai/ai/AiConfigManager.kt) | 支持配置 `OPENAI_COMPATIBLE`（自定义 `baseUrl`、`apiKey`、`modelName`）与 `GEMINI_OFFICIAL` 双协议，支持开关通知采集、无障碍采集与本地启发式兜底。 | 暂不支持端侧离线 `.litertlm` / `.gguf` 小模型推理。 |
| **上下文感知 Prompt 与商户记忆自学习** | [AiAccountingEngine.kt](../app/src/main/java/com/moji/ai/ai/AiAccountingEngine.kt), [MerchantMemoryAndWeightedClassifier.kt](../app/src/main/java/com/moji/ai/ai/MerchantMemoryAndWeightedClassifier.kt) | • **商户记忆自学习 (`MerchantMemoryManager`)**：用户每次保存或修改账单分类时，自动记住商户关键词、分类与偏好账户；<br>• **动态上下文注入**：每次调用动态注入用户全部钱包、最近 15 笔可冲销消费账单及高频商户分类记忆。 | 后续可扩展可视化 `When -> Then` 复合条件规则面板。 |
| **带排除词权重的离线语义引擎** | [MerchantMemoryAndWeightedClassifier.kt](../app/src/main/java/com/moji/ai/ai/MerchantMemoryAndWeightedClassifier.kt) (`WeightedCategoryClassifier`) | 断网或未填 Key 时自动降级，支持：<br>• 优先命中用户自学习商户记忆（置信度 `0.97`）；<br>• `强信号词(+10) + 品牌词(+8) + 品类词(+6) + 排除词(-999 一票否决)` 加权分类（如“美团外卖”自动排除购物/旅游归入“餐饮-三餐”）；<br>• 自动识别 **信用还款 (`REPAYMENT`)**、**内部提现/充值转账 (`TRANSFER`)**、**退款/AA 冲销 (`REFUND_OFFSET`)**、**日常收入 (`INCOME`)**。 | 后续可支持导入导出自定义关键词权重表。 |

---

### 3. 全能账务引擎、余额锚点平账与备份迁移 (`data/`)

| 子模块 | 源码位置 | 已实现能力 | 当前边界 / 待增强点 |
| :--- | :--- | :--- | :--- |
| **4 大账户体系与真实余额锚点平账** | [Entities.kt](../app/src/main/java/com/moji/ai/data/local/Entities.kt), [LedgerRepository.kt](../app/src/main/java/com/moji/ai/data/repository/LedgerRepository.kt) (`reconcileWalletBalance`) | • 支持 **储蓄卡 (`DEBIT_BANK`)**、**数字钱包 (`DIGITAL_WALLET`)**、**信用账户 (`CREDIT`)**、**现金/其他 (`CASH_OTHER`)**；<br>• 支持 **「校准真实余额（平账锚点）」**：输入账户当前真实余额或待还欠款后，自动计算差额并生成 `BALANCE_ANCHOR` 平账补差流水（或仅调整期初余额）。 | 暂不支持跨日外币实时浮动汇率自动拉取。 |
| **全量快照备份与微信/支付宝/钱迹 CSV 导入导出** | [BackupAndCsvTransferEngine.kt](../app/src/main/java/com/moji/ai/data/backup/BackupAndCsvTransferEngine.kt) | • **全量快照 (`.mojibackup`)**：带 `BackupSchemaGuard` 全字段默认值保护，一键备份与恢复钱包、账单、周期规则、商户记忆库、多账本与预算；<br>• **多格式 CSV 自动识别导入**：自动识别 **微信支付官方账单 CSV、支付宝官方账单 CSV、钱迹/iCost CSV**，支持导入前预览与重复流水指纹防重跳过；<br>• **标准 CSV 导出**：一键生成兼容 iCost/钱迹/Excel 的 UTF-8 CSV。 | 暂未接入 WebDAV 远端定时自动上传。 |
| **7 大交易形态、单笔分拆与强一致事务** | [LedgerRepository.kt](../app/src/main/java/com/moji/ai/data/repository/LedgerRepository.kt) | 全程基于 Room `db.withTransaction` 原子化更新账单与钱包余额：<br>1. `EXPENSE`（支出，含优惠 `discountCents` 与手续费 `feeCents`，支持 **`splitExpenseTransaction` 单笔分拆为多分类子账单**）<br>2. `INCOME`（收入）<br>3. `TRANSFER`（内部转账，双向联动资产，不计入收支）<br>4. `REPAYMENT`（信用还款，扣减资产并恢复信用额度）<br>5. `REFUND_OFFSET`（全额/部分退款或 AA 冲销，恢复收款钱包余额并扣减原消费净支出 `refundedAmountCents`）<br>6. `LOAN_IN`（借入）<br>7. `LOAN_OUT`（借出） | 支持两段式连续拆分，后续可扩展一次拆分 3+ 子项表单。 |
| **报销闭环与批量报销核销** | [LedgerRepository.kt](../app/src/main/java/com/moji/ai/data/repository/LedgerRepository.kt) (`settleReimbursement`, `batchSettleReimbursements`) | 支持消费标记为 `reimbursable`（待报销），支持单笔报销入账以及在「批量报销核销中心」多选多笔待报销账单 **一键批量核销入账** 到指定储蓄卡/数字钱包。 | 完整闭环。 |
| **周期性固定账单** | [Entities.kt](../app/src/main/java/com/moji/ai/data/local/Entities.kt) (`RecurringRuleEntity`) | 支持按天/周/月/年配置周期性收支或转账规则，到期自动生成 `RECURRING` 账单并推进下次触发时间。 | 缺少基于历史流水自动发现潜在订阅（如每月固定扣款）的智能提醒。 |
| **通知栏三键快捷操作与保活向导** | [SilentBookkeepingNotifier.kt](../app/src/main/java/com/moji/ai/notification/SilentBookkeepingNotifier.kt), [RomKeepAliveGuideHelper.kt](../app/src/main/java/com/moji/ai/service/RomKeepAliveGuideHelper.kt) | 后台静默入账后发送系统通知，提供 **「撤销入账」「切换分类（联动写入商户记忆）」「标为待报销」** 三大快捷按钮；内置针对 ColorOS / HyperOS / OriginOS / HarmonyOS 的后台保活与 Android 13+ 受限设置一键解除诊断向导。 | 完整闭环。 |
| **可视化条件规则引擎与 WebDAV 云同步** | [SmartRuleEngine.kt](../app/src/main/java/com/moji/ai/ai/SmartRuleEngine.kt), [WebDavSyncClient.kt](../app/src/main/java/com/moji/ai/data/backup/WebDavSyncClient.kt) | • **`SmartRuleEngine (When -> Then)`**：按关键词、金额范围、小时区间组合自动覆盖分类/账本/标签、标为待报销或静默拦截；<br>• **`WebDavSyncClient`**：支持坚果云 / Nextcloud / 群晖 WebDAV 一键上传与恢复 `.mojibackup` 全量快照。 | 后续可扩展后台 WorkManager 定时自动静默同步。 |

---

### 4. 1:1 对标 iCost 的 Jetpack Compose 视觉与交互体系 (`ui/`)

| 页面 / 组件 | 源码位置 | 已实现能力 |
| :--- | :--- | :--- |
| **视觉主题与图标字体** | [ICostTheme.kt](../app/src/main/java/com/moji/ai/ui/theme/ICostTheme.kt), [CategoryCatalog.kt](../app/src/main/java/com/moji/ai/data/model/CategoryCatalog.kt) | 内置 `ICDIN` 凝缩数字字体 + 200 枚线性分类与银行/支付品牌图标（微信、支付宝、花呗、白条、招行、工行、建行、云闪付、Visa 等），1:1 还原 iCost 圆角卡片与配色体系。 |
| **账本首页** | [LedgerHomeTab.kt](../app/src/main/java/com/moji/ai/ui/screens/LedgerHomeTab.kt) | 多账本切换、关键词实时搜索、三页可滑动月度概览卡片（收支结余 / 月度预算进度 / AI 自动识账统计）、收支日历入口、按日可折叠流水卡片组、退款划线冲销标签展示。 |
| **记账计算器面板** | [ICostCalculatorSheet.kt](../app/src/main/java/com/moji/ai/ui/components/ICostCalculatorSheet.kt) | 支出/收入/转账/还款四模式切换、一级分类与二级子分类自动展开网格、账户/报销/优惠/手续费/标签快捷胶囊、支持 `+ - × ÷` 四则实时运算的 `4×4` 键盘。 |
| **账单详情与收支日历** | [TransactionDetailAndCalendarSheets.kt](../app/src/main/java/com/moji/ai/ui/components/TransactionDetailAndCalendarSheets.kt) | 全屏账单详情页（支持直接发起全额/部分退款、完成报销入账、编辑、删除、查看 AI 捕获原文）、月度收支日历热力与按日明细下钻。 |
| **全能资产管理页** | [AssetsTab.kt](../app/src/main/java/com/moji/ai/ui/screens/AssetsTab.kt) | 净资产一键隐私显隐 (`👁️`)、总资产/总负债/总借入/总借出联动卡片、可折叠信用账户（显示账单日/还款日倒计时与可用额度进度）与资金账户列表、单账户独立流水详情与一键还款/转账。 |
| **多维统计页** | [StatisticsTab.kt](../app/src/main/java/com/moji/ai/ui/screens/StatisticsTab.kt) | 周/月/年/全部维度切换，收支柱状对比图、净资产趋势图、支出分类环形图与占比排行、报销统计（待报销/已报销）与流转统计（优惠/手续费/转账）宫格卡片。 |
| **设置与智能记账中心** | [MoJiMainScreen.kt](../app/src/main/java/com/moji/ai/ui/MoJiMainScreen.kt) | 通知监听与无障碍权限状态实时检测与一键跳转授权、AI 接口参数配置、内置 **AI 识账沙盒测试台**（预设微信消费、银行信用卡通知、零钱提现、储蓄卡还花呗、AA 分摊、美元订阅等一键测试样本）、周期性固定账单管理。 |
