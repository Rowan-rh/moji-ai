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
# 1. 在本地编译（以 x86_64 Linux 为例，使用 cargo-zigbuild）
cd server
cargo zigbuild --release --target x86_64-unknown-linux-musl

# 2. 上传二进制与配置模板到 ECS
scp target/x86_64-unknown-linux-musl/release/moji-cloud-server root@<ECS_IP>:/opt/moji-cloud/
scp .env.example root@<ECS_IP>:/opt/moji-cloud/.env
scp deploy/moji-cloud.service root@<ECS_IP>:/etc/systemd/system/

# 3. 在 ECS 上启动开机自启服务
systemctl daemon-reload
systemctl enable --now moji-cloud
```

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
