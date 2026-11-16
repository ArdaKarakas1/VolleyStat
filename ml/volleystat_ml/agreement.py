"""Agreement between two annotators who labelled the same videos independently.

This is the measurement of experiment B0 (docs/AI_PLAN.md, sections 3 and 4.3). It
reports what the two label files say and nothing else: it fills no missing label,
guesses no winner and has no pass mark. Whether a level of agreement is enough is the
owner's decision, so no function here classifies a number.

Rallies are paired with the matcher of `metrics.evaluate_boundaries`: a pair needs its
start AND its end each within the tolerance, and the pairing is one-to-one. Annotator A
takes the place of the gold side and annotator B the place of the predicted side. The
matcher has no preferred side for the size of the pairing, and every difference below
is reported as an absolute value, so the roles only name the columns.

For each categorical field (`winning_side`, `serving_side`) only the matched pairs where
both annotators wrote a value are compared. Values are compared as written, so UNKNOWN
is a value like HOME. A pair where one annotator left the field empty is counted as
"not comparable" and takes no part in the agreement rate or in kappa.

Cohen's kappa:

    p_o = agreeing pairs / compared pairs
    p_e = sum over values v of (share of A's answers that are v) * (share of B's that are v)
    kappa = (p_o - p_e) / (1 - p_e)

Kappa is undefined when p_e = 1, which happens only when both annotators gave one and the
same value to every compared pair. It is then None with a reason, never 0 or 1.

With fewer than MIN_RELIABLE compared pairs a ratio is misleading. The result then carries
`percentages_reliable = False` and `to_dict()` writes null for the agreement rate and for
kappa, so a record built from it can only quote the counts ("18/20 agree").
"""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from typing import Any, Iterable, Mapping, NamedTuple, Sequence

from . import metrics

DEFAULT_TOLERANCE_MS = metrics.DEFAULT_TOLERANCE_MS
# The same sample-size rule as `evaluate` (docs/AI_PLAN.md section 3, rule 3).
MIN_RELIABLE = metrics.MIN_RELIABLE_GOLD

FIELDS = ("winning_side", "serving_side")
# The kind written to the disagreement list for a field.
FIELD_KINDS = {"winning_side": "WINNER", "serving_side": "SERVING"}
KINDS = ("ONLY_A", "ONLY_B", "WINNER", "SERVING")
DISAGREEMENT_COLUMNS = ("video_id", "kind", "a_start_ms", "a_end_ms", "b_start_ms", "b_end_ms", "a_value", "b_value")
# The observation status that says the rally's winner was not labelled. The schema makes
# winningSide required, so an annotator who did not label the winner still writes a value
# (UNKNOWN). Comparing that placeholder would count a label nobody gave.
NOT_ANNOTATED = "NOT_ANNOTATED"

_JSON_NAMES = {"winning_side": "winningSide", "serving_side": "servingSide"}


@dataclass(frozen=True)
class Rally:
    start_ms: int
    end_ms: int
    # None or a blank string: the annotator left the field empty.
    winning_side: str | None = None
    serving_side: str | None = None
    observation_status: str | None = None

    def value(self, field: str) -> str | None:
        """The value of a categorical field as the annotator gave it, or None when the
        annotator gave none."""
        if field == "winning_side" and self.observation_status == NOT_ANNOTATED:
            return None
        value = getattr(self, field)
        if value is None or not str(value).strip():
            return None
        # Only surrounding space is removed; case and spelling are compared as written.
        return str(value).strip()


@dataclass(frozen=True)
class FieldAgreement:
    field: str
    # (A's value, B's value) of every matched pair where both gave a value.
    pairs: tuple[tuple[str, str], ...]
    # Matched pairs where exactly one annotator left the field empty.
    not_comparable: int
    # Matched pairs where both left it empty.
    both_empty: int

    @property
    def compared(self) -> int:
        return len(self.pairs)

    @property
    def agree(self) -> int:
        return sum(a == b for a, b in self.pairs)

    @property
    def percentages_reliable(self) -> bool:
        return self.compared >= MIN_RELIABLE

    def _chance_sum(self) -> int:
        # n^2 * p_e, kept in whole numbers so that "p_e is 1" is an exact test and not a
        # comparison of floats.
        a_counts = Counter(a for a, _ in self.pairs)
        b_counts = Counter(b for _, b in self.pairs)
        return sum(count * b_counts[value] for value, count in a_counts.items())

    @property
    def observed(self) -> float | None:
        return self.agree / self.compared if self.compared else None

    @property
    def expected(self) -> float | None:
        return self._chance_sum() / self.compared ** 2 if self.compared else None

    @property
    def kappa(self) -> float | None:
        if self.kappa_undefined_reason is not None:
            return None
        n, chance = self.compared, self._chance_sum()
        # (p_o - p_e) / (1 - p_e) with both terms multiplied by n^2.
        return (n * self.agree - chance) / (n * n - chance)

    @property
    def kappa_undefined_reason(self) -> str | None:
        if not self.compared:
            return "no matched pair has this field from both annotators"
        if self._chance_sum() == self.compared ** 2:
            value = self.pairs[0][0]
            return (f"both annotators gave the single value {value} to every compared pair, "
                    "so the expected agreement is 1 and kappa divides by zero")
        return None

    def to_dict(self) -> dict[str, Any]:
        """Below MIN_RELIABLE compared pairs the agreement rate and kappa are null and only
        the counts are given, like the ratios of BoundaryEvaluation.to_dict()."""
        reliable = self.percentages_reliable
        return {
            "compared": self.compared,
            "agree": self.agree,
            "notComparable": self.not_comparable,
            "bothEmpty": self.both_empty,
            "agreementRate": self.observed if reliable else None,
            "kappa": self.kappa if reliable else None,
            # Given whenever kappa can not be computed at all, whatever the sample size,
            # so that a null kappa is never read as "no agreement".
            "kappaUndefinedReason": self.kappa_undefined_reason,
            "percentagesReliable": reliable,
            "minReliable": MIN_RELIABLE,
        }


class Disagreement(NamedTuple):
    kind: str
    a: Rally | None
    b: Rally | None
    a_value: str | None
    b_value: str | None


@dataclass(frozen=True)
class Agreement:
    # A is the gold side, B the predicted side: tp = matched pairs, fn = only in A,
    # fp = only in B. The signed errors are B minus A.
    boundaries: metrics.BoundaryEvaluation
    fields: Mapping[str, FieldAgreement]
    # In time order within one video. After pool() the list only keeps its length.
    disagreements: tuple[Disagreement, ...]

    @property
    def a_count(self) -> int:
        return self.boundaries.gold_count

    @property
    def b_count(self) -> int:
        return self.boundaries.predicted_count

    @property
    def matched(self) -> int:
        return self.boundaries.tp

    @property
    def only_a(self) -> int:
        return self.boundaries.fn

    @property
    def only_b(self) -> int:
        return self.boundaries.fp

    def to_dict(self) -> dict[str, Any]:
        boundaries = self.boundaries
        return {
            "toleranceMs": boundaries.tolerance_ms,
            "rallies": {"a": self.a_count, "b": self.b_count, "matched": self.matched,
                        "onlyA": self.only_a, "onlyB": self.only_b},
            "boundaries": {
                "medianAbsStartDiffMs": boundaries.median_abs_start_error_ms,
                "p90AbsStartDiffMs": boundaries.p90_abs_start_error_ms,
                "medianAbsEndDiffMs": boundaries.median_abs_end_error_ms,
                "p90AbsEndDiffMs": boundaries.p90_abs_end_error_ms,
                "meanIou": boundaries.mean_iou,
            },
            "fields": {_JSON_NAMES[name]: field.to_dict() for name, field in self.fields.items()},
            "disagreements": len(self.disagreements),
        }


def compare(a: Sequence[Rally], b: Sequence[Rally], tolerance_ms: int = DEFAULT_TOLERANCE_MS) -> Agreement:
    """Compare the labels of ONE video. Never mix videos in one call, for the same reason
    as in metrics.evaluate_boundaries: a rally could pair with a rally of another video."""
    boundaries = metrics.evaluate_boundaries(
        [(rally.start_ms, rally.end_ms) for rally in a], [(rally.start_ms, rally.end_ms) for rally in b], tolerance_ms)
    pairs = sorted((match.gold_index, match.predicted_index) for match in boundaries.matches)
    paired_a = {a_index for a_index, _ in pairs}
    paired_b = {b_index for _, b_index in pairs}

    # Each item is (time, kind order, disagreement); time is the earlier start of the row.
    found: list[tuple[int, int, Disagreement]] = []
    for index, rally in enumerate(a):
        if index not in paired_a:
            found.append((rally.start_ms, 0, Disagreement("ONLY_A", rally, None, None, None)))
    for index, rally in enumerate(b):
        if index not in paired_b:
            found.append((rally.start_ms, 1, Disagreement("ONLY_B", None, rally, None, None)))

    fields = {}
    for name in FIELDS:
        compared, not_comparable, both_empty = [], 0, 0
        for a_index, b_index in pairs:
            a_value, b_value = a[a_index].value(name), b[b_index].value(name)
            if a_value is None and b_value is None:
                both_empty += 1
            elif a_value is None or b_value is None:
                not_comparable += 1
            else:
                compared.append((a_value, b_value))
                if a_value != b_value:
                    kind = FIELD_KINDS[name]
                    found.append((min(a[a_index].start_ms, b[b_index].start_ms), KINDS.index(kind),
                                  Disagreement(kind, a[a_index], b[b_index], a_value, b_value)))
        fields[name] = FieldAgreement(name, tuple(compared), not_comparable, both_empty)

    found.sort(key=lambda item: (item[0], item[1]))
    return Agreement(boundaries, fields, tuple(item[2] for item in found))


def pool(agreements: Iterable[Agreement]) -> Agreement:
    """Combine per-video results. Counts are summed and the boundary differences are taken
    over all matched pairs. Kappa is computed again over all compared pairs: the kappa of
    a pool is not an average of per-video kappas."""
    agreements = list(agreements)
    if not agreements:
        raise ValueError("pool() needs at least one agreement")
    fields = {
        name: FieldAgreement(
            name,
            tuple(pair for item in agreements for pair in item.fields[name].pairs),
            sum(item.fields[name].not_comparable for item in agreements),
            sum(item.fields[name].both_empty for item in agreements),
        )
        for name in FIELDS
    }
    return Agreement(
        metrics.pool(item.boundaries for item in agreements),
        fields,
        tuple(disagreement for item in agreements for disagreement in item.disagreements),
    )


def disagreement_rows(per_video: Mapping[str, Agreement]) -> list[dict[str, Any]]:
    """The list for the person who adjudicates: one row per disagreement, ordered by video
    id and, within a video, by time. Keys are DISAGREEMENT_COLUMNS; a side or value that
    does not exist is None."""
    rows = []
    for video_id in sorted(per_video):
        for item in per_video[video_id].disagreements:
            rows.append({
                "video_id": video_id,
                "kind": item.kind,
                "a_start_ms": item.a.start_ms if item.a else None,
                "a_end_ms": item.a.end_ms if item.a else None,
                "b_start_ms": item.b.start_ms if item.b else None,
                "b_end_ms": item.b.end_ms if item.b else None,
                "a_value": item.a_value,
                "b_value": item.b_value,
            })
    return rows
