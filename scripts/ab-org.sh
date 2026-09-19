#!/usr/bin/env bash
# 波 3 协作面批次（organizations 全部路由 + KB/agent shares + shared-* 读面）真 PG A/B。
# 掩码面：uuid + 时间戳 + invite_code（16 位随机 hex，create/invite-code 每轮不同）。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
WORK="$(mktemp -d /tmp/ab-org.XXXXXX)"
trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/java"
ORG_TARGET_PORT="${JAVA_PORT:-8082}" ORG_OUT_DIR="${WORK}/java" \
  bash "${SCRIPT_DIR}/scripts/record-org-golden.sh" > "${WORK}/run.log" 2>&1 \
  || { echo "REPLAY FAILED"; tail -25 "${WORK}/run.log"; exit 1; }
mask() { sed -E \
  -e 's/[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})/<ts>/g' \
  -e 's/"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"/"<uuid>"/g' \
  -e 's/"(invite_code|code)":"[0-9a-f]{16}"/"\1":"<code>"/g' \
  -e 's/"by_organization":\{[^}]*\}/"by_organization":<map>/g'; }
FAIL=0
for jf in "${WORK}/java"/*.json; do
  name="$(basename "${jf}")"
  gf="${SCRIPT_DIR}/server/src/test/resources/contracts/${name}"
  [ -f "${gf}" ] || { echo "SKIP  ${name}"; continue; }
  if cmp -s <(mask < "${gf}") <(mask < "${jf}"); then echo "MATCH ${name}";
  else echo "DIFF  ${name}"; diff <(mask < "${gf}") <(mask < "${jf}") | head -6 | sed 's/^/       /'; FAIL=1; fi
done
if [ "${FAIL}" = "0" ]; then echo "ALL MATCH"; else echo "HAS DIFF"; exit 1; fi
