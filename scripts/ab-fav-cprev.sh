#!/usr/bin/env bash
# 波 2 终扫批（favorites + chunker/preview，4 条路由）真 PG A/B。
# 用 record-fav-cprev-golden.sh 对 Java :8082 重放，与 contracts/ 里的 Go golden 比对。
#
# 掩码面：
#   - cprev-*：响应完全确定（无时间戳/uuid），逐字节 cmp。
#   - fav-*：created_at 是服务器时钟（两侧各自 now()），其余全确定
#     （user_id/tenant_id/resource_* 由固定种子身份决定）——仅对含时间戳的
#     列表 golden 做 created_at 掩码后比对，其余逐字节。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
WORK="$(mktemp -d /tmp/ab-fav.XXXXXX)"
trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/java"
FAV_TARGET_PORT="${JAVA_PORT:-8082}" FAV_OUT_DIR="${WORK}/java" \
  bash "${SCRIPT_DIR}/scripts/record-fav-cprev-golden.sh" > "${WORK}/run.log" 2>&1 \
  || { echo "REPLAY FAILED"; tail -25 "${WORK}/run.log"; exit 1; }

# created_at 掩码（RFC3339Nano，带偏移或 Z）
mask_ts() { sed -E 's/[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})/<ts>/g'; }

FAIL=0
for jf in "${WORK}/java"/*.json; do
  name="$(basename "${jf}")"
  gf="${SCRIPT_DIR}/server/src/test/resources/contracts/${name}"
  [ -f "${gf}" ] || { echo "SKIP  ${name}"; continue; }
  case "${name}" in
    fav-list-kb-after.json|fav-list-agent-after.json)
      if cmp -s <(mask_ts < "${gf}") <(mask_ts < "${jf}"); then echo "MATCH ${name} (ts-masked)";
      else echo "DIFF  ${name}"; diff <(mask_ts < "${gf}") <(mask_ts < "${jf}") | head -6 | sed 's/^/       /'; FAIL=1; fi ;;
    *)
      if cmp -s "${gf}" "${jf}"; then echo "MATCH ${name}";
      else echo "DIFF  ${name}"; diff "${gf}" "${jf}" | head -6 | sed 's/^/       /'; FAIL=1; fi ;;
  esac
done
if [ "${FAIL}" = "0" ]; then echo "ALL MATCH"; else echo "HAS DIFF"; exit 1; fi
