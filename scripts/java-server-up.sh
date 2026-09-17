#!/usr/bin/env bash
# 启动 Java 版（Spring Boot，host-run），用于 e2e 验证。
#
# 用法：
#   scripts/java-server-up.sh
#   SSRF_WHITELIST_EXTRA=mcp.example.com scripts/java-server-up.sh
#
# 与 Go 版**共用同一个 dev 数据库**——这正是 e2e 的价值：H2 测不出的差异
# （NOT NULL 零值、jsonb 键序、DDL 默认值）只会在真 PG 上暴露。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=dev-env.sh
source "${SCRIPT_DIR}/dev-env.sh"

LOG="${JAVA_LOG:-/tmp/ragagent-java-server.log}"

echo "==> starting Java server on :${JAVA_PORT}"
echo "    DB       = ${DB_HOST}:${DB_PORT}"
echo "    Redis    = ${REDIS_HOST}:${REDIS_PORT}"
echo "    AES key  = $([ -n "${SYSTEM_AES_KEY}" ] && echo "set (${#SYSTEM_AES_KEY} chars) — 与 Go 一致，加密可互操作" || echo UNSET)"
echo "    SSRF extra whitelist = ${SSRF_WHITELIST_EXTRA:-<none>}"
echo "    log      = ${LOG}"

cd "${RAGAGENT_ROOT}"
SERVER_PORT="${JAVA_PORT}" \
JWT_SECRET="${JWT_SECRET}" \
SYSTEM_AES_KEY="${SYSTEM_AES_KEY}" \
LOCAL_STORAGE_BASE_DIR="${LOCAL_STORAGE_BASE_DIR}" \
./gradlew bootRun --console=plain > "${LOG}" 2>&1 &

echo "==> waiting for readiness（Spring 启动 + Flyway 校验，约 20-40 秒）"
wait_for_port "${JAVA_PORT}" /api/v1/knowledge-bases 40
echo "==> Java server ready. token: TOKEN=\$(scripts/token.sh ${JAVA_PORT})"
