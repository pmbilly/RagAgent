#!/usr/bin/env bash
# 录收尾批 W5d 的 golden：w5d-term-*（沙箱终端 ticket + WS 错误族）/ w5d-lb-*（会话侧
# local-browser ×2）/ w5d-emb-*（embed QA 委托 ×2 + embed 文件代理）。
#
# 覆盖端点：
#   POST /sessions/:sid/sandbox/terminal-ticket     （200 / 404）
#   GET  /sessions/:sid/sandbox/terminal            （无票/坏票 401、跨会话票 403、
#                                                    有票无 Upgrade 头 400 text/plain）
#   GET|POST /sessions/:sid/local-browser           （404 / GET 200 / POST 503）
#   POST /embed/:cid/knowledge-chat/:sid            （404 / 403 sig / 400 invalid json /
#                                                    委托后确定性错误）
#   POST /embed/:cid/agent-chat/:sid                （委托后确定性错误）
#   GET  /embed/:cid/files                          （400 缺参/..、403 跨租户、404、200 种子文件）
#   WS 握手成功路径（101 + close 帧）不进 golden——由 ab-w5d.sh 双端实测比对。
#
# 场景设计：
#   - 独立租户 10008（w5d-batch），种子幂等清理后重建；双端共享 dev DB，录 Go / 重放
#     Java 用同一份种子。
#   - ticket JWT / publish token / ems_ token / 渠道 id / 会话 id / sig 每轮随机 →
#     A/B 掩码比对；链在同一轮内自洽。
#   - X-Embed-Session 签名 = HMAC-SHA256(publish_token, "<cid>|<sid>") base64url 无填充。
#
# 用法：
#   scripts/record-w5d-golden.sh                  # 录 Go（:8080）进 contracts/
#   W5D_TARGET_PORT=8082 W5D_OUT_DIR=/tmp/x scripts/record-w5d-golden.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${W5D_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${W5D_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"
mkdir -p "${OUT}"

req() { local out="$1" method="$2" path="$3"; shift 3
  [[ "${out}" == *.json ]] || out="${out}.json"
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" "$@"; }

# 带响应头一起录（WS 400 族的 Sec-Websocket-Version / nosniff / Content-Type 是契约）
reqh() { local out="$1" method="$2" path="$3"; shift 3
  curl -s -D "${OUT}/${out}.headers" -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" "$@"; }

sig() { python3 - "$1" "$2" "$3" <<'PY'
import hmac, hashlib, base64, sys
key, cid, sid = sys.argv[1], sys.argv[2], sys.argv[3]
mac = hmac.new(key.encode(), f"{cid}|{sid}".encode(), hashlib.sha256).digest()
print(base64.urlsafe_b64encode(mac).decode().rstrip("="))
PY
}

j() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(d["data"]["'"$2"'"])' "$1"; }

# ── 固定种子 id（两侧同 id，不需掩码）──
TENANT=10008
WU="b0000000-0000-0000-0000-000000000801"    # w5d-batch owner
SES_A="b5000000-0000-0000-0000-000000000801" # 普通会话（终端/local-browser 主目标）
SES_B="b5000000-0000-0000-0000-000000000802" # 第二会话（跨会话票 403）
AG8="b1000000-0000-0000-0000-000000000801"   # embed 渠道的 custom agent
GHOST="b5999999-0000-0000-0000-000000000801" # 不存在的会话

AG8_CONFIG='{"kb_selection_mode":"all","web_search_enabled":false,"image_upload_enabled":false}'

echo "==> 幂等清理 + 种子（租户 ${TENANT}）"
${PSQL} >/dev/null 2>&1 <<SQL
DELETE FROM messages WHERE tenant_id = ${TENANT};
DELETE FROM sessions WHERE tenant_id = ${TENANT};
DELETE FROM embed_channels WHERE tenant_id = ${TENANT};
DELETE FROM custom_agents WHERE tenant_id = ${TENANT};
DELETE FROM tenant_members WHERE tenant_id = ${TENANT};
DELETE FROM users WHERE tenant_id = ${TENANT};
DELETE FROM tenants WHERE id = ${TENANT};
SQL

${PSQL} >/dev/null <<SQL
INSERT INTO tenants (id, name, description, business, status) VALUES
  (${TENANT}, 'w5d-batch-tenant', '', '', 'active');
INSERT INTO users (id, username, email, password_hash, tenant_id, is_active) VALUES
  ('${WU}', 'w5dbatch', 'w5d-batch@weknora.test', '\$2a\$10\$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK', ${TENANT}, true);
INSERT INTO tenant_members (user_id, tenant_id, role, status) VALUES
  ('${WU}', ${TENANT}, 'owner', 'active');
INSERT INTO custom_agents (id, name, description, avatar, is_builtin, tenant_id, created_by, config) VALUES
  ('${AG8}', 'w5d-agent', 'w5d embed agent', '', false, ${TENANT}, '${WU}', '${AG8_CONFIG}');
INSERT INTO sessions (id, tenant_id, title, description, user_id, created_at, updated_at) VALUES
  ('${SES_A}', ${TENANT}, 'w5d-term', '', '${WU}', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
  ('${SES_B}', ${TENANT}, 'w5d-term-b', '', '${WU}', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00');
SQL

echo "==> 登录"
OWNER=$(curl -s -X POST "${API}/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"w5d-batch@weknora.test\",\"password\":\"${TEST_PASSWORD}\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
AH="Authorization: Bearer ${OWNER}"
CT='Content-Type: application/json'

# ══════════════ 一、沙箱终端 ticket + WS 错误族 ══════════════
echo "==> w5d-term-*"

req w5d-term-ticket-ok POST "/sessions/${SES_A}/sandbox/terminal-ticket" -H "${AH}"
TICKET_A=$(j "${OUT}/w5d-term-ticket-ok.json" ticket)

req w5d-term-ticket-404 POST "/sessions/${GHOST}/sandbox/terminal-ticket" -H "${AH}"
req w5d-term-ticket-noauth POST "/sessions/${SES_A}/sandbox/terminal-ticket"

req w5d-term-ws-no-ticket GET "/sessions/${SES_A}/sandbox/terminal"
req w5d-term-ws-bad-ticket GET "/sessions/${SES_A}/sandbox/terminal?ticket=bogus"
req w5d-term-ws-cross-session GET "/sessions/${SES_B}/sandbox/terminal?ticket=${TICKET_A}"
req w5d-term-ws-empty-session GET "/sessions/%20/sandbox/terminal?ticket=${TICKET_A}"

# 有票但无 Upgrade 头 → gorilla returnError 形态（400 text/plain + 版本头 + nosniff）
reqh w5d-term-ws-no-upgrade GET "/sessions/${SES_A}/sandbox/terminal?ticket=${TICKET_A}"

# ══════════════ 二、会话侧 local-browser ×2 ══════════════
echo "==> w5d-lb-*"

req w5d-lb-get-404 GET "/sessions/${GHOST}/local-browser" -H "${AH}"
req w5d-lb-get-ok GET "/sessions/${SES_A}/local-browser" -H "${AH}"
req w5d-lb-post-404 POST "/sessions/${GHOST}/local-browser" -H "${AH}" -H "${CT}" -d '{}'
req w5d-lb-post-unavailable POST "/sessions/${SES_A}/local-browser" -H "${AH}" -H "${CT}" \
  -d '{"action":"preview"}'

# ══════════════ 三、embed QA 委托 + 文件代理 ══════════════
echo "==> w5d-emb-*（先建渠道与 embed 会话）"

req w5d-emb-setup-channel POST "/agents/${AG8}/embed-channels" -H "${AH}" -H "${CT}" \
  -d '{"name":"w5d-emb","allowed_origins":["https://a.example.com"],"allow_web_search":true,"allow_file_upload":false}'
CID=$(j "${OUT}/w5d-emb-setup-channel.json" id)
PTOKEN=$(j "${OUT}/w5d-emb-setup-channel.json" publish_token)
EA="Authorization: Embed ${PTOKEN}"
ORIG='Origin: https://a.example.com'

req w5d-emb-setup-session POST "/embed/${CID}/sessions" -H "${EA}" -H "${ORIG}"
ESID=$(j "${OUT}/w5d-emb-setup-session.json" id)
ESIG=$(j "${OUT}/w5d-emb-setup-session.json" sig)
ESIG_HDR="X-Embed-Session: ${ESIG}"

# ensureEmbedSession 失败族
req w5d-emb-chat-404 POST "/embed/${CID}/knowledge-chat/${GHOST}" -H "${EA}" -H "${ORIG}" \
  -H "X-Embed-Session: x" -H "${CT}" -d '{}'
req w5d-emb-chat-bad-sig POST "/embed/${CID}/knowledge-chat/${ESID}" -H "${EA}" -H "${ORIG}" \
  -H "X-Embed-Session: bogus" -H "${CT}" -d '{}'

# patchEmbedChatPayload 失败族（签名合法、会话合法才到得了）
req w5d-emb-chat-invalid-json POST "/embed/${CID}/knowledge-chat/${ESID}" -H "${EA}" -H "${ORIG}" \
  -H "${ESIG_HDR}" -H "${CT}" -d 'not-json'
req w5d-emb-chat-scalar POST "/embed/${CID}/knowledge-chat/${ESID}" -H "${EA}" -H "${ORIG}" \
  -H "${ESIG_HDR}" -H "${CT}" -d '123'

# 委托后确定性错误（patch 成功 → KnowledgeQA/AgentQA 的入口校验）。
# ⚠️ 不带 query 的场景才确定：带 query 会进入完整 QA 管线（SSE/模型解析，dev 无模型
# 配置时挂流）——SSE 字节契约已由波 4.6d 的 stub LLM A/B 钉住，本批只验委托层。
req w5d-emb-chat-kb-empty POST "/embed/${CID}/knowledge-chat/${ESID}" -H "${EA}" -H "${ORIG}" \
  -H "${ESIG_HDR}" -H "${CT}" -d '{}'
req w5d-emb-chat-agent-empty POST "/embed/${CID}/agent-chat/${ESID}" -H "${EA}" -H "${ORIG}" \
  -H "${ESIG_HDR}" -H "${CT}" -d '{}'

# embed 文件代理（渠道租户 10008；种子文件写 LOCAL_STORAGE_BASE_DIR/<tenant>/exports/）
SEED_DIR="${LOCAL_STORAGE_BASE_DIR}/${TENANT}/exports"
mkdir -p "${SEED_DIR}"
printf 'w5d embed seed\n' > "${SEED_DIR}/w5d-embed-seed.txt"

req w5d-emb-files-no-path GET "/embed/${CID}/files" -H "${EA}" -H "${ORIG}"
req w5d-emb-files-dotdot GET "/embed/${CID}/files?file_path=local%3A%2F%2F${TENANT}%2Fexports%2F..%2Fx" -H "${EA}" -H "${ORIG}"
req w5d-emb-files-cross-tenant GET "/embed/${CID}/files?file_path=local%3A%2F%2F77%2Fexports%2Fx.png" -H "${EA}" -H "${ORIG}"
req w5d-emb-files-missing GET "/embed/${CID}/files?file_path=local%3A%2F%2F${TENANT}%2Fexports%2Fno-such.png" -H "${EA}" -H "${ORIG}"
curl -s -D "${OUT}/w5d-emb-files-ok.headers" -o "${OUT}/w5d-emb-files-ok.bin" \
  -w '%{http_code} %{url_effective}\n' \
  "${API}/embed/${CID}/files?file_path=local%3A%2F%2F${TENANT}%2Fexports%2Fw5d-embed-seed.txt" \
  -H "${EA}" -H "${ORIG}"

echo "==> done（${OUT}）"
