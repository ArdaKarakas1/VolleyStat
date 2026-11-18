"""The rule a match is counted with (docs/API_V1.md, sections 1, 4 and 7; ADR-0115).

The rallies come from a scripted analyzer over synthetic test patterns. The intervals, the answers
and the scores are made up by the tests and describe no real match.
"""
import sqlite3

import pytest

from app.analyzers.base import MOCK, AnalyzerInfo
from tests.helpers import ScriptedAnalyzer, cards, decide, one_match, only_sets, proposals, request_job, rows, run_pending, serve, tiny_clip, without_statistics
from tests.test_api import create_match
from tests.test_media import store

FIVB = {"setsToWin": 3, "setTarget": 25, "decidingSetTarget": 15}
BEST_OF_THREE_TO_21 = {"setsToWin": 2, "setTarget": 21, "decidingSetTarget": 15}
# 57 rallies of 0.9 s, one in every second of the one-minute clip.
SPANS = tuple((second * 1000 + 50, second * 1000 + 950) for second in range(57))
# HOME takes the first set 21-0, AWAY the second 21-0 and HOME the third 15-0.
WINNERS = ["HOME"] * 21 + ["AWAY"] * 21 + ["HOME"] * 15


def payload(rules):
    return {**rules, "winBy": 2, "decidingSetNumber": 2 * rules["setsToWin"] - 1}


def set_line(number, home, away, complete=True):
    return {"number": number, "home": home, "away": away, "complete": complete}


def minute_clip(make_clip):
    return make_clip("minute.mp4", size="320x180", seconds=60)


def analysed(client, data, match_id):
    """Upload `data` under the match, run the configured analyzer and return the job id."""
    video = store(client, data, match_id=match_id)
    assert video["status"] == "READY"
    job_id = request_job(client, video["id"])["id"]
    assert run_pending(client) == 1
    return job_id


def answer_winners(client, job_id, winners):
    by_sequence = {card["rallySequence"]: card for card in cards(client, job_id, "SCORE")}
    for sequence, winner in enumerate(winners, start=1):
        decide(client, by_sequence[sequence], winner)


def listing(client, job_id):
    response = client.get(f"/v1/analyses/{job_id}/rallies")
    assert response.status_code == 200, response.text
    return response.json()


def report_of(client, match_id):
    response = client.get(f"/v1/matches/{match_id}/report")
    assert response.status_code == 200, response.text
    return response.json()


SEEN = object()


def put_rules(client, match_id, rules, expected=SEEN):
    """Ask for `rules` and name the rule the caller saw: by default the rule the match has now."""
    if rules is None:
        return client.put(f"/v1/matches/{match_id}/rules", json=None)
    if expected is SEEN:
        found = client.get(f"/v1/matches/{match_id}")
        expected = {key: found.json()["rules"][key] for key in FIVB} if found.status_code == 200 else FIVB
    return client.put(f"/v1/matches/{match_id}/rules", json={**rules, "expectedRules": expected})


def confirm(client, job_id, expected=None):
    version = listing(client, job_id)["rallyListVersion"] if expected is None else expected
    return client.post(f"/v1/analyses/{job_id}/rally-list/confirm", json={"expectedListVersion": version})


def job_row(storage, job_id):
    return rows(storage, "SELECT state, revision, rally_list_version, list_confirmed_version, list_confirmed_at"
                         " FROM analysis_jobs WHERE id=?", job_id)[0]


@pytest.fixture
def best_of_three(storage, make_clip):
    """A best-of-three match to 21 with a third set to 15, its 57 rallies answered as WINNERS.

    Yields the client, the match id, the job id and the storage folder.
    """
    with serve(storage, ScriptedAnalyzer(result=proposals(SPANS))) as client:
        match_id = create_match(client, rules=BEST_OF_THREE_TO_21)
        job_id = analysed(client, minute_clip(make_clip), match_id)
        answer_winners(client, job_id, WINNERS)
        yield client, match_id, job_id, storage


# --- counting with the rule of the match ------------------------------------------------------


def test_a_best_of_three_match_to_21_with_a_third_set_to_15_is_counted_as_played(best_of_three):
    client, match_id, job_id, _ = best_of_three
    played = [set_line(1, 21, 0), set_line(2, 0, 21), set_line(3, 15, 0)]
    assert only_sets(listing(client, job_id)) == played
    score = report_of(client, match_id)["score"]
    # Until the user confirms the list, a rally may still be missing.
    assert one_match(score) == {"status": "PARTIAL", "setsWon": {"home": 2, "away": 1}, "sets": played, "stoppedAtRally": None}
    assert score["matches"][0]["kind"] == "MATCH" and score["matches"][0]["winner"] == "HOME"
    assert confirm(client, job_id).status_code == 200
    assert one_match(report_of(client, match_id)["score"]) == {**one_match(score), "status": "MEASURED"}


def test_the_rally_list_names_the_rule_its_sets_were_counted_with(best_of_three):
    client, _, job_id, _ = best_of_three
    found = listing(client, job_id)
    assert set(found) == {"rallies", "rallyListVersion", "listConfirmed", "videoDurationMs", "matches", "rules"}
    assert found["rules"] == payload(BEST_OF_THREE_TO_21)


def test_the_score_after_each_rally_follows_the_match_rule(best_of_three):
    client, _, job_id, _ = best_of_three
    after = {rally["sequence"]: rally["scoreAfter"] for rally in listing(client, job_id)["rallies"]}
    assert after[21] == {"match": 1, "set": 1, "home": 21, "away": 0}
    assert after[22] == {"match": 1, "set": 2, "home": 0, "away": 1}
    assert after[42] == {"match": 1, "set": 2, "home": 0, "away": 21}
    assert after[57] == {"match": 1, "set": 3, "home": 15, "away": 0}


def test_the_rallies_a_correction_returns_are_scored_with_the_match_rule(best_of_three):
    client, _, job_id, _ = best_of_three
    for sequence, expected in ((22, {"match": 1, "set": 2, "home": 0, "away": 1}), (57, {"match": 1, "set": 3, "home": 15, "away": 0})):
        found = listing(client, job_id)
        moved = found["rallies"][sequence - 1]
        response = client.post(f"/v1/rallies/{moved['id']}/move", json={
            "startMs": moved["startMs"] + 100, "endMs": moved["endMs"], "expectedListVersion": found["rallyListVersion"]})
        assert response.status_code == 200, response.text
        # A moved start keeps the answer, and the answer counts where the rule puts it.
        assert response.json()["affected"][0]["scoreAfter"] == expected
        assert listing(client, job_id)["rallies"][sequence - 1]["scoreAfter"] == expected


def test_the_match_the_list_and_the_report_carry_the_same_rule(best_of_three):
    client, match_id, job_id, _ = best_of_three
    expected = payload(BEST_OF_THREE_TO_21)
    assert client.get(f"/v1/matches/{match_id}").json()["rules"] == expected
    assert listing(client, job_id)["rules"] == expected
    assert report_of(client, match_id)["rules"] == expected


def test_two_matches_count_with_their_own_rules(storage, make_clip):
    with serve(storage, ScriptedAnalyzer(result=proposals(SPANS))) as client:
        counted = {}
        for name, rules in (("fivb", None), ("to 21", BEST_OF_THREE_TO_21)):
            match_id = create_match(client, rules=rules)
            job_id = analysed(client, minute_clip(make_clip), match_id)
            answer_winners(client, job_id, ["HOME"] * 21)
            counted[name] = (only_sets(listing(client, job_id)), one_match(report_of(client, match_id)["score"])["setsWon"])
        assert counted["fivb"] == ([set_line(1, 21, 0, complete=False)], {"home": 0, "away": 0})
        assert counted["to 21"] == ([set_line(1, 21, 0)], {"home": 1, "away": 0})


def test_the_rally_list_and_the_report_split_two_matches_alike(storage, make_clip):
    # Best of three to 15: HOME wins 15-0 and 15-0, and rally 31 opens the second match.
    with serve(storage, ScriptedAnalyzer(result=proposals(SPANS))) as client:
        match_id = create_match(client, rules={"setsToWin": 2, "setTarget": 15, "decidingSetTarget": 15})
        job_id = analysed(client, minute_clip(make_clip), match_id)
        answer_winners(client, job_id, ["HOME"] * 31)
        found = listing(client, job_id)
        report = report_of(client, match_id)
    assert [(part["number"], part["kind"], part["setsWon"]) for part in found["matches"]] == [
        (1, "MATCH", {"home": 2, "away": 0}), (2, "STOPPED", {"home": 0, "away": 0})]
    assert found["matches"] == without_statistics(report["score"]["matches"])
    after = {rally["sequence"]: rally["scoreAfter"] for rally in found["rallies"]}
    assert after[30] == {"match": 1, "set": 2, "home": 15, "away": 0}
    assert after[31] == {"match": 2, "set": 1, "home": 1, "away": 0}
    assert after[32] is None


# --- changing the rule ------------------------------------------------------------------------


def test_changing_the_rule_recounts_the_list_and_the_report(best_of_three):
    client, match_id, job_id, _ = best_of_three
    before = listing(client, job_id)
    changed = put_rules(client, match_id, FIVB)
    assert changed.status_code == 200, changed.text
    assert changed.json() == client.get(f"/v1/matches/{match_id}").json()
    assert changed.json()["rules"] == payload(FIVB)
    found = listing(client, job_id)
    assert (found["rallyListVersion"], found["listConfirmed"], found["rules"]) == (before["rallyListVersion"] + 1, False, payload(FIVB))
    # Counted to 25, the first set runs on into the rallies of the second one.
    assert only_sets(found) == [set_line(1, 25, 21), set_line(2, 11, 0, complete=False)]
    after = {rally["sequence"]: rally["scoreAfter"] for rally in found["rallies"]}
    assert (after[46], after[47]) == ({"match": 1, "set": 1, "home": 25, "away": 21}, {"match": 1, "set": 2, "home": 1, "away": 0})
    score = one_match(report_of(client, match_id)["score"])
    assert (score["status"], score["setsWon"], score["sets"]) == ("PARTIAL", {"home": 1, "away": 0}, only_sets(found))
    assert report_of(client, match_id)["rules"] == payload(FIVB)


def test_setting_the_rule_the_match_already_has_changes_nothing(best_of_three):
    client, match_id, job_id, storage = best_of_three
    assert confirm(client, job_id).status_code == 200
    before = job_row(storage, job_id)
    again = put_rules(client, match_id, BEST_OF_THREE_TO_21)
    assert again.status_code == 200 and again.json()["rules"] == payload(BEST_OF_THREE_TO_21)
    assert job_row(storage, job_id) == before
    assert listing(client, job_id)["listConfirmed"] is True


# The later change differs from the rule the late request names in two fields, or in exactly one of them.
@pytest.mark.parametrize("later", [
    FIVB,
    {**BEST_OF_THREE_TO_21, "setsToWin": 3},
    {**BEST_OF_THREE_TO_21, "setTarget": 25},
    {**BEST_OF_THREE_TO_21, "decidingSetTarget": 25},
], ids=["two-fields", "sets-to-win", "set-target", "deciding-target"])
def test_a_late_request_for_a_rule_does_not_undo_a_later_change(best_of_three, later):
    client, match_id, job_id, storage = best_of_three
    # The phone asked for "every set to 15" and gave up waiting. The user then chose another rule, which landed.
    assert put_rules(client, match_id, later, expected=BEST_OF_THREE_TO_21).status_code == 200
    before = (client.get(f"/v1/matches/{match_id}").json(), job_row(storage, job_id))
    # The first request arrives now. It names the rule its phone last read, which the match no longer has.
    late = put_rules(client, match_id, {"setsToWin": 2, "setTarget": 15, "decidingSetTarget": 15}, expected=BEST_OF_THREE_TO_21)
    assert late.status_code == 409
    assert late.json()["error"]["code"] == "MATCH_RULES_STALE"
    assert late.json()["error"]["details"]["currentRules"] == payload(later)
    assert (client.get(f"/v1/matches/{match_id}").json(), job_row(storage, job_id)) == before


def test_a_late_request_lands_when_the_match_is_back_at_the_rule_it_saw(best_of_three):
    # The known limit of comparing rules (ADR-0115): the match carries no rule revision.
    client, match_id, job_id, _ = best_of_three
    every_set_to_25 = {"setsToWin": 2, "setTarget": 25, "decidingSetTarget": 25}
    assert put_rules(client, match_id, FIVB, expected=BEST_OF_THREE_TO_21).status_code == 200
    assert put_rules(client, match_id, BEST_OF_THREE_TO_21, expected=FIVB).status_code == 200
    late = put_rules(client, match_id, every_set_to_25, expected=BEST_OF_THREE_TO_21)
    assert late.status_code == 200 and late.json()["rules"] == payload(every_set_to_25)
    # Whatever landed, the list names the rule it is counted with.
    assert listing(client, job_id)["rules"] == payload(every_set_to_25)


def test_a_repeated_rule_change_lands_once(best_of_three):
    client, match_id, job_id, storage = best_of_three
    assert confirm(client, job_id).status_code == 200
    assert put_rules(client, match_id, FIVB, expected=BEST_OF_THREE_TO_21).status_code == 200
    moved = job_row(storage, job_id)
    assert confirm(client, job_id).status_code == 200
    confirmed = job_row(storage, job_id)
    # The answer to the first request was lost and the phone sends it again. The match has the
    # rule it asks for, so the server writes nothing, even though the request names the old rule.
    again = put_rules(client, match_id, FIVB, expected=BEST_OF_THREE_TO_21)
    assert again.status_code == 200 and again.json()["rules"] == payload(FIVB)
    assert job_row(storage, job_id) == confirmed and confirmed["rally_list_version"] == moved["rally_list_version"]
    assert listing(client, job_id)["listConfirmed"] is True


@pytest.mark.parametrize("expected", [
    "missing", None, {**FIVB, "setsToWin": 4}, {"setsToWin": 3, "setTarget": 25}, {**FIVB, "winBy": 2},
], ids=["missing", "null", "sets-4", "missing-key", "extra-key"])
def test_a_rule_change_must_name_an_offered_rule_as_the_rule_it_saw(best_of_three, expected):
    client, match_id, job_id, storage = best_of_three
    before = (client.get(f"/v1/matches/{match_id}").json(), job_row(storage, job_id))
    body = dict(FIVB) if expected == "missing" else {**FIVB, "expectedRules": expected}
    refused = client.put(f"/v1/matches/{match_id}/rules", json=body)
    assert refused.status_code == 422 and refused.json()["error"]["code"] == "VALIDATION_ERROR"
    assert (client.get(f"/v1/matches/{match_id}").json(), job_row(storage, job_id)) == before


def test_a_rule_change_moves_every_reviewable_list_of_the_match_and_nothing_else(storage, make_clip):
    with serve(storage, ScriptedAnalyzer(result=proposals())) as client:
        clip = tiny_clip(make_clip)
        match_id, other_match = create_match(client), create_match(client)
        reviewed = [request_job(client, store(client, clip, match_id=match_id)["id"])["id"] for _ in range(2)]
        elsewhere = request_job(client, store(client, clip, match_id=other_match)["id"])["id"]
        assert run_pending(client) == 3
        queued = request_job(client, store(client, clip, match_id=match_id)["id"])["id"]
        before = {job_id: job_row(storage, job_id) for job_id in (*reviewed, elsewhere, queued)}

        assert put_rules(client, match_id, BEST_OF_THREE_TO_21).status_code == 200

        for job_id in reviewed:
            moved = job_row(storage, job_id)
            assert (moved["rally_list_version"], moved["revision"], moved["state"]) == (
                before[job_id]["rally_list_version"] + 1, before[job_id]["revision"] + 1, before[job_id]["state"])
        assert job_row(storage, elsewhere) == before[elsewhere]
        assert job_row(storage, queued) == before[queued] and before[queued]["state"] == "QUEUED"
        # A job that finishes after the change starts its list at version 0 and counts with the new rule.
        assert run_pending(client) == 1
        found = listing(client, queued)
        assert (found["rallyListVersion"], found["rules"]) == (0, payload(BEST_OF_THREE_TO_21))
        assert client.get(f"/v1/matches/{other_match}").json()["rules"] == payload(FIVB)


def test_a_rule_change_takes_back_a_confirmation_given_under_the_old_rule(best_of_three):
    client, match_id, job_id, _ = best_of_three
    assert confirm(client, job_id).status_code == 200
    assert report_of(client, match_id)["score"]["status"] == "MEASURED"
    assert put_rules(client, match_id, FIVB).status_code == 200
    # The user confirmed set scores that the server no longer shows.
    assert listing(client, job_id)["listConfirmed"] is False
    report = report_of(client, match_id)
    assert (report["score"]["status"], report["rallyList"]["confirmed"], report["rallyList"]["confirmedAt"]) == ("PARTIAL", False, None)
    assert confirm(client, job_id).status_code == 200
    assert report_of(client, match_id)["score"]["status"] == "MEASURED"


def test_a_confirmation_sent_for_the_list_before_a_rule_change_is_stale(best_of_three):
    client, match_id, job_id, _ = best_of_three
    seen = listing(client, job_id)["rallyListVersion"]
    assert put_rules(client, match_id, FIVB).status_code == 200
    stale = confirm(client, job_id, expected=seen)
    assert stale.status_code == 409
    assert stale.json()["error"]["code"] == "RALLY_LIST_STALE"
    assert stale.json()["error"]["details"]["currentListVersion"] == seen + 1
    assert listing(client, job_id)["listConfirmed"] is False


def test_a_correction_sent_for_the_list_before_a_rule_change_is_stale(best_of_three):
    client, match_id, job_id, _ = best_of_three
    seen = listing(client, job_id)
    assert put_rules(client, match_id, FIVB).status_code == 200
    stale = client.post(f"/v1/analyses/{job_id}/rallies", json={"startMs": 57_100, "endMs": 58_000,
                                                               "expectedListVersion": seen["rallyListVersion"]})
    assert stale.status_code == 409 and stale.json()["error"]["code"] == "RALLY_LIST_STALE"
    assert len(listing(client, job_id)["rallies"]) == len(seen["rallies"])


def test_a_rule_change_keeps_every_answer_card_and_audit_row(best_of_three):
    client, match_id, job_id, storage = best_of_three
    every_card = cards(client, job_id)
    audit = rows(storage, "SELECT * FROM review_decisions ORDER BY rowid")
    rallies = [{key: rally[key] for key in ("id", "sequence", "startMs", "endMs", "status", "winner")}
               for rally in listing(client, job_id)["rallies"]]
    assert put_rules(client, match_id, FIVB).status_code == 200
    assert cards(client, job_id) == every_card
    assert rows(storage, "SELECT * FROM review_decisions ORDER BY rowid") == audit
    assert [{key: rally[key] for key in ("id", "sequence", "startMs", "endMs", "status", "winner")}
            for rally in listing(client, job_id)["rallies"]] == rallies
    # A card keeps its version, so an answer sent with the version read before the change lands.
    first = next(card for card in every_card if card["rallySequence"] == 1 and card["questionType"] == "RALLY_WINNER")
    assert decide(client, first, "AWAY")["card"]["answer"] == "AWAY"


def test_a_rule_change_is_not_a_correction_of_the_list(best_of_three):
    client, match_id, job_id, storage = best_of_three
    assert put_rules(client, match_id, FIVB).status_code == 200
    assert rows(storage, "SELECT * FROM rally_corrections") == []
    assert report_of(client, match_id)["rallyList"]["corrections"] == 0


def test_the_rule_of_a_match_without_an_analysis_can_be_changed(client):
    match_id = create_match(client)
    changed = put_rules(client, match_id, BEST_OF_THREE_TO_21)
    assert changed.status_code == 200 and changed.json()["rules"] == payload(BEST_OF_THREE_TO_21)
    assert client.get(f"/v1/matches/{match_id}").json() == changed.json()


def test_the_rule_of_an_unknown_match_can_not_be_changed(client, storage):
    missing = put_rules(client, "no-such-match", BEST_OF_THREE_TO_21)
    assert missing.status_code == 404 and missing.json()["error"]["code"] == "MATCH_NOT_FOUND"
    assert rows(storage, "SELECT 1 FROM matches") == []


@pytest.mark.parametrize("rules", [
    {**BEST_OF_THREE_TO_21, "setsToWin": 4},
    {**BEST_OF_THREE_TO_21, "setTarget": "21"},
    {**BEST_OF_THREE_TO_21, "decidingSetTarget": 30},
    {"setsToWin": 2, "decidingSetTarget": 15},
    {**BEST_OF_THREE_TO_21, "decidingSetNumber": 3},
    None,
], ids=["sets-4", "target-text", "deciding-30", "missing-key", "extra-key", "null"])
def test_an_invalid_rule_change_changes_nothing(best_of_three, rules):
    client, match_id, job_id, storage = best_of_three
    before = (client.get(f"/v1/matches/{match_id}").json(), job_row(storage, job_id))
    refused = put_rules(client, match_id, rules)
    assert refused.status_code == 422 and refused.json()["error"]["code"] == "VALIDATION_ERROR"
    assert (client.get(f"/v1/matches/{match_id}").json(), job_row(storage, job_id)) == before


def test_a_rule_change_that_fails_half_way_leaves_nothing_behind(best_of_three, monkeypatch):
    client, match_id, job_id, storage = best_of_three
    before = (client.get(f"/v1/matches/{match_id}").json(), job_row(storage, job_id), listing(client, job_id))

    calls = []

    def broken(*args, **kwargs):
        calls.append(args)
        raise RuntimeError("the disk is full")

    # The answer is built after the rule and the list versions were written. The request names the
    # rule it saw itself: reading the match first would run into the broken answer before the change is sent.
    monkeypatch.setattr("app.main.match_payload", broken)
    with pytest.raises(RuntimeError):
        put_rules(client, match_id, FIVB, expected=BEST_OF_THREE_TO_21)
    monkeypatch.undo()
    # The change got as far as its answer, and the match the route wanted to answer with had the new rule.
    assert [(row["sets_to_win"], row["set_target"], row["deciding_set_target"]) for (row,) in calls] == [(3, 25, 15)]
    assert (client.get(f"/v1/matches/{match_id}").json(), job_row(storage, job_id), listing(client, job_id)) == before


def test_a_demo_list_is_counted_with_the_new_rule_and_still_can_not_be_confirmed(storage, make_clip):
    demo = ScriptedAnalyzer(result=proposals(SPANS), info=AnalyzerInfo("demo-like", "t1", MOCK))
    with serve(storage, demo) as client:
        match_id = create_match(client)
        job_id = analysed(client, minute_clip(make_clip), match_id)
        answer_winners(client, job_id, ["HOME"] * 21)
        assert only_sets(listing(client, job_id)) == [set_line(1, 21, 0, complete=False)]
        assert put_rules(client, match_id, BEST_OF_THREE_TO_21).status_code == 200
        found = listing(client, job_id)
        assert (only_sets(found), found["rules"]) == ([set_line(1, 21, 0)], payload(BEST_OF_THREE_TO_21))
        refused = confirm(client, job_id)
        assert refused.status_code == 409 and refused.json()["error"]["code"] == "MOCK_LIST_NOT_CONFIRMABLE"


# --- storage of an earlier version ------------------------------------------------------------


def test_a_match_stored_by_version_0_5_reads_the_fivb_rule_and_keeps_its_report(storage, make_clip):
    with serve(storage, ScriptedAnalyzer(result=proposals(SPANS))) as client:
        match_id = create_match(client)
        job_id = analysed(client, minute_clip(make_clip), match_id)
        answer_winners(client, job_id, WINNERS)
        assert confirm(client, job_id).status_code == 200
        before = (client.get(f"/v1/matches/{match_id}").json(), listing(client, job_id), report_of(client, match_id))
    # Put the storage back to what version 0.5 wrote: no rule on the match.
    with sqlite3.connect(storage / "volleystat.sqlite3") as db:
        db.executescript("ALTER TABLE matches DROP COLUMN sets_to_win; ALTER TABLE matches DROP COLUMN set_target;"
                         " ALTER TABLE matches DROP COLUMN deciding_set_target;")

    for _ in range(2):
        # The start-up adds the rule as FIVB, the way version 0.5 counted. Nothing moves and the
        # confirmation stands. A second start-up finds nothing left to do.
        with serve(storage, "none") as client:
            after = (client.get(f"/v1/matches/{match_id}").json(), listing(client, job_id), report_of(client, match_id))
            assert after == before
            assert after[0]["rules"] == payload(FIVB) and after[1]["listConfirmed"] is True
