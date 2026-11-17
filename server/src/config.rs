use rand::RngCore;
use std::env;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use uuid::Uuid;

#[derive(Clone, Debug)]
pub struct ServerConfig {
    pub bind_addr: SocketAddr,
    pub data_dir: PathBuf,
    /// 可选的家庭/私有服务器接入密钥：配置后，所有密钥注册与令牌签发请求均需通过定长哈希常量时间比对校验
    pub server_access_code: Option<String>,
    /// 用于签发无状态 Bearer 会话令牌（30天有效期）的密钥；若未通过环境变量指定，首次启动以 0600 权限原子生成并保存在 data_dir/.token_secret
    pub token_secret: Vec<u8>,
    /// 每个用户保留的历史快照回滚版本上限
    pub max_history_revisions: usize,
    /// 是否信任来自本机或容器内网反向代理的 X-Real-IP / X-Forwarded-For 头
    pub trust_proxy_headers: bool,
}

impl ServerConfig {
    pub fn from_env() -> Result<Self, String> {
        let bind_raw = env::var("MOJI_BIND_ADDR").unwrap_or_else(|_| "0.0.0.0:8787".to_string());
        let bind_addr: SocketAddr = bind_raw
            .parse()
            .map_err(|e| format!("无效的 MOJI_BIND_ADDR '{bind_raw}': {e}"))?;

        let data_dir = PathBuf::from(env::var("MOJI_DATA_DIR").unwrap_or_else(|_| "./data".to_string()));
        fs::create_dir_all(&data_dir)
            .map_err(|e| format!("无法创建数据目录 {:?}: {e}", data_dir))?;

        let server_access_code = env::var("MOJI_SERVER_ACCESS_CODE")
            .ok()
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty());

        let token_secret = load_or_create_token_secret(&data_dir)?;

        let max_history_revisions = env::var("MOJI_MAX_HISTORY_REVISIONS")
            .ok()
            .and_then(|v| v.parse::<usize>().ok())
            .unwrap_or(10)
            .clamp(1, 50);

        let trust_proxy_headers = env::var("MOJI_TRUST_PROXY_HEADERS")
            .ok()
            .map(|v| matches!(v.trim().to_ascii_lowercase().as_str(), "1" | "true" | "yes"))
            .unwrap_or(false);

        Ok(Self {
            bind_addr,
            data_dir,
            server_access_code,
            token_secret,
            max_history_revisions,
            trust_proxy_headers,
        })
    }

    #[cfg(test)]
    pub fn for_test(data_dir: PathBuf, access_code: Option<&str>) -> Self {
        fs::create_dir_all(&data_dir).expect("create test data dir");
        Self {
            bind_addr: "127.0.0.1:0".parse().unwrap(),
            data_dir,
            server_access_code: access_code.map(|s| s.to_string()),
            token_secret: b"0123456789abcdef0123456789abcdef".to_vec(),
            max_history_revisions: 3,
            trust_proxy_headers: false,
        }
    }
}

fn load_or_create_token_secret(data_dir: &Path) -> Result<Vec<u8>, String> {
    if let Ok(raw) = env::var("MOJI_TOKEN_SECRET") {
        let trimmed = raw.trim();
        if trimmed.len() >= 16 {
            return Ok(trimmed.as_bytes().to_vec());
        } else if !trimmed.is_empty() {
            return Err("环境变量 MOJI_TOKEN_SECRET 长度至少需要 16 个字符".to_string());
        }
    }

    let secret_file = data_dir.join(".token_secret");
    if secret_file.exists() {
        let content = fs::read_to_string(&secret_file)
            .map_err(|e| format!("读取 {:?} 失败: {e}", secret_file))?;
        let decoded = hex::decode(content.trim())
            .map_err(|e| format!("解析 {:?} 十六进制密钥失败: {e}", secret_file))?;
        if decoded.len() < 32 {
            return Err(format!(
                "密钥文件 {:?} 长度不足 32 字节（已损坏），请修复或删除后重启重新生成",
                secret_file
            ));
        }
        return Ok(decoded);
    }

    let mut random_bytes = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut random_bytes);
    let hex_secret = hex::encode(random_bytes);

    let tmp_path = data_dir.join(format!(".token_secret.tmp.{}", Uuid::new_v4()));
    let write_res = (|| -> std::io::Result<()> {
        let mut opts = OpenOptions::new();
        opts.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            opts.mode(0o600);
        }
        let mut file = opts.open(&tmp_path)?;
        file.write_all(hex_secret.as_bytes())?;
        file.sync_all()?;
        fs::rename(&tmp_path, &secret_file)?;
        Ok(())
    })();

    if let Err(e) = write_res {
        let _ = fs::remove_file(&tmp_path);
        return Err(format!("原子创建密钥文件 {:?} 失败: {e}", secret_file));
    }

    Ok(random_bytes.to_vec())
}
