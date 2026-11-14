"""Analysis jobs: the queue, its state machine and the single background worker (docs/API_V1.md, 3).

An analyzer only proposes rally intervals. This module stores them as PROPOSED rallies with one
open RALLY_WINNER card each; nothing here writes a winner or a statistic.
"""
from __future__ import annotations

import json
import logging
import math
import operator
import sqlite3
import threading
import time
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any, Callable, Iterable

from fastapi import APIRouter, Header
from fastapi.responses import JSONResponse

from . import media
from .analyzers.base import AWAY, HOME, MOCK, MODEL, UNKNOWN, Analyzer, AnalyzerError, ProgressReporter, RallyProposal
from .db import find_video, new_id, utc_now
from .errors import fail

log = logging.getLogger(__name__)

QUEUED = "QUEUED"
PROCESSING = "PROCESSING"
NEEDS_REVIEW = "NEEDS_REVIEW"
COMPLETE = "COMPLETE"
FAILED = "FAILED"
CANCELLED = "CANCELLED"
# States in which the analysis run itself is still pending. Only one such job may exist per video.
ACTIVE_STATES = (QUEUED, PROCESSING)

STAGES = ("PREPROCESSING", "DETECTING_RALLIES", "GENERATING_REVIEW")
FAILURE_CODES = ("ANALYZER_ERROR", "VIDEO_UNREADABLE", "NO_RALLIES_FOUND", "JOB_TIMEOUT")

HEARTBEAT_INTERVAL_SECONDS = 5.0
# A PROCESSING job whose heartbeat is older than this belongs to a process that died.
HEARTBEAT_STALE_SECONDS = 45.0
# A job that keeps killing its process must not be retried forever.
MAX_RECOVERIES = 2
JOB_TIMEOUT_SECONDS = 6 * 3600.0
# Analyzers report per frame; one database write per report would dominate their run time.
PROGRESS_WRITE_SECONDS = 0.5
POLL_SECONDS = 2.0
STOP_JOIN_SECONDS = 5.0
MESSAGE_LIMIT = 300

CLIP_BEFORE_END_MS = 6_000
CLIP_AFTER_END_MS = 2_000

_LOST = "LOST"          # the job was cancelled, deleted or taken over by another run
_STOPPING = "STOPPING"  # the server is shutting down
_TIMEOUT = "TIMEOUT"


class _Halted(Exception):
    """Raised inside the analyzer, through its progress callback, to stop a run early."""


@dataclass(frozen=True)
class _Failure:
    code: str
    message: str


class _Run:
    def __init__(self, job_id: str, token: str, video: sqlite3.Row):
        self.job_id = job_id
        self.token = token
        self.video = video
        self.deadline = time.monotonic() + JOB_TIMEOUT_SECONDS
        self.lost = threading.Event()
        self.done = threading.Event()
        # Kept on the run as well as raised: an analyzer may catch the exception and wrap it.
        self.halted: str | None = None


def clip_bounds(start_ms: int, end_ms: int, duration_ms: int) -> tuple[int, int]:
    """The evidence clip shows how the rally ended, because the ending decides the point."""
    return max(start_ms, end_ms - CLIP_BEFORE_END_MS), min(duration_ms, end_ms + CLIP_AFTER_END_MS)


def measured_progress(done: Any, total: Any, unit: Any) -> tuple[int | None, int | None, str | None]:
    """Keep a progress triple only when it is a usable measurement; otherwise progress is unknown."""
    try:
        done, total = operator.index(done), operator.index(total)
    except TypeError:
        return None, None, None
    if not (isinstance(unit, str) and unit and 0 <= done <= total and total > 0):
        return None, None, None
    return done, total, unit


def checked_proposals(proposals: Iterable[RallyProposal], duration_ms: int, kind: str) -> list[RallyProposal]:
    """Normalise the output of an analyzer of `kind`, or raise ValueError/TypeError when it breaks the analyzer contract."""
    checked = []
    for proposal in proposals:
        if not isinstance(proposal, RallyProposal):
            raise TypeError(f"expected RallyProposal, got {type(proposal).__name__}")
        # operator.index accepts numpy integers and refuses floats, so no fractional time is truncated silently.
        start = operator.index(proposal.start_ms)
        # Frame timestamps can run a few milliseconds past the container duration; nothing exists there.
        end = min(operator.index(proposal.end_ms), duration_ms)
        if not 0 <= start < end:
            raise ValueError(f"rally {proposal.start_ms}-{proposal.end_ms} ms is empty or outside the video")
        if proposal.proposed_winner not in (HOME, AWAY, UNKNOWN):
            raise ValueError(f"unknown proposed winner {proposal.proposed_winner!r}")
        confidence = proposal.confidence
        if confidence is not None:
            confidence = float(confidence)
            if not (math.isfinite(confidence) and 0.0 <= confidence <= 1.0):
                raise ValueError(f"confidence {proposal.confidence!r} is not a probability")
        if kind == MOCK and proposal.proposed_winner != UNKNOWN:
            raise ValueError("a mock analyzer must not propose a winner")
        if kind != MODEL and confidence is not None:
            # A confidence is a calibrated probability. A mock has none and a baseline has never been evaluated.
            raise ValueError(f"an analyzer of kind {kind} must not report a confidence")
        reasons = tuple(proposal.uncertainty_reasons)
        if not all(isinstance(reason, str) and reason for reason in reasons):
            raise TypeError("uncertainty reasons must be non-empty strings")
        checked.append(replace(proposal, start_ms=start, end_ms=end, confidence=confidence, uncertainty_reasons=reasons))
    checked.sort(key=lambda item: (item.start_ms, item.end_ms))
    for earlier, later in zip(checked, checked[1:]):
        # One ball is in play at a time. Rallies may touch; two that share time would count a point twice.
        if later.start_ms < earlier.end_ms:
            raise ValueError(f"rallies {earlier.start_ms}-{earlier.end_ms} ms and {later.start_ms}-{later.end_ms} ms overlap")
    return checked


def analyzer_payload(job) -> dict[str, str]:
    return {"id": job["analyzer_id"], "version": job["analyzer_version"], "kind": job["analyzer_kind"]}


def is_mock(job) -> bool:
    return job["analyzer_kind"] == MOCK


def find_job(db, job_id: str):
    job = db.execute("SELECT * FROM analysis_jobs WHERE id=?", (job_id,)).fetchone()
    if not job:
        fail("ANALYSIS_NOT_FOUND", "Analysis job does not exist", 404)
    return job


def list_confirmed(job) -> bool:
    """True while the user's confirmation still names the current rally list. Any later change of the list moves the version on."""
    return job["list_confirmed_version"] is not None and job["list_confirmed_version"] == job["rally_list_version"]


def job_payload(db, job) -> dict[str, Any]:
    rallies = db.execute("SELECT COUNT(*) FROM rallies WHERE job_id=?", (job["id"],)).fetchone()[0]
    # Only SCORE cards are counted: they alone decide between NEEDS_REVIEW and COMPLETE.
    total, still_open = db.execute(
        "SELECT COUNT(*), COALESCE(SUM(status='OPEN'), 0) FROM review_cards WHERE job_id=? AND question_type='RALLY_WINNER'",
        (job["id"],),
    ).fetchone()
    progress = None
    if job["progress_total"] is not None:
        progress = {"done": job["progress_done"], "total": job["progress_total"], "unit": job["progress_unit"]}
    failure = None
    if job["failure_code"]:
        failure = {"code": job["failure_code"], "message": job["failure_message"]}
    return {
        "id": job["id"], "videoId": job["video_id"], "matchId": job["match_id"],
        "state": job["state"], "stage": job["stage"], "progress": progress,
        "analyzer": analyzer_payload(job), "mock": is_mock(job),
        "failure": failure,
        "counts": {"rallies": rallies, "reviewOpen": still_open, "reviewTotal": total},
        "rallyListVersion": job["rally_list_version"], "listConfirmed": list_confirmed(job),
        "createdAt": job["created_at"], "startedAt": job["started_at"], "finishedAt": job["finished_at"],
    }


def _close(db, job_id: str, state: str, failure: _Failure | None = None) -> None:
    """End the analysis run of a job. finished_at is the end of the run, not of the review."""
    db.execute(
        "UPDATE analysis_jobs SET state=?, stage=NULL, progress_done=NULL, progress_total=NULL, progress_unit=NULL,"
        " run_token=NULL, heartbeat_at=NULL, failure_code=?, failure_message=?, finished_at=? WHERE id=?",
        (state, failure.code if failure else None, failure.message if failure else None, utc_now(), job_id),
    )


class JobWorker:
    """Runs queued jobs one at a time on a background thread of this process.

    A run owns its job through `run_token`. Every write of a run is conditional on that token, so a
    job that was cancelled, deleted or taken over after a crash can not be overwritten by a late run.
    """

    def __init__(self, connect, analyzer: Analyzer | None, video_path: Callable[[Any], Path]):
        self._connect = connect
        self._analyzer = analyzer
        self._video_path = video_path
        self._wake = threading.Event()
        self._stopping = threading.Event()
        self._thread: threading.Thread | None = None
        self._lock = threading.Lock()
        self._current: _Run | None = None

    def start(self) -> None:
        self._stopping.clear()
        # A daemon thread: an analyzer that ignores the stop signal must not keep the process alive.
        self._thread = threading.Thread(target=self._loop, name="volleystat-analysis-worker", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stopping.set()
        self._wake.set()
        if self._thread is not None:
            self._thread.join(STOP_JOIN_SECONDS)
            self._thread = None

    def wake(self) -> None:
        self._wake.set()

    def release(self, job_id: str) -> None:
        """Stop a run of this process whose job was cancelled or deleted, without waiting for its next heartbeat."""
        with self._lock:
            if self._current is not None and self._current.job_id == job_id:
                self._current.lost.set()

    def run_pending(self) -> int:
        """Process every job that can be claimed now, in the calling thread. Returns how many ran.

        The background thread calls this in a loop. Tests call it directly, on an app created with
        start_worker=False, to step the queue without timing.
        """
        handled = 0
        while not self._stopping.is_set():
            run = self._claim()
            if run is None:
                break
            self._process(run)
            handled += 1
        return handled

    def _loop(self) -> None:
        while not self._stopping.is_set():
            # Cleared before the pass: a wake-up that arrives during the pass is then not lost.
            self._wake.clear()
            try:
                self.run_pending()
            except Exception:
                log.exception("Analysis worker pass failed")
            # stop() sets the stop flag before the wake-up, so a stop that the clear above
            # swallowed is seen here and the thread does not sit out a full poll interval.
            if self._stopping.is_set():
                break
            self._wake.wait(POLL_SECONDS)

    def _claim(self) -> _Run | None:
        now = time.time()
        with self._connect(write=True) as db:
            abandoned = db.execute(
                "SELECT id, recoveries FROM analysis_jobs WHERE state=? AND heartbeat_at < ?",
                (PROCESSING, now - HEARTBEAT_STALE_SECONDS),
            ).fetchall()
            for job in abandoned:
                if job["recoveries"] >= MAX_RECOVERIES:
                    _close(db, job["id"], FAILED, _Failure("JOB_TIMEOUT", "The analysis was interrupted repeatedly and did not finish"))
                else:
                    db.execute(
                        "UPDATE analysis_jobs SET state=?, stage=NULL, progress_done=NULL, progress_total=NULL, progress_unit=NULL,"
                        " run_token=NULL, heartbeat_at=NULL, started_at=NULL, recoveries=recoveries+1 WHERE id=?",
                        (QUEUED, job["id"]),
                    )
            while True:
                job = db.execute("SELECT * FROM analysis_jobs WHERE state=? ORDER BY rowid LIMIT 1", (QUEUED,)).fetchone()
                if job is None:
                    return None
                configured = self._analyzer.info if self._analyzer is not None else None
                if configured is None or (job["analyzer_id"], job["analyzer_version"], job["analyzer_kind"]) != (
                        configured.id, configured.version, configured.kind):
                    # Running it with another analyzer would label the output with the wrong origin.
                    _close(db, job["id"], FAILED, _Failure("ANALYZER_ERROR", "The analyzer this job was created for is no longer configured on the server"))
                    continue
                token = new_id()
                db.execute(
                    "UPDATE analysis_jobs SET state=?, run_token=?, heartbeat_at=?, started_at=? WHERE id=?",
                    (PROCESSING, token, now, utc_now(), job["id"]),
                )
                return _Run(job["id"], token, find_video(db, job["video_id"]))

    def _process(self, run: _Run) -> None:
        with self._lock:
            self._current = run
        beat = threading.Thread(target=self._beat, args=(run,), name="volleystat-analysis-heartbeat", daemon=True)
        beat.start()
        try:
            outcome = self._analyze(run)
        finally:
            run.done.set()
            beat.join()
            with self._lock:
                self._current = None
        self._finish(run, outcome)

    def _beat(self, run: _Run) -> None:
        while not run.done.wait(HEARTBEAT_INTERVAL_SECONDS):
            try:
                with self._connect(write=True) as db:
                    owned = db.execute(
                        "UPDATE analysis_jobs SET heartbeat_at=? WHERE id=? AND run_token=? AND state=?",
                        (time.time(), run.job_id, run.token, PROCESSING),
                    ).rowcount
            except sqlite3.Error:
                log.exception("Heartbeat of analysis job %s failed", run.job_id)
                continue
            if not owned:
                run.lost.set()
                return

    def _reporter(self, run: _Run) -> ProgressReporter:
        last_written: tuple[str, bool] | None = None  # (stage, whether its progress was measured)
        last_write = 0.0

        def halt(reason: str) -> None:
            run.halted = reason
            raise _Halted(reason)

        def report(stage: str, done: int | None = None, total: int | None = None, unit: str | None = None) -> None:
            nonlocal last_written, last_write
            # This callback is the only place where a running analyzer can be stopped.
            if self._stopping.is_set():
                halt(_STOPPING)
            if run.lost.is_set():
                halt(_LOST)
            if time.monotonic() > run.deadline:
                halt(_TIMEOUT)
            if stage not in STAGES:
                raise ValueError(f"unknown analysis stage {stage!r}")
            progress = measured_progress(done, total, unit)
            written = (stage, progress[1] is not None)
            now = time.monotonic()
            # Skipped: a report in the stage that was written last which, like that one, carries numbers
            # or, like that one, carries none. A new stage, a first measurement and a withdrawn one are
            # stored at once: a job that keeps showing "5 of 10" after its analyzer stopped measuring
            # would state a progress nobody measured.
            if written == last_written and now - last_write < PROGRESS_WRITE_SECONDS:
                return
            last_written, last_write = written, now
            with self._connect(write=True) as db:
                owned = db.execute(
                    "UPDATE analysis_jobs SET stage=?, progress_done=?, progress_total=?, progress_unit=?, heartbeat_at=?"
                    " WHERE id=? AND run_token=? AND state=?",
                    (stage, *progress, time.time(), run.job_id, run.token, PROCESSING),
                ).rowcount
            if not owned:
                run.lost.set()
                halt(_LOST)

        return report

    def _analyze(self, run: _Run) -> list[RallyProposal] | _Failure | str:
        analyzer = self._analyzer
        path = self._video_path(run.video)
        if not path.is_file():
            return _Failure("VIDEO_UNREADABLE", "The stored bytes of this video are missing")
        media_info = json.loads(run.video["media_json"])
        try:
            # list(): an analyzer written as a generator only runs, and only fails, while it is consumed.
            proposals = list(analyzer.analyze(path, media_info, self._reporter(run)))
        except Exception as error:
            if run.halted:
                return run.halted
            if isinstance(error, AnalyzerError):
                code = error.code if error.code in FAILURE_CODES else "ANALYZER_ERROR"
                return _Failure(code, str(error)[:MESSAGE_LIMIT] or "The analyzer reported a failure")
            # The traceback stays in the server log. Exception text can hold paths and other internals.
            log.exception("Analyzer %s crashed on job %s", analyzer.info.id, run.job_id)
            return _Failure("ANALYZER_ERROR", "The analyzer stopped with an internal error")
        try:
            rallies = checked_proposals(proposals, int(media_info["durationMs"]), analyzer.info.kind)
        except (TypeError, ValueError) as error:
            log.error("Analyzer %s returned invalid output for job %s: %s", analyzer.info.id, run.job_id, error)
            return _Failure("ANALYZER_ERROR", "The analyzer returned output that breaks its contract")
        if not rallies:
            return _Failure("NO_RALLIES_FOUND", "The analyzer found no rally in this video")
        return rallies

    def _finish(self, run: _Run, outcome: list[RallyProposal] | _Failure | str) -> None:
        if outcome == _LOST:
            return
        with self._connect(write=True) as db:
            owned = db.execute(
                "SELECT 1 FROM analysis_jobs WHERE id=? AND run_token=? AND state=?", (run.job_id, run.token, PROCESSING)
            ).fetchone()
            if not owned:
                return
            if outcome == _STOPPING:
                # A clean shutdown is not a crash: the next start picks the job up at once.
                db.execute(
                    "UPDATE analysis_jobs SET state=?, stage=NULL, progress_done=NULL, progress_total=NULL, progress_unit=NULL,"
                    " run_token=NULL, heartbeat_at=NULL, started_at=NULL WHERE id=?",
                    (QUEUED, run.job_id),
                )
            elif outcome == _TIMEOUT:
                _close(db, run.job_id, FAILED, _Failure("JOB_TIMEOUT", "The analysis did not finish within the time limit"))
            elif isinstance(outcome, _Failure):
                _close(db, run.job_id, FAILED, outcome)
            else:
                # Rallies, cards and the state change commit together: a job never shows half a rally list.
                duration_ms = int(json.loads(run.video["media_json"])["durationMs"])
                for sequence, rally in enumerate(outcome, start=1):
                    rally_id, proposal_id = new_id(), new_id()
                    proposed = (rally.proposed_winner, rally.confidence, json.dumps(rally.uncertainty_reasons))
                    # The proposal is kept as its own record. The rally row is the working copy that
                    # the user may correct, and it names the proposal it came from.
                    db.execute(
                        "INSERT INTO rally_proposals(id, job_id, start_ms, end_ms, proposed_winner, confidence, uncertainty_json)"
                        " VALUES(?,?,?,?,?,?,?)", (proposal_id, run.job_id, rally.start_ms, rally.end_ms, *proposed),
                    )
                    db.execute(
                        "INSERT INTO rallies(id, job_id, sequence, start_ms, end_ms, clip_start_ms, clip_end_ms,"
                        " proposed_winner, confidence, uncertainty_json, proposal_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        (rally_id, run.job_id, sequence, rally.start_ms, rally.end_ms,
                         *clip_bounds(rally.start_ms, rally.end_ms, duration_ms), *proposed, proposal_id),
                    )
                    db.execute(
                        "INSERT INTO review_cards(id, job_id, rally_id, question_type) VALUES(?,?,?,'RALLY_WINNER')",
                        (new_id(), run.job_id, rally_id),
                    )
                _close(db, run.job_id, NEEDS_REVIEW)


def router(connect, analyzer: Analyzer | None, worker: JobWorker) -> APIRouter:
    api = APIRouter()

    @api.post("/v1/videos/{video_id}/analyses", responses={202: {"description": "A new job was queued"}})
    def request_analysis(
        video_id: str,
        idempotency_key: str | None = Header(default=None, alias="Idempotency-Key", min_length=1, max_length=128),
    ):
        with connect(write=True) as db:
            video = find_video(db, video_id)
            if video["status"] != media.READY:
                fail("VIDEO_NOT_READY", "Video did not pass media validation and can not be analysed", 409,
                     videoStatus=video["status"], rejectCode=video["reject_code"])
            job = None
            if idempotency_key is not None:
                job = db.execute(
                    "SELECT j.* FROM analysis_request_keys k JOIN analysis_jobs j ON j.id=k.job_id"
                    " WHERE k.video_id=? AND k.idempotency_key=?", (video_id, idempotency_key)
                ).fetchone()
            created = False
            if job is None:
                # Only a request that would start or join a run needs an analyzer. A key that was
                # already answered gets its job back even when the server was restarted without one.
                if analyzer is None:
                    fail("MODEL_NOT_CONFIGURED", "Volleyball computer vision pipeline is not installed. No statistics have been inferred.", 503)
                job = db.execute(
                    "SELECT * FROM analysis_jobs WHERE video_id=? AND state IN (?,?)", (video_id, *ACTIVE_STATES)
                ).fetchone()
                if job is None:
                    job_id = new_id()
                    db.execute(
                        "INSERT INTO analysis_jobs(id, video_id, match_id, state, analyzer_id, analyzer_version, analyzer_kind,"
                        " created_at) VALUES(?,?,?,?,?,?,?,?)",
                        (job_id, video_id, video["match_id"], QUEUED, analyzer.info.id, analyzer.info.version,
                         analyzer.info.kind, utc_now()),
                    )
                    job = find_job(db, job_id)
                    created = True
                if idempotency_key is not None:
                    # Also for a request that only joined a pending job: its retry may arrive after that
                    # run ended and must still get this job, not start a second analysis.
                    db.execute(
                        "INSERT INTO analysis_request_keys(video_id, idempotency_key, job_id) VALUES(?,?,?)",
                        (video_id, idempotency_key, job["id"]),
                    )
            payload = job_payload(db, job)
        if not created:
            return payload
        worker.wake()
        return JSONResponse(status_code=202, content=payload)

    @api.get("/v1/videos/{video_id}/analyses/latest")
    def latest_analysis(video_id: str):
        with connect() as db:
            find_video(db, video_id)
            job = db.execute("SELECT * FROM analysis_jobs WHERE video_id=? ORDER BY rowid DESC LIMIT 1", (video_id,)).fetchone()
            if not job:
                fail("ANALYSIS_NOT_FOUND", "This video has no analysis job", 404)
            return job_payload(db, job)

    @api.get("/v1/analyses/{job_id}")
    def get_analysis(job_id: str):
        with connect() as db:
            return job_payload(db, find_job(db, job_id))

    @api.post("/v1/analyses/{job_id}/cancel")
    def cancel_analysis(job_id: str):
        with connect(write=True) as db:
            job = find_job(db, job_id)
            if job["state"] not in ACTIVE_STATES:
                # Once the run has ended there is nothing left to stop, and review decisions may exist.
                fail("JOB_FINISHED", "The analysis run of this job has already ended", 409, state=job["state"])
            _close(db, job_id, CANCELLED)
            payload = job_payload(db, find_job(db, job_id))
        worker.release(job_id)
        return payload

    return api
