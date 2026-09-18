#!/usr/bin/env bash
# chunk 模块（波 2 第一批）真 PG A/B：Go :8080 vs Java :8082，同一 dev PG。
#
# 两侧各自建 KB + 知识 + 分块（互不共享状态行），按 record-chunk-golden.sh 的
# 同一顺序打完全部场景，掩码后逐字节对比。seq_id 来自 PG 序列、两侧必然不同，
# 掩码之（契约测试已钉住精确值）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

WORK="$(mktemp -d /tmp/ab-chunk.XXXXXX)"
trap 'rm -rf "${WORK}"' EXIT
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"

CROSS_KG="$(${PSQL} -c "SELECT id FROM knowledges WHERE tenant_id=10000 AND deleted_at IS NULL LIMIT 1;" | tr -d ' ')"

mask() {
  python3 - "$1" "$2" <<'PY'
import re, sys
s, kb = sys.argv[1], sys.argv[2]
s = s.replace(kb, "<kb>")
s = re.sub(r'"([a-z_]+)":\s*"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"',
           r'"\1":"<uuid>"', s)
s = re.sub(r'"[2-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})"',
           '"<ts>"', s)
s = re.sub(r'"seq_id":\s*\d+', '"seq_id":<seq>', s)
sys.stdout.write(s)
PY
}

run_side() {
  local port="$1" tag="$2" kgp="$3" ckp="$4" qgp="$5"
  local api="http://localhost:${port}/api/v1"
  local out="${WORK}/${tag}"
  mkdir -p "${out}"
  local token ctoken vtoken
  token="$(curl -s -X POST "${api}/auth/login" -H 'Content-Type: application/json' \
    -d '{"email":"java-phase1@weknora.test","password":"Passw0rd!"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')"
  ctoken="$(curl -s -X POST "${api}/auth/login" -H 'Content-Type: application/json' \
    -d '{"email":"java-phase1-contrib@weknora.test","password":"Passw0rd!"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')"
  vtoken="$(curl -s -X POST "${api}/auth/login" -H 'Content-Type: application/json' \
    -d '{"email":"java-phase1-viewer@weknora.test","password":"Passw0rd!"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["token"])')"
  local auth="Authorization: Bearer ${token}"

  req() {
    local name="$1" method="$2" path="$3"; shift 3
    curl -s -o "${out}/${name}.json" -w '%{http_code}\n' -X "${method}" "${api}${path}" \
      -H "${auth}" "$@" > "${out}/${name}.code"
  }
  reqc() {
    local name="$1" method="$2" path="$3"; shift 3
    curl -s -o "${out}/${name}.json" -w '%{http_code}\n' -X "${method}" "${api}${path}" \
      -H "Authorization: Bearer ${ctoken}" "$@" > "${out}/${name}.code"
  }
  reqv() {
    local name="$1" method="$2" path="$3"; shift 3
    curl -s -o "${out}/${name}.json" -w '%{http_code}\n' -X "${method}" "${api}${path}" \
      -H "Authorization: Bearer ${vtoken}" "$@" > "${out}/${name}.code"
  }

  local kb kg1="${kgp}1" kg2="${kgp}2" kg3="${kgp}3" cross="${CROSS_KG}"
  local c1="${ckp}1" c2="${ckp}2" c3="${ckp}3" c4="${ckp}4" c5="${ckp}5" c6="${ckp}6"
  local q1="${qgp}1"

  kb="$(curl -s -X POST "${api}/knowledge-bases" -H "${auth}" -H 'Content-Type: application/json' \
    -d "{\"name\":\"ab-chunk-${tag}\",\"description\":\"chunk A/B\"}" \
    | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["id"])')"
  ${PSQL} -c "UPDATE knowledge_bases SET indexing_strategy='{\"vector_enabled\":false,\"keyword_enabled\":false,\"wiki_enabled\":false,\"graph_enabled\":false}', summary_model_id='', embedding_model_id='' WHERE id='${kb}';" >/dev/null

  ${PSQL} -c "
DELETE FROM chunk_revisions WHERE chunk_id LIKE '${ckp}%';
DELETE FROM chunks WHERE id LIKE '${ckp}%';
DELETE FROM knowledges WHERE id LIKE '${kgp}%';
INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, source, parse_status, summary_status) VALUES
('${kg1}', 10002, '${kb}', 'document', '文档一', 'manual', 'completed', 'none'),
('${kg2}', 10002, '${kb}', 'document', '文档二', 'manual', 'completed', 'none'),
('${kg3}', 10002, '${kb}', 'document', '空文档', 'manual', 'completed', 'none');
INSERT INTO chunks (id, tenant_id, knowledge_id, knowledge_base_id, content, chunk_index, is_enabled, flags, status, start_at, end_at, chunk_type, source_content, content_revision, last_editor_id, metadata) VALUES
('${c1}', 10002, '${kg1}', '${kb}', '第一段内容', 0, true, 1, 0, 0, 6, 'text', '', 0, '', NULL),
('${c2}', 10002, '${kg1}', '${kb}', '第二段内容', 1, true, 1, 0, 6, 12, 'text', '', 0, '', NULL),
('${c3}', 10002, '${kg1}', '${kb}', '图一', 2, true, 1, 0, 12, 14, 'image_ocr', '', 0, '', NULL),
('${c4}', 10002, '${kg1}', '${kb}', '问答内容', 3, true, 1, 0, 14, 18, 'text', '', 0, '',
 '{\"generated_questions\":[{\"id\":\"${q1}\",\"question\":\"已有问题?\",\"content_revision\":0}],\"generated_questions_revision\":0}'),
('${c5}', 10002, '${kg1}', '${kb}', '带图 ![img](resource://a.png) 与 HTML <img src=\"resource://b.png\"> 的段落', 4, true, 1, 0, 18, 60, 'text', '', 0, '', NULL),
('${c6}', 10002, '${kg2}', '${kb}', '第二篇唯一段', 0, true, 1, 0, 0, 8, 'text', '', 0, '', NULL);
UPDATE chunks SET parent_chunk_id='${c1}', image_info='[{\"url\":\"resource://img-1\",\"original_url\":\"\",\"start_pos\":0,\"end_pos\":0,\"caption\":\"图一\",\"ocr_text\":\"OCR文字\"}]' WHERE id='${c3}';" >/dev/null

  # 1) 列表与读取
  req list GET "/chunks/${kg1}"
  req type-filter GET "/chunks/${kg1}?chunk_type=image_ocr"
  req paged GET "/chunks/${kg1}?page=1&page_size=2"
  req page0 GET "/chunks/${kg1}?page=0&page_size=2"
  req badpage GET "/chunks/${kg1}?page=abc"
  req badsize GET "/chunks/${kg1}?page_size=-5"
  req empty GET "/chunks/${kg3}"
  req list404 GET "/chunks/11111111-2222-3333-4444-999999999999"
  req cross GET "/chunks/${cross}"
  req byid GET "/chunks/by-id/${c1}"
  req byid404 GET "/chunks/by-id/11111111-2222-3333-4444-999999999999"

  # 2) 更新
  req update PUT "/chunks/${kg1}/${c1}" -H 'Content-Type: application/json' \
    -d '{"content":"第一段内容（已编辑）","expected_revision":0}'
  req conflict PUT "/chunks/${kg1}/${c1}" -H 'Content-Type: application/json' \
    -d '{"content":"再改一次","expected_revision":0}'
  req upd-empty PUT "/chunks/${kg1}/${c2}" -H 'Content-Type: application/json' -d '{"content":"   "}'
  req upd-image PUT "/chunks/${kg1}/${c3}" -H 'Content-Type: application/json' -d '{"content":"不能改图"}'
  req upd-addimg PUT "/chunks/${kg1}/${c5}" -H 'Content-Type: application/json' \
    -d '{"content":"带图 ![img](resource://a.png) 加新图 ![n](resource://new.png)"}'
  req upd-404 PUT "/chunks/${kg1}/11111111-2222-3333-4444-999999999999" -H 'Content-Type: application/json' -d '{"content":"x"}'
  req upd-mismatch PUT "/chunks/${kg2}/${c1}" -H 'Content-Type: application/json' -d '{"content":"x"}'
  req upd-nobody PUT "/chunks/${kg1}/${c2}" -H 'Content-Type: application/json'
  req upd-badjson PUT "/chunks/${kg1}/${c2}" -H 'Content-Type: application/json' -d 'not-json'
  req disable PUT "/chunks/${kg1}/${c2}" -H 'Content-Type: application/json' -d '{"is_enabled":false}'

  # 3) 修订与回滚
  req revisions GET "/chunks/${kg1}/${c1}/revisions"
  req rev-missing POST "/chunks/${kg1}/${c1}/revert" -H 'Content-Type: application/json' -d '{}'
  req rev-neg POST "/chunks/${kg1}/${c1}/revert" -H 'Content-Type: application/json' -d '{"revision":-1}'
  req rev-unknown POST "/chunks/${kg1}/${c1}/revert" -H 'Content-Type: application/json' -d '{"revision":99}'
  req revert POST "/chunks/${kg1}/${c1}/revert" -H 'Content-Type: application/json' -d '{"revision":0}'

  # 4) 生成问题
  req q-create PUT "/chunks/by-id/${c4}/questions" -H 'Content-Type: application/json' \
    -d '{"question":"新问题?"}'
  local created
  created="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["data"]["id"])' "${out}/q-create.json")"
  req q-update PUT "/chunks/by-id/${c4}/questions" -H 'Content-Type: application/json' \
    -d "{\"question_id\":\"${q1}\",\"question\":\"已有问题（改）?\"}"
  req q-missing PUT "/chunks/by-id/${c4}/questions" -H 'Content-Type: application/json' \
    -d '{"question_id":"nope","question":"x"}'
  req q-empty PUT "/chunks/by-id/${c4}/questions" -H 'Content-Type: application/json' \
    -d "{\"question_id\":\"${q1}\",\"question\":\"   \"}"
  req q-404 PUT "/chunks/by-id/11111111-2222-3333-4444-999999999999/questions" -H 'Content-Type: application/json' -d '{"question":"x"}'
  req q-delete DELETE "/chunks/by-id/${c4}/questions" -H 'Content-Type: application/json' -d "{\"question_id\":\"${q1}\"}"
  req q-delete2 DELETE "/chunks/by-id/${c4}/questions" -H 'Content-Type: application/json' -d "{\"question_id\":\"${created}\"}"
  req q-delete3 DELETE "/chunks/by-id/${c4}/questions" -H 'Content-Type: application/json' -d "{\"question_id\":\"${q1}\"}"
  req q-nobody DELETE "/chunks/by-id/${c4}/questions" -H 'Content-Type: application/json'
  req regen-nomodel POST "/chunks/by-id/${c1}/questions/regenerate"
  req regen-image POST "/chunks/by-id/${c3}/questions/regenerate"
  req regen-404 POST "/chunks/by-id/11111111-2222-3333-4444-999999999999/questions/regenerate"

  # 5) 权限矩阵
  reqc own-put PUT "/chunks/${kg1}/${c2}" -H 'Content-Type: application/json' -d '{"content":"越权改"}'
  reqc own-delete DELETE "/chunks/${kg1}/${c2}"
  reqv own-viewer DELETE "/chunks/${kg1}/${c2}"
  reqv viewer-read GET "/chunks/${kg1}"

  # 6) 删除
  req del DELETE "/chunks/${kg2}/${c6}"
  req del404 DELETE "/chunks/${kg2}/${c6}"
  req delall DELETE "/chunks/${kg2}"
  req delall2 DELETE "/chunks/${kg2}"

  echo "${kb}"
}

echo "==> A/B chunk：Go :${GO_PORT} vs Java :${JAVA_PORT}"
KB_G="$(run_side "${GO_PORT}" go "aaa00002-0000-0000-0000-00000000000" "bbb00002-0000-0000-0000-00000000000" "ccc00002-0000-0000-0000-00000000000")"
KB_J="$(run_side "${JAVA_PORT}" java "aaa00003-0000-0000-0000-00000000000" "bbb00003-0000-0000-0000-00000000000" "ccc00003-0000-0000-0000-00000000000")"

echo "    KB_G=${KB_G}"
echo "    KB_J=${KB_J}"

fail=0
for f in $(cd "${WORK}/go" && ls *.code | sed 's/\.code$//'); do
  gs="$(cat "${WORK}/go/${f}.code")"
  js="$(cat "${WORK}/java/${f}.code")"
  gm="$(mask "$(cat "${WORK}/go/${f}.json")" "${KB_G}")"
  jm="$(mask "$(cat "${WORK}/java/${f}.json")" "${KB_J}")"
  if [ "${gs}" != "${js}" ]; then
    echo "DIFF  ${f}: status ${gs} vs ${js}"; fail=1
  elif [ "${gm}" != "${jm}" ]; then
    echo "DIFF  ${f}: body"
    diff <(printf '%s' "${gm}") <(printf '%s' "${jm}") | head -4 | sed 's/^/       /'
    fail=1
  else
    echo "MATCH ${f} (${gs})"
  fi
done

if [ "${fail}" = "0" ]; then echo "ALL MATCH"; else echo "HAS DIFF"; exit 1; fi
