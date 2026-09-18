#!/usr/bin/env bash
# session 波 1 G4（steer）A/B：无活轮分支 + 校验错误（排队路径随波 4 e2e）。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
GO="http://localhost:${GO_PORT}/api/v1"
JAVA="http://localhost:${JAVA_PORT}/api/v1"
GTOKEN="$(login "${TEST_EMAIL}" "${GO_PORT}")"
JTOKEN="$(login "${TEST_EMAIL}" "${JAVA_PORT}")"
pass=0; fail=0
mask() { python3 -c 'import re,sys; s=sys.stdin.read(); s=re.sub(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}","<UUID>",s); sys.stdout.write(s)'; }
hit() {
  local side="$1" out="$2" method="$3" path="$4" body="${5:-}"
  local base token
  if [ "${side}" = "go" ]; then base="${GO}"; token="${GTOKEN}"; else base="${JAVA}"; token="${JTOKEN}"; fi
  path="${path//@SID@/${SID}}"
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
echo "==> prep"
curl -s -X POST "${GO}/sessions" -H "Authorization: Bearer ${GTOKEN}" -H 'Content-Type: application/json' -d '{"title":""}' -o /tmp/st-s1.json
SID="$(python3 -c 'import json,sys; print(json.load(open("/tmp/st-s1.json"))["data"]["id"])')"
echo "    SID=${SID}"
ab st-list-empty GET "/sessions/@SID@/steer"
ab st-delete-gone DELETE "/sessions/@SID@/steer/eee00001-0000-0000-0000-000000000001"
ab st-post-new-run POST "/sessions/@SID@/steer" '{"query":"第一问"}'
ab st-promote-new-run POST "/sessions/@SID@/steer/eee00001-0000-0000-0000-000000000001/inject"
ab st-post-empty POST "/sessions/@SID@/steer" '{}'
ab st-post-empty-query POST "/sessions/@SID@/steer" '{"query":""}'
ab st-post-whitespace POST "/sessions/@SID@/steer" '{"query":"   "}'
ab st-post-bad-json POST "/sessions/@SID@/steer" 'not-json'
ab st-post-bad-delivery POST "/sessions/@SID@/steer" '{"query":"x","delivery":"bogus"}'
ab st-post-unknown-session POST "/sessions/11111111-2222-3333-4444-999999999999/steer" '{"query":"x"}'
ab st-post-too-long POST "/sessions/@SID@/steer"
LONG_QUERY="$(python3 -c 'print("\u957f"*10001)')"
ab st-post-too-long POST "/sessions/@SID@/steer" "{\"query\":\"${LONG_QUERY}\"}"
echo "==> done. MATCH=${pass} DIFF=${fail}"
[ "${fail}" -eq 0 ]
