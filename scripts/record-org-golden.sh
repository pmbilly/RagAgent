#!/usr/bin/env bash
# 录波 3 协作面批次 golden：org-*（organizations 全部路由）+ shr-*（KB/agent shares + shared-* 读面）。
# 种子全部固定 uuid/时间戳；create 类响应的 uuid/invite_code/时间戳走掩码（契约测试与 ab-org.sh 同一掩码面）。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
OUT="${ORG_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${ORG_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"
mkdir -p "${OUT}"
req() { local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" "$@"; }

ORG1_SEED="77777777-1111-4444-8888-000000000001"
ORG2="77777777-1111-4444-8888-000000000002"
CODE2="fixedcode00000002"
KB_A="33333333-3333-3333-3333-333333333301"
KB_C="33333333-3333-3333-3333-333333333311"
AG_OK="44444444-4444-4444-4444-444444444401"
AG_UNSET="44444444-4444-4444-4444-444444444402"
AG_C="44444444-4444-4444-4444-444444444411"
UA="11111111-2222-3333-4444-555555555501"
UB="11111111-2222-3333-4444-555555555021"
UC="11111111-2222-3333-4444-555555555022"
MISSING="99999999-9999-9999-9999-999999999999"

echo "==> 幂等清理 + 种子（用户 B/C、租户 10003/10004、KB、agents）"
${PSQL} >/dev/null 2>&1 <<SQL
DELETE FROM kb_shares WHERE shared_by_user_id IN ('${UA}','${UB}','${UC}') OR source_tenant_id IN (10002,10003,10004);
DELETE FROM agent_shares WHERE shared_by_user_id IN ('${UA}','${UB}','${UC}') OR source_tenant_id IN (10002,10003,10004);
DELETE FROM organization_join_requests WHERE user_id IN ('${UA}','${UB}','${UC}') OR tenant_id IN (10002,10003,10004);
DELETE FROM organization_tenant_members WHERE tenant_id IN (10002,10003,10004);
DELETE FROM tenant_disabled_shared_agents WHERE tenant_id IN (10002,10003,10004);
DELETE FROM organizations WHERE owner_id IN ('${UA}','${UB}','${UC}') OR owner_tenant_id IN (10002,10003,10004);
DELETE FROM custom_agents WHERE id IN ('${AG_OK}','${AG_UNSET}','${AG_C}');
DELETE FROM knowledge_bases WHERE id IN ('${KB_A}','${KB_C}');
DELETE FROM tenant_members WHERE user_id IN ('${UB}','${UC}');
DELETE FROM users WHERE id IN ('${UB}','${UC}');
DELETE FROM tenants WHERE id IN (10003,10004);
SQL

${PSQL} >/dev/null <<SQL
INSERT INTO tenants (id, name, description, business, status) VALUES
  (10003, 'org-beta-tenant', '', '', 'active'),
  (10004, 'org-gamma-tenant', '', '', 'active');
INSERT INTO users (id, username, email, password_hash, tenant_id, is_active)
VALUES
  ('${UB}', 'orgb', 'org-b@weknora.test', '\$2a\$10\$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK', 10003, true),
  ('${UC}', 'orgc', 'org-c@weknora.test', '\$2a\$10\$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK', 10004, true);
INSERT INTO tenant_members (user_id, tenant_id, role, status) VALUES
  ('${UB}', 10003, 'owner', 'active'),
  ('${UC}', 10004, 'owner', 'active');
INSERT INTO knowledge_bases (id, name, tenant_id, type, description, creator_id, chunking_config, embedding_model_id, summary_model_id, created_at, updated_at)
VALUES
  ('${KB_A}', 'shr-kb-alpha', 10002, 'document', 'kb owned by tenant 10002', '${UA}', '{}', '', '', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
  ('${KB_C}', 'shr-kb-gamma', 10004, 'document', 'kb owned by tenant 10004', '${UC}', '{}', '', '', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00');
INSERT INTO custom_agents (id, name, description, avatar, is_builtin, tenant_id, created_by, config, created_at, updated_at)
VALUES
  ('${AG_OK}', 'shr-agent-ok', 'agent with model', 'robot', false, 10002, '${UA}',
   '{"agent_mode":"quick-answer","model_id":"shr-model-1","rerank_model_id":"shr-rerank-1","kb_selection_mode":"selected","knowledge_bases":["${KB_A}"],"mcp_selection_mode":"all","web_search_enabled":false}',
   '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
  ('${AG_UNSET}', 'shr-agent-unset', 'agent without model', '', false, 10002, '${UA}',
   '{"agent_mode":"quick-answer","model_id":"","kb_selection_mode":"none","web_search_enabled":false}',
   '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
  ('${AG_C}', 'shr-agent-gamma', 'agent owned by tenant 10004', '', false, 10004, '${UC}',
   '{"agent_mode":"quick-answer","model_id":"gamma-model","rerank_model_id":"gamma-rerank","kb_selection_mode":"none","web_search_enabled":false}',
   '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00');
-- ORG2（require_approval=true）只能 SQL 种子：CreateOrganizationRequest 无该字段
INSERT INTO organizations (id, name, description, owner_id, owner_tenant_id, invite_code, invite_code_expires_at,
  invite_code_validity_days, avatar, require_approval, searchable, member_limit, created_at, updated_at)
VALUES ('${ORG2}', 'shr-approval-org', 'approval required', '${UA}', 10002, '${CODE2}', NULL,
  7, '', true, false, 200, '2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00');
INSERT INTO organization_tenant_members (id, organization_id, tenant_id, role, representative_user_id, joined_at, created_at, updated_at)
VALUES ('66666666-6666-4444-8888-000000000002', '${ORG2}', 10002, 'admin', '${UA}', '2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00');
SQL

TOKEN_A="$(login "${TEST_EMAIL}" "${PORT}")"
TOKEN_B="$(login 'org-b@weknora.test' "${PORT}")"
TOKEN_C="$(login 'org-c@weknora.test' "${PORT}")"
TOKEN_V="$(login "${TEST_VIEWER_EMAIL}" "${PORT}")"
[ -n "${TOKEN_A}" ] && [ -n "${TOKEN_B}" ] && [ -n "${TOKEN_C}" ] && [ -n "${TOKEN_V}" ] || { echo "FATAL: 登录失败"; exit 1; }
JA="Authorization: Bearer ${TOKEN_A}"
JB="Authorization: Bearer ${TOKEN_B}"
JC="Authorization: Bearer ${TOKEN_C}"
JV="Authorization: Bearer ${TOKEN_V}"
CT='Content-Type: application/json'

echo "==> 1) org CRUD + 错误形态"
req org-create.json POST /organizations -H "${JA}" -H "${CT}" \
  -d '{"name":"shr-alpha-org","description":"alpha org for ab","invite_code_validity_days":7,"member_limit":50}'
ORG1="$(python3 -c 'import json,sys; print(json.load(open("'"${OUT}/org-create.json"'"))["data"]["id"])' 2>/dev/null || echo '')"
CODE1="$(python3 -c 'import json,sys; print(json.load(open("'"${OUT}/org-create.json"'"))["data"]["invite_code"])' 2>/dev/null || echo '')"
echo "    org1=${ORG1} code1=${CODE1}"
req org-create-bad-validity.json POST /organizations -H "${JA}" -H "${CT}" \
  -d '{"name":"x-org","invite_code_validity_days":5}'
req org-create-neg-limit.json POST /organizations -H "${JA}" -H "${CT}" \
  -d '{"name":"x-org","member_limit":-1}'
req org-create-no-body.json POST /organizations -H "${JA}" -H "${CT}"
req org-get.json GET "/organizations/${ORG1}" -H "${JA}"
req org-get-missing.json GET "/organizations/${MISSING}" -H "${JA}"
req org-list.json GET /organizations -H "${JA}"
req org-get-nonmember-private.json GET "/organizations/${ORG1}" -H "${JB}"
req org-update-nonmember.json PUT "/organizations/${ORG1}" -H "${JB}" -H "${CT}" -d '{"searchable":true}'
req org-update.json PUT "/organizations/${ORG1}" -H "${JA}" -H "${CT}" \
  -d '{"searchable":true,"description":"alpha updated"}'
req org-delete-nonowner.json DELETE "/organizations/${ORG1}" -H "${JB}"

echo "==> 2) search / preview / join"
req org-search.json GET "/organizations/search?q=shr-alpha" -H "${JB}"
req org-search-noq.json GET "/organizations/search" -H "${JB}"
req org-preview-invalid.json GET "/organizations/preview/deadbeefdeadbeef" -H "${JB}"
req org-preview.json GET "/organizations/preview/${CODE1}" -H "${JB}"
req org-join-by-id-bad-role.json POST /organizations/join-by-id -H "${JB}" -H "${CT}" \
  -d "{\"organization_id\":\"${ORG1}\",\"role\":\"boss\"}"
req org-join-by-id.json POST /organizations/join-by-id -H "${JB}" -H "${CT}" \
  -d "{\"organization_id\":\"${ORG1}\"}"
req org-join-idempotent.json POST /organizations/join-by-id -H "${JB}" -H "${CT}" \
  -d "{\"organization_id\":\"${ORG1}\"}"
req org-join-invalid-code.json POST /organizations/join -H "${JB}" -H "${CT}" \
  -d '{"invite_code":"0000000000000000"}'
req org-join-approval-required.json POST /organizations/join -H "${JB}" -H "${CT}" \
  -d "{\"invite_code\":\"${CODE2}\"}"
req org-join-request.json POST /organizations/join-request -H "${JB}" -H "${CT}" \
  -d "{\"invite_code\":\"${CODE2}\",\"message\":\"let me in\",\"role\":\"editor\"}"
REQ_ID="$(python3 -c 'import json,sys; print(json.load(open("'"${OUT}/org-join-request.json"'"))["data"]["id"])' 2>/dev/null || echo '')"
req org-join-request-dup.json POST /organizations/join-request -H "${JB}" -H "${CT}" \
  -d "{\"invite_code\":\"${CODE2}\",\"message\":\"again\"}"
req org-join-request-bad-role.json POST /organizations/join-request -H "${JB}" -H "${CT}" \
  -d "{\"invite_code\":\"${CODE2}\",\"role\":\"boss\"}"
req org-join-request-no-approval.json POST /organizations/join-request -H "${JC}" -H "${CT}" \
  -d "{\"invite_code\":\"${CODE1}\",\"message\":\"direct\"}"
req org-join-requests-nonadmin.json GET "/organizations/${ORG2}/join-requests" -H "${JC}"
req org-join-requests.json GET "/organizations/${ORG2}/join-requests" -H "${JA}"
req org-review-nonadmin.json PUT "/organizations/${ORG2}/join-requests/${REQ_ID}/review" -H "${JC}" -H "${CT}" \
  -d '{"approved":true}'
req org-review-approve.json PUT "/organizations/${ORG2}/join-requests/${REQ_ID}/review" -H "${JA}" -H "${CT}" \
  -d '{"approved":true,"role":"editor","message":"welcome"}'
req org-review-again.json PUT "/organizations/${ORG2}/join-requests/${REQ_ID}/review" -H "${JA}" -H "${CT}" \
  -d '{"approved":true}'
req org-join-requests-after.json GET "/organizations/${ORG2}/join-requests" -H "${JA}"
req org2-members.json GET "/organizations/${ORG2}/members" -H "${JA}"

echo "==> 3) 成员管理（ORG1：B=viewer）"
req org-search-tenants.json GET "/organizations/${ORG1}/search-tenants?q=org-gamma" -H "${JA}"
req org-search-tenants-empty.json GET "/organizations/${ORG1}/search-tenants" -H "${JA}"
req org-search-tenants-nonadmin.json GET "/organizations/${ORG1}/search-tenants?q=gamma" -H "${JB}"
req org-search-users-alias.json GET "/organizations/${ORG1}/search-users?q=org-gamma" -H "${JA}"
req org-invite-nonadmin.json POST "/organizations/${ORG1}/invite" -H "${JB}" -H "${CT}" \
  -d '{"tenant_id":10004,"role":"viewer"}'
req org-invite-missing-field.json POST "/organizations/${ORG1}/invite" -H "${JA}" -H "${CT}" -d '{}'
req org-invite-bad-role.json POST "/organizations/${ORG1}/invite" -H "${JA}" -H "${CT}" \
  -d '{"tenant_id":10004,"role":"boss"}'
req org-invite-missing-tenant.json POST "/organizations/${ORG1}/invite" -H "${JA}" -H "${CT}" \
  -d '{"tenant_id":99999,"role":"viewer"}'
req org-invite.json POST "/organizations/${ORG1}/invite" -H "${JA}" -H "${CT}" \
  -d '{"tenant_id":10004,"role":"viewer"}'
req org-invite-already.json POST "/organizations/${ORG1}/invite" -H "${JA}" -H "${CT}" \
  -d '{"tenant_id":10004,"role":"viewer"}'
req org-members.json GET "/organizations/${ORG1}/members" -H "${JA}"
req org-leave.json POST "/organizations/${ORG1}/leave" -H "${JB}"
req org-leave-owner.json POST "/organizations/${ORG1}/leave" -H "${JA}"
req org-members-nonmember.json GET "/organizations/${ORG1}/members" -H "${JB}"
req org-join-by-id-rejoin.json POST /organizations/join-by-id -H "${JB}" -H "${CT}" \
  -d "{\"organization_id\":\"${ORG1}\"}"
req org-update-member-role.json PUT "/organizations/${ORG1}/members/10003" -H "${JA}" -H "${CT}" \
  -d '{"role":"editor"}'
req org-update-member-role-nonadmin.json PUT "/organizations/${ORG1}/members/10002" -H "${JB}" -H "${CT}" \
  -d '{"role":"viewer"}'
req org-update-member-role-owner.json PUT "/organizations/${ORG1}/members/10002" -H "${JA}" -H "${CT}" \
  -d '{"role":"viewer"}'
req org-update-member-role-bad-id.json PUT "/organizations/${ORG1}/members/abc" -H "${JA}" -H "${CT}" \
  -d '{"role":"viewer"}'
req org-remove-member.json DELETE "/organizations/${ORG1}/members/10004" -H "${JA}"
req org-remove-member-again.json DELETE "/organizations/${ORG1}/members/10004" -H "${JA}"
req org-remove-member-nonadmin.json DELETE "/organizations/${ORG1}/members/10002" -H "${JB}"

echo "==> 4) invite-code / role upgrade / 成员上限"
req org-invite-code.json POST "/organizations/${ORG1}/invite-code" -H "${JA}"
req org-invite-code-nonadmin.json POST "/organizations/${ORG1}/invite-code" -H "${JC}"
req org-request-upgrade.json POST "/organizations/${ORG1}/request-upgrade" -H "${JB}" -H "${CT}" \
  -d '{"requested_role":"admin","message":"promote me"}'
req org-request-upgrade-dup.json POST "/organizations/${ORG1}/request-upgrade" -H "${JB}" -H "${CT}" \
  -d '{"requested_role":"admin"}'
req org-request-upgrade-same.json POST "/organizations/${ORG1}/request-upgrade" -H "${JB}" -H "${CT}" \
  -d '{"requested_role":"viewer"}'
req org-request-upgrade-admin.json POST "/organizations/${ORG1}/request-upgrade" -H "${JA}" -H "${CT}" \
  -d '{"requested_role":"admin"}'
req org-request-upgrade-nonmember.json POST "/organizations/${ORG1}/request-upgrade" -H "${JC}" -H "${CT}" \
  -d '{"requested_role":"admin"}'
UP_ID="$(python3 -c 'import json,sys; print(json.load(open("'"${OUT}/org-request-upgrade.json"'"))["data"]["id"])' 2>/dev/null || echo '')"
req org-join-requests-upgrade.json GET "/organizations/${ORG1}/join-requests" -H "${JA}"
req org-get-pending-upgrade.json GET "/organizations/${ORG1}" -H "${JB}"
req org-upgrade-review-approve.json PUT "/organizations/${ORG1}/join-requests/${UP_ID}/review" -H "${JA}" -H "${CT}" \
  -d '{"approved":true,"role":"admin"}'
req org3-create.json POST /organizations -H "${JA}" -H "${CT}" \
  -d '{"name":"shr-limit-org","description":"limit 1","member_limit":1}'
ORG3="$(python3 -c 'import json,sys; print(json.load(open("'"${OUT}/org3-create.json"'"))["data"]["id"])' 2>/dev/null || echo '')"
CODE3="$(python3 -c 'import json,sys; print(json.load(open("'"${OUT}/org3-create.json"'"))["data"]["invite_code"])' 2>/dev/null || echo '')"
req org-join-limit.json POST /organizations/join -H "${JB}" -H "${CT}" \
  -d "{\"invite_code\":\"${CODE3}\"}"
req org-invite-limit.json POST "/organizations/${ORG3}/invite" -H "${JA}" -H "${CT}" \
  -d '{"tenant_id":10003,"role":"viewer"}'
req org2-update-searchable.json PUT "/organizations/${ORG2}" -H "${JA}" -H "${CT}" -d '{"searchable":true}'
req org-get-nonmember-searchable.json GET "/organizations/${ORG2}" -H "${JC}"

echo "==> 5) KB shares（OwnedKBOrAdmin 矩阵）"
req shr-kb-share.json POST "/knowledge-bases/${KB_A}/shares" -H "${JA}" -H "${CT}" \
  -d "{\"organization_id\":\"${ORG1}\",\"permission\":\"viewer\"}"
SHARE_ID="$(python3 -c 'import json,sys; print(json.load(open("'"${OUT}/shr-kb-share.json"'"))["data"]["id"])' 2>/dev/null || echo '')"
req shr-kb-share-update-existing.json POST "/knowledge-bases/${KB_A}/shares" -H "${JA}" -H "${CT}" \
  -d "{\"organization_id\":\"${ORG1}\",\"permission\":\"editor\"}"
req shr-kb-share-cross-tenant.json POST "/knowledge-bases/${KB_A}/shares" -H "${JB}" -H "${CT}" \
  -d "{\"organization_id\":\"${ORG1}\",\"permission\":\"viewer\"}"
req shr-kb-share-missing-org.json POST "/knowledge-bases/${KB_A}/shares" -H "${JA}" -H "${CT}" \
  -d "{\"organization_id\":\"${MISSING}\",\"permission\":\"viewer\"}"
req shr-kb-share-bad-perm.json POST "/knowledge-bases/${KB_A}/shares" -H "${JA}" -H "${CT}" \
  -d "{\"organization_id\":\"${ORG1}\",\"permission\":\"boss\"}"
req shr-kb-share-nonmember.json POST "/knowledge-bases/${KB_C}/shares" -H "${JC}" -H "${CT}" \
  -d "{\"organization_id\":\"${ORG1}\",\"permission\":\"viewer\"}"
req shr-kb-shares-list.json GET "/knowledge-bases/${KB_A}/shares" -H "${JA}"
req shr-kb-shares-list-missing-kb.json GET "/knowledge-bases/${MISSING}/shares" -H "${JA}"
req shr-kb-shares-list-notowner.json GET "/knowledge-bases/${KB_A}/shares" -H "${JB}"
req shr-kb-share-update.json PUT "/knowledge-bases/${KB_A}/shares/${SHARE_ID}" -H "${JA}" -H "${CT}" \
  -d '{"permission":"editor"}'
req shr-kb-share-update-badperm.json PUT "/knowledge-bases/${KB_A}/shares/${SHARE_ID}" -H "${JA}" -H "${CT}" \
  -d '{"permission":"boss"}'
req shr-kb-share-update-orgadmin.json PUT "/knowledge-bases/${KB_A}/shares/${SHARE_ID}" -H "${JB}" -H "${CT}" \
  -d '{"permission":"editor"}'
req shr-kb-share-remove-nonadmin.json DELETE "/knowledge-bases/${KB_A}/shares/${SHARE_ID}" -H "${JC}"
req shr-kb-share-remove-missing.json DELETE "/knowledge-bases/${KB_A}/shares/${MISSING}" -H "${JA}"
req shr-kb-share-remove.json DELETE "/knowledge-bases/${KB_A}/shares/${SHARE_ID}" -H "${JA}"
req shr-kb-share-remove-again.json DELETE "/knowledge-bases/${KB_A}/shares/${SHARE_ID}" -H "${JA}"
# 重新共享回 viewer，供组织读面使用
req shr-kb-share-again.json POST "/knowledge-bases/${KB_A}/shares" -H "${JA}" -H "${CT}" \
  -d "{\"organization_id\":\"${ORG1}\",\"permission\":\"viewer\"}"
# C 以 viewer 身份回到 ORG1（供 agent share 的 viewer 拒绝与 disable 场景）
req org-invite-c-viewer.json POST "/organizations/${ORG1}/invite" -H "${JA}" -H "${CT}" \
  -d '{"tenant_id":10004,"role":"viewer"}'

echo "==> 6) org shares 读面 + shared-knowledge-bases"
req shr-org-shares.json GET "/organizations/${ORG1}/shares" -H "${JA}"
req shr-org-shares-nonmember.json GET "/organizations/${ORG2}/shares" -H "${JC}"
req shr-shared-kbs.json GET /shared-knowledge-bases -H "${JB}"
req shr-shared-kbs-owner.json GET /shared-knowledge-bases -H "${JA}"
req shr-org-shared-kbs.json GET "/organizations/${ORG1}/shared-knowledge-bases" -H "${JA}"
req shr-org-shared-kbs-nonmember.json GET "/organizations/${ORG2}/shared-knowledge-bases" -H "${JC}"

echo "==> 7) agent shares 三条 + shared-agents"
req shr-agent-share.json POST "/agents/${AG_OK}/shares" -H "${JA}" -H "${CT}" \
  -d "{\"organization_id\":\"${ORG1}\",\"permission\":\"editor\"}"
ASHARE_ID="$(python3 -c 'import json,sys; print(json.load(open("'"${OUT}/shr-agent-share.json"'"))["data"]["id"])' 2>/dev/null || echo '')"
req shr-agent-share-exists.json POST "/agents/${AG_OK}/shares" -H "${JA}" -H "${CT}" \
  -d "{\"organization_id\":\"${ORG1}\",\"permission\":\"editor\"}"
req shr-agent-share-unset.json POST "/agents/${AG_UNSET}/shares" -H "${JA}" -H "${CT}" \
  -d "{\"organization_id\":\"${ORG1}\",\"permission\":\"viewer\"}"
req shr-agent-share-missing-agent.json POST "/agents/${MISSING}/shares" -H "${JA}" -H "${CT}" \
  -d "{\"organization_id\":\"${ORG1}\",\"permission\":\"viewer\"}"
req shr-agent-share-viewerorg.json POST "/agents/${AG_C}/shares" -H "${JC}" -H "${CT}" \
  -d "{\"organization_id\":\"${ORG1}\",\"permission\":\"viewer\"}"
req shr-agent-shares-list.json GET "/agents/${AG_OK}/shares" -H "${JA}"
req shr-agent-shares-list-notowner.json GET "/agents/${AG_OK}/shares" -H "${JB}"
req shr-agent-share-remove-nonadmin.json DELETE "/agents/${AG_OK}/shares/${ASHARE_ID}" -H "${JC}"
req shr-agent-share-remove.json DELETE "/agents/${AG_OK}/shares/${ASHARE_ID}" -H "${JA}"
req shr-agent-share-remove-again.json DELETE "/agents/${AG_OK}/shares/${ASHARE_ID}" -H "${JA}"
req shr-agent-share-again.json POST "/agents/${AG_OK}/shares" -H "${JA}" -H "${CT}" \
  -d "{\"organization_id\":\"${ORG1}\",\"permission\":\"viewer\"}"
req shr-org-agent-shares.json GET "/organizations/${ORG1}/agent-shares" -H "${JA}"
req shr-org-agent-shares-nonmember.json GET "/organizations/${ORG2}/agent-shares" -H "${JC}"
req shr-shared-agents.json GET /shared-agents -H "${JB}"
req shr-shared-agents-owner.json GET /shared-agents -H "${JA}"
req shr-org-shared-agents.json GET "/organizations/${ORG1}/shared-agents" -H "${JA}"
req shr-org-shared-agents-b.json GET "/organizations/${ORG1}/shared-agents" -H "${JB}"
req shr-shared-agent-disable-forbidden.json POST /shared-agents/disabled -H "${JV}" -H "${CT}" \
  -d "{\"agent_id\":\"${AG_OK}\",\"disabled\":true}"
req shr-shared-agent-disable-missing.json POST /shared-agents/disabled -H "${JA}" -H "${CT}" \
  -d "{\"agent_id\":\"${MISSING}\",\"disabled\":true}"
req shr-shared-agent-disable.json POST /shared-agents/disabled -H "${JA}" -H "${CT}" \
  -d "{\"agent_id\":\"${AG_OK}\",\"disabled\":false}"
req shr-shared-agent-disable-c.json POST /shared-agents/disabled -H "${JC}" -H "${CT}" \
  -d "{\"agent_id\":\"${AG_OK}\",\"disabled\":true}"
req shr-shared-agents-c.json GET /shared-agents -H "${JC}"

echo "==> 8) 收尾 resource_counts（含共享后的计数合并路径）"
req org-list-late.json GET /organizations -H "${JA}"
req org-list-b.json GET /organizations -H "${JB}"

echo "==> 清理"
${PSQL} >/dev/null <<SQL
DELETE FROM kb_shares WHERE shared_by_user_id IN ('${UA}','${UB}','${UC}') OR source_tenant_id IN (10002,10003,10004);
DELETE FROM agent_shares WHERE shared_by_user_id IN ('${UA}','${UB}','${UC}') OR source_tenant_id IN (10002,10003,10004);
DELETE FROM organization_join_requests WHERE user_id IN ('${UA}','${UB}','${UC}') OR tenant_id IN (10002,10003,10004);
DELETE FROM organization_tenant_members WHERE tenant_id IN (10002,10003,10004);
DELETE FROM tenant_disabled_shared_agents WHERE tenant_id IN (10002,10003,10004);
DELETE FROM organizations WHERE owner_id IN ('${UA}','${UB}','${UC}') OR owner_tenant_id IN (10002,10003,10004);
DELETE FROM custom_agents WHERE id IN ('${AG_OK}','${AG_UNSET}','${AG_C}');
DELETE FROM knowledge_bases WHERE id IN ('${KB_A}','${KB_C}');
DELETE FROM tenant_members WHERE user_id IN ('${UB}','${UC}');
DELETE FROM users WHERE id IN ('${UB}','${UC}');
DELETE FROM tenants WHERE id IN (10003,10004);
SQL
echo "==> 完成"
