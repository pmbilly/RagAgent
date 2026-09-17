#!/usr/bin/env bash
# 取测试账号的 JWT，供 curl/录制脚本使用。
#
# 用法：
#   scripts/token.sh            # 打 8080（Go）
#   scripts/token.sh 8082       # 打 8082（Java）
#   scripts/token.sh 8082 viewer
#
# 注意：zsh 里 `GID`/`UID` 等是**只读特殊变量**，赋值 UUID 会炸
# （"bad math expression"）。写 e2e 脚本时变量名避开它们，或用 bash 跑。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=dev-env.sh
source "${SCRIPT_DIR}/dev-env.sh"

PORT="${1:-8080}"
WHO="${2:-owner}"
if [ "${WHO}" = "viewer" ]; then
  login "${TEST_VIEWER_EMAIL}" "${PORT}"
else
  login "${TEST_EMAIL}" "${PORT}"
fi
