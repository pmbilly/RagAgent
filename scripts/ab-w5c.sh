#!/usr/bin/env bash
# W5c 文件代理面 A/B：双端（Go :18080 / Java :18082）同指 dev PG + 同一
# LOCAL_STORAGE_BASE_DIR + 同一 SYSTEM_AES_KEY（dev-env 从 WeKnora/.env 取），
# 重放 record-w5c-golden.sh 的同一场景清单，掩码后逐字节比对（含 headers）。
#
# 用法（两侧 server 起好后）：
#   scripts/ab-w5c.sh
#
# 头部比对归一化（g5/bs 批先例）：status line 的 reason-phrase（gin vs Tomcat）、
# Date / X-Request-Id / Vary / Keep-Alive / Connection。
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

GO_P="${W5C_GO_PORT:-18080}"
JAVA_P="${W5C_JAVA_PORT:-18082}"
WORK="${W5C_AB_WORK:-/tmp/w5c-ab}"
CONTRACTS="${SCRIPT_DIR}/server/src/test/resources/contracts"

rm -rf "${WORK}"
mkdir -p "${WORK}/java"

fail=0
total=0

echo "==> 1) Java 侧重放（W5C_TARGET_PORT=${JAVA_P}）"
W5C_TARGET_PORT="${JAVA_P}" W5C_OUT_DIR="${WORK}/java" \
  GO_PORT="${GO_P}" scripts/record-w5c-golden.sh >/dev/null

mask() { sed -E 's/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/<uuid>/g'; }

# Tomcat 会把 Content-Type 的 ";charset=" 前的空格规范化掉（容器行为，
# 波 1 G5 attachments 批已备案）；比对时两侧同形归一。
norm_hdr() { grep -viE '^(date|x-request-id|keep-alive|connection|vary:|HTTP/)' "$1" 2>/dev/null \
               | sed -E 's/^(Content-Type:[^;]*); ?charset=/\1; charset=/I' \
               | grep -v '^$' | sort; }

echo "==> 2) JSON 场景逐字节"
for gf in "${CONTRACTS}"/w5c-*.json; do
  name="$(basename "${gf}")"
  jf="${WORK}/java/${name}"
  total=$((total + 1))
  [ -f "${jf}" ] || { echo "MISS  ${name}"; fail=1; continue; }
  if cmp -s <(mask < "${gf}") <(mask < "${jf}"); then
    echo "MATCH ${name}"
  else
    echo "DIFF  ${name}"
    diff <(mask < "${gf}") <(mask < "${jf}") | head -6 | sed 's/^/       /'
    fail=1
  fi
done

echo "==> 3) 二进制（*.bin + *.bin.headers）"
for gf in "${CONTRACTS}"/w5c-*.bin; do
  name="$(basename "${gf}")"
  jf="${WORK}/java/${name}"
  total=$((total + 1))
  if [ ! -f "${jf}" ]; then echo "MISS  ${name}"; fail=1; continue; fi
  if cmp -s "${gf}" "${jf}"; then
    echo "MATCH ${name}"
  else
    echo "DIFF  ${name} (bytes)"; fail=1
  fi
  gh="${gf}.headers"
  jh="${jf}.headers"
  total=$((total + 1))
  if [ ! -f "${jh}" ] || [ ! -f "${gh}" ]; then echo "MISS  ${name}.headers"; fail=1; continue; fi
  if diff <(norm_hdr "${gh}") <(norm_hdr "${jh}") >/dev/null; then
    echo "MATCH ${name}.headers"
  else
    echo "DIFF  ${name}.headers"
    diff <(norm_hdr "${gh}") <(norm_hdr "${jh}") | head -8 | sed 's/^/       /'
    fail=1
  fi
done

echo "==> 4) HEAD/纯头部场景（*.hdr）"
for gf in "${CONTRACTS}"/w5c-*.hdr; do
  name="$(basename "${gf}")"
  jf="${WORK}/java/${name}"
  total=$((total + 1))
  if [ ! -f "${jf}" ]; then echo "MISS  ${name}"; fail=1; continue; fi
  if diff <(norm_hdr "${gf}") <(norm_hdr "${jf}") >/dev/null; then
    echo "MATCH ${name}"
  else
    echo "DIFF  ${name}"
    diff <(norm_hdr "${gf}") <(norm_hdr "${jf}") | head -8 | sed 's/^/       /'
    fail=1
  fi
done

echo
if [ "${fail}" = "0" ]; then
  echo "RESULT: ALL MATCH（${total} 项）"
else
  echo "RESULT: 存在 DIFF（${total} 项）"
fi
exit "${fail}"
