#!/usr/bin/env bash
# session 波 1 G6（产物/title/stop）的真 PG A/B 对比。数据 psql 直插一次，两侧共读。
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
hit() {
  local side="$1" out="$2" method="$3" path="$4" body="${5:-}"
  local base token
  if [ "${side}" = "go" ]; then base="${GO}"; token="${GTOKEN}"; else base="${JAVA}"; token="${JTOKEN}"; fi
  path="${path//@SID@/${SID}}"
  path="${path//@S2@/${S2}}"
  local args=(-s -o "/tmp/${out}" -w '%{http_code}' -X "${method}" "${base}${path}" -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json')
  [ -n "${body}" ] && args+=(-d "${body}")
  curl "${args[@]}"
}
ab() {
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
curl -s -X POST "${GO}/sessions" -H "Authorization: Bearer ${GTOKEN}" -H 'Content-Type: application/json' -d '{"title":""}' -o /tmp/g6-s1.json
SID="$(python3 -c 'import json,sys; print(json.load(open("/tmp/g6-s1.json"))["data"]["id"])')"
curl -s -X POST "${GO}/sessions" -H "Authorization: Bearer ${GTOKEN}" -H 'Content-Type: application/json' -d '{"title":"已生成的标题"}' -o /tmp/g6-s2.json
S2="$(python3 -c 'import json,sys; print(json.load(open("/tmp/g6-s2.json"))["data"]["id"])')"
A1="fff00002-0000-0000-0000-000000000001"; U1="fff00002-0000-0000-0000-000000000002"
A3="fff00002-0000-0000-0000-000000000003"
${PSQL} -c "INSERT INTO messages (id, request_id, session_id, role, content, is_completed, artifacts, created_at, updated_at) VALUES ('${A1}', 'hhh00001-0000-0000-0000-000000000001', '${SID}', 'assistant', '带产物的回答', true, '[{\"url\":\"resource://abcdefghijklmnopqrstuv\",\"file_name\":\"report.pdf\",\"file_type\":\"pdf\",\"file_size\":1234,\"source_path\":\"/tmp/report.pdf\",\"mod_time\":\"2026-09-18T21:00:00+08:00\",\"created_at\":\"2026-09-18T21:00:00+08:00\"}]'::jsonb, '2026-09-18 21:00:00+08', '2026-09-18 21:00:00+08'), ('${U1}', 'hhh00001-0000-0000-0000-000000000002', '${SID}', 'user', '用户问题', true, '[]'::jsonb, '2026-09-18 21:00:01+08', '2026-09-18 21:00:01+08'), ('${A3}', 'hhh00001-0000-0000-0000-000000000003', '${SID}', 'assistant', '生成中的回答', false, '[]'::jsonb, '2026-09-18 21:00:02+08', '2026-09-18 21:00:02+08');" >/dev/null
echo "    SID=${SID} S2=${S2}"
echo "==> 1) 列表"
ab g6-artifacts-list GET "/sessions/@SID@/artifacts"
ab g6-artifacts-session-404 GET "/sessions/11111111-2222-3333-4444-999999999999/artifacts"
ab g6-artifacts-msg GET "/sessions/@SID@/messages/${A1}/artifacts"
ab g6-artifacts-msg-empty GET "/sessions/@SID@/messages/${U1}/artifacts"
ab g6-artifacts-msg-404 GET "/sessions/@SID@/messages/11111111-2222-3333-4444-999999999999/artifacts"
echo "==> 2) 下载"
ab g6-dl-noaccess GET "/sessions/@SID@/messages/${A1}/artifacts/0/download"
ab g6-dl-range GET "/sessions/@SID@/messages/${A1}/artifacts/5/download"
ab g6-dl-bad-index GET "/sessions/@SID@/messages/${A1}/artifacts/abc/download"
ab g6-dl-msg-404 GET "/sessions/@SID@/messages/11111111-2222-3333-4444-999999999999/artifacts/0/download"
ab g6-dl-session-404 GET "/sessions/11111111-2222-3333-4444-999999999999/messages/${A1}/artifacts/0/download"
echo "==> 3) generate_title"
UMSG='{"role":"user","content":"第一问","is_completed":true}'
AMSG='{"role":"assistant","content":"答","is_completed":true}'
ab g6-title-existing POST "/sessions/@S2@/generate_title" "{\"messages\":[${UMSG}]}"
ab g6-title-no-user POST "/sessions/@SID@/generate_title" "{\"messages\":[${AMSG}]}"
ab g6-title-empty-list POST "/sessions/@SID@/generate_title" '{"messages":[]}'
ab g6-title-no-body POST "/sessions/@SID@/generate_title"
ab g6-title-bad-json POST "/sessions/@SID@/generate_title" 'not-json'
ab g6-title-404 POST "/sessions/11111111-2222-3333-4444-999999999999/generate_title" "{\"messages\":[${UMSG}]}"
echo "==> 4) stop"
ab g6-stop-completed POST "/sessions/@SID@/stop" "{\"message_id\":\"${U1}\"}"
ab g6-stop-running POST "/sessions/@SID@/stop" "{\"message_id\":\"${A3}\"}"
ab g6-stop-unknown-msg POST "/sessions/@SID@/stop" "{\"message_id\":\"11111111-2222-3333-4444-999999999999\"}"
ab g6-stop-unknown-session POST "/sessions/11111111-2222-3333-4444-999999999999/stop" "{\"message_id\":\"${A1}\"}"
ab g6-stop-no-body POST "/sessions/@SID@/stop"
ab g6-stop-empty-msgid POST "/sessions/@SID@/stop" '{"message_id":""}'
${PSQL} -c "DELETE FROM messages WHERE session_id = '${SID}';" >/dev/null
echo "==> done. MATCH=${pass} DIFF=${fail}"
[ "${fail}" -eq 0 ]
