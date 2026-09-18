#!/usr/bin/env bash
# 录 session 模块波 1 G1（CRUD + pin，8 条端点）的 golden。
#
# 前提：Go server 起在 :8080（scripts/go-server-up.sh）。
# 注意：**一律用 curl -o 落盘**（zsh 的 echo 会解释 \n 转义，§9）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=dev-env.sh
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${RAGAGENT_ROOT}/server/src/test/resources/contracts"
API="http://localhost:${GO_PORT}/api/v1"

TOKEN="$(login "${TEST_EMAIL}" "${GO_PORT}")"
VIEWER="$(login "${TEST_VIEWER_EMAIL}" "${GO_PORT}")"
AUTH="Authorization: Bearer ${TOKEN}"
VAUTH="Authorization: Bearer ${VIEWER}"

req() { # req <outfile> <method> <path> [extra curl args...]
  local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "${AUTH}" "$@"
}

UNKNOWN_ID="11111111-2222-3333-4444-999999999999"

echo "==> 1) 创建（失败分支 + 成功）"
req session-create-no-body.json POST /sessions -H 'Content-Type: application/json'
req session-create-bad-json.json POST /sessions -H 'Content-Type: application/json' -d 'not-json'
req session-create-empty-obj.json POST /sessions -H 'Content-Type: application/json' -d '{}'
req session-create-unknown-field.json POST /sessions -H 'Content-Type: application/json' \
  -d '{"title":"u","unknown_key":123}'
req session-create.json POST /sessions -H 'Content-Type: application/json' \
  -d '{"title":"golden-session","description":"session golden"}'
SESSION_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "${OUT}/session-create.json")"
echo "    SESSION_ID=${SESSION_ID}"

echo "==> 2) 创建第二个（供批量/置顶/删除用）"
req session-create-2.json POST /sessions -H 'Content-Type: application/json' \
  -d '{"description":"second no title"}'
SESSION_ID2="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "${OUT}/session-create-2.json")"
echo "    SESSION_ID2=${SESSION_ID2}"

echo "==> 3) 读"
req session-get.json GET "/sessions/${SESSION_ID}"
req session-get-unknown.json GET "/sessions/${UNKNOWN_ID}"

echo "==> 4) 列表（成功 + 分页边界 + 权限）"
req session-list.json GET /sessions
req session-list-page0.json GET "/sessions?page=0"
req session-list-page2.json GET "/sessions?page=1&page_size=1"
req session-list-keyword.json GET "/sessions?keyword=golden"
req session-list-nohit.json GET "/sessions?keyword=no-such-title-xyz"
req session-list-source-web.json GET "/sessions?source=web"
req session-list-source-api.json GET "/sessions?source=api"
req session-list-source-unknown.json GET "/sessions?source=bogus"
req session-list-page-abc.json GET "/sessions?page=abc"
req session-list-page-neg.json GET "/sessions?page=-1"
req session-list-size-over.json GET "/sessions?page_size=1001"
req session-list-size-abc.json GET "/sessions?page_size=abc"
curl -s -o "${OUT}/session-list-viewer.json" -w '%{http_code} viewer list\n' \
  -X GET "${API}/sessions" -H "${VAUTH}"
curl -s -o "${OUT}/session-list-viewer-api.json" -w '%{http_code} viewer list source=api\n' \
  -X GET "${API}/sessions?source=api" -H "${VAUTH}"
curl -s -o "${OUT}/session-create-viewer.json" -w '%{http_code} viewer create\n' \
  -X POST "${API}/sessions" -H "${VAUTH}" -H 'Content-Type: application/json' \
  -d '{"title":"viewer-session"}'
VIEWER_SESSION_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "${OUT}/session-create-viewer.json")"

echo "==> 5) 更新"
req session-update-no-body.json PUT "/sessions/${SESSION_ID}" -H 'Content-Type: application/json'
req session-update-bad-json.json PUT "/sessions/${SESSION_ID}" -H 'Content-Type: application/json' -d 'not-json'
req session-update.json PUT "/sessions/${SESSION_ID}" -H 'Content-Type: application/json' \
  -d '{"title":"golden-session-renamed","description":"updated desc"}'
req session-update-empty-title.json PUT "/sessions/${SESSION_ID}" -H 'Content-Type: application/json' \
  -d '{"title":"","description":""}'
req session-update-unknown-id.json PUT "/sessions/${UNKNOWN_ID}" -H 'Content-Type: application/json' \
  -d '{"title":"x"}'
req session-update-marker.json PUT "/sessions/${SESSION_ID}" -H 'Content-Type: application/json' \
  -d '{"title":"t","description":"skill_maintenance:planted"}'
req session-get-after-marker.json GET "/sessions/${SESSION_ID}"

echo "==> 6) 置顶 / 取消置顶"
req session-pin.json POST "/sessions/${SESSION_ID}/pin"
req session-pin-again.json POST "/sessions/${SESSION_ID}/pin"
req session-get-pinned.json GET "/sessions/${SESSION_ID}"
req session-list-pinned.json GET "/sessions"
req session-pin-unknown.json POST "/sessions/${UNKNOWN_ID}/pin"
req session-unpin.json DELETE "/sessions/${SESSION_ID}/pin"
req session-unpin-unknown.json DELETE "/sessions/${UNKNOWN_ID}/pin"

echo "==> 7) 批量删除（失败分支）"
req session-batch-no-body.json DELETE /sessions/batch -H 'Content-Type: application/json'
req session-batch-bad-json.json DELETE /sessions/batch -H 'Content-Type: application/json' -d 'not-json'
req session-batch-empty-ids.json DELETE /sessions/batch -H 'Content-Type: application/json' -d '{"ids":[]}'
req session-batch-blank-ids.json DELETE /sessions/batch -H 'Content-Type: application/json' \
  -d '{"ids":["","  "]}'
req session-batch-unknown.json DELETE /sessions/batch -H 'Content-Type: application/json' \
  -d "{\"ids\":[\"${UNKNOWN_ID}\"]}"
req session-batch-mixed.json DELETE /sessions/batch -H 'Content-Type: application/json' \
  -d "{\"ids\":[\"${UNKNOWN_ID}\",\"${SESSION_ID2}\"]}"

echo "==> 8) 单个删除 + 删除后读取"
req session-delete.json DELETE "/sessions/${SESSION_ID}"
req session-delete-again.json DELETE "/sessions/${SESSION_ID}"
req session-get-deleted.json GET "/sessions/${SESSION_ID}"

echo "==> 9) 清理 viewer 会话 + delete_all"
curl -s -o /dev/null -w '%{http_code} delete viewer session\n' \
  -X DELETE "${API}/sessions/${VIEWER_SESSION_ID}" -H "${VAUTH}"
req session-delete-all.json DELETE /sessions/batch -H 'Content-Type: application/json' \
  -d '{"delete_all":true}'
req session-list-after-delete-all.json GET /sessions

echo "==> done. golden 在 ${OUT}/session-*.json"
