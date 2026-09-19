#!/usr/bin/env bash
# 录 auth 注册族（波 2 扫尾批 1）的 golden：reg-*。
#
# 覆盖端点（9 条）：
#   POST /auth/register  POST /auth/auto-setup  POST /auth/register-by-invite
#   POST /auth/invitations/lookup  GET /auth/config  GET /auth/validate
#   GET  /auth/me        PUT  /auth/me/preferences  POST /auth/change-password
#
# 场景设计（录制顺序影响状态，契约测试必须严格复刻本顺序）：
#   - 固定种子身份 regprobe-user / reg-probe@weknora.test（密码 Passw0rd1），
#     每次运行前 SQL 幂等清理（用户/其主租户/成员/令牌/存储后端/邀请链接）。
#   - register 建四表：tenants + storage_backends(env 默认) + users + tenant_members；
#     清理顺序：tenants.default_storage_backend_id 置 NULL → storage_backends →
#     auth_tokens → tenant_members → users → tenants（FK 顺序）。
#   - change-password 成功会吊销该用户全部 token → 之后再录「旧 token 已吊销」
#     与「新密码可登录 / 旧密码不可登录」。
#   - invite_only 分支：借系统管理员（javasysadmin，照 record-system-golden.sh
#     播种模式）PUT system_settings 切换，录完 DELETE 还原。
#   - 邀请链接：Owner 在租户 10002 建 message='reg-golden' 的分享链接，
#     token 从 invite_url 里抠出来（掩码比对，不落原文）。
#   - 动态值：uuid / 时间戳 / JWT / tenant_id 数字 / invite token 由契约测试
#     与 A/B 掩码统一处理。
#
# 用法：
#   scripts/record-reg-golden.sh                  # 录 Go（:8080）进 contracts/
#   REG_TARGET_PORT=8082 REG_OUT_DIR=/tmp/x scripts/record-reg-golden.sh  # A/B 重放 Java
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${REG_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${REG_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"
mkdir -p "${OUT}"

PROBE_USER="regprobe-user"
PROBE_EMAIL="reg-probe@weknora.test"
PROBE_PW="Passw0rd1"
PROBE_PW_NEW="Newpass123"
INVITE_EMAIL="reg-invite@weknora.test"
INVITE_USER="reginvite-user"
SYS_ID="11111111-2222-3333-4444-555555555701"
OWNER_ID="11111111-2222-3333-4444-555555555501"
# 4001 字符的偏好文案（限 4000）
LONG_PREF="$(python3 -c 'print("x"*4001)')"

req() { local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" "$@"; }

login_tok() {
  curl -s -X POST "${API}/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"${1}\",\"password\":\"${2}\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("token") or "")'
}

echo "==> 幂等清理：reg-* 用户及其主租户 / 邀请链接 / sysadmin 种子"
${PSQL} >/dev/null 2>&1 <<SQL
CREATE TEMP TABLE reg_tenants AS
  SELECT DISTINCT tenant_id FROM users
  WHERE email LIKE 'reg-%@weknora.test' AND tenant_id NOT IN (0, 10000, 10002);
UPDATE tenants SET default_storage_backend_id = NULL
  WHERE id IN (SELECT tenant_id FROM reg_tenants);
DELETE FROM storage_backends WHERE tenant_id IN (SELECT tenant_id FROM reg_tenants);
DELETE FROM auth_tokens WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'reg-%@weknora.test');
DELETE FROM tenant_members WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'reg-%@weknora.test');
DELETE FROM tenant_invitations WHERE tenant_id = 10002 AND message = 'reg-golden';
DELETE FROM users WHERE email LIKE 'reg-%@weknora.test';
DELETE FROM tenants WHERE id IN (SELECT tenant_id FROM reg_tenants);
DELETE FROM tenant_members WHERE user_id = '${SYS_ID}';
DELETE FROM users WHERE id = '${SYS_ID}';
SQL

echo "==> 种子：系统管理员（invite_only 切换用）"
${PSQL} >/dev/null <<SQL
INSERT INTO users (id, username, email, password_hash, tenant_id, is_system_admin) VALUES
('${SYS_ID}', 'javasysadmin', 'java-sys-admin@weknora.test',
 (SELECT password_hash FROM users WHERE id='${OWNER_ID}'), 10002, true);
INSERT INTO tenant_members (user_id, tenant_id, role, status, joined_at) VALUES
('${SYS_ID}', 10002, 'owner', 'active', '2026-09-01 10:03:00+00');
SQL

echo "==> 0) 公共读：/auth/config（默认 self_serve）"
req reg-config.json GET /auth/config

echo "==> 1) register 的 400 家族（binding → 空值 → 策略，顺序敏感）"
req reg-empty-body.json        POST /auth/register -H 'Content-Type: application/json'
req reg-missing-fields.json    POST /auth/register -H 'Content-Type: application/json' -d '{}'
req reg-bad-email.json         POST /auth/register -H 'Content-Type: application/json' -d '{"username":"someone-ok","email":"not-an-email","password":"Passw0rd1"}'
req reg-short-username.json    POST /auth/register -H 'Content-Type: application/json' -d '{"username":"a","email":"reg-x1@weknora.test","password":"Passw0rd1"}'
req reg-long-username.json     POST /auth/register -H 'Content-Type: application/json' -d '{"username":"abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxy","email":"reg-x2@weknora.test","password":"Passw0rd1"}'
req reg-short-password.json    POST /auth/register -H 'Content-Type: application/json' -d '{"username":"someone-ok","email":"reg-x3@weknora.test","password":"abc12"}'
req reg-weak-nodigit.json      POST /auth/register -H 'Content-Type: application/json' -d '{"username":"someone-ok","email":"reg-x4@weknora.test","password":"abcdefgh"}'
req reg-weak-noletter.json     POST /auth/register -H 'Content-Type: application/json' -d '{"username":"someone-ok","email":"reg-x5@weknora.test","password":"12345678"}'
req reg-long-password.json     POST /auth/register -H 'Content-Type: application/json' -d '{"username":"someone-ok","email":"reg-x6@weknora.test","password":"a123456789012345678901234567890123"}'

echo "==> 2) register 成功 + 重复身份"
req reg-success.json           POST /auth/register -H 'Content-Type: application/json' -d '{"username":"'"${PROBE_USER}"'","email":"'"${PROBE_EMAIL}"'","password":"'"${PROBE_PW}"'"}'
req reg-dup-email.json         POST /auth/register -H 'Content-Type: application/json' -d '{"username":"regprobe-other","email":"'"${PROBE_EMAIL}"'","password":"'"${PROBE_PW}"'"}'
req reg-dup-username.json      POST /auth/register -H 'Content-Type: application/json' -d '{"username":"'"${PROBE_USER}"'","email":"reg-other@weknora.test","password":"'"${PROBE_PW}"'"}'

echo "==> 3) 登录探针用户，录 validate / me / preferences"
PTOKEN="$(login_tok "${PROBE_EMAIL}" "${PROBE_PW}")"
[ -n "${PTOKEN}" ] || { echo "FATAL: 探针登录失败"; exit 1; }
req reg-validate-noheader.json GET /auth/validate
req reg-validate-badformat.json GET /auth/validate -H 'Authorization: Bearer'
req reg-validate-garbage.json  GET /auth/validate -H 'Authorization: Bearer garbage.token.here'
req reg-validate-ok.json       GET /auth/validate -H "Authorization: Bearer ${PTOKEN}"
req reg-me-ok.json             GET /auth/me -H "Authorization: Bearer ${PTOKEN}"
PROBE_TENANT="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["user"]["tenant_id"])' "${OUT}/reg-me-ok.json")"

req reg-prefs-empty-body.json  PUT /auth/me/preferences -H "Authorization: Bearer ${PTOKEN}" -H 'Content-Type: application/json'
req reg-prefs-set.json         PUT /auth/me/preferences -H "Authorization: Bearer ${PTOKEN}" -H 'Content-Type: application/json' -d '{"browser_search_instructions":"Custom search instructions"}'
req reg-prefs-too-long.json    PUT /auth/me/preferences -H "Authorization: Bearer ${PTOKEN}" -H 'Content-Type: application/json' -d '{"browser_search_instructions":"'"${LONG_PREF}"'"}'
req reg-prefs-default-clear.json PUT /auth/me/preferences -H "Authorization: Bearer ${PTOKEN}" -H 'Content-Type: application/json' -d '{"browser_search_instructions":"Default search engine: Bing.\nSearch URL: https://www.bing.com/search?q={query}"}'
req reg-prefs-tenant.json      PUT /auth/me/preferences -H "Authorization: Bearer ${PTOKEN}" -H 'Content-Type: application/json' -d '{"last_active_tenant_id":'"${PROBE_TENANT}"'}'
req reg-prefs-tenant-clear.json PUT /auth/me/preferences -H "Authorization: Bearer ${PTOKEN}" -H 'Content-Type: application/json' -d '{"last_active_tenant_id":0}'

echo "==> 4) change-password：错误家族 → 成功 → 旧 token 吊销 / 密码轮换"
req reg-chpw-binding.json      POST /auth/change-password -H "Authorization: Bearer ${PTOKEN}" -H 'Content-Type: application/json' -d '{}'
req reg-chpw-wrong-old.json    POST /auth/change-password -H "Authorization: Bearer ${PTOKEN}" -H 'Content-Type: application/json' -d '{"old_password":"WrongPass1","new_password":"'"${PROBE_PW_NEW}"'"}'
req reg-chpw-same.json         POST /auth/change-password -H "Authorization: Bearer ${PTOKEN}" -H 'Content-Type: application/json' -d '{"old_password":"'"${PROBE_PW}"'","new_password":"'"${PROBE_PW}"'"}'
req reg-chpw-weak.json         POST /auth/change-password -H "Authorization: Bearer ${PTOKEN}" -H 'Content-Type: application/json' -d '{"old_password":"'"${PROBE_PW}"'","new_password":"abcdefgh"}'
req reg-chpw-success.json      POST /auth/change-password -H "Authorization: Bearer ${PTOKEN}" -H 'Content-Type: application/json' -d '{"old_password":"'"${PROBE_PW}"'","new_password":"'"${PROBE_PW_NEW}"'"}'
req reg-validate-revoked.json  GET /auth/validate -H "Authorization: Bearer ${PTOKEN}"
req reg-relogin-old.json       POST /auth/login -H 'Content-Type: application/json' -d '{"email":"'"${PROBE_EMAIL}"'","password":"'"${PROBE_PW}"'"}'
req reg-relogin-new.json       POST /auth/login -H 'Content-Type: application/json' -d '{"email":"'"${PROBE_EMAIL}"'","password":"'"${PROBE_PW_NEW}"'"}'

echo "==> 5) auto-setup（standard edition → 403）"
req reg-auto-setup.json        POST /auth/auto-setup -H 'Content-Type: application/json' -d '{}'

echo "==> 6) 邀请族：lookup + register-by-invite"
req reg-inv-lookup-bad.json    POST /auth/invitations/lookup -H 'Content-Type: application/json' -d '{"token":"nosuchtoken123"}'
req reg-inv-lookup-empty.json  POST /auth/invitations/lookup -H 'Content-Type: application/json' -d '{}'
req reg-byinvite-badtoken.json POST /auth/register-by-invite -H 'Content-Type: application/json' -d '{"token":"nosuchtoken123","email":"'"${INVITE_EMAIL}"'","username":"'"${INVITE_USER}"'","password":"'"${PROBE_PW}"'"}'
req reg-byinvite-binding.json  POST /auth/register-by-invite -H 'Content-Type: application/json' -d '{}'

OTOKEN="$(login_tok "${TEST_EMAIL}" "${TEST_PASSWORD}")"
[ -n "${OTOKEN}" ] || { echo "FATAL: owner 登录失败"; exit 1; }
INVITE_JSON="/tmp/reg-invite-link.json"
curl -s -o "${INVITE_JSON}" -X POST "${API}/tenants/10002/invite-links" \
  -H "Authorization: Bearer ${OTOKEN}" -H 'Content-Type: application/json' \
  -d '{"role":"viewer","message":"reg-golden"}'
INV_TOKEN="$(python3 -c '
import json, re, sys
d = json.load(open(sys.argv[1]))["data"]
url = d.get("invite_url") or d.get("register_url") or ""
m = re.search(r"token=([A-Za-z0-9_\-]+)", url)
print(m.group(1) if m else "")' "${INVITE_JSON}")"
[ -n "${INV_TOKEN}" ] || { echo "FATAL: 邀请 token 提取失败"; cat "${INVITE_JSON}"; exit 1; }

req reg-inv-lookup-ok.json     POST /auth/invitations/lookup -H 'Content-Type: application/json' -d '{"token":"'"${INV_TOKEN}"'"}'
req reg-byinvite-existing-email.json POST /auth/register-by-invite -H 'Content-Type: application/json' -d '{"token":"'"${INV_TOKEN}"'","email":"'"${TEST_EMAIL}"'","username":"someone-ok","password":"'"${PROBE_PW}"'"}'
req reg-byinvite-weakpw.json   POST /auth/register-by-invite -H 'Content-Type: application/json' -d '{"token":"'"${INV_TOKEN}"'","email":"'"${INVITE_EMAIL}"'","username":"'"${INVITE_USER}"'","password":"abcdefgh"}'
req reg-byinvite-success.json  POST /auth/register-by-invite -H 'Content-Type: application/json' -d '{"token":"'"${INV_TOKEN}"'","email":"'"${INVITE_EMAIL}"'","username":"'"${INVITE_USER}"'","password":"'"${PROBE_PW}"'"}'

echo "==> 7) invite_only 模式（系统管理员切换 → 录 403 → 还原）"
ATOKEN="$(login_tok java-sys-admin@weknora.test "${TEST_PASSWORD}")"
[ -n "${ATOKEN}" ] || { echo "FATAL: sysadmin 登录失败"; exit 1; }
req reg-mode-set-invite-only.json PUT /system/admin/settings/auth.registration_mode \
  -H "Authorization: Bearer ${ATOKEN}" -H 'Content-Type: application/json' -d '{"value":"invite_only"}'
req reg-invite-only.json       POST /auth/register -H 'Content-Type: application/json' -d '{"username":"regblock-user","email":"reg-block@weknora.test","password":"'"${PROBE_PW}"'"}'
req reg-config-invite-only.json GET /auth/config
req reg-mode-restore.json      DELETE /system/admin/settings/auth.registration_mode -H "Authorization: Bearer ${ATOKEN}"
req reg-config-restored.json   GET /auth/config

echo "==> 收尾：清空录制态"
${PSQL} >/dev/null 2>&1 <<SQL
CREATE TEMP TABLE reg_tenants2 AS
  SELECT DISTINCT tenant_id FROM users
  WHERE email LIKE 'reg-%@weknora.test' AND tenant_id NOT IN (0, 10000, 10002);
UPDATE tenants SET default_storage_backend_id = NULL
  WHERE id IN (SELECT tenant_id FROM reg_tenants2);
DELETE FROM storage_backends WHERE tenant_id IN (SELECT tenant_id FROM reg_tenants2);
DELETE FROM auth_tokens WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'reg-%@weknora.test');
DELETE FROM tenant_members WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'reg-%@weknora.test');
DELETE FROM tenant_invitations WHERE tenant_id = 10002 AND message = 'reg-golden';
DELETE FROM users WHERE email LIKE 'reg-%@weknora.test';
DELETE FROM tenants WHERE id IN (SELECT tenant_id FROM reg_tenants2);
DELETE FROM tenant_members WHERE user_id = '${SYS_ID}';
DELETE FROM users WHERE id = '${SYS_ID}';
SQL

echo "==> 完成：reg-* golden 共 $(ls "${OUT}" | grep -c '^reg-') 条"
