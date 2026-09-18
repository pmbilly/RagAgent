#!/usr/bin/env bash
# 录 knowledge 模块「文档操作面」（波 2 第二批，15+1 条路由）的 golden。
#
# 场景设计（约定 §4 / §9：录制顺序影响状态，契约测试必须严格复刻本顺序）：
#   - KB1/KB2/KB3 经 API 创建（creator=owner 501），SQL 关掉 vector/keyword 索引
#     与 summary_model（NeedsEmbedding=false / summary 未配置 → 全确定性分支）；
#   - knowledges/chunks/tags/relations SQL 直插（固定纯十六进制 id，掩码友好）；
#   - KG1 的文件落在 Go 侧 LOCAL_STORAGE_BASE_DIR（local://kgdocs/kg-doc.txt），
#     下载/预览录真实字节 + 响应头（-D 落 headers）；
#   - 异步任务（batch-delete/batch-reparse/reparse/clear）Go 走 asynq，录完 sleep
#     让 worker 跑完，后续 folders 计数才确定；契约测试侧 Java 为同步尽力而为。
#   - contributor/viewer 用户用于 ownership 守卫的 403 信封；
#   - 跨租户 403 用租户 10000 的真实 knowledge 只读探测（不动 10000 数据）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${KG_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${KG_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"

UNKNOWN="11111111-2222-3333-4444-999999999999"

echo "==> 准备：本地存储文件（KG1 下载/预览的真实字节）"
mkdir -p "${LOCAL_STORAGE_BASE_DIR}/kgdocs"
printf 'kg golden download bytes\nline two\n' > "${LOCAL_STORAGE_BASE_DIR}/kgdocs/kg-doc.txt"

echo "==> 准备：KB1/KB2/KB3（API 建，creator=owner）+ 关索引"
TOKEN="$(login "${TEST_EMAIL}" "${PORT}")"
AUTH="Authorization: Bearer ${TOKEN}"

req() {
  local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "${AUTH}" "$@"
}
reqh() { # 下载/预览：另存响应头
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

CTOKEN="$(login java-phase1-contrib@weknora.test "${PORT}")"
VTOKEN="$(login "${TEST_VIEWER_EMAIL}" "${PORT}")"

KB1=""; KB2=""; KB3=""
# 重建前先清掉上次运行的同名 KB（幂等录制；失败残留也能收掉）
${PSQL} -c "DELETE FROM knowledge_bases WHERE name IN ('kg-golden-kb','kg-second-kb','kg-clear-kb');" >/dev/null
for pair in "kg-golden-kb:KB1" "kg-second-kb:KB2" "kg-clear-kb:KB3"; do
  name="${pair%%:*}"; var="${pair##*:}"
  curl -s -o /tmp/kg-kb-seed.json -X POST "${API}/knowledge-bases" \
    -H "${AUTH}" -H 'Content-Type: application/json' \
    -d "{\"name\":\"${name}\",\"description\":\"kg golden 专用\"}"
  id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' /tmp/kg-kb-seed.json)"
  eval "${var}=${id}"
done
${PSQL} -c "
UPDATE knowledge_bases SET indexing_strategy='{\"vector_enabled\":false,\"keyword_enabled\":false,\"wiki_enabled\":false,\"graph_enabled\":false}', summary_model_id='' WHERE id IN ('${KB1}','${KB2}','${KB3}');" >/dev/null
echo "    KB1=${KB1} KB2=${KB2} KB3=${KB3}"

echo "==> 准备：knowledges + tags + relations + chunks"
KG1="afa00001-0000-0000-0000-000000000001"
KG2="afa00001-0000-0000-0000-000000000002"
KG3="afa00001-0000-0000-0000-000000000003"
KG4="afa00001-0000-0000-0000-000000000004"
KG5="afa00001-0000-0000-0000-000000000005"
KG6="afa00001-0000-0000-0000-000000000006"
KG7="afa00001-0000-0000-0000-000000000007"
KG8="afa00001-0000-0000-0000-000000000008"
KG9="afa00001-0000-0000-0000-000000000009"
KG10="afa00001-0000-0000-0000-000000000010"
KG11="afa00001-0000-0000-0000-000000000011"
KG12="afa00001-0000-0000-0000-000000000012"
KG13="afa00001-0000-0000-0000-000000000013"
KG14="afa00001-0000-0000-0000-000000000014"
KG15="afa00001-0000-0000-0000-000000000015"
KG16="afa00001-0000-0000-0000-000000000016"
T1="bfb00001-0000-0000-0000-000000000001"
T2="bfb00001-0000-0000-0000-000000000002"
C1="bcc00001-0000-0000-0000-000000000001"
C2="bcc00001-0000-0000-0000-000000000002"
C3="bcc00001-0000-0000-0000-000000000003"
${PSQL} -c "
DELETE FROM knowledge_tag_relations WHERE knowledge_id LIKE 'afa00001%' OR tag_id LIKE 'bfb00001%';
DELETE FROM knowledge_tags WHERE id LIKE 'bfb00001%';
DELETE FROM chunks WHERE knowledge_id LIKE 'afa00001%';
DELETE FROM knowledges WHERE id LIKE 'afa00001%';
INSERT INTO knowledge_tags (id, seq_id, tenant_id, knowledge_base_id, name, color, sort_order) VALUES
('${T1}', 960001, 10002, '${KB1}', '重要', '', 0),
('${T2}', 960002, 10002, '${KB2}', '第二库', '', 0);
INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, source, parse_status, summary_status, enable_status, file_name, file_type, file_size, file_hash, file_path) VALUES
('${KG1}', 10002, '${KB1}', 'document', '文档一', 'manual', 'completed', 'none', 'enabled', 'kg-doc.txt', 'txt', 38, '0000000000000000000000000000000a', 'local://kgdocs/kg-doc.txt'),
('${KG2}', 10002, '${KB1}', 'document', '文档二', 'manual', 'completed', 'none', 'enabled', 'doc2.txt', 'txt', 10, '0000000000000000000000000000000b', ''),
('${KG3}', 10002, '${KB1}', 'document', '空文档', 'manual', 'completed', 'none', 'enabled', 'doc3.txt', 'txt', 10, '0000000000000000000000000000000c', ''),
('${KG4}', 10002, '${KB1}', 'manual', '手工知识', 'manual', 'completed', 'completed', 'enabled', '手工知识.md', 'manual', 40, '0000000000000000000000000000000d', ''),
('${KG5}', 10002, '${KB1}', 'document', '待解析', 'manual', 'pending', 'none', 'disabled', 'doc5.txt', 'txt', 10, '0000000000000000000000000000000e', ''),
('${KG6}', 10002, '${KB1}', 'document', '失败文档', 'manual', 'failed', 'failed', 'disabled', 'doc6.txt', 'txt', 10, '0000000000000000000000000000000f', ''),
('${KG7}', 10002, '${KB1}', 'document', '取消文档', 'manual', 'cancelled', 'none', 'disabled', 'doc7.txt', 'txt', 10, '00000000000000000000000000000010', ''),
('${KG8}', 10002, '${KB1}', 'document', '文件夹文档一', 'manual', 'completed', 'none', 'enabled', 'doc8.txt', 'txt', 10, '00000000000000000000000000000011', ''),
('${KG9}', 10002, '${KB1}', 'document', '文件夹文档二', 'manual', 'completed', 'none', 'enabled', 'doc9.txt', 'txt', 10, '00000000000000000000000000000012', ''),
('${KG10}', 10002, '${KB1}', 'document', '重解析文档', 'manual', 'completed', 'none', 'enabled', 'kg-doc.txt', 'txt', 38, '00000000000000000000000000000013', 'local://kgdocs/kg-doc.txt'),
('${KG11}', 10002, '${KB1}', 'document', '图片文档', 'manual', 'completed', 'none', 'enabled', 'doc11.txt', 'txt', 10, '00000000000000000000000000000014', ''),
('${KG12}', 10002, '${KB1}', 'document', '摘要文档', 'manual', 'completed', 'completed', 'enabled', 'doc12.txt', 'txt', 10, '00000000000000000000000000000015', ''),
('${KG13}', 10002, '${KB1}', 'document', '删除中文档', 'manual', 'deleting', 'none', 'disabled', 'doc13.txt', 'txt', 10, '00000000000000000000000000000016', ''),
('${KG14}', 10002, '${KB1}', 'document', '处理中文档', 'manual', 'processing', 'none', 'disabled', 'doc14.txt', 'txt', 10, '00000000000000000000000000000017', ''),
('${KG15}', 10002, '${KB1}', 'document', '穿越文档', 'manual', 'completed', 'none', 'enabled', 'evil.txt', 'txt', 10, '00000000000000000000000000000018', '../../../etc/passwd'),
('${KG16}', 10002, '${KB2}', 'document', '第二库文档', 'manual', 'completed', 'none', 'enabled', 'doc16.txt', 'txt', 10, '00000000000000000000000000000019', '');
UPDATE knowledges SET description='已有摘要' WHERE id='${KG12}';
UPDATE knowledges SET error_message='解析失败：文档格式不支持' WHERE id='${KG6}';
UPDATE knowledges SET folder_path='docs' WHERE id='${KG8}';
UPDATE knowledges SET folder_path='docs/readme' WHERE id='${KG9}';
UPDATE knowledges SET metadata='{\"content\":\"# 手工知识\n\n初始内容\",\"format\":\"markdown\",\"status\":\"publish\",\"version\":1,\"updated_at\":\"2026-09-01T00:00:00Z\"}'::jsonb WHERE id='${KG4}';
INSERT INTO knowledge_tag_relations (knowledge_id, tag_id) VALUES ('${KG1}', '${T1}');
INSERT INTO chunks (id, tenant_id, knowledge_id, knowledge_base_id, content, chunk_index, is_enabled, flags, status, start_at, end_at, chunk_type, source_content, content_revision, last_editor_id, metadata) VALUES
('${C1}', 10002, '${KG11}', '${KB1}', '图片父块', 0, true, 1, 0, 0, 5, 'text', '', 0, '', NULL),
('${C2}', 10002, '${KG11}', '${KB1}', '图一', 1, true, 1, 0, 5, 7, 'image_caption', '', 0, '', NULL),
('${C3}', 10002, '${KG11}', '${KB1}', '别图OCR', 2, true, 1, 0, 7, 9, 'image_ocr', '', 0, '', NULL);
UPDATE chunks SET parent_chunk_id='${C1}', image_info='[{\"url\":\"resource://img-1\",\"original_url\":\"\",\"start_pos\":0,\"end_pos\":0,\"caption\":\"图一\",\"ocr_text\":\"\"}]' WHERE id='${C2}';
UPDATE chunks SET parent_chunk_id='${C1}', image_info='[{\"url\":\"resource://img-2\",\"original_url\":\"\",\"start_pos\":0,\"end_pos\":0,\"caption\":\"别图\",\"ocr_text\":\"别图OCR\"}]' WHERE id='${C3}';" >/dev/null
echo "    KG1..KG16 / T1,T2 / C1..C3 就位"

CROSS_KG="$(${PSQL} -c "SELECT id FROM knowledges WHERE tenant_id=10000 AND deleted_at IS NULL LIMIT 1;" | tr -d ' ')"
echo "    跨租户探测用（10000）KG=${CROSS_KG}"

# ═══ 1) GET /knowledge/batch ═══
echo "==> 1) batch 读取"
req kg-batch.json GET "/knowledge/batch?ids=${KG1}"
req kg-batch-multi.json GET "/knowledge/batch?ids=${KG1}&ids=${KG2}"
req kg-batch-missing.json GET "/knowledge/batch?ids=${KG1}&ids=${UNKNOWN}"
req kg-batch-no-ids.json GET "/knowledge/batch"
req kg-batch-empty-ids.json GET "/knowledge/batch?ids="
req kg-batch-kbscope.json GET "/knowledge/batch?ids=${KG1}&kb_id=${KB1}"
req kg-batch-badkb.json GET "/knowledge/batch?ids=${KG1}&kb_id=${UNKNOWN}"
req kg-batch-agent-unknown.json GET "/knowledge/batch?ids=${KG1}&agent_id=nope"
req kg-batch-bad-source.json GET "/knowledge/batch?ids=${KG1}&agent_source_tenant_id=abc"

# ═══ 2) GET /knowledge/{id}/stages + /spans ═══
echo "==> 2) spans（合成 5 段时间线）"
req kg-spans-completed.json GET "/knowledge/${KG1}/spans"
req kg-stages-completed.json GET "/knowledge/${KG1}/stages"
req kg-spans-failed.json GET "/knowledge/${KG6}/spans"
req kg-spans-pending.json GET "/knowledge/${KG5}/spans"
req kg-spans-cancelled.json GET "/knowledge/${KG7}/spans"
req kg-spans-attempt2.json GET "/knowledge/${KG6}/spans?attempt=2"
req kg-spans-attempt-bad.json GET "/knowledge/${KG1}/spans?attempt=abc"
req kg-spans-404.json GET "/knowledge/${UNKNOWN}/spans"
req kg-spans-cross.json GET "/knowledge/${CROSS_KG}/spans"

# ═══ 3) POST /knowledge/{id}/regenerate-summary ═══
echo "==> 3) regenerate-summary（无 summary model 的确定性分支）"
req kg-regen-none.json POST "/knowledge/${KG1}/regenerate-summary"
req kg-regen-refresh.json POST "/knowledge/${KG12}/regenerate-summary"
req kg-regen-again.json POST "/knowledge/${KG12}/regenerate-summary"
req kg-regen-404.json POST "/knowledge/${UNKNOWN}/regenerate-summary"
req kg-regen-cross.json POST "/knowledge/${CROSS_KG}/regenerate-summary"

# ═══ 4) PUT /knowledge/manual/{id} ═══
echo "==> 4) manual 更新"
req kg-manual-update.json PUT "/knowledge/manual/${KG4}" -H 'Content-Type: application/json' \
  -d '{"title":"手工知识（改）","content":"# 手工知识\n\n更新后的正文","status":"publish"}'
req kg-manual-draft.json PUT "/knowledge/manual/${KG4}" -H 'Content-Type: application/json' \
  -d '{"title":"手工知识（改）","content":"# 手工知识\n\n草稿正文","status":"draft"}'
req kg-manual-empty.json PUT "/knowledge/manual/${KG4}" -H 'Content-Type: application/json' \
  -d '{"title":"手工知识（改）","content":"   ","status":"draft"}'
req kg-manual-badtitle.json PUT "/knowledge/manual/${KG4}" -H 'Content-Type: application/json' \
  -d '{"title":"<script>alert(1)</script>","content":"正文","status":"draft"}'
req kg-manual-badstatus.json PUT "/knowledge/manual/${KG4}" -H 'Content-Type: application/json' \
  -d '{"title":"手工知识（改）","content":"正文","status":"enabled"}'
req kg-manual-notmanual.json PUT "/knowledge/manual/${KG1}" -H 'Content-Type: application/json' \
  -d '{"title":"x","content":"正文","status":"draft"}'
req kg-manual-404.json PUT "/knowledge/manual/${UNKNOWN}" -H 'Content-Type: application/json' \
  -d '{"title":"x","content":"正文","status":"draft"}'
req kg-manual-no-body.json PUT "/knowledge/manual/${KG4}" -H 'Content-Type: application/json'
req kg-manual-cross.json PUT "/knowledge/manual/${CROSS_KG}" -H 'Content-Type: application/json' \
  -d '{"title":"x","content":"正文","status":"draft"}'

# ═══ 5) POST /knowledge/{id}/reparse ═══
echo "==> 5) reparse"
req kg-reparse.json POST "/knowledge/${KG10}/reparse"
req kg-reparse-null-config.json POST "/knowledge/${KG10}/reparse" -H 'Content-Type: application/json' \
  -d '{"process_config":null}'
req kg-reparse-bad.json POST "/knowledge/${KG10}/reparse" -H 'Content-Type: application/json' \
  -d 'not-json'
req kg-reparse-manual.json POST "/knowledge/${KG4}/reparse"
req kg-reparse-404.json POST "/knowledge/${UNKNOWN}/reparse"
req kg-reparse-cross.json POST "/knowledge/${CROSS_KG}/reparse"

# ═══ 6) POST /knowledge/{id}/cancel-parse ═══
echo "==> 6) cancel-parse"
req kg-cancel.json POST "/knowledge/${KG5}/cancel-parse"
req kg-cancel-again.json POST "/knowledge/${KG5}/cancel-parse"
req kg-cancel-cancelled-row.json POST "/knowledge/${KG7}/cancel-parse"
req kg-cancel-processing.json POST "/knowledge/${KG14}/cancel-parse"
req kg-cancel-deleting.json POST "/knowledge/${KG13}/cancel-parse"
req kg-cancel-completed.json POST "/knowledge/${KG1}/cancel-parse"
req kg-cancel-failed.json POST "/knowledge/${KG6}/cancel-parse"
req kg-cancel-404.json POST "/knowledge/${UNKNOWN}/cancel-parse"
req kg-cancel-cross.json POST "/knowledge/${CROSS_KG}/cancel-parse"

# ═══ 7) GET /knowledge/{id}/download + /preview ═══
echo "==> 7) 下载 / 预览"
reqh kg-download.bin GET "/knowledge/${KG1}/download"
reqh kg-download-manual.bin GET "/knowledge/${KG4}/download"
reqh kg-download-traversal.json GET "/knowledge/${KG15}/download"
req kg-download-404.json GET "/knowledge/${UNKNOWN}/download"
req kg-download-cross.json GET "/knowledge/${CROSS_KG}/download"
reqv kg-download-viewer.json GET "/knowledge/${KG1}/download"
reqh kg-preview.bin GET "/knowledge/${KG1}/preview"
reqh kg-preview-manual.bin GET "/knowledge/${KG4}/preview"
req kg-preview-traversal.json GET "/knowledge/${KG15}/preview"
req kg-preview-404.json GET "/knowledge/${UNKNOWN}/preview"
req kg-preview-cross.json GET "/knowledge/${CROSS_KG}/preview"

# ═══ 8) PUT /knowledge/image/{id}/{chunk_id} ═══
echo "==> 8) image info"
IMG='[{"url":"resource://img-1","original_url":"","start_pos":0,"end_pos":0,"caption":"新图说","ocr_text":"新OCR"}]'
req kg-image-update.json PUT "/knowledge/image/${KG11}/${C1}" -H 'Content-Type: application/json' \
  -d "{\"image_info\":$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "${IMG}")}"
req kg-image-again.json PUT "/knowledge/image/${KG11}/${C1}" -H 'Content-Type: application/json' \
  -d "{\"image_info\":$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "${IMG}")}"
req kg-image-empty.json PUT "/knowledge/image/${KG11}/${C1}" -H 'Content-Type: application/json' \
  -d '{"image_info":"[]"}'
req kg-image-badjson.json PUT "/knowledge/image/${KG11}/${C1}" -H 'Content-Type: application/json' \
  -d '{"image_info":"not-json"}'
req kg-image-mismatch.json PUT "/knowledge/image/${KG11}/${C2}" -H 'Content-Type: application/json' \
  -d "{\"image_info\":$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "${IMG}")}"
req kg-image-404.json PUT "/knowledge/image/${KG11}/${UNKNOWN}" -H 'Content-Type: application/json' \
  -d "{\"image_info\":$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "${IMG}")}"
req kg-image-no-body.json PUT "/knowledge/image/${KG11}/${C1}" -H 'Content-Type: application/json'

# ═══ 9) PUT /knowledge/tags ═══
echo "==> 9) tags 批量"
req kg-tags.json PUT "/knowledge/tags" -H 'Content-Type: application/json' \
  -d "{\"updates\":{\"${KG2}\":[\"${T1}\"]},\"kb_id\":\"${KB1}\"}"
req kg-get-tagged.json GET "/knowledge/${KG2}"
req kg-tags-clear.json PUT "/knowledge/tags" -H 'Content-Type: application/json' \
  -d "{\"updates\":{\"${KG2}\":[]}}"
req kg-tags-no-kbid.json PUT "/knowledge/tags" -H 'Content-Type: application/json' \
  -d "{\"updates\":{\"${KG2}\":[\"${T1}\"]}}"
req kg-tags-empty-updates.json PUT "/knowledge/tags" -H 'Content-Type: application/json' -d '{"updates":{}}'
req kg-tags-missing.json PUT "/knowledge/tags" -H 'Content-Type: application/json'
req kg-tags-unknown-tag.json PUT "/knowledge/tags" -H 'Content-Type: application/json' \
  -d "{\"updates\":{\"${KG2}\":[\"nope\"]},\"kb_id\":\"${KB1}\"}"
req kg-tags-wrong-kb.json PUT "/knowledge/tags" -H 'Content-Type: application/json' \
  -d "{\"updates\":{\"${KG2}\":[\"${T2}\"]},\"kb_id\":\"${KB1}\"}"
req kg-tags-unknown-knowledge.json PUT "/knowledge/tags" -H 'Content-Type: application/json' \
  -d "{\"updates\":{\"${UNKNOWN}\":[\"${T1}\"]},\"kb_id\":\"${KB1}\"}"
req kg-tags-cross-kb.json PUT "/knowledge/tags" -H 'Content-Type: application/json' \
  -d "{\"updates\":{\"${KG16}\":[\"${T2}\"]},\"kb_id\":\"${KB1}\"}"
req kg-tags-badkb.json PUT "/knowledge/tags" -H 'Content-Type: application/json' \
  -d "{\"updates\":{\"${KG2}\":[\"${T1}\"]},\"kb_id\":\"${UNKNOWN}\"}"
req kg-tags-cross-tenant.json PUT "/knowledge/tags" -H 'Content-Type: application/json' \
  -d "{\"updates\":{\"${CROSS_KG}\":[\"${T1}\"]}}"
reqc kg-tags-contrib.json PUT "/knowledge/tags" -H 'Content-Type: application/json' \
  -d "{\"updates\":{\"${KG2}\":[\"${T1}\"]},\"kb_id\":\"${KB1}\"}"

# ═══ 10) POST /knowledge/batch-delete（异步：录完 sleep 等 worker） ═══
echo "==> 10) batch-delete"
req kg-batch-delete.json POST "/knowledge/batch-delete" -H 'Content-Type: application/json' \
  -d "{\"kb_id\":\"${KB1}\",\"ids\":[\"${KG3}\"]}"
req kg-batch-delete-missing.json POST "/knowledge/batch-delete" -H 'Content-Type: application/json' \
  -d "{\"kb_id\":\"${KB1}\",\"ids\":[\"${KG2}\",\"${UNKNOWN}\"]}"
req kg-batch-delete-cross.json POST "/knowledge/batch-delete" -H 'Content-Type: application/json' \
  -d "{\"kb_id\":\"${KB1}\",\"ids\":[\"${KG16}\"]}"
req kg-batch-delete-empty-ids.json POST "/knowledge/batch-delete" -H 'Content-Type: application/json' \
  -d "{\"kb_id\":\"${KB1}\",\"ids\":[]}"
req kg-batch-delete-no-body.json POST "/knowledge/batch-delete" -H 'Content-Type: application/json'
req kg-batch-delete-badkb.json POST "/knowledge/batch-delete" -H 'Content-Type: application/json' \
  -d "{\"kb_id\":\"${UNKNOWN}\",\"ids\":[\"${KG2}\"]}"
reqc kg-batch-delete-contrib.json POST "/knowledge/batch-delete" -H 'Content-Type: application/json' \
  -d "{\"kb_id\":\"${KB1}\",\"ids\":[\"${KG2}\"]}"

# ═══ 11) POST /knowledge/batch-reparse ═══
echo "==> 11) batch-reparse"
req kg-batch-reparse.json POST "/knowledge/batch-reparse" -H 'Content-Type: application/json' \
  -d "{\"kb_id\":\"${KB1}\",\"ids\":[\"${KG2}\"]}"
req kg-batch-reparse-missing.json POST "/knowledge/batch-reparse" -H 'Content-Type: application/json' \
  -d "{\"kb_id\":\"${KB1}\",\"ids\":[\"${KG2}\",\"${UNKNOWN}\"]}"
req kg-batch-reparse-empty-ids.json POST "/knowledge/batch-reparse" -H 'Content-Type: application/json' \
  -d "{\"kb_id\":\"${KB1}\",\"ids\":[]}"
req kg-batch-reparse-no-body.json POST "/knowledge/batch-reparse" -H 'Content-Type: application/json'
req kg-batch-reparse-badkb.json POST "/knowledge/batch-reparse" -H 'Content-Type: application/json' \
  -d "{\"kb_id\":\"${UNKNOWN}\",\"ids\":[\"${KG2}\"]}"
req kg-batch-reparse-cross.json POST "/knowledge/batch-reparse" -H 'Content-Type: application/json' \
  -d "{\"kb_id\":\"${KB1}\",\"ids\":[\"${KG16}\"]}"
reqc kg-batch-reparse-contrib.json POST "/knowledge/batch-reparse" -H 'Content-Type: application/json' \
  -d "{\"kb_id\":\"${KB1}\",\"ids\":[\"${KG2}\"]}"

# ═══ 12) POST /knowledge/folder（移动到文件夹） ═══
echo "==> 12) folder move"
req kg-move.json POST "/knowledge/folder" -H 'Content-Type: application/json' \
  -d "{\"kb_id\":\"${KB1}\",\"knowledge_ids\":[\"${KG2}\"],\"folder_path\":\"docs/notes\"}"
req kg-move-back.json POST "/knowledge/folder" -H 'Content-Type: application/json' \
  -d "{\"kb_id\":\"${KB1}\",\"knowledge_ids\":[\"${KG2}\"],\"folder_path\":\"\"}"
req kg-move-empty.json POST "/knowledge/folder" -H 'Content-Type: application/json' \
  -d "{\"kb_id\":\"${KB1}\",\"knowledge_ids\":[]}"
req kg-move-missing.json POST "/knowledge/folder" -H 'Content-Type: application/json' \
  -d "{\"kb_id\":\"${KB1}\",\"knowledge_ids\":[\"${UNKNOWN}\"]}"
req kg-move-cross.json POST "/knowledge/folder" -H 'Content-Type: application/json' \
  -d "{\"kb_id\":\"${KB1}\",\"knowledge_ids\":[\"${KG16}\"]}"
req kg-move-badkb.json POST "/knowledge/folder" -H 'Content-Type: application/json' \
  -d "{\"kb_id\":\"${UNKNOWN}\",\"knowledge_ids\":[\"${KG2}\"]}"
req kg-move-badpath.json POST "/knowledge/folder" -H 'Content-Type: application/json' \
  -d "{\"kb_id\":\"${KB1}\",\"knowledge_ids\":[\"${KG2}\"],\"folder_path\":\"<script>x\"}"
req kg-move-nokb.json POST "/knowledge/folder" -H 'Content-Type: application/json' \
  -d "{\"knowledge_ids\":[\"${KG2}\"]}"
req kg-move-no-body.json POST "/knowledge/folder" -H 'Content-Type: application/json'
reqc kg-move-contrib.json POST "/knowledge/folder" -H 'Content-Type: application/json' \
  -d "{\"kb_id\":\"${KB1}\",\"knowledge_ids\":[\"${KG2}\"]}"

# ═══ 13) PUT /knowledge-bases/{id}/knowledge/folders（重命名） ═══
echo "==> 13) folder rename"
req kg-rename.json PUT "/knowledge-bases/${KB1}/knowledge/folders" -H 'Content-Type: application/json' \
  -d '{"from":"docs","to":"documents"}'
req kg-rename-same.json PUT "/knowledge-bases/${KB1}/knowledge/folders" -H 'Content-Type: application/json' \
  -d '{"from":"documents","to":"documents"}'
req kg-rename-into-self.json PUT "/knowledge-bases/${KB1}/knowledge/folders" -H 'Content-Type: application/json' \
  -d '{"from":"documents","to":"documents/sub"}'
req kg-rename-badto.json PUT "/knowledge-bases/${KB1}/knowledge/folders" -H 'Content-Type: application/json' \
  -d '{"from":"documents","to":"<script>x"}'
req kg-rename-empty-to.json PUT "/knowledge-bases/${KB1}/knowledge/folders" -H 'Content-Type: application/json' \
  -d '{"from":"documents","to":""}'
req kg-rename-no-from.json PUT "/knowledge-bases/${KB1}/knowledge/folders" -H 'Content-Type: application/json' \
  -d '{"to":"x"}'
req kg-rename-404.json PUT "/knowledge-bases/${UNKNOWN}/knowledge/folders" -H 'Content-Type: application/json' \
  -d '{"from":"documents","to":"x"}'
reqc kg-rename-contrib.json PUT "/knowledge-bases/${KB1}/knowledge/folders" -H 'Content-Type: application/json' \
  -d '{"from":"documents","to":"x"}'
req kg-folders2.json GET "/knowledge-bases/${KB1}/knowledge/folders"

# ═══ 14) DELETE /knowledge-bases/{id}/knowledge（清空） ═══
echo "==> 14) clear contents"
req kg-clear-nonempty.json DELETE "/knowledge-bases/${KB2}/knowledge"
req kg-clear-again.json DELETE "/knowledge-bases/${KB2}/knowledge"
req kg-clear-empty.json DELETE "/knowledge-bases/${KB3}/knowledge"
req kg-clear-404.json DELETE "/knowledge-bases/${UNKNOWN}/knowledge"
reqc kg-clear-contrib.json DELETE "/knowledge-bases/${KB1}/knowledge"

echo "==> 等待 Go asynq worker 收尾（删除/重解析是异步的）"
sleep 4

echo "done. KB1=${KB1} KB2=${KB2} KB3=${KB3}"
