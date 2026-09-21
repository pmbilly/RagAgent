#!/usr/bin/env bash
# 录 W5α2 QA 共享 agent 批 golden：w5q-*（knowledge-chat 的 resolveAgent 共享分支
# pre-SSE 错误面）。种子全部 SQL 直插（固定 uuid/时间戳，确定性）；
# SSE 正路径（stub LLM 全链路）由 ab-w5q.sh 双端对拍，不进字节 golden。
#
# 拓扑：复用 w5s 的 10003（调用方 UB=viewer）/10005（源 UD）+ ORG；AG_QA 共享到 ORG，
# 模型行 W5Q_MODEL 只在源租户 10005（执行租户切换的判别锚）；会话 SID 属于调用方。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
OUT="${W5Q_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${W5Q_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"
mkdir -p "${OUT}"

ORG="77777777-1111-4444-8888-0000000000a5"
UB="11111111-2222-3333-4444-555555555021"
UD="11111111-2222-3333-4444-555555555025"
AG_QA="44444444-4444-4444-4444-44444444a511"
W5Q_MODEL="aaaa0000-0000-4000-8000-00000000a501"
SID="22222222-2222-4222-8222-22222222a501"
MISSING="99999999-9999-9999-9999-999999999999"

seed() {
${PSQL} >/dev/null 2>&1 <<SQL
DELETE FROM agent_shares WHERE agent_id='${AG_QA}';
DELETE FROM organization_tenant_members WHERE tenant_id IN (10003,10005) AND organization_id='${ORG}';
DELETE FROM organizations WHERE id='${ORG}';
DELETE FROM sessions WHERE id='${SID}';
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
  ('${SID}', 10003, 'w5q-session', '', '${UB}', '2026-09-01 10:00:00+00', '2026-09-01 10:00:00+00');
SQL
}

cleanup() {
${PSQL} >/dev/null 2>&1 <<SQL
DELETE FROM messages WHERE session_id='${SID}';
DELETE FROM agent_shares WHERE agent_id='${AG_QA}';
DELETE FROM organization_tenant_members WHERE tenant_id IN (10003,10005) AND organization_id='${ORG}';
DELETE FROM organizations WHERE id='${ORG}';
DELETE FROM sessions WHERE id='${SID}';
DELETE FROM models WHERE id='${W5Q_MODEL}';
DELETE FROM custom_agents WHERE id='${AG_QA}';
DELETE FROM tenant_members WHERE user_id IN ('${UB}','${UD}');
DELETE FROM users WHERE id IN ('${UB}','${UD}');
DELETE FROM tenants WHERE id IN (10003,10005);
SQL
}

if [ "${1:-}" = "--cleanup-only" ]; then cleanup; echo "==> 已清理"; exit 0; fi

echo "==> 幂等清理 + 种子（10003/10005 + ORG + AG_QA 共享 + 模型行（仅 10005）+ 会话）"
seed

TOKEN_B="$(login 'w5s-b@weknora.test' "${PORT}")"
[ -n "${TOKEN_B}" ] || { echo "FATAL: B 登录失败"; exit 1; }
JB="Authorization: Bearer ${TOKEN_B}"

req() { local out="$1" body="$2"
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X POST "${API}/knowledge-chat/${SID}" \
    -H "${JB}" -H 'Content-Type: application/json' -d "${body}"; }

echo "==> knowledge-chat resolveAgent 共享分支 pre-SSE 错误面（3 场景）"
req w5q-kch-source-missing.json "{\"query\":\"w5q 探针\",\"agent_id\":\"${MISSING}\",\"agent_source_tenant_id\":10005,\"disable_title\":true}"
req w5q-kch-source-wrong.json   "{\"query\":\"w5q 探针\",\"agent_id\":\"${AG_QA}\",\"agent_source_tenant_id\":10004,\"disable_title\":true}"
req w5q-kch-badsource.json      "{\"query\":\"w5q 探针\",\"agent_id\":\"${AG_QA}\",\"agent_source_tenant_id\":\"abc\",\"disable_title\":true}"

echo "==> 清理"
cleanup
echo "==> 完成（3 条 w5q-*）"
