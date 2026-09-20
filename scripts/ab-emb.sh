#!/usr/bin/env bash
# 波 4.3（embed 管理面 + embed 公开面 + im channels 清单面）真 PG A/B。
# 复用 record-emb-golden.sh 的 EMB_TARGET_PORT 双侧重放：把 golden 用例打到 Java :8082，
# 与 contracts/ 里已录的 Go 实录逐字节掩码比对。
#
# 掩码面：uuid（含错误文案里的裸 uuid）+ 时间戳 + publish token（em_…）+
#          session token（ems_…）+ 会话签名 sig（base64url 43 字符）。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
WORK="$(mktemp -d /tmp/ab-emb.XXXXXX)"
trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/java"
EMB_TARGET_PORT="${JAVA_PORT:-8082}" EMB_OUT_DIR="${WORK}/java" \
  bash "${SCRIPT_DIR}/scripts/record-emb-golden.sh" > "${WORK}/run.log" 2>&1 \
  || { echo "REPLAY FAILED"; tail -25 "${WORK}/run.log"; exit 1; }
mask() { sed -E \
  -e 's/[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})/<ts>/g' \
  -e 's/"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"/"<uuid>"/g' \
  -e 's/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/<uuid>/g' \
  -e 's/em_[A-Za-z0-9_-]{20,}/em_<token>/g' \
  -e 's/ems_[A-Za-z0-9_-]{20,}/ems_<token>/g' \
  -e 's/"sig":"[A-Za-z0-9_-]{20,}"/"sig":"<sig>"/g'; }
FAIL=0
COUNT=0
for jf in "${WORK}/java"/*.json; do
  name="$(basename "${jf}")"
  gf="${SCRIPT_DIR}/server/src/test/resources/contracts/${name}"
  [ -f "${gf}" ] || { echo "SKIP  ${name}"; continue; }
  COUNT=$((COUNT + 1))
  if cmp -s <(mask < "${gf}") <(mask < "${jf}"); then echo "MATCH ${name}";
  else echo "DIFF  ${name}"; diff <(mask < "${gf}") <(mask < "${jf}") | sed 's/^/       /' | head -8 || true; FAIL=1; fi
done
echo "compared ${COUNT} items"
if [ "${FAIL}" = "0" ]; then echo "ALL MATCH"; else echo "HAS DIFF"; exit 1; fi
