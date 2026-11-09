"""Loopback HTTP API and background analysis worker."""

import argparse
import json
import logging
import re
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

from .analysis import inspect_video, manual_review
from .store import Problem, Store

API = "/v1/matches/"
MAX_JSON = 16 * 1024


def select_range(value, size):
    if value is None:
        return 0, size - 1
    match = re.fullmatch(r"bytes=([0-9]{0,20})-([0-9]{0,20})", value)
    if not match:
        raise ValueError()
    first, last = match.groups()
    if first:
        start, end = int(first), min(int(last), size - 1) if last else size - 1
    elif last and int(last):
        start, end = max(size - int(last), 0), size - 1
    else:
        raise ValueError()
    if not 0 <= start <= end:
        raise ValueError()
    return start, end


class Server(ThreadingHTTPServer):
    def __init__(self, address, store, inspect=inspect_video, analyze=manual_review):
        super().__init__(address, Handler)
        self.store = store
        self.inspect = inspect
        self.analyze = analyze
        self.stop_worker = threading.Event()
        self.worker = threading.Thread(target=self.work, daemon=True)
        self.worker.start()

    def work(self):
        while not self.stop_worker.is_set():
            match_id = self.store.claim()
            if match_id is None:
                self.stop_worker.wait(0.5)
                continue
            try:
                path = self.store.path(match_id)
                video = self.inspect(path)
                self.store.publish_analysis(match_id, video, self.analyze(path, video))
            except ValueError as error:
                self.store.fail(match_id, str(error))
            except Exception:
                logging.exception("Analysis failed for %s", match_id)
                self.store.fail(match_id, "Analiz sırasında beklenmedik hata oluştu.")

    def server_close(self):
        self.stop_worker.set()
        self.worker.join()
        super().server_close()


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        self.request.settimeout(45)
        super().setup()

    def do_GET(self):
        self.dispatch()

    def do_HEAD(self):
        self.dispatch()

    def do_PUT(self):
        self.dispatch()

    def do_POST(self):
        self.dispatch()

    def dispatch(self):
        try:
            self.route()
        except Problem as error:
            self.send_json({"error": str(error)}, error.status)
        except (ConnectionError, socket.timeout):
            self.close_connection = True

    def route(self):
        path = urlsplit(self.path).path
        method = self.command
        store = self.server.store
        if path == "/health" and method == "GET":
            self.send_json({"status": "ok"})
            return
        if not path.startswith(API):
            raise Problem(404, "Adres bulunamadı.")
        match_id, *parts = path[len(API):].split("/")
        if not parts and method == "GET":
            result = store.snapshot(match_id)
        elif not parts and method == "PUT":
            result = store.create(match_id, self.read_json())
        elif parts == ["video"] and method == "PUT":
            result = store.upload(match_id, self.rfile, self.length(), self.headers.get("X-Content-SHA256", ""))
        elif parts == ["video"] and method in ("GET", "HEAD"):
            self.send_video(match_id)
            return
        elif parts == ["analysis", "retry"] and method == "POST":
            result = store.retry(match_id)
        elif len(parts) == 2 and parts[0] == "events" and method == "PUT":
            result = store.save_event(match_id, parts[1], self.read_json())
        elif parts == ["review", "complete"] and method == "POST":
            result = store.complete(match_id)
        else:
            raise Problem(404, "Adres bulunamadı.")
        self.send_json(result)

    def length(self):
        value = self.headers.get("Content-Length", "")
        if self.headers.get("Transfer-Encoding") or not re.fullmatch(r"[0-9]{1,16}", value):
            raise Problem(411, "Geçerli içerik uzunluğu gerekli.")
        return int(value)

    def read_json(self):
        length = self.length()
        if length > MAX_JSON:
            raise Problem(413, "İstek çok büyük.")
        try:
            data = self.rfile.read(length)
            if len(data) != length:
                raise ValueError()
            result = json.loads(data)
            if isinstance(result, dict):
                return result
        except (ValueError, RecursionError):
            pass
        raise Problem(400, "Geçerli bir JSON nesnesi gerekli.")

    def send_json(self, result, status=200):
        body = json.dumps(result, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def send_video(self, match_id):
        store = self.server.store
        with store.connect() as db:
            match = store.match(db, match_id)
        if not match["video"]:
            raise Problem(409, "Video henüz oynatmaya hazır değil.")
        size = match["size"]
        requested = self.headers.get("Range")
        try:
            start, end = select_range(requested, size)
        except ValueError:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{size}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        with store.path(match_id).open("rb") as file:
            self.send_response(206 if requested else 200)
            self.send_header("Content-Type", match["video"]["mime"])
            self.send_header("Accept-Ranges", "bytes")
            remaining = end - start + 1
            self.send_header("Content-Length", str(remaining))
            if requested:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            if self.command == "HEAD":
                return
            file.seek(start)
            while remaining:
                data = file.read(min(remaining, 1024 * 1024))
                if not data:
                    break
                self.wfile.write(data)
                remaining -= len(data)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", default=8080, type=int)
    parser.add_argument("--data", default=Path("data"), type=Path)
    args = parser.parse_args()
    # This development service is intentionally local and has no account system.
    with Server(("127.0.0.1", args.port), Store(args.data)) as server:
        print(f"VolleyStat API http://127.0.0.1:{server.server_port}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
