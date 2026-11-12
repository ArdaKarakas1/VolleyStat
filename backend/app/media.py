"""Byte-level video acceptance check (pipeline stage V0).

This inspects the container and the video stream only. It says nothing about
what the video shows: court coverage, ball visibility and camera stability are
not measured here and must never be reported as if they were.
"""
from __future__ import annotations

import json
import math
import shutil
import subprocess
from dataclasses import dataclass, field
from fractions import Fraction
from pathlib import Path
from typing import Any

PROBE_VERSION = "ffprobe-v1"
PROBE_TIMEOUT_SECONDS = 90
MAX_DURATION_SECONDS = 12 * 3600
ACCEPTED_CODECS = {"h264", "hevc", "vp8", "vp9", "av1"}

READY = "READY"
QUARANTINED = "QUARANTINED"
STORED_UNVALIDATED = "STORED_UNVALIDATED"

# Reject codes (video is stored but can not be analysed).
NOT_A_VIDEO = "NOT_A_VIDEO"
VIDEO_CORRUPT = "VIDEO_CORRUPT"
UNSUPPORTED_CODEC = "UNSUPPORTED_CODEC"
VIDEO_DURATION_INVALID = "VIDEO_DURATION_INVALID"
PROBE_UNAVAILABLE = "PROBE_UNAVAILABLE"

# Warnings (video is accepted into a lower quality layer, never auto-rejected).
LOW_RESOLUTION = "LOW_RESOLUTION"
LOW_FRAME_RATE = "LOW_FRAME_RATE"
PORTRAIT_ORIENTATION = "PORTRAIT_ORIENTATION"
VARIABLE_FRAME_RATE = "VARIABLE_FRAME_RATE"


@dataclass
class ProbeResult:
    status: str
    reject_code: str | None = None
    media: dict[str, Any] | None = None
    warnings: list[str] = field(default_factory=list)


def probe_available() -> bool:
    return shutil.which("ffprobe") is not None


def _demuxer(path: Path) -> str | None:
    """Pick the demuxer from magic bytes so ffprobe never guesses from content."""
    with path.open("rb") as handle:
        signature = handle.read(12)
    if signature[4:8] in (b"ftyp", b"moov", b"mdat", b"wide", b"free"):
        return "mov"
    if signature.startswith(b"\x1a\x45\xdf\xa3"):
        return "matroska"
    return None


def _rate(value: Any) -> float | None:
    try:
        rate = float(Fraction(str(value)))
    except (ValueError, ZeroDivisionError):
        return None
    return rate if math.isfinite(rate) and rate > 0 else None


def _rotation(stream: dict[str, Any]) -> int:
    candidates = [entry.get("rotation") for entry in stream.get("side_data_list", [])]
    candidates.append(stream.get("tags", {}).get("rotate"))
    for value in candidates:
        try:
            return int(float(value)) % 360
        except (TypeError, ValueError):
            continue
    return 0


def probe_video(path: Path) -> ProbeResult:
    demuxer = _demuxer(path)
    if demuxer is None:
        return ProbeResult(QUARANTINED, NOT_A_VIDEO)
    command = [
        "ffprobe", "-v", "error", "-protocol_whitelist", "file", "-f", demuxer,
        "-show_entries",
        "format=format_name,duration"
        ":stream=codec_type,codec_name,width,height,avg_frame_rate,r_frame_rate"
        ":stream_disposition=attached_pic:stream_tags=rotate:stream_side_data=rotation",
        "-of", "json", str(path),
    ]
    try:
        completed = subprocess.run(
            command, capture_output=True, text=True, timeout=PROBE_TIMEOUT_SECONDS, check=True
        )
    except FileNotFoundError:
        return ProbeResult(STORED_UNVALIDATED, PROBE_UNAVAILABLE)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        return ProbeResult(QUARANTINED, VIDEO_CORRUPT)

    try:
        data = json.loads(completed.stdout)
        container = data["format"]
        streams = [
            entry for entry in data.get("streams", [])
            if entry.get("codec_type") == "video"
            and not entry.get("disposition", {}).get("attached_pic")
            and int(entry.get("width", 0)) > 0 and int(entry.get("height", 0)) > 0
        ]
    except (KeyError, ValueError, TypeError):
        return ProbeResult(QUARANTINED, VIDEO_CORRUPT)
    if not streams:
        return ProbeResult(QUARANTINED, NOT_A_VIDEO)
    stream = streams[0]

    try:
        duration = float(container["duration"])
    except (KeyError, ValueError, TypeError):
        return ProbeResult(QUARANTINED, VIDEO_CORRUPT)
    if not math.isfinite(duration) or not 0 < duration <= MAX_DURATION_SECONDS:
        return ProbeResult(QUARANTINED, VIDEO_DURATION_INVALID)

    codec = str(stream.get("codec_name", ""))
    formats = str(container.get("format_name", "")).split(",")
    if "mov" in formats:
        mime = "video/mp4"
    elif "matroska" in formats and codec in ("vp8", "vp9", "av1"):
        mime = "video/webm"
    elif "matroska" in formats:
        mime = "video/x-matroska"
    else:
        return ProbeResult(QUARANTINED, NOT_A_VIDEO)

    rotation = _rotation(stream)
    width, height = int(stream["width"]), int(stream["height"])
    if rotation in (90, 270):
        width, height = height, width
    average = _rate(stream.get("avg_frame_rate"))
    nominal = _rate(stream.get("r_frame_rate"))
    media = {
        "container": mime,
        "codec": codec,
        "durationMs": round(duration * 1000),
        "width": width,
        "height": height,
        "rotation": rotation,
        "fpsAverage": round(average, 3) if average else None,
        "fpsNominal": round(nominal, 3) if nominal else None,
    }
    if codec not in ACCEPTED_CODECS:
        return ProbeResult(QUARANTINED, UNSUPPORTED_CODEC, media)

    warnings = []
    if min(width, height) < 720:
        warnings.append(LOW_RESOLUTION)
    if height > width:
        warnings.append(PORTRAIT_ORIENTATION)
    fps = average or nominal
    if fps is not None and fps < 24:
        warnings.append(LOW_FRAME_RATE)
    if average and nominal and abs(average - nominal) / nominal > 0.02:
        warnings.append(VARIABLE_FRAME_RATE)
    return ProbeResult(READY, None, media, warnings)
