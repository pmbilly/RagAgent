#!/usr/bin/env bash
# 录 session 波 1 G4（steer 4 条）的 golden。
# ⚠️ live run 只能由 agent 引擎（波 4/5）设置，HTTP 层造不出"有活轮"状态——
# 本脚本覆盖无活轮分支 + 全部校验错误；排队/注入路径由 Java 单测直种
# streamManager 验证（引擎接线后补 e2e）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${RAGAGENT_ROOT}/server/src/test/resources/contracts"
API="http://localhost:${GO_PORT}/api/v1"
TOKEN="$(login "${TEST_EMAIL}" "${GO_PORT}")"
AUTH="Authorization: Bearer ${TOKEN}"

req() {
  local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "${AUTH}" "$@"
}

UNKNOWN_ID="11111111-2222-3333-4444-999999999999"

echo "==> 准备：无标题会话"
req st-setup.json POST /sessions -H 'Content-Type: application/json' -d '{"title":""}'
SID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "${OUT}/st-setup.json")"
rm -f "${OUT}/st-setup.json"
echo "    SID=${SID}"

echo "==> 1) 无活轮分支"
req st-list-empty.json GET "/sessions/${SID}/steer"
req st-delete-gone.json DELETE "/sessions/${SID}/steer/eee00001-0000-0000-0000-000000000001"
req st-post-new-run.json POST "/sessions/${SID}/steer" -H 'Content-Type: application/json' \
  -d '{"query":"第一问"}'
req st-promote-new-run.json POST "/sessions/${SID}/steer/eee00001-0000-0000-0000-000000000001/inject"

echo "==> 2) 校验错误"
req st-post-empty.json POST "/sessions/${SID}/steer" -H 'Content-Type: application/json' -d '{}'
req st-post-empty-query.json POST "/sessions/${SID}/steer" -H 'Content-Type: application/json' -d '{"query":""}'
req st-post-bad-json.json POST "/sessions/${SID}/steer" -H 'Content-Type: application/json' -d 'not-json'
req st-post-bad-delivery.json POST "/sessions/${SID}/steer" -H 'Content-Type: application/json' \
  -d '{"query":"x","delivery":"bogus"}'
req st-post-unknown-session.json POST "/sessions/${UNKNOWN_ID}/steer" -H 'Content-Type: application/json' \
  -d '{"query":"x"}'
LONG_QUERY="$(python3 -c 'print("长"*10001)')"
req st-post-too-long.json POST "/sessions/${SID}/steer" -H 'Content-Type: application/json' \
  -d "{\"query\":\"${LONG_QUERY}\"}"

echo "==> 清理"
curl -s -o /dev/null -w '%{http_code} delete\n' -X DELETE "${API}/sessions/${SID}" -H "${AUTH}"
echo "==> done. golden 在 ${OUT}/st-*.json"
