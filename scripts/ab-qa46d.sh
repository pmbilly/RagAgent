#!/usr/bin/env bash
# 波 4.6d A/B：chat 三入口（knowledge-chat / agent-chat / knowledge-search）。
#
# 前置（双端 + stub 同指）：
#   1) python3 scripts/stub-llm-server.py 8181 &
#   2) SSRF_WHITELIST_EXTRA=127.0.0.1 scripts/go-server-up.sh
#   3) SSRF_WHITELIST_EXTRA=127.0.0.1 scripts/java-server-up.sh
#   4) 通过任一侧 API 建一条指 stub 的 KnowledgeQA 模型行（STUB_MODEL_ID）。
#
# SSE 场景两侧各跑各的会话/消息（uuid/时间戳/事件 id/耗时经 mask 后逐字节比对）。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
GO="http://localhost:${GO_PORT}/api/v1"
JAVA="http://localhost:${JAVA_PORT}/api/v1"
GTOKEN="$(login "${TEST_EMAIL}" "${GO_PORT}")"
JTOKEN="$(login "${TEST_EMAIL}" "${JAVA_PORT}")"
STUB_MODEL_ID="${STUB_MODEL_ID:?export STUB_MODEL_ID=<model row id pointing at http://127.0.0.1:8181/v1>}"
OUT="${QA46D_OUT_DIR:-/tmp/qa46d-ab}"
mkdir -p "${OUT}"
pass=0; fail=0

mask() { python3 -c '
import re,sys
s=sys.stdin.read()
s=re.sub(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}","<UUID>",s)
s=re.sub(r"[0-9a-f]{8}-answer","<EVT>-answer",s)
s=re.sub(r"[0-9a-f]{8}-thinking","<EVT>-thinking",s)
s=re.sub(r"answer-fallback-[0-9]+","answer-fallback-<TS>",s)
s=re.sub(r"query-[0-9]+","query-<TS>",s)
s=re.sub(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?Z","<TS>",s)
s=re.sub(r"\"completed_at\":[0-9]+","\"completed_at\":<N>",s)
s=re.sub(r"\"duration_ms\":[0-9]+","\"duration_ms\":<N>",s)
s=re.sub(r"\"total_duration_ms\":[0-9]+","\"total_duration_ms\":<N>",s)
sys.stdout.write(s)'; }

run_sse() { # side out path body
  local side="$1" out="$2" path="$3" body="$4"
  local base token
  if [ "${side}" = "go" ]; then base="${GO}"; token="${GTOKEN}"; else base="${JAVA}"; token="${JTOKEN}"; fi
  local args=(--max-time 30 -s -N -o "${OUT}/${out}" -w '%{http_code}' -X POST "${base}${path}"
        -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json')
  [ -n "${body}" ] && args+=(-d "${body}")
  curl "${args[@]}"
}

ab_json() { # name path body
  local name="$1" path="$2" body="${3:-}"
  local g j
  if [ -z "${SID:-}" ]; then
    SID="$(curl -s -X POST "${GO}/sessions" -H "Authorization: Bearer ${GTOKEN}" -H 'Content-Type: application/json' -d '{"title":""}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["id"])')"
  fi
  g="$(curl -s --max-time 30 -o "${OUT}/${name}.go.json" -w '%{http_code}' -X POST "${GO}${path//@SID@/${SID}}" \
      -H "Authorization: Bearer ${GTOKEN}" -H 'Content-Type: application/json' ${body:+-d "${body}"})"
  j="$(curl -s --max-time 30 -o "${OUT}/${name}.java.json" -w '%{http_code}' -X POST "${JAVA}${path//@SID@/${SID}}" \
      -H "Authorization: Bearer ${JTOKEN}" -H 'Content-Type: application/json' ${body:+-d "${body}"})"
  if [ "${g}" = "${j}" ] && diff -q <(mask < "${OUT}/${name}.go.json") <(mask < "${OUT}/${name}.java.json") >/dev/null 2>&1; then
    pass=$((pass+1)); echo "MATCH ${name} (${g})"
  else
    fail=$((fail+1)); echo "DIFF  ${name} go=${g} java=${j}"
    diff <(mask < "${OUT}/${name}.go.json") <(mask < "${OUT}/${name}.java.json") | head -6 || true
  fi
}

ab_sse() { # name path body  （两侧各自新建 session；body 里 @MODEL@/@QUERY@ 占位）
  local name="$1" path="$2" body="${3:-}"
  local g j
  local gsid jsid
  curl -s -X POST "${GO}/sessions" -H "Authorization: Bearer ${GTOKEN}" -H 'Content-Type: application/json' \
      -d '{"title":""}' -o "${OUT}/${name}.gsess.json"
  curl -s -X POST "${JAVA}/sessions" -H "Authorization: Bearer ${JTOKEN}" -H 'Content-Type: application/json' \
      -d '{"title":""}' -o "${OUT}/${name}.jsess.json"
  gsid="$(python3 -c "import json;print(json.load(open('${OUT}/${name}.gsess.json'))['data']['id'])")"
  jsid="$(python3 -c "import json;print(json.load(open('${OUT}/${name}.jsess.json'))['data']['id'])")"
  local gbody jbody
  gbody="${body//@MODEL@/${STUB_MODEL_ID}}"
  gbody="${gbody//@GSID@/${gsid}}"
  jbody="${body//@MODEL@/${STUB_MODEL_ID}}"
  jbody="${jbody//@JSID@/${jsid}}"
  g="$(curl -s --max-time 30 -N -o "${OUT}/${name}.go.sse" -w '%{http_code}' -X POST "${GO}${path//@SID@/${gsid}}" \
      -H "Authorization: Bearer ${GTOKEN}" -H 'Content-Type: application/json' -d "${gbody}")"
  j="$(curl -s --max-time 30 -N -o "${OUT}/${name}.java.sse" -w '%{http_code}' -X POST "${JAVA}${path//@SID@/${jsid}}" \
      -H "Authorization: Bearer ${JTOKEN}" -H 'Content-Type: application/json' -d "${jbody}")"
  if [ "${g}" = "${j}" ] && diff -q <(mask < "${OUT}/${name}.go.sse") <(mask < "${OUT}/${name}.java.sse") >/dev/null 2>&1; then
    pass=$((pass+1)); echo "MATCH ${name} (${g})"
  else
    fail=$((fail+1)); echo "DIFF  ${name} go=${g} java=${j}"
    diff <(mask < "${OUT}/${name}.go.sse") <(mask < "${OUT}/${name}.java.sse") | head -12 || true
  fi
}

echo "==> knowledge-search（kse-：确定性校验/错误 + 空检索结果）"
ab_json kse-empty-query "/knowledge-search" '{"query":""}'
ab_json kse-no-targets "/knowledge-search" '{"query":"anything"}'
ab_json kse-bad-json "/knowledge-search" 'not-json'
ab_json kse-unknown-kb "/knowledge-search" "{\"query\":\"q\",\"knowledge_base_ids\":[\"11111111-2222-3333-4444-555555555555\"]}"

echo "==> knowledge-chat（kch-：校验错 + 404 + stub 全链路）"
SID_G="$(curl -s -X POST "${GO}/sessions" -H "Authorization: Bearer ${GTOKEN}" -H 'Content-Type: application/json' -d '{"title":"kch"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["id"])')"
ab_json kch-empty-query "/knowledge-chat/${SID_G}" '{"query":""}'
ab_json kch-missing-session "/knowledge-chat/11111111-2222-3333-4444-999999999999" '{"query":"x"}'
ab_json kch-local-browser "/knowledge-chat/${SID_G}" '{"query":"x","local_browser_enabled":true}'
ab_sse kch-stub-chat "/knowledge-chat/@SID@" "{\"query\":\"hello <<SCENARIO:chat>>\",\"summary_model_id\":\"@MODEL@\",\"disable_title\":true}"
ab_sse kch-stub-long "/knowledge-chat/@SID@" "{\"query\":\"hello <<SCENARIO:long>>\",\"summary_model_id\":\"@MODEL@\",\"disable_title\":true}"
ab_sse kch-stub-echo "/knowledge-chat/@SID@" "{\"query\":\"hello <<SCENARIO:echo>>\",\"summary_model_id\":\"@MODEL@\",\"disable_title\":true}"

echo "==> agent-chat（ach-：400 门 + agent_mode 回落）"
SID="$(curl -s -X POST "${GO}/sessions" -H "Authorization: Bearer ${GTOKEN}" -H 'Content-Type: application/json' -d '{"title":"ach"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["id"])')"
ab_json ach-no-agent "/agent-chat/@SID@" '{"query":"x","agent_enabled":true}'
ab_json ach-bad-agent "/agent-chat/@SID@" '{"query":"x","agent_enabled":true,"agent_id":"00000000-0000-0000-0000-000000000001","agent_source_tenant_id":424242}'
ab_json ach-gate-off-mode "/agent-chat/@SID@" '{"query":"x"}'
SID_G2="$(curl -s -X POST "${GO}/sessions" -H "Authorization: Bearer ${GTOKEN}" -H 'Content-Type: application/json' -d '{"title":"ach"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["id"])')"
ab_json ach-normal-fallback "/agent-chat/${SID_G2}" '{"query":""}'
ab_sse ach-agent-mode-off "/agent-chat/@SID@" "{\"query\":\"hello <<SCENARIO:chat>>\",\"agent_enabled\":false,\"summary_model_id\":\"@MODEL@\",\"disable_title\":true}"

echo "==> done. MATCH=${pass} DIFF=${fail}"
[ "${fail}" -eq 0 ]
