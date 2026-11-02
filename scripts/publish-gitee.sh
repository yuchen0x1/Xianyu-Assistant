#!/usr/bin/env bash
# 兼容旧入口：发布源已迁移到 GitHub。
# 请使用 scripts/publish-github.sh；保留此包装脚本以免已有自动化中断。
set -euo pipefail

if [ -n "${GITEE_TOKEN:-}" ] && [ -z "${GITHUB_TOKEN:-}" ]; then
  echo "错误：GITEE_TOKEN 不会用于 GitHub 发布，请改用 GITHUB_TOKEN（令牌不会跨服务转发）。" >&2
  exit 1
fi
if [ -n "${GITEE_REPO:-}" ] && [ -z "${GITHUB_REPO:-}" ]; then
  export GITHUB_REPO="$GITEE_REPO"
fi
if [ -n "${GITEE_TARGET_BRANCH:-}" ] && [ -z "${GITHUB_TARGET_BRANCH:-}" ]; then
  export GITHUB_TARGET_BRANCH="$GITEE_TARGET_BRANCH"
fi

exec "$(dirname "$0")/publish-github.sh" "$@"
