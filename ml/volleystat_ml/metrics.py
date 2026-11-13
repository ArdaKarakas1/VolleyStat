"""Rally boundary evaluation, as defined in the product specification.

A predicted rally matches a gold rally when its start AND its end are each within
the tolerance (default 1000 ms) of the gold ones. Matching is one-to-one. An
unmatched prediction is a false positive, an unmatched gold rally a false negative.

    precision = TP / (TP + FP)    recall = TP / (TP + FN)    F1 = 2PR / (P + R)

The absolute start and end errors (median and 90th percentile) and the interval
IoU are computed over the matched pairs only.

With fewer than 30 gold rallies a percentage is misleading. The result then
carries `percentages_reliable = False`; report the raw counts ("18/20") and an
interval instead.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable, Iterable, NamedTuple, Sequence

import numpy as np

DEFAULT_TOLERANCE_MS = 1000
MIN_RELIABLE_GOLD = 30


class Match(NamedTuple):
    predicted_index: int
    gold_index: int
    # Signed: predicted minus gold. Negative means the prediction is early.
    start_error_ms: int
    end_error_ms: int
    iou: float


@dataclass(frozen=True)
class BoundaryEvaluation:
    tolerance_ms: int
    gold_count: int
    predicted_count: int
    tp: int
    fp: int
    fn: int
    # None when the denominator is zero. A ratio that can not be computed is unknown, not 0.
    precision: float | None
    recall: float | None
    f1: float | None
    median_abs_start_error_ms: float | None
    p90_abs_start_error_ms: float | None
    median_abs_end_error_ms: float | None
    p90_abs_end_error_ms: float | None
    mean_iou: float | None
    # False below MIN_RELIABLE_GOLD gold rallies: quote the counts, not the percentages.
    percentages_reliable: bool
    # Indices refer to the sequences given to evaluate_boundaries. After pool() they are
    # per-video indices and only the errors stay meaningful.
    matches: tuple[Match, ...]

    def to_dict(self) -> dict[str, Any]:
        return {
            "toleranceMs": self.tolerance_ms,
            "goldCount": self.gold_count,
            "predictedCount": self.predicted_count,
            "tp": self.tp,
            "fp": self.fp,
            "fn": self.fn,
            "precision": {"value": self.precision, "numerator": self.tp, "denominator": self.tp + self.fp},
            "recall": {"value": self.recall, "numerator": self.tp, "denominator": self.tp + self.fn},
            "f1": self.f1,
            "medianAbsStartErrorMs": self.median_abs_start_error_ms,
            "p90AbsStartErrorMs": self.p90_abs_start_error_ms,
            "medianAbsEndErrorMs": self.median_abs_end_error_ms,
            "p90AbsEndErrorMs": self.p90_abs_end_error_ms,
            "meanIou": self.mean_iou,
            "percentagesReliable": self.percentages_reliable,
            "minReliableGold": MIN_RELIABLE_GOLD,
        }


def evaluate_boundaries(
    gold: Sequence[Sequence[int]],
    predicted: Sequence[Sequence[int]],
    tolerance_ms: int = DEFAULT_TOLERANCE_MS,
) -> BoundaryEvaluation:
    """Evaluate the predictions of ONE video. Items are (start_ms, end_ms) pairs.

    Never mix videos in one call: a prediction could then match a rally of another
    video. Evaluate each video and combine the results with pool().
    """
    if tolerance_ms < 0:
        raise ValueError("tolerance_ms must not be negative")
    gold_intervals = _intervals(gold, "gold")
    predicted_intervals = _intervals(predicted, "predicted")
    pairs = _match(gold_intervals, predicted_intervals, tolerance_ms)
    matches = []
    for predicted_index, gold_index in pairs:
        predicted_start, predicted_end = predicted_intervals[predicted_index]
        gold_start, gold_end = gold_intervals[gold_index]
        overlap = max(0, min(predicted_end, gold_end) - max(predicted_start, gold_start))
        union = (predicted_end - predicted_start) + (gold_end - gold_start) - overlap
        matches.append(Match(
            predicted_index, gold_index,
            predicted_start - gold_start, predicted_end - gold_end, overlap / union,
        ))
    return _summarise(tolerance_ms, len(gold_intervals), len(predicted_intervals), matches)


def pool(evaluations: Iterable[BoundaryEvaluation]) -> BoundaryEvaluation:
    """Combine per-video results: counts are summed, errors are taken over all matched pairs."""
    evaluations = list(evaluations)
    if not evaluations:
        raise ValueError("pool() needs at least one evaluation")
    tolerances = {evaluation.tolerance_ms for evaluation in evaluations}
    if len(tolerances) != 1:
        raise ValueError(f"evaluations use different tolerances: {sorted(tolerances)}")
    return _summarise(
        tolerances.pop(),
        sum(evaluation.gold_count for evaluation in evaluations),
        sum(evaluation.predicted_count for evaluation in evaluations),
        [match for evaluation in evaluations for match in evaluation.matches],
    )


class BootstrapInterval(NamedTuple):
    low: float
    high: float
    confidence: float
    resamples: int
    # True when every resample gave the same statistic (for example 20 of 20 correct).
    # The interval then has zero width and says nothing about the uncertainty.
    degenerate: bool


def bootstrap_ci(
    samples: Sequence[Any],
    statistic: Callable[[np.ndarray], float] = np.mean,
    *,
    confidence: float = 0.95,
    resamples: int = 2000,
    seed: int = 0,
) -> BootstrapInterval:
    """Percentile bootstrap interval of `statistic` over `samples`, resampled along axis 0.

    The generator is seeded, so the same input gives the same interval. Resample
    independent units: rallies of one match are correlated, so for a claim about new
    matches pass one row per match (for example its TP, FP and FN) instead of one per rally.
    """
    data = np.asarray(samples)
    if data.shape[0] == 0:
        raise ValueError("bootstrap_ci needs at least one sample")
    if not 0 < confidence < 1:
        raise ValueError("confidence must be between 0 and 1")
    if resamples < 1:
        raise ValueError("resamples must be at least 1")
    generator = np.random.default_rng(seed)
    count = data.shape[0]
    values = np.array([
        float(statistic(data[generator.integers(0, count, size=count)])) for _ in range(resamples)
    ])
    tail = (1 - confidence) / 2 * 100
    low, high = np.percentile(values, [tail, 100 - tail])
    return BootstrapInterval(float(low), float(high), confidence, resamples, bool(low == high))


def _intervals(items: Sequence[Sequence[int]], name: str) -> list[tuple[int, int]]:
    intervals = []
    for index, item in enumerate(items):
        start, end = int(item[0]), int(item[1])
        if not start < end:
            raise ValueError(f"{name}[{index}]: start ({start}) must be less than end ({end})")
        intervals.append((start, end))
    return intervals


def _summarise(tolerance_ms: int, gold_count: int, predicted_count: int,
               matches: Sequence[Match]) -> BoundaryEvaluation:
    tp = len(matches)
    fp = predicted_count - tp
    fn = gold_count - tp
    start_errors = np.abs([match.start_error_ms for match in matches])
    end_errors = np.abs([match.end_error_ms for match in matches])

    def percentile(values: np.ndarray, q: float) -> float | None:
        return float(np.percentile(values, q)) if tp else None

    return BoundaryEvaluation(
        tolerance_ms=tolerance_ms,
        gold_count=gold_count,
        predicted_count=predicted_count,
        tp=tp,
        fp=fp,
        fn=fn,
        precision=tp / predicted_count if predicted_count else None,
        recall=tp / gold_count if gold_count else None,
        # Equal to 2PR / (P + R) whenever both are defined, and still defined (0.0) when
        # one side is empty and the other is not.
        f1=2 * tp / (predicted_count + gold_count) if predicted_count + gold_count else None,
        median_abs_start_error_ms=percentile(start_errors, 50),
        p90_abs_start_error_ms=percentile(start_errors, 90),
        median_abs_end_error_ms=percentile(end_errors, 50),
        p90_abs_end_error_ms=percentile(end_errors, 90),
        mean_iou=float(np.mean([match.iou for match in matches])) if tp else None,
        percentages_reliable=gold_count >= MIN_RELIABLE_GOLD,
        matches=tuple(matches),
    )


def _match(gold: list[tuple[int, int]], predicted: list[tuple[int, int]],
           tolerance_ms: int) -> list[tuple[int, int]]:
    """One-to-one pairing as (predicted_index, gold_index), sorted by predicted_index.

    The pairing has the largest possible number of pairs, so no prediction is counted as
    a false positive while a free gold rally within tolerance exists. Among pairings of
    that size it has the smallest total |start error| + |end error|.
    """
    cost: dict[tuple[int, int], int] = {}
    for predicted_index, (predicted_start, predicted_end) in enumerate(predicted):
        for gold_index, (gold_start, gold_end) in enumerate(gold):
            start_error = abs(predicted_start - gold_start)
            end_error = abs(predicted_end - gold_end)
            if start_error <= tolerance_ms and end_error <= tolerance_ms:
                cost[predicted_index, gold_index] = start_error + end_error

    # Candidates form small independent groups (usually a single pair). Solving each
    # group on its own keeps the cubic assignment step away from the whole match.
    parent: dict[tuple[str, int], tuple[str, int]] = {}

    def root(node: tuple[str, int]) -> tuple[str, int]:
        while parent.setdefault(node, node) != node:
            parent[node] = parent[parent[node]]
            node = parent[node]
        return node

    for predicted_index, gold_index in cost:
        parent[root(("p", predicted_index))] = root(("g", gold_index))
    groups: dict[tuple[str, int], tuple[set[int], set[int]]] = {}
    for predicted_index, gold_index in cost:
        rows, columns = groups.setdefault(root(("p", predicted_index)), (set(), set()))
        rows.add(predicted_index)
        columns.add(gold_index)

    pairs = []
    for rows, columns in groups.values():
        row_ids, column_ids = sorted(rows), sorted(columns)
        flipped = len(row_ids) > len(column_ids)
        if flipped:
            row_ids, column_ids = column_ids, row_ids
        # A missing candidate costs more than every real pairing of the group together,
        # so the cheapest assignment first uses as many real candidates as possible.
        missing = (2 * tolerance_ms + 1) * (len(row_ids) + 1)
        matrix = [
            [cost.get((column, row) if flipped else (row, column), missing) for column in column_ids]
            for row in row_ids
        ]
        for row_position, column_position in enumerate(_assign(matrix)):
            row, column = row_ids[row_position], column_ids[column_position]
            pair = (column, row) if flipped else (row, column)
            if pair in cost:
                pairs.append(pair)
    return sorted(pairs)


def _assign(matrix: list[list[int]]) -> list[int]:
    """Minimum-cost assignment of every row to a distinct column (Hungarian algorithm
    with potentials). Needs len(rows) <= len(columns). Returns the column of each row."""
    row_count, column_count = len(matrix), len(matrix[0])
    infinity = float("inf")
    row_potential = [0] * (row_count + 1)
    column_potential = [0] * (column_count + 1)
    # Index 0 is a sentinel; rows and columns are numbered from 1 inside this function.
    row_of_column = [0] * (column_count + 1)
    previous_column = [0] * (column_count + 1)
    for row in range(1, row_count + 1):
        row_of_column[0] = row
        column = 0
        slack = [infinity] * (column_count + 1)
        visited = [False] * (column_count + 1)
        while True:
            visited[column] = True
            current_row = row_of_column[column]
            delta = infinity
            next_column = 0
            for candidate in range(1, column_count + 1):
                if visited[candidate]:
                    continue
                reduced = (matrix[current_row - 1][candidate - 1]
                           - row_potential[current_row] - column_potential[candidate])
                if reduced < slack[candidate]:
                    slack[candidate] = reduced
                    previous_column[candidate] = column
                if slack[candidate] < delta:
                    delta = slack[candidate]
                    next_column = candidate
            for candidate in range(column_count + 1):
                if visited[candidate]:
                    row_potential[row_of_column[candidate]] += delta
                    column_potential[candidate] -= delta
                else:
                    slack[candidate] -= delta
            column = next_column
            if row_of_column[column] == 0:
                break
        while column:
            row_of_column[column] = row_of_column[previous_column[column]]
            column = previous_column[column]
    assignment = [0] * row_count
    for column in range(1, column_count + 1):
        if row_of_column[column]:
            assignment[row_of_column[column] - 1] = column - 1
    return assignment
