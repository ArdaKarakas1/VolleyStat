"""One pass through the HTTP API with the demo analyzer, the way the Android client uses it.

The clip is an ffmpeg test pattern and the demo analyzer invents its intervals, so nothing here
describes a match. The answers below are test input; the test checks that the report is exactly
what follows from them.
"""
import hashlib

import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from tests.helpers import wait_for_state

DEMO = {"id": "demo", "version": "0.1", "kind": "MOCK"}
UNKNOWN_VALUE = {"value": None, "status": "UNKNOWN"}
TEAM_FIELDS = ("points", "attackPoints", "blockPoints", "aces", "opponentErrors")
# The demo analyzer places an 8 s interval every 20 s, starting at 4 s. A 56 s clip holds three.
DEMO_SPANS = [(4_000, 12_000), (24_000, 32_000), (44_000, 52_000)]
DEMO_CLIPS = [(6_000, 14_000), (26_000, 34_000), (46_000, 54_000)]


def measured(value):
    return {"value": value, "status": "MEASURED"}


def partial(value):
    return {"value": value, "status": "PARTIAL"}


CASES = {
    "every rally gets a winner": {
        "answers": ("HOME", "AWAY", "HOME"),
        "rallyStatuses": ["VERIFIED", "VERIFIED", "VERIFIED"],
        "verificationStatus": "VERIFIED",
        "rallies": {"proposed": 3, "verified": 3, "rejected": 0, "unresolved": 0, "open": 0},
        "score": {"status": "MEASURED", "setsWon": {"home": 0, "away": 0},
                  "sets": [{"number": 1, "home": 2, "away": 1, "complete": False}]},
        "points": {"home": measured(2), "away": measured(1)},
        "unattributed": {"home": 2, "away": 1},
        "winnerCoverage": {"metricId": "RALLY_WINNER", "eligibleN": 3, "observedN": 3, "verifiedN": 3, "status": "MEASURED"},
    },
    "one winner, one unsure, one rejected": {
        "answers": ("AWAY", "UNSURE", "NOT_A_RALLY"),
        "rallyStatuses": ["VERIFIED", "UNRESOLVED", "REJECTED"],
        "verificationStatus": "PARTIALLY_VERIFIED",
        "rallies": {"proposed": 3, "verified": 1, "rejected": 1, "unresolved": 1, "open": 0},
        "score": {"status": "PARTIAL", "setsWon": {"home": 0, "away": 0},
                  "sets": [{"number": 1, "home": 0, "away": 1, "complete": False}]},
        "points": {"home": UNKNOWN_VALUE, "away": partial(1)},
        "unattributed": {"home": 0, "away": 1},
        "winnerCoverage": {"metricId": "RALLY_WINNER", "eligibleN": 2, "observedN": 1, "verifiedN": 1, "status": "PARTIAL"},
    },
    "nothing was a rally": {
        "answers": ("NOT_A_RALLY", "NOT_A_RALLY", "NOT_A_RALLY"),
        "rallyStatuses": ["REJECTED", "REJECTED", "REJECTED"],
        "verificationStatus": "VERIFIED",
        "rallies": {"proposed": 3, "verified": 0, "rejected": 3, "unresolved": 0, "open": 0},
        "score": {"status": "UNKNOWN", "setsWon": {"home": None, "away": None}, "sets": []},
        "points": {"home": UNKNOWN_VALUE, "away": UNKNOWN_VALUE},
        "unattributed": {"home": 0, "away": 0},
        "winnerCoverage": {"metricId": "RALLY_WINNER", "eligibleN": 0, "observedN": 0, "verifiedN": 0, "status": "UNKNOWN"},
    },
}


def upload(client, match_id, data):
    session = client.post(f"/v1/matches/{match_id}/uploads", json={
        "fileName": "mac.mp4", "sizeBytes": len(data), "mimeType": "video/mp4", "rightsAttested": True,
        "allowTraining": False, "clientSha256": hashlib.sha256(data).hexdigest()})
    assert session.status_code == 201, session.text
    upload_id = session.json()["uploadId"]
    half = len(data) // 2
    assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=data[:half]).json()["nextOffset"] == half
    assert client.put(f"/v1/uploads/{upload_id}/chunks?offset={half}", content=data[half:]).json()["nextOffset"] == len(data)
    done = client.post(f"/v1/uploads/{upload_id}/complete")
    assert done.status_code == 200, done.text
    return done.json()


@pytest.mark.parametrize("name", CASES)
def test_match_upload_demo_analysis_review_and_report(storage, make_clip, monkeypatch, name):
    case = CASES[name]
    clip = make_clip("demo-56s.mp4", size="320x180", rate="10", seconds=56)
    # Selected the way a developer selects it; the worker thread runs as in the real server.
    monkeypatch.setenv("VOLLEYSTAT_ANALYZER", "demo")
    with TestClient(create_app(storage)) as client:
        assert client.get("/v1/health").json()["analyzer"] == DEMO

        match = client.post("/v1/matches", json={
            "homeTeamName": "A Takımı", "awayTeamName": "B Takımı", "playedOn": "2026-11-12", "kind": "TRAINING"})
        assert match.status_code == 201
        match_id = match.json()["id"]
        report_url = f"/v1/matches/{match_id}/report"
        assert client.get(report_url).json()["error"]["code"] == "REPORT_NOT_AVAILABLE"

        video = upload(client, match_id, clip)
        assert (video["status"], video["matchId"]) == ("READY", match_id)
        assert video["quality"]["media"]["durationMs"] == 56_000
        assert client.get(report_url).json()["error"]["code"] == "REPORT_NOT_AVAILABLE"

        started = client.post(f"/v1/videos/{video['id']}/analyses", headers={"Idempotency-Key": "start-1"})
        assert started.status_code == 202
        job_id = started.json()["id"]
        assert (started.json()["mock"], started.json()["analyzer"]) == (True, DEMO)

        job = wait_for_state(client, job_id, "NEEDS_REVIEW", "COMPLETE", "FAILED", "CANCELLED")
        assert job["state"] == "NEEDS_REVIEW", job
        assert (job["mock"], job["analyzer"], job["failure"]) == (True, DEMO, None)
        # The demo analyzer measures nothing, so it reports no stage and no progress.
        assert (job["stage"], job["progress"]) == (None, None)
        assert job["counts"] == {"rallies": 3, "reviewOpen": 3, "reviewTotal": 3}

        rallies = client.get(f"/v1/analyses/{job_id}/rallies").json()["rallies"]
        assert [(rally["startMs"], rally["endMs"]) for rally in rallies] == DEMO_SPANS
        for rally in rallies:
            # Invented intervals carry no winner, no confidence and say what they are.
            assert (rally["status"], rally["proposedWinner"], rally["confidence"], rally["winner"]) == ("PROPOSED", "UNKNOWN", None, None)
            assert rally["uncertaintyReasons"] == ["MOCK_DATA"]

        # --- before any answer: nothing is known, and nothing is reported as zero ---
        draft = client.get(report_url)
        assert draft.status_code == 200
        draft = draft.json()
        assert (draft["matchId"], draft["jobId"], draft["revision"]) == (match_id, job_id, 0)
        assert (draft["mock"], draft["analyzer"]) == (True, DEMO)
        assert draft["verificationStatus"] == "DRAFT"
        assert draft["score"] == {"status": "UNKNOWN", "setsWon": {"home": None, "away": None}, "sets": []}
        for side in ("home", "away"):
            assert draft["teams"][side] == {field: UNKNOWN_VALUE for field in TEAM_FIELDS}
        assert draft["players"] == []
        # What remains are counts of rallies and decisions, not values of the match:
        # three proposals, no decision on any of them, hence no point without a player either.
        assert draft["rallies"] == {"proposed": 3, "verified": 0, "rejected": 0, "unresolved": 0, "open": 3}
        assert draft["unattributed"] == {"home": 0, "away": 0}
        assert [(entry["metricId"], entry["eligibleN"], entry["observedN"], entry["verifiedN"], entry["status"])
                for entry in draft["coverage"]] == [
            ("RALLY_WINNER", 3, 0, 0, "UNKNOWN"), ("TERMINAL_ACTION", 3, 0, 0, "UNKNOWN"), ("SCORER", 3, 0, 0, "UNKNOWN")]

        # --- the SCORE cards ---
        listing = client.get(f"/v1/analyses/{job_id}/review-cards").json()
        assert listing["position"] == {"answered": 0, "total": 3}
        score_cards = listing["cards"]
        assert [(card["clipStartMs"], card["clipEndMs"]) for card in score_cards] == DEMO_CLIPS
        for sequence, card in enumerate(score_cards, start=1):
            assert (card["rallySequence"], card["questionType"], card["scope"]) == (sequence, "RALLY_WINNER", "SCORE")
            assert (card["status"], card["answer"], card["version"]) == ("OPEN", None, 1)
            assert card["options"] == ["HOME", "AWAY", "NOT_A_RALLY", "UNSURE"]
            # The demo analyzer suggests nothing; the user alone decides.
            assert (card["suggestedValue"], card["uncertaintyReasons"]) == (None, ["MOCK_DATA"])
        # The evidence of a card can be fetched in pieces from the uploaded bytes.
        evidence = client.get(f"/v1/videos/{video['id']}/content", headers={"Range": "bytes=0-1023"})
        assert evidence.status_code == 206 and evidence.content == clip[:1024]

        # --- answer every card ---
        for index, (card, selection) in enumerate(zip(score_cards, case["answers"]), start=1):
            decided = client.post(f"/v1/review-cards/{card['id']}/decisions", json={
                "selection": selection, "expectedVersion": card["version"], "idempotencyKey": f"card-{index}"})
            assert decided.status_code == 200, decided.text
            body = decided.json()
            assert (body["card"]["status"], body["card"]["answer"]) == ("ANSWERED", selection)
            assert body["reportRevision"] == index
            assert body["job"]["counts"]["reviewOpen"] == 3 - index
            assert body["job"]["state"] == ("COMPLETE" if index == 3 else "NEEDS_REVIEW")
        assert client.get(f"/v1/analyses/{job_id}/review-cards").json()["position"] == {"answered": 3, "total": 3}
        assert client.get(f"/v1/analyses/{job_id}").json()["state"] == "COMPLETE"
        after = client.get(f"/v1/analyses/{job_id}/rallies").json()["rallies"]
        assert [rally["status"] for rally in after] == case["rallyStatuses"]
        assert [rally["winner"] for rally in after] == [answer if answer in ("HOME", "AWAY") else None for answer in case["answers"]]

        # --- the report follows from exactly those answers ---
        report = client.get(report_url).json()
        assert (report["jobId"], report["revision"], report["mock"], report["analyzer"]) == (job_id, 3, True, DEMO)
        assert report["verificationStatus"] == case["verificationStatus"]
        assert report["rallies"] == case["rallies"]
        assert report["rallies"]["verified"] == sum(answer in ("HOME", "AWAY") for answer in case["answers"])
        assert report["score"] == case["score"]
        for side in ("home", "away"):
            assert report["teams"][side]["points"] == case["points"][side]
            # No TERMINAL_ACTION card was answered: the kinds of points are unknown, not zero.
            assert all(report["teams"][side][field] == UNKNOWN_VALUE for field in TEAM_FIELDS[1:])
        assert report["players"] == []
        assert report["unattributed"] == case["unattributed"]
        assert report["coverage"][0] == case["winnerCoverage"]
        assert [entry["verifiedN"] for entry in report["coverage"][1:]] == [0, 0]
        assert [entry["status"] for entry in report["coverage"][1:]] == ["UNKNOWN", "UNKNOWN"]
