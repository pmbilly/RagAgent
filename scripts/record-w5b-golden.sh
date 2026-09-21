#!/usr/bin/env bash
# 录收尾批 W5b 的 golden：w5b-*（initialization 系统级 14 条：ollama 管理 6 +
# 模型测试 5 + 抽取 3）。
#
# 前置（与 scripts/record-ag-golden.sh 同族）：
#   - Go server 起在 :8080，且带 SSRF_WHITELIST_EXTRA=127.0.0.1（upstream 用例要
#     出站打本机 stub；不触外网）。
#   - 上游 stub：python3 scripts/stub-llm-server.py 8181 &（chat/embeddings/rerank/
#     audio 同一份剧本，Java 契约测试里有逐字节同款的 in-JVM 版）。
#   - ollama stub：DOWN 家族先录（不启 stub），再 python3 scripts/stub-ollama-server.py
#     11434 &（Go server 不设 OLLAMA_BASE_URL → 缺省 localhost:11434，与 Java 测试
#     JVM 的 in-JVM stub 同位）。
#
# 场景与 server/src/test/java/com/ragagent/agentm/W5bInitializationContractTest.java
# 一一对应；掩码：uuid/时间戳/ollama 错误内文。
#
# 用法：
#   scripts/record-w5b-golden.sh
#   W5B_TARGET_PORT=8082 W5B_OUT_DIR=/tmp/x scripts/record-w5b-golden.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${W5B_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${W5B_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"
mkdir -p "${OUT}"

STUB_LLM_PORT=8181
STUB_LLM="http://127.0.0.1:${STUB_LLM_PORT}/v1"

req() { local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" "$@"; }
json() { local out="$1" method="$2" path="$3" body="$4"; shift 4
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "Authorization: Bearer ${TOKEN}" \
    -H 'Content-Type: application/json' -d "${body}" "$@"; }
jsonv() { local out="$1" method="$2" path="$3" body="$4"; shift 4
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "Authorization: Bearer ${VTOKEN}" \
    -H 'Content-Type: application/json' -d "${body}" "$@"; }

AGU="a0000000-0000-0000-0000-000000000001"
AGV="a0000000-0000-0000-0000-000000000002"
MD_STUB="a0000000-0000-0000-0000-000000000402"
MD_ASR="a0000000-0000-0000-0000-000000000403"
MISSING_TASK="99999999-9999-9999-9999-999999999999"
# 1x1 PNG（与 W5bInitializationContractTest.TINY_PNG 同字节）
TINY_PNG_B64="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

echo "==> 幂等清理 + 种子（租户 10005）"
${PSQL} >/dev/null 2>&1 <<SQL
DELETE FROM chunks WHERE tenant_id IN (10005);
DELETE FROM knowledges WHERE tenant_id IN (10005);
DELETE FROM knowledge_bases WHERE tenant_id IN (10005);
DELETE FROM models WHERE tenant_id IN (10005);
DELETE FROM custom_agents WHERE tenant_id IN (10005);
DELETE FROM tenant_disabled_shared_agents WHERE tenant_id IN (10005);
DELETE FROM tenant_members WHERE tenant_id IN (10005);
DELETE FROM users WHERE tenant_id IN (10005);
DELETE FROM tenants WHERE id IN (10005);
INSERT INTO tenants (id, name, description, business, status) VALUES (10005, 'ag-batch-tenant', '', '', 'active');
INSERT INTO users (id, username, email, password_hash, tenant_id, is_active) VALUES
 ('${AGU}', 'agbatch', 'ag-batch@weknora.test', '\$2a\$10\$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK', 10005, true),
 ('${AGV}', 'agviewer', 'ag-batch-viewer@weknora.test', '\$2a\$10\$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK', 10005, true);
INSERT INTO tenant_members (user_id, tenant_id, role, status) VALUES
 ('${AGU}', 10005, 'owner', 'active'), ('${AGV}', 10005, 'viewer', 'active');
INSERT INTO models (id, tenant_id, type, name, source, description, parameters, is_default, status, created_at, updated_at) VALUES
 ('${MD_STUB}', 10005, 'KnowledgeQA', 'w5b-stub-llm', 'remote', 'W5B stub chat model', '{"base_url":"${STUB_LLM}"}', FALSE, 'active', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
 ('${MD_ASR}', 10005, 'ASR', 'w5b-asr-stored', 'remote', 'W5B stored ASR', '{}', FALSE, 'active', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00');
SQL

TOKEN="$(login "ag-batch@weknora.test" "${PORT}")"
VTOKEN="$(login "ag-batch-viewer@weknora.test" "${PORT}")"

echo "==> 1) ollama DOWN 家族（stub 未启动）"
req w5b-noauth.json GET /initialization/ollama/status
jsonv w5b-ollama-status-down.json GET /initialization/ollama/status '{}'
json w5b-ollama-models-down.json GET /initialization/ollama/models '{}'
json w5b-ollama-check-down.json POST /initialization/ollama/models/check '{"models":["stub-model"]}'
json w5b-ollama-download-down.json POST /initialization/ollama/models/download '{"modelName":"stub-model"}'
json w5b-progress-missing.json GET "/initialization/ollama/download/progress/${MISSING_TASK}" '{}'
json w5b-tasks-empty.json GET /initialization/ollama/download/tasks '{}'
jsonv w5b-viewer-post-403.json POST /initialization/remote/check '{}'

echo "==> 2) ollama UP 家族（11434 stub）"
OLLAMA_STUB_PID=""
if ! curl -s -o /dev/null --max-time 1 http://127.0.0.1:11434/ 2>/dev/null; then
  python3 "${SCRIPT_DIR}/scripts/stub-ollama-server.py" 11434 >/dev/null 2>&1 &
  OLLAMA_STUB_PID=$!
  sleep 1
fi
json w5b-ollama-status-up.json GET /initialization/ollama/status '{}'
json w5b-ollama-models.json GET /initialization/ollama/models '{}'
json w5b-ollama-check.json POST /initialization/ollama/models/check '{"models":["stub-model","absent-model"]}'
json w5b-ollama-check-badbody.json POST /initialization/ollama/models/check '{}'
json w5b-download-exists.json POST /initialization/ollama/models/download '{"modelName":"stub-model"}'
json w5b-download-created.json POST /initialization/ollama/models/download '{"modelName":"fresh-model"}'
TASK_ID="$(python3 -c "import json,sys;print(json.load(open('${OUT}/w5b-download-created.json'))['data']['taskId'])")"
for _ in $(seq 1 50); do
  BODY="$(curl -s "${API}/initialization/ollama/download/progress/${TASK_ID}" -H "Authorization: Bearer ${TOKEN}")"
  if echo "${BODY}" | grep -q '"status":"completed"\|"status":"failed"'; then break; fi
  sleep 0.2
done
printf '%s' "${BODY}" > "${OUT}/w5b-progress-done.json"
json w5b-tasks-one.json GET /initialization/ollama/download/tasks '{}'
if [ -n "${OLLAMA_STUB_PID}" ]; then kill "${OLLAMA_STUB_PID}" 2>/dev/null || true; fi

echo "==> 3) upstream 家族（stub-llm 8181）"
LLM_STUB_PID=""
if ! curl -s -o /dev/null --max-time 1 "http://127.0.0.1:${STUB_LLM_PORT}/" 2>/dev/null; then
  python3 "${SCRIPT_DIR}/scripts/stub-llm-server.py" "${STUB_LLM_PORT}" >/dev/null 2>&1 &
  LLM_STUB_PID=$!
  sleep 1
fi
# SSRF 拒绝（10.0.0.1 直连 IP 不在白名单 → 确定性 400，与白名单状态无关）
json w5b-remote-check-ssrf.json POST /initialization/remote/check "{\"modelName\":\"stub-model\",\"baseUrl\":\"http://10.0.0.1:9/v1\"}"
json w5b-embedding-ssrf.json POST /initialization/embedding/test "{\"modelName\":\"stub-model\",\"baseUrl\":\"http://10.0.0.1:9/v1\"}"

json w5b-remote-check-missing.json POST /initialization/remote/check '{}'
json w5b-remote-check-ok.json POST /initialization/remote/check "{\"modelName\":\"stub-model\",\"baseUrl\":\"${STUB_LLM}\"}"
json w5b-embedding-ok.json POST /initialization/embedding/test "{\"modelName\":\"stub-model\",\"baseUrl\":\"${STUB_LLM}\"}"
json w5b-embedding-aliyun.json POST /initialization/embedding/test "{\"provider\":\"aliyun\",\"modelName\":\"text-embedding-vision\",\"baseUrl\":\"${STUB_LLM}\"}"
json w5b-rerank-missing.json POST /initialization/rerank/check '{"modelName":"stub-model"}'
json w5b-rerank-ok.json POST /initialization/rerank/check "{\"modelName\":\"stub-model\",\"baseUrl\":\"${STUB_LLM}\"}"
json w5b-asr-missing.json POST /initialization/asr/check '{"modelName":"stub-model"}'
json w5b-asr-ok.json POST /initialization/asr/check "{\"apiKey\":\"sk-w5b\",\"modelName\":\"stub-model\",\"baseUrl\":\"${STUB_LLM}\"}"
json w5b-asr-401.json POST /initialization/asr/check "{\"apiKey\":\"sk-w5b\",\"modelName\":\"asr-401\",\"baseUrl\":\"${STUB_LLM}\"}"
json w5b-asr-404.json POST /initialization/asr/check "{\"apiKey\":\"sk-w5b\",\"modelName\":\"asr-404\",\"baseUrl\":\"${STUB_LLM}\"}"
json w5b-asr-modelmissing.json POST /initialization/asr/check "{\"apiKey\":\"sk-w5b\",\"modelName\":\"asr-modelmissing\",\"baseUrl\":\"${STUB_LLM}\"}"
json w5b-asr-500text.json POST /initialization/asr/check "{\"apiKey\":\"sk-w5b\",\"modelName\":\"asr-500text\",\"baseUrl\":\"${STUB_LLM}\"}"
json w5b-asr-storedkey.json POST /initialization/asr/check "{\"modelName\":\"stub-model\",\"modelId\":\"${MD_ASR}\",\"baseUrl\":\"${STUB_LLM}\"}"

json w5b-extract-badbody.json POST /initialization/extract/text-relation '{}'
LONG_TEXT="$(python3 -c "print('a'*5001)")"
json w5b-extract-toolong.json POST /initialization/extract/text-relation \
  "{\"text\":\"${LONG_TEXT}\",\"tags\":[\"Author\"],\"model_id\":\"${MD_STUB}\"}"
json w5b-extract-model-missing.json POST /initialization/extract/text-relation \
  '{"text":"x","tags":["Author"],"model_id":"nope"}'
GRAPH_TEXT='<<SCENARIO:graph>> 请从下面的文本中抽取实体与关系。'
GRAPH_JSON="$(python3 -c "import json,sys;print(json.dumps({'text':sys.argv[1],'tags':['Author'],'model_id':sys.argv[2]}))" "${GRAPH_TEXT}" "${MD_STUB}")"
json w5b-extract-graph.json POST /initialization/extract/text-relation "${GRAPH_JSON}"
json w5b-fabritext.json POST /initialization/extract/fabri-text \
  "{\"tags\":[\"Author\",\"Alias\"],\"model_id\":\"${MD_STUB}\"}"
json w5b-fabritext-model-missing.json POST /initialization/extract/fabri-text \
  '{"tags":[],"model_id":"nope"}'
json w5b-fabritag.json POST /initialization/extract/fabri-tag '{}'

echo "==> 4) multimodal 校验族（不触 docreader 的分支）"
PNG="$(mktemp /tmp/w5b-XXXX.png)"; printf '%s' "${TINY_PNG_B64}" | base64 -d > "${PNG}"
TXT="$(mktemp /tmp/w5b-XXXX.txt)"; printf 'hello' > "${TXT}"
curl -s -o "${OUT}/w5b-mm-missing-vlm.json" -w '%{http_code} %{url_effective}\n' \
  -X POST "${API}/initialization/multimodal/test" -H "Authorization: Bearer ${TOKEN}"
curl -s -o "${OUT}/w5b-mm-badstorage.json" -w '%{http_code} %{url_effective}\n' \
  -X POST "${API}/initialization/multimodal/test" -H "Authorization: Bearer ${TOKEN}" \
  -F "vlm_model=vlm-stub" -F "vlm_base_url=${STUB_LLM}" -F "storage_type=s3"
curl -s -o "${OUT}/w5b-mm-cos-incomplete.json" -w '%{http_code} %{url_effective}\n' \
  -X POST "${API}/initialization/multimodal/test" -H "Authorization: Bearer ${TOKEN}" \
  -F "vlm_model=vlm-stub" -F "vlm_base_url=${STUB_LLM}" -F "storage_type=cos"
curl -s -o "${OUT}/w5b-mm-minio-incomplete.json" -w '%{http_code} %{url_effective}\n' \
  -X POST "${API}/initialization/multimodal/test" -H "Authorization: Bearer ${TOKEN}" \
  -F "vlm_model=vlm-stub" -F "vlm_base_url=${STUB_LLM}" -F "storage_type=minio"
curl -s -o "${OUT}/w5b-mm-chunksize.json" -w '%{http_code} %{url_effective}\n' \
  -X POST "${API}/initialization/multimodal/test" -H "Authorization: Bearer ${TOKEN}" \
  -F "image=@${PNG};type=image/png" \
  -F "vlm_model=vlm-stub" -F "vlm_base_url=${STUB_LLM}" \
  -F "storage_type=minio" -F "minio_bucket_name=b" -F "chunk_size=abc"
curl -s -o "${OUT}/w5b-mm-chunkoverlap.json" -w '%{http_code} %{url_effective}\n' \
  -X POST "${API}/initialization/multimodal/test" -H "Authorization: Bearer ${TOKEN}" \
  -F "image=@${PNG};type=image/png" \
  -F "vlm_model=vlm-stub" -F "vlm_base_url=${STUB_LLM}" \
  -F "storage_type=minio" -F "minio_bucket_name=b" -F "chunk_size=1000" -F "chunk_overlap=abc"
curl -s -o "${OUT}/w5b-mm-badtype.json" -w '%{http_code} %{url_effective}\n' \
  -X POST "${API}/initialization/multimodal/test" -H "Authorization: Bearer ${TOKEN}" \
  -F "image=@${TXT};type=text/plain" \
  -F "vlm_model=vlm-stub" -F "vlm_base_url=${STUB_LLM}" \
  -F "storage_type=minio" -F "minio_bucket_name=b" \
  -F "chunk_size=1000" -F "chunk_overlap=200"
rm -f "${PNG}" "${TXT}"
if [ -n "${LLM_STUB_PID}" ]; then kill "${LLM_STUB_PID}" 2>/dev/null || true; fi
echo "==> W5b golden 录制完成：$(ls "${OUT}" | grep -c '^w5b-') 条"
