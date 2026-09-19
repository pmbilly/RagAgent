#!/usr/bin/env bash
# 系统管理端 + 评估（波 2 收官批，24 条路由）真 PG A/B。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
WORK="$(mktemp -d /tmp/ab-sys.XXXXXX)"
trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/java"
SYS_TARGET_PORT="${JAVA_PORT:-8082}" SYS_OUT_DIR="${WORK}/java" \
  bash "${SCRIPT_DIR}/scripts/record-system-golden.sh" > "${WORK}/run.log" 2>&1 \
  || { echo "REPLAY FAILED"; tail -25 "${WORK}/run.log"; exit 1; }

mask() {
  python3 - "$1" <<'PY'
import re, sys
s = sys.argv[1]
s = re.sub(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', '<uuid>', s)
s = re.sub(r'"[2-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})"', '"<ts>"', s)
s = re.sub(r'"(created_at|updated_at|expires_at_unix|started_at|last_modified_at)":\s*\d{7,}', r'"\1":<epoch>', s)
s = re.sub(r'"(token|api_key|generated_password|password)":\s*"[^"]*"', r'"\1":"<tok>"', s)
s = re.sub(r'"(uptime_seconds|db_version|version)":\s*"?[^",}]*"?', r'"\1":<v>', s)
s = re.sub(r'"tenant_id":\s*\d+', '"tenant_id":<n>', s)
s = re.sub(r'"id":\s*\d+', '"id":<seq>', s)
s = re.sub(r'"affected":\s*\d+', '"affected":<n>', s)
sys.stdout.write(s)
PY
}
FAIL=0
for jf in "${WORK}/java"/*.json; do
  name="$(basename "${jf}")"
  gf="${SCRIPT_DIR}/server/src/test/resources/contracts/${name}"
  [ -f "${gf}" ] || { echo "SKIP  ${name}"; continue; }
  # 部署状态差异（Agent 报告 §五-4）：capabilities/db_version 两侧各按部署断言，A/B 跳过
  case "${name}" in
    sys-capabilities.json|sys-capabilities-viewer.json|sys-info.json) echo "XDEP  ${name}（部署态差异，契约测试已掩码）"; continue;;
    # evaluation 执行步是部署能力（Go dev 真跑 LLM 流水线带真实指标；Java 降级 failed）
    ev-post.json|ev-get.json|ev-get-viewer.json) echo "XDEP  ${name}（执行步部署态，见 EvaluationService 注释）"; continue;;
  esac
  gs="$(mask "$(cat "${gf}")")"; js="$(mask "$(cat "${jf}")")"
  if [ "${gs}" = "${js}" ]; then echo "MATCH ${name}";
  else echo "DIFF  ${name}"; diff <(printf '%s' "${gs}") <(printf '%s' "${js}") | head -4 | sed 's/^/       /'; FAIL=1; fi
done
if [ "${FAIL}" = "0" ]; then echo "ALL MATCH（除部署态 XDEP）"; else echo "HAS DIFF"; exit 1; fi
