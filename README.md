# 默记 MoJi

> **默默记好每一笔账，不打扰你的生活。**

一款融合 **iCost 极简卡片交互体验** 与 **AI 全自动静默识账引擎** 的安卓原生开源记账应用（Kotlin + Jetpack Compose + Room），彻底告别传统自动记账软件“正则表达式规则维护地狱”与繁琐弹窗打扰。

## 核心亮点

1. **AI 三模无感采集（通知栏 + 微信/支付宝/拼多多/抖音/京东等全场景无障碍识账 + 免 Root 端侧 OCR 兜底）+ 30s 跨通道事后证据融合 (`AI_FUSED`)**：
   - [`PaymentNotificationListenerService`](app/src/main/java/com/moji/ai/service/PaymentNotificationListenerService.kt) & [`CaptureAppWhitelistManager`](app/src/main/java/com/moji/ai/ai/CaptureAppWhitelistManager.kt)：实时捕获微信支付、支付宝、云闪付、银行 App 及扣款短信通知；支持在「智能记账 › 监听应用白名单」（[`CaptureAppWhitelistScreen`](app/src/main/java/com/moji/ai/ui/screens/CaptureAppWhitelistScreen.kt)）按应用独立控制「通知栏自动识账」与「支付页无障碍识屏」双通道开关（内置三梯队预设：微信/支付宝/云闪付/短信/主流银行默认双通道全开，京东/淘宝/拼多多/美团默认仅开通知、关闭无障碍自动扫屏防逛商品页误记，抖音/小红书/唯品会默认全关，支持手动添加自定义城商行/支付包名并随 `.mojibackup` 与云同步备份恢复；控制中心「默记识屏记账」磁贴手动触发不受白名单限制）。
   - [`SelectToSpeakService`](app/src/main/java/com/google/android/accessibility/selecttospeak/SelectToSpeakService.kt)、[`PaymentAccessibilityService`](app/src/main/java/com/moji/ai/service/PaymentAccessibilityService.kt) & [`CaptureWindowGuard`](app/src/main/java/com/moji/ai/ai/CaptureWindowGuard.kt)：吸收花有谱账本、蓝鲸记账与钱迹自动记账的窗口识别优点，通过 `SelectToSpeakService` 兼容入口 + `FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY` 突破微信 WebView/LiteApp 节点屏蔽；内置 **5 重窗口级防误触门禁**（① 单窗口所有权锁死非画中画最顶层焦点窗口，杜绝多窗口文本串台；② `PackageManager.getActivityInfo` 核验真实 Activity 并按专用支付收银台/账单页、共享严审容器、聊天朋友圈相册看图商品页硬黑名单分级路由，且对内置电商宿主仅在支付宝无障碍开启且真实 Activity 命中内嵌支付宝 SDK 收银台 `com.alipay.sdk.app.*` 时窄口放行，逛商品页/订单列表零误触；③ 可见节点过滤与 `isPassword` / `isEditable` 输入框及看图预览动作词硬件/文本双重否决；④ 交易关闭/取消/失败、待付款核身收银台、资产余额页、商品导购页与无单号旧截图 15 分钟时效拦截；⑤ 前 5 层视图骨架哈希 `StructureFingerprint` 支持在白名单页一键屏蔽误触页面模板并随备份同步），精准识别 **微信支付、支付宝、云闪付、银行及电商跳转专用收银台** 的单笔账单详情与无时间戳实时付款成功页（`AI_LIVE_CASHIER`）；遇到自绘页面或点击控制中心「默记识屏记账」磁贴（[`OcrTileService`](app/src/main/java/com/moji/ai/service/OcrTileService.kt)）时，调用 Android 11+ `takeScreenshot` + 端侧离线中文 OCR 兜底。
   - [`CapturePipelineCoordinator`](app/src/main/java/com/moji/ai/service/CapturePipelineCoordinator.kt)、[`BillScreenshotStorage`](app/src/main/java/com/moji/ai/service/BillScreenshotStorage.kt) & [`RecognitionBanner`](app/src/main/java/com/moji/ai/service/RecognitionBanner.kt)：
     - **iCost 模板「原价 · 优惠 · 实扣」三元组、医保统筹/个账细粒度拆解与组合支付父子双扣闭环**：针对拼多多、抖音支付、京东、淘宝、美团及微信/支付宝账单与收银台完成页，智能合并跨 `TextView` 拆分金额（如 `¥ | 29 | .90`）与划线原价立减（`实付 + 立减 == 原价`），优先锁定 `实付款 / 实扣金额` 作为入账实扣金额，同时提取 `商品总价 / 原价` 与各类补贴立减优惠，以结构化模板格式记录并在流体云胶囊、首页账单行及账单详情页直观展示；针对支付宝/微信**医院门诊与药店医保账单**支持细粒度拆解 **`医保统筹`（统筹基金/大病统筹/医疗救助）** 与 **`医保个账`（个人账户/历年账户/家庭共济）** 并自动防重汇总项，自动打上 **`医保`** / **`组合支付`** 标签（100% 统筹全额报销单强制 `sourceWalletId = null` 不误扣个人医保卡；建有「个人医保账户」且存在现金自付+医保个账混合支付时，在同一 Room 事务内原子拆出 `#MED_PERSONAL` 关联子单同步扣减个人医保卡并剔除主单重复抵扣，支持 30s 多帧 `AI_FUSED` 补全升级与删除主单时父子双腿联动回滚）；
     - **核心四要素留存、专用收银台即时入账 (`AI_LIVE_CASHIER`)、二阶段详情原位校准 (`AI_FUSED`) 与防重机制（默认不录入 + 弹窗点“录入”才入账）**：提取并留存 **订单唯一 Key (`orderKey`，订单号/交易单号/商户单号)**、**实际购物发生时间 (`timestamp`，非截图时间)**、**交易金额 (`amountCents`)** 与 **商品数量 (`itemQuantity` / `quantityDesc`)**；共享/泛 WebView 容器缺失明确交易时间时严格拦截防晒单误触，而针对**专用支付收银台实时付款成功页**（满足专用容器 + 明确支付成功词 + `完成/返回商家` 控制词 + 唯一有效实付金额，及手动识屏磁贴）即时生成 `AI_LIVE_CASHIER` 账单并按收银台 `Visit` 会话防停留连记、支持立减动画实扣校准与连续同金额独立入账；当二阶段银行通知、支付宝/微信账单详情或电商订单详情到达时，按单向时序窗原位升级为 `AI_FUSED` 补齐精确秒级支付时间、单号、具体店铺商品与真实扣款账户且绝不双扣余额（若窗口内存在 `≥2` 笔同金额无单号待校准收银台记录导致归属歧义，则禁止盲目吞并，自动转为疑似重复弹窗交用户确认）；若原账单细节早已齐全无新增字段，则判定为重复扫描，**默认绝不录入数据库**，在流体云胶囊提示 **「检测到疑似重复 · 未录入」**，仅当用户主动点击 **「录入」** 按钮时才写入账本；
     - **识账原图保存与生命周期自动清理**：识屏（无障碍详情页与端侧 OCR）自动将账单截图压缩为 WebP 保存在 `files/bill_screenshots/`，支持在账单详情页异步解码点开查看原图；撤销入账或删除账单时自动清理无引用的本地截图文件；
     - **支付账号精准匹配与未知账户安全挂空（`不关联账户`）**：银行卡按后 4 位尾号（如 `招商银行信用卡(3354)`、`储蓄卡(8848)`）匹配，花呗、白条、零钱、账户余额及医保/社保卡按关键词与卡种匹配并自动扣减对应账户余额或增加待还欠款；对缺失或未建档的支付方式（如未创建医保卡钱包时的纯医保账单）严格保持 `sourceWalletId = null` 挂空展示为 **「不关联账户」**（绝不按包名乱猜花呗/默认账户），记账计算器面板亦支持手动选择「不关联账户」；
     - **一加流体云（Fluid Cloud）120Hz 挖孔延伸胶囊（点击直达当前账单详情）**：设置 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` 切入顶部挖孔区，展开时从前置摄像头挖孔向下拉出水滴连接颈并丝滑撑开为曜石黑实况胶囊（自绘抗锯齿发光图标 + 凝缩金额 + 正常入账「撤销」/ 疑似重复「录入」大热区按钮），点击胶囊主体直接打开当前记录的「账单详情」页，退出账单页、超时或上滑时平滑回吸缩进摄像头孔内，内置看门狗杜绝悬浮窗残留。
2. **通用大模型兼容 + Agent 账单与截图分类梳理（`待梳理` 免分类记账）+ 商户记忆自学习 + 可视化条件规则 (`When -> Then`) + 带排除词权重的离线语义引擎**：
   - [`AiAccountingEngine`](app/src/main/java/com/moji/ai/ai/AiAccountingEngine.kt)、[`AgentBillTriageEngine.kt`](app/src/main/java/com/moji/ai/ai/AgentBillTriageEngine.kt)、[`AgentTriageBottomSheet.kt`](app/src/main/java/com/moji/ai/ui/components/AgentTriageBottomSheet.kt)、[`MerchantMemoryAndWeightedClassifier.kt`](app/src/main/java/com/moji/ai/ai/MerchantMemoryAndWeightedClassifier.kt) & [`SmartRuleEngine.kt`](app/src/main/java/com/moji/ai/ai/SmartRuleEngine.kt)：支持任意 OpenAI 兼容接口与 Google Gemini 官方接口；支持 **「待梳理（交由 Agent）」免分类极速记账**（记账计算器可一键切入或再次点击已选分类取消选择为 `待梳理` 直接保存金额，静默识账遇低置信度未知账单亦自动落入 `待梳理`）；提供 **「✨ Agent 梳理」四入口交互闭环**（账本首页顶部待梳理提示胶囊、记账计算器顶部、分类管理顶栏、智能记账分类规则区），结合账单截图 (`screenshotPath`)、原始文本 (`rawCapturedText`)、交易时段与金额，一键完成 **待梳理账单批量重分类、建议新增细分一二级分类（自动匹配 81 枚 `.webp` 图标）以及 `When -> Then` 条件规则与商户记忆自动提炼**；内置 **商户记忆自学习引擎 (`MerchantMemoryManager`)**、**带排除词一票否决权重的离线分类器 (`WeightedCategoryClassifier`)** 以及 **可视化条件规则引擎 (`SmartRuleEngine`)**。
3. **1:1 iCost 级视觉、模块侧滑扩展功能与丝滑弹簧动效体系**：
   - **「墨册朱签」自适应品牌图标、原生字体、弹簧按压与模块侧滑扩展容器**（[`ic_launcher_foreground.xml`](app/src/main/res/drawable/ic_launcher_foreground.xml)、[`ic_launcher_monochrome.xml`](app/src/main/res/drawable/ic_launcher_monochrome.xml)、[`ICostTheme.kt`](app/src/main/java/com/moji/ai/ui/theme/ICostTheme.kt)）：配备宣白暖石基底 + 玄墨精装账册 + 朱砂燕尾书签全套 Adaptive 矢量图标、Android 13+ Material You 莫奈单色主题图标与 24dp 状态栏通知矢量；内置 `ICDIN-Bold` 凝缩数字字体与 200 枚线性分类/银行品牌圆形图标，全端搭载 `iCostPressScale` 弹簧按压回弹 + 轻触觉震动反馈（`LocalHapticFeedback`）、`AnimatedMoneyText` 数字滚动过渡，以及 `ICostSwipeActionRow` 双向阻尼侧滑扩展功能容器与 `iCostHorizontalSwipeTrigger` 手势切换修饰符。
   - **账本首页与单笔分拆账单**（[`LedgerHomeTab.kt`](app/src/main/java/com/moji/ai/ui/screens/LedgerHomeTab.kt)、[`TransactionDetailAndCalendarSheets.kt`](app/src/main/java/com/moji/ai/ui/components/TransactionDetailAndCalendarSheets.kt)）：多账本切换、标题栏右滑/点击展开 **快捷记账模板抽屉**（点击模板一键秒记）、关键词实时搜索、`HorizontalPager` 三页左右滑动月度概览卡片、年月栏左右滑动切月、全屏收支日历页、按日弹簧收折卡片组、**单笔账单行右滑/左滑呼出「编辑 / 复制 / 报销 / 退款 / 删除」扩展胶囊**，以及账单详情页 **「单笔分拆为多个分类 (Split Transaction)」**。
   - **记账计算器面板、全屏分类管理、全链路标签管理、全能记账模板管理、多币种汇率管理、应用隐私密码保护、多时段定时记账提醒与全屏外观显示管理**（[`ICostCalculatorSheet.kt`](app/src/main/java/com/moji/ai/ui/components/ICostCalculatorSheet.kt)、[`CategoryManagementScreen.kt`](app/src/main/java/com/moji/ai/ui/screens/CategoryManagementScreen.kt)、[`CategoryCatalog.kt`](app/src/main/java/com/moji/ai/data/model/CategoryCatalog.kt)、[`TagManagementScreen.kt`](app/src/main/java/com/moji/ai/ui/screens/TagManagementScreen.kt)、[`TagCatalog.kt`](app/src/main/java/com/moji/ai/data/model/TagCatalog.kt)、[`TemplateManagementScreen.kt`](app/src/main/java/com/moji/ai/ui/screens/TemplateManagementScreen.kt)、[`CurrencyManagementScreen.kt`](app/src/main/java/com/moji/ai/ui/screens/CurrencyManagementScreen.kt)、[`CurrencyRateManager.kt`](app/src/main/java/com/moji/ai/data/model/CurrencyRateManager.kt)、[`PasswordProtectionScreen.kt`](app/src/main/java/com/moji/ai/ui/screens/PasswordProtectionScreen.kt)、[`AppLockOverlayScreen.kt`](app/src/main/java/com/moji/ai/ui/screens/AppLockOverlayScreen.kt)、[`AppLockManager.kt`](app/src/main/java/com/moji/ai/data/model/AppLockManager.kt)、[`ReminderManagementScreen.kt`](app/src/main/java/com/moji/ai/ui/screens/ReminderManagementScreen.kt)、[`ReminderConfigManager.kt`](app/src/main/java/com/moji/ai/data/model/ReminderConfigManager.kt)、[`DailyReminderScheduler.kt`](app/src/main/java/com/moji/ai/notification/DailyReminderScheduler.kt)、[`AppearanceManagementScreen.kt`](app/src/main/java/com/moji/ai/ui/screens/AppearanceManagementScreen.kt)、[`AppearanceConfigManager.kt`](app/src/main/java/com/moji/ai/data/model/AppearanceConfigManager.kt)）：支出/收入/转账三态手势滑动与点击平滑过渡、5 列线性分类网格（选中弹簧放大 + 二级子分类盒平滑展开 + 末尾「管理」与「+ 新建」快捷入口）、转账账户 `180°` 旋转互换动效、支持 `+ - × ÷` 连续运算的 `4×4` 触觉圆角键盘；支持「设置 › 更多功能 › 分类管理」与记账计算器双入口管理支出/收入二级分类树（新增/编辑/上下移动排序/恢复默认）、内置 81 枚线性分类图标分组选择器，以及分类重命名/删除时跨历史账单、周期规则、快捷模板、分期计划、条件规则与商户记忆库的 6 处原子级联迁移；支持 **「设置 › 更多功能 › 标签管理」与记账计算器多标签快捷选择抽屉双入口** 管理 10 色莫奈彩色标签库与 4 大场景分组（日常 / 差旅报销 / 生活事件 / 自定义），支持标签新增、编辑、上下排序、合并、安全删除保护及跨历史账单、快捷模板与条件规则的精确 Token 级联迁移、点击标签卡片展开关联账单下钻明细、统计页「标签排行统计」与 `.mojibackup` 备份同步；支持 **「设置 › 更多功能 › 记账模板」、首页顶部快捷模板抽屉与记账计算器「模板」胶囊三入口** 管理支出/收入/转账三态模板与 `0` 元「待填金额」动态模板，支持一键秒记（含 8 秒撤销与信用卡还款自动识别）、填入计算器预填草稿、8 个常用预设模板按需一键导入、复制/上下移动排序；支持 **「设置 › 更多功能 › 货币和汇率」、记账计算器币种弹窗「汇率设置」与资产页新建/编辑外币账户三入口** 管理 12 种常见货币（`CNY / USD / HKD / EUR / JPY / GBP / KRW / TWD / SGD / AUD / CAD / THB`），内置实时基准金额汇率换算预览器、单币种/全量自定义汇率编辑与恢复默认、跨净资产/总资产/总负债/统计趋势与新记账单的响应式生效汇率联动（严格保持历史已入账流水汇率快照不变），以及 `.mojibackup` 全量快照与 WebDAV 云同步；支持 **「设置 › 更多功能 › 密码保护」** 全屏隐私锁管理，采用 4 位 PIN 码 `PBKDF2-HMAC-SHA256（120,000 轮迭代 + 16 字节随机 Salt，兼容旧版 SHA-256 自动无感升级）` 安全摘要存储、两步式密码设置与原密码验证修改/关闭、连续输错 5 次触发 30 秒持久化防暴力破解冷却锁、密码提示语防泄露校验（严禁包含当前 PIN 本身）、三档基于单调时钟 (`elapsedRealtime`) 的前后台自动锁定（`立即锁定 / 离开 1 分钟后 / 离开 5 分钟后`）、锁定态配置篡改拦截、一键「立即锁定测试」顶层独立窗口 `Dialog` 全屏数字键盘遮罩（阻断一切底层弹窗与返回键交互）及多任务模糊防截屏（`FLAG_SECURE`）开关；支持 **「设置 › 更多功能 › 定时提醒」** 对标 iCost 的多时段记账提醒管理，支持无需特殊精确闹钟权限的安全 `AlarmManager` 调度、自定义多时段（快捷时间芯片 + 周一至周日任意重复周期 + 自定义标题与内容）、「今日已记账则免打扰」智能静默跳过、开机/时间变更自动重注册、点击提醒通知 Deep-Link 直达首页记账计算器面板、一键「立即发送测试提醒」验证及 `.mojibackup` 备份恢复同步；支持 **「设置 › 更多功能 › 外观显示」** 对标 iCost 的全屏外观定制中心，涵盖顶部实时记账预览卡片、浅色模式 / 深色模式 / 跟随系统 0 重启即时切换、红支绿收 / 绿支红收 / 极简单色三套收支配色方案（严格隔离删除/报错警示红 `ICostCoralRed`）、紧凑 (`92%`) / 标准 (`100%`) / 大字版 (`110%`) 全局字号比例缩放、金额千分位分隔符与整数隐藏 `.00` 小数尾缀显示偏好、一键恢复默认及 `.mojibackup` 备份恢复同步。
   - **全能资产管理、个人医保/住房公积金专项保障账户与余额锚点平账**（[`AssetsTab.kt`](app/src/main/java/com/moji/ai/ui/screens/AssetsTab.kt)、[`DedicatedFundManager.kt`](app/src/main/java/com/moji/ai/data/model/DedicatedFundManager.kt)）：净资产双页可滑动大卡（第 1 页净资产/总资产/总负债，原生支持信用卡还款超额产生的负欠款 **「溢缴款」** 核算；右滑/左滑切换第 2 页 **存钱计划进度与信用卡分期管理概览**）、总借入/总借出双卡、**独立「专项保障账户（医保 / 公积金）」可折叠分组与一键向导**（`MEDICAL_CARD` / `PROVIDENT` 默认 `includeInNetWorth = false` 隔离受限保障资金，支持配置个人+单位月度双边缴存参考、预计入账日、关联代发工资储蓄卡、一键生成月度自动入账周期规则及预留 `PayslipRecognitionDraft` 工资条三路入账路由契约，随 `.mojibackup` 与默记云备份恢复）、**单个信用/资金/专项账户行侧滑扩展胶囊**、以及账户详情页 **「校准真实余额（自动计算差额并生成 `BALANCE_ANCHOR` 平账流水）」**。
   - **多维统计、医疗与医保四维透视卡、批量报销结算、个人资料与账号同步卡、iCost/微信/支付宝导入导出、Rust 默记云存储（免登录专属密钥 + 原图增量同步）、WebDAV 云同步与官方公开源 OTA 自动更新**（[`StatisticsTab.kt`](app/src/main/java/com/moji/ai/ui/screens/StatisticsTab.kt)、[`UserProfileManager.kt`](app/src/main/java/com/moji/ai/data/model/UserProfileManager.kt)、[`BackupAndCsvTransferEngine.kt`](app/src/main/java/com/moji/ai/data/backup/BackupAndCsvTransferEngine.kt)、[`MoJiCloudSyncClient.kt`](app/src/main/java/com/moji/ai/data/backup/MoJiCloudSyncClient.kt)、[`server/`](server/README.md)、[`WebDavSyncClient.kt`](app/src/main/java/com/moji/ai/data/backup/WebDavSyncClient.kt)、[`AppUpdateManager.kt`](app/src/main/java/com/moji/ai/update/AppUpdateManager.kt)）：周/月/年/全部四段切换（按当前账本统计，净资产趋势按全账本有效流水与核销时刻精确倒推）、**2×2「医疗与医保费用四维透视卡片」（统计页与标签管理 `#医保` 下钻页联动展示 `医疗总费用 / 医保统筹报销 / 医保个账支出 / 个人现金自付`）**、**批量报销核销中心 (`batchSettleReimbursements`，支持已核销账单删除/改价/关联退款时双向联动守恒回退)**、**设置页顶部「个人资料与账号同步卡」（支持 8 款莫兰迪插画预设头像 + 系统 Photo Picker 免权限相册头像 WebP 裁剪落盘、16 字规范化昵称、主界面三 Tab 顶栏头像实时联动、昵称联动默记云 `memberLabel` 与 `.mojibackup` 快照非破坏性携带）**、**微信支付官方 CSV / 支付宝官方 CSV / 钱迹 / iCost CSV 与 `.xlsx` 自动识别预览（含多行引号字段解析、公式注入防护、Zip Bomb 解压阈值防护、零钱通/余额宝支出精确识别与超大文件只读预览防截断覆盖）、自动建账本/建资产账户并防重导入 (`importExternalBatch`)**、带 `BackupSchemaGuard` 兼容守卫的 `.mojibackup` 全量 JSON 快照备份恢复（涵盖账本、钱包、账单及 Room v6 跨币种变动快照、周期规则、商户记忆、条件规则、快捷模板、存钱计划、分期计划、自定义分类/标签/汇率及提醒/外观/个人资料配置，并通过 `backup_rules.xml` / `data_extraction_rules.xml` 云端与换机双通道排除系统明文提取）、**基于 Rust (`Axum + Rusqlite WAL`) 的自建 ECS 云存储服务 `moji-cloud-server`（支持手机端密码学专属密钥 `MOJI-XXXX-...` 免登录绑定、家人独立密钥账单隔离、`MOJI_SERVER_ACCESS_CODE` 家庭接入保护、已有昵称防换机默认机型名覆盖、乐观并发防空账本误覆盖、识屏 WebP 账单原图内容寻址增量上传与单用户 512 MiB 配额保护及换机自动恢复、记账后后台 6s 防抖静默同步，且底层采用内部 `user_id + user_identities` 解耦模型无缝衔接后续微信/手机登录）**、支持坚果云 / Nextcloud / 群晖的 **WebDAV 安全云备份上传与恢复（强制公网 HTTPS + `.tmp` 临时写入后 `MOVE` 原子替换）**，以及面向 **官方 GitHub 公开仓库（`Rowan-rh/moji-ai`，逐跳校验 HTTPS 受信任 GitHub 域名白名单 + Release Asset SHA-256 摘要校验 + 安装前包名与签名证书一致性核验）的应用内免配置 OTA 自动检查更新与一键覆盖安装**。
4. **纯后台静默入账 + 通知栏三键快捷操作 + 国产 ROM 保活向导**：
   - [`SilentBookkeepingNotifier`](app/src/main/java/com/moji/ai/notification/SilentBookkeepingNotifier.kt) & [`RomKeepAliveGuideHelper`](app/src/main/java/com/moji/ai/service/RomKeepAliveGuideHelper.kt)：后台自动入账后推送系统通知，通知栏提供 **「撤销入账」「切换分类（联动写入商户记忆库）」「标为待报销」** 三大快捷按钮；内置针对 ColorOS / HyperOS / OriginOS / HarmonyOS 的 **后台保活、无障碍真实连接态与系统假开启断连自动检测提醒及 Android 13+ 受限设置一键解除诊断向导**。

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

仓库公开时，每次发布 GitHub Release 并附上 `moji.apk`（`release` 构建默认限定主流 64 位真机 `arm64-v8a` 架构、开启 R8 与资源收缩并仅保留中文语言资源，保持 `.so` 未压缩对齐免解压安装与完整离线中文 OCR；日常模拟器调试使用不锁架构的 `Debug` / `QA` 包），就同时提供了公开下载链接和应用内 OTA 更新（无需 Token）：

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

