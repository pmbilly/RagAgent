#!/usr/bin/env bash
# W5a 收尾批（13 条小散路由）真 PG A/B。
# 用 record-w5a-golden.sh 对 Java :8082 重放，与 contracts/ 里的 Go golden
# 掩码后逐字节比对。两侧共用同一个 dev PG（localhost:15432）。
#
# 两侧同掩码：uuid / 时间戳 / JWT（access_token/refresh_token/token）/
# "seq_id"（tag 序列，两侧各自 NEXTVAL）/ 数字 "id"（自助建租户的序列值）。
#
# 前置：Go :8080 与 Java :8082 都在跑（contracts/ 的 golden 即当前 Go 二进制实录）。
#
# 用法：scripts/ab-w5a.sh          # 一轮 ALL MATCH 为过；跑两轮确认稳定
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
WORK="$(mktemp -d /tmp/ab-w5a.XXXXXX)"
trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/java"

# 前置检查：两台 server 都得在
curl -sf -o /dev/null "http://localhost:${GO_PORT}/api/v1/auth/oidc/config" \
  || { echo "FATAL: Go server (:${GO_PORT}) 不在"; exit 1; }
curl -sf -o /dev/null "http://localhost:${JAVA_PORT:-8082}/api/v1/auth/oidc/config" \
  || { echo "FATAL: Java server (:${JAVA_PORT:-8082}) 不在"; exit 1; }

W5A_TARGET_PORT="${JAVA_PORT:-8082}" W5A_OUT_DIR="${WORK}/java" \
  bash "${SCRIPT_DIR}/scripts/record-w5a-golden.sh" > "${WORK}/run.log" 2>&1 \
  || { echo "REPLAY FAILED"; tail -25 "${WORK}/run.log"; exit 1; }

mask() { # $1=输入 $2=输出：两侧同掩码
  python3 - "$1" "$2" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
s = re.sub(r'"(access_token|refresh_token|token)":"[^"]*"', r'"\1":"<jwt>"', s)
s = re.sub(r'"([a-z_]+)":"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"',
           r'"\1":"<uuid>"', s)
s = re.sub(r'"([a-z_]+)":"[2-9]\d{3}-\d{2}-\d{2}T[0-9:.\-+Z]+"', r'"\1":"<ts>"', s)
s = re.sub(r'"([a-z_]+)":"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"',
           r'"\1":"<uuid>"', s)
s = re.sub(r'"seq_id":\d+', '"seq_id":<seq>', s)
s = re.sub(r'"id":\d+', '"id":<id>', s)
# 部署态键：Go dev 未绑定默认向量库（键缺席）、Java env store 恒解析 postgres——
# XDEP，两侧同剥（约定 §9「波 2 knowledge」同款部署漂移）
s = re.sub(r'"vector_store_engine_type":"[^"]*",', '', s)
open(sys.argv[2], 'w', encoding='utf-8').write(s)
PY
}

MATCH=0; DIFF=0
for g in "${SCRIPT_DIR}"/server/src/test/resources/contracts/w5a-*.json; do
  name="$(basename "${g}")"
  mask "${g}" "${WORK}/go_${name}"
  mask "${WORK}/java/${name}" "${WORK}/java_${name}" 2>/dev/null \
    || { echo "MISS  ${name}"; DIFF=$((DIFF+1)); continue; }
  if diff -q "${WORK}/go_${name}" "${WORK}/java_${name}" >/dev/null; then
    echo "MATCH ${name}"; MATCH=$((MATCH+1))
  else
    echo "DIFF  ${name}"
    diff "${WORK}/go_${name}" "${WORK}/java_${name}" | head -6 | sed 's/^/      /'
    DIFF=$((DIFF+1))
  fi
done

echo
echo "RESULT: ${MATCH} MATCH / ${DIFF} DIFF"
[ "${DIFF}" -eq 0 ]
