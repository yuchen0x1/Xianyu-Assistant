#!/usr/bin/env bash
# 发布物 Ed25519 签名工具。
#
# 用途：为发布附件（JAR / .sha256）生成 .sig 签名文件。客户端在线自更新对官方渠道强制验签，
# SHA-256 校验文件与安装包同源于一个 Release，无法防御发布通道被入侵；Ed25519 签名私钥
# 离线/CI Secret 保管，才构成真正的供应链信任边界。
#
# 私钥来源（按优先级）：
#   1. RELEASE_SIGN_KEY_FILE   —— PKCS#8 PEM 文件路径
#   2. RELEASE_SIGN_PRIVATE_KEY —— PKCS#8 DER 的 Base64 文本（可直接作为 GitHub/Gitee CI Secret）
#
# 用法：
#   scripts/sign-release.sh gen-key [输出目录]   # 生成新密钥对（仅本地保管，切勿入库）
#   scripts/sign-release.sh sign <file> [...]    # 为每个文件生成 <file>.sig（Base64 原始签名）
#
# 依赖：openssl（1.1.1+，支持 Ed25519）。
set -euo pipefail

require_openssl() {
  if ! command -v openssl >/dev/null 2>&1; then
    echo "错误：未找到 openssl，无法进行 Ed25519 签名" >&2
    exit 1
  fi
}

resolve_private_key_pem() {
  if [[ -n "${RELEASE_SIGN_KEY_FILE:-}" ]]; then
    cat "$RELEASE_SIGN_KEY_FILE"
    return
  fi
  if [[ -n "${RELEASE_SIGN_PRIVATE_KEY:-}" ]]; then
    printf -- "-----BEGIN PRIVATE KEY-----\n%s\n-----END PRIVATE KEY-----\n" "$RELEASE_SIGN_PRIVATE_KEY"
    return
  fi
  echo "错误：未设置 RELEASE_SIGN_KEY_FILE 或 RELEASE_SIGN_PRIVATE_KEY，无法签名" >&2
  echo "      私钥属发布方核心机密，禁止写入仓库；请配置为 CI Secret 或本地安全文件。" >&2
  exit 1
}

cmd_gen_key() {
  local out_dir="${1:-.}"
  mkdir -p "$out_dir"
  local private_pem="$out_dir/release-sign-private-key.pem"
  local public_pem="$out_dir/release-sign-public-key.pem"
  openssl genpkey -algorithm ed25519 -out "$private_pem"
  openssl pkey -in "$private_pem" -pubout -out "$public_pem"
  chmod 600 "$private_pem" 2>/dev/null || true
  echo "已生成密钥对："
  echo "  私钥（离线保管 / CI Secret RELEASE_SIGN_PRIVATE_KEY 用其 Base64 体）:"
  echo "    $(grep -v 'PRIVATE KEY' "$private_pem" | tr -d '\n')"
  echo "  公钥 SPKI Base64（需与客户端内置 OFFICIAL_RELEASE_PUBLIC_KEY 一致）:"
  echo "    $(grep -v 'PUBLIC KEY' "$public_pem" | tr -d '\n')"
  echo "警告：私钥必须保存在仓库之外的安全位置；泄露后攻击者可伪造官方更新。"
}

cmd_sign() {
  require_openssl
  if [[ $# -eq 0 ]]; then
    echo "用法: scripts/sign-release.sh sign <file> [...]" >&2
    exit 1
  fi
  local key_tmp
  key_tmp="$(mktemp "${TMPDIR:-/tmp}/release-sign-key.XXXXXX")"
  trap 'rm -f "$key_tmp"' EXIT
  resolve_private_key_pem > "$key_tmp"
  chmod 600 "$key_tmp" 2>/dev/null || true

  local status=0
  for file in "$@"; do
    if [[ ! -f "$file" ]]; then
      echo "跳过：文件不存在 $file" >&2
      status=1
      continue
    fi
    # Ed25519 一步签名（-rawin 输出原始 64 字节签名，与 JDK Ed25519/openssl 验证端一致）
    if openssl pkeyutl -sign -inkey "$key_tmp" -rawin -in "$file" -out "${file}.sig.raw"; then
      base64 < "${file}.sig.raw" | tr -d '\n' > "${file}.sig"
      rm -f "${file}.sig.raw"
      echo "已签名: ${file}.sig"
    else
      rm -f "${file}.sig.raw"
      echo "签名失败: $file（确认私钥为 Ed25519 PKCS#8 格式且 openssl 支持 Ed25519）" >&2
      status=1
    fi
  done
  exit "$status"
}

case "${1:-}" in
  gen-key)
    shift
    cmd_gen_key "$@"
    ;;
  sign)
    shift
    cmd_sign "$@"
    ;;
  *)
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
