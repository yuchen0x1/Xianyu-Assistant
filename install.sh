#!/bin/bash
#
# XianYu Seller Console installer
# 使用方法: 下载 https://raw.githubusercontent.com/yuchen0x1/xianyu-seller-console/main/install.sh 后执行 bash install.sh
#

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# 配置
INSTALL_DIR="${HOME}/xianyu-seller-console"
PORT="${PORT:-12400}"
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx512m}"
JDK_VERSION="21"

# GitHub API（唯一发布源）
GITHUB_REPO="${GITHUB_REPO:-yuchen0x1/xianyu-seller-console}"
if [[ ! "$GITHUB_REPO" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || [[ "$GITHUB_REPO" == *..* ]]; then
    echo -e "${RED}GITHUB_REPO 格式无效，应为 owner/repository${NC}" >&2
    exit 1
fi
GITHUB_API="https://api.github.com/repos/${GITHUB_REPO}/releases/latest"
# 私有仓库安装时可提供令牌；令牌不拼入 URL、命令参数或日志。
if [[ "${GITHUB_TOKEN:-}" == *$'\r'* || "${GITHUB_TOKEN:-}" == *$'\n'* ]]; then
    echo -e "${RED}GITHUB_TOKEN 含非法换行符，已拒绝使用${NC}" >&2
    exit 1
fi
INSTALL_TEMP_FILES=()
cleanup_install_temp_files() {
    if [ "${#INSTALL_TEMP_FILES[@]}" -gt 0 ]; then
        rm -f -- "${INSTALL_TEMP_FILES[@]}"
    fi
}
trap cleanup_install_temp_files EXIT
GITHUB_API_CURL_CONFIG=$(mktemp "${TMPDIR:-/tmp}/xianyu-github-api-XXXXXX.conf")
chmod 600 "$GITHUB_API_CURL_CONFIG"
INSTALL_TEMP_FILES+=("$GITHUB_API_CURL_CONFIG")
{
    if [ -n "${GITHUB_TOKEN:-}" ]; then
        printf 'header = "Authorization: Bearer %s"\n' "$GITHUB_TOKEN"
    fi
    printf 'header = "Accept: application/vnd.github+json"\n'
    printf 'header = "X-GitHub-Api-Version: 2022-11-28"\n'
} > "$GITHUB_API_CURL_CONFIG"
GITHUB_PUBLIC_CURL_CONFIG=$(mktemp "${TMPDIR:-/tmp}/xianyu-github-public-XXXXXX.conf")
chmod 600 "$GITHUB_PUBLIC_CURL_CONFIG"
INSTALL_TEMP_FILES+=("$GITHUB_PUBLIC_CURL_CONFIG")
{
    printf 'header = "Accept: application/octet-stream"\n'
} > "$GITHUB_PUBLIC_CURL_CONFIG"
GITHUB_API_ASSET_PREFIX="https://api.github.com/repos/${GITHUB_REPO}/releases/assets/"

# 官方发布签名验证公钥（Ed25519, X.509 SPKI DER 的 Base64）。
# 私钥仅发布方保管（CI Secret/离线），不随任何发布物分发；
# SHA-256 校验文件与安装包同源，发布通道被入侵时无法同时伪造 Ed25519 签名。
RELEASE_SIGN_PUBLIC_KEY="MCowBQYDK2VwAyEA4BjYdeOn5znHC0utuw8PFFZkzmejLdVRYGQOzpUgK+M="

echo -e "${BLUE}"
echo "╔════════════════════════════════════════════════════════╗"
echo "║         XianYu Seller Console installer              ║"
echo "╚════════════════════════════════════════════════════════╝"
echo -e "${NC}"

# 检测操作系统
detect_os() {
    # 检测 Termux (Android)
    if [ -n "$TERMUX_VERSION" ] || [ -d "/data/data/com.termux" ]; then
        OS="termux"
    elif [ -f /etc/os-release ]; then
        . /etc/os-release
        OS=$ID
    elif [ "$(uname)" = "Darwin" ]; then
        OS="macos"
    else
        OS="unknown"
    fi
}

# 检查 JDK
check_jdk() {
    if command -v java &> /dev/null; then
        JAVA_VER=$(java -version 2>&1 | head -n 1 | awk -F '"' '{print $2}' | awk -F '.' '{print $1}')
        if [ "$JAVA_VER" = "1" ]; then
            JAVA_VER=$(java -version 2>&1 | head -n 1 | awk -F '"' '{print $2}' | awk -F '.' '{print $2}')
        fi
        return 0
    fi
    return 1
}

# 安装 JDK 21
install_jdk() {
    echo ""
    echo -e "${YELLOW}需要安装 JDK ${JDK_VERSION}${NC}"
    echo -e "${YELLOW}输入 y 确认安装，其他键取消${NC}"
    read -p "请选择: " confirm
    
    if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
        echo -e "${RED}已取消安装${NC}"
        exit 1
    fi
    
    echo -e "${YELLOW}正在安装 JDK ${JDK_VERSION}...${NC}"
    
    case $OS in
        ubuntu|debian)
            sudo apt-get update -qq
            sudo apt-get install -y openjdk-${JDK_VERSION}-jdk
            ;;
        centos|rhel|rocky|almalinux)
            sudo yum install -y java-${JDK_VERSION}-openjdk java-${JDK_VERSION}-openjdk-devel
            ;;
        fedora)
            sudo dnf install -y java-${JDK_VERSION}-openjdk java-${JDK_VERSION}-openjdk-devel
            ;;
        macos)
            if command -v brew &> /dev/null; then
                brew install openjdk@${JDK_VERSION}
            else
                echo -e "${RED}请先安装 Homebrew: https://brew.sh${NC}"
                exit 1
            fi
            ;;
        arch|manjaro)
            sudo pacman -S --noconfirm jdk-openjdk
            ;;
        termux)
            pkg update
            pkg install openjdk-21
            ;;
        *)
            echo -e "${RED}不支持的系统，请手动安装 JDK ${JDK_VERSION}${NC}"
            exit 1
            ;;
    esac
}

# 通过 GitHub assets API 下载单个资产。
# 只允许 HTTPS 的 GitHub API/资产主机；Authorization 仅发往 api.github.com，
# 所有跨主机重定向都逐跳校验，从根源上避免令牌泄露到 release-assets 等主机。
API_ASSET_REGEX="^https://api[.]github[.]com/repos/${GITHUB_REPO//./[.]}/releases/assets/[0-9]+$"
TRUSTED_ASSET_REGEX='^https://(release-assets[.]githubusercontent[.]com|objects[.]githubusercontent[.]com|github[.]com)(/[^[:space:]]*)?$'

is_trusted_github_url() {
    local URL="$1"
    [[ "$URL" =~ $API_ASSET_REGEX || "$URL" =~ $TRUSTED_ASSET_REGEX ]]
}

download_github_asset() {
    local URL="$1"
    local OUTPUT="$2"
    local TMP_FILE HEADER_FILE STATUS NEXT_URL HOP CURL_EXIT CURL_CONFIG

    TMP_FILE="${OUTPUT}.tmp.$$"
    rm -f "$TMP_FILE"
    for HOP in 1 2 3 4 5; do
        if [[ "$URL" =~ $API_ASSET_REGEX ]]; then
            CURL_CONFIG="$GITHUB_API_CURL_CONFIG"
        elif [[ "$URL" =~ $TRUSTED_ASSET_REGEX ]]; then
            CURL_CONFIG="$GITHUB_PUBLIC_CURL_CONFIG"
        else
            echo -e "${RED}拒绝不受信任的 GitHub 下载重定向: ${URL}${NC}" >&2
            rm -f "$TMP_FILE"
            return 1
        fi

        HEADER_FILE=$(mktemp)
        rm -f "$TMP_FILE"
        CURL_EXIT=0
        STATUS=$(curl -sS --config "$CURL_CONFIG" --connect-timeout 30 --max-time 300 --proto '=https' \
            --proto-redir '=https' --tlsv1.2 --max-redirs 0 -D "$HEADER_FILE" -o "$TMP_FILE" -w '%{http_code}' \
            "$URL") || CURL_EXIT=$?
        if [ "$CURL_EXIT" -ne 0 ]; then
            echo -e "${RED}GitHub 资产下载失败（HTTP ${STATUS:-unknown}）${NC}" >&2
            rm -f "$HEADER_FILE" "$TMP_FILE"
            return 1
        fi

        if [ "$STATUS" -ge 200 ] && [ "$STATUS" -lt 300 ]; then
            mv -f "$TMP_FILE" "$OUTPUT"
            rm -f "$HEADER_FILE"
            return 0
        fi
        if [ "$STATUS" != "301" ] && [ "$STATUS" != "302" ] && [ "$STATUS" != "303" ] \
            && [ "$STATUS" != "307" ] && [ "$STATUS" != "308" ]; then
            echo -e "${RED}GitHub 资产下载失败（HTTP ${STATUS:-unknown}）${NC}" >&2
            rm -f "$HEADER_FILE" "$TMP_FILE"
            return 1
        fi
        NEXT_URL=$(awk 'BEGIN { IGNORECASE=1 } /^Location:[[:space:]]*/ { sub(/^[^:]*:[[:space:]]*/, ""); sub(/\r$/, ""); print; exit }' "$HEADER_FILE")
        rm -f "$HEADER_FILE"
        if [ -z "$NEXT_URL" ]; then
            echo -e "${RED}GitHub 资产重定向缺少 Location${NC}" >&2
            rm -f "$TMP_FILE"
            return 1
        fi
        URL="$NEXT_URL"
    done
    echo -e "${RED}GitHub 资产重定向次数过多${NC}" >&2
    rm -f "$TMP_FILE"
    return 1
}

# 获取最新版本下载链接
get_latest_release() {
    echo ""
    echo -e "${YELLOW}正在从 GitHub 获取最新版本信息...${NC}"

    RELEASE_INFO=$(curl -sS --config "$GITHUB_API_CURL_CONFIG" --connect-timeout 10 --max-time 60 \
        --proto '=https' --proto-redir '=https' --tlsv1.2 --max-redirs 0 \
        "$GITHUB_API" 2>/dev/null || echo "")

    if [ -z "$RELEASE_INFO" ]; then
        echo -e "${RED}无法获取版本信息，请检查网络或 GitHub Release 权限${NC}"
        exit 1
    fi

    # 由 Node 一次解析顶层 tag_name 与同一 assets[] 对象的 name/url/browser_download_url，
    # 禁止通过 Release body 或字段文本伪造资产映射。
    RELEASE_PARSE_FILE=$(mktemp "${TMPDIR:-/tmp}/xianyu-release-parse-XXXXXX.tsv")
    chmod 600 "$RELEASE_PARSE_FILE"
    INSTALL_TEMP_FILES+=("$RELEASE_PARSE_FILE")
    if ! node -e '
const fs = require("fs");
const repo = process.argv[1];
const d = JSON.parse(fs.readFileSync(0, "utf8"));
const tag = d && d.tag_name;
if (!/^v[0-9]+\.[0-9]+\.[0-9]+$/.test(tag || "")) process.exit(2);
const assets = Array.isArray(d.assets) ? d.assets : [];
const apiPrefix = `https://api.github.com/repos/${repo}/releases/assets/`;
const browserHost = /^https:\/\/github\.com\/[^\s]+$/;
process.stdout.write(`${tag}\n`);
for (const a of assets) {
  if (!a || typeof a.name !== "string" || !a.name || typeof a.url !== "string" || !/^https:\/\/api\.github\.com\/repos\/[^\/]+\/[^\/]+\/releases\/assets\/[0-9]+$/.test(a.url) || !a.url.startsWith(apiPrefix) || typeof a.browser_download_url !== "string" || !browserHost.test(a.browser_download_url)) continue;
  const name = new URL(a.browser_download_url).pathname.split("/").pop();
  if (name !== a.name || /[\\t\\r\\n]/.test(a.name)) process.exit(3);
  process.stdout.write(`${a.name}\t${a.url}\n`);
}
' "$GITHUB_REPO" <<< "$RELEASE_INFO"; then
        echo -e "${RED}Release 响应格式无效，已拒绝下载${NC}" >&2
        exit 1
    fi
    IFS= read -r LATEST_VERSION < "$RELEASE_PARSE_FILE"
    if [[ ! "$LATEST_VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        echo -e "${RED}Release 版本标签无效，已拒绝安装${NC}" >&2
        exit 1
    fi
    ASSET_MAP=$(tail -n +2 "$RELEASE_PARSE_FILE")
    if [ -z "$ASSET_MAP" ]; then
        echo -e "${RED}Release 响应缺少可用资产信息${NC}"
        exit 1
    fi
    JAR_URL=""
    JAR_NAME=""
    SPLIT_ASSETS=""
    SPLIT_BASE_NAME=""
    SHA_URL=""
    SIG_URL=""
    while IFS="$(printf '\t')" read -r BROWSER_URL ASSET_API_URL; do
        ASSET_NAME="${BROWSER_URL##*/}"
        case "$ASSET_NAME" in
            *.jar)
                if [ -n "$JAR_NAME" ]; then
                    echo -e "${RED}Release 包含多个 JAR 资产，已拒绝安装${NC}" >&2
                    exit 1
                fi
                JAR_NAME="$ASSET_NAME"
                JAR_URL="$ASSET_API_URL"
                ;;
            *.jar.split-[0-9][0-9]*)
                PART_INDEX="${ASSET_NAME##*.jar.split-}"
                if [[ ! "$PART_INDEX" =~ ^[0-9]{2,}$ ]]; then
                    echo -e "${RED}Release 分卷编号无效，已拒绝安装${NC}" >&2
                    exit 1
                fi
                PART_BASE_NAME="${ASSET_NAME%%.split-*}"
                if [ -n "$SPLIT_BASE_NAME" ] && [ "$SPLIT_BASE_NAME" != "$PART_BASE_NAME" ]; then
                    echo -e "${RED}Release 包含多个分卷 JAR，已拒绝安装${NC}" >&2
                    exit 1
                fi
                if printf '%s\n' "$SPLIT_ASSETS" | cut -f1 | grep -Fxq "$ASSET_NAME"; then
                    echo -e "${RED}Release 分卷编号重复，已拒绝安装${NC}" >&2
                    exit 1
                fi
                SPLIT_BASE_NAME="$PART_BASE_NAME"
                SPLIT_ASSETS="${SPLIT_ASSETS}${SPLIT_ASSETS:+$'\n'}${ASSET_NAME}$(printf '\t')${ASSET_API_URL}"
                ;;
        esac
    done <<EOF
$ASSET_MAP
EOF
    # 只接受与选定 JAR（或分卷 JAR 的基名）精确对应的校验和签名资产，
    # 避免多个历史包同时存在时把错误的 .sha256/.sig 绑定到当前 JAR。
    ARTIFACT_BASE_NAME="${JAR_NAME:-$SPLIT_BASE_NAME}"
    if [ -n "$ARTIFACT_BASE_NAME" ]; then
        while IFS="$(printf '\t')" read -r BROWSER_URL ASSET_API_URL; do
            ASSET_NAME="${BROWSER_URL##*/}"
            [ "$ASSET_NAME" = "${ARTIFACT_BASE_NAME}.sha256" ] && SHA_URL="$ASSET_API_URL"
            [ "$ASSET_NAME" = "${ARTIFACT_BASE_NAME}.sig" ] && SIG_URL="$ASSET_API_URL"
        done <<EOF
$ASSET_MAP
EOF
    fi
    # 兼容历史分卷附件：要求编号从 00 起连续无缺口，再按编号升序拼接。
    SPLIT_URLS=""
    if [ -n "$SPLIT_ASSETS" ]; then
        SPLIT_NAMES=$(printf '%s\n' "$SPLIT_ASSETS" | sed '/^$/d' | cut -f1 | sort -t '-' -k2,2n)
        EXPECTED_INDEX=0
        while IFS= read -r PART_NAME; do
            [ -n "$PART_NAME" ] || continue
            ACTUAL_INDEX=$((10#${PART_NAME##*.jar.split-}))
            if [ "$ACTUAL_INDEX" -ne "$EXPECTED_INDEX" ]; then
                echo -e "${RED}Release 分卷编号不连续（期望 $(printf '%02d' "$EXPECTED_INDEX")，实际 ${PART_NAME##*.jar.split-}），已拒绝安装${NC}" >&2
                exit 1
            fi
            EXPECTED_INDEX=$((EXPECTED_INDEX + 1))
        done <<< "$SPLIT_NAMES"
        SPLIT_URLS=$(printf '%s\n' "$SPLIT_ASSETS" | sed '/^$/d' | sort -t '-' -k2,2n | cut -f2-)
    fi

    if [ -z "$JAR_URL" ] && [ -z "$SPLIT_URLS" ]; then
        echo -e "${RED}未找到 JAR 下载链接${NC}"
        exit 1
    fi

    echo -e "${GREEN}✓ 最新版本: ${LATEST_VERSION}${NC}"
    if [ -n "$JAR_URL" ]; then
        echo -e "${GREEN}✓ 下载地址: ${JAR_URL}${NC}"
    else
        echo -e "${GREEN}✓ 检测到分卷发布（$(echo "$SPLIT_URLS" | wc -l) 个分卷），将下载后自动拼接${NC}"
    fi
}

# 校验下载产物的 SHA-256（发布源被劫持或传输损坏的最后防线）。
# 与应用内更新器同一策略：拿不到校验文件或摘要不匹配即中止，绝不带病启动。
verify_sha256() {
    local FILE="$1"
    if [ -z "$SHA_URL" ]; then
        echo -e "${RED}发布资产缺少 SHA-256 校验文件，中止安装${NC}"
        rm -f "$FILE"
        exit 1
    fi
    local SHA_TOOL=""
    if command -v sha256sum >/dev/null 2>&1; then
        SHA_TOOL="sha256sum"
    elif command -v shasum >/dev/null 2>&1; then
        SHA_TOOL="shasum -a 256"
    else
        echo -e "${RED}未找到 sha256sum/shasum，无法校验下载完整性，中止安装${NC}"
        rm -f "$FILE"
        exit 1
    fi
    local EXPECTED ACTUAL SHA_FILE
    SHA_FILE="${FILE}.sha256.$$"
    if ! download_github_asset "$SHA_URL" "$SHA_FILE"; then
        echo -e "${RED}无法下载 SHA-256 校验文件，中止安装${NC}"
        rm -f "$FILE" "$SHA_FILE"
        exit 1
    fi
    EXPECTED=$(head -1 "$SHA_FILE" | awk '{print $1}' | tr '[:upper:]' '[:lower:]')
    rm -f "$SHA_FILE"
    if [[ ! "$EXPECTED" =~ ^[[:xdigit:]]{64}$ ]]; then
        echo -e "${RED}SHA-256 校验文件格式无效，中止安装${NC}"
        rm -f "$FILE"
        exit 1
    fi
    ACTUAL=$($SHA_TOOL "$FILE" | awk '{print $1}' | tr '[:upper:]' '[:lower:]')
    if [ "$ACTUAL" != "$EXPECTED" ]; then
        echo -e "${RED}SHA-256 校验失败，下载内容不完整或被篡改，已删除并中止安装${NC}"
        echo -e "  期望: ${EXPECTED}"
        echo -e "  实际: ${ACTUAL}"
        rm -f "$FILE"
        exit 1
    fi
    echo -e "${GREEN}✓ SHA-256 校验通过${NC}"
}

# 校验下载产物的 Ed25519 发布签名。
# 官方渠道自 v3.4.9 起随 Release 附带 .jar.sig；缺失或验签失败一律中止安装
# （缺少 openssl 或版本不支持 Ed25519 时 fail-closed，应用内更新器同样强制验签）。
verify_signature() {
    local FILE="$1"
    if ! command -v openssl >/dev/null 2>&1; then
        echo -e "${RED}未找到 openssl，无法验证官方发布签名，中止安装${NC}"
        rm -f "$FILE"
        exit 1
    fi
    if ! openssl pkeyutl -verify -help 2>&1 | grep -q -- "-rawin"; then
        echo -e "${RED}当前 openssl 不支持 Ed25519 验签，中止安装${NC}"
        rm -f "$FILE"
        exit 1
    fi
    if [ -z "$SIG_URL" ]; then
        echo -e "${RED}官方发布资产缺少 Ed25519 签名文件（.jar.sig），中止安装${NC}"
        rm -f "$FILE"
        exit 1
    fi
    local TMP_DIR SIG_RAW PUB_PEM
    TMP_DIR=$(mktemp -d)
    SIG_RAW="$TMP_DIR/release.sig.raw"
    PUB_PEM="$TMP_DIR/release.pub.pem"

    local SIG_FILE
    SIG_FILE="$TMP_DIR/release.sig"
    if ! download_github_asset "$SIG_URL" "$SIG_FILE"; then
        echo -e "${RED}无法下载 Ed25519 签名文件，中止安装${NC}"
        rm -rf "$TMP_DIR" "$FILE"
        exit 1
    fi
    if ! base64 -d "$SIG_FILE" > "$SIG_RAW" 2>/dev/null; then
        echo -e "${RED}无法解析 Ed25519 签名文件，中止安装${NC}"
        rm -rf "$TMP_DIR" "$FILE"
        exit 1
    fi
    printf -- "-----BEGIN PUBLIC KEY-----\n%s\n-----END PUBLIC KEY-----\n" "$RELEASE_SIGN_PUBLIC_KEY" > "$PUB_PEM"
    if openssl pkeyutl -verify -pubin -inkey "$PUB_PEM" -rawin -in "$FILE" -sigfile "$SIG_RAW" >/dev/null 2>&1; then
        echo -e "${GREEN}✓ 发布物签名校验通过${NC}"
        rm -rf "$TMP_DIR"
    else
        echo -e "${RED}Ed25519 签名校验失败，安装包可能被篡改，已删除并中止安装${NC}"
        rm -rf "$TMP_DIR"
        rm -f "$FILE"
        exit 1
    fi
}

# 下载 JAR 包（整包或分卷自动拼接）
download_jar() {
    mkdir -p "$INSTALL_DIR"
    cd "$INSTALL_DIR"

    echo ""
    echo -e "${YELLOW}正在下载 JAR 包...${NC}"

    if [ -n "$JAR_URL" ]; then
        if download_github_asset "$JAR_URL" xianyu-seller-console.jar; then
            echo -e "${GREEN}✓ 下载成功${NC}"
        else
            echo -e "${RED}下载失败，请检查网络${NC}"
            exit 1
        fi
        verify_sha256 xianyu-seller-console.jar
        verify_signature xianyu-seller-console.jar
        return
    fi

    # $$ 进程号隔离，避免同秒重跑复用上次残留的分卷目录
    TMP_PARTS_DIR="xianyu-jar-parts.$$"
    rm -rf "$TMP_PARTS_DIR"
    mkdir -p "$TMP_PARTS_DIR"
    IDX=0
    for PART_URL in $SPLIT_URLS; do
        IDX=$((IDX + 1))
        echo -e "${YELLOW}  下载分卷 ${IDX}/$(echo "$SPLIT_URLS" | wc -l)...${NC}"
        # 文件名补零：拼接用 glob 字典序展开，part-1/part-10/part-2 的顺序是错的
        PART_NAME=$(printf 'part-%03d' "$IDX")
        if ! download_github_asset "$PART_URL" "$TMP_PARTS_DIR/$PART_NAME"; then
            echo -e "${RED}分卷下载失败，请检查网络${NC}"
            rm -rf "$TMP_PARTS_DIR"
            exit 1
        fi
    done

    cat "$TMP_PARTS_DIR"/part-* > xianyu-seller-console.jar
    rm -rf "$TMP_PARTS_DIR"
    if [ -s xianyu-seller-console.jar ]; then
        verify_sha256 xianyu-seller-console.jar
        verify_signature xianyu-seller-console.jar
        echo -e "${GREEN}✓ 分卷下载并拼接完成${NC}"
    else
        echo -e "${RED}拼接结果为空，请重试${NC}"
        exit 1
    fi
}

# 启动服务
start_service() {
    cd "$INSTALL_DIR"
    mkdir -p data logs

    # 停止旧进程。PID 文件可能过期或被无关进程复用：
    # Linux 通过 /proc cmdline 校验身份，其他平台仅校验进程存活。
    if [ -f xianyu.pid ]; then
        OLD_PID=$(cat xianyu.pid)
        PID_IS_OURS=1
        if [ -d "/proc/$OLD_PID" ] && ! tr '\0' ' ' < "/proc/$OLD_PID/cmdline" 2>/dev/null | grep -q "xianyu-seller-console"; then
            PID_IS_OURS=0
        fi
        if [ "$PID_IS_OURS" = "1" ] && kill -0 "$OLD_PID" 2>/dev/null; then
            # 先记录再杀守护循环：循环一旦死亡就无法再按 2 秒周期拉起 java，
            # 之后再杀循环生前记录的子进程，彻底消除"复活占端口"的竞态
            CHILD_PIDS=$(pgrep -P "$OLD_PID" 2>/dev/null || true)
            kill "$OLD_PID" 2>/dev/null || true
            sleep 1
            for CHILD_PID in $CHILD_PIDS; do
                kill "$CHILD_PID" 2>/dev/null || true
            done
        fi
        rm -f xianyu.pid
    fi

    echo ""
    echo -e "${YELLOW}正在启动服务...${NC}"

    # 守护循环：进程退出后 2 秒自动拉起，也是应用内"在线更新"重启策略的基础。
    # 循环体为静态字符串，JAVA_OPTS/PORT 通过位置参数传入，避免拼接命令串。
    nohup bash -c 'JAVA_OPTS="$1"; PORT="$2"; while true; do java $JAVA_OPTS -Dserver.port="$PORT" -jar xianyu-seller-console.jar >> logs/console.log 2>&1; echo "$(date "+%F %T") process exited, restarting in 2s" >> logs/console.log; sleep 2; done' _ "$JAVA_OPTS" "$PORT" > /dev/null 2>&1 &
    echo $! > xianyu.pid

    # 等待启动
    for i in {1..20}; do
        sleep 1
        if curl -s "http://localhost:$PORT" > /dev/null 2>&1; then
            echo -e "${GREEN}✓ 启动成功${NC}"
            return 0
        fi
    done

    echo -e "${RED}启动超时，查看日志: tail -f $INSTALL_DIR/logs/console.log${NC}"
    exit 1
}

# 主流程
main() {
    # 检测系统
    detect_os
    
    # 检查 JDK
    echo -e "${BLUE}检查 JDK 环境...${NC}"
    if check_jdk; then
        if [ "$JAVA_VER" -ge "$JDK_VERSION" ]; then
            echo -e "${GREEN}✓ JDK $JAVA_VER 已安装${NC}"
        else
            echo -e "${YELLOW}当前 JDK $JAVA_VER，需要版本 $JDK_VERSION${NC}"
            install_jdk
        fi
    else
        install_jdk
    fi

    # 获取最新版本
    get_latest_release
    
    # 下载并启动
    download_jar
    start_service
    
    # 获取 IP（管道退出码取自最后一个命令，hostname -I 失败时 || 分支不会执行，
    # 必须按平台分支取值）
    case "$(uname -s)" in
        Darwin)
            LOCAL_IP=$(ipconfig getifaddr en0 2>/dev/null || echo "127.0.0.1")
            ;;
        *)
            LOCAL_IP=$(hostname -I 2>/dev/null | awk '{print $1}')
            LOCAL_IP=${LOCAL_IP:-127.0.0.1}
            ;;
    esac
    
    echo ""
    echo -e "${GREEN}╔════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║          安装成功！                   ║${NC}"
    echo -e "${GREEN}╚════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${BLUE}版本信息:${NC} ${LATEST_VERSION}"
    echo ""
    echo -e "${BLUE}访问地址:${NC}"
    echo -e "  本地:   ${GREEN}http://localhost:${PORT}${NC}"
    echo -e "  局域网: ${GREEN}http://${LOCAL_IP}:${PORT}${NC}"
    echo ""
    echo -e "${BLUE}管理命令:${NC}"
    echo -e "  日志: tail -f $INSTALL_DIR/logs/console.log"
    echo -e "  停止: pkill -P \$(cat $INSTALL_DIR/xianyu.pid); kill \$(cat $INSTALL_DIR/xianyu.pid)"
    echo ""
    echo -e "${BLUE}在线更新:${NC} 应用内「版本更新」支持一键在线更新（进程退出后自动拉起新版本）"
}

main "$@"
