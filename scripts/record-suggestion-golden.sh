#!/usr/bin/env bash
# 录 session 波 1 G3（追问建议 3 条）的 golden。
#
# 前提：Go server :8080；psql 直插消息与一条 ready 建议集合。
# set -euo pipefail
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${RAGAGENT_ROOT}/server/src/test/resources/contracts"
API="http://localhost:${GO_PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"

TOKEN="$(login "${TEST_EMAIL}" "${GO_PORT}")"
AUTH="Authorization: Bearer ${TOKEN}"

req() { # req <outfile> <method> <path> [extra curl args...]
  local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "${AUTH}" "$@"
}

UNKNOWN_ID="11111111-2222-3333-4444-999999999999"

echo "==> 准备：会话 + 两轮问答 + 一条 ready 建议集合"
req sug-setup.json POST /sessions -H 'Content-Type: application/json' \
  -d '{"title":"sug-golden-session"}'
SID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "${OUT}/sug-setup.json")"
echo "    SID=${SID}"

U1="ccc00001-0000-0000-0000-000000000001"
A1="ccc00001-0000-0000-0000-000000000002"
U2="ccc00001-0000-0000-0000-000000000003"
A2="ccc00001-0000-0000-0000-000000000004"
SET2="ddd00001-0000-0000-0000-000000000001"
R1="eee00001-0000-0000-0000-000000000001"
R2="eee00001-0000-0000-0000-000000000002"

${PSQL} -c "
INSERT INTO messages (id, request_id, session_id, role, content, is_completed, created_at, updated_at) VALUES
('${U1}', '${R1}', '${SID}', 'user',      '如何泡好一杯茶？', true, '2026-09-18 19:30:00+08', '2026-09-18 19:30:00+08'),
('${A1}', '${R1}', '${SID}', 'assistant', '泡茶要先温杯。',   true, '2026-09-18 19:30:05+08', '2026-09-18 19:30:05+08'),
('${U2}', '${R2}', '${SID}', 'user',      '第二问',           true, '2026-09-18 19:31:00+08', '2026-09-18 19:31:00+08'),
('${A2}', '${R2}', '${SID}', 'assistant', '第二答',           true, '2026-09-18 19:31:05+08', '2026-09-18 19:31:05+08');
INSERT INTO message_suggestion_sets (id, tenant_id, session_id, assistant_message_id, agent_id,
  agent_tenant_id, placement, config_hash, locale, status, allow_regenerate, suppression_reason,
  questions, prompt_tokens, completion_tokens, latency_ms, error_code, created_at, updated_at)
VALUES ('${SET2}', 10002, '${SID}', '${A2}', '', 0, 'after_answer', 'no-agent-config', 'zh-CN',
  'ready', false, '', '[{\"id\":\"q1\",\"text\":\"冷泡茶要泡多久？\",\"source\":\"generated\"}]'::jsonb,
  0, 0, 0, '', '2026-09-18 19:32:00+08', '2026-09-18 19:32:00+08');" >/dev/null

echo "==> 1) Get（未生成 → 404；ready 集合 → 200 带问题）"
req sug-get-404.json GET "/sessions/${SID}/messages/${A1}/suggestions"
req sug-get-ready.json GET "/sessions/${SID}/messages/${A2}/suggestions"

echo "==> 2) Ensure（suppress 路径 / 复用 / regenerate 拒绝 / 非法消息）"
req sug-ensure.json POST "/sessions/${SID}/messages/${A1}/suggestions" -H 'Content-Type: application/json'
req sug-ensure-again.json POST "/sessions/${SID}/messages/${A1}/suggestions" -H 'Content-Type: application/json'
req sug-ensure-empty-obj.json POST "/sessions/${SID}/messages/${A1}/suggestions" \
  -H 'Content-Type: application/json' -d '{}'
req sug-ensure-regen.json POST "/sessions/${SID}/messages/${A1}/suggestions" \
  -H 'Content-Type: application/json' -d '{"regenerate":true}'
req sug-ensure-user-msg.json POST "/sessions/${SID}/messages/${U1}/suggestions" \
  -H 'Content-Type: application/json'
req sug-ensure-unknown-msg.json POST "/sessions/${SID}/messages/${UNKNOWN_ID}/suggestions" \
  -H 'Content-Type: application/json'
req sug-ensure-unknown-session.json POST "/sessions/${UNKNOWN_ID}/messages/${A1}/suggestions" \
  -H 'Content-Type: application/json'
req sug-ensure-bad-json.json POST "/sessions/${SID}/messages/${A1}/suggestions" \
  -H 'Content-Type: application/json' -d 'not-json'
req sug-ensure-a2.json POST "/sessions/${SID}/messages/${A2}/suggestions" \
  -H 'Content-Type: application/json'

echo "==> 3) RecordEvent（204 / 子串 400 / 404）"
SET1="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "${OUT}/sug-ensure.json")"
echo "    SET1=${SET1}"
req sug-event-impression.json POST "/sessions/${SID}/suggestion-events" -H 'Content-Type: application/json' \
  -d "{\"suggestion_set_id\":\"${SET2}\",\"event_type\":\"impression\"}"
req sug-event-click.json POST "/sessions/${SID}/suggestion-events" -H 'Content-Type: application/json' \
  -d "{\"suggestion_set_id\":\"${SET2}\",\"question_id\":\"q1\",\"event_type\":\"click\"}"
req sug-event-click-no-qid.json POST "/sessions/${SID}/suggestion-events" -H 'Content-Type: application/json' \
  -d "{\"suggestion_set_id\":\"${SET2}\",\"event_type\":\"click\"}"
req sug-event-bad-qid.json POST "/sessions/${SID}/suggestion-events" -H 'Content-Type: application/json' \
  -d "{\"suggestion_set_id\":\"${SET2}\",\"question_id\":\"nope\",\"event_type\":\"click\"}"
req sug-event-bad-type.json POST "/sessions/${SID}/suggestion-events" -H 'Content-Type: application/json' \
  -d "{\"suggestion_set_id\":\"${SET2}\",\"event_type\":\"hover\"}"
req sug-event-unknown-set.json POST "/sessions/${SID}/suggestion-events" -H 'Content-Type: application/json' \
  -d "{\"suggestion_set_id\":\"${UNKNOWN_ID}\",\"event_type\":\"impression\"}"
req sug-event-no-body.json POST "/sessions/${SID}/suggestion-events" -H 'Content-Type: application/json'
req sug-event-bad-json.json POST "/sessions/${SID}/suggestion-events" -H 'Content-Type: application/json' -d 'not-json'
req sug-event-missing-type.json POST "/sessions/${SID}/suggestion-events" -H 'Content-Type: application/json' \
  -d "{\"suggestion_set_id\":\"${SET2}\"}"

echo "==> 清理"
${PSQL} -c "DELETE FROM message_suggestion_events WHERE session_id = '${SID}';
DELETE FROM message_suggestion_sets WHERE session_id = '${SID}';
DELETE FROM messages WHERE session_id = '${SID}';" >/dev/null
curl -s -o /dev/null -w '%{http_code} delete session\n' -X DELETE "${API}/sessions/${SID}" -H "${AUTH}"
rm -f "${OUT}/sug-setup.json"
echo "==> done. golden 在 ${OUT}/sug-*.json"
