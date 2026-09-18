#!/usr/bin/env bash
# session 波 1 G1（CRUD + pin）的真 PG A/B 对比。
#
# 前提：Go server :8080、Java server :8082，两侧共享 dev PG（localhost:15432）。
# 做法：同一用例两侧各打一发，掩码 UUID/时间戳后对比状态码与 body。
# 列表用例一律带 keyword=<run 标签>——两侧各建各的会话，过滤后集合同构。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

GO="http://localhost:${GO_PORT}/api/v1"
JAVA="http://localhost:${JAVA_PORT}/api/v1"
WORK="$(mktemp -d /tmp/ab-session.XXXXXX)"
TAG="ab-$(date +%s)"
UNKNOWN_ID="11111111-2222-3333-4444-999999999999"

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
  local base token sid
  if [ "${side}" = "go" ]; then base="${GO}"; token="${GTOKEN}"; sid="${GS1:-}"; else base="${JAVA}"; token="${JTOKEN}"; sid="${JS1:-}"; fi
  path="${path//@S1@/${sid}}"
  body="${body//@S1@/${sid}}"
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

id_of() { python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['data']['id'])" "/tmp/$1"; }

echo "==> 1) 创建"
ab "$TAG-create-no-body" POST /sessions
ab "$TAG-create-bad-json" POST /sessions 'not-json'
ab "$TAG-create" POST /sessions "{\"title\":\"${TAG}\",\"description\":\"ab test\"}"
GS1="$(id_of "$TAG-create.go.json")"; JS1="$(id_of "$TAG-create.java.json")"
echo "    go=$GS1 java=$JS1"

echo "==> 2) 读"
ab "$TAG-get" GET "/sessions/@S1@"
ab "$TAG-get-unknown" GET "/sessions/${UNKNOWN_ID}"

echo "==> 3) 列表与分页边界"
ab "$TAG-list" GET "/sessions?keyword=${TAG}"
ab "$TAG-list-page0" GET "/sessions?page=0&keyword=${TAG}"
ab "$TAG-list-keyword" GET "/sessions?keyword=${TAG}-no-hit"
ab "$TAG-list-page-abc" GET "/sessions?page=abc"
ab "$TAG-list-page-neg" GET "/sessions?page=-1"
ab "$TAG-list-size-over" GET "/sessions?page_size=1001"
ab "$TAG-list-size-abc" GET "/sessions?page_size=abc"

echo "==> 4) 更新"
ab "$TAG-update-no-body" PUT "/sessions/@S1@"
ab "$TAG-update-bad-json" PUT "/sessions/@S1@" 'not-json'
ab "$TAG-update" PUT "/sessions/@S1@" '{"title":"renamed","description":"ab updated"}'
ab "$TAG-update-unknown" PUT "/sessions/${UNKNOWN_ID}" '{"title":"x"}'
ab "$TAG-update-marker" PUT "/sessions/@S1@" '{"title":"t","description":"skill_maintenance:planted"}'
ab "$TAG-get-after-marker" GET "/sessions/@S1@"

echo "==> 5) 置顶"
ab "$TAG-pin" POST "/sessions/@S1@/pin"
ab "$TAG-get-pinned" GET "/sessions/@S1@"
ab "$TAG-list-pinned" GET "/sessions?keyword=${TAG}"
ab "$TAG-pin-unknown" POST "/sessions/${UNKNOWN_ID}/pin"
ab "$TAG-unpin" DELETE "/sessions/@S1@/pin"
ab "$TAG-unpin-unknown" DELETE "/sessions/${UNKNOWN_ID}/pin"

echo "==> 6) 批量删除"
ab "$TAG-batch-no-body" DELETE /sessions/batch
ab "$TAG-batch-bad-json" DELETE /sessions/batch 'not-json'
ab "$TAG-batch-empty-ids" DELETE /sessions/batch '{"ids":[]}'
ab "$TAG-batch-blank-ids" DELETE /sessions/batch '{"ids":["","  "]}'
ab "$TAG-batch-unknown" DELETE /sessions/batch "{\"ids\":[\"${UNKNOWN_ID}\"]}"
ab "$TAG-batch-mixed" DELETE /sessions/batch '{"ids":["11111111-2222-3333-4444-999999999999","@S1@"]}'

echo "==> 7) 单删"
ab "$TAG-delete-again" DELETE "/sessions/@S1@"
ab "$TAG-get-deleted" GET "/sessions/@S1@"

echo "==> 8) delete_all（收尾清理）"
ab "$TAG-delete-all" DELETE /sessions/batch '{"delete_all":true}'
ab "$TAG-list-after" GET "/sessions?keyword=${TAG}"

rm -rf "${WORK}"
echo "==> done. MATCH=${pass} DIFF=${fail}"
[ "${fail}" -eq 0 ]
