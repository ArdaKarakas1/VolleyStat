"""Evidence playback: video bytes with HTTP Range (docs/API_V1.md, section 6)."""
import pytest

from app import content, media
from app.content import RangeNotSatisfiable, parse_range
from tests.helpers import rows, tiny_clip
from tests.test_media import store


@pytest.fixture
def stored(client, make_clip):
    data = tiny_clip(make_clip)
    video = store(client, data)
    assert video["status"] == "READY"
    return f"/v1/videos/{video['id']}/content", data


# --- the range header ------------------------------------------------------------------------

@pytest.mark.parametrize("header, expected", [
    (None, None),
    ("", None),
    ("bytes=0-99", (0, 99)),
    ("bytes=0-0", (0, 0)),
    ("bytes=100-", (100, 999)),
    ("bytes=999-", (999, 999)),
    ("bytes=-50", (950, 999)),
    ("bytes=-1", (999, 999)),
    ("bytes=10-5000", (10, 999)),       # the end is cut to the file
    ("bytes=-5000", (0, 999)),          # a suffix longer than the file is the whole file
    ("Bytes = 5 - 9", (5, 9)),
    ("items=0-5", None),                # an unknown unit is ignored
    ("bytes=0-1,5-6", None),            # several ranges are answered with the whole file
])
def test_parse_range_of_a_1000_byte_file(header, expected):
    assert parse_range(header, 1000) == expected


@pytest.mark.parametrize("header", [
    "bytes=1000-", "bytes=1000-1001", "bytes=5-2", "bytes=-0", "bytes=-", "bytes=", "bytes=abc", "bytes=1-2-3",
    "bytes=0x10-", "bytes=١-٢", "bytes=" + "9" * 20 + "-",
])
def test_parse_range_refuses_what_it_can_not_satisfy(header):
    with pytest.raises(RangeNotSatisfiable):
        parse_range(header, 1000)


# --- the route -------------------------------------------------------------------------------

def test_the_whole_video_is_served_without_a_range(client, stored):
    url, data = stored
    response = client.get(url)
    assert response.status_code == 200
    assert response.content == data
    assert response.headers["content-type"] == "video/mp4"
    assert response.headers["accept-ranges"] == "bytes"
    assert response.headers["content-length"] == str(len(data))
    assert "content-range" not in response.headers


@pytest.mark.parametrize("header, bounds", [
    ("bytes=0-99", lambda size: (0, 99)),
    ("bytes=0-0", lambda size: (0, 0)),
    ("bytes=1000-", lambda size: (1000, size - 1)),
    ("bytes=-500", lambda size: (size - 500, size - 1)),
    ("bytes=0-", lambda size: (0, size - 1)),
    ("bytes=10-999999999", lambda size: (10, size - 1)),
])
def test_a_byte_range_is_answered_with_206_and_exactly_those_bytes(client, stored, header, bounds):
    url, data = stored
    first, last = bounds(len(data))
    response = client.get(url, headers={"Range": header})
    assert response.status_code == 206
    assert response.content == data[first:last + 1]
    assert response.headers["content-range"] == f"bytes {first}-{last}/{len(data)}"
    assert response.headers["content-length"] == str(last - first + 1)
    assert response.headers["accept-ranges"] == "bytes"
    assert response.headers["content-type"] == "video/mp4"


def test_a_range_longer_than_one_read_block_arrives_complete(client, stored, monkeypatch):
    url, data = stored
    monkeypatch.setattr(content, "BLOCK_SIZE", 1000)
    response = client.get(url, headers={"Range": "bytes=500-4999"})
    assert response.status_code == 206 and response.content == data[500:5000]
    assert client.get(url).content == data


def test_consecutive_ranges_rebuild_the_file(client, stored):
    url, data = stored
    step = len(data) // 3 + 1
    parts = [client.get(url, headers={"Range": f"bytes={start}-{start + step - 1}"}) for start in range(0, len(data), step)]
    assert all(part.status_code == 206 for part in parts)
    assert b"".join(part.content for part in parts) == data


@pytest.mark.parametrize("header", ["bytes={size}-", "bytes={size}-{size}", "bytes=999999999-", "bytes=5-2", "bytes=-0", "bytes=abc"])
def test_an_unsatisfiable_range_is_answered_with_416(client, stored, header):
    url, data = stored
    response = client.get(url, headers={"Range": header.format(size=len(data))})
    assert response.status_code == 416
    assert response.headers["content-range"] == f"bytes */{len(data)}"
    assert response.headers["accept-ranges"] == "bytes"
    # The error has the envelope of every other error of this API.
    assert response.json() == {"error": {"code": "RANGE_NOT_SATISFIABLE", "message": response.json()["error"]["message"],
                                         "details": {"sizeBytes": len(data)}}}


@pytest.mark.parametrize("headers", [
    {"Range": "items=0-5"},
    {"Range": "bytes=0-1,5-6"},
    # No validator is sent with the video, so a conditional range can never match.
    {"Range": "bytes=0-99", "If-Range": '"some-etag"'},
])
def test_a_range_the_server_does_not_honour_gets_the_whole_video(client, stored, headers):
    url, data = stored
    response = client.get(url, headers=headers)
    assert response.status_code == 200 and response.content == data
    assert "content-range" not in response.headers


def test_webm_is_served_with_its_own_content_type(client, make_clip):
    data = make_clip("tiny.webm", size="320x180", codec=("-c:v", "libvpx-vp9", "-b:v", "100k"))
    video = store(client, data, name="clip.webm", mime="video/webm")
    response = client.get(f"/v1/videos/{video['id']}/content", headers={"Range": "bytes=0-3"})
    assert response.status_code == 206 and response.content == data[:4]
    assert response.headers["content-type"] == "video/webm"


# --- only READY videos -----------------------------------------------------------------------

def test_a_quarantined_video_is_not_served(client):
    video = store(client, b"not a video at all")
    for headers in ({}, {"Range": "bytes=0-3"}):
        refused = client.get(f"/v1/videos/{video['id']}/content", headers=headers)
        assert refused.status_code == 409
        assert refused.json()["error"]["code"] == "VIDEO_NOT_READY"
        assert refused.json()["error"]["details"] == {"videoStatus": "QUARANTINED", "rejectCode": "NOT_A_VIDEO"}
        assert b"not a video" not in refused.content


def test_an_unvalidated_video_is_not_served(client, make_clip, monkeypatch):
    def missing(*_args, **_kwargs):
        raise FileNotFoundError("ffprobe")

    monkeypatch.setattr(media.subprocess, "run", missing)
    video = store(client, tiny_clip(make_clip))
    assert video["status"] == "STORED_UNVALIDATED"
    refused = client.get(f"/v1/videos/{video['id']}/content")
    assert refused.status_code == 409 and refused.json()["error"]["code"] == "VIDEO_NOT_READY"


def test_unknown_and_deleted_videos_answer_404(client, stored):
    url, _ = stored
    unknown = client.get("/v1/videos/unknown/content")
    assert unknown.status_code == 404 and unknown.json()["error"]["code"] == "VIDEO_NOT_FOUND"
    assert client.delete(url.removesuffix("/content")).status_code == 204
    assert client.get(url, headers={"Range": "bytes=0-9"}).status_code == 404


def test_missing_bytes_are_reported_instead_of_an_empty_video(client, stored, storage):
    url, _ = stored
    (storage / rows(storage, "SELECT storage_key FROM videos")[0]["storage_key"]).unlink()
    missing = client.get(url)
    assert missing.status_code == 409 and missing.json()["error"]["code"] == "VIDEO_FILE_MISSING"
