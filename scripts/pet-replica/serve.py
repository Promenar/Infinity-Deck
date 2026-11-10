#!/usr/bin/env python3
"""本地静态服务 + 截图保存端点。

用法：python3 serve.py <根目录> [端口]
- GET：按根目录提供静态文件，关闭缓存，便于反复刷新模型。
- POST /save?name=<相对路径>：请求体为 PNG 的 data URL 或原始字节，写入 <根目录>/<相对路径>。
只监听 127.0.0.1。
"""
import base64, os, sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

ROOT = os.path.abspath(sys.argv[1])
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8765


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=ROOT, **k)

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def do_POST(self):
        u = urlparse(self.path)
        if u.path != "/save":
            self.send_error(404); return
        name = parse_qs(u.query).get("name", [""])[0]
        dest = os.path.abspath(os.path.join(ROOT, name))
        if not name or not dest.startswith(ROOT + os.sep):
            self.send_error(400, "bad name"); return
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if body.startswith(b"data:"):
            body = base64.b64decode(body.split(b",", 1)[1])
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "wb") as f:
            f.write(body)
        self.send_response(200); self.send_header("Content-Type", "text/plain"); self.end_headers()
        self.wfile.write(f"saved {len(body)}".encode())

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
