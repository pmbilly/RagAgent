#!/usr/bin/env bash
# 录 auth OIDC 族（波 2 扫尾批 2，4 条路由）的 golden：oidc-*。
#
# 覆盖端点（routes_auth_tenant.go L220-224，均无鉴权）：
#   GET /auth/oidc/config    GET /auth/oidc/url
#   GET /auth/oidc/start     GET /auth/oidc/callback
#
# golden 形态约定：
#   - JSON 端点（config / url / start 的出错分支）：响应体原样落盘（.json）。
#   - 302 端点（callback 全部分支）：落**合成信封 JSON**（重定向端点的 golden 约定），
#     键字母序：{"body":..., "location":..., "set_cookie":...|null, "status":302}。
#     body 是 gin Redirect 的 HTML 字节（`<a href="...">Found</a>.\n\n`，
#     href 内 Location 经 Go html.EscapeString 转义）；set_cookie 取
#     weknora_oidc_nonce 那条 Set-Cookie 原文，无则 null。
#
# state 锻造：state = b64url_nopad(json).b64url_nopad(hmac-sha256)，密钥 = JWT_SECRET
# （与 Go oidc_state.go / Java OidcStateCodec 共用）。iat 用当前秒；expired 场景用
# now-3600。nonce 自造并经 Cookie: weknora_oidc_nonce=<nonce> 配对。
#
# 动态值：callback 各分支响应内不含 state/nonce/iat（只有固定的 oidc_error 令牌
# 与定制 escaper 产物），故 golden 无掩码项。
#
# 用法：
#   scripts/record-oidc-golden.sh                     # 录 Go（:8080）进 contracts/
#   OIDC_TARGET_PORT=8082 OIDC_OUT_DIR=/tmp/x scripts/record-oidc-golden.sh  # A/B 重放 Java
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${OIDC_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${OIDC_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
mkdir -p "${OUT}"

# 普通 JSON 端点
req() { local out="$1" path="$2"; shift 2
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    "${API}${path}" "$@"; }

# 302 端点 → 合成信封
req302() { local out="$1" path="$2"; shift 2
  local headers body code
  headers="$(mktemp)"; body="$(mktemp)"
  code="$(curl -s -D "${headers}" -o "${body}" -w '%{http_code}' "${API}${path}" "$@")"
  python3 - "$code" "$headers" "$body" > "${OUT}/${out}" <<'PY'
import json, sys
code, hpath, bpath = sys.argv[1], sys.argv[2], sys.argv[3]
location, set_cookie = None, None
with open(hpath, "rb") as f:
    for line in f.read().decode("latin-1").split("\r\n"):
        low = line.lower()
        if low.startswith("location:"):
            location = line.split(":", 1)[1].strip()
        elif low.startswith("set-cookie:"):
            v = line.split(":", 1)[1].strip()
            if v.startswith("weknora_oidc_nonce="):
                set_cookie = v
with open(bpath, "rb") as f:
    body = f.read().decode("utf-8")
env = {"body": body, "location": location, "set_cookie": set_cookie, "status": int(code)}
print(json.dumps(env, sort_keys=True, separators=(",", ":"), ensure_ascii=False))
PY
  rm -f "${headers}" "${body}"
  echo "${code} ${path} -> ${out}"; }

# 锻造合法 state（密钥 = 共用 JWT_SECRET）
forge_state() { # nonce redirect_uri iat
  JWT_SECRET="${JWT_SECRET}" python3 - "$1" "$2" "$3" <<'PY'
import base64, hashlib, hmac, json, os, sys
nonce, redirect, iat = sys.argv[1], sys.argv[2], int(sys.argv[3])
def b64u(b): return base64.urlsafe_b64encode(b).rstrip(b"=").decode()
raw = json.dumps({"nonce": nonce, "redirect_uri": redirect, "iat": iat},
                 separators=(",", ":")).encode()
sig = hmac.new(os.environ["JWT_SECRET"].encode(), raw, hashlib.sha256).digest()
print(b64u(raw) + "." + b64u(sig))
PY
}

NOW="$(python3 -c 'import time; print(int(time.time()))')"
REDIRECT="https://portal.example.com/oidc/callback"
NONCE="oidcprobemain1234567890abcdef"   # 32 字符，与 Go generateRandomString(24) 等长
STATE_OK="$(forge_state "${NONCE}" "${REDIRECT}" "${NOW}")"
STATE_EXP="$(forge_state "${NONCE}" "${REDIRECT}" "$((NOW - 3600))")"

echo "==> 1) JSON 端点（未配置 OIDC：disabled 分支）"
req oidc-config.json          '/auth/oidc/config'
req oidc-url-noredirect.json  '/auth/oidc/url'
req oidc-url-disabled.json    '/auth/oidc/url?redirect_uri=https%3A%2F%2Fportal.example.com%2Foidc%2Fcallback'
req oidc-start-disabled.json  '/auth/oidc/start'

echo "==> 2) callback：provider error 分支（含定制 escaper 特殊字符场景）"
req302 oidc-cb-error.json          '/auth/oidc/callback?error=access_denied&error_description=User%20denied'
# description 原文含 `+ & = ? # 空格`（query 内先标准编码，服务端解码后用定制 escaper 重编）
req302 oidc-cb-error-specials.json '/auth/oidc/callback?error=a%2Bb&error_description=x%2By%26z%3Dw%3Fv%23t%20u'

echo "==> 3) callback：state 家族"
req302 oidc-cb-nostate.json        '/auth/oidc/callback'
req302 oidc-cb-badstate.json       '/auth/oidc/callback?state=garbage'
req302 oidc-cb-state-nocookie.json "/auth/oidc/callback?state=${STATE_OK}"
req302 oidc-cb-state-wrongnonce.json "/auth/oidc/callback?state=${STATE_OK}" \
  -H 'Cookie: weknora_oidc_nonce=some-other-nonce-value-0000'
req302 oidc-cb-expired.json        "/auth/oidc/callback?state=${STATE_EXP}" \
  -H "Cookie: weknora_oidc_nonce=${NONCE}"

echo "==> 4) callback：合法 state + 配对 cookie"
req302 oidc-cb-missing-code.json   "/auth/oidc/callback?state=${STATE_OK}" \
  -H "Cookie: weknora_oidc_nonce=${NONCE}"
req302 oidc-cb-login-failed.json   "/auth/oidc/callback?state=${STATE_OK}&code=abc123" \
  -H "Cookie: weknora_oidc_nonce=${NONCE}"

echo "==> 完成：oidc-* golden 共 $(ls "${OUT}" | grep -c '^oidc-') 条"
