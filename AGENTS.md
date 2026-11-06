# 默记（MoJi）项目开发与验证规范

## Git 与 GitHub 流程

本项目的分支模型（`main` / `develop` / `codex/<简短主题>` / `hotfix/<简短主题>`）、Conventional Commits 中文提交消息、远程安全核验、PR 标题与合并策略，统一遵循全局唯一规范（执行任何提交、推送或 PR 操作前必须先读取并遵守）：

@~/.codex/AGENTS.md

## 项目结构与技术栈

- **包名与应用**：`com.moji.ai`（默记 MoJi）
- **核心架构**：Kotlin + Jetpack Compose（Material 3，对标 iCost 卡片与交互体系）+ Room 本地持久化
- **核心模块**：
  - `app/src/main/java/com/moji/ai/data/`：枚举定义、分类目录、Room 实体/DAO、`LedgerRepository` 账务引擎（含真实余额锚点平账、单笔账单多分类拆分 `splitExpenseTransaction`、批量报销结算 `batchSettleReimbursements`）、`BackupAndCsvTransferEngine` 全量备份恢复/CSV 导入导出引擎与 `WebDavSyncClient` 自托管云同步客户端
  - `app/src/main/java/com/moji/ai/ai/`：OpenAI 兼容接口 / Gemini 官方接口双协议识账引擎、`MerchantMemoryManager` 商户记忆自学习、`WeightedCategoryClassifier` 带排除词权重离线分类器与 `SmartRuleEngine` 可视化条件规则引擎（`When -> Then`）
  - `app/src/main/java/com/moji/ai/service/`：通知监听、无障碍节点采集、`CapturePipelineCoordinator`（1.1s 滑动窗口聚合 + 30s 跨通道事后证据融合 + 采集可观测日志）与 `RomKeepAliveGuideHelper` 国产 ROM 保活及 Android 13+ 受限设置诊断向导
  - `app/src/main/java/com/moji/ai/notification/`：静默入账系统通知与一键原子撤销/切换常用分类/标为待报销广播 (`TransactionActionReceiver`)
  - `app/src/main/java/com/moji/ai/ui/`：账本首页、记账计算器面板、资产管理、多维统计与设置页
- **需求与设计文档**：功能或数据模型发生变更时，需在同一 PR 中同步更新 `README.md` 与 `docs/REQUIREMENTS.md`。

## 本地构建与验证要求

仓库当前未配置云端 CI，提交功能 PR 前必须在本地完成构建验证，并在 PR 正文中写明验证命令与结果：

1. **编译校验**：
   ```bash
   ./gradlew assembleDebug
   ```
2. **真机安装与界面验证（连接真机时）**：
   - 安装调试包：`adb install -r -g app/build/outputs/apk/debug/app-debug.apk`
   - 启动或注入测试操作前，先检查当前前台窗口（`adb shell dumpsys window | grep mFocusedApp`），避免打断用户正在使用的其他前台应用。
   - 改动可见界面时，在 PR 正文中附上截图或说明界面验证结果。
3. **数据安全与高风险改动**：
   - 涉及 Room 数据库版本升级、破坏性迁移（`fallbackToDestructiveMigration`）、通知/无障碍权限变更或账单删除逻辑时，PR 创建后须等待用户确认再合并。
   - 严禁提交 `local.properties`、构建产物（`build/`、`*.apk`）、API Key 凭证或本机个人路径。
