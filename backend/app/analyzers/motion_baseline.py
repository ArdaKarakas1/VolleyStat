"""Rally proposals from motion energy (kind BASELINE). See docs/API_V1.md, section 2.

The algorithm lives in the volleystat_ml package (ml/ in this repository). Its
accuracy on real match video is not measured. It proposes intervals only: it has
no evidence about who won a rally, so the winner stays UNKNOWN and the confidence
stays None.
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

from .base import BASELINE, UNKNOWN, AnalyzerError, AnalyzerInfo, ProgressReporter, RallyProposal

try:
    from volleystat_ml import segmenter
except ImportError as error:
    # The registry turns an ImportError into "analyzer is not installed" (503) instead of a crash.
    raise ImportError(
        "the 'motion-baseline' analyzer needs the volleystat_ml package, which is not installed "
        f"or could not be imported ({error}). Install it from the repository root with: pip install -e ml"
    ) from error

NO_WINNER_MODEL = "NO_WINNER_MODEL"

# Demuxer for each container the upload probe accepts. Forcing it keeps ffmpeg from
# guessing the format from the content of an uploaded file, as the probe already does.
_DEMUXERS = {"video/mp4": "mov", "video/webm": "matroska", "video/x-matroska": "matroska"}


class MotionBaselineAnalyzer:
    info = AnalyzerInfo(id=segmenter.ALGORITHM_ID, version=segmenter.ALGORITHM_VERSION, kind=BASELINE)

    def __init__(self, params: segmenter.SegmenterParams | None = None):
        self.params = params or segmenter.SegmenterParams()

    def analyze(self, video_path: Path, media: dict[str, Any], report: ProgressReporter) -> list[RallyProposal]:
        def frames(done: int, total: int) -> None:
            report("DETECTING_RALLIES", done, total, "FRAMES")

        try:
            result = segmenter.segment_video(
                video_path,
                self.params,
                duration_ms=media.get("durationMs"),
                demuxer=_DEMUXERS.get(media.get("container")),
                progress=frames,
            )
        except segmenter.DecodeError as error:
            raise AnalyzerError("VIDEO_UNREADABLE", str(error)) from error
        except segmenter.SegmenterError as error:
            raise AnalyzerError("ANALYZER_ERROR", str(error)) from error
        return [
            RallyProposal(
                start_ms=interval.start_ms,
                end_ms=interval.end_ms,
                proposed_winner=UNKNOWN,
                confidence=None,
                uncertainty_reasons=(NO_WINNER_MODEL,),
            )
            for interval in result.intervals
        ]
