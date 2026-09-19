#!/usr/bin/env bash
# auth OIDC 族（波 2 扫尾批 2，4 条路由）真 PG A/B。
# 用 record-oidc-golden.sh 对 Java :8082 重放，与 contracts/ 里的 Go golden
# 逐字节比对。callback 302 信封内不含 state/nonce/iat（响应只有固定的
# oidc_error 令牌与定制 escaper 产物），故无掩码项，直接字节比对。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
WORK="$(mktemp -d /tmp/ab-oidc.XXXXXX)"
trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/java"
OIDC_TARGET_PORT="${JAVA_PORT:-8082}" OIDC_OUT_DIR="${WORK}/java" \
  bash "${SCRIPT_DIR}/scripts/record-oidc-golden.sh" > "${WORK}/run.log" 2>&1 \
  || { echo "REPLAY FAILED"; tail -25 "${WORK}/run.log"; exit 1; }
FAIL=0
for jf in "${WORK}/java"/*.json; do
  name="$(basename "${jf}")"
  gf="${SCRIPT_DIR}/server/src/test/resources/contracts/${name}"
  [ -f "${gf}" ] || { echo "SKIP  ${name}"; continue; }
  if cmp -s "${gf}" "${jf}"; then echo "MATCH ${name}";
  else echo "DIFF  ${name}"; diff "${gf}" "${jf}" | head -4 | sed 's/^/       /'; FAIL=1; fi
done
if [ "${FAIL}" = "0" ]; then echo "ALL MATCH"; else echo "HAS DIFF"; exit 1; fi
