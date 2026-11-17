use axum::body::Bytes;
use axum::extract::{ConnectInfo, DefaultBodyLimit, FromRequestParts, Path, Query, State};
use axum::http::request::Parts;
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post, put};
use axum::{Json, Router};
use serde::{Deserialize, Serialize};
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::task::spawn_blocking;
use tower_http::trace::TraceLayer;

use crate::auth::{
    hash_sync_key, issue_access_token, mask_sync_key, normalize_and_validate_sync_key,
    normalize_app_env, verify_access_code, verify_bearer_header, AuthRateLimiter, TokenClaims,
};
use crate::config::ServerConfig;
use crate::db::{
    AccountSummary, BoundDeviceInfo, BoundIdentityInfo, CloudStore, PulledSnapshotData,
    SnapshotHistoryItem,
};
use crate::error::AppError;

pub struct AppState {
    pub config: ServerConfig,
    pub store: CloudStore,
    pub rate_limiter: AuthRateLimiter,
}

/// 在读取任何请求体（如 64 MiB 快照 JSON 或 10 MiB 图片）之前先校验 Bearer 凭证，阻断未授权大载荷内存耗尽攻击
pub struct AuthenticatedUser(pub TokenClaims);

#[axum::async_trait]
impl FromRequestParts<Arc<AppState>> for AuthenticatedUser {
    type Rejection = AppError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &Arc<AppState>,
    ) -> Result<Self, Self::Rejection> {
        let claims = verify_bearer_header(&parts.headers, &state.config.token_secret)?;
        Ok(AuthenticatedUser(claims))
    }
}

/// 提取防暴力破解限流键：默认使用真实 TCP 对端 IP；仅当对端为本机回环反代或显式开启 `MOJI_TRUST_PROXY_HEADERS` 时才读取代理转发头
pub struct ClientRateLimitKey(pub String);

#[axum::async_trait]
impl FromRequestParts<Arc<AppState>> for ClientRateLimitKey {
    type Rejection = AppError;

    async fn from_request_parts(
        parts: &mut Parts,
        state: &Arc<AppState>,
    ) -> Result<Self, Self::Rejection> {
        let key = resolve_rate_limit_key(&parts.headers, parts.extensions.get::<ConnectInfo<SocketAddr>>(), state.config.trust_proxy_headers);
        Ok(ClientRateLimitKey(key))
    }
}

fn resolve_rate_limit_key(
    headers: &HeaderMap,
    connect_info: Option<&ConnectInfo<SocketAddr>>,
    trust_proxy_headers: bool,
) -> String {
    if let Some(ConnectInfo(peer_addr)) = connect_info {
        let peer_ip = peer_addr.ip();
        if peer_ip.is_loopback() || trust_proxy_headers {
            if let Some(forwarded_ip) = extract_proxy_ip(headers) {
                return forwarded_ip;
            }
        }
        return peer_ip.to_string();
    }

    #[cfg(test)]
    if let Some(test_ip) = headers.get("x-test-peer-ip").and_then(|v| v.to_str().ok()) {
        let trimmed = test_ip.trim();
        if !trimmed.is_empty() {
            return trimmed.chars().take(64).collect();
        }
    }

    if trust_proxy_headers {
        if let Some(forwarded_ip) = extract_proxy_ip(headers) {
            return forwarded_ip;
        }
    }

    "local-inproc".to_string()
}

fn extract_proxy_ip(headers: &HeaderMap) -> Option<String> {
    if let Some(real_ip) = headers
        .get("x-real-ip")
        .and_then(|v| v.to_str().ok())
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        return Some(real_ip.chars().take(64).collect());
    }
    headers
        .get("x-forwarded-for")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.rsplit(',').next())
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(|s| s.chars().take(64).collect())
}

pub fn build_router(state: Arc<AppState>) -> Router {
    let snapshot_routes = Router::new()
        .route("/api/v1/sync/snapshot", put(put_snapshot).get(get_snapshot))
        .layer(DefaultBodyLimit::max(80 * 1024 * 1024));

    let screenshot_upload_routes = Router::new()
        .route(
            "/api/v1/screenshots/:sha256",
            put(put_screenshot).get(get_screenshot),
        )
        .layer(DefaultBodyLimit::max(10 * 1024 * 1024));

    Router::new()
        .route("/api/v1/health", get(health_check))
        .route("/api/v1/auth/key-login", post(key_login))
        .route("/api/v1/auth/identities", get(list_identities))
        .route("/api/v1/sync/status", get(get_sync_status))
        .route("/api/v1/screenshots/missing", post(check_missing_screenshots))
        .merge(snapshot_routes)
        .merge(screenshot_upload_routes)
        .layer(DefaultBodyLimit::max(2 * 1024 * 1024))
        .layer(TraceLayer::new_for_http())
        .with_state(state)
}

#[derive(Serialize)]
struct HealthResponse {
    status: &'static str,
    service: &'static str,
    version: &'static str,
    requires_access_code: bool,
}

async fn health_check(State(state): State<Arc<AppState>>) -> Json<HealthResponse> {
    Json(HealthResponse {
        status: "ok",
        service: "moji-cloud-server",
        version: env!("CARGO_PKG_VERSION"),
        requires_access_code: state.config.server_access_code.is_some(),
    })
}

#[derive(Deserialize)]
pub struct KeyLoginRequest {
    pub sync_key: String,
    #[serde(default)]
    pub server_access_code: Option<String>,
    #[serde(default)]
    pub member_label: String,
    #[serde(default)]
    pub device_id: String,
    #[serde(default)]
    pub device_name: String,
    #[serde(default = "default_prod_env")]
    pub app_env: String,
    #[serde(default)]
    pub app_version: String,
}

fn default_prod_env() -> String {
    "prod".to_string()
}

#[derive(Serialize)]
pub struct KeyLoginResponse {
    pub access_token: String,
    pub expires_at_ms: i64,
    pub user_id: String,
    pub key_hint: String,
    pub is_new_account: bool,
    pub account_summary: AccountSummary,
}

async fn key_login(
    State(state): State<Arc<AppState>>,
    ClientRateLimitKey(client_key): ClientRateLimitKey,
    Json(req): Json<KeyLoginRequest>,
) -> Result<Json<KeyLoginResponse>, AppError> {
    state.rate_limiter.check_allowed(&client_key)?;

    if let Err(err) = verify_access_code(
        state.config.server_access_code.as_deref(),
        req.server_access_code.as_deref(),
    ) {
        state.rate_limiter.record_failure(&client_key);
        return Err(err);
    }

    let app_env = normalize_app_env(&req.app_env)?;
    let normalized_key = match normalize_and_validate_sync_key(&req.sync_key, &app_env) {
        Ok(k) => k,
        Err(err) => {
            state.rate_limiter.record_failure(&client_key);
            return Err(err);
        }
    };

    let key_hint = mask_sync_key(&normalized_key);
    let identifier_hash = hash_sync_key(&normalized_key, &app_env);
    let member_label = req.member_label.clone();
    let device_id = req.device_id.clone();
    let device_name = req.device_name.clone();
    let app_version = req.app_version.clone();

    let state_for_db = Arc::clone(&state);
    let env_for_db = app_env.clone();
    let hint_for_db = key_hint.clone();
    let dev_for_db = device_id.clone();

    let outcome = spawn_blocking(move || {
        state_for_db.store.bind_or_login_with_sync_key(
            &identifier_hash,
            &hint_for_db,
            &env_for_db,
            &member_label,
            &dev_for_db,
            &device_name,
            &app_version,
        )
    })
    .await
    .map_err(|e| AppError::Internal(format!("join key_login task: {e}")))??;

    state.rate_limiter.clear_failures(&client_key);

    let (access_token, expires_at_ms) = issue_access_token(
        &state.config.token_secret,
        &outcome.user_id,
        &app_env,
        &device_id,
    )?;

    Ok(Json(KeyLoginResponse {
        access_token,
        expires_at_ms,
        user_id: outcome.user_id,
        key_hint,
        is_new_account: outcome.is_new_account,
        account_summary: outcome.summary,
    }))
}

#[derive(Serialize)]
struct IdentitiesResponse {
    user_id: String,
    identities: Vec<BoundIdentityInfo>,
}

async fn list_identities(
    State(state): State<Arc<AppState>>,
    AuthenticatedUser(claims): AuthenticatedUser,
) -> Result<Json<IdentitiesResponse>, AppError> {
    let uid = claims.uid.clone();
    let env = claims.env.clone();

    let identities = spawn_blocking(move || -> Result<Vec<BoundIdentityInfo>, AppError> {
        state.store.verify_active_user(&uid, &env)?;
        let (_, ids, _, _) = state.store.get_status_details(&uid)?;
        Ok(ids)
    })
    .await
    .map_err(|e| AppError::Internal(format!("join list_identities: {e}")))??;

    Ok(Json(IdentitiesResponse {
        user_id: claims.uid,
        identities,
    }))
}

#[derive(Serialize)]
struct SyncStatusResponse {
    user_id: String,
    display_name: String,
    app_env: String,
    account_summary: AccountSummary,
    identities: Vec<BoundIdentityInfo>,
    devices: Vec<BoundDeviceInfo>,
    history: Vec<SnapshotHistoryItem>,
}

async fn get_sync_status(
    State(state): State<Arc<AppState>>,
    AuthenticatedUser(claims): AuthenticatedUser,
) -> Result<Json<SyncStatusResponse>, AppError> {
    let uid = claims.uid.clone();
    let env = claims.env.clone();

    let (display_name, summary, identities, devices, history) = spawn_blocking(move || {
        let display_name = state.store.verify_active_user(&uid, &env)?;
        let (summary, identities, devices, history) = state.store.get_status_details(&uid)?;
        Ok::<_, AppError>((display_name, summary, identities, devices, history))
    })
    .await
    .map_err(|e| AppError::Internal(format!("join get_sync_status: {e}")))??;

    Ok(Json(SyncStatusResponse {
        user_id: claims.uid,
        display_name,
        app_env: claims.env,
        account_summary: summary,
        identities,
        devices,
        history,
    }))
}

#[derive(Deserialize)]
pub struct PutSnapshotRequest {
    #[serde(default)]
    pub expected_revision: Option<i64>,
    #[serde(default)]
    pub force_overwrite: bool,
    #[serde(default)]
    pub device_id: String,
    #[serde(default)]
    pub device_name: String,
    #[serde(default)]
    pub member_label: String,
    pub snapshot_json: String,
}

#[derive(Serialize)]
pub struct PutSnapshotResponse {
    pub account_summary: AccountSummary,
}

async fn put_snapshot(
    State(state): State<Arc<AppState>>,
    AuthenticatedUser(claims): AuthenticatedUser,
    Json(req): Json<PutSnapshotRequest>,
) -> Result<Json<PutSnapshotResponse>, AppError> {
    let uid = claims.uid;
    let env = claims.env;
    let dev_id = if req.device_id.is_empty() {
        claims.dev
    } else {
        req.device_id
    };

    let summary = spawn_blocking(move || {
        state.store.verify_active_user(&uid, &env)?;
        state.store.save_snapshot(
            &uid,
            req.expected_revision,
            req.force_overwrite,
            &dev_id,
            &req.device_name,
            &req.member_label,
            &req.snapshot_json,
        )
    })
    .await
    .map_err(|e| AppError::Internal(format!("join put_snapshot: {e}")))??;

    Ok(Json(PutSnapshotResponse {
        account_summary: summary,
    }))
}

#[derive(Deserialize)]
struct SnapshotQuery {
    #[serde(default)]
    revision: Option<i64>,
}

async fn get_snapshot(
    State(state): State<Arc<AppState>>,
    AuthenticatedUser(claims): AuthenticatedUser,
    Query(query): Query<SnapshotQuery>,
) -> Result<Json<PulledSnapshotData>, AppError> {
    let uid = claims.uid;
    let env = claims.env;

    let pulled = spawn_blocking(move || {
        state.store.verify_active_user(&uid, &env)?;
        state.store.load_snapshot(&uid, query.revision)
    })
    .await
    .map_err(|e| AppError::Internal(format!("join get_snapshot: {e}")))??;

    Ok(Json(pulled))
}

#[derive(Deserialize)]
struct MissingScreenshotsRequest {
    sha256_list: Vec<String>,
}

#[derive(Serialize)]
struct MissingScreenshotsResponse {
    missing_sha256: Vec<String>,
}

async fn check_missing_screenshots(
    State(state): State<Arc<AppState>>,
    AuthenticatedUser(claims): AuthenticatedUser,
    Json(req): Json<MissingScreenshotsRequest>,
) -> Result<Json<MissingScreenshotsResponse>, AppError> {
    let uid = claims.uid;
    let env = claims.env;

    let missing = spawn_blocking(move || {
        state.store.verify_active_user(&uid, &env)?;
        state.store.find_missing_screenshots(&uid, &req.sha256_list)
    })
    .await
    .map_err(|e| AppError::Internal(format!("join check_missing_screenshots: {e}")))??;

    Ok(Json(MissingScreenshotsResponse {
        missing_sha256: missing,
    }))
}

#[derive(Serialize)]
struct PutScreenshotResponse {
    sha256: String,
    byte_size: usize,
}

async fn put_screenshot(
    State(state): State<Arc<AppState>>,
    AuthenticatedUser(claims): AuthenticatedUser,
    Path(sha256): Path<String>,
    body: Bytes,
) -> Result<Json<PutScreenshotResponse>, AppError> {
    let uid = claims.uid;
    let env = claims.env;
    let sha_ret = sha256.to_ascii_lowercase();

    let byte_size = spawn_blocking(move || {
        state.store.verify_active_user(&uid, &env)?;
        state.store.save_screenshot(&uid, &sha256, &body)
    })
    .await
    .map_err(|e| AppError::Internal(format!("join put_screenshot: {e}")))??;

    Ok(Json(PutScreenshotResponse {
        sha256: sha_ret,
        byte_size,
    }))
}

async fn get_screenshot(
    State(state): State<Arc<AppState>>,
    AuthenticatedUser(claims): AuthenticatedUser,
    Path(sha256): Path<String>,
) -> Result<Response, AppError> {
    let uid = claims.uid;
    let env = claims.env;

    let bytes = spawn_blocking(move || {
        state.store.verify_active_user(&uid, &env)?;
        state.store.load_screenshot(&uid, &sha256)
    })
    .await
    .map_err(|e| AppError::Internal(format!("join get_screenshot: {e}")))??;

    Ok((
        StatusCode::OK,
        [(header::CONTENT_TYPE, "image/webp")],
        bytes,
    )
        .into_response())
}
