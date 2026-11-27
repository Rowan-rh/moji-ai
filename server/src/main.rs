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

    #[tokio::test]
    async fn test_loopback_spoof_blocked_and_registration_quota_enforced() {
        let tmp = TempDir::new().unwrap();
        let mut cfg = ServerConfig::for_test(tmp.path().to_path_buf(), None);
        cfg.max_users = 3;
        let store = CloudStore::open(&cfg.data_dir, cfg.max_history_revisions).unwrap();
        let state = Arc::new(AppState {
            config: cfg,
            store,
            rate_limiter: AuthRateLimiter::with_registration_limit(3, 2),
        });
        let app = build_router(state);

        // 1. 未开启 trust_proxy_headers 时，即使来自 127.0.0.1 回环隧道，也不得采信伪造的 X-Real-IP 绕过限流
        for idx in 0..2 {
            let key = format!("MOJI-AA0{idx}-BBBB-CCCC-DDDD");
            let req = Request::builder()
                .method("POST")
                .uri("/api/v1/auth/key-login")
                .header("Content-Type", "application/json")
                .header("x-real-ip", format!("198.51.100.{idx}"))
                .extension(axum::extract::ConnectInfo::<SocketAddr>(
                    "127.0.0.1:54321".parse().unwrap(),
                ))
                .body(Body::from(
                    serde_json::to_vec(&json!({ "sync_key": key })).unwrap(),
                ))
                .unwrap();
            let resp = app.clone().oneshot(req).await.unwrap();
            assert_eq!(resp.status(), StatusCode::OK);
        }

        // 第 3 个来自同一回环 IP 的新账号无法通过伪造新的 X-Real-IP 绕过每小时注册限流（返回 429）
        let third_req = Request::builder()
            .method("POST")
            .uri("/api/v1/auth/key-login")
            .header("Content-Type", "application/json")
            .header("x-real-ip", "203.0.113.77")
            .extension(axum::extract::ConnectInfo::<SocketAddr>(
                "127.0.0.1:54322".parse().unwrap(),
            ))
            .body(Body::from(
                serde_json::to_vec(&json!({ "sync_key": "MOJI-AA09-BBBB-CCCC-DDDD" })).unwrap(),
            ))
            .unwrap();
        let third_resp = app.clone().oneshot(third_req).await.unwrap();
        assert_eq!(third_resp.status(), StatusCode::TOO_MANY_REQUESTS);

        // 不同对端 IP 可注册第 3 个账号，随后第 4 个账号触发 max_users=3 全局配额上限（返回 403）
        let (st_third_ip, _) = send_json_with_peer(
            &app,
            "POST",
            "/api/v1/auth/key-login",
            None,
            Some("198.51.100.200"),
            Some(json!({ "sync_key": "MOJI-AA08-BBBB-CCCC-DDDD" })),
        )
        .await;
        assert_eq!(st_third_ip, StatusCode::OK);

        let (st_fourth_ip, fourth_body) = send_json_with_peer(
            &app,
            "POST",
            "/api/v1/auth/key-login",
            None,
            Some("198.51.100.201"),
            Some(json!({ "sync_key": "MOJI-AA07-BBBB-CCCC-DDDD" })),
        )
        .await;
        assert_eq!(st_fourth_ip, StatusCode::FORBIDDEN);
        assert_eq!(fourth_body["error"], "max_users_reached");

        // 但已注册的老账号正常重签 Token 不受 max_users 或注册配额影响
        let relogin_req = Request::builder()
            .method("POST")
            .uri("/api/v1/auth/key-login")
            .header("Content-Type", "application/json")
            .extension(axum::extract::ConnectInfo::<SocketAddr>(
                "127.0.0.1:54323".parse().unwrap(),
            ))
            .body(Body::from(
                serde_json::to_vec(&json!({ "sync_key": "MOJI-AA00-BBBB-CCCC-DDDD" })).unwrap(),
            ))
            .unwrap();
        let relogin_resp = app.clone().oneshot(relogin_req).await.unwrap();
        assert_eq!(relogin_resp.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn test_device_login_requires_configured_and_matching_server_sk_and_rate_limits() {
        // 1. 服务端未配置 MOJI_SERVER_ACCESS_CODE 时，必须以 403 server_sk_not_configured 拒绝免密设备登录与认领
        let (_tmp_no_sk, app_no_sk) = make_test_app(None, 3);
        let fp_a = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        let (st_no_sk, res_no_sk) = send_json(
            &app_no_sk,
            "POST",
            "/api/v1/auth/device-login",
            None,
            Some(json!({
                "server_access_code": "any-code",
                "device_fingerprint": fp_a,
                "app_env": "prod"
            })),
        )
        .await;
        assert_eq!(st_no_sk, StatusCode::FORBIDDEN);
        assert_eq!(res_no_sk["error"], "server_sk_not_configured");

        let (st_claim_no_sk, res_claim_no_sk) = send_json(
            &app_no_sk,
            "POST",
            "/api/v1/auth/claim-member",
            None,
            Some(json!({
                "server_access_code": "any-code",
                "target_user_id": "00000000-0000-0000-0000-000000000001",
                "device_fingerprint": fp_a,
                "app_env": "prod"
            })),
        )
        .await;
        assert_eq!(st_claim_no_sk, StatusCode::FORBIDDEN);
        assert_eq!(res_claim_no_sk["error"], "server_sk_not_configured");

        // 2. 配置了 SK 后：漏传 SK / 错传 SK 返回 403，连续输错 3 次触发 429 限流
        let (_tmp, app) = make_test_app(Some("family-8888"), 3);

        let (st_missing_sk, res_missing_sk) = send_json_with_peer(
            &app,
            "POST",
            "/api/v1/auth/device-login",
            None,
            Some("203.0.113.50"),
            Some(json!({
                "device_fingerprint": fp_a,
                "app_env": "prod"
            })),
        )
        .await;
        assert_eq!(st_missing_sk, StatusCode::FORBIDDEN);
        assert_eq!(res_missing_sk["error"], "access_code_required");

        for _ in 0..2 {
            let (st_bad_sk, res_bad_sk) = send_json_with_peer(
                &app,
                "POST",
                "/api/v1/auth/device-login",
                None,
                Some("203.0.113.50"),
                Some(json!({
                    "server_access_code": "wrong-sk",
                    "device_fingerprint": fp_a,
                    "app_env": "prod"
                })),
            )
            .await;
            assert_eq!(st_bad_sk, StatusCode::FORBIDDEN);
            assert_eq!(res_bad_sk["error"], "invalid_access_code");
        }

        let (st_rate_limited, res_rate_limited) = send_json_with_peer(
            &app,
            "POST",
            "/api/v1/auth/device-login",
            None,
            Some("203.0.113.50"),
            Some(json!({
                "server_access_code": "family-8888",
                "device_fingerprint": fp_a,
                "app_env": "prod"
            })),
        )
        .await;
        assert_eq!(st_rate_limited, StatusCode::TOO_MANY_REQUESTS);
        assert_eq!(res_rate_limited["error"], "rate_limited");
    }

    #[tokio::test]
    async fn test_device_login_selection_required_claim_member_and_env_isolation() {
        let (_tmp, app) = make_test_app(Some("family-8888"), 10);
        let fp_husband_old = "111111111111111111111111111111111111111111111111111111111111aaaa";
        let fp_husband_new = "222222222222222222222222222222222222222222222222222222222222bbbb";
        let fp_wife = "333333333333333333333333333333333333333333333333333333333333cccc";
        let fp_qa_tester = "444444444444444444444444444444444444444444444444444444444444dddd";

        // 1. 全家首台设备（create_if_missing = false）应自动完成初始化绑定，返回 status = "bound"
        let (st_first, first_res) = send_json(
            &app,
            "POST",
            "/api/v1/auth/device-login",
            None,
            Some(json!({
                "server_access_code": "family-8888",
                "device_fingerprint": fp_husband_old,
                "device_id": "dev-old-phone",
                "device_name": "OnePlus 13",
                "member_label": "阿豪",
                "app_env": "prod",
                "app_version": "1.2.1",
                "create_if_missing": false
            })),
        )
        .await;
        assert_eq!(st_first, StatusCode::OK);
        assert_eq!(first_res["status"], "bound");
        assert_eq!(first_res["is_new_account"], true);
        assert_eq!(first_res["key_hint"], "FP-****-AAAA");
        let uid_husband = first_res["user_id"].as_str().unwrap().to_string();
        let tok_husband = first_res["access_token"].as_str().unwrap().to_string();

        // 上传 2 笔账单快照
        let snap = json!({
            "appName": "MoJi",
            "exportedAtMillis": 1764000000000i64,
            "ledgers": ["家庭总账"],
            "wallets": [{"id": 1, "name": "招行储蓄卡"}],
            "transactions": [
                {"id": 1, "amountCents": 3200, "merchant": "山姆会员店"},
                {"id": 2, "amountCents": 1800, "merchant": "星巴克"}
            ]
        })
        .to_string();
        let (st_put, _) = send_json(
            &app,
            "PUT",
            "/api/v1/sync/snapshot",
            Some(&tok_husband),
            Some(json!({
                "expected_revision": 0,
                "force_overwrite": false,
                "device_id": "dev-old-phone",
                "device_name": "OnePlus 13",
                "member_label": "阿豪",
                "snapshot_json": snap
            })),
        )
        .await;
        assert_eq!(st_put, StatusCode::OK);

        // 2. 同一台手机卸载重装/断开重连：自动识别原 user_id，无需手动选人
        let (st_reconnect, reconnect_res) = send_json(
            &app,
            "POST",
            "/api/v1/auth/device-login",
            None,
            Some(json!({
                "server_access_code": "family-8888",
                "device_fingerprint": fp_husband_old,
                "device_id": "dev-old-phone-reinstall",
                "device_name": "OnePlus 13",
                "member_label": "默记用户",
                "app_env": "prod",
                "create_if_missing": false
            })),
        )
        .await;
        assert_eq!(st_reconnect, StatusCode::OK);
        assert_eq!(reconnect_res["status"], "bound");
        assert_eq!(reconnect_res["is_new_account"], false);
        assert_eq!(reconnect_res["user_id"], uid_husband);
        assert_eq!(reconnect_res["display_name"], "阿豪");
        assert_eq!(reconnect_res["account_summary"]["tx_count"], 2);

        // 3. QA 环境完全隔离：QA 首台设备看不到 prod 成员，独立建 QA 账号
        let (st_qa, qa_res) = send_json(
            &app,
            "POST",
            "/api/v1/auth/device-login",
            None,
            Some(json!({
                "server_access_code": "family-8888",
                "device_fingerprint": fp_qa_tester,
                "device_id": "dev-qa",
                "device_name": "QA Pixel",
                "member_label": "测试员",
                "app_env": "qa",
                "create_if_missing": false
            })),
        )
        .await;
        assert_eq!(st_qa, StatusCode::OK);
        assert_eq!(qa_res["status"], "bound");
        assert_eq!(qa_res["is_new_account"], true);
        let uid_qa = qa_res["user_id"].as_str().unwrap().to_string();
        assert_ne!(uid_qa, uid_husband);

        // 4. 新手机（fp_husband_new）首次连接 prod：不建垃圾空账号，返回 selection_required 与已有成员列表
        let (st_probe, probe_res) = send_json(
            &app,
            "POST",
            "/api/v1/auth/device-login",
            None,
            Some(json!({
                "server_access_code": "family-8888",
                "device_fingerprint": fp_husband_new,
                "device_id": "dev-new-phone",
                "device_name": "Xiaomi 15 Pro",
                "member_label": "默记用户",
                "app_env": "prod",
                "create_if_missing": false
            })),
        )
        .await;
        assert_eq!(st_probe, StatusCode::OK);
        assert_eq!(probe_res["status"], "selection_required");
        assert!(probe_res["access_token"].is_null());
        let members = probe_res["existing_members"].as_array().unwrap();
        assert_eq!(members.len(), 1);
        assert_eq!(members[0]["user_id"], uid_husband);
        assert_eq!(members[0]["display_name"], "阿豪");
        assert_eq!(members[0]["last_device_name"], "OnePlus 13");
        assert_eq!(members[0]["tx_count"], 2);
        assert_eq!(members[0]["wallet_count"], 1);
        assert_eq!(members[0]["latest_revision"], 1);

        // 5. 跨环境认领 QA 账号应被 env_mismatch 拦截
        let (st_cross_claim, res_cross_claim) = send_json(
            &app,
            "POST",
            "/api/v1/auth/claim-member",
            None,
            Some(json!({
                "server_access_code": "family-8888",
                "target_user_id": uid_qa,
                "device_fingerprint": fp_husband_new,
                "app_env": "prod"
            })),
        )
        .await;
        assert_eq!(st_cross_claim, StatusCode::FORBIDDEN);
        assert_eq!(res_cross_claim["error"], "env_mismatch");

        // 6. 家人 B 点击「我是新家人，创建独立账本」(create_if_missing = true) 建立独立账号并上传 1 笔账单
        let (st_wife, wife_res) = send_json(
            &app,
            "POST",
            "/api/v1/auth/device-login",
            None,
            Some(json!({
                "server_access_code": "family-8888",
                "device_fingerprint": fp_wife,
                "device_id": "dev-wife",
                "device_name": "iQOO 13",
                "member_label": "小美",
                "app_env": "prod",
                "create_if_missing": true
            })),
        )
        .await;
        assert_eq!(st_wife, StatusCode::OK);
        assert_eq!(wife_res["status"], "bound");
        let uid_wife = wife_res["user_id"].as_str().unwrap().to_string();
        let tok_wife = wife_res["access_token"].as_str().unwrap().to_string();
        assert_ne!(uid_wife, uid_husband);

        let (st_wife_put, _) = send_json(
            &app,
            "PUT",
            "/api/v1/sync/snapshot",
            Some(&tok_wife),
            Some(json!({
                "expected_revision": 0,
                "force_overwrite": false,
                "device_id": "dev-wife",
                "device_name": "iQOO 13",
                "member_label": "小美",
                "snapshot_json": json!({
                    "appName": "MoJi",
                    "transactions": [{"id": 99, "amountCents": 5000}]
                }).to_string()
            })),
        )
        .await;
        assert_eq!(st_wife_put, StatusCode::OK);

        // 已绑定且有云端数据的设备（fp_wife）试图改绑阿豪账号应返回 409 拒绝
        let (st_wife_reclaim, res_wife_reclaim) = send_json(
            &app,
            "POST",
            "/api/v1/auth/claim-member",
            None,
            Some(json!({
                "server_access_code": "family-8888",
                "target_user_id": uid_husband,
                "device_fingerprint": fp_wife,
                "app_env": "prod"
            })),
        )
        .await;
        assert_eq!(st_wife_reclaim, StatusCode::CONFLICT);
        assert_eq!(res_wife_reclaim["error"], "device_already_bound_with_data");

        // 7. 即使新手机先误点了新建空账号（0 笔快照），随后改点认领阿豪账号也能安全清理临时空号并完成认领
        let (st_temp, temp_res) = send_json(
            &app,
            "POST",
            "/api/v1/auth/device-login",
            None,
            Some(json!({
                "server_access_code": "family-8888",
                "device_fingerprint": fp_husband_new,
                "device_id": "dev-new-phone",
                "device_name": "Xiaomi 15 Pro",
                "member_label": "默记用户",
                "app_env": "prod",
                "create_if_missing": true
            })),
        )
        .await;
        assert_eq!(st_temp, StatusCode::OK);
        let temp_uid = temp_res["user_id"].as_str().unwrap().to_string();
        assert_ne!(temp_uid, uid_husband);

        let (st_claim, claim_res) = send_json(
            &app,
            "POST",
            "/api/v1/auth/claim-member",
            None,
            Some(json!({
                "server_access_code": "family-8888",
                "target_user_id": uid_husband,
                "device_fingerprint": fp_husband_new,
                "device_id": "dev-new-phone",
                "device_name": "Xiaomi 15 Pro",
                "member_label": "默记用户",
                "app_env": "prod"
            })),
        )
        .await;
        assert_eq!(st_claim, StatusCode::OK);
        assert_eq!(claim_res["status"], "bound");
        assert_eq!(claim_res["user_id"], uid_husband);
        assert_eq!(claim_res["display_name"], "阿豪");
        assert_eq!(claim_res["account_summary"]["tx_count"], 2);
        let tok_claimed = claim_res["access_token"].as_str().unwrap().to_string();

        // 认领后可直接用新签发的 token 拉回原账号完整账本快照，且临时空号已被彻底清理
        let (st_pull, pulled) =
            send_json(&app, "GET", "/api/v1/sync/snapshot", Some(&tok_claimed), None).await;
        assert_eq!(st_pull, StatusCode::OK);
        assert_eq!(pulled["revision"], 1);

        let fp_probe_after = "555555555555555555555555555555555555555555555555555555555555eeee";
        let (_, probe_after) = send_json(
            &app,
            "POST",
            "/api/v1/auth/device-login",
            None,
            Some(json!({
                "server_access_code": "family-8888",
                "device_fingerprint": fp_probe_after,
                "app_env": "prod",
                "create_if_missing": false
            })),
        )
        .await;
        let members_after = probe_after["existing_members"].as_array().unwrap();
        assert_eq!(members_after.len(), 2);
        assert!(!members_after.iter().any(|m| m["user_id"] == temp_uid));
    }

    #[tokio::test]
    async fn test_profile_avatar_webp_upload_download_delete_and_validation() {
        let (tmp, app) = make_test_app(Some("family-8888"), 10);
        let fp = "abcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdef1234";

        // 未授权访问头像接口应返回 401
        let unauth_get = Request::builder()
            .method("GET")
            .uri("/api/v1/profile/avatar")
            .body(Body::empty())
            .unwrap();
        assert_eq!(
            app.clone().oneshot(unauth_get).await.unwrap().status(),
            StatusCode::UNAUTHORIZED
        );

        let (st_login, login_res) = send_json(
            &app,
            "POST",
            "/api/v1/auth/device-login",
            None,
            Some(json!({
                "server_access_code": "family-8888",
                "device_fingerprint": fp,
                "member_label": "阿豪",
                "app_env": "prod"
            })),
        )
        .await;
        assert_eq!(st_login, StatusCode::OK);
        let uid = login_res["user_id"].as_str().unwrap().to_string();
        let tok = login_res["access_token"].as_str().unwrap().to_string();

        // 尚未上传头像时 GET 返回 404
        let init_get = Request::builder()
            .method("GET")
            .uri("/api/v1/profile/avatar")
            .header("Authorization", format!("Bearer {tok}"))
            .body(Body::empty())
            .unwrap();
        assert_eq!(
            app.clone().oneshot(init_get).await.unwrap().status(),
            StatusCode::NOT_FOUND
        );

        // 非法格式（非 RIFF....WEBP）、过小（<16B）、过大（>512KB）均应返回 400
        for bad_bytes in [
            vec![1u8; 8],
            b"PNG_NOT_WEBP_HEADER_1234567890".to_vec(),
            {
                let mut huge = Vec::with_capacity(512 * 1024 + 32);
                huge.extend_from_slice(b"RIFF");
                huge.extend_from_slice(&0u32.to_le_bytes());
                huge.extend_from_slice(b"WEBP");
                huge.resize(512 * 1024 + 32, 0xAB);
                huge
            },
        ] {
            let bad_put = Request::builder()
                .method("PUT")
                .uri("/api/v1/profile/avatar")
                .header("Authorization", format!("Bearer {tok}"))
                .body(Body::from(bad_bytes))
                .unwrap();
            assert_eq!(
                app.clone().oneshot(bad_put).await.unwrap().status(),
                StatusCode::BAD_REQUEST
            );
        }

        // 上传合法 WebP 头像
        let mut valid_avatar = Vec::new();
        valid_avatar.extend_from_slice(b"RIFF");
        valid_avatar.extend_from_slice(&20u32.to_le_bytes());
        valid_avatar.extend_from_slice(b"WEBP");
        valid_avatar.extend_from_slice(b"VP8Xavatar123456");
        let expected_sha = crate::db::sha256_hex(&valid_avatar);

        let put_req = Request::builder()
            .method("PUT")
            .uri("/api/v1/profile/avatar")
            .header("Authorization", format!("Bearer {tok}"))
            .body(Body::from(valid_avatar.clone()))
            .unwrap();
        let put_resp = app.clone().oneshot(put_req).await.unwrap();
        assert_eq!(put_resp.status(), StatusCode::OK);
        let put_json: Value = serde_json::from_slice(
            &put_resp.into_body().collect().await.unwrap().to_bytes(),
        )
        .unwrap();
        assert_eq!(put_json["avatar_sha256"], expected_sha);
        assert!(tmp.path().join("avatars").join(format!("{uid}.webp")).exists());

        // sync/status 与新手机探测 existing_members 均携带最新头像状态
        let (_, status_res) =
            send_json(&app, "GET", "/api/v1/sync/status", Some(&tok), None).await;
        assert_eq!(status_res["account_summary"]["avatar_sha256"], expected_sha);

        let (_, probe_res) = send_json(
            &app,
            "POST",
            "/api/v1/auth/device-login",
            None,
            Some(json!({
                "server_access_code": "family-8888",
                "device_fingerprint": "999999999999999999999999999999999999999999999999999999999999ffff",
                "app_env": "prod",
                "create_if_missing": false
            })),
        )
        .await;
        assert_eq!(probe_res["existing_members"][0]["has_avatar"], true);

        // GET 可完整下载相同字节流
        let get_req = Request::builder()
            .method("GET")
            .uri("/api/v1/profile/avatar")
            .header("Authorization", format!("Bearer {tok}"))
            .body(Body::empty())
            .unwrap();
        let get_resp = app.clone().oneshot(get_req).await.unwrap();
        assert_eq!(get_resp.status(), StatusCode::OK);
        let downloaded = get_resp.into_body().collect().await.unwrap().to_bytes();
        assert_eq!(downloaded.as_ref(), valid_avatar.as_slice());

        // DELETE 清空云端头像并删除磁盘文件
        let (st_del, _) =
            send_json(&app, "DELETE", "/api/v1/profile/avatar", Some(&tok), None).await;
        assert_eq!(st_del, StatusCode::OK);
        assert!(!tmp.path().join("avatars").join(format!("{uid}.webp")).exists());

        let after_del_get = Request::builder()
            .method("GET")
            .uri("/api/v1/profile/avatar")
            .header("Authorization", format!("Bearer {tok}"))
            .body(Body::empty())
            .unwrap();
        assert_eq!(
            app.clone().oneshot(after_del_get).await.unwrap().status(),
            StatusCode::NOT_FOUND
        );
    }
}
