# 默记云端同步服务（`moji-cloud-server`）

基于 **Rust (`Axum + Tokio + Rusqlite WAL`)** 编写的轻量级自托管云存储后端，待机内存仅约 **10 MB**，单二进制内嵌 SQLite，开箱即用。

## 核心架构设计

1. **客户端专属密钥（Sync Key）免登录认证 + 家庭成员账单隔离**：
   - 手机端通过密码学安全随机数生成高强度专属密钥（正式包 `MOJI-XXXX-XXXX-XXXX-XXXX`，QA 测试包 `MOJIQA-XXXX-XXXX-XXXX-XXXX`）。
   - 服务端仅存储经 4,096 轮 `SHA-256` 拉伸的密钥摘要与仅保留末 4 位的脱敏提示（绝不明文落盘），你和家人各自生成独立密钥即可天然隔离各自的账本与原图。
   - 支持配置 `MOJI_SERVER_ACCESS_CODE` 家庭接入保护码（4,096 轮 `SHA-256` 拉伸 + 常量时间校验 + 防爆破限流）及 `MOJI_MAX_USERS` 家庭账号总数上限（配合每 IP 每小时新建账号限流），防止公网陌生人扫描端口耗尽磁盘或写入垃圾数据。
2. **无缝衔接后期微信 / 手机号登录（零数据迁移）**：
   - 数据库采用 `users (user_id)` $\leftarrow$ `user_identities (provider, identifier_hash, user_id)` 标准解耦表结构，所有同步接口统一使用内部 `user_id` 签发的 `Bearer` 令牌。
   - 后续对外推广接入微信开放平台或手机号登录时，只需在 `user_identities` 新增 `'wechat'` 或 `'phone'` 绑定记录，现有云端账单、历史快照与识屏原图无需任何迁移。
3. **乐观并发防误覆盖 + 历史快照归档 + WebP 账单原图内容寻址去重**：
   - 内置 `expected_revision` 版本锁与空账本防误抹除守卫（`409 Conflict`），防止新手机绑定旧密钥时误用空库覆盖云端数据。
   - 自动按用户保留最近 `N` 个历史版本（默认 `10` 版），所有文件写入均采用临时文件 `fsync` + 原子 `rename`。
   - 识屏账单截图按 `SHA-256` 校验 WebP 魔数与摘要后增量上传，换机恢复时自动拉回原图并修复本地路径引用。

## 部署到 ECS 指南

### 方式一：Docker Compose 部署（推荐）

```bash
cd server
cp .env.example .env
# 编辑 .env，将 MOJI_SERVER_ACCESS_CODE 修改为你和家人约定的口令
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
| `GET /api/v1/health` | 公开 | 服务健康检查及是否开启接入码保护 |
| `POST /api/v1/auth/key-login` | Sync Key (+ 接入码) | 免登录绑定/验证专属密钥，签发 30 天 `Bearer` 访问令牌并返回云端摘要 |
| `GET /api/v1/auth/identities` | Bearer Token | 查询当前 `user_id` 已绑定的身份列表（`sync_key` 及未来 `wechat`/`phone`） |
| `GET /api/v1/sync/status` | Bearer Token | 查询最新云端快照摘要、同步过的手机设备列表与历史归档版本 |
| `PUT /api/v1/sync/snapshot` | Bearer Token | 上传全量账本快照（支持 `expected_revision` 冲突拦截与 `force_overwrite`） |
| `GET /api/v1/sync/snapshot` | Bearer Token | 拉取最新（或指定 `?revision=` 历史版本）账本快照及可用截图哈希列表 |
| `POST /api/v1/screenshots/missing` | Bearer Token | 批量比对本地账单原图 `SHA-256`，仅返回云端缺失项 |
| `PUT /api/v1/screenshots/:sha256` | Bearer Token | 上传单张 `image/webp` 账单原图（服务端强校验 `SHA-256` 与 WebP 魔数） |
| `GET /api/v1/screenshots/:sha256` | Bearer Token | 下载当前用户名下的指定账单原图 |
