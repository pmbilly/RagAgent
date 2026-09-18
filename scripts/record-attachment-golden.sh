#!/usr/bin/env bash
# 录 session 波 1 G5（临时文档 attachments 5 条）的 golden。
# 用 .txt（纯文本管线：直读 → chunker auto/1600/160 → ApproxTokenCount）——
# 终态 ready 确定性强；sleep 等待 worker 落终态再录 GET。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

OUT="${RAGAGENT_ROOT}/server/src/test/resources/contracts"
API="http://localhost:${GO_PORT}/api/v1"
TOKEN="$(login "${TEST_EMAIL}" "${GO_PORT}")"
AUTH="Authorization: Bearer ${TOKEN}"

req() {
  local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "${OUT}/${out}" -w '%{http_code} %{url_effective}\n' \
    -X "${method}" "${API}${path}" -H "${AUTH}" "$@"
}

UNKNOWN_ID="11111111-2222-3333-4444-999999999999"
TMPDIR_G5="$(mktemp -d)"
printf '第一行内容\n第二行内容\n第三行内容\n' > "${TMPDIR_G5}/note.txt"

echo "==> 准备：会话"
req att-setup.json POST /sessions -H 'Content-Type: application/json' -d '{"title":""}'
SID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "${OUT}/att-setup.json")"
rm -f "${OUT}/att-setup.json"
echo "    SID=${SID}"

echo "==> 1) 上传（成功 / 各校验失败）"
req att-upload.json POST "/sessions/${SID}/attachments" -F "file=@${TMPDIR_G5}/note.txt;type=text/plain"
ATT_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["id"])' "${OUT}/att-upload.json")"
echo "    ATT_ID=${ATT_ID}"
req att-upload-no-file.json POST "/sessions/${SID}/attachments"
req att-upload-unknown-session.json POST "/sessions/${UNKNOWN_ID}/attachments" -F "file=@${TMPDIR_G5}/note.txt;type=text/plain"
printf 'x' > "${TMPDIR_G5}/evil.exe"
req att-upload-unsupported.json POST "/sessions/${SID}/attachments" -F "file=@${TMPDIR_G5}/evil.exe"
: > "${TMPDIR_G5}/empty.txt"
req att-upload-empty.json POST "/sessions/${SID}/attachments" -F "file=@${TMPDIR_G5}/empty.txt"

echo "==> 2) 列表 / 详情（先等 worker 落终态）"
sleep 4
req att-list.json GET "/sessions/${SID}/attachments"
req att-get.json GET "/sessions/${SID}/attachments/${ATT_ID}"
req att-get-404.json GET "/sessions/${SID}/attachments/${UNKNOWN_ID}"
req att-list-404.json GET "/sessions/${UNKNOWN_ID}/attachments"

echo "==> 3) 预览"
curl -s -D "${OUT}/att-preview.headers" -o "${OUT}/att-preview.body" -w '%{http_code}\n' \
  "${API}/sessions/${SID}/attachments/${ATT_ID}/preview" -H "${AUTH}"
req att-preview-404.json GET "/sessions/${SID}/attachments/${UNKNOWN_ID}/preview"

echo "==> 4) 删除"
req att-delete.json DELETE "/sessions/${SID}/attachments/${ATT_ID}"
req att-list-after-delete.json GET "/sessions/${SID}/attachments"
req att-delete-again.json DELETE "/sessions/${SID}/attachments/${ATT_ID}"

rm -rf "${TMPDIR_G5}"
echo "==> 清理会话"
curl -s -o /dev/null -w '%{http_code} delete\n' -X DELETE "${API}/sessions/${SID}" -H "${AUTH}"
echo "==> done. golden 在 ${OUT}/att-*.json / att-preview.headers / att-preview.body"
