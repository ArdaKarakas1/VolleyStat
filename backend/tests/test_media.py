"""Media validation tests. Every clip is a synthetic ffmpeg test pattern."""
import hashlib
import subprocess
import sys
import tracemalloc

import pytest

from app import media
from tests.test_api import create_match, create_upload

FASTSTART = ("-movflags", "+faststart")


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
    small = store(client, make_clip("small.mp4", size="640x360", rate="15"))
    assert small["status"] == "READY"
    assert small["quality"]["warnings"] == ["LOW_RESOLUTION", "LOW_FRAME_RATE"]
    portrait = store(client, make_clip("portrait.mp4", size="720x1280"))
    assert portrait["status"] == "READY"
    assert portrait["quality"]["warnings"] == ["PORTRAIT_ORIENTATION"]


def test_film_rate_24000_1001_is_not_a_low_frame_rate(client, make_clip):
    video = store(client, make_clip("ntsc.mp4", rate="24000/1001"))
    assert video["status"] == "READY"
    assert video["quality"]["media"]["fpsAverage"] == 23.976
    assert video["quality"]["warnings"] == []


def test_webm_vp9_is_ready(client, make_clip):
    data = make_clip("clip.webm", codec=("-c:v", "libvpx-vp9", "-b:v", "200k"))
    video = store(client, data, name="clip.webm", mime="video/webm")
    assert video["status"] == "READY"
    assert video["quality"]["media"]["container"] == "video/webm"
    assert video["quality"]["warnings"] == []


def test_declared_type_that_differs_from_the_real_container_warns(client, make_clip):
    data = make_clip("clip.webm", codec=("-c:v", "libvpx-vp9", "-b:v", "200k"))
    video = store(client, data, name="renamed.mp4", mime="video/mp4")
    assert video["status"] == "READY"
    assert video["quality"]["media"]["container"] == "video/webm"
    assert video["quality"]["warnings"] == ["CONTAINER_MISMATCH"]


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


@pytest.mark.parametrize("keep", [0.5, 0.97])
def test_truncated_faststart_mp4_is_quarantined(client, make_clip, keep):
    # The index sits at the front, so the headers alone still describe the full video.
    data = make_clip("fast.mp4", size="640x360", seconds=6, audio_seconds=6, output=FASTSTART)
    video = store(client, data[: int(len(data) * keep)])
    assert video["status"] == "QUARANTINED"
    assert video["quality"]["rejectCode"] == "VIDEO_CORRUPT"


@pytest.mark.parametrize("keep", [0.5, 0.97])
def test_truncated_webm_is_quarantined(client, make_clip, keep):
    data = make_clip("long.webm", size="640x360", seconds=6, codec=("-c:v", "libvpx-vp9", "-b:v", "300k"))
    video = store(client, data[: int(len(data) * keep)], name="long.webm", mime="video/webm")
    assert video["status"] == "QUARANTINED"
    assert video["quality"]["rejectCode"] == "VIDEO_CORRUPT"
    assert video["quality"]["media"]["codec"] == "vp9"


def test_complete_files_with_uneven_streams_stay_ready(client, make_clip):
    # Audio runs longer than video: the end of the file holds audio packets only.
    data = make_clip("uneven.mp4", size="640x360", seconds=2, audio_seconds=5, output=FASTSTART)
    video = store(client, data)
    assert video["status"] == "READY"
    assert 4800 <= video["quality"]["media"]["durationMs"] <= 5200


def test_unsupported_codec_is_quarantined_with_metadata(client, make_clip):
    data = make_clip("old.mp4", codec=("-c:v", "mpeg2video"))
    video = store(client, data)
    assert video["status"] == "QUARANTINED"
    assert video["quality"]["rejectCode"] == "UNSUPPORTED_CODEC"
    assert video["quality"]["media"]["codec"] == "mpeg2video"


def test_missing_ffprobe_never_claims_validation_and_can_be_rechecked(client, make_clip, monkeypatch):
    def missing(*_args, **_kwargs):
        raise FileNotFoundError("ffprobe")

    real_run = media.subprocess.run
    monkeypatch.setattr(media.subprocess, "run", missing)
    video = store(client, make_clip("hd.mp4"))
    assert video["status"] == "STORED_UNVALIDATED"
    assert video["quality"]["rejectCode"] == "PROBE_UNAVAILABLE"
    assert client.post(f"/v1/videos/{video['id']}/analyses").status_code == 409
    # ffprobe gets installed later.
    monkeypatch.setattr(media.subprocess, "run", real_run)
    rechecked = client.post(f"/v1/videos/{video['id']}/quality")
    assert rechecked.status_code == 200
    assert (rechecked.json()["status"], rechecked.json()["rejectCode"]) == ("READY", None)
    assert client.get(f"/v1/videos/{video['id']}").json()["status"] == "READY"


def test_probe_timeout_is_not_reported_as_a_corrupt_file(client, make_clip, monkeypatch):
    def slow(command, **_kwargs):
        raise subprocess.TimeoutExpired(command, 90)

    monkeypatch.setattr(media.subprocess, "run", slow)
    video = store(client, make_clip("hd.mp4"))
    assert video["status"] == "STORED_UNVALIDATED"
    assert video["quality"]["rejectCode"] == "PROBE_TIMEOUT"


def test_the_tail_check_keeps_no_packet_listing_in_memory(make_clip, tmp_path):
    # 20 000 packets inside the tail window. ffprobe prints well over 300 KiB for them in any format.
    path = tmp_path / "dense.mp4"
    path.write_bytes(make_clip("dense.mp4", size="64x36", rate="20000",
                               codec=("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p")))
    tracemalloc.start()
    try:
        assert media._tail_is_readable(path, "mov", 1.0) is True
        peak = tracemalloc.get_traced_memory()[1]
    finally:
        tracemalloc.stop()
    assert peak < 256 * 1024


def stand_in_for_ffprobe(monkeypatch, script):
    """Run a short Python script in place of ffprobe; return the list its processes are appended to."""
    started = []
    real_popen = subprocess.Popen

    def launch(_command, **options):
        process = real_popen([sys.executable, "-c", script], **options)
        started.append(process)
        return process

    monkeypatch.setattr(media.subprocess, "Popen", launch)
    return started


def test_a_tail_check_that_runs_too_long_is_stopped_and_counts_as_a_timeout(tmp_path, monkeypatch):
    started = stand_in_for_ffprobe(monkeypatch, "import time; print('0.5,0.5', flush=True); time.sleep(60)")
    monkeypatch.setattr(media, "PROBE_TIMEOUT_SECONDS", 0.3)
    assert media._tail_is_readable(tmp_path / "any.mp4", "mov", 1.0) is None
    # The process was stopped; it does not keep running behind the answer.
    assert len(started) == 1 and started[0].poll() is not None


def test_the_tail_check_reads_a_long_error_stream_next_to_the_packets(tmp_path, monkeypatch):
    # More than a pipe holds on both streams: reading one after the other would block forever.
    script = ("import sys\n"
              "for _ in range(20000): sys.stderr.write('[mov] noise ' + 'x' * 80 + '\\n')\n"
              "sys.stderr.write('[mov] stream 1, offset 0x4e78: partial file\\n')\n"
              "for index in range(20000): sys.stdout.write(f'{index / 10000:.6f},{index / 10000:.6f}\\n')\n")
    stand_in_for_ffprobe(monkeypatch, script)
    monkeypatch.setattr(media, "PROBE_TIMEOUT_SECONDS", 30)
    # The packets reach the end of the video, but the error stream names a truncation.
    assert media._tail_is_readable(tmp_path / "any.mp4", "mov", 2.0) is False


@pytest.mark.parametrize("listing, expected", [
    ("0.500000,0.500000\n1.966667,1.900000\n", True),
    ("N/A,1.950000\n", True),                          # no presentation time: the decode time counts
    ("-0.023220,-0.023220,\n1.950000,1.950000,\n", True),  # a packet with side data ends with an empty field
    ("0.100000,0.100000\n0.200000,0.200000\n", False),  # the packets stop long before the end
    ("N/A,N/A\n", False),
    ("", False),
])
def test_the_tail_check_decides_from_the_latest_packet_time(tmp_path, monkeypatch, listing, expected):
    stand_in_for_ffprobe(monkeypatch, f"import sys; sys.stdout.write({listing!r})")
    assert media._tail_is_readable(tmp_path / "any.mp4", "mov", 3.9) is expected


def test_a_failed_tail_check_means_an_unreadable_tail(tmp_path, monkeypatch):
    stand_in_for_ffprobe(monkeypatch, "import sys; print('3.900000,3.900000'); sys.exit(1)")
    assert media._tail_is_readable(tmp_path / "any.mp4", "mov", 3.9) is False


def test_recheck_leaves_a_decided_video_untouched(client, make_clip):
    video = store(client, b"not a video at all")
    again = client.post(f"/v1/videos/{video['id']}/quality")
    assert again.status_code == 200 and again.json() == video["quality"]
    assert client.post("/v1/videos/unknown/quality").status_code == 404


def test_absurd_rotation_tags_are_ignored():
    assert media._rotation({"tags": {"rotate": "inf"}}) == 0
    assert media._rotation({"tags": {"rotate": "nan"}}) == 0
    assert media._rotation({"side_data_list": [{"rotation": -90}]}) == 270
