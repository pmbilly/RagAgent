#!/usr/bin/env bash
# W5α2 QA 共享 agent 批真 PG A/B：knowledge-chat 的 resolveAgent 共享分支。
#   正路径：共享解析通过 + 执行租户切换（模型行只在源租户 10005——不切换必
#           "model not found"，本批的核心判别锚）+ stub LLM 全链路 SSE 双端对拍；
#   负路径：source 缺失/错误 → 404 "Shared agent not found"、badsource → 400
#           Go unmarshal 措辞，双端逐字节对拍。
#
# 前置（双端 + stub 同指，与 ab-qa46d.sh 同款）：
#   1) python3 scripts/stub-llm-server.py 8181 &
#   2) SSRF_WHITELIST_EXTRA=127.0.0.1 scripts/go-server-up.sh
#   3) SSRF_WHITELIST_EXTRA=127.0.0.1 scripts/java-server-up.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
GO="http://localhost:${GO_PORT}/api/v1"
JAVA="http://localhost:${JAVA_PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"
WORK="$(mktemp -d /tmp/ab-w5q.XXXXXX)"
trap 'rm -rf "${WORK}"' EXIT

ORG="77777777-1111-4444-8888-0000000000a5"
UB="11111111-2222-3333-4444-555555555021"
UD="11111111-2222-3333-4444-555555555025"
AG_QA="44444444-4444-4444-4444-44444444a511"
W5Q_MODEL="aaaa0000-0000-4000-8000-00000000a501"
SID_G="22222222-2222-4222-8222-22222222a501"
SID_J="22222222-2222-4222-8222-22222222a502"
MISSING="99999999-9999-9999-9999-999999999999"

echo "==> 幂等清理 + 种子（双侧各一条会话，避免历史互染）"
${PSQL} >/dev/null 2>&1 <<SQL
DELETE FROM messages WHERE session_id IN ('${SID_G}','${SID_J}');
DELETE FROM agent_shares WHERE agent_id='${AG_QA}';
DELETE FROM organization_tenant_members WHERE tenant_id IN (10003,10005) AND organization_id='${ORG}';
DELETE FROM organizations WHERE id='${ORG}';
DELETE FROM sessions WHERE id IN ('${SID_G}','${SID_J}');
DELETE FROM models WHERE id='${W5Q_MODEL}';
DELETE FROM custom_agents WHERE id='${AG_QA}';
DELETE FROM tenant_members WHERE user_id IN ('${UB}','${UD}');
DELETE FROM users WHERE id IN ('${UB}','${UD}');
DELETE FROM tenants WHERE id IN (10003,10005);
SQL
${PSQL} >/dev/null <<SQL
INSERT INTO tenants (id, name, description, business, status) VALUES
  (10003, 'w5s-caller-tenant', '', '', 'active'),
  (10005, 'w5s-source-tenant', '', '', 'active');
INSERT INTO users (id, username, email, password_hash, tenant_id, is_active) VALUES
  ('${UB}', 'w5sb', 'w5s-b@weknora.test', '\$2a\$10\$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK', 10003, true),
  ('${UD}', 'w5sd', 'w5s-d@weknora.test', '\$2a\$10\$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK', 10005, true);
INSERT INTO tenant_members (user_id, tenant_id, role, status) VALUES
  ('${UB}', 10003, 'owner', 'active'),
  ('${UD}', 10005, 'owner', 'active');
INSERT INTO organizations (id, name, description, owner_id, owner_tenant_id, invite_code,
  invite_code_validity_days, avatar, require_approval, searchable, member_limit, created_at, updated_at)
VALUES ('${ORG}', 'w5s-org', 'w5s share org', '${UD}', 10005, 'w5sfixedcode0001',
  7, '', false, false, 200, '2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00');
INSERT INTO organization_tenant_members (id, organization_id, tenant_id, role, representative_user_id, joined_at, created_at, updated_at) VALUES
  ('66666666-6666-4444-8888-0000000000a5', '${ORG}', 10005, 'admin', '${UD}', '2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00'),
  ('66666666-6666-4444-8888-0000000000a6', '${ORG}', 10003, 'viewer', '${UB}', '2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00');
INSERT INTO models (id, tenant_id, name, display_name, type, source, description, parameters, is_default, status, created_at, updated_at) VALUES
  ('${W5Q_MODEL}', 10005, 'w5q-stub-llm', 'w5q stub llm', 'KnowledgeQA', 'remote', '',
   '{"base_url":"http://127.0.0.1:8181/v1","provider":"openai","api_key":"stub"}',
   false, 'active', '2026-09-01 08:20:00+00', '2026-09-01 08:20:00+00');
INSERT INTO custom_agents (id, name, description, avatar, is_builtin, tenant_id, created_by, config, created_at, updated_at) VALUES
  ('${AG_QA}', 'w5q-agent-shared', 'shared qa agent', '', false, 10005, '${UD}',
   '{"agent_mode":"quick-answer","model_id":"${W5Q_MODEL}","kb_selection_mode":"none","web_search_enabled":false}',
   '2026-09-01 08:10:00+00', '2026-09-01 08:10:00+00');
INSERT INTO agent_shares (id, agent_id, organization_id, shared_by_user_id, source_tenant_id, permission, created_at, updated_at) VALUES
  ('88888888-8888-4444-8888-0000000000b1', '${AG_QA}', '${ORG}', '${UD}', 10005, 'viewer', '2026-09-01 09:10:00+00', '2026-09-01 09:10:00+00');
INSERT INTO sessions (id, tenant_id, title, description, user_id, created_at, updated_at) VALUES
  ('${SID_G}', 10003, 'w5q-session', '', '${UB}', '2026-09-01 10:00:00+00', '2026-09-01 10:00:00+00'),
  ('${SID_J}', 10003, 'w5q-session', '', '${UB}', '2026-09-01 10:00:00+00', '2026-09-01 10:00:00+00');
SQL

GTOKEN="$(login 'w5s-b@weknora.test' "${GO_PORT}")"
JTOKEN="$(login 'w5s-b@weknora.test' "${JAVA_PORT}")"
[ -n "${GTOKEN}" ] && [ -n "${JTOKEN}" ] || { echo "FATAL: 登录失败"; exit 1; }

mask() { python3 -c '
import re,sys
s=sys.stdin.read()
s=re.sub(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}","<UUID>",s)
s=re.sub(r"[0-9a-f]{8}-answer","<EVT>-answer",s)
s=re.sub(r"[0-9a-f]{8}-thinking","<EVT>-thinking",s)
s=re.sub(r"answer-fallback-[0-9]+","answer-fallback-<TS>",s)
s=re.sub(r"query-[0-9]+","query-<TS>",s)
s=re.sub(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?(Z|[+-]\d{2}:?\d{2})","<TS>",s)
s=re.sub(r"\"completed_at\":[0-9]+","\"completed_at\":<N>",s)
s=re.sub(r"\"duration_ms\":[0-9]+","\"duration_ms\":<N>",s)
s=re.sub(r"\"total_duration_ms\":[0-9]+","\"total_duration_ms\":<N>",s)
sys.stdout.write(s)'; }

pass=0; fail=0
# SSE 场景：双侧各自会话（@SID_G@/@SID_J@ 按侧替换），掩码后逐字节比对
ab_sse() { # name body
  local name="$1" body="$2"
  curl -s -N --max-time 30 -o "${WORK}/${name}.go.sse" -X POST "${GO}/knowledge-chat/${SID_G}" \
      -H "Authorization: Bearer ${GTOKEN}" -H 'Content-Type: application/json' -d "${body}"
  curl -s -N --max-time 30 -o "${WORK}/${name}.java.sse" -X POST "${JAVA}/knowledge-chat/${SID_J}" \
      -H "Authorization: Bearer ${JTOKEN}" -H 'Content-Type: application/json' -d "${body}"
  if diff -q <(mask < "${WORK}/${name}.go.sse") <(mask < "${WORK}/${name}.java.sse") >/dev/null 2>&1; then
    pass=$((pass+1)); echo "MATCH ${name}"
  else
    fail=$((fail+1)); echo "DIFF  ${name}"
    diff <(mask < "${WORK}/${name}.go.sse") <(mask < "${WORK}/${name}.java.sse") | head -10 | sed 's/^/       /'
  fi
}

# JSON 场景（pre-SSE 错误面）：状态码 + 体逐字节（无动态字段）
ab_json() { # name sid_field body
  local name="$1" body="$2"
  local g j
  g="$(curl -s -o "${WORK}/${name}.go.json" -w '%{http_code}' --max-time 30 -X POST "${GO}/knowledge-chat/${SID_G}" \
      -H "Authorization: Bearer ${GTOKEN}" -H 'Content-Type: application/json' -d "${body}")"
  j="$(curl -s -o "${WORK}/${name}.java.json" -w '%{http_code}' --max-time 30 -X POST "${JAVA}/knowledge-chat/${SID_J}" \
      -H "Authorization: Bearer ${JTOKEN}" -H 'Content-Type: application/json' -d "${body}")"
  if [ "${g}" = "${j}" ] && cmp -s "${WORK}/${name}.go.json" "${WORK}/${name}.java.json"; then
    pass=$((pass+1)); echo "MATCH ${name} (${g})"
  else
    fail=$((fail+1)); echo "DIFF  ${name} go=${g} java=${j}"
    diff "${WORK}/${name}.go.json" "${WORK}/${name}.java.json" | head -6 | sed 's/^/       /'
  fi
}

echo "==> 正路径（执行租户切换判别：模型行只在源租户 10005）"
ab_sse w5q-kch-shared-stub \
  "{\"query\":\"<<SCENARIO:chat>>\",\"agent_id\":\"${AG_QA}\",\"disable_title\":true}"
ab_sse w5q-kch-shared-source-stub \
  "{\"query\":\"<<SCENARIO:chat>>\",\"agent_id\":\"${AG_QA}\",\"agent_source_tenant_id\":10005,\"disable_title\":true}"

echo "==> 负路径（pre-SSE 错误面）"
ab_json w5q-kch-source-missing \
  "{\"query\":\"w5q 探针\",\"agent_id\":\"${MISSING}\",\"agent_source_tenant_id\":10005,\"disable_title\":true}"
ab_json w5q-kch-source-wrong \
  "{\"query\":\"w5q 探针\",\"agent_id\":\"${AG_QA}\",\"agent_source_tenant_id\":10004,\"disable_title\":true}"
ab_json w5q-kch-badsource \
  "{\"query\":\"w5q 探针\",\"agent_id\":\"${AG_QA}\",\"agent_source_tenant_id\":\"abc\",\"disable_title\":true}"

echo "==> 清理"
${PSQL} >/dev/null 2>&1 <<SQL
DELETE FROM messages WHERE session_id IN ('${SID_G}','${SID_J}');
DELETE FROM agent_shares WHERE agent_id='${AG_QA}';
DELETE FROM organization_tenant_members WHERE tenant_id IN (10003,10005) AND organization_id='${ORG}';
DELETE FROM organizations WHERE id='${ORG}';
DELETE FROM sessions WHERE id IN ('${SID_G}','${SID_J}');
DELETE FROM models WHERE id='${W5Q_MODEL}';
DELETE FROM custom_agents WHERE id='${AG_QA}';
DELETE FROM tenant_members WHERE user_id IN ('${UB}','${UD}');
DELETE FROM users WHERE id IN ('${UB}','${UD}');
DELETE FROM tenants WHERE id IN (10003,10005);
SQL

echo "==> pass=${pass} fail=${fail}"
[ "${fail}" = "0" ] && echo "ALL MATCH" || { echo "HAS DIFF"; exit 1; }
