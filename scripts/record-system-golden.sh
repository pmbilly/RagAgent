#!/usr/bin/env bash
# 录系统管理端 + 评估（波 2 收官批）的 golden：sys-* / adm-* / ev-*。
#
# 场景设计（约定 §4 / §9；录制顺序影响状态，契约测试必须严格复刻本顺序）：
#   - 系统管理员用户 javasysadmin（is_system_admin=true，租户 10002 内，SQL 直插，
#     照 record-members-golden.sh 的播种模式）+ tenant_members owner 行（登录要靠
#     成员关系解析活动租户，否则 409 TENANT_REQUIRED）。
#   - admin 组全部 SystemAdmin 守卫：普通用户打 admin 组 → 403 纯字符串
#     {"error":"Forbidden: system administrator required"}（golden 锁定文案）。
#   - 平台 API Key：API 现场创建、录完即删（token/api_key 不落 golden 原文，掩码比对）。
#   - settings：PUT 会写 system_settings 表（dev PG）→ 幂等清理（DELETE + 还原 quota）。
#   - apply-default-storage-quota 会写**全部**租户行：dev 两个租户的 storage_quota
#     已经是 10GiB（=当前 setting 值）→ 幂等 no-op；脚本收尾仍把 10000/10002
#     显式归位到 10737418240。
#   - runtime/queues：dev Go 走的是 **Redis/asynq 模式**（available=true、实时深度、
#     timestamp）——不是 Java 的 Lite 形态；只录**两侧模式都确定**的错误分支
#     （未知队列 / 未知 state / 未知 action / 未知 queue 的 purge），Lite 的
#     200/503 形态以 Go noopTaskInspector 源码为准直接写契约测试（报告注明）。
#   - docreader：dev 有真 docreader（localhost:50051）→ parser-engines 的远端分支
#     两侧都打真服务，确定性 ✓。reconnect 的成功分支会 SSRF 拒绝 localhost
#     （Go host-run 未配 SSRF_WHITELIST）→ 只录校验失败分支（确定性）。
#   - storage check 只录失败分支（未提供配置 / 引擎被禁用 / 非法 body）。
#   - evaluation：POST 空 body（租户无模型 → 500 "no default models found"）、
#     POST 带 kb+chat_id（成功建任务形态；status 依赖 Go goroutine 竞态，实测恒 1）、
#     GET task（内存存储 → 200/404）、validator 400。后台 EvalDataset 会因
#     dataset 不存在失败并泄漏一个名为 "evaluation" 的 KB → 收尾清理。
#   - sandbox-check：Go 注册但 Java 本批留 404（依赖波 3 sandbox）→ **不录**。
#   - 动态值：任务 id / 时间戳 / UUID / key token / key 数字 id 由契约测试统一掩码。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${SYS_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${SYS_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"

SYS_ID="11111111-2222-3333-4444-555555555701"
OWNER_ID="11111111-2222-3333-4444-555555555501"
VIEWER_ID="11111111-2222-3333-4444-555555555504"
GEN_A_ID="11111111-2222-3333-4444-5555555557a1"  # 不用：生成密码的用户 id 由服务端分配
UNKNOWN_ID="11111111-2222-3333-4444-999999999999"
# 评估用的既有 KB（chunk-golden-kb，录制前验证存在）
EV_KB_ID="2645450c-1060-419c-87de-b1f58f61256d"

req()  { local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" "$@"; }
reqa() { local out="$1" method="$2" path="$3"; shift 3   # 系统管理员
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "Authorization: Bearer ${ATOKEN}" "$@"; }
reqo() { local out="$1" method="$2" path="$3"; shift 3   # 普通租户用户（非系统管理员）
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "Authorization: Bearer ${OTOKEN}" "$@"; }
reqv() { local out="$1" method="$2" path="$3"; shift 3   # viewer
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "Authorization: Bearer ${VTOKEN}" "$@"; }
reqk() { local out="$1" method="$2" path="$3"; shift 3   # 平台 API Key
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "X-API-Key: ${PKEY}" "$@"; }

login_tok() {
  curl -s -X POST "http://localhost:${PORT}/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"${1}\",\"password\":\"${TEST_PASSWORD}\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])'
}

echo "==> 幂等清理：sysadmin 用户 / golden 用户 / settings 行 / evaluation 泄漏 KB / 平台 key"
${PSQL} >/dev/null 2>&1 <<SQL
DELETE FROM system_settings WHERE key IN ('tenant.max_owned_per_user', 'ssrf.whitelist');
DELETE FROM knowledge_bases WHERE tenant_id = 10002 AND name = 'evaluation' AND is_temporary = false AND COALESCE(description,'') IN ('evaluation','');
DELETE FROM users WHERE id = '${SYS_ID}' OR email LIKE 'java-sys-%@weknora.test';
DELETE FROM tenants WHERE name LIKE 'sysgolden%';
DELETE FROM tenant_api_keys WHERE scope_type = 'platform' AND name LIKE 'sys-golden%';
SQL

echo "==> 种子：系统管理员用户（成员行 joined_at 固定）"
${PSQL} >/dev/null <<SQL
INSERT INTO users (id, username, email, password_hash, tenant_id, is_system_admin) VALUES
('${SYS_ID}', 'javasysadmin', 'java-sys-admin@weknora.test',
 (SELECT password_hash FROM users WHERE id='${OWNER_ID}'), 10002, true);
INSERT INTO tenant_members (user_id, tenant_id, role, status, joined_at) VALUES
('${SYS_ID}', 10002, 'owner', 'active', '2026-09-01 10:03:00+00');
SQL

echo "==> 准备：token（A=系统管理员 / O=普通租户用户 / V=viewer）"
ATOKEN="$(login_tok java-sys-admin@weknora.test)"
OTOKEN="$(login_tok java-phase1@weknora.test)"
VTOKEN="$(login_tok java-phase1-viewer@weknora.test)"

echo "==> 1) /system 组：读端点（Viewer+）"
req sys-capabilities.json        GET /system/capabilities -H "Authorization: Bearer ${OTOKEN}"
req sys-capabilities-viewer.json GET /system/capabilities -H "Authorization: Bearer ${VTOKEN}"
req sys-capabilities-apikey.json GET /system/capabilities -H "X-API-Key: none-yet" || true
# 上面的 apikey 用例需要一个真实 key——挪到平台 key 建好之后补录（见第 6 步）
rm -f "${OUT}/sys-capabilities-apikey.json"
req sys-info.json                GET /system/info -H "Authorization: Bearer ${OTOKEN}"
req sys-parser-engines.json      GET /system/parser-engines -H "Authorization: Bearer ${OTOKEN}"
req sys-parser-engines-viewer.json GET /system/parser-engines -H "Authorization: Bearer ${VTOKEN}"
req sys-storage-status.json      GET /system/storage-engine-status -H "Authorization: Bearer ${OTOKEN}"

echo "==> 2) /system 组：Admin 探测端点（失败/校验分支）"
req sys-parser-check-empty.json  POST /system/parser-engines/check -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{}'
req sys-parser-check-badjson.json POST /system/parser-engines/check -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d 'nope'
req sys-parser-check-viewer.json POST /system/parser-engines/check -H "Authorization: Bearer ${VTOKEN}" -H 'Content-Type: application/json' -d '{}'
req sys-reconnect-missing.json   POST /system/docreader/reconnect -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{}'
req sys-reconnect-blank.json     POST /system/docreader/reconnect -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"addr":"   "}'
req sys-reconnect-badjson.json   POST /system/docreader/reconnect -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d 'nope'
req sys-reconnect-ssrf.json      POST /system/docreader/reconnect -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"addr":"http://169.254.169.254:50051"}'
req sys-storage-check-minio-empty.json POST /system/storage-engine-check -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"provider":"minio"}'
req sys-storage-check-bogus.json POST /system/storage-engine-check -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"provider":"bogus"}'
req sys-storage-check-badjson.json POST /system/storage-engine-check -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d 'nope'
req sys-storage-check-local.json POST /system/storage-engine-check -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"provider":"local"}'

echo "==> 3) admin 组：守卫（非系统管理员 → 403 纯字符串）"
req adm-guard-owner.json   POST /system/admin/promote -H "Authorization: Bearer ${OTOKEN}" -H 'Content-Type: application/json' -d '{}'
req adm-guard-viewer.json  GET /system/admin/list -H "Authorization: Bearer ${VTOKEN}"
req adm-guard-noauth.json  GET /system/admin/list

echo "==> 4) admin 组：系统管理员升降级 + 列表"
req adm-promote-missing.json POST /system/admin/promote -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{}'
req adm-promote-404.json     POST /system/admin/promote -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"user_id":"'"${UNKNOWN_ID}"'"}'
req adm-list.json            GET /system/admin/list -H "Authorization: Bearer ${ATOKEN}"
req adm-list-page.json       GET "/system/admin/list?limit=abc&offset=-3" -H "Authorization: Bearer ${ATOKEN}"
req adm-promote-self.json    POST /system/admin/promote -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"user_id":"'"${SYS_ID}"'"}'
# promote 真实分支：把 owner 提上来再撤下去（顺带录 revoke 幂等/真实两形态）
req adm-promote-owner.json   POST /system/admin/promote -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"user_id":"'"${OWNER_ID}"'"}'
req adm-revoke-self.json     POST /system/admin/revoke -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"user_id":"'"${SYS_ID}"'"}'
req adm-revoke-noop.json     POST /system/admin/revoke -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"user_id":"'"${OWNER_ID}"'"}'
req adm-promote-owner2.json  POST /system/admin/promote -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"user_id":"'"${OWNER_ID}"'"}'
req adm-revoke-owner.json    POST /system/admin/revoke -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"user_id":"'"${OWNER_ID}"'"}'
req adm-revoke-404.json      POST /system/admin/revoke -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"user_id":"'"${UNKNOWN_ID}"'"}'
req adm-revoke-badbody.json  POST /system/admin/revoke -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{}'
req adm-list-after.json      GET /system/admin/list -H "Authorization: Bearer ${ATOKEN}"

echo "==> 5) admin 组：用户管理（reset-password / create）"
req adm-reset-weak.json    POST /system/admin/users/reset-password -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"email":"java-phase1-viewer@weknora.test","new_password":"short"}'
req adm-reset-self.json    POST /system/admin/users/reset-password -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"email":"java-sys-admin@weknora.test","new_password":"'"${TEST_PASSWORD}"'"}'
req adm-reset-404.json     POST /system/admin/users/reset-password -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"email":"nobody@weknora.test","new_password":"'"${TEST_PASSWORD}"'"}'
req adm-reset-badbody.json POST /system/admin/users/reset-password -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"email":"notanemail","new_password":"x"}'
req adm-reset-ok.json      POST /system/admin/users/reset-password -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"email":"java-phase1-viewer@weknora.test","new_password":"'"${TEST_PASSWORD}"'"}'
req adm-create-badbody.json POST /system/admin/users/create -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"username":"x","email":"notanemail"}'
req adm-create-shortname.json POST /system/admin/users/create -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"username":"x","email":"java-sys-golden-1@weknora.test"}'
req adm-create-missing.json POST /system/admin/users/create -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{}'
req adm-create.json        POST /system/admin/users/create -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"username":"sysgolden1","email":"java-sys-golden-1@weknora.test","password":"'"${TEST_PASSWORD}"'"}'
req adm-create-dup.json    POST /system/admin/users/create -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"username":"sysgolden1","email":"java-sys-golden-1@weknora.test","password":"'"${TEST_PASSWORD}"'"}'
req adm-create-conflict.json POST /system/admin/users/create -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"username":"sysgoldenX","email":"java-sys-golden-1@weknora.test","password":"'"${TEST_PASSWORD}"'"}'
req adm-create-weakpw.json POST /system/admin/users/create -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"username":"sysgolden3","email":"java-sys-golden-3@weknora.test","password":"short"}'
req adm-create-generated.json POST /system/admin/users/create -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"username":"sysgolden2","email":"java-sys-golden-2@weknora.test"}'

echo "==> 6) admin 组：平台 API Key（list → create → 借道打 capabilities → delete）"
req adm-key-list-empty.json GET /system/admin/api-keys -H "Authorization: Bearer ${ATOKEN}"
req adm-key-create-noname.json POST /system/admin/api-keys -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"name":"  ","capabilities":["chat"]}'
req adm-key-create-badcap.json POST /system/admin/api-keys -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"name":"x","capabilities":["bogus"]}'
req adm-key-create-exppast.json POST /system/admin/api-keys -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"name":"x","capabilities":["chat"],"expires_at_unix":1000000000}'
req adm-key-create-badjson.json POST /system/admin/api-keys -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d 'nope'
KEY_JSON="/tmp/sys-golden-key.json"
curl -s -o "${KEY_JSON}" -X POST "${API}/system/admin/api-keys" \
  -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' \
  -d '{"name":"sys-golden-key","capabilities":["chat"],"expires_at_unix":4102444800}'
KEY_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "${KEY_JSON}")"
PKEY="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["token"])' "${KEY_JSON}")"
mv "${KEY_JSON}" "${OUT}/adm-key-create.json"
req adm-key-list.json GET /system/admin/api-keys -H "Authorization: Bearer ${ATOKEN}"
# 平台 key 打 system 读端（apiKeyManageVectorStores(apiKeyFullAccess()) 组策略 + apiKeyAny 的 capabilities）
req sys-capabilities-apikey.json GET /system/capabilities -H "X-API-Key: ${PKEY}"
req adm-guard-platformkey.json GET /system/admin/settings -H "X-API-Key: ${PKEY}"
req adm-key-delete-404.json DELETE /system/admin/api-keys/999999 -H "Authorization: Bearer ${ATOKEN}"
req adm-key-delete-badid.json DELETE /system/admin/api-keys/abc -H "Authorization: Bearer ${ATOKEN}"
req adm-key-delete.json DELETE "/system/admin/api-keys/${KEY_ID}" -H "Authorization: Bearer ${ATOKEN}"
req adm-key-list-after.json GET /system/admin/api-keys -H "Authorization: Bearer ${ATOKEN}"

echo "==> 7) admin 组：settings（虚拟行 → PUT 落库 → DELETE 还原）"
req adm-settings-list.json GET /system/admin/settings -H "Authorization: Bearer ${ATOKEN}"
req adm-settings-get-virtual.json GET /system/admin/settings/auth.registration_mode -H "Authorization: Bearer ${ATOKEN}"
req adm-settings-404.json GET /system/admin/settings/nope.key -H "Authorization: Bearer ${ATOKEN}"
req adm-settings-put-badtype.json PUT /system/admin/settings/asynq.core_concurrency -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"value":"abc"}'
req adm-settings-put-enum.json PUT /system/admin/settings/auth.registration_mode -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"value":"bogus"}'
req adm-settings-put-null.json PUT /system/admin/settings/auth.registration_mode -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{}'
req adm-settings-put-badjson.json PUT /system/admin/settings/auth.registration_mode -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d 'nope'
req adm-settings-put.json PUT /system/admin/settings/tenant.max_owned_per_user -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"value":12}'
req adm-settings-get-persisted.json GET /system/admin/settings/tenant.max_owned_per_user -H "Authorization: Bearer ${ATOKEN}"
req adm-settings-list-after.json GET /system/admin/settings -H "Authorization: Bearer ${ATOKEN}"
req adm-settings-delete.json DELETE /system/admin/settings/tenant.max_owned_per_user -H "Authorization: Bearer ${ATOKEN}"
req adm-settings-delete-unknown.json DELETE /system/admin/settings/nope.key -H "Authorization: Bearer ${ATOKEN}"

echo "==> 8) admin 组：runtime 队列（只录两侧确定的成功分支前已知错误形态）"
req adm-queues-unknown-list.json GET "/system/admin/runtime/queues/nope/tasks?state=pending" -H "Authorization: Bearer ${ATOKEN}"
req adm-queues-badstate.json GET "/system/admin/runtime/queues/default/tasks?state=bogus" -H "Authorization: Bearer ${ATOKEN}"
req adm-queues-mutate-unknown.json POST "/system/admin/runtime/queues/nope/tasks/t1/actions/cancel" -H "Authorization: Bearer ${ATOKEN}"
req adm-queues-mutate-unknown-action.json POST "/system/admin/runtime/queues/default/tasks/t1/actions/bogus" -H "Authorization: Bearer ${ATOKEN}" || true
rm -f "${OUT}/adm-queues-mutate-unknown-action.json"  # dev Go 是 asynq 模式：走 409 Task is no longer available（Lite 是 503），形态不定 → 不录
req adm-queues-purge-unknown.json DELETE "/system/admin/runtime/queues/nope/archived" -H "Authorization: Bearer ${ATOKEN}"

echo "==> 9) admin 组：默认配额批量应用（幂等 no-op：当前值就是 10GiB）"
req adm-quota-apply.json POST /system/admin/tenants/apply-default-storage-quota -H "Authorization: Bearer ${ATOKEN}"

echo "==> 10) evaluation（Admin 写 / Viewer 读）"
# viewer 的 token 在第 5 步 adm-reset-ok 里被吊销（AdminResetPassword 会吊销全部会话）
VTOKEN="$(login_tok java-phase1-viewer@weknora.test)"
req ev-post-badjson.json POST /evaluation -H "Authorization: Bearer ${OTOKEN}" -H 'Content-Type: application/json' -d 'not-json'
req ev-post-empty.json   POST /evaluation -H "Authorization: Bearer ${OTOKEN}" -H 'Content-Type: application/json' -d '{}'
req ev-post-kb-missing.json POST /evaluation -H "Authorization: Bearer ${OTOKEN}" -H 'Content-Type: application/json' -d '{"knowledge_base_id":"11111111-2222-3333-4444-999999999999","chat_id":"fake-chat-model-id"}'
req ev-post-viewer.json  POST /evaluation -H "Authorization: Bearer ${VTOKEN}" -H 'Content-Type: application/json' -d '{}'
req ev-get-missing.json  GET /evaluation -H "Authorization: Bearer ${OTOKEN}"
req ev-get-unknown.json  GET "/evaluation?task_id=no-such-task" -H "Authorization: Bearer ${OTOKEN}"
req ev-post.json         POST /evaluation -H "Authorization: Bearer ${OTOKEN}" -H 'Content-Type: application/json' -d '{"knowledge_base_id":"'"${EV_KB_ID}"'","chat_id":"fake-chat-model-id"}'
EV_TASK_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["task"]["id"])' "${OUT}/ev-post.json")"
# 轮询到终态再录 GET（dev Go 能真跑完流水线：status=2 + metrics；这是 Go 全执行的
# 参考形态——Java 执行步降级为 failed，见 EvaluationService 类注释，A/B 按部署各自断言）
for i in $(seq 1 30); do
  ST="$(curl -s "http://localhost:${PORT}/api/v1/evaluation?task_id=${EV_TASK_ID}" -H "Authorization: Bearer ${OTOKEN}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["task"]["status"])' 2>/dev/null || echo 0)"
  [ "${ST}" = "2" ] || [ "${ST}" = "3" ] && break
  sleep 1
done
req ev-get.json          GET "/evaluation?task_id=${EV_TASK_ID}" -H "Authorization: Bearer ${OTOKEN}"
req ev-get-viewer.json   GET "/evaluation?task_id=${EV_TASK_ID}" -H "Authorization: Bearer ${VTOKEN}"

echo "==> 收尾：清空录制态"
${PSQL} >/dev/null 2>&1 <<SQL
DELETE FROM system_settings WHERE key IN ('tenant.max_owned_per_user', 'ssrf.whitelist');
DELETE FROM knowledge_bases WHERE tenant_id = 10002 AND name = 'evaluation' AND COALESCE(description,'') IN ('evaluation','');
DELETE FROM tenant_members WHERE user_id = '${SYS_ID}';
DELETE FROM users WHERE id = '${SYS_ID}' OR email LIKE 'java-sys-%@weknora.test';
DELETE FROM tenants WHERE name LIKE 'sysgolden%';
UPDATE tenants SET storage_quota = 10737418240 WHERE id IN (10000, 10002);
SQL

echo "==> 完成：sys-*/adm-*/ev-* golden 共 $(ls "${OUT}" | grep -c '^sys-\|^adm-\|^ev-') 条"
