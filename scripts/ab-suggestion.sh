#!/usr/bin/env bash
# session 波 1 G3（追问建议）的真 PG A/B 对比。
# 会话/消息经 Go API + psql 造一次（同库同 owner），ready 集合 psql 直插；
# ensure 幂等（suppress 复用），事件是 204 无体插入——两侧可先后打同一资源。
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
  -H 'Content-Type: application/json' -d '{"title":"ab-sug-session"}' -o /tmp/ab-sug-session.json
SID="$(python3 -c 'import json,sys; print(json.load(open("/tmp/ab-sug-session.json"))["data"]["id"])')"
U1="ccc00002-0000-0000-0000-000000000001"; A1="ccc00002-0000-0000-0000-000000000002"
A2="ccc00002-0000-0000-0000-000000000004"; SET2="ddd00002-0000-0000-0000-000000000001"
${PSQL} -c "
INSERT INTO messages (id, request_id, session_id, role, content, is_completed, created_at, updated_at) VALUES
('${U1}', 'eee00002-0000-0000-0000-000000000001', '${SID}', 'user',      '如何泡好一杯茶？', true, '2026-09-18 20:30:00+08', '2026-09-18 20:30:00+08'),
('${A1}', 'eee00002-0000-0000-0000-000000000001', '${SID}', 'assistant', '泡茶要先温杯。',   true, '2026-09-18 20:30:05+08', '2026-09-18 20:30:05+08'),
('${A2}', 'eee00002-0000-0000-0000-000000000002', '${SID}', 'assistant', '第二答',           true, '2026-09-18 20:31:05+08', '2026-09-18 20:31:05+08');
INSERT INTO message_suggestion_sets (id, tenant_id, session_id, assistant_message_id, agent_id,
  agent_tenant_id, placement, config_hash, locale, status, allow_regenerate, suppression_reason,
  questions, prompt_tokens, completion_tokens, latency_ms, error_code, created_at, updated_at)
VALUES ('${SET2}', 10002, '${SID}', '${A2}', '', 0, 'after_answer', 'no-agent-config', 'zh-CN',
  'ready', false, '', '[{\"id\":\"q1\",\"text\":\"冷泡茶要泡多久？\",\"source\":\"generated\"}]'::jsonb,
  0, 0, 0, '', '2026-09-18 20:32:00+08', '2026-09-18 20:32:00+08');" >/dev/null
echo "    SID=${SID}"

echo "==> 1) Get"
ab sug-get-404 GET "/sessions/@SID@/messages/${A1}/suggestions"
ab sug-get-ready GET "/sessions/@SID@/messages/${A2}/suggestions"

echo "==> 2) Ensure"
ab sug-ensure POST "/sessions/@SID@/messages/${A1}/suggestions"
ab sug-ensure-again POST "/sessions/@SID@/messages/${A1}/suggestions"
ab sug-ensure-regen POST "/sessions/@SID@/messages/${A1}/suggestions" '{"regenerate":true}'
ab sug-ensure-user-msg POST "/sessions/@SID@/messages/${U1}/suggestions"
ab sug-ensure-unknown-msg POST "/sessions/@SID@/messages/11111111-2222-3333-4444-999999999999/suggestions"
ab sug-ensure-unknown-session POST "/sessions/11111111-2222-3333-4444-999999999999/messages/${A1}/suggestions"
ab sug-ensure-bad-json POST "/sessions/@SID@/messages/${A1}/suggestions" 'not-json'
ab sug-ensure-a2 POST "/sessions/@SID@/messages/${A2}/suggestions"

echo "==> 3) RecordEvent"
ab sug-event-impression POST "/sessions/@SID@/suggestion-events" "{\"suggestion_set_id\":\"${SET2}\",\"event_type\":\"impression\"}"
ab sug-event-click POST "/sessions/@SID@/suggestion-events" "{\"suggestion_set_id\":\"${SET2}\",\"question_id\":\"q1\",\"event_type\":\"click\"}"
ab sug-event-click-no-qid POST "/sessions/@SID@/suggestion-events" "{\"suggestion_set_id\":\"${SET2}\",\"event_type\":\"click\"}"
ab sug-event-bad-qid POST "/sessions/@SID@/suggestion-events" "{\"suggestion_set_id\":\"${SET2}\",\"question_id\":\"nope\",\"event_type\":\"click\"}"
ab sug-event-bad-type POST "/sessions/@SID@/suggestion-events" "{\"suggestion_set_id\":\"${SET2}\",\"event_type\":\"hover\"}"
ab sug-event-unknown-set POST "/sessions/@SID@/suggestion-events" "{\"suggestion_set_id\":\"11111111-2222-3333-4444-999999999999\",\"event_type\":\"impression\"}"
ab sug-event-no-body POST "/sessions/@SID@/suggestion-events"
ab sug-event-missing-type POST "/sessions/@SID@/suggestion-events" "{\"suggestion_set_id\":\"${SET2}\"}"

${PSQL} -c "DELETE FROM message_suggestion_events WHERE session_id = '${SID}';
DELETE FROM message_suggestion_sets WHERE session_id = '${SID}';
DELETE FROM messages WHERE session_id = '${SID}';" >/dev/null
echo "==> done. MATCH=${pass} DIFF=${fail}"
[ "${fail}" -eq 0 ]
