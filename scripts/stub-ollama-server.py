#!/usr/bin/env python3
"""脚本化 Ollama 管理 API stub（W5b 双端同打）。

与 Java 侧契约测试的 in-JVM stub（W5bStubServers.startOllama）同一份剧本：
- HEAD /              → 200（探活）
- GET  /api/version   → {"version":"w5b-stub"}
- GET  /api/tags      → 固定模型列表（stub-model:latest / size 4700000000 /
                        digest sha256:w5bdigest / modified_at 2026-09-01T08:00:00Z）
- POST /api/pull      → NDJSON 进度（pulling manifest → downloading 60/100 →
                        downloading 100/100 → success）

用法：python3 scripts/stub-ollama-server.py 8182
（Go 录制/AB 时 OLLAMA_BASE_URL=http://127.0.0.1:8182；Java 契约测试不走本脚本，
 它的 bean 缺省基址是 localhost:11434，用 JVM 内 stub。）
"""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TAGS = {"models": [{
    "name": "stub-model:latest", "model": "stub-model:latest",
    "size": 4700000000, "digest": "sha256:w5bdigest",
    "modified_at": "2026-09-01T08:00:00Z", "details": {},
}]}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def do_HEAD(self):
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        if self.path == "/api/version":
            return self._json({"version": "w5b-stub"})
        if self.path == "/api/tags":
            return self._json(TAGS)
        self.send_error(404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        if self.path == "/api/pull":
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            for line in [
                json.dumps({"status": "pulling manifest"}),
                json.dumps({"status": "downloading", "digest": "sha256:w5b",
                            "total": 100, "completed": 60}),
                json.dumps({"status": "downloading", "digest": "sha256:w5b",
                            "total": 100, "completed": 100}),
                json.dumps({"status": "success"}),
            ]:
                payload = (line + "\n").encode()
                self.wfile.write(hex(len(payload))[2:].encode() + b"\r\n"
                                 + payload + b"\r\n")
                self.wfile.flush()
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
            return
        self.send_error(404)

    def _json(self, obj):
        data = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    import sys
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8182
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
