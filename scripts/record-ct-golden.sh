#!/usr/bin/env bash
# 录跨租户租户管理族（波 2 扫尾批 3）的 golden：ct-*。
#
# 覆盖端点（5 条，routes_auth_tenant.go L53-81）：
#   GET /tenants/all   GET /tenants/search   POST /tenants
#   GET /tenants/kv/:key   PUT /tenants/kv/:key
#
# 前置部署态（重要）：
#   - 本脚本的大部分 golden 要求 Go server 以
#     WEKNORA_TENANT_ENABLE_CROSS_TENANT_ACCESS=true 启动（否则 /all、/search 只剩
#     flag-off 403 一条路径）。
#   - flag-off 分支单独录：先以默认（flag off）起 Go，然后
#       CT_FLAG_OFF=1 scripts/record-ct-golden.sh
#     再重启成 flag on 跑全量：
#       WEKNORA_TENANT_ENABLE_CROSS_TENANT_ACCESS=true scripts/go-server-up.sh
#       scripts/record-ct-golden.sh
#
# 场景设计（顺序敏感，契约测试/A/B 必须严格复刻本顺序）：
#   - 固定种子身份：ct-super（can_access_all_tenants=true，跨租户超管）、
#     ct-viewer / ct-self（10002 的 viewer）、javasysadmin（系统管理员，切设置用），
#     密码全部 = owner 行的 Passw0rd! 哈希。每次运行前 SQL 幂等清理。
#   - 创建族先行（alpha/beta/gamma 三个 ct-* 租户），list/search 在其后
#     （created_at DESC，顺序 = 创建逆序，掩码覆盖 id/时间戳）。
#   - KV 族打在 alpha 租户上（X-Tenant-ID 切换），ct-viewer 是 alpha 的 viewer
#     （403 集成秘密分支）；ct-self 是 alpha 的 owner。
#   - chat-history 的 enable 场景用 dev DB 里真实 embedding 模型
#     9aa07763-6b4f-4152-9f2b-3ad0e2d2f69f，会自动建隐藏 KB（uuid 掩码，收尾删除）。
#   - 设置切换（max_owned_per_user=1 → 429；self_service=false → 403 code 2005；
#     auto_create_api_key=true → data.api_key）全部录完即 DELETE 还原。
#   - prompt-templates GET 是 Go 独有（vendor 的 config/prompt_templates/*.yaml +
#     语言中间件，Java 侧推迟——见 docs §9「波 2 扫尾批 3」deferral），golden 照录，
#     A/B 列为 EXPECTED DIFF。
#
# 动态值（A/B 与契约测试同掩码）：uuid / 时间戳 / 租户与行的 "id" 数字 /
# data.api_key 明文 token。
#
# 用法：
#   scripts/record-ct-golden.sh                  # 录 Go（:8080）进 contracts/
#   CT_TARGET_PORT=8082 CT_OUT_DIR=/tmp/x scripts/record-ct-golden.sh  # A/B 重放 Java
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${CT_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${CT_TARGET_PORT:-${GO_PORT}}"
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

# ── flag-off 分支（独立小段，单独录） ─────────────────────────────────────
if [ "${CT_FLAG_OFF:-0}" = "1" ]; then
  OTOKEN="$(login_tok "${TEST_EMAIL}")"
  [ -n "${OTOKEN}" ] || { echo "FATAL: owner 登录失败"; exit 1; }
  echo "==> flag-off：跨租户守卫 403（Cross-workspace access is disabled）"
  req ct-all-disabled.json    GET /tenants/all    -H "Authorization: Bearer ${OTOKEN}"
  req ct-search-disabled.json GET /tenants/search -H "Authorization: Bearer ${OTOKEN}"
  echo "==> 完成（flag-off 2 条）"
  exit 0
fi

OWNER_ID="11111111-2222-3333-4444-555555555501"
SUPER_ID="11111111-2222-3333-4444-555555555702"
VIEWER_ID="11111111-2222-3333-4444-555555555703"
SELF_ID="11111111-2222-3333-4444-555555555704"
SYS_ID="11111111-2222-3333-4444-555555555701"
SUPER_EMAIL="ct-super@weknora.test"
VIEWER_EMAIL="ct-viewer@weknora.test"
SELF_EMAIL="ct-self@weknora.test"
SYS_EMAIL="java-sys-admin@weknora.test"
EMBEDDING_MODEL="9aa07763-6b4f-4152-9f2b-3ad0e2d2f69f"
LONG_NAME="$(python3 -c 'print("n"*129)')"
LONG_DESC="$(python3 -c 'print("d"*513)')"

cleanup() {
  ${PSQL} >/dev/null 2>&1 <<SQL
CREATE TEMP TABLE ct_tenants AS SELECT id FROM tenants WHERE name LIKE 'ct-%';
UPDATE tenants SET default_storage_backend_id = NULL WHERE id IN (SELECT id FROM ct_tenants);
DELETE FROM storage_backends WHERE tenant_id IN (SELECT id FROM ct_tenants);
DELETE FROM knowledge WHERE knowledge_base_id IN (SELECT id FROM knowledge_bases WHERE tenant_id IN (SELECT id FROM ct_tenants));
DELETE FROM knowledge_bases WHERE tenant_id IN (SELECT id FROM ct_tenants);
DELETE FROM tenant_api_keys WHERE tenant_id IN (SELECT id FROM ct_tenants);
DELETE FROM tenant_members WHERE tenant_id IN (SELECT id FROM ct_tenants);
DELETE FROM tenants WHERE id IN (SELECT id FROM ct_tenants);
DELETE FROM auth_tokens WHERE user_id IN ('${SUPER_ID}','${VIEWER_ID}','${SELF_ID}','${SYS_ID}');
DELETE FROM tenant_members WHERE user_id IN ('${SUPER_ID}','${VIEWER_ID}','${SELF_ID}','${SYS_ID}');
DELETE FROM users WHERE id IN ('${SUPER_ID}','${VIEWER_ID}','${SELF_ID}','${SYS_ID}');
DELETE FROM system_settings WHERE key IN
  ('tenant.max_owned_per_user','tenant.self_service_creation_enabled','tenant.auto_create_api_key');
SQL
}

echo "==> 幂等清理：ct-* 租户 / 种子用户 / 三个设置键"
cleanup

echo "==> 种子：超管 / viewer / self / sysadmin（密码哈希复用 owner 行）"
${PSQL} >/dev/null <<SQL
INSERT INTO users (id, username, email, password_hash, tenant_id, is_active, can_access_all_tenants, is_system_admin)
SELECT '${SUPER_ID}', 'ct-super', '${SUPER_EMAIL}', password_hash, 10002, true, true, false
  FROM users WHERE id='${OWNER_ID}';
INSERT INTO users (id, username, email, password_hash, tenant_id, is_active)
SELECT '${VIEWER_ID}', 'ct-viewer', '${VIEWER_EMAIL}', password_hash, 10002, true FROM users WHERE id='${OWNER_ID}';
INSERT INTO users (id, username, email, password_hash, tenant_id, is_active)
SELECT '${SELF_ID}', 'ct-self', '${SELF_EMAIL}', password_hash, 10002, true FROM users WHERE id='${OWNER_ID}';
INSERT INTO users (id, username, email, password_hash, tenant_id, is_active, is_system_admin)
SELECT '${SYS_ID}', 'javasysadmin', '${SYS_EMAIL}', password_hash, 10002, true, true FROM users WHERE id='${OWNER_ID}';
INSERT INTO tenant_members (user_id, tenant_id, role, status, joined_at) VALUES
  ('${SUPER_ID}', 10002, 'owner', 'active', '2026-09-01 10:03:00+00'),
  ('${VIEWER_ID}', 10002, 'viewer', 'active', '2026-09-01 10:03:00+00'),
  ('${SELF_ID}', 10002, 'viewer', 'active', '2026-09-01 10:03:00+00'),
  ('${SYS_ID}', 10002, 'owner', 'active', '2026-09-01 10:03:00+00');
SQL

SUPER="$(login_tok "${SUPER_EMAIL}")"
VIEWER="$(login_tok "${VIEWER_EMAIL}")"
SELF="$(login_tok "${SELF_EMAIL}")"
ADMIN="$(login_tok "${SYS_EMAIL}")"
[ -n "${SUPER}" ] && [ -n "${VIEWER}" ] && [ -n "${SELF}" ] && [ -n "${ADMIN}" ] \
  || { echo "FATAL: 种子用户登录失败"; exit 1; }

echo "==> 1) 创建族：self-serve 成功 + binding 家族 + 空名 500"
req ct-create-self.json        POST /tenants -H "Authorization: Bearer ${SELF}" \
  -H 'Content-Type: application/json' -d '{"name":"ct-alpha","description":"alpha workspace"}'
ALPHA_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "${OUT}/ct-create-self.json")"
# ct-viewer 加入 alpha（SQL 直种，避开成员 API 的审计噪声）
${PSQL} >/dev/null <<SQL
INSERT INTO tenant_members (user_id, tenant_id, role, status, joined_at)
VALUES ('${VIEWER_ID}', ${ALPHA_ID}, 'viewer', 'active', '2026-09-01 10:04:00+00');
SQL

req ct-create-binding-empty.json POST /tenants -H "Authorization: Bearer ${SELF}" \
  -H 'Content-Type: application/json' -d '{}'
req ct-create-binding-longname.json POST /tenants -H "Authorization: Bearer ${SELF}" \
  -H 'Content-Type: application/json' -d '{"name":"'"${LONG_NAME}"'"}'
req ct-create-binding-longdesc.json POST /tenants -H "Authorization: Bearer ${SELF}" \
  -H 'Content-Type: application/json' -d '{"name":"ok-name","description":"'"${LONG_DESC}"'"}'
req ct-create-empty-body.json  POST /tenants -H "Authorization: Bearer ${SELF}" -H 'Content-Type: application/json'
req ct-create-wsname-500.json  POST /tenants -H "Authorization: Bearer ${SELF}" \
  -H 'Content-Type: application/json' -d '{"name":"  ","description":""}'

echo "==> 2) 超管全字段路径"
req ct-create-superuser.json   POST /tenants -H "Authorization: Bearer ${SUPER}" \
  -H 'Content-Type: application/json' \
  -d '{"name":"ct-beta","description":"beta workspace","storage_quota":12345,"status":"suspended"}'
req ct-create-super-noname.json POST /tenants -H "Authorization: Bearer ${SUPER}" \
  -H 'Content-Type: application/json' -d '{"name":"","description":"x"}'

echo "==> 3) 配额 429（cap=1，ct-self 已 owns alpha）→ 还原"
req ct-set-quota.json PUT /system/admin/settings/tenant.max_owned_per_user \
  -H "Authorization: Bearer ${ADMIN}" -H 'Content-Type: application/json' -d '{"value":"1"}'
req ct-create-quota-429.json   POST /tenants -H "Authorization: Bearer ${SELF}" \
  -H 'Content-Type: application/json' -d '{"name":"ct-over-quota"}'
req ct-unset-quota.json DELETE /system/admin/settings/tenant.max_owned_per_user \
  -H "Authorization: Bearer ${ADMIN}"

echo "==> 4) self-service 关停 403（code 2005）→ 还原"
req ct-set-noss.json PUT /system/admin/settings/tenant.self_service_creation_enabled \
  -H "Authorization: Bearer ${ADMIN}" -H 'Content-Type: application/json' -d '{"value":false}'
req ct-create-disabled.json    POST /tenants -H "Authorization: Bearer ${SELF}" \
  -H 'Content-Type: application/json' -d '{"name":"ct-blocked"}'
req ct-unset-noss.json DELETE /system/admin/settings/tenant.self_service_creation_enabled \
  -H "Authorization: Bearer ${ADMIN}"

echo "==> 5) auto_create_api_key 兼容路径（data.api_key 明文，掩码）→ 还原"
req ct-set-autokey.json PUT /system/admin/settings/tenant.auto_create_api_key \
  -H "Authorization: Bearer ${ADMIN}" -H 'Content-Type: application/json' -d '{"value":true}'
req ct-create-apikey.json      POST /tenants -H "Authorization: Bearer ${SELF}" \
  -H 'Content-Type: application/json' -d '{"name":"ct-gamma","description":"gamma workspace"}'
req ct-unset-autokey.json DELETE /system/admin/settings/tenant.auto_create_api_key \
  -H "Authorization: Bearer ${ADMIN}"

echo "==> 6) 跨租户守卫与列举/搜索"
req ct-all-nonsuper.json       GET /tenants/all    -H "Authorization: Bearer ${VIEWER}"
req ct-search-nonsuper.json    GET /tenants/search -H "Authorization: Bearer ${VIEWER}"
req ct-all-ok.json             GET /tenants/all    -H "Authorization: Bearer ${SUPER}"
req ct-search-default.json     GET /tenants/search -H "Authorization: Bearer ${SUPER}"
req ct-search-keyword.json     GET '/tenants/search?keyword=beta' -H "Authorization: Bearer ${SUPER}"
req ct-search-tenantid.json    GET '/tenants/search?tenant_id=10002' -H "Authorization: Bearer ${SUPER}"
req ct-search-badid.json       GET '/tenants/search?tenant_id=abc' -H "Authorization: Bearer ${SUPER}"
req ct-search-pageclamp.json   GET '/tenants/search?page=0&page_size=500' -H "Authorization: Bearer ${SUPER}"
req ct-search-kw-id.json       GET '/tenants/search?keyword=alpha&tenant_id=10002' -H "Authorization: Bearer ${SUPER}"

echo "==> 7) KV 分发器：unsupported / prompt-templates / viewer 403"
req ct-kv-unsupported.json     GET /tenants/kv/bogus-key \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}"
req ct-kv-put-prompt-templates.json PUT /tenants/kv/prompt-templates \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}" \
  -H 'Content-Type: application/json' -d '{}'
req ct-kv-get-prompt-templates.json GET /tenants/kv/prompt-templates \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}"
req ct-kv-secret-viewer.json   GET /tenants/kv/web-search-config \
  -H "Authorization: Bearer ${VIEWER}" -H "X-Tenant-ID: ${ALPHA_ID}"
req ct-kv-ret-get-viewer.json  GET /tenants/kv/retrieval-config \
  -H "Authorization: Bearer ${VIEWER}" -H "X-Tenant-ID: ${ALPHA_ID}"

echo "==> 8) KV web-search-config"
req ct-kv-ws-get-default.json  GET /tenants/kv/web-search-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}"
req ct-kv-ws-put.json          PUT /tenants/kv/web-search-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}" -H 'Content-Type: application/json' \
  -d '{"max_results":5,"include_date":true,"compression_method":"summary","blacklist":["bad.com"],"api_key":"ak-secret-123","proxy_url":"http://proxy.local:8080"}'
req ct-kv-ws-get-after.json    GET /tenants/kv/web-search-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}"
req ct-kv-ws-put-preserve.json PUT /tenants/kv/web-search-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}" -H 'Content-Type: application/json' \
  -d '{"max_results":7,"compression_method":"none","api_key":"***","proxy_url":"***"}'
req ct-kv-ws-put-bad51.json    PUT /tenants/kv/web-search-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}" -H 'Content-Type: application/json' \
  -d '{"max_results":51}'

echo "==> 9) KV parser-engine-config"
req ct-kv-parser-get-default.json GET /tenants/kv/parser-engine-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}"
req ct-kv-parser-put.json      PUT /tenants/kv/parser-engine-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}" -H 'Content-Type: application/json' \
  -d '{"mineru_endpoint":"http://mineru.example.com","mineru_api_key":"mk-secret","mineru_model":"pipeline"}'
req ct-kv-parser-get-after.json GET /tenants/kv/parser-engine-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}"
req ct-kv-parser-put-preserve.json PUT /tenants/kv/parser-engine-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}" -H 'Content-Type: application/json' \
  -d '{"mineru_endpoint":"http://mineru2.example.com","mineru_api_key":"***"}'
req ct-kv-parser-put-ssrf.json PUT /tenants/kv/parser-engine-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}" -H 'Content-Type: application/json' \
  -d '{"mineru_endpoint":"http://127.0.0.1:9000/x"}'

echo "==> 10) KV storage-engine-config"
req ct-kv-storage-get-default.json GET /tenants/kv/storage-engine-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}"
req ct-kv-storage-put.json     PUT /tenants/kv/storage-engine-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}" -H 'Content-Type: application/json' \
  -d '{"default_provider":"minio","minio":{"mode":"remote","endpoint":"http://minio.example.com","access_key_id":"AK","secret_access_key":"SK","bucket_name":"b","use_ssl":false,"path_prefix":"p"}}'
req ct-kv-storage-get-after.json GET /tenants/kv/storage-engine-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}"
req ct-kv-storage-put-preserve.json PUT /tenants/kv/storage-engine-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}" -H 'Content-Type: application/json' \
  -d '{"default_provider":"minio","minio":{"mode":"remote","endpoint":"http://minio2.example.com","access_key_id":"***","secret_access_key":"***","bucket_name":"b2","use_ssl":true,"path_prefix":"p2"}}'
req ct-kv-storage-put-empty-provider.json PUT /tenants/kv/storage-engine-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}" -H 'Content-Type: application/json' \
  -d '{"default_provider":" "}'

echo "==> 11) KV chat-history-config（enable 场景自动建隐藏 KB，uuid 掩码）"
req ct-kv-chat-get-default.json GET /tenants/kv/chat-history-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}"
req ct-kv-chat-put-off.json    PUT /tenants/kv/chat-history-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}" -H 'Content-Type: application/json' \
  -d '{"enabled":false,"embedding_model_id":""}'
req ct-kv-chat-put-enable.json PUT /tenants/kv/chat-history-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}" -H 'Content-Type: application/json' \
  -d '{"enabled":true,"embedding_model_id":"'"${EMBEDDING_MODEL}"'"}'
req ct-kv-chat-get-after.json  GET /tenants/kv/chat-history-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}"
req ct-kv-chat-put-again.json  PUT /tenants/kv/chat-history-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}" -H 'Content-Type: application/json' \
  -d '{"enabled":true,"embedding_model_id":"'"${EMBEDDING_MODEL}"'"}'

echo "==> 12) KV retrieval-config"
req ct-kv-ret-get-default.json GET /tenants/kv/retrieval-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}"
req ct-kv-ret-put.json         PUT /tenants/kv/retrieval-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}" -H 'Content-Type: application/json' \
  -d '{"embedding_top_k":20,"vector_threshold":0.5,"keyword_threshold":0.4,"rerank_top_k":5,"rerank_threshold":0.1,"rerank_model_id":"rm-1","rrf_k":60,"rrf_vector_weight":0.7,"rrf_keyword_weight":0.3}'
req ct-kv-ret-get-after.json   GET /tenants/kv/retrieval-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}"
req ct-kv-ret-put-bad-vector.json PUT /tenants/kv/retrieval-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}" -H 'Content-Type: application/json' \
  -d '{"vector_threshold":1.5}'
req ct-kv-ret-put-bad-topk.json PUT /tenants/kv/retrieval-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}" -H 'Content-Type: application/json' \
  -d '{"embedding_top_k":201}'

echo "==> 13) KV memory-config"
req ct-kv-mem-get-default.json GET /tenants/kv/memory-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}"
req ct-kv-mem-put.json         PUT /tenants/kv/memory-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}" -H 'Content-Type: application/json' \
  -d '{"enabled":true,"write_mode":"auto","max_items":500,"extract_delay_seconds":30,"extract_min_interval_seconds":60,"extract_instructions":"  记笔记  ","interest_threshold":5,"embedding_model_id":"","vector_recall":true,"retrieval_conditioning":false}'
req ct-kv-mem-get-after.json   GET /tenants/kv/memory-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}"
req ct-kv-mem-put-bad-mode.json PUT /tenants/kv/memory-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}" -H 'Content-Type: application/json' \
  -d '{"write_mode":"bogus"}'
req ct-kv-mem-put-bad-max.json PUT /tenants/kv/memory-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}" -H 'Content-Type: application/json' \
  -d '{"max_items":2001}'
req ct-kv-mem-put-bad-interest.json PUT /tenants/kv/memory-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}" -H 'Content-Type: application/json' \
  -d '{"interest_threshold":-1}'
req ct-kv-mem-put-bad-delay.json PUT /tenants/kv/memory-config \
  -H "Authorization: Bearer ${SELF}" -H "X-Tenant-ID: ${ALPHA_ID}" -H 'Content-Type: application/json' \
  -d '{"extract_delay_seconds":3601}'

echo "==> 收尾：清空录制态"
cleanup

echo "==> 完成：ct-* golden 共 $(ls "${OUT}" | grep -c '^ct-') 条"
