#!/usr/bin/env bash
# 录基础设施配置三组（波 2 第五批）：/web-search-providers(11) + /vector-stores(9)
# + /storage-backends(9) + 旧版 /web-search/providers(1) 的 golden。
#
# 场景设计（约定 §4 / §9；录制顺序影响状态，契约测试必须严格复刻本顺序）：
#   - 全部 golden 都是**确定性分支**：test 端点只录"构造失败 / SSRF 拒绝 / 环回
#     端口拒连"（Go dev 无 SSRF_WHITELIST，机器 DNS 把一切主机名解析到 198.18.0.0/15
#     假段 → 任何公网目标也落 SSRF 拒绝）。绝不录真实外网成功路径。
#   - wsp PUT 的 **created_at 清零**：repo Update 用 Select("*") 写全新实体 →
#     created_at 被写成 Go 零值，PUT 响应（re-fetch）与之后所有 GET 都是
#     "0001-01-01T00:00:00Z"——真实 Go 行为，Java 必须复刻（掩码正则不遮 year-1）。
#   - wsp PUT 的参数合并：api_key 强制保留、extra_config 为 nil 时保留、
#     **base_url/engine_id/proxy_url 不保留**（请求缺省即清空）。
#   - is_default：create with is_default=true 会清掉同租户其它默认（ClearDefault）。
#   - vs：本部署 RETRIEVE_DRIVER 为空 → env stores 不存在（与 §9 kb golden 的
#     部署漂移同族）：GET /__env_*__ → 404，PUT/DELETE /__env_*__ → 400 readonly
#     （前缀判定先于存在性查询）。test-by-id 的连接拒绝走**环回死端口 19214**。
#   - sb：本地 provider 走 LOCAL_STORAGE_BASE_DIR 的 mkdir+连通检查 → 可确定性成功；
#     minio/s3 等远端 provider 只有"校验/SSRF/immutable"失败形态可录。
#     System LOCAL（source=env, legacy_alias=true）是 dev PG 既有行（不可删改），
#     tenants.default_storage_backend_id 指向它——录制中途 setdefault 会改租户默认，
#     **收尾必须还原**（幂等清理的一部分）。
#   - 幂等清理：wsp-%/probe 行、固定 id 种子行（wsp_/vs_/sb_ 前缀名），租户默认还原。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${INFRA_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${INFRA_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"

UNKNOWN="11111111-2222-3333-4444-999999999999"
# 固定 id 种子（**纯十六进制**——掩码正则认 [0-9a-f-]，§9 chunk 波教训）
CROSS_WSP="be000003-0000-0000-0000-000000000001"   # 租户 10000 的 wsp 行
VS_ES="be000001-0000-0000-0000-000000000001"       # ES 向量库种子（环回死端口）
SB_MINIO="be000002-0000-0000-0000-000000000001"    # minio 存储后端种子（immutable 用例）
SYS_LOCAL="c730730a-70f5-4d86-a7e1-58972cf27567"   # dev PG 既有的 System LOCAL 行

req() {
  local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "${AUTH}" "$@"
}
reqv() { local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "Authorization: Bearer ${VTOKEN}" "$@"
}
reqn() { local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" "$@"
}

echo "==> 准备：token"
AUTH="Authorization: Bearer $(login "${TEST_EMAIL}" "${PORT}")"
VTOKEN="$(login "${TEST_VIEWER_EMAIL}" "${PORT}")"

echo "==> 幂等清理（录制产物 + 租户默认还原）"
${PSQL} >/dev/null 2>&1 <<SQL
DELETE FROM web_search_providers WHERE tenant_id IN (10002, 10000) AND (name LIKE 'wsp-%' OR id = '${CROSS_WSP}');
DELETE FROM vector_stores WHERE tenant_id = 10002 AND (name LIKE 'vs-%' OR id = '${VS_ES}');
DELETE FROM storage_backends WHERE tenant_id = 10002 AND (name LIKE 'sb-%' OR id = '${SB_MINIO}');
UPDATE tenants SET default_storage_backend_id = '${SYS_LOCAL}' WHERE id = 10002;
SQL
# 恢复被软删的种子行（幂等重录）
${PSQL} >/dev/null 2>&1 <<SQL
UPDATE web_search_providers SET deleted_at = NULL WHERE id = '${CROSS_WSP}' AND deleted_at IS NOT NULL;
UPDATE vector_stores SET deleted_at = NULL WHERE id = '${VS_ES}' AND deleted_at IS NOT NULL;
UPDATE storage_backends SET deleted_at = NULL WHERE id = '${SB_MINIO}' AND deleted_at IS NOT NULL;
SQL

echo "==> 种子：跨租户 wsp / ES 向量库 / minio 存储后端"
${PSQL} >/dev/null <<SQL
INSERT INTO web_search_providers (id, tenant_id, name, provider, description, parameters, is_default) VALUES
('${CROSS_WSP}', 10000, 'wsp-foreign', 'duckduckgo', '', '{}', false)
ON CONFLICT (id) DO UPDATE SET deleted_at = NULL;
INSERT INTO vector_stores (id, tenant_id, name, engine_type, connection_config, index_config) VALUES
('${VS_ES}', 10002, 'vs-golden-es', 'elasticsearch',
 '{"addr":"http://127.0.0.1:19214","username":"elastic","password":"vs-seed-secret"}',
 '{"index_name":"vs-golden-idx"}')
ON CONFLICT (id) DO UPDATE SET deleted_at = NULL;
INSERT INTO storage_backends (id, tenant_id, name, provider, config, source, status, legacy_alias) VALUES
('${SB_MINIO}', 10002, 'sb-golden-minio', 'minio',
 '{"mode":"remote","endpoint":"http://127.0.0.1:19314","access_key_id":"sb-seed-ak","secret_access_key":"sb-seed-sk","bucket_name":"sb-golden-bucket","path_prefix":"pp"}',
 'user', 'active', false)
ON CONFLICT (id) DO UPDATE SET deleted_at = NULL;
SQL

echo "==> 1) 静态元数据：types 与旧版 /web-search/providers"
req wsp-types.json            GET "/web-search-providers/types"
req wsp-legacy-providers.json GET "/web-search/providers"
req vs-types.json             GET "/vector-stores/types"
req sb-types.json             GET "/storage-backends/types"

echo "==> 2) wsp：创建（binding / service 校验 / 各 provider 确定性失败）"
req wsp-create-empty.json        POST "/web-search-providers" -H 'Content-Type: application/json' -d ''
req wsp-create-missing-all.json  POST "/web-search-providers" -H 'Content-Type: application/json' -d '{}'
req wsp-create-bad-type.json     POST "/web-search-providers" -H 'Content-Type: application/json' -d '{"name":"x","provider":"bogus"}'
req wsp-create-brave-nokey.json  POST "/web-search-providers" -H 'Content-Type: application/json' -d '{"name":"x","provider":"brave"}'
req wsp-create-zhipu-badengine.json POST "/web-search-providers" -H 'Content-Type: application/json' -d '{"name":"x","provider":"zhipu","parameters":{"extra_config":{"search_engine":"bogus"}}}'
req wsp-create-searxng-nourl.json POST "/web-search-providers" -H 'Content-Type: application/json' -d '{"name":"x","provider":"searxng"}'
req wsp-create-searxng-ssrf.json  POST "/web-search-providers" -H 'Content-Type: application/json' -d '{"name":"x","provider":"searxng","parameters":{"base_url":"http://127.0.0.1:18999"}}'
req wsp-create-badproxy.json      POST "/web-search-providers" -H 'Content-Type: application/json' -d '{"name":"x","provider":"duckduckgo","parameters":{"proxy_url":"http://127.0.0.1:1080"}}'
reqv wsp-viewer-create.json       POST "/web-search-providers" -H 'Content-Type: application/json' -d '{"name":"x","provider":"duckduckgo"}'

echo "==> 3) wsp：创建成功（含默认抢占）+ 列表/详情"
req wsp-create-ok.json      POST "/web-search-providers" -H 'Content-Type: application/json' -d '{"name":"wsp-golden-ddg","provider":"duckduckgo","description":"d","parameters":{"base_url":"x","extra_config":{"k":"v"}},"is_default":false}'
DDG="$(python3 -c 'import json,sys; print(json.load(open("'"${OUT}"'/wsp-create-ok.json"))["data"]["id"])')"
req wsp-create-def1.json    POST "/web-search-providers" -H 'Content-Type: application/json' -d '{"name":"wsp-def-one","provider":"duckduckgo","is_default":true}'
DEF1="$(python3 -c 'import json,sys; print(json.load(open("'"${OUT}"'/wsp-create-def1.json"))["data"]["id"])')"
req wsp-create-def2.json    POST "/web-search-providers" -H 'Content-Type: application/json' -d '{"name":"wsp-def-two","provider":"duckduckgo","is_default":true}'
DEF2="$(python3 -c 'import json,sys; print(json.load(open("'"${OUT}"'/wsp-create-def2.json"))["data"]["id"])')"
req wsp-list-defaults.json  GET "/web-search-providers"
req wsp-get.json            GET "/web-search-providers/${DDG}"
req wsp-get-404.json        GET "/web-search-providers/${UNKNOWN}"
req wsp-cross-tenant.json   GET "/web-search-providers/${CROSS_WSP}"
reqn wsp-list-noauth.json   GET "/web-search-providers"

echo "==> 4) wsp：更新（合并语义 / created_at 清零 / 404）"
req wsp-update-404.json        PUT "/web-search-providers/${UNKNOWN}" -H 'Content-Type: application/json' -d '{"name":"x"}'
req wsp-update-preserve.json   PUT "/web-search-providers/${DEF1}" -H 'Content-Type: application/json' -d '{"name":"","description":"","parameters":{},"is_default":false}'
req wsp-get-after-update.json  GET "/web-search-providers/${DEF1}"
req wsp-update-params-merge.json PUT "/web-search-providers/${DDG}" -H 'Content-Type: application/json' -d '{"parameters":{"engine_id":"e2","api_key":"stale-key"}}'
req wsp-get-after-merge.json   GET "/web-search-providers/${DDG}"

echo "==> 5) wsp：凭据子资源"
req wsp-cred-put-status.json   PUT "/web-search-providers/${DDG}/credentials" -H 'Content-Type: application/json' -d '{}'
req wsp-cred-put.json          PUT "/web-search-providers/${DDG}/credentials" -H 'Content-Type: application/json' -d '{"api_key":"sk-golden-123"}'
req wsp-get-after-cred.json    GET "/web-search-providers/${DDG}"
req wsp-cred-put-badjson.json  PUT "/web-search-providers/${DDG}/credentials" -H 'Content-Type: application/json' -d ''
req wsp-cred-put-404.json      PUT "/web-search-providers/${UNKNOWN}/credentials" -H 'Content-Type: application/json' -d '{}'
req wsp-cred-delete-badfield.json DELETE "/web-search-providers/${DDG}/credentials/notkey"
req wsp-cred-delete.json       DELETE "/web-search-providers/${DDG}/credentials/api_key"
req wsp-get-after-clear.json   GET "/web-search-providers/${DDG}"
req wsp-cred-delete-404.json   DELETE "/web-search-providers/${UNKNOWN}/credentials/api_key"

echo "==> 6) wsp：test 端点（确定性分支）+ 删除"
req wsp-test-raw-unknown.json     POST "/web-search-providers/test" -H 'Content-Type: application/json' -d '{"provider":"nosuch","parameters":{}}'
req wsp-test-raw-searxng-empty.json POST "/web-search-providers/test" -H 'Content-Type: application/json' -d '{"provider":"searxng","parameters":{"base_url":""}}'
req wsp-test-raw-searxng-ssrf.json  POST "/web-search-providers/test" -H 'Content-Type: application/json' -d '{"provider":"searxng","parameters":{"base_url":"http://127.0.0.1:18999"}}'
req wsp-test-raw-zhipu-nokey.json   POST "/web-search-providers/test" -H 'Content-Type: application/json' -d '{"provider":"zhipu","parameters":{}}'
req wsp-test-raw-badjson.json       POST "/web-search-providers/test" -H 'Content-Type: application/json' -d ''
reqv wsp-test-viewer-denied.json    POST "/web-search-providers/test" -H 'Content-Type: application/json' -d '{"provider":"zhipu"}'
req wsp-test-byid-404.json          POST "/web-search-providers/${UNKNOWN}/test"
req wsp-delete.json                 DELETE "/web-search-providers/${DEF2}"
req wsp-delete-404.json             DELETE "/web-search-providers/${UNKNOWN}"
req wsp-get-after-delete.json       GET "/web-search-providers/${DEF2}"

echo "==> 7) vs：列表（env 空）/ env 形态 / 404"
req vs-list-empty.json        GET "/vector-stores"
req vs-get-env-404.json       GET "/vector-stores/__env_postgres__"
req vs-put-env-readonly.json  PUT "/vector-stores/__env_bogus__" -H 'Content-Type: application/json' -d '{"name":"x"}'
req vs-delete-env-readonly.json DELETE "/vector-stores/__env_bogus__"
req vs-test-env-404.json      POST "/vector-stores/__env_bogus__/test"
req vs-get-404.json           GET "/vector-stores/${UNKNOWN}"
req vs-update-404.json        PUT "/vector-stores/${UNKNOWN}" -H 'Content-Type: application/json' -d '{"name":"x"}'
req vs-test-byid-404.json     POST "/vector-stores/${UNKNOWN}/test"
reqn vs-list-noauth.json      GET "/vector-stores"

echo "==> 8) vs：创建（binding / 引擎白名单 / SSRF / 必填顺序）"
req vs-create-empty.json        POST "/vector-stores" -H 'Content-Type: application/json' -d '{}'
req vs-create-sqlite.json       POST "/vector-stores" -H 'Content-Type: application/json' -d '{"name":"x","engine_type":"sqlite","connection_config":{}}'
req vs-create-postgres.json     POST "/vector-stores" -H 'Content-Type: application/json' -d '{"name":"x","engine_type":"postgres","connection_config":{"use_default_connection":true}}'
req vs-create-qdrant-missing-host.json POST "/vector-stores" -H 'Content-Type: application/json' -d '{"name":"x","engine_type":"qdrant","connection_config":{}}'
req vs-create-es-ssrf.json      POST "/vector-stores" -H 'Content-Type: application/json' -d '{"name":"x","engine_type":"elasticsearch","connection_config":{"addr":"http://127.0.0.1:19200"}}'
req vs-create-badindex-ssrf-first.json POST "/vector-stores" -H 'Content-Type: application/json' -d '{"name":"x","engine_type":"elasticsearch","connection_config":{"addr":"http://127.0.0.1:19200"},"index_config":{"index_name":"bad name!"}}'
reqv vs-viewer-create.json      POST "/vector-stores" -H 'Content-Type: application/json' -d '{"name":"x","engine_type":"qdrant","connection_config":{"host":"h"}}'

echo "==> 9) vs：种子行的 CRUD 与 test-by-id（环回拒连）"
req vs-get.json               GET "/vector-stores/${VS_ES}"
req vs-list-withdb.json       GET "/vector-stores"
req vs-test-byid-connrefused.json POST "/vector-stores/${VS_ES}/test"
req vs-put.json               PUT "/vector-stores/${VS_ES}" -H 'Content-Type: application/json' -d '{"name":"vs-golden-es-renamed"}'
req vs-put-badjson.json       PUT "/vector-stores/${VS_ES}" -H 'Content-Type: application/json' -d ''
req vs-test-raw-postgres.json POST "/vector-stores/test" -H 'Content-Type: application/json' -d '{"engine_type":"postgres","connection_config":{"use_default_connection":true}}'
req vs-test-raw-es-missing-addr.json POST "/vector-stores/test" -H 'Content-Type: application/json' -d '{"engine_type":"elasticsearch","connection_config":{}}'
req vs-test-raw-es-ssrf.json  POST "/vector-stores/test" -H 'Content-Type: application/json' -d '{"engine_type":"elasticsearch","connection_config":{"addr":"http://127.0.0.1:19200"}}'
reqv vs-test-viewer-denied.json POST "/vector-stores/test" -H 'Content-Type: application/json' -d '{"engine_type":"elasticsearch","connection_config":{"addr":"http://127.0.0.1:19200"}}'
req vs-delete.json            DELETE "/vector-stores/${VS_ES}"
req vs-get-after-delete.json  GET "/vector-stores/${VS_ES}"

echo "==> 10) sb：列表（System LOCAL env 行 + 默认 id）/ 404 / binding"
req sb-list.json              GET "/storage-backends"
req sb-types-viewer.json      GET "/storage-backends/types"
req sb-get-404.json           GET "/storage-backends/${UNKNOWN}"
req sb-create-empty.json      POST "/storage-backends" -H 'Content-Type: application/json' -d '{}'
reqn sb-list-noauth.json     GET "/storage-backends"

echo "==> 11) sb：创建成功（local 可确定性连通）+ 校验失败形态"
req sb-create-local.json      POST "/storage-backends" -H 'Content-Type: application/json' -d '{"name":"sb-golden-local","provider":"local","config":{"path_prefix":"sbgolden","access_key_id":"sb-ak","secret_access_key":"sb-sk"}}'
SB_LOCAL="$(python3 -c 'import json,sys; print(json.load(open("'"${OUT}"'/sb-create-local.json"))["data"]["id"])')"
req sb-create-local-noprefix.json POST "/storage-backends" -H 'Content-Type: application/json' -d '{"name":"sb-golden-local2","provider":"local","config":{}}'
SB_LOCAL2="$(python3 -c 'import json,sys; print(json.load(open("'"${OUT}"'/sb-create-local-noprefix.json"))["data"]["id"])')"
req sb-create-dupname.json    POST "/storage-backends" -H 'Content-Type: application/json' -d '{"name":"sb-golden-local","provider":"local","config":{}}'
req sb-create-badprovider.json POST "/storage-backends" -H 'Content-Type: application/json' -d '{"name":"x","provider":"bogus","config":{}}'
req sb-create-badstatus.json  POST "/storage-backends" -H 'Content-Type: application/json' -d '{"name":"x","provider":"local","config":{},"status":"bogus"}'
req sb-create-path-traversal.json POST "/storage-backends" -H 'Content-Type: application/json' -d '{"name":"x","provider":"local","config":{"path_prefix":"../evil"}}'
req sb-create-minio-missing.json POST "/storage-backends" -H 'Content-Type: application/json' -d '{"name":"x","provider":"minio","config":{"endpoint":"http://minio.example.internal:9000","access_key_id":"k","secret_access_key":"s"}}'
req sb-create-oss-ssrf.json   POST "/storage-backends" -H 'Content-Type: application/json' -d '{"name":"x","provider":"oss","config":{"endpoint":"http://127.0.0.1:9000","region":"r","access_key_id":"k","secret_access_key":"s","bucket_name":"b"}}'
req sb-get.json               GET "/storage-backends/${SB_LOCAL}"
reqv sb-viewer-create.json    POST "/storage-backends" -H 'Content-Type: application/json' -d '{"name":"x","provider":"local","config":{}}'

echo "==> 12) sb：更新（env 只读 / immutable / *** 占位 / 停用）"
req sb-update-readonly-env.json PUT "/storage-backends/${SYS_LOCAL}" -H 'Content-Type: application/json' -d '{"name":"x","provider":"local","config":{}}'
req sb-update-immutable.json  PUT "/storage-backends/${SB_MINIO}" -H 'Content-Type: application/json' -d '{"name":"sb-golden-minio","provider":"minio","config":{"mode":"remote","endpoint":"http://other.example.internal:9000","bucket_name":"sb-golden-bucket","path_prefix":"pp"}}'
req sb-update-placeholder.json PUT "/storage-backends/${SB_LOCAL}" -H 'Content-Type: application/json' -d '{"name":"sb-golden-local","provider":"local","config":{"path_prefix":"sbgolden","access_key_id":"***","secret_access_key":"***"}}'
req sb-get-after-placeholder.json GET "/storage-backends/${SB_LOCAL}"
req sb-update-badstatus.json  PUT "/storage-backends/${SB_LOCAL}" -H 'Content-Type: application/json' -d '{"name":"sb-golden-local","provider":"local","config":{"path_prefix":"sbgolden"},"status":"bogus"}'
req sb-update-disable.json    PUT "/storage-backends/${SB_LOCAL}" -H 'Content-Type: application/json' -d '{"name":"sb-golden-local","provider":"local","config":{"path_prefix":"sbgolden"},"status":"disabled"}'
req sb-update-404.json        PUT "/storage-backends/${UNKNOWN}" -H 'Content-Type: application/json' -d '{"name":"x","provider":"local","config":{}}'

echo "==> 13) sb：默认语义（disabled 拒绝 / 抢占 / 默认不可删 / 还原）"
req sb-setdefault-disabled.json PUT "/storage-backends/${SB_LOCAL}/default"
req sb-setdefault.json        PUT "/storage-backends/${SB_LOCAL2}/default"
req sb-list-after-default.json GET "/storage-backends"
req sb-delete-default.json    DELETE "/storage-backends/${SB_LOCAL2}"
req sb-setdefault-restore.json PUT "/storage-backends/${SYS_LOCAL}/default"
req sb-test-raw-local.json    POST "/storage-backends/test" -H 'Content-Type: application/json' -d '{"name":"t","provider":"local","config":{"path_prefix":"sbgolden"}}'
req sb-test-raw-badprovider.json POST "/storage-backends/test" -H 'Content-Type: application/json' -d '{"name":"t","provider":"bogus","config":{}}'
req sb-test-raw-minio-missing.json POST "/storage-backends/test" -H 'Content-Type: application/json' -d '{"name":"t","provider":"minio","config":{"endpoint":"http://minio.example.internal:9000","access_key_id":"k","secret_access_key":"s"}}'
req sb-test-raw-ssrf.json     POST "/storage-backends/test" -H 'Content-Type: application/json' -d '{"name":"t","provider":"s3","config":{"endpoint":"http://127.0.0.1:9000","region":"r","access_key_id":"k","secret_access_key":"s","bucket_name":"b"}}'
req sb-test-raw-path-traversal.json POST "/storage-backends/test" -H 'Content-Type: application/json' -d '{"name":"t","provider":"local","config":{"path_prefix":"../evil"}}'
req sb-test-byid.json         POST "/storage-backends/${SB_LOCAL2}/test"
req sb-test-byid-404.json     POST "/storage-backends/${UNKNOWN}/test"

echo "==> 14) sb：删除守卫 + 清理"
req sb-delete-enabled.json    PUT "/storage-backends/${SB_LOCAL}" -H 'Content-Type: application/json' -d '{"name":"sb-golden-local","provider":"local","config":{"path_prefix":"sbgolden"},"status":"active"}'
req sb-delete.json            DELETE "/storage-backends/${SB_LOCAL}"
req sb-delete-404.json        DELETE "/storage-backends/${UNKNOWN}"
req sb-delete-seed.json       DELETE "/storage-backends/${SB_MINIO}"

echo "==> 收尾：清空录制态（契约测试从 SQL 种子重建，不依赖本段残留）"
${PSQL} >/dev/null 2>&1 <<SQL
DELETE FROM web_search_providers WHERE tenant_id IN (10002, 10000) AND (name LIKE 'wsp-%' OR id = '${CROSS_WSP}');
DELETE FROM vector_stores WHERE tenant_id = 10002 AND (name LIKE 'vs-%' OR id = '${VS_ES}');
DELETE FROM storage_backends WHERE tenant_id = 10002 AND (name LIKE 'sb-%' OR id = '${SB_MINIO}' OR id = '${SB_LOCAL}' OR id = '${SB_LOCAL2}');
UPDATE tenants SET default_storage_backend_id = '${SYS_LOCAL}' WHERE id = 10002;
SQL

echo "==> 完成：wsp=$(ls "${OUT}" | grep -c '^wsp-') vs=$(ls "${OUT}" | grep -c '^vs-') sb=$(ls "${OUT}" | grep -c '^sb-') golden"
