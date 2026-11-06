# 开源项目深度对标与默记 (MoJi) 演进蓝图

> 基于本地 `resources/` 目录下归档的 **6 大特性分类、42 个国内外高星开源记账与自动记账项目**，结合默记 (MoJi) 现有架构，提炼出最具实战价值的 **10 大可借鉴特性与分阶段落地方案**。

---

## 一、 核心对标矩阵：默记 vs 行业代表性开源项目

| 维度 | 默记 (MoJi) 当前状态 | 行业标杆开源项目 | 核心差距与可吸收精华 |
| :--- | :--- | :--- | :--- |
| **无感采集与防重** | 通知监听 + 无障碍节点树 + `1.1s` 滑动窗口文本拼接 | `blue-whale-keeper`<br>`huayoupu-ledger`<br>`AutoAccountingOrg` | 缺少 **自绘页面截图 OCR 通道**、**摇一摇/分享截图补录**、**跨时间窗事后证据融合** 以及 **未入账原因可观测面板** |
| **AI 与离线识别** | 云端 OpenAI/Gemini + 本地正则与硬编码词表兜底 | `photo-ledger`<br>`pennywiseai-tracker`<br>`blue-whale-keeper`<br>`double-entry-generator` | 缺少 **商户记忆自学习**、**带权重/排除词的可配置分类词库**、**可视化条件规则引擎 (`When -> Then`)** 及 **端侧小模型 (`Qwen3-0.6B`) 约束解码** |
| **资产对账与核销** | 手动增删改账户余额，全事务联动 7 类交易 | `blue-whale-keeper`<br>`moneywallet`<br>`MyExpenses` | 缺少 **「余额锚点登记 + 自动计算差额平账」**、**单笔账单多分类拆分 (Split Transaction)** |
| **数据备份与迁移** | 纯本地 Room 数据库，尚无导入导出与云同步 | `huayoupu-ledger`<br>`Cashbook`<br>`BeeCount`<br>`pennywiseai-tracker` | 急需补充 **钱迹/微信/支付宝 CSV 导入导出（含预览去重）**、**ZIP 全量备份恢复（含 Schema 兼容守卫）** 与 **WebDAV 自托管同步** |
| **多币种与统计深度** | 6 币种静态参考汇率折算 CNY，基础柱状图/环形图 | `pennywiseai-tracker`<br>`ezbookkeeping`<br>`Cashew`<br>`Monekin` | 可借鉴 **严禁跨币种裸加 (`sumByCurrency`) 的强类型货币安全约束**、**消费热力图**、**订阅自动发现告警** 与 **端侧 AI 查账问答** |
| **国产系统保活引导** | 基础的通知与无障碍权限跳转按钮 | `huayoupu-ledger`<br>`AutoAccountingOrg` | 急需借鉴 **Android 13+「受限设置」解除指引、电池无限制、自启动、最近任务加锁** 一站式分步诊断向导 |

---

## 二、 十大高价值可借鉴特性详解（含参考源码路径与落地方案）

### 特性 1：多通道证据融合 + 采集可观测面板 + 摇一摇/截图补录（P0）

- **参考项目与源码**：
  - [resources/01-auto-capture-accessibility/blue-whale-keeper](../resources/01-auto-capture-accessibility/blue-whale-keeper/README.md)（四通道采集：通知 + 无障碍 + 截图 OCR + 银行短信专项；原始证据与未入账原因全透明可查）
  - [resources/01-auto-capture-accessibility/huayoupu-ledger](../resources/01-auto-capture-accessibility/huayoupu-ledger/README.md)（摇一摇补录、相册/系统分享截图导入、PP-OCRv6 Tiny 5.95MB 极速离线识别）
- **为什么值得借鉴**：
  1. **解决“通知只有金额、无障碍延迟或自绘无节点”痛点**：微信/银行通知往往只有 `¥32.00` 没有商户名，或者银行短信比支付页面晚到 3~10 秒（超出默记当前的 `1.1s` 滑动窗口）。借鉴 `blue-whale-keeper` 的 **证据融合（Evidence Fusion）** 机制：先入账的记录如果在 30 秒内收到同金额的银行尾号通知或页面商户补充，自动 **增量补全（Patch）已有账单的 `merchant` 或 `sourceWalletId`**，而不是简单丢弃或重复入账。
  2. **解决“为什么这笔没自动记上”的黑盒焦虑**：在「智能记账中心」增加 **「最近采集日志 / 证据流」** 列表，展示最近 50 条捕获到的原始文本、命中状态（已入账 / 幂等拦截 / 非金融噪音忽略 / AI 解析失败）及一键重放按钮。
  3. **解决漏记补录摩擦力**：支持从微信/支付宝把账单截图通过系统 `Intent.ACTION_SEND` 分享给默记，或打开账单详情页“摇一摇”触发快速补录。

---

### 特性 2：「规则快路径 + 商户记忆自学习 + 带权重词库」混合识账架构（P0）

- **参考项目与源码**：
  - [resources/02-ai-llm-ocr-accounting/photo-ledger](../resources/02-ai-llm-ocr-accounting/photo-ledger/README.md)（规则快路径 -> 端侧小模型约束解码；用户确认/修改过的商户自动记住分类）
  - [resources/01-auto-capture-accessibility/blue-whale-keeper](../resources/01-auto-capture-accessibility/blue-whale-keeper/README.md)（带权重的关键词库：强信号词 `+10`、品牌词 `+8`、品类词 `+6`、**排除词一票否决**，如“外卖”阻止“美团”被归入购物）
  - [resources/05-bill-rules-sync-import/double-entry-generator](../resources/05-bill-rules-sync-import/double-entry-generator/README.md) & [AutoRuleSubmit](../resources/05-bill-rules-sync-import/AutoRuleSubmit/README.md)（国内微信/支付宝/美团/京东/各大银行真实通知样本与分类规则）
- **为什么值得借鉴**：
  1. **商户记忆表 (`MerchantMemoryEntity`)**：当用户手动将某笔“瑞幸咖啡”或“唐久便利”的分类从“其他”改为“餐饮-咖啡茶饮”或“购物-日用百货”时，自动写入本地 `merchant_memory` 表。下次无论云端 AI 还是离线引擎遇到该商户，直接 `100%` 命中用户习惯分类，越用越聪明。
  2. **规则快路径（毫秒级免调大模型）**：对于格式极其固定的高频通知（如已知尾号银行卡 + 已记忆商户 + 明确金额），本地快路径在 `<5ms` 内直接完成解析，既省 Token 又避免网络超时；仅在遇到陌生商户或复杂退款/转账文本时才调用云端 LLM。
  3. **带排除词权重的离线分类器**：升级 `AiAccountingEngine.localSmartFallbackParse()`，引入 `正权重 + 排除词一票否决` 评分模型，并用 `AutoRuleSubmit` 与 `double-entry-generator` 的真实国内样本扩充内置词库。

---

### 特性 3：可视化条件规则引擎 `Smart Rules Engine (When -> Then)`（P1）

- **参考项目与源码**：
  - [resources/02-ai-llm-ocr-accounting/pennywiseai-tracker](../resources/02-ai-llm-ocr-accounting/pennywiseai-tracker/README.md)（Smart Rules Engine：支持按 `AMOUNT`、`MERCHANT`、`ACCOUNT (Bank||Last4)`、`TIME/HOUR`、`DAY_OF_MONTH` 组合条件自动修改分类、账本、标签或静默拦截）
- **为什么值得借鉴**：
  - 每个人的记账习惯高度个性化，例如：
    - *“工作日早上 07:00~09:30 金额 `< 20` 元的支出 -> 自动归类为「餐饮-早餐」”*
    - *“使用公司公务卡（招行尾号 `8848`）的所有消费 -> 自动打上 `待报销 (reimbursable = true)` 并归入「商务账本」”*
    - *“包含『余额宝收益』的通知 -> 自动设为「日常收入 - 理财收益」”*
  - 在默记中引入轻量化 `AccountingRuleEntity`，在 AI/离线解析产出结果后执行一次用户自定义规则管道覆写，兼顾 AI 的泛化能力与规则的 `100%` 确定性。

---

### 特性 4：「余额锚点登记 + 差额自动平账」与净资产历史曲线（P0）

- **参考项目与源码**：
  - [resources/01-auto-capture-accessibility/blue-whale-keeper](../resources/01-auto-capture-accessibility/blue-whale-keeper/README.md)（以登记的真实余额为锚点：`当前余额 = 上次登记余额 + 期间净收支`；修改余额不一致时弹窗询问差额类型并自动生成补差记录）
  - [resources/06-cross-platform-ux-benchmarks/Monekin](../resources/06-cross-platform-ux-benchmarks/Monekin/README.md) & [ezbookkeeping](../resources/06-cross-platform-ux-benchmarks/ezbookkeeping/README.md)（多粒度净资产与账户余额历史曲线）
- **为什么值得借鉴**：
  - 任何记账软件都难免出现用户漏记现金或某笔未触发通知的消费，导致 App 算出来的钱包余额和微信/银行卡真实余额差了几十块钱。
  - 传统做法是直接暴力改余额（导致月度收支报表和资产变动对不上），或者让用户痛苦地翻银行流水逐笔找漏记。
  - **落地方案**：在默记 `AssetsTab` 的账户详情页提供 **「校准真实余额（平账）」** 功能。当用户输入银行卡当前真实余额（如 `5,000.00` 元）而账面余额为 `5,120.00` 元时，自动提示：*“比账面少了 120.00 元，是否自动生成一笔 120.00 元的『平账支出/漏记补差』或仅直接调整期初余额？”*，一键把账平齐。

---

### 特性 5：微信/支付宝/钱迹 CSV 导入导出 + 全量备份守卫 + WebDAV 同步（P0）

- **参考项目与源码**：
  - [resources/01-auto-capture-accessibility/huayoupu-ledger](../resources/01-auto-capture-accessibility/huayoupu-ledger/README.md) & [resources/03-compose-ui-interaction/Cashbook](../resources/03-compose-ui-interaction/Cashbook/README.md)（CSV 导入预览去重、钱迹模板兼容、ZIP 备份恢复）
  - [resources/02-ai-llm-ocr-accounting/pennywiseai-tracker](../resources/02-ai-llm-ocr-accounting/pennywiseai-tracker/CLAUDE.md)（`BackupSchemaGuard`：所有备份序列化字段强制带默认值，保证旧版本备份文件永远可在新版本恢复）
  - [resources/06-cross-platform-ux-benchmarks/BeeCount](../resources/06-cross-platform-ux-benchmarks/BeeCount/README.md) & [resources/02-ai-llm-ocr-accounting/inkqilin-ledger](../resources/02-ai-llm-ocr-accounting/inkqilin-ledger/README.md)（WebDAV / S3 自托管加密云备份）
- **为什么值得借鉴**：
  - 作为 Local-First（本地优先）应用，用户最担心“换手机丢数据”以及“无法从钱迹/iCost/微信支付宝官方导出账单迁移过来”。
  - **落地方案**：
    1. **JSON/ZIP 全量备份与恢复**：一键导出包含 `wallets`、`transactions`、`recurring_rules`、`ai_config` 的 `.mojibackup` 文件，并遵循 `PennyWise` 的默认值兼容规范。
    2. **CSV 导入导出**：支持导出标准 CSV（兼容 iCost / 钱迹格式），并支持直接导入 **微信支付官方账单 CSV、支付宝官方账单 CSV、钱迹/iCost CSV**（导入时复用 `double-entry-generator` 的字段映射逻辑，并提供预览与重复流水过滤）。
    3. **WebDAV 自动备份**：支持坚果云 / Nextcloud / 群晖 WebDAV 定时静默备份。

---

### 特性 6：国产 ROM 后台保活与「Android 13+ 受限设置」一站式诊断向导（P1）

- **参考项目与源码**：
  - [resources/01-auto-capture-accessibility/huayoupu-ledger](../resources/01-auto-capture-accessibility/huayoupu-ledger/README.md)（系统受限设置引导、电池后台无限制、自启动检查、最近任务加锁图文引导）
  - [resources/01-auto-capture-accessibility/AutoAccountingOrg-AutoAccounting](../resources/01-auto-capture-accessibility/AutoAccountingOrg-AutoAccounting/README.md)
- **为什么值得借鉴**：
  - 在小米 HyperOS、华为 HarmonyOS、vivo OriginOS、OPPO ColorOS 上，侧载安装的 APK 开启「无障碍服务」时会遇到 Android 13+ 的 **“受限设置（Restricted Settings）”** 拦截，且系统杀后台会导致 `NotificationListenerService` 断连。
  - **落地方案**：在默记「设置与智能记账中心」升级 **「后台静默运行健康度诊断卡」**：
    1. 通知使用权状态 + 断连自动 `requestRebind` 重连机制；
    2. 无障碍服务状态 + Android 13+「应用信息 -> 右上角三点 -> 允许受限设置」图文指引；
    3. 电池优化忽略 (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) 一键申请；
    4. 针对小米/华为/vivo/OPPO 识别厂商 `Build.MANUFACTURER` 并给出对应的「后台自启动」与「最近任务下拉加锁」引导。

---

### 特性 7：单笔分拆账单 (Split Transaction) 与批量报销核销（P1）

- **参考项目与源码**：
  - [resources/04-multi-wallet-credit-recurring/MyExpenses](../resources/04-multi-wallet-credit-recurring/MyExpenses/README.md)（父子分拆账单 Split Transaction）
  - [resources/06-cross-platform-ux-benchmarks/Cashew](../resources/06-cross-platform-ux-benchmarks/Cashew/README.md)（借贷/报销分批核销进度）
- **为什么值得借鉴**：
  - 真实场景中，用户在山姆超市一笔刷卡 `500` 元，其中 `300` 元是「餐饮-食材」，`200` 元是「购物-日用百货」；或者出差回公司一次性报销 10 笔「待报销」账单。
  - **落地方案**：
    1. 在 `StatisticsTab` 的「报销统计」卡片点击后，支持 **多选待报销账单 -> 一键批量结算入账到指定银行卡**；
    2. 在账单详情页支持 **「拆分账单」**，将一笔总支出拆分为多条子分类记录，保持原扣款总额一致。

---

### 特性 8：多币种强类型防混加与汇率自动同步（P1）

- **参考项目与源码**：
  - [resources/02-ai-llm-ocr-accounting/pennywiseai-tracker](../resources/02-ai-llm-ocr-accounting/pennywiseai-tracker/CLAUDE.md)（Hard Constraint：严禁不同币种金额直接相加，统一通过 `sumByCurrency` 或显式折算本位币）
  - [resources/06-cross-platform-ux-benchmarks/ezbookkeeping](../resources/06-cross-platform-ux-benchmarks/ezbookkeeping/README.md)（多币种汇率管理）
- **为什么值得借鉴**：
  - 默记目前已支持 `amountCents`（原币种）与 `amountCnyCents`（折算人民币分），但外币钱包（如美元 Visa 卡、港币八达通）在 `AssetsTab` 汇总净资产时，需确保严格按钱包所属币种折算本位币后再计入净资产，并支持在设置中更新或联网同步最新参考汇率。

---

### 特性 9：桌面微件 (Glance Widgets)、快捷磁贴 (Quick Settings Tile) 与生物识别锁（P2）

- **参考项目与源码**：
  - [resources/03-compose-ui-interaction/buckwheat](../resources/03-compose-ui-interaction/buckwheat/README.md) & [DailyLife](../resources/03-compose-ui-interaction/DailyLife/README.md)（Jetpack Glance 桌面小组件：今日消费、月度剩余预算、一键呼出记账键盘）
  - [resources/02-ai-llm-ocr-accounting/pennywiseai-tracker](../resources/02-ai-llm-ocr-accounting/pennywiseai-tracker/README.md)（Biometric App Lock 指纹/面容解锁）
- **为什么值得借鉴**：
  - 在手机桌面放置一个 2×2 的 iCost 风格小组件，一眼看到「本月已花 / 预算剩余」并支持点 `+` 号直接拉起 `ICostCalculatorSheet` 记账面板；
  - 下拉系统控制中心提供「默记·快速记账 / 粘贴识账」快捷磁贴 (`TileService`)；
  - 支持开启指纹/面容应用锁，保护个人资产隐私。

---

### 特性 10：端侧小模型离线识账与 AI 财务洞察助手（P2）

- **参考项目与源码**：
  - [resources/02-ai-llm-ocr-accounting/photo-ledger](../resources/02-ai-llm-ocr-accounting/photo-ledger/README.md)（LiteRT-LM `Qwen3-0.6B` OpenCL GPU 加速 + JSON Schema 约束解码，单次离线推理仅 5~10 秒）
  - [resources/02-ai-llm-ocr-accounting/pennywiseai-tracker](../resources/02-ai-llm-ocr-accounting/pennywiseai-tracker/README.md)（On-Device AI Assistant 自然语言查账问答 + 潜在订阅扣款自动检测）
  - [resources/02-ai-llm-ocr-accounting/EasyAccounts](../resources/02-ai-llm-ocr-accounting/EasyAccounts/README.md)（AI 月度财务诊断报告）
- **为什么值得借鉴**：
  - 对于极致注重隐私、不愿配置云端 API Key 的用户，可提供可选下载的端侧 `Qwen3-0.6B.litertlm` 离线模型包；
  - 在统计页提供 **「AI 月度账单洞察」**（自动分析本月相比上月哪个分类超支最多、识别重复订阅扣款、给出节流建议）。

---

## 三、 建议迭代路线图 (Roadmap)

| 阶段 | 核心目标 | 包含特性 |
| :--- | :--- | :--- |
| **Phase 1：夯实自动记账闭环与数据安全（近期优先 P0，✅ 已落地）** | 记账更准、漏记可查、换机无忧 | 1. ✅ **商户记忆自学习 (`MerchantMemoryManager`) + 带排除词权重的离线分类器 (`WeightedCategoryClassifier`)**<br>2. ✅ **采集证据流可观测面板 (`observabilityLogs`) + 30秒跨通道增量合并 (`AI_FUSED`)**<br>3. ✅ **账户「真实余额校准（自动补差平账 `reconcileWalletBalance`）」**<br>4. ✅ **全量备份恢复 (`.mojibackup` Schema Guard) + 微信/支付宝/钱迹 CSV 导入导出 (`BackupAndCsvTransferEngine`)** |
| **Phase 2：进阶规则、复杂账务与保活体验（中期 P1）** | 适配重度记账玩家与国产系统 | 5. **国产 ROM 后台保活与 Android 13+ 受限设置一键诊断向导**<br>6. **可视化条件规则引擎 (`When -> Then`) + 通知栏快捷改分类**<br>7. **批量报销结算 + 单笔账单多分类拆分 (Split Transaction)**<br>8. **WebDAV 自动加密云同步** |
| **Phase 3：桌面触达与端侧 AI 进阶（远期 P2）** | 打造开源记账体验天花板 | 9. **iCost 风格桌面微件 (Glance Widget) + 控制中心快捷磁贴 + 指纹锁**<br>10. **分享截图 OCR 补录 + AI 月度财务洞察与订阅自动检测** |
