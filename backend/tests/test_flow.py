import hashlib
import http.client
import io
import json
import tempfile
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

from backend.analysis import AnalysisResult
from backend.server import Server
from backend.store import Problem, Store

VIDEO = {"duration_ms": 5000, "width": 1280, "height": 720, "codec": "h264", "mime": "video/mp4"}
CLIP = b"example upload bytes for transport tests"


def event(**overrides):
    return {
        "set": 1, "rally": 1, "at_ms": 1000, "team": "home",
        "player": 7, "kind": "attack", "outcome": "kill", **overrides,
    }


def uid():
    return str(uuid4())


def checksum(content):
    return hashlib.sha256(content).hexdigest()


class FlowTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.store = Store(self.directory.name)
        self.match = uid()
        self.base = "/v1/matches/" + self.match
        self.analysis_should_fail = False
        self.server = Server(
            ("127.0.0.1", 0), self.store,
            inspect=self.inspect, analyze=lambda path, video: AnalysisResult("assisted", [event()]),
        )
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.close)
        self.assertEqual(self.request("PUT", payload={"home": "Ev", "away": "Konuk"})[0], 200)

    def close(self):
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()

    def inspect(self, path):
        if self.analysis_should_fail:
            raise ValueError("Temporary inspection failure")
        if path.read_bytes() != CLIP:
            raise ValueError("Invalid clip")
        return VIDEO

    def request(self, method="GET", suffix="", payload=None, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        try:
            if isinstance(payload, dict):
                payload = json.dumps(payload).encode()
            connection.request(method, self.base + suffix, payload, headers or {})
            response = connection.getresponse()
            raw = response.read()
            if "application/json" in (response.getheader("Content-Type") or ""):
                raw = json.loads(raw)
            return response.status, raw, response.headers
        finally:
            connection.close()

    def upload(self, content=CLIP, digest=None):
        return self.request("PUT", "/video", content, {"X-Content-SHA256": digest or checksum(content)})

    def wait_for(self, expected):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            status, data, _ = self.request()
            if status == 200 and data["state"] == expected:
                return data
            time.sleep(0.05)
        self.fail(f"Expected {expected}, got {data}")

    def save(self, identifier, data=None, revision=0):
        body = {"revision": revision, "action": "reject" if data is None else "verify", "event": data}
        return self.request("PUT", "/events/" + identifier, body)

    def test_review_and_statistics(self):
        self.assertEqual(self.upload()[0], 200)
        review = self.wait_for("needs_review")
        proposal = review["events"][0]
        self.assertEqual(review["statistics"]["pending_events"], 1)
        self.assertEqual(review["statistics"]["verified_events"], 0)
        self.assertEqual(self.request("POST", "/review/complete")[0], 409)
        self.assertEqual(self.save(proposal["id"], event(at_ms=5000), 1)[0], 400)
        self.assertEqual(self.save(proposal["id"], event(), 1)[0], 200)
        self.assertEqual(self.save(proposal["id"], event(), 1)[0], 200)
        self.assertEqual(self.save(proposal["id"], event(team="away"), 1)[0], 409)
        self.assertEqual(self.save(uid(), event(outcome="error", player=8))[0], 200)
        self.assertEqual(self.save(uid(), event(team="away", kind="serve", outcome="ace"))[0], 200)
        point = event(team="away", kind="rally", outcome="won", player=None)
        point_id = uid()
        self.assertEqual(self.save(point_id, point)[0], 200)
        self.assertEqual(self.save(uid(), point)[0], 409)
        stats = self.request()[1]["statistics"]
        self.assertEqual(stats["sets"], [{"set": 1, "home": 0, "away": 1}])
        self.assertEqual(stats["teams"]["home"]["totals"]["hitting_percentage"], 0)
        self.assertEqual(stats["teams"]["home"]["players"][0]["kills"], 1)
        self.assertEqual(stats["teams"]["away"]["totals"]["aces"], 1)
        self.assertIsNone(stats["teams"]["away"]["totals"]["hitting_percentage"])
        self.assertEqual(self.request("POST", "/review/complete")[0], 200)
        self.assertEqual(self.wait_for("reviewed")["statistics"]["verified_events"], 4)
        self.assertEqual(self.save(point_id, revision=1)[0], 200)
        self.assertEqual(self.wait_for("needs_review")["statistics"]["sets"], [])
        self.assertEqual(self.save(point_id, point, 2)[0], 409)

    def test_upload_checksum_idempotency_and_playback_ranges(self):
        self.assertEqual(self.upload(digest="0" * 64)[0], 422)
        self.assertEqual(self.request()[1]["state"], "awaiting_upload")
        self.assertEqual(list(self.store.media.iterdir()), [])
        self.assertEqual(self.upload()[0], 200)
        self.assertEqual(self.upload()[0], 200)
        self.assertEqual(self.upload(b"different")[0], 409)
        self.wait_for("needs_review")
        code, data, headers = self.request("GET", "/video", headers={"Range": "bytes=2-5"})
        self.assertEqual((code, data, headers["Content-Range"]), (206, CLIP[2:6], f"bytes 2-5/{len(CLIP)}"))
        self.assertEqual(self.request("GET", "/video", headers={"Range": "bytes=-4"})[1], CLIP[-4:])
        self.assertEqual(self.request("GET", "/video", headers={"Range": "bytes=999-"})[0], 416)
        self.assertEqual(self.request("HEAD", "/video")[2]["Content-Length"], str(len(CLIP)))
        self.assertEqual(self.request("GET", "/video")[1], CLIP)

    def test_analysis_failure_and_retry(self):
        self.analysis_should_fail = True
        self.assertEqual(self.upload()[0], 200)
        self.assertEqual(self.wait_for("failed")["error"], "Temporary inspection failure")
        self.assertEqual(self.request("GET", "/video")[0], 409)
        self.analysis_should_fail = False
        self.assertEqual(self.request("POST", "/analysis/retry")[0], 200)
        self.assertIsNone(self.wait_for("needs_review")["error"])


class PersistenceTest(unittest.TestCase):
    def test_restart_partial_upload_and_concurrent_duplicate(self):
        with tempfile.TemporaryDirectory() as directory:
            store = Store(directory)
            match_id = uid()
            store.create(match_id, {"home": "Ev", "away": "Konuk"})
            with self.assertRaises(Problem):
                store.upload(match_id, io.BytesIO(CLIP[:1]), len(CLIP), checksum(CLIP))
            self.assertEqual(list(store.media.iterdir()), [])
            with ThreadPoolExecutor(max_workers=2) as pool:
                jobs = [
                    pool.submit(store.upload, match_id, io.BytesIO(CLIP), len(CLIP), checksum(CLIP))
                    for _ in range(2)
                ]
                self.assertTrue(all(job.result()["sha256"] == checksum(CLIP) for job in jobs))
            self.assertEqual(store.claim(), match_id)
            self.assertIsNone(store.claim())
            store = Store(directory)
            self.assertEqual(store.claim(), match_id)
            store.publish_analysis(match_id, VIDEO, AnalysisResult("manual_only"))
            self.assertEqual(store.snapshot(match_id)["mode"], "manual_only")
            self.assertEqual(store.path(match_id).read_bytes(), CLIP)


if __name__ == "__main__":
    unittest.main()
