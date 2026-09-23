#!/usr/bin/env bash
# 切片 2c 双端 stub A/B：wiki 工具族 10 件的 tools 段逐字节对拍。
# 前置：stub（STUB_DUMP_DIR=/tmp/ab-wiki-dump）+ 双端 SSRF_WHITELIST_EXTRA=127.0.0.1
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"
DUMP=/tmp/ab-wiki-dump
AGENT_ID="${AGENT_ID:-cd03be45-0000-0000-0000-00000000wiki}"
QUERY='你好 <<SCENARIO:chat>>'

side() { # name port
  local name="$1" port="$2" base token sid before
  base="http://localhost:${port}/api/v1"
  token="$(login "${TEST_EMAIL}" "${port}")"
  sid="$(curl -s -X POST "${base}/sessions" -H "Authorization: Bearer ${token}" \
        -H 'Content-Type: application/json' -d '{"title":""}' \
        | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["id"])')"
  echo "  ${name} session=${sid}"
  before="$(ls "${DUMP}" 2>/dev/null | wc -l | tr -d ' ')"
  curl -s --max-time 40 -o "/tmp/ab-wiki-${name}.sse" -X POST "${base}/agent-chat/${sid}" \
      -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' \
      -d "{\"query\":\"${QUERY}\",\"agent_enabled\":true,\"agent_id\":\"${AGENT_ID}\"}" >/dev/null || true
  sleep 1
  ls -1 "${DUMP}" | tail -n +$((before + 1)) | grep "v1_chat_completions" > "/tmp/ab-wiki-${name}.list" || true
  local n; n="$(wc -l < "/tmp/ab-wiki-${name}.list" | tr -d ' ')"
  echo "  ${name} chat/completions 请求体 = ${n} 个"
  # 取首个**带 tools** 的请求体（标题生成/改写轮次不带 tools，跳过）
  python3 - "${DUMP}" "/tmp/ab-wiki-${name}.list" "/tmp/ab-wiki-${name}.tools" <<'PY'
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

echo "==> Java :${JAVA_PORT}"
side java "${JAVA_PORT}"
echo "==> Go   :${GO_PORT}"
side go "${GO_PORT}"

echo "==> 对拍 tools 段"
if cmp -s /tmp/ab-wiki-java.tools /tmp/ab-wiki-go.tools; then
  echo "MATCH tools 段逐字节一致（$(wc -c < /tmp/ab-wiki-java.tools | tr -d ' ') 字节）"
  python3 -c 'import json;print("  tools:", ", ".join(t["function"]["name"] for t in json.load(open("/tmp/ab-wiki-java.tools"))))'
else
  echo "DIFF"
  python3 - <<'PY'
import json
g=json.load(open("/tmp/ab-wiki-go.tools",encoding="utf-8"))
j=json.load(open("/tmp/ab-wiki-java.tools",encoding="utf-8"))
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
