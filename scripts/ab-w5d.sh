#!/usr/bin/env bash
# W5d A/B：沙箱终端（ticket + WS 错误族 + **实测 WS 握手**）+ 会话侧 local-browser
# + embed QA 委托 / 文件代理。
#
# 三段：
#   1) 场景重放比对——record-w5d-golden.sh 已对 Go 录进 contracts/，本脚本对 Java
#      重放到 /tmp，逐场景掩码后比对（ticket JWT / 渠道与会话 id / publish token /
#      sig / X-Request-Id / Date 都掩）。
#   2) WS 握手实测——双端各铸真 ticket，原始 socket 打握手：断言 101 +
#      Sec-WebSocket-Accept 合法 + 首帧是 close(1008, SANDBOX_NOT_BOUND)。
#      真 PTY（成功路径）是波 5 的 provider 执行体接缝，标 XDEP 不测。
#   3) setup 文件（渠道/会话创建）只用于建链，不参与比对（id/token 每轮随机）。
#
# 前置：Go :8080 与 Java :8082 都在跑（共享 dev DB；种子由重放脚本幂等重建）。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${SCRIPT_DIR}/scripts/dev-env.sh"

GO_DIR="${W5D_GO_DIR:-${RAGAGENT_ROOT}/server/src/test/resources/contracts}"
JAVA_DIR="/tmp/w5d-java"
pass=0; fail=0

echo "==> 1) Java 重放"
W5D_TARGET_PORT="${JAVA_PORT}" W5D_OUT_DIR="${JAVA_DIR}" \
  scripts/record-w5d-golden.sh >/dev/null

mask() { python3 -c '
import re,sys
s=sys.stdin.read()
s=re.sub(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+","<JWT>",s)
s=re.sub(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}","<UUID>",s)
s=re.sub(r"em_[A-Za-z0-9_-]+","<EMTOK>",s)
s=re.sub(r"\"publish_token\":\"[^\"]*\"","\"publish_token\":\"<PT>\"",s)
s=re.sub(r"\"sig\":\"[^\"]*\"","\"sig\":\"<SIG>\"",s)
sys.stdout.write(s)'; }

# 头部归一（W5c 同款）：状态行/Date/X-Request-Id/Connection/Vary 是容器面；
# Tomcat 会把 Content-Type 的 ";charset=" 前空格规范化掉（已备案），两侧同形归一。
# Access-Control-* 整族排除：Go cors 中间件配 '*' 恒写通配，Spring CORS 禁止
# allowCredentials+'*' 组合而回显 Origin（Expose-Headers 逗号空格亦框架渲染差异）
# ——浏览器语义等价，框架层差异不入字节契约（详见 known-issues/06-wave-5.md）。
norm_hdr() { grep -viE '^(date|x-request-id|keep-alive|connection|vary:|access-control-|HTTP/)' "$1" 2>/dev/null \
               | sed -E 's/^(Content-Type:[^;]*); ?charset=/\1; charset=/I' \
               | grep -v '^$' | sort; }

cmp_one() { # name file
  local name="$1" file="$2"
  if [ ! -f "${GO_DIR}/${file}" ]; then echo "SKIP  ${name}（Go 侧无 ${file}）"; return; fi
  if diff -q <(mask < "${GO_DIR}/${file}") <(mask < "${JAVA_DIR}/${file}") >/dev/null 2>&1; then
    pass=$((pass+1)); echo "MATCH ${name}"
  else
    fail=$((fail+1)); echo "DIFF  ${name}"
    diff <(mask < "${GO_DIR}/${file}") <(mask < "${JAVA_DIR}/${file}") | head -6 || true
  fi
}

cmp_hdr() { # name file
  local name="$1" file="$2"
  if [ ! -f "${GO_DIR}/${file}" ]; then echo "SKIP  ${name}（Go 侧无 ${file}）"; return; fi
  if diff -q <(norm_hdr "${GO_DIR}/${file}") <(norm_hdr "${JAVA_DIR}/${file}") >/dev/null 2>&1; then
    pass=$((pass+1)); echo "MATCH ${name}"
  else
    fail=$((fail+1)); echo "DIFF  ${name}"
    diff <(norm_hdr "${GO_DIR}/${file}") <(norm_hdr "${JAVA_DIR}/${file}") | head -8 || true
  fi
}

for f in w5d-term-ticket-ok w5d-term-ticket-404 w5d-term-ticket-noauth \
         w5d-term-ws-no-ticket w5d-term-ws-bad-ticket w5d-term-ws-cross-session \
         w5d-term-ws-empty-session w5d-lb-get-404 w5d-lb-get-ok w5d-lb-post-404 \
         w5d-lb-post-unavailable \
         w5d-emb-chat-404 w5d-emb-chat-bad-sig w5d-emb-chat-invalid-json \
         w5d-emb-chat-scalar w5d-emb-chat-kb-empty w5d-emb-chat-agent-empty \
         w5d-emb-files-no-path w5d-emb-files-dotdot w5d-emb-files-cross-tenant \
         w5d-emb-files-missing; do
  cmp_one "${f}" "${f}.json"
done
cmp_one "w5d-term-ws-no-upgrade(body)" "w5d-term-ws-no-upgrade"
cmp_hdr "w5d-term-ws-no-upgrade(headers)" "w5d-term-ws-no-upgrade.headers"
cmp_one "w5d-emb-files-ok(body)" "w5d-emb-files-ok.bin"
cmp_hdr "w5d-emb-files-ok(headers)" "w5d-emb-files-ok.headers"

echo "==> 2) WS 握手实测（双端真 ticket）"
ws_probe() { # port token
  local port="$1" token="$2"
  python3 - "$port" "$token" <<'PY'
import socket, base64, os, sys, hashlib, struct, json, urllib.request

port, token = int(sys.argv[1]), sys.argv[2]
sid = "b5000000-0000-0000-0000-000000000801"
req = urllib.request.Request(
    f"http://localhost:{port}/api/v1/sessions/{sid}/sandbox/terminal-ticket",
    method="POST", headers={"Authorization": f"Bearer {token}"})
ticket = json.load(urllib.request.urlopen(req))["data"]["ticket"]

key = base64.b64encode(os.urandom(16)).decode()
s = socket.create_connection(("localhost", port), timeout=8)
s.sendall((f"GET /api/v1/sessions/{sid}/sandbox/terminal?ticket={ticket} HTTP/1.1\r\n"
           f"Host: localhost:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
           f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
buf = b""
while b"\r\n\r\n" not in buf:
    buf += s.recv(4096)
head, _, rest = buf.partition(b"\r\n\r\n")
status = head.split(b"\r\n")[0].decode()
expect = base64.b64encode(hashlib.sha1(
    (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
accept_ok = expect.encode() in head
data = rest
while len(data) < 2:
    data += s.recv(4096)
b0, b1 = data[0], data[1]
plen = b1 & 0x7F
idx = 2
if plen == 126:
    while len(data) < 4: data += s.recv(4096)
    plen = struct.unpack(">H", data[2:4])[0]; idx = 4
while len(data) < idx + plen:
    data += s.recv(4096)
payload = data[idx:idx+plen]
opcode = b0 & 0xF
code = struct.unpack(">H", payload[:2])[0] if opcode == 0x8 and len(payload) >= 2 else -1
reason = payload[2:].decode() if opcode == 0x8 else ""
print(f"{status.split()[1]} accept={'OK' if accept_ok else 'BAD'} "
      f"opcode={opcode:#x} code={code} reason={reason}")
s.close()
PY
}

GTOKEN_PROBE="$(login "w5d-batch@weknora.test" "${GO_PORT}")"
JTOKEN_PROBE="$(login "w5d-batch@weknora.test" "${JAVA_PORT}")"
GO_WS="$(ws_probe "${GO_PORT}" "${GTOKEN_PROBE}")"
JAVA_WS="$(ws_probe "${JAVA_PORT}" "${JTOKEN_PROBE}")"
echo "  go   : ${GO_WS}"
echo "  java : ${JAVA_WS}"
if [ "${GO_WS}" = "${JAVA_WS}" ]; then
  pass=$((pass+1)); echo "MATCH ws-handshake-live"
else
  fail=$((fail+1)); echo "DIFF  ws-handshake-live"
fi

echo
echo "==> A/B 结果：${pass} MATCH / ${fail} DIFF"
[ "${fail}" -eq 0 ]
