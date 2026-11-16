"""Demo analyzer for user-interface work. It looks at no pixel of the video.

The intervals below are invented from the duration alone, so every one of them is marked
MOCK_DATA and the job that carries them is flagged `mock`. This module must never propose a
winner or a confidence: a made-up value in those fields would read as a result.
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

from .base import MOCK, UNKNOWN, AnalyzerInfo, ProgressReporter, RallyProposal

MOCK_DATA = "MOCK_DATA"

LEAD_MS = 4_000
RALLY_MS = 8_000
PERIOD_MS = 20_000


class DemoAnalyzer:
    info = AnalyzerInfo(id="demo", version="0.1", kind=MOCK)

    def analyze(self, video_path: Path, media: dict[str, Any], report: ProgressReporter) -> list[RallyProposal]:
        # No stage is reported: nothing is preprocessed or detected here, and the job
        # contract only shows stages that really ran.
        duration = int(media["durationMs"])
        spans = [
            (start, start + RALLY_MS)
            for start in range(LEAD_MS, duration, PERIOD_MS)
            if start + RALLY_MS <= duration
        ]
        if not spans and duration // 4 < duration * 3 // 4:
            # A clip too short for the fixed grid still gets one card to try the screens with.
            spans = [(duration // 4, duration * 3 // 4)]
        return [
            RallyProposal(start_ms=start, end_ms=end, proposed_winner=UNKNOWN, confidence=None,
                          uncertainty_reasons=(MOCK_DATA,))
            for start, end in spans
        ]
