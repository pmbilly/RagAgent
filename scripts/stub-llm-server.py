#!/usr/bin/env python3
"""脚本化 OpenAI 兼容 stub LLM（波 4.6d 双端同指的全链路 A/B 用；W5b 扩展）。

- POST /v1/chat/completions（stream 或非 stream 均支持）
- 按请求末条 user 消息里的「场景标记」选脚本（缺省 fixed）：
    <<SCENARIO:chat>>   → 分片 "你好，" + "我是知识助手。" + done（usage 固定）
    <<SCENARIO:echo>>   → 原样回显末条 user 消息（单分片）
  分片序列与 usage 数字恒定，双端同 stub 即可逐字节对拍。
- W5b 扩展（non-stream 面）：
    <<SCENARIO:graph>>（或 user 消息含该标记） → 带围栏的抽取 JSON（graph 场景）
    user 消息含 "Please randomly generate a text"        → fabri-text 固定示例文本
    其余非流式 chat                                       → "stub-chat-reply"
- W5b 扩展（新端点，兼容既有 chat 场景不动）：
    POST /embeddings            → 固定 3 维向量 [0.1, 0.2, 0.3]
    POST /rerank                → 固定 1 个结果（relevance_score 0.99）
    POST */audio/transcriptions → 按 multipart 里的 model 字段路由场景：
        缺 Authorization / "Bearer "   → 401 invalid api key（fillSecrets 探针）
        asr-401                        → 401
        asr-404                        → 404
        asr-modelmissing               → 400 "model stub-model not found"
        asr-500text                    → 500 纯文本 "boom"
        其余                           → 200 verbose_json（text=stub-transcript）
- E2E 观察项扩展（2026-09-25，W5γ5.10）：
  <<SCENARIO:early-error>> → 200 + **首帧即 error 对象**，随即关流（不发 [DONE]）
  <<SCENARIO:early-close>> → 200 + **零帧**直接关流（"只截断"形态）
  环境变量 STUB_RECORD_DIR=<dir> → 每个 chat 请求落盘 <ns>.json（请求体原文）+ .meta（path），
  用于 E2E 看出站 tools 段/消息（默认不落盘，行为不变）。
- 幂等：无状态；除非设了 STUB_RECORD_DIR，否则无落盘。
"""
import json
import os
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

USAGE = {"prompt_tokens": 12, "completion_tokens": 9, "total_tokens": 21}
ID = "chatcmpl-stub46d"
CREATED = 1735689600  # 固定（2025-01-01），A/B 掩码兜底

RECORD_DIR = os.environ.get("STUB_RECORD_DIR") or ""


def record_request(path, body):
    """STUB_RECORD_DIR 设了就把请求体落盘：<ns>-<seq>.json（原文）+ 同名 .meta（path）。

    用途：E2E 要看出站请求的 `tools` 段（观察项 2）与 messages，而服务端日志未必可得。
    不设环境变量则完全无副作用（保持 stub 的幂等无落盘默认）。
    """
    if not RECORD_DIR:
        return
    try:
        os.makedirs(RECORD_DIR, exist_ok=True)
        stamp = time.time_ns()
        with open(os.path.join(RECORD_DIR, f"{stamp}.json"), "w", encoding="utf-8") as f:
            json.dump(body, f, ensure_ascii=False)
        with open(os.path.join(RECORD_DIR, f"{stamp}.meta"), "w", encoding="utf-8") as f:
            f.write(path + "\n")
    except Exception:
        pass  # 录制失败不影响 stub 行为


SCENARIOS = {
    "chat": ["你好，", "我是知识助手。"],
    "long": ["第一段。", "第二段，", "第三段内容较长一些，用于观察分片重组与扣留缓冲。"],
    "think": ["<think>内部思考</think>最终答案：42。"],
}

GRAPH_REPLY = "```json\n[\n" \
    "  {\"entity\": \"张三\", \"entity_attributes\": [\"研究员\"], \"chunks\": [\"c1\"]},\n" \
    "  {\"entity\": \"李四\", \"entity_attributes\": []},\n" \
    "  {\"entity1\": \"张三\", \"entity2\": \"李四\", \"relation\": \"Author\"},\n" \
    "  {\"entity1\": \"张三\", \"entity2\": \"王五\", \"relation\": \"Unknown\"}\n" \
    "]\n```"

FABRI_REPLY = "这是一段由 stub 生成的示例文本，用于 fabri-text 契约测试。"


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
        body = self.rfile.read(length) or b"{}"
        # 可选：请求落盘（STUB_DUMP_DIR=<dir> 时生效，默认关闭）——A/B 对拍
        # LLM 请求体（如 tools 数组）时用。文件名 = 序号-端点。
        dump_dir = os.environ.get("STUB_DUMP_DIR")
        if dump_dir:
            try:
                os.makedirs(dump_dir, exist_ok=True)
                Handler._dump_seq = getattr(Handler, "_dump_seq", 0) + 1
                name = "%04d-%s.json" % (Handler._dump_seq,
                                         self.path.strip("/").replace("/", "_"))
                with open(os.path.join(dump_dir, name), "wb") as fh:
                    fh.write(body)
            except OSError:
                pass
        if self.path.endswith("/chat/completions"):
            return self.chat(json.loads(body or b"{}"))
        if self.path.endswith("/embeddings"):
            return self._json({
                "object": "list",
                "data": [{"object": "embedding", "index": 0,
                          "embedding": [0.1, 0.2, 0.3]}],
                "model": "stub-model",
                "usage": {"prompt_tokens": 2, "total_tokens": 2},
            })
        if self.path.endswith("/rerank"):
            return self._json({"results": [{"index": 0, "relevance_score": 0.99}],
                               "model": "stub-model"})
        if self.path.endswith("/audio/transcriptions"):
            return self.audio(body)
        self.send_error(404)

    def audio(self, body):
        ctype = self.headers.get("Content-Type") or ""
        model = ""
        if "boundary=" in ctype:
            boundary = ctype.split("boundary=")[1].strip().strip('"')
            text = body.decode("latin-1")
            for part in text.split("--" + boundary):
                m = re.search(r'name="model"\r\n\r\n(.*)\r\n', part)
                if m:
                    model = m.group(1)
                    break
        auth = self.headers.get("Authorization") or ""
        if not auth.strip() or auth.strip() == "Bearer":
            # Go 的 "Bearer " + "" == "Bearer"（带尾随空格），都视为未带 key
            return self._json(
                {"error": {"message": "invalid api key", "type": "invalid_request_error"}},
                status=401)
        if model == "asr-401":
            return self._json(
                {"error": {"message": "invalid api key", "type": "invalid_request_error"}},
                status=401)
        if model == "asr-404":
            return self._json({"error": {"message": "Not Found"}}, status=404)
        if model == "asr-modelmissing":
            return self._json({"error": {"message": "model stub-model not found"}}, status=400)
        if model == "asr-500text":
            return self._raw(500, "text/plain", b"boom")
        return self._json({"text": "stub-transcript", "segments": [
            {"start": 0.0, "end": 1.5, "text": " stub-transcript "}]})

    def chat(self, body):
        stream = bool(body.get("stream"))
        scenario = pick_scenario(body.get("messages"))
        user_text = last_user(body.get("messages"))
        record_request(self.path, body)
        if scenario in ("early-error", "early-close"):
            # E2E 观察项用：上游在**流早期**出问题
            #   early-error → 200 + 首帧即 error 对象，随即关流（不发 [DONE]）
            #   early-close → 200 + 零帧直接关流（"只截断"形态）
            if not stream:
                return self._json(
                    {"error": {"message": "stub early failure", "type": "server_error"}},
                    status=502)
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            if scenario == "early-error":
                self._sse({"error": {"message": "stub early failure",
                                     "type": "server_error",
                                     "code": "stub_early_error"}})
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
            return
        if scenario == "echo":
            chunks = [user_text]
        elif "<<SCENARIO:graph>>" in user_text:
            chunks = [GRAPH_REPLY]
        elif "Please randomly generate a text" in user_text:
            chunks = [FABRI_REPLY]
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

    def _raw(self, status, ctype, payload):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


if __name__ == "__main__":
    import sys
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8181
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
