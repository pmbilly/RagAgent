#!/usr/bin/env bash
# session 波 1 G2（消息面）的真 PG A/B 对比。
#
# 前提：Go :8080 / Java :8082 共享 dev PG；psql 可达。
# 做法：会话经 Go API 建一次（同库同 owner），消息用 psql 插一份两侧共读；
# 读类用例直接 ab；删除类用例两侧各删各自的孪生消息（@DELMSG@ → go=M5 / java=M6）；
# clear 幂等（两侧先后清空，响应相同）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

GO="http://localhost:${GO_PORT}/api/v1"
JAVA="http://localhost:${JAVA_PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"

GTOKEN="$(login "${TEST_EMAIL}" "${GO_PORT}")"
JTOKEN="$(login "${TEST_EMAIL}" "${JAVA_PORT}")"

pass=0; fail=0

mask() { python3 -c '
import re, sys
s = sys.stdin.read()
s = re.sub(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<UUID>", s)
s = re.sub(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})", "<TS>", s)
sys.stdout.write(s)
'; }

hit() { # hit <side> <outfile> <method> <path> [body]
  local side="$1" out="$2" method="$3" path="$4" body="${5:-}"
  local base token
  if [ "${side}" = "go" ]; then base="${GO}"; token="${GTOKEN}"; else base="${JAVA}"; token="${JTOKEN}"; fi
  path="${path//@SID@/${SID}}"
  # 删除类用例两侧各删各的孪生消息（Go 先删会消费掉同 id 的状态）
  if [ "${side}" = "go" ]; then path="${path//@DELMSG@/${M5}}"; else path="${path//@DELMSG@/${M6}}"; fi
  local args=(-s -o "/tmp/${out}" -w '%{http_code}' -X "${method}" "${base}${path}" -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json')
  [ -n "${body}" ] && args+=(-d "${body}")
  curl "${args[@]}"
}

ab() { # ab <name> <method> <path> [body]
  local name="$1" method="$2" path="$3" body="${4:-}"
  local gcode jcode
  gcode="$(hit go "${name}.go.json" "${method}" "${path}" "${body}")"
  jcode="$(hit java "${name}.java.json" "${method}" "${path}" "${body}")"
  if [ "${gcode}" = "${jcode}" ] && diff -q <(mask < "/tmp/${name}.go.json") <(mask < "/tmp/${name}.java.json") >/dev/null 2>&1; then
    pass=$((pass+1)); echo "MATCH ${name} (${gcode})"
  else
    fail=$((fail+1)); echo "DIFF  ${name} go=${gcode} java=${jcode}"
    diff <(mask < "/tmp/${name}.go.json") <(mask < "/tmp/${name}.java.json") | head -6 || true
  fi
}

echo "==> 准备"
curl -s -X POST "${GO}/sessions" -H "Authorization: Bearer ${GTOKEN}" \
  -H 'Content-Type: application/json' -d '{"title":"ab-msg-session"}' > /tmp/ab-msg-session.json
SID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' /tmp/ab-msg-session.json)"
M1="aaa00002-0000-0000-0000-000000000001"; M2="aaa00002-0000-0000-0000-000000000002"
M3="aaa00002-0000-0000-0000-000000000003"; M4="aaa00002-0000-0000-0000-000000000000"
M5="aaa00002-0000-0000-0000-000000000005"; M6="aaa00002-0000-0000-0000-000000000006"
R1="bbb00002-0000-0000-0000-000000000001"; R2="bbb00002-0000-0000-0000-000000000002"
${PSQL} -c "
INSERT INTO messages (id, request_id, session_id, role, content, created_at, updated_at) VALUES
('${M1}', '${R1}', '${SID}', 'user',      '如何泡好一杯茶？', '2026-09-18 18:30:00+08', '2026-09-18 18:30:00+08'),
('${M2}', '${R1}', '${SID}', 'assistant', '泡茶要先温杯。a&b<c>d', '2026-09-18 18:30:00+08', '2026-09-18 18:30:00+08'),
('${M3}', '${R2}', '${SID}', 'user',      '咖啡和茶哪个提神？', '2026-09-18 18:31:00+08', '2026-09-18 18:31:00+08'),
('${M4}', '${R2}', '${SID}', 'assistant', '咖啡因含量更高的是咖啡。', '2026-09-18 18:31:00+08', '2026-09-18 18:31:00+08'),
('${M5}', '${R2}', '${SID}', 'user',      '第三问', '2026-09-18 18:32:00+08', '2026-09-18 18:32:00+08'),
('${M6}', '${R2}', '${SID}', 'user',      '第三问', '2026-09-18 18:32:00+08', '2026-09-18 18:32:00+08');" >/dev/null
echo "    SID=${SID}"

echo "==> 1) load"
ab msg-load GET "/messages/@SID@/load"
ab msg-load-limit2 GET "/messages/@SID@/load?limit=2"
ab msg-load-limit-abc GET "/messages/@SID@/load?limit=abc"
ab msg-load-before GET "/messages/@SID@/load"  # before_time 见下（URL 编码）
GBC=$(curl -s -o /tmp/msg-bt.go.json -w '%{http_code}' "http://localhost:${GO_PORT}/api/v1/messages/${SID}/load?before_time=2026-09-18T17%3A35%3A00%2B08%3A00" -H "Authorization: Bearer ${GTOKEN}")
JBC=$(curl -s -o /tmp/msg-bt.java.json -w '%{http_code}' "http://localhost:${JAVA_PORT}/api/v1/messages/${SID}/load?before_time=2026-09-18T17%3A35%3A00%2B08%3A00" -H "Authorization: Bearer ${JTOKEN}")
if [ "${GBC}" = "${JBC}" ] && diff -q <(mask < /tmp/msg-bt.go.json) <(mask < /tmp/msg-bt.java.json) >/dev/null; then
  pass=$((pass+1)); echo "MATCH msg-load-before-encoded (${GBC})"
else
  fail=$((fail+1)); echo "DIFF  msg-load-before-encoded go=${GBC} java=${JBC}"
fi
ab msg-load-before-bad GET "/messages/@SID@/load?before_time=notatime"
ab msg-load-urls-bogus GET "/messages/@SID@/load?resource_urls=bogus"
ab msg-load-urls-public GET "/messages/@SID@/load?resource_urls=public"
ab msg-load-unknown GET "/messages/11111111-2222-3333-4444-999999999999/load"

echo "==> 2) search"
ab msg-search-hybrid POST /messages/search '{"query":"泡茶"}'
ab msg-search-hybrid-two POST /messages/search '{"query":"提神"}'
ab msg-search-keyword POST /messages/search '{"query":"泡茶","mode":"keyword"}'
ab msg-search-nohit POST /messages/search '{"query":"no-such-content-xyz"}'
ab msg-search-filter POST /messages/search "{\"query\":\"泡茶\",\"session_ids\":[\"${SID}\"]}"
ab msg-search-empty POST /messages/search '{"query":""}'
ab msg-search-nobody POST /messages/search
ab msg-search-badjson POST /messages/search 'not-json'

echo "==> 3) stats"
ab msg-stats GET /messages/chat-history-stats

echo "==> 4) 删除（两侧各删孪生）"
ab msg-delete DELETE "/messages/@SID@/@DELMSG@"
ab msg-load-after-delete GET "/messages/@SID@/load"
ab msg-delete-again DELETE "/messages/@SID@/@DELMSG@"
ab msg-delete-unknown-session DELETE "/messages/11111111-2222-3333-4444-999999999999/${M1}"

echo "==> 5) 清空"
ab msg-clear DELETE "/sessions/@SID@/messages"
ab msg-load-after-clear GET "/messages/@SID@/load"
ab msg-clear-again DELETE "/sessions/@SID@/messages"

${PSQL} -c "DELETE FROM messages WHERE session_id = '${SID}';" >/dev/null
echo "==> done. MATCH=${pass} DIFF=${fail}"
[ "${fail}" -eq 0 ]
