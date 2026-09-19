#!/usr/bin/env bash
# 录空间成员 / 邀请 / API-Principal（波 2 第六批，17 条路由）的 golden。
#
# 场景设计（约定 §4 / §9；录制顺序影响状态，契约测试必须严格复刻本顺序）：
#   - 种子进测试租户 10002（不动真实租户 10000——跨租户用例只读探测 403/400）。
#   - 成员/邀请数据**幂等清理**：hard DELETE（含软删行）后重插 owner/viewer/contrib
#     三条基线成员行（joined_at 显式固定 → 列表顺序稳定）；邀请链接用 SQL 固定 token
#     种子行之外，共享链接一律经 API 创建（token 随机 → 掩码）。
#   - 直加 / 邀请 / leave 的 ErrLastOwner 用例都排在"第二个 Owner 出现之前"。
#   - accept-by-token 的 token 经 API 创建后从响应 invite_url 提取（录制期变量），
#     契约测试改用 H2 里固定 token 的种子行（响应不含 token，可比对）。
#   - API-Key 直加成员 / 拒绝 Owner：full-access Key 由 API 现场创建、录完即删
#     （api_key/token 不落 golden）。
#   - api-principal-config 会改写 tenants 10002 的 jsonb 列：录制前抓旧值、收尾还原
#     （dev PG 该列原本为 NULL）。
#   - 动态值：时间戳 / UUID / 邀请 id / JWT / invite_url 的 token 段由契约测试统一掩码
#     （本脚本存原始响应）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${MEM_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${MEM_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"

OWNER_ID="11111111-2222-3333-4444-555555555501"
VIEWER_ID="11111111-2222-3333-4444-555555555504"
CONTRIB_ID="11111111-2222-3333-4444-555555555505"
# 本批专用的三个一次性用户（不进基线成员行）
INVITEE_A="11111111-2222-3333-4444-555555555601"   # 直加 owner → leave → accept-by-token
INVITEE_B="11111111-2222-3333-4444-555555555602"   # 邀请收件箱主线
NEW_MEMBER="11111111-2222-3333-4444-555555555603"  # 直加/移除主线
UNKNOWN="11111111-2222-3333-4444-999999999999"

req() {
  local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "${AUTH}" "$@"
}
reqv() { local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "Authorization: Bearer ${VTOKEN}" "$@"
}
reqa() { local out="$1" method="$2" path="$3"; shift 3   # invitee A
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "Authorization: Bearer ${ATOKEN}" "$@"
}
reqb() { local out="$1" method="$2" path="$3"; shift 3   # invitee B
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "Authorization: Bearer ${BTOKEN}" "$@"
}
reqk() { local out="$1" method="$2" path="$3"; shift 3   # full-access API key
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "X-API-Key: ${APIKEY}" "$@"
}
reqn() { local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" "$@"
}

echo "==> 幂等清理：成员/邀请/一次性用户态/api_principal_config"
${PSQL} >/dev/null 2>&1 <<SQL
DELETE FROM tenant_invitations WHERE tenant_id = 10002 OR invitee_user_id IN ('${INVITEE_A}', '${INVITEE_B}', '${NEW_MEMBER}');
DELETE FROM tenant_members WHERE tenant_id = 10002 OR user_id IN ('${INVITEE_A}', '${INVITEE_B}', '${NEW_MEMBER}');
UPDATE tenants SET api_principal_config = NULL WHERE id = 10002;
UPDATE users SET tenant_id = 10002 WHERE id IN ('${INVITEE_A}', '${INVITEE_B}', '${NEW_MEMBER}');
SQL

echo "==> 种子：基线成员行（joined_at 显式固定）+ 一次性用户"
${PSQL} >/dev/null <<SQL
INSERT INTO tenant_members (user_id, tenant_id, role, status, joined_at) VALUES
('${OWNER_ID}',   10002, 'owner',       'active', '2026-09-01 10:00:00+00'),
('${VIEWER_ID}',  10002, 'viewer',      'active', '2026-09-01 10:01:00+00'),
('${CONTRIB_ID}', 10002, 'contributor', 'active', '2026-09-01 10:02:00+00');
INSERT INTO users (id, username, email, password_hash, tenant_id) VALUES
('${INVITEE_A}',   'mbinviteea', 'java-mb-invitee-a@weknora.test', '$(psql -q -t -h localhost -p 15432 -U postgres -d WeKnora -c "SELECT password_hash FROM users WHERE id='${OWNER_ID}';" | tr -d ' ')', 10002),
('${INVITEE_B}',   'mbinviteeb', 'java-mb-invitee-b@weknora.test', '$(psql -q -t -h localhost -p 15432 -U postgres -d WeKnora -c "SELECT password_hash FROM users WHERE id='${OWNER_ID}';" | tr -d ' ')', 10002),
('${NEW_MEMBER}',  'mbnewmember', 'java-mb-newmember@weknora.test', '$(psql -q -t -h localhost -p 15432 -U postgres -d WeKnora -c "SELECT password_hash FROM users WHERE id='${OWNER_ID}';" | tr -d ' ')', 10002)
ON CONFLICT (id) DO NOTHING;
SQL

echo "==> 准备：token"
AUTH="Authorization: Bearer $(login "${TEST_EMAIL}" "${PORT}")"
VTOKEN="$(login "${TEST_VIEWER_EMAIL}" "${PORT}")"
ATOKEN="$(login java-mb-invitee-a@weknora.test "${PORT}")"
BTOKEN="$(login java-mb-invitee-b@weknora.test "${PORT}")"

echo "==> 1) 成员列表与分页"
req mb-member-list.json        GET "/tenants/10002/members"
reqv mb-member-list-viewer.json GET "/tenants/10002/members"
req mb-member-list-q.json      GET "/tenants/10002/members?q=mbviewer"
req mb-member-list-qemail.json GET "/tenants/10002/members?q=JAVA-MB-CONTRIB"
req mb-member-list-qnone.json  GET "/tenants/10002/members?q=zzz-nomatch"
req mb-member-list-badpage.json GET "/tenants/10002/members?page=abc"
req mb-member-list-badsize.json GET "/tenants/10002/members?page_size=200"
req mb-member-list-page2.json  GET "/tenants/10002/members?page=2&page_size=2"
req mb-member-list-cross.json  GET "/tenants/10000/members"
req mb-member-list-badid.json  GET "/tenants/abc/members"

echo "==> 2) 直加校验（service 哨兵之前的 handler 分支）"
reqv mb-member-viewer-add.json POST "/tenants/10002/members" -H 'Content-Type: application/json' -d '{"email":"java-mb-newmember@weknora.test","role":"viewer"}'
req mb-member-add-badrole.json POST "/tenants/10002/members" -H 'Content-Type: application/json' -d '{"email":"java-mb-newmember@weknora.test","role":"bogus"}'
req mb-member-add-bademail.json POST "/tenants/10002/members" -H 'Content-Type: application/json' -d '{"email":"notanemail","role":"viewer"}'
req mb-member-add-missing.json POST "/tenants/10002/members" -H 'Content-Type: application/json' -d '{}'
req mb-member-add-unregistered.json POST "/tenants/10002/members" -H 'Content-Type: application/json' -d '{"email":"nobody@weknora.test","role":"viewer"}'

echo "==> 3) 最后一位 Owner（直改/直删，均在第二个 Owner 出现前）"
req mb-owner-demote-last.json PUT "/tenants/10002/members/${OWNER_ID}" -H 'Content-Type: application/json' -d '{"role":"viewer"}'
req mb-owner-remove-last.json DELETE "/tenants/10002/members/${OWNER_ID}"

echo "==> 4) 角色更新"
req mb-member-update.json PUT "/tenants/10002/members/${CONTRIB_ID}" -H 'Content-Type: application/json' -d '{"role":"admin"}'
req mb-member-update-same.json PUT "/tenants/10002/members/${CONTRIB_ID}" -H 'Content-Type: application/json' -d '{"role":"admin"}'
req mb-member-update-badrole.json PUT "/tenants/10002/members/${CONTRIB_ID}" -H 'Content-Type: application/json' -d '{"role":"bogus"}'
req mb-member-update-404.json PUT "/tenants/10002/members/${UNKNOWN}" -H 'Content-Type: application/json' -d '{"role":"viewer"}'
req mb-member-update-nobody.json PUT "/tenants/10002/members/${UNKNOWN}" -H 'Content-Type: application/json' -d '{}'

echo "==> 5) 直加成功 / 冲突 / 移除"
req mb-member-add.json POST "/tenants/10002/members" -H 'Content-Type: application/json' -d '{"email":"java-mb-newmember@weknora.test","role":"contributor"}'
req mb-member-add-dup.json POST "/tenants/10002/members" -H 'Content-Type: application/json' -d '{"email":"java-mb-newmember@weknora.test","role":"viewer"}'
req mb-member-remove.json DELETE "/tenants/10002/members/${NEW_MEMBER}"
req mb-member-remove-again.json DELETE "/tenants/10002/members/${NEW_MEMBER}"

echo "==> 6) 第二个 Owner 与 leave"
req mb-member-add-owner.json POST "/tenants/10002/members" -H 'Content-Type: application/json' -d "{\"email\":\"java-mb-invitee-a@weknora.test\",\"role\":\"owner\"}"
# B 的首个 token 是 tenantless 的（登录时无成员关系）→ /leave 落 TENANT_REQUIRED 409
reqb mb-leave-nonmember.json POST "/tenants/10002/leave"
reqa mb-leave.json POST "/tenants/10002/leave"
req mb-leave-last-owner.json POST "/tenants/10002/leave"
# A 的 token 已被 RemoveMember 的清理撤销（RevokeTokensByUserID）→ 后续用例前重登
# /leave 的 404 分支：成员行必须在"绕开 service"的前提下消失（service 路径会顺带吊销
# token，401 挡在 404 之前）——用 SQL 直删成员行模拟，B 重登后拿租户内 token 再 leave
req mb-member-add-b.json POST "/tenants/10002/members" -H 'Content-Type: application/json' -d "{\"email\":\"java-mb-invitee-b@weknora.test\",\"role\":\"contributor\"}"
BTOKEN="$(login java-mb-invitee-b@weknora.test "${PORT}")"
${PSQL} >/dev/null <<SQL
DELETE FROM tenant_members WHERE user_id = '${INVITEE_B}' AND tenant_id = 10002;
SQL
reqb mb-leave-not-member.json POST "/tenants/10002/leave"

echo "==> 7) API-Key 直加（manage_members：可加低角色、不可授 Owner）"
KEY_JSON="/tmp/mb-golden-key.json"
curl -s -o "${KEY_JSON}" -X POST "${API}/tenants/10002/api-keys" \
  -H "${AUTH}" -H 'Content-Type: application/json' \
  -d '{"name":"mb-golden-key","full_access":true}' >/dev/null
# 创建响应里 data.api_key 是 AES 密文、明文在 data.token
APIKEY="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["token"])' "${KEY_JSON}")"
KEY_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "${KEY_JSON}")"
rm -f "${KEY_JSON}"
reqk mb-member-apikey-owner.json POST "/tenants/10002/members" -H 'Content-Type: application/json' -d "{\"email\":\"java-mb-newmember@weknora.test\",\"role\":\"owner\"}"
# 注：API-Key 直加成功（201）的 golden 未录——Go 的 X-API-Key 通道经
# GetUserByTenantID 以"租户第一个用户"（=owner 501）为 caller，invited_by 落 501；
# Java 侧该读取口未接线（走合成用户兜底，§9 API Key 回补差异 #2）→ invited_by
# 会缺键。等 GetUserByTenantID 接线后补录本例。
reqk mb-member-apikey-add.json POST "/tenants/10002/members" -H 'Content-Type: application/json' -d "{\"email\":\"java-mb-newmember@weknora.test\",\"role\":\"viewer\"}"
rm -f "${OUT}/mb-member-apikey-add.json"
curl -s -o /dev/null -X DELETE "${API}/tenants/10002/api-keys/${KEY_ID}" -H "${AUTH}"

echo "==> 8) 邀请（空列表 → 创建 → 冲突族）"
req mb-inv-list-empty.json GET "/tenants/10002/invitations"
reqb mb-my-list-empty.json GET "/me/invitations"
req mb-inv-create.json POST "/tenants/10002/invitations" -H 'Content-Type: application/json' -d '{"email":"java-mb-invitee-b@weknora.test","role":"viewer","message":"welcome to mb golden"}'
req mb-inv-create-dup.json POST "/tenants/10002/invitations" -H 'Content-Type: application/json' -d '{"email":"java-mb-invitee-b@weknora.test","role":"viewer"}'
req mb-inv-create-already-member.json POST "/tenants/10002/invitations" -H 'Content-Type: application/json' -d '{"email":"java-phase1-contrib@weknora.test","role":"viewer"}'
req mb-inv-create-unregistered.json POST "/tenants/10002/invitations" -H 'Content-Type: application/json' -d '{"email":"nobody@weknora.test","role":"viewer"}'
req mb-inv-create-badrole.json POST "/tenants/10002/invitations" -H 'Content-Type: application/json' -d '{"email":"java-mb-invitee-b@weknora.test","role":"bogus"}'
req mb-inv-list.json GET "/tenants/10002/invitations"
reqv mb-inv-list-viewer.json GET "/tenants/10002/invitations"

echo "==> 9) 撤销（404 → 200 → 409 → 终态可见）"
INV_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "${OUT}/mb-inv-create.json")"
req mb-inv-revoke-404.json DELETE "/tenants/10002/invitations/999999"
req mb-inv-revoke.json DELETE "/tenants/10002/invitations/${INV_ID}"
req mb-inv-revoke-again.json DELETE "/tenants/10002/invitations/${INV_ID}"
req mb-inv-list-terminal.json GET "/tenants/10002/invitations?include_terminal=true"

echo "==> 10) 收件箱（计数 → 列表 → 接受 → 再接受 409）"
req mb-inv-create2.json POST "/tenants/10002/invitations" -H 'Content-Type: application/json' -d '{"email":"java-mb-invitee-b@weknora.test","role":"contributor"}'
INV_ID2="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "${OUT}/mb-inv-create2.json")"
# A 的旧 token 已被 leave 的清理吊销 → 重登（tenantless，/me 路由可用）
ATOKEN="$(login java-mb-invitee-a@weknora.test "${PORT}")"
# B 的 phase-6 token 是"有租户上下文但成员行已被 SQL 删掉"的形态 → auth 中间件 403。
# /me 收件箱要成功形态：重登 → 无成员关系时登录解析为 tenantless（/me 是 tenant-optional）。
# 注意不能 SQL 清零 users.tenant_id：fk_users_tenant 不认 0，且 GORM Updates 跳过零值、
# Go 的"清 home"根本不会持久化 0——解析是内存行为。
BTOKEN="$(login java-mb-invitee-b@weknora.test "${PORT}")"
# 非被邀请人（A）接受 B 的邀请 → 403 only-invitee；未知 id → 404
reqa mb-my-accept-wrong-user.json POST "/me/invitations/${INV_ID2}/accept"
reqa mb-my-accept-404.json POST "/me/invitations/999999/accept"
reqb mb-my-pending-count.json GET "/me/invitations/pending-count"
reqb mb-my-list.json GET "/me/invitations"
reqb mb-my-accept.json POST "/me/invitations/${INV_ID2}/accept"
reqb mb-my-accept-again.json POST "/me/invitations/${INV_ID2}/accept"
reqb mb-my-list-after.json GET "/me/invitations"
# B 已成为成员 → decline 主线换 A（A 留过所以不是成员，可再被邀请）
req mb-inv-create3.json POST "/tenants/10002/invitations" -H 'Content-Type: application/json' -d '{"email":"java-mb-invitee-a@weknora.test","role":"admin"}'
INV_ID3="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "${OUT}/mb-inv-create3.json")"
reqa mb-my-decline-404.json POST "/me/invitations/999999/decline"
reqa mb-my-decline.json POST "/me/invitations/${INV_ID3}/decline"
reqa mb-my-accept-declined.json POST "/me/invitations/${INV_ID3}/accept"

echo "==> 11) 共享邀请链接（invite_url / 角色差异 / token 接受 / 幂等）"
req mb-link-create.json POST "/tenants/10002/invite-links" -H 'Content-Type: application/json' -d '{"role":"contributor","message":"join us"}'
req mb-link-create-badrole.json POST "/tenants/10002/invite-links" -H 'Content-Type: application/json' -d '{"role":"bogus"}'
req mb-link-create-owner.json POST "/tenants/10002/invite-links" -H 'Content-Type: application/json' -d '{"role":"owner"}'
req mb-inv-list-withlinks.json GET "/tenants/10002/invitations"
reqv mb-inv-list-viewer-links.json GET "/tenants/10002/invitations"
LINK_TOKEN="$(python3 -c 'import json,sys,urllib.parse; u=json.load(open(sys.argv[1]))["data"]["invite_url"]; print(urllib.parse.parse_qs(urllib.parse.urlsplit(u).query)["token"][0])' "${OUT}/mb-link-create.json")"
reqa mb-my-accept-by-token.json POST "/me/invitations/accept-by-token" -H 'Content-Type: application/json' -d "{\"token\":\"${LINK_TOKEN}\"}"
reqa mb-my-accept-by-token-again.json POST "/me/invitations/accept-by-token" -H 'Content-Type: application/json' -d "{\"token\":\"${LINK_TOKEN}\"}"
req mb-inv-list-after-accept.json GET "/tenants/10002/invitations"
req mb-my-accept-by-token-bad.json POST "/me/invitations/accept-by-token" -H 'Content-Type: application/json' -d '{"token":"no-such-token"}'
req mb-my-accept-by-token-blank.json POST "/me/invitations/accept-by-token" -H 'Content-Type: application/json' -d '{"token":"   "}'
req mb-my-accept-by-token-empty.json POST "/me/invitations/accept-by-token" -H 'Content-Type: application/json' -d '{"token":""}'
req mb-my-accept-by-token-nobody.json POST "/me/invitations/accept-by-token" -H 'Content-Type: application/json' -d '{}'

echo "==> 12) api-principal-config（缺省归一 / 打码 / *** 占位 / 模式校验）"
APC_OLD="$(${PSQL} -c "SELECT COALESCE(api_principal_config::text,'NULL') FROM tenants WHERE id=10002;" | tr -d ' ')"
req mb-apc-get.json GET "/tenants/10002/api-principal-config"
reqv mb-apc-get-viewer.json GET "/tenants/10002/api-principal-config"
req mb-apc-put-badmode.json PUT "/tenants/10002/api-principal-config" -H 'Content-Type: application/json' -d '{"mode":"bogus"}'
req mb-apc-put-signed-nosecret.json PUT "/tenants/10002/api-principal-config" -H 'Content-Type: application/json' -d '{"mode":"signed_token"}'
req mb-apc-put-signed.json PUT "/tenants/10002/api-principal-config" -H 'Content-Type: application/json' -d '{"mode":"signed_token","hmac_secret":"mb-golden-hmac-secret-0123456789abcdef"}'
req mb-apc-get-after.json GET "/tenants/10002/api-principal-config"
req mb-apc-put-placeholder.json PUT "/tenants/10002/api-principal-config" -H 'Content-Type: application/json' -d '{"mode":"signed_token","hmac_secret":"***"}'
req mb-apc-put-null-secret.json PUT "/tenants/10002/api-principal-config" -H 'Content-Type: application/json' -d '{"mode":"signed_token","hmac_secret":null}'
req mb-apc-put-clear-secret.json PUT "/tenants/10002/api-principal-config" -H 'Content-Type: application/json' -d '{"mode":"signed_token","hmac_secret":"   "}'
req mb-apc-put-badjson.json PUT "/tenants/10002/api-principal-config" -H 'Content-Type: application/json' -d 'not-json'

echo "==> 13) api-principal-test-token（HS256 / TTL / external_user_id 校验）"
req mb-test-token.json POST "/tenants/10002/api-principal-test-token" -H 'Content-Type: application/json' -d '{"external_user_id":"ext-user-1"}'
req mb-test-token-ttl-max.json POST "/tenants/10002/api-principal-test-token" -H 'Content-Type: application/json' -d '{"external_user_id":"ext-user-2","expires_in_seconds":3600}'
req mb-test-token-ttl-default.json POST "/tenants/10002/api-principal-test-token" -H 'Content-Type: application/json' -d '{"external_user_id":"ext-user-3","expires_in_seconds":0}'
req mb-test-token-ttl-over.json POST "/tenants/10002/api-principal-test-token" -H 'Content-Type: application/json' -d '{"external_user_id":"ext-user-4","expires_in_seconds":3601}'
req mb-test-token-empty.json POST "/tenants/10002/api-principal-test-token" -H 'Content-Type: application/json' -d '{"external_user_id":""}'
req mb-test-token-long.json POST "/tenants/10002/api-principal-test-token" -H 'Content-Type: application/json' -d '{"external_user_id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}'
req mb-test-token-ctrl.json POST "/tenants/10002/api-principal-test-token" -H 'Content-Type: application/json' -d '{"external_user_id":"bad\u0007id"}'
req mb-apc-put-tenant.json PUT "/tenants/10002/api-principal-config" -H 'Content-Type: application/json' -d '{"mode":"tenant"}'
req mb-test-token-not-signed.json POST "/tenants/10002/api-principal-test-token" -H 'Content-Type: application/json' -d '{"external_user_id":"ext-user-5"}'

echo "==> 收尾：清空录制态 + 还原 api_principal_config（原值：${APC_OLD}）"
${PSQL} >/dev/null 2>&1 <<SQL
DELETE FROM tenant_invitations WHERE tenant_id = 10002 OR invitee_user_id IN ('${INVITEE_A}', '${INVITEE_B}', '${NEW_MEMBER}');
DELETE FROM tenant_members WHERE tenant_id = 10002 OR user_id IN ('${INVITEE_A}', '${INVITEE_B}', '${NEW_MEMBER}');
INSERT INTO tenant_members (user_id, tenant_id, role, status, joined_at) VALUES
('${OWNER_ID}',   10002, 'owner',       'active', '2026-09-01 10:00:00+00'),
('${VIEWER_ID}',  10002, 'viewer',      'active', '2026-09-01 10:01:00+00'),
('${CONTRIB_ID}', 10002, 'contributor', 'active', '2026-09-01 10:02:00+00');
UPDATE tenants SET api_principal_config = NULL WHERE id = 10002;
UPDATE users SET tenant_id = 10002 WHERE id IN ('${INVITEE_A}', '${INVITEE_B}', '${NEW_MEMBER}');
SQL

echo "==> 完成：mb-* golden 共 $(ls "${OUT}" | grep -c '^mb-') 条"
