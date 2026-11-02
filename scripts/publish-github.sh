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
#   GITHUB_REPO          默认 yuchen0x1/xianyu-seller-console
#   GITHUB_TARGET_BRANCH 默认 main（创建 Release 时的目标分支）
#   RELEASE_SIGN_KEY_FILE / RELEASE_SIGN_PRIVATE_KEY  官方渠道发布物签名私钥
#   SKIP_BUILD=1         跳过构建，仅发布 target/ 下已存在的产物
#   SPLIT_JAR=1          可选，显式启用旧版分卷兼容（默认关闭）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

REPO_SLUG="${GITHUB_REPO:-yuchen0x1/xianyu-seller-console}"
[[ "$REPO_SLUG" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || { echo "GITHUB_REPO 格式无效，应为 owner/repository" >&2; exit 1; }
[[ "$REPO_SLUG" != *..* ]] || { echo "GITHUB_REPO 不允许包含连续点号" >&2; exit 1; }
API_BASE="https://api.github.com/repos/${REPO_SLUG}"
UPLOAD_BASE="https://uploads.github.com/repos/${REPO_SLUG}"
TARGET_BRANCH="${GITHUB_TARGET_BRANCH:-main}"
TOKEN="${GITHUB_TOKEN:?需要 GITHUB_TOKEN 环境变量（GitHub Contents 写权限）}"
AUTH=(-H "Authorization: Bearer ${TOKEN}" -H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2022-11-28')

log() { echo -e "\033[1;34m[release]\033[0m $*"; }
die() { echo -e "\033[1;31m[release] $*\033[0m" >&2; exit 1; }

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  VERSION="$(./mvnw --batch-mode --no-transfer-progress help:evaluate -Dexpression=project.version -q -DforceStdout)"
fi
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "版本号必须是 MAJOR.MINOR.PATCH: $VERSION"
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

IS_OFFICIAL_REPO=0
[ "$REPO_SLUG" = "yuchen0x1/xianyu-seller-console" ] && IS_OFFICIAL_REPO=1
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

RELEASE_JSON="$(curl -fsSL "${AUTH[@]}" "${API_BASE}/releases/tags/${TAG}" 2>/dev/null || true)"
if [ -n "$RELEASE_JSON" ]; then
  RELEASE_ID="$(extract_release_id <<<"$RELEASE_JSON")"
  log "Release ${TAG} 已存在 (id=${RELEASE_ID})，将补传附件"
else
  log "创建 Release ${TAG}..."
  RELEASE_BODY_FILE="$(mktemp "${TMPDIR:-/tmp}/release-body-XXXXXX.md")"
  trap 'rm -f "$RELEASE_BODY_FILE"' EXIT
  awk -v ver="Version ${VERSION}" '$0 ~ "^# " ver {flag=1;next} /^# Version/&&flag{exit} flag{print}' CHANGELOG.md 2>/dev/null > "$RELEASE_BODY_FILE"
  [ -s "$RELEASE_BODY_FILE" ] || printf 'XianYu Seller Console %s\n' "$TAG" > "$RELEASE_BODY_FILE"
  RELEASE_JSON="$(node -e 'const fs=require("fs"); const body=fs.readFileSync(process.argv[1],"utf8"); process.stdout.write(JSON.stringify({tag_name:process.argv[2],name:process.argv[2],body,target_commitish:process.argv[3]}))' "$RELEASE_BODY_FILE" "$TAG" "$TARGET_BRANCH" | curl -fsSL -X POST "${AUTH[@]}" -H 'Content-Type: application/json' "${API_BASE}/releases" --data-binary @- )"
  RELEASE_ID="$(extract_release_id <<<"$RELEASE_JSON")"
  rm -f "$RELEASE_BODY_FILE"
  trap - EXIT
fi

# GitHub 返回资产 id，删除同名资产后再上传，保证重复发布幂等。
ASSETS_JSON="$(curl -fsSL "${AUTH[@]}" "${API_BASE}/releases/${RELEASE_ID}/assets?per_page=100")"
for FILE in "${UPLOAD_FILES[@]}"; do
  NAME="$(basename "$FILE")"
  OLD_IDS="$(node -e 'const l=JSON.parse(process.argv[1]);for(const a of l)if(a&&a.name===process.argv[2]&&a.id!=null)console.log(a.id)' "$ASSETS_JSON" "$NAME")"
  for OLD_ID in $OLD_IDS; do
    log "删除同名旧资产: ${NAME} (id=${OLD_ID})"
    curl -fsSL -X DELETE "${AUTH[@]}" "${API_BASE}/releases/assets/${OLD_ID}" >/dev/null
  done
done

for FILE in "${UPLOAD_FILES[@]}"; do
  NAME="$(basename "$FILE")"
  log "上传资产: ${NAME} ($(du -h "$FILE" | cut -f1))"
  curl -fsSL -X POST "${AUTH[@]}" -H 'Content-Type: application/octet-stream' \
    "${UPLOAD_BASE}/releases/${RELEASE_ID}/assets?name=${NAME}" --data-binary "@${FILE}" >/dev/null \
    || die "资产上传失败: ${NAME}"
done

log "校验发布结果..."
FINAL_JSON="$(curl -fsSL "${AUTH[@]}" "${API_BASE}/releases/tags/${TAG}")"
for FILE in "${UPLOAD_FILES[@]}"; do
  NAME="$(basename "$FILE")"
  node -e 'const r=JSON.parse(process.argv[1]);if(!(r.assets||[]).some(a=>a.name===process.argv[2]))process.exit(1)' "$FINAL_JSON" "$NAME" \
    || die "发布结果缺少资产: ${NAME}"
done
log "✓ ${TAG} 发布完成，资产齐全: ${UPLOAD_FILES[*]##*/}"
