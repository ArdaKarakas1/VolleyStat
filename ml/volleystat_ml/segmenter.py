"""Rally segmentation from motion energy (kind BASELINE).

Assumption: the picture changes little between rallies and a lot during one. The
segmenter measures how much consecutive low-resolution frames differ, splits the
clip's own energy values into an idle and an active level, and returns the
stretches that sit at the active level.

This is a heuristic, not a model. It was only run on synthetic clips; its
accuracy on real match video is not measured. It knows nothing about the ball,
the court or the score, so it never proposes a winner and never produces a
confidence value.
"""
from __future__ import annotations

import math
import os
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import IO, Callable, NamedTuple

import numpy as np

ALGORITHM_ID = "motion-baseline"
# Stored proposals carry this version. Change it whenever the algorithm or a default
# parameter changes, otherwise results from different behaviour look comparable.
ALGORITHM_VERSION = "0.1"

OK = "OK"
# The energy does not split into two clearly different levels (a still clip, a clip
# that moves all the time, or motion that is too weak). No interval is returned.
NO_SEPARATION = "NO_SEPARATION"
# Fewer samples than the levels can be estimated from.
TOO_SHORT = "TOO_SHORT"
# Two levels exist, but every active stretch is shorter than `min_rally_ms`.
ONLY_SHORT_RUNS = "ONLY_SHORT_RUNS"

PROBE_TIMEOUT_SECONDS = 90
# Frames read from the pipe per step; also how often progress is reported.
_CHUNK_FRAMES = 32
# How much of the end of ffmpeg's messages is read back for an error text.
_MESSAGE_TAIL_BYTES = 4096

# progress(frames_done, frames_total), both in sampled frames.
ProgressCallback = Callable[[int, int], None]


class SegmenterError(Exception):
    """The segmenter could not run. Not raised for a video that simply has no rallies."""


class FfmpegMissing(SegmenterError):
    pass


class DecodeError(SegmenterError):
    pass


class Interval(NamedTuple):
    start_ms: int
    end_ms: int


@dataclass(frozen=True)
class SegmenterParams:
    """Every tunable of the baseline.

    The defaults are engineering guesses that were only exercised on synthetic clips.
    None of them was tuned or validated on real match video.
    """

    # Frames per second sampled from the video. Keep it below the source frame rate:
    # ffmpeg repeats frames to reach a higher rate and a repeated frame reads as zero motion.
    sample_fps: float = 5.0
    # Size of the grayscale frames the energy is computed on. Small frames average sensor
    # noise away and keep a full match within seconds of numpy work.
    frame_width: int = 96
    frame_height: int = 54
    # Width of the centred median filter. A median keeps a step edge where it is and drops
    # single-frame spikes (scene cut, flash); a mean would smear both into the idle time.
    smooth_window_ms: int = 1000
    # Energy is the mean absolute gray-level change per pixel (0..255) between two sampled
    # frames, so it depends on sample_fps. This floor is added before the logarithm: changes
    # far below it are compression and quantisation noise and must not form a level of their own.
    noise_floor: float = 0.05
    # Active level divided by idle level, both raised by noise_floor. Below this ratio the
    # clip counts as having no usable separation.
    min_contrast_ratio: float = 3.0
    # Hysteresis thresholds as positions between the idle level (0.0) and the active level
    # (1.0) on the log scale. A run needs one sample at or above `enter_level` and then
    # extends over every neighbouring sample at or above `exit_level`.
    enter_level: float = 0.5
    exit_level: float = 0.25
    # Active runs separated by a shorter idle gap become one rally.
    merge_gap_ms: int = 1500
    # Runs shorter than this are dropped after merging.
    min_rally_ms: int = 2000

    def __post_init__(self) -> None:
        if not (math.isfinite(self.sample_fps) and self.sample_fps > 0):
            raise ValueError("sample_fps must be a positive number")
        if self.frame_width < 1 or self.frame_height < 1:
            raise ValueError("frame_width and frame_height must be at least 1")
        if self.smooth_window_ms < 0 or self.merge_gap_ms < 0:
            raise ValueError("smooth_window_ms and merge_gap_ms must not be negative")
        if self.min_rally_ms < 1:
            raise ValueError("min_rally_ms must be at least 1")
        if not self.noise_floor > 0:
            raise ValueError("noise_floor must be positive")
        if not self.min_contrast_ratio > 1:
            raise ValueError("min_contrast_ratio must be greater than 1")
        if not 0 < self.exit_level <= self.enter_level <= 1:
            raise ValueError("levels must satisfy 0 < exit_level <= enter_level <= 1")


@dataclass(frozen=True)
class SegmentationResult:
    status: str
    intervals: tuple[Interval, ...]
    duration_ms: int
    frames_processed: int
    frames_expected: int
    # Typical smoothed energy of the two levels. Diagnostics for a person tuning the
    # parameters; these are raw scores and must never be shown as a confidence.
    idle_energy: float | None = None
    active_energy: float | None = None
    contrast_ratio: float | None = None


def probe_duration_ms(path: Path, demuxer: str | None = None) -> int:
    command = ["ffprobe", "-v", "error", "-protocol_whitelist", "file"]
    if demuxer:
        command += ["-f", demuxer]
    command += ["-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1",
                str(Path(path).resolve())]
    try:
        completed = subprocess.run(command, capture_output=True, text=True, timeout=PROBE_TIMEOUT_SECONDS)
    except FileNotFoundError as error:
        raise FfmpegMissing("ffprobe is not installed or not on PATH") from error
    except subprocess.TimeoutExpired as error:
        raise DecodeError(f"ffprobe did not finish within {PROBE_TIMEOUT_SECONDS} s") from error
    try:
        seconds = float(completed.stdout.strip().splitlines()[0])
    except (ValueError, IndexError):
        seconds = math.nan
    if completed.returncode != 0 or not (math.isfinite(seconds) and seconds > 0):
        raise DecodeError(f"ffprobe could not read a duration: {_last_line(completed.stderr)}")
    return round(seconds * 1000)


def decode_motion_energy(
    path: Path,
    params: SegmenterParams,
    duration_ms: int,
    *,
    demuxer: str | None = None,
    progress: ProgressCallback | None = None,
) -> tuple[np.ndarray, int, int]:
    """Return (energy, frames_processed, frames_expected).

    `energy[i]` is the mean absolute gray-level difference between sampled frames i and
    i + 1, so it describes the time between i / sample_fps and (i + 1) / sample_fps.
    `demuxer` forces the input format (as the upload probe does) so ffmpeg never guesses
    the container from the content of an untrusted file.
    """
    width, height = params.frame_width, params.frame_height
    # round=up makes sampled frame k the newest source frame at or before k / sample_fps,
    # which is what a player shows at that position; the default (near) samples up to half
    # a period late. start_time=0 keeps k / sample_fps on the file's own timeline.
    filters = (
        f"fps=fps={params.sample_fps}:round=up:start_time=0,"
        f"scale={width}:{height}:flags=area,format=gray"
    )
    command = ["ffmpeg", "-v", "error", "-nostdin", "-protocol_whitelist", "file"]
    if demuxer:
        command += ["-f", demuxer]
    # 0:V:0 is the first real video stream; cover art and thumbnails are skipped.
    command += ["-i", str(Path(path).resolve()), "-map", "0:V:0", "-an", "-sn", "-dn",
                "-vf", filters, "-f", "rawvideo", "-pix_fmt", "gray", "pipe:1"]

    expected = max(1, round(duration_ms * params.sample_fps / 1000))
    frame_bytes = width * height
    energies: list[np.ndarray] = []
    previous: np.ndarray | None = None
    done = 0
    # ffmpeg's messages go to a file: an undrained stderr pipe would block the decoder
    # once it fills, and a damaged video can produce a lot of messages.
    with tempfile.TemporaryFile() as messages:
        try:
            process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=messages)
        except FileNotFoundError as error:
            raise FfmpegMissing("ffmpeg is not installed or not on PATH") from error
        try:
            while True:
                data = process.stdout.read(frame_bytes * _CHUNK_FRAMES)
                count = len(data) // frame_bytes
                if count == 0:
                    break
                block = np.frombuffer(data, np.uint8, count * frame_bytes).reshape(count, height, width)
                block = block.astype(np.int16)
                if previous is not None:
                    block = np.concatenate([previous[None], block])
                energies.append(np.abs(np.diff(block, axis=0)).mean(axis=(1, 2)))
                previous = block[-1]
                done += count
                if progress is not None:
                    # The expected count comes from the container duration and can be a
                    # frame or two short; the total then follows the frames really seen.
                    progress(done, max(expected, done))
            return_code = process.wait()
        finally:
            # Reached with the decoder still running when the progress callback raises
            # (for example to cancel a job).
            if process.poll() is None:
                process.kill()
                process.wait()
            process.stdout.close()
        detail = _last_line(_tail(messages))

    if return_code != 0:
        raise DecodeError(f"ffmpeg could not decode the video: {detail}")
    if done == 0:
        raise DecodeError("ffmpeg decoded no video frames")
    if progress is not None and done < expected:
        # The video stream ended before the container duration (audio runs longer, for
        # example). The real total is known now.
        progress(done, done)
    energy = np.concatenate(energies) if energies else np.zeros(0)
    return energy, done, expected


def intervals_from_energy(
    energy: np.ndarray, params: SegmenterParams, duration_ms: int
) -> tuple[list[Interval], str, tuple[float, float, float] | None]:
    """Turn an energy series into intervals. Returns (intervals, status, levels) where
    levels is (idle_energy, active_energy, contrast_ratio) or None when no cut exists."""
    period_ms = 1000.0 / params.sample_fps
    window = max(1, round(params.smooth_window_ms / period_ms)) | 1
    energy = np.asarray(energy, dtype=np.float64)
    if energy.size < 2 * window:
        return [], TOO_SHORT, None

    log_energy = np.log(_median_filter(energy, window) + params.noise_floor)
    cut = _otsu_cut(log_energy)
    if cut is None:
        return [], NO_SEPARATION, None
    idle = float(np.median(log_energy[log_energy < cut]))
    active = float(np.median(log_energy[log_energy >= cut]))
    contrast = math.exp(active - idle)
    levels = (
        max(0.0, round(math.exp(idle) - params.noise_floor, 4)),
        max(0.0, round(math.exp(active) - params.noise_floor, 4)),
        round(contrast, 4),
    )
    if contrast < params.min_contrast_ratio:
        return [], NO_SEPARATION, levels

    enter = idle + params.enter_level * (active - idle)
    leave = idle + params.exit_level * (active - idle)
    # The video stream can end before the container duration, never after it.
    timeline_end = min(float(duration_ms), (energy.size + 1) * period_ms)
    spans: list[list[float]] = []
    for first, last in _hysteresis_runs(log_energy, enter, leave):
        # The motion started somewhere inside sample `first` and stopped somewhere inside
        # sample `last`; the middle of the sample halves the worst-case error. A run that
        # touches the edge of the clip was already (or still) active there.
        start = 0.0 if first == 0 else (first + 0.5) * period_ms
        end = timeline_end if last == energy.size - 1 else (last + 0.5) * period_ms
        if spans and start - spans[-1][1] < params.merge_gap_ms:
            spans[-1][1] = end
        else:
            spans.append([start, end])

    intervals = []
    for start, end in spans:
        start_ms = max(0, round(start))
        end_ms = min(duration_ms, round(end))
        if end_ms - start_ms >= params.min_rally_ms:
            intervals.append(Interval(start_ms, end_ms))
    return intervals, (OK if intervals else ONLY_SHORT_RUNS), levels


def segment_video(
    path: Path,
    params: SegmenterParams | None = None,
    *,
    duration_ms: int | None = None,
    demuxer: str | None = None,
    progress: ProgressCallback | None = None,
) -> SegmentationResult:
    """Propose rally intervals for one video. `duration_ms` is probed when not given."""
    params = params or SegmenterParams()
    if duration_ms is None:
        duration_ms = probe_duration_ms(path, demuxer)
    energy, done, expected = decode_motion_energy(path, params, duration_ms, demuxer=demuxer, progress=progress)
    intervals, status, levels = intervals_from_energy(energy, params, duration_ms)
    idle, active, contrast = levels if levels else (None, None, None)
    return SegmentationResult(
        status=status,
        intervals=tuple(intervals),
        duration_ms=duration_ms,
        frames_processed=done,
        frames_expected=expected,
        idle_energy=idle,
        active_energy=active,
        contrast_ratio=contrast,
    )


def _tail(messages: IO[bytes]) -> str:
    # A damaged video can make ffmpeg log a line for every frame. Only the end of that
    # log goes into the error text, so only the end is read into memory.
    size = messages.seek(0, os.SEEK_END)
    messages.seek(max(0, size - _MESSAGE_TAIL_BYTES))
    return messages.read().decode("utf-8", errors="replace")


def _last_line(text: str) -> str:
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    return lines[-1] if lines else "no message"


def _median_filter(values: np.ndarray, window: int) -> np.ndarray:
    if window <= 1:
        return values
    padded = np.pad(values, window // 2, mode="edge")
    return np.median(np.lib.stride_tricks.sliding_window_view(padded, window), axis=1)


def _otsu_cut(values: np.ndarray, bins: int = 128) -> float | None:
    """Otsu's threshold: the cut that maximises the variance between the two sides.
    None when all values are equal."""
    low, high = float(values.min()), float(values.max())
    if not high > low:
        return None
    histogram, edges = np.histogram(values, bins=bins, range=(low, high))
    weights = histogram / histogram.sum()
    centres = (edges[:-1] + edges[1:]) / 2
    below = np.cumsum(weights)[:-1]
    mean_below = np.cumsum(weights * centres)[:-1]
    total_mean = float(np.sum(weights * centres))
    above = 1.0 - below
    usable = (below > 0) & (above > 0)
    between = np.zeros_like(below)
    between[usable] = (total_mean * below[usable] - mean_below[usable]) ** 2 / (below[usable] * above[usable])
    return float(edges[int(np.argmax(between)) + 1])


def _hysteresis_runs(values: np.ndarray, enter: float, leave: float) -> list[tuple[int, int]]:
    """Inclusive index ranges of samples >= leave that contain a sample >= enter."""
    padded = np.concatenate([[False], values >= leave, [False]])
    changes = np.flatnonzero(padded[1:] != padded[:-1])
    runs = []
    for first, stop in zip(changes[::2], changes[1::2]):
        if values[first:stop].max() >= enter:
            runs.append((int(first), int(stop) - 1))
    return runs
