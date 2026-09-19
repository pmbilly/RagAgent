#!/usr/bin/env bash
# 基础设施配置三组（波 2 第五批，30 条路由）真 PG A/B。
# 原理同 ab-knowledge.sh：对 Java 重放录制脚本，掩码后与 Go 录的 golden 逐字节比对。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
WORK="$(mktemp -d /tmp/ab-infra.XXXXXX)"
trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/java"
INFRA_TARGET_PORT="${JAVA_PORT:-8082}" INFRA_OUT_DIR="${WORK}/java" \
  bash "${SCRIPT_DIR}/scripts/record-infra-config-golden.sh" > "${WORK}/run.log" 2>&1 \
  || { echo "REPLAY FAILED"; tail -25 "${WORK}/run.log"; exit 1; }

mask() {
  python3 - "$1" <<'PY'
import re, sys
s = sys.argv[1]
s = re.sub(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', '<uuid>', s)
s = re.sub(r'"[2-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})"', '"<ts>"', s)
s = re.sub(r'"(created_at|updated_at)":\s*\d{9,}', r'"\1":<epoch>', s)
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
