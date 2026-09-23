#!/usr/bin/env bash
# 切片 2d 双端 stub A/B：web_search / web_fetch 两件的**全请求体**逐字节对拍
# （tools 段 + messages/system prompt，掩 UUID/时间戳；2026-09-24 升级）。
# 前置（对照 ab-tools-wiki.sh）：
#   1) STUB_DUMP_DIR=/tmp/ab-web-dump python3 scripts/stub-llm-server.py 8181 &
#   2) SSRF_WHITELIST_EXTRA=127.0.0.1 scripts/go-server-up.sh
#   3) SSRF_WHITELIST_EXTRA=127.0.0.1 scripts/java-server-up.sh
# 本脚本自带夹具：租户 10009（md-batch）建 web 临时 agent，跑完删除。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
DUMP=/tmp/ab-web-dump
# 夹具模型 provider=openai：命中 prompt-cache sendKey 策略 → Go 出站体改写为 map
# （字母序 + prompt_cache_key），与 Java goSorted 同形；无 provider 的行走 SDK
# 结构体路径，顶层键序本就不同（Go 分路径备案，见 known-issues 07）。
MD_CHAT="b0000000-0000-0000-0000-0000000000d5"
psql_seed() {
  export PGPASSWORD='postgres123!@#'
  psql -h localhost -p 15432 -U postgres -d WeKnora -q <<SQL
DELETE FROM models WHERE id = '${MD_CHAT}';
INSERT INTO models (id, tenant_id, type, name, source, description, parameters, is_default, status, created_at, updated_at) VALUES
 ('${MD_CHAT}', 10009, 'KnowledgeQA', 'deepseek-chat', 'remote', 'web ab stub chat',
  '{"base_url":"http://127.0.0.1:8181/v1","provider":"openai"}', FALSE, 'active', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00');
SQL
}
psql_seed
EMAIL="${WEB_AB_EMAIL:-md-batch@weknora.test}"
QUERY='你好 <<SCENARIO:chat>>'

# agent id 由服务端生成（双端同库，建一次即可）；清理按名字全删。
fixture_up() { # port  建临时 agent，stdout = "token AGENT_ID"
  local port="$1" base token
  base="http://localhost:${port}/api/v1"
  token="$(login "${EMAIL}" "${port}")"
  local agent_id
  agent_id="$(curl -s -X POST "${base}/agents" -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' -d "{
      \"name\":\"ab-web\",\"description\":\"temp web ab agent\",
      \"config\":{\"agent_mode\":\"smart-reasoning\",\"model_id\":\"${MD_CHAT}\",
        \"thinking\":true,
        \"allowed_tools\":[\"thinking\",\"todo_write\",\"web_search\",\"web_fetch\"],
        \"web_search_enabled\":true,\"web_search_max_results\":7,
        \"web_search_provider_id\":\"wsp-ab\",\"kb_selection_mode\":\"none\"}}" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["data"]["id"])')"
  echo "${token} ${agent_id}"
}

fixture_down() { # port  按名字删除本租户全部 ab-web
  local port="$1" base token
  base="http://localhost:${port}/api/v1"
  token="$(login "${EMAIL}" "${port}")"
  for id in $(curl -s "${base}/agents" -H "Authorization: Bearer ${token}" \
      | python3 -c 'import json,sys; [print(a["id"]) for a in json.load(sys.stdin)["data"] if a["name"]=="ab-web"]'); do
    curl -s -X DELETE "${base}/agents/${id}" -H "Authorization: Bearer ${token}" >/dev/null || true
  done
}

side() { # name port token
  local name="$1" port="$2" token="$3" base sid before
  base="http://localhost:${port}/api/v1"
  sid="$(curl -s -X POST "${base}/sessions" -H "Authorization: Bearer ${token}" \
        -H 'Content-Type: application/json' -d '{"title":""}' \
        | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["id"])')"
  echo "  ${name} session=${sid}"
  before="$(ls "${DUMP}" 2>/dev/null | wc -l | tr -d ' ')"
  curl -s --max-time 40 -o "/tmp/ab-web-${name}.sse" -X POST "${base}/agent-chat/${sid}" \
      -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' \
      -d "{\"query\":\"${QUERY}\",\"agent_enabled\":true,\"agent_id\":\"${AGENT_ID}\",\"web_search_enabled\":true}" >/dev/null || true
  sleep 1
  ls -1 "${DUMP}" | tail -n +$((before + 1)) | grep "v1_chat_completions" > "/tmp/ab-web-${name}.list" || true
  local n; n="$(wc -l < "/tmp/ab-web-${name}.list" | tr -d ' ')"
  echo "  ${name} chat/completions 请求体 = ${n} 个"
  cp "/tmp/ab-web-${name}.list" "/tmp/ab-web-${name}.rawlist"
}

# 全 body 对拍（对照 2026-09-24 批：messages/system prompt/tools 逐字节，掩 UUID/时间戳）
compare_full_body() {
  for side in java go; do
    python3 - "/tmp/ab-web-dump" "/tmp/ab-web-${side}.rawlist" "/tmp/ab-web-${side}" <<'PY'
import json, os, re, sys
dump, lst, out = sys.argv[1], sys.argv[2], sys.argv[3]
picked = {"tools": None, "title": None}
for line in open(lst, encoding="utf-8"):
    name = line.strip()
    if not name:
        continue
    body = json.load(open(os.path.join(dump, name), encoding="utf-8"))
    key = "tools" if body.get("tools") else "title"
    if picked[key] is None:
        picked[key] = body
def norm(body):
    s = json.dumps(body, ensure_ascii=False, indent=1, sort_keys=True)
    s = re.sub(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<UUID>", s)
    s = re.sub(r"\d{4}-\d\d-\d\dT[\d:.]+(Z|[+-]\d{2}:\d{2})", "<TS>", s)
    return s
for key, body in picked.items():
    if body is None:
        sys.exit("%s 轮缺失" % key)
    open("%s.%s.body" % (out, key), "w", encoding="utf-8").write(norm(body))
PY
  done
  local ok=1
  for key in tools title; do
    if cmp -s "/tmp/ab-web-java.${key}.body" "/tmp/ab-web-go.${key}.body"; then
      echo "MATCH ${key} 轮全 body（$(wc -c < /tmp/ab-web-java.${key}.body | tr -d ' ') 字节）"
    else
      echo "DIFF ${key} 轮"
      diff <(cat "/tmp/ab-web-go.${key}.body") <(cat "/tmp/ab-web-java.${key}.body") | head -20 || true
      ok=0
    fi
  done
  [ "${ok}" = "1" ] || exit 1
  python3 -c 'import json;print("  tools:", ", ".join(t["function"]["name"] for t in json.load(open("/tmp/ab-web-java.tools.body"))["tools"]))'
}

trap 'fixture_down "${JAVA_PORT}" >/dev/null 2>&1 || true; fixture_down "${GO_PORT}" >/dev/null 2>&1 || true' EXIT

echo "==> 夹具：临时 agent（web_search_enabled，max_results=7）"
read -r JTOKEN AGENT_ID <<< "$(fixture_up "${JAVA_PORT}")"

echo "==> Java :${JAVA_PORT}"
side java "${JAVA_PORT}" "${JTOKEN}"
echo "==> Go   :${GO_PORT}"
side go "${GO_PORT}" "${JTOKEN}"

echo "==> 对拍全请求体（tools 轮 + 标题轮）"
compare_full_body
