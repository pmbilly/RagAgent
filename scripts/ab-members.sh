#!/usr/bin/env bash
# 空间成员/邀请/API-Principal（波 2 第六批，17 条路由）真 PG A/B。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
WORK="$(mktemp -d /tmp/ab-mb.XXXXXX)"
trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/java"
MEM_TARGET_PORT="${JAVA_PORT:-8082}" MEM_OUT_DIR="${WORK}/java" \
  bash "${SCRIPT_DIR}/scripts/record-members-golden.sh" > "${WORK}/run.log" 2>&1 \
  || { echo "REPLAY FAILED"; tail -25 "${WORK}/run.log"; exit 1; }

mask() {
  python3 - "$1" <<'PY'
import re, sys
s = sys.argv[1]
s = re.sub(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', '<uuid>', s)
s = re.sub(r'"[2-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})"', '"<ts>"', s)
s = re.sub(r'"(created_at|updated_at|joined_at|expires_at|expires_at_unix)":\s*\d{7,}', r'"\1":<epoch>', s)
s = re.sub(r'"token":\s*"[^"]*"', '"token":"<tok>"', s)
s = re.sub(r'"id":\s*\d+', '"id":<seq>', s)
s = re.sub(r'"(invited_by|last_login|tenant_id)":\s*\d+', r'"\1":<n>', s)
s = re.sub(r'"invite_token":\s*"[^"]*"', '"invite_token":"<tok>"', s)
s = re.sub(r'"register_url":\s*"[^"]*"', '"register_url":"<url>"', s)
s = re.sub(r'"invite_url":\s*"[^"]*"', '"invite_url":"<url>"', s)
s = re.sub(r'(token=)[A-Za-z0-9_\-]+', r'\1<tok>', s)
s = re.sub(r'"data":\s*\{"token":"<tok>"', '"data":{"token":"<tok>"', s)
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
