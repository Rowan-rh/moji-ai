use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashSet;
use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use uuid::Uuid;

use crate::auth::now_millis;
use crate::error::AppError;

const MAX_MISSING_CHECK_BATCH: usize = 20_000;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AccountSummary {
    pub latest_revision: i64,
    pub wallet_count: usize,
    pub tx_count: usize,
    pub ledger_count: usize,
    pub screenshot_count: usize,
    pub snapshot_bytes: usize,
    pub snapshot_sha256: String,
    pub updated_by_device: String,
    pub updated_by_member: String,
    pub updated_at_ms: i64,
}

impl Default for AccountSummary {
    fn default() -> Self {
        Self {
            latest_revision: 0,
            wallet_count: 0,
            tx_count: 0,
            ledger_count: 0,
            screenshot_count: 0,
            snapshot_bytes: 0,
            snapshot_sha256: String::new(),
            updated_by_device: String::new(),
            updated_by_member: String::new(),
            updated_at_ms: 0,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct BoundIdentityInfo {
    pub provider: String,
    pub key_hint: String,
    pub created_at_ms: i64,
    pub last_used_at_ms: i64,
}

#[derive(Debug, Clone, Serialize)]
pub struct BoundDeviceInfo {
    pub device_id: String,
    pub device_name: String,
    pub member_label: String,
    pub app_version: String,
    pub last_sync_at_ms: i64,
}

#[derive(Debug, Clone, Serialize)]
pub struct SnapshotHistoryItem {
    pub revision: i64,
    pub snapshot_sha256: String,
    pub byte_size: usize,
    pub tx_count: usize,
    pub updated_by_device: String,
    pub updated_by_member: String,
    pub created_at_ms: i64,
}

#[derive(Debug, Clone)]
pub struct KeyBindOutcome {
    pub user_id: String,
    pub is_new_account: bool,
    pub summary: AccountSummary,
}

#[derive(Debug, Clone, Serialize)]
pub struct PulledSnapshotData {
    pub revision: i64,
    pub snapshot_sha256: String,
    pub updated_at_ms: i64,
    pub updated_by_device: String,
    pub updated_by_member: String,
    pub snapshot_json: String,
    pub available_screenshots: Vec<String>,
}

/// 用于轻量级校验上传 JSON 是否为合法的 MoJiFullBackupSnapshot 并提取统计指标
#[derive(Deserialize)]
struct SnapshotProbe {
    #[serde(rename = "appName", default)]
    app_name: String,
    #[serde(rename = "exportedAtMillis", default)]
    exported_at_millis: i64,
    #[serde(default)]
    ledgers: Vec<serde_json::Value>,
    #[serde(default)]
    wallets: Vec<serde_json::Value>,
    #[serde(default)]
    transactions: Vec<serde_json::Value>,
}

pub const DEFAULT_MAX_USER_SCREENSHOT_QUOTA_BYTES: usize = 512 * 1024 * 1024;

pub struct CloudStore {
    conn: Mutex<Connection>,
    data_dir: PathBuf,
    max_history_revisions: usize,
    max_user_screenshot_bytes: usize,
}

impl CloudStore {
    pub fn open(data_dir: &Path, max_history_revisions: usize) -> Result<Self, String> {
        fs::create_dir_all(data_dir.join("snapshots"))
            .map_err(|e| format!("create snapshots dir: {e}"))?;
        fs::create_dir_all(data_dir.join("screenshots"))
            .map_err(|e| format!("create screenshots dir: {e}"))?;

        let db_path = data_dir.join("moji_cloud.db");
        let conn = Connection::open(&db_path)
            .map_err(|e| format!("open sqlite {:?}: {e}", db_path))?;

        conn.execute_batch(
            r#"
            PRAGMA journal_mode = WAL;
            PRAGMA synchronous = NORMAL;
            PRAGMA foreign_keys = ON;
            PRAGMA busy_timeout = 5000;

            -- 1. 内部唯一用户表（与具体登录方式完全解耦）
            CREATE TABLE IF NOT EXISTS users (
                user_id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL DEFAULT '',
                app_env TEXT NOT NULL DEFAULT 'prod',
                status TEXT NOT NULL DEFAULT 'active',
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL
            );

            -- 2. 多身份凭证绑定表（一期 provider='sync_key'；后期可直接新增 'wechat' / 'phone' / 'email' 挂接同一 user_id）
            CREATE TABLE IF NOT EXISTS user_identities (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                provider TEXT NOT NULL,
                identifier_hash TEXT NOT NULL,
                key_hint TEXT NOT NULL DEFAULT '',
                created_at_ms INTEGER NOT NULL,
                last_used_at_ms INTEGER NOT NULL,
                UNIQUE(provider, identifier_hash)
            );
            CREATE INDEX IF NOT EXISTS idx_user_identities_uid ON user_identities(user_id);

            -- 3. 设备同步记录表（区分不同家庭成员或换机前后的手机设备）
            CREATE TABLE IF NOT EXISTS user_devices (
                device_id TEXT NOT NULL,
                user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                device_name TEXT NOT NULL DEFAULT '',
                member_label TEXT NOT NULL DEFAULT '',
                app_version TEXT NOT NULL DEFAULT '',
                last_sync_at_ms INTEGER NOT NULL,
                PRIMARY KEY (user_id, device_id)
            );

            -- 4. 用户当前最新云端快照索引表
            CREATE TABLE IF NOT EXISTS cloud_snapshots (
                user_id TEXT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
                revision INTEGER NOT NULL DEFAULT 0,
                snapshot_sha256 TEXT NOT NULL,
                byte_size INTEGER NOT NULL,
                wallet_count INTEGER NOT NULL DEFAULT 0,
                tx_count INTEGER NOT NULL DEFAULT 0,
                ledger_count INTEGER NOT NULL DEFAULT 0,
                updated_by_device TEXT NOT NULL DEFAULT '',
                updated_by_member TEXT NOT NULL DEFAULT '',
                exported_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL
            );

            -- 5. 历史快照回滚归档表
            CREATE TABLE IF NOT EXISTS cloud_snapshot_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                revision INTEGER NOT NULL,
                snapshot_sha256 TEXT NOT NULL,
                byte_size INTEGER NOT NULL,
                tx_count INTEGER NOT NULL DEFAULT 0,
                updated_by_device TEXT NOT NULL DEFAULT '',
                updated_by_member TEXT NOT NULL DEFAULT '',
                created_at_ms INTEGER NOT NULL,
                UNIQUE(user_id, revision)
            );

            -- 6. 账单识屏原图内容寻址关联表（按 user_id 严格隔离访问权限）
            CREATE TABLE IF NOT EXISTS user_screenshots (
                user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                sha256 TEXT NOT NULL,
                byte_size INTEGER NOT NULL,
                created_at_ms INTEGER NOT NULL,
                PRIMARY KEY (user_id, sha256)
            );
            "#,
        )
        .map_err(|e| format!("init sqlite schema: {e}"))?;

        // 兼容开发期早期未带 updated_by_member 列的本地库文件
        let _ = conn.execute(
            "ALTER TABLE cloud_snapshot_history ADD COLUMN updated_by_member TEXT NOT NULL DEFAULT ''",
            [],
        );

        let max_user_screenshot_bytes = std::env::var("MOJI_MAX_USER_SCREENSHOT_MB")
            .ok()
            .and_then(|v| v.trim().parse::<usize>().ok())
            .filter(|&mb| mb > 0)
            .and_then(|mb| mb.checked_mul(1024 * 1024))
            .unwrap_or(DEFAULT_MAX_USER_SCREENSHOT_QUOTA_BYTES);

        Ok(Self {
            conn: Mutex::new(conn),
            data_dir: data_dir.to_path_buf(),
            max_history_revisions,
            max_user_screenshot_bytes,
        })
    }

    #[cfg(test)]
    pub fn set_max_user_screenshot_bytes(&mut self, max_bytes: usize) {
        self.max_user_screenshot_bytes = max_bytes;
    }

    pub fn bind_or_login_with_sync_key<F>(
        &self,
        identifier_hash: &str,
        key_hint: &str,
        app_env: &str,
        member_label: &str,
        device_id: &str,
        device_name: &str,
        app_version: &str,
        max_users: usize,
        authorize_new_registration: F,
    ) -> Result<KeyBindOutcome, AppError>
    where
        F: FnOnce() -> Result<(), AppError>, {
        let now = now_millis();
        let clean_member = sanitize_short_text(member_label, 48);
        let clean_device_id = sanitize_short_text(device_id, 64);
        let clean_device_name = sanitize_short_text(device_name, 64);
        let clean_version = sanitize_short_text(app_version, 32);

        let mut conn = self
            .conn
            .lock()
            .map_err(|_| AppError::Internal("sqlite mutex poisoned".to_string()))?;
        let tx = conn
            .transaction()
            .map_err(|e| AppError::Internal(format!("begin tx: {e}")))?;

        let existing: Option<(String, String, String, String)> = tx
            .query_row(
                r#"
                SELECT u.user_id, u.app_env, u.status, u.display_name
                FROM user_identities i
                JOIN users u ON u.user_id = i.user_id
                WHERE i.provider = 'sync_key' AND i.identifier_hash = ?1
                "#,
                params![identifier_hash],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
            )
            .optional()
            .map_err(|e| AppError::Internal(format!("query identity: {e}")))?;

        let (user_id, is_new_account) = if let Some((uid, stored_env, status, existing_display_name)) = existing {
            if status != "active" {
                return Err(AppError::Forbidden(
                    "account_disabled",
                    "该云端账号已被停用".to_string(),
                ));
            }
            if stored_env != app_env {
                return Err(AppError::Forbidden(
                    "env_mismatch",
                    "密钥所属环境与当前客户端包类型不匹配".to_string(),
                ));
            }
            tx.execute(
                "UPDATE user_identities SET last_used_at_ms = ?1, key_hint = ?2 WHERE provider = 'sync_key' AND identifier_hash = ?3",
                params![now, key_hint, identifier_hash],
            )
            .map_err(|e| AppError::Internal(format!("update identity ts: {e}")))?;

            let should_update_display_name = !clean_member.is_empty()
                && (existing_display_name.trim().is_empty()
                    || !is_default_or_device_member_label(&clean_member, &clean_device_name));
            if should_update_display_name {
                tx.execute(
                    "UPDATE users SET display_name = ?1, updated_at_ms = ?2 WHERE user_id = ?3",
                    params![clean_member, now, uid],
                )
                .map_err(|e| AppError::Internal(format!("update user name: {e}")))?;
            }
            (uid, false)
        } else {
            if max_users > 0 {
                let total_users: i64 = tx
                    .query_row("SELECT COUNT(*) FROM users", [], |row| row.get(0))
                    .map_err(|e| AppError::Internal(format!("count users: {e}")))?;
                let active_count = usize::try_from(total_users.max(0)).unwrap_or(usize::MAX);
                if active_count >= max_users {
                    return Err(AppError::Forbidden(
                        "max_users_reached",
                        format!("此云端服务器已达到最大账号绑定上限（{max_users}），请联系管理员调整 MOJI_MAX_USERS"),
                    ));
                }
            }
            authorize_new_registration()?;
            let uid = Uuid::new_v4().to_string();
            tx.execute(
                "INSERT INTO users (user_id, display_name, app_env, status, created_at_ms, updated_at_ms) VALUES (?1, ?2, ?3, 'active', ?4, ?4)",
                params![uid, clean_member, app_env, now],
            )
            .map_err(|e| AppError::Internal(format!("insert user: {e}")))?;

            tx.execute(
                "INSERT INTO user_identities (user_id, provider, identifier_hash, key_hint, created_at_ms, last_used_at_ms) VALUES (?1, 'sync_key', ?2, ?3, ?4, ?4)",
                params![uid, identifier_hash, key_hint, now],
            )
            .map_err(|e| AppError::Internal(format!("insert identity: {e}")))?;
            (uid, true)
        };

        if !clean_device_id.is_empty() {
            tx.execute(
                r#"
                INSERT INTO user_devices (device_id, user_id, device_name, member_label, app_version, last_sync_at_ms)
                VALUES (?1, ?2, ?3, ?4, ?5, ?6)
                ON CONFLICT(user_id, device_id) DO UPDATE SET
                    device_name = excluded.device_name,
                    member_label = CASE WHEN excluded.member_label != '' THEN excluded.member_label ELSE user_devices.member_label END,
                    app_version = excluded.app_version,
                    last_sync_at_ms = excluded.last_sync_at_ms
                "#,
                params![clean_device_id, user_id, clean_device_name, clean_member, clean_version, now],
            )
            .map_err(|e| AppError::Internal(format!("upsert device: {e}")))?;
        }

        let summary = query_account_summary(&tx, &user_id)?;
        tx.commit()
            .map_err(|e| AppError::Internal(format!("commit bind tx: {e}")))?;

        Ok(KeyBindOutcome {
            user_id,
            is_new_account,
            summary,
        })
    }

    pub fn verify_active_user(&self, user_id: &str, expected_env: &str) -> Result<String, AppError> {
        validate_uuid(user_id)?;
        let conn = self
            .conn
            .lock()
            .map_err(|_| AppError::Internal("sqlite mutex poisoned".to_string()))?;

        let row: Option<(String, String, String)> = conn
            .query_row(
                "SELECT display_name, app_env, status FROM users WHERE user_id = ?1",
                params![user_id],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .optional()
            .map_err(|e| AppError::Internal(format!("query user: {e}")))?;

        let Some((display_name, app_env, status)) = row else {
            return Err(AppError::Unauthorized(
                "user_not_found",
                "云端账户不存在，请重新绑定同步密钥".to_string(),
            ));
        };
        if status != "active" || app_env != expected_env {
            return Err(AppError::Forbidden(
                "user_access_denied",
                "当前凭证无权访问该环境数据".to_string(),
            ));
        }
        Ok(display_name)
    }

    pub fn get_status_details(
        &self,
        user_id: &str,
    ) -> Result<
        (
            AccountSummary,
            Vec<BoundIdentityInfo>,
            Vec<BoundDeviceInfo>,
            Vec<SnapshotHistoryItem>,
        ),
        AppError,
    > {
        validate_uuid(user_id)?;
        let conn = self
            .conn
            .lock()
            .map_err(|_| AppError::Internal("sqlite mutex poisoned".to_string()))?;

        let summary = query_account_summary(&conn, user_id)?;

        let mut id_stmt = conn
            .prepare(
                "SELECT provider, key_hint, created_at_ms, last_used_at_ms FROM user_identities WHERE user_id = ?1 ORDER BY id ASC",
            )
            .map_err(|e| AppError::Internal(format!("prepare identities: {e}")))?;
        let identities = id_stmt
            .query_map(params![user_id], |r| {
                Ok(BoundIdentityInfo {
                    provider: r.get(0)?,
                    key_hint: r.get(1)?,
                    created_at_ms: r.get(2)?,
                    last_used_at_ms: r.get(3)?,
                })
            })
            .map_err(|e| AppError::Internal(format!("map identities: {e}")))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| AppError::Internal(format!("collect identities: {e}")))?;

        let mut dev_stmt = conn
            .prepare(
                "SELECT device_id, device_name, member_label, app_version, last_sync_at_ms FROM user_devices WHERE user_id = ?1 ORDER BY last_sync_at_ms DESC LIMIT 20",
            )
            .map_err(|e| AppError::Internal(format!("prepare devices: {e}")))?;
        let devices = dev_stmt
            .query_map(params![user_id], |r| {
                Ok(BoundDeviceInfo {
                    device_id: r.get(0)?,
                    device_name: r.get(1)?,
                    member_label: r.get(2)?,
                    app_version: r.get(3)?,
                    last_sync_at_ms: r.get(4)?,
                })
            })
            .map_err(|e| AppError::Internal(format!("map devices: {e}")))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| AppError::Internal(format!("collect devices: {e}")))?;

        let mut hist_stmt = conn
            .prepare(
                "SELECT revision, snapshot_sha256, byte_size, tx_count, updated_by_device, updated_by_member, created_at_ms FROM cloud_snapshot_history WHERE user_id = ?1 ORDER BY revision DESC LIMIT ?2",
            )
            .map_err(|e| AppError::Internal(format!("prepare history: {e}")))?;
        let history = hist_stmt
            .query_map(params![user_id, self.max_history_revisions as i64], |r| {
                Ok(SnapshotHistoryItem {
                    revision: r.get(0)?,
                    snapshot_sha256: r.get(1)?,
                    byte_size: r.get::<_, i64>(2)? as usize,
                    tx_count: r.get::<_, i64>(3)? as usize,
                    updated_by_device: r.get(4)?,
                    updated_by_member: r.get(5)?,
                    created_at_ms: r.get(6)?,
                })
            })
            .map_err(|e| AppError::Internal(format!("map history: {e}")))?
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| AppError::Internal(format!("collect history: {e}")))?;

        Ok((summary, identities, devices, history))
    }

    pub fn save_snapshot(
        &self,
        user_id: &str,
        expected_revision: Option<i64>,
        force_overwrite: bool,
        device_id: &str,
        device_name: &str,
        member_label: &str,
        snapshot_json: &str,
    ) -> Result<AccountSummary, AppError> {
        validate_uuid(user_id)?;
        let raw_trimmed = snapshot_json.trim();
        if raw_trimmed.is_empty() {
            return Err(AppError::BadRequest(
                "empty_snapshot",
                "上传的账本快照内容为空".to_string(),
            ));
        }

        let probe: SnapshotProbe = serde_json::from_str(raw_trimmed).map_err(|e| {
            AppError::BadRequest(
                "invalid_snapshot_json",
                format!("账本快照 JSON 格式不合法: {e}"),
            )
        })?;
        if !probe.app_name.is_empty() && probe.app_name != "MoJi" {
            return Err(AppError::BadRequest(
                "unsupported_app_snapshot",
                "仅支持默记（MoJi）标准备份快照格式".to_string(),
            ));
        }

        let raw_bytes = raw_trimmed.as_bytes();
        let snapshot_sha256 = sha256_hex(raw_bytes);
        let wallet_count = probe.wallets.len();
        let tx_count = probe.transactions.len();
        let ledger_count = probe.ledgers.len().max(1);
        let now = now_millis();
        let exported_at = if probe.exported_at_millis > 0 {
            probe.exported_at_millis
        } else {
            now
        };
        let clean_dev_id = sanitize_short_text(device_id, 64);
        let clean_dev_name = sanitize_short_text(device_name, 64);
        let clean_member = sanitize_short_text(member_label, 48);

        let user_snap_dir = self.user_snapshot_dir(user_id)?;
        let history_dir = user_snap_dir.join("history");
        fs::create_dir_all(&history_dir)
            .map_err(|e| AppError::Internal(format!("create user history dir: {e}")))?;

        // 先在全局 SQLite 互斥锁外将新快照写入临时暂存文件并完成 fsync，避免慢盘 I/O 阻塞其他数据库请求
        let staging_path = history_dir.join(format!(".staging_{}.json.tmp", Uuid::new_v4()));
        write_staging_file(&staging_path, raw_bytes)?;

        let tx_result: Result<(AccountSummary, Vec<i64>), AppError> = (|| {
            let mut conn = self
                .conn
                .lock()
                .map_err(|_| AppError::Internal("sqlite mutex poisoned".to_string()))?;
            let tx = conn
                .transaction()
                .map_err(|e| AppError::Internal(format!("begin snapshot tx: {e}")))?;

            let current_summary = query_account_summary(&tx, user_id)?;

            // 1. 乐观并发控制：远端已有快照且非强制覆盖时，必须显式提供且匹配当前最新 revision
            if !force_overwrite && current_summary.latest_revision > 0 {
                if expected_revision != Some(current_summary.latest_revision) {
                    return Err(AppError::Conflict {
                        code: "revision_conflict",
                        message: format!(
                            "云端已有更新版本（v{}，共 {} 笔账单，由「{}」同步），请先从云端拉取恢复，或手动确认强制覆盖",
                            current_summary.latest_revision,
                            current_summary.tx_count,
                            if current_summary.updated_by_member.is_empty() {
                                &current_summary.updated_by_device
                            } else {
                                &current_summary.updated_by_member
                            }
                        ),
                        account_summary: Some(current_summary),
                    });
                }
                // 2. 空账本防误抹除守卫：远端已有账单时，禁止非强制模式写入 0 笔账单快照
                if current_summary.tx_count > 0 && tx_count == 0 {
                    return Err(AppError::Conflict {
                        code: "empty_snapshot_guard",
                        message: format!(
                            "云端当前存有 {} 笔账单，安全守卫已拦截空账本自动覆盖；如确需清空云端，请点击手动强制上传",
                            current_summary.tx_count
                        ),
                        account_summary: Some(current_summary),
                    });
                }
            }

            // 幂等短路：如果内容 SHA-256 与云端最新版本完全一致，无需重复递增版本号
            if current_summary.latest_revision > 0
                && current_summary.snapshot_sha256 == snapshot_sha256
            {
                if !clean_dev_id.is_empty() {
                    let _ = tx.execute(
                        "UPDATE user_devices SET last_sync_at_ms = ?1 WHERE user_id = ?2 AND device_id = ?3",
                        params![now, user_id, clean_dev_id],
                    );
                }
                tx.commit()
                    .map_err(|e| AppError::Internal(format!("commit idempotent tx: {e}")))?;
                return Ok((current_summary, Vec::new()));
            }

            let new_revision = current_summary.latest_revision + 1;
            let history_path = history_dir.join(format!("rev_{new_revision}.json"));

            fs::rename(&staging_path, &history_path).map_err(|e| {
                AppError::Internal(format!("rename staging to {:?} failed: {e}", history_path))
            })?;

            let db_ops = (|| -> Result<(AccountSummary, Vec<i64>), AppError> {
                tx.execute(
                    r#"
                    INSERT INTO cloud_snapshots (
                        user_id, revision, snapshot_sha256, byte_size,
                        wallet_count, tx_count, ledger_count,
                        updated_by_device, updated_by_member, exported_at_ms, updated_at_ms
                    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)
                    ON CONFLICT(user_id) DO UPDATE SET
                        revision = excluded.revision,
                        snapshot_sha256 = excluded.snapshot_sha256,
                        byte_size = excluded.byte_size,
                        wallet_count = excluded.wallet_count,
                        tx_count = excluded.tx_count,
                        ledger_count = excluded.ledger_count,
                        updated_by_device = excluded.updated_by_device,
                        updated_by_member = excluded.updated_by_member,
                        exported_at_ms = excluded.exported_at_ms,
                        updated_at_ms = excluded.updated_at_ms
                    "#,
                    params![
                        user_id,
                        new_revision,
                        snapshot_sha256,
                        raw_bytes.len() as i64,
                        wallet_count as i64,
                        tx_count as i64,
                        ledger_count as i64,
                        clean_dev_name,
                        clean_member,
                        exported_at,
                        now
                    ],
                )
                .map_err(|e| AppError::Internal(format!("upsert cloud_snapshots: {e}")))?;

                tx.execute(
                    r#"
                    INSERT INTO cloud_snapshot_history (
                        user_id, revision, snapshot_sha256, byte_size, tx_count,
                        updated_by_device, updated_by_member, created_at_ms
                    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
                    "#,
                    params![
                        user_id,
                        new_revision,
                        snapshot_sha256,
                        raw_bytes.len() as i64,
                        tx_count as i64,
                        clean_dev_name,
                        clean_member,
                        now
                    ],
                )
                .map_err(|e| AppError::Internal(format!("insert snapshot history: {e}")))?;

                if !clean_dev_id.is_empty() {
                    tx.execute(
                        r#"
                        INSERT INTO user_devices (device_id, user_id, device_name, member_label, app_version, last_sync_at_ms)
                        VALUES (?1, ?2, ?3, ?4, '', ?5)
                        ON CONFLICT(user_id, device_id) DO UPDATE SET
                            device_name = CASE WHEN excluded.device_name != '' THEN excluded.device_name ELSE user_devices.device_name END,
                            member_label = CASE WHEN excluded.member_label != '' THEN excluded.member_label ELSE user_devices.member_label END,
                            last_sync_at_ms = excluded.last_sync_at_ms
                        "#,
                        params![clean_device_id_or(&clean_dev_id), user_id, clean_dev_name, clean_member, now],
                    )
                    .map_err(|e| AppError::Internal(format!("upsert device in snapshot: {e}")))?;
                }

                // 清理超出上限的旧历史快照记录
                let stale_revs: Vec<i64> = {
                    let mut stmt = tx
                        .prepare(
                            r#"
                            SELECT revision FROM cloud_snapshot_history
                            WHERE user_id = ?1
                            ORDER BY revision DESC
                            LIMIT -1 OFFSET ?2
                            "#,
                        )
                        .map_err(|e| AppError::Internal(format!("prepare prune history: {e}")))?;
                    let rows = stmt
                        .query_map(params![user_id, self.max_history_revisions as i64], |r| {
                            r.get(0)
                        })
                        .map_err(|e| AppError::Internal(format!("map prune history: {e}")))?;
                    rows.collect::<Result<Vec<_>, _>>()
                        .map_err(|e| AppError::Internal(format!("collect prune history: {e}")))?
                };

                for old_rev in &stale_revs {
                    tx.execute(
                        "DELETE FROM cloud_snapshot_history WHERE user_id = ?1 AND revision = ?2",
                        params![user_id, old_rev],
                    )
                    .map_err(|e| AppError::Internal(format!("delete stale history rev {old_rev}: {e}")))?;
                }

                let updated_summary = query_account_summary(&tx, user_id)?;
                tx.commit()
                    .map_err(|e| AppError::Internal(format!("commit snapshot tx: {e}")))?;
                Ok((updated_summary, stale_revs))
            })();

            if db_ops.is_err() {
                // SQLite 事务回滚时清理刚重命名的孤儿新版本文件，保证既有历史文件与 DB 100% 一致
                let _ = fs::remove_file(&history_path);
            }
            db_ops
        })();

        // 确保任何提前返回（如 409 冲突或幂等短路）均清理暂存文件
        let _ = fs::remove_file(&staging_path);

        let (updated_summary, stale_revs) = tx_result?;
        for old_rev in stale_revs {
            let _ = fs::remove_file(history_dir.join(format!("rev_{old_rev}.json")));
        }

        Ok(updated_summary)
    }

    pub fn load_snapshot(
        &self,
        user_id: &str,
        requested_revision: Option<i64>,
    ) -> Result<PulledSnapshotData, AppError> {
        validate_uuid(user_id)?;
        let user_snap_dir = self.user_snapshot_dir(user_id)?;

        let (revision, expected_sha256, updated_at_ms, updated_by_device, updated_by_member, screenshots, target_path) = {
            let conn = self
                .conn
                .lock()
                .map_err(|_| AppError::Internal("sqlite mutex poisoned".to_string()))?;

            let mut ss_stmt = conn
                .prepare("SELECT sha256 FROM user_screenshots WHERE user_id = ?1")
                .map_err(|e| AppError::Internal(format!("prepare screenshots: {e}")))?;
            let available_screenshots = ss_stmt
                .query_map(params![user_id], |r| r.get::<_, String>(0))
                .map_err(|e| AppError::Internal(format!("map screenshots: {e}")))?
                .collect::<Result<Vec<_>, _>>()
                .map_err(|e| AppError::Internal(format!("collect screenshots: {e}")))?;

            if let Some(rev) = requested_revision {
                let row: Option<(i64, String, i64, String, String)> = conn
                    .query_row(
                        "SELECT revision, snapshot_sha256, created_at_ms, updated_by_device, updated_by_member FROM cloud_snapshot_history WHERE user_id = ?1 AND revision = ?2",
                        params![user_id, rev],
                        |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
                    )
                    .optional()
                    .map_err(|e| AppError::Internal(format!("query snapshot history: {e}")))?;

                let Some((r, sha, ts, dev, member)) = row else {
                    return Err(AppError::NotFound(
                        "revision_not_found",
                        format!("未找到历史快照版本 v{rev}"),
                    ));
                };
                let p = user_snap_dir.join("history").join(format!("rev_{r}.json"));
                (r, sha, ts, dev, member, available_screenshots, p)
            } else {
                let row: Option<(i64, String, i64, String, String)> = conn
                    .query_row(
                        "SELECT revision, snapshot_sha256, updated_at_ms, updated_by_device, updated_by_member FROM cloud_snapshots WHERE user_id = ?1",
                        params![user_id],
                        |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
                    )
                    .optional()
                    .map_err(|e| AppError::Internal(format!("query latest snapshot: {e}")))?;

                let Some((r, sha, ts, dev, member)) = row else {
                    return Err(AppError::NotFound(
                        "snapshot_not_found",
                        "该密钥名下尚无云端备份快照，请先上传当前账本".to_string(),
                    ));
                };
                let rev_path = user_snap_dir.join("history").join(format!("rev_{r}.json"));
                let p = if rev_path.exists() {
                    rev_path
                } else {
                    user_snap_dir.join("latest.json")
                };
                (r, sha, ts, dev, member, available_screenshots, p)
            }
        };

        let bytes = fs::read(&target_path).map_err(|e| {
            AppError::Internal(format!("读取云端快照文件 {:?} 失败: {e}", target_path))
        })?;
        let actual_sha = sha256_hex(&bytes);
        if actual_sha != expected_sha256 {
            return Err(AppError::Internal(
                "云端快照文件完整性摘要校验不匹配".to_string(),
            ));
        }
        let snapshot_json = String::from_utf8(bytes)
            .map_err(|e| AppError::Internal(format!("invalid utf8 snapshot: {e}")))?;

        Ok(PulledSnapshotData {
            revision,
            snapshot_sha256: expected_sha256,
            updated_at_ms,
            updated_by_device,
            updated_by_member,
            snapshot_json,
            available_screenshots: screenshots,
        })
    }

    pub fn find_missing_screenshots(
        &self,
        user_id: &str,
        candidate_hashes: &[String],
    ) -> Result<Vec<String>, AppError> {
        validate_uuid(user_id)?;
        if candidate_hashes.len() > MAX_MISSING_CHECK_BATCH {
            return Err(AppError::BadRequest(
                "too_many_screenshot_hashes",
                format!("单次检查原图数量超出上限（最多 {MAX_MISSING_CHECK_BATCH} 张）"),
            ));
        }

        let normalized_candidates: Vec<String> = candidate_hashes
            .iter()
            .map(|h| normalize_sha256(h))
            .collect::<Result<Vec<_>, _>>()?;

        let linked_set: HashSet<String> = {
            let conn = self
                .conn
                .lock()
                .map_err(|_| AppError::Internal("sqlite mutex poisoned".to_string()))?;
            let mut stmt = conn
                .prepare("SELECT sha256 FROM user_screenshots WHERE user_id = ?1")
                .map_err(|e| AppError::Internal(format!("prepare user screenshots set: {e}")))?;
            let rows = stmt
                .query_map(params![user_id], |r| r.get::<_, String>(0))
                .map_err(|e| AppError::Internal(format!("map user screenshots set: {e}")))?;
            rows.collect::<Result<HashSet<_>, _>>()
                .map_err(|e| AppError::Internal(format!("collect user screenshots set: {e}")))?
        };

        let mut missing = Vec::new();
        let mut seen = HashSet::with_capacity(normalized_candidates.len());
        for norm in normalized_candidates {
            if !seen.insert(norm.clone()) {
                continue;
            }
            let blob_path = self.screenshot_blob_path(&norm)?;
            if !linked_set.contains(&norm) || !blob_path.exists() {
                missing.push(norm);
            }
        }
        Ok(missing)
    }

    pub fn save_screenshot(
        &self,
        user_id: &str,
        expected_sha256_raw: &str,
        webp_bytes: &[u8],
    ) -> Result<usize, AppError> {
        validate_uuid(user_id)?;
        let expected_sha = normalize_sha256(expected_sha256_raw)?;

        if webp_bytes.len() < 16 || webp_bytes.len() > 8 * 1024 * 1024 {
            return Err(AppError::BadRequest(
                "invalid_screenshot_size",
                "账单截图文件大小不合法（单张需在 16B ~ 8MB 之间）".to_string(),
            ));
        }
        if &webp_bytes[0..4] != b"RIFF" || &webp_bytes[8..12] != b"WEBP" {
            return Err(AppError::BadRequest(
                "invalid_webp_header",
                "仅允许上传合法的 WebP 账单截图文件".to_string(),
            ));
        }

        let actual_sha = sha256_hex(webp_bytes);
        if actual_sha != expected_sha {
            return Err(AppError::BadRequest(
                "screenshot_hash_mismatch",
                "上传图片的 SHA-256 摘要与请求参数不一致".to_string(),
            ));
        }

        let blob_path = self.screenshot_blob_path(&expected_sha)?;
        if let Some(parent) = blob_path.parent() {
            fs::create_dir_all(parent)
                .map_err(|e| AppError::Internal(format!("create screenshot shard dir: {e}")))?;
        }

        // 写盘前预检单用户原图配额（同 user_id + sha256 幂等重传放行，不重复占用配额）
        {
            let conn = self
                .conn
                .lock()
                .map_err(|_| AppError::Internal("sqlite mutex poisoned".to_string()))?;
            ensure_screenshot_quota_available(
                &conn,
                user_id,
                &expected_sha,
                webp_bytes.len(),
                self.max_user_screenshot_bytes,
            )?;
        }

        let mut wrote_new_blob = false;
        if !blob_path.exists() {
            write_file_atomically(&blob_path, webp_bytes)?;
            wrote_new_blob = true;
        }

        let tx_res: Result<bool, AppError> = (|| {
            let mut conn = self
                .conn
                .lock()
                .map_err(|_| AppError::Internal("sqlite mutex poisoned".to_string()))?;
            let tx = conn
                .transaction()
                .map_err(|e| AppError::Internal(format!("begin screenshot tx: {e}")))?;
            if let Err(quota_err) = ensure_screenshot_quota_available(
                &tx,
                user_id,
                &expected_sha,
                webp_bytes.len(),
                self.max_user_screenshot_bytes,
            ) {
                let any_ref: bool = tx
                    .query_row(
                        "SELECT EXISTS(SELECT 1 FROM user_screenshots WHERE sha256 = ?1)",
                        params![expected_sha],
                        |r| r.get(0),
                    )
                    .unwrap_or(true);
                drop(tx);
                if wrote_new_blob && !any_ref {
                    let _ = fs::remove_file(&blob_path);
                }
                return Err(quota_err);
            }

            tx.execute(
                r#"
                INSERT INTO user_screenshots (user_id, sha256, byte_size, created_at_ms)
                VALUES (?1, ?2, ?3, ?4)
                ON CONFLICT(user_id, sha256) DO NOTHING
                "#,
                params![user_id, expected_sha, webp_bytes.len() as i64, now_millis()],
            )
            .map_err(|e| AppError::Internal(format!("insert user_screenshot: {e}")))?;

            tx.commit()
                .map_err(|e| AppError::Internal(format!("commit screenshot tx: {e}")))?;
            Ok(true)
        })();
        tx_res?;

        Ok(webp_bytes.len())
    }

    pub fn load_screenshot(&self, user_id: &str, sha256_raw: &str) -> Result<Vec<u8>, AppError> {
        validate_uuid(user_id)?;
        let sha = normalize_sha256(sha256_raw)?;

        let authorized: bool = {
            let conn = self
                .conn
                .lock()
                .map_err(|_| AppError::Internal("sqlite mutex poisoned".to_string()))?;
            conn.query_row(
                "SELECT EXISTS(SELECT 1 FROM user_screenshots WHERE user_id = ?1 AND sha256 = ?2)",
                params![user_id, sha],
                |r| r.get(0),
            )
            .map_err(|e| AppError::Internal(format!("verify screenshot ownership: {e}")))?
        };

        if !authorized {
            return Err(AppError::NotFound(
                "screenshot_not_found",
                "未找到该账单原图或无访问权限".to_string(),
            ));
        }

        let blob_path = self.screenshot_blob_path(&sha)?;
        if !blob_path.exists() {
            return Err(AppError::NotFound(
                "screenshot_blob_missing",
                "云端账单原图文件不存在".to_string(),
            ));
        }

        fs::read(&blob_path)
            .map_err(|e| AppError::Internal(format!("read screenshot {:?}: {e}", blob_path)))
    }

    fn user_snapshot_dir(&self, user_id: &str) -> Result<PathBuf, AppError> {
        validate_uuid(user_id)?;
        Ok(self.data_dir.join("snapshots").join(user_id))
    }

    fn screenshot_blob_path(&self, normalized_sha256: &str) -> Result<PathBuf, AppError> {
        let sha = normalize_sha256(normalized_sha256)?;
        Ok(self
            .data_dir
            .join("screenshots")
            .join(&sha[0..2])
            .join(format!("{sha}.webp")))
    }
}

fn clean_device_id_or(s: &str) -> &str {
    s
}

fn query_account_summary(conn: &Connection, user_id: &str) -> Result<AccountSummary, AppError> {
    let screenshot_count: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM user_screenshots WHERE user_id = ?1",
            params![user_id],
            |r| r.get(0),
        )
        .map_err(|e| AppError::Internal(format!("count screenshots: {e}")))?;

    let snap_row: Option<AccountSummary> = conn
        .query_row(
            r#"
            SELECT revision, wallet_count, tx_count, ledger_count, byte_size,
                   snapshot_sha256, updated_by_device, updated_by_member, updated_at_ms
            FROM cloud_snapshots
            WHERE user_id = ?1
            "#,
            params![user_id],
            |r| {
                Ok(AccountSummary {
                    latest_revision: r.get(0)?,
                    wallet_count: r.get::<_, i64>(1)? as usize,
                    tx_count: r.get::<_, i64>(2)? as usize,
                    ledger_count: r.get::<_, i64>(3)? as usize,
                    screenshot_count: screenshot_count as usize,
                    snapshot_bytes: r.get::<_, i64>(4)? as usize,
                    snapshot_sha256: r.get(5)?,
                    updated_by_device: r.get(6)?,
                    updated_by_member: r.get(7)?,
                    updated_at_ms: r.get(8)?,
                })
            },
        )
        .optional()
        .map_err(|e| AppError::Internal(format!("query cloud_snapshots: {e}")))?;

    Ok(snap_row.unwrap_or_else(|| AccountSummary {
        screenshot_count: screenshot_count as usize,
        ..AccountSummary::default()
    }))
}

fn validate_uuid(raw: &str) -> Result<(), AppError> {
    if Uuid::parse_str(raw).is_err() {
        return Err(AppError::BadRequest(
            "invalid_user_id",
            "非法用户标识格式".to_string(),
        ));
    }
    Ok(())
}

pub fn normalize_sha256(raw: &str) -> Result<String, AppError> {
    let lower = raw.trim().to_ascii_lowercase();
    if lower.len() != 64 || !lower.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err(AppError::BadRequest(
            "invalid_sha256",
            "SHA-256 哈希参数格式无效（需为 64 位十六进制字符）".to_string(),
        ));
    }
    Ok(lower)
}

fn sanitize_short_text(raw: &str, max_chars: usize) -> String {
    raw.trim()
        .chars()
        .filter(|c| !c.is_control())
        .take(max_chars)
        .collect()
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    hex::encode(hasher.finalize())
}

fn write_staging_file(staging_path: &Path, bytes: &[u8]) -> Result<(), AppError> {
    let write_res = (|| -> std::io::Result<()> {
        let mut f = File::create(staging_path)?;
        f.write_all(bytes)?;
        f.sync_all()?;
        Ok(())
    })();
    if let Err(e) = write_res {
        let _ = fs::remove_file(staging_path);
        return Err(AppError::Internal(format!(
            "write staging {:?} failed: {e}",
            staging_path
        )));
    }
    Ok(())
}

fn write_file_atomically(dest: &Path, bytes: &[u8]) -> Result<(), AppError> {
    let tmp_name = format!(
        ".{}.tmp.{}",
        dest.file_name().and_then(|n| n.to_str()).unwrap_or("blob"),
        Uuid::new_v4()
    );
    let tmp_path = dest.with_file_name(tmp_name);
    let write_res = (|| -> std::io::Result<()> {
        let mut f = File::create(&tmp_path)?;
        f.write_all(bytes)?;
        f.sync_all()?;
        fs::rename(&tmp_path, dest)?;
        Ok(())
    })();
    if let Err(e) = write_res {
        let _ = fs::remove_file(&tmp_path);
        return Err(AppError::Internal(format!(
            "atomic write {:?} failed: {e}",
            dest
        )));
    }
    Ok(())
}

fn is_default_or_device_member_label(member: &str, device_name: &str) -> bool {
    let m = member.trim();
    if m.is_empty() {
        return true;
    }
    let dev = device_name.trim();
    if !dev.is_empty() && m.eq_ignore_ascii_case(dev) {
        return true;
    }
    let lower = m.to_ascii_lowercase();
    matches!(
        lower.as_str(),
        "默记用户"
            | "我的手机"
            | "android"
            | "android 设备"
            | "android设备"
            | "未知设备"
            | "unknown"
            | "unknown device"
            | "device"
    ) || lower.starts_with("sdk_gphone")
        || lower.starts_with("emulator")
        || lower.starts_with("android ")
        || lower.starts_with("pixel ")
        || lower.starts_with("oneplus ")
        || lower.starts_with("xiaomi ")
        || lower.starts_with("redmi ")
        || lower.starts_with("huawei ")
        || lower.starts_with("honor ")
        || lower.starts_with("oppo ")
        || lower.starts_with("vivo ")
        || lower.starts_with("sm-")
        || lower.starts_with("rmx")
}

fn ensure_screenshot_quota_available(
    conn: &Connection,
    user_id: &str,
    expected_sha: &str,
    incoming_len: usize,
    max_quota_bytes: usize,
) -> Result<(), AppError> {
    let already_linked: bool = conn
        .query_row(
            "SELECT EXISTS(SELECT 1 FROM user_screenshots WHERE user_id = ?1 AND sha256 = ?2)",
            params![user_id, expected_sha],
            |r| r.get(0),
        )
        .map_err(|e| AppError::Internal(format!("check existing screenshot: {e}")))?;
    if already_linked {
        return Ok(());
    }

    let current_used_i64: i64 = conn
        .query_row(
            "SELECT COALESCE(SUM(byte_size), 0) FROM user_screenshots WHERE user_id = ?1",
            params![user_id],
            |r| r.get(0),
        )
        .map_err(|e| AppError::Internal(format!("sum user screenshot bytes: {e}")))?;
    let current_used = usize::try_from(current_used_i64.max(0)).unwrap_or(usize::MAX);
    if current_used.saturating_add(incoming_len) > max_quota_bytes {
        let quota_mb = (max_quota_bytes as f64) / (1024.0 * 1024.0);
        return Err(AppError::Forbidden(
            "screenshot_quota_exceeded",
            format!(
                "当前账号云端账单原图存储空间已达配额上限（{quota_mb:.1} MiB），请清理无用原图或联系管理员扩容"
            ),
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn make_webp(tag: &[u8]) -> Vec<u8> {
        let mut buf = Vec::new();
        buf.extend_from_slice(b"RIFF");
        buf.extend_from_slice(&(12u32 + tag.len() as u32).to_le_bytes());
        buf.extend_from_slice(b"WEBP");
        buf.extend_from_slice(b"VP8 ");
        buf.extend_from_slice(tag);
        buf
    }

    #[test]
    fn test_screenshot_per_user_quota_and_idempotent_reupload() {
        let tmp = TempDir::new().unwrap();
        let mut store = CloudStore::open(tmp.path(), 5).unwrap();
        // 设定配额上限为 60 字节（每张测试 WebP 24 字节，最多允许 2 张不同原图 = 48B，第 3 张 72B 超限）
        store.set_max_user_screenshot_bytes(60);

        let bind_a = store
            .bind_or_login_with_sync_key(
                "hash_user_a",
                "MOJI-****-AAAA",
                "prod",
                "阿豪",
                "dev-1",
                "OnePlus 13",
                "1.1.6",
                10,
                || Ok(()),
            )
            .unwrap();
        let bind_b = store
            .bind_or_login_with_sync_key(
                "hash_user_b",
                "MOJI-****-BBBB",
                "prod",
                "家人B",
                "dev-2",
                "Xiaomi 15",
                "1.1.6",
                10,
                || Ok(()),
            )
            .unwrap();

        let webp1 = make_webp(b"12345678");
        let sha1 = sha256_hex(&webp1);
        let webp2 = make_webp(b"87654321");
        let sha2 = sha256_hex(&webp2);
        let webp3 = make_webp(b"abcdefgh");
        let sha3 = sha256_hex(&webp3);

        assert_eq!(webp1.len(), 24);
        assert_eq!(store.save_screenshot(&bind_a.user_id, &sha1, &webp1).unwrap(), 24);
        assert_eq!(store.save_screenshot(&bind_a.user_id, &sha2, &webp2).unwrap(), 24);

        // 幂等重传已存在的同 sha256 原图：不重复累加配额，哪怕已用 48B + 24B > 60B 也必须成功
        assert_eq!(store.save_screenshot(&bind_a.user_id, &sha1, &webp1).unwrap(), 24);

        // 上传第 3 张不同 sha256 原图（48 + 24 = 72 > 60）：触发 screenshot_quota_exceeded 且不残留孤儿文件
        let err = store.save_screenshot(&bind_a.user_id, &sha3, &webp3).unwrap_err();
        match err {
            AppError::Forbidden(code, _) => assert_eq!(code, "screenshot_quota_exceeded"),
            other => panic!("expected Forbidden(screenshot_quota_exceeded), got {other:?}"),
        }
        assert!(!store.screenshot_blob_path(&sha3).unwrap().exists());

        // 配额按单用户独立核算：用户 B 依然可以正常上传 sha3
        assert_eq!(store.save_screenshot(&bind_b.user_id, &sha3, &webp3).unwrap(), 24);
    }

    #[test]
    fn test_bind_or_login_preserves_existing_display_name_against_empty_or_device_defaults() {
        let tmp = TempDir::new().unwrap();
        let store = CloudStore::open(tmp.path(), 5).unwrap();

        // 1. 初始注册无昵称（空字符串）
        let first = store
            .bind_or_login_with_sync_key(
                "hash_nick_test",
                "MOJI-****-1234",
                "prod",
                "",
                "dev-init",
                "Pixel 8",
                "1.1.6",
                10,
                || Ok(()),
            )
            .unwrap();
        assert_eq!(store.verify_active_user(&first.user_id, "prod").unwrap(), "");

        // 2. 原 display_name 为空时，后续登录可正常初始化昵称
        store
            .bind_or_login_with_sync_key(
                "hash_nick_test",
                "MOJI-****-1234",
                "prod",
                "阿豪",
                "dev-init",
                "Pixel 8",
                "1.1.6",
                10,
                || Ok(()),
            )
            .unwrap();
        assert_eq!(store.verify_active_user(&first.user_id, "prod").unwrap(), "阿豪");

        // 3. 换新机登录传入空昵称、默认占位（默记用户/Android 设备）或等于 device_name 的机型特征时，严禁覆盖已有云端昵称
        for (incoming_member, incoming_dev) in [
            ("", "Xiaomi 15 Pro"),
            ("默记用户", "Xiaomi 15 Pro"),
            ("Android 设备", "Xiaomi 15 Pro"),
            ("Xiaomi 15 Pro", "Xiaomi 15 Pro"),
            ("OnePlus 13", "PJZ110"),
            ("sdk_gphone64_arm64", "emu64a"),
        ] {
            store
                .bind_or_login_with_sync_key(
                    "hash_nick_test",
                    "MOJI-****-1234",
                    "prod",
                    incoming_member,
                    "dev-new-phone",
                    incoming_dev,
                    "1.1.6",
                    10,
                    || Ok(()),
                )
                .unwrap();
            assert_eq!(
                store.verify_active_user(&first.user_id, "prod").unwrap(),
                "阿豪",
                "incoming ({incoming_member:?}, {incoming_dev:?}) should not overwrite existing nickname"
            );
        }

        // 4. 用户主动设置明确的新自定义昵称时，允许正常更新
        store
            .bind_or_login_with_sync_key(
                "hash_nick_test",
                "MOJI-****-1234",
                "prod",
                "瑞豪的账本",
                "dev-new-phone",
                "Xiaomi 15 Pro",
                "1.1.6",
                10,
                || Ok(()),
            )
            .unwrap();
        assert_eq!(
            store.verify_active_user(&first.user_id, "prod").unwrap(),
            "瑞豪的账本"
        );
    }
}
