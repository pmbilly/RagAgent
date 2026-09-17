#!/usr/bin/env bash
# golden 响应录制：对 Go 版服务（默认 localhost:8080）逐端点录响应，
# 存到 Java 仓 server/src/test/resources/contracts/，供 Java 集成测试逐字段比对。
#
# 用法：
#   ./scripts/record-golden.sh                 # 录公共端点（无需登录）
#   ./scripts/record-golden.sh --auth <token>  # 带 JWT 录受保护端点
#   BASE=http://localhost:8081 ./scripts/record-golden.sh   # 换源
#
# 前置：Go 服务已在跑（cd ~/WeKnora && host-run 后端，见 Go 仓 .env）
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
OUT="$(cd "$(dirname "$0")/.." && pwd)/server/src/test/resources/contracts"
TOKEN=""
[[ "${1:-}" == "--auth" && -n "${2:-}" ]] && TOKEN="$2"

mkdir -p "$OUT"

# record <name> <method> <path> [data]
record() {
  local name="$1" method="$2" path="$3" data="${4:-}"
  local args=(-s -o "$OUT/$name.json" -w "%{http_code}" -X "$method" "$BASE$path")
  [[ -n "$TOKEN" ]] && args+=(-H "Authorization: Bearer $TOKEN")
  [[ -n "$data" ]] && args+=(-H "Content-Type: application/json" -d "$data")
  local code
  code=$(curl "${args[@]}")
  echo "$code  $name  ($method $path)"
}

# ---- 公共端点（404/401 也是契约的一部分，前端依赖这些错误格式）----
record health             GET  /health
record root-404           GET  /api/v1/definitely-not-exist
record unauthorized-401   GET  /api/v1/auth/me

# ---- 受保护端点（需要 --auth <jwt>）----
if [[ -n "$TOKEN" ]]; then
  record me               GET  /api/v1/auth/me
  # 模块翻译时逐个补充：
  # record kb-list          GET  /api/v1/knowledge-bases?page=1&page_size=10
fi

echo ""
echo "已录制到 $OUT"
echo "提示：JWT 获取方式——先登录 Go 版前端，从浏览器 DevTools 复制，或用："
echo "  curl -s $BASE/api/v1/auth/login -d '{\"username\":\"...\",\"password\":\"...\"}' | jq -r .data.token"
