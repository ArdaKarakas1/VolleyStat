"""Shared pieces of the analysis, review and report tests.

The analyzers here are scripted stand-ins: they return what a test hands them. No video content
is analysed and no result of theirs describes a real match.
"""
import sqlite3
import threading
import time
from contextlib import contextmanager

from fastapi.testclient import TestClient

from app.analyzers.base import BASELINE, AnalyzerInfo, RallyProposal
from app.main import create_app
from tests.test_media import store

# Four rally spans inside the one-second synthetic clip.
SPANS = ((100, 250), (300, 450), (500, 650), (700, 850))


def proposals(spans=SPANS):
    return [RallyProposal(start_ms=start, end_ms=end, uncertainty_reasons=("NO_WINNER_MODEL",)) for start, end in spans]


class ScriptedAnalyzer:
    def __init__(self, result=None, error=None, reports=(), info=AnalyzerInfo("scripted", "t1", BASELINE)):
        self.info = info
        self.result = proposals() if result is None else result
        self.error = error
        self.reports = reports
        self.calls = 0

    def analyze(self, video_path, media, report):
        self.calls += 1
        for call in self.reports:
            report(*call)
        if self.error is not None:
            raise self.error
        return self.result


class BlockingAnalyzer:
    """Holds its job in PROCESSING until the test lets go, so cancel, delete and shutdown can be tried mid-run."""

    info = AnalyzerInfo("scripted", "t1", BASELINE)

    def __init__(self, reports=(("DETECTING_RALLIES", None, None, None),), polls=True):
        self.reports = reports
        self.polls = polls
        self.started = threading.Event()
        self.release = threading.Event()
        self.interrupted = threading.Event()

    def analyze(self, video_path, media, report):
        try:
            for call in self.reports:
                report(*call)
            self.started.set()
            if self.polls:
                # A real analyzer reports once per frame; that call is where the worker can stop it.
                while not self.release.wait(0.005):
                    report(*self.reports[-1])
            else:
                assert self.release.wait(10), "the test never released the analyzer"
        except Exception:
            self.interrupted.set()
            raise
        return proposals()


@contextmanager
def serve(storage, analyzer, start_worker=False):
    """A client over `storage`. Without the worker thread, tests step the queue with run_pending(client)."""
    with TestClient(create_app(storage, analyzer=analyzer, start_worker=start_worker)) as client:
        yield client


def run_pending(client):
    return client.app.state.worker.run_pending()


def wait_for(condition, timeout=10.0, interval=0.005):
    """Poll until `condition()` is truthy and return its value; fail the test after `timeout` seconds."""
    deadline = time.monotonic() + timeout
    while True:
        value = condition()
        if value:
            return value
        if time.monotonic() >= deadline:
            raise AssertionError(f"condition was not met within {timeout} s")
        time.sleep(interval)


def wait_for_state(client, job_id, *states):
    return wait_for(lambda: (job := client.get(f"/v1/analyses/{job_id}").json())["state"] in states and job)


def tiny_clip(make_clip):
    """One second of test pattern at 320x180: enough for the routes, and cheap to encode."""
    return make_clip("tiny.mp4", size="320x180")


def ready_video(client, make_clip):
    video = store(client, tiny_clip(make_clip))
    assert video["status"] == "READY"
    return video


def request_job(client, video_id, **headers):
    response = client.post(f"/v1/videos/{video_id}/analyses", headers=headers)
    assert response.status_code == 202, response.text
    return response.json()


def reviewed_job(client, make_clip):
    """Upload the synthetic clip, run the configured analyzer and return (video, job) in NEEDS_REVIEW."""
    video = ready_video(client, make_clip)
    job_id = request_job(client, video["id"])["id"]
    assert run_pending(client) == 1
    job = client.get(f"/v1/analyses/{job_id}").json()
    assert job["state"] == "NEEDS_REVIEW", job
    return video, job


def cards(client, job_id, scope="ALL"):
    return client.get(f"/v1/analyses/{job_id}/review-cards", params={"scope": scope}).json()["cards"]


def card_of(client, job_id, sequence, question_type="RALLY_WINNER"):
    matches = [card for card in cards(client, job_id) if (card["rallySequence"], card["questionType"]) == (sequence, question_type)]
    return matches[0] if matches else None


def decide(client, card, selection, **extra):
    response = client.post(f"/v1/review-cards/{card['id']}/decisions",
                           json={"selection": selection, "expectedVersion": card["version"], **extra})
    assert response.status_code == 200, response.text
    return response.json()


def rows(storage, query, *parameters):
    with sqlite3.connect(storage / "volleystat.sqlite3") as db:
        db.row_factory = sqlite3.Row
        return [dict(row) for row in db.execute(query, parameters)]


def execute(storage, statement, *parameters):
    with sqlite3.connect(storage / "volleystat.sqlite3") as db:
        db.execute(statement, parameters)


def one_match(score):
    """The score of a video that holds one match at most, in the shape of the tests written before
    several matches could share a video: status, setsWon, sets and stoppedAtRally."""
    assert len(score["matches"]) <= 1, score["matches"]
    only = score["matches"][0] if score["matches"] else None
    nothing = {"home": None, "away": None} if score["status"] == "UNKNOWN" else {"home": 0, "away": 0}
    return {"status": score["status"], "setsWon": only["setsWon"] if only else nothing,
            "sets": only["sets"] if only else [], "stoppedAtRally": score["stoppedAtRally"]}


def only_sets(rally_list):
    """The sets of a rally list whose video holds one match at most."""
    assert len(rally_list["matches"]) <= 1, rally_list["matches"]
    return rally_list["matches"][0]["sets"] if rally_list["matches"] else []


def without_statistics(parts):
    """The parts of a report score as the rally list gives them: without the statistics of each part."""
    return [{key: value for key, value in part.items() if key not in ("teams", "players", "unattributed")} for part in parts]
