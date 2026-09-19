#!/usr/bin/env bash
# 波 3 browserskill 批（/me/browser + /me/browser/extension + local-browser 三条）
# 真 PG A/B：用 record-bs-golden.sh 对 Java :8082 重放，与 contracts/ 的 Go golden
# 掩码后逐字节比对。跑两轮（ROUND=2）确认状态收敛稳定。
#
# 前置：两侧 server 必须带同一组 BROWSERSKILL_* env 启动：
#   BROWSERSKILL_BINARY=/usr/bin/false \
#   BROWSERSKILL_CLUSTER_SECRET=bs-ab-cluster-secret-0123456789abcdef0123456789abcdef \
#   BROWSERSKILL_EXTENSION_PATH=/tmp/weknora-bs-ext/browser-skill-weknora.zip \
#   scripts/go-server-up.sh      # Go :8080（golden 已录）
#   同 env scripts/java-server-up.sh   # Java :8082
#
# 掩码面（与 BrowserSkillContractTest 同族）：
#   - "pairing_link":"<一次性 token>"（43 字符两侧各自随机）
#   - "device_id"/device.id 的 32 hex
#   - expires_at/renew_after/created_at/last_seen_at（两侧墙钟）
# 其余全确定：错误文案逐字对照；download 是头+字节（Last-Modified 两侧同文件同
# mtime，逐字节可比）。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

mask() {
  sed -E \
    -e 's/("pairing_link":")([^"]+)(")/\1<link>\3/g' \
    -e 's/("device_id":")([0-9a-f]{32})(")/\1<device_id>\3/g' \
    -e 's/("device":\{"id":")([0-9a-f]{32})(")/\1<device_id>\3/g' \
    -e 's/("(expires_at|renew_after|created_at|last_seen_at)":")([^"]*)(")/\1<ts>\4/g'
}

run_round() {
  local round="$1"
  local WORK
  WORK="$(mktemp -d /tmp/ab-bs-r"${round}".XXXXXX)"
  mkdir -p "${WORK}/java"
  BS_TARGET_PORT="${JAVA_PORT:-8082}" BS_OUT_DIR="${WORK}/java" \
    bash "${SCRIPT_DIR}/scripts/record-bs-golden.sh" > "${WORK}/run.log" 2>&1 \
    || { echo "REPLAY FAILED (round ${round})"; tail -25 "${WORK}/run.log"; rm -rf "${WORK}"; return 1; }

  local FAIL=0
  for jf in "${WORK}/java"/bs-*.json; do
    local name gf
    name="$(basename "${jf}")"
    gf="${SCRIPT_DIR}/server/src/test/resources/contracts/${name}"
    [ -f "${gf}" ] || { echo "SKIP  ${name}"; continue; }
    if cmp -s <(mask < "${gf}") <(mask < "${jf}"); then
      echo "MATCH ${name}"
    else
      echo "DIFF  ${name}"
      diff <(mask < "${gf}") <(mask < "${jf}") | head -6 | sed 's/^/       /'
      FAIL=1
    fi
  done
  # 下载：头（噪音头除外）+ 字节。头比对归一化容器噪音（g5 批先例）：
  #   - status line：gin "HTTP/1.1 200 OK" vs Tomcat "HTTP/1.1 200 "（无 reason phrase）
  #   - Vary：Spring CORS 过滤器多三个 Vary（Origin/Request-Method/Request-Headers）
  local jh="${WORK}/java/bs-download.bin.headers"
  local gh="${SCRIPT_DIR}/server/src/test/resources/contracts/bs-download.bin.headers"
  norm_hdr() { grep -viE '^(date|x-request-id|keep-alive|connection|vary:|HTTP/)' "$1" 2>/dev/null \
                 | grep -v '^$' | sort; }
  if diff <(norm_hdr "${gh}") <(norm_hdr "${jh}") > /dev/null; then
    echo "MATCH bs-download.bin.headers"
  else
    echo "DIFF  bs-download.bin.headers"
    diff <(norm_hdr "${gh}") <(norm_hdr "${jh}") | head -8 | sed 's/^/       /'
    FAIL=1
  fi
  if cmp -s "${SCRIPT_DIR}/server/src/test/resources/contracts/bs-download.bin" \
            "${WORK}/java/bs-download.bin"; then
    echo "MATCH bs-download.bin"
  else
    echo "DIFF  bs-download.bin"; FAIL=1
  fi
  rm -rf "${WORK}"
  return "${FAIL}"
}

FAIL=0
for round in 1 2; do
  echo "===== ROUND ${round} ====="
  run_round "${round}" || FAIL=1
done
if [ "${FAIL}" = "0" ]; then
  echo "ALL MATCH (2 rounds)"
else
  echo "HAS DIFF"
  exit 1
fi
