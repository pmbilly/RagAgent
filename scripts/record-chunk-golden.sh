#!/usr/bin/env bash
# 录 chunk 模块（波 2 第一批，10 条路由）的 golden。
#
# 场景设计（约定 §4：录制顺序影响状态，契约测试必须严格复刻本顺序）：
#   - KB1 经 API 创建（creator=owner……501），SQL 关掉 vector/keyword 索引
#     （NeedsEmbedding=false → syncChunkIndex 早退 → index_status=ready，全确定性）；
#   - knowledges/chunks SQL 直插（chunk 没有 HTTP 创建端点，聊天管线产物）；
#   - contributor 用户直插（对照 403 纯字符串的 ownership 守卫）；
#   - 跨租户 403 用租户 10000 的真实 knowledge 只读探测（不动 10000 数据）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${RAGAGENT_ROOT}/server/src/test/resources/contracts"
API="http://localhost:${GO_PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"

UNKNOWN="11111111-2222-3333-4444-999999999999"

echo "==> 准备：contributor 用户（复用 owner 口令哈希，须在登录前就位）"
OWNER_HASH="$(${PSQL} -c "SELECT password_hash FROM users WHERE id='11111111-2222-3333-4444-555555555501';" | tr -d ' ')"
${PSQL} -c "
INSERT INTO users (id, username, email, password_hash, tenant_id) VALUES
('11111111-2222-3333-4444-555555555505', 'phase1contrib', 'java-phase1-contrib@weknora.test', '${OWNER_HASH}', 10002)
ON CONFLICT (id) DO NOTHING;
INSERT INTO tenant_members (user_id, tenant_id, role, status) VALUES
('11111111-2222-3333-4444-555555555505', 10002, 'contributor', 'active') ON CONFLICT DO NOTHING;" >/dev/null

TOKEN="$(login "${TEST_EMAIL}" "${GO_PORT}")"
AUTH="Authorization: Bearer ${TOKEN}"
CTOKEN="$(login java-phase1-contrib@weknora.test "${GO_PORT}")"
CAUTH="Authorization: Bearer ${CTOKEN}"
VTOKEN="$(login "${TEST_VIEWER_EMAIL}" "${GO_PORT}")"
VAUTH="Authorization: Bearer ${VTOKEN}"

req() {
  local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "${AUTH}" "$@"
}
reqc() { # contributor
  local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "${CAUTH}" "$@"
}
reqv() { # viewer
  local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "${VAUTH}" "$@"
}

UNKNOWN="11111111-2222-3333-4444-999999999999"

echo "==> 准备：KB1（API 建，creator=owner）+ 关索引"
req chunk-seed-kb.json POST /knowledge-bases -H 'Content-Type: application/json' \
  -d '{"name":"chunk-golden-kb","description":"chunk golden 专用"}'
KB1="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "${OUT}/chunk-seed-kb.json")"
rm -f "${OUT}/chunk-seed-kb.json"
${PSQL} -c "UPDATE knowledge_bases SET indexing_strategy='{\"vector_enabled\":false,\"keyword_enabled\":false,\"wiki_enabled\":false,\"graph_enabled\":false}', summary_model_id='' WHERE id='${KB1}';" >/dev/null
echo "    KB1=${KB1}"

echo "==> 准备：knowledges + chunks"
KG1="aaa00001-0000-0000-0000-000000000001"
KG2="aaa00001-0000-0000-0000-000000000002"
KG3="aaa00001-0000-0000-0000-000000000003"
C1="bbb00001-0000-0000-0000-000000000001"
C2="bbb00001-0000-0000-0000-000000000002"
C3="bbb00001-0000-0000-0000-000000000003"
C4="bbb00001-0000-0000-0000-000000000004"
C5="bbb00001-0000-0000-0000-000000000005"
C6="bbb00001-0000-0000-0000-000000000006"
Q1="ccc00001-0000-0000-0000-000000000001"
${PSQL} -c "
INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, source, parse_status, summary_status, description) VALUES
('${KG1}', 10002, '${KB1}', 'document', '文档一', 'manual', 'completed', 'none', NULL),
('${KG2}', 10002, '${KB1}', 'document', '文档二', 'manual', 'completed', 'none', NULL),
('${KG3}', 10002, '${KB1}', 'document', '空文档', 'manual', 'completed', 'none', NULL);
INSERT INTO chunks (id, tenant_id, knowledge_id, knowledge_base_id, content, chunk_index, is_enabled, flags, status, start_at, end_at, chunk_type, source_content, content_revision, last_editor_id, metadata) VALUES
('${C1}', 10002, '${KG1}', '${KB1}', '第一段内容', 0, true, 1, 0, 0, 6, 'text', '', 0, '', NULL),
('${C2}', 10002, '${KG1}', '${KB1}', '第二段内容', 1, true, 1, 0, 6, 12, 'text', '', 0, '', NULL),
('${C3}', 10002, '${KG1}', '${KB1}', '图一', 2, true, 1, 0, 12, 14, 'image_ocr', '', 0, '', NULL),
('${C4}', 10002, '${KG1}', '${KB1}', '问答内容', 3, true, 1, 0, 14, 18, 'text', '', 0, '',
 '{\"generated_questions\":[{\"id\":\"${Q1}\",\"question\":\"已有问题?\",\"content_revision\":0}],\"generated_questions_revision\":0}'),
('${C5}', 10002, '${KG1}', '${KB1}', '带图 ![img](resource://a.png) 与 HTML <img src=\"resource://b.png\"> 的段落', 4, true, 1, 0, 18, 60, 'text', '', 0, '', NULL),
('${C6}', 10002, '${KG2}', '${KB1}', '第二篇唯一段', 0, true, 1, 0, 0, 8, 'text', '', 0, '', NULL);
UPDATE chunks SET parent_chunk_id='${C1}', image_info='[{\"url\":\"resource://img-1\",\"original_url\":\"\",\"start_pos\":0,\"end_pos\":0,\"caption\":\"图一\",\"ocr_text\":\"OCR文字\"}]' WHERE id='${C3}';" >/dev/null
echo "    KG1=${KG1} KG2=${KG2} KG3=${KG3} C1..C6 就位"

CROSS_KG="$(${PSQL} -c "SELECT id FROM knowledges WHERE tenant_id=10000 AND deleted_at IS NULL LIMIT 1;" | tr -d ' ')"
echo "    跨租户探测用（10000）KG=${CROSS_KG}"

echo "==> 1) 列表与读取"
req chunk-list.json GET "/chunks/${KG1}"
req chunk-list-type-filter.json GET "/chunks/${KG1}?chunk_type=image_ocr"
req chunk-list-paged.json GET "/chunks/${KG1}?page=1&page_size=2"
req chunk-list-page0.json GET "/chunks/${KG1}?page=0&page_size=2"
req chunk-list-badpage.json GET "/chunks/${KG1}?page=abc"
req chunk-list-badsize.json GET "/chunks/${KG1}?page_size=-5"
req chunk-list-empty.json GET "/chunks/${KG3}"
req chunk-list-404.json GET "/chunks/${UNKNOWN}"
req chunk-list-cross-tenant.json GET "/chunks/${CROSS_KG}"
req chunk-by-id.json GET "/chunks/by-id/${C1}"
req chunk-by-id-404.json GET "/chunks/by-id/${UNKNOWN}"

echo "==> 2) 更新（顺序敏感：C1 的 revision 会推进）"
req chunk-update.json PUT "/chunks/${KG1}/${C1}" -H 'Content-Type: application/json' \
  -d '{"content":"第一段内容（已编辑）","expected_revision":0}'
req chunk-update-conflict.json PUT "/chunks/${KG1}/${C1}" -H 'Content-Type: application/json' \
  -d '{"content":"再改一次","expected_revision":0}'
req chunk-update-empty.json PUT "/chunks/${KG1}/${C2}" -H 'Content-Type: application/json' \
  -d '{"content":"   "}'
req chunk-update-image-chunk.json PUT "/chunks/${KG1}/${C3}" -H 'Content-Type: application/json' \
  -d '{"content":"不能改图"}'
req chunk-update-add-image.json PUT "/chunks/${KG1}/${C5}" -H 'Content-Type: application/json' \
  -d '{"content":"带图 ![img](resource://a.png) 加新图 ![n](resource://new.png)"}'
req chunk-update-404.json PUT "/chunks/${KG1}/${UNKNOWN}" -H 'Content-Type: application/json' \
  -d '{"content":"x"}'
req chunk-update-mismatch.json PUT "/chunks/${KG2}/${C1}" -H 'Content-Type: application/json' \
  -d '{"content":"x"}'
req chunk-update-no-body.json PUT "/chunks/${KG1}/${C2}" -H 'Content-Type: application/json'
req chunk-update-bad-json.json PUT "/chunks/${KG1}/${C2}" -H 'Content-Type: application/json' -d 'not-json'
req chunk-update-disable.json PUT "/chunks/${KG1}/${C2}" -H 'Content-Type: application/json' \
  -d '{"is_enabled":false}'

echo "==> 3) 修订历史与回滚"
req chunk-revisions.json GET "/chunks/${KG1}/${C1}/revisions"
req chunk-revert-missing.json POST "/chunks/${KG1}/${C1}/revert" -H 'Content-Type: application/json' -d '{}'
req chunk-revert-negative.json POST "/chunks/${KG1}/${C1}/revert" -H 'Content-Type: application/json' -d '{"revision":-1}'
req chunk-revert-unknown.json POST "/chunks/${KG1}/${C1}/revert" -H 'Content-Type: application/json' -d '{"revision":99}'
req chunk-revert.json POST "/chunks/${KG1}/${C1}/revert" -H 'Content-Type: application/json' -d '{"revision":0}'

echo "==> 4) 生成问题（by-id）"
req chunk-q-create.json PUT "/chunks/by-id/${C4}/questions" -H 'Content-Type: application/json' \
  -d '{"question":"新问题?"}'
req chunk-q-update.json PUT "/chunks/by-id/${C4}/questions" -H 'Content-Type: application/json' \
  -d "{\"question_id\":\"${Q1}\",\"question\":\"已有问题（改）?\"}"
req chunk-q-update-missing.json PUT "/chunks/by-id/${C4}/questions" -H 'Content-Type: application/json' \
  -d '{"question_id":"nope","question":"x"}'
req chunk-q-empty.json PUT "/chunks/by-id/${C4}/questions" -H 'Content-Type: application/json' \
  -d "{\"question_id\":\"${Q1}\",\"question\":\"   \"}"
req chunk-q-404.json PUT "/chunks/by-id/${UNKNOWN}/questions" -H 'Content-Type: application/json' \
  -d '{"question":"x"}'
CREATED_Q="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "${OUT}/chunk-q-create.json")"
req chunk-q-delete.json DELETE "/chunks/by-id/${C4}/questions" -H 'Content-Type: application/json' \
  -d "{\"question_id\":\"${Q1}\"}"
req chunk-q-delete-created.json DELETE "/chunks/by-id/${C4}/questions" -H 'Content-Type: application/json' \
  -d "{\"question_id\":\"${CREATED_Q}\"}"
req chunk-q-delete-none.json DELETE "/chunks/by-id/${C4}/questions" -H 'Content-Type: application/json' \
  -d "{\"question_id\":\"${Q1}\"}"
req chunk-q-delete-no-body.json DELETE "/chunks/by-id/${C4}/questions" -H 'Content-Type: application/json'
req chunk-q-regen-no-model.json POST "/chunks/by-id/${C1}/questions/regenerate"
req chunk-q-regen-image.json POST "/chunks/by-id/${C3}/questions/regenerate"
req chunk-q-regen-404.json POST "/chunks/by-id/${UNKNOWN}/questions/regenerate"

echo "==> 5) 权限矩阵（contributor/viewer 撞 owner 的 KB）"
reqc chunk-own-contrib-put.json PUT "/chunks/${KG1}/${C2}" -H 'Content-Type: application/json' -d '{"content":"越权改"}'
reqc chunk-own-contrib-delete.json DELETE "/chunks/${KG1}/${C2}"
reqv chunk-own-viewer-delete.json DELETE "/chunks/${KG1}/${C2}"
reqv chunk-viewer-read.json GET "/chunks/${KG1}"

echo "==> 6) 删除（最后，消费状态）"
req chunk-delete.json DELETE "/chunks/${KG2}/${C6}"
req chunk-delete-404.json DELETE "/chunks/${KG2}/${C6}"
req chunk-delete-all.json DELETE "/chunks/${KG2}"
req chunk-delete-all-again.json DELETE "/chunks/${KG2}"

echo "done. KB1=${KB1} KG1=${KG1} KG2=${KG2} KG3=${KG3}"
