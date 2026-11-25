"""Storage limits, the free-disk floor and the sweep of idle uploads (docs/API_V1.md, section 10; design 6.5).

No test writes gigabytes: initiate_upload records the declared size and touches an empty file, and a
live video is an upload row in the COMPLETED state, because the limits read only the declared sizes.
Every test here patches the free space of the disk, so none depends on the runner's disk.
"""
import hashlib
import logging
import shutil
import sqlite3
import threading
import time
import uuid
from collections import namedtuple
from contextlib import closing, contextmanager

import pytest
from fastapi.testclient import TestClient

from app import main as main_module, quotas, sweep
from app.main import MAX_VIDEO_BYTES, create_app
from app.quotas import GIB
from tests.helpers import OWNER, as_account, rows, serve, session_for, user_id_of, wait_for
from tests.test_api import create_match, create_upload, set_upload
from tests.test_media import store

PIB = 1024 ** 5
REAL_FREE_BYTES = quotas.free_bytes
T = 1_700_000_000.0  # in the past, and a whole number of seconds, so T + 86400 - 86400 is T again


class Disk:
    """The free space the server sees: 1 PiB unless a test sets another number."""

    def __init__(self):
        self.free = PIB
        self.calls = 0

    def __call__(self, path):
        self.calls += 1
        return self.free


@pytest.fixture
def client(storage):
    """The signed-in client without the background threads: the sweep runs only when a test calls it,
    with the test's clock, and never in the middle of a test that dates an upload back."""
    with serve(storage, None) as signed_in:
        yield signed_in


@pytest.fixture(autouse=True)
def disk(monkeypatch):
    patched = Disk()
    monkeypatch.setattr(quotas, "free_bytes", patched)
    return patched


def refusal(response, status=409, code="QUOTA_EXCEEDED"):
    assert response.status_code == status, response.text
    assert response.json()["error"]["code"] == code, response.text
    return response.json()["error"]["details"]


def upload_ids(storage):
    return [row["id"] for row in rows(storage, "SELECT id FROM uploads ORDER BY rowid")]


def media_files(storage):
    return sorted(path.name for path in (storage / "media").iterdir())


def personal_match(storage, owner_id, size, status="COMPLETED", match_status="PERSONAL"):
    """A match of `owner_id` holding one upload row of `size` declared bytes, written straight into SQLite."""
    match_id, upload_id = str(uuid.uuid4()), str(uuid.uuid4())
    with closing(sqlite3.connect(storage / "volleystat.sqlite3")) as db, db:
        db.execute("INSERT INTO matches(id, home_team, away_team, status, owner_user_id) VALUES(?,?,?,?,?)",
                   (match_id, "Dolgu Ev", "Dolgu Konuk", match_status, owner_id))
        db.execute("INSERT INTO uploads(id, match_id, file_name, mime_type, expected_size, status, touched_at)"
                   " VALUES(?,?,?,?,?,?,?)", (upload_id, match_id, "dolgu.mp4", "video/mp4", size, status, time.time()))
    return match_id


# --- the personal limit ---------------------------------------------------------------------------

def test_a_declared_size_over_the_personal_quota_answers_409_and_stores_nothing(client, storage):
    match_id = create_match(client)
    for _ in range(2):
        assert create_upload(client, match_id, size=4 * GIB).status_code == 201
    before, files = upload_ids(storage), media_files(storage)
    details = refusal(create_upload(client, match_id, size=1))
    assert details == {"scope": "PERSONAL", "usedBytes": 8 * GIB, "limitBytes": 8 * GIB}
    assert upload_ids(storage) == before and media_files(storage) == files


def test_a_link_account_has_4_gib_and_one_unfinished_upload(client, storage, link_account):
    invited = link_account()
    first_match, second_match = create_match(invited), create_match(invited)
    first = create_upload(invited, first_match, size=4 * GIB)
    assert first.status_code == 201, first.text
    before, files = upload_ids(storage), media_files(storage)
    second = refusal(create_upload(invited, second_match, size=1))
    assert second == {"scope": "PERSONAL", "reason": "UNFINISHED_UPLOAD", "usedBytes": 4 * GIB, "limitBytes": 4 * GIB}
    # Once the first upload is a video, the account has no unfinished upload, and its 4 GiB are full.
    set_upload(storage, first.json()["uploadId"], status="COMPLETED")
    full = refusal(create_upload(invited, second_match, size=1))
    assert full == {"scope": "PERSONAL", "usedBytes": 4 * GIB, "limitBytes": 4 * GIB}
    assert upload_ids(storage) == before and media_files(storage) == files


def test_an_account_opened_without_a_link_or_a_code_gets_the_link_quota(client, storage):
    """OPEN (a server started with VOLLEYSTAT_SIGNUP=open) gets what every account without an operator
    code gets: 4 GiB, one maximum-size video, and one unfinished upload."""
    assert quotas.PERSONAL_LIMIT_OTHER == MAX_VIDEO_BYTES
    opened = as_account(client, storage, "test.acik", "OPEN")
    first_match, second_match = create_match(opened), create_match(opened)
    first = create_upload(opened, first_match, size=4 * GIB)
    assert first.status_code == 201, first.text
    before, files = upload_ids(storage), media_files(storage)
    unfinished = refusal(create_upload(opened, second_match, size=1))
    assert unfinished == {"scope": "PERSONAL", "reason": "UNFINISHED_UPLOAD", "usedBytes": 4 * GIB, "limitBytes": 4 * GIB}
    set_upload(storage, first.json()["uploadId"], status="COMPLETED")
    full = refusal(create_upload(opened, second_match, size=1))
    assert full == {"scope": "PERSONAL", "usedBytes": 4 * GIB, "limitBytes": 4 * GIB}
    assert upload_ids(storage) == before and media_files(storage) == files


def test_an_upload_being_verified_is_the_link_accounts_unfinished_upload(storage, link_account):
    invited = link_account()
    match_id = create_match(invited)
    upload_id = create_upload(invited, match_id, size=GIB).json()["uploadId"]
    set_upload(storage, upload_id, status="FINALIZING", finalize_started_at=time.time())
    assert refusal(create_upload(invited, match_id, size=1))["reason"] == "UNFINISHED_UPLOAD"
    set_upload(storage, upload_id, status="COMPLETED", finalize_started_at=None)
    assert create_upload(invited, match_id, size=1).status_code == 201


@pytest.mark.parametrize("signup_via", ["OPEN_MATCH", "INVITE", "CAPTAIN_CODE", "SOMETHING_NEW", ""])
def test_an_unknown_signup_via_gets_the_link_quota(client, storage, signup_via):
    other = as_account(client, storage, "test.baska", signup_via)
    first_match, second_match = create_match(other), create_match(other)
    first = create_upload(other, first_match, size=1)
    assert first.status_code == 201, first.text
    assert refusal(create_upload(other, second_match, size=1)) == {
        "scope": "PERSONAL", "reason": "UNFINISHED_UPLOAD", "usedBytes": 1, "limitBytes": 4 * GIB}
    set_upload(storage, first.json()["uploadId"], status="COMPLETED")
    assert refusal(create_upload(other, second_match, size=4 * GIB)) == {
        "scope": "PERSONAL", "usedBytes": 1, "limitBytes": 4 * GIB}
    assert create_upload(other, second_match, size=4 * GIB - 1).status_code == 201


def test_an_account_opened_with_a_code_gets_8_gib(client, storage):
    coded = as_account(client, storage, "test.kodlu", "SIGNUP_CODE")
    # Two maximum-size videos, both unfinished at once: a code account has no limit on unfinished uploads.
    for _ in range(2):
        assert create_upload(coded, create_match(coded), size=4 * GIB).status_code == 201
    details = refusal(create_upload(coded, create_match(coded), size=1))
    assert details == {"scope": "PERSONAL", "usedBytes": 8 * GIB, "limitBytes": 8 * GIB}


# --- the server-wide pool -------------------------------------------------------------------------

def test_ten_link_accounts_fill_the_personal_pool_and_the_next_upload_answers_server(client, storage):
    for number in range(10):
        invited = as_account(client, storage, f"test.davetli{number}", "INVITE")
        assert create_upload(invited, create_match(invited), size=4 * GIB).status_code == 201
    eleventh = as_account(client, storage, "test.davetli10", "INVITE")
    details = refusal(create_upload(eleventh, create_match(eleventh), size=1))
    # The answer names the limit, and not how much the other accounts hold.
    assert details == {"scope": "SERVER", "pool": "PERSONAL", "limitBytes": 40 * GIB}
    assert len(upload_ids(storage)) == 10


def test_legacy_bytes_do_not_count_in_the_personal_pool(client, storage):
    personal_match(storage, None, 40 * GIB, match_status="LEGACY")
    assert create_upload(client, create_match(client), size=4 * GIB).status_code == 201
    personal_match(storage, user_id_of(storage), 36 * GIB - 1)
    assert refusal(create_upload(client, create_match(client), size=1))["scope"] == "PERSONAL"


# --- what counts --------------------------------------------------------------------------------------

def test_a_live_or_quarantined_video_counts_and_a_deleted_or_aborted_one_does_not(client, storage):
    match_id = create_match(client)
    live = create_upload(client, match_id, size=4 * GIB).json()["uploadId"]
    set_upload(storage, live, status="COMPLETED")
    quarantined = store(client, b"not a video at all", match_id=match_id)
    assert quarantined["status"] != "READY"
    used = 4 * GIB + len(b"not a video at all")
    assert refusal(create_upload(client, match_id, size=4 * GIB))["usedBytes"] == used
    # A deleted video frees its bytes.
    assert client.delete(f"/v1/videos/{quarantined['id']}").status_code == 204
    aborted = create_upload(client, match_id, size=4 * GIB)
    assert aborted.status_code == 201
    # So does an upload given up.
    assert client.delete(f"/v1/uploads/{aborted.json()['uploadId']}").status_code == 204
    assert create_upload(client, match_id, size=4 * GIB).status_code == 201
    assert refusal(create_upload(client, match_id, size=1))["usedBytes"] == 8 * GIB


def test_a_finalizing_upload_counts(client, storage):
    match_id = create_match(client)
    for _ in range(2):
        set_upload(storage, create_upload(client, match_id, size=4 * GIB).json()["uploadId"], status="FINALIZING",
                   finalize_started_at=time.time())
    assert refusal(create_upload(client, match_id, size=1))["usedBytes"] == 8 * GIB


def test_deleting_a_match_frees_its_quota(client):
    full = create_match(client)
    for _ in range(2):
        assert create_upload(client, full, size=4 * GIB).status_code == 201
    other = create_match(client)
    refusal(create_upload(client, other, size=1))
    assert client.delete(f"/v1/matches/{full}").status_code == 204
    assert create_upload(client, other, size=4 * GIB).status_code == 201


def test_the_checks_come_in_their_order(storage, disk, monkeypatch):
    monkeypatch.setenv("VOLLEYSTAT_DISK_FLOOR_GIB", "20")
    with serve(storage, None) as client:
        invited = as_account(client, storage, "test.davetli", "INVITE")
        match_id = create_match(invited)
        unfinished = create_upload(invited, match_id, size=4 * GIB).json()["uploadId"]
        personal_match(storage, "dolgu-hesabi", 40 * GIB)               # the pool is full without it
        disk.free = 20 * GIB                                              # and the disk has nothing to spare
        # Every limit is reached at once: the first check answers, then each next one as the one before it clears.
        assert refusal(create_upload(invited, match_id, size=1))["reason"] == "UNFINISHED_UPLOAD"
        set_upload(storage, unfinished, status="COMPLETED")
        details = refusal(create_upload(invited, match_id, size=1))
        assert details == {"scope": "PERSONAL", "usedBytes": 4 * GIB, "limitBytes": 4 * GIB}
        set_upload(storage, unfinished, status="DELETED")
        assert refusal(create_upload(invited, match_id, size=1))["scope"] == "SERVER"
        with closing(sqlite3.connect(storage / "volleystat.sqlite3")) as db, db:
            db.execute("UPDATE uploads SET status='ABORTED' WHERE expected_size=?", (40 * GIB,))
        assert refusal(create_upload(invited, match_id, size=1), 507, "STORAGE_FULL") == {"floorBytes": 20 * GIB}
        disk.free = 20 * GIB + 1
        assert create_upload(invited, match_id, size=1).status_code == 201


def test_two_uploads_of_one_link_account_at_once_give_one_201_and_one_409(storage, monkeypatch):
    session_for(storage, "test.ikili", "INVITE")
    with serve(storage, None, user="test.ikili") as invited:
        match_id = create_match(invited)
        # The free space is read inside the transaction, after the account's own checks. Slowed down,
        # it holds the first upload's transaction open while the second request arrives.
        def slow(path):
            time.sleep(0.3)
            return PIB
        monkeypatch.setattr(quotas, "free_bytes", slow)
        barrier = threading.Barrier(2)
        codes = []

        def start():
            barrier.wait(5)
            codes.append(create_upload(invited, match_id, size=GIB).status_code)

        threads = [threading.Thread(target=start) for _ in range(2)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(30)
        assert sorted(codes) == [201, 409]
        assert len(rows(storage, "SELECT 1 FROM uploads")) == 1


# --- a start whose answer was lost --------------------------------------------------------------------

def start_of(client, match_id, data=None, size=None, sha=None, **overrides):
    """The app's own start: the declared size and the SHA-256 of the whole file."""
    return create_upload(client, match_id, size=size if size is not None else len(data),
                         clientSha256=sha or hashlib.sha256(data).hexdigest(), **overrides)


def test_a_start_repeated_after_a_lost_answer_gets_the_same_upload_and_counts_it_once(client, storage, link_account):
    invited = link_account()
    match_id = create_match(invited)
    first = start_of(invited, match_id, size=4 * GIB, sha="a" * 64)
    assert first.status_code == 201, first.text
    # The phone never read the first answer and asks again for the same file: the server names the
    # unfinished upload it already holds instead of refusing a second one or counting the file twice.
    again = start_of(invited, match_id, size=4 * GIB, sha="a" * 64)
    assert again.status_code == 200, again.text
    assert again.json() == {**first.json(), "nextOffset": 0, "status": "UPLOADING"}
    assert len(upload_ids(storage)) == 1 and len(media_files(storage)) == 1
    assert refusal(create_upload(invited, create_match(invited), size=1)) == {
        "scope": "PERSONAL", "reason": "UNFINISHED_UPLOAD", "usedBytes": 4 * GIB, "limitBytes": 4 * GIB}
    # A code account has no limit on unfinished uploads, so the second start would have held 4 GiB more.
    match_id = create_match(client)
    assert [start_of(client, match_id, size=4 * GIB, sha="b" * 64).status_code for _ in range(3)] == [201, 200, 200]
    assert create_upload(client, create_match(client), size=4 * GIB).status_code == 201
    assert refusal(create_upload(client, create_match(client), size=1))["usedBytes"] == 8 * GIB


def test_a_repeated_start_resumes_from_the_bytes_the_server_holds(client, storage):
    data = b"0123456789"
    match_id = create_match(client)
    upload_id = start_of(client, match_id, data).json()["uploadId"]
    assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=data[:4]).status_code == 200
    set_upload(storage, upload_id, touched_at=T)
    again = start_of(client, match_id, data)
    assert again.status_code == 200, again.text
    assert (again.json()["uploadId"], again.json()["nextOffset"]) == (upload_id, 4)
    # A start counts as activity: the sweep waits a day from it.
    assert touched(storage, upload_id) > T
    assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=4", content=data[4:]).status_code == 200
    assert client.post(f"/v1/uploads/{upload_id}/complete").status_code == 200


def test_a_start_of_another_file_opens_a_new_upload(client, storage):
    match_id = create_match(client)
    first = start_of(client, match_id, size=GIB, sha="c" * 64).json()["uploadId"]
    others = [
        start_of(client, match_id, size=GIB, sha="d" * 64),                        # other bytes
        start_of(client, match_id, size=GIB - 1, sha="c" * 64),                    # another size
        start_of(client, match_id, size=GIB, sha="c" * 64, mimeType="video/webm", fileName="video.webm"),
        create_upload(client, match_id, size=GIB),                                 # no checksum: the server can not tell
        start_of(client, create_match(client), size=GIB, sha="c" * 64),            # another match
    ]
    assert [response.status_code for response in others] == [201] * 5
    assert len(set(upload_ids(storage))) == 6
    # A closed upload is never handed out again: the phone starts from zero on a new one.
    client.delete(f"/v1/uploads/{first}")
    reopened = start_of(client, match_id, size=GIB, sha="c" * 64)
    assert reopened.status_code == 201 and reopened.json()["uploadId"] != first


def test_a_repeated_start_by_another_account_is_refused_before_anything_is_named(client, storage, stranger):
    match_id = create_match(client)
    start_of(client, match_id, size=GIB, sha="e" * 64)
    refused = start_of(stranger, match_id, size=GIB, sha="e" * 64)
    assert refused.status_code == 403 and "uploadId" not in refused.text


# --- touched_at and started_by ------------------------------------------------------------------------

def test_started_by_and_touched_at_are_written_at_initiate_upload(client, storage, stranger):
    before = time.time()
    upload_id = create_upload(client, create_match(client), size=10).json()["uploadId"]
    other = create_upload(stranger, create_match(stranger), size=10).json()["uploadId"]
    after = time.time()
    found = {row["id"]: row for row in rows(storage, "SELECT id, started_by, touched_at FROM uploads")}
    assert found[upload_id]["started_by"] == user_id_of(storage, OWNER)
    assert found[other]["started_by"] == user_id_of(storage, "test.yabanci")
    assert all(before <= row["touched_at"] <= after for row in found.values())


def touched(storage, upload_id):
    return rows(storage, "SELECT touched_at FROM uploads WHERE id=?", upload_id)[0]["touched_at"]


def test_touched_at_moves_with_every_stored_chunk_and_with_a_checksum_reset(client, storage):
    good = hashlib.sha256(b"abcde").hexdigest()
    upload_id = create_upload(client, create_match(client), size=5, clientSha256=good).json()["uploadId"]
    chunks = f"/v1/uploads/{upload_id}/chunks"
    for offset, data in ((0, b"ab"), (2, b"cd")):
        set_upload(storage, upload_id, touched_at=T)
        assert client.put(f"{chunks}?offset={offset}", content=data).status_code == 200
        assert touched(storage, upload_id) > T
    # A refused chunk moves nothing.
    set_upload(storage, upload_id, touched_at=T)
    assert client.put(f"{chunks}?offset=0", content=b"zz").status_code == 409
    assert touched(storage, upload_id) == T
    # Bytes lost from the disk put the offset back, and that counts as a touch too.
    with (storage / "media" / f"{upload_id}.bin").open("r+b") as part:
        part.truncate(1)
    assert client.put(f"{chunks}?offset=4", content=b"e").status_code == 409
    assert touched(storage, upload_id) > T
    set_upload(storage, upload_id, touched_at=T)
    assert client.put(f"{chunks}?offset=1", content=b"bcdX").status_code == 200
    set_upload(storage, upload_id, touched_at=T)
    assert client.post(f"/v1/uploads/{upload_id}/complete").json()["error"]["code"] == "UPLOAD_CHECKSUM_MISMATCH"
    assert touched(storage, upload_id) > T
    # A missing file found at complete reopens the upload from what is on disk.
    assert client.put(f"{chunks}?offset=0", content=b"abcde").status_code == 200
    (storage / "media" / f"{upload_id}.bin").unlink()
    set_upload(storage, upload_id, touched_at=T)
    assert client.post(f"/v1/uploads/{upload_id}/complete").json()["error"]["code"] == "UPLOAD_INCOMPLETE"
    assert touched(storage, upload_id) > T


# --- the free-disk floor -------------------------------------------------------------------------------

def test_a_patched_free_space_below_the_floor_answers_507_and_stores_nothing(storage, disk, monkeypatch):
    monkeypatch.setenv("VOLLEYSTAT_DISK_FLOOR_GIB", "20")
    with serve(storage, None) as client:
        match_id = create_match(client)
        disk.free = 20 * GIB + 10
        assert create_upload(client, match_id, size=10).status_code == 201
        before, files = upload_ids(storage), media_files(storage)
        refused = create_upload(client, match_id, size=11)
        assert refusal(refused, 507, "STORAGE_FULL") == {"floorBytes": 20 * GIB}
        # No number of the free space reaches the client.
        assert str(disk.free) not in refused.text
        assert upload_ids(storage) == before and media_files(storage) == files


def test_unfinished_uploads_hold_the_free_space_they_still_need(storage, disk, monkeypatch):
    """The floor counts the bytes every unfinished upload still has to send, so two uploads can not
    pass on the same free bytes, one after the other or at once."""
    monkeypatch.setenv("VOLLEYSTAT_DISK_FLOOR_GIB", "20")
    with serve(storage, None) as client:
        other = as_account(client, storage, "test.ikinci")
        data = b"0123456789"
        disk.free = 20 * GIB + 10
        upload_id = start_of(client, create_match(client), data).json()["uploadId"]
        refused = create_upload(other, create_match(other), size=1)
        assert refusal(refused, 507, "STORAGE_FULL") == {"floorBytes": 20 * GIB}
        # A stored chunk takes its bytes from the disk and from what the upload still owes at once.
        assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=data[:4]).status_code == 200
        disk.free -= 4
        refusal(create_upload(other, create_match(other), size=1), 507, "STORAGE_FULL")
        # A finished upload owes nothing, and one byte more of free space lets the next one in.
        assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=4", content=data[4:]).status_code == 200
        disk.free -= 6
        assert client.post(f"/v1/uploads/{upload_id}/complete").status_code == 200
        disk.free += 1
        assert create_upload(other, create_match(other), size=1).status_code == 201
        # An unfinished upload of a match that belongs to no account holds its bytes too, until it is closed.
        disk.free += GIB
        personal_match(storage, None, GIB, status="UPLOADING", match_status="LEGACY")
        refusal(create_upload(other, create_match(other), size=1), 507, "STORAGE_FULL")
        with closing(sqlite3.connect(storage / "volleystat.sqlite3")) as db, db:
            db.execute("UPDATE uploads SET status='ABORTED' WHERE expected_size=?", (GIB,))
        assert create_upload(other, create_match(other), size=1).status_code == 201


def test_two_uploads_started_at_once_do_not_both_take_the_last_free_space(storage, monkeypatch):
    monkeypatch.setenv("VOLLEYSTAT_DISK_FLOOR_GIB", "20")
    with serve(storage, None) as client:
        accounts = [as_account(client, storage, f"test.ayni{number}") for number in range(2)]
        matches = [create_match(account) for account in accounts]

        def slow(path):
            # Holds the first start's transaction open while the second request arrives.
            time.sleep(0.3)
            return 21 * GIB
        monkeypatch.setattr(quotas, "free_bytes", slow)
        barrier = threading.Barrier(2)
        codes = []

        def start(account, match_id):
            barrier.wait(5)
            codes.append(create_upload(account, match_id, size=GIB).status_code)

        threads = [threading.Thread(target=start, args=pair) for pair in zip(accounts, matches)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(30)
        assert sorted(codes) == [201, 507]


def test_a_chunk_below_half_the_floor_answers_507_and_keeps_the_offset(storage, disk, monkeypatch):
    monkeypatch.setenv("VOLLEYSTAT_DISK_FLOOR_GIB", "20")
    with serve(storage, None) as client:
        upload_id = create_upload(client, create_match(client), size=10).json()["uploadId"]
        disk.free = 10 * GIB + 3
        assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abc").status_code == 200
        set_upload(storage, upload_id, touched_at=T)
        refused = client.put(f"/v1/uploads/{upload_id}/chunks?offset=3", content=b"defg")
        assert refusal(refused, 507, "STORAGE_FULL") == {"floorBytes": 10 * GIB}
        state = client.get(f"/v1/uploads/{upload_id}").json()
        assert (state["nextOffset"], state["status"]) == (3, "UPLOADING")
        assert (storage / "media" / f"{upload_id}.bin").read_bytes() == b"abc"
        assert touched(storage, upload_id) == T
        disk.free = 10 * GIB + 4
        assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=3", content=b"defg").json()["nextOffset"] == 7


@pytest.mark.parametrize("value", ["-1", "abc", "1.5", "²", "", "20 GiB"])
def test_a_wrong_floor_stops_the_start_before_storage_is_touched(tmp_path, monkeypatch, value):
    monkeypatch.setenv("VOLLEYSTAT_DISK_FLOOR_GIB", value)
    with pytest.raises(ValueError, match="VOLLEYSTAT_DISK_FLOOR_GIB"):
        create_app(tmp_path / "hic-yok", analyzer="none")
    assert not (tmp_path / "hic-yok").exists()


@pytest.mark.parametrize("value, floor", [(None, 20 * GIB), (" 7 ", 7 * GIB), ("0", 0)])
def test_the_floor_is_read_from_the_environment_and_a_wrong_value_stops_the_start(storage, disk, monkeypatch, value, floor):
    if value is None:
        monkeypatch.delenv("VOLLEYSTAT_DISK_FLOOR_GIB")
    else:
        monkeypatch.setenv("VOLLEYSTAT_DISK_FLOOR_GIB", value)
    with serve(storage, None) as client:
        match_id = create_match(client)
        disk.free = floor + 5
        assert create_upload(client, match_id, size=5).status_code == 201
        refused = create_upload(client, match_id, size=6)
        assert refusal(refused, 507, "STORAGE_FULL") == {"floorBytes": floor}
    monkeypatch.setenv("VOLLEYSTAT_DISK_FLOOR_GIB", "twenty")
    with pytest.raises(ValueError, match="VOLLEYSTAT_DISK_FLOOR_GIB"):
        create_app(storage, analyzer="none")


def test_free_bytes_reads_the_disk_of_the_storage_folder(storage, monkeypatch):
    seen = []
    Usage = namedtuple("Usage", "total used free")

    def usage(path):
        seen.append(path)
        return Usage(100, 40, 60)

    monkeypatch.setattr(quotas, "free_bytes", REAL_FREE_BYTES)
    monkeypatch.setattr(shutil, "disk_usage", usage)
    with serve(storage, None) as client:
        upload_id = create_upload(client, create_match(client), size=5).json()["uploadId"]
        assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abc").status_code == 200
    assert seen and all(path == storage.resolve() for path in seen)
    assert REAL_FREE_BYTES(storage) == 60


# --- the sweep -----------------------------------------------------------------------------------------

HOUR = 3600


def sweeper(client):
    """The app's sweeper. The client runs no sweeper thread, so nothing called resume(): the server has
    recorded no time off, and the tests below count idle time from T as if it had run since long before.
    The tests of a server that was off call resume() themselves with the moment of each start."""
    return client.app.state.sweeper


def clock(storage):
    """What the server wrote about its own uptime: the last moment it was seen running, and every time off."""
    alive = rows(storage, "SELECT last_alive FROM server_clock")
    return (alive[0]["last_alive"] if alive else None,
            [(row["stopped_at"], row["started_at"])
             for row in rows(storage, "SELECT stopped_at, started_at FROM server_downtimes ORDER BY started_at")])


def run_passes(swept, start, end):
    """Play a server that runs from `start` to `end`: a pass at `start`, then one every MAX_PASS_GAP_SECONDS
    (the longest gap between two passes that still counts as uptime), and the last one at `end`. Returns how
    many uploads the passes closed."""
    closed, moment = 0, start
    while True:
        closed += swept.run_once(now=moment)["uploads"]
        if moment >= end:
            return closed
        moment = min(moment + sweep.MAX_PASS_GAP_SECONDS, end)


def upload_status(storage, upload_id):
    return rows(storage, "SELECT status FROM uploads WHERE id=?", upload_id)[0]["status"]


def owed(storage):
    """The bytes the unfinished uploads still hold on the disk (the free-disk floor's reservation)."""
    with closing(sqlite3.connect(storage / "volleystat.sqlite3")) as db:
        return quotas.still_owed(db)


class RouteClock:
    """Stands in for the time module in app.main: time.time() there reads the moment the test sets."""

    def __init__(self):
        self.now = None

    def time(self):
        return self.now


@pytest.fixture
def route_clock(monkeypatch):
    """The server's clock as the upload routes read it, so that a chunk and a complete are written with the
    moment the test tells. The sessions keep the real clock."""
    clock = RouteClock()
    monkeypatch.setattr(main_module, "time", clock)
    return clock


def test_the_sweep_aborts_an_upload_idle_for_24_hours_and_deletes_its_bytes(client, storage):
    upload_id = create_upload(client, create_match(client), size=10).json()["uploadId"]
    assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abc").status_code == 200
    old_part = storage / "uploads" / f"{upload_id}.part"
    old_part.parent.mkdir(exist_ok=True)
    old_part.write_bytes(b"0.2")
    set_upload(storage, upload_id, touched_at=T)
    assert sweeper(client).run_once(now=T + 86399) == {"uploads": 0}
    assert rows(storage, "SELECT status FROM uploads")[0]["status"] == "UPLOADING"
    assert media_files(storage) == [f"{upload_id}.bin"]
    assert sweeper(client).run_once(now=T + 86400) == {"uploads": 1}
    assert rows(storage, "SELECT status FROM uploads")[0]["status"] == "ABORTED"
    assert media_files(storage) == [] and not old_part.exists()
    # The row stays, as after DELETE /v1/uploads/{id}, and the phone reads why its upload ended.
    assert client.get(f"/v1/uploads/{upload_id}").json()["status"] == "ABORTED"


def test_the_sweep_aborts_a_verification_that_started_a_day_ago_and_leaves_a_younger_one(client, storage):
    match_id = create_match(client)
    old, young = (create_upload(client, match_id, size=5).json()["uploadId"] for _ in range(2))
    set_upload(storage, old, status="FINALIZING", finalize_started_at=T, touched_at=T + 86000)
    set_upload(storage, young, status="FINALIZING", finalize_started_at=T + 1, touched_at=T)
    assert sweeper(client).run_once(now=T + 86400) == {"uploads": 1}
    found = {row["id"]: (row["status"], row["finalize_started_at"])
             for row in rows(storage, "SELECT id, status, finalize_started_at FROM uploads")}
    assert found == {old: ("ABORTED", None), young: ("FINALIZING", T + 1)}
    assert media_files(storage) == [f"{young}.bin"]


def test_the_sweep_leaves_completed_and_recent_uploads(client, storage):
    upload_id = create_upload(client, create_match(client), size=5).json()["uploadId"]
    assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abcde").status_code == 200
    video_id = client.post(f"/v1/uploads/{upload_id}/complete").json()["videoId"]
    recent = create_upload(client, create_match(client), size=5).json()["uploadId"]
    set_upload(storage, upload_id, touched_at=0)
    assert sweeper(client).run_once() == {"uploads": 0}
    assert {row["id"]: row["status"] for row in rows(storage, "SELECT id, status FROM uploads")} == {
        upload_id: "COMPLETED", recent: "UPLOADING"}
    assert client.get(f"/v1/videos/{video_id}").status_code == 200
    assert media_files(storage) == sorted([f"{upload_id}.bin", f"{recent}.bin"])


def test_a_chunk_after_the_sweep_answers_409_and_a_new_upload_starts_from_zero(client, storage):
    match_id = create_match(client)
    upload_id = create_upload(client, match_id, size=5).json()["uploadId"]
    assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abc").status_code == 200
    set_upload(storage, upload_id, touched_at=T)
    sweeper(client).run_once(now=T + 86400)
    late = client.put(f"/v1/uploads/{upload_id}/chunks?offset=3", content=b"de")
    assert (late.status_code, late.json()["error"]["code"]) == (409, "UPLOAD_CLOSED")
    assert client.post(f"/v1/uploads/{upload_id}/complete").json()["error"]["code"] == "UPLOAD_CLOSED"
    again = create_upload(client, match_id, size=5)
    assert again.status_code == 201 and again.json()["nextOffset"] == 0


def test_an_aborted_upload_frees_the_link_accounts_unfinished_slot(client, storage, link_account):
    invited = link_account()
    match_id = create_match(invited)
    upload_id = create_upload(invited, match_id, size=GIB).json()["uploadId"]
    assert refusal(create_upload(invited, match_id, size=1))["reason"] == "UNFINISHED_UPLOAD"
    set_upload(storage, upload_id, touched_at=T)
    sweeper(client).run_once(now=T + 86400)
    assert create_upload(invited, match_id, size=GIB).status_code == 201


def test_the_time_the_server_was_off_does_not_count_as_idle_time(client, storage):
    """A phone can not send a chunk to a server that is off: idle time counts only while the server runs.
    The last chunk came two hours before a 25-hour outage, so the upload has 22 hours left after the start."""
    match_id = create_match(client)
    waiting, verifying = (create_upload(client, match_id, size=10).json()["uploadId"] for _ in range(2))
    assert client.put(f"/v1/uploads/{waiting}/chunks?offset=0", content=b"abc").status_code == 200
    set_upload(storage, waiting, touched_at=T)
    set_upload(storage, verifying, status="FINALIZING", finalize_started_at=T, touched_at=T)
    swept = sweeper(client)
    assert swept.run_once(now=T + 2 * HOUR) == {"uploads": 0}
    started = T + 27 * HOUR
    swept.resume(now=started)
    assert clock(storage) == (started, [(T + 2 * HOUR, started)])
    assert run_passes(swept, started, started + 22 * HOUR - 1) == 0
    assert {row["status"] for row in rows(storage, "SELECT status FROM uploads")} == {"UPLOADING", "FINALIZING"}
    assert len(media_files(storage)) == 2
    # 2 hours before the stop and 22 after the start make a day of uptime without a chunk: both are idle.
    assert swept.run_once(now=started + 22 * HOUR) == {"uploads": 2}
    assert media_files(storage) == []


def test_a_server_off_for_three_days_sweeps_nothing_in_its_first_hour_back(client, storage):
    """The upload was idle for 23 hours of uptime when the server stopped. It stays for the first hour after
    the start and goes when that hour is over, three days after the stop."""
    upload_id = create_upload(client, create_match(client), size=10).json()["uploadId"]
    assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abc").status_code == 200
    set_upload(storage, upload_id, touched_at=T)
    swept = sweeper(client)
    assert swept.run_once(now=T + 23 * HOUR) == {"uploads": 0}
    started = T + 23 * HOUR + 72 * HOUR
    swept.resume(now=started)
    for minute in range(60):
        assert swept.run_once(now=started + minute * 60) == {"uploads": 0}
    assert swept.run_once(now=started + HOUR - 1) == {"uploads": 0}
    assert rows(storage, "SELECT status FROM uploads")[0]["status"] == "UPLOADING"
    assert media_files(storage) == [f"{upload_id}.bin"]
    assert swept.run_once(now=started + HOUR) == {"uploads": 1}
    assert media_files(storage) == []


def test_a_server_that_runs_20_hours_a_day_aborts_an_idle_upload_after_24_hours_of_its_uptime(client, storage):
    """The review's probe: the owner's computer is the server and is off every night from 00:00 to 04:00.
    The upload went idle 10 minutes after the first start, so the first day gave it 19 h 50 min of uptime.
    Its 24th idle hour of uptime ends on the second day at 08:10, and the pass of 08:12 closes it. The third
    day closes nothing more."""
    opened = as_account(client, storage, "test.acik", "OPEN")
    upload_id = create_upload(opened, create_match(opened), size=GIB).json()["uploadId"]
    assert refusal(create_upload(opened, create_match(opened), size=1))["reason"] == "UNFINISHED_UPLOAD"
    swept = sweeper(client)
    every = int(sweep.MAX_PASS_GAP_SECONDS)
    closed_at = []
    for day in range(3):
        started = T + day * 86400 + 4 * HOUR
        swept.resume(now=started)
        if day == 0:
            set_upload(storage, upload_id, touched_at=started)
        # A pass every three minutes while the server runs; the last one at midnight, just before the stop.
        for moment in range(int(started), int(started + 20 * HOUR) + 1, every):
            if day == 0 and moment == int(started) + 4 * every:
                # The last chunk came at 04:10, between the passes of 04:09 and 04:12.
                set_upload(storage, upload_id, touched_at=started + 600)
            if swept.run_once(now=float(moment))["uploads"]:
                closed_at.append(moment)
    assert closed_at == [T + 86400 + 8 * HOUR + 12 * 60]
    assert rows(storage, "SELECT status FROM uploads WHERE id=?", upload_id)[0]["status"] == "ABORTED"
    assert f"{upload_id}.bin" not in media_files(storage)
    # The account's single unfinished upload is free again.
    assert create_upload(opened, create_match(opened), size=GIB).status_code == 201


def test_a_chunk_after_a_restart_gives_the_upload_a_new_day(client, storage):
    """Two uploads with the same past: touched 95 hours before the chunk below, then the server ran 10 hours,
    was off 72 hours and has run 13 hours since. Only one of them gets the chunk. The chunk is written with
    the server's own clock, so the story is told backwards from now."""
    match_id = create_match(client)
    chunked, left = (create_upload(client, match_id, size=10).json()["uploadId"] for _ in range(2))
    # Whole seconds, so that the sums below are exact.
    touched = float(int(time.time())) - 95 * HOUR
    for upload_id in (chunked, left):
        set_upload(storage, upload_id, touched_at=touched)
    swept = sweeper(client)
    assert swept.run_once(now=touched + 10 * HOUR) == {"uploads": 0}
    swept.resume(now=touched + 82 * HOUR)
    assert run_passes(swept, touched + 82 * HOUR, touched + 95 * HOUR - 60) == 0
    assert client.put(f"/v1/uploads/{chunked}/chunks?offset=0", content=b"abc").status_code == 200
    chunk_at = rows(storage, "SELECT touched_at FROM uploads WHERE id=?", chunked)[0]["touched_at"]
    assert chunk_at >= touched + 95 * HOUR
    # 10 hours before the stop and 14 after the start: the upload without the chunk goes.
    assert run_passes(swept, chunk_at, touched + 96 * HOUR - 1) == 0
    assert swept.run_once(now=touched + 96 * HOUR) == {"uploads": 1}
    assert {row["id"]: row["status"] for row in rows(storage, "SELECT id, status FROM uploads")} == {
        chunked: "UPLOADING", left: "ABORTED"}
    # The chunk started a new day of uptime.
    assert run_passes(swept, touched + 96 * HOUR, chunk_at + 86399) == 0
    assert swept.run_once(now=chunk_at + 86400) == {"uploads": 1}
    assert media_files(storage) == []


def test_the_first_start_without_a_clock_gives_every_upload_a_full_day(client, storage):
    """A server that never wrote its clock (the first start of this version) can not tell how long it was off,
    so it counts all time before the start as time off. An upload stored before server 0.9 has no touch time
    and still goes at the first pass."""
    match_id = create_match(client)
    kept, stored_before_accounts = (create_upload(client, match_id, size=10).json()["uploadId"] for _ in range(2))
    set_upload(storage, kept, touched_at=T)
    set_upload(storage, stored_before_accounts, touched_at=None)
    assert clock(storage) == (None, [])
    swept = sweeper(client)
    started = T + 27 * HOUR
    swept.resume(now=started)
    assert swept.run_once(now=started) == {"uploads": 1}
    assert rows(storage, "SELECT status FROM uploads WHERE id=?", stored_before_accounts)[0]["status"] == "ABORTED"
    assert run_passes(swept, started, started + 86399) == 0
    assert swept.run_once(now=started + 86400) == {"uploads": 1}
    assert media_files(storage) == []


def test_a_verification_counts_only_the_uptime_after_it_started(client, storage):
    """Two verifications that never end, around a 48-hour outage. The first started an hour before the stop:
    it goes after 23 more hours of uptime, and the start leaves its finalize_started_at as the server's clock
    wrote it (see the next test). The second took its last chunk an hour before the stop and started a minute
    after the start. The time off between that chunk and its start does not count for it: it goes exactly a
    day of uptime after it started, not two days later."""
    match_id = create_match(client)
    before, after = (create_upload(client, match_id, size=10).json()["uploadId"] for _ in range(2))
    set_upload(storage, before, status="FINALIZING", finalize_started_at=T, touched_at=T - 10 * HOUR)
    set_upload(storage, after, touched_at=T)
    swept = sweeper(client)
    assert swept.run_once(now=T + HOUR) == {"uploads": 0}
    started = T + 49 * HOUR
    swept.resume(now=started)
    assert rows(storage, "SELECT finalize_started_at FROM uploads WHERE id=?", before)[0]["finalize_started_at"] == T
    assert swept.run_once(now=started) == {"uploads": 0}
    set_upload(storage, after, status="FINALIZING", finalize_started_at=started + 60)
    assert run_passes(swept, started + 60, started + 23 * HOUR - 1) == 0
    assert swept.run_once(now=started + 23 * HOUR) == {"uploads": 1}
    assert (upload_status(storage, before), upload_status(storage, after)) == ("ABORTED", "FINALIZING")
    assert run_passes(swept, started + 23 * HOUR, started + 60 + 24 * HOUR - 1) == 0
    assert swept.run_once(now=started + 60 + 24 * HOUR) == {"uploads": 1}
    assert upload_status(storage, after) == "ABORTED"


def test_after_a_start_the_next_complete_takes_over_a_verification_that_the_stop_ended(client, storage):
    """The server stopped a minute after a verification started and was off for 19 minutes. No verification
    runs after a start. The verification is 20 minutes old by the server's clock, so the next complete takes
    it over at once (15 minutes) and the phone does not wait for a verification that the stop ended."""
    upload_id = create_upload(client, create_match(client), size=5).json()["uploadId"]
    assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abcde").status_code == 200
    now = time.time()
    set_upload(storage, upload_id, status="FINALIZING", finalize_started_at=now - 20 * 60)
    swept = sweeper(client)
    swept.run_once(now=now - 19 * 60)
    swept.resume(now=now)
    done = client.post(f"/v1/uploads/{upload_id}/complete")
    assert done.status_code == 200, done.text
    assert rows(storage, "SELECT status FROM uploads")[0]["status"] == "COMPLETED"


def test_a_start_keeps_only_the_time_off_that_an_unfinished_upload_still_needs(client, storage):
    """Every later touch comes after a time off that no unfinished upload predates, so that time off can
    never count again: a start or a pass forgets it. The table stays as long as the oldest unfinished upload."""
    swept = sweeper(client)
    swept.run_once(now=T)
    swept.resume(now=T + HOUR)
    assert clock(storage) == (T + HOUR, [])
    upload_id = create_upload(client, create_match(client), size=10).json()["uploadId"]
    set_upload(storage, upload_id, touched_at=T + 2 * HOUR)
    run_passes(swept, T + HOUR, T + 3 * HOUR)
    swept.resume(now=T + 4 * HOUR)
    run_passes(swept, T + 4 * HOUR, T + 5 * HOUR)
    swept.resume(now=T + 6 * HOUR)
    assert clock(storage) == (T + 6 * HOUR, [(T + 3 * HOUR, T + 4 * HOUR), (T + 5 * HOUR, T + 6 * HOUR)])
    set_upload(storage, upload_id, touched_at=T + 4 * HOUR + 1)
    swept.run_once(now=T + 6 * HOUR)
    assert clock(storage) == (T + 6 * HOUR, [(T + 5 * HOUR, T + 6 * HOUR)])
    run_passes(swept, T + 6 * HOUR, T + 7 * HOUR)
    swept.resume(now=T + 8 * HOUR)
    assert clock(storage) == (T + 8 * HOUR, [(T + 5 * HOUR, T + 6 * HOUR), (T + 7 * HOUR, T + 8 * HOUR)])
    set_upload(storage, upload_id, status="COMPLETED")
    swept.resume(now=T + 9 * HOUR)
    assert clock(storage) == (T + 9 * HOUR, [])


def touched_upload(client, storage, touched_at=T):
    upload_id = create_upload(client, create_match(client), size=10).json()["uploadId"]
    set_upload(storage, upload_id, touched_at=touched_at)
    return upload_id


def test_a_computer_that_sleeps_between_two_passes_does_not_count_the_night(client, storage):
    """The owner's computer may sleep instead of shutting down: the process keeps running, but no pass comes
    until the computer wakes, and no phone can reach the server meanwhile. A gap between two passes longer
    than MAX_PASS_GAP_SECONDS counts as time off, a gap of exactly that length as uptime. The upload had an
    hour of uptime before an 8-hour sleep and a short nap after it, and goes 23 hours of uptime after the nap."""
    # The documents name this length: three minutes, three sweep intervals.
    assert sweep.MAX_PASS_GAP_SECONDS == 3 * 60 == 3 * sweep.SWEEP_SECONDS
    upload_id = touched_upload(client, storage)
    swept = sweeper(client)
    swept.resume(now=T)
    assert run_passes(swept, T, T + HOUR) == 0
    assert clock(storage) == (T + HOUR, [])
    woke = T + 9 * HOUR
    assert swept.run_once(now=woke) == {"uploads": 0}
    assert clock(storage) == (woke, [(T + HOUR, woke)])
    napped = woke + sweep.MAX_PASS_GAP_SECONDS + 1
    assert swept.run_once(now=napped) == {"uploads": 0}
    assert clock(storage) == (napped, [(T + HOUR, woke), (woke, napped)])
    assert run_passes(swept, napped, napped + 23 * HOUR - 1) == 0
    assert upload_status(storage, upload_id) == "UPLOADING"
    assert swept.run_once(now=napped + 23 * HOUR) == {"uploads": 1}


def test_a_stop_right_after_a_sleep_counts_the_sleep_as_time_off(client, storage):
    """The computer wakes from an 8-hour sleep and the server stops before its next pass. The stop writes the
    clock as a pass does, so the sleep counts as time off, and the next start counts its own time off from
    the stop."""
    slept = time.time() - 8 * HOUR
    touched_upload(client, storage, touched_at=slept - HOUR)
    swept = sweeper(client)
    swept.resume(now=slept - HOUR)
    run_passes(swept, slept - HOUR, slept)
    before_stop = time.time()
    swept.stop()
    stopped, downtimes = clock(storage)
    assert stopped >= before_stop
    assert downtimes == [(slept, stopped)]
    swept.resume(now=stopped + HOUR)
    assert clock(storage) == (stopped + HOUR, [(slept, stopped), (stopped, stopped + HOUR)])


def test_a_clock_ahead_at_the_start_gives_no_extra_time_off_once_it_is_corrected(client, storage):
    """The computer starts after an 8-hour night with its clock a year ahead, and the clock is corrected a
    minute later. The start records a time off that ends a year ahead. The first pass after the correction
    cuts it at that pass, so the upload, with an hour of uptime before the night, goes 23 hours of uptime
    after the correction (the minute before it counts as time off). Once the upload is gone, the next pass
    forgets the time off."""
    touched_upload(client, storage)
    swept = sweeper(client)
    swept.resume(now=T)
    assert run_passes(swept, T, T + HOUR) == 0
    ahead = T + 9 * HOUR + 365 * 86400
    swept.resume(now=ahead)
    assert swept.run_once(now=ahead) == {"uploads": 0}
    assert clock(storage) == (ahead, [(T + HOUR, ahead)])
    corrected = T + 9 * HOUR + 60
    assert swept.run_once(now=corrected) == {"uploads": 0}
    assert clock(storage) == (corrected, [(T + HOUR, corrected)])
    assert run_passes(swept, corrected, corrected + 23 * HOUR - 1) == 0
    assert swept.run_once(now=corrected + 23 * HOUR) == {"uploads": 1}
    swept.run_once(now=corrected + 23 * HOUR + 60)
    assert clock(storage) == (corrected + 23 * HOUR + 60, [])


def test_a_clock_behind_at_the_start_records_nothing_and_its_correction_counts_the_night(client, storage):
    """The computer starts after an 8-hour night with its clock 9 hours behind, so the clock reads an hour
    before the last pass. The start records no time off: a time off can not end before it began. The
    correction a minute later moves the clock 9 hours forward, and that gap counts as time off. The upload
    had 2 hours of uptime before the night and never goes early: it goes an hour and a minute after its day
    of real uptime, because the hour the clock went back counts as time off too."""
    touched_upload(client, storage)
    swept = sweeper(client)
    swept.resume(now=T)
    assert run_passes(swept, T, T + 2 * HOUR) == 0
    behind = T + HOUR  # the computer starts at T + 10 h
    swept.resume(now=behind)
    assert clock(storage) == (behind, [])
    assert swept.run_once(now=behind) == {"uploads": 0}
    corrected = T + 10 * HOUR + 60
    assert swept.run_once(now=corrected) == {"uploads": 0}
    assert clock(storage) == (corrected, [(behind, corrected)])
    assert run_passes(swept, corrected, T + 33 * HOUR + 60 - 1) == 0
    assert swept.run_once(now=T + 33 * HOUR + 60) == {"uploads": 1}


def test_a_start_with_a_clock_behind_the_last_pass_keeps_the_time_off_and_the_timing(client, storage):
    """The clock reads 30 minutes before the last pass when the server starts again, and nothing corrects it.
    A time off can not end before it began, so the start leaves the table as it was. The uptime counts by the
    clock from the start on: the upload goes when the clock reaches a day of uptime after its touch, as for a
    server that never stopped after its first restart."""
    touched_upload(client, storage)
    swept = sweeper(client)
    swept.resume(now=T)
    assert run_passes(swept, T, T + HOUR) == 0
    swept.resume(now=T + 2 * HOUR)
    assert run_passes(swept, T + 2 * HOUR, T + 4 * HOUR) == 0
    assert clock(storage) == (T + 4 * HOUR, [(T + HOUR, T + 2 * HOUR)])
    swept.resume(now=T + 3.5 * HOUR)
    assert clock(storage) == (T + 3.5 * HOUR, [(T + HOUR, T + 2 * HOUR)])
    assert run_passes(swept, T + 3.5 * HOUR, T + 25 * HOUR - 1) == 0
    assert swept.run_once(now=T + 25 * HOUR) == {"uploads": 1}


def test_a_start_inside_a_sleep_leaves_the_sleep_to_the_first_pass_which_cuts_it_at_its_own_moment(client, storage):
    """An hour of uptime, a 2-hour sleep, an hour of uptime, then a stop at T + 4 h. The server starts again with
    its clock at T + 2.5 h, inside the sleep and behind the last pass. The start records no time off and leaves
    the sleep as it was. The first pass reads the same moment as the start, and still cuts the sleep at that
    moment, because a time off can not end after now. The upload had two hours of uptime before the stop and
    goes 23 hours later by the clock, so it never goes early."""
    touched_upload(client, storage)
    swept = sweeper(client)
    swept.resume(now=T)
    assert run_passes(swept, T, T + HOUR) == 0
    assert run_passes(swept, T + 3 * HOUR, T + 4 * HOUR) == 0
    assert clock(storage) == (T + 4 * HOUR, [(T + HOUR, T + 3 * HOUR)])
    swept.resume(now=T + 2.5 * HOUR)
    assert clock(storage) == (T + 2.5 * HOUR, [(T + HOUR, T + 3 * HOUR)])
    assert swept.run_once(now=T + 2.5 * HOUR) == {"uploads": 0}
    assert clock(storage) == (T + 2.5 * HOUR, [(T + HOUR, T + 2.5 * HOUR)])
    assert run_passes(swept, T + 2.5 * HOUR, T + 25.5 * HOUR - 1) == 0
    assert swept.run_once(now=T + 25.5 * HOUR) == {"uploads": 1}


def test_a_clock_that_goes_back_while_the_server_runs_never_closes_an_upload_early(client, storage):
    """Two sleeps, then the clock goes back to the middle of the first one between two passes. The pass records
    no time off and writes the earlier moment as the last one. A time off can not end after now, so the pass
    cuts the first sleep at now and deletes the second, which now begins after now. The upload then stays
    longer than a day of real uptime, by less than the jump, and still goes."""
    touched_upload(client, storage)
    swept = sweeper(client)
    swept.resume(now=T)
    assert run_passes(swept, T, T + HOUR) == 0
    assert run_passes(swept, T + 2 * HOUR, T + 3 * HOUR) == 0
    assert run_passes(swept, T + 5 * HOUR, T + 6 * HOUR) == 0
    assert clock(storage) == (T + 6 * HOUR, [(T + HOUR, T + 2 * HOUR), (T + 3 * HOUR, T + 5 * HOUR)])
    back = T + 1.5 * HOUR  # three minutes after the last pass, the clock went back 4 h 33 min
    assert swept.run_once(now=back) == {"uploads": 0}
    assert clock(storage) == (back, [(T + HOUR, back)])
    # Half an hour of time off still counts: the upload goes 24.5 hours after its touch by the clock, after
    # 26 h 3 min of real uptime.
    assert run_passes(swept, back, T + 24.5 * HOUR - 1) == 0
    assert swept.run_once(now=T + 24.5 * HOUR) == {"uploads": 1}


def test_a_chunk_written_while_the_clock_read_ahead_holds_the_upload_a_day_after_the_first_pass(
        client, storage, link_account, route_clock):
    """The computer starts after an 8-hour night with its clock a year ahead, and the clock is corrected a
    minute later. In that minute the phone of a link account sends its last chunk and gives up, so the server
    writes the touch a year ahead. The first pass after the correction moves the touch back to its own moment.
    The upload then holds the account's single unfinished upload and its bytes on the disk for a day of uptime
    after that pass, and goes. It never goes early, because the chunk came before that pass."""
    invited = link_account()
    route_clock.now = T
    upload_id = create_upload(invited, create_match(invited), size=GIB).json()["uploadId"]
    swept = sweeper(client)
    swept.resume(now=T)
    assert run_passes(swept, T, T + HOUR) == 0
    ahead = T + 9 * HOUR + 365 * 86400
    swept.resume(now=ahead)
    assert swept.run_once(now=ahead) == {"uploads": 0}
    route_clock.now = ahead + 30
    assert invited.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abc").status_code == 200
    corrected = T + 9 * HOUR + 60
    assert swept.run_once(now=corrected) == {"uploads": 0}
    assert rows(storage, "SELECT touched_at FROM uploads WHERE id=?", upload_id) == [{"touched_at": corrected}]
    assert run_passes(swept, corrected, corrected + 86400 - 1) == 0
    route_clock.now = corrected + 86400 - 1
    assert refusal(create_upload(invited, create_match(invited), size=1))["reason"] == "UNFINISHED_UPLOAD"
    assert owed(storage) == GIB - 3
    assert swept.run_once(now=corrected + 86400) == {"uploads": 1}
    assert owed(storage) == 0
    route_clock.now = corrected + 86400
    assert create_upload(invited, create_match(invited), size=GIB).status_code == 201


def test_a_verification_started_while_the_clock_read_ahead_can_be_taken_over_15_minutes_after_the_first_pass(
        client, storage, route_clock):
    """The computer starts after an 8-hour night with its clock a year ahead. In the wrong minute four phones
    send their last chunk and call complete, and the server stops before any verification ends. It starts
    again with a right clock, and its first pass moves each touch and each start of a verification back to its
    own moment. For 15 minutes after that pass, complete, DELETE /v1/uploads/{id} and DELETE /v1/matches/{id}
    answer 409 UPLOAD_FINALIZING, as for any verification that may still run. Then complete takes the
    verification over, the two deletions go through, and the sweep closes the verification that nobody took
    over a day of uptime after the pass."""
    route_clock.now = T
    matches = [create_match(client) for _ in range(4)]
    uploads = [create_upload(client, match_id, size=3).json()["uploadId"] for match_id in matches]
    taken, aborted, _, left = uploads
    swept = sweeper(client)
    swept.resume(now=T)
    assert run_passes(swept, T, T + HOUR) == 0
    ahead = T + 9 * HOUR + 365 * 86400
    swept.resume(now=ahead)
    assert swept.run_once(now=ahead) == {"uploads": 0}
    route_clock.now = ahead + 10
    for upload_id in uploads:
        assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abc").status_code == 200
        # The complete marked the upload, and the stop ended the verification before it wrote a video.
        set_upload(storage, upload_id, status="FINALIZING", finalize_started_at=ahead + 20)
    swept.mark_alive(now=ahead + 30)
    started = T + 10 * HOUR
    swept.resume(now=started)
    assert swept.run_once(now=started) == {"uploads": 0}
    assert rows(storage, "SELECT DISTINCT status, touched_at, finalize_started_at FROM uploads") == [
        {"status": "FINALIZING", "touched_at": started, "finalize_started_at": started}]
    assert run_passes(swept, started, started + 15 * 60 - 1) == 0
    route_clock.now = started + 15 * 60 - 1
    for answer in (client.post(f"/v1/uploads/{taken}/complete"), client.delete(f"/v1/uploads/{aborted}"),
                   client.delete(f"/v1/matches/{matches[2]}")):
        assert (answer.status_code, answer.json()["error"]["code"]) == (409, "UPLOAD_FINALIZING")
    route_clock.now = started + 15 * 60
    assert client.post(f"/v1/uploads/{taken}/complete").status_code == 200
    assert client.delete(f"/v1/uploads/{aborted}").status_code == 204
    assert client.delete(f"/v1/matches/{matches[2]}").status_code == 204
    assert {row["id"]: row["status"] for row in rows(storage, "SELECT id, status FROM uploads")} == {
        taken: "COMPLETED", aborted: "ABORTED", left: "FINALIZING"}
    assert run_passes(swept, started + 15 * 60, started + 86400 - 1) == 0
    assert swept.run_once(now=started + 86400) == {"uploads": 1}
    assert upload_status(storage, left) == "ABORTED"


def test_a_verification_that_runs_while_the_clock_goes_back_is_verified_again_15_minutes_after_the_pass(
        client, storage, route_clock, monkeypatch):
    """A complete starts while the clock reads an hour ahead, and the clock is set back while the file is
    checked. The pass in between moves the start of the verification back to its own moment, and the request
    no longer owns the verification: it answers 409 UPLOAD_FINALIZING and writes no video. For 15 minutes
    after that pass the next complete answers the same, then it verifies the upload again."""
    route_clock.now = T
    upload_id = create_upload(client, create_match(client), size=3).json()["uploadId"]
    assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abc").status_code == 200
    swept = sweeper(client)
    swept.resume(now=T)
    probe = main_module.media.probe_video

    def clock_set_back_meanwhile(*arguments):
        assert swept.run_once(now=T + 60) == {"uploads": 0}
        return probe(*arguments)

    monkeypatch.setattr(main_module.media, "probe_video", clock_set_back_meanwhile)
    route_clock.now = T + HOUR
    first = client.post(f"/v1/uploads/{upload_id}/complete")
    assert (first.status_code, first.json()["error"]["code"]) == (409, "UPLOAD_FINALIZING")
    assert rows(storage, "SELECT status, finalize_started_at, video_id FROM uploads") == [
        {"status": "FINALIZING", "finalize_started_at": T + 60, "video_id": None}]
    monkeypatch.setattr(main_module.media, "probe_video", probe)
    route_clock.now = T + 60 + 15 * 60 - 1
    refused = client.post(f"/v1/uploads/{upload_id}/complete")
    assert (refused.status_code, refused.json()["error"]["code"]) == (409, "UPLOAD_FINALIZING")
    route_clock.now = T + 60 + 15 * 60
    assert client.post(f"/v1/uploads/{upload_id}/complete").status_code == 200
    assert upload_status(storage, upload_id) == "COMPLETED"


def test_a_pass_reads_the_clock_after_it_waited_for_the_database(client, storage, monkeypatch):
    """A pass reads the clock once it holds the write lock. A chunk that committed while the pass waited for the
    lock was written with an earlier reading of a right clock, so the pass leaves its touch as it is."""
    upload_id = create_upload(client, create_match(client), size=10).json()["uploadId"]
    swept = sweeper(client)
    connect, chunk = swept._connect, []

    @contextmanager
    def chunk_first(write=False):
        if write and not chunk:
            assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abc").status_code == 200
            chunk.append(rows(storage, "SELECT touched_at FROM uploads WHERE id=?", upload_id)[0]["touched_at"])
        with connect(write) as db:
            yield db

    monkeypatch.setattr(swept, "_connect", chunk_first)
    assert swept.run_once() == {"uploads": 0}
    assert rows(storage, "SELECT touched_at FROM uploads WHERE id=?", upload_id) == [{"touched_at": chunk[0]}]


def test_a_pass_that_can_not_write_the_clock_closes_nothing(client, storage, monkeypatch, caplog):
    """A pass that can not write the clock can not record a sleep before it either, so it closes nothing. The
    upload had 23 hours of uptime before an 8-hour sleep. The first pass after the sleep can not write the
    clock, and the next one records the sleep."""
    upload_id = touched_upload(client, storage)
    swept = sweeper(client)
    swept.resume(now=T)
    assert run_passes(swept, T, T + 23 * HOUR) == 0
    mark_alive = sweep.Sweeper.mark_alive

    def locked(self, now=None):
        raise sqlite3.OperationalError("database is locked")

    monkeypatch.setattr(sweep.Sweeper, "mark_alive", locked)
    with caplog.at_level(logging.ERROR, logger="app.sweep"):
        assert swept.run_once(now=T + 31 * HOUR) == {}
    assert upload_status(storage, upload_id) == "UPLOADING"
    assert any("server clock" in record.getMessage() for record in caplog.records)
    monkeypatch.setattr(sweep.Sweeper, "mark_alive", mark_alive)
    assert swept.run_once(now=T + 31 * HOUR + 60) == {"uploads": 0}
    assert clock(storage) == (T + 31 * HOUR + 60, [(T + 23 * HOUR, T + 31 * HOUR + 60)])


def test_the_first_pass_after_a_start_leaves_an_upload_touched_before_the_outage(storage):
    with serve(storage, None) as client:
        match_id = create_match(client)
        kept, stored_before_accounts = (create_upload(client, match_id, size=10).json()["uploadId"] for _ in range(2))
        assert client.put(f"/v1/uploads/{kept}/chunks?offset=0", content=b"abc").status_code == 200
    set_upload(storage, kept, touched_at=time.time() - 27 * 3600)
    # An upload stored before server 0.9 has no touch time and goes at the first pass (its match is
    # LEGACY): here it shows that the pass ran.
    set_upload(storage, stored_before_accounts, touched_at=None)
    with serve(storage, None, start_worker=True):
        wait_for(lambda: rows(storage, "SELECT status FROM uploads WHERE id=?", stored_before_accounts)[0]["status"] == "ABORTED")
        assert rows(storage, "SELECT status FROM uploads WHERE id=?", kept)[0]["status"] == "UPLOADING"
        assert (storage / "media" / f"{kept}.bin").read_bytes() == b"abc"


def write_clock(storage, last_alive):
    with closing(sqlite3.connect(storage / "volleystat.sqlite3")) as db, db:
        db.execute("INSERT OR REPLACE INTO server_clock(id, last_alive) VALUES(1, ?)", (last_alive,))


def test_the_first_pass_of_a_started_server_counts_the_uptime_before_its_stop(storage):
    """The server stopped 25 hours ago. One upload was idle for 2 hours of uptime before the stop and stays;
    the other was idle for a whole day of uptime before the stop and goes at the first pass."""
    with serve(storage, None) as client:
        match_id = create_match(client)
        kept, idle = (create_upload(client, match_id, size=10).json()["uploadId"] for _ in range(2))
    stopped = time.time() - 25 * HOUR
    write_clock(storage, stopped)
    set_upload(storage, kept, touched_at=stopped - 2 * HOUR)
    set_upload(storage, idle, touched_at=stopped - 86400 - 60)
    with serve(storage, None, start_worker=True):
        wait_for(lambda: rows(storage, "SELECT status FROM uploads WHERE id=?", idle)[0]["status"] == "ABORTED")
        assert rows(storage, "SELECT status FROM uploads WHERE id=?", kept)[0]["status"] == "UPLOADING"
        assert media_files(storage) == [f"{kept}.bin"]


def test_a_stop_writes_the_moment_the_server_stopped(storage):
    """The time off then starts at the stop, not at the last pass a minute before it."""
    with serve(storage, None) as client:
        marker = create_upload(client, create_match(client), size=10).json()["uploadId"]
    set_upload(storage, marker, touched_at=None)
    with serve(storage, None, start_worker=True):
        # The first pass closes the upload without a touch time: the pass and its clock write are done.
        wait_for(lambda: rows(storage, "SELECT status FROM uploads")[0]["status"] == "ABORTED")
        before_stop = time.time()
    assert clock(storage)[0] >= before_stop


def test_the_sweep_waits_for_the_clock_before_its_first_pass(storage, monkeypatch, caplog):
    """A sweep that could not record the time off would count it as idle time and could close every upload
    after a long outage. So the thread tries again a minute later and sweeps only after the clock is read."""
    events = []
    resume = sweep.Sweeper.resume

    def flaky_resume(self, now=None):
        events.append("resume")
        if events.count("resume") == 1:
            raise sqlite3.OperationalError("database is locked")
        return resume(self, now)

    def step(self, now):
        events.append("pass")
        return 0

    monkeypatch.setattr(sweep, "SWEEP_SECONDS", 0.01)
    monkeypatch.setattr(sweep.Sweeper, "resume", flaky_resume)
    monkeypatch.setattr(sweep.Sweeper, "abort_idle_uploads", step)
    with caplog.at_level(logging.ERROR, logger="app.sweep"):
        with TestClient(create_app(storage, analyzer="none")):
            wait_for(lambda: events.count("pass") >= 3)
    assert events[:3] == ["resume", "resume", "pass"]
    assert events.count("resume") == 2
    assert any("server clock" in record.getMessage() for record in caplog.records)


def test_a_sweeper_that_never_read_the_clock_leaves_it_as_it_was_at_the_stop(storage, monkeypatch):
    """A process whose start could not read the clock never wrote it, and its stop does not write it either:
    the clock stays as the last process that swept left it, and the next start counts the whole time off
    from there."""
    with serve(storage, None):
        pass
    stopped = time.time() - 8 * HOUR
    write_clock(storage, stopped)
    tries = []

    def locked(self, now=None):
        tries.append(now)
        raise sqlite3.OperationalError("database is locked")

    monkeypatch.setattr(sweep, "SWEEP_SECONDS", 0.01)
    monkeypatch.setattr(sweep.Sweeper, "resume", locked)
    with TestClient(create_app(storage, analyzer="none")):
        wait_for(lambda: len(tries) >= 2)
    assert clock(storage) == (stopped, [])


def sweeper_threads():
    return [thread for thread in threading.enumerate() if thread.name == sweep.THREAD_NAME]


def test_the_sweeper_thread_starts_and_stops_with_the_app(storage):
    assert sweeper_threads() == []
    with TestClient(create_app(storage, analyzer="none")):
        assert len(sweeper_threads()) == 1
    assert sweeper_threads() == []
    with serve(storage, None, start_worker=False):
        assert sweeper_threads() == []


def test_a_failing_sweep_step_does_not_stop_the_thread(storage, monkeypatch, caplog):
    calls = []

    def flaky(self, now):
        calls.append(now)
        if len(calls) == 1:
            raise RuntimeError("the disk went away for a moment")
        return 0

    monkeypatch.setattr(sweep, "SWEEP_SECONDS", 0.01)
    monkeypatch.setattr(sweep.Sweeper, "abort_idle_uploads", flaky)
    with caplog.at_level(logging.ERROR, logger="app.sweep"):
        with TestClient(create_app(storage, analyzer="none")):
            wait_for(lambda: len(calls) >= 3)
            assert len(sweeper_threads()) == 1
    assert any("sweep step uploads failed" in record.getMessage() for record in caplog.records)
