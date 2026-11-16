"""Rallies, review cards, decisions and the audit trail (docs/API_V1.md, sections 4 and 5).

The rallies come from a scripted analyzer. The decisions are made by the test, not by a viewer of
a real match.
"""
import re
import sqlite3

import pytest

from app.analyzers.base import MOCK, MODEL, AnalyzerInfo, RallyProposal
from app.jobs import clip_bounds
from tests.helpers import SPANS, ScriptedAnalyzer, card_of, cards, decide, execute, reviewed_job, rows, serve

WINNER_OPTIONS = ["HOME", "AWAY", "NOT_A_RALLY", "UNSURE"]
ACTION_OPTIONS = ["ATTACK", "BLOCK", "ACE", "OPPONENT_ERROR", "UNSURE"]
SCORER_OPTIONS = [f"JERSEY:{number}" for number in range(1, 100)] + ["UNKNOWN_PLAYER"]
TIMESTAMP = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z")


@pytest.fixture
def review(storage, make_clip):
    """A client, the id of a job in NEEDS_REVIEW with four proposed rallies, and the storage folder."""
    with serve(storage, ScriptedAnalyzer()) as client:
        video, job = reviewed_job(client, make_clip)
        yield client, job["id"], storage


def rally(client, job_id, sequence):
    return client.get(f"/v1/analyses/{job_id}/rallies").json()["rallies"][sequence - 1]


def job_of(client, job_id):
    return client.get(f"/v1/analyses/{job_id}").json()


def post_decision(client, card_id, **body):
    return client.post(f"/v1/review-cards/{card_id}/decisions", json=body)


def reopen(client, card_id, expected_version):
    return client.post(f"/v1/review-cards/{card_id}/reopen", json={"expectedVersion": expected_version})


def audit(storage):
    return rows(storage, "SELECT * FROM review_decisions ORDER BY rowid")


def revision(storage, job_id):
    return rows(storage, "SELECT revision FROM analysis_jobs WHERE id=?", job_id)[0]["revision"]


def detail_questions(client, job_id, sequence):
    return [card["questionType"] for card in cards(client, job_id, "DETAIL") if card["rallySequence"] == sequence]


def answer_details(client, job_id, sequence, winner="HOME", action="ATTACK", scorer="JERSEY:7"):
    """Decide the winner, the action and the scorer of one rally."""
    decide(client, card_of(client, job_id, sequence), winner)
    decide(client, card_of(client, job_id, sequence, "TERMINAL_ACTION"), action)
    decide(client, card_of(client, job_id, sequence, "SCORER"), scorer)


# --- rallies ---------------------------------------------------------------------------------

def test_rallies_are_listed_in_order_as_undecided_proposals(review):
    client, job_id, _ = review
    rallies = client.get(f"/v1/analyses/{job_id}/rallies").json()["rallies"]
    assert len(rallies) == len(SPANS)
    for sequence, (found, (start, end)) in enumerate(zip(rallies, SPANS), start=1):
        assert found == {
            "id": found["id"], "sequence": sequence, "startMs": start, "endMs": end, "origin": "ANALYZER",
            "status": "PROPOSED", "proposedWinner": "UNKNOWN", "confidence": None,
            "winner": None, "terminalAction": None, "scorer": None,
            "uncertaintyReasons": ["NO_WINNER_MODEL"], "scoreAfter": None,
        }
    assert len({found["id"] for found in rallies}) == len(SPANS)


def test_a_model_proposal_and_the_user_decision_are_kept_apart(storage, make_clip):
    proposing = ScriptedAnalyzer(
        result=[RallyProposal(100, 400, proposed_winner="HOME", confidence=0.75, uncertainty_reasons=("LOW_LIGHT",)),
                RallyProposal(500, 900)],
        info=AnalyzerInfo("scripted", "t1", MODEL))
    with serve(storage, proposing) as client:
        _, job = reviewed_job(client, make_clip)
        proposed = rally(client, job["id"], 1)
        # The proposal alone decides nothing.
        assert (proposed["proposedWinner"], proposed["confidence"]) == ("HOME", 0.75)
        assert (proposed["status"], proposed["winner"]) == ("PROPOSED", None)
        card, other = cards(client, job["id"], "SCORE")
        assert (card["suggestedValue"], card["answer"], card["status"]) == ("HOME", None, "OPEN")
        assert card["uncertaintyReasons"] == ["LOW_LIGHT"]
        # No proposal means no suggestion: UNKNOWN is not offered as a value.
        assert (other["suggestedValue"], other["uncertaintyReasons"]) == (None, [])

        decided = decide(client, card, "AWAY")["card"]
        assert (decided["suggestedValue"], decided["answer"]) == ("HOME", "AWAY")
        after = rally(client, job["id"], 1)
        assert (after["proposedWinner"], after["confidence"], after["winner"], after["status"]) == ("HOME", 0.75, "AWAY", "VERIFIED")


# --- cards -----------------------------------------------------------------------------------

@pytest.mark.parametrize("start, end, duration, expected", [
    (41_200, 52_800, 3_600_000, (46_800, 54_800)),  # the example of the contract
    (50_000, 52_800, 3_600_000, (50_000, 54_800)),  # a rally shorter than six seconds is shown from its start
    (46_800, 52_800, 3_600_000, (46_800, 54_800)),
    (41_200, 52_800, 53_500, (46_800, 53_500)),     # the video ends before the two seconds are over
    (0, 1_000, 1_000, (0, 1_000)),
])
def test_the_clip_shows_the_end_of_the_rally(start, end, duration, expected):
    assert clip_bounds(start, end, duration) == expected


def test_every_rally_gets_one_score_card(storage, make_clip):
    with serve(storage, ScriptedAnalyzer()) as client:
        video, job = reviewed_job(client, make_clip)
        duration = video["quality"]["media"]["durationMs"]
        rallies = client.get(f"/v1/analyses/{job['id']}/rallies").json()["rallies"]
        listing = client.get(f"/v1/analyses/{job['id']}/review-cards").json()
        assert listing["position"] == {"answered": 0, "total": len(SPANS)}
        assert len(listing["cards"]) == len(SPANS)
        for found, source in zip(listing["cards"], rallies):
            assert found == {
                "id": found["id"], "rallyId": source["id"], "rallySequence": source["sequence"],
                "questionType": "RALLY_WINNER", "scope": "SCORE",
                # Rallies of this clip are shorter than six seconds, and the clip ends with the video.
                "clipStartMs": source["startMs"], "clipEndMs": duration,
                "suggestedValue": None, "uncertaintyReasons": ["NO_WINNER_MODEL"],
                "options": WINNER_OPTIONS,
                "status": "OPEN", "answer": None, "version": 1,
            }
        assert client.get(f"/v1/analyses/{job['id']}/review-cards", params={"scope": "SCORE"}).json() == listing
        assert client.get(f"/v1/analyses/{job['id']}/review-cards", params={"scope": "ALL"}).json() == listing
        assert client.get(f"/v1/analyses/{job['id']}/review-cards", params={"scope": "DETAIL"}).json() == {
            "cards": [], "position": {"answered": 0, "total": 0}}


def test_an_unknown_scope_is_a_validation_error(review):
    client, job_id, _ = review
    for scope in ("score", "EVERYTHING", ""):
        refused = client.get(f"/v1/analyses/{job_id}/review-cards", params={"scope": scope})
        assert refused.status_code == 422 and refused.json()["error"]["code"] == "VALIDATION_ERROR"


# --- winner decisions ------------------------------------------------------------------------

def test_a_winner_decision_verifies_the_rally(review):
    client, job_id, storage = review
    card = card_of(client, job_id, 2)
    result = decide(client, card, "AWAY")
    assert set(result) == {"decisionId", "card", "job", "reportRevision"}
    assert result["card"] == {**card, "status": "ANSWERED", "answer": "AWAY", "version": 2}
    assert result["job"] == job_of(client, job_id)
    assert result["job"]["state"] == "NEEDS_REVIEW"
    assert result["job"]["counts"] == {"rallies": 4, "proposed": 4, "reviewOpen": 3, "reviewTotal": 4}
    assert result["reportRevision"] == 1 == revision(storage, job_id)

    decided = rally(client, job_id, 2)
    assert (decided["status"], decided["winner"], decided["proposedWinner"]) == ("VERIFIED", "AWAY", "UNKNOWN")
    assert (decided["terminalAction"], decided["scorer"]) == (None, None)
    assert [rally(client, job_id, sequence)["status"] for sequence in (1, 3, 4)] == ["PROPOSED"] * 3
    assert client.get(f"/v1/analyses/{job_id}/review-cards").json()["position"] == {"answered": 1, "total": 4}


@pytest.mark.parametrize("selection, status", [("NOT_A_RALLY", "REJECTED"), ("UNSURE", "UNRESOLVED")])
def test_an_answer_without_a_winner_closes_the_card_and_asks_no_detail(review, selection, status):
    client, job_id, _ = review
    result = decide(client, card_of(client, job_id, 1), selection)
    assert (result["card"]["status"], result["card"]["answer"]) == ("ANSWERED", selection)
    decided = rally(client, job_id, 1)
    assert (decided["status"], decided["winner"]) == (status, None)
    assert cards(client, job_id, "DETAIL") == []


def test_the_job_is_complete_when_the_last_score_card_is_answered(review):
    client, job_id, _ = review
    states = [decide(client, card, "HOME")["job"]["state"] for card in cards(client, job_id, "SCORE")]
    assert states == ["NEEDS_REVIEW", "NEEDS_REVIEW", "NEEDS_REVIEW", "COMPLETE"]
    done = job_of(client, job_id)
    # Four detail cards are open. They are optional and do not hold the job back.
    assert [card["status"] for card in cards(client, job_id, "DETAIL")] == ["OPEN"] * 4
    assert (done["state"], done["counts"]) == ("COMPLETE", {"rallies": 4, "proposed": 4, "reviewOpen": 0, "reviewTotal": 4})
    refused = client.post(f"/v1/analyses/{job_id}/cancel")
    assert refused.status_code == 409 and refused.json()["error"]["details"] == {"state": "COMPLETE"}


def test_unsure_and_rejected_answers_also_complete_the_job(review):
    client, job_id, _ = review
    for card, selection in zip(cards(client, job_id, "SCORE"), ("UNSURE", "NOT_A_RALLY", "HOME", "UNSURE")):
        last = decide(client, card, selection)
    assert last["job"]["state"] == "COMPLETE"


# --- detail cards ----------------------------------------------------------------------------

def test_a_winner_creates_the_terminal_action_card(review):
    client, job_id, _ = review
    winner_card = decide(client, card_of(client, job_id, 3), "HOME")["card"]
    assert client.get(f"/v1/analyses/{job_id}/review-cards").json()["cards"][2] == winner_card
    detail = client.get(f"/v1/analyses/{job_id}/review-cards", params={"scope": "DETAIL"}).json()
    assert detail["position"] == {"answered": 0, "total": 1}
    assert detail["cards"] == [{
        "id": detail["cards"][0]["id"], "rallyId": winner_card["rallyId"], "rallySequence": 3,
        "questionType": "TERMINAL_ACTION", "scope": "DETAIL",
        "clipStartMs": winner_card["clipStartMs"], "clipEndMs": winner_card["clipEndMs"],
        "suggestedValue": None, "uncertaintyReasons": [],
        "options": ACTION_OPTIONS,
        "status": "OPEN", "answer": None, "version": 1,
    }]
    # Cards come in rally order; the cards of one rally follow the order of their questions.
    assert [(card["rallySequence"], card["questionType"]) for card in cards(client, job_id, "ALL")] == [
        (1, "RALLY_WINNER"), (2, "RALLY_WINNER"), (3, "RALLY_WINNER"), (3, "TERMINAL_ACTION"), (4, "RALLY_WINNER")]
    assert client.get(f"/v1/analyses/{job_id}/review-cards", params={"scope": "ALL"}).json()["position"] == {"answered": 1, "total": 5}


@pytest.mark.parametrize("action", ["ATTACK", "BLOCK", "ACE"])
def test_a_scoring_action_creates_the_scorer_card(review, action):
    client, job_id, _ = review
    decide(client, card_of(client, job_id, 1), "AWAY")
    answered = decide(client, card_of(client, job_id, 1, "TERMINAL_ACTION"), action)
    assert (answered["card"]["status"], answered["card"]["answer"], answered["card"]["version"]) == ("ANSWERED", action, 2)
    assert rally(client, job_id, 1)["terminalAction"] == action
    scorer = card_of(client, job_id, 1, "SCORER")
    assert (scorer["scope"], scorer["status"], scorer["answer"], scorer["version"]) == ("DETAIL", "OPEN", None, 1)
    assert scorer["options"] == SCORER_OPTIONS
    assert (scorer["clipStartMs"], scorer["clipEndMs"]) == (answered["card"]["clipStartMs"], answered["card"]["clipEndMs"])
    assert [card["questionType"] for card in cards(client, job_id, "ALL") if card["rallySequence"] == 1] == [
        "RALLY_WINNER", "TERMINAL_ACTION", "SCORER"]


@pytest.mark.parametrize("selection, stored", [("OPPONENT_ERROR", "OPPONENT_ERROR"), ("UNSURE", None)])
def test_an_action_without_a_scorer_creates_no_scorer_card(review, selection, stored):
    client, job_id, _ = review
    decide(client, card_of(client, job_id, 1), "HOME")
    decide(client, card_of(client, job_id, 1, "TERMINAL_ACTION"), selection)
    assert rally(client, job_id, 1)["terminalAction"] == stored
    assert detail_questions(client, job_id, 1) == ["TERMINAL_ACTION"]


def test_a_jersey_decision_names_the_scorer_by_side_and_number(review):
    client, job_id, _ = review
    answer_details(client, job_id, 1, winner="AWAY", action="BLOCK", scorer="JERSEY:99")
    assert rally(client, job_id, 1)["scorer"] == {"side": "AWAY", "jersey": 99}
    assert client.get(f"/v1/analyses/{job_id}/review-cards", params={"scope": "DETAIL"}).json()["position"] == {"answered": 2, "total": 2}


def test_an_unknown_player_answers_the_card_without_naming_a_scorer(review):
    client, job_id, _ = review
    answer_details(client, job_id, 1, scorer="UNKNOWN_PLAYER")
    scorer = card_of(client, job_id, 1, "SCORER")
    assert (scorer["status"], scorer["answer"]) == ("ANSWERED", "UNKNOWN_PLAYER")
    assert rally(client, job_id, 1)["scorer"] is None


def test_detail_decisions_do_not_change_the_job_state(review):
    client, job_id, storage = review
    for card in cards(client, job_id, "SCORE"):
        decide(client, card, "HOME")
    action = decide(client, card_of(client, job_id, 1, "TERMINAL_ACTION"), "ACE")
    assert (action["job"]["state"], action["reportRevision"]) == ("COMPLETE", 5)
    undone = reopen(client, action["card"]["id"], action["card"]["version"]).json()
    assert (undone["job"]["state"], undone["reportRevision"]) == ("COMPLETE", 6)


# --- corrections -----------------------------------------------------------------------------

def test_a_changed_winner_removes_the_detail_cards_and_their_answers(review):
    client, job_id, storage = review
    answer_details(client, job_id, 1, winner="HOME", action="ATTACK", scorer="JERSEY:7")
    old_cards = {card["questionType"]: card for card in cards(client, job_id, "DETAIL")}
    assert rally(client, job_id, 1)["scorer"] == {"side": "HOME", "jersey": 7}

    corrected = decide(client, card_of(client, job_id, 1), "AWAY")
    assert (corrected["card"]["answer"], corrected["card"]["version"]) == ("AWAY", 3)
    after = rally(client, job_id, 1)
    assert (after["status"], after["winner"], after["terminalAction"], after["scorer"]) == ("VERIFIED", "AWAY", None, None)
    # The point now belongs to the other side: its action is asked again, on a fresh card.
    [fresh] = cards(client, job_id, "DETAIL")
    assert (fresh["questionType"], fresh["status"], fresh["answer"], fresh["version"]) == ("TERMINAL_ACTION", "OPEN", None, 1)
    assert fresh["id"] not in {card["id"] for card in old_cards.values()}
    for card in old_cards.values():
        gone = post_decision(client, card["id"], selection="UNSURE", expectedVersion=card["version"])
        assert gone.status_code == 404 and gone.json()["error"]["code"] == "CARD_NOT_FOUND"
    # The removed answers stay in the audit trail.
    removed = [(row["question_type"], row["previous_value"], row["new_value"]) for row in audit(storage) if row["action"] == "REMOVED"]
    assert removed == [("TERMINAL_ACTION", "ATTACK", None), ("SCORER", "JERSEY:7", None)]


@pytest.mark.parametrize("selection, status", [("UNSURE", "UNRESOLVED"), ("NOT_A_RALLY", "REJECTED")])
def test_a_withdrawn_winner_removes_the_detail_cards_without_new_ones(review, selection, status):
    client, job_id, _ = review
    answer_details(client, job_id, 1)
    decide(client, card_of(client, job_id, 1), selection)
    after = rally(client, job_id, 1)
    assert (after["status"], after["winner"], after["terminalAction"], after["scorer"]) == (status, None, None, None)
    assert cards(client, job_id, "DETAIL") == []


def test_the_same_winner_again_keeps_the_detail_answers(review):
    client, job_id, storage = review
    answer_details(client, job_id, 1, winner="HOME", action="BLOCK", scorer="JERSEY:4")
    before = cards(client, job_id, "DETAIL")
    again = decide(client, card_of(client, job_id, 1), "HOME")
    # The answer that is already there is no new decision: no version moves and nothing is recorded.
    assert (again["card"]["version"], again["reportRevision"], again["decisionId"]) == (2, 3, None)
    assert cards(client, job_id, "DETAIL") == before
    assert rally(client, job_id, 1)["scorer"] == {"side": "HOME", "jersey": 4}


def test_an_action_changed_to_opponent_error_removes_the_scorer(review):
    client, job_id, storage = review
    answer_details(client, job_id, 1, action="ACE", scorer="JERSEY:12")
    decide(client, card_of(client, job_id, 1, "TERMINAL_ACTION"), "OPPONENT_ERROR")
    after = rally(client, job_id, 1)
    assert (after["winner"], after["terminalAction"], after["scorer"]) == ("HOME", "OPPONENT_ERROR", None)
    assert detail_questions(client, job_id, 1) == ["TERMINAL_ACTION"]
    assert [(row["action"], row["previous_value"]) for row in audit(storage)][-2:] == [
        ("REMOVED", "JERSEY:12"), ("DECISION", "ACE")]


def test_an_action_changed_to_another_scoring_action_keeps_the_scorer(review):
    client, job_id, _ = review
    answer_details(client, job_id, 1, action="ATTACK", scorer="JERSEY:12")
    scorer_card = card_of(client, job_id, 1, "SCORER")
    decide(client, card_of(client, job_id, 1, "TERMINAL_ACTION"), "BLOCK")
    after = rally(client, job_id, 1)
    assert (after["terminalAction"], after["scorer"]) == ("BLOCK", {"side": "HOME", "jersey": 12})
    assert card_of(client, job_id, 1, "SCORER") == scorer_card


def test_a_scorer_can_be_corrected(review):
    client, job_id, _ = review
    answer_details(client, job_id, 1, scorer="JERSEY:7")
    decide(client, card_of(client, job_id, 1, "SCORER"), "JERSEY:8")
    assert rally(client, job_id, 1)["scorer"] == {"side": "HOME", "jersey": 8}
    decide(client, card_of(client, job_id, 1, "SCORER"), "UNKNOWN_PLAYER")
    assert rally(client, job_id, 1)["scorer"] is None


# --- expectedVersion and invalid selections --------------------------------------------------

def test_a_decision_on_a_stale_version_is_refused_and_changes_nothing(review):
    client, job_id, storage = review
    card = card_of(client, job_id, 1)
    decide(client, card, "HOME")
    for expected in (1, 3):
        stale = post_decision(client, card["id"], selection="AWAY", expectedVersion=expected)
        assert stale.status_code == 409
        assert stale.json()["error"]["code"] == "REVIEW_STALE_VERSION"
        assert stale.json()["error"]["details"] == {"currentVersion": 2}
    current = card_of(client, job_id, 1)
    assert (current["answer"], current["version"]) == ("HOME", 2)
    assert rally(client, job_id, 1)["winner"] == "HOME"
    assert revision(storage, job_id) == 1 and len(audit(storage)) == 1


@pytest.mark.parametrize("selection", ["DRAW", "home", "", "ATTACK", "JERSEY:7", "UNKNOWN", " HOME"])
def test_a_selection_outside_the_options_of_a_winner_card_is_refused(review, selection):
    client, job_id, storage = review
    card = card_of(client, job_id, 1)
    refused = post_decision(client, card["id"], selection=selection, expectedVersion=1)
    assert refused.status_code == 422 and refused.json()["error"]["code"] == "INVALID_SELECTION"
    assert card_of(client, job_id, 1) == card
    assert rally(client, job_id, 1)["status"] == "PROPOSED"
    assert revision(storage, job_id) == 0 and audit(storage) == []


@pytest.mark.parametrize("selection", ["JERSEY:0", "JERSEY:100", "JERSEY:07", "JERSEY:-1", "JERSEY:", "7", "HOME", "UNSURE"])
def test_a_selection_outside_the_options_of_a_scorer_card_is_refused(review, selection):
    client, job_id, _ = review
    decide(client, card_of(client, job_id, 1), "HOME")
    decide(client, card_of(client, job_id, 1, "TERMINAL_ACTION"), "ATTACK")
    card = card_of(client, job_id, 1, "SCORER")
    refused = post_decision(client, card["id"], selection=selection, expectedVersion=1)
    assert refused.status_code == 422 and refused.json()["error"]["code"] == "INVALID_SELECTION"
    assert card_of(client, job_id, 1, "SCORER") == card and rally(client, job_id, 1)["scorer"] is None


@pytest.mark.parametrize("body", [
    {"selection": "HOME"},
    {"expectedVersion": 1},
    {"selection": "HOME", "expectedVersion": "1"},
    {"selection": "HOME", "expectedVersion": 0},
    {"selection": "HOME", "expectedVersion": 1.5},
    {"selection": "HOME", "expectedVersion": 1, "idempotencyKey": ""},
    {"selection": "HOME", "expectedVersion": 1, "idempotencyKey": "k" * 129},
])
def test_a_malformed_decision_is_a_validation_error(review, body):
    client, job_id, storage = review
    refused = post_decision(client, card_of(client, job_id, 1)["id"], **body)
    assert refused.status_code == 422 and refused.json()["error"]["code"] == "VALIDATION_ERROR"
    assert audit(storage) == []


def test_a_decision_on_an_unknown_card_answers_404(review):
    client, _, _ = review
    for response in (post_decision(client, "unknown", selection="HOME", expectedVersion=1),
                     post_decision(client, "unknown", selection="HOME", expectedVersion=1, idempotencyKey="k"),
                     reopen(client, "unknown", 1)):
        assert response.status_code == 404 and response.json()["error"]["code"] == "CARD_NOT_FOUND"


# --- idempotent decisions --------------------------------------------------------------------

def test_the_same_idempotency_key_returns_the_same_answer_and_stores_one_decision(review):
    client, job_id, storage = review
    card = card_of(client, job_id, 1)
    body = {"selection": "HOME", "expectedVersion": 1, "idempotencyKey": "tap-1"}
    first = post_decision(client, card["id"], **body)
    again = post_decision(client, card["id"], **body)
    assert first.status_code == again.status_code == 200
    assert again.json() == first.json()
    assert len(audit(storage)) == 1 and revision(storage, job_id) == 1
    assert card_of(client, job_id, 1)["version"] == 2
    # Without the key the repeated request is an ordinary stale write.
    assert post_decision(client, card["id"], selection="HOME", expectedVersion=1).status_code == 409


def test_a_late_retry_gets_the_original_answer_after_the_card_moved_on(review):
    client, job_id, storage = review
    card = card_of(client, job_id, 1)
    body = {"selection": "HOME", "expectedVersion": 1, "idempotencyKey": "tap-1"}
    first = post_decision(client, card["id"], **body).json()
    decide(client, card_of(client, job_id, 1), "AWAY")
    late = post_decision(client, card["id"], **body)
    assert late.status_code == 200 and late.json() == first
    # The retry did not undo the correction.
    assert rally(client, job_id, 1)["winner"] == "AWAY" and len(audit(storage)) == 2


def test_a_retry_for_a_card_that_was_removed_since_gets_the_original_answer(review):
    client, job_id, _ = review
    decide(client, card_of(client, job_id, 1), "HOME")
    action_card = card_of(client, job_id, 1, "TERMINAL_ACTION")
    body = {"selection": "ACE", "expectedVersion": 1, "idempotencyKey": "tap-2"}
    first = post_decision(client, action_card["id"], **body).json()
    decide(client, card_of(client, job_id, 1), "AWAY")
    late = post_decision(client, action_card["id"], **body)
    assert late.status_code == 200 and late.json() == first


def reused(response):
    return response.status_code == 409 and response.json()["error"]["code"] == "IDEMPOTENCY_KEY_REUSED"


def test_a_key_reused_with_another_selection_is_refused_and_the_correction_stays(review):
    client, job_id, storage = review
    card = card_of(client, job_id, 1)
    first = post_decision(client, card["id"], selection="HOME", expectedVersion=1, idempotencyKey="card1-HOME")
    corrected = post_decision(client, card["id"], selection="AWAY", expectedVersion=2, idempotencyKey="card1-AWAY")
    assert first.status_code == corrected.status_code == 200
    # The user goes back to HOME; the client derives the same key as for the first tap.
    again = post_decision(client, card["id"], selection="HOME", expectedVersion=3, idempotencyKey="card1-HOME")
    assert reused(again), again.text
    # The answer did not claim a decision that was never stored.
    current = card_of(client, job_id, 1)
    assert (current["answer"], current["version"]) == ("AWAY", 3)
    assert rally(client, job_id, 1)["winner"] == "AWAY"
    assert len(audit(storage)) == 2 and revision(storage, job_id) == 2


@pytest.mark.parametrize("body", [
    {"selection": "AWAY", "expectedVersion": 1},
    {"selection": "HOME", "expectedVersion": 2},
    {"selection": "NOT_AN_OPTION", "expectedVersion": 999},
])
def test_a_key_reused_with_a_different_request_is_refused_and_changes_nothing(review, body):
    client, job_id, storage = review
    card = card_of(client, job_id, 1)
    first = post_decision(client, card["id"], selection="HOME", expectedVersion=1, idempotencyKey="tap-1")
    assert first.status_code == 200
    trail = audit(storage)
    assert reused(post_decision(client, card["id"], idempotencyKey="tap-1", **body))
    assert card_of(client, job_id, 1) == first.json()["card"]
    assert audit(storage) == trail and revision(storage, job_id) == 1
    # The original request is still answered with its stored response.
    replay = post_decision(client, card["id"], selection="HOME", expectedVersion=1, idempotencyKey="tap-1")
    assert replay.status_code == 200 and replay.json() == first.json()


def test_a_key_reused_on_another_card_is_refused(review):
    client, job_id, storage = review
    first = decide(client, card_of(client, job_id, 1), "HOME", idempotencyKey="tap-1")
    other = card_of(client, job_id, 2)
    assert reused(post_decision(client, other["id"], selection="HOME", expectedVersion=1, idempotencyKey="tap-1"))
    assert card_of(client, job_id, 2) == other and rally(client, job_id, 2)["status"] == "PROPOSED"
    assert [row["id"] for row in audit(storage)] == [first["decisionId"]]
    # The key is taken, whatever the request names: an unknown card does not free it.
    assert reused(post_decision(client, "unknown", selection="HOME", expectedVersion=1, idempotencyKey="tap-1"))
    # With its own key the second card is decided as usual.
    assert decide(client, other, "AWAY", idempotencyKey="tap-2")["card"]["answer"] == "AWAY"


# --- reopen ----------------------------------------------------------------------------------

def test_reopen_returns_the_card_to_open_and_the_rally_to_proposed(review):
    client, job_id, storage = review
    card = card_of(client, job_id, 1)
    decide(client, card, "HOME")
    decide(client, card_of(client, job_id, 1, "TERMINAL_ACTION"), "ACE")

    response = reopen(client, card["id"], 2)
    assert response.status_code == 200
    body = response.json()
    # The shape of a decision response, without a decisionId.
    assert set(body) == {"card", "job", "reportRevision"}
    assert body["card"] == {**card, "version": 3}
    assert body["job"] == job_of(client, job_id)
    assert (body["job"]["state"], body["job"]["counts"]["reviewOpen"]) == ("NEEDS_REVIEW", 4)
    assert body["reportRevision"] == 3 == revision(storage, job_id)
    undone = rally(client, job_id, 1)
    assert (undone["status"], undone["winner"], undone["terminalAction"]) == ("PROPOSED", None, None)
    # The detail question belonged to the withdrawn winner.
    assert cards(client, job_id, "DETAIL") == []
    # The card can be answered again from its new version.
    assert decide(client, body["card"], "AWAY")["card"]["version"] == 4


def test_reopen_on_a_stale_version_is_refused(review):
    client, job_id, storage = review
    card = card_of(client, job_id, 1)
    decide(client, card, "HOME")
    for expected in (1, 3):
        stale = reopen(client, card["id"], expected)
        assert stale.status_code == 409
        assert stale.json()["error"]["code"] == "REVIEW_STALE_VERSION"
        assert stale.json()["error"]["details"] == {"currentVersion": 2}
    assert card_of(client, job_id, 1)["status"] == "ANSWERED" and rally(client, job_id, 1)["winner"] == "HOME"
    assert revision(storage, job_id) == 1


def test_reopening_an_open_card_answers_200_and_changes_nothing(review):
    client, job_id, storage = review
    card = card_of(client, job_id, 1)
    # A card that was never answered.
    untouched = reopen(client, card["id"], 1)
    assert untouched.status_code == 200
    assert untouched.json() == {"card": card, "job": job_of(client, job_id), "reportRevision": 0}
    assert audit(storage) == [] and revision(storage, job_id) == 0

    # A card that was answered and reopened: the second reopen is the no-op.
    decide(client, card, "HOME")
    first = reopen(client, card["id"], 2).json()
    trail = audit(storage)
    second = reopen(client, card["id"], 3)
    assert second.status_code == 200 and second.json() == first
    assert audit(storage) == trail and revision(storage, job_id) == 2
    assert card_of(client, job_id, 1)["version"] == 3
    # The version check comes first, also for an open card.
    assert reopen(client, card["id"], 2).status_code == 409


def test_reopening_a_score_card_returns_a_complete_job_to_review(review):
    client, job_id, _ = review
    for card in cards(client, job_id, "SCORE"):
        last = decide(client, card, "AWAY")
    assert last["job"]["state"] == "COMPLETE"
    undone = reopen(client, last["card"]["id"], last["card"]["version"]).json()
    assert (undone["job"]["state"], undone["job"]["counts"]["reviewOpen"]) == ("NEEDS_REVIEW", 1)
    assert decide(client, undone["card"], "HOME")["job"]["state"] == "COMPLETE"


def test_reopening_a_terminal_action_card_removes_the_scorer(review):
    client, job_id, _ = review
    answer_details(client, job_id, 1, action="ATTACK", scorer="JERSEY:7")
    action_card = card_of(client, job_id, 1, "TERMINAL_ACTION")
    undone = reopen(client, action_card["id"], action_card["version"]).json()
    assert (undone["card"]["status"], undone["card"]["answer"], undone["card"]["version"]) == ("OPEN", None, 3)
    after = rally(client, job_id, 1)
    assert (after["winner"], after["terminalAction"], after["scorer"]) == ("HOME", None, None)
    assert detail_questions(client, job_id, 1) == ["TERMINAL_ACTION"]


def test_reopening_a_scorer_card_clears_the_scorer_only(review):
    client, job_id, _ = review
    answer_details(client, job_id, 1, action="ATTACK", scorer="JERSEY:7")
    scorer_card = card_of(client, job_id, 1, "SCORER")
    reopen(client, scorer_card["id"], scorer_card["version"])
    after = rally(client, job_id, 1)
    assert (after["winner"], after["terminalAction"], after["scorer"]) == ("HOME", "ATTACK", None)
    assert card_of(client, job_id, 1, "SCORER")["status"] == "OPEN"


@pytest.mark.parametrize("body", [{}, {"expectedVersion": "2"}, {"expectedVersion": 0}])
def test_a_malformed_reopen_is_a_validation_error(review, body):
    client, job_id, _ = review
    refused = client.post(f"/v1/review-cards/{card_of(client, job_id, 1)['id']}/reopen", json=body)
    assert refused.status_code == 422 and refused.json()["error"]["code"] == "VALIDATION_ERROR"


# --- audit trail -----------------------------------------------------------------------------

def test_every_decision_correction_and_reopen_is_recorded(review):
    client, job_id, storage = review
    card = card_of(client, job_id, 1)
    first = decide(client, card, "HOME")
    second = decide(client, first["card"], "AWAY")
    reopen(client, card["id"], 3)
    trail = audit(storage)
    assert [(row["action"], row["previous_value"], row["new_value"]) for row in trail] == [
        ("DECISION", None, "HOME"), ("DECISION", "HOME", "AWAY"), ("REOPEN", "AWAY", None)]
    assert [row["id"] for row in trail[:2]] == [first["decisionId"], second["decisionId"]]
    for row in trail:
        assert (row["job_id"], row["card_id"], row["rally_id"], row["question_type"]) == (job_id, card["id"], card["rallyId"], "RALLY_WINNER")
        assert TIMESTAMP.fullmatch(row["created_at"])
    assert len({row["id"] for row in trail}) == 3


def test_the_audit_trail_can_not_be_edited_or_trimmed(review):
    client, job_id, storage = review
    decide(client, card_of(client, job_id, 1), "HOME")
    before = audit(storage)
    with pytest.raises(sqlite3.IntegrityError, match="append-only"):
        execute(storage, "UPDATE review_decisions SET new_value='AWAY'")
    with pytest.raises(sqlite3.IntegrityError, match="append-only"):
        execute(storage, "DELETE FROM review_decisions")
    assert audit(storage) == before


def test_a_mock_job_marks_its_detail_cards_as_mock_data(storage, make_clip):
    pretending = ScriptedAnalyzer(result=[RallyProposal(100, 400, uncertainty_reasons=("MOCK_DATA",))],
                                  info=AnalyzerInfo("pretend", "t1", MOCK))
    with serve(storage, pretending) as client:
        _, job = reviewed_job(client, make_clip)
        decide(client, card_of(client, job["id"], 1), "HOME")
        decide(client, card_of(client, job["id"], 1, "TERMINAL_ACTION"), "ATTACK")
        # The clip of every card of this job shows an invented interval.
        assert [card["uncertaintyReasons"] for card in cards(client, job["id"], "ALL")] == [["MOCK_DATA"]] * 3


# --- deleting the video ----------------------------------------------------------------------

def test_deleting_a_video_removes_its_job_rallies_cards_and_decisions(storage, make_clip):
    with serve(storage, ScriptedAnalyzer()) as client:
        video, job = reviewed_job(client, make_clip)
        kept_video, kept_job = reviewed_job(client, make_clip)
        answer_details(client, job["id"], 1)
        decide(client, card_of(client, job["id"], 1), "AWAY")  # leaves REMOVED rows behind
        decide(client, card_of(client, kept_job["id"], 1), "AWAY")
        winner_card = card_of(client, job["id"], 1)
        assert len(audit(storage)) == 7

        assert client.delete(f"/v1/videos/{video['id']}").status_code == 204

        for url in (f"/v1/analyses/{job['id']}", f"/v1/analyses/{job['id']}/rallies", f"/v1/analyses/{job['id']}/review-cards"):
            gone = client.get(url)
            assert gone.status_code == 404 and gone.json()["error"]["code"] == "ANALYSIS_NOT_FOUND"
        assert client.get(f"/v1/videos/{video['id']}/analyses/latest").json()["error"]["code"] == "VIDEO_NOT_FOUND"
        assert post_decision(client, winner_card["id"], selection="HOME", expectedVersion=winner_card["version"]).status_code == 404
        assert reopen(client, winner_card["id"], winner_card["version"]).status_code == 404
        # The match is still there, but nothing is left to report.
        report = client.get(f"/v1/matches/{video['matchId']}/report")
        assert report.status_code == 404 and report.json()["error"]["code"] == "REPORT_NOT_AVAILABLE"

        assert [row["id"] for row in rows(storage, "SELECT id FROM analysis_jobs")] == [kept_job["id"]]
        for table in ("rallies", "review_cards", "review_decisions"):
            assert {row["job_id"] for row in rows(storage, f"SELECT job_id FROM {table}")} == {kept_job["id"]}, table
        # The other video kept its review.
        assert len(audit(storage)) == 1
        assert client.get(f"/v1/matches/{kept_video['matchId']}/report").json()["rallies"]["verified"] == 1
        assert client.get(f"/v1/videos/{kept_video['id']}/analyses/latest").json()["id"] == kept_job["id"]
