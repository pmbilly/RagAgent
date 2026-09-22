#!/usr/bin/env bash
# 录制 models/{id}/debug 的 Go golden（阶段 7：DebugModel 端点）。
#
# 场景与 server/src/test/java/com/ragagent/model/ModelDebugContractTest.java
# 一一对应；掩码只有 elapsed_ms（其余全部由 stub 固定值钉死）。
#
# 前置：
#   1) python3 scripts/stub-llm-server.py 8181 &   # chat/embeddings/rerank/audio
#   2) SSRF_WHITELIST_EXTRA=127.0.0.1 scripts/go-server-up.sh
#
# 用法：
#   scripts/record-modeldebug-golden.sh
#   MD_TARGET_PORT=8082 MD_OUT_DIR=/tmp/x scripts/record-modeldebug-golden.sh  # 回放 Java 侧
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${MD_OUT_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
PORT="${MD_TARGET_PORT:-${GO_PORT}}"
API="http://localhost:${PORT}/api/v1"
export PGPASSWORD='postgres123!@#'
PSQL="psql -q -t -h localhost -p 15432 -U postgres -d WeKnora"
mkdir -p "${OUT}"

STUB_LLM="http://127.0.0.1:8181/v1"

mm() { #  multipart POST：mm <out> <modelId> [--form-string ...]...
  # 注意必须用 --form-string 而不是 -F：-F 把以 < 开头的值当成文件读
  # （curl exit 26），<<SCENARIO:...>> 标记会踩中。
  local out="$1" mid="$2"; shift 2
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X POST "${API}/models/${mid}/debug" -H "Authorization: Bearer ${TOKEN}" "$@"; }

TENANT=10009
MDU="b0000000-0000-0000-0000-0000000000f1"
MD_CHAT="b0000000-0000-0000-0000-000000000001"
MD_CHAT_THINK="b0000000-0000-0000-0000-000000000002"
MD_EMB="b0000000-0000-0000-0000-000000000003"
MD_RERANK="b0000000-0000-0000-0000-000000000004"
MD_VLM="b0000000-0000-0000-0000-000000000005"
MD_ASR="b0000000-0000-0000-0000-000000000006"
MD_ASR_NOKEY="b0000000-0000-0000-0000-000000000007"
MD_BADSRC="b0000000-0000-0000-0000-000000000008"
MD_EMB_DL="b0000000-0000-0000-0000-000000000009"
MISSING="b0000000-0000-0000-0000-000000000099"
# 1x1 PNG（与 W5b 同字节）
TINY_PNG_B64="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

echo "==> 幂等清理 + 种子（租户 ${TENANT}）"
${PSQL} >/dev/null 2>&1 <<SQL
DELETE FROM models WHERE tenant_id IN (${TENANT});
DELETE FROM tenant_members WHERE tenant_id IN (${TENANT});
DELETE FROM users WHERE tenant_id IN (${TENANT});
DELETE FROM tenants WHERE id IN (${TENANT});
INSERT INTO tenants (id, name, description, business, status) VALUES (${TENANT}, 'md-batch-tenant', '', '', 'active');
INSERT INTO users (id, username, email, password_hash, tenant_id, is_active) VALUES
 ('${MDU}', 'mdbatch', 'md-batch@weknora.test', '\$2a\$10\$9U3ZmqQkmCqoQUZapJ1Txe5puo70IHlrnyZnSdE9LO/HUagt5exnK', ${TENANT}, true);
INSERT INTO tenant_members (user_id, tenant_id, role, status) VALUES
 ('${MDU}', ${TENANT}, 'owner', 'active');
INSERT INTO models (id, tenant_id, type, name, source, description, parameters, is_default, status, created_at, updated_at) VALUES
 ('${MD_CHAT}',       ${TENANT}, 'KnowledgeQA', 'md-chat',       'remote', 'debug chat',       '{"base_url":"${STUB_LLM}"}', FALSE, 'active', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
 ('${MD_CHAT_THINK}', ${TENANT}, 'KnowledgeQA', 'md-chat-think', 'remote', 'debug chat think', '{"base_url":"${STUB_LLM}","extra_config":{"thinking_control":"enable_thinking","api_token":"super-secret"}}', FALSE, 'active', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
 ('${MD_EMB}',        ${TENANT}, 'Embedding',   'md-emb',        'remote', 'debug embedding',  '{"base_url":"${STUB_LLM}"}', FALSE, 'active', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
 ('${MD_RERANK}',     ${TENANT}, 'Rerank',      'md-rerank',     'remote', 'debug rerank',     '{"base_url":"${STUB_LLM}"}', FALSE, 'active', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
 ('${MD_VLM}',        ${TENANT}, 'VLLM',        'md-vlm',        'remote', 'debug vlm',        '{"base_url":"${STUB_LLM}"}', FALSE, 'active', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
 ('${MD_ASR}',        ${TENANT}, 'ASR',         'md-asr',        'remote', 'debug asr',        '{"base_url":"${STUB_LLM}","api_key":"sk-debug"}', FALSE, 'active', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
 ('${MD_ASR_NOKEY}',  ${TENANT}, 'ASR',         'md-asr-nokey',  'remote', 'debug asr nokey',  '{"base_url":"${STUB_LLM}"}', FALSE, 'active', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
 ('${MD_BADSRC}',     ${TENANT}, 'KnowledgeQA', 'md-badsrc',     'bogus',  'debug bad source', '{"base_url":"${STUB_LLM}"}', FALSE, 'active', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00'),
 ('${MD_EMB_DL}',     ${TENANT}, 'Embedding',   'md-emb-dl',     'remote', 'debug downloading','{"base_url":"${STUB_LLM}"}', FALSE, 'downloading', '2026-09-01 08:00:00+00', '2026-09-01 08:00:00+00');
SQL

TOKEN=$(login "md-batch@weknora.test" "${PORT}")
echo "==> token ok（租户 ${TENANT}）"

TMPDIR_MD=$(mktemp -d)
printf '%s' "${TINY_PNG_B64}" | base64 -d > "${TMPDIR_MD}/tiny.png"
printf 'fake-mp3-bytes' > "${TMPDIR_MD}/tiny.mp3"
# 恰好超过 64KB 的 input（64*1024+1 字节）
python3 -c 'import sys; sys.stdout.write("x" * (64*1024+1))' > "${TMPDIR_MD}/long-input.txt"
# 101 条 documents
python3 -c 'import json; print(json.dumps(["d%d" % i for i in range(101)]))' > "${TMPDIR_MD}/docs-101.json"

echo "==> 参数校验族（确定性 400/404/500）"
mm md-not-found.json            "${MISSING}"      --form-string "input=hi"
mm md-input-too-long.json       "${MD_CHAT}"      -F "input=<${TMPDIR_MD}/long-input.txt"
mm md-options-bad-type.json     "${MD_CHAT}"      --form-string "input=hi" --form-string 'options={"max_tokens":"abc"}'
mm md-options-syntax.json       "${MD_CHAT}"      --form-string "input=hi" --form-string 'options={'
mm md-options-maxtokens.json    "${MD_CHAT}"      --form-string "input=hi" --form-string 'options={"max_tokens":0}'
mm md-options-temperature.json  "${MD_CHAT}"      --form-string "input=hi" --form-string 'options={"temperature":2.5}'
mm md-options-topp.json         "${MD_CHAT}"      --form-string "input=hi" --form-string 'options={"top_p":0}'
mm md-documents-bad.json        "${MD_RERANK}"    --form-string "input=q" --form-string "documents=not-json"
mm md-documents-too-many.json   "${MD_RERANK}"    --form-string "input=q" -F "documents=<${TMPDIR_MD}/docs-101.json"
mm md-model-downloading.json    "${MD_EMB_DL}"    --form-string "input=hi"

echo "==> 分支前置校验（400）"
mm md-chat-empty-query.json     "${MD_CHAT}"      --form-string "input=   "
mm md-embedding-empty.json      "${MD_EMB}"       --form-string "input=  "
mm md-rerank-missing-docs.json  "${MD_RERANK}"    --form-string "input=q"
mm md-vlm-no-file.json          "${MD_VLM}"       --form-string "input=describe"
mm md-asr-no-file.json          "${MD_ASR}"       --form-string "input="

echo "==> 成功路径（stub 上游）"
mm md-chat-ok.json              "${MD_CHAT}"      --form-string "input=<<SCENARIO:chat>>你好"
mm md-chat-options.json         "${MD_CHAT}"      --form-string "input=<<SCENARIO:chat>>你好" \
    --form-string 'options={"system_prompt":"你是助手","temperature":0.5,"top_p":0.9,"max_tokens":100,"thinking":true}'
mm md-chat-thinking.json        "${MD_CHAT_THINK}" --form-string "input=<<SCENARIO:chat>>你好" --form-string 'options={"thinking":true}'
mm md-chat-bad-source.json      "${MD_BADSRC}"    --form-string "input=hi"
mm md-embedding-ok.json         "${MD_EMB}"       --form-string "input=embed me"
mm md-rerank-ok.json            "${MD_RERANK}"    --form-string "input=q" --form-string 'documents=["第一段","第二段"]'
mm md-vlm-ok.json               "${MD_VLM}"       --form-string "input=描述这张图" -F "file=@${TMPDIR_MD}/tiny.png"
mm md-asr-ok.json               "${MD_ASR}"       -F "file=@${TMPDIR_MD}/tiny.mp3"
mm md-asr-401.json              "${MD_ASR_NOKEY}" -F "file=@${TMPDIR_MD}/tiny.mp3"

rm -rf "${TMPDIR_MD}"
echo "==> 完成。golden 写入 ${OUT}（掩码：elapsed_ms）"
