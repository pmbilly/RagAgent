#!/usr/bin/env bash
# 启动 Go 版 WeKnora（host-run），用于**录制 golden 响应**与 A/B 对比。
#
# 用法：
#   scripts/go-server-up.sh                 # 默认 8080
#   GO_PORT=8080 SSRF_WHITELIST_EXTRA=mcp.example.com scripts/go-server-up.sh
#
# 背景：Java 翻译的验收标准是「与 Go 实录逐字节一致」，所以需要一个能跑的
# Go 实例来录 golden。这里把启动踩过的坑都固化了（见 dev-env.sh 的注释）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=dev-env.sh
source "${SCRIPT_DIR}/dev-env.sh"

# dev-env 在本仓存在 .env 时**不设** WEKNORA_ROOT（见其 37-43 行的分支）→ 这里自兜底，
# 否则 ${WEKNORA_ROOT} 在 set -u 下直接 unbound（--with-ab 会因此起不来）。
WEKNORA_ROOT="${WEKNORA_ROOT:-$(cd "${SCRIPT_DIR}/../WeKnora" 2>/dev/null && pwd || true)}"
[ -n "${WEKNORA_ROOT}" ] || { echo "找不到 Go 仓（../WeKnora，或设 WEKNORA_ROOT）"; exit 1; }

BIN="${WEKNORA_ROOT}/bin/weknora-server"
LOG="${WEKNORA_LOG:-/tmp/weknora-go-server.log}"

echo "==> building Go server（首次或代码变更后较慢，约 1-3 分钟）"
mkdir -p "$(dirname "${BIN}")"
( cd "${WEKNORA_ROOT}" && go build -o "${BIN}" ./cmd/server )

echo "==> starting Go server on :${GO_PORT}"
echo "    DB       = ${DB_HOST}:${DB_PORT}"
echo "    Redis    = ${REDIS_HOST}:${REDIS_PORT}"
echo "    docreader= ${DOCREADER_ADDR}"
echo "    AES key  = $([ -n "${SYSTEM_AES_KEY}" ] && echo "set (${#SYSTEM_AES_KEY} chars)" || echo UNSET)"
echo "    SSRF extra whitelist = ${SSRF_WHITELIST_EXTRA:-<none>}"
echo "    log      = ${LOG}"

# Go 二进制按 CWD 找 config/config.yaml，必须在 WeKnora 仓根目录启动。
SERVER_PORT="${GO_PORT}" \
LOCAL_STORAGE_BASE_DIR="${LOCAL_STORAGE_BASE_DIR}" \
bash -c 'cd "$1" && shift && exec "$@"' _ "${WEKNORA_ROOT}" "${BIN}" > "${LOG}" 2>&1 &

echo "==> waiting for readiness"
wait_for_port "${GO_PORT}" /api/v1/knowledge-bases 40
echo "==> Go server ready. token: TOKEN=\$(scripts/token.sh 8080)"
