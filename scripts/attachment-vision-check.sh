#!/usr/bin/env bash
# 附件图片进 vision 的端到端复验：上传一张特征图（左红右蓝）→ 提问 → 打印 SSE 回答。
#
# 用法：
#   scripts/attachment-vision-check.sh <agent_id> [agent_source_tenant_id] [port]
#
# 例（dev 里唯一开了图片上传且模型支持视觉的内置 agent）：
#   scripts/attachment-vision-check.sh builtin-smart-reasoning 10122
#
# 前置条件（不满足时脚本照常跑，但模型看不到图）：
#   - 目标 agent 的 `image_upload_enabled=true`，且其配置模型 `supports_vision=true`
#     （图片经 ResolveForPrompt 的 ImageURLs → reqCtx.images → 多模态请求下发）；
#   - 运行实例的 `LOCAL_STORAGE_BASE_DIR` 与上传侧一致（dev = /tmp/weknora-java-files），
#     否则 `local://` 手柄读不回字节。
#
# 脚本自建临时会话（`vision-check-*`），退出时删除会话与附件；SSE 原文留在
# `${SSE_LOG}` 供人工核对帧序列。
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=dev-env.sh
source "${SCRIPT_DIR}/dev-env.sh"

AGENT_ID="${1:-}"
if [ -z "${AGENT_ID}" ]; then
  echo "用法：scripts/attachment-vision-check.sh <agent_id> [agent_source_tenant_id] [port]" >&2
  exit 2
fi
AGENT_SRC="${2:-}"
PORT="${3:-${JAVA_PORT:-8082}}"
BASE="http://localhost:${PORT}"
IMG="${IMG:-/tmp/vision-check.png}"
SSE_LOG="${SSE_LOG:-/tmp/vision-check-sse.txt}"

# 内置特征图：200x150，左半红右半蓝（模型答"左红右蓝"即证明真看到了像素）
python3 - "$IMG" <<'PY'
import struct
import sys
import zlib

width, height = 200, 150
rows = bytearray()
for _y in range(height):
    rows.append(0)
    for x in range(width):
        rows += b"\xff\x00\x00" if x < width // 2 else b"\x00\x00\xff"


def chunk(tag, data):
    return (struct.pack(">I", len(data)) + tag + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))


png = b"\x89PNG\r\n\x1a\n"
png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
png += chunk(b"IDAT", zlib.compress(bytes(rows), 9))
png += chunk(b"IEND", b"")
open(sys.argv[1], "wb").write(png)
PY
echo "==> 特征图：${IMG}（200x150，左红右蓝）"

TOKEN="$(bash "${SCRIPT_DIR}/token.sh" "${PORT}")"
AUTH=(-H "Authorization: Bearer ${TOKEN}")

SID="$(curl -s -X POST "${BASE}/api/v1/sessions" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -d '{"title":"vision-check"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin).get("data",{}).get("id",""))')"
if [ -z "${SID}" ]; then
  echo "创建会话失败" >&2
  exit 1
fi
echo "==> 临时会话 ${SID}"

cleanup() {
  for aid in $(curl -s "${BASE}/api/v1/sessions/${SID}/attachments" "${AUTH[@]}" 2>/dev/null \
      | python3 -c 'import sys,json;d=json.load(sys.stdin).get("data") or [];print(" ".join(x.get("id","") for x in d))' 2>/dev/null); do
    curl -s -X DELETE "${BASE}/api/v1/sessions/${SID}/attachments/${aid}" "${AUTH[@]}" >/dev/null
  done
  curl -s -X DELETE "${BASE}/api/v1/sessions/${SID}" "${AUTH[@]}" >/dev/null
  echo "==> 已清理会话 ${SID}"
}
trap cleanup EXIT

AID="$(curl -s -X POST "${BASE}/api/v1/sessions/${SID}/attachments" "${AUTH[@]}" \
  -F "file=@${IMG};filename=vision-check.png" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin).get("data",{}).get("id",""))')"
if [ -z "${AID}" ]; then
  echo "上传附件失败" >&2
  exit 1
fi
echo "==> 附件 ${AID}，等待解析"
for _i in $(seq 1 30); do
  status="$(curl -s "${BASE}/api/v1/sessions/${SID}/attachments/${AID}" "${AUTH[@]}" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin).get("data",{}).get("status",""))' 2>/dev/null)"
  [ "${status}" = "ready" ] && break
  [ "${status}" = "failed" ] && { echo "解析失败" >&2; exit 1; }
  sleep 1
done
echo "==> 解析终态 status=${status}"

BODY="$(python3 - "$SID" "$AGENT_ID" "$AGENT_SRC" "$AID" <<'PY'
import json
import sys

sid, agent_id, agent_src, aid = sys.argv[1:5]
body = {
    "session_id": sid,
    "query": "这张图片里有什么？请描述颜色和布局。",
    "agent_enabled": True,
    "agent_id": agent_id,
    "attachment_ids": [aid],
}
if agent_src:
    body["agent_source_tenant_id"] = int(agent_src)
print(json.dumps(body, ensure_ascii=False))
PY
)"

echo "==> 发起问答（SSE 原文：${SSE_LOG}）"
# 端点是 `/api/v1/agent-chat/{session_id}`（会话 id 在路径上，body 里也带一份无妨）
curl -sN -X POST "${BASE}/api/v1/agent-chat/${SID}" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -d "${BODY}" > "${SSE_LOG}"

python3 - "${SSE_LOG}" <<'PY'
import json
import sys

frames = 0
answer = []
errors = []
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    line = line.strip()
    if not line.startswith("data:"):
        continue
    payload = line[5:].strip()
    if not payload or payload == "[DONE]":
        continue
    try:
        obj = json.loads(payload)
    except Exception:
        continue
    frames += 1
    kind = obj.get("type") or obj.get("event") or obj.get("response_type") or ""
    if obj.get("error"):
        errors.append(json.dumps(obj, ensure_ascii=False)[:300])
        continue
    if "answer" in str(kind):
        data = obj.get("data")
        content = obj.get("content")
        if content is None and isinstance(data, dict):
            content = data.get("content") or data.get("answer") or data.get("text")
        if content is None and isinstance(data, str):
            content = data
        if content:
            answer.append(content)
print("SSE 帧数：%d" % frames)
text = "".join(answer).strip()
if text:
    print("模型回答：%s" % text[:800])
else:
    print("模型回答：(无 answer 帧)")
for err in errors:
    print("错误帧：%s" % err)
if frames == 0:
    print("提示：0 帧通常意味着请求被拒（看上面错误/原文）；SSE 原文在 %s" % sys.argv[1])
PY
