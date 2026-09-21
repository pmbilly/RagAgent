#!/usr/bin/env bash
# 录 W5α 共享 agent 收口批 golden：w5s-*（KB list / knowledge batch / knowledge search 的
# agent_id 分支）。种子全部 SQL 直插（固定 uuid/时间戳，确定性），无需掩码——
# 契约测试与 ab-w5s.sh 均逐字节比对。
#
# 拓扑：租户 10005（源，UD=owner）把 3 个 agent 共享到 ORG_W5S；租户 10003（UB=viewer）
# 是组织成员，以 B 身份走 agent_id 分支。KB_B 是调用方自有库（验证 own-grant 后的
# scope 拒绝文案）。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
OUT="${W5S_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${W5S_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"
mkdir -p "${OUT}"
req() { local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" "$@"; }

ORG="77777777-1111-4444-8888-0000000000a5"
UB="11111111-2222-3333-4444-555555555021"
UD="11111111-2222-3333-4444-555555555025"
KB_S1="33333333-3333-3333-3333-33333333a501"
KB_S2="33333333-3333-3333-3333-33333333a502"
KB_S3="33333333-3333-3333-3333-33333333a503"
KB_B="33333333-3333-3333-3333-33333333a504"
AG_SEL="44444444-4444-4444-4444-44444444a501"
AG_ALL="44444444-4444-4444-4444-44444444a502"
AG_NONE="44444444-4444-4444-4444-44444444a503"
K1="55555555-5555-5555-5555-55555555a511"
K2="55555555-5555-5555-5555-55555555a512"
K3="55555555-5555-5555-5555-55555555a513"
KB_NO="33333333-3333-3333-3333-33333333a599"
MISSING="99999999-9999-9999-9999-999999999999"

echo "==> 幂等清理 + 种子（租户 10003/10005、KB×4、agent×3、share×3、knowledge×4）"
${PSQL} >/dev/null 2>&1 <<SQL
DELETE FROM agent_shares WHERE source_tenant_id IN (10003,10005) OR shared_by_user_id IN ('${UB}','${UD}');
DELETE FROM organization_tenant_members WHERE tenant_id IN (10003,10005) AND organization_id='${ORG}';
DELETE FROM organizations WHERE id='${ORG}';
DELETE FROM knowledges WHERE id IN ('${K1}','${K2}','${K3}');
DELETE FROM knowledge_bases WHERE id IN ('${KB_S1}','${KB_S2}','${KB_S3}','${KB_B}');
DELETE FROM custom_agents WHERE id IN ('${AG_SEL}','${AG_ALL}','${AG_NONE}');
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
INSERT INTO knowledge_bases (id, name, tenant_id, type, description, creator_id, chunking_config, embedding_model_id, summary_model_id, created_at, updated_at) VALUES
  ('${KB_S1}', 'w5s-kb-selected', 10005, 'document', 'selected kb', '${UD}', '{}', '', '', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
  ('${KB_S2}', 'w5s-kb-other', 10005, 'document', 'other kb', '${UD}', '{}', '', '', '2026-09-01 08:01:00+00', '2026-09-01 08:01:00+00'),
  ('${KB_S3}', 'w5s-kb-faq', 10005, 'faq', 'faq kb', '${UD}', '{}', '', '', '2026-09-01 08:02:00+00', '2026-09-01 08:02:00+00'),
  ('${KB_B}', 'w5s-kb-caller', 10003, 'document', 'caller own kb', '${UB}', '{}', '', '', '2026-09-01 08:03:00+00', '2026-09-01 08:03:00+00');
INSERT INTO custom_agents (id, name, description, avatar, is_builtin, tenant_id, created_by, config, created_at, updated_at) VALUES
  ('${AG_SEL}', 'w5s-agent-sel', 'selected scope', '', false, 10005, '${UD}',
   '{"agent_mode":"quick-answer","model_id":"w5s-model","rerank_model_id":"w5s-rerank","kb_selection_mode":"selected","knowledge_bases":["${KB_S1}"],"web_search_enabled":false}',
   '2026-09-01 08:10:00+00', '2026-09-01 08:10:00+00'),
  ('${AG_ALL}', 'w5s-agent-all', 'all scope', '', false, 10005, '${UD}',
   '{"agent_mode":"smart","model_id":"w5s-model","kb_selection_mode":"all","web_search_enabled":false}',
   '2026-09-01 08:11:00+00', '2026-09-01 08:11:00+00'),
  ('${AG_NONE}', 'w5s-agent-none', 'empty scope', '', false, 10005, '${UD}',
   '{"agent_mode":"quick-answer","model_id":"w5s-model","kb_selection_mode":"none","web_search_enabled":false}',
   '2026-09-01 08:12:00+00', '2026-09-01 08:12:00+00');
INSERT INTO agent_shares (id, agent_id, organization_id, shared_by_user_id, source_tenant_id, permission, created_at, updated_at) VALUES
  ('88888888-8888-4444-8888-0000000000a1', '${AG_SEL}', '${ORG}', '${UD}', 10005, 'viewer', '2026-09-01 09:10:00+00', '2026-09-01 09:10:00+00'),
  ('88888888-8888-4444-8888-0000000000a2', '${AG_ALL}', '${ORG}', '${UD}', 10005, 'viewer', '2026-09-01 09:11:00+00', '2026-09-01 09:11:00+00'),
  ('88888888-8888-4444-8888-0000000000a3', '${AG_NONE}', '${ORG}', '${UD}', 10005, 'viewer', '2026-09-01 09:12:00+00', '2026-09-01 09:12:00+00');
INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, source, parse_status, summary_status, enable_status, file_name, file_type, file_size, file_hash, created_at, updated_at) VALUES
  ('${K1}', 10005, '${KB_S1}', 'document', 'w5sdoc alpha 指南', 'manual', 'completed', 'none', 'enabled', 'w5s-alpha.txt', 'txt', 10, '0000000000000000000000000000a511', '2026-09-01 10:01:00+00', '2026-09-01 10:01:00+00'),
  ('${K2}', 10005, '${KB_S1}', 'document', 'w5sdoc beta 报表', 'manual', 'completed', 'none', 'enabled', 'w5s-beta.txt', 'txt', 10, '0000000000000000000000000000a512', '2026-09-01 10:02:00+00', '2026-09-01 10:02:00+00'),
  ('${K3}', 10005, '${KB_S2}', 'document', 'w5sdoc gamma 手册', 'manual', 'completed', 'none', 'enabled', 'w5s-gamma.txt', 'txt', 10, '0000000000000000000000000000a513', '2026-09-01 10:03:00+00', '2026-09-01 10:03:00+00');
SQL

TOKEN_B="$(login 'w5s-b@weknora.test' "${PORT}")"
[ -n "${TOKEN_B}" ] || { echo "FATAL: B 登录失败"; exit 1; }
JB="Authorization: Bearer ${TOKEN_B}"

echo "==> 1) KB list agent_id 分支（8 场景）"
req w5s-kblist-sel.json          GET "/knowledge-bases?agent_id=${AG_SEL}" -H "${JB}"
req w5s-kblist-all.json          GET "/knowledge-bases?agent_id=${AG_ALL}" -H "${JB}"
req w5s-kblist-none.json         GET "/knowledge-bases?agent_id=${AG_NONE}" -H "${JB}"
req w5s-kblist-missing.json      GET "/knowledge-bases?agent_id=${MISSING}" -H "${JB}"
req w5s-kblist-badsource.json    GET "/knowledge-bases?agent_id=${AG_SEL}&agent_source_tenant_id=abc" -H "${JB}"
req w5s-kblist-sourceself.json   GET "/knowledge-bases?agent_id=${AG_SEL}&agent_source_tenant_id=10003" -H "${JB}"
req w5s-kblist-source-ok.json    GET "/knowledge-bases?agent_id=${AG_SEL}&agent_source_tenant_id=10005" -H "${JB}"
req w5s-kblist-source-wrong.json GET "/knowledge-bases?agent_id=${AG_SEL}&agent_source_tenant_id=10004" -H "${JB}"

echo "==> 2) knowledge batch agent_id 分支（7 场景）"
req w5s-batch-agent-ok.json         GET "/knowledge/batch?ids=${K1}&ids=${K2}&ids=${K3}&agent_id=${AG_SEL}" -H "${JB}"
req w5s-batch-agent-none.json       GET "/knowledge/batch?ids=${K1}&agent_id=${AG_NONE}" -H "${JB}"
req w5s-batch-agent-missing.json    GET "/knowledge/batch?ids=${K1}&agent_id=${MISSING}" -H "${JB}"
req w5s-batch-agent-all.json        GET "/knowledge/batch?ids=${K1}&ids=${K2}&ids=${K3}&agent_id=${AG_ALL}" -H "${JB}"
req w5s-batch-agent-kb-ok.json      GET "/knowledge/batch?ids=${K1}&kb_id=${KB_S1}&agent_id=${AG_SEL}" -H "${JB}"
req w5s-batch-agent-kb-denied.json  GET "/knowledge/batch?ids=${K3}&kb_id=${KB_S2}&agent_id=${AG_SEL}" -H "${JB}"
req w5s-batch-agent-kb-own.json     GET "/knowledge/batch?ids=${K1}&kb_id=${KB_B}&agent_id=${AG_SEL}" -H "${JB}"
req w5s-batch-agent-kb-missing.json GET "/knowledge/batch?ids=${K1}&kb_id=${KB_NO}&agent_id=${AG_SEL}" -H "${JB}"

echo "==> 3) knowledge search agent_id 分支（6 场景）"
req w5s-search-agent-sel.json     GET "/knowledge/search?keyword=w5sdoc&agent_id=${AG_SEL}" -H "${JB}"
req w5s-search-agent-all.json     GET "/knowledge/search?keyword=w5sdoc&agent_id=${AG_ALL}" -H "${JB}"
req w5s-search-agent-none.json    GET "/knowledge/search?keyword=w5sdoc&agent_id=${AG_NONE}" -H "${JB}"
req w5s-search-agent-missing.json GET "/knowledge/search?keyword=w5sdoc&agent_id=${MISSING}" -H "${JB}"
req w5s-search-agent-source.json  GET "/knowledge/search?keyword=w5sdoc&agent_id=${AG_SEL}&agent_source_tenant_id=10005" -H "${JB}"
req w5s-search-agent-nohit.json   GET "/knowledge/search?keyword=zzzznope&agent_id=${AG_SEL}" -H "${JB}"

echo "==> 清理"
${PSQL} >/dev/null <<SQL
DELETE FROM agent_shares WHERE source_tenant_id IN (10003,10005) OR shared_by_user_id IN ('${UB}','${UD}');
DELETE FROM organization_tenant_members WHERE tenant_id IN (10003,10005) AND organization_id='${ORG}';
DELETE FROM organizations WHERE id='${ORG}';
DELETE FROM knowledges WHERE id IN ('${K1}','${K2}','${K3}');
DELETE FROM knowledge_bases WHERE id IN ('${KB_S1}','${KB_S2}','${KB_S3}','${KB_B}');
DELETE FROM custom_agents WHERE id IN ('${AG_SEL}','${AG_ALL}','${AG_NONE}');
DELETE FROM tenant_members WHERE user_id IN ('${UB}','${UD}');
DELETE FROM users WHERE id IN ('${UB}','${UD}');
DELETE FROM tenants WHERE id IN (10003,10005);
SQL
echo "==> 完成（21 条 w5s-*）"
