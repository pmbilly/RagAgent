#!/usr/bin/env bash
# 录 knowledge 模块「搜索与移动/复制」（波 2 第三批，8 条路由）的 golden。
#
# 场景设计（约定 §4 / §9 / §9「波 2 chunk」的录制纪律）：
#   - KB1..KB7 经 API/SQL 建（creator=owner 501）；种子 knowledges 纯十六进制 id
#     （a2e00001-…，掩码正则认 [0-9a-f-]，§9「波 2 chunk」#8）；
#   - 搜索 golden 用 unique 关键词 ksdoc——dev PG 租户 10002 有历史残留行，
#     关键词命中集合只含本批种子（recent=true 的无过滤浏览含残留，
#     只录 limit=3 的 top3 = 本批最新三行；录制后不得再往 10002 插新行，
#     A/B 前注意）；
#   - created_at 显式给出且互不相同（Go 按 created_at DESC 排序，并列不稳定）；
#   - hybrid-search 只录确定性分支（retriever 未翻译，波 4）：空库 200 data:null、
#     各 400/403/404；真实向量/关键词命中路径 Go 能出结果，Java 侧为已知差异；
#   - move/copy 是 asynq 异步：录完 sleep 4s 等 worker 收尾，progress 录终态
#     （completed/100）；Go worker 覆写 progress 时**不保留 created_at**（置 0），
#     这是源码行为的实录，别"修好"它；
#   - duplicate 是同步的：zh-CN 缺省后缀 " 副本"，重名去重 " 2"；
#   - 录制顺序 = 契约测试播种顺序：search → hybrid → move(+progress) →
#     copy(+progress) → duplicate（copy 会删 KS2 原行，必须在 search 之后）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${KG_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${KG_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"

UNKNOWN="11111111-2222-3333-4444-999999999999"

echo "==> 清理上一次运行残留（幂等录制）"
# 探测期的 ks-probe 残留也一并收掉
${PSQL} -c "DELETE FROM chunks WHERE knowledge_base_id IN (SELECT id FROM knowledge_bases WHERE name LIKE 'ks-probe%' OR name LIKE 'ks-golden%');" >/dev/null
${PSQL} -c "DELETE FROM knowledges WHERE knowledge_base_id IN (SELECT id FROM knowledge_bases WHERE name LIKE 'ks-probe%' OR name LIKE 'ks-golden%');" >/dev/null
${PSQL} -c "DELETE FROM knowledge_bases WHERE name LIKE 'ks-probe%' OR name LIKE 'ks-golden%';" >/dev/null

echo "==> 准备：KB1..KB7 + 种子 knowledges"
TOKEN="$(login "${TEST_EMAIL}" "${PORT}")"
AUTH="Authorization: Bearer ${TOKEN}"
CTOKEN="$(login java-phase1-contrib@weknora.test "${PORT}")"
VTOKEN="$(login "${TEST_VIEWER_EMAIL}" "${PORT}")"

req() {
  local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
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

KB1=""; KB2=""; KB3=""; KB4=""
for pair in "ks-golden-kb:KB1" "ks-golden-second:KB2" "ks-golden-empty:KB3" "ks-golden-faq:KB4"; do
  name="${pair%%:*}"; var="${pair##*:}"
  type="document"; [ "${name}" = "ks-golden-faq" ] && type="faq"
  curl -s -o /tmp/ks-kb-seed.json -X POST "${API}/knowledge-bases" \
    -H "${AUTH}" -H 'Content-Type: application/json' \
    -d "{\"name\":\"${name}\",\"description\":\"ks golden 专用\",\"type\":\"${type}\"}"
  id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' /tmp/ks-kb-seed.json)"
  eval "${var}=${id}"
done
# KB5/KB6/KB7 走 SQL（挂 embedding/store 差异，API 建完再 UPDATE 更省事）
for pair in "ks-golden-emb:KB5" "ks-golden-store:KB6" "ks-golden-src:KB7"; do
  name="${pair%%:*}"; var="${pair##*:}"
  curl -s -o /tmp/ks-kb-seed.json -X POST "${API}/knowledge-bases" \
    -H "${AUTH}" -H 'Content-Type: application/json' \
    -d "{\"name\":\"${name}\",\"description\":\"ks golden 专用\"}"
  id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' /tmp/ks-kb-seed.json)"
  eval "${var}=${id}"
done
${PSQL} -c "
UPDATE knowledge_bases SET indexing_strategy='{\"vector_enabled\":false,\"keyword_enabled\":false,\"wiki_enabled\":false,\"graph_enabled\":false}', summary_model_id='' WHERE id IN ('${KB1}','${KB2}','${KB3}','${KB7}');
UPDATE knowledge_bases SET embedding_model_id='emb-other' WHERE id='${KB5}';
UPDATE knowledge_bases SET vector_store_id='b1c2d3d4-0000-0000-0000-000000000001' WHERE id='${KB6}';" >/dev/null
echo "    KB1=${KB1}"; echo "    KB2=${KB2}"; echo "    KB3=${KB3}"; echo "    KB4=${KB4}"
echo "    KB5=${KB5}"; echo "    KB6=${KB6}"; echo "    KB7=${KB7}"

# 种子行：created_at 互不相同（Go 按 created_at DESC，并列顺序不稳定）
${PSQL} -c "
DELETE FROM chunks WHERE knowledge_id LIKE 'a2e00001%';
DELETE FROM knowledges WHERE id LIKE 'a2e00001%';
INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, source, parse_status, summary_status, enable_status, file_name, file_type, file_size, file_hash, created_at, updated_at) VALUES
('a2e00001-0000-0000-0000-000000000001', 10002, '${KB1}', 'document', 'ksdoc alpha 指南', 'manual', 'completed', 'none', 'enabled', 'ksdoc-alpha.txt', 'txt', 10, '000000000000000000000000000000a1', now() - interval '50 minutes', now() - interval '50 minutes'),
('a2e00001-0000-0000-0000-000000000002', 10002, '${KB1}', 'document', 'beta 报表', 'manual', 'completed', 'none', 'enabled', 'ksdoc-beta.txt', 'txt', 10, '000000000000000000000000000000a2', now() - interval '40 minutes', now() - interval '40 minutes'),
('a2e00001-0000-0000-0000-000000000003', 10002, '${KB1}', 'document', 'ksdoc gamma 手册', 'manual', 'completed', 'none', 'enabled', 'ksdoc-gamma.pdf', 'pdf', 10, '000000000000000000000000000000a3', now() - interval '30 minutes', now() - interval '30 minutes'),
('a2e00001-0000-0000-0000-000000000004', 10002, '${KB1}', 'document', 'ksdoc delta 表格', 'manual', 'completed', 'none', 'enabled', 'ksdoc-delta.xlsx', 'xlsx', 10, '000000000000000000000000000000a4', now() - interval '20 minutes', now() - interval '20 minutes'),
('a2e00001-0000-0000-0000-000000000005', 10002, '${KB1}', 'document', 'ksdoc epsilon 待解析', 'manual', 'pending', 'none', 'enabled', 'ksdoc-epsilon.txt', 'txt', 10, '000000000000000000000000000000a5', now() - interval '5 minutes', now() - interval '5 minutes'),
('a2e00001-0000-0000-0000-000000000006', 10002, '${KB2}', 'document', '第二库的 ksdoc', 'manual', 'completed', 'none', 'enabled', 'ksdoc-sixth.txt', 'txt', 10, '000000000000000000000000000000a6', now(), now()),
('a2e00001-0000-0000-0000-000000000007', 10002, '${KB7}', 'document', 'clone 源一', 'manual', 'completed', 'none', 'enabled', 'clone-src-one.txt', 'txt', 10, '000000000000000000000000000000a7', now() - interval '45 minutes', now() - interval '45 minutes'),
('a2e00001-0000-0000-0000-000000000008', 10002, '${KB7}', 'document', 'clone 源二', 'manual', 'completed', 'none', 'enabled', 'clone-src-two.txt', 'txt', 10, '000000000000000000000000000000a8', now() - interval '44 minutes', now() - interval '44 minutes');
INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, source, parse_status, summary_status, enable_status, file_name, file_type, file_size, file_hash, created_at, updated_at) VALUES
('a2e00001-0000-0000-0000-000000000009', 10002, '${KB1}', 'url', 'ksdoc url 页', 'url', 'completed', 'none', 'enabled', 'https://example.com/ksdoc-page', 'url', 0, '000000000000000000000000000000a9', now() - interval '10 minutes', now() - interval '10 minutes');" >/dev/null
KG1="a2e00001-0000-0000-0000-000000000001"; KG2="a2e00001-0000-0000-0000-000000000002"
KG3="a2e00001-0000-0000-0000-000000000003"; KG4="a2e00001-0000-0000-0000-000000000004"
KG5="a2e00001-0000-0000-0000-000000000005"; KG6="a2e00001-0000-0000-0000-000000000006"
KG7="a2e00001-0000-0000-0000-000000000007"; KG8="a2e00001-0000-0000-0000-000000000008"
KG9="a2e00001-0000-0000-0000-000000000009"
echo "    KG1..KG9 就位"

CROSS_KB="$(${PSQL} -c "SELECT id FROM knowledge_bases WHERE tenant_id=10000 AND deleted_at IS NULL LIMIT 1;" | tr -d ' ')"
echo "    跨租户（10000）KB=${CROSS_KB}"

# ═══ 1) GET /knowledge/search ═══
echo "==> 1) search"
req ks-search-missing.json GET "/knowledge/search"
req ks-search-blank.json GET "/knowledge/search?keyword=%20%20"
req ks-search-recent-false.json GET "/knowledge/search?keyword=&recent=false"
req ks-search-query-alias.json GET "/knowledge/search?query=ksdoc&limit=100"
req ks-search-hits.json GET "/knowledge/search?keyword=ksdoc"
req ks-search-case.json GET "/knowledge/search?keyword=KSDOC"
req ks-search-title.json GET "/knowledge/search?keyword=%E6%8C%87%E5%8D%97"
req ks-search-none.json GET "/knowledge/search?keyword=zzzznope"
# 注意：不带过滤的 recent=true 浏览的 total=租户全量行数，dev PG 里含历史残留，
# 不是契约稳定值——只录 file_types 收敛后的 recent（total 只含本批种子）。
# req ks-search-recent.json GET "/knowledge/search?recent=true&limit=3"  # 残留敏感，勿录
req ks-search-recent-url.json GET "/knowledge/search?recent=true&file_types=url"
req ks-search-ft-pdf.json GET "/knowledge/search?keyword=ksdoc&file_types=pdf"
req ks-search-ft-xls-alias.json GET "/knowledge/search?keyword=ksdoc&file_types=xls"
req ks-search-ft-multi.json GET "/knowledge/search?keyword=ksdoc&file_types=pdf,txt"
req ks-search-page.json GET "/knowledge/search?keyword=ksdoc&offset=1&limit=2"
req ks-search-offset-beyond.json GET "/knowledge/search?keyword=ksdoc&offset=99&limit=2"
req ks-search-offset-neg.json GET "/knowledge/search?keyword=a&offset=-1"
req ks-search-offset-nan.json GET "/knowledge/search?keyword=a&offset=abc"
req ks-search-limit-zero.json GET "/knowledge/search?keyword=a&limit=0"
req ks-search-limit-over.json GET "/knowledge/search?keyword=a&limit=101"
req ks-search-limit-nan.json GET "/knowledge/search?keyword=a&limit=abc"
req ks-search-agent.json GET "/knowledge/search?keyword=x&agent_id=nope"
reqv ks-search-viewer.json GET "/knowledge/search?keyword=ksdoc&limit=1"
req ks-search-badbool.json GET "/knowledge/search?keyword=zzz&recent=notabool"

# ═══ 2) POST+GET /knowledge-bases/{id}/hybrid-search ═══
echo "==> 2) hybrid-search（确定性分支：retriever 未接线）"
req ks-hybrid-404.json POST "/knowledge-bases/${UNKNOWN}/hybrid-search" -H 'Content-Type: application/json' -d '{"query_text":"x"}'
req ks-hybrid-cross.json POST "/knowledge-bases/${CROSS_KB}/hybrid-search" -H 'Content-Type: application/json' -d '{"query_text":"x"}'
req ks-hybrid-nobody.json POST "/knowledge-bases/${KB3}/hybrid-search" -H 'Content-Type: application/json'
req ks-hybrid-badjson.json POST "/knowledge-bases/${KB3}/hybrid-search" -H 'Content-Type: application/json' -d 'not-json'
req ks-hybrid-missing.json POST "/knowledge-bases/${KB3}/hybrid-search" -H 'Content-Type: application/json' -d '{}'
req ks-hybrid-blank.json POST "/knowledge-bases/${KB3}/hybrid-search" -H 'Content-Type: application/json' -d '{"query_text":"   "}'
req ks-hybrid-empty.json POST "/knowledge-bases/${KB3}/hybrid-search" -H 'Content-Type: application/json' -d '{"query_text":"probe"}'
req ks-hybrid-matchcount.json POST "/knowledge-bases/${KB3}/hybrid-search" -H 'Content-Type: application/json' -d '{"query_text":"probe","match_count":5}'
req ks-hybrid-novec.json POST "/knowledge-bases/${KB3}/hybrid-search" -H 'Content-Type: application/json' -d '{"query_text":"probe","disable_vector_match":true}'
req ks-hybrid-precomputed.json POST "/knowledge-bases/${KB3}/hybrid-search" -H 'Content-Type: application/json' -d '{"query_embedding":[0.1,0.2],"disable_keywords_match":true}'
req ks-hybrid-badmode.json POST "/knowledge-bases/${KB3}/hybrid-search?resource_urls=bogus" -H 'Content-Type: application/json' -d '{"query_text":"probe"}'
req ks-hybrid-unknown-multi.json POST "/knowledge-bases/${KB3}/hybrid-search" -H 'Content-Type: application/json' -d '{"query_text":"x","knowledge_base_ids":["'"${UNKNOWN}"'"]}'
req ks-hybrid-get.json GET "/knowledge-bases/${KB3}/hybrid-search" -H 'Content-Type: application/json' -d '{"query_text":"probe"}'
req ks-hybrid-get-nobody.json GET "/knowledge-bases/${KB3}/hybrid-search" -H 'Content-Type: application/json'

# ═══ 3) POST /knowledge/move ═══
echo "==> 3) move"
req ks-move-same.json POST "/knowledge/move" -H 'Content-Type: application/json' \
  -d "{\"knowledge_ids\":[\"${KG1}\"],\"source_kb_id\":\"${KB1}\",\"target_kb_id\":\"${KB1}\",\"mode\":\"reparse\"}"
req ks-move-missing-source.json POST "/knowledge/move" -H 'Content-Type: application/json' \
  -d "{\"knowledge_ids\":[\"${KG1}\"],\"source_kb_id\":\"${UNKNOWN}\",\"target_kb_id\":\"${KB2}\",\"mode\":\"reparse\"}"
req ks-move-missing-target.json POST "/knowledge/move" -H 'Content-Type: application/json' \
  -d "{\"knowledge_ids\":[\"${KG1}\"],\"source_kb_id\":\"${KB1}\",\"target_kb_id\":\"${UNKNOWN}\",\"mode\":\"reparse\"}"
req ks-move-unknown-item.json POST "/knowledge/move" -H 'Content-Type: application/json' \
  -d "{\"knowledge_ids\":[\"${UNKNOWN}\"],\"source_kb_id\":\"${KB1}\",\"target_kb_id\":\"${KB2}\",\"mode\":\"reparse\"}"
req ks-move-wrong-kb.json POST "/knowledge/move" -H 'Content-Type: application/json' \
  -d "{\"knowledge_ids\":[\"${KG6}\"],\"source_kb_id\":\"${KB1}\",\"target_kb_id\":\"${KB2}\",\"mode\":\"reparse\"}"
req ks-move-pending.json POST "/knowledge/move" -H 'Content-Type: application/json' \
  -d "{\"knowledge_ids\":[\"${KG5}\"],\"source_kb_id\":\"${KB1}\",\"target_kb_id\":\"${KB2}\",\"mode\":\"reparse\"}"
req ks-move-bad-mode.json POST "/knowledge/move" -H 'Content-Type: application/json' \
  -d "{\"knowledge_ids\":[\"${KG1}\"],\"source_kb_id\":\"${KB1}\",\"target_kb_id\":\"${KB2}\",\"mode\":\"bogus\"}"
req ks-move-no-body.json POST "/knowledge/move" -H 'Content-Type: application/json'
req ks-move-empty-body.json POST "/knowledge/move" -H 'Content-Type: application/json' -d '{}'
req ks-move-missing-mode.json POST "/knowledge/move" -H 'Content-Type: application/json' \
  -d "{\"knowledge_ids\":[\"${KG1}\"],\"source_kb_id\":\"${KB1}\",\"target_kb_id\":\"${KB2}\"}"
req ks-move-empty-ids.json POST "/knowledge/move" -H 'Content-Type: application/json' \
  -d "{\"knowledge_ids\":[],\"source_kb_id\":\"${KB1}\",\"target_kb_id\":\"${KB2}\",\"mode\":\"reparse\"}"
req ks-move-blank-id.json POST "/knowledge/move" -H 'Content-Type: application/json' \
  -d "{\"knowledge_ids\":[\" \"],\"source_kb_id\":\"${KB1}\",\"target_kb_id\":\"${KB2}\",\"mode\":\"reparse\"}"
req ks-move-type-mismatch.json POST "/knowledge/move" -H 'Content-Type: application/json' \
  -d "{\"knowledge_ids\":[\"${KG1}\"],\"source_kb_id\":\"${KB1}\",\"target_kb_id\":\"${KB4}\",\"mode\":\"reparse\"}"
req ks-move-emb-mismatch.json POST "/knowledge/move" -H 'Content-Type: application/json' \
  -d "{\"knowledge_ids\":[\"${KG1}\"],\"source_kb_id\":\"${KB1}\",\"target_kb_id\":\"${KB5}\",\"mode\":\"reparse\"}"
req ks-move-store-mismatch.json POST "/knowledge/move" -H 'Content-Type: application/json' \
  -d "{\"knowledge_ids\":[\"${KG1}\"],\"source_kb_id\":\"${KB1}\",\"target_kb_id\":\"${KB6}\",\"mode\":\"reuse_vectors\"}"
req ks-move-cross-source.json POST "/knowledge/move" -H 'Content-Type: application/json' \
  -d "{\"knowledge_ids\":[\"${KG1}\"],\"source_kb_id\":\"${CROSS_KB}\",\"target_kb_id\":\"${KB2}\",\"mode\":\"reparse\"}"
req ks-move-cross-target.json POST "/knowledge/move" -H 'Content-Type: application/json' \
  -d "{\"knowledge_ids\":[\"${KG1}\"],\"source_kb_id\":\"${KB1}\",\"target_kb_id\":\"${CROSS_KB}\",\"mode\":\"reparse\"}"
# happy path 最后录（真实搬 KG2: KB1 → KB2, reuse_vectors, 两库都无绑定）
req ks-move-ok.json POST "/knowledge/move" -H 'Content-Type: application/json' \
  -d "{\"knowledge_ids\":[\"${KG2}\"],\"source_kb_id\":\"${KB1}\",\"target_kb_id\":\"${KB2}\",\"mode\":\"reuse_vectors\"}"
sleep 4
MV_TASK="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["task_id"])' "${OUT}/ks-move-ok.json")"
req ks-move-progress.json GET "/knowledge/move/progress/${MV_TASK}"
req ks-move-progress-unknown.json GET "/knowledge/move/progress/kg_move_10002_1704628851692_a1b2c3d4_probe"
req ks-move-progress-invalid.json GET "/knowledge/move/progress/bogus"
req ks-move-progress-cross.json GET "/knowledge/move/progress/kg_move_10000_1704628851692_a1b2c3d4_probe"

# ═══ 4) POST /knowledge-bases/copy ═══
echo "==> 4) copy"
req ks-copy-create.json POST "/knowledge-bases/copy" -H 'Content-Type: application/json' \
  -d "{\"source_id\":\"${KB3}\"}"
sleep 4
req ks-copy-to-existing.json POST "/knowledge-bases/copy" -H 'Content-Type: application/json' \
  -d "{\"source_id\":\"${KB7}\",\"target_id\":\"${KB2}\"}"
sleep 4
req ks-copy-missing-source.json POST "/knowledge-bases/copy" -H 'Content-Type: application/json' \
  -d "{\"source_id\":\"${UNKNOWN}\"}"
req ks-copy-cross-source.json POST "/knowledge-bases/copy" -H 'Content-Type: application/json' \
  -d "{\"source_id\":\"${CROSS_KB}\"}"
req ks-copy-no-body.json POST "/knowledge-bases/copy" -H 'Content-Type: application/json'
req ks-copy-missing-field.json POST "/knowledge-bases/copy" -H 'Content-Type: application/json' -d '{}'
req ks-copy-bad-task-id.json POST "/knowledge-bases/copy" -H 'Content-Type: application/json' \
  -d "{\"source_id\":\"${KB3}\",\"task_id\":\"bogus\"}"
req ks-copy-cross-task-id.json POST "/knowledge-bases/copy" -H 'Content-Type: application/json' \
  -d "{\"source_id\":\"${KB3}\",\"task_id\":\"kb_clone_10000_1704628851692_a1b2c3d4_probe\"}"
reqc ks-copy-contrib-replace.json POST "/knowledge-bases/copy" -H 'Content-Type: application/json' \
  -d "{\"source_id\":\"${KB3}\",\"target_id\":\"${KB2}\"}"
req ks-copy-type-mismatch.json POST "/knowledge-bases/copy" -H 'Content-Type: application/json' \
  -d "{\"source_id\":\"${KB7}\",\"target_id\":\"${KB4}\"}"
req ks-copy-emb-mismatch.json POST "/knowledge-bases/copy" -H 'Content-Type: application/json' \
  -d "{\"source_id\":\"${KB7}\",\"target_id\":\"${KB5}\"}"
CP_TASK="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["task_id"])' "${OUT}/ks-copy-to-existing.json")"
req ks-copy-progress.json GET "/knowledge-bases/copy/progress/${CP_TASK}"
req ks-copy-progress-unknown.json GET "/knowledge-bases/copy/progress/kb_clone_10002_1704628851692_a1b2c3d4_probe"
req ks-copy-progress-invalid.json GET "/knowledge-bases/copy/progress/bogus"
req ks-copy-progress-cross.json GET "/knowledge-bases/copy/progress/kb_clone_10000_1704628851692_a1b2c3d4_probe"

# ═══ 5) POST /knowledge-bases/{id}/duplicate ═══
echo "==> 5) duplicate"
req ks-duplicate.json POST "/knowledge-bases/${KB1}/duplicate"
req ks-duplicate-again.json POST "/knowledge-bases/${KB1}/duplicate"
req ks-duplicate-404.json POST "/knowledge-bases/${UNKNOWN}/duplicate"

echo "done. KB1=${KB1} KB2=${KB2} KB3=${KB3} KB7=${KB7}"
