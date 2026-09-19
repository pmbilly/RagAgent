#!/usr/bin/env bash
# 录波 3 browserskill 批的 golden：bs-*（/me/browser 账户面 + /me/browser/extension
# 下载 + /api/v1/local-browser 三条免 Auth 路由的鉴权失败族）。
#
# ⚠️ 两侧 server 必须带同一组 BROWSERSKILL_* env 启动（对照 Go NewManager 读 env）：
#   BROWSERSKILL_BINARY=/usr/bin/false          # Enabled=true 但 daemon 必然起不来
#                                               # → ext 握手落 503 "browser runtime unavailable"
#   BROWSERSKILL_CLUSTER_SECRET=bs-ab-cluster-secret-0123456789abcdef0123456789abcdef
#   BROWSERSKILL_EXTENSION_PATH=/tmp/weknora-bs-ext/browser-skill-weknora.zip
#   scripts/go-server-up.sh   /   scripts/java-server-up.sh
# 不带 env 的「禁用形态」（503 browser unavailable / 404 extension package is not
# configured / 404 unavailable）由 Java 侧 manager 单测钉住，报告说明。
#
# 掩码面（ab-bs.sh 与契约测试同掩码后逐字节）：
#   - pairing_link 的一次性 token（# 后 43 字符）
#   - device_id / device.id（32 hex，两侧各自随机）
#   - expires_at / renew_after / created_at / last_seen_at（两侧墙钟）
#
# 状态收敛：两侧共用 dev PG，脚本按录制序重放时状态自愈（revoke 收尾、
# pair 为 scope_key upsert、authorize 一次性票据用后即焚）。
#
# 用法：
#   scripts/record-bs-golden.sh
#   BS_TARGET_PORT=8082 BS_OUT_DIR=/tmp/x scripts/record-bs-golden.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${BS_OUT_DIR:-${SCRIPT_DIR}/server/src/test/resources/contracts}"
PORT="${BS_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"
mkdir -p "${OUT}"

ZIP_PATH=/tmp/weknora-bs-ext/browser-skill-weknora.zip
if [ ! -f "${ZIP_PATH}" ]; then
  mkdir -p "$(dirname "${ZIP_PATH}")"
  python3 - "${ZIP_PATH}" <<'PY'
import zipfile, sys
with zipfile.ZipFile(sys.argv[1], 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr(zipfile.ZipInfo('manifest.json', date_time=(2026, 1, 1, 0, 0, 0)),
               '{"name":"browser-skill-weknora","version":"1.0.0"}\n')
    z.writestr(zipfile.ZipInfo('background.js', date_time=(2026, 1, 1, 0, 0, 0)),
               '// browser skill extension stub\n')
PY
fi

req() { local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" "$@"; }
reqh() { # 下载：另存响应头（与 record-knowledge-golden.sh 同款）
  local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -D "${OUT}/${out}.headers" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "${AUTH}" "$@"; }

b64t() { python3 -c 'import base64,os; print(base64.urlsafe_b64encode(os.urandom(32)).decode().rstrip("="))'; }

EXT='Origin: chrome-extension://abcdefghijklmnopabcdefghijklmnop'
CT='Content-Type: application/json'

echo "==> 幂等清理：browser 授权行 + A/B 临时 API key"
${PSQL} >/dev/null 2>&1 <<'SQL'
DELETE FROM browser_task_interruptions;
DELETE FROM browser_pairings;
DELETE FROM browser_devices;
DELETE FROM tenant_api_keys WHERE name = 'bs-ab-key';
SQL

TOKEN="$(login "${TEST_EMAIL}" "${PORT}")"
[ -n "${TOKEN}" ] || { echo "FATAL: owner 登录失败"; exit 1; }
AUTH="Authorization: Bearer ${TOKEN}"

# ═══ 1) /me/browser 鉴权族（无状态） ═══
echo "==> 1) /me/browser 鉴权族"
req bs-account-unauth.json GET /me/browser
req bs-account-badtoken.json GET /me/browser -H "Authorization: Bearer garbage-token"

echo "==> 2) API-Key 直访（v1 组未声明路由 → default deny）"
KEY_JSON="$(mktemp)"
curl -s -o "${KEY_JSON}" -X POST "${API}/tenants/10002/api-keys" \
  -H "${AUTH}" -H "${CT}" -d '{"name":"bs-ab-key","full_access":true}' >/dev/null
APIKEY="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["token"])' "${KEY_JSON}")"
KEY_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "${KEY_JSON}")"
rm -f "${KEY_JSON}"
req bs-account-apikey-denied.json GET /me/browser -H "X-API-Key: ${APIKEY}"

# ═══ 3) GET 空形态 + POST 校验族 ═══
echo "==> 3) GET 空形态 + POST 校验族"
req bs-account-get.json GET /me/browser -H "${AUTH}"
req bs-account-post-badjson.json POST /me/browser -H "${AUTH}" -H "${CT}" -d 'not-json'
python3 -c 'print("{\"action\":\"pair\",\"origin\":\"" + "x"*4200 + "\"}")' > /tmp/bs-oversize.json
req bs-account-post-oversize.json POST /me/browser -H "${AUTH}" -H "${CT}" --data-binary @/tmp/bs-oversize.json
req bs-account-post-unknown.json POST /me/browser -H "${AUTH}" -H "${CT}" -d '{"action":"bogus"}'
req bs-pair-origin-noscheme.json POST /me/browser -H "${AUTH}" -H "${CT}" -d '{"action":"pair","origin":"example.com"}'
req bs-pair-origin-wss.json POST /me/browser -H "${AUTH}" -H "${CT}" -d '{"action":"pair","origin":"http://example.com"}'
req bs-pair-ok.json POST /me/browser -H "${AUTH}" -H "${CT}" -d '{"action":"pair","origin":"https://browser.example.com"}'
TOKEN1="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["pairing_link"].split("#")[1])' "${OUT}/bs-pair-ok.json")"

# ═══ 4) authorize 失败族 + 一次性票据兑换 ═══
echo "==> 4) authorize 失败族"
AUTHZ="${API}/local-browser/extension/authorize"
az() { local out="$1"; shift; curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' -X POST "${AUTHZ}" "$@"; }
az bs-authz-no-origin.json -H "Authorization: Bearer ${TOKEN1}" -H "${CT}" -d '{"action":"renew","next_token":"x"}'
az bs-authz-bad-origin.json -H 'Origin: https://example.com' -H "Authorization: Bearer ${TOKEN1}" -H "${CT}" -d '{}'
az bs-authz-no-token.json -H "${EXT}" -H "${CT}" -d '{}'
az bs-authz-bad-token.json -H "${EXT}" -H "Authorization: Bearer short" -H "${CT}" -d '{}'
az bs-authz-bad-body.json -H "${EXT}" -H "Authorization: Bearer ${TOKEN1}" -d 'not-json'
az bs-authz-bad-next.json -H "${EXT}" -H "Authorization: Bearer ${TOKEN1}" -H "${CT}" -d '{"action":"pair","next_token":"short"}'
az bs-authz-same-token.json -H "${EXT}" -H "Authorization: Bearer ${TOKEN1}" -H "${CT}" -d "{\"action\":\"pair\",\"next_token\":\"${TOKEN1}\"}"
az bs-authz-bad-action.json -H "${EXT}" -H "Authorization: Bearer ${TOKEN1}" -H "${CT}" -d "{\"action\":\"bogus\",\"next_token\":\"$(b64t)\"}"
LONG_LABEL="$(python3 -c 'print("L"*101)')"
az bs-authz-label-long.json -H "${EXT}" -H "Authorization: Bearer ${TOKEN1}" -H "${CT}" -d "{\"action\":\"pair\",\"next_token\":\"$(b64t)\",\"label\":\"${LONG_LABEL}\"}"
az bs-authz-renew-unpaired.json -H "${EXT}" -H "Authorization: Bearer ${TOKEN1}" -H "${CT}" -d "{\"action\":\"renew\",\"next_token\":\"$(b64t)\"}"
TOKEN2="$(b64t)"
az bs-authz-pair-ok.json -H "${EXT}" -H "Authorization: Bearer ${TOKEN1}" -H "${CT}" \
  -d "{\"action\":\"pair\",\"next_token\":\"${TOKEN2}\",\"label\":\"AB Device\"}"
az bs-authz-pair-reuse.json -H "${EXT}" -H "Authorization: Bearer ${TOKEN1}" -H "${CT}" \
  -d "{\"action\":\"pair\",\"next_token\":\"${TOKEN2}\",\"label\":\"AB Device\"}"

echo "==> 5) GET 配对后形态（device 出现）"
req bs-account-get-paired.json GET /me/browser -H "${AUTH}"

# ═══ 6) extension WS 握手失败族（免 Auth 路由） ═══
echo "==> 6) extension WS 握手失败族"
EXTURL="${API}/local-browser/extension"
ex() { local out="$1"; shift; curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' "${EXTURL}" "$@"; }
ex bs-ext-no-origin.json
ex bs-ext-bad-origin.json -H 'Origin: https://example.com'
ex bs-ext-no-proto.json -H "${EXT}"
ex bs-ext-bad-proto.json -H "${EXT}" -H 'Sec-WebSocket-Protocol: other-protocol'
ex bs-ext-bad-token-len.json -H "${EXT}" -H 'Sec-WebSocket-Protocol: bsk-auth.short'
ex bs-ext-unpaired.json -H "${EXT}" -H "Sec-WebSocket-Protocol: bsk-auth.$(b64t)"
ex bs-ext-runtime.json -H "${EXT}" -H "Sec-WebSocket-Protocol: bsk-auth.${TOKEN2}"

echo "==> 7) extension 下载（头 + 字节）"
req bs-download-unauth.json GET /me/browser/extension
reqh bs-download.bin GET /me/browser/extension

# ═══ 8) internal 签名族（HMAC 消息 = ts + "\n" + nonce + "\n" + body） ═══
echo "==> 8) internal 签名族"
SECRET="bs-ab-cluster-secret-0123456789abcdef0123456789abcdef"
INTURL="${API}/local-browser/internal"
BODY='{"node":"unknown-node","scope":{"tenant":10002,"user":"someone"},"session":"","operation":"status","method":""}'
sign() { python3 -c '
import hmac, hashlib, sys
secret, ts, nonce, body = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
print(hmac.new(secret.encode(), (ts + "\n" + nonce + "\n" + body).encode(), hashlib.sha256).hexdigest())
' "${SECRET}" "$1" "$2" "${BODY}"; }
TS="$(date +%s)"
NONCE="$(python3 -c 'import os; print(os.urandom(16).hex())')"
SIG="$(sign "${TS}" "${NONCE}")"
inn() { local out="$1"; shift; curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' -X POST "${INTURL}" "$@"; }
inn bs-internal-no-ts.json
inn bs-internal-stale-ts.json -H "X-Browser-Timestamp: $((TS - 120))" -H "X-Browser-Nonce: ${NONCE}" -H "X-Browser-Signature: ${SIG}" -d "${BODY}"
inn bs-internal-future-ts.json -H "X-Browser-Timestamp: $((TS + 120))" -H "X-Browser-Nonce: ${NONCE}" -H "X-Browser-Signature: ${SIG}" -d "${BODY}"
inn bs-internal-nonhex-sig.json -H "X-Browser-Timestamp: ${TS}" -H "X-Browser-Nonce: ${NONCE}" -H "X-Browser-Signature: zz${SIG}" -d "${BODY}"
inn bs-internal-bad-sig.json -H "X-Browser-Timestamp: ${TS}" -H "X-Browser-Nonce: ${NONCE}" -H "X-Browser-Signature: ${SIG%?}0" -d "${BODY}"
BAD_SIG="$(sign "${TS}" "short")"
inn bs-internal-bad-nonce.json -H "X-Browser-Timestamp: ${TS}" -H "X-Browser-Nonce: short" -H "X-Browser-Signature: ${BAD_SIG}" -d "${BODY}"
inn bs-internal-wrong-node.json -H "X-Browser-Timestamp: ${TS}" -H "X-Browser-Nonce: ${NONCE}" -H "X-Browser-Signature: ${SIG}" -d "${BODY}"
inn bs-internal-replay.json -H "X-Browser-Timestamp: ${TS}" -H "X-Browser-Nonce: ${NONCE}" -H "X-Browser-Signature: ${SIG}" -d "${BODY}"
python3 -c 'print("{\"pad\":\"" + "x"*1050000 + "\"}")' > /tmp/bs-big.json
inn bs-internal-oversize.json -H "X-Browser-Timestamp: ${TS}" -H "X-Browser-Nonce: ${NONCE}" -H "X-Browser-Signature: ${SIG}" --data-binary @/tmp/bs-big.json

# ═══ 9) revoke 收尾（状态收敛：device 行 revoked，pairing 清空） ═══
echo "==> 9) revoke 收尾"
req bs-revoke.json POST /me/browser -H "${AUTH}" -H "${CT}" -d '{"action":"revoke"}'
req bs-account-get-revoked.json GET /me/browser -H "${AUTH}"

rm -f /tmp/bs-oversize.json /tmp/bs-big.json
echo "==> 完成：golden 落在 ${OUT}"
