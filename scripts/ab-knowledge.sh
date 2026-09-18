#!/usr/bin/env bash
# knowledge 域（波 2 第二/三批，24 条路由）真 PG A/B。
#
# 原理：两个录制脚本已参数化（KG_TARGET_PORT / KG_OUT_DIR）——同一个请求序列
# 对 Java 侧重放（种子幂等，固定 id 与 Go 录制时一致），把落盘的响应与 Go 录的
# golden 掩码后逐字节比对。动态字段（uuid/时间戳/seq/任务 id）掩码。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

WORK="$(mktemp -d /tmp/ab-kg.XXXXXX)"
trap 'rm -rf "${WORK}"' EXIT

JAVA_PORT="${JAVA_PORT:-8082}"
run_java() {
  local script="$1" dir="$2"
  mkdir -p "${dir}"
  echo "==> 对 Java :${JAVA_PORT} 重放 ${script}"
  KG_TARGET_PORT="${JAVA_PORT}" KG_OUT_DIR="${dir}" \
    bash "${SCRIPT_DIR}/scripts/${script}" > "${WORK}/${script}.log" 2>&1 \
    || { echo "REPLAY FAILED: ${script}（日志尾部如下）"; tail -20 "${WORK}/${script}.log"; return 1; }
}

mask() {
  python3 - "$1" <<'PY'
import re, sys
s = sys.argv[1]
s = re.sub(r'"([a-z_]+)":\s*"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"',
           r'"\1":"<uuid>"', s)
s = re.sub(r'"([A-Za-z_]+)":\s*"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"',
           r'"\1":"<uuid>"', s)
s = re.sub(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', '<uuid>', s)
s = re.sub(r'"[2-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})"',
           '"<ts>"', s)
s = re.sub(r'\b[2-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})\b', '<ts>', s)
s = re.sub(r'"task_id":\s*"[^"]*"', '"task_id":"<task>"', s)
s = re.sub(r'"(created_at|updated_at)":\s*\d{9,}', r'"\1":<epoch>', s)
sys.stdout.write(s)
PY
}

# 容器噪音归一化（§9 G5：status line 无 reason phrase、Vary 多三条；
# 新增：错误响应 Go 带 "; charset=utf-8"+Content-Length，Java 裸 application/json+chunked
# ——只比状态码 / Content-Type(去 charset) / Content-Disposition）
norm_headers() {
  grep -iE '^(HTTP/|content-type:|content-disposition:)' "$1" \
    | sed 's/ *$//' | tr -d '\r' | tr 'A-Z' 'a-z' \
    | sed 's/; *charset=utf-8//' | sed 's/\(^http\/1\.1 [0-9]*\).*/\1/'
}

FAIL=0
compare() {
  local script="$1" jdir="$2"
  local golden="${SCRIPT_DIR}/server/src/test/resources/contracts"
  for jf in "${jdir}"/*.json; do
    local name; name="$(basename "${jf}")"
    local gf="${golden}/${name}"
    if [ ! -f "${gf}" ]; then echo "SKIP  ${name}（Go 侧无对应 golden）"; continue; fi
    local gs js
    gs="$(mask "$(cat "${gf}")")"
    js="$(mask "$(cat "${jf}")")"
    if [ "${gs}" = "${js}" ]; then
      echo "MATCH ${name}"
    else
      echo "DIFF  ${name}"
      diff <(printf '%s' "${gs}") <(printf '%s' "${js}") | head -6 | sed 's/^/       /'
      FAIL=1
    fi
  done
  # 下载/预览响应头
  for jh in "${jdir}"/*.json.headers; do
    [ -f "${jh}" ] || continue
    local name; name="$(basename "${jh}")"
    local gf="${golden}/${name}"
    if [ ! -f "${gf}" ]; then continue; fi
    if diff -q <(norm_headers "${gf}") <(norm_headers "${jh}") >/dev/null; then
      echo "MATCH ${name}（headers）"
    else
      echo "DIFF  ${name}（headers）"
      diff <(norm_headers "${gf}") <(norm_headers "${jh}") | head -6 | sed 's/^/       /'
      FAIL=1
    fi
  done
}

run_java "record-knowledge-golden.sh" "${WORK}/kg" || FAIL=1
run_java "record-knowledge-search-golden.sh" "${WORK}/ks" || FAIL=1

echo "==> 比对"
compare "kg" "${WORK}/kg"
compare "ks" "${WORK}/ks"

if [ "${FAIL}" = "0" ]; then echo "ALL MATCH"; else echo "HAS DIFF"; exit 1; fi
