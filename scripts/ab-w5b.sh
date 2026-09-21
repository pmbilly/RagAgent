#!/usr/bin/env bash
# 收尾批 W5b A/B：initialization 系统级 14 条（ollama 管理 6 + 模型测试 5 + 抽取 3）。
#
# 前置（双端 + stub 同指，与 ab-qa46d.sh 同族）：
#   1) python3 scripts/stub-llm-server.py 8181 &        # chat/embeddings/rerank/audio
#   2) python3 scripts/stub-ollama-server.py 8182 &     # ollama 管理 API
#   3) SSRF_WHITELIST_EXTRA=127.0.0.1 OLLAMA_BASE_URL=http://127.0.0.1:8182 \
#        scripts/go-server-up.sh                        # 从干净进程起（downloadTasks 是进程内存）
#   4) SSRF_WHITELIST_EXTRA=127.0.0.1 OLLAMA_BASE_URL=http://127.0.0.1:8182 \
#        scripts/java-server-up.sh                      # lsof -ti :8082 先杀旧
#   5) 脚本自带 psql 种子（租户 10005，模型行指 8181 stub）。
#
# multimodal 的 docreader 分支（成功路径）两侧打同一 dev docreader → 逐字节（掩 ms）。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
GO="http://localhost:${GO_PORT}/api/v1"
JAVA="http://localhost:${JAVA_PORT}/api/v1"
GTOKEN="$(login "${TEST_EMAIL}" "${GO_PORT}")"
JTOKEN="$(login "${TEST_EMAIL}" "${JAVA_PORT}")"
OUT="${W5B_AB_OUT_DIR:-/tmp/w5b-ab}"
ROUNDS="${W5B_ROUNDS:-2}"
mkdir -p "${OUT}"
STUB_LLM="http://127.0.0.1:8181/v1"

export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"
AGU="a0000000-0000-0000-0000-000000000001"
AGV="a0000000-0000-0000-0000-000000000002"
MD_STUB="a0000000-0000-0000-0000-000000000402"
MD_ASR="a0000000-0000-0000-0000-000000000403"
TINY_PNG_B64="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

mask() { python3 -c '
import re,sys
s=sys.stdin.read()
s=re.sub(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}","<UUID>",s)
s=re.sub(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?(Z|[+-]\d{2}:\d{2})","<TS>",s)
s=re.sub(r"\"processing_time\":[0-9]+","\"processing_time\":<MS>",s)
s=re.sub(r"\"tags\":\[[^]]*\]","\"tags\":<TAGS>",s)
sys.stdout.write(s)'; }

pass=0; fail=0
ab_json() { # name method path body [token_suffix]
  local name="$1" method="$2" path="$3" body="${4:-}"
  local g j
  g="$(curl -s --max-time 60 -o "${OUT}/${name}.go.json" -w '%{http_code}' -X "${method}" "${GO}${path}" \
      -H "Authorization: Bearer ${GTOKEN}" ${body:+-H 'Content-Type: application/json' -d "${body}"})"
  j="$(curl -s --max-time 60 -o "${OUT}/${name}.java.json" -w '%{http_code}' -X "${method}" "${JAVA}${path}" \
      -H "Authorization: Bearer ${JTOKEN}" ${body:+-H 'Content-Type: application/json' -d "${body}"})"
  if [ "${g}" = "${j}" ] && diff -q <(mask < "${OUT}/${name}.go.json") <(mask < "${OUT}/${name}.java.json") >/dev/null 2>&1; then
    pass=$((pass+1)); echo "MATCH ${name} (${g})"
  else
    fail=$((fail+1)); echo "DIFF  ${name} go=${g} java=${j}"
  fi
}
ab_viewer() { # name method path body — viewer token 403 族
  local name="$1" method="$2" path="$3" body="${4:-}"
  local g j vt
  vt="$(login "${TEST_VIEWER_EMAIL}" "${GO_PORT}")"
  g="$(curl -s -o "${OUT}/${name}.go.json" -w '%{http_code}' -X "${method}" "${GO}${path}" \
      -H "Authorization: Bearer ${vt}" ${body:+-H 'Content-Type: application/json' -d "${body}"})"
  vt="$(login "${TEST_VIEWER_EMAIL}" "${JAVA_PORT}")"
  j="$(curl -s -o "${OUT}/${name}.java.json" -w '%{http_code}' -X "${method}" "${JAVA}${path}" \
      -H "Authorization: Bearer ${vt}" ${body:+-H 'Content-Type: application/json' -d "${body}"})"
  if [ "${g}" = "${j}" ] && diff -q <(mask < "${OUT}/${name}.go.json") <(mask < "${OUT}/${name}.java.json") >/dev/null 2>&1; then
    pass=$((pass+1)); echo "MATCH ${name} (${g})"
  else
    fail=$((fail+1)); echo "DIFF  ${name} go=${g} java=${j}"
  fi
}
ab_mm() { # name extra_curl_args...
  local name="$1"; shift
  local g j
  g="$(curl -s -o "${OUT}/${name}.go.json" -w '%{http_code}' -X POST "${GO}/initialization/multimodal/test" \
      -H "Authorization: Bearer ${GTOKEN}" "$@")"
  j="$(curl -s -o "${OUT}/${name}.java.json" -w '%{http_code}' -X POST "${JAVA}/initialization/multimodal/test" \
      -H "Authorization: Bearer ${JTOKEN}" "$@")"
  if [ "${g}" = "${j}" ] && diff -q <(mask < "${OUT}/${name}.go.json") <(mask < "${OUT}/${name}.java.json") >/dev/null 2>&1; then
    pass=$((pass+1)); echo "MATCH ${name} (${g})"
  else
    fail=$((fail+1)); echo "DIFF  ${name} go=${g} java=${j}"
  fi
}

echo "==> 种子（租户 10005；模型行指 stub）"
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
INSERT INTO tenants (id, name, description, business, status) VALUES (10005, 'w5b-ab-tenant', '', '', 'active');
INSERT INTO users (id, username, email, password_hash, tenant_id, is_active) VALUES
 ('${AGU}', 'w5bab', 'w5b-ab@weknora.test', '\$2a\$10\$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK', 10005, true),
 ('${AGV}', 'w5babv', 'w5b-ab-viewer@weknora.test', '\$2a\$10\$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK', 10005, true);
INSERT INTO tenant_members (user_id, tenant_id, role, status) VALUES
 ('${AGU}', 10005, 'owner', 'active'), ('${AGV}', 10005, 'viewer', 'active');
INSERT INTO models (id, tenant_id, type, name, source, description, parameters, is_default, status, created_at, updated_at) VALUES
 ('${MD_STUB}', 10005, 'KnowledgeQA', 'w5b-stub-llm', 'remote', 'W5B stub chat model', '{"base_url":"${STUB_LLM}"}', FALSE, 'active', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
 ('${MD_ASR}', 10005, 'ASR', 'w5b-asr-stored', 'remote', 'W5B stored ASR', '{}', FALSE, 'active', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00');
SQL
# A/B 登录改用 10005 账号（dev-env 默认 TEST_EMAIL 是 10002）
GTOKEN="$(curl -s -X POST "http://localhost:${GO_PORT}/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"w5b-ab@weknora.test","password":"Passw0rd!"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')"
JTOKEN="$(curl -s -X POST "http://localhost:${JAVA_PORT}/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"w5b-ab@weknora.test","password":"Passw0rd!"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')"

PNG="$(mktemp /tmp/w5b-ab-XXXX.png)"; printf '%s' "${TINY_PNG_B64}" | base64 -d > "${PNG}"
TXT="$(mktemp /tmp/w5b-ab-XXXX.txt)"; printf 'hello' > "${TXT}"

for round in $(seq 1 "${ROUNDS}"); do
  echo "==> Round ${round}"
  # ── ollama 管理段 ──
  ab_json w5b-ab-status       GET  /initialization/ollama/status
  ab_json w5b-ab-models       GET  /initialization/ollama/models
  ab_json w5b-ab-check        POST /initialization/ollama/models/check '{"models":["stub-model","absent-model"]}'
  ab_json w5b-ab-check-bad    POST /initialization/ollama/models/check '{}'
  ab_json w5b-ab-dl-exists    POST /initialization/ollama/models/download '{"modelName":"stub-model"}'
  ab_json w5b-ab-dl-create    POST /initialization/ollama/models/download '{"modelName":"fresh-model"}'
  # 轮询两侧到终态再比 progress/tasks（taskId/时间戳掩码）
  poll_side() { local base="$1" token="$2" task="$3"
    for _ in $(seq 1 50); do
      local b
      b="$(curl -s "${base}/initialization/ollama/download/progress/${task}" -H "Authorization: Bearer ${token}")"
      if echo "${b}" | grep -q '"status":"completed"\|"status":"failed"'; then echo "${b}"; return; fi
      sleep 0.2
    done
    echo "{}"
  }
  GT="$(python3 -c "import json,sys;print(json.load(open('${OUT}/w5b-ab-dl-create.go.json'))['data']['taskId'])")"
  JT="$(python3 -c "import json,sys;print(json.load(open('${OUT}/w5b-ab-dl-create.java.json'))['data']['taskId'])")"
  poll_side "${GO}" "${GTOKEN}" "${GT}" > "${OUT}/w5b-ab-progress.go.json"
  poll_side "${JAVA}" "${JTOKEN}" "${JT}" > "${OUT}/w5b-ab-progress.java.json"
  if diff -q <(mask < "${OUT}/w5b-ab-progress.go.json") <(mask < "${OUT}/w5b-ab-progress.java.json") >/dev/null; then
    pass=$((pass+1)); echo "MATCH w5b-ab-progress"
  else
    fail=$((fail+1)); echo "DIFF  w5b-ab-progress"
  fi
  # tasks 跨轮累积且 Go map 迭代序随机 → 掩码后按元素排序再比对（数量按各自历史）
  norm_tasks() { python3 -c '
import json,sys,re
d=json.load(open(sys.argv[1]))
arr=sorted(json.dumps(t,ensure_ascii=False,sort_keys=True) for t in d.get("data",[]))
s=json.dumps({"data":arr,"success":d.get("success")},ensure_ascii=False)
s=re.sub(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}","<UUID>",s)
s=re.sub(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?(Z|[+-]\d{2}:\d{2})","<TS>",s)
print(s)' "$1"; }
  curl -s -o "${OUT}/w5b-ab-tasks.go.raw" "${GO}/initialization/ollama/download/tasks" -H "Authorization: Bearer ${GTOKEN}"
  curl -s -o "${OUT}/w5b-ab-tasks.java.raw" "${JAVA}/initialization/ollama/download/tasks" -H "Authorization: Bearer ${JTOKEN}"
  if diff -q <(norm_tasks "${OUT}/w5b-ab-tasks.go.raw") <(norm_tasks "${OUT}/w5b-ab-tasks.java.raw") >/dev/null; then
    pass=$((pass+1)); echo "MATCH w5b-ab-tasks"
  else
    fail=$((fail+1)); echo "DIFF  w5b-ab-tasks"
  fi
  ab_json w5b-ab-progress-missing GET "/initialization/ollama/download/progress/99999999-9999-9999-9999-999999999999"

  # ── 模型连通性测试段 ──
  ab_json w5b-ab-remote-missing POST /initialization/remote/check '{}'
  ab_json w5b-ab-remote-ssrf    POST /initialization/remote/check '{"modelName":"stub-model","baseUrl":"http://10.0.0.1:9/v1"}'
  ab_json w5b-ab-remote-ok      POST /initialization/remote/check "{\"modelName\":\"stub-model\",\"baseUrl\":\"${STUB_LLM}\"}"
  ab_json w5b-ab-emb-ok         POST /initialization/embedding/test "{\"modelName\":\"stub-model\",\"baseUrl\":\"${STUB_LLM}\"}"
  ab_json w5b-ab-emb-aliyun     POST /initialization/embedding/test "{\"provider\":\"aliyun\",\"modelName\":\"text-embedding-vision\",\"baseUrl\":\"${STUB_LLM}\"}"
  ab_json w5b-ab-rerank-missing POST /initialization/rerank/check '{"modelName":"stub-model"}'
  ab_json w5b-ab-rerank-ok      POST /initialization/rerank/check "{\"modelName\":\"stub-model\",\"baseUrl\":\"${STUB_LLM}\"}"
  ab_json w5b-ab-asr-missing    POST /initialization/asr/check '{"modelName":"stub-model"}'
  ab_json w5b-ab-asr-ok         POST /initialization/asr/check "{\"apiKey\":\"sk-w5b\",\"modelName\":\"stub-model\",\"baseUrl\":\"${STUB_LLM}\"}"
  ab_json w5b-ab-asr-401        POST /initialization/asr/check "{\"apiKey\":\"sk-w5b\",\"modelName\":\"asr-401\",\"baseUrl\":\"${STUB_LLM}\"}"
  ab_json w5b-ab-asr-404        POST /initialization/asr/check "{\"apiKey\":\"sk-w5b\",\"modelName\":\"asr-404\",\"baseUrl\":\"${STUB_LLM}\"}"
  ab_json w5b-ab-asr-modelmissing POST /initialization/asr/check "{\"apiKey\":\"sk-w5b\",\"modelName\":\"asr-modelmissing\",\"baseUrl\":\"${STUB_LLM}\"}"
  ab_json w5b-ab-asr-500text    POST /initialization/asr/check "{\"apiKey\":\"sk-w5b\",\"modelName\":\"asr-500text\",\"baseUrl\":\"${STUB_LLM}\"}"
  ab_json w5b-ab-asr-storedkey  POST /initialization/asr/check "{\"modelName\":\"stub-model\",\"modelId\":\"${MD_ASR}\",\"baseUrl\":\"${STUB_LLM}\"}"

  # ── 抽取段 ──
  ab_json w5b-ab-extract-badbody POST /initialization/extract/text-relation '{}'
  ab_json w5b-ab-extract-toolong POST /initialization/extract/text-relation "{\"text\":\"$(python3 -c "print('a'*5001)")\",\"tags\":[\"Author\"],\"model_id\":\"${MD_STUB}\"}"
  ab_json w5b-ab-extract-nomodel POST /initialization/extract/text-relation '{"text":"x","tags":["Author"],"model_id":"nope"}'
  GRAPH_JSON="$(python3 -c "import json,sys;print(json.dumps({'text':sys.argv[1],'tags':['Author'],'model_id':sys.argv[2]}))" '<<SCENARIO:graph>> 请从下面的文本中抽取实体与关系。' "${MD_STUB}")"
  ab_json w5b-ab-extract-graph  POST /initialization/extract/text-relation "${GRAPH_JSON}"
  ab_json w5b-ab-fabritext      POST /initialization/extract/fabri-text "{\"tags\":[\"Author\",\"Alias\"],\"model_id\":\"${MD_STUB}\"}"
  ab_json w5b-ab-fabritext-nomodel POST /initialization/extract/fabri-text '{"tags":[],"model_id":"nope"}'
  ab_json w5b-ab-fabritag       POST /initialization/extract/fabri-tag

  # ── multimodal（校验族 + docreader 成功路径）──
  ab_mm w5b-ab-mm-missing
  ab_mm w5b-ab-mm-badstorage  -F "vlm_model=vlm-stub" -F "vlm_base_url=${STUB_LLM}" -F "storage_type=s3"
  ab_mm w5b-ab-mm-cos         -F "vlm_model=vlm-stub" -F "vlm_base_url=${STUB_LLM}" -F "storage_type=cos"
  ab_mm w5b-ab-mm-minio       -F "vlm_model=vlm-stub" -F "vlm_base_url=${STUB_LLM}" -F "storage_type=minio"
  ab_mm w5b-ab-mm-chunksize   -F "image=@${PNG};type=image/png" -F "vlm_model=vlm-stub" -F "vlm_base_url=${STUB_LLM}" -F "storage_type=minio" -F "minio_bucket_name=b" -F "chunk_size=abc"
  ab_mm w5b-ab-mm-chunkoverlap -F "image=@${PNG};type=image/png" -F "vlm_model=vlm-stub" -F "vlm_base_url=${STUB_LLM}" -F "storage_type=minio" -F "minio_bucket_name=b" -F "chunk_size=1000" -F "chunk_overlap=abc"
  ab_mm w5b-ab-mm-badtype     -F "image=@${TXT};type=text/plain" -F "vlm_model=vlm-stub" -F "vlm_base_url=${STUB_LLM}" -F "storage_type=minio" -F "minio_bucket_name=b" -F "chunk_size=1000" -F "chunk_overlap=200"
  ab_mm w5b-ab-mm-docreader   -F "image=@${PNG};type=image/png" -F "vlm_model=vlm-stub" -F "vlm_base_url=${STUB_LLM}" -F "storage_type=minio" -F "minio_bucket_name=b" -F "chunk_size=1000" -F "chunk_overlap=200"
  # ── 403/401 ──
  ab_viewer w5b-ab-viewer-403 POST /initialization/remote/check '{}'
  g="$(curl -s -o "${OUT}/w5b-ab-noauth.go.json" -w '%{http_code}' "${GO}/initialization/ollama/status")"
  j="$(curl -s -o "${OUT}/w5b-ab-noauth.java.json" -w '%{http_code}' "${JAVA}/initialization/ollama/status")"
  if [ "${g}" = "${j}" ] && diff -q "${OUT}/w5b-ab-noauth.go.json" "${OUT}/w5b-ab-noauth.java.json" >/dev/null; then
    pass=$((pass+1)); echo "MATCH w5b-ab-noauth (${g})"
  else
    fail=$((fail+1)); echo "DIFF  w5b-ab-noauth go=${g} java=${j}"
  fi
done
rm -f "${PNG}" "${TXT}"
echo "==> W5b A/B: ${pass} MATCH / ${fail} DIFF"
[ "${fail}" = "0" ]
