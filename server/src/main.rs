mod auth;
mod config;
mod db;
mod error;
mod routes;

use std::sync::Arc;
use tokio::net::TcpListener;
use tracing_subscriber::EnvFilter;

use crate::auth::AuthRateLimiter;
use crate::config::ServerConfig;
use crate::db::CloudStore;
use crate::routes::{build_router, AppState};

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| EnvFilter::new("moji_cloud_server=info,tower_http=info")),
        )
        .init();

    let config = match ServerConfig::from_env() {
        Ok(cfg) => cfg,
        Err(err) => {
            eprintln!("启动配置错误: {err}");
            std::process::exit(1);
        }
    };

    let store = match CloudStore::open(&config.data_dir, config.max_history_revisions) {
        Ok(s) => s,
        Err(err) => {
            eprintln!("初始化 SQLite 云端存储失败: {err}");
            std::process::exit(1);
        }
    };

    let bind_addr = config.bind_addr;
    let has_access_code = config.server_access_code.is_some();

    let state = Arc::new(AppState {
        config,
        store,
        rate_limiter: AuthRateLimiter::new(15),
    });

    let app = build_router(state);

    let listener = TcpListener::bind(bind_addr)
        .await
        .unwrap_or_else(|e| panic!("无法绑定监听地址 {bind_addr}: {e}"));

    tracing::info!(
        "moji-cloud-server v{} 已启动，监听地址: http://{} (接入码保护: {})",
        env!("CARGO_PKG_VERSION"),
        bind_addr,
        if has_access_code { "已开启" } else { "未开启" }
    );

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await
        .expect("server exited with error");
}

async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };

    #[cfg(unix)]
    let terminate = async {
        if let Ok(mut sigterm) =
            tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        {
            sigterm.recv().await;
        }
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
    tracing::info!("收到退出信号，正在优雅关闭 moji-cloud-server...");
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use http_body_util::BodyExt;
    use serde_json::{json, Value};
    use tempfile::TempDir;
    use tower::ServiceExt;

    fn make_test_app(access_code: Option<&str>) -> (TempDir, axum::Router) {
        let tmp = TempDir::new().unwrap();
        let cfg = ServerConfig::for_test(tmp.path().to_path_buf(), access_code);
        let store = CloudStore::open(&cfg.data_dir, cfg.max_history_revisions).unwrap();
        let state = Arc::new(AppState {
            config: cfg,
            store,
            rate_limiter: AuthRateLimiter::new(10),
        });
        (tmp, build_router(state))
    }

    async fn send_json(
        app: &axum::Router,
        method: &str,
        uri: &str,
        bearer: Option<&str>,
        payload: Option<Value>,
    ) -> (StatusCode, Value) {
        let mut builder = Request::builder().method(method).uri(uri);
        if let Some(tok) = bearer {
            builder = builder.header("Authorization", format!("Bearer {tok}"));
        }
        let body = if let Some(val) = payload {
            builder = builder.header("Content-Type", "application/json");
            Body::from(serde_json::to_vec(&val).unwrap())
        } else {
            Body::empty()
        };
        let resp = app.clone().oneshot(builder.body(body).unwrap()).await.unwrap();
        let status = resp.status();
        let bytes = resp.into_body().collect().await.unwrap().to_bytes();
        let json_val: Value = serde_json::from_slice(&bytes).unwrap_or(json!({}));
        (status, json_val)
    }

    #[tokio::test]
    async fn test_end_to_end_family_isolation_conflict_and_screenshots() {
        let (_tmp, app) = make_test_app(Some("family-8888"));

        // 1. 未带家庭接入码应被拒绝
        let (st, _) = send_json(
            &app,
            "POST",
            "/api/v1/auth/key-login",
            None,
            Some(json!({
                "sync_key": "MOJI-AAAA-BBBB-CCCC-DDDD",
                "member_label": "我的手机",
                "device_id": "dev-husband",
                "device_name": "OnePlus 13",
                "app_env": "prod"
            })),
        )
        .await;
        assert_eq!(st, StatusCode::FORBIDDEN);

        // 2. QA 环境试图使用正式版 MOJI- 前缀密钥应被拦截
        let (st, _) = send_json(
            &app,
            "POST",
            "/api/v1/auth/key-login",
            None,
            Some(json!({
                "sync_key": "MOJI-AAAA-BBBB-CCCC-DDDD",
                "server_access_code": "family-8888",
                "app_env": "qa"
            })),
        )
        .await;
        assert_eq!(st, StatusCode::BAD_REQUEST);

        // 3. 成员 A 与成员 B 使用不同专属 Key 登录，应获得各自独立隔离的 user_id
        let (st_a, login_a) = send_json(
            &app,
            "POST",
            "/api/v1/auth/key-login",
            None,
            Some(json!({
                "sync_key": "MOJI-AAAA-BBBB-CCCC-DDDD",
                "server_access_code": "family-8888",
                "member_label": "老公",
                "device_id": "dev-a",
                "device_name": "OnePlus 13",
                "app_env": "prod"
            })),
        )
        .await;
        assert_eq!(st_a, StatusCode::OK);
        let token_a = login_a["access_token"].as_str().unwrap().to_string();
        let uid_a = login_a["user_id"].as_str().unwrap().to_string();
        assert_eq!(login_a["is_new_account"], true);

        let (st_b, login_b) = send_json(
            &app,
            "POST",
            "/api/v1/auth/key-login",
            None,
            Some(json!({
                "sync_key": "MOJI-1111-2222-3333-4444",
                "server_access_code": "family-8888",
                "member_label": "老婆",
                "device_id": "dev-b",
                "device_name": "Xiaomi 15",
                "app_env": "prod"
            })),
        )
        .await;
        assert_eq!(st_b, StatusCode::OK);
        let token_b = login_b["access_token"].as_str().unwrap().to_string();
        assert_ne!(uid_a, login_b["user_id"].as_str().unwrap());

        // 4. 成员 A 上传第一版快照（含 2 笔账单）
        let snap_v1 = json!({
            "schemaVersion": 1,
            "appName": "MoJi",
            "exportedAtMillis": 1763380000000i64,
            "ledgers": ["默认账本"],
            "wallets": [{"id": 1, "name": "微信零钱"}],
            "transactions": [
                {"id": 101, "amountCents": 2500, "merchant": "瑞幸咖啡"},
                {"id": 102, "amountCents": 6800, "merchant": "盒马鲜生"}
            ]
        })
        .to_string();

        let (st_put, put_res) = send_json(
            &app,
            "PUT",
            "/api/v1/sync/snapshot",
            Some(&token_a),
            Some(json!({
                "expected_revision": 0,
                "force_overwrite": false,
                "device_id": "dev-a",
                "device_name": "OnePlus 13",
                "member_label": "老公",
                "snapshot_json": snap_v1
            })),
        )
        .await;
        assert_eq!(st_put, StatusCode::OK);
        assert_eq!(put_res["account_summary"]["latest_revision"], 1);
        assert_eq!(put_res["account_summary"]["tx_count"], 2);

        // 5. 模拟新手机绑定成员 A 的同一个 Key，未拉取前误传 expected_revision=0 应触发 409 冲突保护
        let (st_conflict, conflict_res) = send_json(
            &app,
            "PUT",
            "/api/v1/sync/snapshot",
            Some(&token_a),
            Some(json!({
                "expected_revision": 0,
                "force_overwrite": false,
                "snapshot_json": json!({"appName": "MoJi", "transactions": []}).to_string()
            })),
        )
        .await;
        assert_eq!(st_conflict, StatusCode::CONFLICT);
        assert_eq!(conflict_res["error"], "revision_conflict");

        // 6. 成员 B 完全读不到成员 A 的账本快照（返回 404）
        let (st_b_get, _) = send_json(&app, "GET", "/api/v1/sync/snapshot", Some(&token_b), None).await;
        assert_eq!(st_b_get, StatusCode::NOT_FOUND);

        // 7. 测试 WebP 账单截图增量检查、上传与跨租户隔离
        let mut fake_webp = Vec::new();
        fake_webp.extend_from_slice(b"RIFF");
        fake_webp.extend_from_slice(&16u32.to_le_bytes());
        fake_webp.extend_from_slice(b"WEBP");
        fake_webp.extend_from_slice(b"VP8 12345678");
        let ss_hash = crate::db::sha256_hex(&fake_webp);

        let (_, miss_res) = send_json(
            &app,
            "POST",
            "/api/v1/screenshots/missing",
            Some(&token_a),
            Some(json!({ "sha256_list": [&ss_hash] })),
        )
        .await;
        assert_eq!(miss_res["missing_sha256"], json!([&ss_hash]));

        let put_ss_req = Request::builder()
            .method("PUT")
            .uri(format!("/api/v1/screenshots/{ss_hash}"))
            .header("Authorization", format!("Bearer {token_a}"))
            .body(Body::from(fake_webp.clone()))
            .unwrap();
        let put_ss_resp = app.clone().oneshot(put_ss_req).await.unwrap();
        assert_eq!(put_ss_resp.status(), StatusCode::OK);

        // 再次查询 missing 应为空（去重生效）
        let (_, miss_res2) = send_json(
            &app,
            "POST",
            "/api/v1/screenshots/missing",
            Some(&token_a),
            Some(json!({ "sha256_list": [&ss_hash] })),
        )
        .await;
        assert_eq!(miss_res2["missing_sha256"], json!([]));

        // 成员 B 即使知道哈希也无法下载成员 A 的截图
        let get_ss_b = Request::builder()
            .method("GET")
            .uri(format!("/api/v1/screenshots/{ss_hash}"))
            .header("Authorization", format!("Bearer {token_b}"))
            .body(Body::empty())
            .unwrap();
        assert_eq!(
            app.clone().oneshot(get_ss_b).await.unwrap().status(),
            StatusCode::NOT_FOUND
        );

        // 成员 A 可以完整拉回快照与截图
        let (st_a_get, pulled_a) =
            send_json(&app, "GET", "/api/v1/sync/snapshot", Some(&token_a), None).await;
        assert_eq!(st_a_get, StatusCode::OK);
        assert_eq!(pulled_a["revision"], 1);
        assert_eq!(pulled_a["available_screenshots"], json!([&ss_hash]));
    }
}
