#!/usr/bin/env bash
# 录 FAQ 模块（波 2 第四批，/knowledge-bases/{id}/faq 12 条路由 + 导入进度）的 golden。
#
# 场景设计（约定 §4 / §9；录制顺序影响状态，契约测试必须严格复刻本顺序）：
#   - FKB1 faq-golden-kb：API 建（type=faq，creator=owner），SQL 关全部索引与
#     embedding/summary model —— FAQ 全链路里凡是走到 GetEmbeddingModel("")
#     的分支都收敛为同一句确定性 500 "…model ID cannot be empty"；
#   - FK1 容器（type=faq）+ last_faq_import_result SQL 直插（display 200 路径）；
#   - FE1..FE4 chunk 直插（固定纯十六进制 id + seq_id，metadata jsonb）；
#   - UpdateEntry / AddSimilarQuestions / DeleteEntries 在 Go 是**先落库后失败**
#     （GetEmbeddingModel 在写路径之后）——golden 抓的是这个顺序，Java 侧实现
#     必须同样"先持久化再 500"，后续 GetEntry/列表能看到变更；
#   - UpsertEntries 走 asynq：dry_run=true 只做验证（无 embedding 依赖，~1-2s
#     完成可轮询）；非 dry_run 会卡在重试（导入 worker 需要 embedding），
#     只录立即响应、用完即弃的独立 KB（FKB5），且放最后（它会锁 running key）；
#   - POST /search 走 HybridSearch（retriever，波 4）：无 embedding 绑定下 Go 的
#     错误形态是确定性的，录参数校验 + 该错误形态；keyword 搜索（H2 跑不了
#     MySQL 语法的非 PG 分支）只录给真 PG 的 A/B 用，契约测试跳过（ab-only）。
#   - 幂等清理：chunk_revisions → chunks → knowledges → tag_relations →
#     knowledge_tags → knowledge_bases（外键序，§9 chunk 波教训）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${FAQ_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${FAQ_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"

UNKNOWN="11111111-2222-3333-4444-999999999999"

req() {
  local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "${AUTH}" "$@"
}
reqh() { # 导出：另存响应头
  local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -D "${OUT}/${out}.headers" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "${AUTH}" "$@"
}
reqc() { local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "Authorization: Bearer ${CTOKEN}" "$@"
}
reqv() { local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "Authorization: Bearer ${VTOKEN}" "$@"
}
reqn() { # 未认证
  local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" "$@"
}

echo "==> 准备：token"
AUTH="Authorization: Bearer $(login "${TEST_EMAIL}" "${PORT}")"
CTOKEN="$(login java-phase1-contrib@weknora.test "${PORT}")"
VTOKEN="$(login "${TEST_VIEWER_EMAIL}" "${PORT}")"

echo "==> 幂等清理（faq-* KB 及其全部子行）"
${PSQL} >/dev/null 2>&1 <<'SQL'
DELETE FROM chunk_revisions WHERE chunk_id IN (SELECT c.id FROM chunks c JOIN knowledge_bases kb ON c.knowledge_base_id = kb.id WHERE kb.name LIKE 'faq-%');
DELETE FROM chunks WHERE knowledge_base_id IN (SELECT id FROM knowledge_bases WHERE name LIKE 'faq-%');
DELETE FROM knowledges WHERE knowledge_base_id IN (SELECT id FROM knowledge_bases WHERE name LIKE 'faq-%');
DELETE FROM knowledge_tag_relations WHERE tag_id IN (SELECT id FROM knowledge_tags WHERE knowledge_base_id IN (SELECT id FROM knowledge_bases WHERE name LIKE 'faq-%'));
DELETE FROM knowledge_tags WHERE knowledge_base_id IN (SELECT id FROM knowledge_bases WHERE name LIKE 'faq-%');
DELETE FROM knowledge_bases WHERE name LIKE 'faq-%';
SQL

echo "==> 建 FAQ KB（API，creator=owner）+ SQL 关索引/模型"
FKB1=""; FKB2=""; FKB3=""; FKB4=""; FKB5=""
for pair in "faq-golden-kb:FKB1" "faq-empty-kb:FKB2" "faq-noresult-kb:FKB3" "faq-doc-kb:FKB4" "faq-import-kb:FKB5"; do
  name="${pair%%:*}"; var="${pair##*:}"
  type="faq"
  [ "${var}" = "FKB4" ] && type="document"
  curl -s -o /tmp/faq-kb-seed.json -X POST "${API}/knowledge-bases" \
    -H "${AUTH}" -H 'Content-Type: application/json' \
    -d "{\"name\":\"${name}\",\"description\":\"faq golden 专用\",\"type\":\"${type}\"}"
  id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' /tmp/faq-kb-seed.json)"
  eval "${var}=${id}"
done
# FKB1 显式带 index_mode=question_only 的 faq_config；FKB3 留空（EnsureDefaults 兜 question_answer）
${PSQL} -c "
UPDATE knowledge_bases SET indexing_strategy='{\"vector_enabled\":false,\"keyword_enabled\":false,\"wiki_enabled\":false,\"graph_enabled\":false}', summary_model_id='', embedding_model_id='', faq_config='{\"index_mode\":\"question_only\",\"question_index_mode\":\"combined\"}' WHERE id='${FKB1}';
UPDATE knowledge_bases SET indexing_strategy='{\"vector_enabled\":false,\"keyword_enabled\":false,\"wiki_enabled\":false,\"graph_enabled\":false}', summary_model_id='', embedding_model_id='' WHERE id IN ('${FKB2}','${FKB3}','${FKB5}');
UPDATE knowledge_bases SET indexing_strategy='{\"vector_enabled\":false,\"keyword_enabled\":false,\"wiki_enabled\":false,\"graph_enabled\":false}', summary_model_id='', embedding_model_id='' WHERE id='${FKB4}';
INSERT INTO knowledge_bases (id, name, tenant_id, type, creator_id, embedding_model_id, summary_model_id) VALUES ('fa900001-0000-0000-0000-00000000c001', 'faq-cross-tenant', 10000, 'faq', '99999999-9999-9999-9999-999999999999', '', '');" >/dev/null
CROSS_KB="fa900001-0000-0000-0000-00000000c001"
echo "    FKB1=${FKB1} FKB2=${FKB2} FKB3=${FKB3} FKB4=${FKB4} FKB5=${FKB5}"

echo "==> 准备：容器 + 标签 + FAQ chunk 种子"
FK1="faa00001-0000-0000-0000-000000000001"
FK3="faa00001-0000-0000-0000-000000000003"
FT1="fab00001-0000-0000-0000-000000000001"
FT2="fab00001-0000-0000-0000-000000000002"
FE1="fbc00001-0000-0000-0000-000000000001"
FE2="fbc00001-0000-0000-0000-000000000002"
FE3="fbc00001-0000-0000-0000-000000000003"
FE4="fbc00001-0000-0000-0000-000000000004"
META1='{"standard_question":"怎么 绑定 手机？","similar_questions":["如何绑定手机","How to bind phone"],"negative_questions":["怎么解绑手机"],"answers":["进入设置，选择设备，点击绑定。"],"answer_strategy":"all","version":1,"source":"faq"}'
META2='{"standard_question":"退货政策是什么","answers":["7天无理由退货","质量问题15天内退"],"answer_strategy":"random","version":1,"source":"faq"}'
META3='{"standard_question":"如何退款","similar_questions":["退款流程"],"answers":["请参见帮助中心。"],"answer_strategy":"all","version":1,"source":"faq"}'
RESULT1='{"total_entries":2,"success_count":1,"failed_count":1,"partial_failed_count":0,"skipped_count":0,"merged_count":0,"added_count":1,"import_mode":"append","imported_at":"2026-09-01T08:00:00+08:00","task_id":"faqgolden-seed","display_status":"open","processing_time":5}'
${PSQL} >/dev/null <<SQL
INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, description, source, parse_status, enable_status, last_faq_import_result) VALUES
('${FK1}', 10002, '${FKB1}', 'faq', 'faq-golden-kb', 'FAQ 条目容器', 'faq', 'completed', 'enabled', '${RESULT1}'),
('${FK3}', 10002, '${FKB3}', 'faq', 'faq-noresult-kb', 'FAQ 条目容器', 'faq', 'completed', 'enabled', NULL);
INSERT INTO knowledge_tags (id, seq_id, tenant_id, knowledge_base_id, name, color, sort_order) VALUES
('${FT1}', 965001, 10002, '${FKB1}', '热门问题', '', 0),
('${FT2}', 965002, 10002, '${FKB1}', '售后', '', 0);
INSERT INTO chunks (id, seq_id, tenant_id, knowledge_id, knowledge_base_id, content, chunk_type, tag_id, is_enabled, flags, status, chunk_index, start_at, end_at, metadata, created_at, updated_at) VALUES
('${FE1}', 970001, 10002, '${FK1}', '${FKB1}', 'Q: 怎么 绑定 手机？
Similar Questions:
- 如何绑定手机
- How to bind phone
', 'faq', '${FT1}', true, 1, 2, 0, 0, 0, '${META1}', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
('${FE2}', 970002, 10002, '${FK1}', '${FKB1}', 'Q: 退货政策是什么
', 'faq', NULL, true, 0, 2, 0, 0, 0, '${META2}', '2026-09-01 07:00:00+00', '2026-09-01 07:00:00+00'),
('${FE3}', 970003, 10002, '${FK1}', '${FKB1}', 'Q: 如何退款
Similar Questions:
- 退款流程
', 'faq', '${FT2}', false, 1, 2, 0, 0, 0, '${META3}', '2026-09-01 06:00:00+00', '2026-09-01 06:00:00+00'),
('${FE4}', 970004, 10002, '${FK1}', '${FKB1}', '裸内容条目', 'faq', NULL, true, 1, 2, 0, 0, 0, NULL, '2026-09-01 05:00:00+00', '2026-09-01 05:00:00+00');
SQL

echo "==> 1) 列表：空库 / 参数错误 / 全量 / 分页 / 筛选"
req faq-list-empty.json            GET "/knowledge-bases/${FKB2}/faq/entries"
req faq-list-badpage.json          GET "/knowledge-bases/${FKB1}/faq/entries?page=abc"
req faq-list-negpage.json          GET "/knowledge-bases/${FKB1}/faq/entries?page=-1"
req faq-list-bigsize.json          GET "/knowledge-bases/${FKB1}/faq/entries?page_size=1001"
req faq-list-page0.json            GET "/knowledge-bases/${FKB1}/faq/entries?page=0&page_size=2"
req faq-list-paged.json            GET "/knowledge-bases/${FKB1}/faq/entries?page=2&page_size=2"
req faq-list-badtag.json           GET "/knowledge-bases/${FKB1}/faq/entries?tag_id=abc"
req faq-list-badenabled.json       GET "/knowledge-bases/${FKB1}/faq/entries?is_enabled=xyz"
req faq-list-enabled-false.json    GET "/knowledge-bases/${FKB1}/faq/entries?is_enabled=false"
req faq-list-tag.json              GET "/knowledge-bases/${FKB1}/faq/entries?tag_id=965001"
req faq-list-taguuid.json          GET "/knowledge-bases/${FKB1}/faq/entries?tag_ids=${FT2}"
req faq-list-untagged.json         GET "/knowledge-bases/${FKB1}/faq/entries?tag_ids=__untagged__,${FT2}"
req faq-list-sortasc.json          GET "/knowledge-bases/${FKB1}/faq/entries?sort_order=asc"
req faq-list-notkb.json            GET "/knowledge-bases/${UNKNOWN}/faq/entries"
req faq-list-cross.json            GET "/knowledge-bases/${CROSS_KB}/faq/entries"
reqn faq-list-noauth.json          GET "/knowledge-bases/${FKB1}/faq/entries"
reqv faq-list-viewer.json          GET "/knowledge-bases/${FKB1}/faq/entries"
reqc faq-create-contrib.json       POST "/knowledge-bases/${FKB1}/faq/entry" -H 'Content-Type: application/json' \
  -d '{"standard_question":"贡献者创建","answers":["答案"]}'
reqv faq-fields-viewer.json        PUT "/knowledge-bases/${FKB1}/faq/entries/fields" -H 'Content-Type: application/json' \
  -d '{"by_id":{"970001":{"is_enabled":false}}}'

echo "==> 2) 详情：正常 / 非整数 / 404"
req faq-get.json                   GET "/knowledge-bases/${FKB1}/faq/entries/970001"
req faq-get-noentry.json           GET "/knowledge-bases/${FKB1}/faq/entries/999999"
req faq-get-badid.json             GET "/knowledge-bases/${FKB1}/faq/entries/abc"
req faq-get-wrongkb.json           GET "/knowledge-bases/${FKB2}/faq/entries/970001"

echo "==> 3) 创建（CreateEntry）：binding / 校验 / 重复 / 无 embedding 500"
req faq-create-empty.json          POST "/knowledge-bases/${FKB1}/faq/entry" -H 'Content-Type: application/json' -d ''
req faq-create-nullbody.json       POST "/knowledge-bases/${FKB1}/faq/entry" -H 'Content-Type: application/json' -d 'null'
req faq-create-noquestion.json     POST "/knowledge-bases/${FKB1}/faq/entry" -H 'Content-Type: application/json' -d '{"standard_question":"","answers":["答案"]}'
req faq-create-noanswer.json       POST "/knowledge-bases/${FKB1}/faq/entry" -H 'Content-Type: application/json' -d '{"standard_question":"问题"}'
req faq-create-badstrategy.json    POST "/knowledge-bases/${FKB1}/faq/entry" -H 'Content-Type: application/json' -d '{"standard_question":"问题","answers":["答案"],"answer_strategy":"bogus"}'
req faq-create-simileq.json        POST "/knowledge-bases/${FKB1}/faq/entry" -H 'Content-Type: application/json' -d '{"standard_question":"问题A","similar_questions":["问题A"],"answers":["答案"]}'
req faq-create-simidup.json        POST "/knowledge-bases/${FKB1}/faq/entry" -H 'Content-Type: application/json' -d '{"standard_question":"问题B","similar_questions":["重复问","重复问"],"answers":["答案"]}'
req faq-create-badtag.json         POST "/knowledge-bases/${FKB1}/faq/entry" -H 'Content-Type: application/json' -d '{"standard_question":"问题C","answers":["答案"],"tag_id":960999}'
req faq-create-ghosttag.json       POST "/knowledge-bases/${FKB1}/faq/entry" -H 'Content-Type: application/json' -d '{"standard_question":"问题D","answers":["答案"],"tag_name":"没人建过这分类"}'
req faq-create-dupstd.json         POST "/knowledge-bases/${FKB1}/faq/entry" -H 'Content-Type: application/json' -d '{"standard_question":"退货政策是什么","answers":["答案"]}'
req faq-create-dupsim.json         POST "/knowledge-bases/${FKB1}/faq/entry" -H 'Content-Type: application/json' -d '{"standard_question":"全新问题","similar_questions":["如何绑定手机"],"answers":["答案"]}'
req faq-create-500.json            POST "/knowledge-bases/${FKB1}/faq/entry" -H 'Content-Type: application/json' -d '{"standard_question":"怎么绑定手机？","answers":["见正文"]}'
req faq-create-wrongkb-type.json   POST "/knowledge-bases/${FKB4}/faq/entry" -H 'Content-Type: application/json' -d '{"standard_question":"问题","answers":["答案"]}'

echo "==> 4) 更新（UpdateEntry）：400 / 404 / 先落库后 500 / 更新后读回"
req faq-update-badid.json          PUT "/knowledge-bases/${FKB1}/faq/entries/abc" -H 'Content-Type: application/json' -d '{"standard_question":"问题","answers":["答案"]}'
req faq-update-404.json            PUT "/knowledge-bases/${FKB1}/faq/entries/999999" -H 'Content-Type: application/json' -d '{"standard_question":"问题","answers":["答案"]}'
req faq-update-noanswer.json       PUT "/knowledge-bases/${FKB1}/faq/entries/970004" -H 'Content-Type: application/json' -d '{"standard_question":"换一个问题"}'
req faq-update-500.json            PUT "/knowledge-bases/${FKB1}/faq/entries/970002" -H 'Content-Type: application/json' \
  -d '{"standard_question":"退货政策是什么流程","answers":["7天无理由退货","质量问题15天内退","运费险说明"],"is_recommended":true}'
req faq-get-after-update.json      GET "/knowledge-bases/${FKB1}/faq/entries/970002"

echo "==> 5) 相似问（AddSimilarQuestions）：400 / 500 先落库 / 读回"
req faq-similar-empty.json         POST "/knowledge-bases/${FKB1}/faq/entries/970001/similar-questions" -H 'Content-Type: application/json' -d '{"similar_questions":[]}'
req faq-similar-nometa.json        POST "/knowledge-bases/${FKB1}/faq/entries/970004/similar-questions" -H 'Content-Type: application/json' -d '{"similar_questions":["新问题"]}'
req faq-similar-404.json           POST "/knowledge-bases/${FKB1}/faq/entries/999999/similar-questions" -H 'Content-Type: application/json' -d '{"similar_questions":["新问题"]}'
req faq-similar-500.json           POST "/knowledge-bases/${FKB1}/faq/entries/970001/similar-questions" -H 'Content-Type: application/json' \
  -d '{"similar_questions":["在线绑定入口在哪里"]}'
req faq-get-after-similar.json     GET "/knowledge-bases/${FKB1}/faq/entries/970001"

echo "==> 6) 批量字段（UpdateEntryFieldsBatch）：400 / 404 / 200"
req faq-fields-empty.json          PUT "/knowledge-bases/${FKB1}/faq/entries/fields" -H 'Content-Type: application/json' -d '{}'
req faq-fields-negid.json          PUT "/knowledge-bases/${FKB1}/faq/entries/fields" -H 'Content-Type: application/json' -d '{"by_id":{"-5":{"is_enabled":false}}}'
req faq-fields-missing.json        PUT "/knowledge-bases/${FKB1}/faq/entries/fields" -H 'Content-Type: application/json' -d '{"by_id":{"999999":{"is_enabled":false}}}'
req faq-fields-badtag.json         PUT "/knowledge-bases/${FKB1}/faq/entries/fields" -H 'Content-Type: application/json' -d '{"by_id":{"970003":{"tag_id":960999}}}'
req faq-fields-foreigntag.json     PUT "/knowledge-bases/${FKB1}/faq/entries/fields" -H 'Content-Type: application/json' -d '{"by_id":{"970003":{"tag_id":960002}}}'
req faq-fields-ok.json             PUT "/knowledge-bases/${FKB1}/faq/entries/fields" -H 'Content-Type: application/json' \
  -d '{"by_id":{"970003":{"is_enabled":true,"is_recommended":false,"tag_id":965001}},"by_tag":{"965002":{"is_enabled":true,"tag_id":965001}},"exclude_ids":[970001]}'
req faq-get-after-fields.json      GET "/knowledge-bases/${FKB1}/faq/entries/970003"

echo "==> 7) 批量标签（UpdateEntryTagBatch）：400 / 200 / 移除标签"
req faq-tags-empty.json            PUT "/knowledge-bases/${FKB1}/faq/entries/tags" -H 'Content-Type: application/json' -d '{"updates":{}}'
req faq-tags-nullbody.json         PUT "/knowledge-bases/${FKB1}/faq/entries/tags" -H 'Content-Type: application/json' -d 'null'
req faq-tags-missing.json          PUT "/knowledge-bases/${FKB1}/faq/entries/tags" -H 'Content-Type: application/json' -d '{"updates":{"999999":965001}}'
req faq-tags-ok.json               PUT "/knowledge-bases/${FKB1}/faq/entries/tags" -H 'Content-Type: application/json' -d '{"updates":{"970002":965001,"970003":null}}'

echo "==> 8) 导入（UpsertEntries）：binding / tag 校验 / 400 / dry_run 异步"
req faq-upsert-noentries.json      POST "/knowledge-bases/${FKB1}/faq/entries" -H 'Content-Type: application/json' -d '{"entries":[]}'
req faq-upsert-badmode.json        POST "/knowledge-bases/${FKB1}/faq/entries" -H 'Content-Type: application/json' -d '{"entries":[{"standard_question":"问题","answers":["答案"]}],"mode":"bogus"}'
req faq-upsert-badtask.json        POST "/knowledge-bases/${FKB1}/faq/entries" -H 'Content-Type: application/json' -d '{"entries":[{"standard_question":"问题","answers":["答案"]}],"task_id":"bad/id"}'
req faq-upsert-nomode.json         POST "/knowledge-bases/${FKB1}/faq/entries" -H 'Content-Type: application/json' -d '{"entries":[{"standard_question":"问题","answers":["答案"]}]}'
req faq-upsert-foreigntag.json     POST "/knowledge-bases/${FKB2}/faq/entries" -H 'Content-Type: application/json' \
  -d '{"entries":[{"standard_question":"问题","answers":["答案"],"tag_id":960002}],"mode":"append"}'
req faq-upsert-ghosttag.json       POST "/knowledge-bases/${FKB1}/faq/entries" -H 'Content-Type: application/json' \
  -d '{"entries":[{"standard_question":"问题","answers":["答案"],"tag_id":960999}],"mode":"append"}'
req faq-upsert-dryrun.json         POST "/knowledge-bases/${FKB1}/faq/entries" -H 'Content-Type: application/json' \
  -d '{"entries":[{"standard_question":"怎么 绑定 手机？","answers":["重复的标准问"]},{"standard_question":"","answers":[]},{"standard_question":"全新问题","similar_questions":["如何绑定手机","如何绑定手机"],"answers":["新答案"],"answer_strategy":"random"}],"mode":"append","dry_run":true}'
DRY_TASK="$(python3 -c 'import json,sys; print(json.load(open("'"${OUT}"'/faq-upsert-dryrun.json"))["data"]["task_id"])')"
echo "    dry_run task: ${DRY_TASK}"
sleep 3
req faq-progress-dryrun.json       GET "/faq/import/progress/${DRY_TASK}"
# 自定义 task_id：能通过 ValidateTaskID，但不带 _<tenant>_ 段 → 进度查询恒 400 "invalid task ID"；
# 且它是**非 dry_run** → worker 卡在重试（无 embedding）→ FKB1 的 running key 被占，
# 后续对 FKB1 的导入全部 400 "该知识库已有导入任务正在进行中（任务ID: faqgolden_custom_1）"（固定文案，天然确定性）
req faq-upsert-customtask.json     POST "/knowledge-bases/${FKB1}/faq/entries" -H 'Content-Type: application/json' \
  -d '{"entries":[{"standard_question":"自定义任务ID问题","answers":["答案"]}],"mode":"append","task_id":"faqgolden_custom_1"}'
sleep 1
req faq-upsert-running.json        POST "/knowledge-bases/${FKB1}/faq/entries" -H 'Content-Type: application/json' \
  -d '{"entries":[{"standard_question":"撞上运行中任务","answers":["答案"]}],"mode":"append"}'
req faq-progress-badid.json        GET "/faq/import/progress/not_a_task"
req faq-progress-crosstenant.json  GET "/faq/import/progress/faq_import_1_1700000000000_abc12345"
req faq-progress-customtask.json   GET "/faq/import/progress/faqgolden_custom_1"

echo "==> 9) 导入结果显示状态（UpdateLastImportResultDisplayStatus）"
req faq-display-bad.json           PUT "/knowledge-bases/${FKB1}/faq/import/last-result/display" -H 'Content-Type: application/json' -d '{"display_status":"bogus"}'
req faq-display-nokb.json          PUT "/knowledge-bases/${FKB2}/faq/import/last-result/display" -H 'Content-Type: application/json' -d '{"display_status":"close"}'
req faq-display-noresult.json      PUT "/knowledge-bases/${FKB3}/faq/import/last-result/display" -H 'Content-Type: application/json' -d '{"display_status":"close"}'
req faq-display-close.json         PUT "/knowledge-bases/${FKB1}/faq/import/last-result/display" -H 'Content-Type: application/json' -d '{"display_status":"close"}'
req faq-display-open.json          PUT "/knowledge-bases/${FKB1}/faq/import/last-result/display" -H 'Content-Type: application/json' -d '{"display_status":"open"}'

echo "==> 10) 导出：CSV（含 BOM）+ JSON + 空库"
reqh faq-export-csv.body           GET "/knowledge-bases/${FKB1}/faq/entries/export"
reqh faq-export-json.body          GET "/knowledge-bases/${FKB1}/faq/entries/export?format=json"
reqh faq-export-empty-csv.body     GET "/knowledge-bases/${FKB2}/faq/entries/export"
reqh faq-export-empty-json.body    GET "/knowledge-bases/${FKB2}/faq/entries/export?format=json"

echo "==> 11) 搜索（SearchFAQ）：400 / 无 embedding 错误形态 / keyword（ab-only 用）"
req faq-search-empty-query.json    POST "/knowledge-bases/${FKB1}/faq/search" -H 'Content-Type: application/json' -d '{"query_text":""}'
req faq-search-noquery.json        POST "/knowledge-bases/${FKB1}/faq/search" -H 'Content-Type: application/json' -d '{}'
req faq-search-embed-missing.json  POST "/knowledge-bases/${FKB1}/faq/search" -H 'Content-Type: application/json' -d '{"query_text":"怎么绑定手机","match_count":5}'
req faq-search-kw-std.json         POST "/knowledge-bases/${FKB1}/faq/search" -H 'Content-Type: application/json' -d '{"query_text":"退货","match_count":5}'
req faq-search-notkb.json          POST "/knowledge-bases/${FKB4}/faq/search" -H 'Content-Type: application/json' -d '{"query_text":"退货"}'

echo "==> 12) 删除（DeleteEntries）：400 / 先删后 500 / 删除后读回"
req faq-delete-empty.json          DELETE "/knowledge-bases/${FKB1}/faq/entries" -H 'Content-Type: application/json' -d '{"ids":[]}'
req faq-delete-missing.json        DELETE "/knowledge-bases/${FKB1}/faq/entries" -H 'Content-Type: application/json' -d '{"ids":[999999]}'
req faq-delete-500.json            DELETE "/knowledge-bases/${FKB1}/faq/entries" -H 'Content-Type: application/json' -d '{"ids":[970004]}'
req faq-get-after-delete.json      GET "/knowledge-bases/${FKB1}/faq/entries/970004"
req faq-list-final.json            GET "/knowledge-bases/${FKB1}/faq/entries"

echo "==> 13) 非 dry_run 导入（只录立即响应；独立 KB，放最后）"
req faq-upsert-import.json         POST "/knowledge-bases/${FKB5}/faq/entries" -H 'Content-Type: application/json' \
  -d '{"entries":[{"standard_question":"导入一条","answers":["答案"]}],"mode":"append"}'
req faq-list-keyword.json          GET "/knowledge-bases/${FKB1}/faq/entries?keyword=%E7%BB%91%E5%AE%9A"
req faq-list-keyword-std.json      GET "/knowledge-bases/${FKB1}/faq/entries?keyword=%E9%80%80%E8%B4%A7&search_field=standard_question"
req faq-list-keyword-sim.json      GET "/knowledge-bases/${FKB1}/faq/entries?keyword=%E9%80%80%E8%B4%A7&search_field=similar_questions"
req faq-list-keyword-ans.json      GET "/knowledge-bases/${FKB1}/faq/entries?keyword=%E8%BF%90%E8%B4%B9&search_field=answers"

echo "==> 完成：$(ls "${OUT}" | grep -c '^faq-') 个 faq-* golden"
