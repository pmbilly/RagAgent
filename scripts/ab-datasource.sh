#!/usr/bin/env bash
# datasource 的 Go/Java A/B：同一台机器上两个实现打同一批请求，掩码后逐字节 diff。
#
# 前提（缺一不可）：
#   1) Go   在 :8080 —— 带 SSRF_WHITELIST=127.0.0.1,::1,localhost 启动
#   2) Java 在 :8082 —— 同样带 SSRF_WHITELIST（脚本里给）
#   3) 两边共用 dev PG（localhost:15432），且 127.0.0.1:18099 上有 stub feed
#
# 设计要点：
#   * 两边各建一套自己的 KB + 数据源（不互相干扰），比对前把 UUID 与时间戳掩码；
#   * 中文按原始字节比（curl -o 落盘再 diff，不经 echo）；
#   * `/datasource/types` 同优先级顺序在 Go 侧本来就是随机的 → 单独按 type 归一化。
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=dev-env.sh
source "${SCRIPT_DIR}/dev-env.sh"

GO_PORT="${GO_PORT:-8080}"
JAVA_PORT="${JAVA_PORT:-8082}"
FEED="http://127.0.0.1:18099/feed.xml"
WORK="$(mktemp -d)"
GO_A="${WORK}/go"; JAVA_A="${WORK}/java"
mkdir -p "${GO_A}" "${JAVA_A}"

# ⚠️ 两侧各登各的：JWT 是**各自的密钥**签的（Go 用 .env 的 JWT_SECRET，
# Java 用 dev-env.sh 的 JWT_SECRET），一条 token 打到对面只会得到 401。
GO_TOKEN="$(login "${TEST_EMAIL}" "${GO_PORT}")"
JAVA_TOKEN="$(login "${TEST_EMAIL}" "${JAVA_PORT}")"
GO_VIEWER_TOKEN="$(login "${TEST_VIEWER_EMAIL}" "${GO_PORT}")"
JAVA_VIEWER_TOKEN="$(login "${TEST_VIEWER_EMAIL}" "${JAVA_PORT}")"

PASS=0; DIFF=0; DIFFS=()

# hit <name> <port> <token> <method> <path> [curl args...]
hit() {
  local name="$1" port="$2" token="$3" method="$4" path="$5"; shift 5
  curl -s -o "${WORK}/${name}.${port}" -w '%{http_code}' \
    -X "${method}" "http://localhost:${port}/api/v1${path}" \
    -H "Authorization: Bearer ${token}" "$@" > "${WORK}/${name}.${port}.status"
}

# cmp <name>  —— 比状态码 + 掩码后的 body
cmp_resp() {
  local name="$1"
  local gs js gb jb
  gs="$(cat "${WORK}/${name}.${GO_PORT}.status")"; js="$(cat "${WORK}/${name}.${JAVA_PORT}.status")"
  gb="$(mask "$(cat "${WORK}/${name}.${GO_PORT}")" "${GO_KB}")"
  jb="$(mask "$(cat "${WORK}/${name}.${JAVA_PORT}")" "${JAVA_KB}")"
  if [ "${gs}" = "${js}" ] && [ "${gb}" = "${jb}" ]; then
    PASS=$((PASS+1)); printf '  MATCH  %-42s %s\n' "${name}" "${gs}"
  else
    DIFF=$((DIFF+1)); DIFFS+=("${name}")
    printf '  DIFF   %-42s go=%s java=%s\n' "${name}" "${gs}" "${js}"
    if [ "${gs}" != "${js}" ]; then printf '         status 不一致\n'; fi
    if [ "${gb}" != "${jb}" ]; then
      diff <(printf '%s' "${gb}") <(printf '%s' "${jb}") | head -6 | sed 's/^/         /'
    fi
  fi
}

# 掩码：UUID、真实时间戳、以及两边各自的 KB id / 数据源 id / 日志 id
mask() {
  python3 - "$1" "$2" <<'PY'
import re, sys
s, kb = sys.argv[1], sys.argv[2]
s = s.replace(kb, "<kb>")
s = re.sub(r'"([a-z_]+)":\s*"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"',
           r'"\1":"<uuid>"', s)
s = re.sub(r'"[2-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})"',
           '"<ts>"', s)
# 同步日志的进度计数与状态：后台任务跑不跑、什么时候跑完，两侧都不可控
s = re.sub(r'"(items_total|items_created|items_updated|items_deleted|items_skipped|items_failed)":\s*\d+',
           r'"\1":<n>', s)
s = re.sub(r'"(result)":\s*(\{.*?\}|null)', r'"\1":<r>', s)
s = re.sub(r'"(status)":\s*"(running|success|partial|failed|canceled)"', r'"\1":"<s>"', s)
s = re.sub(r'"finished_at":\s*"[^"]*"', '"finished_at":<f>', s)
s = re.sub(r'"total_items_synced":\s*\d+', '"total_items_synced":<n>', s)
return_ = s
sys.stdout.write(return_)
PY
}

echo "==> 准备：两侧各建一个 KB"
GO_KB="$(curl -s -X POST "http://localhost:${GO_PORT}/api/v1/knowledge-bases" \
  -H "Authorization: Bearer ${GO_TOKEN}" -H 'Content-Type: application/json' \
  -d '{"name":"ab-ds-go","description":"datasource A/B"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["id"])')"
JAVA_KB="$(curl -s -X POST "http://localhost:${JAVA_PORT}/api/v1/knowledge-bases" \
  -H "Authorization: Bearer ${JAVA_TOKEN}" -H 'Content-Type: application/json' \
  -d '{"name":"ab-ds-java","description":"datasource A/B"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["id"])')"
echo "    go_kb=${GO_KB}  java_kb=${JAVA_KB}"

UNKNOWN="11111111-2222-3333-4444-999999999999"

echo "==> 1) 无状态端点"
hit types "${GO_PORT}" "${GO_TOKEN}" GET /datasource/types
hit types "${JAVA_PORT}" "${JAVA_TOKEN}" GET /datasource/types
# 同优先级顺序两侧都不可复现 → 按 type 归一化后比
python3 - "${WORK}/types.${GO_PORT}" "${WORK}/types.${JAVA_PORT}" <<'PY'
import json, sys
a = {x["type"]: x for x in json.load(open(sys.argv[1]))}
b = {x["type"]: x for x in json.load(open(sys.argv[2]))}
if a == b:
    print("  MATCH  types（按 type 归一化，顺序两侧都随机）")
else:
    print("  DIFF   types")
    for k in sorted(set(a) | set(b)):
        if a.get(k) != b.get(k):
            print("         ", k, a.get(k), b.get(k))
PY

hit list_no_kb "${GO_PORT}" "${GO_TOKEN}" GET /datasource
hit list_no_kb "${JAVA_PORT}" "${JAVA_TOKEN}" GET /datasource
cmp_resp list_no_kb

hit create_no_body "${GO_PORT}" "${GO_TOKEN}" POST /datasource -H 'Content-Type: application/json'
hit create_no_body "${JAVA_PORT}" "${JAVA_TOKEN}" POST /datasource -H 'Content-Type: application/json'
cmp_resp create_no_body

hit create_bad_conn "${GO_PORT}" "${GO_TOKEN}" POST /datasource -H 'Content-Type: application/json' \
  -d '{"name":"x","type":"confluence","knowledge_base_id":"'"${GO_KB}"'"}'
hit create_bad_conn "${JAVA_PORT}" "${JAVA_TOKEN}" POST /datasource -H 'Content-Type: application/json' \
  -d '{"name":"x","type":"confluence","knowledge_base_id":"'"${JAVA_KB}"'"}'
cmp_resp create_bad_conn

hit create_unknown_kb "${GO_PORT}" "${GO_TOKEN}" POST /datasource -H 'Content-Type: application/json' \
  -d '{"name":"x","type":"rss","knowledge_base_id":"'"${UNKNOWN}"'"}'
hit create_unknown_kb "${JAVA_PORT}" "${JAVA_TOKEN}" POST /datasource -H 'Content-Type: application/json' \
  -d '{"name":"x","type":"rss","knowledge_base_id":"'"${UNKNOWN}"'"}'
cmp_resp create_unknown_kb

hit create_missing_kb "${GO_PORT}" "${GO_TOKEN}" POST /datasource -H 'Content-Type: application/json' -d '{"name":"x","type":"rss"}'
hit create_missing_kb "${JAVA_PORT}" "${JAVA_TOKEN}" POST /datasource -H 'Content-Type: application/json' -d '{"name":"x","type":"rss"}'
cmp_resp create_missing_kb

hit create_null_cfg "${GO_PORT}" "${GO_TOKEN}" POST /datasource -H 'Content-Type: application/json' \
  -d '{"name":"x","type":"rss","knowledge_base_id":"'"${GO_KB}"'"}'
hit create_null_cfg "${JAVA_PORT}" "${JAVA_TOKEN}" POST /datasource -H 'Content-Type: application/json' \
  -d '{"name":"x","type":"rss","knowledge_base_id":"'"${JAVA_KB}"'"}'
cmp_resp create_null_cfg

echo "==> 2) 创建成功 → 读 → 改 → 列表"
create_body() { # create_body <kb>
  printf '{"name":"ab-rss","type":"rss","knowledge_base_id":"%s","sync_schedule":"0 0 * * * *","config":{"type":"rss","settings":{"feed_urls":"%s"},"credentials":{"feed_urls":"%s"}}}' "$1" "${FEED}" "${FEED}"
}
hit create "${GO_PORT}" "${GO_TOKEN}" POST /datasource -H 'Content-Type: application/json' \
  -d "$(create_body "${GO_KB}")"
hit create "${JAVA_PORT}" "${JAVA_TOKEN}" POST /datasource -H 'Content-Type: application/json' \
  -d "$(create_body "${JAVA_KB}")"
cmp_resp create

GO_DS="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "${WORK}/create.${GO_PORT}")"
JAVA_DS="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "${WORK}/create.${JAVA_PORT}")"
echo "    go_ds=${GO_DS}  java_ds=${JAVA_DS}"

hit get "${GO_PORT}" "${GO_TOKEN}" GET "/datasource/${GO_DS}"
hit get "${JAVA_PORT}" "${JAVA_TOKEN}" GET "/datasource/${JAVA_DS}"
cmp_resp get

hit get_unknown "${GO_PORT}" "${GO_TOKEN}" GET "/datasource/${UNKNOWN}"
hit get_unknown "${JAVA_PORT}" "${JAVA_TOKEN}" GET "/datasource/${UNKNOWN}"
cmp_resp get_unknown

PUT_BODY='{"name":"ab-rss-renamed","sync_mode":"full","sync_deletions":false,"error_message":"","config":{"type":"rss","settings":{"feed_urls":"'"${FEED}"'"},"credentials":{"feed_urls":"'"${FEED}"'","api_token":"should-be-ignored"}}}'
hit update "${GO_PORT}" "${GO_TOKEN}" PUT "/datasource/${GO_DS}" -H 'Content-Type: application/json' -d "${PUT_BODY}"
hit update "${JAVA_PORT}" "${JAVA_TOKEN}" PUT "/datasource/${JAVA_DS}" -H 'Content-Type: application/json' -d "${PUT_BODY}"
cmp_resp update

hit get_after_update "${GO_PORT}" "${GO_TOKEN}" GET "/datasource/${GO_DS}"
hit get_after_update "${JAVA_PORT}" "${JAVA_TOKEN}" GET "/datasource/${JAVA_DS}"
cmp_resp get_after_update

hit list "${GO_PORT}" "${GO_TOKEN}" GET "/datasource?kb_id=${GO_KB}"
hit list "${JAVA_PORT}" "${JAVA_TOKEN}" GET "/datasource?kb_id=${JAVA_KB}"
# 列表里含刚建的那一条；UUID 掩掉后两侧可比
python3 - "${WORK}/list.${GO_PORT}" "${GO_A}/list.json" "${GO_KB}" <<'PY'
import json, re, sys
s = open(sys.argv[1]).read().replace(sys.argv[3], "<kb>")
s = re.sub(r'"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"', '"<uuid>"', s)
s = re.sub(r'"[2-9]\d{3}-[^"]*"', '"<ts>"', s)
open(sys.argv[2], "w").write(s)
PY
python3 - "${WORK}/list.${JAVA_PORT}" "${JAVA_A}/list.json" "${JAVA_KB}" <<'PY'
import json, re, sys
s = open(sys.argv[1]).read().replace(sys.argv[3], "<kb>")
s = re.sub(r'"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"', '"<uuid>"', s)
s = re.sub(r'"[2-9]\d{3}-[^"]*"', '"<ts>"', s)
open(sys.argv[2], "w").write(s)
PY
if diff -q "${GO_A}/list.json" "${JAVA_A}/list.json" >/dev/null; then
  PASS=$((PASS+1)); echo "  MATCH  list                                   200"
else
  DIFF=$((DIFF+1)); DIFFS+=(list); echo "  DIFF   list"
  diff "${GO_A}/list.json" "${JAVA_A}/list.json" | head -6 | sed 's/^/         /'
fi

# ── 跨语言互读：Go 读 Java 写的行、Java 读 Go 写的行 ──────────────────────
# 这是本项目最有价值的一类验证（MCP/Wiki 两轮都做过）：两边共用同一个 PG，
# 如果 JSON/列语义有分叉，对方写的那一行会读不出来或读出来不一样。
cross_read() { # cross_read <kb> <label：这一行是谁写的>
  local kb="$1" label="$2"
  hit "xread_g_${label}" "${GO_PORT}" "${GO_TOKEN}" GET "/datasource?kb_id=${kb}"
  hit "xread_j_${label}" "${JAVA_PORT}" "${JAVA_TOKEN}" GET "/datasource?kb_id=${kb}"
  local a b
  a="$(mask "$(cat "${WORK}/xread_g_${label}.${GO_PORT}")" "${kb}")"
  b="$(mask "$(cat "${WORK}/xread_j_${label}.${JAVA_PORT}")" "${kb}")"
  if [ "${a}" = "${b}" ]; then
    PASS=$((PASS+1)); echo "  MATCH  ${label} 写的行：Go 与 Java 读出来完全一致"
  else
    DIFF=$((DIFF+1)); DIFFS+=("xread_${label}"); echo "  DIFF   ${label} 写的行跨语言读不一致"
    diff <(printf '%s' "${a}") <(printf '%s' "${b}") | head -6 | sed 's/^/         /'
  fi
}
echo "==> 2b) 跨语言互读（同一个 PG，谁写谁读都要一致）"
cross_read "${JAVA_KB}" java
cross_read "${GO_KB}" go

echo "==> 3) 连接校验 / 资源枚举"
hit validate "${GO_PORT}" "${GO_TOKEN}" POST "/datasource/${GO_DS}/validate"
hit validate "${JAVA_PORT}" "${JAVA_TOKEN}" POST "/datasource/${JAVA_DS}/validate"
cmp_resp validate

hit validate_creds_bad "${GO_PORT}" "${GO_TOKEN}" POST /datasource/validate-credentials -H 'Content-Type: application/json' -d '{}'
hit validate_creds_bad "${JAVA_PORT}" "${JAVA_TOKEN}" POST /datasource/validate-credentials -H 'Content-Type: application/json' -d '{}'
cmp_resp validate_creds_bad

hit validate_creds "${GO_PORT}" "${GO_TOKEN}" POST /datasource/validate-credentials -H 'Content-Type: application/json' \
  -d '{"type":"rss","credentials":{"feed_urls":"'"${FEED}"'"}}'
hit validate_creds "${JAVA_PORT}" "${JAVA_TOKEN}" POST /datasource/validate-credentials -H 'Content-Type: application/json' \
  -d '{"type":"rss","credentials":{"feed_urls":"'"${FEED}"'"}}'
cmp_resp validate_creds

hit resources "${GO_PORT}" "${GO_TOKEN}" GET "/datasource/${GO_DS}/resources"
hit resources "${JAVA_PORT}" "${JAVA_TOKEN}" GET "/datasource/${JAVA_DS}/resources"
cmp_resp resources

hit ancestors_empty "${GO_PORT}" "${GO_TOKEN}" POST "/datasource/${GO_DS}/resource-ancestors" -H 'Content-Type: application/json' -d '{"resource_ids":[]}'
hit ancestors_empty "${JAVA_PORT}" "${JAVA_TOKEN}" POST "/datasource/${JAVA_DS}/resource-ancestors" -H 'Content-Type: application/json' -d '{"resource_ids":[]}'
cmp_resp ancestors_empty

hit ancestors "${GO_PORT}" "${GO_TOKEN}" POST "/datasource/${GO_DS}/resource-ancestors" -H 'Content-Type: application/json' -d '{"resource_ids":["'"${FEED}"'"]}'
hit ancestors "${JAVA_PORT}" "${JAVA_TOKEN}" POST "/datasource/${JAVA_DS}/resource-ancestors" -H 'Content-Type: application/json' -d '{"resource_ids":["'"${FEED}"'"]}'
cmp_resp ancestors

echo "==> 4) 凭据子资源"
for spec in "creds_missing:{}" "creds_empty:{\"credentials\":{}}"; do
  name="${spec%%:*}"; body="${spec#*:}"
  hit "${name}" "${GO_PORT}" "${GO_TOKEN}" PUT "/datasource/${GO_DS}/credentials" -H 'Content-Type: application/json' -d "${body}"
  hit "${name}" "${JAVA_PORT}" "${JAVA_TOKEN}" PUT "/datasource/${JAVA_DS}/credentials" -H 'Content-Type: application/json' -d "${body}"
  cmp_resp "${name}"
done

hit creds_put "${GO_PORT}" "${GO_TOKEN}" PUT "/datasource/${GO_DS}/credentials" -H 'Content-Type: application/json' \
  -d '{"credentials":{"feed_urls":"'"${FEED}"'"}}'
hit creds_put "${JAVA_PORT}" "${JAVA_TOKEN}" PUT "/datasource/${JAVA_DS}/credentials" -H 'Content-Type: application/json' \
  -d '{"credentials":{"feed_urls":"'"${FEED}"'"}}'
cmp_resp creds_put

hit creds_put_auth "${GO_PORT}" "${GO_TOKEN}" PUT "/datasource/${GO_DS}/credentials" -H 'Content-Type: application/json' \
  -d '{"credentials":{"feed_urls":"'"${FEED}"'","auth_headers":"X-Token: abc"}}'
hit creds_put_auth "${JAVA_PORT}" "${JAVA_TOKEN}" PUT "/datasource/${JAVA_DS}/credentials" -H 'Content-Type: application/json' \
  -d '{"credentials":{"feed_urls":"'"${FEED}"'","auth_headers":"X-Token: abc"}}'
cmp_resp creds_put_auth

hit creds_del_bad "${GO_PORT}" "${GO_TOKEN}" DELETE "/datasource/${GO_DS}/credentials/nope"
hit creds_del_bad "${JAVA_PORT}" "${JAVA_TOKEN}" DELETE "/datasource/${JAVA_DS}/credentials/nope"
cmp_resp creds_del_bad

hit get_after_creds "${GO_PORT}" "${GO_TOKEN}" GET "/datasource/${GO_DS}"
hit get_after_creds "${JAVA_PORT}" "${JAVA_TOKEN}" GET "/datasource/${JAVA_DS}"
cmp_resp get_after_creds

hit creds_del "${GO_PORT}" "${GO_TOKEN}" DELETE "/datasource/${GO_DS}/credentials/credentials"
hit creds_del "${JAVA_PORT}" "${JAVA_TOKEN}" DELETE "/datasource/${JAVA_DS}/credentials/credentials"
cmp_resp creds_del

echo "==> 5) 同步控制与日志"
hit pause "${GO_PORT}" "${GO_TOKEN}" POST "/datasource/${GO_DS}/pause"
hit pause "${JAVA_PORT}" "${JAVA_TOKEN}" POST "/datasource/${JAVA_DS}/pause"
cmp_resp pause

hit resume "${GO_PORT}" "${GO_TOKEN}" POST "/datasource/${GO_DS}/resume"
hit resume "${JAVA_PORT}" "${JAVA_TOKEN}" POST "/datasource/${JAVA_DS}/resume"
cmp_resp resume

hit logs_empty_bad_limit "${GO_PORT}" "${GO_TOKEN}" GET "/datasource/${GO_DS}/logs?limit=0"
hit logs_empty_bad_limit "${JAVA_PORT}" "${JAVA_TOKEN}" GET "/datasource/${JAVA_DS}/logs?limit=0"
cmp_resp logs_empty_bad_limit

hit logs_bad_limit_abc "${GO_PORT}" "${GO_TOKEN}" GET "/datasource/${GO_DS}/logs?limit=abc"
hit logs_bad_limit_abc "${JAVA_PORT}" "${JAVA_TOKEN}" GET "/datasource/${JAVA_DS}/logs?limit=abc"
cmp_resp logs_bad_limit_abc

hit logs_empty "${GO_PORT}" "${GO_TOKEN}" GET "/datasource/${GO_DS}/logs"
hit logs_empty "${JAVA_PORT}" "${JAVA_TOKEN}" GET "/datasource/${JAVA_DS}/logs"
cmp_resp logs_empty

hit sync "${GO_PORT}" "${GO_TOKEN}" POST "/datasource/${GO_DS}/sync"
hit sync "${JAVA_PORT}" "${JAVA_TOKEN}" POST "/datasource/${JAVA_DS}/sync"
cmp_resp sync

GO_LOG="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "${WORK}/sync.${GO_PORT}")"
JAVA_LOG="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "${WORK}/sync.${JAVA_PORT}")"

# 等两侧的后台同步都跑到终态再比日志。
# ⚠️ 不能"发完就比"：Go 的 asynq 任务要经 Redis 派发给 worker（实测 ~1-2s 跑完），
# Java 是进程内虚拟线程队列，两侧完成时刻不同 → 直接比就是在比竞态。
# 这里等 finished_at 非空（最多 30s），顺便把**真实跑完后的计数**也比掉。
settle() { # settle <port> <token> <logid>
  for _ in $(seq 1 30); do
    local body
    body="$(curl -s "http://localhost:$1/api/v1/datasource/logs/$3" -H "Authorization: Bearer $2")"
    if ! printf '%s' "${body}" | /usr/bin/grep -q '"finished_at":null'; then
      printf '%s' "${body}"
      return 0
    fi
    sleep 1
  done
  echo "TIMEOUT: 日志 $3 在 30s 内没有跑完" >&2
  printf '%s' "${body}"
}
echo "    等待两侧同步跑完…"
settle "${GO_PORT}" "${GO_TOKEN}" "${GO_LOG}" > "${WORK}/settled.${GO_PORT}"
settle "${JAVA_PORT}" "${JAVA_TOKEN}" "${JAVA_LOG}" > "${WORK}/settled.${JAVA_PORT}"
# 计数与状态**不掩码**直接比：这才是"两侧真的对 stub feed 跑到同一个终态"的证据
python3 - "${WORK}/settled.${GO_PORT}" "${WORK}/settled.${JAVA_PORT}" <<'PY'
import json, sys
g = json.load(open(sys.argv[1])); j = json.load(open(sys.argv[2]))
keys = ["status", "items_total", "items_created", "items_updated", "items_deleted",
        "items_skipped", "items_failed", "error_message"]
diff = {k: (g.get(k), j.get(k)) for k in keys if g.get(k) != j.get(k)}
if diff:
    print("  DIFF   sync_result（终态计数）")
    for k, (a, b) in diff.items():
        print(f"         {k}: go={a!r} java={b!r}")
else:
    print(f"  MATCH  sync_result（终态计数逐项相同：status={g['status']} "
          f"total={g['items_total']} created={g['items_created']} "
          f"failed={g['items_failed']}）")
PY

hit logs_offset_neg "${GO_PORT}" "${GO_TOKEN}" GET "/datasource/${GO_DS}/logs?offset=-5"
hit logs_offset_neg "${JAVA_PORT}" "${JAVA_TOKEN}" GET "/datasource/${JAVA_DS}/logs?offset=-5"
cmp_resp logs_offset_neg

hit log "${GO_PORT}" "${GO_TOKEN}" GET "/datasource/logs/${GO_LOG}"
hit log "${JAVA_PORT}" "${JAVA_TOKEN}" GET "/datasource/logs/${JAVA_LOG}"
cmp_resp log

hit log_unknown "${GO_PORT}" "${GO_TOKEN}" GET "/datasource/logs/${UNKNOWN}"
hit log_unknown "${JAVA_PORT}" "${JAVA_TOKEN}" GET "/datasource/logs/${UNKNOWN}"
cmp_resp log_unknown

echo "==> 6) 权限"
hit viewer_create "${GO_PORT}" "${GO_VIEWER_TOKEN}" POST /datasource -H 'Content-Type: application/json' \
  -d '{"name":"x","type":"rss","knowledge_base_id":"'"${GO_KB}"'"}'
hit viewer_create "${JAVA_PORT}" "${JAVA_VIEWER_TOKEN}" POST /datasource -H 'Content-Type: application/json' \
  -d '{"name":"x","type":"rss","knowledge_base_id":"'"${JAVA_KB}"'"}'
cmp_resp viewer_create

echo "==> 7) 删除"
hit delete "${GO_PORT}" "${GO_TOKEN}" DELETE "/datasource/${GO_DS}"
hit delete "${JAVA_PORT}" "${JAVA_TOKEN}" DELETE "/datasource/${JAVA_DS}"
cmp_resp delete

hit get_deleted "${GO_PORT}" "${GO_TOKEN}" GET "/datasource/${GO_DS}"
hit get_deleted "${JAVA_PORT}" "${JAVA_TOKEN}" GET "/datasource/${JAVA_DS}"
cmp_resp get_deleted

echo
echo "==> 结果：MATCH=${PASS}  DIFF=${DIFF}"
if [ "${DIFF}" -gt 0 ]; then
  printf '    有差异的用例：%s\n' "${DIFFS[*]}"
fi
echo "    原始响应留在 ${WORK}"

# 清理两侧的 A/B 知识库
curl -s -o /dev/null -X DELETE "http://localhost:${GO_PORT}/api/v1/knowledge-bases/${GO_KB}" -H "Authorization: Bearer ${GO_TOKEN}" || true
curl -s -o /dev/null -X DELETE "http://localhost:${JAVA_PORT}/api/v1/knowledge-bases/${JAVA_KB}" -H "Authorization: Bearer ${JAVA_TOKEN}" || true
exit 0
