"""Segmenter tests. The clips are synthetic, so these tests prove the plumbing only."""
import contextlib
import os
import signal
import subprocess
import tracemalloc
from pathlib import Path

import numpy as np
import pytest

from volleystat_ml import segmenter
from volleystat_ml.metrics import evaluate_boundaries
from volleystat_ml.segmenter import (
    Interval, SegmenterParams, decode_motion_energy, intervals_from_energy, segment_video,
)

PARAMS = SegmenterParams()
PERIOD_MS = 200  # one energy sample at the default 5 fps
FRAME_BYTES = PARAMS.frame_width * PARAMS.frame_height


def energy_of(*stretches):
    """Build an energy series from (value, sample_count) pairs."""
    return np.concatenate([np.full(count, float(value)) for value, count in stretches])


# --- synthetic video -------------------------------------------------------------------

@pytest.mark.parametrize("clip", ["moving_box", "concatenated"])
def test_known_boundaries_are_recovered(clips, clip):
    result = segment_video(getattr(clips, clip))
    assert result.status == segmenter.OK
    assert result.duration_ms == clips.rally_clip_ms

    evaluation = evaluate_boundaries(clips.rallies_ms, result.intervals)
    assert (evaluation.tp, evaluation.fp, evaluation.fn) == (3, 0, 0)
    # One sample period is 200 ms, so the boundaries land well inside the 1000 ms tolerance.
    assert evaluation.p90_abs_start_error_ms <= 300 and evaluation.p90_abs_end_error_ms <= 300
    # Three synthetic rallies are no basis for a percentage.
    assert evaluation.percentages_reliable is False


def test_intervals_are_ordered_whole_milliseconds_inside_the_clip(clips):
    result = segment_video(clips.moving_box)
    assert all(isinstance(value, int) for interval in result.intervals for value in interval)
    assert all(0 <= interval.start_ms < interval.end_ms <= result.duration_ms for interval in result.intervals)
    assert list(result.intervals) == sorted(result.intervals)
    assert all(later.start_ms > earlier.end_ms for earlier, later in zip(result.intervals, result.intervals[1:]))


def test_progress_reports_frames_processed_of_frames_expected(clips):
    calls = []
    result = segment_video(clips.moving_box, progress=lambda done, total: calls.append((done, total)))
    assert len(calls) > 1
    assert [done for done, _ in calls] == sorted({done for done, _ in calls})
    assert all(0 < done <= total for done, total in calls)
    # 40 s at 5 sampled frames per second.
    assert calls[-1] == (200, 200)
    assert (result.frames_processed, result.frames_expected) == (200, 200)


def test_progress_ends_at_the_real_frame_count_when_the_picture_is_shorter_than_the_file(clips):
    calls = []
    result = segment_video(clips.short_picture, progress=lambda done, total: calls.append((done, total)))
    assert result.duration_ms == 9000 and result.frames_expected == 45
    assert result.frames_processed == 30
    assert calls[-1] == (30, 30)


@pytest.mark.parametrize("clip", ["still", "still_noisy"])
def test_clip_without_activity_gives_no_intervals(clips, clip):
    result = segment_video(getattr(clips, clip))
    assert result.status == segmenter.NO_SEPARATION
    assert result.intervals == ()


def test_clip_that_is_all_activity_gives_no_intervals(clips):
    result = segment_video(clips.busy)
    assert result.status == segmenter.NO_SEPARATION
    assert result.intervals == ()
    # The levels are reported so a person can see why: the two halves barely differ.
    assert result.contrast_ratio < PARAMS.min_contrast_ratio


def test_duration_and_demuxer_can_be_supplied_by_the_caller(clips):
    result = segment_video(clips.webm, duration_ms=6000, demuxer="matroska")
    assert result.frames_processed == 30
    with pytest.raises(segmenter.DecodeError):
        segment_video(clips.webm, duration_ms=6000, demuxer="mov")


def test_unreadable_file_raises_decode_error(clips, tmp_path):
    with pytest.raises(segmenter.DecodeError):
        segment_video(clips.not_a_video)
    with pytest.raises(segmenter.DecodeError):
        segment_video(clips.not_a_video, duration_ms=5000)
    with pytest.raises(segmenter.DecodeError):
        segment_video(tmp_path / "missing.mp4", duration_ms=5000)


@pytest.mark.parametrize("clip", ["truncated", "damaged"])
def test_video_that_was_not_decoded_to_its_end_is_refused(clips, clip):
    # ffmpeg exits with 0 for both files; only its messages tell that frames are missing.
    # Intervals for the readable part would look like a complete result.
    with pytest.raises(segmenter.DecodeError, match="could not decode every part of the video"):
        segment_video(getattr(clips, clip))


def test_hole_in_the_timestamps_is_idle_time_and_not_the_idle_level(clips):
    result = segment_video(clips.timestamp_gap)
    assert result.status == segmenter.OK
    evaluation = evaluate_boundaries(clips.rallies_ms, result.intervals)
    assert (evaluation.tp, evaluation.fp, evaluation.fn) == (3, 0, 0)
    # The idle level is the noise of the resting picture, not the zero of the repeated frames.
    assert 0.5 < result.idle_energy < result.active_energy


def test_sampling_faster_than_the_source_only_adds_repeated_frames(clips):
    # The clip has 15 frames per second, so every second sample at 30 is a copy.
    result = segment_video(clips.moving_box, SegmenterParams(sample_fps=30))
    assert result.frames_processed == 1200
    evaluation = evaluate_boundaries(clips.rallies_ms, result.intervals)
    assert (evaluation.tp, evaluation.fp, evaluation.fn) == (3, 0, 0)


@pytest.mark.parametrize("clip, container_ms", [("offset_start", 45000), ("late_picture", 42000)])
def test_times_count_from_the_first_video_frame(clips, clip, container_ms):
    # In the first clip the container starts at 5 s, in the second the sound starts 2 s
    # before the picture. Neither shift may reach the intervals.
    result = segment_video(getattr(clips, clip))
    assert result.duration_ms == container_ms
    assert result.frames_processed == 200
    evaluation = evaluate_boundaries(clips.rallies_ms, result.intervals)
    assert (evaluation.tp, evaluation.fp, evaluation.fn) == (3, 0, 0)
    assert evaluation.p90_abs_start_error_ms <= 300 and evaluation.p90_abs_end_error_ms <= 300
    assert all(0 <= interval.start_ms < interval.end_ms <= clips.rally_clip_ms for interval in result.intervals)


def test_missing_ffmpeg_is_reported_as_such(clips, monkeypatch, tmp_path):
    monkeypatch.setenv("PATH", str(tmp_path))
    with pytest.raises(segmenter.FfmpegMissing):
        segment_video(clips.busy, duration_ms=20000)
    with pytest.raises(segmenter.FfmpegMissing):
        segment_video(clips.busy)


def test_error_in_the_progress_callback_stops_the_run(clips):
    def cancel(done, total):
        raise RuntimeError("cancelled")

    with pytest.raises(RuntimeError, match="cancelled"):
        segment_video(clips.moving_box, progress=cancel)


# --- decoder process -------------------------------------------------------------------
# A shell script stands in for ffmpeg here. It produces what no real clip produces on
# demand: megabytes of messages, a stream that never ends, a stream far longer than the
# test clips. Only the pipe handling is under test; nothing is decoded.

needs_posix_shell = pytest.mark.skipif(os.name != "posix", reason="the stand-in decoder is a shell script")


@pytest.fixture
def stand_in_decoder(tmp_path, monkeypatch):
    def install(script):
        folder = tmp_path / "bin"
        folder.mkdir(exist_ok=True)
        target = folder / "ffmpeg"
        target.write_text(f"#!/bin/sh\n{script}\n", encoding="utf-8")
        target.chmod(0o755)
        monkeypatch.setenv("PATH", f"{folder}{os.pathsep}{os.environ['PATH']}")

    return install


@contextlib.contextmanager
def time_limit(seconds):
    """Fail instead of hanging: a blocked pipe would otherwise stall the whole test run."""
    def expired(signal_number, frame):
        raise TimeoutError(f"no result within {seconds} s")

    previous = signal.signal(signal.SIGALRM, expired)
    signal.alarm(seconds)
    try:
        yield
    finally:
        signal.alarm(0)
        signal.signal(signal.SIGALRM, previous)


@needs_posix_shell
def test_megabytes_of_decoder_messages_do_not_block_the_frames(stand_in_decoder):
    # A pipe holds about 64 KB. If nobody drained the messages, the decoder would stop
    # before its first frame and the reader would wait for that frame forever.
    stand_in_decoder(f'yes "damaged frame" | head -n 150000 >&2\nhead -c {10 * FRAME_BYTES} /dev/zero')
    calls = []
    with time_limit(60), pytest.raises(segmenter.DecodeError, match="every part of the video: damaged frame$"):
        decode_motion_energy(Path("unused.mp4"), PARAMS, duration_ms=2000,
                             progress=lambda done, total: calls.append((done, total)))
    # Every frame came through before the messages made the run fail.
    assert calls[-1] == (10, 10)


@needs_posix_shell
def test_messages_with_exit_code_zero_fail_the_run_and_name_the_real_message(stand_in_decoder):
    stand_in_decoder(
        'echo "[h264] Invalid data found when processing input" >&2\n'
        'echo "    Last message repeated 3 times" >&2\n'
        f"head -c {10 * FRAME_BYTES} /dev/zero"
    )
    with time_limit(60), pytest.raises(segmenter.DecodeError, match="Invalid data found when processing input$"):
        decode_motion_energy(Path("unused.mp4"), PARAMS, duration_ms=2000)


@needs_posix_shell
def test_failed_decode_reports_the_last_message_however_long_the_log_is(stand_in_decoder):
    stand_in_decoder('yes "damaged frame" | head -n 150000 >&2\necho "moov atom not found" >&2\nexit 1')
    with time_limit(60), pytest.raises(segmenter.DecodeError, match="could not decode the video: moov atom not found$"):
        decode_motion_energy(Path("unused.mp4"), PARAMS, duration_ms=2000)


@needs_posix_shell
def test_decoder_is_killed_and_reaped_when_the_run_stops_early(stand_in_decoder, monkeypatch):
    stand_in_decoder("exec cat /dev/zero")   # never ends on its own
    started = []
    popen = subprocess.Popen

    def recording_popen(*arguments, **options):
        started.append(popen(*arguments, **options))
        return started[-1]

    monkeypatch.setattr(segmenter.subprocess, "Popen", recording_popen)

    def cancel(done, total):
        raise RuntimeError("cancelled")

    with time_limit(60), pytest.raises(RuntimeError, match="cancelled"):
        decode_motion_energy(Path("unused.mp4"), PARAMS, duration_ms=2000, progress=cancel)
    (process,) = started
    # A return code is only known after the parent waited for the child, so the child is
    # neither running nor left behind as a zombie.
    assert process.returncode == -signal.SIGKILL
    assert process.stdout.closed


@needs_posix_shell
def test_long_stream_is_processed_with_a_small_rolling_window(stand_in_decoder):
    frames = 20000   # more than an hour at 5 sampled frames per second, 104 MB of pixels
    stand_in_decoder(f"exec head -c {frames * FRAME_BYTES} /dev/zero")
    tracemalloc.start()
    try:
        with time_limit(120):
            energy, done, _ = decode_motion_energy(Path("unused.mp4"), PARAMS, duration_ms=frames * PERIOD_MS)
        peak = tracemalloc.get_traced_memory()[1]
    finally:
        tracemalloc.stop()
    assert (done, energy.size) == (frames, frames - 1)
    # Holding the stream would need at least frames * FRAME_BYTES. The peak has to stay
    # near a few chunks of frames whatever the length of the video.
    assert peak < 8 * 1024 * 1024 < frames * FRAME_BYTES


# --- energy series ---------------------------------------------------------------------

def test_step_edges_map_to_the_middle_of_the_boundary_samples():
    energy = energy_of((0.1, 20), (5, 30), (0.1, 20))
    intervals, status, levels = intervals_from_energy(energy, PARAMS, duration_ms=14200)
    # Samples 20..49 are active: the motion started inside sample 20 and ended inside sample 49.
    assert intervals == [Interval(4100, 9900)]
    assert status == segmenter.OK
    assert levels == (0.1, 5.0, pytest.approx(5.05 / 0.15, abs=1e-3))


def test_run_touching_the_clip_edges_extends_to_them():
    energy = energy_of((5, 20), (0.1, 30), (5, 20))
    intervals, _, _ = intervals_from_energy(energy, PARAMS, duration_ms=14200)
    assert intervals == [Interval(0, 3900), Interval(10100, 14200)]


def test_interval_never_passes_the_end_of_the_picture():
    # 70 samples are 71 frames, so the picture ends at 14.2 s although the file says 20 s.
    energy = energy_of((0.1, 40), (5, 30))
    intervals, _, _ = intervals_from_energy(energy, PARAMS, duration_ms=20000)
    assert intervals == [Interval(8100, 14200)]


def test_short_gap_is_merged_and_long_gap_is_kept():
    merged, _, _ = intervals_from_energy(
        energy_of((0.1, 20), (5, 20), (0.1, 6), (5, 20), (0.1, 20)), PARAMS, duration_ms=20000)
    assert merged == [Interval(4100, 13100)]
    separate, _, _ = intervals_from_energy(
        energy_of((0.1, 20), (5, 20), (0.1, 10), (5, 20), (0.1, 20)), PARAMS, duration_ms=20000)
    assert separate == [Interval(4100, 7900), Interval(10100, 13900)]


def test_single_spike_is_not_a_rally_and_single_dip_does_not_split_one():
    spike = energy_of((0.1, 30), (90, 1), (0.1, 30), (5, 30), (0.1, 30))
    assert intervals_from_energy(spike, PARAMS, duration_ms=30000)[0] == [Interval(12300, 18100)]
    dip = energy_of((0.1, 30), (5, 15), (0.1, 1), (5, 15), (0.1, 30))
    assert intervals_from_energy(dip, PARAMS, duration_ms=30000)[0] == [Interval(6100, 12100)]


def test_runs_shorter_than_the_minimum_are_dropped():
    energy = energy_of((0.1, 40), (5, 8), (0.1, 40), (5, 30), (0.1, 40))
    intervals, status, _ = intervals_from_energy(energy, PARAMS, duration_ms=40000)
    assert intervals == [Interval(17700, 23500)]
    only_short, status, _ = intervals_from_energy(energy_of((0.1, 40), (5, 8), (0.1, 40)), PARAMS, duration_ms=20000)
    assert only_short == [] and status == segmenter.ONLY_SHORT_RUNS


def test_hysteresis_extends_a_run_but_does_not_start_one():
    # With idle 0.1 and active 10 the run starts at about 1.18 and continues down to about 0.38.
    shoulder_only = energy_of((0.1, 60), (0.7, 20), (0.1, 60), (10, 60), (0.1, 60))
    assert intervals_from_energy(shoulder_only, PARAMS, duration_ms=60000)[0] == [Interval(28100, 39900)]
    attached = energy_of((0.1, 60), (0.7, 20), (10, 60), (0.1, 60))
    assert intervals_from_energy(attached, PARAMS, duration_ms=60000)[0] == [Interval(12100, 27900)]


def test_repeated_frames_take_no_part_in_the_levels_and_count_as_idle():
    # Five seconds of exact zeros inside idle time: a hole in the timestamps or a frozen
    # picture. Taken as the idle level, they would turn the idle noise into rallies.
    energy = energy_of((0.9, 50), (0, 25), (0.9, 25), (5, 30), (0.9, 40))
    intervals, status, levels = intervals_from_energy(energy, PARAMS, duration_ms=34200)
    assert status == segmenter.OK
    assert intervals == [Interval(20100, 25900)]
    assert levels[:2] == (0.9, 5.0)


def test_repeated_frames_inside_a_rally_split_it_only_when_they_last():
    one_copy = energy_of((0.9, 30), (5, 15), (0, 1), (5, 15), (0.9, 30))
    assert intervals_from_energy(one_copy, PARAMS, duration_ms=18400)[0] == [Interval(6100, 12100)]
    # Two seconds without a new frame are longer than the gap that is merged.
    frozen = energy_of((0.9, 30), (5, 15), (0, 10), (5, 15), (0.9, 30))
    assert intervals_from_energy(frozen, PARAMS, duration_ms=20200)[0] == [Interval(6100, 8900), Interval(11100, 13900)]


def test_every_second_sample_repeated_does_not_break_the_runs():
    # What sampling at twice the source frame rate produces.
    def doubled(value, count):
        return np.tile([float(value), 0.0], count)

    energy = np.concatenate([doubled(0.9, 20), doubled(5, 20), doubled(0.9, 20)])
    intervals, status, levels = intervals_from_energy(energy, PARAMS, duration_ms=24200)
    assert status == segmenter.OK and levels[:2] == (0.9, 5.0)
    assert intervals == [Interval(8100, 15700)]


def test_lasting_zeros_are_the_rest_when_everything_that_moved_is_one_level():
    # A perfectly still picture between the rallies, as in a screen recording or a
    # synthetic clip. Left out of the levels, the zeros would leave nothing to call idle.
    energy = energy_of((0, 40), (5, 30), (0, 40))
    intervals, status, levels = intervals_from_energy(energy, PARAMS, duration_ms=22200)
    assert status == segmenter.OK and levels[:2] == (0.0, 5.0)
    assert intervals == [Interval(8100, 13900)]


def test_lasting_zeros_are_the_rest_when_the_motion_only_splits_into_short_bursts():
    # The rally itself has a slow and a fast part. Without the zeros the cut would fall
    # between the two, and neither part is long enough to be a rally on its own.
    energy = energy_of((0, 40), (4, 8), (20, 6), (4, 24), (20, 6), (4, 8), (0, 40))
    without_zeros, status, _ = intervals_from_energy(energy[energy > 0], PARAMS, duration_ms=10600)
    assert without_zeros == [] and status == segmenter.ONLY_SHORT_RUNS

    intervals, status, levels = intervals_from_energy(energy, PARAMS, duration_ms=26600)
    assert status == segmenter.OK and levels[0] == 0.0
    assert intervals == [Interval(8100, 18300)]


def test_zeros_that_come_one_at_a_time_are_never_the_rest():
    # A resting picture sampled at twice its frame rate: noise, copy, noise, copy. The
    # copies are not a second level, so the clip has no separation and no rally.
    energy = np.tile([0.9, 0.0], 60)
    assert intervals_from_energy(energy, PARAMS, duration_ms=24200) == ([], segmenter.NO_SEPARATION, None)
    assert intervals_from_energy(np.zeros(100), PARAMS, duration_ms=20200) == ([], segmenter.NO_SEPARATION, None)


def test_a_hole_in_a_clip_without_rallies_is_a_known_false_proposal():
    # Documents a limit, not a wish. Only resting noise and one long hole: the energy can
    # not tell this from a still picture with one stretch of play, so the noise is proposed.
    energy = energy_of((0.9, 50), (0, 25), (0.9, 50))
    intervals, status, _ = intervals_from_energy(energy, PARAMS, duration_ms=25200)
    assert status == segmenter.OK
    assert intervals == [Interval(0, 9900), Interval(15100, 25200)]


def test_no_separation_cases_return_nothing():
    constant = intervals_from_energy(energy_of((2.0, 100)), PARAMS, duration_ms=20000)
    assert constant == ([], segmenter.NO_SEPARATION, None)

    weak, status, levels = intervals_from_energy(energy_of((1.0, 50), (2.0, 50)), PARAMS, duration_ms=20000)
    assert weak == [] and status == segmenter.NO_SEPARATION
    assert levels[2] < PARAMS.min_contrast_ratio

    # Twenty times the idle value, but both are far below the noise floor.
    noise, status, _ = intervals_from_energy(energy_of((0.001, 50), (0.02, 50)), PARAMS, duration_ms=20000)
    assert noise == [] and status == segmenter.NO_SEPARATION


def test_too_few_samples_are_reported():
    assert intervals_from_energy(energy_of((5, 4)), PARAMS, duration_ms=1000) == ([], segmenter.TOO_SHORT, None)
    assert intervals_from_energy(np.zeros(0), PARAMS, duration_ms=100) == ([], segmenter.TOO_SHORT, None)


@pytest.mark.parametrize("override", [
    {"sample_fps": 0}, {"frame_width": 0}, {"min_rally_ms": 0}, {"merge_gap_ms": -1}, {"noise_floor": 0},
    {"min_contrast_ratio": 1.0}, {"enter_level": 0.2, "exit_level": 0.3}, {"exit_level": 0}, {"enter_level": 1.5},
])
def test_invalid_parameters_are_rejected(override):
    with pytest.raises(ValueError):
        SegmenterParams(**override)
