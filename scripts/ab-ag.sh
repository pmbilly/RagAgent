#!/usr/bin/env bash
# 波 3 agents 批（agents CRUD 家族 8 条 + initialization 3 条）真 PG A/B。
# 用 record-ag-golden.sh 对 Java :8082 重放，与 contracts/ 里的 Go golden 比对。
#
# 掩码面：
#   - uuid：create/copy/initialize 的动态主键（每轮不同）；agent id（掩码后 URL 流程
#     在各侧重放脚本内部自洽）。
#   - 时间戳：created_at/updated_at 是各自服务器时钟。
#   - 其余（含内建 agent 的 YAML 大文本、错误文案、静态面）逐字节 cmp。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
WORK="$(mktemp -d /tmp/ab-ag.XXXXXX)"
trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/java"
AG_TARGET_PORT="${JAVA_PORT:-8082}" AG_OUT_DIR="${WORK}/java" \
  bash "${SCRIPT_DIR}/scripts/record-ag-golden.sh" > "${WORK}/run.log" 2>&1 \
  || { echo "REPLAY FAILED"; tail -25 "${WORK}/run.log"; exit 1; }

mask() {
  sed -E \
    -e 's/[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})/<ts>/g' \
    -e 's/"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"/"<uuid>"/g'
}

FAIL=0
for jf in "${WORK}/java"/*.json; do
  name="$(basename "${jf}")"
  gf="${SCRIPT_DIR}/server/src/test/resources/contracts/${name}"
  [ -f "${gf}" ] || { echo "SKIP  ${name}"; continue; }
  if cmp -s <(mask < "${gf}") <(mask < "${jf}"); then echo "MATCH ${name}";
  else echo "DIFF  ${name}"; diff <(mask < "${gf}") <(mask < "${jf}") | head -6 | sed 's/^/       /'; FAIL=1; fi
done
if [ "${FAIL}" = "0" ]; then echo "ALL MATCH"; else echo "HAS DIFF"; exit 1; fi
