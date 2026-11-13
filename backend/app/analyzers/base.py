"""The one interface every analyzer implements. See docs/API_V1.md, section 2.

An analyzer proposes; it never decides. Its output becomes rally proposals and
review cards, and only a user decision turns a proposal into a statistic.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Protocol

MOCK = "MOCK"          # invented segments for UI work; every output is flagged mock
BASELINE = "BASELINE"  # a real algorithm whose accuracy has not been measured
MODEL = "MODEL"        # a trained model with a recorded evaluation

HOME = "HOME"
AWAY = "AWAY"
UNKNOWN = "UNKNOWN"


@dataclass(frozen=True)
class AnalyzerInfo:
    id: str
    version: str
    kind: str


@dataclass(frozen=True)
class RallyProposal:
    start_ms: int
    end_ms: int
    # Leave UNKNOWN unless the analyzer has real evidence for a side.
    proposed_winner: str = UNKNOWN
    # Only a calibrated probability may go here. A raw score stays None.
    confidence: float | None = None
    uncertainty_reasons: tuple[str, ...] = ()


# report(stage, done, total, unit): stage is one of PREPROCESSING, DETECTING_RALLIES,
# GENERATING_REVIEW. Pass done/total/unit only when the amount of work is really measured,
# otherwise None; the client then shows an indeterminate indicator instead of a percentage.
ProgressReporter = Callable[[str, int | None, int | None, str | None], None]


class AnalyzerError(Exception):
    """Raised by an analyzer for a failure the user should see. `code` is a job failure code."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


class Analyzer(Protocol):
    info: AnalyzerInfo

    def analyze(self, video_path: Path, media: dict[str, Any], report: ProgressReporter) -> list[RallyProposal]:
        """Return rally proposals ordered by start_ms. `media` is the stored probe result
        (durationMs, width, height, fpsAverage, codec, ...). An empty list means no rally
        was found; the job then fails with NO_RALLIES_FOUND."""
        ...
