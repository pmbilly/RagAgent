#!/usr/bin/env bash
# 跨空间租户目录 + KV 配置族（波 2 扫尾批 3，5 条路由）真 PG A/B。
# 用 record-ct-golden.sh 对 Java :8082 重放，与 contracts/ 里的 Go golden
# 比对。两侧同掩码：uuid / 时间戳 / 数字 id / api_key 明文 / parser SSRF
# 错误里的解析 IP（fake-ip 段每次解析可能不同）。
#
# 前置：
#   - Go :8080 以 WEKNORA_TENANT_ENABLE_CROSS_TENANT_ACCESS=true 在跑
#     （contracts/ 的 golden 就是同部署态录的）
#   - Java :8082 同样 flag on 起：
#       WEKNORA_TENANT_ENABLE_CROSS_TENANT_ACCESS=true bash scripts/java-server-up.sh
#
# EXPECTED DIFF：ct-kv-get-prompt-templates.json（Go 独有 vendor yaml 功能，
# Java 推迟 → 400 unsupported key，见 docs §9「波 2 扫尾批 3」）。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
WORK="$(mktemp -d /tmp/ab-ct.XXXXXX)"
trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/java" "${WORK}/mask"

# 前置检查：两台 server 都得在
curl -sf -o /dev/null "http://localhost:${GO_PORT}/api/v1/auth/oidc/config" \
  || { echo "FATAL: Go server (:${GO_PORT}) 不在"; exit 1; }
curl -sf -o /dev/null "http://localhost:${JAVA_PORT:-8082}/api/v1/auth/oidc/config" \
  || { echo "FATAL: Java server (:${JAVA_PORT:-8082}) 不在"; exit 1; }

CT_TARGET_PORT="${JAVA_PORT:-8082}" CT_OUT_DIR="${WORK}/java" \
  bash "${SCRIPT_DIR}/scripts/record-ct-golden.sh" > "${WORK}/run.log" 2>&1 \
  || { echo "REPLAY FAILED"; tail -25 "${WORK}/run.log"; exit 1; }

mask() { # $1=输入 $2=输出：两侧同掩码
  python3 - "$1" "$2" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
s = re.sub(r'"api_key":"[^"]*"', '"api_key":"<api_key>"', s)
s = re.sub(r'"([a-z_]+)":"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"',
           r'"\1":"<uuid>"', s)
s = re.sub(r'"([a-z_]+)":"[2-9]\d{3}-\d{2}-\d{2}T[0-9:.\-+Z]+"', r'"\1":"<ts>"', s)
s = re.sub(r'"id":\d+', '"id":"<id>"', s)
s = re.sub(r'resolves to restricted IP [0-9.]+', 'resolves to restricted IP <ip>', s)
open(sys.argv[2], 'w', encoding='utf-8').write(s)
PY
}

EXPECTED_DIFFS="ct-kv-get-prompt-templates.json"
FAIL=0
for jf in "${WORK}/java"/ct-*.json; do
  name="$(basename "${jf}")"
  gf="${SCRIPT_DIR}/server/src/test/resources/contracts/${name}"
  [ -f "${gf}" ] || { echo "SKIP  ${name}（无 Go golden）"; continue; }
  mask "${gf}" "${WORK}/mask/go.json"
  mask "${jf}" "${WORK}/mask/java.json"
  if cmp -s "${WORK}/mask/go.json" "${WORK}/mask/java.json"; then
    echo "MATCH ${name}"
  elif echo " ${EXPECTED_DIFFS} " | grep -q " ${name} "; then
    echo "EXPECTED-DIFF ${name}"
  else
    echo "DIFF  ${name}"
    diff "${WORK}/mask/go.json" "${WORK}/mask/java.json" | head -4 | sed 's/^/       /'
    FAIL=1
  fi
done
if [ "${FAIL}" = "0" ]; then echo "ALL MATCH（EXPECTED-DIFF 另列）"; else echo "HAS DIFF"; exit 1; fi
