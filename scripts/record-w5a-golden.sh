#!/usr/bin/env bash
# 录 W5a 收尾批的 golden：w5a-*（13 条小散路由）。
#
# 覆盖端点：
#   auth 补 3   POST /auth/logout  POST /auth/refresh  POST /auth/switch-tenant
#   tenants 4   GET /tenants  GET/PUT/DELETE /tenants/:id
#   KB 标签 4   GET|POST /knowledge-bases/:id/tags  PUT|DELETE /knowledge-bases/:id/tags/:tag_id
#   IM 回调 2   GET|POST /im/callback/:channel_id（engine 级、无鉴权）
#
# 场景设计（顺序敏感，契约测试/A/B 必须严格复刻本顺序）：
#   - 身份固定：owner=java-phase1（10002 owner）、viewer=java-phase1-viewer。
#   - refresh 场景：登录取 refresh_token → 刷新成功 → 同 token 二刷（revoked 401）。
#   - switch 场景：切换到自家 10002（成功路径）+ 非成员 403 + 绑定错误族；
#     成功路径会把 last_active_tenant 偏好写成 10002（与 home 同值，无副作用）。
#   - tenants：PUT 改名后立即用 psql 记录的原值还原（不改 dev 租户的持久状态）；
#     DELETE 用自助创建的一次性租户（创建→删除→再删一次），不碰 10002。
#   - tags：API 建 KB + SQL 播一条 knowledge（固定 hex uuid）做标签引用；
#     seq_id 用 psql 按 (tenant,kb,name) 查（两侧各自查询，掩码覆盖数值）。
#   - im：API 建 agent + mattermost 渠道（webhook 模式）。回调对 enabled 渠道
#     Go 会进入平台验签（403 verification failed 等）——Java 侧适配器随波 5，
#     现落 503 channel not available → A/B 列 EXPECTED DIFF（约定 §9 W5a）；
#     disabled 渠道两侧恒 503 "channel is disabled"（MATCH）。
#
# 动态值（A/B 与契约测试同掩码）：uuid / 时间戳 / token（access/refresh）/
# 数字 id / seq_id。
#
# 用法：
#   scripts/record-w5a-golden.sh                  # 录 Go（:8080）进 contracts/
#   W5A_TARGET_PORT=8082 W5A_OUT_DIR=/tmp/x scripts/record-w5a-golden.sh  # A/B 重放
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${W5A_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${W5A_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"
mkdir -p "${OUT}"

req() { local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" "$@"; }

login_tok() {
  curl -s -X POST "${API}/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"${1}\",\"password\":\"Passw0rd!\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("token") or "")'
}
login_refresh() {
  curl -s -X POST "${API}/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"${1}\",\"password\":\"Passw0rd!\"}" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("refresh_token") or "")'
}
AUTH_OWNER=""; AUTH_VIEWER=""

# ═══ 0) 幂等清理（上一轮残留） ═══
echo "==> cleanup"
${PSQL} -c "DELETE FROM knowledge_tag_relations WHERE knowledge_id IN (SELECT id FROM knowledges WHERE id='5a5a0000000000000000000000000001');" >/dev/null 2>&1 || true
${PSQL} -c "DELETE FROM knowledges WHERE id='5a5a0000000000000000000000000001';" >/dev/null 2>&1 || true
${PSQL} -c "DELETE FROM knowledge_tags WHERE knowledge_base_id IN (SELECT id FROM knowledge_bases WHERE name LIKE 'w5a-%');" >/dev/null 2>&1 || true
${PSQL} -c "DELETE FROM knowledge_bases WHERE name LIKE 'w5a-%';" >/dev/null 2>&1 || true
${PSQL} -c "DELETE FROM tenants WHERE name LIKE 'w5a-tmp-%';" >/dev/null 2>&1 || true

OWNER_JSON=$(curl -s -X POST "${API}/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"java-phase1@weknora.test\",\"password\":\"Passw0rd!\"}")
OWNER_TOK=$(python3 -c "import json,sys; print(json.loads(sys.stdin.read()).get('token',''))" <<<"${OWNER_JSON}")
AUTH_OWNER="Authorization: Bearer ${OWNER_TOK}"
VIEWER_TOK=$(login_tok java-phase1-viewer@weknora.test)
AUTH_VIEWER="Authorization: Bearer ${VIEWER_TOK}"

# ═══ 1) auth 三兄弟 ═══
echo "==> auth"
req w5a-auth-refresh-missing-body.json POST "/auth/refresh" -H 'Content-Type: application/json'
req w5a-auth-refresh-empty-obj.json   POST "/auth/refresh" -H 'Content-Type: application/json' -d '{}'
req w5a-auth-refresh-garbage.json     POST "/auth/refresh" -H 'Content-Type: application/json' -d '{"refreshToken":"garbage.token.value"}'
REFRESH_TOKEN=$(login_refresh java-phase1@weknora.test)
req w5a-auth-refresh-ok.json          POST "/auth/refresh" -H 'Content-Type: application/json' -d "{\"refreshToken\":\"${REFRESH_TOKEN}\"}"
req w5a-auth-refresh-revoked.json     POST "/auth/refresh" -H 'Content-Type: application/json' -d "{\"refreshToken\":\"${REFRESH_TOKEN}\"}"
req w5a-auth-switch-missing-body.json POST "/auth/switch-tenant" -H "${AUTH_OWNER}" -H 'Content-Type: application/json'
req w5a-auth-switch-empty.json        POST "/auth/switch-tenant" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' -d '{}'
req w5a-auth-switch-badtype.json      POST "/auth/switch-tenant" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' -d '{"tenant_id":"abc"}'
req w5a-auth-switch-not-member.json   POST "/auth/switch-tenant" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' -d '{"tenant_id":424242}'
req w5a-auth-switch-ok.json           POST "/auth/switch-tenant" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' -d '{"tenant_id":10002}'

# ═══ 2) tenants CRUD ═══
echo "==> tenants"
req w5a-tenant-list.json        GET  "/tenants" -H "${AUTH_OWNER}"
req w5a-tenant-list-viewer.json GET  "/tenants" -H "${AUTH_VIEWER}"
req w5a-tenant-get.json         GET  "/tenants/10002" -H "${AUTH_OWNER}"
req w5a-tenant-get-viewer.json  GET  "/tenants/10002" -H "${AUTH_VIEWER}"
req w5a-tenant-get-cross.json   GET  "/tenants/424242" -H "${AUTH_OWNER}"
ORIG_NAME=$(${PSQL} -c "SELECT name FROM tenants WHERE id=10002;" | xargs)
ORIG_DESC=$(${PSQL} -c "SELECT COALESCE(description,'') FROM tenants WHERE id=10002;" | xargs)
req w5a-tenant-put.json         PUT  "/tenants/10002" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' \
  -d '{"name":"w5a 工作空间","description":"w5a put 描述"}'
${PSQL} -c "UPDATE tenants SET name='${ORIG_NAME}', description=NULLIF('${ORIG_DESC}','') WHERE id=10002;" >/dev/null
req w5a-tenant-put-blank.json   PUT  "/tenants/10002" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' -d '{"name":"   "}'
LONGNAME=$(python3 -c "print('a'*129)")
req w5a-tenant-put-longname.json PUT "/tenants/10002" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' -d "{\"name\":\"${LONGNAME}\"}"
req w5a-tenant-put-badjson.json PUT  "/tenants/10002" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' -d '{"name": 123}'
req w5a-tenant-put-nonowner.json PUT "/tenants/10002" -H "${AUTH_VIEWER}" -H 'Content-Type: application/json' -d '{"name":"nope"}'
req w5a-tenant-create.json      POST "/tenants" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' -d '{"name":"w5a-tmp-租户"}'
TMP_TENANT=$(python3 -c "import json;print(json.load(open('${OUT}/w5a-tenant-create.json'))['data']['id'])")
req w5a-tenant-delete.json      DELETE "/tenants/${TMP_TENANT}" -H "${AUTH_OWNER}"
req w5a-tenant-delete-again.json DELETE "/tenants/${TMP_TENANT}" -H "${AUTH_OWNER}"

# ═══ 3) KB 标签 ═══
echo "==> tags"
req w5a-tag-kb-create.json POST "/knowledge-bases" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' \
  -d '{"name":"w5a-tag-kb","description":"w5a 标签批","type":"document"}'
KB=$(python3 -c "import json;print(json.load(open('${OUT}/w5a-tag-kb-create.json'))['data']['id'])")
# 引用用 knowledge（固定 hex uuid；KB 删除时随级联清理）
${PSQL} -c "INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, source, parse_status, summary_status, enable_status, file_name, file_type, file_size, file_hash, file_path) VALUES ('5a5a0000000000000000000000000001', 10002, '${KB}', 'document', 'w5a 引用文档', 'manual', 'completed', 'none', 'enabled', 'w5a-ref.txt', 'txt', 10, '0000000000000000000000000000005a', '');" >/dev/null

req w5a-tag-list-empty.json GET "/knowledge-bases/${KB}/tags" -H "${AUTH_OWNER}"
req w5a-tag-create.json POST "/knowledge-bases/${KB}/tags" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' \
  -d '{"name":"w5a-标签A","color":"#ff0000","sort_order":3}'
TAG_A=$(python3 -c "import json;print(json.load(open('${OUT}/w5a-tag-create.json'))['data']['id'])")
req w5a-tag-create-b.json POST "/knowledge-bases/${KB}/tags" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' \
  -d '{"name":"w5a-标签B"}'
TAG_B=$(python3 -c "import json;print(json.load(open('${OUT}/w5a-tag-create-b.json'))['data']['id'])")
req w5a-tag-create-c.json POST "/knowledge-bases/${KB}/tags" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' \
  -d '{"name":"w5a-标签C"}'
TAG_C=$(python3 -c "import json;print(json.load(open('${OUT}/w5a-tag-create-c.json'))['data']['id'])")
req w5a-tag-create-dup.json POST "/knowledge-bases/${KB}/tags" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' \
  -d '{"name":"w5a-标签A"}'
req w5a-tag-create-missing-name.json POST "/knowledge-bases/${KB}/tags" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' -d '{"color":"#000000"}'
req w5a-tag-create-nonowner.json POST "/knowledge-bases/${KB}/tags" -H "${AUTH_VIEWER}" -H 'Content-Type: application/json' -d '{"name":"nope"}'
req w5a-tag-list.json GET "/knowledge-bases/${KB}/tags" -H "${AUTH_OWNER}"
curl -s -o "${OUT}/w5a-tag-list-keyword.json" -w '%{http_code} %{url_effective}\n' \
  -G "http://localhost:${PORT}/api/v1/knowledge-bases/${KB}/tags" -H "${AUTH_OWNER}" --data-urlencode "keyword=w5a-标签A"
req w5a-tag-list-page.json GET "/knowledge-bases/${KB}/tags?page=1&page_size=1" -H "${AUTH_OWNER}"
req w5a-tag-list-badpage.json GET "/knowledge-bases/${KB}/tags?page=abc" -H "${AUTH_OWNER}"
req w5a-tag-list-zeropage.json GET "/knowledge-bases/${KB}/tags?page=0" -H "${AUTH_OWNER}"
req w5a-tag-update.json PUT "/knowledge-bases/${KB}/tags/${TAG_A}" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' \
  -d '{"name":"w5a-标签A2","sort_order":1}'
SEQ_A=$(${PSQL} -c "SELECT seq_id FROM knowledge_tags WHERE id='${TAG_A}';" | xargs)
req w5a-tag-update-seqid.json PUT "/knowledge-bases/${KB}/tags/${SEQ_A}" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' \
  -d '{"color":"#00ff00"}'
req w5a-tag-update-missing.json PUT "/knowledge-bases/${KB}/tags/00000000-0000-0000-0000-100000000000" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' -d '{"name":"x"}'
req w5a-tag-update-missing-seq.json PUT "/knowledge-bases/${KB}/tags/999999" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' -d '{"name":"x"}'
req w5a-tag-update-blank.json PUT "/knowledge-bases/${KB}/tags/${TAG_A}" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' -d '{"name":"   "}'
# 引用：updates 的键是 **knowledge_id**（值才是 tag uuid 列表——knowledge.go L955-968
# 把键当 knowledge 批量加载），把标签C挂到 knowledge 上，钉住"仍有引用"的删除分支
req w5a-tag-ref-assign.json PUT "/knowledge/tags" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' \
  -d "{\"kb_id\":\"${KB}\",\"updates\":{\"5a5a0000000000000000000000000001\":[\"${TAG_C}\"]}}"
req w5a-tag-delete-referenced.json DELETE "/knowledge-bases/${KB}/tags/${TAG_C}" -H "${AUTH_OWNER}"
req w5a-tag-delete-content-only.json DELETE "/knowledge-bases/${KB}/tags/${TAG_C}?content_only=true" -H "${AUTH_OWNER}"
req w5a-tag-delete-ok.json DELETE "/knowledge-bases/${KB}/tags/${TAG_B}" -H "${AUTH_OWNER}"

# ═══ 4) IM 回调 ═══
echo "==> im"
req w5a-agent-create.json POST "/agents" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' \
  -d '{"name":"w5a-im-agent","description":"w5a im agent","config":{}}'
AGENT=$(python3 -c "import json;print(json.load(open('${OUT}/w5a-agent-create.json'))['data']['id'])")
req w5a-im-callback-unknown-get.json  GET  "/im/callback/00000000-0000-0000-0000-000000000000"
req w5a-im-callback-unknown-post.json POST "/im/callback/00000000-0000-0000-0000-000000000000" -H 'Content-Type: application/json' -d '{}'
req w5a-im-channel-create.json POST "/agents/${AGENT}/im-channels" -H "${AUTH_OWNER}" -H 'Content-Type: application/json' \
  -d '{"platform":"mattermost","name":"w5a-im"}'
CHANNEL=$(python3 -c "import json;print(json.load(open('${OUT}/w5a-im-channel-create.json'))['data']['id'])")
req w5a-im-callback-enabled-get.json  GET  "/im/callback/${CHANNEL}"
req w5a-im-callback-enabled-post.json POST "/im/callback/${CHANNEL}" -H 'Content-Type: application/x-www-form-urlencoded' -d 'text=hello&user_id=u1&channel_id=c1&post_id=p1'
req w5a-im-channel-toggle.json POST "/im-channels/${CHANNEL}/toggle" -H "${AUTH_OWNER}"
req w5a-im-callback-disabled.json POST "/im/callback/${CHANNEL}" -H 'Content-Type: application/json' -d '{}'

# ═══ 5) 清理 ═══
curl -s -o /dev/null -X DELETE "${API}/im-channels/${CHANNEL}" -H "${AUTH_OWNER}" || true
curl -s -o /dev/null -X DELETE "${API}/agents/${AGENT}" -H "${AUTH_OWNER}" || true
curl -s -o /dev/null -X DELETE "${API}/knowledge-bases/${KB}" -H "${AUTH_OWNER}" || true
${PSQL} -c "DELETE FROM tenants WHERE name LIKE 'w5a-tmp-%';" >/dev/null 2>&1 || true
# ═══ 6) logout 放最后（Logout 吊销 java-phase1 的**全部** token——不能影响前面的场景）
echo "==> logout"
req w5a-auth-logout-noheader.json     POST "/auth/logout"
LOGOUT_TOK=$(login_tok java-phase1@weknora.test)
req w5a-auth-logout-ok.json           POST "/auth/logout" -H "Authorization: Bearer ${LOGOUT_TOK}"
req w5a-auth-logout-revoked.json      GET  "/auth/validate" -H "Authorization: Bearer ${LOGOUT_TOK}"

echo "==> done: $(ls "${OUT}" | grep -c '^w5a-') w5a-* golden in ${OUT}"
