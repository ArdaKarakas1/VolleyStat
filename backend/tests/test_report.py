"""The match report (docs/API_V1.md, section 7).

The first part tests the pure function with hand-written rally records. The records are test
input, not results of any match. The second part reads the report through the route.
"""
import copy

import pytest

from app.analyzers.base import AnalyzerInfo, MODEL, RallyProposal
from app.report import RULES, build_report
from tests.helpers import ScriptedAnalyzer, card_of, decide, ready_video, request_job, reviewed_job, run_pending, serve
from tests.test_api import create_match

HOME, AWAY = "HOME", "AWAY"
OPEN = {"status": "PROPOSED", "proposed_winner": "UNKNOWN", "winner": None, "terminal_action": None, "scorer_jersey": None}
UNSURE = {**OPEN, "status": "UNRESOLVED"}
NOT_A_RALLY = {**OPEN, "status": "REJECTED"}
UNKNOWN_VALUE = {"value": None, "status": "UNKNOWN"}
TEAM_FIELDS = ("points", "attackPoints", "blockPoints", "aces", "opponentErrors")


def won(side, action=None, jersey=None):
    return {"status": "VERIFIED", "proposed_winner": "UNKNOWN", "winner": side, "terminal_action": action, "scorer_jersey": jersey}


def measured(value):
    return {"value": value, "status": "MEASURED"}


def partial(value):
    return {"value": value, "status": "PARTIAL"}


def score(rallies):
    return build_report(rallies)["score"]


def set_line(number, home, away, complete=True):
    return {"number": number, "home": home, "away": away, "complete": complete}


def coverage(rallies, metric):
    return next(entry for entry in build_report(rallies)["coverage"] if entry["metricId"] == metric)


def value_objects(node):
    """Every {"value", "status"} object anywhere inside a report."""
    if isinstance(node, dict):
        if set(node) == {"value", "status"}:
            yield node
        for child in node.values():
            yield from value_objects(child)
    elif isinstance(node, list):
        for child in node:
            yield from value_objects(child)


# --- nothing reviewed ------------------------------------------------------------------------

def test_unreviewed_rallies_give_a_draft_with_unknown_values_and_no_zero():
    report = build_report([OPEN, OPEN, OPEN])
    assert report["verificationStatus"] == "DRAFT"
    assert report["rallies"] == {"proposed": 3, "total": 3, "userCreated": 0, "verified": 0, "rejected": 0, "unresolved": 0, "open": 3}
    # The score walk stops at the first rally: it has no winner yet.
    assert report["score"] == {"status": "UNKNOWN", "setsWon": {"home": None, "away": None}, "sets": [], "stoppedAtRally": 1}
    for side in ("home", "away"):
        assert report["teams"][side] == {field: UNKNOWN_VALUE for field in TEAM_FIELDS}
    assert report["players"] == []
    assert report["unattributed"] == {"home": None, "away": None}
    assert [(entry["metricId"], entry["eligibleN"], entry["observedN"], entry["verifiedN"], entry["status"])
            for entry in report["coverage"]] == [
        ("RALLY_WINNER", 3, 0, 0, "UNKNOWN"), ("TERMINAL_ACTION", 3, 0, 0, "UNKNOWN"), ("SCORER", 3, 0, 0, "UNKNOWN")]


def test_the_rules_in_use_are_part_of_the_report():
    assert build_report([OPEN])["rules"] == {"setTarget": 25, "decidingSetTarget": 15, "winBy": 2, "decidingSetNumber": 5}
    # A caller that edits its copy must not change the rules of the next report.
    build_report([OPEN])["rules"]["setTarget"] = 1
    assert RULES["setTarget"] == 25


def test_a_model_proposal_alone_scores_nothing():
    proposed = {**OPEN, "proposed_winner": HOME}
    report = build_report([proposed, proposed])
    assert report["verificationStatus"] == "DRAFT"
    assert report["score"]["status"] == "UNKNOWN" and report["score"]["sets"] == []
    assert report["teams"]["home"]["points"] == UNKNOWN_VALUE
    # The proposal is visible as "observed", never as "verified".
    assert coverage([proposed, proposed], "RALLY_WINNER") == {
        "metricId": "RALLY_WINNER", "eligibleN": 2, "observedN": 2, "verifiedN": 0, "status": "UNKNOWN"}


# --- set scoring -----------------------------------------------------------------------------

def test_a_set_ends_at_25_with_a_two_point_lead():
    result = score([won(AWAY)] * 23 + [won(HOME)] * 25)
    assert result == {"status": "MEASURED", "setsWon": {"home": 1, "away": 0},
                      "sets": [{"number": 1, "home": 25, "away": 23, "complete": True}], "stoppedAtRally": None}


def test_24_points_do_not_end_a_set():
    result = score([won(HOME)] * 24)
    assert result == {"status": "MEASURED", "setsWon": {"home": 0, "away": 0},
                      "sets": [{"number": 1, "home": 24, "away": 0, "complete": False}], "stoppedAtRally": None}


def test_a_set_tied_at_24_runs_until_one_side_leads_by_two():
    tied = [won(HOME)] * 24 + [won(AWAY)] * 24
    assert score(tied)["sets"] == [{"number": 1, "home": 24, "away": 24, "complete": False}]
    assert score(tied + [won(AWAY)])["sets"] == [{"number": 1, "home": 24, "away": 25, "complete": False}]
    assert score(tied + [won(AWAY), won(HOME), won(HOME)])["sets"] == [{"number": 1, "home": 26, "away": 25, "complete": False}]
    result = score(tied + [won(AWAY), won(HOME), won(HOME), won(HOME)])
    assert result["sets"] == [{"number": 1, "home": 27, "away": 25, "complete": True}]
    assert result["setsWon"] == {"home": 1, "away": 0}


def test_points_after_a_finished_set_open_the_next_set():
    result = score([won(HOME)] * 25 + [won(AWAY)] * 3)
    assert result == {"status": "MEASURED", "setsWon": {"home": 1, "away": 0}, "sets": [
        {"number": 1, "home": 25, "away": 0, "complete": True},
        {"number": 2, "home": 0, "away": 3, "complete": False}], "stoppedAtRally": None}


def test_the_fifth_set_is_played_to_15_and_the_fourth_is_not():
    four_sets = [won(HOME)] * 25 + [won(AWAY)] * 25 + [won(HOME)] * 25 + [won(AWAY)] * 15
    # 15 points are not enough in the fourth set.
    assert score(four_sets)["sets"][3] == {"number": 4, "home": 0, "away": 15, "complete": False}
    four_sets += [won(AWAY)] * 10
    assert score(four_sets)["setsWon"] == {"home": 2, "away": 2}
    assert score(four_sets + [won(HOME)] * 14)["sets"][4] == {"number": 5, "home": 14, "away": 0, "complete": False}
    decided = score(four_sets + [won(HOME)] * 15)
    assert decided["sets"][4] == {"number": 5, "home": 15, "away": 0, "complete": True}
    assert decided["setsWon"] == {"home": 3, "away": 2} and decided["status"] == "MEASURED"


def test_the_fifth_set_also_needs_a_two_point_lead():
    four_sets = [won(HOME)] * 25 + [won(AWAY)] * 25 + [won(HOME)] * 25 + [won(AWAY)] * 25
    tied = four_sets + [won(HOME)] * 14 + [won(AWAY)] * 14
    assert score(tied + [won(AWAY)])["sets"][4] == {"number": 5, "home": 14, "away": 15, "complete": False}
    result = score(tied + [won(AWAY), won(AWAY)])
    assert result["sets"][4] == {"number": 5, "home": 14, "away": 16, "complete": True}
    assert result["setsWon"] == {"home": 2, "away": 3}


def test_three_won_sets_do_not_end_the_match_and_further_rallies_open_the_next_set():
    # Training matches often play a fixed number of sets, so a 3-0 lead closes nothing.
    result = score([won(AWAY)] * 75 + [won(HOME)] * 4)
    assert result == {"status": "MEASURED", "setsWon": {"home": 0, "away": 3}, "stoppedAtRally": None,
                      "sets": [set_line(1, 0, 25), set_line(2, 0, 25), set_line(3, 0, 25), set_line(4, 4, 0, complete=False)]}


def test_a_fourth_set_after_three_to_nil_is_scored_and_agrees_with_the_team_points():
    report = build_report([won(HOME)] * 75 + [won(AWAY)] * 25)
    assert report["score"] == {"status": "MEASURED", "setsWon": {"home": 3, "away": 1}, "stoppedAtRally": None,
                               "sets": [set_line(1, 25, 0), set_line(2, 25, 0), set_line(3, 25, 0), set_line(4, 0, 25)]}
    for side in ("home", "away"):
        assert report["teams"][side]["points"] == measured(sum(entry[side] for entry in report["score"]["sets"]))


def test_a_set_after_the_fifth_is_played_to_25_again():
    five_sets = [won(HOME)] * 25 + [won(AWAY)] * 25 + [won(HOME)] * 25 + [won(AWAY)] * 25 + [won(HOME)] * 15
    assert score(five_sets)["setsWon"] == {"home": 3, "away": 2}
    # 15 points close the fifth set only.
    assert score(five_sets + [won(AWAY)] * 15)["sets"][5] == set_line(6, 0, 15, complete=False)
    sixth = score(five_sets + [won(AWAY)] * 25)
    assert sixth["sets"][5] == set_line(6, 0, 25) and sixth["setsWon"] == {"home": 3, "away": 3}
    # A sixth set tied at 24 also needs the two-point lead.
    tied = five_sets + [won(HOME)] * 24 + [won(AWAY)] * 24 + [won(AWAY)]
    assert score(tied)["sets"][5] == set_line(6, 24, 25, complete=False)
    assert score(tied + [won(AWAY)])["sets"][5] == set_line(6, 24, 26)


@pytest.mark.parametrize("undecided", [OPEN, UNSURE])
def test_the_score_stops_at_the_first_undecided_rally(undecided):
    result = score([won(HOME)] * 10 + [undecided] + [won(HOME)] * 15)
    # Rally 11 may have gone to either side, so nothing after it can be placed in a set.
    assert result == {"status": "PARTIAL", "setsWon": {"home": 0, "away": 0},
                      "sets": [set_line(1, 10, 0, complete=False)], "stoppedAtRally": 11}


def test_rallies_after_the_undecided_rally_enter_no_set_line():
    result = score([won(HOME)] * 10 + [OPEN] + [won(HOME)] * 40)
    assert result == {"status": "PARTIAL", "setsWon": {"home": 0, "away": 0},
                      "sets": [set_line(1, 10, 0, complete=False)], "stoppedAtRally": 11}


def test_one_unsure_answer_never_produces_a_set_line_beyond_the_rules():
    # Three sets that HOME won 25-20, with the third rally answered UNSURE.
    one_set = [won(HOME)] * 20 + [won(AWAY)] * 20 + [won(HOME)] * 5
    rallies = one_set * 3
    rallies[2] = UNSURE
    report = build_report(rallies)
    assert report["score"] == {"status": "PARTIAL", "setsWon": {"home": 0, "away": 0},
                               "sets": [set_line(1, 2, 0, complete=False)], "stoppedAtRally": 3}
    # The team totals still count every verified rally of the match.
    assert report["teams"]["home"]["points"] == partial(74) and report["teams"]["away"]["points"] == partial(60)


def test_sets_finished_before_the_undecided_rally_stay_finished():
    result = score([won(HOME)] * 25 + [won(AWAY)] * 2 + [UNSURE] + [won(AWAY)] * 30)
    assert result == {"status": "PARTIAL", "setsWon": {"home": 1, "away": 0}, "stoppedAtRally": 28,
                      "sets": [set_line(1, 25, 0), set_line(2, 0, 2, complete=False)]}


def test_an_undecided_rally_right_after_a_finished_set_makes_the_score_partial():
    result = score([won(HOME)] * 25 + [OPEN])
    # The second set has no counted point, so it gets no line.
    assert result == {"status": "PARTIAL", "setsWon": {"home": 1, "away": 0},
                      "sets": [{"number": 1, "home": 25, "away": 0, "complete": True}], "stoppedAtRally": 26}


def test_review_in_progress_shows_the_counted_points_of_the_open_set():
    result = score([won(HOME), won(AWAY), won(HOME)] + [OPEN] * 10)
    assert result == {"status": "PARTIAL", "setsWon": {"home": 0, "away": 0},
                      "sets": [{"number": 1, "home": 2, "away": 1, "complete": False}], "stoppedAtRally": 4}


def test_an_undecided_first_rally_stops_the_score_before_any_point():
    result = score([OPEN, won(HOME), won(HOME)])
    assert result == {"status": "PARTIAL", "setsWon": {"home": 0, "away": 0}, "sets": [], "stoppedAtRally": 1}


def test_the_score_walk_skips_rejected_rallies_and_names_the_stop_by_its_sequence_number():
    result = score([won(HOME), NOT_A_RALLY, UNSURE, won(AWAY)])
    assert result == {"status": "PARTIAL", "setsWon": {"home": 0, "away": 0},
                      "sets": [set_line(1, 1, 0, complete=False)], "stoppedAtRally": 3}
    # A record may carry its own sequence number; without one its position in the list counts.
    numbered = [{**rally, "sequence": 10 * position} for position, rally in enumerate([won(HOME), UNSURE, won(AWAY)], start=1)]
    assert score(numbered)["stoppedAtRally"] == 20


# --- rejected rallies ------------------------------------------------------------------------

def test_a_rejected_rally_takes_part_in_nothing():
    rallies = [won(HOME)] * 24 + [NOT_A_RALLY] + [won(HOME)]
    report = build_report(rallies)
    assert report["rallies"] == {"proposed": 26, "total": 26, "userCreated": 0, "verified": 25, "rejected": 1, "unresolved": 0, "open": 0}
    # It is not an undecided rally either: the set closes across it.
    assert report["score"] == {"status": "MEASURED", "setsWon": {"home": 1, "away": 0},
                               "sets": [{"number": 1, "home": 25, "away": 0, "complete": True}], "stoppedAtRally": None}
    assert report["verificationStatus"] == "VERIFIED"
    assert report["teams"]["home"]["points"] == measured(25)
    assert [entry["eligibleN"] for entry in report["coverage"]] == [25, 25, 25]
    assert report == build_report([rally for rally in rallies if rally is not NOT_A_RALLY]) | {"rallies": report["rallies"]}


def test_only_rejected_rallies_leave_every_value_unknown():
    report = build_report([NOT_A_RALLY, NOT_A_RALLY])
    # No open card and no unresolved rally: the review is finished, and it found no rally.
    assert report["verificationStatus"] == "VERIFIED"
    assert report["score"] == {"status": "UNKNOWN", "setsWon": {"home": None, "away": None}, "sets": [], "stoppedAtRally": None}
    assert report["teams"]["home"] == {field: UNKNOWN_VALUE for field in TEAM_FIELDS}
    assert report["unattributed"] == {"home": None, "away": None}
    assert [(entry["eligibleN"], entry["status"]) for entry in report["coverage"]] == [(0, "UNKNOWN")] * 3


# --- unknown, partial and measured values ----------------------------------------------------

def test_once_a_rally_is_verified_both_sides_carry_a_number():
    report = build_report([won(HOME), won(HOME), won(HOME), OPEN])
    assert report["teams"]["home"]["points"] == partial(3)
    # Away won none of the verified rallies. PARTIAL says that the open rally can still add to the count.
    assert report["teams"]["away"]["points"] == partial(0)
    assert report["unattributed"] == {"home": 3, "away": 0}
    # The kinds of points keep their own rule: nothing was counted for them, so they stay unknown.
    assert all(report["teams"]["away"][field] == UNKNOWN_VALUE for field in TEAM_FIELDS[1:])


@pytest.mark.parametrize("rallies", [[OPEN, OPEN], [UNSURE, OPEN], [NOT_A_RALLY, OPEN], [{**OPEN, "proposed_winner": HOME}]])
def test_without_a_verified_rally_points_and_unattributed_are_null_for_both_sides(rallies):
    report = build_report(rallies)
    for side in ("home", "away"):
        assert report["teams"][side]["points"] == UNKNOWN_VALUE
    assert report["unattributed"] == {"home": None, "away": None}


def test_a_counted_zero_is_measured_once_every_rally_has_a_winner():
    teams = build_report([won(HOME), won(HOME), won(HOME)])["teams"]
    assert teams["home"]["points"] == measured(3)
    assert teams["away"]["points"] == measured(0)
    # A side without a point has no point of any kind either.
    assert all(teams["away"][field] == measured(0) for field in TEAM_FIELDS)


def test_an_unresolved_rally_keeps_points_partial():
    report = build_report([won(HOME), UNSURE])
    assert report["rallies"] == {"proposed": 2, "total": 2, "userCreated": 0, "verified": 1, "rejected": 0, "unresolved": 1, "open": 0}
    assert report["teams"]["home"]["points"] == partial(1)
    assert report["teams"]["away"]["points"] == partial(0)


def test_point_kinds_count_only_rallies_with_a_terminal_action_decision():
    rallies = [won(HOME, "ATTACK"), won(HOME, "ATTACK"), won(HOME), won(HOME), won(AWAY), won(AWAY), won(AWAY)]
    teams = build_report(rallies + [OPEN])["teams"]
    assert teams["home"] == {"points": partial(4), "attackPoints": partial(2), "blockPoints": UNKNOWN_VALUE,
                             "aces": UNKNOWN_VALUE, "opponentErrors": UNKNOWN_VALUE}
    assert teams["away"] == {"points": partial(3), **{field: UNKNOWN_VALUE for field in TEAM_FIELDS[1:]}}


def test_point_kinds_are_measured_when_every_point_of_the_side_has_its_action():
    rallies = [won(HOME, "ATTACK"), won(HOME, "BLOCK"), won(AWAY, "OPPONENT_ERROR"), won(AWAY, "ACE"), won(AWAY, "ACE")]
    teams = build_report(rallies)["teams"]
    assert teams["home"] == {"points": measured(2), "attackPoints": measured(1), "blockPoints": measured(1),
                             "aces": measured(0), "opponentErrors": measured(0)}
    assert teams["away"] == {"points": measured(3), "attackPoints": measured(0), "blockPoints": measured(0),
                             "aces": measured(2), "opponentErrors": measured(1)}


def test_one_missing_action_keeps_the_point_kinds_of_that_side_partial():
    teams = build_report([won(HOME, "ATTACK"), won(HOME), won(AWAY, "BLOCK")])["teams"]
    assert teams["home"] == {"points": measured(2), "attackPoints": partial(1), "blockPoints": UNKNOWN_VALUE,
                             "aces": UNKNOWN_VALUE, "opponentErrors": UNKNOWN_VALUE}
    # The other side is not held back by it.
    assert teams["away"]["blockPoints"] == measured(1) and teams["away"]["aces"] == measured(0)


def test_point_kinds_stay_partial_while_a_rally_has_no_winner():
    teams = build_report([won(HOME, "ACE"), won(AWAY, "ATTACK"), OPEN])["teams"]
    assert teams["home"]["aces"] == partial(1) and teams["home"]["attackPoints"] == UNKNOWN_VALUE
    assert teams["away"]["attackPoints"] == partial(1)


SCENARIOS = {
    "draft": [OPEN] * 4,
    "model proposals only": [{**OPEN, "proposed_winner": AWAY}] * 3,
    "one side verified": [won(HOME)] * 3 + [OPEN],
    "unresolved": [won(AWAY, "ACE", 4), UNSURE, won(HOME)],
    "all rejected": [NOT_A_RALLY] * 2,
    "complete without details": [won(HOME), won(AWAY)],
    "complete with details": [won(HOME, "ATTACK", 7), won(AWAY, "OPPONENT_ERROR"), won(HOME, "BLOCK")],
    "one side scoreless": [won(HOME, "ACE", 1)] * 3,
}


@pytest.mark.parametrize("name", SCENARIOS)
def test_unknown_is_always_null_and_a_number_is_always_counted(name):
    report = build_report(SCENARIOS[name])
    found = list(value_objects(report))
    assert len(found) == 10  # five fields for each side
    for item in found:
        if item["status"] == "UNKNOWN":
            assert item["value"] is None
        else:
            assert item["status"] in ("PARTIAL", "MEASURED") and isinstance(item["value"], int) and item["value"] >= 0
    any_verified = report["rallies"]["verified"] > 0
    for side in ("home", "away"):
        team = report["teams"][side]
        # Points are a number for both sides or for neither, and unattributed follows them.
        assert (team["points"]["value"] is not None) == any_verified
        assert (report["unattributed"][side] is None) == (team["points"]["value"] is None)
        assert (team["points"]["status"] == "MEASURED") == (any_verified and report["verificationStatus"] == "VERIFIED")
        for field in TEAM_FIELDS[1:]:
            # A lower bound of a kind of point is only shown when something was counted for it.
            assert team[field]["status"] != "PARTIAL" or team[field]["value"] > 0
    if report["score"]["status"] == "UNKNOWN":
        assert report["score"]["setsWon"] == {"home": None, "away": None} and report["score"]["sets"] == []
    else:
        assert all(isinstance(count, int) for count in report["score"]["setsWon"].values())


# --- players and unattributed points ---------------------------------------------------------

def test_players_hold_only_points_with_a_jersey_decision():
    rallies = [
        won(AWAY, "ATTACK", 9), won(HOME, "ATTACK", 7), won(HOME, "BLOCK", 7), won(HOME, "ACE", 12),
        won(HOME, "ATTACK", 7), won(AWAY, "ACE", 7),
        won(HOME, "ATTACK"),          # scorer card open, or answered UNKNOWN_PLAYER
        won(HOME, "OPPONENT_ERROR"),  # nobody on the winning side scored it
        won(AWAY),                    # no action decided
    ]
    report = build_report(rallies)
    # Home side first, then jersey number. The same number on both sides is two players.
    assert report["players"] == [
        {"side": "HOME", "jersey": 7, "points": 3, "attackPoints": 2, "blockPoints": 1, "aces": 0},
        {"side": "HOME", "jersey": 12, "points": 1, "attackPoints": 0, "blockPoints": 0, "aces": 1},
        {"side": "AWAY", "jersey": 7, "points": 1, "attackPoints": 0, "blockPoints": 0, "aces": 1},
        {"side": "AWAY", "jersey": 9, "points": 1, "attackPoints": 1, "blockPoints": 0, "aces": 0},
    ]
    assert report["unattributed"] == {"home": 2, "away": 1}
    # Unattributed points are part of the team total.
    for side in ("HOME", "AWAY"):
        attributed = sum(player["points"] for player in report["players"] if player["side"] == side)
        assert report["teams"][side.lower()]["points"]["value"] == attributed + report["unattributed"][side.lower()]


def test_without_scorer_decisions_every_point_is_unattributed():
    report = build_report([won(HOME, "ATTACK"), won(HOME), won(AWAY, "BLOCK"), OPEN])
    assert report["players"] == []
    assert report["unattributed"] == {"home": 2, "away": 1}


# --- coverage --------------------------------------------------------------------------------

def test_coverage_counts_eligible_observed_and_verified_rallies():
    rallies = [won(HOME, "ATTACK", 7), won(HOME, "ATTACK"), won(AWAY, "OPPONENT_ERROR"), won(AWAY),
               UNSURE, OPEN, {**OPEN, "proposed_winner": AWAY}, NOT_A_RALLY]
    assert build_report(rallies)["coverage"] == [
        # Seven rallies are not rejected. Four have a decision; one more has only a model proposal.
        {"metricId": "RALLY_WINNER", "eligibleN": 7, "observedN": 5, "verifiedN": 4, "status": "PARTIAL"},
        {"metricId": "TERMINAL_ACTION", "eligibleN": 7, "observedN": 3, "verifiedN": 3, "status": "PARTIAL"},
        # The opponent error has no scorer to find.
        {"metricId": "SCORER", "eligibleN": 6, "observedN": 1, "verifiedN": 1, "status": "PARTIAL"},
    ]


def test_coverage_is_measured_when_every_eligible_rally_is_verified():
    rallies = [won(HOME, "ATTACK", 7), won(AWAY, "OPPONENT_ERROR"), NOT_A_RALLY]
    assert build_report(rallies)["coverage"] == [
        {"metricId": "RALLY_WINNER", "eligibleN": 2, "observedN": 2, "verifiedN": 2, "status": "MEASURED"},
        {"metricId": "TERMINAL_ACTION", "eligibleN": 2, "observedN": 2, "verifiedN": 2, "status": "MEASURED"},
        {"metricId": "SCORER", "eligibleN": 1, "observedN": 1, "verifiedN": 1, "status": "MEASURED"},
    ]


# --- verification status ---------------------------------------------------------------------

@pytest.mark.parametrize("rallies, expected", [
    ([OPEN, OPEN], "DRAFT"),
    ([won(HOME), OPEN], "PARTIALLY_VERIFIED"),
    ([NOT_A_RALLY, OPEN], "PARTIALLY_VERIFIED"),
    ([UNSURE, OPEN], "PARTIALLY_VERIFIED"),
    # Every card is answered, but an unresolved rally still stands between the report and the truth.
    ([won(HOME), UNSURE], "PARTIALLY_VERIFIED"),
    ([won(HOME), won(AWAY)], "VERIFIED"),
    ([won(HOME), NOT_A_RALLY], "VERIFIED"),
])
def test_verification_status(rallies, expected):
    assert build_report(rallies)["verificationStatus"] == expected


# --- the example of the contract -------------------------------------------------------------

def test_the_example_of_the_contract_is_reproduced():
    rallies = ([won(HOME, "ATTACK", 7), won(AWAY), won(HOME, "ATTACK", 7), won(AWAY), won(HOME), won(AWAY), won(HOME)]
               + [NOT_A_RALLY] + [OPEN] * 10)
    report = build_report(rallies)
    assert report["verificationStatus"] == "PARTIALLY_VERIFIED"
    assert report["rallies"] == {"proposed": 18, "total": 18, "userCreated": 0, "verified": 7, "rejected": 1, "unresolved": 0, "open": 10}
    assert report["score"] == {"status": "PARTIAL", "setsWon": {"home": 0, "away": 0},
                               "sets": [{"number": 1, "home": 4, "away": 3, "complete": False}], "stoppedAtRally": 9}
    assert report["teams"]["home"] == {"points": partial(4), "attackPoints": partial(2), "blockPoints": UNKNOWN_VALUE,
                                       "aces": UNKNOWN_VALUE, "opponentErrors": UNKNOWN_VALUE}
    assert report["players"] == [{"side": "HOME", "jersey": 7, "points": 2, "attackPoints": 2, "blockPoints": 0, "aces": 0}]
    assert report["unattributed"] == {"home": 2, "away": 3}
    assert report["coverage"][0] == {"metricId": "RALLY_WINNER", "eligibleN": 17, "observedN": 7, "verifiedN": 7, "status": "PARTIAL"}
    assert set(report) == {"verificationStatus", "rallies", "ralliesMayBeMissing", "rules", "score", "teams", "players",
                           "unattributed", "coverage"}


def test_the_rally_counts_tell_proposals_from_rallies_the_user_made():
    made_by_user = {**won(AWAY), "user_created": True}
    counts = build_report([won(HOME), made_by_user, OPEN], proposed=7)["rallies"]
    # Seven were proposed, three are in the list now, and one of the three is the user's own.
    assert counts == {"proposed": 7, "total": 3, "userCreated": 1, "verified": 2, "rejected": 0, "unresolved": 0, "open": 1}


def test_nothing_is_measured_while_the_analyzer_may_have_missed_rallies():
    """Answering every card can not make a count exact when rallies may be missing from the list."""
    rallies = [won(HOME, "ATTACK", 7), won(AWAY, "OPPONENT_ERROR"), won(HOME, "BLOCK", 3)]
    exact = build_report(rallies)
    assert exact["ralliesMayBeMissing"] is False
    assert exact["score"]["status"] == "MEASURED"
    assert exact["teams"]["home"]["points"] == measured(2) and exact["teams"]["home"]["aces"] == measured(0)

    cautious = build_report(rallies, rallies_may_be_missing=True)
    assert cautious["ralliesMayBeMissing"] is True
    assert cautious["verificationStatus"] == "VERIFIED"
    assert cautious["score"] == {**exact["score"], "status": "PARTIAL"}
    assert cautious["teams"]["home"] == {"points": partial(2), "attackPoints": partial(1), "blockPoints": partial(1),
                                         # A lower bound of zero says nothing and is not printed as a number.
                                         "aces": UNKNOWN_VALUE, "opponentErrors": UNKNOWN_VALUE}
    assert cautious["teams"]["away"]["points"] == partial(1)
    # What the user decided is unchanged: only the claim of completeness is withdrawn.
    for key in ("rallies", "players", "unattributed", "coverage"):
        assert cautious[key] == exact[key]


# --- the function itself ---------------------------------------------------------------------

def test_the_report_is_a_pure_function_of_the_records():
    rallies = [won(HOME, "ATTACK", 7), UNSURE, NOT_A_RALLY, OPEN, won(AWAY)]
    before = copy.deepcopy(rallies)
    assert build_report(rallies) == build_report(copy.deepcopy(rallies))
    assert rallies == before
    # proposed_winner is optional: records without it are read as "no proposal".
    without = [{key: value for key, value in rally.items() if key != "proposed_winner"} for rally in rallies]
    assert build_report(without) == build_report(rallies)
    # sequence is optional too: the position in the list stands in for it.
    numbered = [{**rally, "sequence": position} for position, rally in enumerate(rallies, start=1)]
    assert build_report(numbered) == build_report(rallies)


@pytest.mark.parametrize("record", [
    {**OPEN, "status": "VERIFIED"},              # verified without a winner
    {**OPEN, "winner": HOME},                    # a winner without a decision
    {**OPEN, "status": "REJECTED", "winner": AWAY},
    {**OPEN, "status": "DONE"},
    {**won(HOME), "winner": "DRAW"},
])
def test_an_inconsistent_record_is_refused_instead_of_counted(record):
    with pytest.raises(ValueError):
        build_report([won(HOME), record])


# --- the route -------------------------------------------------------------------------------

def test_report_route_answers_404_until_a_job_has_rallies(storage, make_clip):
    with serve(storage, ScriptedAnalyzer(result=[])) as client:
        unknown = client.get("/v1/matches/unknown/report")
        assert unknown.status_code == 404 and unknown.json()["error"]["code"] == "MATCH_NOT_FOUND"
        empty_match = client.get(f"/v1/matches/{create_match(client)}/report")
        assert empty_match.status_code == 404 and empty_match.json()["error"]["code"] == "REPORT_NOT_AVAILABLE"
        video = ready_video(client, make_clip)
        report_url = f"/v1/matches/{video['matchId']}/report"
        # An uploaded video alone is no report, and neither is a job that waits or failed.
        assert client.get(report_url).json()["error"]["code"] == "REPORT_NOT_AVAILABLE"
        job = request_job(client, video["id"])
        assert client.get(report_url).json()["error"]["code"] == "REPORT_NOT_AVAILABLE"
        run_pending(client)
        assert client.get(f"/v1/analyses/{job['id']}").json()["state"] == "FAILED"
        waiting = client.get(report_url)
        assert waiting.status_code == 404 and waiting.json()["error"]["code"] == "REPORT_NOT_AVAILABLE"


def test_report_route_names_its_job_and_follows_the_decisions(storage, make_clip):
    with serve(storage, ScriptedAnalyzer()) as client:
        video, job = reviewed_job(client, make_clip)
        report_url = f"/v1/matches/{video['matchId']}/report"
        draft = client.get(report_url)
        assert draft.status_code == 200
        draft = draft.json()
        assert (draft["matchId"], draft["jobId"], draft["revision"]) == (video["matchId"], job["id"], 0)
        assert draft["analyzer"] == {"id": "scripted", "version": "t1", "kind": "BASELINE"} and draft["mock"] is False
        assert set(draft) == {"matchId", "jobId", "revision", "analyzer", "mock", "rallyList", "verificationStatus", "rallies",
                              "ralliesMayBeMissing", "rules", "score", "teams", "players", "unattributed", "coverage"}
        # Nobody has confirmed the rally list, so the report says that rallies may be missing.
        assert draft["ralliesMayBeMissing"] is True and draft["rallyList"] == {"confirmed": False, "confirmedAt": None}
        assert draft["verificationStatus"] == "DRAFT" and draft["score"]["status"] == "UNKNOWN"

        first = decide(client, card_of(client, job["id"], 1), "HOME")
        action = decide(client, card_of(client, job["id"], 1, "TERMINAL_ACTION"), "BLOCK")
        scorer = decide(client, card_of(client, job["id"], 1, "SCORER"), "JERSEY:11")
        decide(client, card_of(client, job["id"], 2), "NOT_A_RALLY")
        assert (first["reportRevision"], action["reportRevision"], scorer["reportRevision"]) == (1, 2, 3)

        report = client.get(report_url).json()
        assert report["revision"] == 4
        computed = build_report([won("HOME", "BLOCK", 11), NOT_A_RALLY, OPEN, OPEN], rallies_may_be_missing=True)
        assert {key: report[key] for key in computed} == computed
        assert report["players"] == [{"side": "HOME", "jersey": 11, "points": 1, "attackPoints": 0, "blockPoints": 1, "aces": 0}]
        assert report["teams"]["home"]["points"] == partial(1) and report["teams"]["away"]["points"] == partial(0)
        # Rally 2 was rejected and is skipped; rally 3 is the first one without a winner.
        assert report["score"] == {"status": "PARTIAL", "setsWon": {"home": 0, "away": 0},
                                   "sets": [set_line(1, 1, 0, complete=False)], "stoppedAtRally": 3}


def test_a_model_proposal_reaches_the_report_only_as_observed(storage, make_clip):
    proposing = ScriptedAnalyzer(
        result=[RallyProposal(100, 400, proposed_winner="HOME", confidence=0.75), RallyProposal(500, 900)],
        info=AnalyzerInfo("scripted", "t1", MODEL))
    with serve(storage, proposing) as client:
        video, job = reviewed_job(client, make_clip)
        report = client.get(f"/v1/matches/{video['matchId']}/report").json()
        assert report["verificationStatus"] == "DRAFT"
        # Not even a measured model is trusted to have found every rally: only the user's confirmation closes the list.
        assert report["ralliesMayBeMissing"] is True
        assert report["score"] == {"status": "UNKNOWN", "setsWon": {"home": None, "away": None}, "sets": [], "stoppedAtRally": 1}
        assert report["teams"]["home"]["points"] == UNKNOWN_VALUE
        assert report["coverage"][0] == {"metricId": "RALLY_WINNER", "eligibleN": 2, "observedN": 1, "verifiedN": 0, "status": "UNKNOWN"}


def test_a_newer_job_without_rallies_does_not_hide_the_reviewed_one(storage, make_clip):
    analyzer = ScriptedAnalyzer()
    with serve(storage, analyzer) as client:
        video, job = reviewed_job(client, make_clip)
        decide(client, card_of(client, job["id"], 1), "AWAY")
        report_url = f"/v1/matches/{video['matchId']}/report"
        analyzer.result = []
        failed = request_job(client, video["id"])
        assert client.get(report_url).json()["jobId"] == job["id"]
        run_pending(client)
        assert client.get(f"/v1/analyses/{failed['id']}").json()["state"] == "FAILED"
        report = client.get(report_url).json()
        assert report["jobId"] == job["id"] and report["rallies"]["verified"] == 1

        # A newer job that did produce rallies replaces it: the report always describes one job.
        analyzer.result = [RallyProposal(100, 900)]
        newer = request_job(client, video["id"])
        run_pending(client)
        report = client.get(report_url).json()
        assert report["jobId"] == newer["id"] and report["rallies"] == {"proposed": 1, "total": 1, "userCreated": 0, "verified": 0, "rejected": 0, "unresolved": 0, "open": 1}


def test_a_match_whose_only_job_was_cancelled_has_no_report(storage, make_clip):
    with serve(storage, ScriptedAnalyzer()) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        assert client.post(f"/v1/analyses/{job['id']}/cancel").json()["state"] == "CANCELLED"
        missing = client.get(f"/v1/matches/{video['matchId']}/report")
        assert missing.status_code == 404 and missing.json()["error"]["code"] == "REPORT_NOT_AVAILABLE"


def test_the_revision_belongs_to_the_job_the_report_follows(storage, make_clip):
    with serve(storage, ScriptedAnalyzer()) as client:
        video, job = reviewed_job(client, make_clip)
        report_url = f"/v1/matches/{video['matchId']}/report"
        for sequence in (1, 2, 3):
            decide(client, card_of(client, job["id"], sequence), "HOME")
        assert (client.get(report_url).json()["jobId"], client.get(report_url).json()["revision"]) == (job["id"], 3)

        newer = request_job(client, video["id"])
        run_pending(client)
        followed = client.get(report_url).json()
        # The newer job starts its own count, so the revision of the report can go down.
        assert (followed["jobId"], followed["revision"], followed["verificationStatus"]) == (newer["id"], 0, "DRAFT")

        # A decision on the earlier job is still stored. Its answer names that job, not the one the report follows.
        late = decide(client, card_of(client, job["id"], 4), "AWAY")
        assert (late["job"]["id"], late["reportRevision"]) == (job["id"], 4)
        assert client.get(report_url).json() == followed
