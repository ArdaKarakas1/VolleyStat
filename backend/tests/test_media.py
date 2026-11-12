"""Media validation tests. Every clip is a synthetic ffmpeg test pattern."""
import hashlib

import pytest

from app import media
from tests.test_api import create_match, create_upload


def store(client, data, name="match.mp4", mime="video/mp4"):
    match_id = create_match(client)
    upload = create_upload(
        client, match_id, size=len(data), fileName=name, mimeType=mime,
        clientSha256=hashlib.sha256(data).hexdigest(),
    )
    assert upload.status_code == 201, upload.text
    uid = upload.json()["uploadId"]
    assert client.put(f"/v1/uploads/{uid}/chunks?offset=0", content=data).status_code == 200
    done = client.post(f"/v1/uploads/{uid}/complete")
    assert done.status_code == 200, done.text
    return done.json()


def test_valid_h264_is_ready_and_reaches_the_missing_model(client, make_clip):
    video = store(client, make_clip("hd.mp4"))
    assert video["status"] == "READY"
    quality = video["quality"]
    assert quality["rejectCode"] is None and quality["warnings"] == []
    assert quality["media"]["codec"] == "h264"
    assert (quality["media"]["width"], quality["media"]["height"]) == (1280, 720)
    assert quality["media"]["fpsAverage"] == 30
    assert 900 <= quality["media"]["durationMs"] <= 1100
    assert client.get(f"/v1/videos/{video['id']}/quality").json() == quality
    analysis = client.post(f"/v1/videos/{video['id']}/analyses")
    assert analysis.status_code == 503
    assert analysis.json()["error"]["code"] == "MODEL_NOT_CONFIGURED"


def test_scene_quality_is_reported_as_not_measured(client, make_clip):
    checks = store(client, make_clip("hd.mp4"))["quality"]["sceneChecks"]
    assert set(checks) == {"courtVisible", "ballVisible", "cameraStable", "lighting"}
    assert all(item == {"value": None, "status": "NOT_MEASURED"} for item in checks.values())


def test_low_quality_layers_warn_but_are_not_rejected(client, make_clip):
    small = store(client, make_clip("small.mp4", size="640x360", rate=15))
    assert small["status"] == "READY"
    assert small["quality"]["warnings"] == ["LOW_RESOLUTION", "LOW_FRAME_RATE"]
    portrait = store(client, make_clip("portrait.mp4", size="720x1280"))
    assert portrait["status"] == "READY"
    assert portrait["quality"]["warnings"] == ["PORTRAIT_ORIENTATION"]


def test_webm_vp9_is_ready(client, make_clip):
    data = make_clip("clip.webm", codec=("-c:v", "libvpx-vp9", "-b:v", "200k"))
    video = store(client, data, name="clip.webm", mime="video/webm")
    assert video["status"] == "READY"
    assert video["quality"]["media"]["container"] == "video/webm"


@pytest.mark.parametrize("data", [b"not a video at all", b"\x89PNG\r\n\x1a\n" + b"0" * 64])
def test_non_video_bytes_are_quarantined(client, data):
    video = store(client, data)
    assert video["status"] == "QUARANTINED"
    assert video["quality"]["rejectCode"] == "NOT_A_VIDEO"
    assert video["quality"]["media"] is None
    blocked = client.post(f"/v1/videos/{video['id']}/analyses")
    assert blocked.status_code == 409
    assert blocked.json()["error"]["code"] == "VIDEO_NOT_READY"


def test_truncated_mp4_is_quarantined(client, make_clip):
    data = make_clip("hd.mp4")
    video = store(client, data[: len(data) // 2])
    assert video["status"] == "QUARANTINED"
    assert video["quality"]["rejectCode"] == "VIDEO_CORRUPT"


def test_unsupported_codec_is_quarantined_with_metadata(client, make_clip):
    data = make_clip("old.mp4", codec=("-c:v", "mpeg2video"))
    video = store(client, data)
    assert video["status"] == "QUARANTINED"
    assert video["quality"]["rejectCode"] == "UNSUPPORTED_CODEC"
    assert video["quality"]["media"]["codec"] == "mpeg2video"


def test_missing_ffprobe_never_claims_validation(client, make_clip, monkeypatch):
    def missing(*_args, **_kwargs):
        raise FileNotFoundError("ffprobe")

    monkeypatch.setattr(media.subprocess, "run", missing)
    video = store(client, make_clip("hd.mp4"))
    assert video["status"] == "STORED_UNVALIDATED"
    assert video["quality"]["rejectCode"] == "PROBE_UNAVAILABLE"
    assert client.post(f"/v1/videos/{video['id']}/analyses").status_code == 409
