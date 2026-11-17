use rand::RngCore;
use std::env;
use std::fs;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};

#[derive(Clone, Debug)]
pub struct ServerConfig {
    pub bind_addr: SocketAddr,
    pub data_dir: PathBuf,
    /// 可选的家庭/私有服务器接入密钥：配置后，所有密钥注册与令牌签发请求均需通过常量时间比对校验，防止公网陌生人滥用 ECS 存储
    pub server_access_code: Option<String>,
    /// 用于签发无状态 Bearer 会话令牌（30天有效期）的 32 字节密钥；若未通过环境变量显式指定，首次启动自动生成并持久化在 data_dir/.token_secret
    pub token_secret: Vec<u8>,
    /// 每个用户保留的历史快照回滚版本上限
    pub max_history_revisions: usize,
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

        Ok(Self {
            bind_addr,
            data_dir,
            server_access_code,
            token_secret,
            max_history_revisions,
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
            max_history_revisions: 5,
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
        if decoded.len() >= 32 {
            return Ok(decoded);
        }
    }

    let mut random_bytes = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut random_bytes);
    let hex_secret = hex::encode(random_bytes);
    fs::write(&secret_file, &hex_secret)
        .map_err(|e| format!("写入 {:?} 失败: {e}", secret_file))?;

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = fs::set_permissions(&secret_file, fs::Permissions::from_mode(0o600));
    }

    Ok(random_bytes.to_vec())
}
