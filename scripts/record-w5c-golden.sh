#!/usr/bin/env bash
# 录收尾批 W5c 的 golden：w5c-*（文件代理面 8 条路由，Go internal/router/files.go）。
#
#   GET  /files                              租户级存储代理（/api/v1 之外）
#   GET+HEAD /r/:token                       capability URL（资源授权短时 URL）
#   GET+HEAD /api/v1/files/presigned         IM 签名 URL
#   GET  /api/v1/files/presigned-preview     Admin 诊断
#   GET  /api/v1/knowledge-bases/:id/files   KB 图片代理（scoped）
#   GET  /api/v1/sessions/:id/messages/:mid/files  消息图片代理（scoped）
#
# ⚠️ 两侧 server 必须带同一 SYSTEM_AES_KEY（≥16 字节）启动，否则 presigned 的
#   有效签名分支不可达（Go getPresignKey=nil → 恒 403）：
#   SYSTEM_AES_KEY=w5c-ab-aes-key-0123456789abcdef \
#     scripts/go-server-up.sh   /   scripts/java-server-up.sh
# 本脚本自身导出同一把 key 供签名计算。
#
# 种子（双端共享：同一 dev PG + 同一 LOCAL_STORAGE_BASE_DIR）：
#   - 6 个已知字节的种子文件（local 后端直接落 10002/exports/）
#   - resources 行（handle 固定 22 字符）+ /r/ 授权行三枚（有效/过期/撤销）
#   - KB×2（有绑定/无绑定）+ knowledge 行 + resource_bindings 行
#   - session 行（固定 id）+ messages×4（agent_tenant 0/10002 两态 + 无引用 + resource 引用）
#
# 二进制/头部锚：*.bin + *.bin.headers（与 bs-download 同款）。
#
# 用法：
#   scripts/record-w5c-golden.sh
#   W5C_TARGET_PORT=18082 W5C_OUT_DIR=/tmp/x scripts/record-w5c-golden.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${W5C_OUT_DIR:-${SCRIPT_DIR}/server/src/test/resources/contracts}"
PORT="${W5C_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"
mkdir -p "${OUT}"

# presigned 签名密钥（两侧一致；dev-env 允许 env 覆盖）
export SYSTEM_AES_KEY="${SYSTEM_AES_KEY:-w5c-ab-aes-key-0123456789abcdef}"
AES_KEY="${SYSTEM_AES_KEY}"

SEED_DIR="${LOCAL_STORAGE_BASE_DIR:-/tmp/weknora-java-files}/10002/exports"
KB_MAIN="5c000000-0000-0000-0000-000000000000"
KB_NOBIND="5c000000-0000-0000-0000-000000000099"
KNOW_ID="5c000001-0000-0000-0000-000000000001"
RES_ID="5c00000a-0000-0000-0000-00000000000a"
RES_HANDLE="w5cresourcehandle00001"          # 22 字符 [A-Za-z0-9_-]
BIND_ID="5c00000c-0000-0000-0000-00000000000c"
SES_ID="5c000004-0000-0000-0000-000000000004"
MSG_REF="5c000002-0000-0000-0000-000000000002"    # agent_tenant=10002，content 引用 seed
MSG_NOREF="5c000002-0000-0000-0000-000000000003"  # agent_tenant=0，无引用
MSG_RESREF="5c000002-0000-0000-0000-000000000004" # agent_tenant=0，引用 resource://
MSG_USER="5c000002-0000-0000-0000-000000000005"   # role=user，agent_tenant=10002
GRANT_TOKEN="w5cgranttoken0000000001"
OWNER_UID="11111111-2222-3333-4444-555555555501"

echo "==> 1) 种子文件（已知字节，local 后端直接落目录）"
mkdir -p "${SEED_DIR}"
printf 'w5c seed payload line1\nline2 bytes 12345\n' > "${SEED_DIR}/w5c-seed.txt"
printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\n' > "${SEED_DIR}/w5c-image.png"
printf '<html><body>w5c active content</body></html>\n' > "${SEED_DIR}/w5c-page.html"
printf '%%PDF-1.4 w5c pdf bytes\n%%%%EOF\n' > "${SEED_DIR}/w5c-report.pdf"
printf 'w5c unknown ext bytes\n' > "${SEED_DIR}/w5c-blob.xyz"
printf 'w5c CJK filename content\n' > "${SEED_DIR}/w5c-数据.txt"

echo "==> 2) DB 种子（resources / grants / KB / knowledge / binding / session / messages）"
LOC_HASH="$(python3 -c "import hashlib; print(hashlib.sha256(b'local://10002/exports/w5c-seed.txt').hexdigest())")"
TOK_HASH="$(python3 -c "import hashlib; print(hashlib.sha256(b'${GRANT_TOKEN}').hexdigest())")"
EXP_HASH="$(python3 -c "import hashlib; print(hashlib.sha256(b'w5cexpiredtoken000000001').hexdigest())")"
REV_HASH="$(python3 -c "import hashlib; print(hashlib.sha256(b'w5crevokedtoken000000001').hexdigest())")"

${PSQL} >/dev/null <<SQL
DELETE FROM resource_access_grants WHERE token_hash IN ('${TOK_HASH}', '${EXP_HASH}', '${REV_HASH}');
DELETE FROM resource_bindings WHERE id = '${BIND_ID}';
DELETE FROM resource_bindings WHERE resource_id = '${RES_ID}' AND owner_type = 'knowledge';
DELETE FROM resources WHERE handle = '${RES_HANDLE}';
DELETE FROM knowledges WHERE id = '${KNOW_ID}';
DELETE FROM knowledge_bases WHERE id IN ('${KB_MAIN}', '${KB_NOBIND}');
DELETE FROM messages WHERE id IN ('${MSG_REF}', '${MSG_NOREF}', '${MSG_RESREF}', '${MSG_USER}');
DELETE FROM sessions WHERE id = '${SES_ID}';

INSERT INTO resources (id, handle, tenant_id, storage_backend_id, provider, physical_path,
                       location_hash, kind, mime_type, original_name, size, content_hash, lifecycle, state)
VALUES ('${RES_ID}', '${RES_HANDLE}', 10002, NULL, 'local',
        'local://10002/exports/w5c-seed.txt', '${LOC_HASH}',
        'file', 'text/plain; charset=utf-8', 'w5c-resource.txt', 41, '', 'persistent', 'active');

INSERT INTO resource_access_grants (id, token_hash, resource_id, access_scope, expires_at, revoked_at)
VALUES ('5c00000b-0000-0000-0000-00000000000b', '${TOK_HASH}', '${RES_ID}', 'read', NOW() + INTERVAL '365 days', NULL),
       ('5c00000b-0000-0000-0000-00000000000e', '${EXP_HASH}', '${RES_ID}', 'read', NOW() - INTERVAL '1 hour', NULL),
       ('5c00000b-0000-0000-0000-00000000000f', '${REV_HASH}', '${RES_ID}', 'read', NOW() + INTERVAL '365 days', NOW());

INSERT INTO knowledge_bases (id, name, tenant_id, description, creator_id, embedding_model_id, summary_model_id)
VALUES ('${KB_MAIN}', 'w5c KB', 10002, 'w5c file proxy', '${OWNER_UID}', '', ''),
       ('${KB_NOBIND}', 'w5c KB unbound', 10002, 'w5c no binding', '${OWNER_UID}', '', '');

INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, source)
VALUES ('${KNOW_ID}', 10002, '${KB_MAIN}', 'document', 'w5c doc', 'file');

INSERT INTO resource_bindings (id, resource_id, tenant_id, owner_type, owner_id, relation)
VALUES ('${BIND_ID}', '${RES_ID}', 10002, 'knowledge', '${KNOW_ID}', 'source_file');

INSERT INTO sessions (id, tenant_id, user_id, title)
VALUES ('${SES_ID}', 10002, '${OWNER_UID}', 'w5c files session');

INSERT INTO messages (id, request_id, session_id, role, content, is_completed, agent_tenant_id)
VALUES ('${MSG_REF}', '5c000003-0000-0000-0000-000000000001', '${SES_ID}', 'assistant',
        '图片见 local://10002/exports/w5c-image.png', true, 10002),
       ('${MSG_NOREF}', '5c000003-0000-0000-0000-000000000002', '${SES_ID}', 'assistant',
        '没有引用的回复', true, 0),
       ('${MSG_RESREF}', '5c000003-0000-0000-0000-000000000003', '${SES_ID}', 'assistant',
        '引用 resource://${RES_HANDLE} 图片', true, 0),
       ('${MSG_USER}', '5c000003-0000-0000-0000-000000000004', '${SES_ID}', 'user',
        '用户提问 local://10002/exports/w5c-seed.txt', true, 10002);
SQL

${PSQL} >/dev/null <<'SQL'
DELETE FROM tenant_api_keys WHERE name IN ('w5c-full', 'w5c-retrieve', 'w5c-kbrestricted');
SQL

echo "==> 3) 登录 + API-Key 种子"
TOKEN="$(login "${TEST_EMAIL}" "${PORT}")"
VTOKEN="$(login "${TEST_VIEWER_EMAIL}" "${PORT}")"
[ -n "${TOKEN}" ] || { echo "FATAL: owner 登录失败"; exit 1; }
AUTH="Authorization: Bearer ${TOKEN}"
VAUTH="Authorization: Bearer ${VTOKEN}"

KEY_JSON="$(mktemp)"
curl -s -o "${KEY_JSON}" -X POST "${API}/api/v1/tenants/10002/api-keys" \
  -H "${AUTH}" -H 'Content-Type: application/json' -d '{"name":"w5c-full","full_access":true}' >/dev/null
FULLKEY="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["token"])' "${KEY_JSON}" 2>/dev/null || true)"
curl -s -o "${KEY_JSON}" -X POST "${API}/api/v1/tenants/10002/api-keys" \
  -H "${AUTH}" -H 'Content-Type: application/json' -d '{"name":"w5c-retrieve","capabilities":["retrieve"]}' >/dev/null
RETRKEY="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["token"])' "${KEY_JSON}" 2>/dev/null || true)"
curl -s -o "${KEY_JSON}" -X POST "${API}/api/v1/tenants/10002/api-keys" \
  -H "${AUTH}" -H 'Content-Type: application/json' \
  -d '{"name":"w5c-kbrestricted","capabilities":["retrieve"],"knowledge_base_ids":["5c000000-0000-0000-0000-000000000000"]}' >/dev/null
KBKEY="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["token"])' "${KEY_JSON}" 2>/dev/null || true)"
rm -f "${KEY_JSON}"

# ── 请求辅助 ────────────────────────────────────────────────────────────────
req() { # req <name> <method> <path> [curl opts...]
  local out="$1" method="$2" path="$3"; shift 3
  if [ "${method}" = "HEAD" ]; then
    # curl -X HEAD 会等待永不到来的 body → 用 -I
    curl -s -I -o /dev/null -D "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
      "${API}${path}" "$@"
    return
  fi
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" "$@"; }
reqb() { # 二进制：body + headers 双锚
  local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}.bin" -D "${OUT}/${out}.bin.headers" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" "$@"; }

sig_for() { # sig_for <path> <tenant> [ttl] → echo "expires sig"
  python3 - "$1" "$2" "${3:-7200}" "$AES_KEY" <<'PY'
import hmac, hashlib, sys, time
path, tenant, ttl, key = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4].encode()
exp = int(time.time()) + ttl
payload = f"file_path={path}&tenant_id={tenant}&expires={exp}"
print(exp, hmac.new(key, payload.encode(), hashlib.sha256).hexdigest())
PY
}

TXT='local://10002/exports/w5c-seed.txt'
TXT_ENC='local%3A%2F%2F10002%2Fexports%2Fw5c-seed.txt'
PNG_ENC='local%3A%2F%2F10002%2Fexports%2Fw5c-image.png'
HTML_ENC='local%3A%2F%2F10002%2Fexports%2Fw5c-page.html'
PDF_ENC='local%3A%2F%2F10002%2Fexports%2Fw5c-report.pdf'
CJK_ENC='local%3A%2F%2F10002%2Fexports%2Fw5c-%E6%95%B0%E6%8D%AE.txt'
XYZ_ENC='local%3A%2F%2F10002%2Fexports%2Fw5c-blob.xyz'
LOCAL_BACKEND="c730730a-70f5-4d86-a7e1-58972cf27567"

echo "==> 4) GET /files（租户级存储代理）"
req w5c-files-noauth.json GET "/files?file_path=${TXT_ENC}"
req w5c-files-missing-param.json GET "/files" -H "${AUTH}"
req w5c-files-traversal.json GET "/files?file_path=local%3A%2F%2F..%2F..%2Fetc%2Fpasswd" -H "${AUTH}"
req w5c-files-missing-file GET "/files?file_path=local%3A%2F%2F10002%2Fexports%2Fno-such.png" -H "${AUTH}"
reqb w5c-files-txt GET "/files?file_path=${TXT_ENC}" -H "${AUTH}"
reqb w5c-files-png GET "/files?file_path=${PNG_ENC}" -H "${AUTH}"
reqb w5c-files-html GET "/files?file_path=${HTML_ENC}" -H "${AUTH}"
reqb w5c-files-pdf GET "/files?file_path=${PDF_ENC}" -H "${AUTH}"
reqb w5c-files-cjk GET "/files?file_path=${CJK_ENC}" -H "${AUTH}"
reqb w5c-files-unknownext GET "/files?file_path=${XYZ_ENC}" -H "${AUTH}"
req w5c-files-crosstenant.json GET "/files?file_path=local%3A%2F%2F77%2Fexports%2Fx.png" -H "${AUTH}"
req w5c-files-head.hdr HEAD "/files?file_path=${TXT_ENC}" -H "${AUTH}"
req w5c-files-storage-mismatch GET "/files?file_path=storage%3A%2F%2F00000000-0000-0000-0000-000000000009%2Flocal%3A%2F%2F10002%2Fexports%2Fw5c-seed.txt" -H "${AUTH}"
reqb w5c-files-storage-ok GET "/files?file_path=storage%3A%2F%2F${LOCAL_BACKEND}%2Flocal%3A%2F%2F10002%2Fexports%2Fw5c-seed.txt" -H "${AUTH}"
if [ -n "${FULLKEY}" ]; then
  reqb w5c-files-key-full GET "/files?file_path=${TXT_ENC}" -H "X-API-Key: ${FULLKEY}"
  reqb w5c-files-key-retrieve GET "/files?file_path=${TXT_ENC}" -H "X-API-Key: ${RETRKEY}"
  req w5c-files-key-kbrestricted.json GET "/files?file_path=${TXT_ENC}" -H "X-API-Key: ${KBKEY}"
fi

echo "==> 5) Range / 条件请求（ServeContent 契约）"
reqb w5c-range-simple GET "/files?file_path=${TXT_ENC}" -H "${AUTH}" -H 'Range: bytes=4-11'
reqb w5c-range-suffix GET "/files?file_path=${TXT_ENC}" -H "${AUTH}" -H 'Range: bytes=-6'
req w5c-range-nooverlap.json GET "/files?file_path=${TXT_ENC}" -H "${AUTH}" -H 'Range: bytes=100-'
reqb w5c-ifnonematch GET "/files?file_path=${TXT_ENC}" -H "${AUTH}" -H 'If-None-Match: "x"'

echo "==> 6) GET+HEAD /api/v1/files/presigned（IM 签名 URL）"
read -r EXP SIG <<< "$(sig_for "${TXT}" 10002)"
read -r EXPP SIGP <<< "$(sig_for "local://10002/exports/w5c-image.png" 10002)"
read -r EXPH SIGH <<< "$(sig_for "local://10002/exports/w5c-page.html" 10002)"
read -r EXP2 SIG2 <<< "$(sig_for "local://10002/exports/no-such.png" 10002)"
read -r EXP3 SIG3 <<< "$(sig_for "local://10002/exports/x.png" 99999999)"
reqb w5c-pres-txt GET "/api/v1/files/presigned?file_path=${TXT_ENC}&tenant_id=10002&expires=${EXP}&sig=${SIG}"
reqb w5c-pres-png GET "/api/v1/files/presigned?file_path=${PNG_ENC}&tenant_id=10002&expires=${EXPP}&sig=${SIGP}"
reqb w5c-pres-html GET "/api/v1/files/presigned?file_path=${HTML_ENC}&tenant_id=10002&expires=${EXPH}&sig=${SIGH}"
req w5c-pres-head.hdr HEAD "/api/v1/files/presigned?file_path=${TXT_ENC}&tenant_id=10002&expires=${EXP}&sig=${SIG}"
# 签名与路径绑定：A 文件的有效签名取 B 文件 → 403（防篡改语义）
req w5c-pres-wrongpath.json GET "/api/v1/files/presigned?file_path=${PNG_ENC}&tenant_id=10002&expires=${EXP}&sig=${SIG}"
req w5c-pres-badsig.json GET "/api/v1/files/presigned?file_path=${TXT_ENC}&tenant_id=10002&expires=9999999999&sig=deadbeef"
req w5c-pres-missing-params.json GET "/api/v1/files/presigned"
req w5c-pres-bad-tenant.json GET "/api/v1/files/presigned?file_path=x&tenant_id=abc&expires=9999999999&sig=ab"
req w5c-pres-traversal.json GET "/api/v1/files/presigned?file_path=local%3A%2F%2F..%2Fx&tenant_id=10002&expires=9999999999&sig=ab"
req w5c-pres-missing-file GET "/api/v1/files/presigned?file_path=local%3A%2F%2F10002%2Fexports%2Fno-such.png&tenant_id=10002&expires=${EXP2}&sig=${SIG2}"
req w5c-pres-tenant-missing GET "/api/v1/files/presigned?file_path=local%3A%2F%2F10002%2Fexports%2Fx.png&tenant_id=99999999&expires=${EXP3}&sig=${SIG3}"

echo "==> 7) GET /api/v1/files/presigned-preview（Admin 诊断）"
req w5c-prev-ok.json GET "/api/v1/files/presigned-preview?file_path=${TXT_ENC}" -H "${AUTH}"
req w5c-prev-cjk.json GET "/api/v1/files/presigned-preview?file_path=${CJK_ENC}" -H "${AUTH}"
req w5c-prev-minio.json GET "/api/v1/files/presigned-preview?file_path=minio%3A%2F%2Fbucket%2Fx.png" -H "${AUTH}"
req w5c-prev-missing-param.json GET "/api/v1/files/presigned-preview" -H "${AUTH}"
req w5c-prev-unauth.json GET "/api/v1/files/presigned-preview?file_path=x"
req w5c-prev-viewer.json GET "/api/v1/files/presigned-preview?file_path=x" -H "${VAUTH}"
req w5c-prev-backend-missing.json GET "/api/v1/files/presigned-preview?file_path=storage%3A%2F%2F00000000-0000-0000-0000-000000000009%2Flocal%3A%2F%2F10002%2Fx" -H "${AUTH}"
req w5c-prev-head.hdr HEAD "/api/v1/files/presigned-preview?file_path=x" -H "${AUTH}"
if [ -n "${FULLKEY}" ]; then
  req w5c-prev-apikey.json GET "/api/v1/files/presigned-preview?file_path=${TXT_ENC}" -H "X-API-Key: ${FULLKEY}"
fi

echo "==> 8) GET+HEAD /r/:token（capability URL）"
reqb w5c-grant-ok GET "/r/${GRANT_TOKEN}"
req w5c-grant-head.hdr HEAD "/r/${GRANT_TOKEN}"
req w5c-grant-unknown GET "/r/unknown00000000000000000"
req w5c-grant-expired GET "/r/w5cexpiredtoken000000001"
req w5c-grant-revoked GET "/r/w5crevokedtoken000000001"

echo "==> 9) GET /api/v1/knowledge-bases/:id/files（KB 图片代理）"
reqb w5c-kbfiles-ok GET "/api/v1/knowledge-bases/${KB_MAIN}/files?file_path=${TXT_ENC}" -H "${AUTH}"
reqb w5c-kbfiles-viewer GET "/api/v1/knowledge-bases/${KB_MAIN}/files?file_path=${TXT_ENC}" -H "${VAUTH}"
req w5c-kbfiles-unbound.json GET "/api/v1/knowledge-bases/${KB_NOBIND}/files?file_path=${TXT_ENC}" -H "${AUTH}"
req w5c-kbfiles-outside-exports.json GET "/api/v1/knowledge-bases/${KB_MAIN}/files?file_path=local%3A%2F%2F10002%2Fprivate%2Fsecret.txt" -H "${AUTH}"
req w5c-kbfiles-missing-kb.json GET "/api/v1/knowledge-bases/00000000-4444-0000-0000-000000000001/files?file_path=${TXT_ENC}" -H "${AUTH}"
req w5c-kbfiles-missing-param.json GET "/api/v1/knowledge-bases/${KB_MAIN}/files" -H "${AUTH}"
req w5c-kbfiles-missing-file GET "/api/v1/knowledge-bases/${KB_MAIN}/files?file_path=local%3A%2F%2F10002%2Fexports%2Fno-such.png" -H "${AUTH}"
req w5c-kbfiles-head.hdr HEAD "/api/v1/knowledge-bases/${KB_MAIN}/files?file_path=${TXT_ENC}" -H "${AUTH}"
if [ -n "${KBKEY}" ]; then
  req w5c-kbfiles-key-kbrestricted.json GET "/api/v1/knowledge-bases/${KB_MAIN}/files?file_path=${TXT_ENC}" -H "X-API-Key: ${KBKEY}"
fi

echo "==> 10) GET /api/v1/sessions/:id/messages/:mid/files（消息图片代理）"
reqb w5c-msgfiles-ok GET "/api/v1/sessions/${SES_ID}/messages/${MSG_REF}/files?file_path=${PNG_ENC}" -H "${AUTH}"
reqb w5c-msgfiles-user GET "/api/v1/sessions/${SES_ID}/messages/${MSG_USER}/files?file_path=${TXT_ENC}" -H "${AUTH}"
reqb w5c-msgfiles-resource GET "/api/v1/sessions/${SES_ID}/messages/${MSG_RESREF}/files?file_path=resource%3A%2F%2F${RES_HANDLE}" -H "${AUTH}"
req w5c-msgfiles-notreferenced.json GET "/api/v1/sessions/${SES_ID}/messages/${MSG_NOREF}/files?file_path=${PNG_ENC}" -H "${AUTH}"
req w5c-msgfiles-zerotenant.json GET "/api/v1/sessions/${SES_ID}/messages/${MSG_NOREF}/files?file_path=${TXT_ENC}" -H "${AUTH}"
req w5c-msgfiles-missing-message GET "/api/v1/sessions/${SES_ID}/messages/00000000-0000-0000-0000-000000000099/files?file_path=${PNG_ENC}" -H "${AUTH}"
req w5c-msgfiles-missing-session GET "/api/v1/sessions/00000000-0000-0000-0000-000000000098/messages/${MSG_REF}/files?file_path=${PNG_ENC}" -H "${AUTH}"
req w5c-msgfiles-missing-param.json GET "/api/v1/sessions/${SES_ID}/messages/${MSG_REF}/files" -H "${AUTH}"
req w5c-msgfiles-head.hdr HEAD "/api/v1/sessions/${SES_ID}/messages/${MSG_REF}/files?file_path=${PNG_ENC}" -H "${AUTH}"

echo "==> 清理：A/B 临时 API key"
${PSQL} >/dev/null <<'SQL'
DELETE FROM tenant_api_keys WHERE name IN ('w5c-full', 'w5c-retrieve', 'w5c-kbrestricted');
SQL

echo "==> 完成：$(ls "${OUT}" | grep -c '^w5c-') 个 w5c-* golden → ${OUT}"
