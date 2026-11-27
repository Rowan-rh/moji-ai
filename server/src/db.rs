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
const MAX_FAMILY_SYNC_PUSH_BATCH: usize = 2_000;
const DEFAULT_FAMILY_SYNC_PULL_LIMIT: usize = 500;
const MAX_FAMILY_SYNC_PULL_LIMIT: usize = 2_000;
pub const MIN_USER_AVATAR_BYTES: usize = 16;
pub const MAX_USER_AVATAR_BYTES: usize = 512 * 1024;

fn default_cny_currency() -> String {
    "CNY".to_string()
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FamilyTxMutationInput {
    pub tx_uuid: String,
    pub tx_type: String,
    #[serde(default = "default_cny_currency")]
    pub currency: String,
    #[serde(default)]
    pub amount_cents: i64,
    #[serde(default)]
    pub amount_cny_cents: i64,
    #[serde(default)]
    pub refunded_amount_cents: i64,
    #[serde(default)]
    pub category: String,
    #[serde(default)]
    pub merchant: String,
    #[serde(default)]
    pub note: String,
    #[serde(default)]
    pub tags: String,
    pub occurred_at_ms: i64,
    pub updated_at_ms: i64,
    #[serde(default)]
    pub deleted_at_ms: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FamilyTxDeltaItem {
    pub seq: i64,
    pub tx_uuid: String,
    pub owner_user_id: String,
    pub tx_type: String,
    pub currency: String,
    pub amount_cents: i64,
    pub amount_cny_cents: i64,
    pub refunded_amount_cents: i64,
    pub category: String,
    pub merchant: String,
    pub note: String,
    pub tags: String,
    pub occurred_at_ms: i64,
    pub updated_at_ms: i64,
    pub deleted_at_ms: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FamilyCircleMemberInfo {
    pub user_id: String,
    pub display_name: String,
    pub avatar_sha256: String,
    pub avatar_updated_at_ms: i64,
}

#[derive(Debug, Clone, Serialize)]
pub struct FamilySyncResult {
    pub accepted_count: usize,
    pub items: Vec<FamilyTxDeltaItem>,
    pub next_since_seq: i64,
    pub latest_seq: i64,
    pub has_more: bool,
    pub members: Vec<FamilyCircleMemberInfo>,
}

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
    #[serde(default)]
    pub avatar_sha256: String,
    #[serde(default)]
    pub avatar_updated_at_ms: i64,
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
            avatar_sha256: String::new(),
            avatar_updated_at_ms: 0,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FamilyMemberSummary {
    pub user_id: String,
    pub display_name: String,
    pub last_device_name: String,
    pub tx_count: usize,
    pub wallet_count: usize,
    pub screenshot_count: usize,
    pub latest_revision: i64,
    pub updated_at_ms: i64,
    pub has_avatar: bool,
}

#[derive(Debug, Clone)]
pub struct DeviceBoundSession {
    pub user_id: String,
    pub display_name: String,
    pub key_hint: String,
    pub is_new_account: bool,
    pub avatar_sha256: String,
    pub summary: AccountSummary,
}

#[derive(Debug, Clone)]
pub enum DeviceLoginOutcome {
    Bound(DeviceBoundSession),
    SelectionRequired {
        existing_members: Vec<FamilyMemberSummary>,
    },
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
        fs::create_dir_all(data_dir.join("avatars"))
            .map_err(|e| format!("create avatars dir: {e}"))?;

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
                avatar_sha256 TEXT NOT NULL DEFAULT '',
                avatar_updated_at_ms INTEGER NOT NULL DEFAULT 0,
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

            -- 7. 家庭共享账单增量池（按 app_env 严格物理隔离 prod/qa，仅存脱敏收支展示字段）
            CREATE TABLE IF NOT EXISTS family_transactions (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                app_env TEXT NOT NULL,
                tx_uuid TEXT NOT NULL,
                owner_user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                tx_type TEXT NOT NULL,
                currency TEXT NOT NULL DEFAULT 'CNY',
                amount_cents INTEGER NOT NULL DEFAULT 0,
                amount_cny_cents INTEGER NOT NULL DEFAULT 0,
                refunded_amount_cents INTEGER NOT NULL DEFAULT 0,
                category TEXT NOT NULL DEFAULT '',
                merchant TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                tags TEXT NOT NULL DEFAULT '',
                occurred_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                deleted_at_ms INTEGER,
                UNIQUE(app_env, tx_uuid)
            );
            CREATE INDEX IF NOT EXISTS idx_family_tx_env_seq ON family_transactions(app_env, seq);
            CREATE INDEX IF NOT EXISTS idx_family_tx_owner ON family_transactions(owner_user_id);
            "#,
        )
        .map_err(|e| format!("init sqlite schema: {e}"))?;

        // 兼容开发期早期未带 updated_by_member / avatar_* 列的存量库文件（非破坏性幂等迁移）
        let _ = conn.execute(
            "ALTER TABLE cloud_snapshot_history ADD COLUMN updated_by_member TEXT NOT NULL DEFAULT ''",
            [],
        );
        let _ = conn.execute(
            "ALTER TABLE users ADD COLUMN avatar_sha256 TEXT NOT NULL DEFAULT ''",
            [],
        );
        let _ = conn.execute(
            "ALTER TABLE users ADD COLUMN avatar_updated_at_ms INTEGER NOT NULL DEFAULT 0",
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

    /// 推送本人家庭共享账单变更（含新建/编辑/退款冲销校准/删除墓碑）并按 `since_seq` 增量拉取同环境家庭账单池与成员摘要
    pub fn sync_family_transactions(
        &self,
        caller_uid: &str,
        app_env: &str,
        member_label: &str,
        since_seq: i64,
        requested_limit: Option<usize>,
        mutations: &[FamilyTxMutationInput],
    ) -> Result<FamilySyncResult, AppError> {
        validate_uuid(caller_uid)?;
        if mutations.len() > MAX_FAMILY_SYNC_PUSH_BATCH {
            return Err(AppError::BadRequest(
                "batch_too_large",
                format!("单次家庭账单同步最多推送 {MAX_FAMILY_SYNC_PUSH_BATCH} 笔变更"),
            ));
        }

        let clean_since_seq = since_seq.max(0);
        let pull_limit = requested_limit
            .unwrap_or(DEFAULT_FAMILY_SYNC_PULL_LIMIT)
            .clamp(1, MAX_FAMILY_SYNC_PULL_LIMIT);
        let clean_member_label = sanitize_short_text(member_label, 48);
        let now = now_millis();

        let mut conn = self
            .conn
            .lock()
            .map_err(|_| AppError::Internal("sqlite mutex poisoned".to_string()))?;
        let tx = conn
            .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)
            .map_err(|e| AppError::Internal(format!("begin family sync tx: {e}")))?;

        let user_row: Option<(String, String, String)> = tx
            .query_row(
                "SELECT app_env, status, display_name FROM users WHERE user_id = ?1",
                params![caller_uid],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .optional()
            .map_err(|e| AppError::Internal(format!("query caller user: {e}")))?;
        let (stored_env, status, existing_display_name) = user_row.ok_or_else(|| {
            AppError::Unauthorized("user_not_found", "云端身份不存在或已失效".to_string())
        })?;
        if status != "active" {
            return Err(AppError::Forbidden(
                "account_disabled",
                "当前云同步账号已停用".to_string(),
            ));
        }
        if stored_env != app_env {
            return Err(AppError::Forbidden(
                "env_mismatch",
                format!("账号环境不匹配（云端为 {stored_env}，当前请求为 {app_env}）"),
            ));
        }

        if !clean_member_label.is_empty()
            && clean_member_label != existing_display_name
            && !is_default_or_device_member_label(&clean_member_label, "")
        {
            tx.execute(
                "UPDATE users SET display_name = ?1, updated_at_ms = ?2 WHERE user_id = ?3",
                params![clean_member_label, now, caller_uid],
            )
            .map_err(|e| AppError::Internal(format!("update caller display_name: {e}")))?;
        }

        let mut accepted_count = 0usize;

        for item in mutations {
            let clean_uuid = normalize_family_tx_uuid(&item.tx_uuid)?;
            let clean_type = normalize_family_tx_type(&item.tx_type)?;
            let clean_currency = normalize_currency_code(&item.currency);
            if item.amount_cents < 0 || item.amount_cny_cents < 0 || item.refunded_amount_cents < 0 {
                return Err(AppError::BadRequest(
                    "invalid_amount",
                    "账单金额或已退款金额不能为负数".to_string(),
                ));
            }
            if item.deleted_at_ms.is_none() && (item.amount_cents == 0 && item.amount_cny_cents == 0)
            {
                return Err(AppError::BadRequest(
                    "invalid_amount",
                    "有效家庭共享账单金额必须大于 0".to_string(),
                ));
            }

            let clean_cny = if item.amount_cny_cents > 0 {
                item.amount_cny_cents
            } else {
                item.amount_cents
            };
            let clean_refunded = item.refunded_amount_cents.clamp(0, clean_cny);
            let clean_category = sanitize_short_text(&item.category, 48);
            let clean_merchant = sanitize_short_text(&item.merchant, 96);
            let clean_note = sanitize_short_text(&item.note, 240);
            let clean_tags = sanitize_short_text(&item.tags, 160);
            let clean_occurred = if item.occurred_at_ms > 0 {
                item.occurred_at_ms
            } else {
                now
            };
            let clean_updated = if item.updated_at_ms > 0 {
                item.updated_at_ms
            } else {
                now
            };
            let clean_deleted = item.deleted_at_ms.map(|ts| if ts > 0 { ts } else { now });

            #[allow(clippy::type_complexity)]
            let existing: Option<(
                String,
                String,
                String,
                i64,
                i64,
                i64,
                String,
                String,
                String,
                String,
                i64,
                Option<i64>,
            )> = tx
                .query_row(
                    r#"
                    SELECT owner_user_id, tx_type, currency, amount_cents, amount_cny_cents,
                           refunded_amount_cents, category, merchant, note, tags,
                           occurred_at_ms, deleted_at_ms
                    FROM family_transactions
                    WHERE app_env = ?1 AND tx_uuid = ?2
                    "#,
                    params![app_env, clean_uuid],
                    |r| {
                        Ok((
                            r.get(0)?,
                            r.get(1)?,
                            r.get(2)?,
                            r.get(3)?,
                            r.get(4)?,
                            r.get(5)?,
                            r.get(6)?,
                            r.get(7)?,
                            r.get(8)?,
                            r.get(9)?,
                            r.get(10)?,
                            r.get(11)?,
                        ))
                    },
                )
                .optional()
                .map_err(|e| AppError::Internal(format!("query existing family tx: {e}")))?;

            if let Some((
                ref ex_owner,
                ref ex_type,
                ref ex_currency,
                ex_amt,
                ex_cny,
                ex_ref,
                ref ex_cat,
                ref ex_merch,
                ref ex_note,
                ref ex_tags,
                ex_occurred,
                ex_deleted,
            )) = existing
            {
                if ex_owner != caller_uid {
                    return Err(AppError::Forbidden(
                        "forbidden_not_tx_owner",
                        "无权修改或删除其他家庭成员创建的账单".to_string(),
                    ));
                }

                // 若客户端本地已硬删除仅上传 tx_uuid 墓碑，沿用云端已有摘要字段避免丢失展示信息
                let (
                    final_type,
                    final_currency,
                    final_amt,
                    final_cny,
                    final_ref,
                    final_cat,
                    final_merch,
                    final_note,
                    final_tags,
                    final_occurred,
                ) = if clean_deleted.is_some() && item.amount_cents == 0 && item.amount_cny_cents == 0 {
                    (
                        ex_type.clone(),
                        ex_currency.clone(),
                        ex_amt,
                        ex_cny,
                        ex_ref,
                        ex_cat.clone(),
                        ex_merch.clone(),
                        ex_note.clone(),
                        ex_tags.clone(),
                        ex_occurred,
                    )
                } else {
                    (
                        clean_type,
                        clean_currency,
                        item.amount_cents,
                        clean_cny,
                        clean_refunded,
                        clean_category,
                        clean_merchant,
                        clean_note,
                        clean_tags,
                        clean_occurred,
                    )
                };

                let unchanged = ex_type == &final_type
                    && ex_currency == &final_currency
                    && ex_amt == final_amt
                    && ex_cny == final_cny
                    && ex_ref == final_ref
                    && ex_cat == &final_cat
                    && ex_merch == &final_merch
                    && ex_note == &final_note
                    && ex_tags == &final_tags
                    && ex_occurred == final_occurred
                    && ex_deleted.is_some() == clean_deleted.is_some();

                if unchanged {
                    accepted_count += 1;
                    continue;
                }

                tx.execute(
                    "DELETE FROM family_transactions WHERE app_env = ?1 AND tx_uuid = ?2",
                    params![app_env, clean_uuid],
                )
                .map_err(|e| AppError::Internal(format!("delete old family tx for seq bump: {e}")))?;

                tx.execute(
                    r#"
                    INSERT INTO family_transactions (
                        app_env, tx_uuid, owner_user_id, tx_type, currency,
                        amount_cents, amount_cny_cents, refunded_amount_cents,
                        category, merchant, note, tags,
                        occurred_at_ms, updated_at_ms, deleted_at_ms
                    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15)
                    "#,
                    params![
                        app_env,
                        clean_uuid,
                        caller_uid,
                        final_type,
                        final_currency,
                        final_amt,
                        final_cny,
                        final_ref,
                        final_cat,
                        final_merch,
                        final_note,
                        final_tags,
                        final_occurred,
                        clean_updated,
                        clean_deleted,
                    ],
                )
                .map_err(|e| AppError::Internal(format!("reinsert updated family tx: {e}")))?;
                accepted_count += 1;
            } else {
                // 若云端原本不存在该 tx_uuid 且客户端仅上送墓碑，则幂等忽略，避免产生无意义空墓碑
                if clean_deleted.is_some() {
                    accepted_count += 1;
                    continue;
                }
                tx.execute(
                    r#"
                    INSERT INTO family_transactions (
                        app_env, tx_uuid, owner_user_id, tx_type, currency,
                        amount_cents, amount_cny_cents, refunded_amount_cents,
                        category, merchant, note, tags,
                        occurred_at_ms, updated_at_ms, deleted_at_ms
                    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, NULL)
                    "#,
                    params![
                        app_env,
                        clean_uuid,
                        caller_uid,
                        clean_type,
                        clean_currency,
                        item.amount_cents,
                        clean_cny,
                        clean_refunded,
                        clean_category,
                        clean_merchant,
                        clean_note,
                        clean_tags,
                        clean_occurred,
                        clean_updated,
                    ],
                )
                .map_err(|e| AppError::Internal(format!("insert family tx: {e}")))?;
                accepted_count += 1;
            }
        }

        let latest_seq: i64 = tx
            .query_row(
                "SELECT COALESCE(MAX(seq), 0) FROM family_transactions WHERE app_env = ?1",
                params![app_env],
                |r| r.get(0),
            )
            .map_err(|e| AppError::Internal(format!("query max family seq: {e}")))?;

        let fetch_limit = (pull_limit + 1) as i64;
        let mut items = Vec::new();
        {
            let mut stmt = tx
                .prepare(
                    r#"
                    SELECT seq, tx_uuid, owner_user_id, tx_type, currency,
                           amount_cents, amount_cny_cents, refunded_amount_cents,
                           category, merchant, note, tags,
                           occurred_at_ms, updated_at_ms, deleted_at_ms
                    FROM family_transactions
                    WHERE app_env = ?1 AND seq > ?2
                    ORDER BY seq ASC
                    LIMIT ?3
                    "#,
                )
                .map_err(|e| AppError::Internal(format!("prepare family delta query: {e}")))?;
            let rows = stmt
                .query_map(params![app_env, clean_since_seq, fetch_limit], |r| {
                    Ok(FamilyTxDeltaItem {
                        seq: r.get(0)?,
                        tx_uuid: r.get(1)?,
                        owner_user_id: r.get(2)?,
                        tx_type: r.get(3)?,
                        currency: r.get(4)?,
                        amount_cents: r.get(5)?,
                        amount_cny_cents: r.get(6)?,
                        refunded_amount_cents: r.get(7)?,
                        category: r.get(8)?,
                        merchant: r.get(9)?,
                        note: r.get(10)?,
                        tags: r.get(11)?,
                        occurred_at_ms: r.get(12)?,
                        updated_at_ms: r.get(13)?,
                        deleted_at_ms: r.get(14)?,
                    })
                })
                .map_err(|e| AppError::Internal(format!("query family delta rows: {e}")))?;
            for r in rows {
                items.push(r.map_err(|e| AppError::Internal(format!("map family delta: {e}")))?);
            }
        }

        let has_more = items.len() > pull_limit;
        if has_more {
            items.truncate(pull_limit);
        }
        let next_since_seq = if has_more {
            items
                .last()
                .map(|x| x.seq)
                .unwrap_or(clean_since_seq)
        } else {
            latest_seq.max(clean_since_seq)
        };

        let mut members = Vec::new();
        {
            let mut member_stmt = tx
                .prepare(
                    r#"
                    SELECT u.user_id,
                           u.display_name,
                           u.avatar_sha256,
                           u.avatar_updated_at_ms,
                           COALESCE(
                               (SELECT d.member_label FROM user_devices d WHERE d.user_id = u.user_id AND d.member_label != '' ORDER BY d.last_sync_at_ms DESC LIMIT 1),
                               (SELECT d.device_name FROM user_devices d WHERE d.user_id = u.user_id AND d.device_name != '' ORDER BY d.last_sync_at_ms DESC LIMIT 1),
                               ''
                           ) AS fallback_label
                    FROM users u
                    WHERE u.app_env = ?1 AND u.status = 'active'
                    ORDER BY u.created_at_ms ASC
                    "#,
                )
                .map_err(|e| AppError::Internal(format!("prepare family members: {e}")))?;
            let member_rows = member_stmt
                .query_map(params![app_env], |r| {
                    let uid: String = r.get(0)?;
                    let disp: String = r.get(1)?;
                    let av_sha: String = r.get(2)?;
                    let av_ts: i64 = r.get(3)?;
                    let fb: String = r.get(4)?;
                    let effective_name = if !disp.trim().is_empty() {
                        disp
                    } else if !fb.trim().is_empty() {
                        fb
                    } else {
                        "家庭成员".to_string()
                    };
                    Ok(FamilyCircleMemberInfo {
                        user_id: uid,
                        display_name: effective_name,
                        avatar_sha256: av_sha,
                        avatar_updated_at_ms: av_ts,
                    })
                })
                .map_err(|e| AppError::Internal(format!("query family members: {e}")))?;
            for m in member_rows {
                members.push(m.map_err(|e| AppError::Internal(format!("map family member: {e}")))?);
            }
        }

        tx.commit()
            .map_err(|e| AppError::Internal(format!("commit family sync tx: {e}")))?;

        Ok(FamilySyncResult {
            accepted_count,
            items,
            next_since_seq,
            latest_seq,
            has_more,
            members,
        })
    }

    /// 读取同一 `app_env` 环境内活跃家庭成员的自定义头像 WebP 字节流（严禁跨 `prod`/`qa` 环境读取）
    pub fn load_family_member_avatar(
        &self,
        caller_uid: &str,
        caller_env: &str,
        target_uid: &str,
    ) -> Result<Vec<u8>, AppError> {
        self.verify_active_user(caller_uid, caller_env)?;
        validate_uuid(target_uid)?;

        let target_row: Option<(String, String, String)> = {
            let conn = self
                .conn
                .lock()
                .map_err(|_| AppError::Internal("sqlite mutex poisoned".to_string()))?;
            conn.query_row(
                "SELECT app_env, status, avatar_sha256 FROM users WHERE user_id = ?1",
                params![target_uid],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .optional()
            .map_err(|e| AppError::Internal(format!("query target member avatar: {e}")))?
        };

        let (target_env, target_status, avatar_sha) = target_row.ok_or_else(|| {
            AppError::NotFound("member_not_found", "目标家庭成员不存在".to_string())
        })?;
        if target_status != "active" {
            return Err(AppError::Forbidden(
                "account_disabled",
                "目标家庭成员账号已停用".to_string(),
            ));
        }
        if target_env != caller_env {
            return Err(AppError::Forbidden(
                "env_mismatch",
                "禁止跨应用环境读取家庭成员头像".to_string(),
            ));
        }
        if avatar_sha.trim().is_empty() {
            return Err(AppError::NotFound(
                "avatar_not_found",
                "该家庭成员尚未设置自定义头像".to_string(),
            ));
        }

        let blob_path = self.data_dir.join("avatars").join(format!("{target_uid}.webp"));
        if !blob_path.exists() {
            return Err(AppError::NotFound(
                "avatar_not_found",
                "该家庭成员头像文件不存在".to_string(),
            ));
        }
        fs::read(&blob_path)
            .map_err(|e| AppError::Internal(format!("read member avatar {:?}: {e}", blob_path)))
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

    pub fn device_login_or_probe<F>(
        &self,
        identifier_hash: &str,
        fp_hint: &str,
        app_env: &str,
        member_label: &str,
        device_id: &str,
        device_name: &str,
        app_version: &str,
        create_if_missing: bool,
        max_users: usize,
        authorize_new_registration: F,
    ) -> Result<DeviceLoginOutcome, AppError>
    where
        F: FnOnce() -> Result<(), AppError>,
    {
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
            .map_err(|e| AppError::Internal(format!("begin device_login tx: {e}")))?;

        let existing: Option<(String, String, String, String)> = tx
            .query_row(
                r#"
                SELECT u.user_id, u.app_env, u.status, u.display_name
                FROM user_identities i
                JOIN users u ON u.user_id = i.user_id
                WHERE i.provider = 'device_fp' AND i.identifier_hash = ?1
                "#,
                params![identifier_hash],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
            )
            .optional()
            .map_err(|e| AppError::Internal(format!("query device_fp identity: {e}")))?;

        if let Some((uid, stored_env, status, existing_display_name)) = existing {
            if status != "active" {
                return Err(AppError::Forbidden(
                    "account_disabled",
                    "该云端账号已被停用".to_string(),
                ));
            }
            if stored_env != app_env {
                return Err(AppError::Forbidden(
                    "env_mismatch",
                    "设备指纹所属环境与当前客户端包类型不匹配".to_string(),
                ));
            }

            tx.execute(
                "UPDATE user_identities SET last_used_at_ms = ?1, key_hint = ?2 WHERE provider = 'device_fp' AND identifier_hash = ?3",
                params![now, fp_hint, identifier_hash],
            )
            .map_err(|e| AppError::Internal(format!("update device_fp ts: {e}")))?;

            let should_update_display_name = !clean_member.is_empty()
                && (existing_display_name.trim().is_empty()
                    || !is_default_or_device_member_label(&clean_member, &clean_device_name));
            let final_display_name = if should_update_display_name {
                tx.execute(
                    "UPDATE users SET display_name = ?1, updated_at_ms = ?2 WHERE user_id = ?3",
                    params![clean_member, now, uid],
                )
                .map_err(|e| AppError::Internal(format!("update user name: {e}")))?;
                clean_member.clone()
            } else {
                existing_display_name
            };

            upsert_user_device(
                &tx,
                &uid,
                &clean_device_id,
                &clean_device_name,
                &clean_member,
                &clean_version,
                now,
            )?;

            let summary = query_account_summary(&tx, &uid)?;
            let avatar_sha256 = summary.avatar_sha256.clone();
            tx.commit()
                .map_err(|e| AppError::Internal(format!("commit device_login bound tx: {e}")))?;

            return Ok(DeviceLoginOutcome::Bound(DeviceBoundSession {
                user_id: uid,
                display_name: final_display_name,
                key_hint: fp_hint.to_string(),
                is_new_account: false,
                avatar_sha256,
                summary,
            }));
        }

        // 当前指纹尚未绑定：检查同环境下是否已有活跃家庭成员
        let active_in_env: i64 = tx
            .query_row(
                "SELECT COUNT(*) FROM users WHERE app_env = ?1 AND status = 'active'",
                params![app_env],
                |r| r.get(0),
            )
            .map_err(|e| AppError::Internal(format!("count active users in env: {e}")))?;

        if active_in_env > 0 && !create_if_missing {
            let existing_members = query_family_members(&tx, app_env)?;
            return Ok(DeviceLoginOutcome::SelectionRequired { existing_members });
        }

        if max_users > 0 {
            let total_users: i64 = tx
                .query_row("SELECT COUNT(*) FROM users", [], |row| row.get(0))
                .map_err(|e| AppError::Internal(format!("count users: {e}")))?;
            let active_count = usize::try_from(total_users.max(0)).unwrap_or(usize::MAX);
            if active_count >= max_users {
                return Err(AppError::Forbidden(
                    "max_users_reached",
                    format!(
                        "此云端服务器已达到最大账号绑定上限（{max_users}），请联系管理员调整 MOJI_MAX_USERS"
                    ),
                ));
            }
        }

        authorize_new_registration()?;
        let uid = Uuid::new_v4().to_string();
        let initial_name = if is_default_or_device_member_label(&clean_member, &clean_device_name) {
            clean_member.clone()
        } else {
            clean_member.clone()
        };

        tx.execute(
            "INSERT INTO users (user_id, display_name, app_env, status, created_at_ms, updated_at_ms) VALUES (?1, ?2, ?3, 'active', ?4, ?4)",
            params![uid, initial_name, app_env, now],
        )
        .map_err(|e| AppError::Internal(format!("insert user: {e}")))?;

        tx.execute(
            "INSERT INTO user_identities (user_id, provider, identifier_hash, key_hint, created_at_ms, last_used_at_ms) VALUES (?1, 'device_fp', ?2, ?3, ?4, ?4)",
            params![uid, identifier_hash, fp_hint, now],
        )
        .map_err(|e| AppError::Internal(format!("insert device_fp identity: {e}")))?;

        upsert_user_device(
            &tx,
            &uid,
            &clean_device_id,
            &clean_device_name,
            &clean_member,
            &clean_version,
            now,
        )?;

        let summary = query_account_summary(&tx, &uid)?;
        let avatar_sha256 = summary.avatar_sha256.clone();
        tx.commit()
            .map_err(|e| AppError::Internal(format!("commit device_login new tx: {e}")))?;

        Ok(DeviceLoginOutcome::Bound(DeviceBoundSession {
            user_id: uid,
            display_name: initial_name,
            key_hint: fp_hint.to_string(),
            is_new_account: true,
            avatar_sha256,
            summary,
        }))
    }

    pub fn claim_family_member(
        &self,
        target_user_id: &str,
        identifier_hash: &str,
        fp_hint: &str,
        app_env: &str,
        member_label: &str,
        device_id: &str,
        device_name: &str,
        app_version: &str,
    ) -> Result<DeviceBoundSession, AppError> {
        validate_uuid(target_user_id)?;
        let now = now_millis();
        let clean_member = sanitize_short_text(member_label, 48);
        let clean_device_id = sanitize_short_text(device_id, 64);
        let clean_device_name = sanitize_short_text(device_name, 64);
        let clean_version = sanitize_short_text(app_version, 32);

        let mut orphan_empty_uid_to_cleanup: Option<String> = None;

        let session = {
            let mut conn = self
                .conn
                .lock()
                .map_err(|_| AppError::Internal("sqlite mutex poisoned".to_string()))?;
            let tx = conn
                .transaction()
                .map_err(|e| AppError::Internal(format!("begin claim_member tx: {e}")))?;

            let target_row: Option<(String, String, String)> = tx
                .query_row(
                    "SELECT display_name, app_env, status FROM users WHERE user_id = ?1",
                    params![target_user_id],
                    |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
                )
                .optional()
                .map_err(|e| AppError::Internal(format!("query target member: {e}")))?;

            let Some((existing_display_name, target_env, target_status)) = target_row else {
                return Err(AppError::NotFound(
                    "target_member_not_found",
                    "未找到要认领的家庭成员账号，请刷新列表后重试".to_string(),
                ));
            };

            if target_status != "active" {
                return Err(AppError::Forbidden(
                    "account_disabled",
                    "该家庭成员云端账号已被停用".to_string(),
                ));
            }
            if target_env != app_env {
                return Err(AppError::Forbidden(
                    "env_mismatch",
                    "目标成员所属环境与当前客户端包类型不匹配".to_string(),
                ));
            }

            let bound_uid_opt: Option<String> = tx
                .query_row(
                    "SELECT user_id FROM user_identities WHERE provider = 'device_fp' AND identifier_hash = ?1",
                    params![identifier_hash],
                    |r| r.get(0),
                )
                .optional()
                .map_err(|e| AppError::Internal(format!("query existing fp binding: {e}")))?;

            match bound_uid_opt {
                None => {
                    tx.execute(
                        "INSERT INTO user_identities (user_id, provider, identifier_hash, key_hint, created_at_ms, last_used_at_ms) VALUES (?1, 'device_fp', ?2, ?3, ?4, ?4)",
                        params![target_user_id, identifier_hash, fp_hint, now],
                    )
                    .map_err(|e| AppError::Internal(format!("insert claimed device_fp: {e}")))?;
                }
                Some(ref current_uid) if current_uid == target_user_id => {
                    tx.execute(
                        "UPDATE user_identities SET key_hint = ?1, last_used_at_ms = ?2 WHERE provider = 'device_fp' AND identifier_hash = ?3",
                        params![fp_hint, now, identifier_hash],
                    )
                    .map_err(|e| AppError::Internal(format!("update claimed device_fp: {e}")))?;
                }
                Some(other_uid) => {
                    let other_has_data: bool = tx
                        .query_row(
                            r#"
                            SELECT EXISTS(SELECT 1 FROM cloud_snapshots WHERE user_id = ?1)
                                OR EXISTS(SELECT 1 FROM cloud_snapshot_history WHERE user_id = ?1)
                                OR EXISTS(SELECT 1 FROM user_screenshots WHERE user_id = ?1)
                                OR EXISTS(SELECT 1 FROM users WHERE user_id = ?1 AND avatar_sha256 != '')
                            "#,
                            params![other_uid],
                            |r| r.get(0),
                        )
                        .map_err(|e| AppError::Internal(format!("check other_uid data: {e}")))?;

                    if other_has_data {
                        return Err(AppError::Conflict {
                            code: "device_already_bound_with_data",
                            message: "当前手机已绑定另一个含云端数据的成员账号，为防误丢数据无法直接改绑；请先在原账号解绑设备或新建独立账本".to_string(),
                            account_summary: None,
                        });
                    }

                    // 此前仅误绑过 0 笔快照的临时空账号：安全改绑到目标成员并清理孤儿临时空号
                    tx.execute(
                        "UPDATE user_identities SET user_id = ?1, key_hint = ?2, last_used_at_ms = ?3 WHERE provider = 'device_fp' AND identifier_hash = ?4",
                        params![target_user_id, fp_hint, now, identifier_hash],
                    )
                    .map_err(|e| AppError::Internal(format!("rebind empty temp device_fp: {e}")))?;

                    let remaining_identities: i64 = tx
                        .query_row(
                            "SELECT COUNT(*) FROM user_identities WHERE user_id = ?1",
                            params![other_uid],
                            |r| r.get(0),
                        )
                        .map_err(|e| AppError::Internal(format!("count other identities: {e}")))?;
                    if remaining_identities == 0 {
                        tx.execute("DELETE FROM users WHERE user_id = ?1", params![other_uid])
                            .map_err(|e| AppError::Internal(format!("delete empty temp user: {e}")))?;
                        orphan_empty_uid_to_cleanup = Some(other_uid);
                    }
                }
            }

            // 认领接管时保留原成员已有的自定义昵称；仅当原昵称为空且新设备传入了非默认昵称时补齐
            let final_display_name = if existing_display_name.trim().is_empty()
                && !clean_member.is_empty()
                && !is_default_or_device_member_label(&clean_member, &clean_device_name)
            {
                tx.execute(
                    "UPDATE users SET display_name = ?1, updated_at_ms = ?2 WHERE user_id = ?3",
                    params![clean_member, now, target_user_id],
                )
                .map_err(|e| AppError::Internal(format!("fill claimed member name: {e}")))?;
                clean_member.clone()
            } else {
                existing_display_name
            };

            let member_for_device = if !final_display_name.trim().is_empty() {
                final_display_name.as_str()
            } else {
                clean_member.as_str()
            };
            upsert_user_device(
                &tx,
                target_user_id,
                &clean_device_id,
                &clean_device_name,
                member_for_device,
                &clean_version,
                now,
            )?;

            let summary = query_account_summary(&tx, target_user_id)?;
            let avatar_sha256 = summary.avatar_sha256.clone();
            tx.commit()
                .map_err(|e| AppError::Internal(format!("commit claim_member tx: {e}")))?;

            DeviceBoundSession {
                user_id: target_user_id.to_string(),
                display_name: final_display_name,
                key_hint: fp_hint.to_string(),
                is_new_account: false,
                avatar_sha256,
                summary,
            }
        };

        if let Some(orphan_uid) = orphan_empty_uid_to_cleanup {
            if let Ok(snap_dir) = self.user_snapshot_dir(&orphan_uid) {
                let _ = fs::remove_dir_all(snap_dir);
            }
            if let Ok(avatar_path) = self.user_avatar_path(&orphan_uid) {
                let _ = fs::remove_file(avatar_path);
            }
        }

        Ok(session)
    }

    pub fn unbind_device_fingerprint(
        &self,
        user_id: &str,
        identifier_hash: &str,
    ) -> Result<bool, AppError> {
        validate_uuid(user_id)?;
        let conn = self
            .conn
            .lock()
            .map_err(|_| AppError::Internal("sqlite mutex poisoned".to_string()))?;
        let affected = conn
            .execute(
                "DELETE FROM user_identities WHERE user_id = ?1 AND provider = 'device_fp' AND identifier_hash = ?2",
                params![user_id, identifier_hash],
            )
            .map_err(|e| AppError::Internal(format!("delete device_fp binding: {e}")))?;
        Ok(affected > 0)
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
                if !clean_member.is_empty()
                    && !is_default_or_device_member_label(&clean_member, &clean_dev_name)
                {
                    let _ = tx.execute(
                        "UPDATE users SET display_name = ?1, updated_at_ms = ?2 WHERE user_id = ?3",
                        params![clean_member, now, user_id],
                    );
                }

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

                if !clean_member.is_empty()
                    && !is_default_or_device_member_label(&clean_member, &clean_dev_name)
                {
                    let _ = tx.execute(
                        "UPDATE users SET display_name = ?1, updated_at_ms = ?2 WHERE user_id = ?3",
                        params![clean_member, now, user_id],
                    );
                }

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

    pub fn save_user_avatar(
        &self,
        user_id: &str,
        webp_bytes: &[u8],
    ) -> Result<(String, usize, i64), AppError> {
        validate_uuid(user_id)?;
        if webp_bytes.len() < MIN_USER_AVATAR_BYTES {
            return Err(AppError::BadRequest(
                "avatar_too_small",
                "自定义头像文件过小（需至少 16 字节合法 WebP 图像）".to_string(),
            ));
        }
        if webp_bytes.len() > MAX_USER_AVATAR_BYTES {
            return Err(AppError::BadRequest(
                "avatar_too_large",
                "自定义头像文件超过上限（最大允许 512 KB WebP 图像）".to_string(),
            ));
        }
        if &webp_bytes[0..4] != b"RIFF" || &webp_bytes[8..12] != b"WEBP" {
            return Err(AppError::BadRequest(
                "invalid_avatar_webp_format",
                "自定义头像仅支持标准 WebP 图像格式（RIFF....WEBP）".to_string(),
            ));
        }

        let avatar_sha = sha256_hex(webp_bytes);
        let avatar_path = self.user_avatar_path(user_id)?;
        if let Some(parent) = avatar_path.parent() {
            fs::create_dir_all(parent)
                .map_err(|e| AppError::Internal(format!("create avatars dir: {e}")))?;
        }
        write_file_atomically(&avatar_path, webp_bytes)?;

        let now = now_millis();
        let conn = self
            .conn
            .lock()
            .map_err(|_| AppError::Internal("sqlite mutex poisoned".to_string()))?;
        conn.execute(
            "UPDATE users SET avatar_sha256 = ?1, avatar_updated_at_ms = ?2, updated_at_ms = ?2 WHERE user_id = ?3",
            params![avatar_sha, now, user_id],
        )
        .map_err(|e| AppError::Internal(format!("update user avatar_sha256: {e}")))?;

        Ok((avatar_sha, webp_bytes.len(), now))
    }

    pub fn load_user_avatar(&self, user_id: &str) -> Result<Vec<u8>, AppError> {
        validate_uuid(user_id)?;
        let expected_sha: String = {
            let conn = self
                .conn
                .lock()
                .map_err(|_| AppError::Internal("sqlite mutex poisoned".to_string()))?;
            conn.query_row(
                "SELECT avatar_sha256 FROM users WHERE user_id = ?1",
                params![user_id],
                |r| r.get(0),
            )
            .optional()
            .map_err(|e| AppError::Internal(format!("query user avatar_sha256: {e}")))?
            .unwrap_or_default()
        };

        if expected_sha.trim().is_empty() {
            return Err(AppError::NotFound(
                "avatar_not_found",
                "当前账号尚未设置云端自定义头像".to_string(),
            ));
        }

        let avatar_path = self.user_avatar_path(user_id)?;
        if !avatar_path.exists() {
            return Err(AppError::NotFound(
                "avatar_blob_missing",
                "云端自定义头像文件不存在".to_string(),
            ));
        }

        fs::read(&avatar_path)
            .map_err(|e| AppError::Internal(format!("read avatar {:?}: {e}", avatar_path)))
    }

    pub fn delete_user_avatar(&self, user_id: &str) -> Result<i64, AppError> {
        validate_uuid(user_id)?;
        let now = now_millis();
        {
            let conn = self
                .conn
                .lock()
                .map_err(|_| AppError::Internal("sqlite mutex poisoned".to_string()))?;
            conn.execute(
                "UPDATE users SET avatar_sha256 = '', avatar_updated_at_ms = ?1, updated_at_ms = ?1 WHERE user_id = ?2",
                params![now, user_id],
            )
            .map_err(|e| AppError::Internal(format!("clear user avatar_sha256: {e}")))?;
        }
        let avatar_path = self.user_avatar_path(user_id)?;
        if avatar_path.exists() {
            let _ = fs::remove_file(&avatar_path);
        }
        Ok(now)
    }

    fn user_avatar_path(&self, user_id: &str) -> Result<PathBuf, AppError> {
        validate_uuid(user_id)?;
        Ok(self.data_dir.join("avatars").join(format!("{user_id}.webp")))
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

fn upsert_user_device(
    conn: &Connection,
    user_id: &str,
    clean_device_id: &str,
    clean_device_name: &str,
    clean_member: &str,
    clean_version: &str,
    now: i64,
) -> Result<(), AppError> {
    if clean_device_id.is_empty() {
        return Ok(());
    }
    conn.execute(
        r#"
        INSERT INTO user_devices (device_id, user_id, device_name, member_label, app_version, last_sync_at_ms)
        VALUES (?1, ?2, ?3, ?4, ?5, ?6)
        ON CONFLICT(user_id, device_id) DO UPDATE SET
            device_name = CASE WHEN excluded.device_name != '' THEN excluded.device_name ELSE user_devices.device_name END,
            member_label = CASE WHEN excluded.member_label != '' THEN excluded.member_label ELSE user_devices.member_label END,
            app_version = CASE WHEN excluded.app_version != '' THEN excluded.app_version ELSE user_devices.app_version END,
            last_sync_at_ms = excluded.last_sync_at_ms
        "#,
        params![
            clean_device_id,
            user_id,
            clean_device_name,
            clean_member,
            clean_version,
            now
        ],
    )
    .map_err(|e| AppError::Internal(format!("upsert device: {e}")))?;
    Ok(())
}

fn query_family_members(
    conn: &Connection,
    app_env: &str,
) -> Result<Vec<FamilyMemberSummary>, AppError> {
    let mut stmt = conn
        .prepare(
            r#"
            SELECT
                u.user_id,
                u.display_name,
                u.avatar_sha256,
                u.updated_at_ms,
                COALESCE(s.revision, 0) AS latest_revision,
                COALESCE(s.tx_count, 0) AS tx_count,
                COALESCE(s.wallet_count, 0) AS wallet_count,
                COALESCE(s.updated_by_device, '') AS snap_device_name,
                COALESCE(s.updated_by_member, '') AS snap_member_name,
                COALESCE(s.updated_at_ms, 0) AS snap_updated_at_ms,
                (SELECT COUNT(*) FROM user_screenshots sc WHERE sc.user_id = u.user_id) AS screenshot_count,
                COALESCE(
                    (SELECT d.device_name FROM user_devices d WHERE d.user_id = u.user_id AND d.device_name != '' ORDER BY d.last_sync_at_ms DESC LIMIT 1),
                    ''
                ) AS recent_device_name,
                COALESCE(
                    (SELECT MAX(d2.last_sync_at_ms) FROM user_devices d2 WHERE d2.user_id = u.user_id),
                    0
                ) AS recent_device_sync_ms
            FROM users u
            LEFT JOIN cloud_snapshots s ON s.user_id = u.user_id
            WHERE u.app_env = ?1 AND u.status = 'active'
            ORDER BY COALESCE(s.updated_at_ms, u.updated_at_ms) DESC, u.created_at_ms ASC
            "#,
        )
        .map_err(|e| AppError::Internal(format!("prepare query_family_members: {e}")))?;

    let rows = stmt
        .query_map(params![app_env], |r| {
            let user_id: String = r.get(0)?;
            let raw_display_name: String = r.get(1)?;
            let avatar_sha256: String = r.get(2)?;
            let user_updated_ms: i64 = r.get(3)?;
            let latest_revision: i64 = r.get(4)?;
            let tx_count: i64 = r.get(5)?;
            let wallet_count: i64 = r.get(6)?;
            let snap_device_name: String = r.get(7)?;
            let snap_member_name: String = r.get(8)?;
            let snap_updated_ms: i64 = r.get(9)?;
            let screenshot_count: i64 = r.get(10)?;
            let recent_device_name: String = r.get(11)?;
            let recent_device_sync_ms: i64 = r.get(12)?;

            let display_name = if !raw_display_name.trim().is_empty() {
                raw_display_name
            } else if !snap_member_name.trim().is_empty() {
                snap_member_name
            } else {
                "家人成员".to_string()
            };

            let last_device_name = if !snap_device_name.trim().is_empty() {
                snap_device_name
            } else {
                recent_device_name
            };

            let updated_at_ms = user_updated_ms
                .max(snap_updated_ms)
                .max(recent_device_sync_ms);

            Ok(FamilyMemberSummary {
                user_id,
                display_name,
                last_device_name,
                tx_count: usize::try_from(tx_count.max(0)).unwrap_or(0),
                wallet_count: usize::try_from(wallet_count.max(0)).unwrap_or(0),
                screenshot_count: usize::try_from(screenshot_count.max(0)).unwrap_or(0),
                latest_revision,
                updated_at_ms,
                has_avatar: !avatar_sha256.trim().is_empty(),
            })
        })
        .map_err(|e| AppError::Internal(format!("map query_family_members: {e}")))?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| AppError::Internal(format!("collect query_family_members: {e}")))?;

    Ok(rows)
}

fn query_account_summary(conn: &Connection, user_id: &str) -> Result<AccountSummary, AppError> {
    let screenshot_count: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM user_screenshots WHERE user_id = ?1",
            params![user_id],
            |r| r.get(0),
        )
        .map_err(|e| AppError::Internal(format!("count screenshots: {e}")))?;

    let (avatar_sha256, avatar_updated_at_ms): (String, i64) = conn
        .query_row(
            "SELECT avatar_sha256, avatar_updated_at_ms FROM users WHERE user_id = ?1",
            params![user_id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()
        .map_err(|e| AppError::Internal(format!("query user avatar meta: {e}")))?
        .unwrap_or_default();

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
                    avatar_sha256: avatar_sha256.clone(),
                    avatar_updated_at_ms,
                })
            },
        )
        .optional()
        .map_err(|e| AppError::Internal(format!("query cloud_snapshots: {e}")))?;

    Ok(snap_row.unwrap_or_else(|| AccountSummary {
        screenshot_count: screenshot_count as usize,
        avatar_sha256,
        avatar_updated_at_ms,
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

fn normalize_family_tx_uuid(raw: &str) -> Result<String, AppError> {
    let trimmed = raw.trim();
    if trimmed.is_empty()
        || trimmed.len() > 64
        || !trimmed
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
    {
        return Err(AppError::BadRequest(
            "invalid_tx_uuid",
            "非法家庭账单标识 tx_uuid（需为 1~64 位字母数字/连字符）".to_string(),
        ));
    }
    Ok(trimmed.to_ascii_lowercase())
}

fn normalize_family_tx_type(raw: &str) -> Result<String, AppError> {
    let upper = raw.trim().to_ascii_uppercase();
    match upper.as_str() {
        "EXPENSE" | "INCOME" => Ok(upper),
        _ => Err(AppError::BadRequest(
            "invalid_tx_type",
            "家庭账单仅允许同步日常支出（EXPENSE）或日常收入（INCOME）".to_string(),
        )),
    }
}

fn normalize_currency_code(raw: &str) -> String {
    let cleaned: String = raw
        .trim()
        .chars()
        .filter(|c| c.is_ascii_alphabetic())
        .take(8)
        .collect();
    if cleaned.is_empty() {
        "CNY".to_string()
    } else {
        cleaned.to_ascii_uppercase()
    }
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
