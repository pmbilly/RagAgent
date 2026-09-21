#!/usr/bin/env bash
# 录 W5α3 跨租户消息文件授予 golden：w5f-*（FileAccessResolver.authorizeMessageFile
# 的两条授予路径，Go internal/application/access/files.go L154-230 +
# message_files.go 的证据链收集）。
#
#   GET /api/v1/sessions/:id/messages/:mid/files?file_path=resource://<handle>
#
# 拓扑（id 全部 w5f 专属，租户 10003/10005 与 w5s 共用一个 dev PG）：
#   - 租户 10005（源，w5f-d=owner）持有资源/KB/agent；租户 10003（w5f-b=owner）
#     是 ORG_F 的 viewer 成员，以 caller 身份走跨租户授予。
#   - shared-agent 授予：agent_shares + agent.config.knowledge_bases ⊇ 绑定 KB
#     （+ 消息 artifact 绑定的独立放行）。
#   - org-shared KB 证据链：knowledge_references / agent_steps 里的持久化证据 +
#     kb_shares viewer + 存活 resource_bindings。
#
# 种子全部 SQL 直插（固定 uuid/时间戳），文件字节已知 → 契约测试与 ab-w5f.sh
# 逐字节比对，无需掩码。
#
# 用法：
#   scripts/record-w5f-golden.sh
#   W5F_TARGET_PORT=18082 W5F_OUT_DIR=/tmp/x scripts/record-w5f-golden.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${W5F_OUT_DIR:-${SCRIPT_DIR}/server/src/test/resources/contracts}"
PORT="${W5F_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"
mkdir -p "${OUT}"

ORG="77777777-1111-4444-8888-0000000000f5"
UB="11111111-2222-3333-4444-55555555f521"
UD="11111111-2222-3333-4444-55555555f525"
KB_A="33333333-3333-3333-3333-33333333f501"      # 10005，agent scope 内，不 org 共享
KB_E="33333333-3333-3333-3333-33333333f502"      # 10005，kb_shares → ORG viewer
KB_F="33333333-3333-3333-3333-33333333f503"      # 10005，不共享
KB_OWN="33333333-3333-3333-3333-33333333f504"    # 10003，caller 自有（API-Key 白名单用）
KNOW_A="55555555-5555-5555-5555-55555555f511"
KNOW_E="55555555-5555-5555-5555-55555555f512"
KNOW_F="55555555-5555-5555-5555-55555555f513"
AG_A="44444444-4444-4444-4444-44444444f501"      # selected [KB_A]，已共享
AG_B="44444444-4444-4444-4444-44444444f502"      # none（scope 空），已共享
AG_C="44444444-4444-4444-4444-44444444f503"      # selected [KB_A]，未共享
RES_A="5f00000a-0000-0000-0000-0000000000a1"; H_A="w5fresourcehandle000a1"
RES_B="5f00000a-0000-0000-0000-0000000000a2"; H_B="w5fresourcehandle000b1"
RES_C="5f00000a-0000-0000-0000-0000000000a3"; H_C="w5fresourcehandle000c1"
RES_D="5f00000a-0000-0000-0000-0000000000a4"; H_D="w5fresourcehandle000d1"
RES_E="5f00000a-0000-0000-0000-0000000000a5"; H_E="w5fresourcehandle000e1"
SES="5f000004-0000-0000-0000-0000000000f4"
M_GRANT="5f000002-0000-0000-0000-0000000000f1"     # agent_tenant=10005, AG_A → 授予 200
M_SCOPE="5f000002-0000-0000-0000-0000000000f2"     # agent_tenant=10005, AG_B → scope 外 403
M_NOSHARE="5f000002-0000-0000-0000-0000000000f3"   # agent_tenant=10005, AG_C → 未共享 403
M_ARTIFACT="5f000002-0000-0000-0000-0000000000f4"  # agent_tenant=10005, AG_B + artifact 绑定 → 200
M_EVID="5f000002-0000-0000-0000-0000000000f5"      # agent_tenant=0 + knowledge_references 证据 → 200
M_STEPS="5f000002-0000-0000-0000-0000000000f6"     # agent_tenant=0 + agent_steps 证据 → 200
M_NOBIND="5f000002-0000-0000-0000-0000000000f7"    # 证据 KB 共享但资源无绑定 → 403
M_NOKBSHARE="5f000002-0000-0000-0000-0000000000f8" # 证据 KB 未共享 → 403
M_MISMATCH="5f000002-0000-0000-0000-0000000000f9"  # agent_tenant=10003 ≠ 资源属主 → 证据链失败即 403
M_USERX="5f000002-0000-0000-0000-0000000000fa"     # role=user 跨租户 → 403

SEED_DIR="${LOCAL_STORAGE_BASE_DIR:-/tmp/weknora-java-files}/10005/exports"

echo "==> 1) 种子文件（已知字节，落 10005/exports/）"
mkdir -p "${SEED_DIR}"
printf 'w5f shared-agent granted file\n' > "${SEED_DIR}/w5f-a.png"
printf 'w5f org kb evidence file\n'    > "${SEED_DIR}/w5f-b.png"
printf 'w5f kb not shared file\n'      > "${SEED_DIR}/w5f-c.png"
printf 'w5f message artifact file\n'   > "${SEED_DIR}/w5f-d.png"
printf 'w5f unbound evidence file\n'   > "${SEED_DIR}/w5f-e.png"
SIZE_A=$(stat -f%z "${SEED_DIR}/w5f-a.png")
SIZE_B=$(stat -f%z "${SEED_DIR}/w5f-b.png")
SIZE_C=$(stat -f%z "${SEED_DIR}/w5f-c.png")
SIZE_D=$(stat -f%z "${SEED_DIR}/w5f-d.png")
SIZE_E=$(stat -f%z "${SEED_DIR}/w5f-e.png")

echo "==> 2) 幂等清理（只碰 w5f 专属 id）"
${PSQL} >/dev/null 2>&1 <<SQL
DELETE FROM resource_bindings WHERE id IN ('5f00000c-0000-0000-0000-0000000000c1','5f00000c-0000-0000-0000-0000000000c2','5f00000c-0000-0000-0000-0000000000c3','5f00000c-0000-0000-0000-0000000000c4');
DELETE FROM messages WHERE id IN ('${M_GRANT}','${M_SCOPE}','${M_NOSHARE}','${M_ARTIFACT}','${M_EVID}','${M_STEPS}','${M_NOBIND}','${M_NOKBSHARE}','${M_MISMATCH}','${M_USERX}');
DELETE FROM sessions WHERE id = '${SES}';
DELETE FROM agent_shares WHERE organization_id = '${ORG}';
DELETE FROM kb_shares WHERE organization_id = '${ORG}';
DELETE FROM custom_agents WHERE id IN ('${AG_A}','${AG_B}','${AG_C}');
DELETE FROM knowledges WHERE id IN ('${KNOW_A}','${KNOW_E}','${KNOW_F}');
DELETE FROM knowledge_bases WHERE id IN ('${KB_A}','${KB_E}','${KB_F}','${KB_OWN}');
DELETE FROM resources WHERE id IN ('${RES_A}','${RES_B}','${RES_C}','${RES_D}','${RES_E}');
DELETE FROM organization_tenant_members WHERE organization_id = '${ORG}';
DELETE FROM organizations WHERE id = '${ORG}';
DELETE FROM tenant_members WHERE user_id IN ('${UB}','${UD}');
DELETE FROM users WHERE id IN ('${UB}','${UD}');
DELETE FROM tenant_api_keys WHERE name IN ('w5f-full','w5f-kbrestricted');
SQL

echo "==> 3) 种子（租户 ensure + 用户/组织/KB/agent/share/资源/绑定/会话/消息）"
HASH_A=$(python3 -c "import hashlib; print(hashlib.sha256(b'local://10005/exports/w5f-a.png').hexdigest())")
HASH_B=$(python3 -c "import hashlib; print(hashlib.sha256(b'local://10005/exports/w5f-b.png').hexdigest())")
HASH_C=$(python3 -c "import hashlib; print(hashlib.sha256(b'local://10005/exports/w5f-c.png').hexdigest())")
HASH_D=$(python3 -c "import hashlib; print(hashlib.sha256(b'local://10005/exports/w5f-d.png').hexdigest())")
HASH_E=$(python3 -c "import hashlib; print(hashlib.sha256(b'local://10005/exports/w5f-e.png').hexdigest())")

${PSQL} >/dev/null <<SQL
INSERT INTO tenants (id, name, description, business, status) VALUES
  (10003, 'w5s-caller-tenant', '', '', 'active'),
  (10005, 'w5s-source-tenant', '', '', 'active')
ON CONFLICT (id) DO NOTHING;
INSERT INTO users (id, username, email, password_hash, tenant_id, is_active) VALUES
  ('${UB}', 'w5fb', 'w5f-b@weknora.test', '\$2a\$10\$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK', 10003, true),
  ('${UD}', 'w5fd', 'w5f-d@weknora.test', '\$2a\$10\$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK', 10005, true);
INSERT INTO tenant_members (user_id, tenant_id, role, status) VALUES
  ('${UB}', 10003, 'owner', 'active'),
  ('${UD}', 10005, 'owner', 'active');
INSERT INTO organizations (id, name, description, owner_id, owner_tenant_id, invite_code,
  invite_code_validity_days, avatar, require_approval, searchable, member_limit, created_at, updated_at)
VALUES ('${ORG}', 'w5f-org', 'w5f share org', '${UD}', 10005, 'w5ffixedcode0001',
  7, '', false, false, 200, '2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00');
INSERT INTO organization_tenant_members (id, organization_id, tenant_id, role, representative_user_id, joined_at, created_at, updated_at) VALUES
  ('66666666-6666-4444-8888-0000000000f5', '${ORG}', 10005, 'admin', '${UD}', '2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00'),
  ('66666666-6666-4444-8888-0000000000f6', '${ORG}', 10003, 'viewer', '${UB}', '2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00', '2026-09-01 09:00:00+00');
INSERT INTO knowledge_bases (id, name, tenant_id, type, description, creator_id, chunking_config, embedding_model_id, summary_model_id, created_at, updated_at) VALUES
  ('${KB_A}', 'w5f-kb-agent-scope', 10005, 'document', 'agent scoped kb', '${UD}', '{}', '', '', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
  ('${KB_E}', 'w5f-kb-org-shared', 10005, 'document', 'org shared kb', '${UD}', '{}', '', '', '2026-09-01 08:01:00+00', '2026-09-01 08:01:00+00'),
  ('${KB_F}', 'w5f-kb-private', 10005, 'document', 'private kb', '${UD}', '{}', '', '', '2026-09-01 08:02:00+00', '2026-09-01 08:02:00+00'),
  ('${KB_OWN}', 'w5f-kb-caller-own', 10003, 'document', 'caller own kb', '${UB}', '{}', '', '', '2026-09-01 08:03:00+00', '2026-09-01 08:03:00+00');
INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, source, parse_status, summary_status, enable_status, file_name, file_type, file_size, file_hash, created_at, updated_at) VALUES
  ('${KNOW_A}', 10005, '${KB_A}', 'document', 'w5f doc alpha', 'file', 'completed', 'none', 'enabled', 'w5f-a.png', 'png', ${SIZE_A}, '000000000000000000000000000000f511', '2026-09-01 10:01:00+00', '2026-09-01 10:01:00+00'),
  ('${KNOW_E}', 10005, '${KB_E}', 'document', 'w5f doc evidence', 'file', 'completed', 'none', 'enabled', 'w5f-b.png', 'png', ${SIZE_B}, '000000000000000000000000000000f512', '2026-09-01 10:02:00+00', '2026-09-01 10:02:00+00'),
  ('${KNOW_F}', 10005, '${KB_F}', 'document', 'w5f doc private', 'file', 'completed', 'none', 'enabled', 'w5f-c.png', 'png', ${SIZE_C}, '000000000000000000000000000000f513', '2026-09-01 10:03:00+00', '2026-09-01 10:03:00+00');
INSERT INTO custom_agents (id, name, description, avatar, is_builtin, tenant_id, created_by, config, created_at, updated_at) VALUES
  ('${AG_A}', 'w5f-agent-grant', 'scope KB_A shared', '', false, 10005, '${UD}',
   '{"agent_mode":"quick-answer","model_id":"w5f-model","kb_selection_mode":"selected","knowledge_bases":["${KB_A}"],"web_search_enabled":false}',
   '2026-09-01 08:10:00+00', '2026-09-01 08:10:00+00'),
  ('${AG_B}', 'w5f-agent-empty', 'empty scope shared', '', false, 10005, '${UD}',
   '{"agent_mode":"quick-answer","model_id":"w5f-model","kb_selection_mode":"none","web_search_enabled":false}',
   '2026-09-01 08:11:00+00', '2026-09-01 08:11:00+00'),
  ('${AG_C}', 'w5f-agent-private', 'scope KB_A not shared', '', false, 10005, '${UD}',
   '{"agent_mode":"quick-answer","model_id":"w5f-model","kb_selection_mode":"selected","knowledge_bases":["${KB_A}"],"web_search_enabled":false}',
   '2026-09-01 08:12:00+00', '2026-09-01 08:12:00+00');
INSERT INTO agent_shares (id, agent_id, organization_id, shared_by_user_id, source_tenant_id, permission, created_at, updated_at) VALUES
  ('88888888-8888-4444-8888-0000000000f1', '${AG_A}', '${ORG}', '${UD}', 10005, 'viewer', '2026-09-01 09:10:00+00', '2026-09-01 09:10:00+00'),
  ('88888888-8888-4444-8888-0000000000f2', '${AG_B}', '${ORG}', '${UD}', 10005, 'viewer', '2026-09-01 09:11:00+00', '2026-09-01 09:11:00+00');
INSERT INTO kb_shares (id, knowledge_base_id, organization_id, shared_by_user_id, source_tenant_id, permission, created_at, updated_at) VALUES
  ('88888888-8888-4444-8888-0000000000f3', '${KB_E}', '${ORG}', '${UD}', 10005, 'viewer', '2026-09-01 09:12:00+00', '2026-09-01 09:12:00+00');
INSERT INTO resources (id, handle, tenant_id, storage_backend_id, provider, physical_path,
                       location_hash, kind, mime_type, original_name, size, content_hash, lifecycle, state) VALUES
  ('${RES_A}', '${H_A}', 10005, NULL, 'local', 'local://10005/exports/w5f-a.png', '${HASH_A}', 'file', 'image/png', 'w5f-a.png', ${SIZE_A}, '', 'persistent', 'active'),
  ('${RES_B}', '${H_B}', 10005, NULL, 'local', 'local://10005/exports/w5f-b.png', '${HASH_B}', 'file', 'image/png', 'w5f-b.png', ${SIZE_B}, '', 'persistent', 'active'),
  ('${RES_C}', '${H_C}', 10005, NULL, 'local', 'local://10005/exports/w5f-c.png', '${HASH_C}', 'file', 'image/png', 'w5f-c.png', ${SIZE_C}, '', 'persistent', 'active'),
  ('${RES_D}', '${H_D}', 10005, NULL, 'local', 'local://10005/exports/w5f-d.png', '${HASH_D}', 'file', 'image/png', 'w5f-d.png', ${SIZE_D}, '', 'persistent', 'active'),
  ('${RES_E}', '${H_E}', 10005, NULL, 'local', 'local://10005/exports/w5f-e.png', '${HASH_E}', 'file', 'image/png', 'w5f-e.png', ${SIZE_E}, '', 'persistent', 'active');
INSERT INTO resource_bindings (id, resource_id, tenant_id, owner_type, owner_id, relation) VALUES
  ('5f00000c-0000-0000-0000-0000000000c1', '${RES_A}', 10005, 'knowledge', '${KNOW_A}', 'source_file'),
  ('5f00000c-0000-0000-0000-0000000000c2', '${RES_B}', 10005, 'knowledge', '${KNOW_E}', 'source_file'),
  ('5f00000c-0000-0000-0000-0000000000c3', '${RES_C}', 10005, 'knowledge', '${KNOW_F}', 'source_file'),
  ('5f00000c-0000-0000-0000-0000000000c4', '${RES_D}', 10005, 'message', '${M_ARTIFACT}', 'artifact');
INSERT INTO sessions (id, tenant_id, user_id, title)
VALUES ('${SES}', 10003, '${UB}', 'w5f cross-tenant files session');
INSERT INTO messages (id, request_id, session_id, role, content, is_completed, agent_id, agent_tenant_id) VALUES
  ('${M_GRANT}',    '5f000003-0000-0000-0000-0000000000f1', '${SES}', 'assistant', '授权图 resource://${H_A}', true, '${AG_A}', 10005),
  ('${M_SCOPE}',    '5f000003-0000-0000-0000-0000000000f2', '${SES}', 'assistant', '越权图 resource://${H_A}', true, '${AG_B}', 10005),
  ('${M_NOSHARE}',  '5f000003-0000-0000-0000-0000000000f3', '${SES}', 'assistant', '私享图 resource://${H_A}', true, '${AG_C}', 10005),
  ('${M_ARTIFACT}', '5f000003-0000-0000-0000-0000000000f4', '${SES}', 'assistant', '产物图 resource://${H_D}', true, '${AG_B}', 10005),
  ('${M_NOBIND}',   '5f000003-0000-0000-0000-0000000000f7', '${SES}', 'assistant', '无绑定图 resource://${H_E}', true, '', 0),
  ('${M_NOKBSHARE}','5f000003-0000-0000-0000-0000000000f8', '${SES}', 'assistant', '私库图 resource://${H_C}', true, '', 0),
  ('${M_MISMATCH}', '5f000003-0000-0000-0000-0000000000f9', '${SES}', 'assistant', '错配图 resource://${H_A}', true, '', 10003),
  ('${M_USERX}',    '5f000003-0000-0000-0000-0000000000fa', '${SES}', 'user',      '用户图 resource://${H_A}', true, '', 10005);
INSERT INTO messages (id, request_id, session_id, role, content, is_completed, agent_id, agent_tenant_id, knowledge_references) VALUES
  ('${M_EVID}', '5f000003-0000-0000-0000-0000000000f5', '${SES}', 'assistant',
   '证据图 resource://${H_B}', true, '', 0,
   '[{"content":"chunk 文本 图片 resource://${H_B} 在此","knowledge_id":"${KNOW_E}","knowledge_base_id":"${KB_E}"}]');
INSERT INTO messages (id, request_id, session_id, role, content, is_completed, agent_id, agent_tenant_id, agent_steps) VALUES
  ('${M_STEPS}', '5f000003-0000-0000-0000-0000000000f6', '${SES}', 'assistant',
   '步骤产出的图见上', true, '', 0,
   '[{"tool_calls":[{"id":"tc1","name":"search","result":{"output":"检索完成","data":{"knowledge_base_id":"${KB_E}","snippet":"图片 resource://${H_B} 在此"}}}]}]');
INSERT INTO messages (id, request_id, session_id, role, content, is_completed, agent_id, agent_tenant_id, knowledge_references) VALUES
  ('${M_NOBIND}', '5f000003-0000-0000-0000-0000000000f7', '${SES}', 'assistant',
   '无绑定图 resource://${H_E}', true, '', 0,
   '[{"content":"chunk 文本 图片 resource://${H_E} 在此","knowledge_id":"${KNOW_E}","knowledge_base_id":"${KB_E}"}]')
ON CONFLICT (id) DO UPDATE SET knowledge_references = EXCLUDED.knowledge_references;
INSERT INTO messages (id, request_id, session_id, role, content, is_completed, agent_id, agent_tenant_id, knowledge_references) VALUES
  ('${M_NOKBSHARE}', '5f000003-0000-0000-0000-0000000000f8', '${SES}', 'assistant',
   '私库图 resource://${H_C}', true, '', 0,
   '[{"content":"chunk 文本 图片 resource://${H_C} 在此","knowledge_id":"${KNOW_F}","knowledge_base_id":"${KB_F}"}]')
ON CONFLICT (id) DO UPDATE SET knowledge_references = EXCLUDED.knowledge_references;
SQL

echo "==> 4) 登录 + API-Key 种子"
TOKEN="$(login 'w5f-b@weknora.test' "${PORT}")"
[ -n "${TOKEN}" ] || { echo "FATAL: w5f-b 登录失败"; exit 1; }
AUTH="Authorization: Bearer ${TOKEN}"

KEY_JSON="$(mktemp)"
curl -s -o "${KEY_JSON}" -X POST "${API}/tenants/10003/api-keys" \
  -H "${AUTH}" -H 'Content-Type: application/json' -d '{"name":"w5f-full","full_access":true}' >/dev/null
FULLKEY="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["token"])' "${KEY_JSON}" 2>/dev/null || true)"
curl -s -o "${KEY_JSON}" -X POST "${API}/tenants/10003/api-keys" \
  -H "${AUTH}" -H 'Content-Type: application/json' \
  -d "{\"name\":\"w5f-kbrestricted\",\"capabilities\":[\"retrieve\"],\"knowledge_base_ids\":[\"${KB_OWN}\"]}" >/dev/null
KBKEY="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["token"])' "${KEY_JSON}" 2>/dev/null || true)"
rm -f "${KEY_JSON}"

# key-owned 会话：API-Key 主体的会话可见性按 owner = "api_tenant_key:<tenant>:<keyID>"
# 精确匹配（Go loadSessionForRead + runtimeMayBypassAdminConsoleRead）。web 用户的
# 会话对 API-Key 主体恒 404（owner 不匹配），所以白名单段必须用 key 自有的会话。
SES_KF="5f000004-0000-0000-0000-0000000000f5"
SES_KR="5f000004-0000-0000-0000-0000000000f6"
M_GRANT_KF="5f000002-0000-0000-0000-0000000000fb"
M_GRANT_KR="5f000002-0000-0000-0000-0000000000fc"
FULLKEY_ID="$(${PSQL} -c "SELECT id FROM tenant_api_keys WHERE name = 'w5f-full' AND tenant_id = 10003" | tr -d '[:space:]')"
KBKEY_ID="$(${PSQL} -c "SELECT id FROM tenant_api_keys WHERE name = 'w5f-kbrestricted' AND tenant_id = 10003" | tr -d '[:space:]')"
${PSQL} >/dev/null <<SQL
DELETE FROM messages WHERE id IN ('${M_GRANT_KF}','${M_GRANT_KR}');
DELETE FROM sessions WHERE id IN ('${SES_KF}','${SES_KR}');
INSERT INTO sessions (id, tenant_id, user_id, title) VALUES
  ('${SES_KF}', 10003, 'api_tenant_key:10003:${FULLKEY_ID}', 'w5f full-key session'),
  ('${SES_KR}', 10003, 'api_tenant_key:10003:${KBKEY_ID}', 'w5f restricted-key session');
INSERT INTO messages (id, request_id, session_id, role, content, is_completed, agent_id, agent_tenant_id) VALUES
  ('${M_GRANT_KF}', '5f000003-0000-0000-0000-0000000000fb', '${SES_KF}', 'assistant', '授权图 resource://${H_A}', true, '${AG_A}', 10005),
  ('${M_GRANT_KR}', '5f000003-0000-0000-0000-0000000000fc', '${SES_KR}', 'assistant', '授权图 resource://${H_A}', true, '${AG_A}', 10005);
SQL

req() { # req <name> <method> <path> [curl opts...]
  local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" "$@"; }
reqb() { # 二进制：body + headers 双锚
  local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}.bin" -D "${OUT}/${out}.bin.headers" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" "$@"; }

echo "==> 5) shared-agent 授予路径（files.go L200-224）"
reqb w5f-grant-ok GET "/sessions/${SES}/messages/${M_GRANT}/files?file_path=resource%3A%2F%2F${H_A}" -H "${AUTH}"
req w5f-scope-denied.json GET "/sessions/${SES}/messages/${M_SCOPE}/files?file_path=resource%3A%2F%2F${H_A}" -H "${AUTH}"
req w5f-agent-not-shared.json GET "/sessions/${SES}/messages/${M_NOSHARE}/files?file_path=resource%3A%2F%2F${H_A}" -H "${AUTH}"
reqb w5f-artifact-ok GET "/sessions/${SES}/messages/${M_ARTIFACT}/files?file_path=resource%3A%2F%2F${H_D}" -H "${AUTH}"

echo "==> 6) org-shared KB 证据链（message_files.go）"
reqb w5f-evidence-ok GET "/sessions/${SES}/messages/${M_EVID}/files?file_path=resource%3A%2F%2F${H_B}" -H "${AUTH}"
reqb w5f-evidence-steps-ok GET "/sessions/${SES}/messages/${M_STEPS}/files?file_path=resource%3A%2F%2F${H_B}" -H "${AUTH}"
req w5f-evidence-nobind.json GET "/sessions/${SES}/messages/${M_NOBIND}/files?file_path=resource%3A%2F%2F${H_E}" -H "${AUTH}"
req w5f-evidence-kb-not-shared.json GET "/sessions/${SES}/messages/${M_NOKBSHARE}/files?file_path=resource%3A%2F%2F${H_C}" -H "${AUTH}"
req w5f-agent-tenant-mismatch.json GET "/sessions/${SES}/messages/${M_MISMATCH}/files?file_path=resource%3A%2F%2F${H_A}" -H "${AUTH}"
req w5f-user-crosstenant.json GET "/sessions/${SES}/messages/${M_USERX}/files?file_path=resource%3A%2F%2F${H_A}" -H "${AUTH}"

echo "==> 7) API-Key 段（web 会话 404 锚 + key-owned 会话的白名单判定）"
if [ -n "${FULLKEY}" ]; then
  # API-Key 主体读 web 用户的会话 → owner 不匹配 → 404（Go 真实契约锚）
  req w5f-key-websession GET "/sessions/${SES}/messages/${M_GRANT}/files?file_path=resource%3A%2F%2F${H_A}" -H "X-API-Key: ${FULLKEY}"
  # full-access key 读自己的会话 → 授予循环 apiKeyAllowsKb 恒放行 → 200
  reqb w5f-key-full GET "/sessions/${SES_KF}/messages/${M_GRANT_KF}/files?file_path=resource%3A%2F%2F${H_A}" -H "X-API-Key: ${FULLKEY}"
fi
if [ -n "${KBKEY}" ]; then
  # KB 受限 key 白名单不含 KB_A → apiKeyAllowsKb=false → 403
  req w5f-key-kb-outofscope.json GET "/sessions/${SES_KR}/messages/${M_GRANT_KR}/files?file_path=resource%3A%2F%2F${H_A}" -H "X-API-Key: ${KBKEY}"
fi

echo "==> 清理：A/B 临时 API key"
${PSQL} >/dev/null <<'SQL'
DELETE FROM tenant_api_keys WHERE name IN ('w5f-full','w5f-kbrestricted');
SQL

echo "==> 完成：$(ls "${OUT}" | grep -c '^w5f-') 个 w5f-* golden → ${OUT}"
