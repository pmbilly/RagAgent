#!/usr/bin/env bash
# 波 3 sandbox 子批 2（sandbox-check + templates/query provider 面）真 PG A/B。
# 用 record-sbx2-golden.sh 对 Java :8082 重放，与 contracts/ 里的 Go golden 比对。
#
# 掩码面：latency_ms 是两侧各自的探测耗时——Go 拒连 0ms 时 omitempty 整键省略、
# Java HttpClient 1ms+ 键出现，把整个片段（连同前置逗号）从两侧移除后逐字节。
# 其余全确定：错误文案是 sandboxCheckReason 固定中文分类；tpl 的 500 是
# plain 分支固定形态（无 details 键）。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
WORK="$(mktemp -d /tmp/ab-sbx2.XXXXXX)"
trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/java"
SBX2_TARGET_PORT="${JAVA_PORT:-8082}" SBX2_OUT_DIR="${WORK}/java" \
  bash "${SCRIPT_DIR}/scripts/record-sbx2-golden.sh" > "${WORK}/run.log" 2>&1 \
  || { echo "REPLAY FAILED"; tail -25 "${WORK}/run.log"; exit 1; }

mask() { sed -E 's/,"latency_ms":[0-9]+//g'; }

FAIL=0
for jf in "${WORK}/java"/*.json; do
  name="$(basename "${jf}")"
  gf="${SCRIPT_DIR}/server/src/test/resources/contracts/${name}"
  [ -f "${gf}" ] || { echo "SKIP  ${name}"; continue; }
  if cmp -s <(mask < "${gf}") <(mask < "${jf}"); then echo "MATCH ${name}";
  else echo "DIFF  ${name}"; diff <(mask < "${gf}") <(mask < "${jf}") | head -6 | sed 's/^/       /'; FAIL=1; fi
done
if [ "${FAIL}" = "0" ]; then echo "ALL MATCH"; else echo "HAS DIFF"; exit 1; fi
