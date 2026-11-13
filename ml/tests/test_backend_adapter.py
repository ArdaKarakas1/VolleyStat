"""Tests of backend/app/analyzers/motion_baseline.py.

They live here and not in backend/tests because the backend test run does not
install volleystat_ml. The clips are synthetic, so these tests prove the plumbing only.
"""
import importlib
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

from volleystat_ml import segmenter
from volleystat_ml.metrics import evaluate_boundaries

BACKEND = Path(__file__).resolve().parents[2] / "backend"
MP4 = {"container": "video/mp4", "codec": "h264", "durationMs": 40000, "width": 320, "height": 180}


@pytest.fixture(scope="module")
def backend():
    # The backend is not an installed package. Its folder goes on the import path for
    # this module only, so the name `app` does not leak into the other test modules.
    with pytest.MonkeyPatch.context() as patch:
        patch.syspath_prepend(str(BACKEND))
        yield SimpleNamespace(
            base=importlib.import_module("app.analyzers.base"),
            registry=importlib.import_module("app.analyzers"),
            adapter=importlib.import_module("app.analyzers.motion_baseline"),
        )
    for name in [name for name in sys.modules if name == "app" or name.startswith("app.")]:
        del sys.modules[name]


def recorder():
    calls = []
    return calls, lambda stage, done, total, unit: calls.append((stage, done, total, unit))


def ignore(stage, done, total, unit):
    pass


def test_identity_is_the_baseline_named_in_the_contract(backend):
    info = backend.adapter.MotionBaselineAnalyzer.info
    assert info == backend.base.AnalyzerInfo(id="motion-baseline", version=segmenter.ALGORITHM_VERSION, kind="BASELINE")
    assert info.kind == backend.base.BASELINE
    assert info.kind not in (backend.base.MOCK, backend.base.MODEL)


def test_registry_loads_the_adapter_under_its_contract_name(backend):
    analyzer = backend.registry.load_analyzer("motion-baseline")
    assert isinstance(analyzer, backend.adapter.MotionBaselineAnalyzer)
    assert analyzer.params == segmenter.SegmenterParams()


def test_proposals_cover_the_known_stretches_and_never_name_a_winner(backend, clips):
    calls, report = recorder()
    proposals = backend.adapter.MotionBaselineAnalyzer().analyze(clips.moving_box, MP4, report)

    assert len(proposals) == 3
    for proposal in proposals:
        assert isinstance(proposal, backend.base.RallyProposal)
        assert isinstance(proposal.start_ms, int) and isinstance(proposal.end_ms, int)
        assert proposal.proposed_winner == backend.base.UNKNOWN
        assert proposal.confidence is None
        assert proposal.uncertainty_reasons == ("NO_WINNER_MODEL",)
    assert [proposal.start_ms for proposal in proposals] == sorted(proposal.start_ms for proposal in proposals)
    evaluation = evaluate_boundaries(clips.rallies_ms, [(p.start_ms, p.end_ms) for p in proposals])
    assert (evaluation.tp, evaluation.fp, evaluation.fn) == (3, 0, 0)

    # Progress is the measured frame count under the one stage this analyzer really has.
    assert len(calls) > 1
    assert {(stage, unit) for stage, _, _, unit in calls} == {("DETECTING_RALLIES", "FRAMES")}
    assert all(0 < done <= total for _, done, total, _ in calls)
    assert calls[-1] == ("DETECTING_RALLIES", 200, 200, "FRAMES")


def test_clip_without_separation_gives_no_proposal_instead_of_a_guess(backend, clips):
    analyzer = backend.adapter.MotionBaselineAnalyzer()
    media = {**MP4, "durationMs": 20000}
    # An empty list is what makes the job fail with NO_RALLIES_FOUND.
    assert analyzer.analyze(clips.still_noisy, media, ignore) == []
    assert analyzer.analyze(clips.busy, media, ignore) == []


def test_parameters_can_be_replaced(backend, clips):
    analyzer = backend.adapter.MotionBaselineAnalyzer(segmenter.SegmenterParams(min_rally_ms=7000))
    proposals = analyzer.analyze(clips.moving_box, MP4, ignore)
    assert len(proposals) == 1 and proposals[0].end_ms - proposals[0].start_ms >= 7000


def test_stored_container_selects_the_demuxer_and_missing_media_fields_are_probed(backend, clips):
    analyzer = backend.adapter.MotionBaselineAnalyzer()
    calls, report = recorder()
    analyzer.analyze(clips.webm, {"container": "video/webm", "durationMs": 6000}, report)
    assert calls[-1] == ("DETECTING_RALLIES", 30, 30, "FRAMES")

    calls, report = recorder()
    assert len(analyzer.analyze(clips.moving_box, {}, report)) == 3
    assert calls[-1] == ("DETECTING_RALLIES", 200, 200, "FRAMES")

    # The stored container is trusted over the content of the file, as in the upload probe.
    with pytest.raises(backend.base.AnalyzerError) as failure:
        analyzer.analyze(clips.webm, {"container": "video/mp4", "durationMs": 6000}, ignore)
    assert failure.value.code == "VIDEO_UNREADABLE"


def test_unreadable_video_fails_with_the_contract_code(backend, clips):
    with pytest.raises(backend.base.AnalyzerError) as failure:
        backend.adapter.MotionBaselineAnalyzer().analyze(clips.not_a_video, {**MP4, "durationMs": 5000}, ignore)
    assert failure.value.code == "VIDEO_UNREADABLE"
    assert "ffmpeg could not decode the video" in str(failure.value)


@pytest.mark.parametrize("clip", ["truncated", "damaged"])
def test_partly_decoded_video_fails_the_job_instead_of_giving_partial_proposals(backend, clips, clip):
    # ffmpeg exits with 0 for these files. Proposals for the part it could read would be
    # stored as if the whole video had been analysed.
    with pytest.raises(backend.base.AnalyzerError) as failure:
        backend.adapter.MotionBaselineAnalyzer().analyze(getattr(clips, clip), MP4, ignore)
    assert failure.value.code == "VIDEO_UNREADABLE"
    assert "could not decode every part of the video" in str(failure.value)


def test_proposals_count_from_the_first_video_frame_when_the_container_starts_later(backend, clips):
    # The probe stores the container duration, which here runs 5 s past the last frame.
    media = {"container": "video/x-matroska", "codec": "h264", "durationMs": 45000}
    proposals = backend.adapter.MotionBaselineAnalyzer().analyze(clips.offset_start, media, ignore)
    evaluation = evaluate_boundaries(clips.rallies_ms, [(p.start_ms, p.end_ms) for p in proposals])
    assert (evaluation.tp, evaluation.fp, evaluation.fn) == (3, 0, 0)


def test_proposals_are_clamped_to_the_stored_duration(backend, clips, monkeypatch):
    def outside(path, params, *, duration_ms, demuxer, progress):
        intervals = (segmenter.Interval(-300, 4000), segmenter.Interval(9000, 12400), segmenter.Interval(12100, 15000))
        return segmenter.SegmentationResult(segmenter.OK, intervals, duration_ms, 60, 60)

    monkeypatch.setattr(segmenter, "segment_video", outside)
    proposals = backend.adapter.MotionBaselineAnalyzer().analyze(clips.busy, {**MP4, "durationMs": 12000}, ignore)
    # Nothing can be played before 0 or after durationMs; an interval that lies outside
    # completely has nothing left to show.
    assert [(proposal.start_ms, proposal.end_ms) for proposal in proposals] == [(0, 4000), (9000, 12000)]


def test_missing_ffmpeg_is_an_analyzer_error_not_an_unreadable_video(backend, clips, monkeypatch, tmp_path):
    monkeypatch.setenv("PATH", str(tmp_path))
    with pytest.raises(backend.base.AnalyzerError) as failure:
        backend.adapter.MotionBaselineAnalyzer().analyze(clips.busy, {**MP4, "durationMs": 20000}, ignore)
    assert failure.value.code == "ANALYZER_ERROR"
    assert "ffmpeg is not installed" in str(failure.value)


def test_exception_from_the_reporter_passes_through_unchanged(backend, clips):
    class Cancelled(Exception):
        pass

    def cancel(stage, done, total, unit):
        raise Cancelled()

    # The worker stops a job by raising from the reporter. Wrapping that in an
    # AnalyzerError would turn a cancellation into a failed job.
    with pytest.raises(Cancelled):
        backend.adapter.MotionBaselineAnalyzer().analyze(clips.moving_box, MP4, cancel)


def test_missing_package_is_reported_with_the_way_to_install_it(backend, monkeypatch):
    # None in sys.modules makes `import volleystat_ml` fail as it does when the package is absent.
    monkeypatch.setitem(sys.modules, "volleystat_ml", None)
    monkeypatch.delitem(sys.modules, "app.analyzers.motion_baseline")

    with pytest.raises(ImportError, match="needs the volleystat_ml package, which is not installed") as failure:
        importlib.import_module("app.analyzers.motion_baseline")
    assert "pip install -e ml" in str(failure.value)

    with pytest.raises(backend.registry.AnalyzerNotAvailable, match="needs the volleystat_ml package"):
        backend.registry.load_analyzer("motion-baseline")
