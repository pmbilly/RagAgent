#!/usr/bin/env python3
"""脚本化 OpenAI 兼容 stub LLM（波 4.6d 双端同指的全链路 A/B 用）。

- POST /v1/chat/completions（stream 或非 stream 均支持）
- 按请求末条 user 消息里的「场景标记」选脚本（缺省 fixed）：
    <<SCENARIO:chat>>   → 分片 "你好，" + "我是知识助手。" + done（usage 固定）
    <<SCENARIO:echo>>   → 原样回显末条 user 消息（单分片）
  分片序列与 usage 数字恒定，双端同 stub 即可逐字节对拍。
- 幂等：无状态，无落盘。
"""
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

USAGE = {"prompt_tokens": 12, "completion_tokens": 9, "total_tokens": 21}
ID = "chatcmpl-stub46d"
CREATED = 1735689600  # 固定（2025-01-01），A/B 掩码兜底

SCENARIOS = {
    "chat": ["你好，", "我是知识助手。"],
    "long": ["第一段。", "第二段，", "第三段内容较长一些，用于观察分片重组与扣留缓冲。"],
    "think": ["<think>内部思考</think>最终答案：42。"],
}


def pick_scenario(messages):
    for m in reversed(messages or []):
        if m.get("role") == "user":
            content = m.get("content") or ""
            if "<<SCENARIO:" in content:
                return content.split("<<SCENARIO:")[1].split(">>")[0]
    return "chat"


def last_user(messages):
    for m in reversed(messages or []):
        if m.get("role") == "user":
            return m.get("content") or ""
    return ""


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 安静
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")
        if self.path.endswith("/chat/completions"):
            return self.chat(body)
        self.send_error(404)

    def chat(self, body):
        stream = bool(body.get("stream"))
        scenario = pick_scenario(body.get("messages"))
        if scenario == "echo":
            chunks = [last_user(body.get("messages"))]
        else:
            chunks = SCENARIOS.get(scenario, SCENARIOS["chat"])
        model = body.get("model") or "stub-model"
        if not stream:
            content = "".join(chunks)
            self._json({
                "id": ID, "object": "chat.completion", "created": CREATED, "model": model,
                "choices": [{"index": 0, "message": {"role": "assistant", "content": content},
                             "finish_reason": "stop"}],
                "usage": USAGE,
            })
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        for i, piece in enumerate(chunks):
            delta = {"role": "assistant"} if i == 0 else {}
            if piece:
                delta["content"] = piece
            done = i == len(chunks) - 1
            obj = {"id": ID, "object": "chat.completion.chunk", "created": CREATED,
                   "model": model,
                   "choices": [{"index": 0, "delta": delta,
                                "finish_reason": "stop" if done else None}],
                   }
            if done:
                obj["usage"] = USAGE
            self._sse(obj)
        self._sse_done()
        self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()

    def _sse(self, obj):
        data = json.dumps(obj, ensure_ascii=False, separators=(",", ":"))
        payload = f"data: {data}\n\n".encode()
        self.wfile.write(hex(len(payload))[2:].encode() + b"\r\n" + payload + b"\r\n")
        self.wfile.flush()

    def _sse_done(self):
        payload = b"data: [DONE]\n\n"
        self.wfile.write(hex(len(payload))[2:].encode() + b"\r\n" + payload + b"\r\n")
        self.wfile.flush()

    def _json(self, obj, status=200):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    import sys
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8181
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
