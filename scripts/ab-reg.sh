#!/usr/bin/env bash
# auth 注册族（波 2 扫尾批 1，9 条路由）真 PG A/B。
# 用 record-reg-golden.sh 对 Java :8082 重放，与 contracts/ 里的 Go golden
# 做同掩码比对。动态值：uuid / 时间戳 / JWT / tenant_id 数字 / 邀请 token /
# 设置行 id（reg-mode-set-invite-only 的 id/时间戳同样动态）。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
WORK="$(mktemp -d /tmp/ab-reg.XXXXXX)"
trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/java"
REG_TARGET_PORT="${JAVA_PORT:-8082}" REG_OUT_DIR="${WORK}/java" \
  bash "${SCRIPT_DIR}/scripts/record-reg-golden.sh" > "${WORK}/run.log" 2>&1 \
  || { echo "REPLAY FAILED"; tail -25 "${WORK}/run.log"; exit 1; }

mask() {
  python3 - "$1" <<'PY'
import re, sys
s = sys.argv[1]
s = re.sub(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', '<uuid>', s)
s = re.sub(r'"[2-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})"', '"<ts>"', s)
s = re.sub(r'"token":\s*"[^"]*"', '"token":"<tok>"', s)
s = re.sub(r'"refresh_token":\s*"[^"]*"', '"refresh_token":"<tok>"', s)
s = re.sub(r'"tenant_id":\s*\d+', '"tenant_id":<n>', s)
s = re.sub(r'"last_active_tenant_id":\s*\d+', '"last_active_tenant_id":<n>', s)
s = re.sub(r'"id":\s*\d+', '"id":<seq>', s)
sys.stdout.write(s)
PY
}
FAIL=0
for jf in "${WORK}/java"/*.json; do
  name="$(basename "${jf}")"
  gf="${SCRIPT_DIR}/server/src/test/resources/contracts/${name}"
  [ -f "${gf}" ] || { echo "SKIP  ${name}"; continue; }
  gs="$(mask "$(cat "${gf}")")"; js="$(mask "$(cat "${jf}")")"
  if [ "${gs}" = "${js}" ]; then echo "MATCH ${name}";
  else echo "DIFF  ${name}"; diff <(printf '%s' "${gs}") <(printf '%s' "${js}") | head -4 | sed 's/^/       /'; FAIL=1; fi
done
if [ "${FAIL}" = "0" ]; then echo "ALL MATCH"; else echo "HAS DIFF"; exit 1; fi
