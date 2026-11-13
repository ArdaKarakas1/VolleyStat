"""Analysis jobs and their worker (docs/API_V1.md, section 3).

Most tests step the queue in the test thread through run_pending(). The tests that need a run to
be in progress start the real worker thread and wait on events or on a bounded poll, never on a
fixed delay.
"""
import re
import sqlite3
import threading
import time

import pytest
from fastapi.testclient import TestClient

from app import jobs
from app.analyzers.base import BASELINE, MOCK, MODEL, AnalyzerError, AnalyzerInfo, RallyProposal
from app.main import create_app
from tests.helpers import (
    SPANS, BlockingAnalyzer, ScriptedAnalyzer, cards, execute, ready_video, request_job, reviewed_job, rows,
    run_pending, serve, wait_for, wait_for_state,
)
from tests.test_media import store

TIMESTAMP = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z")
SCRIPTED = {"id": "scripted", "version": "t1", "kind": "BASELINE"}
STAGE_ONLY = (("DETECTING_RALLIES", None, None, None),)


def get_job(client, job_id):
    response = client.get(f"/v1/analyses/{job_id}")
    assert response.status_code == 200, response.text
    return response.json()


def run_one(client, make_clip):
    """Queue one job for a fresh video, run it in this thread and return the finished job."""
    video = ready_video(client, make_clip)
    job = request_job(client, video["id"])
    run_pending(client)
    return get_job(client, job["id"])


def stored_rows(storage):
    return {table: len(rows(storage, f"SELECT 1 FROM {table}")) for table in ("rallies", "review_cards")}


def worker_threads():
    return [thread for thread in threading.enumerate() if thread.name == "volleystat-analysis-worker"]


# --- requesting and reading a job ------------------------------------------------------------

def test_a_requested_analysis_is_queued_and_can_be_read(storage, make_clip):
    with serve(storage, ScriptedAnalyzer()) as client:
        video = ready_video(client, make_clip)
        accepted = client.post(f"/v1/videos/{video['id']}/analyses")
        assert accepted.status_code == 202
        job = accepted.json()
        assert job == {
            "id": job["id"], "videoId": video["id"], "matchId": video["matchId"],
            "state": "QUEUED", "stage": None, "progress": None,
            "analyzer": SCRIPTED, "mock": False, "failure": None,
            "counts": {"rallies": 0, "reviewOpen": 0, "reviewTotal": 0},
            "createdAt": job["createdAt"], "startedAt": None, "finishedAt": None,
        }
        assert TIMESTAMP.fullmatch(job["createdAt"])
        assert get_job(client, job["id"]) == job
        assert client.get(f"/v1/videos/{video['id']}/analyses/latest").json() == job
        # Nothing was analysed yet, so there is nothing to review.
        assert client.get(f"/v1/analyses/{job['id']}/rallies").json() == {"rallies": []}
        assert client.get(f"/v1/analyses/{job['id']}/review-cards").json() == {"cards": [], "position": {"answered": 0, "total": 0}}


def test_a_finished_run_waits_for_review(storage, make_clip):
    analyzer = ScriptedAnalyzer()
    with serve(storage, analyzer) as client:
        job = run_one(client, make_clip)
        assert (job["state"], job["stage"], job["progress"], job["failure"]) == ("NEEDS_REVIEW", None, None, None)
        assert job["counts"] == {"rallies": len(SPANS), "reviewOpen": len(SPANS), "reviewTotal": len(SPANS)}
        assert TIMESTAMP.fullmatch(job["startedAt"]) and TIMESTAMP.fullmatch(job["finishedAt"])
        assert analyzer.calls == 1
        assert run_pending(client) == 0 and analyzer.calls == 1


def test_unknown_ids_answer_404(storage, make_clip):
    with serve(storage, ScriptedAnalyzer()) as client:
        video = ready_video(client, make_clip)
        for response in (client.get("/v1/analyses/unknown"), client.post("/v1/analyses/unknown/cancel"),
                         client.get("/v1/analyses/unknown/rallies"), client.get("/v1/analyses/unknown/review-cards")):
            assert response.status_code == 404 and response.json()["error"]["code"] == "ANALYSIS_NOT_FOUND"
        no_job = client.get(f"/v1/videos/{video['id']}/analyses/latest")
        assert no_job.status_code == 404 and no_job.json()["error"]["code"] == "ANALYSIS_NOT_FOUND"
        for response in (client.get("/v1/videos/unknown/analyses/latest"), client.post("/v1/videos/unknown/analyses")):
            assert response.status_code == 404 and response.json()["error"]["code"] == "VIDEO_NOT_FOUND"


def test_a_mock_analyzer_flags_its_job(storage, make_clip):
    with serve(storage, ScriptedAnalyzer(info=AnalyzerInfo("pretend", "t1", MOCK))) as client:
        video = ready_video(client, make_clip)
        queued = request_job(client, video["id"])
        assert queued["mock"] is True and queued["analyzer"]["kind"] == "MOCK"
        run_pending(client)
        assert get_job(client, queued["id"])["mock"] is True


# --- one active job per video, idempotency ---------------------------------------------------

def test_a_video_has_one_pending_job_at_a_time(storage, make_clip):
    with serve(storage, ScriptedAnalyzer()) as client:
        video = ready_video(client, make_clip)
        first = request_job(client, video["id"])
        again = client.post(f"/v1/videos/{video['id']}/analyses")
        assert again.status_code == 200 and again.json() == first
        # Another key does not open a second job next to a pending one.
        other_key = client.post(f"/v1/videos/{video['id']}/analyses", headers={"Idempotency-Key": "other"})
        assert other_key.status_code == 200 and other_key.json()["id"] == first["id"]
        assert len(rows(storage, "SELECT 1 FROM analysis_jobs")) == 1

        run_pending(client)
        # The run has ended; a new request is a new analysis of the same video.
        second = client.post(f"/v1/videos/{video['id']}/analyses")
        assert second.status_code == 202 and second.json()["id"] != first["id"]
        assert client.get(f"/v1/videos/{video['id']}/analyses/latest").json()["id"] == second.json()["id"]
        assert get_job(client, first["id"])["state"] == "NEEDS_REVIEW"


def test_the_database_refuses_a_second_pending_job_for_a_video(storage, make_clip):
    with serve(storage, ScriptedAnalyzer()) as client:
        video = ready_video(client, make_clip)
        request_job(client, video["id"])
        with pytest.raises(sqlite3.IntegrityError):
            execute(storage, "INSERT INTO analysis_jobs(id, video_id, match_id, state, analyzer_id, analyzer_version,"
                             " analyzer_kind, created_at) VALUES('second', ?, ?, 'PROCESSING', 'a', '1', 'BASELINE', 'now')",
                    video["id"], video["matchId"])


def test_a_request_while_the_job_is_processing_returns_that_job(storage, make_clip):
    analyzer = BlockingAnalyzer()
    with serve(storage, analyzer, start_worker=True) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        assert analyzer.started.wait(10)
        again = client.post(f"/v1/videos/{video['id']}/analyses")
        assert again.status_code == 200
        assert (again.json()["id"], again.json()["state"]) == (job["id"], "PROCESSING")
        analyzer.release.set()
        wait_for_state(client, job["id"], "NEEDS_REVIEW")


def test_the_same_idempotency_key_returns_the_same_job(storage, make_clip):
    with serve(storage, ScriptedAnalyzer()) as client:
        video = ready_video(client, make_clip)
        other_video = ready_video(client, make_clip)
        first = request_job(client, video["id"], **{"Idempotency-Key": "tap-1"})
        run_pending(client)
        # The retry arrives after the run ended: it still gets its job, in its current state.
        replay = client.post(f"/v1/videos/{video['id']}/analyses", headers={"Idempotency-Key": "tap-1"})
        assert replay.status_code == 200
        assert (replay.json()["id"], replay.json()["state"]) == (first["id"], "NEEDS_REVIEW")
        fresh = client.post(f"/v1/videos/{video['id']}/analyses", headers={"Idempotency-Key": "tap-2"})
        assert fresh.status_code == 202 and fresh.json()["id"] != first["id"]
        # A key belongs to one video.
        elsewhere = client.post(f"/v1/videos/{other_video['id']}/analyses", headers={"Idempotency-Key": "tap-1"})
        assert elsewhere.status_code == 202 and elsewhere.json()["videoId"] == other_video["id"]
        assert len(rows(storage, "SELECT 1 FROM analysis_jobs")) == 3


def test_a_key_answered_with_the_pending_job_returns_that_job_after_the_run_ended(storage, make_clip):
    with serve(storage, ScriptedAnalyzer()) as client:
        video = ready_video(client, make_clip)
        first = request_job(client, video["id"], **{"Idempotency-Key": "tap-1"})
        joined = client.post(f"/v1/videos/{video['id']}/analyses", headers={"Idempotency-Key": "tap-2"})
        assert joined.status_code == 200 and joined.json()["id"] == first["id"]
        run_pending(client)
        # The retry of the second request must not start a second analysis of the same video.
        for key in ("tap-2", "tap-1"):
            retry = client.post(f"/v1/videos/{video['id']}/analyses", headers={"Idempotency-Key": key})
            assert retry.status_code == 200
            assert (retry.json()["id"], retry.json()["state"]) == (first["id"], "NEEDS_REVIEW")
        assert len(rows(storage, "SELECT 1 FROM analysis_jobs")) == 1
        # A key that never met this job is a new request.
        fresh = client.post(f"/v1/videos/{video['id']}/analyses", headers={"Idempotency-Key": "tap-3"})
        assert fresh.status_code == 202 and fresh.json()["id"] != first["id"]


def test_a_key_keeps_its_job_after_the_job_was_cancelled(storage, make_clip):
    with serve(storage, ScriptedAnalyzer()) as client:
        video = ready_video(client, make_clip)
        first = request_job(client, video["id"], **{"Idempotency-Key": "tap-1"})
        client.post(f"/v1/videos/{video['id']}/analyses", headers={"Idempotency-Key": "tap-2"})
        assert client.post(f"/v1/analyses/{first['id']}/cancel").status_code == 200
        for key in ("tap-1", "tap-2"):
            retry = client.post(f"/v1/videos/{video['id']}/analyses", headers={"Idempotency-Key": key})
            assert retry.status_code == 200
            assert (retry.json()["id"], retry.json()["state"]) == (first["id"], "CANCELLED")
        assert len(rows(storage, "SELECT 1 FROM analysis_jobs")) == 1


def test_the_keys_of_a_job_leave_with_its_video(storage, make_clip):
    with serve(storage, ScriptedAnalyzer()) as client:
        video = ready_video(client, make_clip)
        request_job(client, video["id"], **{"Idempotency-Key": "tap-1"})
        client.post(f"/v1/videos/{video['id']}/analyses", headers={"Idempotency-Key": "tap-2"})
        assert len(rows(storage, "SELECT 1 FROM analysis_request_keys")) == 2
        assert client.delete(f"/v1/videos/{video['id']}").status_code == 204
        assert rows(storage, "SELECT 1 FROM analysis_request_keys") == []


def test_a_key_stored_by_the_previous_schema_still_returns_its_job(storage, make_clip):
    with serve(storage, ScriptedAnalyzer()) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"], **{"Idempotency-Key": "tap-1"})
        run_pending(client)
    # Until this table existed, the key was a column of the job it had created.
    execute(storage, "DELETE FROM analysis_request_keys")
    execute(storage, "ALTER TABLE analysis_jobs ADD COLUMN idempotency_key TEXT")
    execute(storage, "UPDATE analysis_jobs SET idempotency_key='tap-1'")
    with serve(storage, ScriptedAnalyzer()) as client:
        retry = client.post(f"/v1/videos/{video['id']}/analyses", headers={"Idempotency-Key": "tap-1"})
        assert retry.status_code == 200 and retry.json()["id"] == job["id"]
        assert len(rows(storage, "SELECT 1 FROM analysis_jobs")) == 1


def test_an_idempotency_key_must_be_1_to_128_characters(storage, make_clip):
    with serve(storage, ScriptedAnalyzer()) as client:
        video = ready_video(client, make_clip)
        too_long = client.post(f"/v1/videos/{video['id']}/analyses", headers={"Idempotency-Key": "k" * 129})
        assert too_long.status_code == 422 and too_long.json()["error"]["code"] == "VALIDATION_ERROR"
        assert client.post(f"/v1/videos/{video['id']}/analyses", headers={"Idempotency-Key": "k" * 128}).status_code == 202


# --- stages and progress ---------------------------------------------------------------------

def test_stage_and_measured_progress_are_visible_while_processing(storage, make_clip):
    analyzer = BlockingAnalyzer(reports=(("PREPROCESSING", None, None, None), ("DETECTING_RALLIES", 1200, 5400, "FRAMES")))
    with serve(storage, analyzer, start_worker=True) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        assert analyzer.started.wait(10)
        running = get_job(client, job["id"])
        assert (running["state"], running["stage"]) == ("PROCESSING", "DETECTING_RALLIES")
        assert running["progress"] == {"done": 1200, "total": 5400, "unit": "FRAMES"}
        assert TIMESTAMP.fullmatch(running["startedAt"]) and running["finishedAt"] is None
        assert running["counts"] == {"rallies": 0, "reviewOpen": 0, "reviewTotal": 0}
        analyzer.release.set()
        done = wait_for_state(client, job["id"], "NEEDS_REVIEW")
        # The run is over: no stage is running and there is no progress left to show.
        assert (done["stage"], done["progress"]) == (None, None)


def test_progress_stays_null_when_the_analyzer_did_not_measure_it(storage, make_clip):
    analyzer = BlockingAnalyzer(reports=(("PREPROCESSING", None, None, None),))
    with serve(storage, analyzer, start_worker=True) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        assert analyzer.started.wait(10)
        running = get_job(client, job["id"])
        assert (running["state"], running["stage"], running["progress"]) == ("PROCESSING", "PREPROCESSING", None)
        analyzer.release.set()
        wait_for_state(client, job["id"], "NEEDS_REVIEW")


def test_a_withdrawn_measurement_is_written_at_once(storage, make_clip):
    analyzer = BlockingAnalyzer(reports=(("DETECTING_RALLIES", 5, 10, "FRAMES"), ("DETECTING_RALLIES", None, None, None)),
                                polls=False)
    with serve(storage, analyzer, start_worker=True) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        assert analyzer.started.wait(10)
        running = get_job(client, job["id"])
        # The analyzer no longer measures its progress; the job must not keep showing 5 of 10.
        assert (running["state"], running["stage"], running["progress"]) == ("PROCESSING", "DETECTING_RALLIES", None)
        analyzer.release.set()
        wait_for_state(client, job["id"], "NEEDS_REVIEW")


def test_a_first_measurement_after_an_unmeasured_report_is_written_at_once(storage, make_clip):
    analyzer = BlockingAnalyzer(reports=(("DETECTING_RALLIES", None, None, None), ("DETECTING_RALLIES", 3, 10, "FRAMES")),
                                polls=False)
    with serve(storage, analyzer, start_worker=True) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        assert analyzer.started.wait(10)
        assert get_job(client, job["id"])["progress"] == {"done": 3, "total": 10, "unit": "FRAMES"}
        analyzer.release.set()
        wait_for_state(client, job["id"], "NEEDS_REVIEW")


def test_repeated_progress_numbers_within_a_stage_are_throttled(storage, make_clip, monkeypatch):
    monkeypatch.setattr(jobs, "PROGRESS_WRITE_SECONDS", 3600.0)
    reports = (("DETECTING_RALLIES", 1, 10, "FRAMES"), ("DETECTING_RALLIES", 2, 10, "FRAMES"),
               ("DETECTING_RALLIES", 3, 10, "FRAMES"))
    analyzer = BlockingAnalyzer(reports=reports, polls=False)
    with serve(storage, analyzer, start_worker=True) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        assert analyzer.started.wait(10)
        assert get_job(client, job["id"])["progress"] == {"done": 1, "total": 10, "unit": "FRAMES"}
        analyzer.release.set()
        wait_for_state(client, job["id"], "NEEDS_REVIEW")


class Indexable:
    """Stands in for a numpy integer: usable as an index, but not an int."""

    def __init__(self, value):
        self.value = value

    def __index__(self):
        return self.value


@pytest.mark.parametrize("triple, expected", [
    ((0, 10, "FRAMES"), (0, 10, "FRAMES")),
    ((10, 10, "FRAMES"), (10, 10, "FRAMES")),
    ((Indexable(3), Indexable(9), "SECONDS"), (3, 9, "SECONDS")),
    ((None, None, None), (None, None, None)),
    ((5, None, "FRAMES"), (None, None, None)),
    ((11, 10, "FRAMES"), (None, None, None)),
    ((-1, 10, "FRAMES"), (None, None, None)),
    ((0, 0, "FRAMES"), (None, None, None)),
    ((2.5, 10, "FRAMES"), (None, None, None)),
    ((5, 10, None), (None, None, None)),
    ((5, 10, ""), (None, None, None)),
])
def test_progress_is_kept_only_as_a_usable_measurement(triple, expected):
    result = jobs.measured_progress(*triple)
    assert result == expected
    assert all(value is None or type(value) in (int, str) for value in result)


def test_a_stage_outside_the_contract_fails_the_job(storage, make_clip):
    with serve(storage, ScriptedAnalyzer(reports=(("GUESSING", None, None, None),))) as client:
        job = run_one(client, make_clip)
        assert (job["state"], job["failure"]["code"], job["stage"]) == ("FAILED", "ANALYZER_ERROR", None)
        assert stored_rows(storage) == {"rallies": 0, "review_cards": 0}


# --- failures --------------------------------------------------------------------------------

def test_an_analyzer_without_rallies_fails_the_job(storage, make_clip):
    with serve(storage, ScriptedAnalyzer(result=[])) as client:
        job = run_one(client, make_clip)
        assert job["state"] == "FAILED"
        assert job["failure"] == {"code": "NO_RALLIES_FOUND", "message": "The analyzer found no rally in this video"}
        assert job["counts"] == {"rallies": 0, "reviewOpen": 0, "reviewTotal": 0}
        assert TIMESTAMP.fullmatch(job["finishedAt"])
        assert client.get(f"/v1/analyses/{job['id']}/rallies").json() == {"rallies": []}
        # A failed run does not block the next request.
        assert client.post(f"/v1/videos/{job['videoId']}/analyses").status_code == 202


@pytest.mark.parametrize("code", ["VIDEO_UNREADABLE", "NO_RALLIES_FOUND", "JOB_TIMEOUT", "ANALYZER_ERROR"])
def test_an_analyzer_error_fails_the_job_with_its_code(storage, make_clip, code):
    with serve(storage, ScriptedAnalyzer(error=AnalyzerError(code, "The video stream could not be decoded"))) as client:
        job = run_one(client, make_clip)
        assert job["state"] == "FAILED"
        assert job["failure"] == {"code": code, "message": "The video stream could not be decoded"}
        assert stored_rows(storage) == {"rallies": 0, "review_cards": 0}


def test_an_analyzer_error_with_an_unknown_code_is_an_analyzer_error(storage, make_clip):
    with serve(storage, ScriptedAnalyzer(error=AnalyzerError("OUT_OF_COFFEE", "x" * 1000))) as client:
        job = run_one(client, make_clip)
        assert job["failure"]["code"] == "ANALYZER_ERROR"
        assert job["failure"]["message"] == "x" * jobs.MESSAGE_LIMIT


def test_an_unexpected_exception_is_an_analyzer_error_without_internals(storage, make_clip, caplog):
    secret = "/srv/private/weights.bin"
    with serve(storage, ScriptedAnalyzer(error=RuntimeError(f"could not open {secret}"))) as client:
        job = run_one(client, make_clip)
        assert job["state"] == "FAILED"
        assert job["failure"] == {"code": "ANALYZER_ERROR", "message": "The analyzer stopped with an internal error"}
        answers = (client.get(f"/v1/analyses/{job['id']}"), client.get(f"/v1/videos/{job['videoId']}/analyses/latest"),
                   client.post(f"/v1/analyses/{job['id']}/cancel"), client.get(f"/v1/analyses/{job['id']}/rallies"))
        for answer in answers:
            assert secret not in answer.text
            assert "Traceback" not in answer.text and "RuntimeError" not in answer.text
    assert rows(storage, "SELECT failure_message FROM analysis_jobs") == [
        {"failure_message": "The analyzer stopped with an internal error"}]
    # The cause is kept for the operator, in the server log only.
    assert secret in caplog.text and "RuntimeError" in caplog.text


class LazyAnalyzer:
    """A generator: its body, and its failure, run only while the worker reads the result."""

    info = AnalyzerInfo("scripted", "t1", BASELINE)

    def analyze(self, video_path, media, report):
        yield RallyProposal(100, 200)
        raise RuntimeError("failed after the first proposal")


def test_an_exception_raised_while_the_result_is_read_fails_the_job(storage, make_clip):
    with serve(storage, LazyAnalyzer()) as client:
        job = run_one(client, make_clip)
        assert (job["state"], job["failure"]["code"]) == ("FAILED", "ANALYZER_ERROR")
        assert stored_rows(storage) == {"rallies": 0, "review_cards": 0}


@pytest.mark.parametrize("result", [
    [RallyProposal(300, 300)],
    [RallyProposal(400, 300)],
    [RallyProposal(-5, 100)],
    [RallyProposal(5_000, 6_000)],                       # starts after the end of the one-second clip
    [RallyProposal(100.5, 200)],
    [RallyProposal(100, 200, proposed_winner="DRAW")],
    [RallyProposal(100, 200, proposed_winner=None)],
    [RallyProposal(100, 200, confidence=1.5)],
    [RallyProposal(100, 200, confidence=float("nan"))],
    [RallyProposal(100, 200, uncertainty_reasons=("",))],
    [(100, 200)],
    [RallyProposal(100, 200), {"start_ms": 300, "end_ms": 400}],
    "not a list of proposals",
    7,
], ids=repr)
def test_output_that_breaks_the_analyzer_contract_fails_the_job_and_stores_nothing(storage, make_clip, result):
    with serve(storage, ScriptedAnalyzer(result=result)) as client:
        job = run_one(client, make_clip)
        assert job["state"] == "FAILED" and job["failure"]["code"] == "ANALYZER_ERROR"
        assert job["counts"] == {"rallies": 0, "reviewOpen": 0, "reviewTotal": 0}
        assert stored_rows(storage) == {"rallies": 0, "review_cards": 0}


@pytest.mark.parametrize("proposal", [
    RallyProposal(100, 200, proposed_winner="HOME"),
    RallyProposal(100, 200, confidence=0.9),
    RallyProposal(100, 200, proposed_winner="AWAY", confidence=0.5),
])
def test_a_mock_analyzer_can_not_store_a_winner_or_a_confidence(storage, make_clip, proposal):
    pretending = ScriptedAnalyzer(result=[RallyProposal(300, 400), proposal], info=AnalyzerInfo("pretend", "t1", MOCK))
    with serve(storage, pretending) as client:
        job = run_one(client, make_clip)
        assert job["state"] == "FAILED" and job["failure"]["code"] == "ANALYZER_ERROR"
        assert stored_rows(storage) == {"rallies": 0, "review_cards": 0}


@pytest.mark.parametrize("proposal", [
    RallyProposal(100, 200, confidence=0.93),
    RallyProposal(100, 200, proposed_winner="HOME", confidence=0.93),
    RallyProposal(100, 200, confidence=0),
])
def test_a_baseline_analyzer_can_not_store_a_confidence(storage, make_clip, proposal):
    # The accuracy of a baseline is not measured, so it has no calibrated probability to give.
    with serve(storage, ScriptedAnalyzer(result=[RallyProposal(300, 400), proposal])) as client:
        job = run_one(client, make_clip)
        assert job["analyzer"]["kind"] == "BASELINE"
        assert job["state"] == "FAILED" and job["failure"]["code"] == "ANALYZER_ERROR"
        assert stored_rows(storage) == {"rallies": 0, "review_cards": 0}


def test_a_baseline_analyzer_may_propose_a_winner_as_a_hint(storage, make_clip):
    hinting = ScriptedAnalyzer(result=[RallyProposal(100, 200, proposed_winner="AWAY"), RallyProposal(300, 400)])
    with serve(storage, hinting) as client:
        _, job = reviewed_job(client, make_clip)
        rallies = client.get(f"/v1/analyses/{job['id']}/rallies").json()["rallies"]
        assert [(rally["proposedWinner"], rally["confidence"], rally["winner"], rally["status"]) for rally in rallies] == [
            ("AWAY", None, None, "PROPOSED"), ("UNKNOWN", None, None, "PROPOSED")]
        assert [card["suggestedValue"] for card in cards(client, job["id"])] == ["AWAY", None]
        assert [card["answer"] for card in cards(client, job["id"])] == [None, None]


def test_proposals_are_stored_in_time_order_and_cut_at_the_end_of_the_video(storage, make_clip):
    # Kind MODEL: only a model may carry the confidence that this test follows through the sort.
    analyzer = ScriptedAnalyzer(result=[RallyProposal(700, 60_000), RallyProposal(Indexable(100), Indexable(250)),
                                        RallyProposal(300, 450, confidence=1)],
                                info=AnalyzerInfo("scripted", "t1", MODEL))
    with serve(storage, analyzer) as client:
        video, job = reviewed_job(client, make_clip)
        duration = video["quality"]["media"]["durationMs"]
        rallies = client.get(f"/v1/analyses/{job['id']}/rallies").json()["rallies"]
        assert [(rally["sequence"], rally["startMs"], rally["endMs"]) for rally in rallies] == [
            (1, 100, 250), (2, 300, 450), (3, 700, duration)]
        assert [rally["confidence"] for rally in rallies] == [None, 1.0, None]


def test_missing_video_bytes_fail_the_job_as_unreadable(storage, make_clip):
    analyzer = ScriptedAnalyzer()
    with serve(storage, analyzer) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        (storage / rows(storage, "SELECT storage_key FROM videos WHERE id=?", video["id"])[0]["storage_key"]).unlink()
        run_pending(client)
        failed = get_job(client, job["id"])
        assert (failed["state"], failed["failure"]["code"]) == ("FAILED", "VIDEO_UNREADABLE")
        assert analyzer.calls == 0


def test_a_run_longer_than_the_time_limit_fails_with_job_timeout(storage, make_clip, monkeypatch):
    monkeypatch.setattr(jobs, "JOB_TIMEOUT_SECONDS", -1.0)
    with serve(storage, ScriptedAnalyzer(reports=STAGE_ONLY)) as client:
        job = run_one(client, make_clip)
        assert (job["state"], job["failure"]["code"]) == ("FAILED", "JOB_TIMEOUT")
        assert stored_rows(storage) == {"rallies": 0, "review_cards": 0}


def test_a_queued_job_of_another_analyzer_is_not_run_under_the_wrong_name(storage, make_clip):
    with serve(storage, ScriptedAnalyzer()) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
    replacement = ScriptedAnalyzer(info=AnalyzerInfo("scripted", "t2", BASELINE))
    with serve(storage, replacement) as client:
        assert run_pending(client) == 0
        failed = get_job(client, job["id"])
        assert (failed["state"], failed["failure"]["code"], failed["analyzer"]) == ("FAILED", "ANALYZER_ERROR", SCRIPTED)
        assert replacement.calls == 0


def test_a_queued_job_is_not_run_by_an_analyzer_of_another_kind(storage, make_clip):
    with serve(storage, ScriptedAnalyzer()) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
    # Same id and version, but invented output: it must not land in a job that says BASELINE.
    replacement = ScriptedAnalyzer(info=AnalyzerInfo("scripted", "t1", MOCK))
    with serve(storage, replacement) as client:
        assert run_pending(client) == 0
        failed = get_job(client, job["id"])
        assert (failed["state"], failed["failure"]["code"], failed["analyzer"]) == ("FAILED", "ANALYZER_ERROR", SCRIPTED)
        assert failed["mock"] is False and replacement.calls == 0
        assert stored_rows(storage) == {"rallies": 0, "review_cards": 0}


# --- cancel ----------------------------------------------------------------------------------

def test_a_queued_job_can_be_cancelled_and_is_never_run(storage, make_clip):
    analyzer = ScriptedAnalyzer()
    with serve(storage, analyzer) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        cancelled = client.post(f"/v1/analyses/{job['id']}/cancel")
        assert cancelled.status_code == 200
        body = cancelled.json()
        assert (body["id"], body["state"], body["failure"], body["stage"]) == (job["id"], "CANCELLED", None, None)
        assert TIMESTAMP.fullmatch(body["finishedAt"])
        assert run_pending(client) == 0 and analyzer.calls == 0
        assert get_job(client, job["id"]) == body

        again = client.post(f"/v1/analyses/{job['id']}/cancel")
        assert again.status_code == 409
        assert again.json()["error"]["code"] == "JOB_FINISHED" and again.json()["error"]["details"] == {"state": "CANCELLED"}
        # The video is free for a new analysis.
        assert client.post(f"/v1/videos/{video['id']}/analyses").status_code == 202


def test_a_job_whose_run_has_ended_can_not_be_cancelled(storage, make_clip):
    analyzer = ScriptedAnalyzer()
    with serve(storage, analyzer) as client:
        reviewing = run_one(client, make_clip)
        refused = client.post(f"/v1/analyses/{reviewing['id']}/cancel")
        assert refused.status_code == 409 and refused.json()["error"]["code"] == "JOB_FINISHED"
        assert refused.json()["error"]["details"] == {"state": "NEEDS_REVIEW"}
        assert get_job(client, reviewing["id"]) == reviewing

        analyzer.result = []
        failed = run_one(client, make_clip)
        refused = client.post(f"/v1/analyses/{failed['id']}/cancel")
        assert refused.status_code == 409 and refused.json()["error"]["details"] == {"state": "FAILED"}


def test_cancel_stops_a_running_analysis(storage, make_clip):
    analyzer = BlockingAnalyzer()
    with serve(storage, analyzer, start_worker=True) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        assert analyzer.started.wait(10)
        cancelled = client.post(f"/v1/analyses/{job['id']}/cancel")
        assert cancelled.status_code == 200 and cancelled.json()["state"] == "CANCELLED"
        # The analyzer is interrupted at its next progress report.
        assert analyzer.interrupted.wait(10)

        # The worker is free again and runs the next job of the same video.
        analyzer.release.set()
        second = request_job(client, video["id"])
        wait_for_state(client, second["id"], "NEEDS_REVIEW")
        first = get_job(client, job["id"])
        assert (first["state"], first["counts"]["rallies"]) == ("CANCELLED", 0)
        assert {row["job_id"] for row in rows(storage, "SELECT job_id FROM rallies")} == {second["id"]}


def test_a_cancelled_run_that_finishes_anyway_writes_nothing(storage, make_clip):
    # This analyzer never reports progress after its start, so nothing can interrupt it.
    analyzer = BlockingAnalyzer(polls=False)
    with serve(storage, analyzer, start_worker=True) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        assert analyzer.started.wait(10)
        assert client.post(f"/v1/analyses/{job['id']}/cancel").json()["state"] == "CANCELLED"
        second = request_job(client, video["id"])
        assert second["state"] == "QUEUED"
        analyzer.release.set()
        # The worker runs one job at a time: once the second job is done, the first run has returned.
        wait_for_state(client, second["id"], "NEEDS_REVIEW")
        assert get_job(client, job["id"])["state"] == "CANCELLED"
        assert {row["job_id"] for row in rows(storage, "SELECT job_id FROM rallies")} == {second["id"]}
        assert {row["job_id"] for row in rows(storage, "SELECT job_id FROM review_cards")} == {second["id"]}


# --- the worker thread -----------------------------------------------------------------------

def test_the_worker_thread_starts_and_stops_with_the_app(storage):
    assert worker_threads() == []
    with TestClient(create_app(storage, analyzer=ScriptedAnalyzer())):
        assert len(worker_threads()) == 1
    assert worker_threads() == []
    with serve(storage, ScriptedAnalyzer(), start_worker=False):
        assert worker_threads() == []


def test_the_worker_thread_runs_a_job_without_being_stepped(storage, make_clip):
    with serve(storage, ScriptedAnalyzer(), start_worker=True) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        done = wait_for_state(client, job["id"], "NEEDS_REVIEW")
        assert done["counts"]["rallies"] == len(SPANS)


def test_a_job_queued_before_the_start_is_picked_up_at_the_start(storage, make_clip):
    with serve(storage, ScriptedAnalyzer()) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
    with serve(storage, ScriptedAnalyzer(), start_worker=True) as client:
        wait_for_state(client, job["id"], "NEEDS_REVIEW")


def test_jobs_run_one_after_the_other_in_the_order_they_were_requested(storage, make_clip):
    order = []

    class Recording(ScriptedAnalyzer):
        def analyze(self, video_path, media, report):
            order.append(video_path.name)
            return super().analyze(video_path, media, report)

    with serve(storage, Recording()) as client:
        videos = [ready_video(client, make_clip) for _ in range(3)]
        for video in videos:
            request_job(client, video["id"])
        assert run_pending(client) == 3
        keys = [rows(storage, "SELECT storage_key FROM videos WHERE id=?", video["id"])[0]["storage_key"] for video in videos]
        assert order == [key.split("/")[-1] for key in keys]


def test_shutdown_returns_a_running_job_to_the_queue(storage, make_clip):
    analyzer = BlockingAnalyzer()
    with serve(storage, analyzer, start_worker=True) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        assert analyzer.started.wait(10)
    assert analyzer.interrupted.is_set()
    # A clean stop is not a crash: nothing is counted against the job and it waits for the next start.
    assert rows(storage, "SELECT state, stage, run_token, heartbeat_at, started_at, finished_at, recoveries FROM analysis_jobs") == [
        {"state": "QUEUED", "stage": None, "run_token": None, "heartbeat_at": None, "started_at": None,
         "finished_at": None, "recoveries": 0}]
    with serve(storage, ScriptedAnalyzer()) as client:
        assert run_pending(client) == 1
        assert get_job(client, job["id"])["state"] == "NEEDS_REVIEW"


# --- heartbeat and recovery ------------------------------------------------------------------

def abandon(storage, job_id, seconds_ago, recoveries=0):
    """Leave the job the way a killed process leaves it: PROCESSING, owned by a run that no longer exists."""
    execute(storage,
            "UPDATE analysis_jobs SET state='PROCESSING', run_token='run-of-a-dead-process', heartbeat_at=?,"
            " started_at='2026-11-12T10:00:00Z', stage='DETECTING_RALLIES', progress_done=5, progress_total=10,"
            " progress_unit='FRAMES', recoveries=? WHERE id=?",
            time.time() - seconds_ago, recoveries, job_id)


def test_a_job_abandoned_by_a_crashed_process_is_queued_and_run_again(storage, make_clip):
    analyzer = ScriptedAnalyzer()
    with serve(storage, analyzer) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        abandon(storage, job["id"], jobs.HEARTBEAT_STALE_SECONDS + 1)
        assert get_job(client, job["id"])["state"] == "PROCESSING"
        assert run_pending(client) == 1
        done = get_job(client, job["id"])
        assert (done["state"], done["counts"]["rallies"]) == ("NEEDS_REVIEW", len(SPANS))
        assert done["startedAt"] != "2026-11-12T10:00:00Z"
        assert analyzer.calls == 1
        assert rows(storage, "SELECT recoveries, run_token, heartbeat_at FROM analysis_jobs") == [
            {"recoveries": 1, "run_token": None, "heartbeat_at": None}]


def test_a_job_with_a_recent_heartbeat_is_left_to_its_process(storage, make_clip):
    analyzer = ScriptedAnalyzer()
    with serve(storage, analyzer) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        abandon(storage, job["id"], jobs.HEARTBEAT_STALE_SECONDS - 10)
        assert run_pending(client) == 0 and analyzer.calls == 0
        untouched = get_job(client, job["id"])
        assert (untouched["state"], untouched["stage"]) == ("PROCESSING", "DETECTING_RALLIES")
        assert untouched["progress"] == {"done": 5, "total": 10, "unit": "FRAMES"}
        assert rows(storage, "SELECT run_token, recoveries FROM analysis_jobs") == [
            {"run_token": "run-of-a-dead-process", "recoveries": 0}]


def test_a_job_that_keeps_being_abandoned_fails_with_job_timeout(storage, make_clip):
    analyzer = ScriptedAnalyzer()
    with serve(storage, analyzer) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        # The job already took down as many processes as it is allowed to.
        abandon(storage, job["id"], jobs.HEARTBEAT_STALE_SECONDS + 1, recoveries=jobs.MAX_RECOVERIES)
        assert run_pending(client) == 0
        failed = get_job(client, job["id"])
        assert (failed["state"], failed["failure"]["code"]) == ("FAILED", "JOB_TIMEOUT")
        assert (failed["stage"], failed["progress"]) == (None, None)
        assert analyzer.calls == 0


def heartbeat(storage, job_id):
    return rows(storage, "SELECT heartbeat_at FROM analysis_jobs WHERE id=?", job_id)[0]["heartbeat_at"]


def test_a_running_job_keeps_its_heartbeat_fresh_between_progress_reports(storage, make_clip, monkeypatch):
    monkeypatch.setattr(jobs, "HEARTBEAT_INTERVAL_SECONDS", 0.01)
    analyzer = BlockingAnalyzer(polls=False)
    with serve(storage, analyzer, start_worker=True) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        assert analyzer.started.wait(10)
        first = heartbeat(storage, job["id"])
        assert first is not None
        wait_for(lambda: heartbeat(storage, job["id"]) > first)
        analyzer.release.set()
        wait_for_state(client, job["id"], "NEEDS_REVIEW")
        assert heartbeat(storage, job["id"]) is None


def test_a_run_whose_job_was_taken_over_writes_nothing(storage, make_clip, monkeypatch):
    monkeypatch.setattr(jobs, "HEARTBEAT_INTERVAL_SECONDS", 0.01)
    analyzer = BlockingAnalyzer(polls=False)
    with serve(storage, analyzer, start_worker=True) as client:
        video = ready_video(client, make_clip)
        other_video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        assert analyzer.started.wait(10)
        # Another process judged this run dead and claimed the job for itself.
        execute(storage, "UPDATE analysis_jobs SET run_token='run-of-another-process', heartbeat_at=? WHERE id=?",
                time.time() + 3600, job["id"])
        follower = request_job(client, other_video["id"])
        analyzer.release.set()
        wait_for_state(client, follower["id"], "NEEDS_REVIEW")
        taken = rows(storage, "SELECT state, run_token FROM analysis_jobs WHERE id=?", job["id"])
        assert taken == [{"state": "PROCESSING", "run_token": "run-of-another-process"}]
        assert {row["job_id"] for row in rows(storage, "SELECT job_id FROM rallies")} == {follower["id"]}


# --- deleting the video ----------------------------------------------------------------------

def test_deleting_a_video_removes_its_queued_job(storage, make_clip):
    analyzer = ScriptedAnalyzer()
    with serve(storage, analyzer) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        assert client.delete(f"/v1/videos/{video['id']}").status_code == 204
        assert client.get(f"/v1/analyses/{job['id']}").status_code == 404
        assert run_pending(client) == 0 and analyzer.calls == 0
        assert rows(storage, "SELECT 1 FROM analysis_jobs") == []


def test_deleting_a_video_cancels_its_running_job(storage, make_clip):
    analyzer = BlockingAnalyzer()
    with serve(storage, analyzer, start_worker=True) as client:
        video = ready_video(client, make_clip)
        other_video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        assert analyzer.started.wait(10)
        assert client.delete(f"/v1/videos/{video['id']}").status_code == 204
        assert analyzer.interrupted.wait(10)
        gone = client.get(f"/v1/analyses/{job['id']}")
        assert gone.status_code == 404 and gone.json()["error"]["code"] == "ANALYSIS_NOT_FOUND"

        # The worker survived the loss of its job.
        analyzer.release.set()
        follower = request_job(client, other_video["id"])
        wait_for_state(client, follower["id"], "NEEDS_REVIEW")
        assert [row["id"] for row in rows(storage, "SELECT id FROM analysis_jobs")] == [follower["id"]]
        assert {row["job_id"] for row in rows(storage, "SELECT job_id FROM rallies")} == {follower["id"]}


def test_a_run_that_outlives_its_deleted_video_writes_nothing(storage, make_clip):
    analyzer = BlockingAnalyzer(polls=False)
    with serve(storage, analyzer, start_worker=True) as client:
        video = ready_video(client, make_clip)
        other_video = ready_video(client, make_clip)
        request_job(client, video["id"])
        assert analyzer.started.wait(10)
        assert client.delete(f"/v1/videos/{video['id']}").status_code == 204
        follower = request_job(client, other_video["id"])
        analyzer.release.set()
        wait_for_state(client, follower["id"], "NEEDS_REVIEW")
        assert [row["id"] for row in rows(storage, "SELECT id FROM analysis_jobs")] == [follower["id"]]
        assert {row["job_id"] for row in rows(storage, "SELECT job_id FROM rallies")} == {follower["id"]}
        assert {card["rallySequence"] for card in cards(client, follower["id"])} == {1, 2, 3, 4}


def test_a_video_that_is_not_ready_gets_no_job(storage):
    with serve(storage, ScriptedAnalyzer()) as client:
        quarantined = store(client, b"not a video at all")
        refused = client.post(f"/v1/videos/{quarantined['id']}/analyses", headers={"Idempotency-Key": "k"})
        assert refused.status_code == 409 and refused.json()["error"]["code"] == "VIDEO_NOT_READY"
        assert refused.json()["error"]["details"] == {"videoStatus": "QUARANTINED", "rejectCode": "NOT_A_VIDEO"}
        assert rows(storage, "SELECT 1 FROM analysis_jobs") == []
