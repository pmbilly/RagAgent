#!/usr/bin/env bash
# 录 session 波 1 G6（产物 3 条 + generate_title + stop）的 golden。
# title 的 LLM 生成路径非确定性——只录确定性分支（已有标题/无 user 消息/validator）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${RAGAGENT_ROOT}/server/src/test/resources/contracts"
API="http://localhost:${GO_PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"

TOKEN="$(login "${TEST_EMAIL}" "${GO_PORT}")"
AUTH="Authorization: Bearer ${TOKEN}"

req() {
  local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "${AUTH}" "$@"
}

UNKNOWN_ID="11111111-2222-3333-4444-999999999999"
UMSG='{"id":"","request_id":"","session_id":"","role":"user","content":"第一问","knowledge_references":[],"is_completed":true}'

echo "==> 准备：3 个会话"
req g6-s1.json POST /sessions -H 'Content-Type: application/json' -d '{"title":""}'
S1="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "${OUT}/g6-s1.json")"
req g6-s2.json POST /sessions -H 'Content-Type: application/json' -d '{"title":"已生成的标题"}'
S2="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "${OUT}/g6-s2.json")"
req g6-s3.json POST /sessions -H 'Content-Type: application/json' -d '{"title":""}'
S3="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "${OUT}/g6-s3.json")"
rm -f "${OUT}/g6-s1.json" "${OUT}/g6-s2.json" "${OUT}/g6-s3.json"
echo "    S1=${S1} S2=${S2} S3=${S3}"

A1="fff00001-0000-0000-0000-000000000001"
U1="fff00001-0000-0000-0000-000000000002"
A3="fff00001-0000-0000-0000-000000000003"

${PSQL} -c "
INSERT INTO messages (id, request_id, session_id, role, content, is_completed, artifacts, created_at, updated_at) VALUES
('${A1}', 'ggg00001-0000-0000-0000-000000000001', '${S1}', 'assistant', '带产物的回答', true,
 '[{\"url\":\"resource://abcdefghijklmnopqrstuv\",\"file_name\":\"report.pdf\",\"file_type\":\"pdf\",\"file_size\":1234,\"source_path\":\"/tmp/report.pdf\",\"mod_time\":\"2026-09-18T21:00:00+08:00\",\"created_at\":\"2026-09-18T21:00:00+08:00\"}]'::jsonb,
 '2026-09-18 21:00:00+08', '2026-09-18 21:00:00+08'),
('${U1}', 'ggg00001-0000-0000-0000-000000000002', '${S1}', 'user', '用户问题', true, '[]'::jsonb,
 '2026-09-18 21:00:01+08', '2026-09-18 21:00:01+08'),
('${A3}', 'ggg00001-0000-0000-0000-000000000003', '${S3}', 'assistant', '生成中的回答', false, '[]'::jsonb,
 '2026-09-18 21:00:02+08', '2026-09-18 21:00:02+08');" >/dev/null

echo "==> 1) 产物列表"
req g6-artifacts-list.json GET "/sessions/${S1}/artifacts"
req g6-artifacts-session-404.json GET "/sessions/${UNKNOWN_ID}/artifacts"
req g6-artifacts-msg.json GET "/sessions/${S1}/messages/${A1}/artifacts"
req g6-artifacts-msg-empty.json GET "/sessions/${S1}/messages/${U1}/artifacts"
req g6-artifacts-msg-404.json GET "/sessions/${S1}/messages/${UNKNOWN_ID}/artifacts"

echo "==> 2) 产物下载（确定性分支）"
req g6-dl-noaccess.json GET "/sessions/${S1}/messages/${A1}/artifacts/0/download"
req g6-dl-range.json GET "/sessions/${S1}/messages/${A1}/artifacts/5/download"
req g6-dl-bad-index.json GET "/sessions/${S1}/messages/${A1}/artifacts/abc/download"
req g6-dl-msg-404.json GET "/sessions/${S1}/messages/${UNKNOWN_ID}/artifacts/0/download"
req g6-dl-session-404.json GET "/sessions/${UNKNOWN_ID}/messages/${A1}/artifacts/0/download"

echo "==> 3) generate_title"
req g6-title-existing.json POST "/sessions/${S2}/generate_title" -H 'Content-Type: application/json' \
  -d "{\"messages\":[${UMSG}]}"
req g6-title-no-user.json POST "/sessions/${S1}/generate_title" -H 'Content-Type: application/json' \
  -d '{"messages":[{"id":"","request_id":"","session_id":"","role":"assistant","content":"答","knowledge_references":[],"is_completed":true}]}'
req g6-title-empty-list.json POST "/sessions/${S1}/generate_title" -H 'Content-Type: application/json' \
  -d '{"messages":[]}'
req g6-title-no-body.json POST "/sessions/${S1}/generate_title" -H 'Content-Type: application/json'
req g6-title-bad-json.json POST "/sessions/${S1}/generate_title" -H 'Content-Type: application/json' -d 'not-json'
req g6-title-404.json POST "/sessions/${UNKNOWN_ID}/generate_title" -H 'Content-Type: application/json' \
  -d "{\"messages\":[${UMSG}]}"

echo "==> 4) stop"
req g6-stop-completed.json POST "/sessions/${S1}/stop" -H 'Content-Type: application/json' \
  -d "{\"message_id\":\"${U1}\"}"
req g6-stop-running.json POST "/sessions/${S3}/stop" -H 'Content-Type: application/json' \
  -d "{\"message_id\":\"${A3}\"}"
req g6-stop-unknown-msg.json POST "/sessions/${S1}/stop" -H 'Content-Type: application/json' \
  -d "{\"message_id\":\"${UNKNOWN_ID}\"}"
req g6-stop-unknown-session.json POST "/sessions/${UNKNOWN_ID}/stop" -H 'Content-Type: application/json' \
  -d "{\"message_id\":\"${A1}\"}"
req g6-stop-no-body.json POST "/sessions/${S1}/stop" -H 'Content-Type: application/json'
req g6-stop-empty-msgid.json POST "/sessions/${S1}/stop" -H 'Content-Type: application/json' -d '{"message_id":""}"

echo "==> 清理"
${PSQL} -c "DELETE FROM messages WHERE session_id IN ('${S1}','${S2}','${S3}');" >/dev/null
for S in "${S1}" "${S2}" "${S3}"; do
  curl -s -o /dev/null -w '%{http_code} delete\n' -X DELETE "${API}/sessions/${S}" -H "${AUTH}"
done
echo "==> done. golden 在 ${OUT}/g6-*.json"
