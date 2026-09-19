#!/usr/bin/env bash
# 录波 2 终扫批（favorites + chunker/preview）的 golden：fav-* / cprev-*。
#
# 覆盖端点（4 条）：
#   GET/POST /user/favorites  DELETE /user/favorites/{type}/{id}
#   POST /chunker/preview
#
# 场景设计：
#   - favorites 挂在租户 10002 的 phase1 owner 下；表无外键，resource_id 用
#     固定假 id（fav-kb-fixed-0001 / fav-agent-fixed-0001），不依赖真实 KB/agent。
#     每次运行前幂等清理该 owner 的全部收藏行。
#   - 录制顺序敏感（契约测试必须复刻）：空列表 → add ×2 → 重复 add →
#     列表 → remove → 幽灵 remove → 列表 → 400 家族 → 鉴权家族。
#   - 空列表 golden 同时钉住 GORM Find 空结果序列化成 [] 还是 null。
#   - chunker/preview 纯无状态：400/413 错误形态 + auto/explicit/parent-child/
#     token-limit/中文 各一发。响应完全确定（无时间戳），可零掩码逐字节。
#   - 深层结构类型错误的 binding 文案 Go/Jackson 措辞差异大（GoJsonBindError
#     已知差异），刻意不录。
#
# 用法：
#   scripts/record-fav-cprev-golden.sh                  # 录 Go（:8080）进 contracts/
#   FAV_TARGET_PORT=8082 FAV_OUT_DIR=/tmp/x scripts/record-fav-cprev-golden.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${FAV_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${FAV_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"
mkdir -p "${OUT}"

req() { local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" "$@"; }

echo "==> 幂等清理：phase1 owner 的收藏行"
${PSQL} >/dev/null 2>&1 <<SQL
DELETE FROM user_resource_favorites
  WHERE user_id IN (SELECT id FROM users WHERE email = 'java-phase1@weknora.test');
SQL

TOKEN="$(login "${TEST_EMAIL}" "${PORT}")"
[ -n "${TOKEN}" ] || { echo "FATAL: owner 登录失败"; exit 1; }
AUTH="Authorization: Bearer ${TOKEN}"
CT='Content-Type: application/json'

echo "==> 1) 空列表（钉 GORM Find 空结果形态）+ 缺 type 参数"
req fav-list-empty-kb.json    GET  "/user/favorites?type=kb" -H "${AUTH}"
req fav-list-empty-agent.json GET  "/user/favorites?type=agent" -H "${AUTH}"
req fav-list-notype.json      GET  "/user/favorites" -H "${AUTH}"

echo "==> 2) add 成功 ×2 + 幂等重复 add"
req fav-add-kb.json           POST "/user/favorites" -H "${AUTH}" -H "${CT}" -d '{"type":"kb","id":"fav-kb-fixed-0001"}'
req fav-add-agent.json        POST "/user/favorites" -H "${AUTH}" -H "${CT}" -d '{"type":"agent","id":"fav-agent-fixed-0001"}'
req fav-add-dup.json          POST "/user/favorites" -H "${AUTH}" -H "${CT}" -d '{"type":"kb","id":"fav-kb-fixed-0001"}'

echo "==> 3) 列表回读（created_at 掩码）"
req fav-list-kb-after.json    GET  "/user/favorites?type=kb" -H "${AUTH}"
req fav-list-agent-after.json GET  "/user/favorites?type=agent" -H "${AUTH}"

echo "==> 4) remove 真实行 + 幽灵行（都 200）"
req fav-remove-agent.json     DELETE "/user/favorites/agent/fav-agent-fixed-0001" -H "${AUTH}"
req fav-remove-ghost.json     DELETE "/user/favorites/agent/ghost-id-000" -H "${AUTH}"
req fav-list-agent-after-rm.json GET "/user/favorites?type=agent" -H "${AUTH}"

echo "==> 5) 400 家族（类型白名单 / 空 id / binding）"
req fav-add-invalid-type.json POST "/user/favorites" -H "${AUTH}" -H "${CT}" -d '{"type":"wiki","id":"x"}'
req fav-add-missing-id.json   POST "/user/favorites" -H "${AUTH}" -H "${CT}" -d '{"type":"kb"}'
req fav-add-blank-id.json     POST "/user/favorites" -H "${AUTH}" -H "${CT}" -d '{"type":"kb","id":"   "}'
req fav-add-bad-body.json     POST "/user/favorites" -H "${AUTH}" -H "${CT}" -d 'not-json'
req fav-add-empty-body.json   POST "/user/favorites" -H "${AUTH}" -H "${CT}"
req fav-list-invalid-type.json GET "/user/favorites?type=wiki" -H "${AUTH}"
req fav-remove-invalid-type.json DELETE "/user/favorites/wiki/x" -H "${AUTH}"

echo "==> 6) 鉴权家族"
req fav-noauth.json           GET "/user/favorites?type=kb"
req fav-badtoken.json         GET "/user/favorites?type=kb" -H 'Authorization: Bearer garbage.token.here'

echo "==> 7) chunker/preview：鉴权 + binding + 空文本 + 超限"
req cprev-noauth.json         POST "/chunker/preview" -H "${CT}" -d '{"text":"hello"}'
req cprev-bad-body.json       POST "/chunker/preview" -H "${AUTH}" -H "${CT}" -d 'not-json'
req cprev-empty-text.json     POST "/chunker/preview" -H "${AUTH}" -H "${CT}" -d '{"text":"   "}'
req cprev-oversize.json       POST "/chunker/preview" -H "${AUTH}" -H "${CT}" \
  -d "{\"text\":\"$(python3 -c 'print("x"*70000)')\"}"

echo "==> 8) chunker/preview：策略矩阵（响应全确定，零掩码）"
MARKDOWN=$'# Chapter One\n\nIntro paragraph with enough words to fill a chunk naturally.\n\n## Section A\n\nBody text for section A keeps going a little longer.\n\n## Section B\n\nBody text for section B also keeps going.\n\n# Chapter Two\n\nFinal paragraph closes the sample document.'
json_str() { python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'; }
MD_JSON="$(printf '%s' "${MARKDOWN}" | json_str)"

req cprev-basic-markdown.json POST "/chunker/preview" -H "${AUTH}" -H "${CT}" \
  -d "{\"text\":${MD_JSON},\"chunking_config\":{\"chunk_size\":200,\"chunk_overlap\":20}}"
req cprev-plain-text.json     POST "/chunker/preview" -H "${AUTH}" -H "${CT}" \
  -d '{"text":"Just a plain paragraph. No structure at all. Still needs a second sentence to be realistic.","chunking_config":{"chunk_size":200,"chunk_overlap":20}}'
req cprev-explicit-legacy.json POST "/chunker/preview" -H "${AUTH}" -H "${CT}" \
  -d "{\"text\":${MD_JSON},\"chunking_config\":{\"chunk_size\":200,\"chunk_overlap\":20,\"strategy\":\"legacy\"}}"
req cprev-explicit-heading.json POST "/chunker/preview" -H "${AUTH}" -H "${CT}" \
  -d "{\"text\":${MD_JSON},\"chunking_config\":{\"chunk_size\":200,\"chunk_overlap\":20,\"strategy\":\"heading\"}}"
req cprev-unknown-strategy.json POST "/chunker/preview" -H "${AUTH}" -H "${CT}" \
  -d "{\"text\":${MD_JSON},\"chunking_config\":{\"chunk_size\":200,\"chunk_overlap\":20,\"strategy\":\"nonsense\"}}"
req cprev-parent-child.json   POST "/chunker/preview" -H "${AUTH}" -H "${CT}" \
  -d "{\"text\":${MD_JSON},\"chunking_config\":{\"enable_parent_child\":true,\"parent_chunk_size\":300,\"child_chunk_size\":100}}"
req cprev-token-limit.json    POST "/chunker/preview" -H "${AUTH}" -H "${CT}" \
  -d "{\"text\":${MD_JSON},\"chunking_config\":{\"chunk_size\":1600,\"chunk_overlap\":160,\"token_limit\":80}}"
req cprev-chinese.json        POST "/chunker/preview" -H "${AUTH}" -H "${CT}" \
  -d '{"text":"这是第一段话，用来测试中文分块的效果。这一句再长一点，好让分块器有实际的边界可以选。这是第二段话，继续填充内容让段落更长一些。第三段保持同样的风格，确保总体超过一行。","chunking_config":{"chunk_size":60,"chunk_overlap":10}}'

echo "==> 完成：golden 落在 ${OUT}"
