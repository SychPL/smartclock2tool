"""LAN file-exchange server for the Clock ADB Probe research bench.

GET  /<name>          -> serves files from dist/ (APKs, plugins, binaries)
PUT/POST /upload/<name> -> stores the body into uploads/<name> (dropped files)

No auth beyond the LAN itself; filenames are sanitized to a safe basename.
"""
import http.server
import os
import re
import socketserver

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "dist")
UPLOADS = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "uploads")
SAFE = re.compile(r"[A-Za-z0-9._-]+")
PORT = 8000


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def _safe_name(self, raw):
        base = os.path.basename(raw.replace("\\", "/"))
        if not SAFE.fullmatch(base) or base.startswith("."):
            return None
        return base

    def do_GET(self):
        if self.path.startswith("/raw/"):
            name = self._safe_name(self.path.rsplit("/", 1)[-1])
            if not name:
                self.send_error(404)
                return
            path = os.path.join(ROOT, name)
            try:
                with open(path, "rb") as handle:
                    self.wfile.write(b"HTTP/1.0 200 OK\r\n\r\n")
                    while True:
                        chunk = handle.read(65536)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
            except OSError:
                self.send_error(404)
            return
        return super().do_GET()

    def do_PUT(self):
        self._store()

    def do_POST(self):
        self._store()

    def _store(self):
        name = self._safe_name(self.path.rsplit("/", 1)[-1])
        if not name:
            self.send_error(400, "bad name")
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.send_error(400, "bad length")
            return
        if length > 512 * 1024 * 1024:
            self.send_error(413, "too large")
            return
        os.makedirs(UPLOADS, exist_ok=True)
        target = os.path.join(UPLOADS, name)
        remaining = length
        with open(target + ".part", "wb") as out:
            while remaining > 0:
                chunk = self.rfile.read(min(65536, remaining))
                if not chunk:
                    break
                out.write(chunk)
                remaining -= len(chunk)
        os.replace(target + ".part", target)
        self.send_response(201)
        self.send_header("Content-Type", "text/plain")
        body = ("stored %s (%d bytes)\n" % (name, length - remaining)).encode()
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        print("[filedrop] %s - %s" % (self.address_string(), fmt % args), flush=True)


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


if __name__ == "__main__":
    os.makedirs(ROOT, exist_ok=True)
    os.makedirs(UPLOADS, exist_ok=True)
    print("filedrop on 0.0.0.0:%d  GET->%s  PUT->%s" % (PORT, ROOT, UPLOADS), flush=True)
    Server(("0.0.0.0", PORT), Handler).serve_forever()
