# 默记云端同步服务（`moji-cloud-server`）

基于 **Rust (`Axum + Tokio + Rusqlite WAL`)** 编写的轻量级自托管云存储后端，待机内存仅约 **10 MB**，单二进制内嵌 SQLite，开箱即用。

## 核心架构设计

1. **「服务器密钥 SK + 手机设备指纹」免密云同步 + 换新机家庭成员认领接管**：
   - **强制 `MOJI_SERVER_ACCESS_CODE`（服务器 SK）门禁**：免密设备登录与成员认领接口强制要求服务端在 `.env` 配置非空 `MOJI_SERVER_ACCESS_CODE`（4,096 轮 `SHA-256` 拉伸 + 常量时间比对 + `AuthRateLimiter` 防爆破限流）；若未配置则直接以 `403 (server_sk_not_configured)` 拦截，杜绝公网无口令裸奔。
   - **同机无感重连与多家人天然隔离**：手机端基于 `ANDROID_ID` 与运行环境（`prod` / `qa`）生成域分离设备指纹摘要，服务端再次进行 4,096 轮 `SHA-256` 拉伸后写入 `user_identities (provider='device_fp')`。同机卸载重装重填 `URL + SK` 直接认出原 `user_id`；不同家人的手机可创建各自独立的云端账本。
   - **换新机两步探测与家庭成员认领接管（防误建垃圾空账号）**：新手机指纹首次连接已有成员的家庭服务器时，`POST /api/v1/auth/device-login` 返回 `selection_required` 及当前环境下的家庭成员摘要列表（不偷偷新建空号）；用户既可点选本人旧账号经二次确认调用 `POST /api/v1/auth/claim-member` 接管绑定并拉回账本、识屏原图与自定义头像，也可显式选择「我是新家人，创建独立账本」（`create_if_missing = true`）。
   - **兼容保留专属密钥（Sync Key）**：完整保留旧版 `POST /api/v1/auth/key-login`（`MOJI-XXXX` / `MOJIQA-XXXX`）与存量 SQLite 库幂等无损迁移，`prod` 与 `qa` 环境严格物理隔离。
2. **无缝衔接后期微信 / 手机号登录（零数据迁移）**：
   - 数据库采用 `users (user_id)` $\leftarrow$ `user_identities (provider, identifier_hash, user_id)` 标准解耦表结构，所有同步与资料接口统一使用内部 `user_id` 签发的 `Bearer` 令牌。
   - 后续对外推广接入微信开放平台或手机号登录时，只需在 `user_identities` 新增 `'wechat'` 或 `'phone'` 绑定记录，现有云端账单、历史快照、识屏原图与自定义头像无需任何迁移。
3. **乐观并发防误覆盖 + 历史快照归档 + WebP 账单原图与自定义头像同步**：
   - 内置 `expected_revision` 版本锁与空账本防误抹除守卫（`409 Conflict`），防止新手机认领账号后尚未拉取就误用空库覆盖云端数据。
   - 自动按用户保留最近 `N` 个历史版本（默认 `10` 版），所有文件写入均采用临时文件 `fsync` + 原子 `rename`。
   - 识屏账单截图（`data_dir/screenshots/`）与个人自定义头像（`data_dir/avatars/<user_id>.webp`，限制 `16B ~ 512KB` WebP）均强校验 `RIFF....WEBP` 魔数后原子落盘，换机恢复时一键完整还原。
4. **「个人管资产，家庭看收支」增量家庭共享池（`family_transactions`）**：
   - 独立幂等表 `family_transactions` 按 `(app_env, tx_uuid)` 唯一约束严格隔离 `prod` 与 `qa`，采用单调自增游标 `seq` 支持多设备毫秒级增量推拉（`POST /api/v1/family/sync`），仅存储脱敏收支展示字段（`EXPENSE` / `INCOME` 金额、币种、分类、商户、备注、标签、成员昵称/预设头像及软删除墓碑 `deleted_at_ms`），**零钱包卡号、零抓取原文、零识屏截图路径**。
   - 强所有权门禁：仅账单原作者（`owner_user_id == claims.uid`）可更新或软删除名下家庭账单，越权覆写直接以 `403 (forbidden_not_tx_owner)` 拦截；支持同环境成员按需拉取家人自定义头像（`GET /api/v1/family/avatar/:user_id`）。

## 部署到 ECS 指南

### 方式一：Docker Compose 部署（推荐）

```bash
cd server
cp .env.example .env
# 必填：编辑 .env，将 MOJI_SERVER_ACCESS_CODE 修改为你和家人约定的高强度服务器密钥 SK
vim .env

docker compose up -d --build
curl http://127.0.0.1:8787/api/v1/health
```

随后通过宿主机 Nginx / Caddy 配置 HTTPS 反向代理至 `http://127.0.0.1:8787`（注意将反向代理请求体上限设为 `client_max_body_size 80m;`，Docker Bridge 模式下若需按真实客户端 IP 限流可在 `.env` 开启 `MOJI_TRUST_PROXY_HEADERS=true`）。

### 方式二：本机交叉编译单二进制部署（适合 1C1G / 1C2G 轻量 ECS）

在开发机（macOS）交叉编译 Linux 静态二进制后直接上传至 ECS，免去低配服务器编译耗时：

```bash
# 1. 在本地编译（以 x86_64 Linux 静态 musl 二进制为例）
cd server
cargo zigbuild --release --locked --target x86_64-unknown-linux-musl

# 2. 在 ECS 初始化目录权限（数据目录 0700 www-data，.env 0640 root:www-data）
sudo install -d -o root -g root -m 0755 /opt/moji-cloud
sudo install -d -o www-data -g www-data -m 0700 /opt/moji-cloud/data
sudo install -o root -g www-data -m 0640 .env.example /opt/moji-cloud/.env
# 编辑 /opt/moji-cloud/.env，填入高强度 MOJI_SERVER_ACCESS_CODE（服务器 SK）与 MOJI_TRUST_PROXY_HEADERS=true

# 3. 上传二进制与 systemd 配置并启动开机自启服务
scp target/x86_64-unknown-linux-musl/release/moji-cloud-server root@203.0.113.10:/opt/moji-cloud/
scp deploy/moji-cloud.service root@203.0.113.10:/etc/systemd/system/
ssh root@203.0.113.10 "chmod 0755 /opt/moji-cloud/moji-cloud-server && systemctl daemon-reload && systemctl enable --now moji-cloud"
```

### Caddy 子路径无损挂载示例（如 `https://sync.example.com/moji`）

若服务器已有其他站点运行在根路径，可参考 [`deploy/Caddyfile.subpath.example`](deploy/Caddyfile.subpath.example) 在同一域名下通过 `@moji path /moji /moji/*` + `uri strip_prefix /moji` 无损挂载 `/moji` 子路径（修改前请先备份 `/etc/caddy/Caddyfile`，并先执行 `sudo caddy validate --config /etc/caddy/Caddyfile` 校验通过后再 `sudo systemctl reload caddy`）。手机端「同步地址」直接填写 `https://sync.example.com/moji` 即可。

### GitHub Actions 最小权限自动部署（`.github/workflows/deploy-server.yml`）

仓库内置 `.github/workflows/deploy-server.yml`：
- **PR 门禁校验**：当 `server/**` 或工作流变更时，自动执行 `cargo test --locked` 与 `x86_64-unknown-linux-musl` 静态 Release 编译校验。
- **合并自动发布**：推送至 `develop` / `main` 或手动触发 `workflow_dispatch` 时，编译静态二进制并通过专用受限系统账号 `moji-deploy` 上传至 `/home/moji-deploy/moji-cloud-server.new`，调用受限脚本 [`deploy/moji-atomic-update.sh`](deploy/moji-atomic-update.sh)（安装于 `/usr/local/sbin/moji-atomic-update`，配合 [`deploy/moji-deploy.sudoers`](deploy/moji-deploy.sudoers)）完成 ELF 校验、同分区原子替换、`moji-cloud` 重启、本机失败自动回滚与公网 `/api/v1/health` 校验；`moji-deploy` 全程无权读写 `/opt/moji-cloud/data` 与 `/opt/moji-cloud/.env`。

在 GitHub 仓库 `Settings -> Secrets and variables -> Actions` 配置以下 Encrypted Secrets 即可启用自动部署：

| Secret 名称 | 示例值（占位符） | 说明 |
| --- | --- | --- |
| `MOJI_DEPLOY_HOST` | `203.0.113.10` | 云服务器公网 IP 或主机名 |
| `MOJI_DEPLOY_PORT` | `22` | SSH 端口 |
| `MOJI_DEPLOY_USER` | `moji-deploy` | 仅授予以 `sudo -n /usr/local/sbin/moji-atomic-update` 更新二进制的受限账号 |
| `MOJI_DEPLOY_SSH_KEY` | `<Ed25519 私钥全文>` | `moji-deploy` 专用部署私钥 |
| `MOJI_HEALTHCHECK_URL` | `https://sync.example.com/moji/api/v1/health` | 公网健康检查地址（需返回 HTTP 200 且 `requires_access_code: true`） |

## 接口一览

| 方法与路径 | 鉴权 | 说明 |
| --- | --- | --- |
| `GET /api/v1/health` | 公开 | 服务健康检查及是否已配置 `MOJI_SERVER_ACCESS_CODE` |
| `POST /api/v1/auth/device-login` | 必填服务器 SK + 设备指纹 | 免密设备指纹登录/探测：已绑设备直接签发 30 天 `Bearer` 令牌；新设备遇已有成员返回 `selection_required` 成员列表，或 `create_if_missing=true` 新建独立账本 |
| `POST /api/v1/auth/claim-member` | 必填服务器 SK + 设备指纹 | 换新机认领接管指定家庭成员 `user_id`，绑定当前设备指纹并签发访问令牌 |
| `POST /api/v1/auth/key-login` | Sync Key (+ 接入码) | 兼容旧版专属密钥绑定/验证，签发 30 天 `Bearer` 访问令牌并返回云端摘要 |
| `GET /api/v1/auth/identities` | Bearer Token | 查询当前 `user_id` 已绑定的身份列表（`device_fp`、`sync_key` 及未来扩展身份） |
| `PUT /api/v1/profile/avatar` | Bearer Token | 上传/更新当前用户自定义头像（强校验 `RIFF....WEBP` 魔数，大小 `16B ~ 512KB`） |
| `GET /api/v1/profile/avatar` | Bearer Token | 下载当前用户的云端自定义头像（`image/webp`） |
| `DELETE /api/v1/profile/avatar` | Bearer Token | 清除当前用户的云端自定义头像 |
| `GET /api/v1/sync/status` | Bearer Token | 查询最新云端快照摘要、同步过的手机设备列表与历史归档版本 |
| `PUT /api/v1/sync/snapshot` | Bearer Token | 上传全量账本快照（支持 `expected_revision` 冲突拦截与 `force_overwrite`） |
| `GET /api/v1/sync/snapshot` | Bearer Token | 拉取最新（或指定 `?revision=` 历史版本）账本快照及可用截图哈希列表 |
| `POST /api/v1/screenshots/missing` | Bearer Token | 批量比对本地账单原图 `SHA-256`，仅返回云端缺失项 |
| `PUT /api/v1/screenshots/:sha256` | Bearer Token | 上传单张 `image/webp` 账单原图（服务端强校验 `SHA-256` 与 WebP 魔数） |
| `GET /api/v1/screenshots/:sha256` | Bearer Token | 下载当前用户名下的指定账单原图 |
| `POST /api/v1/family/sync` | Bearer Token | 家庭共享账本增量推拉（校验 `owner_user_id == claims.uid`，返回 `since_seq` 之后增量变更、家庭成员列表与最新 `latest_seq`） |
| `GET /api/v1/family/avatar/:user_id` | Bearer Token | 下载同一 `app_env` 下指定家庭成员的云端自定义头像（`image/webp`） |
