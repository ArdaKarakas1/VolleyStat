"""Measurement of a rally winner component against gold labels.

No winner component exists yet. This module only measures one: it reads proposals
(HOME, AWAY or UNKNOWN, with or without a confidence) and gold rallies, and reports
what the two files say. It trains nothing and decides nothing.

Definitions of the handover package (05_DENEY_METRIKLERI_KABUL_KAPILARI.md, section 3):

    eligibleN            gold rallies that have a definite winner
    autoN                proposals other than UNKNOWN (counted on eligible rallies)
    selective_precision  correct automatic decisions / autoN
    auto_coverage        autoN / eligibleN

Proposals are paired with gold rallies by the matcher of `metrics.evaluate_boundaries`
(start AND end within the tolerance, one-to-one). Every eligible gold rally then has
exactly one outcome:

    CORRECT        paired, the proposed side is the gold winner
    WRONG          paired, the proposed side is the other one
    UNKNOWN        paired, the component abstained
    NOT_PROPOSED   no proposal pairs with it

so that coverage + UNKNOWN rate + not-proposed rate = 1.

A gold rally without a definite winner takes part in no ratio. It is excluded with the
first reason of EXCLUSIONS that applies, and the exclusions are counted by reason.
Eligible rallies are in one of two layers: GOLD (the label is DOUBLE_AGREED or
ADJUDICATED) or SINGLE (one annotator, or no status given). The G3 targets are read on
the GOLD layer only (docs/AI_PLAN.md 4.3).

Two kinds of automatic decision are counted and never scored: one on a gold rally that
is not eligible, and one that pairs with no gold rally. The second kind is split three
ways, because they are different faults: a second proposal for a gold rally that another
proposal already pairs with, a boundary off by more than the tolerance, and a decision
where the gold has no rally. `lower_bound_precision` shows what the precision would be if
every unpaired decision of the layer's videos were a wrong one. The proposals are paired
in an order taken from their contents, so the rows of a file can be shuffled without
changing a number. Proposals nearest to one gold rally that name different winners are
refused (ContradictingProposals): the component made no single decision on that rally.

With fewer than MIN_RELIABLE in its own denominator a ratio is misleading. `to_dict()`
then writes null for it and keeps the counts and the interval, so a record built from it
can only quote "18/20". Intervals are Wilson score intervals: near the targets the
expected result is "all correct", where a percentile bootstrap has zero width.

Calibration reads the automatic decisions on eligible rallies that carry a confidence.
The confidence is read as the probability that the proposed side is right.

    Brier = mean((confidence - y)^2)                       y = 1 for CORRECT, 0 for WRONG
    ECE   = sum over bins of (n_bin / N) * |share correct in bin - mean confidence in bin|

over BINS equal-width bins [i/10, (i+1)/10), with 1.0 in the last one. A binned ECE reads
high in a small sample even for a perfectly calibrated component, so it is reported next
to `calibrated_ece_reference`: the ECE such a component would show with these same
confidences at this sample size.

Nothing here prints PASS or FAIL. `g3()` compares the point estimates and the intervals
with the targets; the gate decision is made and signed by a person.
"""
from __future__ import annotations

import math
from collections import Counter
from dataclasses import dataclass, field
from fractions import Fraction
from statistics import NormalDist
from typing import Any, Mapping, NamedTuple, Sequence

import numpy as np

from . import agreement, metrics

DEFAULT_TOLERANCE_MS = metrics.DEFAULT_TOLERANCE_MS
# The same sample-size rule as `evaluate` and `agreement`, applied to each ratio's own denominator.
MIN_RELIABLE = metrics.MIN_RELIABLE_GOLD
# Targets of gate G3. They are goals chosen by the owner, not measurements.
G3_MIN_PRECISION = 0.95
G3_MIN_COVERAGE = 0.60
DEFAULT_HIGH_CONFIDENCE = 0.90
INTERVAL_CONFIDENCE = 0.95
BINS = 10

SIDES = ("HOME", "AWAY")
UNKNOWN = "UNKNOWN"
LAYERS = ("GOLD", "SINGLE")
GOLD_LAYER_STATUSES = ("DOUBLE_AGREED", "ADJUDICATED")
# In the order in which they are tested.
EXCLUSIONS = (
    "NOT_ANNOTATED", "NEEDS_MORE_EVIDENCE", "UNRESOLVABLE", "REVIEW_DISPUTED", "REVIEW_UNRESOLVABLE",
    "WINNER_UNKNOWN", "OBSERVATION_UNKNOWN", "OBSERVATION_NOT_APPLICABLE",
)
SCORED = ("CORRECT", "WRONG", "UNKNOWN", "NOT_PROPOSED")
UNPAIRED = ("SECOND_PROPOSAL", "OVERLAPS_GOLD", "NO_GOLD_THERE")
OBSERVATIONS = ("OBSERVED", "INFERRED", "NOT_GIVEN")
PROPOSED = ("HOME", "AWAY", "UNKNOWN", "NOT_PROPOSED")
# The rows of the failure list: everything except a correct decision.
FAILURE_KINDS = ("WRONG", "UNKNOWN", "NOT_PROPOSED", "AUTO_ON_INELIGIBLE_GOLD", "AUTO_UNPAIRED")
FAILURE_COLUMNS = ("video_id", "kind", "layer", "gold_start_ms", "gold_end_ms", "pred_start_ms", "pred_end_ms",
                   "gold_winner", "proposed_winner", "confidence", "reason")

NO_CONFIDENCE = "no automatic decision carries a confidence"
NO_PROPOSAL = "the evaluated videos carry no HOME or AWAY proposal, so nothing about a winner component was measured"
# The hard cases of the double-annotated set: an adjudication that could not settle the winner.
HARD_CASES = ("UNRESOLVABLE", "NEEDS_MORE_EVIDENCE")


@dataclass(frozen=True)
class Proposal:
    start_ms: int
    end_ms: int
    # HOME, AWAY or UNKNOWN. UNKNOWN is the component's own abstention.
    proposed_winner: str
    # The component's probability that the proposed side is right, or None when it gives none.
    confidence: float | None = None

    def __post_init__(self) -> None:
        if self.proposed_winner not in (*SIDES, UNKNOWN):
            raise ValueError(f"proposed_winner must be HOME, AWAY or UNKNOWN, found {self.proposed_winner!r}")
        # The range is tested first: Python compares an integer of any size exactly, while
        # math.isfinite would overflow on one. NaN and the infinities fail the range test.
        if self.confidence is not None and not (
                isinstance(self.confidence, (int, float)) and not isinstance(self.confidence, bool)
                and 0 <= self.confidence <= 1):
            raise ValueError(f"confidence must be a number from 0 to 1 or None, found {self.confidence!r}")

    @property
    def automatic(self) -> bool:
        return self.proposed_winner in SIDES


def _given(value: str | None) -> str | None:
    return str(value).strip() or None if value is not None else None


def exclusion(rally: agreement.Rally) -> str | None:
    """Why this gold rally has no definite winner, or None when it is eligible."""
    side = rally.value("winning_side")
    if side is None:
        return "NOT_ANNOTATED"
    if side not in (*SIDES, UNKNOWN):
        raise ValueError(f"winning_side must be HOME, AWAY or UNKNOWN, found {side!r}")
    status = _given(rally.ground_truth_status)
    if status in ("NEEDS_MORE_EVIDENCE", "UNRESOLVABLE"):
        return status
    review = _given(rally.review_status)
    if review in ("DISPUTED", "UNRESOLVABLE"):
        return f"REVIEW_{review}"
    if side == UNKNOWN:
        return "WINNER_UNKNOWN"
    observation = _given(rally.observation_status)
    if observation in ("UNKNOWN", "NOT_APPLICABLE"):
        return f"OBSERVATION_{observation}"
    return None


def hard_case(rally: agreement.Rally) -> str | None:
    """UNRESOLVABLE or NEEDS_MORE_EVIDENCE when the label carries that status, else None.
    Read from the status alone: such a label often leaves the winner empty, and exclusion()
    then gives NOT_ANNOTATED as the first reason."""
    status = _given(rally.ground_truth_status)
    return status if status in HARD_CASES else None


def layer_of(rally: agreement.Rally) -> str:
    """The layer of an eligible rally. A label without a status is never taken for a double-checked one."""
    return "GOLD" if _given(rally.ground_truth_status) in GOLD_LAYER_STATUSES else "SINGLE"


class Outcome(NamedTuple):
    # One of SCORED, or AUTO_ON_INELIGIBLE_GOLD, or AUTO_UNPAIRED.
    kind: str
    # The layer of the eligible gold rally; None for the two kinds that are not scored.
    layer: str | None
    gold: agreement.Rally | None
    proposal: Proposal | None
    # The exclusion reason (AUTO_ON_INELIGIBLE_GOLD) or one of UNPAIRED (AUTO_UNPAIRED).
    reason: str | None


@dataclass(frozen=True)
class VideoEvaluation:
    # In time order: by the earlier start of the gold rally and the proposal.
    outcomes: tuple[Outcome, ...]
    # Every gold rally without a definite winner, by reason.
    excluded: Mapping[str, int]
    gold_rallies: int
    predicted_rallies: int
    automatic_proposals: int
    # Gold rallies whose status is one of HARD_CASES, whatever their exclusion reason.
    hard_cases: Mapping[str, int] = field(default_factory=dict)


class ContradictingProposals(ValueError):
    """The proposals nearest to one gold rally name different winners."""


def _within(proposal: Proposal, rally: agreement.Rally, tolerance_ms: int) -> bool:
    """The pairing test of metrics: both ends within the tolerance."""
    return abs(proposal.start_ms - rally.start_ms) <= tolerance_ms and abs(proposal.end_ms - rally.end_ms) <= tolerance_ms


def _cost(proposal: Proposal, rally: agreement.Rally) -> int:
    """The pairing cost of metrics: the start error plus the end error."""
    return abs(proposal.start_ms - rally.start_ms) + abs(proposal.end_ms - rally.end_ms)


def _in_content_order(proposal: Proposal) -> tuple[Any, ...]:
    """The order in which the proposals are paired. The pairing breaks a cost tie by
    position, so the position is taken from the contents and never from the file: at a tie
    the more confident proposal is scored, and a confident wrong decision can not hide."""
    return (proposal.start_ms, proposal.end_ms, proposal.proposed_winner, proposal.confidence is None,
            -(proposal.confidence or 0.0))


def _refuse_contradictions(gold: Sequence[agreement.Rally], proposals: Sequence[Proposal], tolerance_ms: int) -> None:
    """Each proposal belongs to the gold rallies it is nearest to within the tolerance. The
    proposals that belong to one gold rally must name one winner."""
    nearest = []
    for proposal in proposals:
        costs = [_cost(proposal, rally) for rally in gold if _within(proposal, rally, tolerance_ms)]
        nearest.append(min(costs) if costs else None)
    for rally in gold:
        named = {proposal.proposed_winner for proposal, best in zip(proposals, nearest)
                 if best is not None and _within(proposal, rally, tolerance_ms) and _cost(proposal, rally) == best}
        # Two values out of HOME, AWAY and UNKNOWN always include a side, so two abstentions
        # never contradict each other.
        if len(named) > 1:
            raise ContradictingProposals(
                f"proposals within +/-{tolerance_ms} ms of the gold rally {rally.start_ms}-{rally.end_ms} ms name "
                f"different winners ({', '.join(sorted(named))}); the component made no single decision on that rally")


def evaluate_video(gold: Sequence[agreement.Rally], proposals: Sequence[Proposal],
                   tolerance_ms: int = DEFAULT_TOLERANCE_MS) -> VideoEvaluation:
    """Score the proposals of ONE video. Never mix videos in one call: a proposal could
    then pair with a rally of another video. Combine videos with pool().

    The result depends on the contents of the proposals and never on their order: the
    proposals are paired in _in_content_order. Raises ContradictingProposals when the
    proposals nearest to one gold rally within the tolerance name different winners (HOME
    and AWAY, or a side and UNKNOWN): the component made no single decision on that rally.
    A proposal nearest to a neighbouring rally belongs to that rally."""
    proposals = sorted(proposals, key=_in_content_order)
    _refuse_contradictions(gold, proposals, tolerance_ms)
    boundaries = metrics.evaluate_boundaries(
        [(rally.start_ms, rally.end_ms) for rally in gold],
        [(proposal.start_ms, proposal.end_ms) for proposal in proposals], tolerance_ms)
    proposal_of = {match.gold_index: match.predicted_index for match in boundaries.matches}
    paired = set(proposal_of.values())

    excluded: Counter[str] = Counter()
    # Each item is (time, order within one moment, outcome).
    found: list[tuple[int, int, Outcome]] = []
    for index, rally in enumerate(gold):
        proposal = proposals[proposal_of[index]] if index in proposal_of else None
        time = rally.start_ms if proposal is None else min(rally.start_ms, proposal.start_ms)
        reason = exclusion(rally)
        if reason is not None:
            excluded[reason] += 1
            if proposal is not None and proposal.automatic:
                found.append((time, 0, Outcome("AUTO_ON_INELIGIBLE_GOLD", None, rally, proposal, reason)))
            continue
        if proposal is None:
            kind = "NOT_PROPOSED"
        elif not proposal.automatic:
            kind = "UNKNOWN"
        else:
            kind = "CORRECT" if proposal.proposed_winner == rally.value("winning_side") else "WRONG"
        found.append((time, 0, Outcome(kind, layer_of(rally), rally, proposal, None)))
    for index, proposal in enumerate(proposals):
        if index in paired or not proposal.automatic:
            continue
        # The pairing has the largest possible number of pairs, so a decision within the
        # tolerance of a gold rally stays unpaired only when another proposal took that rally.
        if any(_within(proposal, rally, tolerance_ms) for rally in gold):
            reason = "SECOND_PROPOSAL"
        elif any(proposal.start_ms < rally.end_ms and rally.start_ms < proposal.end_ms for rally in gold):
            reason = "OVERLAPS_GOLD"
        else:
            reason = "NO_GOLD_THERE"
        found.append((proposal.start_ms, 1, Outcome("AUTO_UNPAIRED", None, None, proposal, reason)))

    found.sort(key=lambda item: (item[0], item[1]))
    return VideoEvaluation(
        outcomes=tuple(item[2] for item in found),
        excluded={reason: excluded[reason] for reason in EXCLUSIONS if excluded[reason]},
        gold_rallies=len(gold),
        predicted_rallies=len(proposals),
        automatic_proposals=sum(proposal.automatic for proposal in proposals),
        hard_cases={status: count for status in HARD_CASES
                    if (count := sum(hard_case(rally) == status for rally in gold))},
    )


class Interval(NamedTuple):
    low: float
    high: float
    confidence: float


def wilson_interval(successes: int, total: int, confidence: float = INTERVAL_CONFIDENCE) -> Interval:
    """Wilson score interval of the share successes / total.

    It treats the items as independent draws. Rallies of one match are not, so an
    interval for new matches is wider than this one.
    """
    if total <= 0 or not 0 <= successes <= total:
        raise ValueError(f"{successes} of {total} is not a share")
    z = NormalDist().inv_cdf(1 - (1 - confidence) / 2)
    share = successes / total
    scale = 1 + z * z / total
    centre = (share + z * z / (2 * total)) / scale
    half = z * math.sqrt(share * (1 - share) / total + z * z / (4 * total * total)) / scale
    # At the two ends the closed form lands a rounding error beside 0 and 1.
    low = 0.0 if successes == 0 else max(0.0, centre - half)
    high = 1.0 if successes == total else min(1.0, centre + half)
    return Interval(min(low, share), max(high, share), confidence)


@dataclass(frozen=True)
class Ratio:
    numerator: int
    denominator: int

    @property
    def value(self) -> float | None:
        """None when the denominator is zero. A ratio that can not be computed is unknown, not 0."""
        return self.numerator / self.denominator if self.denominator else None

    @property
    def reliable(self) -> bool:
        return self.denominator >= MIN_RELIABLE

    @property
    def interval(self) -> Interval | None:
        return wilson_interval(self.numerator, self.denominator) if self.denominator else None

    def to_dict(self, measured: bool = True) -> dict[str, Any]:
        """The form written to a report. Below MIN_RELIABLE the value is null and the counts
        and the interval stay. `measured=False` (no component took part) leaves only the counts."""
        interval = self.interval if measured else None
        return {
            "value": self.value if measured and self.reliable else None,
            "numerator": self.numerator,
            "denominator": self.denominator,
            "reliable": measured and self.reliable,
            "interval": None if interval is None else {
                "low": interval.low, "high": interval.high, "confidence": interval.confidence,
                "method": "wilson", "unit": "rally"},
        }


def bin_index(confidence: float) -> int:
    """The bin of a confidence: [i/10, (i+1)/10), and 1.0 in the last bin. The product is
    rounded first, so a value written as 0.3 is not pushed over or under its edge by floating point."""
    return min(BINS - 1, math.floor(round(confidence * BINS, 9)))


class Bin(NamedTuple):
    low: float
    high: float
    n: int
    mean_confidence: float
    correct: int


class EceReference(NamedTuple):
    mean: float
    p95: float


def _ece(confidences: np.ndarray, outcomes: np.ndarray, indices: np.ndarray) -> float:
    total = 0.0
    for index in np.unique(indices):
        members = indices == index
        total += members.sum() * abs(outcomes[members].mean() - confidences[members].mean())
    return float(total / len(confidences))


def calibrated_ece_reference(confidences: Sequence[float], *, resamples: int = 2000, seed: int = 0) -> EceReference:
    """The ECE a perfectly calibrated component shows with these confidences at this size.

    Each resample draws every outcome as right with exactly its stated probability. The
    binned ECE of such a component is above zero in a finite sample; a measured ECE at or
    below the 95th percentile given here can not be told apart from calibrated.
    """
    values = np.asarray(confidences, dtype=float)
    if values.size == 0:
        raise ValueError("calibrated_ece_reference needs at least one confidence")
    if resamples < 1:
        raise ValueError("resamples must be at least 1")
    indices = np.array([bin_index(value) for value in values])
    generator = np.random.default_rng(seed)
    drawn = np.array([
        _ece(values, (generator.random(values.size) < values).astype(float), indices) for _ in range(resamples)
    ])
    return EceReference(float(drawn.mean()), float(np.percentile(drawn, 95)))


@dataclass(frozen=True)
class Calibration:
    # (confidence, 1 for CORRECT or 0 for WRONG) of every automatic decision on an eligible
    # rally that carries a confidence.
    pairs: tuple[tuple[float, int], ...]
    # Automatic decisions on eligible rallies that carry none, and the wrong ones among them.
    without_confidence: int
    wrong_without_confidence: int

    @property
    def n(self) -> int:
        return len(self.pairs)

    @property
    def reliable(self) -> bool:
        return self.n >= MIN_RELIABLE

    @property
    def brier(self) -> float | None:
        return float(np.mean([(confidence - right) ** 2 for confidence, right in self.pairs])) if self.pairs else None

    @property
    def ece(self) -> float | None:
        if not self.pairs:
            return None
        return float(sum(row.n * abs(row.correct / row.n - row.mean_confidence) for row in self.bins) / self.n)

    @property
    def bins(self) -> tuple[Bin, ...]:
        """The reliability table: one row for every bin that holds a decision."""
        members: dict[int, list[tuple[float, int]]] = {}
        for confidence, right in self.pairs:
            members.setdefault(bin_index(confidence), []).append((confidence, right))
        return tuple(
            Bin(index / BINS, (index + 1) / BINS, len(rows),
                float(np.mean([confidence for confidence, _ in rows])), sum(right for _, right in rows))
            for index, rows in sorted(members.items())
        )

    def high_confidence(self, level: float) -> tuple[int, int] | None:
        """(decisions with a confidence at or above the level, wrong ones among them).
        None when no decision carries a confidence: that count is unknown, not zero."""
        if not self.pairs:
            return None
        confident = [right for confidence, right in self.pairs if confidence >= level]
        return len(confident), len(confident) - sum(confident)

    def to_dict(self, level: float = DEFAULT_HIGH_CONFIDENCE, *, resamples: int = 2000, seed: int = 0) -> dict[str, Any]:
        """Brier, ECE and the reference are null below MIN_RELIABLE decisions with a
        confidence. The table of counts is always given."""
        quoted = self.reliable
        brier = None
        reference = None
        if quoted:
            interval = metrics.bootstrap_ci(
                np.array([(confidence - right) ** 2 for confidence, right in self.pairs]), resamples=resamples, seed=seed)
            brier = {"value": self.brier, "interval": {
                "low": interval.low, "high": interval.high, "confidence": interval.confidence,
                "resamples": interval.resamples, "degenerate": interval.degenerate, "seed": seed,
                "method": "bootstrap", "unit": "decision"}}
            drawn = calibrated_ece_reference([confidence for confidence, _ in self.pairs], resamples=resamples, seed=seed)
            reference = {"mean": drawn.mean, "p95": drawn.p95, "resamples": resamples, "seed": seed}
        high = self.high_confidence(level)
        return {
            "n": self.n,
            "reliable": quoted,
            "minReliable": MIN_RELIABLE,
            "brier": brier,
            "ece": self.ece if quoted else None,
            "eceIfCalibrated": reference,
            "binCount": BINS,
            "bins": [{"low": row.low, "high": row.high, "n": row.n, "meanConfidence": row.mean_confidence,
                      "correct": row.correct} for row in self.bins],
            "withoutConfidence": self.without_confidence,
            "wrongWithoutConfidence": self.wrong_without_confidence,
            "highConfidence": {
                "level": level,
                "n": None if high is None else high[0],
                "wrong": None if high is None else high[1],
                "notMeasuredReason": NO_CONFIDENCE if high is None else None,
            },
        }


@dataclass(frozen=True)
class LayerEvaluation:
    name: str
    correct: int
    wrong: int
    unknown: int
    not_proposed: int
    # Automatic decisions that pair with no gold rally, in the videos that have at least
    # one eligible rally of this layer.
    unpaired_auto: int
    # Videos with at least one eligible rally of this layer.
    videos: int
    # (gold side, what was proposed) -> eligible rallies. What was proposed is one of PROPOSED.
    confusion: Mapping[tuple[str, str], int]
    # How the gold winner is known -> (eligible, automatic decisions, correct ones).
    by_observation: Mapping[str, tuple[int, int, int]]
    # Eligible rallies whose winner evidence is RULE_CONSTRAINT, among the `evidence_given`
    # eligible rallies that state an evidence at all. None when none states one: unknown,
    # not zero. A missing column and an empty cell both state nothing.
    rule_constraint: int | None
    evidence_given: int
    calibration: Calibration

    @property
    def eligible(self) -> int:
        return self.correct + self.wrong + self.unknown + self.not_proposed

    @property
    def auto(self) -> int:
        return self.correct + self.wrong

    @property
    def precision(self) -> Ratio:
        return Ratio(self.correct, self.auto)

    @property
    def coverage(self) -> Ratio:
        return Ratio(self.auto, self.eligible)

    @property
    def unknown_rate(self) -> Ratio:
        return Ratio(self.unknown, self.eligible)

    @property
    def not_proposed_rate(self) -> Ratio:
        return Ratio(self.not_proposed, self.eligible)

    @property
    def lower_bound_precision(self) -> Ratio:
        """The precision if every unpaired automatic decision of these videos were a wrong
        one. A bound for reading next to the precision, never a replacement for it."""
        return Ratio(self.correct, self.auto + self.unpaired_auto)

    def counts(self) -> dict[str, int]:
        return {"eligibleN": self.eligible, "autoN": self.auto, "correct": self.correct, "wrong": self.wrong,
                "unknown": self.unknown, "notProposed": self.not_proposed}

    def to_dict(self, measured: bool = True, level: float = DEFAULT_HIGH_CONFIDENCE, *,
                resamples: int = 2000, seed: int = 0) -> dict[str, Any]:
        return {
            **self.counts(),
            "videos": self.videos,
            "selectivePrecision": self.precision.to_dict(measured),
            "autoCoverage": self.coverage.to_dict(measured),
            "unknownRate": self.unknown_rate.to_dict(measured),
            "notProposedRate": self.not_proposed_rate.to_dict(measured),
            "unpairedAutomatic": self.unpaired_auto,
            "lowerBoundPrecision": self.lower_bound_precision.to_dict(measured),
            "confusion": {side: {proposed: self.confusion[side, proposed] for proposed in PROPOSED} for side in SIDES},
            "byObservationStatus": {
                name: {"eligibleN": eligible, "autoN": auto, "correct": correct}
                for name, (eligible, auto, correct) in self.by_observation.items()},
            "ruleConstraintEvidence": self.rule_constraint,
            "evidenceGiven": self.evidence_given,
            "calibration": self.calibration.to_dict(level, resamples=resamples, seed=seed),
        }


def _layer(name: str, per_video: Mapping[str, VideoEvaluation]) -> LayerEvaluation:
    outcomes: list[Outcome] = []
    unpaired = videos = 0
    for video in per_video.values():
        own = [outcome for outcome in video.outcomes if outcome.layer == name]
        if own:
            videos += 1
            unpaired += sum(outcome.kind == "AUTO_UNPAIRED" for outcome in video.outcomes)
            outcomes += own
    kinds = Counter(outcome.kind for outcome in outcomes)

    confusion = {(side, proposed): 0 for side in SIDES for proposed in PROPOSED}
    observed = {observation: [0, 0, 0] for observation in OBSERVATIONS}
    pairs: list[tuple[float, int]] = []
    without_confidence = wrong_without_confidence = 0
    for outcome in outcomes:
        rally, proposal = outcome.gold, outcome.proposal
        proposed = "NOT_PROPOSED" if proposal is None else proposal.proposed_winner
        confusion[rally.value("winning_side"), proposed] += 1
        automatic = outcome.kind in ("CORRECT", "WRONG")
        right = int(outcome.kind == "CORRECT")
        row = observed[_given(rally.observation_status) or "NOT_GIVEN"]
        row[0] += 1
        row[1] += automatic
        row[2] += right
        if automatic and proposal.confidence is not None:
            pairs.append((float(proposal.confidence), right))
        elif automatic:
            without_confidence += 1
            wrong_without_confidence += 1 - right
    stated = [value for value in (_given(outcome.gold.winner_evidence) for outcome in outcomes) if value is not None]
    return LayerEvaluation(
        name=name,
        correct=kinds["CORRECT"], wrong=kinds["WRONG"], unknown=kinds["UNKNOWN"], not_proposed=kinds["NOT_PROPOSED"],
        unpaired_auto=unpaired, videos=videos, confusion=confusion,
        by_observation={observation: tuple(row) for observation, row in observed.items()},
        rule_constraint=sum(value == "RULE_CONSTRAINT" for value in stated) if stated else None,
        evidence_given=len(stated),
        calibration=Calibration(tuple(pairs), without_confidence, wrong_without_confidence),
    )


@dataclass(frozen=True)
class Evaluation:
    per_video: Mapping[str, VideoEvaluation]
    layers: Mapping[str, LayerEvaluation]
    # Gold rallies without a definite winner, by reason, over all videos.
    excluded: Mapping[str, int]
    # Automatic decisions on such rallies, by the same reasons. Counted, never scored.
    not_scored_ineligible: Mapping[str, int]
    # Automatic decisions that pair with no gold rally, by UNPAIRED. Counted, never scored.
    unpaired: Mapping[str, int]
    gold_rallies: int
    predicted_rallies: int
    automatic_proposals: int
    # video id -> layer -> the six counts of that video.
    videos: Mapping[str, Mapping[str, Mapping[str, int]]]
    # Gold rallies by HARD_CASES status over all videos, and the automatic decisions on them.
    hard_cases: Mapping[str, int] = field(default_factory=dict)
    hard_cases_decided: int = 0

    @property
    def not_measured_reason(self) -> str | None:
        """Without a single HOME or AWAY proposal no winner component took part. Its
        coverage is then not "0 of N": nothing was measured."""
        return None if self.automatic_proposals else NO_PROPOSAL

    def unscored_high_confidence(self, level: float) -> int | None:
        """Unscored automatic decisions with a confidence at or above the level. None when
        none of them carries a confidence."""
        confidences = [
            outcome.proposal.confidence for video in self.per_video.values() for outcome in video.outcomes
            if outcome.kind in ("AUTO_ON_INELIGIBLE_GOLD", "AUTO_UNPAIRED") and outcome.proposal.confidence is not None
        ]
        return sum(confidence >= level for confidence in confidences) if confidences else None

    def to_dict(self, level: float = DEFAULT_HIGH_CONFIDENCE, *, resamples: int = 2000, seed: int = 0) -> dict[str, Any]:
        measured = self.not_measured_reason is None
        return {
            "minReliable": MIN_RELIABLE,
            "goldRallies": self.gold_rallies,
            "predictedRallies": self.predicted_rallies,
            "automaticProposals": self.automatic_proposals,
            "notMeasuredReason": self.not_measured_reason,
            "excluded": dict(self.excluded),
            "hardCases": {**{status: self.hard_cases.get(status, 0) for status in HARD_CASES},
                          "automaticDecisions": self.hard_cases_decided},
            "layers": {name: layer.to_dict(measured, level, resamples=resamples, seed=seed)
                       for name, layer in self.layers.items()},
            "notScored": {
                "onIneligibleGold": dict(self.not_scored_ineligible),
                "unpaired": {"secondProposal": self.unpaired["SECOND_PROPOSAL"], "overlapsGold": self.unpaired["OVERLAPS_GOLD"],
                             "noGoldThere": self.unpaired["NO_GOLD_THERE"]},
                "highConfidence": {"level": level, "n": self.unscored_high_confidence(level)},
            },
            "videos": {video_id: {layer: dict(counts) for layer, counts in layers.items()}
                       for video_id, layers in self.videos.items()},
        }


def pool(per_video: Mapping[str, VideoEvaluation]) -> Evaluation:
    """Combine per-video results. Counts are summed; every ratio and the calibration are
    computed again over all rallies of a layer, not averaged over videos."""
    if not per_video:
        raise ValueError("pool() needs at least one video")
    excluded: Counter[str] = Counter()
    ineligible: Counter[str] = Counter()
    unpaired: Counter[str] = Counter()
    hard: Counter[str] = Counter()
    for video in per_video.values():
        excluded.update(video.excluded)
        hard.update(video.hard_cases)
        for outcome in video.outcomes:
            if outcome.kind == "AUTO_ON_INELIGIBLE_GOLD":
                ineligible[outcome.reason] += 1
            elif outcome.kind == "AUTO_UNPAIRED":
                unpaired[outcome.reason] += 1
    return Evaluation(
        per_video=dict(per_video),
        layers={name: _layer(name, per_video) for name in LAYERS},
        excluded={reason: excluded[reason] for reason in EXCLUSIONS if excluded[reason]},
        not_scored_ineligible={reason: ineligible[reason] for reason in EXCLUSIONS if ineligible[reason]},
        unpaired={reason: unpaired[reason] for reason in UNPAIRED},
        gold_rallies=sum(video.gold_rallies for video in per_video.values()),
        predicted_rallies=sum(video.predicted_rallies for video in per_video.values()),
        automatic_proposals=sum(video.automatic_proposals for video in per_video.values()),
        videos={video_id: {name: _layer(name, {video_id: video}).counts() for name in LAYERS}
                for video_id, video in per_video.items()},
        hard_cases={status: hard[status] for status in HARD_CASES if hard[status]},
        hard_cases_decided=sum(outcome.kind == "AUTO_ON_INELIGIBLE_GOLD" and hard_case(outcome.gold) is not None
                               for video in per_video.values() for outcome in video.outcomes),
    )


def failure_rows(per_video: Mapping[str, VideoEvaluation]) -> list[dict[str, Any]]:
    """The list for error analysis: one row for every outcome except a correct decision,
    ordered by video id and, within a video, by time. Keys are FAILURE_COLUMNS; a side or
    a value that does not exist is None."""
    rows = []
    for video_id in sorted(per_video):
        for outcome in per_video[video_id].outcomes:
            if outcome.kind not in FAILURE_KINDS:
                continue
            rally, proposal = outcome.gold, outcome.proposal
            rows.append({
                "video_id": video_id,
                "kind": outcome.kind,
                "layer": outcome.layer,
                "gold_start_ms": rally.start_ms if rally else None,
                "gold_end_ms": rally.end_ms if rally else None,
                "pred_start_ms": proposal.start_ms if proposal else None,
                "pred_end_ms": proposal.end_ms if proposal else None,
                "gold_winner": rally.value("winning_side") if rally else None,
                "proposed_winner": proposal.proposed_winner if proposal else None,
                "confidence": proposal.confidence if proposal else None,
                "reason": outcome.reason,
            })
    return rows


def against(value: float, target: float) -> str:
    """A share printed with three decimals, or with more when three would land it on the
    other side of the target: 0.94975 is printed 0.9497, never 0.950, beside 0.95."""
    meets = value >= target
    for digits in range(3, 10):
        text = f"{value:.{digits}f}"
        if (float(text) >= target) == meets:
            return text
    return repr(value)


@dataclass(frozen=True)
class TargetCheck:
    ratio: Ratio
    target: float

    @property
    def checked(self) -> bool:
        """A target is compared only with a ratio that may be quoted."""
        return self.ratio.reliable

    @property
    def point_estimate_meets(self) -> bool | None:
        if not self.checked:
            return None
        # Compared as fractions: 57/60 meets 0.95 and 186/196 does not, whatever the floats say.
        return Fraction(self.ratio.numerator, self.ratio.denominator) >= Fraction(str(self.target))

    @property
    def interval_low_meets(self) -> bool | None:
        return self.ratio.interval.low >= self.target if self.checked else None

    @property
    def wording(self) -> str:
        if not self.ratio.denominator:
            return "not checked: the ratio is not defined, there is no automatic decision"
        if not self.checked:
            return f"not checked: {self.ratio.denominator} is below {MIN_RELIABLE}"
        interval = self.ratio.interval
        if self.point_estimate_meets:
            if self.interval_low_meets:
                return "point estimate and whole interval at or above the target"
            return (f"point estimate at or above the target, interval reaches down to {against(interval.low, self.target)}: "
                    "this sample is also compatible with a value below the target")
        if interval.high >= self.target:
            return (f"point estimate below the target, interval reaches up to {against(interval.high, self.target)}: "
                    "this sample does not show a miss either")
        return "whole interval below the target"

    def to_dict(self) -> dict[str, Any]:
        interval = self.ratio.interval
        return {
            "value": self.ratio.value if self.checked else None,
            "target": self.target,
            "numerator": self.ratio.numerator,
            "denominator": self.ratio.denominator,
            "checked": self.checked,
            "pointEstimateMeetsTarget": self.point_estimate_meets,
            "intervalLow": None if interval is None else interval.low,
            "intervalHigh": None if interval is None else interval.high,
            "intervalLowMeetsTarget": self.interval_low_meets,
            "wording": self.wording,
        }


def check_target(ratio: Ratio, target: float) -> TargetCheck:
    return TargetCheck(ratio, target)


@dataclass(frozen=True)
class G3:
    precision: TargetCheck
    coverage: TargetCheck
    # The lower bound of the precision against the precision target. Read next to
    # `precision`; it takes no part in `point_estimates_meet_targets`.
    lower_bound: TargetCheck

    @property
    def point_estimates_meet_targets(self) -> bool | None:
        """False as soon as one checked target is missed: abstaining until too few
        decisions are left to check the precision does not hide a missed coverage. True
        only when both are checked and met. None otherwise."""
        met = (self.precision.point_estimate_meets, self.coverage.point_estimate_meets)
        if False in met:
            return False
        return True if all(met) else None

    def to_dict(self) -> dict[str, Any]:
        return {
            "targets": {"precision": G3_MIN_PRECISION, "coverage": G3_MIN_COVERAGE},
            "precision": self.precision.to_dict(),
            "coverage": self.coverage.to_dict(),
            "lowerBoundPrecision": self.lower_bound.to_dict(),
            "pointEstimatesMeetTargets": self.point_estimates_meet_targets,
        }


def g3(layer: LayerEvaluation) -> G3:
    """Compare a layer with the targets of gate G3. This states how the numbers stand
    against the targets. It is not the gate decision."""
    return G3(
        check_target(layer.precision, G3_MIN_PRECISION),
        check_target(layer.coverage, G3_MIN_COVERAGE),
        check_target(layer.lower_bound_precision, G3_MIN_PRECISION),
    )


def pilot_reasons(*, manifest_given: bool, digest_compared: bool, split: str | None,
                  missing_test_videos: Sequence[str], tolerance_ms: int, analyzer_stated: bool,
                  automatic_proposals: int, gold_layer_eligible: int) -> list[str]:
    """Why a run is a pilot run and not input for the G3 decision: every reason that
    applies, in a fixed order. An empty list means that every condition holds."""
    reasons = []
    if not manifest_given:
        reasons.append("no lock manifest was given, so the tool can not tell that these are the locked test videos")
    else:
        if not digest_compared:
            reasons.append("the digest of the manifest was not compared with the one noted at lock time (--expect-sha256)")
        if split != "test":
            reasons.append(f"the videos are in the {split} split, and the gate is checked on the test split only")
        elif missing_test_videos:
            reasons.append("test video(s) of the manifest were not evaluated: " + ", ".join(missing_test_videos))
    if tolerance_ms != DEFAULT_TOLERANCE_MS:
        reasons.append(f"rallies were paired at +/-{tolerance_ms} ms and not at the fixed {DEFAULT_TOLERANCE_MS} ms")
    if not analyzer_stated:
        reasons.append("the prediction files do not name the analyzer and its version")
    if not automatic_proposals:
        # Today's segment output abstains on every rally. Its coverage of 0 is no measurement
        # of a winner component, so it is not compared with the targets.
        reasons.append("the evaluated videos carry no HOME or AWAY proposal, so there is no winner decision "
                       "to compare with the targets")
    if not gold_layer_eligible:
        reasons.append("no eligible rally carries a double-agreed or adjudicated label")
    return reasons
