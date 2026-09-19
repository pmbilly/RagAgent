#!/usr/bin/env bash
# 录波 3 agents 批的 golden：ag-*（agents CRUD 家族）+ init-*（initialization 三条）。
#
# 覆盖端点（8 + 3 条）：
#   GET/POST /agents  GET/PUT/DELETE /agents/:id  POST /agents/:id/copy
#   GET /agents/placeholders  GET /agents/type-presets  GET /agents/:id/suggested-questions
#   GET/PUT /initialization/config/:kbId  POST /initialization/initialize/:kbId
#
# 场景设计：
#   - 独立租户 10005（ag-batch 用户），不动 org 批在 10002/10003/10004 的种子行。
#   - 内建 agent 走 builtin_agents.yaml 注册表（dev 默认 locale=zh-CN → 中文名），
#     system_prompt/context_template 由 prompt_templates 解析填充 —— 大文本进 golden。
#   - 录制顺序敏感（契约测试复刻）：鉴权 → 静态面 → 空列表 → 内建 get →
#     CRUD → creator 筛选 → update → delete → copy → 内建 PUT（落 DB 行）→
#     suggested-questions → initialization。
#   - suggested-questions 走静态分支（curated / 无 KB / 单 FAQ chunk），rand.Shuffle
#     在单元素集上确定 → golden 稳定；多 chunk 随机序刻意不录。
#   - initialization 的 KB/模型/文档行用固定 id SQL 种子；成功路径的动态模型 id
#     靠 uuid 掩码吸收。
#
# 用法：
#   scripts/record-ag-golden.sh                  # 录 Go（:8080）进 contracts/
#   AG_TARGET_PORT=8082 AG_OUT_DIR=/tmp/x scripts/record-ag-golden.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${AG_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${AG_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"
mkdir -p "${OUT}"

req() { local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" "$@"; }

# ── 固定种子 id（两侧同 id，不需掩码）──
AGU="a0000000-0000-0000-0000-000000000001"   # ag-batch owner（租户 10005）
AGV="a0000000-0000-0000-0000-000000000002"   # ag-batch viewer
TENANT=10005
KB_INIT="a0000000-0000-0000-0000-000000000101"   # initialization 主 KB
KB_FAQ="a0000000-0000-0000-0000-000000000102"   # suggested-questions FAQ KB
KN_FAQ="a0000000-0000-0000-0000-000000000201"
CH_FAQ="a0000000-0000-0000-0000-000000000301"
KN_FILE="a0000000-0000-0000-0000-000000000202"   # 让 hasFiles=true 的文档行
MD_LLM="a0000000-0000-0000-0000-000000000401"   # PUT config 用的固定 LLM 模型
FAQMETA='{"standard_question":"怎么 绑定 手机？","answers":["进入设置，选择设备，点击绑定。"],"answer_strategy":"all","version":1,"source":"faq"}'

echo "==> 幂等清理 + 种子（租户 10005 全量清理后重建）"
${PSQL} >/dev/null 2>&1 <<SQL
DELETE FROM chunks WHERE tenant_id IN (${TENANT});
DELETE FROM knowledges WHERE tenant_id IN (${TENANT});
DELETE FROM knowledge_bases WHERE tenant_id IN (${TENANT});
DELETE FROM models WHERE tenant_id IN (${TENANT});
DELETE FROM custom_agents WHERE tenant_id IN (${TENANT});
DELETE FROM tenant_disabled_shared_agents WHERE tenant_id IN (${TENANT});
DELETE FROM tenant_members WHERE tenant_id IN (${TENANT});
DELETE FROM users WHERE tenant_id IN (${TENANT});
DELETE FROM tenants WHERE id IN (${TENANT});
SQL

${PSQL} >/dev/null <<SQL
INSERT INTO tenants (id, name, description, business, status) VALUES
  (${TENANT}, 'ag-batch-tenant', '', '', 'active');
INSERT INTO users (id, username, email, password_hash, tenant_id, is_active) VALUES
  ('${AGU}', 'agbatch', 'ag-batch@weknora.test', '\$2a\$10\$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK', ${TENANT}, true),
  ('${AGV}', 'agviewer', 'ag-batch-viewer@weknora.test', '\$2a\$10\$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK', ${TENANT}, true);
INSERT INTO tenant_members (user_id, tenant_id, role, status) VALUES
  ('${AGU}', ${TENANT}, 'owner', 'active'),
  ('${AGV}', ${TENANT}, 'viewer', 'active');
INSERT INTO knowledge_bases (id, name, tenant_id, type, description, creator_id, chunking_config, embedding_model_id, summary_model_id, created_at, updated_at) VALUES
  ('${KB_INIT}', 'ag-init-kb', ${TENANT}, 'document', 'kb for initialization batch', '${AGU}', '{}', '', '', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
  ('${KB_FAQ}', 'ag-faq-kb', ${TENANT}, 'faq', 'kb for suggested questions', '${AGU}', '{}', '', '', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00');
INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, description, source, parse_status, enable_status) VALUES
  ('${KN_FAQ}', ${TENANT}, '${KB_FAQ}', 'faq', 'faq-knowledge', '', 'faq', 'completed', 'enabled');
INSERT INTO chunks (id, seq_id, tenant_id, knowledge_id, knowledge_base_id, content, chunk_type, is_enabled, flags, status, chunk_index, start_at, end_at, metadata, created_at, updated_at) VALUES
  ('${CH_FAQ}', 980001, ${TENANT}, '${KN_FAQ}', '${KB_FAQ}', 'Q: 怎么 绑定 手机？
', 'faq', true, 1, 2, 0, 0, 0, '${FAQMETA}', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00');
INSERT INTO models (id, tenant_id, type, name, source, description, parameters, is_default, status, created_at, updated_at) VALUES
  ('${MD_LLM}', ${TENANT}, 'KnowledgeQA', 'ag-fixed-llm', 'remote', 'LLM Model for Knowledge QA', '{}', false, 'active', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00');
SQL

TOKEN="$(login "ag-batch@weknora.test" "${PORT}")"
[ -n "${TOKEN}" ] || { echo "FATAL: ag-batch 登录失败"; exit 1; }
AUTH="Authorization: Bearer ${TOKEN}"
CT='Content-Type: application/json'
MISSING="99999999-9999-9999-9999-999999999999"

echo "==> 1) 鉴权家族 + 静态面"
req ag-noauth.json        GET  "/agents" 
req ag-badtoken.json      GET  "/agents" -H 'Authorization: Bearer garbage.token.here'
req ag-placeholders.json  GET  "/agents/placeholders" -H "${AUTH}"
req ag-type-presets.json  GET  "/agents/type-presets" -H "${AUTH}"

echo "==> 2) 空列表（只含内建 agent；disabled_own_agent_ids 形态）"
req ag-list-empty.json    GET  "/agents" -H "${AUTH}"

echo "==> 3) 内建 get + 404 家族"
req ag-get-builtin-qa.json GET "/agents/builtin-quick-answer" -H "${AUTH}"
req ag-get-builtin-nope.json GET "/agents/builtin-nope" -H "${AUTH}"
req ag-get-missing.json   GET  "/agents/${MISSING}" -H "${AUTH}"

echo "==> 4) create 家族"
req ag-create-empty.json  POST "/agents" -H "${AUTH}" -H "${CT}" \
  -d '{"name":"ag-empty","description":"empty config agent","config":{}}'
AG_EMPTY="$(python3 -c "import json;print(json.load(open('${OUT}/ag-create-empty.json'))['data']['id'])")"
req ag-get-created.json   GET  "/agents/${AG_EMPTY}" -H "${AUTH}"

FULL_BODY=$(cat <<EOF
{
  "name":"ag-full","description":"full config agent","avatar":"robot",
  "config":{
    "agent_mode":"smart-reasoning","agent_type":"rag-qa",
    "system_prompt":"you are full","context_template":"ctx {{query}}",
    "model_id":"shr-model-1","rerank_model_id":"shr-rerank-1",
    "temperature":0.5,"max_completion_tokens":4096,
    "thinking":true,"citation_enabled":false,"max_iterations":-1,
    "llm_call_timeout":120,
    "allowed_tools":["knowledge_search","web_search"],
    "mcp_selection_mode":"selected","mcp_services":["mcp-1","mcp-2"],
    "mcp_auth_wait_timeout":30,
    "skills_selection_mode":"selected","selected_skills":["skill-a"],
    "kb_selection_mode":"selected","knowledge_bases":["${KB_FAQ}"],
    "retrieve_kb_only_when_mentioned":true,"retain_retrieval_history":true,
    "image_upload_enabled":true,"vlm_model_id":"vlm-1",
    "audio_upload_enabled":true,"asr_model_id":"asr-1",
    "image_storage_provider":"local",
    "supported_file_types":["csv","xlsx"],
    "attachment_image_understanding":true,
    "attachment_ocr_max_pages":5,"attachment_parse_wait_timeout_sec":30,
    "data_analysis_enabled":true,
    "faq_priority_enabled":true,"faq_direct_answer_threshold":0.8,"faq_score_boost":1.5,
    "web_search_enabled":true,"web_search_max_results":8,
    "web_search_provider_id":"wsp-1","web_fetch_enabled":true,"web_fetch_top_n":4,
    "history_turns":3,
    "memory_enabled":true,
    "embedding_top_k":8,"keyword_threshold":0.2,"vector_threshold":0.4,
    "rerank_top_k":3,"rerank_threshold":0.1,
    "enable_query_expansion":true,"enable_rewrite":true,
    "rewrite_prompt_system":"rsys","rewrite_prompt_user":"ruser {{conversation}}",
    "query_understand_model_id":"qu-model",
    "fallback_strategy":"fixed","fallback_response":"sorry","fallback_prompt":"fp",
    "intent_prompts":{"greeting":"hi there"},
    "question_suggestions":{
      "starters":{"enabled":true,"mode":"curated","items":["问题A","问题B"],"count":2},
      "follow_ups":{"enabled":true,"mode":"hybrid","count":2,"model_id":"fu-model",
        "additional_instruction":"be nice","categories":["clarify","deepen"],
        "max_context_turns":3,"suppress_on_fallback":true,
        "suppress_when_answer_asks_question":true,"knowledge_fallback":true,
        "allow_regenerate":true}
    }
  }
}
EOF
)
req ag-create-full.json   POST "/agents" -H "${AUTH}" -H "${CT}" -d "${FULL_BODY}"
AG_FULL="$(python3 -c "import json;print(json.load(open('${OUT}/ag-create-full.json'))['data']['id'])")"

req ag-create-kbref.json  POST "/agents" -H "${AUTH}" -H "${CT}" \
  -d "{\"name\":\"ag-kbref\",\"description\":\"kb reference agent\",\"config\":{\"agent_mode\":\"quick-answer\",\"kb_selection_mode\":\"selected\",\"knowledge_bases\":[\"${KB_FAQ}\"]}}"
AG_KBREF="$(python3 -c "import json;print(json.load(open('${OUT}/ag-create-kbref.json'))['data']['id'])")"

req ag-create-missing-name.json POST "/agents" -H "${AUTH}" -H "${CT}" \
  -d '{"description":"no name"}'
req ag-create-badbody.json POST "/agents" -H "${AUTH}" -H "${CT}" -d 'not-json'
req ag-create-blank-name.json POST "/agents" -H "${AUTH}" -H "${CT}" \
  -d '{"name":"   "}'
req ag-create-bad-starters-count.json POST "/agents" -H "${AUTH}" -H "${CT}" \
  -d '{"name":"ag-bad-count","config":{"question_suggestions":{"starters":{"enabled":true,"mode":"curated","count":9},"follow_ups":{"enabled":false}}}}'
req ag-create-bad-starter-mode.json POST "/agents" -H "${AUTH}" -H "${CT}" \
  -d '{"name":"ag-bad-mode","config":{"question_suggestions":{"starters":{"enabled":true,"mode":"nonsense","count":2},"follow_ups":{"enabled":false}}}}'
req ag-create-empty-starter-item.json POST "/agents" -H "${AUTH}" -H "${CT}" \
  -d '{"name":"ag-bad-item","config":{"question_suggestions":{"starters":{"enabled":true,"mode":"curated","items":["  "],"count":1},"follow_ups":{"enabled":false}}}}'
req ag-create-bad-sandbox.json POST "/agents" -H "${AUTH}" -H "${CT}" \
  -d '{"name":"ag-bad-sandbox","config":{"sandbox_config_id":"no-such-sandbox"}}'

echo "==> 5) creator 筛选"
req ag-list-mine.json     GET  "/agents?creator=mine" -H "${AUTH}"
req ag-list-others.json   GET  "/agents?creator=others" -H "${AUTH}"
req ag-list-bogus.json    GET  "/agents?creator=nonsense" -H "${AUTH}"

echo "==> 6) update 家族"
req ag-update.json        PUT  "/agents/${AG_EMPTY}" -H "${AUTH}" -H "${CT}" \
  -d '{"name":"ag-empty-renamed","description":"renamed","config":{"agent_mode":"quick-answer","faq_priority_enabled":true}}'
req ag-update-blank-name.json PUT "/agents/${AG_EMPTY}" -H "${AUTH}" -H "${CT}" \
  -d '{"name":""}'
req ag-update-missing.json PUT "/agents/${MISSING}" -H "${AUTH}" -H "${CT}" \
  -d '{"name":"ghost"}'

echo "==> 7) delete 家族"
req ag-delete.json        DELETE "/agents/${AG_KBREF}" -H "${AUTH}"
req ag-delete-again.json  DELETE "/agents/${AG_KBREF}" -H "${AUTH}"
req ag-delete-builtin.json DELETE "/agents/builtin-quick-answer" -H "${AUTH}"

echo "==> 8) copy 家族"
req ag-copy.json          POST "/agents/${AG_FULL}/copy" -H "${AUTH}"
AG_COPY="$(python3 -c "import json;print(json.load(open('${OUT}/ag-copy.json'))['data']['id'])")"
req ag-get-copy.json      GET  "/agents/${AG_COPY}" -H "${AUTH}"
req ag-copy-missing.json  POST "/agents/${MISSING}/copy" -H "${AUTH}"

echo "==> 9) 内建 agent 的 PUT（落 DB 定制行）+ 回读 + 终态列表"
req ag-put-builtin.json   PUT  "/agents/builtin-quick-answer" -H "${AUTH}" -H "${CT}" \
  -d '{"name":"ignored","description":"ignored","config":{"agent_mode":"quick-answer","system_prompt_id":"default_kb","temperature":0.3}}'
req ag-get-builtin-qa-after.json GET "/agents/builtin-quick-answer" -H "${AUTH}"
req ag-list-final.json    GET  "/agents" -H "${AUTH}"

echo "==> 10) suggested-questions 家族"
req ag-sq-noauth.json     GET  "/agents/${AG_FULL}/suggested-questions"
req ag-sq-missing.json    GET  "/agents/${MISSING}/suggested-questions" -H "${AUTH}"
req ag-sq-curated.json    GET  "/agents/${AG_FULL}/suggested-questions" -H "${AUTH}"
req ag-sq-curated-limit1.json GET "/agents/${AG_FULL}/suggested-questions?limit=1" -H "${AUTH}"
req ag-sq-curated-cap.json GET "/agents/${AG_FULL}/suggested-questions?limit=1000" -H "${AUTH}"
req ag-sq-default.json    GET  "/agents/${AG_EMPTY}/suggested-questions" -H "${AUTH}"
req ag-sq-badtagscopes.json GET "/agents/${AG_FULL}/suggested-questions?tag_scopes=not-json" -H "${AUTH}"
req ag-sq-faq.json        GET  "/agents/${AG_EMPTY}/suggested-questions?knowledge_base_ids=${KB_FAQ}" -H "${AUTH}"
req ag-sq-faq-knowledge.json GET "/agents/${AG_EMPTY}/suggested-questions?knowledge_ids=${KN_FAQ}" -H "${AUTH}"

echo "==> 11) initialization：GET config / POST initialize / PUT config"
req init-get-config.json    GET "/initialization/config/${KB_INIT}" -H "${AUTH}"
req init-get-config-404.json GET "/initialization/config/${MISSING}" -H "${AUTH}"
req init-get-config-noauth.json GET "/initialization/config/${KB_INIT}"

req init-post-initialize.json POST "/initialization/initialize/${KB_INIT}" -H "${AUTH}" -H "${CT}" -d '{
  "llm":{"source":"remote","modelName":"ag-init-llm","baseUrl":"","apiKey":"sk-init"},
  "embedding":{"source":"remote","modelName":"ag-init-embed","baseUrl":"","dimension":1024},
  "documentSplitting":{"chunkSize":500,"chunkOverlap":50,"separators":["\n\n"]}
}'
req init-post-initialize-404.json POST "/initialization/initialize/${MISSING}" -H "${AUTH}" -H "${CT}" \
  -d '{"llm":{"source":"remote","modelName":"m"},"embedding":{"source":"remote","modelName":"e"},"documentSplitting":{"chunkSize":500,"chunkOverlap":50,"separators":["\n\n"]}}'
req init-post-initialize-empty.json POST "/initialization/initialize/${KB_INIT}" -H "${AUTH}" -H "${CT}"
req init-post-initialize-chunksize.json POST "/initialization/initialize/${KB_INIT}" -H "${AUTH}" -H "${CT}" \
  -d '{"llm":{"source":"remote","modelName":"m"},"embedding":{"source":"remote","modelName":"e"},"documentSplitting":{"chunkSize":50,"chunkOverlap":50,"separators":["\n\n"]}}'
req init-post-initialize-ssrf.json POST "/initialization/initialize/${KB_INIT}" -H "${AUTH}" -H "${CT}" \
  -d '{"llm":{"source":"remote","modelName":"m","baseUrl":"http://127.0.0.1:11434"},"embedding":{"source":"remote","modelName":"e"},"documentSplitting":{"chunkSize":500,"chunkOverlap":50,"separators":["\n\n"]}}'
req init-post-initialize-rerank.json POST "/initialization/initialize/${KB_INIT}" -H "${AUTH}" -H "${CT}" \
  -d '{"llm":{"source":"remote","modelName":"m"},"embedding":{"source":"remote","modelName":"e"},"rerank":{"enabled":true,"modelName":"rr"},"documentSplitting":{"chunkSize":500,"chunkOverlap":50,"separators":["\n\n"]}}'

req init-get-config-after.json GET "/initialization/config/${KB_INIT}" -H "${AUTH}"

echo "==> 12) 种文档行（hasFiles=true）→ PUT 家族"
${PSQL} >/dev/null <<SQL
INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, description, source, parse_status, enable_status) VALUES
  ('${KN_FILE}', ${TENANT}, '${KB_INIT}', 'document', 'has-files-doc', '', 'manual', 'completed', 'enabled');
SQL
req init-get-config-hasfiles.json GET "/initialization/config/${KB_INIT}" -H "${AUTH}"

req init-put-config.json  PUT "/initialization/config/${KB_INIT}" -H "${AUTH}" -H "${CT}" \
  -d "$(python3 -c 'import json,sys;print(json.dumps({"llmModelId":sys.argv[1],"documentSplitting":{"chunkSize":800,"chunkOverlap":100,"separators":["\n\n","。"]},"questionGeneration":{"enabled":True,"questionCount":99,"customInstructions":"  gen  "}}))' "${MD_LLM}")"
req init-put-config-embedding-change.json PUT "/initialization/config/${KB_INIT}" -H "${AUTH}" -H "${CT}" \
  -d "{\"llmModelId\":\"${MD_LLM}\",\"embeddingModelId\":\"a0000000-0000-0000-0000-000000000402\"}"
req init-put-config-llm-missing.json PUT "/initialization/config/${KB_INIT}" -H "${AUTH}" -H "${CT}" \
  -d "{\"llmModelId\":\"a0000000-0000-0000-0000-0000000004ff\"}"
req init-put-config-404.json PUT "/initialization/config/${MISSING}" -H "${AUTH}" -H "${CT}" \
  -d "{\"llmModelId\":\"${MD_LLM}\"}"
req init-put-config-empty.json PUT "/initialization/config/${KB_INIT}" -H "${AUTH}" -H "${CT}"
req init-put-config-badprovider.json PUT "/initialization/config/${KB_INIT}" -H "${AUTH}" -H "${CT}" \
  -d "{\"llmModelId\":\"${MD_LLM}\",\"storageProvider\":\"ftp\"}"

echo "==> 完成：golden 落在 ${OUT}"
