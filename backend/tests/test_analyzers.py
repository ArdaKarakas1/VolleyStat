"""Analyzer selection and the demo analyzer (docs/API_V1.md, section 2)."""
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import app.__main__ as cli
from app.analyzers import AnalyzerNotAvailable, load_analyzer
from app.analyzers.base import MOCK, UNKNOWN
from app.analyzers.demo import MOCK_DATA, DemoAnalyzer
from app.main import create_app
from tests.helpers import ScriptedAnalyzer, serve
from tests.test_media import store

DEMO = {"id": "demo", "version": "0.1", "kind": "MOCK"}


def health(storage, **options):
    with TestClient(create_app(storage, **options)) as client:
        return client.get("/v1/health").json()


def test_no_analyzer_is_the_default_and_health_says_so(storage):
    body = health(storage)
    assert body["analyzer"] is None and body["ai"] == "not-configured"


@pytest.mark.parametrize("value", ["", "none", " None "])
def test_empty_and_none_in_the_environment_mean_no_analyzer(storage, monkeypatch, value):
    monkeypatch.setenv("VOLLEYSTAT_ANALYZER", value)
    assert health(storage)["analyzer"] is None


def test_environment_variable_selects_the_analyzer(storage, monkeypatch):
    monkeypatch.setenv("VOLLEYSTAT_ANALYZER", "demo")
    body = health(storage)
    assert body["analyzer"] == DEMO
    # A mock is not a trained model.
    assert body["ai"] == "not-configured"


def test_parameter_takes_a_name_or_an_analyzer_object(storage, monkeypatch):
    assert health(storage, analyzer="demo")["analyzer"] == DEMO
    assert health(storage, analyzer=ScriptedAnalyzer())["analyzer"] == {"id": "scripted", "version": "t1", "kind": "BASELINE"}
    # The parameter wins over the environment.
    monkeypatch.setenv("VOLLEYSTAT_ANALYZER", "demo")
    assert health(storage, analyzer="none")["analyzer"] is None


def test_unknown_analyzer_stops_the_start_and_touches_no_storage(tmp_path, monkeypatch):
    target = tmp_path / "never-created"
    with pytest.raises(AnalyzerNotAvailable):
        create_app(target, analyzer="guesswork")
    monkeypatch.setenv("VOLLEYSTAT_ANALYZER", "guesswork")
    with pytest.raises(AnalyzerNotAvailable):
        create_app(target)
    assert not target.exists()


def test_without_an_analyzer_the_analyses_route_keeps_its_answers(storage, make_clip, monkeypatch):
    monkeypatch.setenv("VOLLEYSTAT_ANALYZER", "none")
    with serve(storage, None) as client:
        ready = store(client, make_clip("hd.mp4"))
        refused = client.post(f"/v1/videos/{ready['id']}/analyses", headers={"Idempotency-Key": "k"})
        assert refused.status_code == 503 and refused.json()["error"]["code"] == "MODEL_NOT_CONFIGURED"
        quarantined = store(client, b"not a video at all")
        blocked = client.post(f"/v1/videos/{quarantined['id']}/analyses")
        assert blocked.status_code == 409 and blocked.json()["error"]["code"] == "VIDEO_NOT_READY"
        assert client.post("/v1/videos/unknown/analyses").status_code == 404
        assert client.get(f"/v1/videos/{ready['id']}/analyses/latest").json()["error"]["code"] == "ANALYSIS_NOT_FOUND"


def test_a_video_that_is_not_ready_is_refused_before_the_analyzer_is_asked(storage):
    analyzer = ScriptedAnalyzer()
    with serve(storage, analyzer) as client:
        quarantined = store(client, b"not a video at all")
        blocked = client.post(f"/v1/videos/{quarantined['id']}/analyses")
        assert blocked.status_code == 409 and blocked.json()["error"]["code"] == "VIDEO_NOT_READY"
        assert client.app.state.worker.run_pending() == 0 and analyzer.calls == 0


def run_cli(monkeypatch, storage, *arguments):
    started = {}
    monkeypatch.setenv("VOLLEYSTAT_STORAGE", str(storage))
    monkeypatch.setattr(sys, "argv", ["app", *arguments])
    monkeypatch.setattr(cli.uvicorn, "run", lambda app, **options: started.update(app=app, **options))
    cli.main()
    return started


def test_command_line_selects_the_analyzer_and_binds_to_loopback(storage, monkeypatch):
    started = run_cli(monkeypatch, storage, "--analyzer", "demo", "--port", "8123")
    assert (started["host"], started["port"]) == ("127.0.0.1", 8123)
    with TestClient(started["app"]) as client:
        assert client.get("/v1/health").json()["analyzer"] == DEMO


def test_command_line_falls_back_to_the_environment(storage, monkeypatch):
    assert_none = run_cli(monkeypatch, storage)
    with TestClient(assert_none["app"]) as client:
        assert client.get("/v1/health").json()["analyzer"] is None
    monkeypatch.setenv("VOLLEYSTAT_ANALYZER", "demo")
    with TestClient(run_cli(monkeypatch, storage)["app"]) as client:
        assert client.get("/v1/health").json()["analyzer"] == DEMO
    with TestClient(run_cli(monkeypatch, storage, "--analyzer", "none")["app"]) as client:
        assert client.get("/v1/health").json()["analyzer"] is None


def test_command_line_refuses_an_unknown_analyzer(storage, monkeypatch, capsys):
    with pytest.raises(SystemExit):
        run_cli(monkeypatch, storage, "--analyzer", "guesswork")
    assert "guesswork" in capsys.readouterr().err


# --- demo analyzer ---------------------------------------------------------------------------

def demo(duration_ms):
    def no_report(*_call):
        raise AssertionError("the demo analyzer has no real stage to report")

    return DemoAnalyzer().analyze(Path("unused.mp4"), {"durationMs": duration_ms}, no_report)


def test_demo_is_registered_as_a_mock():
    analyzer = load_analyzer("demo")
    assert isinstance(analyzer, DemoAnalyzer)
    assert (analyzer.info.id, analyzer.info.kind) == ("demo", MOCK)


@pytest.mark.parametrize("duration_ms", [1, 3, 400, 1_000, 11_999, 12_000, 60_000, 95_500, 3_600_000, 12 * 3_600_000])
def test_demo_never_proposes_a_winner_or_a_confidence(duration_ms):
    for rally in demo(duration_ms):
        assert rally.proposed_winner == UNKNOWN
        assert rally.confidence is None
        assert rally.uncertainty_reasons == (MOCK_DATA,)


@pytest.mark.parametrize("duration_ms", [400, 1_000, 11_999, 12_000, 60_000, 95_500, 3_600_000])
def test_demo_intervals_are_ordered_and_inside_the_video(duration_ms):
    rallies = demo(duration_ms)
    assert rallies, "a clip of this length must give at least one card to try the screens with"
    previous_end = 0
    for rally in rallies:
        assert previous_end <= rally.start_ms < rally.end_ms <= duration_ms
        previous_end = rally.end_ms


def test_demo_depends_on_the_duration_only():
    assert demo(60_000) == demo(60_000)
    assert [(rally.start_ms, rally.end_ms) for rally in demo(60_000)] == [(4_000, 12_000), (24_000, 32_000), (44_000, 52_000)]
    assert [(rally.start_ms, rally.end_ms) for rally in demo(1_000)] == [(250, 750)]
    one, other = DemoAnalyzer(), DemoAnalyzer()
    ignore = lambda *_call: None
    assert one.analyze(Path("a.mp4"), {"durationMs": 95_500, "width": 1280}, ignore) == \
        other.analyze(Path("b.webm"), {"durationMs": 95_500, "width": 640}, ignore)


def test_demo_gives_nothing_for_a_video_too_short_to_hold_an_interval():
    assert demo(1) == []
