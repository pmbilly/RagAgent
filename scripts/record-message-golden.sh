#!/usr/bin/env bash
# 录 session 波 1 G2（消息面：load×2 / delete / search / chat-history-stats +
# ClearSessionMessages）的 golden。
#
# 前提：Go server :8080；psql 可达 dev PG（localhost:15432）。
# 消息没有 HTTP 创建端点（由聊天管线产生，波 4/5）——所以用 psql 直接造数据，
# 会话本身走 API 创建（拿到真实的 owner 范围）。
# 注意：**一律用 curl -o 落盘**（zsh 的 echo 会解释 \n 转义，§9）。
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

echo "==> 准备：会话 + 4 条消息（M1/M2 同一 request_id 且同 created_at，测 user-first 平序）"
req msg-setup-session.json POST /sessions -H 'Content-Type: application/json' \
  -d '{"title":"msg-golden-session","description":"message golden"}'
SID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "${OUT}/msg-setup-session.json")"
echo "    SID=${SID}"

M1="aaa00001-0000-0000-0000-000000000001"
M2="aaa00001-0000-0000-0000-000000000002"
M3="aaa00001-0000-0000-0000-000000000003"
M4="aaa00001-0000-0000-0000-000000000000"
R1="bbb00001-0000-0000-0000-000000000001"
R2="bbb00001-0000-0000-0000-000000000002"

${PSQL} -c "
INSERT INTO messages (id, request_id, session_id, role, content, created_at, updated_at) VALUES
('${M1}', '${R1}', '${SID}', 'user',      '如何泡好一杯茶？', '2026-09-18 17:30:00+08', '2026-09-18 17:30:00+08'),
('${M2}', '${R1}', '${SID}', 'assistant', '泡茶要先温杯。a&b<c>d', '2026-09-18 17:30:00+08', '2026-09-18 17:30:00+08'),
('${M3}', '${R2}', '${SID}', 'user',      '咖啡和茶哪个提神？', '2026-09-18 17:31:00+08', '2026-09-18 17:31:00+08'),
('${M4}', '${R2}', '${SID}', 'assistant', '咖啡因含量更高的是咖啡。', '2026-09-18 17:31:00+08', '2026-09-18 17:31:00+08');" >/dev/null

echo "==> 1) load（成功 + limit 容错 + before_time + resource_urls + 404）"
req msg-load.json GET "/messages/${SID}/load"
req msg-load-limit2.json GET "/messages/${SID}/load?limit=2"
req msg-load-limit-abc.json GET "/messages/${SID}/load?limit=abc"
req msg-load-before.json GET "/messages/${SID}/load?before_time=2026-09-18T17%3A30%3A30%2B08%3A00"
req msg-load-before-empty.json GET "/messages/${SID}/load?before_time=2026-09-18T17%3A30%3A00%2B08%3A00"
req msg-load-before-bad.json GET "/messages/${SID}/load?before_time=notatime"
req msg-load-urls-bogus.json GET "/messages/${SID}/load?resource_urls=bogus"
req msg-load-urls-public.json GET "/messages/${SID}/load?resource_urls=public"
req msg-load-unknown.json GET "/messages/${UNKNOWN_ID}/load"

echo "==> 2) search（hybrid RRF / keyword 线性分 / 校验失败分支）"
req msg-search-hybrid.json POST /messages/search -H 'Content-Type: application/json' \
  -d '{"query":"泡茶"}'
req msg-search-hybrid-two.json POST /messages/search -H 'Content-Type: application/json' \
  -d '{"query":"提神"}'
req msg-search-keyword.json POST /messages/search -H 'Content-Type: application/json' \
  -d '{"query":"泡茶","mode":"keyword"}'
req msg-search-nohit.json POST /messages/search -H 'Content-Type: application/json' \
  -d '{"query":"no-such-content-xyz"}'
req msg-search-session-filter.json POST /messages/search -H 'Content-Type: application/json' \
  -d "{\"query\":\"泡茶\",\"session_ids\":[\"${SID}\"]}"
req msg-search-empty-query.json POST /messages/search -H 'Content-Type: application/json' \
  -d '{"query":""}'
req msg-search-no-body.json POST /messages/search -H 'Content-Type: application/json'
req msg-search-bad-json.json POST /messages/search -H 'Content-Type: application/json' -d 'not-json'

echo "==> 3) chat-history-stats"
req msg-stats.json GET /messages/chat-history-stats

echo "==> 4) 删除消息（成功 / 幂等 404 / 会话 404）"
req msg-delete.json DELETE "/messages/${SID}/${M3}"
req msg-load-after-delete.json GET "/messages/${SID}/load"
req msg-delete-again.json DELETE "/messages/${SID}/${M3}"
req msg-delete-unknown-session.json DELETE "/messages/${UNKNOWN_ID}/${M1}"

echo "==> 5) 清空会话消息"
req msg-clear.json DELETE "/sessions/${SID}/messages"
req msg-load-after-clear.json GET "/messages/${SID}/load"
req msg-clear-again.json DELETE "/sessions/${SID}/messages"

echo "==> 清理：删会话（软删）+ 消息行"
${PSQL} -c "DELETE FROM messages WHERE session_id = '${SID}';" >/dev/null
curl -s -o /dev/null -w '%{http_code} delete session\n' -X DELETE "${API}/sessions/${SID}" -H "${AUTH}"
rm -f "${OUT}/msg-setup-session.json"

echo "==> done. golden 在 ${OUT}/msg-*.json"
