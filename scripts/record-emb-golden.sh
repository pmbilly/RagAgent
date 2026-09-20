#!/usr/bin/env bash
# 录波 4 子批 4.3 的 golden：emb-*（embed 管理面 + embed 公开面）+ imc-*（im channels 清单面）。
#
# 覆盖端点：
#   管理面（9）：POST/GET /agents/:id/embed-channels  GET /embed-channels
#                GET/PUT/DELETE /embed-channels/:cid  rotate-token  preview-session  stats
#   公开面（EmbedAuth 门）：exchange  config  suggested-questions  chunks/:chunk_id
#                sessions(POST)  messages/:sid/load  sessions/:sid/stop
#                suggestions GET/POST  suggestion-events  sessions/:sid/events
#                mcp-oauth(status/authorize-url/resolutions/resolutions-cancel)  tool-approvals
#   im 清单面：POST/GET /agents/:id/im-channels  GET /im-channels  PUT/DELETE /im-channels/:id
#                toggle  wechat/qrcode/status(400 分支)
#   QA 委托面（knowledge-chat / agent-chat）与 /embed/:cid/files、/im/callback、/wechat/qrcode
#   不在本批（QA 运行时/文件服务面/外部微信），脚本刻意不录。
#
# 场景设计：
#   - 独立租户 10006/10007（emb-batch / emb-foreign），种子全量清理后重建。
#   - publish token / ems_ session token / 渠道 id / 会话 id / sig 每轮随机 → 掩码。
#     脚本内用 jq 从上一响应提取（token/sig 链在**同一轮**内自洽，golden 与 replay 各自成链）。
#   - 移动会话的 description/user_id 在建渠道后 SQL 回填（marker 依赖随机渠道 id）。
#   - X-Embed-Session 签名 = HMAC-SHA256(publish_token, "<cid>|<sid>") base64url 无填充，
#     python3 现算——与 Go service.SignEmbedSessionHandle 逐字节同式。
#   - wechat/qrcode（外部 iLink API）不录：错误体含两侧 HTTP client 各异的消息（XDEP）。
#   - mcp-oauth-resolutions ×2 与 tool-approvals 的 **gate 依赖分支**不录：Go dev 容器
#     装配了 approval.Gate（未知 pending → 404），Java 的 Gate bean 随 agent 引擎批（4.6）
#     才接线（今日 Java 恒 500 "gate is not configured"）——部署状态差，A/B 无法对齐。
#     已录的 authorize-url / status 不依赖 gate，两侧确定。
#
# 用法：
#   scripts/record-emb-golden.sh                  # 录 Go（:8080）进 contracts/
#   EMB_TARGET_PORT=8082 EMB_OUT_DIR=/tmp/x scripts/record-emb-golden.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${EMB_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${EMB_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"
mkdir -p "${OUT}"

req() { local out="$1" method="$2" path="$3"; shift 3
  [[ "${out}" == *.json ]] || out="${out}.json"
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
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
TENANT=10006
FTENANT=10007
EMU="b0000000-0000-0000-0000-000000000601"   # emb-batch owner
EMV="b0000000-0000-0000-0000-000000000602"   # emb-batch viewer
FMO="b0000000-0000-0000-0000-000000000701"   # foreign tenant owner
AG="b1000000-0000-0000-0000-000000000601"    # 主 agent（selected KB + curated starters）
AGP="b1000000-0000-0000-0000-000000000602"   # 第二 agent（改绑/更新用）
AGF="b1000000-0000-0000-0000-000000000701"   # foreign agent
KB_A="b2000000-0000-0000-0000-000000000601"  # agent selected 的 KB
KB_B="b2000000-0000-0000-0000-000000000602"  # 未 selected 的 KB
KN_A="b3000000-0000-0000-0000-000000000601"
KN_B="b3000000-0000-0000-0000-000000000602"
KN_F="b3000000-0000-0000-0000-000000000701"
CH_A="b4000000-0000-0000-0000-000000000601"  # KB_A 内 chunk（可读）
CH_B="b4000000-0000-0000-0000-000000000602"  # KB_B 内 chunk（forbidden）
CH_F="b4000000-0000-0000-0000-000000000701"  # 跨租户 chunk（forbidden）
SES_MAIN="b5000000-0000-0000-0000-000000000601"  # 渠道 A 的 embed 会话
SES_OTHER="b5000000-0000-0000-0000-000000000602" # 渠道 B 的 embed 会话
SES_OFF="b5000000-0000-0000-0000-000000000603"   # 禁用渠道的 embed 会话
MSG_DONE="b6000000-0000-0000-0000-000000000601"  # 已完成助手消息（stop → already completed）
SSET="b7000000-0000-0000-0000-000000000601"      # 种子的推荐问题集

AG_CONFIG='{"kb_selection_mode":"selected","knowledge_bases":["'${KB_A}'"],"web_search_enabled":true,"image_upload_enabled":false,"question_suggestions":{"starters":{"enabled":true,"mode":"curated","items":["怎么 绑定 手机？","如何 重置 密码？"]}}}'
AGP_CONFIG='{"kb_selection_mode":"all","web_search_enabled":false,"image_upload_enabled":false}'

echo "==> 幂等清理 + 种子（租户 10006/10007）"
${PSQL} >/dev/null 2>&1 <<SQL
DELETE FROM messages WHERE tenant_id IN (${TENANT}, ${FTENANT});
DELETE FROM sessions WHERE tenant_id IN (${TENANT}, ${FTENANT});
DELETE FROM message_suggestion_events WHERE tenant_id IN (${TENANT}, ${FTENANT});
DELETE FROM message_suggestion_sets WHERE tenant_id IN (${TENANT}, ${FTENANT});
DELETE FROM chunks WHERE tenant_id IN (${TENANT}, ${FTENANT});
DELETE FROM knowledges WHERE tenant_id IN (${TENANT}, ${FTENANT});
DELETE FROM knowledge_bases WHERE tenant_id IN (${TENANT}, ${FTENANT});
DELETE FROM im_channels WHERE tenant_id IN (${TENANT}, ${FTENANT});
DELETE FROM embed_channels WHERE tenant_id IN (${TENANT}, ${FTENANT});
DELETE FROM custom_agents WHERE tenant_id IN (${TENANT}, ${FTENANT});
DELETE FROM models WHERE tenant_id IN (${TENANT}, ${FTENANT});
DELETE FROM tenant_members WHERE tenant_id IN (${TENANT}, ${FTENANT});
DELETE FROM users WHERE tenant_id IN (${TENANT}, ${FTENANT});
DELETE FROM tenants WHERE id IN (${TENANT}, ${FTENANT});
SQL

${PSQL} >/dev/null <<SQL
INSERT INTO tenants (id, name, description, business, status) VALUES
  (${TENANT}, 'emb-batch-tenant', '', '', 'active'),
  (${FTENANT}, 'emb-foreign-tenant', '', '', 'active');
INSERT INTO users (id, username, email, password_hash, tenant_id, is_active) VALUES
  ('${EMU}', 'embbatch', 'emb-batch@weknora.test', '\$2a\$10\$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK', ${TENANT}, true),
  ('${EMV}', 'embviewer', 'emb-batch-viewer@weknora.test', '\$2a\$10\$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK', ${TENANT}, true),
  ('${FMO}', 'embforeign', 'emb-foreign@weknora.test', '\$2a\$10\$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK', ${FTENANT}, true);
INSERT INTO tenant_members (user_id, tenant_id, role, status) VALUES
  ('${EMU}', ${TENANT}, 'owner', 'active'),
  ('${EMV}', ${TENANT}, 'viewer', 'active'),
  ('${FMO}', ${FTENANT}, 'owner', 'active');
INSERT INTO custom_agents (id, name, description, avatar, is_builtin, tenant_id, created_by, config) VALUES
  ('${AG}', 'emb-agent', 'embed batch agent', '', false, ${TENANT}, '${EMU}', '${AG_CONFIG}'),
  ('${AGP}', 'emb-agent-2', 'second agent', '', false, ${TENANT}, '${EMU}', '${AGP_CONFIG}'),
  ('${AGF}', 'foreign-agent', 'other tenant agent', '', false, ${FTENANT}, '${FMO}', '{}');
INSERT INTO knowledge_bases (id, name, tenant_id, type, description, creator_id, chunking_config, embedding_model_id, summary_model_id, created_at, updated_at) VALUES
  ('${KB_A}', 'emb-kb-a', ${TENANT}, 'document', '', '${EMU}', '{}', '', '', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
  ('${KB_B}', 'emb-kb-b', ${TENANT}, 'document', '', '${EMU}', '{}', '', '', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00');
INSERT INTO knowledges (id, tenant_id, knowledge_base_id, type, title, description, source, parse_status, enable_status) VALUES
  ('${KN_A}', ${TENANT}, '${KB_A}', 'document', 'kn-a', '', 'manual', 'completed', 'enabled'),
  ('${KN_B}', ${TENANT}, '${KB_B}', 'document', 'kn-b', '', 'manual', 'completed', 'enabled'),
  ('${KN_F}', ${FTENANT}, 'b2000000-0000-0000-0000-000000000701', 'document', 'kn-f', '', 'manual', 'completed', 'enabled');
INSERT INTO chunks (id, seq_id, tenant_id, knowledge_id, knowledge_base_id, content, chunk_type, is_enabled, flags, status, chunk_index, start_at, end_at, metadata, created_at, updated_at) VALUES
  ('${CH_A}', 990001, ${TENANT}, '${KN_A}', '${KB_A}', '允许读取的正文内容', 'text', true, 1, 0, 0, 0, 8, '{}', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
  ('${CH_B}', 990002, ${TENANT}, '${KN_B}', '${KB_B}', '未选中知识库的正文', 'text', true, 1, 0, 0, 0, 9, '{}', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
  ('${CH_F}', 990003, ${FTENANT}, '${KN_F}', 'b2000000-0000-0000-0000-000000000701', '隔壁租户的正文', 'text', true, 1, 0, 0, 0, 9, '{}', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00');
INSERT INTO sessions (id, tenant_id, title, description, user_id, created_at, updated_at) VALUES
  ('${SES_MAIN}', ${TENANT}, '', 'placeholder', '', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
  ('${SES_OTHER}', ${TENANT}, '', 'placeholder', '', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
  ('${SES_OFF}', ${TENANT}, '', 'placeholder', '', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00');
INSERT INTO messages (id, request_id, session_id, role, content, is_completed, agent_duration_ms, channel, rendered_content, agent_id, agent_tenant_id, model_id, execution_context, knowledge_references, mentioned_items, images, attachments, artifacts, created_at, updated_at) VALUES
  ('${MSG_DONE}', 'b6f00000-0000-0000-0000-000000000601', '${SES_MAIN}', 'assistant', '已完成回答', true, 0, '', '', '', 0, '', '{}', '[]', '[]', '[]', '[]', '[]', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00');
SQL

echo "==> 登录"
# emb-batch 专用账号登录（dev-env 的 login 默认用 java-phase1 账号，这里显式传邮箱）
login_as() { curl -s -X POST "http://localhost:${PORT}/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$1\",\"password\":\"${TEST_PASSWORD}\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])'; }
OWNER=$(login_as emb-batch@weknora.test)
VIEWER=$(login_as emb-batch-viewer@weknora.test)
AH="Authorization: Bearer ${OWNER}"
VH="Authorization: Bearer ${VIEWER}"
CT='Content-Type: application/json'
VISITOR_LONG=$(python3 -c 'print("v"*200)')

# ══════════════ 一、embed 管理面 ══════════════
echo "==> embed 管理面"

req emb-mgmt-create-missing-origin POST "/agents/${AG}/embed-channels" -H "$AH" -H "$CT" \
  -d '{"name":"no origins"}'
req emb-mgmt-create-bad-origin POST "/agents/${AG}/embed-channels" -H "$AH" -H "$CT" \
  -d '{"name":"bad","allowed_origins":["not a url"]}'
req emb-mgmt-create-bad-agent POST "/agents/ghost-agent/embed-channels" -H "$AH" -H "$CT" \
  -d '{"name":"x","allowed_origins":["https://a.example.com"]}'
req emb-mgmt-create-cross-agent POST "/agents/${AGF}/embed-channels" -H "$AH" -H "$CT" \
  -d '{"name":"x","allowed_origins":["https://a.example.com"]}'
req emb-mgmt-create-bad-icon POST "/agents/${AG}/embed-channels" -H "$AH" -H "$CT" \
  -d '{"name":"x","allowed_origins":["https://a.example.com"],"launcher_icon":"data:text/html;base64,PGI+"}'
req emb-mgmt-guard-viewer POST "/agents/${AG}/embed-channels" -H "$VH" -H "$CT" \
  -d '{"name":"x","allowed_origins":["https://a.example.com"]}'
req emb-mgmt-noauth POST "/agents/${AG}/embed-channels" -H "$CT" \
  -d '{"name":"x","allowed_origins":["https://a.example.com"]}'

req emb-mgmt-create POST "/agents/${AG}/embed-channels" -H "$AH" -H "$CT" -d @- <<JSON
{"name":"emb-main","allowed_origins":["https://a.example.com","*.b.example.com"],"welcome_message":"你好","rate_limit_per_minute":500,"rate_limit_per_day":9000,"primary_color":"#0052d9","page_title":"帮助中心","header_title_mode":"session","show_thinking":true,"widget_position":"bottom-left","allow_web_search":true,"allow_file_upload":true,"default_locale":"zh-CN","webhook_url":"https://hook.example.com/embed","webhook_secret":"sec123","launcher_icon":""}
JSON
CID=$(j "${OUT}/emb-mgmt-create.json" id)
PTOKEN=$(j "${OUT}/emb-mgmt-create.json" publish_token)

req emb-mgmt-create-minimal POST "/agents/${AGP}/embed-channels" -H "$AH" -H "$CT" \
  -d '{"name":"emb-min","allowed_origins":["*"]}'
CID_MIN=$(j "${OUT}/emb-mgmt-create-minimal.json" id)
PTOKEN_MIN=$(j "${OUT}/emb-mgmt-create-minimal.json" publish_token)

req emb-mgmt-create-disabled POST "/agents/${AGP}/embed-channels" -H "$AH" -H "$CT" \
  -d '{"name":"emb-off","allowed_origins":["https://c.example.com"],"enabled":false,"show_suggested_questions":false}'
CID_OFF=$(j "${OUT}/emb-mgmt-create-disabled.json" id)
PTOKEN_OFF=$(j "${OUT}/emb-mgmt-create-disabled.json" publish_token)

# ⚠️ Go quirk（golden 钉死）：create 对 default:true 的零值 bool 走 DB 默认——
# 请求 enabled:false / show_suggested_questions:false 落库后仍是 true（GORM Create
# 省略零值列）。禁用态只能经 Update（Save 全列写）达成。
req emb-mgmt-update-disable PUT "/embed-channels/${CID_OFF}" -H "$AH" -H "$CT" \
  -d '{"enabled":false,"show_suggested_questions":false}'
# ⚠️ 第二个 quirk（golden 钉死）：update 请求体**不带** allowed_origins 时，
# json.Marshal(nil) = "null" 会整列覆写 jsonb → 读回 null → allowlist 清空
# （后续公开请求 403 origin not allowed）。故此处必须显式带上 allowlist。
# CID_MIN：启用态 + 关闭推荐问题（suppressed 分支的载体）
req emb-mgmt-update-sugg-off PUT "/embed-channels/${CID_MIN}" -H "$AH" -H "$CT" \
  -d '{"show_suggested_questions":false,"allowed_origins":["*"]}'

# 回填 embed 会话 marker / owner（依赖随机渠道 id）
${PSQL} >/dev/null <<SQL
UPDATE sessions SET description = 'embed_channel:${CID}',
  user_id = 'embed_session:${TENANT}:${CID}:${SES_MAIN}' WHERE id = '${SES_MAIN}';
UPDATE sessions SET description = 'embed_channel:${CID_MIN}',
  user_id = 'embed_session:${TENANT}:${CID_MIN}:${SES_OTHER}' WHERE id = '${SES_OTHER}';
UPDATE sessions SET description = 'embed_channel:${CID_OFF}',
  user_id = 'embed_session:${TENANT}:${CID_OFF}:${SES_OFF}' WHERE id = '${SES_OFF}';
SQL
SIG_MAIN=$(sig "${PTOKEN}" "${CID}" "${SES_MAIN}")
SIG_OTHER=$(sig "${PTOKEN_MIN}" "${CID_MIN}" "${SES_OTHER}")
SIG_OFF=$(sig "${PTOKEN_OFF}" "${CID_OFF}" "${SES_OFF}")

req emb-mgmt-list-by-agent GET "/agents/${AG}/embed-channels" -H "$VH"
req emb-mgmt-list-all GET "/embed-channels" -H "$VH"
req emb-mgmt-get GET "/embed-channels/${CID}" -H "$VH"
req emb-mgmt-get-404 GET "/embed-channels/b9999999-0000-0000-0000-000000000001" -H "$VH"
req emb-mgmt-update PUT "/embed-channels/${CID}" -H "$AH" -H "$CT" \
  -d '{"name":"emb-main-renamed","welcome_message":"欢迎回来","allowed_origins":["https://a.example.com","*.b.example.com","https://c.example.com"],"primary_color":"#1177ee","show_thinking":false}'
req emb-mgmt-update-bad-webhook PUT "/embed-channels/${CID}" -H "$AH" -H "$CT" \
  -d '{"webhook_url":"ftp://hook.example.com/x"}'
req emb-mgmt-update-404 PUT "/embed-channels/b9999999-0000-0000-0000-000000000001" -H "$AH" -H "$CT" \
  -d '{"name":"x"}'
req emb-mgmt-rotate POST "/embed-channels/${CID}/rotate-token" -H "$AH"
PTOKEN=$(j "${OUT}/emb-mgmt-rotate.json" publish_token)
SIG_MAIN=$(sig "${PTOKEN}" "${CID}" "${SES_MAIN}")
req emb-mgmt-preview POST "/embed-channels/${CID}/preview-session" -H "$VH"
req emb-mgmt-preview-disabled POST "/embed-channels/${CID_OFF}/preview-session" -H "$VH"
req emb-mgmt-preview-404 POST "/embed-channels/b9999999-0000-0000-0000-000000000001/preview-session" -H "$VH"
req emb-mgmt-stats GET "/embed-channels/${CID}/stats" -H "$VH"
req emb-mgmt-stats-404 GET "/embed-channels/b9999999-0000-0000-0000-000000000001/stats" -H "$VH"

# ══════════════ 二、embed 公开面：EmbedAuth 门 ══════════════
echo "==> embed 公开面（EmbedAuth 门）"
EA="Authorization: Embed ${PTOKEN}"
ORIG='Origin: https://a.example.com'

req emb-pub-noauth GET "/embed/${CID}/config" -H "$ORIG"
req emb-pub-badtoken GET "/embed/${CID}/config" -H "Authorization: Embed em_wrongtoken" -H "$ORIG"
req emb-pub-badorigin GET "/embed/${CID}/config" -H "$EA" -H "Origin: https://evil.example"
req emb-pub-disabled POST "/embed/${CID_OFF}/exchange" -H "Authorization: Embed ${PTOKEN_OFF}" -H "Origin: https://c.example.com"
req emb-pub-config GET "/embed/${CID}/config" -H "$EA" -H "$ORIG"

req emb-pub-exchange POST "/embed/${CID}/exchange" -H "$EA" -H "$ORIG"
STOKEN=$(j "${OUT}/emb-pub-exchange.json" session_token)
req emb-pub-exchange-session-token POST "/embed/${CID}/exchange" -H "Authorization: Embed ${STOKEN}" -H "$ORIG"
req emb-pub-exchange-bearer POST "/embed/${CID}/exchange" -H "Authorization: Bearer ${PTOKEN}" -H "$ORIG"

req emb-pub-suggested-off GET "/embed/${CID_MIN}/suggested-questions" -H "Authorization: Embed ${PTOKEN_MIN}" -H "$ORIG"
req emb-pub-suggested GET "/embed/${CID}/suggested-questions" -H "$EA" -H "$ORIG"
req emb-pub-suggested-limit GET "/embed/${CID}/suggested-questions?limit=99" -H "$EA" -H "$ORIG"

req emb-pub-chunk-404 GET "/embed/${CID}/chunks/b4999999-0000-0000-0000-000000000001" -H "$EA" -H "$ORIG"
req emb-pub-chunk-ok GET "/embed/${CID}/chunks/${CH_A}" -H "$EA" -H "$ORIG"
req emb-pub-chunk-forbidden GET "/embed/${CID}/chunks/${CH_B}" -H "$EA" -H "$ORIG"
req emb-pub-chunk-cross-tenant GET "/embed/${CID}/chunks/${CH_F}" -H "$EA" -H "$ORIG"

req emb-pub-create-session POST "/embed/${CID}/sessions" -H "$EA" -H "$ORIG"

req emb-pub-load GET "/embed/${CID}/messages/${SES_MAIN}/load" -H "$EA" -H "$ORIG" -H "X-Embed-Session: ${SIG_MAIN}"
req emb-pub-load-nosig GET "/embed/${CID}/messages/${SES_MAIN}/load" -H "$EA" -H "$ORIG"
req emb-pub-load-badsig GET "/embed/${CID}/messages/${SES_MAIN}/load" -H "$EA" -H "$ORIG" -H "X-Embed-Session: bogus"
req emb-pub-load-othersession GET "/embed/${CID}/messages/${SES_OTHER}/load" -H "$EA" -H "$ORIG" -H "X-Embed-Session: ${SIG_OTHER}"
req emb-pub-load-404 GET "/embed/${CID}/messages/b5999999-0000-0000-0000-000000000001/load" -H "$EA" -H "$ORIG" -H "X-Embed-Session: x"
req emb-pub-load-badvisitor GET "/embed/${CID}/messages/${SES_MAIN}/load" -H "$EA" -H "$ORIG" -H "X-Embed-Session: ${SIG_MAIN}" -H "X-Embed-Visitor: bad visitor
id"

req emb-pub-stop-done POST "/embed/${CID}/sessions/${SES_MAIN}/stop" -H "$EA" -H "$ORIG" -H "X-Embed-Session: ${SIG_MAIN}" -H "$CT" \
  -d "{\"message_id\":\"${MSG_DONE}\"}"
req emb-pub-stop-404 POST "/embed/${CID}/sessions/${SES_MAIN}/stop" -H "$EA" -H "$ORIG" -H "X-Embed-Session: ${SIG_MAIN}" -H "$CT" \
  -d '{"message_id":"b6999999-0000-0000-0000-000000000001"}'
req emb-pub-stop-nobody POST "/embed/${CID}/sessions/${SES_MAIN}/stop" -H "$EA" -H "$ORIG" -H "X-Embed-Session: ${SIG_MAIN}" -H "$CT"

req emb-pub-suggestions-off GET "/embed/${CID_MIN}/sessions/${SES_OTHER}/messages/${MSG_DONE}/suggestions" -H "Authorization: Embed ${PTOKEN_MIN}" -H "$ORIG" -H "X-Embed-Session: ${SIG_OTHER}"
req emb-pub-suggestions-get-none GET "/embed/${CID}/sessions/${SES_MAIN}/messages/b6999999-0000-0000-0000-000000000009/suggestions" -H "$EA" -H "$ORIG" -H "X-Embed-Session: ${SIG_MAIN}"
req emb-pub-suggestions-ensure-badbody POST "/embed/${CID}/sessions/${SES_MAIN}/messages/${MSG_DONE}/suggestions" -H "$EA" -H "$ORIG" -H "X-Embed-Session: ${SIG_MAIN}" -H "$CT" \
  -d 'not-json'

# 种子推荐问题集（GET/事件上报用）
${PSQL} >/dev/null <<SQL
INSERT INTO message_suggestion_sets (id, tenant_id, session_id, assistant_message_id, agent_id, agent_tenant_id, placement, config_hash, locale, status, allow_regenerate, suppression_reason, questions, model_id, prompt_tokens, completion_tokens, latency_ms, error_code, created_at, updated_at)
VALUES ('${SSET}', ${TENANT}, '${SES_MAIN}', '${MSG_DONE}', '${AG}', ${TENANT}, 'after_answer', 'no-agent-config', 'zh-CN', 'ready', true, '',
  '[{"id":"q1","question":"还想了解什么？"}]', '', 0, 0, 0, '', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00');
SQL

req emb-pub-suggestions-get GET "/embed/${CID}/sessions/${SES_MAIN}/messages/${MSG_DONE}/suggestions" -H "$EA" -H "$ORIG" -H "X-Embed-Session: ${SIG_MAIN}"
req emb-pub-suggestion-events POST "/embed/${CID}/sessions/${SES_MAIN}/suggestion-events" -H "$EA" -H "$ORIG" -H "X-Embed-Session: ${SIG_MAIN}" -H "$CT" \
  -d "{\"suggestion_set_id\":\"${SSET}\",\"question_id\":\"q1\",\"event_type\":\"impression\"}"
req emb-pub-suggestion-events-badbody POST "/embed/${CID}/sessions/${SES_MAIN}/suggestion-events" -H "$EA" -H "$ORIG" -H "X-Embed-Session: ${SIG_MAIN}" -H "$CT" \
  -d 'not-json'

req emb-pub-events-badtype POST "/embed/${CID}/sessions/${SES_MAIN}/events" -H "$EA" -H "$ORIG" -H "X-Embed-Session: ${SIG_MAIN}" -H "$CT" \
  -d '{"type":"message_deleted"}'
req emb-pub-events-badbody POST "/embed/${CID}/sessions/${SES_MAIN}/events" -H "$EA" -H "$ORIG" -H "X-Embed-Session: ${SIG_MAIN}" -H "$CT" \
  -d 'not-json'
req emb-pub-events-ok POST "/embed/${CID}/sessions/${SES_MAIN}/events" -H "$EA" -H "$ORIG" -H "X-Embed-Session: ${SIG_MAIN}" -H "$CT" \
  -d '{"type":"message_sent","query":"你好","content":"收到","session_id":""}'

# MCP OAuth ×4 + tool-approvals（委托已翻 MCP 面；未知对象分支确定）
SVC_OAUTH="b8000000-0000-0000-0000-000000000601"
req emb-pub-mcp-authorize-nobody POST "/embed/${CID}/sessions/${SES_MAIN}/mcp-services/${SVC_OAUTH}/oauth/authorize-url" -H "$EA" -H "$ORIG" -H "X-Embed-Session: ${SIG_MAIN}" -H "$CT"
req emb-pub-mcp-authorize-unknown POST "/embed/${CID}/sessions/${SES_MAIN}/mcp-services/${SVC_OAUTH}/oauth/authorize-url" -H "$EA" -H "$ORIG" -H "X-Embed-Session: ${SIG_MAIN}" -H "$CT" \
  -d '{"redirect_uri":"https://a.example.com/oauth","frontend_redirect":""}'
req emb-pub-mcp-status GET "/embed/${CID}/sessions/${SES_MAIN}/mcp-services/${SVC_OAUTH}/oauth/status" -H "$EA" -H "$ORIG" -H "X-Embed-Session: ${SIG_MAIN}"
# gate 依赖分支（resolve/tool-approvals）不录，见文件头注释\n
req emb-mgmt-delete DELETE "/embed-channels/${CID_MIN}" -H "$AH"
req emb-mgmt-get-deleted GET "/embed-channels/${CID_MIN}" -H "$VH"

# ══════════════ 三、im channels 清单面 ══════════════
echo "==> im channels 清单面"

req imc-create-noplat POST "/agents/${AG}/im-channels" -H "$AH" -H "$CT" -d '{}'
req imc-create-badplatform POST "/agents/${AG}/im-channels" -H "$AH" -H "$CT" \
  -d '{"platform":"msn","name":"x"}'
req imc-guard-viewer POST "/agents/${AG}/im-channels" -H "$VH" -H "$CT" \
  -d '{"platform":"slack"}'

req imc-create POST "/agents/${AG}/im-channels" -H "$AH" -H "$CT" \
  -d '{"platform":"telegram","name":"tg-bot","knowledge_base_id":"","credentials":{"bot_token":"7654321:AAEmbtoken123"},"enabled":true}'
IMC1=$(j "${OUT}/imc-create.json" id)
req imc-create-wechat POST "/agents/${AG}/im-channels" -H "$AH" -H "$CT" \
  -d '{"platform":"wechat","name":"wx-bot","credentials":{"ilink_bot_id":"wx-bot-1"}}'
IMC2=$(j "${OUT}/imc-create-wechat.json" id)
req imc-create-mattermost POST "/agents/${AGP}/im-channels" -H "$AH" -H "$CT" \
  -d '{"platform":"mattermost","name":"mm-bot","credentials":{"outgoing_token":"mm-token-1"}}'
IMC3=$(j "${OUT}/imc-create-mattermost.json" id)
req imc-create-dup POST "/agents/${AG}/im-channels" -H "$AH" -H "$CT" \
  -d '{"platform":"telegram","name":"tg-again","credentials":{"bot_token":"7654321:AAEmbtoken123"}}'
req imc-create-badsessionmode POST "/agents/${AG}/im-channels" -H "$AH" -H "$CT" \
  -d '{"platform":"slack","name":"bad-mode","session_mode":"party"}'
req imc-create-tg2 POST "/agents/${AGP}/im-channels" -H "$AH" -H "$CT" \
  -d '{"platform":"telegram","name":"tg-two","credentials":{"bot_token":"8888777:BBOtherToken"}}'
IMC4=$(j "${OUT}/imc-create-tg2.json" id)

req imc-list-by-agent GET "/agents/${AG}/im-channels" -H "$VH"
req imc-list-all GET "/im-channels" -H "$VH"
req imc-update PUT "/im-channels/${IMC1}" -H "$AH" -H "$CT" \
  -d '{"name":"tg-bot-renamed","knowledge_base_id":"'"${KB_A}"'","credentials":{"bot_token":"7654321:AAEmbtoken123"},"enabled":false}'
req imc-update-404 PUT "/im-channels/b9999999-0000-0000-0000-000000000001" -H "$AH" -H "$CT" \
  -d '{"name":"x"}'
req imc-update-badagent PUT "/im-channels/${IMC1}" -H "$AH" -H "$CT" \
  -d '{"agent_id":"ghost-agent"}'
req imc-update-dup PUT "/im-channels/${IMC4}" -H "$AH" -H "$CT" \
  -d '{"credentials":{"bot_token":"7654321:AAEmbtoken123"}}'
req imc-toggle POST "/im-channels/${IMC1}/toggle" -H "$AH"
req imc-toggle-off POST "/im-channels/${IMC1}/toggle" -H "$AH"
req imc-toggle-404 POST "/im-channels/b9999999-0000-0000-0000-000000000001/toggle" -H "$AH"
req imc-delete DELETE "/im-channels/${IMC3}" -H "$AH"
req imc-delete-404 DELETE "/im-channels/b9999999-0000-0000-0000-000000000001" -H "$AH"
req imc-qrcode-status-nobody POST "/wechat/qrcode/status" -H "$AH" -H "$CT"

echo "==> 完成：$(ls "${OUT}" | grep -c 'emb-\|imc-') 条 golden"
