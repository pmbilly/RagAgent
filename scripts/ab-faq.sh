#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
WORK="$(mktemp -d /tmp/ab-faq.XXXXXX)"
trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/java"
FAQ_TARGET_PORT="${JAVA_PORT:-8082}" FAQ_OUT_DIR="${WORK}/java" \
  bash "${SCRIPT_DIR}/scripts/record-faq-golden.sh" > "${WORK}/run.log" 2>&1 \
  || { echo "REPLAY FAILED"; tail -20 "${WORK}/run.log"; exit 1; }

mask() {
  python3 - "$1" <<'PY'
import re, sys
s = sys.argv[1]
s = re.sub(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', '<uuid>', s)
s = re.sub(r'"[2-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})"', '"<ts>"', s)
s = re.sub(r'"(created_at|updated_at|imported_at)":\s*\d{9,}', r'"\1":<epoch>', s)
s = re.sub(r'"failed_entries_url":\s*"[^"]*"', '"failed_entries_url":"<url>"', s)
s = re.sub(r'"task_id":\s*"[^"]*"', '"task_id":"<task>"', s)
sys.stdout.write(s)
PY
}
norm_headers() {
  grep -iE '^(HTTP/|content-type:|content-disposition:)' "$1" \
    | sed 's/ *$//' | tr -d '\r' | tr 'A-Z' 'a-z' \
    | sed 's/; *charset=utf-8//' | sed 's/\(^http\/1\.1 [0-9]*\).*/\1/'
}
FAIL=0
for jf in "${WORK}/java"/*.json; do
  name="$(basename "${jf}")"
  gf="${SCRIPT_DIR}/server/src/test/resources/contracts/${name}"
  [ -f "${gf}" ] || { echo "SKIP  ${name}"; continue; }
  # 预期差异：Go asynq 重试窗口 vs Java 直落终态（§9 FAQ 已知差异 2）
  if [ "${name}" = "faq-upsert-running.json" ]; then echo "XDIFF ${name}（预期：asynq 重试窗口形态）"; continue; fi
  gs="$(mask "$(cat "${gf}")")"; js="$(mask "$(cat "${jf}")")"
  if [ "${gs}" = "${js}" ]; then echo "MATCH ${name}";
  else echo "DIFF  ${name}"; diff <(printf '%s' "${gs}") <(printf '%s' "${js}") | head -4 | sed 's/^/       /'; FAIL=1; fi
done
for jh in "${WORK}/java"/*.json.headers; do
  [ -f "${jh}" ] || continue
  name="$(basename "${jh}")"; gf="${SCRIPT_DIR}/server/src/test/resources/contracts/${name}"
  [ -f "${gf}" ] || continue
  if diff -q <(norm_headers "${gf}") <(norm_headers "${jh}") >/dev/null; then echo "MATCH ${name}（headers）";
  else echo "DIFF  ${name}（headers）"; diff <(norm_headers "${gf}") <(norm_headers "${jh}") | head -4 | sed 's/^/       /'; FAIL=1; fi
done
if [ "${FAIL}" = "0" ]; then echo "ALL MATCH（除预期 XDIFF）"; else echo "HAS DIFF"; exit 1; fi
