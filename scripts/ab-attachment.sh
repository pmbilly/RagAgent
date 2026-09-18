#!/usr/bin/env bash
# session 波 1 G5（临时文档 attachments）的真 PG A/B 对比。
#
# 前提：Go server :8080、Java server :8082，两侧共享 dev PG（localhost:15432）。
# 做法：两侧各建各的会话、各传各的附件；掩码 UUID/时间戳后对比状态码与 body。
# 附件解析是异步的（Go asynq / Java executor），终态 ready 前有 sleep。
# 预览对比头（去掉 X-Request-Id/Date）与字节体。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

GO="http://localhost:${GO_PORT}/api/v1"
JAVA="http://localhost:${JAVA_PORT}/api/v1"
WORK="$(mktemp -d /tmp/ab-att.XXXXXX)"
TAG="abatt-$(date +%s)"
UNKNOWN_ID="11111111-2222-3333-4444-999999999999"
TMP="$(mktemp -d)"
printf '第一行内容\n第二行内容\n第三行内容\n' > "${TMP}/note.txt"

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

mask_headers() {
  # 状态行归一（Tomcat 不发 reason phrase）；滤容器/CORS 层非业务头；
  # "charset=" 前空白归一——Tomcat 对 Content-Type 规范化（语义等价，已知差异）
  sed -E 's#^HTTP/[0-9.]+ [0-9]+.*#HTTP <code>#; s#;[[:space:]]*charset=#;charset=#g' \
    | grep -viE '^(X-Request-Id|Date|Content-Length|Connection|Keep-Alive|Vary):' | sort
}

# hit <side> <outfile> <method> <path> [--file <path>|--data <body>]
hit() {
  local side="$1" out="$2" method="$3" path="$4"; shift 4
  local base token sid
  if [ "${side}" = "go" ]; then base="${GO}"; token="${GTOKEN}"; sid="${GS1:-}"; else base="${JAVA}"; token="${JTOKEN}"; sid="${JS1:-}"; fi
  path="${path//@S1@/${sid}}"
  local args=(-s -o "/tmp/${out}" -w '%{http_code}' -X "${method}" "${base}${path}" -H "Authorization: Bearer ${token}")
  while [ $# -gt 0 ]; do
    case "$1" in
      --file) args+=(-F "file=@$2;type=text/plain"); shift 2 ;;
      --data) args+=(-H 'Content-Type: application/json' -d "$2"); shift 2 ;;
      *) shift ;;
    esac
  done
  curl "${args[@]}"
}

ab() { # ab <name> <method> <path> [--file|-–data ...]
  local name="$1" method="$2" path="$3"; shift 3
  local gcode jcode
  gcode="$(hit go "${name}.go.json" "${method}" "${path}" "$@")"
  jcode="$(hit java "${name}.java.json" "${method}" "${path}" "$@")"
  if [ "${gcode}" = "${jcode}" ] && diff -q <(mask < "/tmp/${name}.go.json") <(mask < "/tmp/${name}.java.json") >/dev/null 2>&1; then
    pass=$((pass+1)); echo "MATCH ${name} (${gcode})"
  else
    fail=$((fail+1)); echo "DIFF  ${name} go=${gcode} java=${jcode}"
    diff <(mask < "/tmp/${name}.go.json") <(mask < "/tmp/${name}.java.json") | head -6 || true
  fi
}

create_session() { # create_session <side> -> sid
  local side="$1" token base
  local out="/tmp/${side}-ab-att-session.json"
  if [ "${side}" = "go" ]; then base="${GO}"; token="${GTOKEN}"; else base="${JAVA}"; token="${JTOKEN}"; fi
  curl -s -o "${out}" -X POST "${base}/sessions" \
    -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' \
    -d "{\"title\":\"${TAG}\",\"description\":\"ab att\"}"
  python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['data']['id'])" "${out}"
}

echo "==> 0) 两侧建会话"
GS1="$(create_session go)"; JS1="$(create_session java)"
echo "    go=${GS1} java=${JS1}"

echo "==> 1) 上传"
GCODE="$(hit go "${TAG}-up.go.json" POST "/sessions/@S1@/attachments" --file "${TMP}/note.txt")"
JCODE="$(hit java "${TAG}-up.java.json" POST "/sessions/@S1@/attachments" --file "${TMP}/note.txt")"
if [ "${GCODE}" = "${JCODE}" ] && diff -q <(mask < "/tmp/${TAG}-up.go.json") <(mask < "/tmp/${TAG}-up.java.json") >/dev/null; then
  pass=$((pass+1)); echo "MATCH upload (${GCODE})"
else
  fail=$((fail+1)); echo "DIFF  upload go=${GCODE} java=${JCODE}"
  diff <(mask < "/tmp/${TAG}-up.go.json") <(mask < "/tmp/${TAG}-up.java.json") | head -6 || true
fi
GATT="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['data']['id'])" "/tmp/${TAG}-up.go.json")"
JATT="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['data']['id'])" "/tmp/${TAG}-up.java.json")"

printf 'x' > "${TMP}/evil.exe"
ab "$TAG-up-unknown-session" POST "/sessions/${UNKNOWN_ID}/attachments" --file "${TMP}/note.txt"
printf 'y' > "${TMP}/empty.txt"
# unsupported：直接对两侧已存在会话各打一发
GC2="$(hit go "${TAG}-unsup.go.json" POST "/sessions/${GS1}/attachments" --file "${TMP}/evil.exe")"
JC2="$(hit java "${TAG}-unsup.java.json" POST "/sessions/${JS1}/attachments" --file "${TMP}/evil.exe")"
if [ "${GC2}" = "${JC2}" ] && diff -q <(mask < "/tmp/${TAG}-unsup.go.json") <(mask < "/tmp/${TAG}-unsup.java.json") >/dev/null; then
  pass=$((pass+1)); echo "MATCH upload-unsupported (${GC2})"
else
  fail=$((fail+1)); echo "DIFF  upload-unsupported go=${GC2} java=${JC2}"
  diff <(mask < "/tmp/${TAG}-unsup.go.json") <(mask < "/tmp/${TAG}-unsup.java.json") | head -4 || true
fi

echo "==> 3) 等待两侧 worker 落终态"
sleep 4

# 详情/列表：两侧各自附件 id，掩码后对比（ID 掩码使两条不同 id 同构）
ab_detail() {
  local side="$1" token att out code
  if [ "${side}" = "go" ]; then token="${GTOKEN}"; att="${GATT}"; base="${GO}"; name="${TAG}-get.go.json"; else token="${JTOKEN}"; att="${JATT}"; base="${JAVA}"; name="${TAG}-get.java.json"; fi
  code="$(curl -s -o "/tmp/${name}" -w '%{http_code}' "${base}/sessions/$([ "${side}" = go ] && echo "${GS1}" || echo "${JS1}")/attachments/${att}" -H "Authorization: Bearer ${token}")"
  echo "${code}"
}
GC="$(ab_detail go)"; JC="$(ab_detail java)"
if [ "${GC}" = "${JC}" ] && diff -q <(mask < "/tmp/${TAG}-get.go.json") <(mask < "/tmp/${TAG}-get.java.json") >/dev/null; then
  pass=$((pass+1)); echo "MATCH get-ready (${GC})"
else
  fail=$((fail+1)); echo "DIFF  get-ready go=${GC} java=${JC}"
  diff <(mask < "/tmp/${TAG}-get.go.json") <(mask < "/tmp/${TAG}-get.java.json") | head -8 || true
fi

ab "$TAG-get-404" GET "/sessions/@S1@/attachments/${UNKNOWN_ID}"
ab "$TAG-list-404" GET "/sessions/${UNKNOWN_ID}/attachments"

echo "==> 4) 预览（头 + 体）"
GH="$(curl -s -D - -o "/tmp/${TAG}-pbody.go" "${GO}/sessions/${GS1}/attachments/${GATT}/preview" -H "Authorization: Bearer ${GTOKEN}" | mask_headers)"
JH="$(curl -s -D - -o "/tmp/${TAG}-pbody.java" "${JAVA}/sessions/${JS1}/attachments/${JATT}/preview" -H "Authorization: Bearer ${JTOKEN}" | mask_headers)"
if [ "${GH}" = "${JH}" ] && diff -q "/tmp/${TAG}-pbody.go" "/tmp/${TAG}-pbody.java" >/dev/null; then
  pass=$((pass+1)); echo "MATCH preview"
else
  fail=$((fail+1)); echo "DIFF  preview"
  diff <(echo "${GH}") <(echo "${JH}") | head -6 || true
  diff "/tmp/${TAG}-pbody.go" "/tmp/${TAG}-pbody.java" | head -3 || true
fi

ab "$TAG-preview-404" GET "/sessions/@S1@/attachments/${UNKNOWN_ID}/preview"

echo "==> 5) 删除（幂等，按侧 id）"
GC3="$(hit go "${TAG}-del.go.json" DELETE "/sessions/${GS1}/attachments/${GATT}")"
JC3="$(hit java "${TAG}-del.java.json" DELETE "/sessions/${JS1}/attachments/${JATT}")"
if [ "${GC3}" = "${JC3}" ] && [ "${GC3}" = "204" ]; then
  pass=$((pass+1)); echo "MATCH delete (${GC3})"
else
  fail=$((fail+1)); echo "DIFF  delete go=${GC3} java=${JC3}"
fi
GC4="$(hit go "${TAG}-list-del.go.json" GET "/sessions/${GS1}/attachments")"
JC4="$(hit java "${TAG}-list-del.java.json" GET "/sessions/${JS1}/attachments")"
if [ "${GC4}" = "${JC4}" ] && diff -q <(mask < "/tmp/${TAG}-list-del.go.json") <(mask < "/tmp/${TAG}-list-del.java.json") >/dev/null; then
  pass=$((pass+1)); echo "MATCH list-after-delete (${GC4})"
else
  fail=$((fail+1)); echo "DIFF  list-after-delete go=${GC4} java=${JC4}"
  diff <(mask < "/tmp/${TAG}-list-del.go.json") <(mask < "/tmp/${TAG}-list-del.java.json") | head -4 || true
fi
GC5="$(hit go "${TAG}-del2.go.json" DELETE "/sessions/${GS1}/attachments/${GATT}")"
JC5="$(hit java "${TAG}-del2.java.json" DELETE "/sessions/${JS1}/attachments/${JATT}")"
if [ "${GC5}" = "${JC5}" ] && [ "${GC5}" = "204" ]; then
  pass=$((pass+1)); echo "MATCH delete-again (${GC5})"
else
  fail=$((fail+1)); echo "DIFF  delete-again go=${GC5} java=${JC5}"
fi

echo "==> 清理会话"
curl -s -o /dev/null -w '%{http_code} go\n' -X DELETE "${GO}/sessions/${GS1}" -H "Authorization: Bearer ${GTOKEN}"
curl -s -o /dev/null -w '%{http_code} java\n' -X DELETE "${JAVA}/sessions/${JS1}" -H "Authorization: Bearer ${JTOKEN}"
rm -rf "${TMP}" "${WORK}"

echo "== A/B 完成：MATCH=${pass} DIFF=${fail} =="
[ "${fail}" = "0" ]
