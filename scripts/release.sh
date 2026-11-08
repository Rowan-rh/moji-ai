#!/usr/bin/env bash
# 默记 MoJi 正式版发布脚本：校验 → 打正式签名包 → 打 Tag → 创建 GitHub Release 并上传 moji.apk。
#
# 用法：
#   scripts/release.sh                     # 正式发布（必须在与 origin/main 一致的干净 main 上）
#   scripts/release.sh --notes notes.md    # 用指定文件作为更新说明（默认按 PR 自动生成）
#   scripts/release.sh --dry-run           # 只构建并校验签名，不打 Tag、不发布，可在任意分支运行
#
# 依赖：git、gh（已登录 Rowan-rh）、Android SDK build-tools（apksigner）、local.properties 中的正式签名配置。
set -euo pipefail

REPO="Rowan-rh/moji-ai"
OWNER="${REPO%%/*}"
ASSET_NAME="moji.apk"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DRY_RUN=0
NOTES_FILE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --notes) NOTES_FILE="${2:?--notes 需要一个文件路径}"; shift 2 ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

die() { echo "✗ $*" >&2; exit 1; }
info() { echo "→ $*"; }

# 从 local.properties 或同名环境变量读取配置（与 app/build.gradle.kts 规则一致）
prop() {
  local key="$1" value=""
  if [[ -f local.properties ]]; then
    value="$(grep -E "^[[:space:]]*${key}[[:space:]]*=" local.properties | tail -n1 | cut -d= -f2- | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')" || true
  fi
  if [[ -z "$value" && "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
    value="${!key:-}"
  fi
  printf '%s' "$value"
}

# ---------- 1. 版本号 ----------
VERSION_NAME="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' app/build.gradle.kts | head -n1)"
VERSION_CODE="$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' app/build.gradle.kts | head -n1)"
[[ -n "$VERSION_NAME" && -n "$VERSION_CODE" ]] || die "无法从 app/build.gradle.kts 读取 versionName / versionCode"
TAG="v${VERSION_NAME}"
info "待发布版本：${TAG}（versionCode ${VERSION_CODE}）"

# ---------- 2. 签名配置 ----------
for key in MOJI_RELEASE_STORE_FILE MOJI_RELEASE_STORE_PASSWORD MOJI_RELEASE_KEY_ALIAS MOJI_RELEASE_KEY_PASSWORD; do
  [[ -n "$(prop "$key")" ]] || die "缺少签名配置 ${key}（写入 local.properties，见 README「发布正式版」）"
done
STORE_FILE="$(prop MOJI_RELEASE_STORE_FILE)"
[[ "$STORE_FILE" = /* ]] || STORE_FILE="$ROOT/$STORE_FILE"
[[ -f "$STORE_FILE" ]] || die "签名证书不存在：$STORE_FILE"

# ---------- 3. 仓库与远程安全校验 ----------
git remote get-url origin | grep -qiE "github\.com[:/]${REPO}(\.git)?$" \
  || die "origin 不是 ${REPO}：$(git remote get-url origin)"

if [[ $DRY_RUN -eq 0 ]]; then
  command -v gh >/dev/null || die "未安装 gh"
  [[ "$(gh api user --jq .login)" == "$OWNER" ]] || die "gh 当前登录账号不是 ${OWNER}"
  [[ "$(git branch --show-current)" == "main" ]] || die "请在 main 分支上发布（发布 PR 合并后 git switch main && git pull）"
  [[ -z "$(git status --porcelain --untracked-files=no)" ]] || die "工作区有未提交的改动"
  git fetch origin main --tags --quiet
  [[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || die "本地 main 与 origin/main 不一致，请先 git pull"
  git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null && die "Tag ${TAG} 已存在；已推送的 Tag 不移动，请先升 versionName / versionCode"

  LATEST_TAG="$(gh release view --repo "$REPO" --json tagName --jq .tagName 2>/dev/null || true)"
  if [[ -n "$LATEST_TAG" ]]; then
    NEWEST="$(printf '%s\n%s\n' "${LATEST_TAG#v}" "$VERSION_NAME" | sort -V | tail -n1)"
    [[ "$NEWEST" == "$VERSION_NAME" && "${LATEST_TAG#v}" != "$VERSION_NAME" ]] \
      || die "版本 ${VERSION_NAME} 不高于已发布的 ${LATEST_TAG}，用户端不会收到更新"
    info "当前最新 Release：${LATEST_TAG}"
  fi
  [[ -z "$NOTES_FILE" || -f "$NOTES_FILE" ]] || die "更新说明文件不存在：$NOTES_FILE"
else
  info "dry-run：跳过分支、工作区与发布校验"
fi

# ---------- 4. 构建 ----------
# AGP 8.7 的 lint 不识别 JDK 22+ 的版本号，release 构建会在 lintVitalAnalyzeRelease 崩溃，因此改用 JDK 21/17
java_major() { "$1" -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -n1; }
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if [[ "$(java_major "$JAVA_BIN")" -gt 21 ]]; then
  ALT_HOME=""
  for v in 21 17; do
    ALT_HOME="$(/usr/libexec/java_home -v "$v" 2>/dev/null || true)"
    [[ -n "$ALT_HOME" && "$(java_major "$ALT_HOME/bin/java")" == "$v" ]] && break
    ALT_HOME=""
  done
  [[ -n "$ALT_HOME" ]] || die "当前 JDK 版本过高，请安装 JDK 21 或 17 并设置 JAVA_HOME"
  export JAVA_HOME="$ALT_HOME"
  info "使用 JDK：$JAVA_HOME"
fi

info "构建正式包（./gradlew clean assembleRelease）"
./gradlew clean assembleRelease --quiet
APK="app/build/outputs/apk/release/app-release.apk"
[[ -f "$APK" ]] || die "未生成已签名的 ${APK}（若只有 app-release-unsigned.apk，说明签名配置未生效）"

# ---------- 5. 签名校验 ----------
SDK_DIR="$(prop sdk.dir)"
SDK_DIR="${SDK_DIR:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}}"
APKSIGNER="$(ls -d "$SDK_DIR"/build-tools/*/ 2>/dev/null | sort -V | tail -n1)apksigner"
[[ -x "$APKSIGNER" ]] || die "找不到 apksigner（Android SDK build-tools）：$APKSIGNER"
CERT_SHA256="$("$APKSIGNER" verify --print-certs "$APK" | sed -nE 's/.*certificate SHA-256 digest: ([0-9a-f]+).*/\1/p' | head -n1)"
[[ -n "$CERT_SHA256" ]] || die "APK 签名校验失败"
info "签名证书 SHA-256：${CERT_SHA256}"

EXPECTED_SHA256="$(prop MOJI_RELEASE_CERT_SHA256 | tr -d ': ' | tr 'A-F' 'a-f')"
if [[ -n "$EXPECTED_SHA256" ]]; then
  [[ "$CERT_SHA256" == "$EXPECTED_SHA256" ]] \
    || die "签名证书与 MOJI_RELEASE_CERT_SHA256 不一致，用此包覆盖安装会失败"
else
  echo "! 建议把上面的 SHA-256 写入 local.properties 的 MOJI_RELEASE_CERT_SHA256，防止误用其他证书" >&2
fi

mkdir -p build/dist
cp "$APK" "build/dist/${ASSET_NAME}"
info "安装包：build/dist/${ASSET_NAME}（$(du -h "build/dist/${ASSET_NAME}" | awk '{print $1}')）"

if [[ $DRY_RUN -eq 1 ]]; then
  info "dry-run 完成，未打 Tag、未发布"
  exit 0
fi

# ---------- 6. 打 Tag 并发布 ----------
info "打 Tag ${TAG} 并推送"
git tag -a "$TAG" -m "release: ${TAG}"
git push origin "refs/tags/${TAG}"

NOTES_ARGS=(--generate-notes)
[[ -n "$NOTES_FILE" ]] && NOTES_ARGS=(--notes-file "$NOTES_FILE")

info "创建 GitHub Release 并上传 ${ASSET_NAME}"
gh release create "$TAG" "build/dist/${ASSET_NAME}" \
  --repo "$REPO" --verify-tag --latest --title "$TAG" "${NOTES_ARGS[@]}"

echo
echo "✓ 已发布 ${TAG}"
echo "  最新版直链：https://github.com/${REPO}/releases/latest/download/${ASSET_NAME}"
echo "  发布页：    https://github.com/${REPO}/releases/tag/${TAG}"
