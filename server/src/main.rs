mod auth;
mod config;
mod db;
mod error;
mod routes;

use std::net::SocketAddr;
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

    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .with_graceful_shutdown(shutdown_signal())
    .await
    .expect("server exited with error");
}

async fn shutdown_signal() {
    let ctrl_c = async {
        if let Err(e) = tokio::signal::ctrl_c().await {
            tracing::warn!("无法监听 Ctrl-C 信号 ({e})，挂起该分支");
            std::future::pending::<()>().await;
        }
    };

    #[cfg(unix)]
    let terminate = async {
        match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            Ok(mut sigterm) => {
                if sigterm.recv().await.is_none() {
                    std::future::pending::<()>().await;
                }
            }
            Err(e) => {
                tracing::warn!("无法监听 SIGTERM 信号 ({e})，挂起该分支");
                std::future::pending::<()>().await;
            }
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

    fn make_test_app(access_code: Option<&str>, max_failures: usize) -> (TempDir, axum::Router) {
        let tmp = TempDir::new().unwrap();
        let cfg = ServerConfig::for_test(tmp.path().to_path_buf(), access_code);
        let store = CloudStore::open(&cfg.data_dir, cfg.max_history_revisions).unwrap();
        let state = Arc::new(AppState {
            config: cfg,
            store,
            rate_limiter: AuthRateLimiter::new(max_failures),
        });
        (tmp, build_router(state))
    }

    async fn send_json_with_peer(
        app: &axum::Router,
        method: &str,
        uri: &str,
        bearer: Option<&str>,
        peer_ip: Option<&str>,
        payload: Option<Value>,
    ) -> (StatusCode, Value) {
        let mut builder = Request::builder().method(method).uri(uri);
        if let Some(tok) = bearer {
            builder = builder.header("Authorization", format!("Bearer {tok}"));
        }
        if let Some(ip) = peer_ip {
            builder = builder.header("x-test-peer-ip", ip);
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

    async fn send_json(
        app: &axum::Router,
        method: &str,
        uri: &str,
        bearer: Option<&str>,
        payload: Option<Value>,
    ) -> (StatusCode, Value) {
        send_json_with_peer(app, method, uri, bearer, None, payload).await
    }

    #[tokio::test]
    async fn test_end_to_end_family_isolation_conflict_and_screenshots() {
        let (_tmp, app) = make_test_app(Some("family-8888"), 10);

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

        // 3. 成员 A 与成员 B 使用不同专属 Key 登录，应获得各自独立隔离的 user_id，且 key_hint 仅暴露末尾 4 位
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
        assert_eq!(login_a["key_hint"], "MOJI-****-****-****-DDDD");
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

        // 5a. 新手机未传 expected_revision (null) 或传 0 均应触发 409 revision_conflict
        let (st_null_rev, null_rev_res) = send_json(
            &app,
            "PUT",
            "/api/v1/sync/snapshot",
            Some(&token_a),
            Some(json!({
                "force_overwrite": false,
                "snapshot_json": json!({"appName": "MoJi", "transactions": [{"id": 1}]}).to_string()
            })),
        )
        .await;
        assert_eq!(st_null_rev, StatusCode::CONFLICT);
        assert_eq!(null_rev_res["error"], "revision_conflict");

        // 5b. 即使传对了 expected_revision=1，非强制模式下上传 0 笔账单也应命中 empty_snapshot_guard (409)
        let (st_empty_guard, empty_guard_res) = send_json(
            &app,
            "PUT",
            "/api/v1/sync/snapshot",
            Some(&token_a),
            Some(json!({
                "expected_revision": 1,
                "force_overwrite": false,
                "snapshot_json": json!({"appName": "MoJi", "transactions": []}).to_string()
            })),
        )
        .await;
        assert_eq!(st_empty_guard, StatusCode::CONFLICT);
        assert_eq!(empty_guard_res["error"], "empty_snapshot_guard");

        // 6. 成员 B 完全读不到成员 A 的账本快照（返回 404）
        let (st_b_get, _) =
            send_json(&app, "GET", "/api/v1/sync/snapshot", Some(&token_b), None).await;
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
            .body(Body::from(fake_webp))
            .unwrap();
        let put_ss_resp = app.clone().oneshot(put_ss_req).await.unwrap();
        assert_eq!(put_ss_resp.status(), StatusCode::OK);

        let (_, miss_res2) = send_json(
            &app,
            "POST",
            "/api/v1/screenshots/missing",
            Some(&token_a),
            Some(json!({ "sha256_list": [&ss_hash] })),
        )
        .await;
        assert_eq!(miss_res2["missing_sha256"], json!([]));

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

        let (st_a_get, pulled_a) =
            send_json(&app, "GET", "/api/v1/sync/snapshot", Some(&token_a), None).await;
        assert_eq!(st_a_get, StatusCode::OK);
        assert_eq!(pulled_a["revision"], 1);
        assert_eq!(pulled_a["available_screenshots"], json!([&ss_hash]));
    }

    #[tokio::test]
    async fn test_rate_limit_unauthenticated_body_guard_and_history_pruning() {
        let (tmp, app) = make_test_app(Some("secret-code"), 3);

        // 1. 未授权请求（即使发送非法 JSON 体）应在提取 Body 前直接以 401 拒绝，而非 400 解析错误
        let unauth_req = Request::builder()
            .method("PUT")
            .uri("/api/v1/sync/snapshot")
            .header("Content-Type", "application/json")
            .body(Body::from("not-even-valid-json{{{"))
            .unwrap();
        let unauth_resp = app.clone().oneshot(unauth_req).await.unwrap();
        assert_eq!(unauth_resp.status(), StatusCode::UNAUTHORIZED);

        // 2. 篡改签名的 Token 应返回 401
        let (st_bad_tok, _) = send_json(
            &app,
            "GET",
            "/api/v1/sync/status",
            Some("v1.eyJ2IjoxfQ.invalidsig"),
            None,
        )
        .await;
        assert_eq!(st_bad_tok, StatusCode::UNAUTHORIZED);

        // 3. 攻击者 IP 连续输错 3 次触发 429 限流，即使伪造 X-Real-IP 也无法绕过，且不影响正常家庭成员 IP
        for _ in 0..3 {
            let (st, _) = send_json_with_peer(
                &app,
                "POST",
                "/api/v1/auth/key-login",
                None,
                Some("203.0.113.99"),
                Some(json!({
                    "sync_key": "MOJI-AAAA-BBBB-CCCC-DDDD",
                    "server_access_code": "wrong-code"
                })),
            )
            .await;
            assert_eq!(st, StatusCode::FORBIDDEN);
        }
        let mut spoof_req = Request::builder()
            .method("POST")
            .uri("/api/v1/auth/key-login")
            .header("Content-Type", "application/json")
            .header("x-test-peer-ip", "203.0.113.99")
            .header("x-real-ip", "198.51.100.1")
            .body(Body::from(
                serde_json::to_vec(&json!({
                    "sync_key": "MOJI-AAAA-BBBB-CCCC-DDDD",
                    "server_access_code": "secret-code"
                }))
                .unwrap(),
            ))
            .unwrap();
        let _ = &mut spoof_req;
        let spoof_resp = app.clone().oneshot(spoof_req).await.unwrap();
        assert_eq!(spoof_resp.status(), StatusCode::TOO_MANY_REQUESTS);

        // 正常家庭成员来自不同对端 IP，不受攻击者限流连坐
        let (st_ok, login_ok) = send_json_with_peer(
            &app,
            "POST",
            "/api/v1/auth/key-login",
            None,
            Some("198.51.100.88"),
            Some(json!({
                "sync_key": "MOJI-AAAA-BBBB-CCCC-DDDD",
                "server_access_code": "secret-code",
                "member_label": "家人A"
            })),
        )
        .await;
        assert_eq!(st_ok, StatusCode::OK);
        let tok = login_ok["access_token"].as_str().unwrap().to_string();
        let uid = login_ok["user_id"].as_str().unwrap().to_string();

        // 4. 验证历史快照保留上限裁剪（for_test 设置 max_history_revisions = 3）
        for rev in 0..5i64 {
            let snap = json!({
                "appName": "MoJi",
                "exportedAtMillis": 1763380000000i64 + rev,
                "transactions": [{"id": rev + 1, "amountCents": (rev + 1) * 100}]
            })
            .to_string();
            let (st, _) = send_json(
                &app,
                "PUT",
                "/api/v1/sync/snapshot",
                Some(&tok),
                Some(json!({
                    "expected_revision": rev,
                    "force_overwrite": false,
                    "member_label": "家人A",
                    "device_name": "Pixel 9",
                    "snapshot_json": snap
                })),
            )
            .await;
            assert_eq!(st, StatusCode::OK);
        }

        // v1、v2 应已被裁剪（返回 404 且磁盘文件已清理），v3、v4、v5 仍可按 ?revision= 拉回
        let (st_v2, _) =
            send_json(&app, "GET", "/api/v1/sync/snapshot?revision=2", Some(&tok), None).await;
        assert_eq!(st_v2, StatusCode::NOT_FOUND);
        assert!(!tmp
            .path()
            .join("snapshots")
            .join(&uid)
            .join("history/rev_2.json")
            .exists());

        let (st_v3, pulled_v3) =
            send_json(&app, "GET", "/api/v1/sync/snapshot?revision=3", Some(&tok), None).await;
        assert_eq!(st_v3, StatusCode::OK);
        assert_eq!(pulled_v3["revision"], 3);
        assert_eq!(pulled_v3["updated_by_member"], "家人A");
        assert_eq!(pulled_v3["updated_by_device"], "Pixel 9");
    }
}
