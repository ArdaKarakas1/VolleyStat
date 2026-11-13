import itertools
import random

import numpy as np
import pytest

from volleystat_ml import metrics
from volleystat_ml.metrics import bootstrap_ci, evaluate_boundaries, pool


def counts(evaluation):
    return evaluation.tp, evaluation.fp, evaluation.fn


def test_exact_predictions_score_perfectly():
    gold = [(1000, 6000), (10000, 18000)]
    evaluation = evaluate_boundaries(gold, gold)
    assert counts(evaluation) == (2, 0, 0)
    assert (evaluation.precision, evaluation.recall, evaluation.f1) == (1.0, 1.0, 1.0)
    assert evaluation.median_abs_start_error_ms == 0 and evaluation.p90_abs_end_error_ms == 0
    assert evaluation.mean_iou == 1.0
    assert evaluation.tolerance_ms == 1000


def test_tolerance_is_inclusive_and_applies_to_each_boundary():
    gold = [(10000, 20000)]
    assert counts(evaluate_boundaries(gold, [(11000, 19000)])) == (1, 0, 0)
    assert counts(evaluate_boundaries(gold, [(11001, 20000)])) == (0, 1, 1)
    assert counts(evaluate_boundaries(gold, [(10000, 18999)])) == (0, 1, 1)
    assert counts(evaluate_boundaries(gold, [(10300, 20300)], tolerance_ms=299)) == (0, 1, 1)
    assert counts(evaluate_boundaries(gold, [(10300, 20300)], tolerance_ms=300)) == (1, 0, 0)


def test_start_within_tolerance_is_not_enough():
    # A prediction that merges two gold rallies has the right start and the wrong end.
    gold = [(0, 5000), (8000, 12000)]
    evaluation = evaluate_boundaries(gold, [(0, 12000)])
    assert counts(evaluation) == (0, 1, 2)
    assert (evaluation.precision, evaluation.recall, evaluation.f1) == (0.0, 0.0, 0.0)
    assert evaluation.median_abs_start_error_ms is None and evaluation.mean_iou is None


def test_matching_is_one_to_one():
    gold = [(10000, 20000)]
    evaluation = evaluate_boundaries(gold, [(9800, 20100), (10100, 19900)])
    assert counts(evaluation) == (1, 1, 0)
    assert (evaluation.precision, evaluation.recall) == (0.5, 1.0)
    assert evaluation.f1 == pytest.approx(2 / 3)

    evaluation = evaluate_boundaries([(10000, 20000), (10200, 20200)], [(10100, 20100)])
    assert counts(evaluation) == (1, 0, 1)


def test_pairing_keeps_as_many_pairs_as_possible():
    # Both predictions are closest to gold A, but only the second can also take gold B.
    # Pairing each prediction with its nearest gold rally first would lose one match.
    gold = [(10000, 12000), (11500, 13500)]
    predicted = [(9100, 11100), (10600, 12600)]
    evaluation = evaluate_boundaries(gold, predicted)
    assert counts(evaluation) == (2, 0, 0)
    assert [(match.predicted_index, match.gold_index) for match in evaluation.matches] == [(0, 0), (1, 1)]


def test_pairing_prefers_the_smaller_total_error_among_equal_sizes():
    gold = [(10000, 20000)]
    evaluation = evaluate_boundaries(gold, [(10900, 20900), (10100, 20100)])
    assert [(match.predicted_index, match.gold_index) for match in evaluation.matches] == [(1, 0)]
    assert evaluation.matches[0].start_error_ms == 100


def test_error_statistics_and_iou_use_matched_pairs_only():
    gold = [(0, 10000), (20000, 30000), (40000, 50000), (60000, 70000)]
    predicted = [(100, 9800), (20400, 30000), (39000, 50600), (80000, 90000)]
    evaluation = evaluate_boundaries(gold, predicted)
    assert counts(evaluation) == (3, 1, 1)
    assert [match.start_error_ms for match in evaluation.matches] == [100, 400, -1000]
    assert [match.end_error_ms for match in evaluation.matches] == [-200, 0, 600]
    assert evaluation.median_abs_start_error_ms == 400
    assert evaluation.p90_abs_start_error_ms == pytest.approx(880)   # linear interpolation over 100, 400, 1000
    assert evaluation.median_abs_end_error_ms == 200
    assert evaluation.p90_abs_end_error_ms == pytest.approx(520)
    expected_iou = (9700 / 10000 + 9600 / 10000 + 10000 / 11600) / 3
    assert evaluation.mean_iou == pytest.approx(expected_iou)
    assert evaluation.precision == 0.75 and evaluation.recall == 0.75 and evaluation.f1 == 0.75


def test_matched_pair_without_overlap_has_zero_iou():
    evaluation = evaluate_boundaries([(0, 500)], [(600, 1100)])
    assert counts(evaluation) == (1, 0, 0)
    assert evaluation.mean_iou == 0.0


def test_empty_sides_give_unknown_ratios_not_zero():
    nothing = evaluate_boundaries([], [])
    assert counts(nothing) == (0, 0, 0)
    assert (nothing.precision, nothing.recall, nothing.f1) == (None, None, None)

    no_predictions = evaluate_boundaries([(0, 1000)], [])
    assert counts(no_predictions) == (0, 0, 1)
    assert (no_predictions.precision, no_predictions.recall, no_predictions.f1) == (None, 0.0, 0.0)

    no_gold = evaluate_boundaries([], [(0, 1000)])
    assert counts(no_gold) == (0, 1, 0)
    assert (no_gold.precision, no_gold.recall, no_gold.f1) == (0.0, None, 0.0)


def test_input_order_does_not_matter_and_indices_refer_to_the_input():
    gold = [(40000, 50000), (0, 10000)]
    predicted = [(100, 9900), (70000, 80000), (40100, 50100)]
    evaluation = evaluate_boundaries(gold, predicted)
    assert [(match.predicted_index, match.gold_index) for match in evaluation.matches] == [(0, 1), (2, 0)]


@pytest.mark.parametrize("bad", [[(5000, 5000)], [(6000, 5000)]])
def test_intervals_must_have_positive_length(bad):
    with pytest.raises(ValueError, match="must be less than end"):
        evaluate_boundaries(bad, [])
    with pytest.raises(ValueError, match="must be less than end"):
        evaluate_boundaries([], bad)


def test_fewer_than_thirty_gold_rallies_are_flagged_with_raw_counts():
    def rallies(count):
        return [(index * 20000, index * 20000 + 8000) for index in range(count)]

    small = evaluate_boundaries(rallies(20), rallies(18))
    assert small.percentages_reliable is False
    assert (small.tp, small.gold_count, small.predicted_count) == (18, 20, 18)
    document = small.to_dict()
    assert document["percentagesReliable"] is False and document["minReliableGold"] == 30
    assert document["recall"] == {"value": 0.9, "numerator": 18, "denominator": 20}
    assert document["precision"] == {"value": 1.0, "numerator": 18, "denominator": 18}

    assert evaluate_boundaries(rallies(29), rallies(29)).percentages_reliable is False
    assert evaluate_boundaries(rallies(30), rallies(30)).percentages_reliable is True
    # Many predictions do not make a small gold set reliable.
    assert evaluate_boundaries(rallies(5), rallies(40)).percentages_reliable is False


def test_pool_sums_counts_and_recomputes_statistics_over_all_pairs():
    first = evaluate_boundaries([(0, 10000), (20000, 30000)], [(200, 10000)])
    second = evaluate_boundaries([(0, 10000)], [(600, 10400), (50000, 60000)])
    total = pool([first, second])
    assert counts(total) == (2, 1, 1)
    assert (total.gold_count, total.predicted_count) == (3, 3)
    assert total.median_abs_start_error_ms == 400   # median of 200 and 600
    assert total.precision == pytest.approx(2 / 3) and total.recall == pytest.approx(2 / 3)
    assert total.percentages_reliable is False

    with pytest.raises(ValueError):
        pool([])
    with pytest.raises(ValueError, match="different tolerances"):
        pool([first, evaluate_boundaries([], [], tolerance_ms=500)])


def test_pairing_matches_exhaustive_search_on_random_cases():
    def best_by_search(gold, predicted, tolerance):
        """Largest pairing, then smallest total error, by trying every assignment."""
        best = (0, 0)
        for size in range(1, min(len(gold), len(predicted)) + 1):
            for chosen in itertools.combinations(range(len(predicted)), size):
                for targets in itertools.permutations(range(len(gold)), size):
                    errors = [
                        (abs(predicted[p][0] - gold[g][0]), abs(predicted[p][1] - gold[g][1]))
                        for p, g in zip(chosen, targets)
                    ]
                    if all(start <= tolerance and end <= tolerance for start, end in errors):
                        best = max(best, (size, -sum(start + end for start, end in errors)))
        return best

    generator = random.Random(7)
    for _ in range(300):
        # Short, crowded intervals so that several candidates compete for the same rally.
        gold = [(start, start + generator.randint(200, 1500))
                for start in (generator.randint(0, 5000) for _ in range(generator.randint(0, 5)))]
        predicted = [(start, start + generator.randint(200, 1500))
                     for start in (generator.randint(0, 5000) for _ in range(generator.randint(0, 5)))]
        evaluation = evaluate_boundaries(gold, predicted, tolerance_ms=800)
        total_error = sum(abs(match.start_error_ms) + abs(match.end_error_ms) for match in evaluation.matches)
        assert (evaluation.tp, -total_error) == best_by_search(gold, predicted, 800)
        assert len({match.predicted_index for match in evaluation.matches}) == evaluation.tp
        assert len({match.gold_index for match in evaluation.matches}) == evaluation.tp


def test_bootstrap_interval_is_reproducible_with_a_seed():
    errors = np.linspace(0, 1000, 37) ** 1.5
    first = bootstrap_ci(errors, seed=42)
    assert first == bootstrap_ci(errors, seed=42)
    assert first != bootstrap_ci(errors, seed=43)
    assert (first.confidence, first.resamples) == (0.95, 2000)

    # 18 of 20 correct: the interval contains the observed share and is not a single point.
    outcomes = [1] * 18 + [0] * 2
    interval = bootstrap_ci(outcomes, seed=42)
    assert 0 <= interval.low <= 0.9 <= interval.high <= 1
    assert interval.low < interval.high and interval.degenerate is False


def test_bootstrap_interval_narrows_with_confidence_and_follows_the_statistic():
    values = np.arange(100.0)
    wide = bootstrap_ci(values, seed=1, confidence=0.95)
    narrow = bootstrap_ci(values, seed=1, confidence=0.50)
    assert wide.low < narrow.low < narrow.high < wide.high
    median = bootstrap_ci(values, np.median, seed=1)
    assert median.low < 49.5 < median.high


def test_bootstrap_resamples_rows_so_a_ratio_of_sums_can_be_bootstrapped():
    # One row per match: TP, FP, FN.
    per_match = np.array([[9, 1, 1], [5, 5, 3], [10, 0, 0], [7, 2, 4]])

    def f1(rows):
        tp, fp, fn = rows.sum(axis=0)
        return 2 * tp / (2 * tp + fp + fn)

    interval = bootstrap_ci(per_match, f1, seed=3, resamples=500)
    assert 0 < interval.low < f1(per_match) < interval.high <= 1


def test_bootstrap_marks_an_interval_without_spread_as_degenerate():
    interval = bootstrap_ci([1] * 20, seed=0)
    assert (interval.low, interval.high) == (1.0, 1.0)
    assert interval.degenerate is True


def test_bootstrap_rejects_unusable_input():
    with pytest.raises(ValueError):
        bootstrap_ci([])
    with pytest.raises(ValueError):
        bootstrap_ci([1, 0], confidence=1.0)
    with pytest.raises(ValueError):
        bootstrap_ci([1, 0], resamples=0)


def test_default_constants_follow_the_specification():
    assert metrics.DEFAULT_TOLERANCE_MS == 1000
    assert metrics.MIN_RELIABLE_GOLD == 30
