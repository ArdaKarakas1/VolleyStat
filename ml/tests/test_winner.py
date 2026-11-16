"""Tests of the rally winner measurement. Every rally list here is made up: no winner
component exists yet, so these tests prove the arithmetic and nothing about a model."""
import numpy as np
import pytest

from volleystat_ml import metrics, winner
from volleystat_ml.agreement import Rally
from volleystat_ml.winner import Proposal


def at(index):
    """The interval of the rally with this number: rallies are 5 s long and 10 s apart."""
    return index * 10_000, index * 10_000 + 5_000


def gold(index, side="HOME", observation="OBSERVED", status="DOUBLE_AGREED", review=None, evidence=None):
    start, end = at(index)
    return Rally(start, end, side, None, observation, ground_truth_status=status, review_status=review,
                 winner_evidence=evidence)


def prop(index, side="HOME", confidence=None, shift=0):
    start, end = at(index)
    return Proposal(start + shift, end + shift, side, confidence)


def one_video(gold_rallies, proposals, tolerance_ms=1000):
    return winner.pool({"V1": winner.evaluate_video(gold_rallies, proposals, tolerance_ms)})


def kinds(evaluation):
    return [outcome.kind for outcome in evaluation.outcomes]


# ----- which gold rallies are eligible -----

@pytest.mark.parametrize("rally, reason", [
    (gold(0), None),
    (gold(0, observation="INFERRED"), None),
    (gold(0, observation=None, status=None), None),
    (gold(0, observation="NOT_ANNOTATED"), "NOT_ANNOTATED"),
    (gold(0, side=""), "NOT_ANNOTATED"),
    (gold(0, status="NEEDS_MORE_EVIDENCE"), "NEEDS_MORE_EVIDENCE"),
    (gold(0, status="UNRESOLVABLE"), "UNRESOLVABLE"),
    (gold(0, review="DISPUTED"), "REVIEW_DISPUTED"),
    (gold(0, review="UNRESOLVABLE"), "REVIEW_UNRESOLVABLE"),
    (gold(0, side="UNKNOWN", observation="UNKNOWN"), "WINNER_UNKNOWN"),
    (gold(0, observation="UNKNOWN"), "OBSERVATION_UNKNOWN"),
    (gold(0, observation="NOT_APPLICABLE"), "OBSERVATION_NOT_APPLICABLE"),
    # The first reason that applies is the one reported.
    (gold(0, side="UNKNOWN", observation="NOT_ANNOTATED", status="UNRESOLVABLE"), "NOT_ANNOTATED"),
    (gold(0, side="UNKNOWN", observation="UNKNOWN", status="UNRESOLVABLE"), "UNRESOLVABLE"),
    (gold(0, side="UNKNOWN", observation="UNKNOWN", review="DISPUTED"), "REVIEW_DISPUTED"),
])
def test_a_gold_rally_is_eligible_or_excluded_with_the_first_reason_that_applies(rally, reason):
    assert winner.exclusion(rally) == reason


@pytest.mark.parametrize("status, layer", [
    ("DOUBLE_AGREED", "GOLD"), ("ADJUDICATED", "GOLD"), ("SINGLE_HUMAN", "SINGLE"), (None, "SINGLE"), ("", "SINGLE"),
])
def test_only_double_agreed_and_adjudicated_labels_are_in_the_gold_layer(status, layer):
    assert winner.layer_of(gold(0, status=status)) == layer


# ----- one outcome per eligible gold rally -----

def test_every_eligible_rally_gets_exactly_one_outcome():
    video = winner.evaluate_video(
        [gold(0, "HOME"), gold(1, "AWAY"), gold(2, "HOME"), gold(3, "AWAY")],
        [prop(0, "HOME"), prop(1, "HOME"), prop(2, "UNKNOWN")],
    )
    assert kinds(video) == ["CORRECT", "WRONG", "UNKNOWN", "NOT_PROPOSED"]
    assert [outcome.layer for outcome in video.outcomes] == ["GOLD"] * 4
    assert (video.gold_rallies, video.predicted_rallies, video.automatic_proposals) == (4, 3, 2)

    layer = winner.pool({"V1": video}).layers["GOLD"]
    assert (layer.correct, layer.wrong, layer.unknown, layer.not_proposed) == (1, 1, 1, 1)
    assert (layer.eligible, layer.auto) == (4, 2)


def test_a_proposal_pairs_with_a_gold_rally_only_within_the_tolerance_on_both_ends():
    inside = winner.evaluate_video([gold(0)], [prop(0, shift=1000)])
    assert kinds(inside) == ["CORRECT"]
    outside = winner.evaluate_video([gold(0)], [prop(0, shift=1001)])
    assert kinds(outside) == ["NOT_PROPOSED", "AUTO_UNPAIRED"]
    wider = winner.evaluate_video([gold(0)], [prop(0, shift=1001)], tolerance_ms=1001)
    assert kinds(wider) == ["CORRECT"]


def test_an_automatic_decision_on_a_rally_without_a_definite_winner_is_counted_and_not_scored():
    evaluation = one_video(
        [gold(0, "UNKNOWN", observation="UNKNOWN"), gold(1, status="UNRESOLVABLE"), gold(2, "AWAY")],
        [prop(0, "HOME"), prop(1, "HOME"), prop(2, "AWAY")],
    )
    assert evaluation.excluded == {"WINNER_UNKNOWN": 1, "UNRESOLVABLE": 1}
    assert evaluation.not_scored_ineligible == {"WINNER_UNKNOWN": 1, "UNRESOLVABLE": 1}
    layer = evaluation.layers["GOLD"]
    # Only the rally with a definite winner is in a ratio.
    assert (layer.eligible, layer.auto, layer.correct) == (1, 1, 1)


def test_an_abstention_on_a_rally_without_a_definite_winner_leaves_no_trace_in_the_numbers():
    evaluation = one_video([gold(0, "UNKNOWN", observation="UNKNOWN")], [prop(0, "UNKNOWN")])
    assert evaluation.excluded == {"WINNER_UNKNOWN": 1}
    assert evaluation.not_scored_ineligible == {}
    assert evaluation.layers["GOLD"].eligible == 0


def test_an_unpaired_automatic_decision_is_told_apart_by_whether_the_gold_has_a_rally_there():
    video = winner.evaluate_video(
        [gold(0, "HOME")],
        # The first starts 3 s late: it overlaps the gold rally and is outside the tolerance.
        # The second lies where the gold has no rally at all. The third abstains.
        [prop(0, "HOME", shift=3000), prop(4, "AWAY"), prop(6, "UNKNOWN")],
    )
    assert [(outcome.kind, outcome.reason) for outcome in video.outcomes] == [
        ("NOT_PROPOSED", None), ("AUTO_UNPAIRED", "OVERLAPS_GOLD"), ("AUTO_UNPAIRED", "NO_GOLD_THERE"),
    ]
    evaluation = winner.pool({"V1": video})
    assert evaluation.unpaired == {"SECOND_PROPOSAL": 0, "OVERLAPS_GOLD": 1, "NO_GOLD_THERE": 1}
    layer = evaluation.layers["GOLD"]
    # Neither is a wrong decision: the gold rally they miss is counted as not proposed.
    assert (layer.eligible, layer.auto, layer.wrong, layer.not_proposed) == (1, 0, 0, 1)


def test_a_second_proposal_for_a_paired_rally_is_not_called_a_boundary_miss():
    # Both lie within the tolerance of the gold rally; the closer one pairs with it.
    video = winner.evaluate_video([gold(0, "HOME")], [prop(0, "HOME", 0.6), prop(0, "HOME", 0.95, shift=300)])
    assert [(outcome.kind, outcome.reason) for outcome in video.outcomes] == [
        ("CORRECT", None), ("AUTO_UNPAIRED", "SECOND_PROPOSAL")]
    assert winner.pool({"V1": video}).unpaired == {"SECOND_PROPOSAL": 1, "OVERLAPS_GOLD": 0, "NO_GOLD_THERE": 0}


@pytest.mark.parametrize("sides", [("HOME", "AWAY"), ("AWAY", "HOME"), ("HOME", "UNKNOWN"), ("UNKNOWN", "AWAY")])
def test_proposals_that_name_different_winners_for_one_rally_are_refused_in_either_order(sides):
    # The pairing would keep whichever comes first, so the score would depend on the order of the file.
    with pytest.raises(winner.ContradictingProposals, match=r"0-5000 ms .*AWAY|0-5000 ms .*HOME"):
        winner.evaluate_video([gold(0, "HOME")], [prop(0, sides[0], 0.6), prop(0, sides[1], 0.95, shift=200)])
    # Two abstentions on one rally decide nothing, so there is nothing to contradict.
    assert kinds(winner.evaluate_video([gold(0)], [prop(0, "UNKNOWN"), prop(0, "UNKNOWN", shift=200)])) == ["UNKNOWN"]


def scored(video):
    return [(outcome.kind, outcome.reason, outcome.gold and outcome.gold.start_ms, outcome.proposal and outcome.proposal.confidence)
            for outcome in video.outcomes]


def test_a_tie_is_broken_by_the_contents_of_the_file_and_never_by_its_row_order():
    # Same side, same interval, two confidences: the more confident one is scored, so a
    # confident wrong decision can not hide behind a row order.
    rows = [prop(0, "HOME", 0.95), prop(0, "HOME", 0.5)]
    for order in (rows, rows[::-1]):
        video = winner.evaluate_video([gold(0, "AWAY")], order)
        assert scored(video) == [("WRONG", None, 0, 0.95), ("AUTO_UNPAIRED", "SECOND_PROPOSAL", None, 0.5)]
        assert winner.pool({"V1": video}).layers["GOLD"].calibration.high_confidence(0.9) == (1, 1)
    # One proposal between two gold rallies, equally far from both: both pair, and which
    # rally each one takes follows the contents too.
    short = [Rally(0, 800, "HOME", None, "OBSERVED", ground_truth_status="DOUBLE_AGREED"),
             Rally(900, 1700, "AWAY", None, "OBSERVED", ground_truth_status="DOUBLE_AGREED")]
    between = [Proposal(450, 1250, "HOME", 0.95), Proposal(450, 1250, "HOME", 0.5)]
    first, second = (winner.evaluate_video(short, order) for order in (between, between[::-1]))
    assert scored(first) == scored(second) == [("CORRECT", None, 0, 0.95), ("WRONG", None, 900, 0.5)]
    # Two abstentions say the same whatever confidence they carry: an abstention is never calibrated.
    assert kinds(winner.evaluate_video([gold(0)], [prop(0, "UNKNOWN", 0.3), prop(0, "UNKNOWN", 0.7)])) == ["UNKNOWN"]
    # Equal cost with different intervals: still the more confident one, whichever starts first.
    rows = [Proposal(9700, 15000, "HOME", 0.5), Proposal(10000, 14700, "HOME", 0.95)]
    for order in (rows, rows[::-1]):
        video = winner.evaluate_video([gold(1, "AWAY")], order)
        # The unscored one starts 300 ms earlier, so it comes first in time.
        assert scored(video) == [("AUTO_UNPAIRED", "SECOND_PROPOSAL", None, 0.5), ("WRONG", None, 10_000, 0.95)]
    # A confidence of zero is still a confidence, and a proposal without one comes last.
    for rows in ([prop(0, "HOME", 0.0), prop(0, "HOME")], [prop(0, "HOME", 0.99), prop(0, "HOME")]):
        for order in (rows, rows[::-1]):
            video = winner.evaluate_video([gold(0, "AWAY")], order)
            assert scored(video) == [("WRONG", None, 0, rows[0].confidence), ("AUTO_UNPAIRED", "SECOND_PROPOSAL", None, None)]
    assert winner.pool({"V1": winner.evaluate_video([gold(0, "AWAY")], [prop(0, "HOME"), prop(0, "HOME", 0.99)])}
                       ).layers["GOLD"].calibration.high_confidence(0.9) == (1, 1)


def test_shuffling_the_rows_of_a_prediction_file_changes_no_number():
    import random
    generator = random.Random(11)
    checked = refused = 0
    for _ in range(300):
        rallies = [gold(index, generator.choice(["HOME", "AWAY"])) for index in range(6)]
        proposals = [Proposal(at(index)[0] + generator.choice([-900, -300, 0, 0, 400, 1500]),
                              at(index)[1] + generator.choice([-600, 0, 0, 200, 1200]),
                              generator.choice(["HOME", "HOME", "AWAY", "UNKNOWN"]),
                              generator.choice([None, 0.5, 0.9, 0.95]))
                     for index in [generator.randrange(7) for _ in range(9)]]
        results = []
        for _ in range(4):
            generator.shuffle(proposals)
            try:
                results.append(winner.pool({"V1": winner.evaluate_video(rallies, list(proposals))}).to_dict(resamples=50))
            except winner.ContradictingProposals as error:
                results.append(str(error))
        assert all(result == results[0] for result in results)
        refused += isinstance(results[0], str)
        checked += 1
    # Both kinds of case were drawn.
    assert 30 < refused < checked - 30


def test_proposals_equally_near_two_rallies_that_name_different_winners_are_refused_in_either_order():
    # Both proposals sit halfway between the two rallies, so each could pair with either.
    golds = [gold(0, "HOME"), gold(2, "AWAY")]
    halfway = [prop(1, "HOME", 0.9), prop(1, "AWAY", 0.9)]
    for order in (halfway, halfway[::-1]):
        with pytest.raises(winner.ContradictingProposals, match="AWAY, HOME"):
            winner.evaluate_video(golds, order, tolerance_ms=30_000)


def short(start, end, side):
    return Rally(start, end, side, None, "OBSERVED", ground_truth_status="DOUBLE_AGREED")


def test_a_proposal_the_pairing_scores_on_a_rally_speaks_for_that_rally():
    # P1 is nearer to the HOME rally, but the pairing scores it on the AWAY rally, whose own
    # nearest proposal says AWAY: on that rally the component said two things.
    golds = [short(1000, 1800, "AWAY"), short(1900, 2700, "HOME")]
    rows = [Proposal(100, 1100, "AWAY", 0.9), Proposal(1500, 2500, "HOME", 0.9), Proposal(1900, 2700, "HOME", 0.9)]
    for order in (rows, rows[::-1]):
        with pytest.raises(winner.ContradictingProposals, match="gold rally 1000-1800 ms"):
            winner.evaluate_video(golds, order)


@pytest.mark.parametrize("golds, rows, expected", [
    # The cost is the sum of both errors: the start alone would put the third proposal on the first rally.
    ([short(0, 800, "HOME"), short(900, 1700, "AWAY")],
     [Proposal(0, 800, "HOME"), Proposal(900, 1700, "AWAY"), Proposal(400, 1650, "AWAY")],
     [("CORRECT", None), ("AUTO_UNPAIRED", "SECOND_PROPOSAL"), ("CORRECT", None)]),
    # The maximum of the errors, or the end alone, would put it on the second rally.
    ([short(0, 400, "HOME"), short(500, 1800, "AWAY")],
     [Proposal(0, 400, "HOME"), Proposal(500, 1800, "AWAY"), Proposal(0, 1300, "HOME")],
     [("CORRECT", None), ("AUTO_UNPAIRED", "SECOND_PROPOSAL"), ("CORRECT", None)]),
    # An equal cost to a rally out of reach does not count.
    ([short(0, 1000, "HOME"), short(1901, 2699, "AWAY")], [Proposal(900, 1900, "HOME"), Proposal(1901, 2699, "AWAY")],
     [("CORRECT", None), ("CORRECT", None)]),
    # One millisecond decides which rally is nearest.
    ([short(0, 1000, "HOME"), short(1000, 2001, "AWAY")], [Proposal(500, 1500, "HOME"), Proposal(1000, 2001, "AWAY")],
     [("CORRECT", None), ("CORRECT", None)]),
])
def test_each_proposal_belongs_to_the_rally_with_the_smallest_sum_of_errors(golds, rows, expected):
    video = winner.evaluate_video(golds, rows)
    assert [(outcome.kind, outcome.reason) for outcome in video.outcomes] == expected


def test_a_contradiction_on_a_later_rally_of_the_video_is_refused_too():
    with pytest.raises(winner.ContradictingProposals, match="gold rally 10000-15000 ms"):
        winner.evaluate_video([gold(0, "HOME"), gold(1, "HOME")], [prop(0, "HOME"), prop(1, "HOME"), prop(1, "AWAY")])


def test_a_second_proposal_nearest_to_its_own_rally_contradicts_no_neighbour():
    # At a loose tolerance the extra AWAY lies within reach of the HOME rally too, but it is
    # nearest to the AWAY rally, where it repeats the paired decision.
    video = winner.evaluate_video([gold(0, "HOME"), gold(1, "AWAY"), gold(2, "AWAY")],
                                  [prop(0, "HOME"), prop(1, "AWAY"), prop(2, "AWAY"), prop(1, "AWAY", 0.8)],
                                  tolerance_ms=30_000)
    assert [(outcome.kind, outcome.reason) for outcome in video.outcomes] == [
        ("CORRECT", None), ("CORRECT", None), ("AUTO_UNPAIRED", "SECOND_PROPOSAL"), ("CORRECT", None)]


def test_neighbouring_rallies_within_the_tolerance_of_each_other_are_scored_and_not_refused():
    # Each proposal pairs with its own rally, so nothing is left over to contradict anything.
    short = [Rally(0, 800, "HOME", None, "OBSERVED", ground_truth_status="DOUBLE_AGREED"),
             Rally(900, 1700, "AWAY", None, "OBSERVED", ground_truth_status="DOUBLE_AGREED")]
    video = winner.evaluate_video(short, [Proposal(0, 800, "HOME", 0.9), Proposal(900, 1700, "AWAY", 0.9)])
    assert kinds(video) == ["CORRECT", "CORRECT"]
    # A loose pilot tolerance puts every rally within reach of its neighbours.
    loose = winner.evaluate_video([gold(0, "HOME"), gold(1, "AWAY"), gold(2, "AWAY")],
                                  [prop(0, "HOME"), prop(1, "AWAY"), prop(2, "UNKNOWN")], tolerance_ms=30_000)
    assert kinds(loose) == ["CORRECT", "CORRECT", "UNKNOWN"]


def test_only_proposals_within_the_tolerance_on_both_ends_can_contradict_each_other():
    # Exactly at the tolerance on both ends: a candidate for the rally, so the sides contradict.
    with pytest.raises(winner.ContradictingProposals):
        winner.evaluate_video([gold(0, "HOME")], [prop(0, "HOME"), prop(0, "AWAY", shift=1000)])
    # The same start but an end 4 s off: no candidate for the rally, so nothing contradicts.
    video = winner.evaluate_video([gold(0, "HOME")], [prop(0, "HOME"), Proposal(0, 9000, "AWAY")])
    assert [(outcome.kind, outcome.reason) for outcome in video.outcomes] == [
        ("CORRECT", None), ("AUTO_UNPAIRED", "OVERLAPS_GOLD")]
    # A same-side proposal exactly at the tolerance is a second proposal, not a boundary miss.
    twice = winner.evaluate_video([gold(0, "HOME")], [prop(0, "HOME"), prop(0, "HOME", shift=1000)])
    assert [(outcome.kind, outcome.reason) for outcome in twice.outcomes] == [
        ("CORRECT", None), ("AUTO_UNPAIRED", "SECOND_PROPOSAL")]


def test_a_proposal_that_only_touches_a_gold_rally_does_not_overlap_it():
    video = winner.evaluate_video([gold(0, "HOME")], [prop(0, "HOME", shift=5000)])
    assert [(outcome.kind, outcome.reason) for outcome in video.outcomes] == [
        ("NOT_PROPOSED", None), ("AUTO_UNPAIRED", "NO_GOLD_THERE")]


def test_at_one_moment_the_gold_rally_comes_before_the_unpaired_decision():
    video = winner.evaluate_video([gold(0, "HOME")], [Proposal(0, 9000, "HOME")])
    assert kinds(video) == ["NOT_PROPOSED", "AUTO_UNPAIRED"]


def test_outcomes_are_listed_in_time_order():
    video = winner.evaluate_video(
        [gold(1, "HOME"), gold(3, "AWAY")], [prop(0, "HOME"), prop(1, "AWAY"), prop(2, "HOME"), prop(3, "AWAY")])
    assert kinds(video) == ["AUTO_UNPAIRED", "WRONG", "AUTO_UNPAIRED", "CORRECT"]


def test_a_proposal_must_name_a_side_or_unknown_and_a_confidence_from_zero_to_one():
    for side in ("home", "", "DRAW", None):
        with pytest.raises(ValueError, match="proposed_winner"):
            Proposal(0, 1000, side)
    for confidence in (-0.01, 1.01, float("nan"), float("inf"), True, "0.9", 10 ** 400, -(10 ** 400)):
        with pytest.raises(ValueError, match="confidence"):
            Proposal(0, 1000, "HOME", confidence)
    assert Proposal(0, 1000, "HOME", 0).confidence == 0
    assert Proposal(0, 1000, "HOME", 1).automatic is True
    assert Proposal(0, 1000, "UNKNOWN", 0.4).automatic is False


def test_a_gold_winner_that_is_no_schema_value_is_an_error_and_not_an_exclusion():
    with pytest.raises(ValueError, match="winning_side"):
        winner.exclusion(gold(0, side="home"))


def test_the_failure_list_has_a_row_for_everything_except_a_correct_decision():
    first = winner.evaluate_video(
        [gold(0, "HOME"), gold(1, "AWAY", status="SINGLE_HUMAN"), gold(2, "HOME"), gold(3, "UNKNOWN", observation="UNKNOWN"),
         gold(4, "AWAY")],
        [prop(0, "HOME", 0.9), prop(1, "HOME", 0.75), prop(2, "UNKNOWN"), prop(3, "AWAY"), prop(6, "HOME", 0.5)],
    )
    second = winner.evaluate_video([gold(0, "HOME")], [prop(0, "AWAY")])
    rows = winner.failure_rows({"V2": second, "V1": first})
    assert [tuple(row) for row in rows] == [winner.FAILURE_COLUMNS] * 6
    assert [(row["video_id"], row["kind"], row["layer"], row["reason"]) for row in rows] == [
        ("V1", "WRONG", "SINGLE", None),
        ("V1", "UNKNOWN", "GOLD", None),
        ("V1", "AUTO_ON_INELIGIBLE_GOLD", None, "WINNER_UNKNOWN"),
        ("V1", "NOT_PROPOSED", "GOLD", None),
        ("V1", "AUTO_UNPAIRED", None, "NO_GOLD_THERE"),
        ("V2", "WRONG", "GOLD", None),
    ]
    wrong, unknown, ineligible, missing, unpaired, _ = rows
    assert (wrong["gold_start_ms"], wrong["gold_end_ms"], wrong["pred_start_ms"], wrong["pred_end_ms"]) == (10_000, 15_000, 10_000, 15_000)
    assert (wrong["gold_winner"], wrong["proposed_winner"], wrong["confidence"]) == ("AWAY", "HOME", 0.75)
    assert (unknown["proposed_winner"], unknown["confidence"]) == ("UNKNOWN", None)
    assert (ineligible["gold_winner"], ineligible["proposed_winner"]) == ("UNKNOWN", "AWAY")
    assert (missing["pred_start_ms"], missing["proposed_winner"]) == (None, None)
    assert (unpaired["gold_start_ms"], unpaired["gold_winner"], unpaired["pred_start_ms"]) == (None, None, 60_000)


def test_unscored_decisions_with_a_high_confidence_are_counted_on_their_own():
    evaluation = one_video(
        [gold(0, "UNKNOWN", observation="UNKNOWN"), gold(1)],
        [prop(0, "HOME", 0.97), prop(1, "AWAY", 0.99), prop(5, "HOME", 0.92), prop(6, "HOME", 0.4), prop(7, "AWAY")],
    )
    # The wrong decision on the eligible rally is the layer's own count, not this one.
    assert evaluation.unscored_high_confidence(0.9) == 2
    assert evaluation.unscored_high_confidence(0.92) == 2
    assert evaluation.unscored_high_confidence(0.93) == 1
    assert evaluation.layers["GOLD"].calibration.high_confidence(0.9) == (1, 1)
    # No unscored decision with a confidence: unknown, not zero.
    assert one_video([gold(0)], [prop(0), prop(5, "HOME")]).unscored_high_confidence(0.9) is None


# ----- the main numbers -----

def forty_eligible():
    """27 correct, 3 wrong, 6 abstentions, 4 rallies the predictions do not contain."""
    rallies = [gold(index, "HOME" if index % 2 else "AWAY") for index in range(40)]
    proposals = (
        [prop(index, rallies[index].winning_side) for index in range(27)]
        + [prop(index, "HOME" if rallies[index].winning_side == "AWAY" else "AWAY") for index in range(27, 30)]
        + [prop(index, "UNKNOWN") for index in range(30, 36)]
    )
    return rallies, proposals


def test_precision_and_coverage_follow_the_definitions_of_the_handover_document():
    layer = one_video(*forty_eligible()).layers["GOLD"]
    assert (layer.eligible, layer.auto, layer.correct, layer.wrong, layer.unknown, layer.not_proposed) == (40, 30, 27, 3, 6, 4)
    assert (layer.precision.numerator, layer.precision.denominator, layer.precision.value) == (27, 30, 0.9)
    assert (layer.coverage.numerator, layer.coverage.denominator, layer.coverage.value) == (30, 40, 0.75)
    assert (layer.unknown_rate.numerator, layer.unknown_rate.denominator) == (6, 40)
    assert (layer.not_proposed_rate.numerator, layer.not_proposed_rate.denominator) == (4, 40)
    # Every eligible rally is in exactly one of the three shares.
    assert layer.coverage.value + layer.unknown_rate.value + layer.not_proposed_rate.value == pytest.approx(1)


def test_abstaining_more_raises_precision_and_lowers_coverage_by_the_same_rallies():
    rallies, proposals = forty_eligible()
    # The component now abstains on the three rallies it got wrong.
    cautious = [proposal if index not in (27, 28, 29) else prop(index, "UNKNOWN") for index, proposal in enumerate(proposals)]
    layer = one_video(rallies, cautious).layers["GOLD"]
    assert (layer.precision.numerator, layer.precision.denominator) == (27, 27)
    assert (layer.coverage.numerator, layer.coverage.denominator) == (27, 40)


def test_a_ratio_is_quoted_from_thirty_in_its_own_denominator_on():
    below = winner.Ratio(29, 29)
    assert below.reliable is False
    assert below.to_dict()["value"] is None
    # The counts and the interval stay: "29/29" with an interval is what the record may quote.
    assert (below.to_dict()["numerator"], below.to_dict()["denominator"]) == (29, 29)
    assert below.to_dict()["interval"]["low"] == pytest.approx(0.8831, abs=1e-4)

    enough = winner.Ratio(27, 30)
    assert enough.reliable is True
    assert enough.to_dict()["value"] == 0.9

    # Precision is quoted by the automatic decisions, not by the gold rallies: 12 of 120 is too few.
    rallies = [gold(index) for index in range(120)]
    layer = one_video(rallies, [prop(index) for index in range(12)]).layers["GOLD"]
    assert layer.precision.to_dict()["value"] is None
    assert layer.coverage.to_dict()["value"] == 0.1


def test_a_ratio_without_a_denominator_has_no_value_and_no_interval():
    ratio = winner.Ratio(0, 0)
    assert ratio.value is None
    assert ratio.interval is None
    assert ratio.to_dict() == {"value": None, "numerator": 0, "denominator": 0, "reliable": False, "interval": None}


@pytest.mark.parametrize("successes, total, low, high", [
    (20, 20, 0.8389, 1.0), (29, 30, 0.8333, 0.9941), (18, 20, 0.6990, 0.9721), (0, 5, 0.0, 0.4345),
    (190, 200, 0.9104, 0.9726),
])
def test_the_wilson_interval_of_known_counts(successes, total, low, high):
    interval = winner.wilson_interval(successes, total)
    assert interval.low == pytest.approx(low, abs=1e-4)
    assert interval.high == pytest.approx(high, abs=1e-4)
    assert interval.confidence == 0.95


# At 21 rallies the closed form gives a lower end of -1.4e-17 for 0 successes.
@pytest.mark.parametrize("total", [1, 5, 21, 30, 60, 200, 1000])
def test_the_wilson_interval_never_leaves_zero_to_one_and_contains_its_own_ratio(total):
    assert winner.wilson_interval(total, total).high == 1.0
    assert winner.wilson_interval(0, total).low == 0.0
    for successes in (0, total // 3, total):
        interval = winner.wilson_interval(successes, total)
        assert 0.0 <= interval.low <= successes / total <= interval.high <= 1.0


def test_the_wilson_interval_refuses_counts_that_are_not_a_share():
    for successes, total in ((1, 0), (-1, 5), (6, 5)):
        with pytest.raises(ValueError, match=f"^{successes} of {total} is not a share$"):
            winner.wilson_interval(successes, total)


def test_the_confusion_counts_show_a_component_that_always_names_one_side():
    rallies = [gold(index, "HOME" if index < 8 else "AWAY") for index in range(10)]
    layer = one_video(rallies, [prop(index, "HOME") for index in range(9)]).layers["GOLD"]
    assert layer.confusion == {
        ("HOME", "HOME"): 8, ("HOME", "AWAY"): 0, ("HOME", "UNKNOWN"): 0, ("HOME", "NOT_PROPOSED"): 0,
        ("AWAY", "HOME"): 1, ("AWAY", "AWAY"): 0, ("AWAY", "UNKNOWN"): 0, ("AWAY", "NOT_PROPOSED"): 1,
    }


def test_the_layers_are_counted_apart_and_pooled_over_videos():
    double = [gold(0, "HOME"), gold(1, "AWAY", status="ADJUDICATED")]
    single = [gold(0, "HOME", status="SINGLE_HUMAN"), gold(1, "AWAY", status=None), gold(2, "AWAY", status="SINGLE_HUMAN")]
    evaluation = winner.pool({
        "V1": winner.evaluate_video(double, [prop(0, "HOME"), prop(1, "HOME")]),
        "V2": winner.evaluate_video(single, [prop(0, "HOME"), prop(1, "UNKNOWN")]),
    })
    gold_layer, single_layer = evaluation.layers["GOLD"], evaluation.layers["SINGLE"]
    assert (gold_layer.eligible, gold_layer.correct, gold_layer.wrong, gold_layer.videos) == (2, 1, 1, 1)
    assert (single_layer.eligible, single_layer.correct, single_layer.unknown, single_layer.not_proposed, single_layer.videos) == (3, 1, 1, 1, 1)
    assert (evaluation.gold_rallies, evaluation.predicted_rallies, evaluation.automatic_proposals) == (5, 4, 3)
    assert evaluation.videos["V1"]["GOLD"] == {"eligibleN": 2, "autoN": 2, "correct": 1, "wrong": 1, "unknown": 0, "notProposed": 0}
    assert evaluation.videos["V2"]["GOLD"]["eligibleN"] == 0


def test_the_lower_bound_counts_every_unpaired_decision_of_the_layers_videos_as_wrong():
    rallies = [gold(index) for index in range(60)]
    proposals = [prop(index, "HOME" if index < 58 else "AWAY") for index in range(60)]
    # Six more automatic decisions where the gold has no rally.
    proposals += [prop(index) for index in range(70, 76)]
    other_video = winner.evaluate_video([gold(0, status="SINGLE_HUMAN")], [prop(0), prop(5)])
    evaluation = winner.pool({"V1": winner.evaluate_video(rallies, proposals), "V2": other_video})
    layer = evaluation.layers["GOLD"]
    assert (layer.precision.numerator, layer.precision.denominator) == (58, 60)
    # The unpaired decision of V2 belongs to a video without a rally of the GOLD layer.
    assert layer.unpaired_auto == 6
    assert (layer.lower_bound_precision.numerator, layer.lower_bound_precision.denominator) == (58, 66)
    assert evaluation.layers["SINGLE"].unpaired_auto == 1


def test_how_the_gold_winner_is_known_is_counted_within_the_eligible_rallies():
    rallies = [
        gold(0, evidence="VIDEO"), gold(1, observation="INFERRED", evidence="RULE_CONSTRAINT"),
        gold(2, observation="INFERRED", evidence="SCOREBOARD"), gold(3, observation=None, evidence=""),
        gold(4, "UNKNOWN", observation="UNKNOWN", evidence="RULE_CONSTRAINT"),
    ]
    layer = one_video(rallies, [prop(0), prop(1), prop(2, "AWAY")]).layers["GOLD"]
    # (eligible, automatic, correct) for each way.
    assert layer.by_observation == {"OBSERVED": (1, 1, 1), "INFERRED": (2, 2, 1), "NOT_GIVEN": (1, 0, 0)}
    # The excluded rally is not counted, and an empty cell states no evidence.
    assert (layer.rule_constraint, layer.evidence_given) == (1, 3)

    # A file that has no evidence column says nothing about it: unknown, not zero.
    assert one_video([gold(0)], [prop(0)]).layers["GOLD"].rule_constraint is None
    # Nor does a column whose cells are all empty, as in a template nobody filled in.
    empty = one_video([gold(0, evidence=""), gold(1, evidence="  ")], [prop(0), prop(1)]).layers["GOLD"]
    assert (empty.rule_constraint, empty.evidence_given) == (None, 0)
    assert empty.to_dict()["ruleConstraintEvidence"] is None and empty.to_dict()["evidenceGiven"] == 0


def test_nothing_is_measured_when_the_predictions_hold_no_automatic_proposal():
    rallies = [gold(index) for index in range(40)]
    evaluation = one_video(rallies, [prop(index, "UNKNOWN") for index in range(28)])
    assert evaluation.automatic_proposals == 0
    assert evaluation.not_measured_reason is not None
    document = evaluation.to_dict()
    assert document["notMeasuredReason"] == evaluation.not_measured_reason
    layer = document["layers"]["GOLD"]
    # The counts say what the files hold. No ratio and no interval is quoted from them.
    assert (layer["eligibleN"], layer["autoN"], layer["unknown"], layer["notProposed"]) == (40, 0, 28, 12)
    for name in ("selectivePrecision", "autoCoverage", "unknownRate", "notProposedRate"):
        assert layer[name]["value"] is None
        assert layer[name]["interval"] is None

    measured = one_video(rallies, [prop(index) for index in range(40)])
    assert measured.not_measured_reason is None
    assert measured.to_dict()["layers"]["GOLD"]["autoCoverage"]["value"] == 1.0


# ----- calibration -----

@pytest.mark.parametrize("confidence, index", [
    (0.0, 0), (0.05, 0), (0.0999999, 0), (0.1, 1), (0.3, 3), (0.5, 5), (0.6, 6), (0.7, 7), (0.8, 8),
    (0.9, 9), (0.95, 9), (1.0, 9),
    # Computed values a floating point hair below their edge: 0.19999999999999998 and 0.09999999999999998.
    (0.3 - 0.1, 2), (1 - 0.9, 1),
])
def test_a_confidence_on_an_edge_belongs_to_the_bin_above_it(confidence, index):
    assert winner.bin_index(confidence) == index


def calibrated_layer(pairs, extra=()):
    """One eligible rally per (confidence, right) pair. `extra` proposals carry no confidence."""
    rallies = [gold(index, "HOME") for index in range(len(pairs) + len(extra))]
    proposals = [prop(index, "HOME" if right else "AWAY", confidence) for index, (confidence, right) in enumerate(pairs)]
    proposals += [prop(len(pairs) + index, "HOME" if right else "AWAY") for index, right in enumerate(extra)]
    return one_video(rallies, proposals).layers["GOLD"]


def test_brier_and_ece_of_a_small_hand_computed_case():
    calibration = calibrated_layer([(0.9, True), (0.9, True), (0.9, False), (0.6, True), (0.6, False)]).calibration
    assert calibration.n == 5
    # (0.01 + 0.01 + 0.81 + 0.16 + 0.36) / 5
    assert calibration.brier == pytest.approx(0.27)
    # Bin [0.9, 1.0]: 3 proposals, mean confidence 0.9, 2 of 3 correct. Bin [0.6, 0.7): 2, 0.6, 1 of 2.
    assert calibration.ece == pytest.approx(3 / 5 * abs(2 / 3 - 0.9) + 2 / 5 * abs(1 / 2 - 0.6))
    assert [(row.low, row.high, row.n, row.correct) for row in calibration.bins] == [(0.6, 0.7, 2, 1), (0.9, 1.0, 3, 2)]
    assert [row.mean_confidence for row in calibration.bins] == pytest.approx([0.6, 0.9])


def test_an_overconfident_bin_and_an_underconfident_bin_do_not_cancel_out():
    # Bin [0.9, 1.0]: mean confidence 0.9, 1 of 2 correct. Bin [0.1, 0.2): mean confidence 0.1, 1 of 2 correct.
    calibration = calibrated_layer([(0.9, True), (0.9, False), (0.1, True), (0.1, False)]).calibration
    assert calibration.ece == pytest.approx(2 / 4 * 0.4 + 2 / 4 * 0.4)


def test_an_abstention_is_no_decision_even_when_it_carries_a_confidence():
    layer = one_video([gold(0), gold(1), gold(2)], [prop(0, "HOME", 0.95), prop(1, "UNKNOWN", 0.97), prop(2, "UNKNOWN")]).layers["GOLD"]
    assert (layer.correct, layer.unknown) == (1, 2)
    # (eligible, automatic, correct): an abstention is no automatic decision here either.
    assert layer.by_observation["OBSERVED"] == (3, 1, 1)
    calibration = layer.calibration
    assert (calibration.n, calibration.without_confidence) == (1, 0)
    assert calibration.high_confidence(0.9) == (1, 0)


def test_calibration_reads_only_the_decisions_that_carry_a_confidence():
    layer = calibrated_layer([(0.8, True), (0.8, False)], extra=(True, False, False))
    calibration = layer.calibration
    assert (layer.auto, calibration.n) == (5, 2)
    assert (calibration.without_confidence, calibration.wrong_without_confidence) == (3, 2)


def test_a_component_without_confidences_has_no_calibration_and_no_high_confidence_count():
    calibration = calibrated_layer([], extra=(True, False)).calibration
    assert calibration.n == 0
    assert calibration.brier is None and calibration.ece is None
    assert calibration.bins == ()
    # Not zero: with no confidence nobody can say how many of the wrong ones were confident.
    assert calibration.high_confidence(0.9) is None
    assert calibration.wrong_without_confidence == 1
    document = calibration.to_dict(0.9, resamples=50, seed=0)
    assert document["highConfidence"] == {"level": 0.9, "n": None, "wrong": None,
                                          "notMeasuredReason": "no automatic decision carries a confidence"}
    assert document["brier"] is None and document["ece"] is None


def test_the_high_confidence_count_gives_the_wrong_ones_among_those_at_or_above_the_level():
    calibration = calibrated_layer(
        [(0.95, True), (0.95, False), (0.9, False), (0.9, True), (0.8999, False), (0.5, False)]).calibration
    assert calibration.high_confidence(0.9) == (4, 2)
    assert calibration.high_confidence(0.95) == (2, 1)
    assert calibration.high_confidence(1.0) == (0, 0)


def test_brier_and_ece_are_quoted_from_thirty_decisions_with_a_confidence_on():
    few = calibrated_layer([(0.9, True)] * 29).calibration
    document = few.to_dict(0.9, resamples=50, seed=0)
    assert document["n"] == 29 and document["reliable"] is False
    assert document["brier"] is None and document["ece"] is None and document["eceIfCalibrated"] is None
    # The table of counts stays.
    assert document["bins"] == [{"low": 0.9, "high": 1.0, "n": 29, "meanConfidence": pytest.approx(0.9), "correct": 29}]

    enough = calibrated_layer([(0.9, True)] * 27 + [(0.9, False)] * 3).calibration
    document = enough.to_dict(0.9, resamples=200, seed=3)
    assert document["reliable"] is True
    assert document["brier"]["value"] == pytest.approx((27 * 0.01 + 3 * 0.81) / 30)
    assert document["brier"]["interval"]["resamples"] == 200 and document["brier"]["interval"]["seed"] == 3
    # The interval is drawn with that seed, not only labelled with it.
    squared = np.array([(confidence - right) ** 2 for confidence, right in enough.pairs])
    drawn = metrics.bootstrap_ci(squared, resamples=200, seed=3)
    assert drawn.high != metrics.bootstrap_ci(squared, resamples=200, seed=0).high
    assert (document["brier"]["interval"]["low"], document["brier"]["interval"]["high"]) == pytest.approx((drawn.low, drawn.high))
    reference = winner.calibrated_ece_reference([0.9] * 30, resamples=200, seed=3)
    assert reference != winner.calibrated_ece_reference([0.9] * 30, resamples=200, seed=0)
    assert (document["eceIfCalibrated"]["mean"], document["eceIfCalibrated"]["p95"]) == pytest.approx((reference.mean, reference.p95))
    # 27 of 30 correct at a stated 0.9: the bin agrees with itself.
    assert document["ece"] == pytest.approx(0.0, abs=1e-12)
    assert document["highConfidence"] == {"level": 0.9, "n": 30, "wrong": 3, "notMeasuredReason": None}


def test_the_ece_of_a_calibrated_component_is_given_as_a_reference_and_is_reproducible():
    confidences = [0.55 + 0.01 * index for index in range(40)]
    first = winner.calibrated_ece_reference(confidences, resamples=300, seed=7)
    assert first == winner.calibrated_ece_reference(confidences, resamples=300, seed=7)
    assert first != winner.calibrated_ece_reference(confidences, resamples=300, seed=8)
    # A component whose confidences are exactly right still shows an ECE well above zero at this size.
    assert 0.05 < first.mean < first.p95 < 0.5

    # Confidence 1.0 can only be right, so there is nothing to draw.
    certain = winner.calibrated_ece_reference([1.0] * 30, resamples=50, seed=0)
    assert (certain.mean, certain.p95) == (0.0, 0.0)


# ----- the G3 targets -----

@pytest.mark.parametrize("numerator, denominator, target, meets, low_meets, fragment", [
    (200, 200, 0.95, True, True, "point estimate and whole interval at or above the target"),
    (29, 30, 0.95, True, False, "interval reaches down to 0.833"),
    (28, 30, 0.95, False, False, "interval reaches up to 0.982"),
    (10, 30, 0.60, False, False, "whole interval below the target"),
])
def test_a_target_is_compared_with_the_point_estimate_and_with_the_interval(numerator, denominator, target, meets, low_meets, fragment):
    check = winner.check_target(winner.Ratio(numerator, denominator), target)
    assert check.checked is True
    assert (check.point_estimate_meets, check.interval_low_meets) == (meets, low_meets)
    assert fragment in check.wording


@pytest.mark.parametrize("numerator, denominator, target, meets", [
    (57, 60, 0.95, True), (56, 60, 0.95, False), (18, 30, 0.60, True), (17, 30, 0.60, False),
    (19, 30, 0.60, True), (186, 196, 0.95, False), (190, 200, 0.95, True),
])
def test_a_ratio_exactly_on_its_target_meets_it_and_nothing_is_rounded_up(numerator, denominator, target, meets):
    assert winner.check_target(winner.Ratio(numerator, denominator), target).point_estimate_meets is meets


@pytest.mark.parametrize("value, target, text", [
    (0.951, 0.95, "0.951"), (0.9497487437185929, 0.95, "0.9497"), (0.949854375408217, 0.95, "0.9499"),
    (0.5999453676338891, 0.60, "0.5999"), (0.59996, 0.60, "0.59996"), (0.95, 0.95, "0.950"), (1.0, 0.95, "1.000"), (0.0, 0.60, "0.000"),
])
def test_a_value_beside_a_target_is_printed_on_the_same_side_of_it(value, target, text):
    # Three decimals would print 0.94975 as 0.950 next to "below the target".
    assert winner.against(value, target) == text


def test_the_wording_never_rounds_an_interval_end_onto_the_target():
    check = winner.check_target(winner.Ratio(108, 109), 0.95)
    assert "interval reaches down to 0.9499" in check.wording


def test_a_target_is_not_checked_on_fewer_than_thirty_or_without_any_decision():
    few = winner.check_target(winner.Ratio(25, 25), 0.95)
    assert few.checked is False
    assert (few.point_estimate_meets, few.interval_low_meets) == (None, None)
    assert "25 is below 30" in few.wording

    none = winner.check_target(winner.Ratio(0, 0), 0.95)
    assert none.checked is False
    assert "not defined" in none.wording


def gate(correct, wrong, unknown):
    rallies = [gold(index) for index in range(correct + wrong + unknown)]
    proposals = (
        [prop(index, "HOME") for index in range(correct)]
        + [prop(index, "AWAY") for index in range(correct, correct + wrong)]
        + [prop(index, "UNKNOWN") for index in range(correct + wrong, correct + wrong + unknown)]
    )
    return winner.g3(one_video(rallies, proposals).layers["GOLD"])


def test_the_targets_are_met_only_when_both_are_checked_and_met():
    met = gate(correct=130, wrong=5, unknown=65)
    assert (met.precision.point_estimate_meets, met.coverage.point_estimate_meets) == (True, True)
    assert met.point_estimates_meet_targets is True

    # 0.949 is below 0.95: nothing is rounded up to a target.
    just_below = gate(correct=186, wrong=10, unknown=4)
    assert just_below.precision.point_estimate_meets is False
    assert just_below.point_estimates_meet_targets is False


def test_abstaining_until_precision_looks_perfect_does_not_get_past_the_coverage_target():
    # 25 automatic decisions, all correct, and 175 abstentions.
    hidden = gate(correct=25, wrong=0, unknown=175)
    assert hidden.precision.checked is False
    assert (hidden.coverage.checked, hidden.coverage.point_estimate_meets) == (True, False)
    # One checked target that is missed decides, whatever the other one would have been.
    assert hidden.point_estimates_meet_targets is False

    # Nothing can be said when no checked target is missed and one is not checked.
    open_question = gate(correct=25, wrong=0, unknown=5)
    assert (open_question.precision.checked, open_question.coverage.checked) == (False, True)
    assert open_question.coverage.point_estimate_meets is True
    assert open_question.point_estimates_meet_targets is None


def test_the_gate_block_carries_the_lower_bound_next_to_the_precision():
    rallies = [gold(index) for index in range(60)]
    proposals = [prop(index, "HOME" if index < 58 else "AWAY") for index in range(60)] + [prop(index) for index in range(70, 76)]
    check = winner.g3(one_video(rallies, proposals).layers["GOLD"])
    assert check.precision.point_estimate_meets is True
    assert (check.lower_bound.ratio.numerator, check.lower_bound.ratio.denominator) == (58, 66)
    assert check.lower_bound.point_estimate_meets is False
    document = check.to_dict()
    assert document["targets"] == {"precision": 0.95, "coverage": 0.60}
    assert document["lowerBoundPrecision"]["pointEstimateMeetsTarget"] is False
    assert document["pointEstimatesMeetTargets"] is True


ALL_HOLD = dict(manifest_given=True, digest_compared=True, split="test", missing_test_videos=(),
                tolerance_ms=1000, analyzer_stated=True, automatic_proposals=1, gold_layer_eligible=120)


def test_a_run_is_input_for_the_gate_only_when_every_condition_holds():
    assert winner.pilot_reasons(**ALL_HOLD) == []


@pytest.mark.parametrize("change, fragment", [
    (dict(manifest_given=False, digest_compared=False, split=None), "no lock manifest"),
    (dict(digest_compared=False), "--expect-sha256"),
    (dict(split="validation"), "validation split"),
    (dict(missing_test_videos=("V4", "V9")), "V4, V9"),
    (dict(tolerance_ms=5000), "5000 ms"),
    (dict(tolerance_ms=500), "+/-500 ms"),
    (dict(analyzer_stated=False), "analyzer"),
    (dict(automatic_proposals=0), "the evaluated videos carry no HOME or AWAY proposal"),
    (dict(gold_layer_eligible=0), "double-agreed or adjudicated"),
])
def test_each_missing_condition_makes_the_run_a_pilot_run_and_is_named(change, fragment):
    reasons = winner.pilot_reasons(**{**ALL_HOLD, **change})
    assert len(reasons) >= 1
    assert any(fragment in reason for reason in reasons)


def test_every_reason_that_applies_is_given_in_a_fixed_order():
    reasons = winner.pilot_reasons(manifest_given=True, digest_compared=False, split="train", missing_test_videos=(),
                                   tolerance_ms=2000, analyzer_stated=False, automatic_proposals=0, gold_layer_eligible=0)
    assert len(reasons) == 6
    assert ["--expect-sha256" in reasons[0], "train split" in reasons[1], "2000 ms" in reasons[2],
            "analyzer" in reasons[3], "no HOME or AWAY proposal" in reasons[4],
            "double-agreed or adjudicated" in reasons[5]] == [True] * 6
    # Without a manifest nothing can be said about the split, the digest or the videos of the split.
    without = winner.pilot_reasons(manifest_given=False, digest_compared=False, split=None, missing_test_videos=(),
                                   tolerance_ms=1000, analyzer_stated=True, automatic_proposals=3, gold_layer_eligible=50)
    assert len(without) == 1
