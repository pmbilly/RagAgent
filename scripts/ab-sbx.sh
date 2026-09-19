#!/usr/bin/env bash
# 波 3 sandbox 子批 1（/sandbox-configs 配置 CRUD，8 条路由）真 PG A/B。
# 用 record-sbx-golden.sh 对 Java :8082 重放，与 contracts/ 里的 Go golden 比对。
#
# 掩码面（两侧同掩码后逐字节）：
#   - config id：两侧各自 uuid
#   - created_at/updated_at：服务器时钟（且 Go 自身 create/list 的时区形态都不稳定）
# 其余全部确定：api_key/env_vars 值两侧都被响应投影掩成 "***"；
# 409/423 拒绝体是固定文案；docker 恒禁用（两侧 env 均未设）。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
WORK="$(mktemp -d /tmp/ab-sbx.XXXXXX)"
trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/java"
SBX_TARGET_PORT="${JAVA_PORT:-8082}" SBX_OUT_DIR="${WORK}/java" \
  bash "${SCRIPT_DIR}/scripts/record-sbx-golden.sh" > "${WORK}/run.log" 2>&1 \
  || { echo "REPLAY FAILED"; tail -25 "${WORK}/run.log"; exit 1; }

mask() { sed -E \
  -e 's/[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})/<ts>/g' \
  -e 's/"id":"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"/"id":"<uuid>"/g'; }

FAIL=0
for jf in "${WORK}/java"/*.json; do
  name="$(basename "${jf}")"
  gf="${SCRIPT_DIR}/server/src/test/resources/contracts/${name}"
  [ -f "${gf}" ] || { echo "SKIP  ${name}"; continue; }
  if cmp -s <(mask < "${gf}") <(mask < "${jf}"); then echo "MATCH ${name}";
  else echo "DIFF  ${name}"; diff <(mask < "${gf}") <(mask < "${jf}") | head -6 | sed 's/^/       /'; FAIL=1; fi
done
if [ "${FAIL}" = "0" ]; then echo "ALL MATCH"; else echo "HAS DIFF"; exit 1; fi
