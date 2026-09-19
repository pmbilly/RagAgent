#!/usr/bin/env bash
# 录波 3 sandbox 子批 2（sandbox-check + templates/query provider 面）的 golden：
# schk-* / tpl-*。（skills 子资源 12 条随子批 3，届时补 sbk-* 场景。）
#
# 场景设计：
#   - dev 无 provider：cube 端点 127.0.0.1:39171 拒连、docker 禁用——
#     sandbox-check 的失败文案走 sandboxCheckReason 的固定中文分类
#     （拒连→"服务不可用：端点拒绝连接"），latency_ms 两侧各自计时（A/B 掩码）。
#   - templates/query 的 provider 段失败 → 500 信封 message 含 RemoteError.Error()
#     （cause 是各运行时的拨号措辞）——A/B 对该 golden 掩码 message。
#   - 错误形态是老式 {"code":1,"msg":"..."}（系统组遗留风格）。
#
# 用法：
#   scripts/record-sbx2-golden.sh
#   SBX2_TARGET_PORT=8082 SBX2_OUT_DIR=/tmp/x scripts/record-sbx2-golden.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${SBX2_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${SBX2_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"
mkdir -p "${OUT}"

req() { local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" "$@"; }

echo "==> 幂等清理：租户 10002 的 sandbox 配置"
${PSQL} >/dev/null 2>&1 <<'SQL'
DELETE FROM tenant_sandbox_configs WHERE tenant_id = 10002;
SQL

TOKEN="$(login "${TEST_EMAIL}" "${PORT}")"
[ -n "${TOKEN}" ] || { echo "FATAL: owner 登录失败"; exit 1; }
AUTH="Authorization: Bearer ${TOKEN}"
CT='Content-Type: application/json'

echo "==> 1) sandbox-check：binding / config_id / docker / 校验失败（确定性 400）"
req schk-bad-body.json POST /system/sandbox-check -H "${AUTH}" -H "${CT}" -d 'not-json'
req schk-config-missing.json POST /system/sandbox-check -H "${AUTH}" -H "${CT}" \
  -d '{"config_id":"00000000-0000-0000-0000-000000000000"}'
req schk-docker.json POST /system/sandbox-check -H "${AUTH}" -H "${CT}" \
  -d '{"config":{"sandbox_type":"docker","docker":{"image":"ubuntu:22.04"}}}'
req schk-incomplete.json POST /system/sandbox-check -H "${AUTH}" -H "${CT}" \
  -d '{"config":{"sandbox_type":"cube","allow_private_endpoints":true,"cube":{"api_url":"http://127.0.0.1:39171","sandbox_domain":"sb.example.internal"}}}'

echo "==> 2) sandbox-check：拒连探测（200 结构化，latency 掩码）"
req schk-cube-unreachable.json POST /system/sandbox-check -H "${AUTH}" -H "${CT}" \
  -d '{"config":{"sandbox_type":"cube","allow_private_endpoints":true,"cube":{"api_url":"http://127.0.0.1:39171","proxy_url":"http://127.0.0.1:39172","sandbox_domain":"sb.example.internal","template_id":"tpl-probe-1"}}}'
req schk-e2b-unreachable.json POST /system/sandbox-check -H "${AUTH}" -H "${CT}" \
  -d '{"config":{"sandbox_type":"e2b","allow_private_endpoints":true,"e2b":{"api_key":"sk-e2b-secret-1","template_id":"tpl-e2b-1","api_url":"http://127.0.0.1:39173"}}}'
req schk-deep-unreachable.json POST /system/sandbox-check -H "${AUTH}" -H "${CT}" \
  -d '{"deep":true,"config":{"sandbox_type":"cube","allow_private_endpoints":true,"cube":{"api_url":"http://127.0.0.1:39171","proxy_url":"http://127.0.0.1:39172","sandbox_domain":"sb.example.internal","template_id":"tpl-probe-1"}}}'

echo "==> 3) templates/query provider 面（500，message 掩码）"
req tpl-cube-unreachable.json POST /sandbox-configs/templates/query -H "${AUTH}" -H "${CT}" \
  -d '{"config":{"sandbox_type":"cube","allow_private_endpoints":true,"cube":{"api_url":"http://127.0.0.1:39171","proxy_url":"http://127.0.0.1:39172","sandbox_domain":"sb.example.internal","template_id":"tpl-probe-1"}}}'

echo "==> 清理"
${PSQL} >/dev/null 2>&1 <<'SQL'
DELETE FROM tenant_sandbox_configs WHERE tenant_id = 10002;
SQL
echo "==> 完成：golden 落在 ${OUT}"
