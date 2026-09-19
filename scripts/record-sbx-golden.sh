#!/usr/bin/env bash
# 录波 3 sandbox 子批 1（/sandbox-configs 配置 CRUD 8 条路由）的 golden：sbx-*。
#
# 场景设计：
#   - 挂租户 10002 phase1 owner 下；每次运行前幂等清理该租户的 sandbox 配置行。
#   - dev 两侧 WEKNORA_SANDBOX_DOCKER_ENABLED 未设 → docker 后端恒禁用（确定性 400）。
#   - Create 全程不拨号（纯校验+落库）→ 成功路径完全确定；UUID/时间戳掩码，
#     api_key/env_vars 值由 Go 的 SandboxConfigForResponse 掩成 "***"（两侧同形）。
#   - Delete 无 force：provider 列举失败 → 409 固定文案 sandbox_inventory_unverifiable
#     （listErr 只进日志不进响应体，两侧字节稳定）；force → 200。
#   - Update 换连接字段：provider 列举失败 → 警告后继续保存 → 200。
#   - templates/query 只录 pre-provider 校验分支（docker 禁用 / 非法类型）；
#     带 cube 配置的执行分支两侧 500 文案不同（Go 拨号错误 vs Java 接缝异常），
#     不录（子批 2 接真客户端后再补）。
#   - 录制顺序敏感（契约测试必须复刻）：auth/binding → 校验 400 族 → CRUD →
#     workspace-policy → templates pre-分支。
#
# 用法：
#   scripts/record-sbx-golden.sh                    # 录 Go（:8080）进 contracts/
#   SBX_TARGET_PORT=8082 SBX_OUT_DIR=/tmp/x scripts/record-sbx-golden.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${SBX_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${SBX_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"
mkdir -p "${OUT}"

req() { local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" "$@"; }

echo "==> 幂等清理：租户 10002 的 sandbox 配置"
${PSQL} >/dev/null 2>&1 <<'SQL'
DELETE FROM tenant_skills WHERE tenant_id = 10002;
DELETE FROM tenant_sandbox_configs WHERE tenant_id = 10002;
SQL

TOKEN="$(login "${TEST_EMAIL}" "${PORT}")"
[ -n "${TOKEN}" ] || { echo "FATAL: owner 登录失败"; exit 1; }
AUTH="Authorization: Bearer ${TOKEN}"
CT='Content-Type: application/json'

echo "==> 1) 鉴权 + binding"
req sbx-noauth.json      GET  /sandbox-configs
req sbx-badtoken.json    GET  /sandbox-configs -H 'Authorization: Bearer garbage.token.here'
req sbx-empty-body.json  POST /sandbox-configs -H "${AUTH}" -H "${CT}"
req sbx-missing-name.json POST /sandbox-configs -H "${AUTH}" -H "${CT}" -d '{}'
req sbx-missing-type.json POST /sandbox-configs -H "${AUTH}" -H "${CT}" -d '{"name":"probe"}'

echo "==> 2) 校验 400 族（类型/后端开关/必填/URL 守卫）"
req sbx-bad-type.json POST /sandbox-configs -H "${AUTH}" -H "${CT}" \
  -d '{"name":"probe","config":{"sandbox_type":"openshift"}}'
req sbx-docker-disabled.json POST /sandbox-configs -H "${AUTH}" -H "${CT}" \
  -d '{"name":"probe","config":{"sandbox_type":"docker","docker":{"image":"ubuntu:22.04"}}}'
req sbx-cube-incomplete.json POST /sandbox-configs -H "${AUTH}" -H "${CT}" \
  -d '{"name":"probe","config":{"sandbox_type":"cube","cube":{"api_url":"http://127.0.0.1:39171","sandbox_domain":"sb.example.internal"}}}'
req sbx-e2b-incomplete.json POST /sandbox-configs -H "${AUTH}" -H "${CT}" \
  -d '{"name":"probe","config":{"sandbox_type":"e2b","e2b":{"template_id":"tpl-e2b-1"}}}'
req sbx-cube-unsafe-url.json POST /sandbox-configs -H "${AUTH}" -H "${CT}" \
  -d '{"name":"probe","config":{"sandbox_type":"cube","cube":{"api_url":"http://169.254.169.254:8080","proxy_url":"http://127.0.0.1:39172","sandbox_domain":"sb.example.internal","template_id":"tpl-probe-1"}}}'

echo "==> 3) CRUD 成功路径"
req sbx-create-cube.json POST /sandbox-configs -H "${AUTH}" -H "${CT}" \
  -d '{"name":"sbx-probe-cube","description":"probe cube","config":{"sandbox_type":"cube","allow_private_endpoints":true,"env_vars":{"PROBE_TOKEN":"tok-secret-1"},"cube":{"api_url":"http://127.0.0.1:39171","proxy_url":"http://127.0.0.1:39172","sandbox_domain":"sb.example.internal","template_id":"tpl-probe-1","api_key":"sk-cube-secret-1"}}}'
req sbx-create-e2b.json POST /sandbox-configs -H "${AUTH}" -H "${CT}" \
  -d '{"name":"sbx-probe-e2b","description":"probe e2b","config":{"sandbox_type":"e2b","allow_private_endpoints":true,"e2b":{"api_key":"sk-e2b-secret-1","template_id":"tpl-e2b-1"}}}'
req sbx-list-two.json GET /sandbox-configs -H "${AUTH}"
req sbx-get-missing.json GET /sandbox-configs/00000000-0000-0000-0000-000000000000 -H "${AUTH}"

CID="$(python3 -c 'import json,sys; print(json.load(open("'"${OUT}/sbx-create-cube.json"'"))["data"]["id"])')"
[ -n "${CID}" ] || { echo "FATAL: 未取到 cube 配置 id"; exit 1; }
echo "    cube id = ${CID}"
req sbx-get-cube.json GET "/sandbox-configs/${CID}" -H "${AUTH}"
req sbx-update-rename.json PUT "/sandbox-configs/${CID}" -H "${AUTH}" -H "${CT}" \
  -d '{"name":"sbx-probe-cube-rn","description":"renamed","config":{"sandbox_type":"cube","allow_private_endpoints":true,"env_vars":{"PROBE_TOKEN":"tok-secret-1"},"cube":{"api_url":"http://127.0.0.1:39171","proxy_url":"http://127.0.0.1:39172","sandbox_domain":"sb.example.internal","template_id":"tpl-probe-1","api_key":"sk-cube-secret-1"}}}'
req sbx-update-identity.json PUT "/sandbox-configs/${CID}" -H "${AUTH}" -H "${CT}" \
  -d '{"name":"sbx-probe-cube-rn","description":"renamed","config":{"sandbox_type":"cube","allow_private_endpoints":true,"env_vars":{"PROBE_TOKEN":"tok-secret-1"},"cube":{"api_url":"http://127.0.0.1:39179","proxy_url":"http://127.0.0.1:39172","sandbox_domain":"sb.example.internal","template_id":"tpl-probe-1","api_key":"sk-cube-secret-1"}}}'
req sbx-inventory.json GET "/sandbox-configs/${CID}/sandboxes" -H "${AUTH}"

echo "==> 4) Delete：无 force → 409 固定文案；force → 200；再删 → 404"
req sbx-delete-noforce.json DELETE "/sandbox-configs/${CID}" -H "${AUTH}"
req sbx-delete-force.json DELETE "/sandbox-configs/${CID}?force=true" -H "${AUTH}"
req sbx-delete-again.json DELETE "/sandbox-configs/${CID}" -H "${AUTH}"

echo "==> 5) workspace-policy 开关（e2b 行在场，policy 行恒隐藏）"
req sbx-policy-on.json PUT /sandbox-configs/workspace-policy -H "${AUTH}" -H "${CT}" \
  -d '{"scripts_disabled":true}'
req sbx-policy-on-list.json GET /sandbox-configs -H "${AUTH}"
req sbx-policy-off.json PUT /sandbox-configs/workspace-policy -H "${AUTH}" -H "${CT}" \
  -d '{"scripts_disabled":false}'
req sbx-policy-off-list.json GET /sandbox-configs -H "${AUTH}"

echo "==> 6) templates/query 的 pre-provider 校验分支"
req sbx-templates-docker.json POST /sandbox-configs/templates/query -H "${AUTH}" -H "${CT}" \
  -d '{"config":{"sandbox_type":"docker","docker":{"image":"ubuntu:22.04"}}}'
req sbx-templates-invalid.json POST /sandbox-configs/templates/query -H "${AUTH}" -H "${CT}" \
  -d '{"config":{"sandbox_type":"bogus"}}'

echo "==> 清理：剩余 e2b 行（SQL 直删）"
${PSQL} >/dev/null 2>&1 <<'SQL'
DELETE FROM tenant_skills WHERE tenant_id = 10002;
DELETE FROM tenant_sandbox_configs WHERE tenant_id = 10002;
SQL
echo "==> 完成：golden 落在 ${OUT}"
