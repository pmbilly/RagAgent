#!/usr/bin/env bash
# 被 go-server-up.sh / java-server-up.sh source 的公共环境。
#
# ─────────────────────────────────────────────────────────────────────────────
# 这里固化的每一条都是**踩过的坑**，不要再手工重来：
#
# 1. WeKnora/.env 是**容器内**地址（DB_HOST=postgres / REDIS_ADDR=redis:6379 /
#    DOCREADER_ADDR=docreader:50051）。host-run 必须逐项覆盖为 localhost + 映射端口，
#    否则启动直接崩（"连接Redis失败: lookup redis: no such host"）。
#    也**不要**图省事整份 `source .env` —— 它会覆盖掉刚设好的 localhost 地址。
#    只取需要的那几个 key（下面用 `env_value` 函数）。
# 2. **SYSTEM_AES_KEY 两侧必须相同**：不同 key 下跨语言读对方写的密文会静默置空
#    （宽容解密 → credentials.configured=false），看起来像 bug，其实是密码学预期行为。
#    e2e 验证加密互操作时必须用同一把 key。
# 3. Go server 需要先用 `go build` 出二进制；仓库里的 `make run` 会走容器路径。
# 4. 需要访问被 SSRF 白名单拦住的 MCP/LLM 域名时，设 SSRF_WHITELIST_EXTRA
#    （逗号分隔，支持 *.example.com 通配）。
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

# JDK 21：Gradle 与 bootRun 都要求 JAVA_HOME/PATH 指向 21。
# 踩过的坑：脚本里若依赖调用者已配好 PATH，换一个 shell 就会得到
# "Unable to locate a Java Runtime"（Homebrew 的 openjdk 不在默认 PATH）。
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@21 ]; then
  export JAVA_HOME="/opt/homebrew/opt/openjdk@21"
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi

RAGAGENT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# 密钥/连接值优先读本仓自己的 .env（自 Go 仓下线后成为唯一来源）；
# 本仓没有时回落 WeKnora 仓的 .env（历史开发机兼容，已废弃路径）。
# 注意是**按 key** 回落：本仓 .env 存在但缺某个 key（如 RETRIEVE_DRIVER）时，
# 仍可从 WeKnora 仓 .env 补——否则整文件抢占会让缺键静默为空（走查踩坑：
# Java 缺 RETRIEVE_DRIVER → 默认检索引擎为空 → 检索恒空）。
WEKNORA_ROOT_FALLBACK="$(cd "${RAGAGENT_ROOT}/../WeKnora" 2>/dev/null && pwd || true)"
if [ -f "${RAGAGENT_ROOT}/.env" ]; then
  WEKNORA_ENV="${RAGAGENT_ROOT}/.env"
  WEKNORA_ENV_FALLBACK="${WEKNORA_ROOT_FALLBACK:+$WEKNORA_ROOT_FALLBACK/.env}"
else
  WEKNORA_ROOT="${WEKNORA_ROOT:-$WEKNORA_ROOT_FALLBACK}"
  WEKNORA_ENV="${WEKNORA_ROOT}/.env"
  WEKNORA_ENV_FALLBACK=""
fi

# dev 环境的宿主机端口（docker-compose 映射）
export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-15432}"
export REDIS_HOST="${REDIS_HOST:-localhost}"
export REDIS_PORT="${REDIS_PORT:-16379}"
export DOCREADER_ADDR="${DOCREADER_ADDR:-localhost:50051}"
export JAVA_PORT="${JAVA_PORT:-8082}"
export GO_PORT="${GO_PORT:-8080}"

# 从 .env 取单个 key（不要 source 整个文件，见坑 1）
env_value() {
  local key="$1" val=""
  if [ -f "${WEKNORA_ENV}" ]; then
    val=$(grep -m1 "^${key}=" "${WEKNORA_ENV}" | cut -d= -f2- || true)
  fi
  # 按 key 回落到 WeKnora 仓 .env（主文件缺键时）
  if [ -z "$val" ] && [ -n "${WEKNORA_ENV_FALLBACK:-}" ] && [ -f "${WEKNORA_ENV_FALLBACK}" ]; then
    val=$(grep -m1 "^${key}=" "${WEKNORA_ENV_FALLBACK}" | cut -d= -f2- || true)
  fi
  echo "$val"
}

# 与 Go 侧一致的加密密钥——跨语言 e2e 的前提（见坑 2）
export SYSTEM_AES_KEY="${SYSTEM_AES_KEY:-$(env_value SYSTEM_AES_KEY)}"
export REDIS_PASSWORD="${REDIS_PASSWORD:-$(env_value REDIS_PASSWORD)}"

# Go 二进制自己不读 .env（godotenv 只在容器入口用），host-run 必须显式导出。
# 这些值在 .env 里与 dev PG 一致（同一个库，只是容器/宿主地址不同），直接取用。
export DB_DRIVER="${DB_DRIVER:-$(env_value DB_DRIVER)}"
export DB_USER="${DB_USER:-$(env_value DB_USER)}"
export DB_PASSWORD="${DB_PASSWORD:-$(env_value DB_PASSWORD)}"
export DB_NAME="${DB_NAME:-$(env_value DB_NAME)}"
# 检索引擎兜底（走查踩坑：Go 从 .env 拿到 RETRIEVE_DRIVER=postgres，Java 缺了它
# 会让「租户 engines 空 → 默认引擎」兜底落空，检索恒空「No retrievable indexing
# pipelines」）。与 Go 同源取 .env，未配置则不导出（保持 Go 的"未配置"语义）。
export RETRIEVE_DRIVER="${RETRIEVE_DRIVER:-$(env_value RETRIEVE_DRIVER)}"

# Java 侧自有配置（与 Go 无关）
export JWT_SECRET="${JWT_SECRET:-java-e2e-jwt-secret-key-0123456789abcdef}"
export LOCAL_STORAGE_BASE_DIR="${LOCAL_STORAGE_BASE_DIR:-/tmp/weknora-java-files}"

# 测试账号（阶段 1 建的专用租户 10002）
export TEST_EMAIL="${TEST_EMAIL:-java-phase1@weknora.test}"
export TEST_VIEWER_EMAIL="${TEST_VIEWER_EMAIL:-java-phase1-viewer@weknora.test}"
export TEST_PASSWORD="${TEST_PASSWORD:-Passw0rd!}"

# 登录并回显 token（供录制脚本使用）
login() {
  local email="${1:-${TEST_EMAIL}}"
  curl -s -X POST "http://localhost:${2:-8080}/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"${email}\",\"password\":\"${TEST_PASSWORD}\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])'
}

# 等待服务就绪（轮询到非 000 即认为起来了）
wait_for_port() {
  local port="$1" path="${2:-/api/v1/knowledge-bases}" tries="${3:-40}"
  for _ in $(seq 1 "${tries}"); do
    local code
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${port}${path}" || true)
    if [ "${code}" != "000" ]; then
      echo "ready (HTTP ${code}) on :${port}"
      return 0
    fi
    sleep 2
  done
  echo "TIMEOUT waiting for :${port}" >&2
  return 1
}
