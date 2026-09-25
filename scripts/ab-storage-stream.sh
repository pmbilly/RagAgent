#!/usr/bin/env bash
# 存储读路径流式化的**真 A/B**（W5γ5.1 ①a）：用 MinIO 造真云面，双端同连同一
# endpoint/bucket/key，走同一路由下载，比头（归一化后逐行）+ 体（sha256）。
#
# 背景：① 的偏差是"云对象整对象入堆"，本地盘走不到流式支路（golden 全绿也不覆盖它），
# 所以必须用真远端 provider 才能验证。方案与判据见 docs/storage-a3-plan.md §2.1。
#
# 用法：
#   scripts/ab-storage-stream.sh                    # 对拍（4KB 小对象）
#   AB_STREAM_MEM=1 scripts/ab-storage-stream.sh    # 追加内存实证：-Xmx 收紧下载大对象不 OOM
#   AB_STREAM_BIG_MB=192 AB_STREAM_MEM=1 scripts/ab-storage-stream.sh
#
# 前置：dev 栈（PG/Redis/docreader）在位、OrbStack 可用、AB_STREAM_*_PORT 空闲。
# 产物：/tmp/ab-storage-stream/（两侧 headers 与 body；日志 /tmp/ragagent-java-server.log）
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=dev-env.sh
source "${SCRIPT_DIR}/scripts/dev-env.sh"

BUCKET="${AB_STREAM_BUCKET:-weknora-ab}"
# 对象 key 必须带**租户前缀**（两侧布局同为 {tenant}/…）：/files 的路由会校验
# file_path 里的租户与 token 租户一致（不带前缀 → 双端同为 403 forbidden: file path not accessible）
TENANT="${AB_STREAM_TENANT:-10002}"
KEY_SMALL="${AB_STREAM_SMALL_KEY:-${TENANT}/exports/stream-small.bin}"
KEY_BIG="${AB_STREAM_BIG_KEY:-${TENANT}/exports/stream-big.bin}"
BIG_MB="${AB_STREAM_BIG_MB:-192}"
MEM_PROOF="${AB_STREAM_MEM:-0}"
SMALL_HEAP="${AB_STREAM_HEAP:-96m}"
# 端口默认避开本机常驻服务：9000/9001 是本机 rustfs、18080-18082 被 rocketmq 占用
MINIO_PORT="${AB_STREAM_MINIO_PORT:-9100}"
MINIO_CONSOLE_PORT="${AB_STREAM_MINIO_CONSOLE_PORT:-9101}"
GO_P="${AB_STREAM_GO_PORT:-19080}"
JAVA_P="${AB_STREAM_JAVA_PORT:-19082}"
WORK="${AB_STREAM_WORK:-/tmp/ab-storage-stream}"
MINIO_USER="${AB_STREAM_MINIO_USER:-minioadmin}"
MINIO_PASS="${AB_STREAM_MINIO_PASS:-minioadmin}"
# 原生 minio/mc（docker 镜像站对本镜像 403；dl.min.io 的 darwin 构建已 410）——
# brew install minio minio-mc
MINIO_BIN="${AB_STREAM_MINIO_BIN:-$(command -v minio)}"
MC_BIN="${AB_STREAM_MC_BIN:-$(command -v mc)}"

export SYSTEM_AES_KEY="${SYSTEM_AES_KEY:-w5c-ab-aes-key-0123456789abcdef}"

fail=0
mkdir -p "${WORK}"

echo "==> 1) MinIO 起服 + 造桶/上传对象（原生：${MINIO_BIN:-未找到}）"
[ -n "${MINIO_BIN}" ] && [ -n "${MC_BIN}" ] || {
  echo "缺 minio/mc：brew install minio minio-mc"; exit 1;
}
if pgrep -f "minio server .*--address :${MINIO_PORT}" >/dev/null 2>&1; then
  echo "    （已有 minio 在 ${MINIO_PORT}，复用）"
else
  MINIO_ROOT_USER="${MINIO_USER}" MINIO_ROOT_PASSWORD="${MINIO_PASS}" \
    nohup "${MINIO_BIN}" server "${WORK}/minio-data" --address ":${MINIO_PORT}" \
    --console-address ":${MINIO_CONSOLE_PORT}" > "${WORK}/minio.log" 2>&1 &
  echo $! > "${WORK}/minio.pid"
fi
ready=0
for _ in $(seq 1 30); do
  if curl -s -o /dev/null --max-time 2 "http://localhost:${MINIO_PORT}/minio/health/live"; then ready=1; break; fi
  sleep 1
done
[ "${ready}" = "1" ] || { echo "MinIO 健康检查超时（见 ${WORK}/minio.log）"; exit 1; }

LOCAL_SMALL="${WORK}/$(basename "${KEY_SMALL}")"
LOCAL_BIG="${WORK}/$(basename "${KEY_BIG}")"
head -c 4096 /dev/urandom > "${LOCAL_SMALL}"
"${MC_BIN}" alias set ab "http://localhost:${MINIO_PORT}" "${MINIO_USER}" "${MINIO_PASS}" >/dev/null \
  || { echo "mc alias 失败"; exit 1; }
"${MC_BIN}" mb -p "ab/${BUCKET}" >/dev/null 2>&1 || true
"${MC_BIN}" cp "${LOCAL_SMALL}" "ab/${BUCKET}/${KEY_SMALL}" >/dev/null \
  || { echo "mc 上传小对象失败"; exit 1; }
if [ "${MEM_PROOF}" = "1" ]; then
  echo "    造 ${BIG_MB}MB 大对象（内存实证用）"
  head -c $((BIG_MB * 1024 * 1024)) /dev/urandom > "${LOCAL_BIG}"
  "${MC_BIN}" cp "${LOCAL_BIG}" "ab/${BUCKET}/${KEY_BIG}" >/dev/null \
    || { echo "mc 上传大对象失败"; exit 1; }
fi

echo "==> 2) 双侧起服（同指 ${BUCKET}，STORAGE_TYPE=minio）"
export STORAGE_TYPE=minio
# minio-go 要求 host:port（带 scheme 会 "Endpoint url cannot have fully qualified paths"）；
# 本仓 FileServiceFactory 对缺 scheme 的 endpoint 会补 http://（对照 Go 的 Secure 开关）→ 两兼容
export MINIO_ENDPOINT="localhost:${MINIO_PORT}"
export MINIO_ACCESS_KEY_ID="${MINIO_USER}"
export MINIO_SECRET_ACCESS_KEY="${MINIO_PASS}"
export MINIO_BUCKET_NAME="${BUCKET}"
# 两侧都把 loopback 从 SSRF 守卫里放行（Go: "unsafe MinIO endpoint: SSRF validation failed:
# hostname localhost is restricted"；Java 的 SsrfGuard 同款机器名清单）
export SSRF_WHITELIST_EXTRA="${SSRF_WHITELIST_EXTRA:-localhost,127.0.0.1}"
export GO_PORT="${GO_P}" JAVA_PORT="${JAVA_P}"
lsof -ti ":${GO_P}" 2>/dev/null | xargs kill >/dev/null 2>&1 || true
lsof -ti ":${JAVA_P}" 2>/dev/null | xargs kill >/dev/null 2>&1 || true
sleep 2

# kg 场景要求"租户默认 backend = minio"：Go 的 resolveFileService 按 KB/租户默认解析
# （**不看 path scheme**，见 known-issues/08 的备案），Java 按 scheme——故起服前临时翻转，
# 结束（trap）还原。
if [ "${AB_STREAM_KG:-0}" = "1" ]; then
  export PGPASSWORD="${PGPASSWORD:-postgres123!@#}"
  PSQL="psql -q -t -h ${DB_HOST:-localhost} -p ${DB_PORT:-15432} -U ${DB_USER:-postgres} -d ${DB_NAME:-WeKnora}"
  MINIO_BACKEND_ID="$(${PSQL} -c "select id from storage_backends where tenant_id=${TENANT} and provider='minio' order by updated_at desc limit 1;" | tr -d ' ')"
  PREV_DEFAULT="$(${PSQL} -c "select coalesce(default_storage_backend_id::text,'') from tenants where id=${TENANT};" | tr -d ' ')"
  restore_default_backend() {
    if [ -n "${PREV_DEFAULT}" ]; then
      ${PSQL} -c "UPDATE tenants SET default_storage_backend_id='${PREV_DEFAULT}' WHERE id=${TENANT};" >/dev/null 2>&1 || true
    else
      ${PSQL} -c "UPDATE tenants SET default_storage_backend_id=NULL WHERE id=${TENANT};" >/dev/null 2>&1 || true
    fi
  }
  trap restore_default_backend EXIT
  if [ -n "${MINIO_BACKEND_ID}" ]; then
    ${PSQL} -c "UPDATE tenants SET default_storage_backend_id='${MINIO_BACKEND_ID}' WHERE id=${TENANT};" >/dev/null
    echo "    （租户 ${TENANT} 默认 backend 临时切到 minio 行；结束还原为 '${PREV_DEFAULT}'）"
  else
    echo "    找不到 minio 的实例行 → kg 场景跳过"
    AB_STREAM_KG=0
  fi
fi

# Go 直接起二进制（不走 go-server-up.sh：那会重复构建并写共享日志 /tmp/weknora-go-server.log）
# dev-env 在本仓有 .env 时不设 WEKNORA_ROOT（其 37-43 行分支）→ 这里自兜底
GO_ROOT="${AB_STREAM_GO_ROOT:-${WEKNORA_ROOT:-$(cd "${SCRIPT_DIR}/../WeKnora" 2>/dev/null && pwd || true)}}"
[ -n "${GO_ROOT}" ] || { echo "找不到 Go 仓（../WeKnora）"; exit 1; }
GO_BIN="${GO_ROOT}/bin/weknora-server"
if [ ! -x "${GO_BIN}" ]; then
  echo "    构建 Go server（首次较慢）"
  ( cd "${GO_ROOT}" && go build -o bin/weknora-server ./cmd/server ) || { echo "Go 构建失败"; exit 1; }
fi
( cd "${GO_ROOT}" && SERVER_PORT="${GO_P}" nohup "${GO_BIN}" > "${WORK}/go-server.log" 2>&1 & echo $! > "${WORK}/go.pid" )
gready=0
for _ in $(seq 1 40); do
  if curl -s -o /dev/null --max-time 2 "http://localhost:${GO_P}/api/v1/knowledge-bases"; then gready=1; break; fi
  sleep 1
done
[ "${gready}" = "1" ] || { echo "Go 起服失败（见 ${WORK}/go-server.log）"; exit 1; }

bash "${SCRIPT_DIR}/scripts/java-server-up.sh" || { echo "Java 起服失败"; exit 1; }

GTOK="$(bash "${SCRIPT_DIR}/scripts/token.sh" "${GO_P}")"
JTOK="$(bash "${SCRIPT_DIR}/scripts/token.sh" "${JAVA_P}")"

mask_hdr() {
  grep -viE '^(date|x-request-id|keep-alive|connection|vary:|HTTP/)' "$1" 2>/dev/null \
    | sed -E 's/^(Content-Type:[^;]*); ?charset=/\1; charset=/I' \
    | grep -v '^$' | sort
}

fetch() { # port, token, key, tag
  curl -sD "${WORK}/$4.h" -o "${WORK}/$4.bin" \
    -H "Authorization: Bearer $2" \
    "http://localhost:$1/files?file_path=minio://${BUCKET}/$3"
}

echo "==> 3) 对拍：GET /files?file_path=minio://${BUCKET}/${KEY_SMALL}"
fetch "${GO_P}" "${GTOK}" "${KEY_SMALL}" go
fetch "${JAVA_P}" "${JTOK}" "${KEY_SMALL}" java
for side in go java; do
  echo "    --- ${side}: 关键头 ---"
  grep -iE '^(HTTP/|accept-ranges|content-length|transfer-encoding|content-type|content-disposition)' \
    "${WORK}/${side}.h" | sed 's/^/        /'
done
if diff <(mask_hdr "${WORK}/go.h") <(mask_hdr "${WORK}/java.h") > "${WORK}/hdr.diff"; then
  echo "    MATCH 头（归一化后逐行一致）"
else
  echo "    DIFF  头："; sed 's/^/        /' "${WORK}/hdr.diff"; fail=1
fi
if cmp -s "${WORK}/go.bin" "${WORK}/java.bin"; then
  echo "    MATCH 体（逐字节一致，$(( $(wc -c < "${WORK}/go.bin") )) 字节）"
else
  echo "    DIFF  体：go=$(shasum -a256 < "${WORK}/go.bin" | cut -c1-16) java=$(shasum -a256 < "${WORK}/java.bin" | cut -c1-16)"; fail=1
fi

echo "==> 3b) Range 对拍：Range: bytes=0-99（期望 206 + Content-Range + 切片体）"
fetch_range() { # port, token, key, tag
  curl -sD "${WORK}/$4.range.h" -o "${WORK}/$4.range.bin" \
    -H "Authorization: Bearer $2" -H "Range: bytes=0-99" \
    "http://localhost:$1/files?file_path=minio://${BUCKET}/$3"
}
fetch_range "${GO_P}" "${GTOK}" "${KEY_SMALL}" go
fetch_range "${JAVA_P}" "${JTOK}" "${KEY_SMALL}" java
for side in go java; do
  echo "    --- ${side}: Range 关键头 ---"
  grep -iE '^(HTTP/|accept-ranges|content-range|content-length)' "${WORK}/${side}.range.h" \
    | sed 's/^/        /'
done
if diff <(mask_hdr "${WORK}/go.range.h") <(mask_hdr "${WORK}/java.range.h") > "${WORK}/range-hdr.diff"; then
  echo "    MATCH 头（含 Content-Range）"
else
  echo "    DIFF  头："; sed 's/^/        /' "${WORK}/range-hdr.diff"; fail=1
fi
if cmp -s "${WORK}/go.range.bin" "${WORK}/java.range.bin"; then
  echo "    MATCH 体（切片 $(( $(wc -c < "${WORK}/go.range.bin") )) 字节）"
else
  echo "    DIFF  体：go=$(( $(wc -c < "${WORK}/go.range.bin") ))B java=$(( $(wc -c < "${WORK}/java.range.bin") ))B"; fail=1
fi

if [ "${AB_STREAM_KG:-0}" = "1" ]; then
  echo "==> 4) kg 场景（云引用）：/api/v1/knowledge/<id>/download 双端对拍"
  OWNER_UID="$(${PSQL} -c "select id from users where email='${TEST_EMAIL}' limit 1;" | tr -d ' ')"
  KB_ID="ab000000-0000-0000-0000-00000000ab01"
  KG_ID="ab000000-0000-0000-0000-00000000ab02"
  cleanup_kg() {
    ${PSQL} -c "DELETE FROM knowledges WHERE id='${KG_ID}';" >/dev/null 2>&1 || true
    ${PSQL} -c "DELETE FROM knowledge_bases WHERE id='${KB_ID}';" >/dev/null 2>&1 || true
  }
  # 同时还原租户默认 backend（起服前翻转的那一处）
  trap 'cleanup_kg; restore_default_backend' EXIT
  ${PSQL} -c "DELETE FROM knowledges WHERE id='${KG_ID}';" >/dev/null 2>&1 || true
  ${PSQL} -c "DELETE FROM knowledge_bases WHERE id='${KB_ID}';" >/dev/null 2>&1 || true
  ${PSQL} -c "INSERT INTO knowledge_bases (id, name, tenant_id, description, creator_id, embedding_model_id, summary_model_id) VALUES ('${KB_ID}','ab-stream-kg',${TENANT},'ab-stream','${OWNER_UID}','','');" >/dev/null \
    || { echo "    INSERT KB 失败"; fail=1; }
  ${PSQL} -c "INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, source, file_path, file_name) VALUES ('${KG_ID}',${TENANT},'${KB_ID}','document','ab-stream-kg','file','minio://${BUCKET}/${KEY_SMALL}','stream-small.bin');" >/dev/null \
    || { echo "    INSERT knowledge 失败"; fail=1; }

  kg_fetch() { # $1 port, $2 token, $3 tag, $4 range（可空）
    local args=(-sD "${WORK}/$3.h" -o "${WORK}/$3.bin" -H "Authorization: Bearer $2")
    if [ -n "${4:-}" ]; then
      args+=(-H "Range: $4")
    fi
    curl "${args[@]}" "http://localhost:$1/api/v1/knowledge/${KG_ID}/download"
  }
  kg_fetch "${GO_P}" "${GTOK}" go-kg ""
  kg_fetch "${JAVA_P}" "${JTOK}" java-kg ""
  kg_fetch "${GO_P}" "${GTOK}" go-kg-range "bytes=0-99"
  kg_fetch "${JAVA_P}" "${JTOK}" java-kg-range "bytes=0-99"

  for pair in "go-kg java-kg" "go-kg-range java-kg-range"; do
    read -r g j <<< "${pair}"
    for s in "${g}" "${j}"; do
      grep -iE '^(HTTP/|accept-ranges|content-range|content-length|content-disposition)' \
        "${WORK}/${s}.h" 2>/dev/null | sed "s/^/        ${s} /"
    done
    if diff <(mask_hdr "${WORK}/${g}.h") <(mask_hdr "${WORK}/${j}.h") > "${WORK}/${g}.diff"; then
      echo "        MATCH 头（${g} vs ${j}）"
    else
      echo "        DIFF  头（${g} vs ${j}）："; sed 's/^/          /' "${WORK}/${g}.diff"; fail=1
    fi
    if cmp -s "${WORK}/${g}.bin" "${WORK}/${j}.bin"; then
      echo "        MATCH 体（$(( $(wc -c < "${WORK}/${g}.bin") )) 字节）"
    else
      echo "        DIFF  体（go=$(( $(wc -c < "${WORK}/${g}.bin") ))B java=$(( $(wc -c < "${WORK}/${j}.bin") ))B）"; fail=1
    fi
  done
  cleanup_kg
  restore_default_backend
  trap - EXIT
fi

if [ "${MEM_PROOF}" = "1" ]; then
  echo "==> 5) 内存实证：Java 侧 -Xmx${SMALL_HEAP} 下载 ${BIG_MB}MB 对象"
  lsof -ti ":${JAVA_P}" 2>/dev/null | xargs kill >/dev/null 2>&1 || true
  sleep 2
  rm -f /tmp/ragagent-java-server.log
  JAVA_TOOL_OPTIONS="-Xmx${SMALL_HEAP}" bash "${SCRIPT_DIR}/scripts/java-server-up.sh" \
    || { echo "Java 起服失败（小堆）"; exit 1; }
  JTOK="$(bash "${SCRIPT_DIR}/scripts/token.sh" "${JAVA_P}")"
  code=$(curl -s -o "${WORK}/java-big.bin" -w '%{http_code}' \
    -H "Authorization: Bearer ${JTOK}" \
    "http://localhost:${JAVA_P}/files?file_path=minio://${BUCKET}/${KEY_BIG}")
  echo "        HTTP ${code}（期望 200）"
  if [ "${code}" = "200" ] && cmp -s <(head -c $((BIG_MB * 1024 * 1024)) "${LOCAL_BIG}") "${WORK}/java-big.bin"; then
    echo "        MATCH 体（${BIG_MB}MB 逐字节一致）"
  else
    echo "        DIFF/FAIL 体"; fail=1
  fi
  if grep -qi "OutOfMemoryError" /tmp/ragagent-java-server.log; then
    echo "        FAIL 服务端出现 OutOfMemoryError（不是流式）"; fail=1
  else
    echo "        OK 无 OutOfMemoryError（-Xmx${SMALL_HEAP} 也能下发 ${BIG_MB}MB）"
  fi
fi

echo
if [ "${fail}" = "0" ]; then
  echo "AB PASS：存储读路径流式化双端一致${MEM_PROOF:+（含内存实证）}"
else
  echo "AB FAIL（产物在 ${WORK}）"
fi
echo "提示：minio 仍在 ${MINIO_PORT}/${MINIO_CONSOLE_PORT}（原生进程；pid 见 ${WORK}/minio.pid，重启机器即消失）"
exit "${fail}"
