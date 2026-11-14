"""Rally corrections: add, move, split, merge and the confirmed list (docs/API_V1.md, section 9).

The rallies come from a scripted analyzer over a synthetic one-minute test pattern. The times,
the decisions and the scores are made up by the tests and describe no real match.
"""
import json
import re
import sqlite3

import pytest

from app.analyzers.base import MOCK, MODEL, AnalyzerInfo, RallyProposal
from app.main import create_app
from fastapi.testclient import TestClient
from tests.helpers import ScriptedAnalyzer, card_of, cards, decide, proposals, request_job, rows, run_pending, serve
from tests.test_media import store

# Four rallies with dead time between them, inside the one-minute clip.
SPANS = ((2_000, 9_000), (15_000, 24_000), (30_000, 41_000), (48_000, 56_000))
VIDEO_MS = 60_000
TIMESTAMP = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z")


def start_job(client, make_clip):
    """Upload the one-minute test pattern and run the configured analyzer on it."""
    video = store(client, make_clip("minute.mp4", size="320x180", seconds=60))
    assert video["status"] == "READY"
    job_id = request_job(client, video["id"])["id"]
    assert run_pending(client) == 1
    return job_id


@pytest.fixture
def match(storage, make_clip):
    """A client, the id of a job in NEEDS_REVIEW with four proposed rallies, and the storage folder."""
    with serve(storage, ScriptedAnalyzer(result=proposals(SPANS))) as client:
        yield client, start_job(client, make_clip), storage


def listing(client, job_id):
    response = client.get(f"/v1/analyses/{job_id}/rallies")
    assert response.status_code == 200, response.text
    return response.json()


def spans(client, job_id):
    return [(rally["startMs"], rally["endMs"]) for rally in listing(client, job_id)["rallies"]]


def version(client, job_id):
    return listing(client, job_id)["rallyListVersion"]


def rally(client, job_id, sequence):
    found = listing(client, job_id)["rallies"][sequence - 1]
    assert found["sequence"] == sequence
    return found


def job_of(client, job_id):
    return client.get(f"/v1/analyses/{job_id}").json()


def report_of(client, job_id):
    return client.get(f"/v1/matches/{job_of(client, job_id)['matchId']}/report").json()


def body(client, job_id, expected, **fields):
    return {**fields, "expectedListVersion": version(client, job_id) if expected is None else expected}


def add(client, job_id, start, end, expected=None):
    return client.post(f"/v1/analyses/{job_id}/rallies", json=body(client, job_id, expected, startMs=start, endMs=end))


def move(client, job_id, sequence, start, end, expected=None):
    return client.post(f"/v1/rallies/{rally(client, job_id, sequence)['id']}/move",
                       json=body(client, job_id, expected, startMs=start, endMs=end))


def split(client, job_id, sequence, at, expected=None):
    return client.post(f"/v1/rallies/{rally(client, job_id, sequence)['id']}/split", json=body(client, job_id, expected, atMs=at))


def merge_previous(client, job_id, sequence, expected=None):
    return client.post(f"/v1/rallies/{rally(client, job_id, sequence)['id']}/merge-previous", json=body(client, job_id, expected))


def confirm(client, job_id, expected=None):
    return client.post(f"/v1/analyses/{job_id}/rally-list/confirm", json=body(client, job_id, expected))


def code(response):
    return response.json()["error"]["code"]


def answer(client, job_id, sequence, winner, action=None, scorer=None):
    decide(client, card_of(client, job_id, sequence), winner)
    if action:
        decide(client, card_of(client, job_id, sequence, "TERMINAL_ACTION"), action)
    if scorer:
        decide(client, card_of(client, job_id, sequence, "SCORER"), scorer)


def answer_all(client, job_id, winners):
    for sequence, winner in enumerate(winners, start=1):
        answer(client, job_id, sequence, winner)


def corrections(storage):
    return rows(storage, "SELECT * FROM rally_corrections ORDER BY rowid")


def audit(storage):
    return rows(storage, "SELECT * FROM review_decisions ORDER BY rowid")


def revision(storage, job_id):
    return rows(storage, "SELECT revision FROM analysis_jobs WHERE id=?", job_id)[0]["revision"]


# --- the rally list -------------------------------------------------------------------------


def test_the_rally_list_carries_its_version_the_video_length_and_where_each_rally_came_from(match):
    client, job_id, _ = match
    found = listing(client, job_id)
    assert set(found) == {"rallies", "rallyListVersion", "listConfirmed", "videoDurationMs", "sets"}
    assert (found["rallyListVersion"], found["listConfirmed"], found["videoDurationMs"]) == (0, False, VIDEO_MS)
    assert [(item["sequence"], item["origin"], item["scoreAfter"]) for item in found["rallies"]] == [
        (1, "ANALYZER", None), (2, "ANALYZER", None), (3, "ANALYZER", None), (4, "ANALYZER", None)]
    job = job_of(client, job_id)
    assert (job["rallyListVersion"], job["listConfirmed"]) == (0, False)


def test_the_score_after_each_rally_follows_the_walk_of_the_report_and_stops_where_it_stops(match):
    client, job_id, _ = match
    answer_all(client, job_id, ["HOME", "NOT_A_RALLY", "AWAY", "UNSURE"])
    after = [item["scoreAfter"] for item in listing(client, job_id)["rallies"]]
    # A rejected rally is skipped and has no score of its own; an unsure rally ends the walk.
    assert after == [{"set": 1, "home": 1, "away": 0}, None, {"set": 1, "home": 1, "away": 1}, None]

    decide(client, card_of(client, job_id, 1), "UNSURE")
    assert [item["scoreAfter"] for item in listing(client, job_id)["rallies"]] == [None, None, None, None]


def test_the_rally_list_carries_the_set_scores_that_the_user_is_asked_to_check(match):
    client, job_id, _ = match
    assert listing(client, job_id)["sets"] == []

    answer_all(client, job_id, ["HOME", "NOT_A_RALLY", "AWAY", "HOME"])

    found = listing(client, job_id)
    assert found["sets"] == [{"number": 1, "home": 2, "away": 1, "complete": False}]
    # One count for both: the scores a user confirms on the list are the scores the report prints.
    assert found["sets"] == report_of(client, job_id)["score"]["sets"]


def test_the_job_tells_how_many_rallies_the_analyzer_proposed_apart_from_how_many_the_list_holds(match):
    client, job_id, _ = match
    assert job_of(client, job_id)["counts"] == {"rallies": 4, "proposed": 4, "reviewOpen": 4, "reviewTotal": 4}
    assert add(client, job_id, 10_000, 13_000).status_code == 201
    # The user made the fifth one. A screen that says what the server proposed must not count it.
    assert job_of(client, job_id)["counts"] == {"rallies": 5, "proposed": 4, "reviewOpen": 5, "reviewTotal": 5}
    assert merge_previous(client, job_id, 5).status_code == 200
    assert merge_previous(client, job_id, 4).status_code == 200
    assert job_of(client, job_id)["counts"] == {"rallies": 3, "proposed": 4, "reviewOpen": 3, "reviewTotal": 3}


# --- add ------------------------------------------------------------------------------------


def test_a_missed_rally_is_added_in_time_order_with_an_open_winner_card(match):
    client, job_id, storage = match
    before = revision(storage, job_id)

    added = add(client, job_id, 10_000, 13_000)

    assert added.status_code == 201, added.text
    answer_body = added.json()
    assert set(answer_body) == {"correctionId", "affected", "removed", "job", "reportRevision"}
    assert answer_body["removed"] == [] and answer_body["reportRevision"] == before + 1 == revision(storage, job_id)
    new = answer_body["affected"][0]
    assert (new["sequence"], new["startMs"], new["endMs"], new["origin"]) == (2, 10_000, 13_000, "USER")
    # No analyzer looked at this interval, so it carries no proposal of any kind.
    assert (new["status"], new["proposedWinner"], new["confidence"], new["uncertaintyReasons"]) == ("PROPOSED", "UNKNOWN", None, [])
    assert spans(client, job_id) == [SPANS[0], (10_000, 13_000), *SPANS[1:]]
    assert [item["sequence"] for item in listing(client, job_id)["rallies"]] == [1, 2, 3, 4, 5]
    card = card_of(client, job_id, 2)
    assert (card["rallyId"], card["status"], card["version"]) == (new["id"], "OPEN", 1)
    assert (card["clipStartMs"], card["clipEndMs"], card["suggestedValue"]) == (10_000, 15_000, None)
    job = answer_body["job"]
    assert (job["state"], job["counts"], job["rallyListVersion"]) == (
        "NEEDS_REVIEW", {"rallies": 5, "proposed": 4, "reviewOpen": 5, "reviewTotal": 5}, 1)
    record = corrections(storage)[-1]
    assert (record["id"], record["operation"], record["rally_id"], record["list_version_after"]) == (
        answer_body["correctionId"], "ADD", new["id"], 1)
    assert TIMESTAMP.fullmatch(record["created_at"])


def test_adding_a_rally_reopens_a_review_that_was_finished(match):
    client, job_id, _ = match
    answer_all(client, job_id, ["HOME", "AWAY", "HOME", "AWAY"])
    assert job_of(client, job_id)["state"] == "COMPLETE"
    assert add(client, job_id, 10_000, 13_000).json()["job"]["state"] == "NEEDS_REVIEW"
    # The four answers are still there: the new rally asks one question and takes none away.
    assert [item["status"] for item in listing(client, job_id)["rallies"]] == ["VERIFIED", "PROPOSED", "VERIFIED", "VERIFIED", "VERIFIED"]


def test_a_rally_may_touch_its_neighbours_but_never_overlaps_one(match):
    client, job_id, _ = match
    first, second = rally(client, job_id, 1), rally(client, job_id, 2)
    for start, end, blocker in ((8_999, 12_000, first), (12_000, 15_001, second), (1_000, 10_000, first), (16_000, 17_000, second)):
        refused = add(client, job_id, start, end)
        assert (refused.status_code, code(refused)) == (409, "RALLY_OVERLAP"), (start, end)
        assert refused.json()["error"]["details"] == {"rallyId": blocker["id"]}
    # What the user called "not a rally" still holds its time: it can come back with one answer.
    decide(client, card_of(client, job_id, 2), "NOT_A_RALLY")
    assert code(add(client, job_id, 16_000, 17_000)) == "RALLY_OVERLAP"
    assert spans(client, job_id) == list(SPANS)

    assert add(client, job_id, 9_000, 15_000).status_code == 201
    assert spans(client, job_id) == [SPANS[0], (9_000, 15_000), *SPANS[1:]]


def test_an_interval_without_length_or_outside_the_video_is_refused(match):
    client, job_id, storage = match
    for start, end in ((10_000, 10_000), (13_000, 10_000), (-1, 1_000), (57_000, VIDEO_MS + 1)):
        refused = add(client, job_id, start, end)
        assert (refused.status_code, code(refused)) == (422, "INVALID_INTERVAL"), (start, end)
    assert (version(client, job_id), spans(client, job_id), corrections(storage)) == (0, list(SPANS), [])
    # The last millisecond of the video is still inside it.
    assert add(client, job_id, 57_000, VIDEO_MS).status_code == 201


def test_a_correction_made_on_an_older_list_is_refused(match):
    client, job_id, storage = match
    assert add(client, job_id, 10_000, 13_000, expected=0).status_code == 201
    stale = add(client, job_id, 25_000, 28_000, expected=0)
    assert (stale.status_code, code(stale)) == (409, "RALLY_LIST_STALE")
    assert stale.json()["error"]["details"] == {"currentListVersion": 1}
    # A lost answer followed by the same request can therefore never add the rally twice.
    assert code(add(client, job_id, 10_000, 13_000, expected=0)) == "RALLY_LIST_STALE"
    assert len(spans(client, job_id)) == 5 and len(corrections(storage)) == 1
    missing = client.post(f"/v1/analyses/{job_id}/rallies", json={"startMs": 25_000, "endMs": 28_000})
    assert missing.status_code == 422


def test_corrections_need_a_job_whose_run_ended_with_rallies(storage, make_clip):
    analyzer = ScriptedAnalyzer(result=proposals(SPANS))
    with serve(storage, analyzer) as client:
        video = store(client, make_clip("minute.mp4", size="320x180", seconds=60))
        queued = request_job(client, video["id"])["id"]
        for response in (
            client.post(f"/v1/analyses/{queued}/rallies", json={"startMs": 1, "endMs": 2, "expectedListVersion": 0}),
            client.post(f"/v1/analyses/{queued}/rally-list/confirm", json={"expectedListVersion": 0}),
        ):
            assert (response.status_code, code(response)) == (409, "JOB_NOT_REVIEWABLE")
            assert response.json()["error"]["details"] == {"state": "QUEUED"}
        # The run that follows is not disturbed by the refused requests.
        assert run_pending(client) == 1 and spans(client, queued) == list(SPANS)

        analyzer.result = []
        failed = request_job(client, video["id"])["id"]
        assert run_pending(client) == 1 and job_of(client, failed)["state"] == "FAILED"
        refused = client.post(f"/v1/analyses/{failed}/rallies", json={"startMs": 1, "endMs": 2, "expectedListVersion": 0})
        assert (code(refused), refused.json()["error"]["details"]) == ("JOB_NOT_REVIEWABLE", {"state": "FAILED"})

        unknown = client.post("/v1/analyses/no-such-job/rallies", json={"startMs": 1, "endMs": 2, "expectedListVersion": 0})
        assert (unknown.status_code, code(unknown)) == (404, "ANALYSIS_NOT_FOUND")
        gone = client.post("/v1/rallies/no-such-rally/split", json={"atMs": 1, "expectedListVersion": 0})
        assert (gone.status_code, code(gone)) == (404, "RALLY_NOT_FOUND")


# --- move -----------------------------------------------------------------------------------


def test_moving_only_the_start_keeps_every_answer(match):
    client, job_id, storage = match
    answer(client, job_id, 2, "HOME", "ATTACK", "JERSEY:7")
    before_cards = [card for card in cards(client, job_id) if card["rallySequence"] == 2]
    before = rally(client, job_id, 2)

    moved = move(client, job_id, 2, 13_000, 24_000)

    assert moved.status_code == 200, moved.text
    after = rally(client, job_id, 2)
    assert (after["id"], after["startMs"], after["endMs"]) == (before["id"], 13_000, 24_000)
    assert (after["status"], after["winner"], after["terminalAction"], after["scorer"]) == (
        "VERIFIED", "HOME", "ATTACK", {"side": "HOME", "jersey": 7})
    # The clip shows the end of the rally, and the end did not move.
    assert [card for card in cards(client, job_id) if card["rallySequence"] == 2] == before_cards
    assert moved.json()["affected"] == [after] and moved.json()["removed"] == []
    record = corrections(storage)[-1]
    assert (record["operation"], record["rally_id"], record["list_version_after"]) == ("MOVE", before["id"], version(client, job_id))


def test_moving_the_end_asks_for_the_winner_again_because_the_clip_changed(match):
    client, job_id, storage = match
    answer(client, job_id, 2, "HOME", "ATTACK", "JERSEY:7")
    answer_all_others = [answer(client, job_id, sequence, "AWAY") for sequence in (1, 3, 4)]
    assert len(answer_all_others) == 3 and job_of(client, job_id)["state"] == "COMPLETE"
    old_card = card_of(client, job_id, 2)
    seen = len(audit(storage))

    moved = move(client, job_id, 2, 15_000, 26_000)

    assert moved.status_code == 200, moved.text
    after = rally(client, job_id, 2)
    assert (after["endMs"], after["status"], after["winner"], after["terminalAction"], after["scorer"]) == (26_000, "PROPOSED", None, None, None)
    card = card_of(client, job_id, 2)
    assert (card["id"], card["status"], card["answer"], card["version"]) == (old_card["id"], "OPEN", None, old_card["version"] + 1)
    assert (card["clipStartMs"], card["clipEndMs"]) == (20_000, 28_000)
    assert [item["questionType"] for item in cards(client, job_id) if item["rallySequence"] == 2] == ["RALLY_WINNER"]
    assert moved.json()["job"]["state"] == "NEEDS_REVIEW"
    # The audit says which correction took the answers away.
    correction_id = moved.json()["correctionId"]
    written = [(row["question_type"], row["action"], row["previous_value"], row["correction_id"]) for row in audit(storage)[seen:]]
    assert sorted(written) == sorted([
        ("TERMINAL_ACTION", "REMOVED", "ATTACK", correction_id), ("SCORER", "REMOVED", "JERSEY:7", correction_id),
        ("RALLY_WINNER", "REOPEN", "HOME", correction_id)])
    # A phone that still shows the old clip can not answer for the new one.
    late = client.post(f"/v1/review-cards/{old_card['id']}/decisions", json={"selection": "HOME", "expectedVersion": old_card["version"]})
    assert (late.status_code, code(late)) == (409, "REVIEW_STALE_VERSION")


def test_moving_the_end_of_an_undecided_rally_still_moves_its_card_on(match):
    client, job_id, storage = match
    open_card = card_of(client, job_id, 3)
    assert move(client, job_id, 3, 30_000, 43_000).status_code == 200
    after = card_of(client, job_id, 3)
    assert (after["status"], after["version"], after["clipEndMs"]) == ("OPEN", open_card["version"] + 1, 45_000)
    # Nothing was answered, so there is no answer to record as reopened.
    assert audit(storage) == []


def test_a_move_that_changes_nothing_writes_nothing(match):
    client, job_id, storage = match
    before = revision(storage, job_id)
    same = move(client, job_id, 2, *SPANS[1])
    assert same.status_code == 200 and same.json()["affected"] == [rally(client, job_id, 2)]
    assert (version(client, job_id), revision(storage, job_id), corrections(storage)) == (0, before, [])


def test_a_move_may_reach_a_neighbour_but_not_cross_it(match):
    client, job_id, _ = match
    crossed = move(client, job_id, 2, 8_000, 24_000)
    assert (crossed.status_code, code(crossed)) == (409, "RALLY_OVERLAP")
    assert crossed.json()["error"]["details"] == {"rallyId": rally(client, job_id, 1)["id"]}
    for start, end in ((24_000, 24_000), (15_000, VIDEO_MS + 1)):
        assert code(move(client, job_id, 2, start, end)) == "INVALID_INTERVAL"
    assert move(client, job_id, 2, 9_000, 30_000).status_code == 200
    assert spans(client, job_id) == [SPANS[0], (9_000, 30_000), *SPANS[2:]]


# --- split ----------------------------------------------------------------------------------


def test_a_split_keeps_the_later_part_with_its_answers_and_opens_the_earlier_part(match):
    client, job_id, storage = match
    answer(client, job_id, 3, "HOME", "ACE")
    original = rally(client, job_id, 3)
    kept_cards = [card for card in cards(client, job_id) if card["rallyId"] == original["id"]]

    done = split(client, job_id, 3, 35_000)

    assert done.status_code == 200, done.text
    earlier, later = rally(client, job_id, 3), rally(client, job_id, 4)
    assert (earlier["startMs"], earlier["endMs"], later["startMs"], later["endMs"]) == (30_000, 35_000, 35_000, 41_000)
    # The later part ends where the original ended: it is the rally whose ending the user judged.
    assert (later["id"], later["origin"], later["status"], later["winner"], later["terminalAction"]) == (
        original["id"], "ANALYZER", "VERIFIED", "HOME", "ACE")
    assert [(card["id"], card["answer"], card["version"]) for card in cards(client, job_id) if card["rallyId"] == original["id"]] == [
        (card["id"], card["answer"], card["version"]) for card in kept_cards]
    assert (earlier["origin"], earlier["status"], earlier["proposedWinner"], earlier["uncertaintyReasons"]) == ("USER", "PROPOSED", "UNKNOWN", [])
    assert earlier["id"] != original["id"] and card_of(client, job_id, 3)["status"] == "OPEN"
    assert rally(client, job_id, 5)["startMs"] == SPANS[3][0]
    assert sorted(item["id"] for item in done.json()["affected"]) == sorted([earlier["id"], later["id"]])
    assert (done.json()["removed"], done.json()["job"]["state"]) == ([], "NEEDS_REVIEW")
    record = corrections(storage)[-1]
    assert (record["operation"], record["rally_id"], record["created_rally_id"]) == ("SPLIT", original["id"], earlier["id"])


def test_a_split_point_lies_inside_the_rally(match):
    client, job_id, storage = match
    for at in (30_000, 41_000, 29_999, 45_000):
        refused = split(client, job_id, 3, at)
        assert (refused.status_code, code(refused)) == (422, "SPLIT_POINT_OUTSIDE"), at
    assert (spans(client, job_id), corrections(storage)) == (list(SPANS), [])


# --- merge ----------------------------------------------------------------------------------


def test_a_merge_keeps_the_later_rally_with_its_answers_and_removes_the_earlier_one(match):
    client, job_id, storage = match
    answer(client, job_id, 2, "AWAY", "BLOCK")
    answer(client, job_id, 3, "HOME", "ATTACK", "JERSEY:4")
    earlier, later = rally(client, job_id, 2), rally(client, job_id, 3)
    seen = len(audit(storage))

    merged = merge_previous(client, job_id, 3)

    assert merged.status_code == 200, merged.text
    kept = rally(client, job_id, 2)
    assert (kept["id"], kept["startMs"], kept["endMs"]) == (later["id"], 15_000, 41_000)
    assert (kept["status"], kept["winner"], kept["terminalAction"], kept["scorer"]) == ("VERIFIED", "HOME", "ATTACK", {"side": "HOME", "jersey": 4})
    assert spans(client, job_id) == [SPANS[0], (15_000, 41_000), SPANS[3]]
    assert (merged.json()["affected"], merged.json()["removed"]) == ([kept], [earlier["id"]])
    assert all(card["rallyId"] != earlier["id"] for card in cards(client, job_id))
    # The answers of the rally that no longer exists end in the audit, tied to this correction.
    written = [(row["rally_id"], row["question_type"], row["action"], row["previous_value"], row["correction_id"]) for row in audit(storage)[seen:]]
    correction_id = merged.json()["correctionId"]
    assert sorted(written) == sorted([
        (earlier["id"], "RALLY_WINNER", "REMOVED", "AWAY", correction_id),
        (earlier["id"], "TERMINAL_ACTION", "REMOVED", "BLOCK", correction_id)])
    record = corrections(storage)[-1]
    assert (record["operation"], record["rally_id"], record["removed_rally_id"]) == ("MERGE_PREVIOUS", later["id"], earlier["id"])
    # The point of the removed rally left the count.
    assert report_of(client, job_id)["teams"]["away"]["points"]["value"] == 0


def test_the_first_rally_has_no_rally_before_it(match):
    client, job_id, storage = match
    refused = merge_previous(client, job_id, 1)
    assert (refused.status_code, code(refused)) == (409, "NO_PREVIOUS_RALLY")
    assert (spans(client, job_id), corrections(storage)) == (list(SPANS), [])


def test_a_merge_into_what_the_user_called_not_a_rally_asks_for_the_winner_again(match):
    client, job_id, storage = match
    answer(client, job_id, 2, "HOME")
    answer(client, job_id, 3, "NOT_A_RALLY")
    later_card = card_of(client, job_id, 3)

    merged = merge_previous(client, job_id, 3)

    # "Not a rally" was said about a clip that the merge has just made the end of a rally.
    kept = rally(client, job_id, 2)
    assert (kept["status"], kept["winner"]) == ("PROPOSED", None)
    card = card_of(client, job_id, 2)
    assert (card["id"], card["status"], card["version"]) == (later_card["id"], "OPEN", later_card["version"] + 1)
    reopened = [row for row in audit(storage) if row["action"] == "REOPEN"]
    assert [(row["previous_value"], row["correction_id"]) for row in reopened] == [("NOT_A_RALLY", merged.json()["correctionId"])]


# --- the list version and its confirmation ---------------------------------------------------


def test_winner_decisions_move_the_list_version_and_detail_decisions_do_not(match):
    client, job_id, _ = match
    answer(client, job_id, 1, "HOME")
    assert version(client, job_id) == 1
    decide(client, card_of(client, job_id, 1, "TERMINAL_ACTION"), "ATTACK")
    decide(client, card_of(client, job_id, 1, "SCORER"), "JERSEY:9")
    assert version(client, job_id) == 1
    card = card_of(client, job_id, 1)
    assert client.post(f"/v1/review-cards/{card['id']}/reopen", json={"expectedVersion": card["version"]}).status_code == 200
    assert version(client, job_id) == 2
    # Reopening a card that is already open changes nothing, so it moves nothing.
    card = card_of(client, job_id, 1)
    assert client.post(f"/v1/review-cards/{card['id']}/reopen", json={"expectedVersion": card["version"]}).status_code == 200
    assert version(client, job_id) == 2


def test_the_list_is_confirmed_only_when_every_winner_is_known(match):
    client, job_id, storage = match
    not_ready = confirm(client, job_id)
    assert (not_ready.status_code, code(not_ready)) == (409, "LIST_NOT_READY")
    assert not_ready.json()["error"]["details"] == {"open": 4, "unresolved": 0, "verified": 0}

    answer_all(client, job_id, ["HOME", "AWAY", "UNSURE", "NOT_A_RALLY"])
    assert confirm(client, job_id).json()["error"]["details"] == {"open": 0, "unresolved": 1, "verified": 2}

    answer_all(client, job_id, ["NOT_A_RALLY"] * 4)
    assert confirm(client, job_id).json()["error"]["details"] == {"open": 0, "unresolved": 0, "verified": 0}
    assert corrections(storage) == [] and job_of(client, job_id)["listConfirmed"] is False

    answer_all(client, job_id, ["HOME", "AWAY", "HOME", "NOT_A_RALLY"])
    before = (version(client, job_id), revision(storage, job_id))
    done = confirm(client, job_id)
    assert done.status_code == 200, done.text
    assert set(done.json()) == {"job", "reportRevision"} and done.json()["job"]["listConfirmed"] is True
    assert listing(client, job_id)["listConfirmed"] is True
    # Confirming changes no rally and no winner, so the list is still the list that was confirmed.
    assert version(client, job_id) == before[0] and done.json()["reportRevision"] == before[1] + 1
    assert [row["operation"] for row in corrections(storage)] == ["CONFIRM_LIST"]

    again = confirm(client, job_id)
    assert again.status_code == 200 and len(corrections(storage)) == 1 and revision(storage, job_id) == before[1] + 1


def test_a_confirmation_belongs_to_the_list_the_user_was_shown(match):
    client, job_id, _ = match
    answer_all(client, job_id, ["HOME", "AWAY", "HOME", "AWAY"])
    shown = version(client, job_id)
    decide(client, card_of(client, job_id, 4), "HOME")
    late = confirm(client, job_id, expected=shown)
    assert (late.status_code, code(late)) == (409, "RALLY_LIST_STALE")
    assert late.json()["error"]["details"] == {"currentListVersion": shown + 1}
    assert job_of(client, job_id)["listConfirmed"] is False


def test_a_confirmation_lapses_when_a_winner_or_the_list_changes_but_not_on_a_detail_answer(match):
    client, job_id, _ = match

    def confirmed():
        return job_of(client, job_id)["listConfirmed"]

    answer_all(client, job_id, ["HOME", "AWAY", "HOME", "AWAY"])
    assert confirm(client, job_id).status_code == 200 and confirmed()

    decide(client, card_of(client, job_id, 1, "TERMINAL_ACTION"), "ATTACK")
    decide(client, card_of(client, job_id, 1, "SCORER"), "JERSEY:3")
    assert confirmed(), "an answer about how a point was scored changes no score"

    decide(client, card_of(client, job_id, 2), "HOME")
    assert not confirmed() and report_of(client, job_id)["rallyList"] == {"confirmed": False, "confirmedAt": None, "corrections": 0}
    assert confirm(client, job_id).status_code == 200 and confirmed()

    assert move(client, job_id, 3, 29_000, 41_000).status_code == 200
    assert not confirmed(), "a moved start can open a gap that hides a rally"
    assert confirm(client, job_id).status_code == 200 and confirmed()

    assert add(client, job_id, 10_000, 13_000).status_code == 201
    assert not confirmed()


def test_a_decision_that_repeats_the_stored_answer_changes_nothing_and_keeps_the_confirmation(match):
    client, job_id, storage = match
    answer_all(client, job_id, ["HOME", "AWAY", "HOME", "AWAY"])
    assert confirm(client, job_id).status_code == 200
    card = card_of(client, job_id, 2)
    before = (job_of(client, job_id), revision(storage, job_id), len(audit(storage)))

    # The user opens the review again and taps the answer that is already there.
    for key in (None, "same-answer-key"):
        again = client.post(f"/v1/review-cards/{card['id']}/decisions",
                            json={"selection": "AWAY", "expectedVersion": card["version"], "idempotencyKey": key})
        assert again.status_code == 200, again.text
        assert (again.json()["decisionId"], again.json()["card"]) == (None, card)
    assert (job_of(client, job_id), revision(storage, job_id), len(audit(storage))) == before
    assert job_of(client, job_id)["listConfirmed"] is True

    # A stale request is still refused, and an answer that does change takes the confirmation away.
    stale = client.post(f"/v1/review-cards/{card['id']}/decisions", json={"selection": "AWAY", "expectedVersion": card["version"] - 1})
    assert (stale.status_code, code(stale)) == (409, "REVIEW_STALE_VERSION")
    decide(client, card, "HOME")
    assert job_of(client, job_id)["listConfirmed"] is False


def test_the_list_of_a_demo_job_can_not_be_confirmed(storage, make_clip):
    analyzer = ScriptedAnalyzer(result=[RallyProposal(start, end, uncertainty_reasons=("MOCK_DATA",)) for start, end in SPANS],
                                info=AnalyzerInfo("scripted-demo", "t1", MOCK))
    with serve(storage, analyzer) as client:
        job_id = start_job(client, make_clip)
        answer_all(client, job_id, ["HOME", "AWAY", "HOME", "AWAY"])
        refused = confirm(client, job_id)
        assert (refused.status_code, code(refused)) == (409, "MOCK_LIST_NOT_CONFIRMABLE")
        report = report_of(client, job_id)
        # Invented intervals never add up to an exact score, however they were corrected.
        assert (report["mock"], report["ralliesMayBeMissing"], report["score"]["status"]) == (True, True, "PARTIAL")
        # Corrections themselves work on a demo job: they are what the interface is tried with.
        assert add(client, job_id, 10_000, 13_000).status_code == 201


# --- the report -----------------------------------------------------------------------------


def test_the_counts_are_lower_bounds_until_the_user_confirms_the_list(match):
    client, job_id, _ = match
    answer_all(client, job_id, ["HOME", "AWAY", "HOME", "HOME"])
    draft = report_of(client, job_id)
    assert (draft["ralliesMayBeMissing"], draft["rallyList"]) == (True, {"confirmed": False, "confirmedAt": None, "corrections": 0})
    assert (draft["teams"]["home"]["points"], draft["score"]["status"]) == ({"value": 3, "status": "PARTIAL"}, "PARTIAL")

    assert confirm(client, job_id).status_code == 200
    exact = report_of(client, job_id)
    assert exact["ralliesMayBeMissing"] is False and exact["rallyList"]["confirmed"] is True
    assert TIMESTAMP.fullmatch(exact["rallyList"]["confirmedAt"])
    assert (exact["teams"]["home"]["points"], exact["teams"]["away"]["points"]) == (
        {"value": 3, "status": "MEASURED"}, {"value": 1, "status": "MEASURED"})
    assert (exact["score"]["status"], exact["score"]["sets"]) == ("MEASURED", [{"number": 1, "home": 3, "away": 1, "complete": False}])

    assert add(client, job_id, 10_000, 13_000).status_code == 201
    again = report_of(client, job_id)
    assert (again["ralliesMayBeMissing"], again["rallyList"]["confirmed"], again["teams"]["home"]["points"]["status"]) == (True, False, "PARTIAL")


def test_the_report_says_how_many_corrections_shaped_its_intervals(match):
    client, job_id, _ = match

    def corrected():
        return report_of(client, job_id)["rallyList"]["corrections"]

    assert corrected() == 0
    assert move(client, job_id, 1, 2_000, 9_000).status_code == 200, "the interval it already has: nothing to correct"
    assert code(add(client, job_id, 8_000, 12_000)) == "RALLY_OVERLAP"
    assert corrected() == 0

    assert add(client, job_id, 10_000, 13_000).status_code == 201
    assert move(client, job_id, 1, 1_000, 9_000).status_code == 200
    assert split(client, job_id, 5, 52_000).status_code == 200
    assert merge_previous(client, job_id, 6).status_code == 200
    assert corrected() == 4

    answer_all(client, job_id, ["HOME", "AWAY", "HOME", "AWAY", "HOME"])
    assert confirm(client, job_id).status_code == 200
    assert corrected() == 4, "a confirmation changes no interval"


def test_a_moved_end_takes_the_suggestion_of_the_analyzer_off_the_card(storage, make_clip):
    model = ScriptedAnalyzer(result=[RallyProposal(start, end, proposed_winner="HOME", confidence=0.9) for start, end in SPANS],
                             info=AnalyzerInfo("scripted-model", "t1", MODEL))
    with serve(storage, model) as client:
        job_id = start_job(client, make_clip)
        assert card_of(client, job_id, 2)["suggestedValue"] == "HOME"

        # A moved start leaves the end the analyzer looked at, so its suggestion still belongs to the clip.
        assert move(client, job_id, 2, 14_000, 24_000).status_code == 200
        assert card_of(client, job_id, 2)["suggestedValue"] == "HOME"

        # A moved end is an end no analyzer judged. Its suggestion and its confidence do not carry over.
        assert move(client, job_id, 2, 14_000, 27_000).status_code == 200
        moved = rally(client, job_id, 2)
        assert (card_of(client, job_id, 2)["suggestedValue"], moved["proposedWinner"], moved["confidence"]) == (None, "UNKNOWN", None)
        # A split and a merge keep the end of the rally they keep, and with it the suggestion.
        assert split(client, job_id, 3, 35_000).status_code == 200
        assert merge_previous(client, job_id, 4).status_code == 200
        assert card_of(client, job_id, 3)["suggestedValue"] == "HOME"
    # What the analyzer proposed stays on record as it was.
    assert [(row["proposed_winner"], row["confidence"]) for row in rows(storage, "SELECT * FROM rally_proposals ORDER BY start_ms")] == [("HOME", 0.9)] * 4


def test_a_measured_model_does_not_make_its_own_list_complete(storage, make_clip):
    model = ScriptedAnalyzer(result=[RallyProposal(start, end, proposed_winner="HOME", confidence=0.9) for start, end in SPANS],
                             info=AnalyzerInfo("scripted-model", "t1", MODEL))
    with serve(storage, model) as client:
        job_id = start_job(client, make_clip)
        answer_all(client, job_id, ["HOME", "AWAY", "HOME", "AWAY"])
        # Even a model with measured recall misses rallies. Only a check of this list can close it.
        assert report_of(client, job_id)["ralliesMayBeMissing"] is True
        assert confirm(client, job_id).status_code == 200
        assert report_of(client, job_id)["ralliesMayBeMissing"] is False


def test_the_report_tells_what_the_analyzer_proposed_from_what_the_user_made(match):
    client, job_id, _ = match
    assert report_of(client, job_id)["rallies"] == {
        "proposed": 4, "total": 4, "userCreated": 0, "verified": 0, "rejected": 0, "unresolved": 0, "open": 4}
    assert add(client, job_id, 10_000, 13_000).status_code == 201
    assert split(client, job_id, 4, 35_000).status_code == 200
    assert merge_previous(client, job_id, 2).status_code == 200
    counts = report_of(client, job_id)["rallies"]
    # The analyzer proposed four rallies. That number does not follow the user's corrections.
    assert (counts["proposed"], counts["total"], counts["userCreated"]) == (4, 5, 2)
    assert [item["origin"] for item in listing(client, job_id)["rallies"]] == ["USER", "ANALYZER", "USER", "ANALYZER", "ANALYZER"]


# --- what the analyzer proposed stays on record -----------------------------------------------


def test_the_proposals_stay_as_the_analyzer_made_them_whatever_the_user_corrects(match):
    client, job_id, storage = match

    def proposed():
        return [(row["start_ms"], row["end_ms"], row["proposed_winner"]) for row in rows(storage, "SELECT * FROM rally_proposals ORDER BY start_ms")]

    original = [(start, end, "UNKNOWN") for start, end in SPANS]
    assert proposed() == original
    assert move(client, job_id, 1, 1_000, 9_500).status_code == 200
    assert split(client, job_id, 3, 35_000).status_code == 200
    assert merge_previous(client, job_id, 2).status_code == 200
    assert add(client, job_id, 57_000, 59_000).status_code == 201
    assert proposed() == original


def test_every_correction_records_the_rallies_as_they_were_and_as_it_left_them(match):
    client, job_id, storage = match
    answer(client, job_id, 2, "AWAY")

    def record():
        row = corrections(storage)[-1]
        picture = lambda side: [(item["id"], item["startMs"], item["endMs"], item["status"], item["winner"]) for item in json.loads(row[side])]
        return row["operation"], picture("before_json"), picture("after_json")

    made = add(client, job_id, 10_000, 13_000).json()["affected"][0]["id"]
    assert record() == ("ADD", [], [(made, 10_000, 13_000, "PROPOSED", None)])

    # The answered rally is now the third one. Its end moves, so its answer goes.
    answered = rally(client, job_id, 3)["id"]
    assert move(client, job_id, 3, 15_000, 26_000).status_code == 200
    assert record() == ("MOVE", [(answered, 15_000, 24_000, "VERIFIED", "AWAY")], [(answered, 15_000, 26_000, "PROPOSED", None)])

    whole = rally(client, job_id, 4)["id"]
    earlier = split(client, job_id, 4, 35_000).json()["affected"][0]["id"]
    assert record() == ("SPLIT", [(whole, 30_000, 41_000, "PROPOSED", None)],
                        [(earlier, 30_000, 35_000, "PROPOSED", None), (whole, 35_000, 41_000, "PROPOSED", None)])

    assert merge_previous(client, job_id, 5).status_code == 200
    assert record() == ("MERGE_PREVIOUS", [(earlier, 30_000, 35_000, "PROPOSED", None), (whole, 35_000, 41_000, "PROPOSED", None)],
                        [(whole, 30_000, 41_000, "PROPOSED", None)])
    # A snapshot also says where a rally came from: the analyzer's rally names its proposal, the user's rally names none.
    first = json.loads(corrections(storage)[0]["after_json"])[0]
    moved = json.loads(corrections(storage)[1]["after_json"])[0]
    assert first["proposalId"] is None and moved["proposalId"] == rows(storage, "SELECT proposal_id FROM rallies WHERE id=?", answered)[0]["proposal_id"]


def test_a_merge_leaves_an_unsure_answer_of_the_rally_it_keeps_alone(match):
    client, job_id, storage = match
    answer(client, job_id, 2, "UNSURE")
    version_before = card_of(client, job_id, 2)["version"]
    assert merge_previous(client, job_id, 2).status_code == 200
    # "Not sure" was said about the end of this rally, and the end did not move. Only "not a rally" is taken back.
    kept = card_of(client, job_id, 1)
    assert (rally(client, job_id, 1)["status"], kept["answer"], kept["version"]) == ("UNRESOLVED", "UNSURE", version_before)


def test_a_split_keeps_the_answer_on_the_later_part_whatever_it_was(match):
    client, job_id, _ = match
    answer(client, job_id, 2, "NOT_A_RALLY")
    assert split(client, job_id, 2, 20_000).status_code == 200
    earlier, later = rally(client, job_id, 2), rally(client, job_id, 3)
    assert (earlier["status"], earlier["origin"], later["status"], later["origin"]) == ("PROPOSED", "USER", "REJECTED", "ANALYZER")
    assert (card_of(client, job_id, 2)["status"], card_of(client, job_id, 3)["answer"]) == ("OPEN", "NOT_A_RALLY")


@pytest.mark.parametrize("given", ["NOT_A_RALLY", "UNSURE"])
def test_a_moved_end_asks_again_about_a_rally_the_user_did_not_count(match, given):
    client, job_id, storage = match
    answer(client, job_id, 2, given)
    assert move(client, job_id, 2, 15_000, 26_000).status_code == 200
    assert (rally(client, job_id, 2)["status"], card_of(client, job_id, 2)["status"]) == ("PROPOSED", "OPEN")
    last = audit(storage)[-1]
    assert (last["action"], last["previous_value"], last["new_value"], last["correction_id"]) == ("REOPEN", given, None, corrections(storage)[-1]["id"])


def test_a_rally_moved_past_another_one_is_numbered_by_where_it_is_now(match):
    client, job_id, _ = match
    answer(client, job_id, 1, "HOME")
    first = rally(client, job_id, 1)["id"]
    # Into the pause between the second and the third rally. Only the start of its clip changes with the end, so the answer goes.
    assert move(client, job_id, 1, 25_000, 29_000).status_code == 200
    assert spans(client, job_id) == [(15_000, 24_000), (25_000, 29_000), (30_000, 41_000), (48_000, 56_000)]
    assert (rally(client, job_id, 2)["id"], card_of(client, job_id, 2)["rallyId"], card_of(client, job_id, 2)["status"]) == (first, first, "OPEN")


def test_a_correction_that_fails_half_way_leaves_nothing_behind(match, monkeypatch):
    client, job_id, storage = match
    answer(client, job_id, 1, "HOME")

    def state():
        return listing(client, job_id), cards(client, job_id), len(audit(storage)), len(corrections(storage)), revision(storage, job_id)

    before = state()

    def broken(*args, **kwargs):
        raise RuntimeError("the disk is full")

    # A merge has removed the earlier rally and written its answer to the audit by the time it sets the new interval.
    monkeypatch.setattr("app.corrections._set_interval", broken)
    with pytest.raises(RuntimeError):
        merge_previous(client, job_id, 2)
    monkeypatch.undo()
    assert state() == before


def test_proposals_and_corrections_can_not_be_rewritten_and_leave_only_with_their_match(match):
    client, job_id, storage = match
    assert add(client, job_id, 10_000, 13_000).status_code == 201
    for table in ("rally_proposals", "rally_corrections"):
        for statement in (f"UPDATE {table} SET job_id='x'", f"DELETE FROM {table}"):
            with pytest.raises(sqlite3.IntegrityError, match="append-only"):
                with sqlite3.connect(storage / "volleystat.sqlite3") as db:
                    db.execute("PRAGMA foreign_keys=ON")
                    db.execute(statement)
    match_id = job_of(client, job_id)["matchId"]
    assert client.delete(f"/v1/matches/{match_id}").status_code == 204
    for table in ("rallies", "rally_proposals", "rally_corrections"):
        assert rows(storage, f"SELECT 1 FROM {table}") == [], table


def test_storage_written_by_version_0_4_gets_its_new_columns_and_its_proposals_at_the_next_start(storage, make_clip):
    with serve(storage, ScriptedAnalyzer(result=proposals(SPANS))) as client:
        job_id = start_job(client, make_clip)
        decide(client, card_of(client, job_id, 1), "HOME")
    # Put the storage back to what version 0.4 wrote: no list version, no confirmation, no record of
    # the proposals and no table of corrections.
    with sqlite3.connect(storage / "volleystat.sqlite3") as db:
        db.executescript(
            "DROP TABLE rally_corrections; DROP TABLE rally_proposals;"
            "ALTER TABLE rallies DROP COLUMN proposal_id;"
            "ALTER TABLE review_decisions DROP COLUMN correction_id;"
            "ALTER TABLE analysis_jobs DROP COLUMN rally_list_version;"
            "ALTER TABLE analysis_jobs DROP COLUMN list_confirmed_version;"
            "ALTER TABLE analysis_jobs DROP COLUMN list_confirmed_at;"
        )

    with TestClient(create_app(storage, analyzer="none")) as client:
        found = listing(client, job_id)
        assert [(item["startMs"], item["endMs"], item["origin"]) for item in found["rallies"]] == [(start, end, "ANALYZER") for start, end in SPANS]
        assert (found["rallyListVersion"], found["listConfirmed"], found["rallies"][0]["winner"]) == (0, False, "HOME")
        assert report_of(client, job_id)["rallies"]["proposed"] == 4
        # The storage of the old version takes corrections like any other.
        assert add(client, job_id, 10_000, 13_000).status_code == 201
    assert [(row["start_ms"], row["end_ms"]) for row in rows(storage, "SELECT * FROM rally_proposals ORDER BY start_ms")] == list(SPANS)

    # The start-up after that one finds nothing left to do. A rally the user made is not turned into a proposal.
    with TestClient(create_app(storage, analyzer="none")) as client:
        assert len(rows(storage, "SELECT 1 FROM rally_proposals")) == 4
        assert [item["origin"] for item in listing(client, job_id)["rallies"]] == ["ANALYZER", "USER", "ANALYZER", "ANALYZER", "ANALYZER"]
        counts = report_of(client, job_id)["rallies"]
        assert (counts["proposed"], counts["userCreated"]) == (4, 1)


def test_an_analyzer_whose_rallies_overlap_fails_its_job(storage, make_clip):
    overlapping = ScriptedAnalyzer(result=proposals(((2_000, 9_000), (8_999, 12_000))))
    with serve(storage, overlapping) as client:
        video = store(client, make_clip("minute.mp4", size="320x180", seconds=60))
        job_id = request_job(client, video["id"])["id"]
        assert run_pending(client) == 1
        job = job_of(client, job_id)
        assert (job["state"], job["failure"]["code"]) == ("FAILED", "ANALYZER_ERROR")

        overlapping.result = proposals(((2_000, 9_000), (9_000, 12_000)))
        touching = request_job(client, video["id"])["id"]
        assert run_pending(client) == 1 and job_of(client, touching)["state"] == "NEEDS_REVIEW"
