#!/usr/bin/env bash
# W5α3 跨租户消息文件授予 A/B：双端（Go :8080 / Java :8082）同指 dev PG +
# 同一 LOCAL_STORAGE_BASE_DIR，重放 record-w5f-golden.sh 的同一场景清单，
# 逐字节比对（json 体 / bin 体 + bin.headers / 空体 404）。
#
# 前置（双端起好）：
#   scripts/go-server-up.sh && scripts/java-server-up.sh
#
# 用法：
#   scripts/ab-w5f.sh        # 一轮
#   scripts/ab-w5f.sh && scripts/ab-w5f.sh   # 两轮（闭环要求）
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

JAVA_P="${JAVA_PORT:-8082}"
WORK="$(mktemp -d /tmp/ab-w5f.XXXXXX)"
trap 'rm -rf "${WORK}"' EXIT
CONTRACTS="${SCRIPT_DIR}/server/src/test/resources/contracts"

mkdir -p "${WORK}/java"

fail=0
total=0

echo "==> 1) Java 侧重放（W5F_TARGET_PORT=${JAVA_P}）"
W5F_TARGET_PORT="${JAVA_P}" W5F_OUT_DIR="${WORK}/java" \
  scripts/record-w5f-golden.sh >/dev/null

# Tomcat 会把 Content-Type 的 ";charset=" 前的空格规范化掉（容器行为，w5c 同例）。
norm_hdr() { grep -viE '^(date|x-request-id|keep-alive|connection|vary:|HTTP/)' "$1" 2>/dev/null \
               | sed -E 's/^(Content-Type:[^;]*); ?charset=/\1; charset=/I' \
               | grep -v '^$' | sort; }

echo "==> 2) JSON / 空体场景逐字节"
for gf in "${CONTRACTS}"/w5f-*.json "${CONTRACTS}"/w5f-key-websession; do
  name="$(basename "${gf}")"
  jf="${WORK}/java/${name}"
  total=$((total + 1))
  [ -f "${jf}" ] || { echo "MISS  ${name}"; fail=1; continue; }
  if cmp -s "${gf}" "${jf}"; then
    echo "MATCH ${name}"
  else
    echo "DIFF  ${name}"
    diff "${gf}" "${jf}" | head -6 | sed 's/^/       /'
    fail=1
  fi
done

echo "==> 3) 二进制（*.bin + *.bin.headers）"
for gf in "${CONTRACTS}"/w5f-*.bin; do
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

echo
if [ "${fail}" = "0" ]; then
  echo "RESULT: ALL MATCH（${total} 项）"
else
  echo "RESULT: 存在 DIFF（${total} 项）"
fi
exit "${fail}"
