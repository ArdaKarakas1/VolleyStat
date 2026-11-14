"""Rallies, review cards, decisions and the report route (docs/API_V1.md, 4, 5 and 7).

A decision is the only thing that writes a rally's status, winner, terminal action or scorer.
The model's proposal stays in its own columns and never moves into those fields by itself.
"""
from __future__ import annotations

import json
from typing import Any, Literal

from fastapi import APIRouter
from pydantic import BaseModel, Field

from . import report
from .analyzers.base import AWAY, HOME
from .analyzers.demo import MOCK_DATA
from .db import new_id, utc_now
from .errors import fail
from .jobs import COMPLETE, NEEDS_REVIEW, analyzer_payload, find_job, is_mock, job_payload, list_confirmed

RALLY_WINNER = "RALLY_WINNER"
TERMINAL_ACTION = "TERMINAL_ACTION"
SCORER = "SCORER"
SCORING_ACTIONS = ("ATTACK", "BLOCK", "ACE")

# question type -> (scope, options). The order of the keys is the order of the cards of one rally.
QUESTIONS: dict[str, tuple[str, tuple[str, ...]]] = {
    RALLY_WINNER: ("SCORE", (HOME, AWAY, "NOT_A_RALLY", "UNSURE")),
    TERMINAL_ACTION: ("DETAIL", (*SCORING_ACTIONS, "OPPONENT_ERROR", "UNSURE")),
    SCORER: ("DETAIL", (*(f"JERSEY:{number}" for number in range(1, 100)), "UNKNOWN_PLAYER")),
}
QUESTION_ORDER = tuple(QUESTIONS)
RALLY_STATUS = {HOME: "VERIFIED", AWAY: "VERIFIED", "NOT_A_RALLY": "REJECTED", "UNSURE": "UNRESOLVED", None: "PROPOSED"}

CARD_QUERY = (
    "SELECT c.id, c.job_id, c.rally_id, c.question_type, c.status, c.answer, c.version,"
    " r.sequence, r.clip_start_ms, r.clip_end_ms, r.proposed_winner, r.uncertainty_json"
    " FROM review_cards c JOIN rallies r ON r.id=c.rally_id"
)


class DecisionInput(BaseModel):
    # Unbounded on purpose: any string that is not an option must reach INVALID_SELECTION.
    selection: str
    expectedVersion: int = Field(ge=1, strict=True)
    idempotencyKey: str | None = Field(default=None, min_length=1, max_length=128)


class ReopenInput(BaseModel):
    expectedVersion: int = Field(ge=1, strict=True)


def rally_payload(rally, after: dict[str, int] | None) -> dict[str, Any]:
    scorer = None
    if rally["scorer_jersey"] is not None:
        scorer = {"side": rally["winner"], "jersey": rally["scorer_jersey"]}
    return {
        "id": rally["id"], "sequence": rally["sequence"], "startMs": rally["start_ms"], "endMs": rally["end_ms"],
        # A rally without a proposal behind it was made by the user: added, or cut off by a split.
        "origin": "ANALYZER" if rally["proposal_id"] else "USER",
        "status": rally["status"], "proposedWinner": rally["proposed_winner"], "confidence": rally["confidence"],
        "winner": rally["winner"], "terminalAction": rally["terminal_action"], "scorer": scorer,
        "uncertaintyReasons": json.loads(rally["uncertainty_json"]),
        "scoreAfter": after,
    }


def rally_payloads(db, job_id: str) -> list[dict[str, Any]]:
    """Every rally of a job in match order, each with the set score after it where the count reaches it."""
    rallies = db.execute("SELECT * FROM rallies WHERE job_id=? ORDER BY sequence", (job_id,)).fetchall()
    after = report.score_after([dict(rally) for rally in rallies])
    return [rally_payload(rally, after.get(rally["sequence"])) for rally in rallies]


def card_payload(card, mock: bool) -> dict[str, Any]:
    scope, options = QUESTIONS[card["question_type"]]
    if card["question_type"] == RALLY_WINNER:
        suggested = card["proposed_winner"] if card["proposed_winner"] in (HOME, AWAY) else None
        reasons = json.loads(card["uncertainty_json"])
    else:
        # No model proposes an action or a scorer. The clip of a mock job is still invented.
        suggested = None
        reasons = [MOCK_DATA] if mock else []
    return {
        "id": card["id"], "rallyId": card["rally_id"], "rallySequence": card["sequence"],
        "questionType": card["question_type"], "scope": scope,
        "clipStartMs": card["clip_start_ms"], "clipEndMs": card["clip_end_ms"],
        "suggestedValue": suggested, "uncertaintyReasons": reasons,
        "options": list(options),
        "status": card["status"], "answer": card["answer"], "version": card["version"],
    }


def find_card(db, card_id: str):
    card = db.execute(CARD_QUERY + " WHERE c.id=?", (card_id,)).fetchone()
    if not card:
        fail("CARD_NOT_FOUND", "Review card does not exist", 404)
    return card


def _check_version(card, expected: int) -> None:
    if card["version"] != expected:
        fail("REVIEW_STALE_VERSION", "The card changed since it was read; fetch it again", 409, currentVersion=card["version"])


def _audit(db, card, action: str, new_value: str | None, decision_id: str | None = None,
           idempotency_key: str | None = None, response: dict[str, Any] | None = None,
           correction_id: str | None = None) -> None:
    db.execute(
        "INSERT INTO review_decisions(id, job_id, rally_id, card_id, question_type, action, previous_value, new_value,"
        " idempotency_key, response_json, created_at, correction_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        (decision_id or new_id(), card["job_id"], card["rally_id"], card["id"], card["question_type"], action,
         card["answer"], new_value, idempotency_key, json.dumps(response) if response else None, utc_now(), correction_id),
    )


def _drop_cards(db, rally_id: str, question_types: tuple[str, ...], correction_id: str | None = None) -> None:
    """Remove cards whose question no longer applies. Their history stays in the audit table."""
    for question_type in question_types:
        card = db.execute(
            "SELECT * FROM review_cards WHERE rally_id=? AND question_type=?", (rally_id, question_type)
        ).fetchone()
        if card is None:
            continue
        if card["answer"] is not None:
            _audit(db, card, "REMOVED", None, correction_id=correction_id)
        db.execute("DELETE FROM review_cards WHERE id=?", (card["id"],))


def _add_card(db, card, question_type: str) -> None:
    # OR IGNORE: a card that already exists keeps its answer and its version.
    db.execute(
        "INSERT OR IGNORE INTO review_cards(id, job_id, rally_id, question_type) VALUES(?,?,?,?)",
        (new_id(), card["job_id"], card["rally_id"], question_type),
    )


def _set_answer(db, card, selection: str | None, correction_id: str | None = None) -> None:
    """Write an answer (None reopens the card) and keep the rally and its dependent cards consistent.

    `correction_id` marks the audit rows of answers that go away because of a rally correction.
    """
    rally_id = card["rally_id"]
    if card["question_type"] == RALLY_WINNER:
        winner = selection if selection in (HOME, AWAY) else None
        previous = card["answer"] if card["answer"] in (HOME, AWAY) else None
        db.execute("UPDATE rallies SET status=?, winner=? WHERE id=?", (RALLY_STATUS[selection], winner, rally_id))
        if winner != previous:
            # An action and a scorer describe a point of one side; they say nothing once that side changes.
            _drop_cards(db, rally_id, (TERMINAL_ACTION, SCORER), correction_id)
            db.execute("UPDATE rallies SET terminal_action=NULL, scorer_jersey=NULL WHERE id=?", (rally_id,))
            if winner is not None:
                _add_card(db, card, TERMINAL_ACTION)
    elif card["question_type"] == TERMINAL_ACTION:
        action = selection if selection in (*SCORING_ACTIONS, "OPPONENT_ERROR") else None
        db.execute("UPDATE rallies SET terminal_action=? WHERE id=?", (action, rally_id))
        if action in SCORING_ACTIONS:
            _add_card(db, card, SCORER)
        else:
            # An opponent error has no scorer on the winning side, and an unknown action can not have one yet.
            _drop_cards(db, rally_id, (SCORER,), correction_id)
            db.execute("UPDATE rallies SET scorer_jersey=NULL WHERE id=?", (rally_id,))
    else:
        jersey = int(selection.removeprefix("JERSEY:")) if selection and selection.startswith("JERSEY:") else None
        db.execute("UPDATE rallies SET scorer_jersey=? WHERE id=?", (jersey, rally_id))
    db.execute(
        "UPDATE review_cards SET status=?, answer=?, version=version+1 WHERE id=?",
        ("ANSWERED" if selection is not None else "OPEN", selection, card["id"]),
    )


def _change(db, card, selection: str | None, action: str, idempotency_key: str | None) -> dict[str, Any]:
    decision_id = new_id()
    _set_answer(db, card, selection)
    still_open = db.execute(
        "SELECT COUNT(*) FROM review_cards WHERE job_id=? AND question_type=? AND status='OPEN'", (card["job_id"], RALLY_WINNER)
    ).fetchone()[0]
    # Cards exist only on jobs whose run ended with rallies, so the job is in one of these two states.
    # A winner decides which rallies count and for whom, so it moves the rally list on: a list the
    # user confirmed earlier is no longer the list that was confirmed. An action or a scorer is not.
    db.execute(
        "UPDATE analysis_jobs SET revision=revision+1, rally_list_version=rally_list_version+?, state=? WHERE id=?",
        (int(card["question_type"] == RALLY_WINNER), NEEDS_REVIEW if still_open else COMPLETE, card["job_id"]),
    )
    job = find_job(db, card["job_id"])
    response = {
        "decisionId": decision_id,
        "card": card_payload(find_card(db, card["id"]), is_mock(job)),
        "job": job_payload(db, job),
        "reportRevision": job["revision"],
    }
    # The stored response is what a retry with the same idempotency key gets back.
    _audit(db, card, action, selection, decision_id, idempotency_key, response if idempotency_key else None)
    return response


def router(connect) -> APIRouter:
    api = APIRouter()

    @api.get("/v1/analyses/{job_id}/rallies")
    def list_rallies(job_id: str):
        with connect() as db:
            job = find_job(db, job_id)
            media = db.execute("SELECT media_json FROM videos WHERE id=?", (job["video_id"],)).fetchone()
            return {
                "rallies": rally_payloads(db, job_id),
                "rallyListVersion": job["rally_list_version"], "listConfirmed": list_confirmed(job),
                # The bound of every interval a correction may name.
                "videoDurationMs": int(json.loads(media["media_json"])["durationMs"]),
            }

    @api.get("/v1/analyses/{job_id}/review-cards")
    def list_cards(job_id: str, scope: Literal["SCORE", "DETAIL", "ALL"] = "SCORE"):
        with connect() as db:
            job = find_job(db, job_id)
            rows = db.execute(CARD_QUERY + " WHERE c.job_id=?", (job_id,)).fetchall()
        cards = [card_payload(row, is_mock(job)) for row in rows]
        cards = [card for card in cards if scope in ("ALL", card["scope"])]
        cards.sort(key=lambda card: (card["rallySequence"], QUESTION_ORDER.index(card["questionType"])))
        answered = sum(card["status"] == "ANSWERED" for card in cards)
        return {"cards": cards, "position": {"answered": answered, "total": len(cards)}}

    @api.post("/v1/review-cards/{card_id}/decisions")
    def decide(card_id: str, payload: DecisionInput):
        with connect(write=True) as db:
            if payload.idempotencyKey is not None:
                # Looked up before the card: a later decision may have removed the card or moved its version.
                earlier = db.execute(
                    "SELECT response_json FROM review_decisions WHERE idempotency_key=? ORDER BY rowid LIMIT 1",
                    (payload.idempotencyKey,),
                ).fetchone()
                if earlier:
                    stored = json.loads(earlier["response_json"])
                    answered = stored["card"]
                    # A decision moves its card exactly one version on, so the stored answer also tells
                    # which version its request expected.
                    if (answered["id"], answered["answer"], answered["version"] - 1) != (
                            card_id, payload.selection, payload.expectedVersion):
                        # Replaying the old answer here would report a decision as stored that never was.
                        fail("IDEMPOTENCY_KEY_REUSED", "This idempotency key already belongs to another decision", 409)
                    return stored
            card = find_card(db, card_id)
            _check_version(card, payload.expectedVersion)
            if payload.selection not in QUESTIONS[card["question_type"]][1]:
                fail("INVALID_SELECTION", "Selection is not one of the options of this card", 422)
            return _change(db, card, payload.selection, "DECISION", payload.idempotencyKey)

    @api.post("/v1/review-cards/{card_id}/reopen")
    def reopen(card_id: str, payload: ReopenInput):
        with connect(write=True) as db:
            card = find_card(db, card_id)
            _check_version(card, payload.expectedVersion)
            if card["status"] == "OPEN":
                # Nothing to undo: no version bump, no revision bump and no audit row.
                job = find_job(db, card["job_id"])
                return {"card": card_payload(card, is_mock(job)), "job": job_payload(db, job), "reportRevision": job["revision"]}
            response = _change(db, card, None, "REOPEN", None)
            # The reopen is recorded in the audit table, but the contract returns no decisionId for it.
            del response["decisionId"]
            return response

    @api.get("/v1/matches/{match_id}/report")
    def match_report(match_id: str):
        with connect() as db:
            if not db.execute("SELECT 1 FROM matches WHERE id=?", (match_id,)).fetchone():
                fail("MATCH_NOT_FOUND", "Match does not exist", 404)
            # A newer job that is still running, failed or was cancelled does not hide an earlier result.
            job = db.execute(
                "SELECT * FROM analysis_jobs WHERE match_id=? AND state IN (?,?) ORDER BY rowid DESC LIMIT 1",
                (match_id, NEEDS_REVIEW, COMPLETE),
            ).fetchone()
            if not job:
                fail("REPORT_NOT_AVAILABLE", "No analysis of this match has produced rallies yet", 404)
            rallies = [
                dict(rally) for rally in db.execute(
                    "SELECT sequence, status, proposed_winner, winner, terminal_action, scorer_jersey,"
                    " proposal_id IS NULL AS user_created FROM rallies WHERE job_id=? ORDER BY sequence", (job["id"],))
            ]
            proposed = db.execute("SELECT COUNT(*) FROM rally_proposals WHERE job_id=?", (job["id"],)).fetchone()[0]
        confirmed = list_confirmed(job)
        return {
            "matchId": match_id, "jobId": job["id"], "revision": job["revision"],
            "analyzer": analyzer_payload(job), "mock": is_mock(job),
            "rallyList": {"confirmed": confirmed, "confirmedAt": job["list_confirmed_at"] if confirmed else None},
            # No analyzer finds every rally, whatever its kind. The list is complete only by the
            # user's own confirmation, and the invented intervals of a demo job can not be confirmed.
            **report.build_report(rallies, rallies_may_be_missing=is_mock(job) or not confirmed, proposed=proposed),
        }

    return api
