"""Team logos: the strict lossy WebP check, PUT, DELETE and GET of .../logo, and what stays in the files (docs/API_V1.md,
section 10, Takımlar; design v5.1 sections 4.2, 4.5 and 4.6; ADR-0120).

The server accepts only a simple lossy WebP: RIFF, WEBP and one VP8 key frame that ends the file, of at most 256 px
and 36 KiB for the large logo and 128 px and 8 KiB for the small one. It never decodes pixels. Every file here is a
synthetic test pattern or a header a builder made (tests.webp_fixtures); none shows a real image.
"""
import json
import sqlite3
import uuid

import pytest

from app import logo
from app.bodylimit import MAX_JSON_BODY_BYTES
from tests.helpers import as_account, create_team, rows, seed_member
from tests.test_access import refused_as, snapshot
from tests.test_match_delete import (  # noqa: F401 (cheap_hashing is a fixture)
    DELETED_NAME, cheap_hashing, database_bytes, delete_account, deleting_account,
)
from tests.webp_fixtures import (
    KIB, LARGE, SMALL, SOLID_16, SOLID_128, SOLID_256, animated, b64, chunk, first_chunk, frame_tag, header_of_size,
    logo_body, odd_payload, of_length, padded, riff, simple, then_chunk, trailing, vp8_payload, vp8l, vp8x,
    vp8x_alph, vp8x_exif, vp8x_icc, vp8x_xmp, with_frame,
)

LARGE_LIMITS = (256, 36 * KIB)
SMALL_LIMITS = (128, 8 * KIB)


def logo_url(team_id, size=None):
    return f"/v1/teams/{team_id}/logo" + (f"?size={size}" if size else "")


def put_logo(caller, team_id, large=LARGE, small=SMALL):
    return caller.put(logo_url(team_id), json=logo_body(large, small))


def read_logo(caller, team_id, size, **headers):
    return caller.get(logo_url(team_id, size), headers=headers)


def logo_columns(storage, team_id):
    return rows(storage, "SELECT logo, logo_small, logo_version FROM teams WHERE id=?", team_id)[0]


def with_tag(data, *, key=True, version=0, shown=True, partition=None):
    """The file with a frame tag built from its fields: key frame (bit 0 clear), version (bits 1-3), shown
    (bit 4) and the length of the first partition (bits 5-23, kept when None)."""
    if partition is None:
        partition = frame_tag(data) >> 5
    return with_frame(data, tag=(0 if key else 1) | (version << 1) | ((1 if shown else 0) << 4) | (partition << 5))


# --- the check alone -------------------------------------------------------------------------------------------

def test_check_lossy_webp_reads_the_frame_header():
    # The real test patterns, with the size their frame header names.
    assert logo.check_lossy_webp(SOLID_16, *LARGE_LIMITS) == (16, 16)
    assert logo.check_lossy_webp(SOLID_128, *SMALL_LIMITS) == (128, 128)
    assert logo.check_lossy_webp(SOLID_256, *LARGE_LIMITS) == (256, 256)
    # The width and the height come from the header: 1 x 1 and 256 x 256 are accepted, 0 is refused.
    assert logo.check_lossy_webp(header_of_size(1, 1), *LARGE_LIMITS) == (1, 1)
    assert logo.check_lossy_webp(header_of_size(256, 256), *LARGE_LIMITS) == (256, 256)
    assert logo.check_lossy_webp(header_of_size(128, 3), *SMALL_LIMITS) == (128, 3)
    assert logo.check_lossy_webp(header_of_size(7, 256), *LARGE_LIMITS) == (7, 256)
    for width, height, side in ((0, 16, "width"), (16, 0, "height"), (0, 0, "width")):
        with pytest.raises(ValueError, match=side):
            logo.check_lossy_webp(header_of_size(width, height), *LARGE_LIMITS)


@pytest.mark.parametrize("data, limits", [
    pytest.param(of_length(LARGE, 36 * KIB), LARGE_LIMITS, id="large of exactly 36 KiB"),
    pytest.param(of_length(SMALL, 8 * KIB), SMALL_LIMITS, id="small of exactly 8 KiB"),
    pytest.param(odd_payload(), LARGE_LIMITS, id="an odd payload with its zero pad byte"),
    pytest.param(with_tag(SOLID_16, partition=len(vp8_payload(SOLID_16)) - 10), LARGE_LIMITS,
                 id="a first partition that fills the chunk"),
    pytest.param(with_tag(SOLID_16, version=3), LARGE_LIMITS, id="frame version 3"),
])
def test_check_lossy_webp_accepts_the_limits_of_the_rule(data, limits):
    logo.check_lossy_webp(data, *limits)


def _hidden_and_inter(data):
    return with_tag(data, key=False, shown=False)


REFUSALS = [
    # What the file is.
    pytest.param(b"", LARGE_LIMITS, "too short", id="empty"),
    pytest.param(SOLID_16[:29], LARGE_LIMITS, "too short", id="shorter than the headers"),
    pytest.param(b"\x89PNG\r\n\x1a\n" + bytes(40), LARGE_LIMITS, "not a RIFF file", id="PNG"),
    pytest.param(b"RIFX" + SOLID_16[4:], LARGE_LIMITS, "not a RIFF file", id="big-endian RIFX"),
    pytest.param(SOLID_16[:8] + b"WAVE" + SOLID_16[12:], LARGE_LIMITS, "not a WebP file", id="RIFF WAVE"),
    pytest.param(riff(chunk(b"VP8 ", vp8_payload(SOLID_16)), size_change=2), LARGE_LIMITS, "RIFF size",
                 id="RIFF size 2 more"),
    pytest.param(riff(chunk(b"VP8 ", vp8_payload(SOLID_16)), size_change=-2), LARGE_LIMITS, "RIFF size",
                 id="RIFF size 2 less"),
    pytest.param(SOLID_16 + b"\x00\x00", LARGE_LIMITS, "RIFF size", id="bytes after the RIFF"),
    # Only a simple lossy file: one VP8 chunk first.
    pytest.param(vp8l(), LARGE_LIMITS, "first chunk", id="VP8L"),
    pytest.param(vp8x(), LARGE_LIMITS, "first chunk", id="VP8X"),
    pytest.param(vp8x_alph(), LARGE_LIMITS, "first chunk", id="VP8X with ALPH"),
    pytest.param(vp8x_exif(), LARGE_LIMITS, "first chunk", id="VP8X with EXIF"),
    pytest.param(vp8x_xmp(), LARGE_LIMITS, "first chunk", id="VP8X with XMP"),
    pytest.param(vp8x_icc(), LARGE_LIMITS, "first chunk", id="VP8X with ICCP"),
    pytest.param(animated(), LARGE_LIMITS, "first chunk", id="animation"),
    pytest.param(first_chunk(b"ALPH"), LARGE_LIMITS, "first chunk", id="ALPH before the frame"),
    pytest.param(first_chunk(b"vp8 "), LARGE_LIMITS, "first chunk", id="lowercase vp8"),
    # The VP8 chunk ends the file exactly.
    pytest.param(then_chunk(b"EXIF"), LARGE_LIMITS, "does not end the file", id="EXIF after the frame"),
    pytest.param(then_chunk(b"ALPH"), LARGE_LIMITS, "does not end the file", id="ALPH after the frame"),
    pytest.param(then_chunk(b"VP8 "), LARGE_LIMITS, "does not end the file", id="a second VP8 chunk"),
    pytest.param(trailing(SOLID_16), LARGE_LIMITS, "does not end the file", id="trailing bytes"),
    pytest.param(trailing(SOLID_16, b"\x00" * 100), LARGE_LIMITS, "does not end the file", id="100 trailing bytes"),
    pytest.param(riff(SOLID_16[12:-2]), LARGE_LIMITS, "does not end the file", id="a chunk that runs past the end"),
    pytest.param(riff(b"VP8 " + b"\xff\xff\xff\xff" + vp8_payload(SOLID_16)), LARGE_LIMITS,
                 "does not end the file", id="a chunk size of 4 GiB"),
    pytest.param(odd_payload(pad=b"\x01"), LARGE_LIMITS, "pad byte", id="a pad byte that is not zero"),
    pytest.param(simple(vp8_payload(SOLID_16)[:9]), LARGE_LIMITS, "frame header", id="a chunk shorter than a frame"),
    # A shown key frame of a known version with its first partition inside the chunk.
    pytest.param(with_tag(SOLID_16, key=False), LARGE_LIMITS, "not a key frame", id="an inter frame"),
    pytest.param(_hidden_and_inter(SOLID_16), LARGE_LIMITS, "not a key frame", id="a hidden inter frame"),
    pytest.param(with_tag(SOLID_16, version=4), LARGE_LIMITS, "version", id="frame version 4"),
    pytest.param(with_tag(SOLID_16, version=7), LARGE_LIMITS, "version", id="frame version 7"),
    pytest.param(with_tag(SOLID_16, shown=False), LARGE_LIMITS, "not shown", id="a hidden key frame"),
    pytest.param(with_tag(SOLID_16, partition=0), LARGE_LIMITS, "first partition", id="an empty first partition"),
    pytest.param(with_tag(SOLID_16, partition=len(vp8_payload(SOLID_16)) - 9), LARGE_LIMITS, "first partition",
                 id="a first partition past the chunk"),
    pytest.param(with_frame(SOLID_16, start=b"\x9d\x01\x2b"), LARGE_LIMITS, "start code", id="a wrong start code"),
    pytest.param(with_frame(SOLID_16, start=b"\x00\x00\x00"), LARGE_LIMITS, "start code", id="no start code"),
    # The size the frame names.
    pytest.param(header_of_size(257, 16), LARGE_LIMITS, "width", id="width 257 for large"),
    pytest.param(header_of_size(16, 257), LARGE_LIMITS, "height", id="height 257 for large"),
    pytest.param(header_of_size(16383, 16383), LARGE_LIMITS, "width", id="16383 x 16383"),
    pytest.param(header_of_size(129, 16), SMALL_LIMITS, "width", id="width 129 for small"),
    pytest.param(header_of_size(16, 129), SMALL_LIMITS, "height", id="height 129 for small"),
    pytest.param(SOLID_256, SMALL_LIMITS, "width", id="the large pattern as small"),
    pytest.param(with_frame(SOLID_16, width=16 | 0x4000), LARGE_LIMITS, "scale", id="a horizontal scale"),
    pytest.param(with_frame(SOLID_16, height=16 | 0xC000), LARGE_LIMITS, "scale", id="a vertical scale"),
    # The length of the file.
    pytest.param(of_length(LARGE, 36 * KIB + 2), LARGE_LIMITS, "larger than", id="large of 36 KiB + 2"),
    pytest.param(of_length(SMALL, 8 * KIB + 2), SMALL_LIMITS, "larger than", id="small of 8 KiB + 2"),
    pytest.param(of_length(LARGE, 8 * KIB + 2), SMALL_LIMITS, "larger than", id="a large file as small"),
]


@pytest.mark.parametrize("data, limits, reason", REFUSALS)
def test_check_lossy_webp_names_the_rule_each_refusal_breaks(data, limits, reason):
    with pytest.raises(ValueError, match=reason):
        logo.check_lossy_webp(data, *limits)


def test_a_riff_file_has_an_even_length_so_the_byte_limits_are_tested_two_bytes_over():
    """36 KiB + 1 (plan P5.3) can not be a well-formed file: the RIFF header, the chunk header and the padded payload
    always add up to an even length. An odd length is refused before the limit could matter."""
    for total in (60, 62, 36 * KIB, 36 * KIB + 2):
        assert len(of_length(SOLID_16, total)) == total
    with pytest.raises(ValueError):
        logo.check_lossy_webp(of_length(LARGE, 36 * KIB) + b"\x00", *LARGE_LIMITS)


# --- PUT and GET -----------------------------------------------------------------------------------------------

def test_a_simple_lossy_webp_of_each_size_is_accepted_and_read_back_by_members(client, storage):
    team = create_team(client)
    member = as_account(client, storage, "test.uye")
    seed_member(storage, team["id"], "test.uye")
    assert read_logo(client, team["id"], "large").status_code == 404

    put = put_logo(client, team["id"])
    assert put.status_code == 200, put.text
    assert put.json() == {"logo": {"version": 1}}
    for caller in (client, member):
        for size, data in (("large", LARGE), ("small", SMALL)):
            read = read_logo(caller, team["id"], size)
            assert read.status_code == 200, read.text
            assert read.content == data
            assert read.headers["content-type"] == "image/webp"
            assert read.headers["etag"] == '"1"'
            assert read.headers["cache-control"] == "private, no-cache"
            # The phone holds version 1: nothing to send.
            for held in ('"1"', 'W/"1"', '"0", "1"', "*"):
                again = read_logo(caller, team["id"], size, **{"If-None-Match": held})
                assert again.status_code == 304, held
                assert again.content == b""
                assert again.headers["etag"] == '"1"' and again.headers["cache-control"] == "private, no-cache"
            assert read_logo(caller, team["id"], size, **{"If-None-Match": '"0"'}).content == data

    # A replacement moves the version, and the tag of the old one no longer holds.
    replaced = put_logo(client, team["id"], large=SOLID_16, small=SOLID_16)
    assert replaced.json() == {"logo": {"version": 2}}
    for size in ("large", "small"):
        read = read_logo(member, team["id"], size, **{"If-None-Match": '"1"'})
        assert read.status_code == 200 and read.content == SOLID_16 and read.headers["etag"] == '"2"'
    assert client.get(f"/v1/teams/{team['id']}").json()["logo"] == {"version": 2}

    # The same bytes again change nothing: the same version, the same row.
    before = snapshot(storage)
    assert put_logo(client, team["id"], large=SOLID_16, small=SOLID_16).json() == {"logo": {"version": 2}}
    assert snapshot(storage) == before

    # The largest files the rule allows, read back whole.
    largest, smallest = of_length(LARGE, 36 * KIB), of_length(SMALL, 8 * KIB)
    assert put_logo(client, team["id"], large=largest, small=smallest).json() == {"logo": {"version": 3}}
    assert read_logo(member, team["id"], "large").content == largest
    assert read_logo(member, team["id"], "small").content == smallest
    assert logo_columns(storage, team["id"]) == {"logo": largest, "logo_small": smallest, "logo_version": 3}


def _replace_letter(text):
    return text.replace("A", "-", 1)


REFUSED_PUTS = [
    pytest.param("large", b64(vp8l(256, 256)), id="VP8L"),
    pytest.param("small", b64(vp8x_alph(SOLID_128)), id="ALPH"),
    pytest.param("large", b64(vp8x(LARGE)), id="VP8X"),
    pytest.param("small", b64(vp8x_exif(SOLID_128)), id="EXIF"),
    pytest.param("large", b64(trailing(LARGE)), id="trailing bytes"),
    pytest.param("small", b64(riff(chunk(b"VP8 ", vp8_payload(SMALL)), size_change=2)), id="wrong RIFF size"),
    pytest.param("large", b64(b"\x89PNG\r\n\x1a\n" + bytes(40)), id="not RIFF"),
    pytest.param("small", b64(with_tag(SMALL, key=False)), id="an inter frame"),
    pytest.param("large", b64(with_frame(LARGE, start=b"\x9d\x01\x2b")), id="a wrong start code"),
    pytest.param("large", b64(with_frame(LARGE, width=257)), id="width 257 for large"),
    pytest.param("small", b64(with_frame(SMALL, width=129)), id="width 129 for small"),
    pytest.param("small", b64(LARGE), id="the large logo as small"),
    pytest.param("large", b64(of_length(LARGE, 36 * KIB + 2)), id="large of 36 KiB + 2"),
    pytest.param("small", b64(of_length(SMALL, 8 * KIB + 2)), id="small of 8 KiB + 2"),
    pytest.param("large", "UklGRg==!", id="bad base64"),
    pytest.param("small", _replace_letter(b64(SMALL)), id="URL-safe base64 letters"),
    pytest.param("large", b64(LARGE)[:40] + "\n" + b64(LARGE)[40:], id="base64 with a line break"),
    pytest.param("small", b64(SMALL)[:-1], id="base64 without its padding"),
    pytest.param("large", "", id="empty"),
]


@pytest.mark.parametrize("image, text", REFUSED_PUTS)
def test_logo_refusals_store_nothing(client, storage, image, text):
    team = create_team(client)
    assert put_logo(client, team["id"]).status_code == 200
    before = snapshot(storage)
    body = {**logo_body(), image: text}
    error = refused_as(client.put(logo_url(team["id"]), json=body), 422, "LOGO_INVALID")
    assert error["details"] == {"image": image}
    assert snapshot(storage) == before
    assert logo_columns(storage, team["id"]) == {"logo": LARGE, "logo_small": SMALL, "logo_version": 1}


def test_an_invalid_large_logo_is_named_first_when_both_are_invalid(client):
    team = create_team(client)
    error = refused_as(client.put(logo_url(team["id"]), json={"large": b64(vp8l()), "small": b64(vp8l())}),
                       422, "LOGO_INVALID")
    assert error["details"] == {"image": "large"}


def test_a_logo_body_over_64_kib_answers_413(client, storage):
    team = create_team(client)
    before = snapshot(storage)
    large = client.put(logo_url(team["id"]), json={"large": "A" * MAX_JSON_BODY_BYTES, "small": b64(SMALL)})
    assert refused_as(large, 413, "PAYLOAD_TOO_LARGE")["details"] == {"maxBytes": MAX_JSON_BODY_BYTES}
    # Under 64 KiB, a text above its bound or a body of another shape is refused before anything is read.
    for body, location in (({"large": "A" * 50_001, "small": b64(SMALL)}, "body.large"),
                           ({"large": b64(LARGE), "small": "A" * 11_001}, "body.small"),
                           ({"large": b64(LARGE)}, "body.small"),
                           ({**logo_body(), "medium": b64(SMALL)}, "body.medium"),
                           ({"large": 7, "small": b64(SMALL)}, "body.large")):
        error = refused_as(client.put(logo_url(team["id"]), json=body), 422, "VALIDATION_ERROR")
        assert [entry["location"] for entry in error["details"]["fields"]] == [location], body.keys()
    assert snapshot(storage) == before
    # The largest files the rule allows fit in one body under the limit.
    largest = logo_body(of_length(LARGE, 36 * KIB), of_length(SMALL, 8 * KIB))
    assert len(json.dumps(largest)) < MAX_JSON_BODY_BYTES
    assert client.put(logo_url(team["id"]), json=largest).status_code == 200


def test_deleting_the_logo_answers_404_on_read_and_moves_the_version(client, storage):
    team = create_team(client)
    member = as_account(client, storage, "test.uye")
    seed_member(storage, team["id"], "test.uye")
    for size in ("large", "small"):
        assert refused_as(read_logo(member, team["id"], size), 404, "LOGO_NOT_FOUND")["details"] == {}
    assert put_logo(client, team["id"]).json() == {"logo": {"version": 1}}

    deleted = client.delete(logo_url(team["id"]))
    assert deleted.status_code == 204 and deleted.content == b""
    assert logo_columns(storage, team["id"]) == {"logo": None, "logo_small": None, "logo_version": 2}
    for caller in (client, member):
        assert caller.get(f"/v1/teams/{team['id']}").json()["logo"] is None
        for size in ("large", "small"):
            refused_as(read_logo(caller, team["id"], size, **{"If-None-Match": '"1"'}), 404, "LOGO_NOT_FOUND")

    # A repeat finds nothing to delete and changes nothing.
    before = snapshot(storage)
    assert client.delete(logo_url(team["id"])).status_code == 204
    assert snapshot(storage) == before
    # The next logo gets a version no phone has seen.
    assert put_logo(client, team["id"]).json() == {"logo": {"version": 3}}
    assert read_logo(member, team["id"], "small", **{"If-None-Match": '"1"'}).status_code == 200


def test_only_members_read_the_logo_and_only_the_captain_changes_it(client, storage, stranger, anonymous):
    team = create_team(client)
    member = as_account(client, storage, "test.uye")
    member_id = seed_member(storage, team["id"], "test.uye")
    assert put_logo(client, team["id"]).status_code == 200
    bad = {"large": b64(vp8l()), "small": b64(SMALL)}
    missing = str(uuid.uuid4())
    before = snapshot(storage)

    for size in ("large", "small"):
        assert read_logo(member, team["id"], size).status_code == 200
        assert refused_as(read_logo(stranger, team["id"], size), 403, "NOT_A_MEMBER")["details"] == {}
        refused_as(read_logo(client, missing, size), 404, "TEAM_NOT_FOUND")
        refused_as(read_logo(anonymous, team["id"], size), 401, "AUTH_REQUIRED")
    # Access comes before the route's own check, so an invalid file tells a stranger or a member nothing.
    for body in (logo_body(), bad):
        assert refused_as(member.put(logo_url(team["id"]), json=body), 403, "CAPTAIN_ONLY")["details"] == {}
        assert refused_as(stranger.put(logo_url(team["id"]), json=body), 403, "NOT_A_MEMBER")["details"] == {}
        refused_as(client.put(logo_url(missing), json=body), 404, "TEAM_NOT_FOUND")
        refused_as(anonymous.put(logo_url(team["id"]), json=body), 401, "AUTH_REQUIRED")
    assert refused_as(member.delete(logo_url(team["id"])), 403, "CAPTAIN_ONLY")["details"] == {}
    assert refused_as(stranger.delete(logo_url(team["id"])), 403, "NOT_A_MEMBER")["details"] == {}
    refused_as(client.delete(logo_url(missing)), 404, "TEAM_NOT_FOUND")
    refused_as(anonymous.delete(logo_url(team["id"])), 401, "AUTH_REQUIRED")
    # The size is one of the two.
    for url in (logo_url(team["id"]), logo_url(team["id"], "medium"), logo_url(team["id"], "LARGE")):
        error = refused_as(member.get(url), 422, "VALIDATION_ERROR")
        assert [entry["location"] for entry in error["details"]["fields"]] == ["query.size"]
    assert snapshot(storage) == before

    # The captaincy moves the right: the new captain changes the logo, the old one no longer does.
    assert client.post(f"/v1/teams/{team['id']}/captain", json={"userId": member_id}).status_code == 200
    refused_as(put_logo(client, team["id"]), 403, "CAPTAIN_ONLY")
    assert put_logo(member, team["id"], large=SOLID_16).json() == {"logo": {"version": 2}}
    assert read_logo(client, team["id"], "large").content == SOLID_16
    # The old captain, now a member, leaves and no longer reads it.
    assert client.delete(f"/v1/teams/{team['id']}/members/{team['members'][0]['userId']}").status_code == 204
    refused_as(read_logo(client, team["id"], "large"), 403, "NOT_A_MEMBER")


def test_the_team_payload_carries_the_logo_version_and_never_its_bytes(client, storage):
    created = create_team(client)
    team_id = created["id"]
    assert created["logo"] is None
    member = as_account(client, storage, "test.uye")
    seed_member(storage, team_id, "test.uye")
    large, small = marked(LARGE, b"PAYLOADBUYUK", 30 * KIB), marked(SMALL, b"PAYLOADKUCUK", 6 * KIB)
    assert put_logo(client, team_id, large, small).status_code == 200
    answers = [
        client.get(f"/v1/teams/{team_id}"),
        member.get(f"/v1/teams/{team_id}"),
        client.put(f"/v1/teams/{team_id}", json={"name": "Kuzey Spor"}),
        client.put(f"/v1/teams/{team_id}/settings", json={"name": "Güney Spor", "schedule": None}),
        client.put(f"/v1/teams/{team_id}/seats", json={"substituteSeats": 2}),
        client.post(f"/v1/teams/{team_id}/captain", json={"userId": created["members"][0]["userId"]}),
    ]
    for answer in answers:
        assert answer.status_code == 200, answer.text
        assert answer.json()["logo"] == {"version": 1}
    for listing in (client.get("/v1/teams"), member.get("/v1/teams"), client.get("/v1/me"), member.get("/v1/me")):
        assert [team["logo"] for team in listing.json()["teams"]] == [{"version": 1}]
        answers.append(listing)
    for answer in answers:
        # 36 KiB of logo fit in no answer of under 4 KiB, in whatever encoding.
        assert len(answer.content) < 4 * KIB, answer.request.url
        for data in (large, small):
            assert b64(data) not in answer.text and b64(data[:3000]) not in answer.text


# --- what stays in the files ---------------------------------------------------------------------------------

def marked(data, mark: bytes, total: int):
    """The file padded inside its VP8 payload with `mark` repeated, to about `total` bytes. A large logo spans
    several pages of the database file, so the mark stands in every page the logo touches."""
    copies = (total - len(data)) // len(mark)
    return padded(data, mark * copies)


@pytest.fixture
def bystander(storage):
    """A second connection that stays open, so SQLite does not clear its write-ahead log by itself when a request
    ends, as on a busy server (test_match_delete). The log then still holds the pages as first written."""
    connections = []

    def open_one():
        connection = sqlite3.connect(storage / "volleystat.sqlite3")
        assert connection.execute("SELECT COUNT(*) FROM teams").fetchone() is not None
        connections.append(connection)
        return connection

    yield open_one
    for connection in connections:
        connection.close()


def test_a_replaced_or_removed_logo_leaves_no_bytes_in_the_database_files(client, storage, bystander):
    team = create_team(client, name="LogoTakimi")
    kept = create_team(client, name="KalanLogoTakimi")
    bystander()
    first = (marked(LARGE, b"ILKBUYUKLOGO", 30 * KIB), marked(SMALL, b"ILKKUCUKLOGO", 6 * KIB))
    second = (marked(LARGE, b"YENIBUYUKLOG", 20 * KIB), marked(SMALL, b"YENIKUCUKLOG", 4 * KIB))
    other = (marked(LARGE, b"KALANBUYUKLO", 10 * KIB), marked(SMALL, b"KALANKUCUKLO", 2 * KIB))
    assert put_logo(client, kept["id"], *other).status_code == 200
    assert put_logo(client, team["id"], *first).status_code == 200
    before = database_bytes(storage)
    assert all(mark in before for mark in (b"ILKBUYUKLOGO", b"ILKKUCUKLOGO")), "the test can not see what it looks for"

    # A replacement overwrites the old bytes and empties the log after its commit.
    assert put_logo(client, team["id"], *second).json() == {"logo": {"version": 2}}
    after = database_bytes(storage)
    for mark in (b"ILKBUYUKLOGO", b"ILKKUCUKLOGO"):
        assert mark not in after, mark
    assert b"YENIBUYUKLOG" in after and b"YENIKUCUKLOG" in after

    # A deletion does the same.
    assert client.delete(logo_url(team["id"])).status_code == 204
    after = database_bytes(storage)
    for mark in (b"ILKBUYUKLOGO", b"ILKKUCUKLOGO", b"YENIBUYUKLOG", b"YENIKUCUKLOG"):
        assert mark not in after, mark
    # The other team's logo is still there, so the check did not pass on an emptied file.
    assert b"KALANBUYUKLO" in after and b"KALANKUCUKLO" in after
    assert read_logo(client, kept["id"], "large").content == other[0]


@pytest.mark.parametrize("how", ["delete", "leave"])
def test_a_deleted_team_leaves_no_logo_bytes(client, storage, bystander, how):
    team = create_team(client, name="SilinecekLogoTakimi")
    kept = create_team(client, name="KalanLogoTakimi")
    bystander()
    assert put_logo(client, kept["id"], marked(LARGE, b"KALANBUYUKLO", 10 * KIB),
                    marked(SMALL, b"KALANKUCUKLO", 2 * KIB)).status_code == 200
    assert put_logo(client, team["id"], marked(LARGE, b"SILBUYUKLOGO", 30 * KIB),
                    marked(SMALL, b"SILKUCUKLOGO", 6 * KIB)).status_code == 200
    marks = (b"SILBUYUKLOGO", b"SILKUCUKLOGO")
    assert all(mark in database_bytes(storage) for mark in marks), "the test can not see what it looks for"

    if how == "delete":
        assert client.delete(f"/v1/teams/{team['id']}").status_code == 204
    else:
        # The captain alone in the team deletes it by leaving (plan U8).
        captain = team["members"][0]["userId"]
        assert client.delete(f"/v1/teams/{team['id']}/members/{captain}").status_code == 204

    after = database_bytes(storage)
    for mark in marks:
        assert mark not in after, mark
    assert b"KALANBUYUKLO" in after and b"KALANKUCUKLO" in after


def test_an_account_deletion_leaves_no_logo_bytes_of_its_only_member_team(storage, cheap_hashing):
    with deleting_account(storage) as (client, _user_id):
        connection = sqlite3.connect(storage / "volleystat.sqlite3")
        try:
            assert connection.execute("SELECT COUNT(*) FROM users").fetchone() == (1,)
            team = create_team(client, name="HesapLogoTakimi")
            assert put_logo(client, team["id"], marked(LARGE, b"HESAPBUYUKLG", 30 * KIB),
                            marked(SMALL, b"HESAPKUCUKLG", 6 * KIB)).status_code == 200
            marks = (b"HESAPBUYUKLG", b"HESAPKUCUKLG", DELETED_NAME.encode())
            assert all(mark in database_bytes(storage) for mark in marks), "the test can not see what it looks for"
            assert delete_account(client).status_code == 204
            after = database_bytes(storage)
            for mark in marks:
                assert mark not in after, mark
            assert rows(storage, "SELECT * FROM teams") == []
        finally:
            connection.close()
