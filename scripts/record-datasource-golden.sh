#!/usr/bin/env bash
# 录 datasource 模块的 golden（对照 docs/HANDOFF.md §4 的标准流程）。
#
# 前提：
#   1) Go server 起在 :8080，且带 SSRF_WHITELIST=127.0.0.1,::1,localhost
#      （RSS 连接器会真的去抓 feed，必须放行 loopback）；
#   2) loopback 上有个 stub feed：cd /tmp/ds-stub-root && python3 -m http.server 18099 --bind 127.0.0.1
#      （feed.xml 与 server/src/test/resources/datasource/stub-feed.xml 同一份字节）
#
# 注意：**一律用 curl -o 落盘**。zsh 的 echo 会解释 `\n` 转义，把 golden 写坏（§9）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=dev-env.sh
source "${SCRIPT_DIR}/dev-env.sh"

OUT="${RAGAGENT_ROOT}/server/src/test/resources/contracts"
API="http://localhost:${GO_PORT}/api/v1"
FEED="http://127.0.0.1:18099/feed.xml"

TOKEN="$(login "${TEST_EMAIL}" "${GO_PORT}")"
VIEWER="$(login "${TEST_VIEWER_EMAIL}" "${GO_PORT}")"
AUTH="Authorization: Bearer ${TOKEN}"
VAUTH="Authorization: Bearer ${VIEWER}"

req() { # req <outfile> <method> <path> [extra curl args...]
  local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "${AUTH}" "$@"
}

new_kb() {
  curl -s -X POST "${API}/knowledge-bases" -H "${AUTH}" -H 'Content-Type: application/json' \
    -d "{\"name\":\"$1\",\"description\":\"datasource golden\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["id"])'
}

echo "==> 准备：两个新知识库（一个放数据源、一个保持空以录空列表响应）"
KB_EMPTY="$(new_kb ds-golden-empty)"
KB="$(new_kb ds-golden-main)"
UNKNOWN_KB="11111111-2222-3333-4444-999999999999"
echo "    KB=$KB  KB_EMPTY=$KB_EMPTY"

echo "==> 1) 连接器目录 / 列表"
req ds-types.json GET /datasource/types
req ds-list-kb-required.json GET /datasource
req ds-list-empty.json GET "/datasource?kb_id=${KB_EMPTY}"

echo "==> 2) 创建（各种失败 + 成功）"
req ds-create-no-body.json POST /datasource -H 'Content-Type: application/json'
req ds-create-bad-json.json POST /datasource -H 'Content-Type: application/json' -d 'not-json'
req ds-create-bad-connector.json POST /datasource -H 'Content-Type: application/json' \
  -d "{\"name\":\"x\",\"type\":\"confluence\",\"knowledge_base_id\":\"${KB}\"}"
req ds-create-bad-kb.json POST /datasource -H 'Content-Type: application/json' \
  -d '{"name":"x","type":"rss","knowledge_base_id":"'"${UNKNOWN_KB}"'"}'
req ds-create-missing-kb.json POST /datasource -H 'Content-Type: application/json' \
  -d '{"name":"x","type":"rss"}'
req ds-create-bad-creds.json POST /datasource -H 'Content-Type: application/json' \
  -d "{\"name\":\"x\",\"type\":\"rss\",\"knowledge_base_id\":\"${KB}\"}"

req ds-create.json POST /datasource -H 'Content-Type: application/json' \
  -d "{\"name\":\"golden-rss\",\"type\":\"rss\",\"knowledge_base_id\":\"${KB}\",\"sync_schedule\":\"0 0 * * * *\",\"config\":{\"type\":\"rss\",\"settings\":{\"feed_urls\":\"http://127.0.0.1:18099/feed.xml\"},\"credentials\":{\"feed_urls\":\"http://127.0.0.1:18099/feed.xml\"}}}"
DS_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "${OUT}/ds-create.json")"
echo "    DS_ID=$DS_ID"

echo "==> 3) 读 / 改 / 列表"
req ds-get.json GET "/datasource/${DS_ID}"
req ds-update.json PUT "/datasource/${DS_ID}" -H 'Content-Type: application/json' \
  -d '{"name":"golden-rss-renamed","sync_mode":"full","sync_deletions":false,"error_message":"","config":{"type":"rss","settings":{"feed_urls":"http://127.0.0.1:18099/feed.xml"},"credentials":{"feed_urls":"http://127.0.0.1:18099/feed.xml","api_token":"should-be-ignored"}}}'
req ds-get-after-update.json GET "/datasource/${DS_ID}"
req ds-list.json GET "/datasource?kb_id=${KB}"
req ds-unknown-id.json GET "/datasource/${UNKNOWN_KB}"

echo "==> 4) 连接校验 / 资源枚举"
req ds-validate.json POST "/datasource/${DS_ID}/validate"
req ds-validate-credentials-bad.json POST /datasource/validate-credentials \
  -H 'Content-Type: application/json' -d '{}'
req ds-validate-credentials.json POST /datasource/validate-credentials \
  -H 'Content-Type: application/json' \
  -d '{"type":"rss","credentials":{"feed_urls":"http://127.0.0.1:18099/feed.xml"}}'
req ds-resources.json GET "/datasource/${DS_ID}/resources"
req ds-ancestors-empty.json POST "/datasource/${DS_ID}/resource-ancestors" \
  -H 'Content-Type: application/json' -d '{"resource_ids":[]}'
req ds-ancestors.json POST "/datasource/${DS_ID}/resource-ancestors" \
  -H 'Content-Type: application/json' -d '{"resource_ids":["http://127.0.0.1:18099/feed.xml"]}'

echo "==> 5) 凭据子资源"
req ds-credentials-put-missing.json PUT "/datasource/${DS_ID}/credentials" \
  -H 'Content-Type: application/json' -d '{}'
req ds-credentials-put-empty.json PUT "/datasource/${DS_ID}/credentials" \
  -H 'Content-Type: application/json' -d '{"credentials":{}}'
req ds-credentials-put.json PUT "/datasource/${DS_ID}/credentials" \
  -H 'Content-Type: application/json' \
  -d '{"credentials":{"feed_urls":"http://127.0.0.1:18099/feed.xml"}}'
req ds-credentials-put-auth-headers.json PUT "/datasource/${DS_ID}/credentials" \
  -H 'Content-Type: application/json' \
  -d '{"credentials":{"feed_urls":"http://127.0.0.1:18099/feed.xml","auth_headers":"X-Token: abc"}}'
req ds-credentials-delete-bad-field.json DELETE "/datasource/${DS_ID}/credentials/nope"
req ds-get-after-credentials.json GET "/datasource/${DS_ID}"

echo "==> 6) 同步控制与日志"
req ds-sync.json POST "/datasource/${DS_ID}/sync"
LOG_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "${OUT}/ds-sync.json")"
req ds-logs-bad-limit.json GET "/datasource/${DS_ID}/logs?limit=0"
req ds-logs-bad-limit-abc.json GET "/datasource/${DS_ID}/logs?limit=abc"
req ds-logs-tolerant-offset.json GET "/datasource/${DS_ID}/logs?offset=-5"
req ds-logs.json GET "/datasource/${DS_ID}/logs?limit=2&offset=0"
req ds-log.json GET "/datasource/logs/${LOG_ID}"
req ds-log-unknown.json GET "/datasource/logs/${UNKNOWN_KB}"
req ds-pause.json POST "/datasource/${DS_ID}/pause"
req ds-resume.json POST "/datasource/${DS_ID}/resume"

echo "==> 7) 权限（Viewer 与 scoped API Key）"
curl -s -o "${OUT}/ds-create-forbidden.json" -w '%{http_code} viewer create\n' \
  -X POST "${API}/datasource" -H "${VAUTH}" -H 'Content-Type: application/json' \
  -d "{\"name\":\"x\",\"type\":\"rss\",\"knowledge_base_id\":\"${KB}\"}"
curl -s -o "${OUT}/ds-types-viewer.json" -w '%{http_code} viewer types\n' \
  -X GET "${API}/datasource/types" -H "${VAUTH}"

echo "==> 8) 清理（删掉 golden 数据源与空库）"
curl -s -o /dev/null -w '%{http_code} delete ds\n' -X DELETE "${API}/datasource/${DS_ID}" -H "${AUTH}"
curl -s -o "${OUT}/ds-deleted-get.json" -w '%{http_code} get deleted\n' \
  -X GET "${API}/datasource/${DS_ID}" -H "${AUTH}"
curl -s -o /dev/null -X DELETE "${API}/knowledge-bases/${KB_EMPTY}" -H "${AUTH}" || true
curl -s -o /dev/null -X DELETE "${API}/knowledge-bases/${KB}" -H "${AUTH}" || true

echo "==> done. golden 在 ${OUT}/ds-*.json"
