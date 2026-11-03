#!/usr/bin/env bash
# 构建并发布 GitHub Release（官方发布源；产物形状与 install.sh / 应用内更新器一致）：
#   - 前端 npm build → 产物落入 src/main/resources/static
#   - 后端 mvn package → target/XianYuSellerConsole-<version>.jar
#   - SHA-256 校验文件与可选 Ed25519 签名随发布上传
#
# GitHub 单个 Release 资产上限远高于本项目 JAR，默认不分卷。需要兼容旧分卷
# 安装包时可显式设置 SPLIT_JAR=1；不要为绕过 Gitee 的 100MB 限制而自动分卷。
#
# 用法:
#   GITHUB_TOKEN=xxx scripts/publish-github.sh            # 版本取自 pom.xml
#   GITHUB_TOKEN=xxx scripts/publish-github.sh 3.4.0      # 显式指定版本
#
# 环境变量:
#   GITHUB_TOKEN         必填，GitHub fine-grained token（Contents: read/write）；仅经 Authorization 头传递
#   GITHUB_REPO          默认 yuchen0x1/Xianyu-Assistant
#   GITHUB_TARGET_BRANCH 默认 main（创建 Release 时的目标分支）
#   RELEASE_SIGN_KEY_FILE / RELEASE_SIGN_PRIVATE_KEY  官方渠道发布物签名私钥
#   SKIP_BUILD=1         跳过构建，仅发布 target/ 下已存在的产物
#   SPLIT_JAR=1          可选，显式启用旧版分卷兼容（默认关闭）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

REPO_SLUG="${GITHUB_REPO:-yuchen0x1/Xianyu-Assistant}"
[[ "$REPO_SLUG" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || { echo "GITHUB_REPO 格式无效，应为 owner/repository" >&2; exit 1; }
[[ "$REPO_SLUG" != *..* ]] || { echo "GITHUB_REPO 不允许包含连续点号" >&2; exit 1; }
API_BASE="https://api.github.com/repos/${REPO_SLUG}"
UPLOAD_BASE="https://uploads.github.com/repos/${REPO_SLUG}"
TARGET_BRANCH="${GITHUB_TARGET_BRANCH:-main}"
TOKEN="${GITHUB_TOKEN:?需要 GITHUB_TOKEN 环境变量（GitHub Contents 写权限）}"

TEMP_FILES=()
cleanup() {
  if ((${#TEMP_FILES[@]})); then
    rm -f -- "${TEMP_FILES[@]}"
  fi
}
trap cleanup EXIT

# 将令牌写入仅当前用户可读的临时 curl 配置，避免令牌进入 curl argv、进程列表或日志。
CURL_CONFIG="$(mktemp "${TMPDIR:-/tmp}/github-curl-XXXXXX.conf")"
chmod 600 "$CURL_CONFIG"
TEMP_FILES+=("$CURL_CONFIG")
{
  printf 'header = "Authorization: Bearer %s"\n' "$TOKEN"
  printf 'header = "Accept: application/vnd.github+json"\n'
  printf 'header = "X-GitHub-Api-Version: 2022-11-28"\n'
} > "$CURL_CONFIG"

IS_OFFICIAL_REPO=0
[ "${REPO_SLUG,,}" = "yuchen0x1/xianyu-assistant" ] && IS_OFFICIAL_REPO=1

log() { echo -e "\033[1;34m[release]\033[0m $*"; }
die() { echo -e "\033[1;31m[release] $*\033[0m" >&2; exit 1; }

# 返回 HTTP 状态码并把响应体写入指定文件。网络失败返回非零，
# HTTP 错误仍返回状态码，调用方据此区分 404 与权限/限流/服务端故障。
github_api() {
  local method="$1"
  local url="$2"
  local output="$3"
  shift 3
  local status curl_exit=0
  status="$(curl -sS --config "$CURL_CONFIG" --connect-timeout 20 --max-time 300 --proto '=https' --tlsv1.2 \
    -X "$method" "$@" -o "$output" -w '%{http_code}' "$url")" || curl_exit=$?
  if [ "$curl_exit" -ne 0 ]; then
    rm -f -- "$output"
    return "$curl_exit"
  fi
  printf '%s' "$status"
}

VERSION="${1:-}"
PROJECT_VERSION="$(./mvnw --batch-mode --no-transfer-progress help:evaluate -Dexpression=project.version -q -DforceStdout)"
if [ -z "$VERSION" ]; then
  VERSION="$PROJECT_VERSION"
fi
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "版本号必须是 MAJOR.MINOR.PATCH: $VERSION"
[ "$PROJECT_VERSION" = "$VERSION" ] || die "显式版本($VERSION)与 pom.xml 版本($PROJECT_VERSION)不一致"
FRONTEND_VERSION="$(node -p "require('./vue-code/package.json').version")"
[ "$FRONTEND_VERSION" = "$VERSION" ] || die "vue-code/package.json 版本($FRONTEND_VERSION)与 $VERSION 不一致"
grep -q "^version: ${VERSION}+" mobile/pubspec.yaml || die "mobile/pubspec.yaml 版本与 $VERSION 不一致"
TAG="v${VERSION}"
log "发布版本 ${TAG} → ${API_BASE}"

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  log "构建前端..."
  (cd vue-code && npm ci && npm run build)
  test -f src/main/resources/static/index.html || die "前端构建未产出 static/index.html"
  test -d src/main/resources/static/assets || die "前端构建未产出 static/assets"
  log "构建后端 JAR..."
  ./mvnw --batch-mode --no-transfer-progress clean package
fi

JAR="target/XianYuSellerConsole-${VERSION}.jar"
[ -f "$JAR" ] || die "未找到构建产物 ${JAR}"
SHA256_FILE="${JAR}.sha256"
sha256sum "$JAR" | tr -d '\n' > "$SHA256_FILE"

SIGN_FILE=""
if [ -n "${RELEASE_SIGN_KEY_FILE:-}" ] || [ -n "${RELEASE_SIGN_PRIVATE_KEY:-}" ]; then
  log "对发布物进行 Ed25519 签名..."
  bash scripts/sign-release.sh sign "$JAR"
  SIGN_FILE="${JAR}.sig"
  [ -f "$SIGN_FILE" ] || die "签名产物未生成: ${SIGN_FILE}"
elif [ "$IS_OFFICIAL_REPO" = "1" ]; then
  die "官方渠道发布必须提供签名私钥（RELEASE_SIGN_KEY_FILE 或 RELEASE_SIGN_PRIVATE_KEY）"
else
  log "警告: 社区渠道未配置签名私钥，发布物将不带 .jar.sig"
fi

UPLOAD_FILES=("$JAR" "$SHA256_FILE")
[ -n "$SIGN_FILE" ] && UPLOAD_FILES+=("$SIGN_FILE")
# GitHub 资产上限足以容纳当前 JAR；只在显式要求时生成旧版分卷。
if [ "${SPLIT_JAR:-0}" = "1" ]; then
  rm -f "${JAR}".split-*
  split -b 95m -d -a 2 "$JAR" "${JAR}.split-"
  UPLOAD_FILES=("${JAR}".split-* "$SHA256_FILE")
  [ -n "$SIGN_FILE" ] && UPLOAD_FILES+=("$SIGN_FILE")
fi

extract_release_id() {
  node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{const r=JSON.parse(d);if(!r||r.id==null){process.stderr.write("Release 响应中缺少 id\n");process.exit(1)}process.stdout.write(String(r.id))})'
}

RELEASE_LOOKUP_FILE="$(mktemp "${TMPDIR:-/tmp}/release-lookup-XXXXXX.json")"
TEMP_FILES+=("$RELEASE_LOOKUP_FILE")
RELEASE_STATUS="$(github_api GET "${API_BASE}/releases/tags/${TAG}" "$RELEASE_LOOKUP_FILE")" || die "无法连接 GitHub 查询 Release"
case "$RELEASE_STATUS" in
  200)
    RELEASE_ID="$(extract_release_id <"$RELEASE_LOOKUP_FILE")"
    log "Release ${TAG} 已存在 (id=${RELEASE_ID})，将补传附件"
    ;;
  404)
    log "创建 Release ${TAG}..."
    RELEASE_BODY_FILE="$(mktemp "${TMPDIR:-/tmp}/release-body-XXXXXX.md")"
    TEMP_FILES+=("$RELEASE_BODY_FILE")
    RELEASE_CREATE_FILE="$(mktemp "${TMPDIR:-/tmp}/release-create-XXXXXX.json")"
    TEMP_FILES+=("$RELEASE_CREATE_FILE")
    awk -v ver="Version ${VERSION}" '$0 ~ "^# " ver {flag=1;next} /^# Version/&&flag{exit} flag{print}' CHANGELOG.md 2>/dev/null > "$RELEASE_BODY_FILE"
    [ -s "$RELEASE_BODY_FILE" ] || printf 'XianYu Seller Console %s\n' "$TAG" > "$RELEASE_BODY_FILE"
    node -e 'const fs=require("fs"); const body=fs.readFileSync(process.argv[1],"utf8"); process.stdout.write(JSON.stringify({tag_name:process.argv[2],name:process.argv[2],body,target_commitish:process.argv[3]}))' "$RELEASE_BODY_FILE" "$TAG" "$TARGET_BRANCH" > "$RELEASE_BODY_FILE.json"
    TEMP_FILES+=("$RELEASE_BODY_FILE.json")
    RELEASE_STATUS="$(github_api POST "${API_BASE}/releases" "$RELEASE_CREATE_FILE" -H 'Content-Type: application/json' --data-binary "@$RELEASE_BODY_FILE.json")" || die "无法连接 GitHub 创建 Release"
    [ "$RELEASE_STATUS" = "201" ] || die "创建 Release 失败（HTTP ${RELEASE_STATUS}）"
    RELEASE_ID="$(extract_release_id <"$RELEASE_CREATE_FILE")"
    ;;
  401|403) die "查询 Release 被拒绝（HTTP ${RELEASE_STATUS}，请检查 GITHUB_TOKEN 权限）" ;;
  429) die "GitHub API 请求频率受限（HTTP 429），请稍后重试" ;;
  5??) die "GitHub 服务暂时不可用（HTTP ${RELEASE_STATUS}）" ;;
  *) die "查询 Release 失败（HTTP ${RELEASE_STATUS}）" ;;
esac

# GitHub 返回资产 id，删除同名资产后再上传，保证重复发布幂等。
ASSETS_FILE="$(mktemp "${TMPDIR:-/tmp}/release-assets-XXXXXX.json")"
TEMP_FILES+=("$ASSETS_FILE")
ASSETS_STATUS="$(github_api GET "${API_BASE}/releases/${RELEASE_ID}/assets?per_page=100" "$ASSETS_FILE")" || die "无法连接 GitHub 获取资产列表"
[ "$ASSETS_STATUS" = "200" ] || die "获取 Release 资产失败（HTTP ${ASSETS_STATUS}）"
DESIRED_NAMES_FILE="$(mktemp "${TMPDIR:-/tmp}/release-names-XXXXXX.txt")"
TEMP_FILES+=("$DESIRED_NAMES_FILE")
for FILE in "${UPLOAD_FILES[@]}"; do basename "$FILE"; done > "$DESIRED_NAMES_FILE"
ARTIFACT_NAME="$(basename "$JAR")"
# 整包/分卷切换时清理同一 JAR 的互斥旧资产，避免安装器误选过期整包或分卷。
STALE_ASSETS="$(node -e 'const fs=require("fs");const l=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));const wanted=new Set(fs.readFileSync(process.argv[2],"utf8").split(/\\r?\\n/).filter(Boolean));const base=process.argv[3];const re=new RegExp("^"+base.replace(/[.*+?^${}()|[\\]\\\\]/g,"\\\\$&")+"(?:\\.split-[0-9]+)?(?:\\.sha256|\\.sig)?$");for(const a of l){if(a&&a.id!=null&&a.name&&re.test(a.name)&&!wanted.has(a.name))console.log(a.id+"\\t"+a.name)}' "$ASSETS_FILE" "$DESIRED_NAMES_FILE" "$ARTIFACT_NAME")"
while IFS=$'\t' read -r OLD_ID OLD_NAME; do
  [ -n "$OLD_ID" ] || continue
  log "删除互斥旧资产: ${OLD_NAME} (id=${OLD_ID})"
  DELETE_RESPONSE="$(mktemp "${TMPDIR:-/tmp}/release-delete-XXXXXX.json")"
  TEMP_FILES+=("$DELETE_RESPONSE")
  DELETE_STATUS="$(github_api DELETE "${API_BASE}/releases/assets/${OLD_ID}" "$DELETE_RESPONSE")" || die "无法连接 GitHub 删除旧资产: ${OLD_NAME}"
  [ "$DELETE_STATUS" = "204" ] || die "删除旧资产失败（HTTP ${DELETE_STATUS}）: ${OLD_NAME}"
done <<< "$STALE_ASSETS"
for FILE in "${UPLOAD_FILES[@]}"; do
  NAME="$(basename "$FILE")"
  OLD_IDS="$(node -e 'const l=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));for(const a of l)if(a&&a.name===process.argv[2]&&a.id!=null)console.log(a.id)' "$ASSETS_FILE" "$NAME")"
  for OLD_ID in $OLD_IDS; do
    log "删除同名旧资产: ${NAME} (id=${OLD_ID})"
    DELETE_RESPONSE="$(mktemp "${TMPDIR:-/tmp}/release-delete-XXXXXX.json")"
    TEMP_FILES+=("$DELETE_RESPONSE")
    DELETE_STATUS="$(github_api DELETE "${API_BASE}/releases/assets/${OLD_ID}" "$DELETE_RESPONSE")" || die "无法连接 GitHub 删除旧资产: ${NAME}"
    [ "$DELETE_STATUS" = "204" ] || die "删除旧资产失败（HTTP ${DELETE_STATUS}）: ${NAME}"
  done
done

for FILE in "${UPLOAD_FILES[@]}"; do
  NAME="$(basename "$FILE")"
  ENCODED_NAME="$(node -p 'encodeURIComponent(process.argv[1])' "$NAME")"
  UPLOAD_RESPONSE="$(mktemp "${TMPDIR:-/tmp}/release-upload-XXXXXX.json")"
  TEMP_FILES+=("$UPLOAD_RESPONSE")
  log "上传资产: ${NAME} ($(du -h "$FILE" | cut -f1))"
  UPLOAD_STATUS="$(github_api POST "${UPLOAD_BASE}/releases/${RELEASE_ID}/assets?name=${ENCODED_NAME}" "$UPLOAD_RESPONSE" -H 'Content-Type: application/octet-stream' --data-binary "@${FILE}")" || die "无法连接 GitHub 上传资产: ${NAME}"
  [ "$UPLOAD_STATUS" = "201" ] || die "资产上传失败（HTTP ${UPLOAD_STATUS}）: ${NAME}"
done

log "校验发布结果..."
FINAL_FILE="$(mktemp "${TMPDIR:-/tmp}/release-final-XXXXXX.json")"
TEMP_FILES+=("$FINAL_FILE")
FINAL_STATUS="$(github_api GET "${API_BASE}/releases/tags/${TAG}" "$FINAL_FILE")" || die "无法连接 GitHub 校验发布结果"
[ "$FINAL_STATUS" = "200" ] || die "校验发布结果失败（HTTP ${FINAL_STATUS}）"
for FILE in "${UPLOAD_FILES[@]}"; do
  NAME="$(basename "$FILE")"
  node -e 'const r=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));if(!(r.assets||[]).some(a=>a.name===process.argv[2]))process.exit(1)' "$FINAL_FILE" "$NAME" \
    || die "发布结果缺少资产: ${NAME}"
done
log "✓ ${TAG} 发布完成，资产齐全: ${UPLOAD_FILES[*]##*/}"
