#!/usr/bin/env bash
# 切片 2d 双端 stub A/B：web_search / web_fetch 两件的 tools 段逐字节对拍。
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
  # 取首个**带 tools** 的请求体（标题生成/改写轮次不带 tools，跳过）
  python3 - "${DUMP}" "/tmp/ab-web-${name}.list" "/tmp/ab-web-${name}.tools" <<'PY'
import json,os,sys
dump, lst, out = sys.argv[1], sys.argv[2], sys.argv[3]
picked = None
for line in open(lst, encoding="utf-8"):
    name = line.strip()
    if not name:
        continue
    body = json.load(open(os.path.join(dump, name), encoding="utf-8"))
    tools = body.get("tools")
    if tools:
        print("  %s: tools 数 = %d" % (name, len(tools)), file=sys.stderr)
        if picked is None:
            picked = tools
    else:
        print("  %s: 无 tools（%s）" % (name, body.get("model")), file=sys.stderr)
if picked is None:
    sys.exit("无带 tools 的请求体")
open(out, "w", encoding="utf-8").write(json.dumps(picked, ensure_ascii=False, separators=(",", ":")))
PY
}

trap 'fixture_down "${JAVA_PORT}" >/dev/null 2>&1 || true; fixture_down "${GO_PORT}" >/dev/null 2>&1 || true' EXIT

echo "==> 夹具：临时 agent（web_search_enabled，max_results=7）"
read -r JTOKEN AGENT_ID <<< "$(fixture_up "${JAVA_PORT}")"

echo "==> Java :${JAVA_PORT}"
side java "${JAVA_PORT}" "${JTOKEN}"
echo "==> Go   :${GO_PORT}"
side go "${GO_PORT}" "${JTOKEN}"

echo "==> 对拍 tools 段"
if cmp -s /tmp/ab-web-java.tools /tmp/ab-web-go.tools; then
  echo "MATCH tools 段逐字节一致（$(wc -c < /tmp/ab-web-java.tools | tr -d ' ') 字节）"
  python3 -c 'import json;print("  tools:", ", ".join(t["function"]["name"] for t in json.load(open("/tmp/ab-web-java.tools"))))'
else
  echo "DIFF"
  python3 - <<'PY'
import json
g=json.load(open("/tmp/ab-web-go.tools",encoding="utf-8"))
j=json.load(open("/tmp/ab-web-java.tools",encoding="utf-8"))
gn=[t["function"]["name"] for t in g]; jn=[t["function"]["name"] for t in j]
print("  Go   :", ", ".join(gn))
print("  Java :", ", ".join(jn))
print("  仅 Go :", [x for x in gn if x not in jn])
print("  仅 Java:", [x for x in jn if x not in gn])
gm={t["function"]["name"]:t for t in g}; jm={t["function"]["name"]:t for t in j}
for n in sorted(set(gn)&set(jn)):
    a=json.dumps(gm[n],ensure_ascii=False,sort_keys=True); b=json.dumps(jm[n],ensure_ascii=False,sort_keys=True)
    if a!=b:
        print("  内容差异:", n)
        for i in range(min(len(a),len(b))):
            if a[i]!=b[i]:
                print("    首差@%d Go=%r Java=%r" % (i,a[max(0,i-60):i+60],b[max(0,i-60):i+60]))
                break
PY
  exit 1
fi
