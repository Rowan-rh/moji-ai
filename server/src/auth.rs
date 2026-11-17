use axum::http::HeaderMap;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use hmac::{Hmac, Mac};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};
use subtle::ConstantTimeEq;

use crate::error::AppError;

type HmacSha256 = Hmac<Sha256>;

/// 默认签发 30 天有效期的访问令牌，客户端在过期时会自动静默重签
pub const TOKEN_TTL_MS: i64 = 30 * 24 * 60 * 60 * 1000;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TokenClaims {
    pub v: u8,
    pub uid: String,
    pub env: String,
    pub dev: String,
    pub iat: i64,
    pub exp: i64,
}

pub fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// 校验并规范化应用运行环境（严格隔离正式版 prod 与测试包 qa）
pub fn normalize_app_env(raw: &str) -> Result<String, AppError> {
    let env = raw.trim().to_ascii_lowercase();
    match env.as_str() {
        "prod" | "qa" => Ok(env),
        _ => Err(AppError::BadRequest(
            "invalid_app_env",
            "无效的应用环境标识（仅允许 prod 或 qa）".to_string(),
        )),
    }
}

/// 规范化并校验客户端生成的专属同步密钥：
/// - 正式版格式：`MOJI-XXXX-XXXX-XXXX-XXXX`（允许 4~8 组，每组 4 位大写字母数字）
/// - QA 版格式：`MOJIQA-XXXX-XXXX-XXXX-XXXX`
pub fn normalize_and_validate_sync_key(raw_key: &str, app_env: &str) -> Result<String, AppError> {
    let cleaned = raw_key.trim().to_ascii_uppercase();
    let expected_prefix = if app_env == "qa" { "MOJIQA-" } else { "MOJI-" };

    if !cleaned.starts_with(expected_prefix) {
        let hint = if app_env == "qa" {
            "QA 测试包必须使用 MOJIQA- 开头的测试专属密钥，禁止绑定正式版账本密钥"
        } else {
            "正式版同步密钥必须以 MOJI- 开头（例如 MOJI-8F3K-92LP-7X4Q-2M9N）"
        };
        return Err(AppError::BadRequest("invalid_sync_key_prefix", hint.to_string()));
    }

    let body = &cleaned[expected_prefix.len()..];
    let groups: Vec<&str> = body.split('-').collect();
    if groups.len() < 4 || groups.len() > 8 {
        return Err(AppError::BadRequest(
            "invalid_sync_key_format",
            "同步密钥格式不合法：需要至少 4 组 4 位字母/数字（如 MOJI-XXXX-XXXX-XXXX-XXXX）".to_string(),
        ));
    }

    for g in &groups {
        if g.len() != 4 || !g.chars().all(|c| c.is_ascii_uppercase() || c.is_ascii_digit()) {
            return Err(AppError::BadRequest(
                "invalid_sync_key_chars",
                "同步密钥每段必须为 4 位字母或数字".to_string(),
            ));
        }
    }

    Ok(cleaned)
}

/// 生成脱敏提示文本（如 `MOJI-8F3K-****-2M9N`），避免在接口或日志中回显完整密钥
pub fn mask_sync_key(normalized_key: &str) -> String {
    let parts: Vec<&str> = normalized_key.split('-').collect();
    if parts.len() >= 5 {
        format!("{}-{}-****-{}", parts[0], parts[1], parts[parts.len() - 1])
    } else {
        "MOJI-****".to_string()
    }
}

/// 计算入库存储的单向 SHA-256 身份哈希（数据库绝不明文存储原始 Sync Key）
pub fn hash_sync_key(normalized_key: &str, app_env: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(b"moji-sync-key-v1:");
    hasher.update(app_env.as_bytes());
    hasher.update(b":");
    hasher.update(normalized_key.as_bytes());
    hex::encode(hasher.finalize())
}

/// 常量时间校验服务端接入码，防止时序侧信道探测
pub fn verify_access_code(configured: Option<&str>, provided: Option<&str>) -> Result<(), AppError> {
    let Some(expected) = configured else {
        return Ok(());
    };
    let actual = provided.unwrap_or("").trim();
    if actual.is_empty() {
        return Err(AppError::Forbidden(
            "access_code_required",
            "此云端服务已启用家庭接入保护，请填写正确的「服务器接入码」".to_string(),
        ));
    }
    let eq: bool = expected.as_bytes().ct_eq(actual.as_bytes()).into();
    if !eq {
        return Err(AppError::Forbidden(
            "invalid_access_code",
            "服务器接入码不正确，请核对后重试".to_string(),
        ));
    }
    Ok(())
}

/// 签发与具体登录方式解耦的内部 `user_id` Bearer Token（后续增加微信/手机登录直接共用此套签发机制）
pub fn issue_access_token(
    secret: &[u8],
    user_id: &str,
    app_env: &str,
    device_id: &str,
) -> Result<(String, i64), AppError> {
    let iat = now_millis();
    let exp = iat + TOKEN_TTL_MS;
    let claims = TokenClaims {
        v: 1,
        uid: user_id.to_string(),
        env: app_env.to_string(),
        dev: device_id.to_string(),
        iat,
        exp,
    };
    let payload_bytes = serde_json::to_vec(&claims)
        .map_err(|e| AppError::Internal(format!("serialize token claims: {e}")))?;
    let payload_b64 = URL_SAFE_NO_PAD.encode(&payload_bytes);

    let mut mac = HmacSha256::new_from_slice(secret)
        .map_err(|e| AppError::Internal(format!("hmac init: {e}")))?;
    mac.update(payload_b64.as_bytes());
    let sig_b64 = URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes());

    Ok((format!("v1.{payload_b64}.{sig_b64}"), exp))
}

pub fn verify_bearer_header(headers: &HeaderMap, secret: &[u8]) -> Result<TokenClaims, AppError> {
    let raw_auth = headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .ok_or_else(|| {
            AppError::Unauthorized("missing_token", "缺少云端访问凭证，请先验证绑定密钥".to_string())
        })?;

    let token = raw_auth
        .strip_prefix("Bearer ")
        .or_else(|| raw_auth.strip_prefix("bearer "))
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| {
            AppError::Unauthorized("malformed_token", "Authorization 请求头格式无效".to_string())
        })?;

    let parts: Vec<&str> = token.split('.').collect();
    if parts.len() != 3 || parts[0] != "v1" {
        return Err(AppError::Unauthorized(
            "invalid_token_version",
            "访问凭证格式无效或已升级，请重新连接云端".to_string(),
        ));
    }

    let payload_b64 = parts[1];
    let provided_sig = URL_SAFE_NO_PAD.decode(parts[2]).map_err(|_| {
        AppError::Unauthorized("invalid_token_signature", "访问凭证签名解析失败".to_string())
    })?;

    let mut mac = HmacSha256::new_from_slice(secret)
        .map_err(|e| AppError::Internal(format!("hmac verify init: {e}")))?;
    mac.update(payload_b64.as_bytes());
    let expected_sig = mac.finalize().into_bytes();

    let sig_ok: bool = expected_sig.as_slice().ct_eq(provided_sig.as_slice()).into();
    if !sig_ok {
        return Err(AppError::Unauthorized(
            "token_signature_mismatch",
            "访问凭证签名无效，请重新验证同步密钥".to_string(),
        ));
    }

    let payload_bytes = URL_SAFE_NO_PAD.decode(payload_b64).map_err(|_| {
        AppError::Unauthorized("invalid_token_payload", "访问凭证载荷损坏".to_string())
    })?;
    let claims: TokenClaims = serde_json::from_slice(&payload_bytes).map_err(|_| {
        AppError::Unauthorized("invalid_token_claims", "访问凭证内容无效".to_string())
    })?;

    if claims.exp <= now_millis() {
        return Err(AppError::Unauthorized(
            "token_expired",
            "云端会话已过期，请重新刷新凭证".to_string(),
        ));
    }

    Ok(claims)
}

/// 轻量级内存滑动窗口防暴力破解限流器（按客户端标识统计 60 秒内失败次数）
pub struct AuthRateLimiter {
    failures: Mutex<HashMap<String, Vec<i64>>>,
    max_failures_per_minute: usize,
}

impl AuthRateLimiter {
    pub fn new(max_failures_per_minute: usize) -> Self {
        Self {
            failures: Mutex::new(HashMap::new()),
            max_failures_per_minute,
        }
    }

    pub fn check_allowed(&self, client_key: &str) -> Result<(), AppError> {
        let now = now_millis();
        let cutoff = now - 60_000;
        let mut guard = self.failures.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(times) = guard.get_mut(client_key) {
            times.retain(|&ts| ts >= cutoff);
            if times.len() >= self.max_failures_per_minute {
                return Err(AppError::TooManyRequests(
                    "鉴权失败次数过多，请等待 1 分钟后再试".to_string(),
                ));
            }
        }
        Ok(())
    }

    pub fn record_failure(&self, client_key: &str) {
        let now = now_millis();
        let cutoff = now - 60_000;
        let mut guard = self.failures.lock().unwrap_or_else(|e| e.into_inner());
        if guard.len() > 2048 {
            guard.retain(|_, v| {
                v.retain(|&ts| ts >= cutoff);
                !v.is_empty()
            });
        }
        let entry = guard.entry(client_key.to_string()).or_default();
        entry.retain(|&ts| ts >= cutoff);
        entry.push(now);
    }

    pub fn clear_failures(&self, client_key: &str) {
        let mut guard = self.failures.lock().unwrap_or_else(|e| e.into_inner());
        guard.remove(client_key);
    }
}
