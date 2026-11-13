import pytest

from volleystat_ml import agreement
from volleystat_ml.agreement import Rally


def rallies(*spans, winner=None, serving=None):
    return [Rally(start, end, winning_side=winner, serving_side=serving) for start, end in spans]


SPANS = [(0, 8000), (12000, 20000), (25000, 31000)]


def test_identical_labels_agree_completely():
    a = rallies(*SPANS, winner="HOME", serving="AWAY")
    result = agreement.compare(a, list(a))
    assert (result.a_count, result.b_count, result.matched, result.only_a, result.only_b) == (3, 3, 3, 0, 0)
    assert result.boundaries.median_abs_start_error_ms == 0 and result.boundaries.p90_abs_end_error_ms == 0
    assert result.boundaries.mean_iou == 1.0
    assert result.disagreements == ()
    winner = result.fields["winning_side"]
    assert (winner.compared, winner.agree, winner.not_comparable, winner.both_empty) == (3, 3, 0, 0)
    assert winner.observed == 1.0


def test_boundary_differences_inside_the_tolerance_match_and_are_measured():
    a = rallies(*SPANS)
    # Start 400 ms, 0 ms and 1000 ms late; end 200 ms early, 600 ms late and exact.
    b = rallies((400, 7800), (12000, 20600), (26000, 31000))
    result = agreement.compare(a, b, tolerance_ms=1000)
    assert (result.matched, result.only_a, result.only_b) == (3, 0, 0)
    # Absolute start differences 0, 400, 1000: median 400; p90 by linear interpolation
    # between 400 and 1000 at 0.8 of the way: 400 + 0.8 * 600 = 880.
    assert result.boundaries.median_abs_start_error_ms == 400
    assert result.boundaries.p90_abs_start_error_ms == pytest.approx(880)
    assert result.boundaries.median_abs_end_error_ms == 200
    # IoU: 7400/8000, 8000/8600, 5000/6000.
    assert result.boundaries.mean_iou == pytest.approx((7400 / 8000 + 8000 / 8600 + 5000 / 6000) / 3)


def test_a_boundary_outside_the_tolerance_leaves_one_rally_on_each_side():
    a = rallies((0, 8000), (12000, 20000))
    b = rallies((0, 8000), (12000, 21001))
    result = agreement.compare(a, b, tolerance_ms=1000)
    assert (result.matched, result.only_a, result.only_b) == (1, 1, 1)
    assert [(item.kind, item.a, item.b) for item in result.disagreements] == [
        ("ONLY_A", a[1], None), ("ONLY_B", None, b[1]),
    ]
    # A wider tolerance pairs them.
    assert agreement.compare(a, b, tolerance_ms=1001).matched == 2


def test_rallies_only_one_annotator_has_are_listed():
    a = rallies((0, 8000), (12000, 20000), (25000, 31000))
    b = rallies((12000, 20000), (40000, 45000))
    result = agreement.compare(a, b)
    assert (result.a_count, result.b_count, result.matched, result.only_a, result.only_b) == (3, 2, 1, 2, 1)
    assert [item.kind for item in result.disagreements] == ["ONLY_A", "ONLY_A", "ONLY_B"]


def test_categorical_disagreement_is_counted_and_listed_with_both_values():
    a = [Rally(0, 8000, "HOME", "HOME"), Rally(12000, 20000, "AWAY", "HOME")]
    b = [Rally(0, 8000, "HOME", "AWAY"), Rally(12000, 20000, "HOME", "HOME")]
    result = agreement.compare(a, b)
    winner, serving = result.fields["winning_side"], result.fields["serving_side"]
    assert (winner.compared, winner.agree) == (2, 1)
    assert (serving.compared, serving.agree) == (2, 1)
    assert [(item.kind, item.a_value, item.b_value) for item in result.disagreements] == [
        ("SERVING", "HOME", "AWAY"), ("WINNER", "AWAY", "HOME"),
    ]


def test_values_are_compared_as_written_and_unknown_is_a_value():
    a = [Rally(0, 8000, "UNKNOWN"), Rally(12000, 20000, "UNKNOWN")]
    b = [Rally(0, 8000, "HOME"), Rally(12000, 20000, "UNKNOWN")]
    winner = agreement.compare(a, b).fields["winning_side"]
    assert (winner.compared, winner.agree) == (2, 1)


def test_a_field_one_annotator_left_empty_is_not_compared_and_counted_apart():
    a = [Rally(0, 8000, "HOME", None), Rally(12000, 20000, None, None), Rally(25000, 31000, "AWAY", "HOME")]
    b = [Rally(0, 8000, None, None), Rally(12000, 20000, "HOME", None), Rally(25000, 31000, "AWAY", "  ")]
    result = agreement.compare(a, b)
    winner, serving = result.fields["winning_side"], result.fields["serving_side"]
    assert (winner.compared, winner.agree, winner.not_comparable, winner.both_empty) == (1, 1, 2, 0)
    # A blank cell is an empty field, not a value.
    assert (serving.compared, serving.not_comparable, serving.both_empty) == (0, 1, 2)
    # Nothing compared: every ratio is unknown, never 0.
    assert serving.observed is None and serving.kappa is None
    assert serving.kappa_undefined_reason == "no matched pair has this field from both annotators"
    # An empty field is not a disagreement row: nothing was said that could disagree.
    assert result.disagreements == ()


def test_observation_status_not_annotated_means_the_winner_was_not_labelled():
    a = [Rally(0, 8000, "UNKNOWN", "HOME", observation_status="NOT_ANNOTATED")]
    b = [Rally(0, 8000, "HOME", "HOME", observation_status="OBSERVED")]
    result = agreement.compare(a, b)
    assert result.fields["winning_side"].not_comparable == 1
    assert result.fields["winning_side"].compared == 0
    assert result.fields["serving_side"].agree == 1
    assert result.disagreements == ()


def test_cohen_kappa_matches_a_hand_computation():
    # 10 pairs. Both HOME 4, both AWAY 3, A HOME / B AWAY 2, A AWAY / B HOME 1.
    # Observed agreement p_o = 7/10 = 0.7.
    # A says HOME 6 times and AWAY 4 times; B says HOME 5 times and AWAY 5 times.
    # Expected agreement p_e = 0.6 * 0.5 + 0.4 * 0.5 = 0.5.
    # kappa = (p_o - p_e) / (1 - p_e) = (0.7 - 0.5) / 0.5 = 0.4.
    pairs = [("HOME", "HOME")] * 4 + [("AWAY", "AWAY")] * 3 + [("HOME", "AWAY")] * 2 + [("AWAY", "HOME")]
    field = agreement.FieldAgreement("winning_side", tuple(pairs), 0, 0)
    assert (field.compared, field.agree) == (10, 7)
    assert field.observed == pytest.approx(0.7)
    assert field.expected == pytest.approx(0.5)
    assert field.kappa == pytest.approx(0.4)
    assert field.kappa_undefined_reason is None


def test_cohen_kappa_with_three_values_matches_a_hand_computation():
    # 5 pairs: (HOME, HOME), (AWAY, AWAY), (UNKNOWN, HOME), (HOME, HOME), (AWAY, UNKNOWN).
    # p_o = 3/5 = 0.6. A: HOME 2, AWAY 2, UNKNOWN 1. B: HOME 3, AWAY 1, UNKNOWN 1.
    # p_e = (2*3 + 2*1 + 1*1) / 25 = 9/25 = 0.36. kappa = (0.6 - 0.36) / 0.64 = 0.375.
    pairs = (("HOME", "HOME"), ("AWAY", "AWAY"), ("UNKNOWN", "HOME"), ("HOME", "HOME"), ("AWAY", "UNKNOWN"))
    field = agreement.FieldAgreement("winning_side", pairs, 0, 0)
    assert field.kappa == pytest.approx(0.375)


def test_kappa_is_undefined_when_both_annotators_use_one_single_value():
    field = agreement.FieldAgreement("winning_side", (("HOME", "HOME"),) * 40, 0, 0)
    assert field.observed == 1.0 and field.expected == 1.0
    assert field.kappa is None
    assert "HOME" in field.kappa_undefined_reason and "expected agreement is 1" in field.kappa_undefined_reason
    document = field.to_dict()
    assert document["kappa"] is None and document["agreementRate"] == 1.0
    assert document["kappaUndefinedReason"] == field.kappa_undefined_reason


def test_one_single_value_on_each_side_but_different_values_gives_a_defined_kappa():
    # A always HOME, B always AWAY: p_o = 0, p_e = 0, kappa = 0. Defined, and really 0.
    field = agreement.FieldAgreement("winning_side", (("HOME", "AWAY"),) * 5, 0, 0)
    assert field.kappa == 0.0 and field.kappa_undefined_reason is None


def test_below_thirty_compared_pairs_the_dictionary_carries_counts_only():
    field = agreement.FieldAgreement("winning_side", (("HOME", "HOME"),) * 18 + (("HOME", "AWAY"),) * 11, 3, 1)
    assert field.compared == 29 and field.percentages_reliable is False
    assert field.to_dict() == {
        "compared": 29, "agree": 18, "notComparable": 3, "bothEmpty": 1,
        "agreementRate": None, "kappa": None, "kappaUndefinedReason": None,
        "percentagesReliable": False, "minReliable": 30,
    }
    grown = agreement.FieldAgreement("winning_side", field.pairs + (("AWAY", "AWAY"),), 3, 1)
    document = grown.to_dict()
    assert document["percentagesReliable"] is True
    assert document["agreementRate"] == pytest.approx(19 / 30)
    assert document["kappa"] == pytest.approx(grown.kappa)


def test_pool_adds_counts_and_takes_kappa_over_all_pairs():
    first = agreement.compare([Rally(0, 8000, "HOME")] * 1, [Rally(0, 8000, "HOME")])
    second = agreement.compare([Rally(0, 8000, "AWAY")], [Rally(500, 8000, "HOME")])
    pooled = agreement.pool([first, second])
    assert (pooled.a_count, pooled.b_count, pooled.matched) == (2, 2, 2)
    winner = pooled.fields["winning_side"]
    assert (winner.compared, winner.agree) == (2, 1)
    # Each video alone has one value per side; the pool has two values and a defined kappa.
    assert first.fields["winning_side"].kappa is None
    assert winner.kappa == pytest.approx((0.5 - 0.5) / (1 - 0.5))
    assert pooled.boundaries.median_abs_start_error_ms == 250
    assert len(pooled.disagreements) == 1


def test_disagreement_rows_are_ordered_by_video_and_time():
    v2 = agreement.compare([Rally(0, 8000, "HOME", "HOME"), Rally(20000, 26000, "HOME")],
                           [Rally(0, 8000, "AWAY", "AWAY"), Rally(12000, 15000, "HOME")])
    v1 = agreement.compare([Rally(5000, 9000, "HOME")], [Rally(1000, 3000, "HOME"), Rally(5000, 9000, "AWAY")])
    rows = agreement.disagreement_rows({"V2": v2, "V1": v1})
    assert [(row["video_id"], row["kind"], row["a_start_ms"], row["b_start_ms"], row["a_value"], row["b_value"])
            for row in rows] == [
        ("V1", "ONLY_B", None, 1000, None, None),
        ("V1", "WINNER", 5000, 5000, "HOME", "AWAY"),
        ("V2", "WINNER", 0, 0, "HOME", "AWAY"),
        ("V2", "SERVING", 0, 0, "HOME", "AWAY"),
        ("V2", "ONLY_B", None, 12000, None, None),
        ("V2", "ONLY_A", 20000, None, None, None),
    ]
    assert list(rows[0]) == list(agreement.DISAGREEMENT_COLUMNS)


def test_agreement_dictionary_has_counts_boundaries_and_fields():
    result = agreement.compare(rallies((0, 8000), winner="HOME"), rallies((100, 8000), winner="HOME"))
    document = result.to_dict()
    assert document["rallies"] == {"a": 1, "b": 1, "matched": 1, "onlyA": 0, "onlyB": 0}
    assert document["toleranceMs"] == 1000
    assert document["boundaries"]["medianAbsStartDiffMs"] == 100
    assert document["boundaries"]["meanIou"] == pytest.approx(7900 / 8000)
    assert set(document["fields"]) == {"winningSide", "servingSide"}
    assert document["disagreements"] == 0


def test_no_matched_pair_leaves_the_boundary_numbers_unknown():
    result = agreement.compare(rallies((0, 8000)), rallies((20000, 26000)))
    document = result.to_dict()
    assert document["boundaries"] == {
        "medianAbsStartDiffMs": None, "p90AbsStartDiffMs": None,
        "medianAbsEndDiffMs": None, "p90AbsEndDiffMs": None, "meanIou": None,
    }
