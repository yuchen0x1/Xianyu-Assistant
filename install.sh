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
# 私有仓库安装时可提供令牌；令牌只经 Authorization 请求头发送，不拼入 URL。
GITHUB_AUTH=()
if [ -n "${GITHUB_TOKEN:-}" ]; then
    GITHUB_AUTH=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
fi
GITHUB_API_HEADERS=(-H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2022-11-28')

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
download_github_asset() {
    local URL="$1"
    local OUTPUT="$2"
    local TMP_FILE HEADER_FILE STATUS NEXT_URL HOP CURL_EXIT

    TMP_FILE="${OUTPUT}.tmp.$$"
    rm -f "$TMP_FILE"
    for HOP in 1 2 3 4 5; do
        case "$URL" in
            https://api.github.com/repos/*/releases/assets/[0-9]*)
                REQUEST_HEADERS=("${GITHUB_AUTH[@]}" "${GITHUB_API_HEADERS[@]}" -H 'Accept: application/octet-stream')
                ;;
            https://release-assets.githubusercontent.com/*|https://objects.githubusercontent.com/*|https://github.com/*)
                REQUEST_HEADERS=(-H 'Accept: application/octet-stream')
                ;;
            *)
                echo -e "${RED}拒绝不受信任的 GitHub 下载重定向: ${URL}${NC}" >&2
                rm -f "$TMP_FILE"
                return 1
                ;;
        esac

        HEADER_FILE=$(mktemp)
        rm -f "$TMP_FILE"
        CURL_EXIT=0
        STATUS=$(curl -sS --connect-timeout 30 --max-time 300 --proto '=https' --tlsv1.2 \
            --max-redirs 0 -D "$HEADER_FILE" -o "$TMP_FILE" -w '%{http_code}' \
            "${REQUEST_HEADERS[@]}" "$URL") || CURL_EXIT=$?
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

    RELEASE_INFO=$(curl -fsSL --connect-timeout 10 "${GITHUB_AUTH[@]}" "${GITHUB_API_HEADERS[@]}" "$GITHUB_API" 2>/dev/null || echo "")

    if [ -z "$RELEASE_INFO" ]; then
        echo -e "${RED}无法获取版本信息，请检查网络${NC}"
        exit 1
    fi

    # 资产名称仍从 browser_download_url 提取（仅用于匹配后缀）；实际下载一律使用
    # assets[].url API 地址。这样下载函数可逐跳校验重定向，并在跨主机时剥离令牌。
    ALL_URLS=$(echo "$RELEASE_INFO" | grep -oE '"browser_download_url"[[:space:]]*:[[:space:]]*"[^"]+"' \
        | sed -E 's/.*"([^"]+)".*/\1/' || true)
    ASSET_API_URLS=$(echo "$RELEASE_INFO" \
        | grep -oE '"url"[[:space:]]*:[[:space:]]*"https://api[.]github[.]com/repos/[^"]+/releases/assets/[0-9]+"' \
        | sed -E 's/.*"([^"]+)".*/\1/' || true)

    LATEST_VERSION=$(echo "$RELEASE_INFO" | grep -oE '"tag_name"[[:space:]]*:[[:space:]]*"[^"]+"' \
        | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
    if [[ ! "$LATEST_VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        echo -e "${RED}Release 版本标签无效，已拒绝安装${NC}" >&2
        exit 1
    fi

    if [ -z "$ALL_URLS" ] || [ -z "$ASSET_API_URLS" ]; then
        echo -e "${RED}Release 响应缺少可用资产信息${NC}"
        exit 1
    fi
    ASSET_NAME_COUNT=$(printf '%s\n' "$ALL_URLS" | sed '/^$/d' | wc -l | tr -d ' ')
    ASSET_API_COUNT=$(printf '%s\n' "$ASSET_API_URLS" | sed '/^$/d' | wc -l | tr -d ' ')
    if [ "$ASSET_NAME_COUNT" -ne "$ASSET_API_COUNT" ]; then
        echo -e "${RED}Release 资产信息不完整，已拒绝下载${NC}"
        exit 1
    fi

    # 按 GitHub assets 数组的稳定顺序将名称与 API URL 配对。browser_download_url
    # 不再传给 curl，避免携带 Authorization 跟随其跨主机重定向。
    ASSET_MAP=$(paste <(printf '%s\n' "$ALL_URLS") <(printf '%s\n' "$ASSET_API_URLS"))
    JAR_URL=""
    SPLIT_ASSETS=""
    SHA_URL=""
    SIG_URL=""
    while IFS="$(printf '\t')" read -r BROWSER_URL ASSET_API_URL; do
        case "$BROWSER_URL" in
            *.jar)
                JAR_URL="$ASSET_API_URL"
                ;;
            *.jar.split-[0-9]*)
                SPLIT_ASSETS="${SPLIT_ASSETS}${SPLIT_ASSETS:+$'\n'}${BROWSER_URL}$(printf '\t')${ASSET_API_URL}"
                ;;
            *.jar.sha256)
                SHA_URL="$ASSET_API_URL"
                ;;
            *.jar.sig)
                SIG_URL="$ASSET_API_URL"
                ;;
        esac
    done <<EOF
$ASSET_MAP
EOF
    # 兼容历史分卷附件：按资产名称中的编号升序拼接。
    SPLIT_URLS=$(printf '%s\n' "$SPLIT_ASSETS" | sed '/^$/d' | sort -t "$(printf '\t')" -k1,1V | cut -f2-)

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
    EXPECTED=$(head -1 "$SHA_FILE" | awk '{print $1}')
    rm -f "$SHA_FILE"
    if [ -z "$EXPECTED" ]; then
        echo -e "${RED}无法解析 SHA-256 校验文件，中止安装${NC}"
        rm -f "$FILE"
        exit 1
    fi
    ACTUAL=$($SHA_TOOL "$FILE" | awk '{print $1}')
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
# （仅 openssl 过旧不支持 Ed25519 时降级为警告，应用内更新器仍会强制验签）。
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
