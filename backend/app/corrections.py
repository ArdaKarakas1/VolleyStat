"""Corrections of the rally list: add, move, split, merge and the confirmed list (docs/API_V1.md, 9).

An analyzer misses rallies, joins two into one and cuts one in two. These routes let the user
put the list right. A correction changes which intervals are rallies; it never decides a winner.
An answer stays with a rally as long as the rally keeps the end that the user judged in the
evidence clip, and is asked for again when that end changes.

Every correction is one write transaction. It carries the version of the list it was made on,
so a correction of a list that has moved on is refused, and so is a repeat of one that already
went through. What the analyzer proposed stays in rally_proposals, untouched.
"""
from __future__ import annotations

import json
from typing import Any

from fastapi import APIRouter
from pydantic import BaseModel, Field

from .db import new_id, utc_now
from .errors import fail
from .jobs import COMPLETE, NEEDS_REVIEW, clip_bounds, find_job, is_mock, job_payload, list_confirmed
from .review import CONFIRM_LIST, RALLY_WINNER, _audit, _set_answer, rally_payloads

ADD = "ADD"
MOVE = "MOVE"
SPLIT = "SPLIT"
MERGE_PREVIOUS = "MERGE_PREVIOUS"


class _OnList(BaseModel):
    # The version of the rally list that the user was looking at.
    expectedListVersion: int = Field(ge=0, strict=True)


class IntervalInput(_OnList):
    startMs: int = Field(strict=True)
    endMs: int = Field(strict=True)


class SplitInput(_OnList):
    atMs: int = Field(strict=True)


def _reviewable(db, job_id: str):
    """The job, if its run ended with rallies. Before that there is no list to correct, and a row added early would break the run."""
    job = find_job(db, job_id)
    if job["state"] not in (NEEDS_REVIEW, COMPLETE):
        fail("JOB_NOT_REVIEWABLE", "The analysis has no rally list to correct", 409, state=job["state"])
    return job


def _on_current_list(job, expected: int) -> None:
    if job["rally_list_version"] != expected:
        fail("RALLY_LIST_STALE", "The rally list changed since it was read; fetch it again", 409,
             currentListVersion=job["rally_list_version"])


def _find_rally(db, rally_id: str):
    rally = db.execute("SELECT * FROM rallies WHERE id=?", (rally_id,)).fetchone()
    if not rally:
        fail("RALLY_NOT_FOUND", "Rally does not exist", 404)
    return rally


def _duration_ms(db, job) -> int:
    media = db.execute("SELECT media_json FROM videos WHERE id=?", (job["video_id"],)).fetchone()
    return int(json.loads(media["media_json"])["durationMs"])


def _check_interval(db, job, start_ms: int, end_ms: int, duration_ms: int, own_id: str | None = None) -> None:
    if not 0 <= start_ms < end_ms <= duration_ms:
        fail("INVALID_INTERVAL", "The interval is empty or outside the video", 422, videoDurationMs=duration_ms)
    # One ball is in play at a time, so no two rallies share time; they may touch. A rally the user
    # called "not a rally" keeps its time too, because one answer can bring it back into the count.
    blocker = db.execute(
        "SELECT id FROM rallies WHERE job_id=? AND id IS NOT ? AND start_ms < ? AND ? < end_ms ORDER BY start_ms LIMIT 1",
        (job["id"], own_id, end_ms, start_ms),
    ).fetchone()
    if blocker:
        fail("RALLY_OVERLAP", "The interval overlaps another rally", 409, rallyId=blocker["id"])


def _new_rally(db, job, start_ms: int, end_ms: int, duration_ms: int) -> str:
    """A rally the user made. No analyzer looked at it, so it carries no proposal, and one question: who won it."""
    rally_id = new_id()
    # The sequence is settled by _renumber. Until then a number below every real one keeps the unique index quiet.
    lowest = db.execute("SELECT COALESCE(MIN(sequence), 0) FROM rallies WHERE job_id=?", (job["id"],)).fetchone()[0]
    db.execute(
        "INSERT INTO rallies(id, job_id, sequence, start_ms, end_ms, clip_start_ms, clip_end_ms, proposed_winner,"
        " confidence, uncertainty_json, proposal_id) VALUES(?,?,?,?,?,?,?,'UNKNOWN',NULL,'[]',NULL)",
        (rally_id, job["id"], min(lowest, 0) - 1, start_ms, end_ms, *clip_bounds(start_ms, end_ms, duration_ms)),
    )
    db.execute("INSERT INTO review_cards(id, job_id, rally_id, question_type) VALUES(?,?,?,?)",
               (new_id(), job["id"], rally_id, RALLY_WINNER))
    return rally_id


def _set_interval(db, rally, start_ms: int, end_ms: int, duration_ms: int) -> None:
    db.execute("UPDATE rallies SET start_ms=?, end_ms=?, clip_start_ms=?, clip_end_ms=? WHERE id=?",
               (start_ms, end_ms, *clip_bounds(start_ms, end_ms, duration_ms), rally["id"]))


def _ask_winner_again(db, rally_id: str, correction_id: str) -> None:
    """The clip that the winner was judged on no longer shows this rally's end."""
    card = db.execute("SELECT * FROM review_cards WHERE rally_id=? AND question_type=?", (rally_id, RALLY_WINNER)).fetchone()
    if card["answer"] is None:
        # Nothing to take back. The card still moves on, so that a phone showing the old clip can not answer for the new one.
        db.execute("UPDATE review_cards SET version=version+1 WHERE id=?", (card["id"],))
        return
    _set_answer(db, card, None, correction_id)
    _audit(db, card, "REOPEN", None, correction_id=correction_id)


def _renumber(db, job_id: str) -> None:
    """Number the rallies of a job 1..n in match order. In two steps, because the unique index is checked row by row."""
    db.execute("UPDATE rallies SET sequence = -sequence - 1000000 WHERE job_id=?", (job_id,))
    ordered = db.execute("SELECT id FROM rallies WHERE job_id=? ORDER BY start_ms, end_ms, id", (job_id,)).fetchall()
    db.executemany("UPDATE rallies SET sequence=? WHERE id=?", [(number, row["id"]) for number, row in enumerate(ordered, start=1)])


def _snapshot(db, *rally_ids: str | None) -> str:
    """The audit's picture of rallies: where they were, what was decided, and which proposal they came from."""
    found = []
    for rally_id in rally_ids:
        rally = db.execute("SELECT * FROM rallies WHERE id=?", (rally_id,)).fetchone() if rally_id else None
        if rally:
            found.append({"id": rally["id"], "startMs": rally["start_ms"], "endMs": rally["end_ms"], "status": rally["status"],
                          "winner": rally["winner"], "proposalId": rally["proposal_id"]})
    return json.dumps(found)


def _record(db, job, correction_id: str, operation: str, before: str, after: str, rally_id: str | None = None,
            created: str | None = None, removed: str | None = None, moves_list: bool = True) -> dict[str, Any]:
    """Close a correction: the list version, the job state, the audit row and the answer to the client."""
    still_open = db.execute(
        "SELECT COUNT(*) FROM review_cards WHERE job_id=? AND question_type=? AND status='OPEN'", (job["id"], RALLY_WINNER)
    ).fetchone()[0]
    db.execute(
        "UPDATE analysis_jobs SET revision=revision+1, rally_list_version=rally_list_version+?, state=? WHERE id=?",
        (int(moves_list), NEEDS_REVIEW if still_open else COMPLETE, job["id"]),
    )
    job = find_job(db, job["id"])
    db.execute(
        "INSERT INTO rally_corrections(id, job_id, operation, rally_id, created_rally_id, removed_rally_id, before_json,"
        " after_json, list_version_after, created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
        (correction_id, job["id"], operation, rally_id, created, removed, before, after, job["rally_list_version"], utc_now()),
    )
    return {"job": job_payload(db, job), "reportRevision": job["revision"]}


def _changed(db, job, correction_id: str | None, affected_ids: tuple[str, ...], removed_ids: tuple[str, ...],
             closing: dict[str, Any]) -> dict[str, Any]:
    by_id = {rally["id"]: rally for rally in rally_payloads(db, job["id"])}
    return {"correctionId": correction_id, "affected": [by_id[rally_id] for rally_id in affected_ids],
            "removed": list(removed_ids), **closing}


def router(connect) -> APIRouter:
    api = APIRouter()

    @api.post("/v1/analyses/{job_id}/rallies", status_code=201)
    def add_rally(job_id: str, payload: IntervalInput):
        """Add a rally that the analyzer missed."""
        with connect(write=True) as db:
            job = _reviewable(db, job_id)
            _on_current_list(job, payload.expectedListVersion)
            duration_ms = _duration_ms(db, job)
            _check_interval(db, job, payload.startMs, payload.endMs, duration_ms)
            correction_id = new_id()
            rally_id = _new_rally(db, job, payload.startMs, payload.endMs, duration_ms)
            _renumber(db, job_id)
            closing = _record(db, job, correction_id, ADD, "[]", _snapshot(db, rally_id), rally_id=rally_id, created=rally_id)
            return _changed(db, job, correction_id, (rally_id,), (), closing)

    @api.post("/v1/rallies/{rally_id}/move")
    def move_rally(rally_id: str, payload: IntervalInput):
        """Set the start and the end of a rally. A changed end asks for the winner again."""
        with connect(write=True) as db:
            rally = _find_rally(db, rally_id)
            job = _reviewable(db, rally["job_id"])
            _on_current_list(job, payload.expectedListVersion)
            duration_ms = _duration_ms(db, job)
            _check_interval(db, job, payload.startMs, payload.endMs, duration_ms, own_id=rally_id)
            if (payload.startMs, payload.endMs) == (rally["start_ms"], rally["end_ms"]):
                # Nothing to correct: no version moves and nothing is recorded.
                return _changed(db, job, None, (rally_id,), (), {"job": job_payload(db, job), "reportRevision": job["revision"]})
            correction_id = new_id()
            before = _snapshot(db, rally_id)
            _set_interval(db, rally, payload.startMs, payload.endMs, duration_ms)
            if payload.endMs != rally["end_ms"]:
                _ask_winner_again(db, rally_id, correction_id)
            _renumber(db, job["id"])
            closing = _record(db, job, correction_id, MOVE, before, _snapshot(db, rally_id), rally_id=rally_id)
            return _changed(db, job, correction_id, (rally_id,), (), closing)

    @api.post("/v1/rallies/{rally_id}/split")
    def split_rally(rally_id: str, payload: SplitInput):
        """Cut a rally that holds two. The later part is the rally whose end the user may already have judged."""
        with connect(write=True) as db:
            rally = _find_rally(db, rally_id)
            job = _reviewable(db, rally["job_id"])
            _on_current_list(job, payload.expectedListVersion)
            if not rally["start_ms"] < payload.atMs < rally["end_ms"]:
                fail("SPLIT_POINT_OUTSIDE", "The split point is not inside the rally", 422,
                     startMs=rally["start_ms"], endMs=rally["end_ms"])
            duration_ms = _duration_ms(db, job)
            correction_id = new_id()
            before = _snapshot(db, rally_id)
            _set_interval(db, rally, payload.atMs, rally["end_ms"], duration_ms)
            earlier_id = _new_rally(db, job, rally["start_ms"], payload.atMs, duration_ms)
            _renumber(db, job["id"])
            closing = _record(db, job, correction_id, SPLIT, before, _snapshot(db, earlier_id, rally_id),
                              rally_id=rally_id, created=earlier_id)
            return _changed(db, job, correction_id, (earlier_id, rally_id), (), closing)

    @api.post("/v1/rallies/{rally_id}/merge-previous")
    def merge_previous(rally_id: str, payload: _OnList):
        """Join a rally with the one before it. The earlier one was no rally of its own, so its answers go."""
        with connect(write=True) as db:
            rally = _find_rally(db, rally_id)
            job = _reviewable(db, rally["job_id"])
            _on_current_list(job, payload.expectedListVersion)
            previous = db.execute("SELECT * FROM rallies WHERE job_id=? AND sequence=?", (job["id"], rally["sequence"] - 1)).fetchone()
            if not previous:
                fail("NO_PREVIOUS_RALLY", "There is no rally before this one", 409)
            correction_id = new_id()
            before = _snapshot(db, previous["id"], rally_id)
            for card in db.execute("SELECT * FROM review_cards WHERE rally_id=? AND answer IS NOT NULL", (previous["id"],)).fetchall():
                _audit(db, card, "REMOVED", None, correction_id=correction_id)
            db.execute("DELETE FROM rallies WHERE id=?", (previous["id"],))
            _set_interval(db, rally, previous["start_ms"], rally["end_ms"], _duration_ms(db, job))
            if rally["status"] == "REJECTED":
                # "Not a rally" was said about a clip that the user has now made the end of a rally.
                _ask_winner_again(db, rally_id, correction_id)
            _renumber(db, job["id"])
            closing = _record(db, job, correction_id, MERGE_PREVIOUS, before, _snapshot(db, rally_id),
                              rally_id=rally_id, removed=previous["id"])
            return _changed(db, job, correction_id, (rally_id,), (previous["id"],), closing)

    @api.post("/v1/analyses/{job_id}/rally-list/confirm")
    def confirm_list(job_id: str, payload: _OnList):
        """Record that the user declares the list complete: it holds every rally of the video."""
        with connect(write=True) as db:
            job = _reviewable(db, job_id)
            if is_mock(job):
                fail("MOCK_LIST_NOT_CONFIRMABLE", "The intervals of a demo analysis are invented and can not be confirmed", 409)
            _on_current_list(job, payload.expectedListVersion)
            counts = {status: count for status, count in db.execute(
                "SELECT status, COUNT(*) FROM rallies WHERE job_id=? GROUP BY status", (job_id,))}
            still_open, unresolved, verified = (counts.get(status, 0) for status in ("PROPOSED", "UNRESOLVED", "VERIFIED"))
            if still_open or unresolved or not verified:
                # The user confirms a list by its score, and there is no score while a winner is unknown.
                fail("LIST_NOT_READY", "Every rally needs a winner before the list can be confirmed", 409,
                     open=still_open, unresolved=unresolved, verified=verified)
            if list_confirmed(job):
                return {"job": job_payload(db, job), "reportRevision": job["revision"]}
            db.execute("UPDATE analysis_jobs SET list_confirmed_version=rally_list_version, list_confirmed_at=? WHERE id=?",
                       (utc_now(), job_id))
            return _record(db, job, new_id(), CONFIRM_LIST, "[]", "[]", moves_list=False)

    return api
