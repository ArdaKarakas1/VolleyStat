"""Storage limits, the free-disk floor and the sweep of idle uploads (docs/API_V1.md, section 10; design 6.5).

No test writes gigabytes: initiate_upload records the declared size and touches an empty file, and a
live video is an upload row in the COMPLETED state, because the limits read only the declared sizes.
Every test here patches the free space of the disk, so none depends on the runner's disk.
"""
import hashlib
import logging
import os
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
import uuid
from collections import namedtuple
from contextlib import closing, contextmanager
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app import admin, deletion, main as main_module, quotas, storagelock, sweep
from app.main import MAX_VIDEO_BYTES, create_app
from app.quotas import GIB
from tests.helpers import OWNER, as_account, rows, serve, session_for, user_id_of, wait_for
from tests.test_api import create_match, create_upload, set_upload
from tests.test_media import store

PIB = 1024 ** 5
REAL_FREE_BYTES = quotas.free_bytes
T = 1_700_000_000.0  # in the past, and a whole number of seconds, so T + 86400 - 86400 is T again
BACKEND = Path(__file__).resolve().parent.parent


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
        db.execute("INSERT INTO uploads(id, match_id, file_name, mime_type, expected_size, status, touched_at,"
                   " touched_uptime) VALUES(?,?,?,?,?,?,?,?)",
                   (upload_id, match_id, "dolgu.mp4", "video/mp4", size, status, time.time(), 0.0))
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
    set_upload(storage, upload_id, touched_at=T, touched_uptime=-1.0)
    write_uptime(storage, 7.0)
    again = start_of(client, match_id, data)
    assert again.status_code == 200, again.text
    assert (again.json()["uploadId"], again.json()["nextOffset"]) == (upload_id, 4)
    # A start counts as activity: the sweep waits a day of uptime from it.
    assert touched(storage, upload_id) > T and touched_uptime(storage, upload_id) == 7.0
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


# --- the touch and started_by -------------------------------------------------------------------------

def test_started_by_and_the_touch_are_written_at_initiate_upload(client, storage, stranger):
    write_uptime(storage, 3 * 3600.0)
    before = time.time()
    upload_id = create_upload(client, create_match(client), size=10).json()["uploadId"]
    other = create_upload(stranger, create_match(stranger), size=10).json()["uploadId"]
    after = time.time()
    found = {row["id"]: row for row in rows(storage, "SELECT id, started_by, touched_at, touched_uptime FROM uploads")}
    assert found[upload_id]["started_by"] == user_id_of(storage, OWNER)
    assert found[other]["started_by"] == user_id_of(storage, "test.yabanci")
    # The sweep decides by the server's uptime; the wall clock moment is kept as information only.
    assert all(row["touched_uptime"] == 3 * 3600.0 for row in found.values())
    assert all(before <= row["touched_at"] <= after for row in found.values())


def touched(storage, upload_id):
    return rows(storage, "SELECT touched_at FROM uploads WHERE id=?", upload_id)[0]["touched_at"]


def touched_uptime(storage, upload_id):
    return rows(storage, "SELECT touched_uptime FROM uploads WHERE id=?", upload_id)[0]["touched_uptime"]


def test_the_touch_moves_with_every_stored_chunk_and_with_a_checksum_reset(client, storage):
    """Without a running sweep the routes write the stored uptime (here 7 seconds) with every touch."""
    write_uptime(storage, 7.0)
    good = hashlib.sha256(b"abcde").hexdigest()
    upload_id = create_upload(client, create_match(client), size=5, clientSha256=good).json()["uploadId"]
    chunks = f"/v1/uploads/{upload_id}/chunks"

    def untouched():
        set_upload(storage, upload_id, touched_at=T, touched_uptime=-1.0)

    def moved():
        return touched(storage, upload_id) > T and touched_uptime(storage, upload_id) == 7.0

    for offset, data in ((0, b"ab"), (2, b"cd")):
        untouched()
        assert client.put(f"{chunks}?offset={offset}", content=data).status_code == 200
        assert moved()
    # A refused chunk moves nothing.
    untouched()
    assert client.put(f"{chunks}?offset=0", content=b"zz").status_code == 409
    assert (touched(storage, upload_id), touched_uptime(storage, upload_id)) == (T, -1.0)
    # Bytes lost from the disk put the offset back, and that counts as a touch too.
    with (storage / "media" / f"{upload_id}.bin").open("r+b") as part:
        part.truncate(1)
    assert client.put(f"{chunks}?offset=4", content=b"e").status_code == 409
    assert moved()
    untouched()
    assert client.put(f"{chunks}?offset=1", content=b"bcdX").status_code == 200
    untouched()
    assert client.post(f"/v1/uploads/{upload_id}/complete").json()["error"]["code"] == "UPLOAD_CHECKSUM_MISMATCH"
    assert moved()
    # A missing file found at complete reopens the upload from what is on disk.
    assert client.put(f"{chunks}?offset=0", content=b"abcde").status_code == 200
    (storage / "media" / f"{upload_id}.bin").unlink()
    untouched()
    assert client.post(f"/v1/uploads/{upload_id}/complete").json()["error"]["code"] == "UPLOAD_INCOMPLETE"
    assert moved()


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
        set_upload(storage, upload_id, touched_at=T, touched_uptime=-1.0)
        refused = client.put(f"/v1/uploads/{upload_id}/chunks?offset=3", content=b"defg")
        assert refusal(refused, 507, "STORAGE_FULL") == {"floorBytes": 10 * GIB}
        state = client.get(f"/v1/uploads/{upload_id}").json()
        assert (state["nextOffset"], state["status"]) == (3, "UPLOADING")
        assert (storage / "media" / f"{upload_id}.bin").read_bytes() == b"abc"
        assert (touched(storage, upload_id), touched_uptime(storage, upload_id)) == (T, -1.0)
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
DAY = 24 * HOUR
YEAR = 365 * DAY
STALE = deletion.FINALIZE_STALE_SECONDS
# A running server makes a pass once a minute. The counter counts any gap up to MAX_PASS_GAP_SECONDS between
# two of its writes in full, so the long stories below make a pass every three minutes: they count the same.
EVERY = sweep.MAX_PASS_GAP_SECONDS
# What a computer with a dead clock battery reads at every power on: 2009-01-01, five seconds after it.
RESET = 1_230_768_005.0


def upload_status(storage, upload_id):
    return rows(storage, "SELECT status FROM uploads WHERE id=?", upload_id)[0]["status"]


def owed(storage):
    """The bytes the unfinished uploads still hold on the disk (the free-disk floor's reservation)."""
    with closing(sqlite3.connect(storage / "volleystat.sqlite3")) as db:
        return quotas.still_owed(db)


def stored_uptime(storage):
    """The uptime the server wrote last (server_clock), or None before its first write."""
    found = rows(storage, "SELECT uptime FROM server_clock")
    return found[0]["uptime"] if found else None


def write_uptime(storage, uptime):
    with closing(sqlite3.connect(storage / "volleystat.sqlite3")) as db, db:
        db.execute("INSERT OR REPLACE INTO server_clock(id, uptime) VALUES(1, ?)", (uptime,))


class RouteClock:
    """Stands in for the time module in app.main: time.time() there reads the moment the test sets."""

    def __init__(self):
        self.now = None

    def time(self):
        return self.now


@pytest.fixture
def route_clock(monkeypatch):
    """The wall clock as the upload routes read it. They write touched_at and finalize_started_at with it, as
    information only: no decision reads those two columns. The sessions keep the real clock."""
    clock = RouteClock()
    monkeypatch.setattr(main_module, "time", clock)
    return clock


@pytest.fixture(autouse=True)
def no_wall_clock_in_the_sweep(monkeypatch):
    """The sweep module gets a time module without a wall clock: a pass, a start or a stop that read one
    fails, and so does every test here that runs it."""
    monkeypatch.setattr(sweep, "time", SimpleNamespace(monotonic=time.monotonic))


class Ticks:
    """The monotonic clock of a server process (time.monotonic), moved by the test."""

    def __init__(self, now):
        self.now = now

    def __call__(self):
        return self.now


class Computer:
    """The owner's computer and the server on it, played by the test.

    `real` is the real time of the story. The upload routes read the wall clock (route_clock), which shows
    `real` plus `wrong`: the test sets `wrong` where the old scenarios moved the clock. The sweep never reads
    it. `ticks` is the monotonic clock of the server process: it moves while the computer runs, and while it
    sleeps only when `sleep_counts`. `ran` sums the real time the server ran, the measure an upload's day of
    uptime must reach before the sweep closes it. Each boot starts a new sweeper, as a new process would."""

    def __init__(self, client, storage, wall, sleep_counts=False):
        self.client, self.storage, self.wall, self.sleep_counts = client, storage, wall, sleep_counts
        original = client.app.state.sweeper
        self._connect, self._files = original._connect, original._files
        self.real, self.wrong, self.ran = T, 0.0, 0.0
        self.ticks = Ticks(5.0)
        self.swept = None
        # Monotonic seconds since the last write of the uptime: run() keeps every gap within `every`.
        self.since = 0.0
        self._show()

    def _show(self):
        self.wall.now = self.real + self.wrong

    def set_wall(self, wrong):
        """From now on the wall clock reads `wrong` seconds away from the real time (0: right)."""
        self.wrong = wrong
        self._show()

    def boot(self, wrong=None, reboot=True):
        """A new server process: its start reads the stored uptime and it makes its first pass. A reboot starts
        the monotonic clock afresh; a process that restarts on a running computer finds it running on."""
        if wrong is not None:
            self.set_wall(wrong)
        if reboot:
            self.ticks = Ticks(5.0)
        self.swept = sweep.Sweeper(self._connect, self._files, monotonic=self.ticks)
        self.client.app.state.sweeper = self.swept
        self.swept.resume()
        self.since = 0.0
        return self.sweep_pass()

    def sweep_pass(self):
        closed = self.swept.run_once()["uploads"]
        self.since = 0.0
        return closed

    def go(self, seconds):
        """`seconds` of real time pass while the server runs, without a pass."""
        self.real += seconds
        self.ran += seconds
        self.ticks.now += seconds
        self.since += seconds
        self._show()

    def run(self, seconds, every=EVERY):
        """The server runs `seconds` of real time with a pass at most `every` seconds after the last write
        and one at the end. Returns how many uploads the passes closed."""
        closed = 0
        while seconds > 0:
            step = min(every - self.since, seconds)
            assert step > 0, "the story left a gap longer than a pass interval; it makes that pass itself"
            self.go(step)
            closed += self.sweep_pass()
            seconds -= step
        return closed

    def run_to(self, ran, every=EVERY):
        """Run until the story's uptime reaches `ran`, with a pass at that moment."""
        return self.run(ran - self.ran, every)

    def sleep(self, seconds):
        """The computer sleeps: the process stays, but it runs nothing and no phone reaches it."""
        self.real += seconds
        if self.sleep_counts:
            self.ticks.now += seconds
            self.since += seconds
        self._show()

    def stop(self):
        """A clean stop: the sweeper writes its uptime."""
        self.swept.stop()

    def crash(self):
        """The process dies, or the power goes: nothing is written."""
        self.swept = None

    def off(self, seconds):
        """No server runs. The computer's monotonic clock runs on unless the next boot is a reboot."""
        self.real += seconds
        self.ticks.now += seconds
        self._show()

    def uptime(self):
        return self.swept.uptime_now()


@pytest.fixture
def computer(client, storage, route_clock):
    return Computer(client, storage, route_clock)


def chunk(client, upload_id):
    """One more byte of the upload, through the route: a touch."""
    offset = client.get(f"/v1/uploads/{upload_id}").json()["nextOffset"]
    response = client.put(f"/v1/uploads/{upload_id}/chunks?offset={offset}", content=b"x")
    assert response.status_code == 200, response.text


def new_upload(client, size=10_000):
    return create_upload(client, create_match(client), size=size).json()["uploadId"]


def goes_at(computer, upload_ids, ran):
    """The passes leave every upload in `upload_ids` open until one second of uptime before `ran`, and the
    pass at `ran` closes them: never earlier, and not a second later."""
    assert computer.run_to(ran - 1) == 0
    assert {upload_status(computer.storage, each) for each in upload_ids} <= {"UPLOADING", "FINALIZING"}
    assert computer.run(1) == len(upload_ids)
    assert {upload_status(computer.storage, each) for each in upload_ids} == {"ABORTED"}


def verifying(storage, upload_id, computer, token):
    """The upload as a complete marks it (FINALIZING at the server's uptime), for a request that then dies."""
    set_upload(storage, upload_id, status="FINALIZING", finalize_started_at=computer.wall.now,
               finalize_started_uptime=computer.uptime(), finalize_token=token)


def full_upload(client, data=b"abc"):
    upload_id = create_upload(client, create_match(client), size=len(data)).json()["uploadId"]
    assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=data).status_code == 200
    return upload_id


def test_the_sweep_aborts_an_upload_idle_for_24_hours_of_uptime_and_deletes_its_bytes(client, storage, computer):
    upload_id = create_upload(client, create_match(client), size=10).json()["uploadId"]
    computer.boot()
    computer.go(30)
    assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abc").status_code == 200
    touched = computer.ran
    old_part = storage / "uploads" / f"{upload_id}.part"
    old_part.parent.mkdir(exist_ok=True)
    old_part.write_bytes(b"0.2")
    assert computer.run_to(touched + DAY - 1) == 0
    assert upload_status(storage, upload_id) == "UPLOADING"
    assert media_files(storage) == [f"{upload_id}.bin"]
    assert computer.run(1) == 1
    assert upload_status(storage, upload_id) == "ABORTED"
    assert media_files(storage) == [] and not old_part.exists()
    # The row stays, as after DELETE /v1/uploads/{id}, and the phone reads why its upload ended.
    assert client.get(f"/v1/uploads/{upload_id}").json()["status"] == "ABORTED"


def test_the_sweep_aborts_a_verification_that_started_a_day_of_uptime_ago_and_leaves_a_younger_one(
        client, storage, computer):
    """A verification counts from the moment it started, not from the last chunk."""
    match_id = create_match(client)
    old, young = (create_upload(client, match_id, size=5).json()["uploadId"] for _ in range(2))
    computer.boot()
    started = computer.ran
    verifying(storage, old, computer, "old")
    set_upload(storage, old, touched_uptime=computer.uptime() + DAY - 600)
    computer.run(1)
    verifying(storage, young, computer, "young")
    set_upload(storage, young, touched_uptime=computer.uptime() - 2 * DAY)
    young_start = rows(storage, "SELECT finalize_started_uptime FROM uploads WHERE id=?", young)[0]
    goes_at(computer, [old], started + DAY)
    found = {row["id"]: (row["status"], row["finalize_started_uptime"], row["finalize_token"]) for row in rows(
        storage, "SELECT id, status, finalize_started_uptime, finalize_token FROM uploads")}
    assert found == {old: ("ABORTED", None, None),
                     young: ("FINALIZING", young_start["finalize_started_uptime"], "young")}
    assert media_files(storage) == [f"{young}.bin"]


def test_the_sweep_leaves_completed_and_recent_uploads(client, storage, computer):
    computer.boot()
    upload_id = full_upload(client, b"abcde")
    video_id = client.post(f"/v1/uploads/{upload_id}/complete").json()["videoId"]
    recent = create_upload(client, create_match(client), size=5).json()["uploadId"]
    set_upload(storage, upload_id, touched_uptime=-10 * DAY, touched_at=0)
    assert computer.run(HOUR) == 0
    assert {row["id"]: row["status"] for row in rows(storage, "SELECT id, status FROM uploads")} == {
        upload_id: "COMPLETED", recent: "UPLOADING"}
    assert client.get(f"/v1/videos/{video_id}").status_code == 200
    assert media_files(storage) == sorted([f"{upload_id}.bin", f"{recent}.bin"])


def test_a_chunk_after_the_sweep_answers_409_and_a_new_upload_starts_from_zero(client, storage, computer):
    match_id = create_match(client)
    upload_id = create_upload(client, match_id, size=5).json()["uploadId"]
    computer.boot()
    assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abc").status_code == 200
    goes_at(computer, [upload_id], computer.ran + DAY)
    late = client.put(f"/v1/uploads/{upload_id}/chunks?offset=3", content=b"de")
    assert (late.status_code, late.json()["error"]["code"]) == (409, "UPLOAD_CLOSED")
    assert client.post(f"/v1/uploads/{upload_id}/complete").json()["error"]["code"] == "UPLOAD_CLOSED"
    again = create_upload(client, match_id, size=5)
    assert again.status_code == 201 and again.json()["nextOffset"] == 0


def test_an_aborted_upload_frees_the_link_accounts_unfinished_slot(client, storage, link_account, computer):
    invited = link_account()
    match_id = create_match(invited)
    computer.boot()
    upload_id = create_upload(invited, match_id, size=GIB).json()["uploadId"]
    assert refusal(create_upload(invited, match_id, size=1))["reason"] == "UNFINISHED_UPLOAD"
    goes_at(computer, [upload_id], computer.ran + DAY)
    assert create_upload(invited, match_id, size=GIB).status_code == 201


def test_the_time_the_server_was_off_does_not_count_as_idle_time(client, storage, computer):
    """A phone can not send a chunk to a server that is off: idle time counts only while the server runs.
    The last chunk and the start of a verification came two hours before a 25-hour outage, so both have 22
    hours left after the start. The stop wrote two hours of uptime and the start found them."""
    match_id = create_match(client)
    waiting, verifying_one = (create_upload(client, match_id, size=10).json()["uploadId"] for _ in range(2))
    computer.boot()
    assert client.put(f"/v1/uploads/{waiting}/chunks?offset=0", content=b"abc").status_code == 200
    verifying(storage, verifying_one, computer, "lost")
    touched = computer.ran
    assert computer.run(2 * HOUR) == 0
    computer.stop()
    assert stored_uptime(storage) == 2 * HOUR
    computer.off(25 * HOUR)
    assert computer.boot() == 0
    assert stored_uptime(storage) == 2 * HOUR
    goes_at(computer, [waiting, verifying_one], touched + DAY)
    assert media_files(storage) == []


def test_a_server_off_for_three_days_sweeps_nothing_in_its_first_hour_back(client, storage, computer):
    """The upload was idle for 23 hours of uptime when the server stopped. It stays for the first hour after
    the start, with a pass every minute, and goes when that hour is over, three days after the stop."""
    upload_id = new_upload(client)
    computer.boot()
    chunk(client, upload_id)
    touched = computer.ran
    assert computer.run(23 * HOUR) == 0
    computer.stop()
    computer.off(72 * HOUR)
    assert computer.boot() == 0
    assert computer.run(HOUR - 1, every=60) == 0
    assert upload_status(storage, upload_id) == "UPLOADING"
    assert computer.run(1) == 1
    assert computer.ran == touched + DAY
    assert media_files(storage) == []


def test_a_server_that_runs_20_hours_a_day_aborts_an_idle_upload_after_24_hours_of_its_uptime(
        client, storage, computer):
    """The review's probe: the owner's computer is the server and is off every night from 00:00 to 04:00.
    The upload went idle 10 minutes after the first start, so the first day gave it 19 h 50 min of uptime.
    Its 24th idle hour of uptime ends on the second day at 08:10, and the pass of 08:12 closes it. The third
    day closes nothing more."""
    opened = as_account(client, storage, "test.acik", "OPEN")
    upload_id = create_upload(opened, create_match(opened), size=GIB).json()["uploadId"]
    assert refusal(create_upload(opened, create_match(opened), size=1))["reason"] == "UNFINISHED_UPLOAD"
    closed_on = []
    for day in range(3):
        computer.boot()
        booted = computer.ran
        if day == 0:
            computer.run(600, every=60)
            set_upload(storage, upload_id, touched_uptime=computer.uptime())
            touched = computer.ran
        # A pass every three minutes while the server runs; the last one at midnight, just before the stop.
        while computer.ran < booted + 20 * HOUR:
            if computer.run(min(EVERY, booted + 20 * HOUR - computer.ran)):
                closed_on.append((day, computer.ran - booted))
        computer.stop()
        computer.off(4 * HOUR)
    # The day ends at 08:10, between the passes of 08:09 and 08:12.
    assert closed_on == [(1, 4 * HOUR + 720)] and touched == 600
    assert upload_status(storage, upload_id) == "ABORTED"
    assert f"{upload_id}.bin" not in media_files(storage)
    # The account's single unfinished upload is free again.
    assert create_upload(opened, create_match(opened), size=GIB).status_code == 201


def test_a_chunk_after_a_restart_gives_the_upload_a_new_day(client, storage, computer):
    """Two uploads with the same past: touched, then the server ran 10 hours, was off 72 hours and has run 13
    hours since. Only one of them gets a chunk then. The other goes after 11 more hours of uptime, the one with
    the chunk a day of uptime after the chunk."""
    chunked, left = new_upload(client), new_upload(client)
    computer.boot()
    chunk(client, chunked)
    chunk(client, left)
    touched = computer.ran
    assert computer.run(10 * HOUR) == 0
    computer.stop()
    computer.off(72 * HOUR)
    computer.boot()
    assert computer.run(13 * HOUR) == 0
    chunk(client, chunked)
    chunk_at = computer.ran
    goes_at(computer, [left], touched + DAY)
    assert upload_status(storage, chunked) == "UPLOADING"
    goes_at(computer, [chunked], chunk_at + DAY)
    assert media_files(storage) == []


def test_a_chunk_between_two_passes_counts_from_its_own_moment(client, storage, computer):
    """A chunk 40 seconds after a pass is written with the uptime of that moment, not with the uptime of the
    pass before it: the upload goes exactly a day of uptime after the chunk."""
    upload_id = new_upload(client)
    computer.boot()
    computer.run(HOUR)
    computer.go(40)
    chunk(client, upload_id)
    touched = computer.ran
    assert rows(storage, "SELECT touched_uptime FROM uploads") == [{"touched_uptime": stored_uptime(storage) + 40}]
    goes_at(computer, [upload_id], touched + DAY)


def test_a_verification_counts_only_the_uptime_after_it_started(client, storage, computer):
    """Two verifications that never end, around a 48-hour outage. The first started an hour before the stop and
    goes after 23 more hours of uptime. The second took its last chunk an hour before the stop and started a
    minute after the start: it goes a day of uptime after it started, not after its last chunk."""
    before, after = new_upload(client), new_upload(client)
    computer.boot()
    computer.run(HOUR)
    chunk(client, after)
    verifying(storage, before, computer, "before")
    started_before = computer.ran
    computer.run(HOUR)
    computer.stop()
    computer.off(48 * HOUR)
    computer.boot()
    computer.run(60)
    verifying(storage, after, computer, "after")
    started_after = computer.ran
    goes_at(computer, [before], started_before + DAY)
    assert upload_status(storage, after) == "FINALIZING"
    goes_at(computer, [after], started_after + DAY)


@pytest.mark.parametrize("end", ["stop", "crash"])
def test_a_dead_verification_is_taken_over_after_15_minutes_of_uptime_also_across_a_restart(
        client, storage, computer, end):
    """A verification starts, a minute and 40 seconds of uptime pass, and the server stops (or crashes 40
    seconds after its last pass) and stays off for 19 minutes. The wall clock then reads 21 minutes after the
    start of the verification, but only uptime counts. complete, DELETE of the upload and DELETE of the match
    answer 409 UPLOAD_FINALIZING until the verification is 15 minutes of uptime old; then complete takes it
    over and writes the video, and the two deletions go through. A crash loses the 40 seconds after the last
    pass, so the takeover comes 40 seconds of uptime later, never earlier."""
    taken, aborted, deleted = (full_upload(client) for _ in range(3))
    computer.boot()
    for upload_id in (taken, aborted, deleted):
        verifying(storage, upload_id, computer, "dead")
    started = computer.ran
    computer.run(60)
    computer.go(40)
    if end == "stop":
        computer.stop()
    else:
        computer.crash()
    computer.off(19 * 60)
    computer.boot()
    deleted_match = rows(storage, "SELECT match_id FROM uploads WHERE id=?", deleted)[0]["match_id"]
    free_at = started + STALE + (40 if end == "crash" else 0)
    computer.run_to(free_at - 1, every=60)
    for answer in (client.post(f"/v1/uploads/{taken}/complete"), client.delete(f"/v1/uploads/{aborted}"),
                   client.delete(f"/v1/matches/{deleted_match}")):
        assert (answer.status_code, answer.json()["error"]["code"]) == (409, "UPLOAD_FINALIZING")
    computer.run(1)
    assert client.post(f"/v1/uploads/{taken}/complete").status_code == 200
    assert client.delete(f"/v1/uploads/{aborted}").status_code == 204
    assert client.delete(f"/v1/matches/{deleted_match}").status_code == 204
    assert {row["id"]: (row["status"], row["finalize_token"])
            for row in rows(storage, "SELECT id, status, finalize_token FROM uploads")} == {
        taken: ("COMPLETED", None), aborted: ("ABORTED", None)}


@pytest.mark.parametrize("process", ["new", "same"])
def test_a_restart_of_a_few_seconds_adds_no_uptime(client, storage, computer, process):
    """The server stops three seconds of uptime before an upload's day ends and starts again five seconds later
    on the same computer, whose monotonic clock ran on. The start reads the stored uptime and a fresh reading
    of the monotonic clock, so the five seconds count as nothing: the upload goes three seconds of uptime after
    the start, not at its first pass. A new process and a sweeper started again behave alike."""
    upload_id = new_upload(client)
    computer.boot()
    chunk(client, upload_id)
    touched = computer.ran
    assert computer.run_to(touched + DAY - 3) == 0
    stopped = stored_uptime(storage)
    computer.stop()
    assert stored_uptime(storage) == stopped
    computer.off(5)
    if process == "new":
        assert computer.boot(reboot=False) == 0
    else:
        computer.swept.resume()
        assert computer.sweep_pass() == 0
    assert stored_uptime(storage) == stopped
    goes_at(computer, [upload_id], touched + DAY)


def test_a_stop_writes_the_uptime_since_the_last_pass(client, storage, computer):
    """A stop counts the uptime since the last pass, as a pass does: up to MAX_PASS_GAP_SECONDS in full, a
    longer gap as no uptime, and a monotonic clock that reads less than at the last write as no uptime either.
    After the stop the routes write the stored uptime."""
    computer.boot()
    computer.run(HOUR)
    computer.go(100)
    computer.stop()
    assert stored_uptime(storage) == HOUR + 100
    assert computer.swept.uptime_now() == HOUR + 100
    computer.boot()
    computer.go(sweep.MAX_PASS_GAP_SECONDS + 1)
    computer.stop()
    assert stored_uptime(storage) == HOUR + 100
    computer.boot()
    computer.ticks.now -= 10
    computer.stop()
    assert stored_uptime(storage) == HOUR + 100


@pytest.mark.parametrize("since_pass", [0, 50])
def test_a_crash_makes_an_upload_late_by_the_uptime_since_the_last_pass_and_never_early(
        client, storage, computer, since_pass):
    """The process dies `since_pass` seconds of uptime after its last pass, and the computer is off for 8 hours.
    The restart reads the uptime of that pass, so the seconds after it are lost: an upload touched before the
    pass goes that much late. A chunk 30 seconds after the pass, before the crash, was written 30 seconds ahead
    of the uptime the restart reads, so it goes late by the same seconds and never early."""
    before, between = new_upload(client), new_upload(client)
    computer.boot()
    chunk(client, before)
    touched_before = computer.ran
    computer.run(HOUR)
    computer.go(min(30, since_pass))
    chunk(client, between)
    touched_between = computer.ran
    computer.go(since_pass - min(30, since_pass))
    computer.crash()
    computer.off(8 * HOUR)
    assert computer.boot() == 0
    goes_at(computer, [before], touched_before + DAY + since_pass)
    goes_at(computer, [between], touched_between + DAY + since_pass)


@pytest.mark.parametrize("sleep_counts", [False, True])
def test_a_computer_that_sleeps_between_two_passes_does_not_count_the_night(client, storage, computer, sleep_counts):
    """The owner's computer may sleep instead of shutting down: the process stays, but no pass comes until the
    computer wakes, and no phone can reach the server. The computer sleeps 20 seconds after a pass and wakes 40
    seconds before the next one, and later naps for three minutes 10 seconds after a pass. A monotonic clock
    that stops in a sleep counts only the running seconds, so the upload goes after its day of uptime. One that
    counts the sleep makes each gap longer than MAX_PASS_GAP_SECONDS, and the server counts such a gap as no
    uptime: the upload goes late by the running seconds of the two gaps (60 + 10), never early."""
    # The documents name this length: three minutes, three sweep intervals.
    assert sweep.MAX_PASS_GAP_SECONDS == 3 * 60 == 3 * sweep.SWEEP_SECONDS
    computer.sleep_counts = sleep_counts
    upload_id = new_upload(client)
    computer.boot()
    chunk(client, upload_id)
    touched = computer.ran
    computer.run(HOUR)
    computer.go(20)
    computer.sleep(8 * HOUR)
    computer.go(40)
    assert computer.sweep_pass() == 0
    computer.run(HOUR)
    computer.go(10)
    computer.sleep(sweep.MAX_PASS_GAP_SECONDS)
    assert computer.sweep_pass() == 0
    goes_at(computer, [upload_id], touched + DAY + (70 if sleep_counts else 0))


def test_a_stop_right_after_a_sleep_counts_the_sleep_as_no_uptime(client, storage, computer):
    """The computer wakes from an 8-hour sleep and the server stops 10 seconds later, before its next pass. The
    monotonic clock counted the sleep, so the stop finds a gap longer than MAX_PASS_GAP_SECONDS since the last
    pass and writes no uptime for it. The upload goes late by the 30 running seconds of that gap."""
    computer.sleep_counts = True
    upload_id = new_upload(client)
    computer.boot()
    chunk(client, upload_id)
    touched = computer.ran
    computer.run(HOUR)
    computer.go(20)
    computer.sleep(8 * HOUR)
    computer.go(10)
    computer.stop()
    assert stored_uptime(storage) == HOUR
    computer.off(HOUR)
    computer.boot()
    goes_at(computer, [upload_id], touched + DAY + 30)


def test_a_nap_shorter_than_the_longest_pass_gap_counts_as_uptime_where_the_monotonic_clock_counts_it(
        client, storage, computer):
    """The documented tolerance: the computer naps for 100 seconds between two passes, and its monotonic clock
    counts the nap. The gap is 160 seconds, not longer than MAX_PASS_GAP_SECONDS, so the server counts it in
    full: the upload goes up to the nap early. A nap always leaves the gap shorter than three minutes here."""
    computer.sleep_counts = True
    upload_id = new_upload(client)
    computer.boot()
    chunk(client, upload_id)
    touched = computer.ran
    computer.run(HOUR)
    computer.go(20)
    computer.sleep(100)
    computer.go(40)
    assert computer.sweep_pass() == 0
    goes_at(computer, [upload_id], touched + DAY - 100)


def test_a_pass_that_comes_after_more_than_three_minutes_counts_the_whole_gap_as_no_uptime(client, storage, computer):
    """A pass waits for the database and comes 250 seconds after the last one while the server runs. The server
    can not tell that gap from a sleep, so it counts no uptime for it: an upload touched before it goes 250
    seconds late. A chunk 100 seconds into the gap was written 100 seconds ahead of the uptime the slow pass
    writes, so it goes 250 seconds late too, never early."""
    before, inside = new_upload(client), new_upload(client)
    computer.boot()
    chunk(client, before)
    touched_before = computer.ran
    computer.run(HOUR)
    computer.go(100)
    chunk(client, inside)
    touched_inside = computer.ran
    computer.go(150)
    assert computer.sweep_pass() == 0
    assert stored_uptime(storage) == HOUR
    goes_at(computer, [before], touched_before + DAY + 250)
    goes_at(computer, [inside], touched_inside + DAY + 250)


@pytest.mark.parametrize("step", [-0.5, -HOUR, -2 * DAY, -YEAR, YEAR])
@pytest.mark.parametrize("seen", [False, True])
def test_a_wall_clock_that_steps_while_the_server_runs_moves_no_upload(client, storage, computer, step, seen):
    """The wall clock steps right after a pass while the server runs: half a second, an hour, two days or a year
    back, or a year ahead. A chunk lands 20 seconds into the step. Either NTP puts the clock right 40 seconds after
    the step, before any pass sees it, or the clock stays wrong for half an hour of passes. Then the computer
    sleeps 8 hours. One upload was touched 5 hours before the step, one an hour before it, one inside it, and a
    phone keeps a fourth upload alive with a chunk every 30 minutes of uptime. Each abandoned upload goes
    exactly a day of uptime after its last chunk, the live one never goes, and the wall clock changes nothing."""
    early, before, inside, live = (new_upload(client) for _ in range(4))
    computer.boot()
    chunk(client, early)
    touched = {early: computer.ran}
    computer.run(4 * HOUR)
    chunk(client, before)
    touched[before] = computer.ran
    computer.run(HOUR)
    computer.set_wall(step)
    computer.go(20)
    chunk(client, inside)
    touched[inside] = computer.ran
    computer.go(20)
    if not seen:
        computer.set_wall(0)
    computer.go(20)
    assert computer.sweep_pass() == 0
    computer.run(30 * 60)
    computer.set_wall(0)
    computer.sleep(8 * HOUR)
    for upload_id in (early, before, inside):
        while computer.ran + 30 * 60 < touched[upload_id] + DAY:
            assert computer.run(30 * 60) == 0
            chunk(client, live)
        goes_at(computer, [upload_id], touched[upload_id] + DAY)
    assert upload_status(storage, live) == "UPLOADING"


@pytest.mark.parametrize("wrong, corrected", [
    (YEAR, True),                 # a year ahead after an 8-hour night, put right a minute later
    (2 * HOUR, True),             # two hours ahead, put right a minute later
    (-9 * HOUR, True),            # 9 hours behind, the end of the earlier sleep: put right a minute later
    (-30 * 60, False),            # half an hour behind the stop, never put right
    (-9.5 * HOUR, False),         # inside the earlier sleep, never put right
    (RESET - (T + 12 * HOUR), False),  # 2009 at power on, never put right
])
def test_a_wall_clock_wrong_at_the_start_moves_no_upload_and_no_verification(
        client, storage, computer, wrong, corrected):
    """An hour of uptime, a 2-hour sleep, an hour of uptime, a stop and an 8-hour night. The server starts with
    its wall clock `wrong` seconds off. In the wrong minute one phone sends its last chunk and two phones call
    complete; their requests die with a restart 5 minutes later. The wall clock is put right a minute after the
    start, or never. complete takes one verification over 15 minutes of uptime after it started, the sweep
    closes the other a day of uptime after it started, and each upload goes a day of uptime after its last
    chunk."""
    first, wrong_minute, taken, left = new_upload(client), new_upload(client), full_upload(client), full_upload(client)
    computer.boot()
    chunk(client, first)
    touched_first = computer.ran
    computer.run(HOUR)
    computer.sleep(2 * HOUR)
    computer.run(HOUR)
    computer.stop()
    computer.off(8 * HOUR)
    assert computer.real == T + 12 * HOUR
    assert computer.boot(wrong=wrong) == 0
    computer.go(10)
    chunk(client, wrong_minute)
    verifying(storage, taken, computer, "dead")
    verifying(storage, left, computer, "dead")
    started = computer.ran
    computer.go(50)
    if corrected:
        computer.set_wall(0)
    assert computer.sweep_pass() == 0
    computer.run(4 * 60)
    computer.stop()
    computer.off(60)
    computer.boot()
    computer.run_to(started + STALE - 1, every=60)
    answer = client.post(f"/v1/uploads/{taken}/complete")
    assert (answer.status_code, answer.json()["error"]["code"]) == (409, "UPLOAD_FINALIZING")
    computer.run(1)
    assert client.post(f"/v1/uploads/{taken}/complete").status_code == 200
    computer.run(HOUR)
    computer.sleep(8 * HOUR)
    goes_at(computer, [first], touched_first + DAY)
    goes_at(computer, [wrong_minute, left], started + DAY)
    assert upload_status(storage, taken) == "COMPLETED"


@pytest.mark.parametrize("hours", [20, 2])
@pytest.mark.parametrize("ntp", ["a minute later", "never", "after a restart inside that minute"])
def test_a_clock_reset_at_every_boot_still_lets_an_abandoned_upload_go(client, storage, computer, hours, ntp):
    """The checker's probes: the computer runs `hours` hours a day, and a dead clock battery makes its wall clock
    read 2009-01-01 at every power on. NTP puts it right a minute later, or never, or the server process
    restarts 10 seconds after the boot and NTP puts the clock right after that. The phone sends its last chunk
    20 seconds after the first boot, while the clock reads 2009. The upload goes exactly a day of uptime after
    that chunk, on whichever day that falls."""
    upload_id = new_upload(client)
    touched = None
    for day in range(30):
        computer.off(T + day * DAY + 4 * HOUR - computer.real)
        booted, end = computer.ran, computer.ran + hours * HOUR
        assert computer.boot(wrong=RESET - computer.real) == 0
        if ntp == "after a restart inside that minute":
            computer.go(10)
            computer.stop()
            computer.off(5)
            # The wall clock runs on from 2009.
            assert computer.boot(reboot=False) == 0
        if touched is None:
            computer.go(20)
            chunk(client, upload_id)
            touched = computer.ran
        if ntp != "never" and touched + DAY > booted + 60:
            assert computer.run_to(booted + 60) == 0
            computer.set_wall(0)
        if touched + DAY <= end:
            goes_at(computer, [upload_id], touched + DAY)
            break
        assert computer.run_to(end) == 0
        computer.stop()
    else:
        raise AssertionError("the upload never went")
    assert day == (1 if hours == 20 else 12)


def test_chunks_written_before_the_sweep_read_its_uptime_count_from_the_stored_uptime(client, storage, computer):
    """The computer boots with its wall clock reading 2009. The sweep can not read the stored uptime for six
    minutes after the process started (the database was busy), and a phone sends its last chunk in that time.
    Until the sweep reads its uptime the routes write the stored uptime, and the six minutes count as nothing:
    that upload goes late by the uptime between its chunk and the moment the sweep read the stored uptime, never
    early. A chunk after the sweep read its uptime counts from its own moment."""
    early, late = new_upload(client), new_upload(client)
    computer.boot()
    computer.run(HOUR)
    computer.stop()
    computer.off(8 * HOUR)
    computer.set_wall(RESET - computer.real)
    computer.ticks = Ticks(5.0)
    reborn = sweep.Sweeper(computer._connect, computer._files, monotonic=computer.ticks)
    client.app.state.sweeper = computer.swept = reborn
    computer.go(1)
    chunk(client, early)
    touched_early = computer.ran
    assert rows(storage, "SELECT touched_uptime FROM uploads WHERE id=?", early) == [{"touched_uptime": HOUR}]
    computer.go(359)
    reborn.resume()
    assert computer.sweep_pass() == 0
    computer.go(40)
    chunk(client, late)
    touched_late = computer.ran
    computer.set_wall(0)
    goes_at(computer, [early], touched_early + DAY + 359)
    goes_at(computer, [late], touched_late + DAY)


def test_a_chunk_written_while_the_clock_read_ahead_holds_the_upload_a_day_of_uptime(
        client, storage, link_account, computer):
    """The computer starts after an 8-hour night with its wall clock a year ahead, and the clock is put right a
    minute later. In that minute the phone of a link account sends its last chunk and gives up. The upload holds
    the account's single unfinished upload and its bytes on the disk for a day of uptime after that chunk, and
    goes then: the year ahead neither holds it longer nor lets it go earlier."""
    invited = link_account()
    upload_id = create_upload(invited, create_match(invited), size=GIB).json()["uploadId"]
    computer.boot()
    computer.run(HOUR)
    computer.stop()
    computer.off(8 * HOUR)
    computer.boot(wrong=YEAR)
    computer.go(30)
    assert invited.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abc").status_code == 200
    touched = computer.ran
    computer.go(30)
    computer.set_wall(0)
    assert computer.run_to(touched + DAY - 1) == 0
    assert refusal(create_upload(invited, create_match(invited), size=1))["reason"] == "UNFINISHED_UPLOAD"
    assert owed(storage) == GIB - 3
    assert computer.run(1) == 1
    assert owed(storage) == 0
    assert create_upload(invited, create_match(invited), size=GIB).status_code == 201


@pytest.mark.parametrize("back", [0.5, HOUR, 2 * DAY])
def test_a_verification_that_runs_while_the_clock_steps_back_still_writes_its_video(
        client, storage, computer, monkeypatch, back):
    """A complete starts, and while it checks the file the wall clock steps back and a pass lands in between.
    The request owns the verification by the token it wrote with its mark, and the pass changes neither the
    token nor the start: the request writes the video and answers 200 at once."""
    upload_id = full_upload(client)
    computer.boot()
    computer.run(HOUR)
    probe = main_module.media.probe_video
    during = []

    def clock_set_back_meanwhile(*arguments):
        computer.set_wall(-back)
        computer.go(30)
        assert computer.sweep_pass() == 0
        during.extend(rows(storage, "SELECT status, finalize_started_uptime FROM uploads"))
        return probe(*arguments)

    monkeypatch.setattr(main_module.media, "probe_video", clock_set_back_meanwhile)
    computer.go(30)
    started = computer.uptime()
    done = client.post(f"/v1/uploads/{upload_id}/complete")
    assert done.status_code == 200, done.text
    assert during == [{"status": "FINALIZING", "finalize_started_uptime": started}]
    assert rows(storage, "SELECT status, video_id, finalize_started_uptime, finalize_token FROM uploads") == [
        {"status": "COMPLETED", "video_id": done.json()["videoId"], "finalize_started_uptime": None,
         "finalize_token": None}]
    assert rows(storage, "SELECT id FROM videos") == [{"id": done.json()["videoId"]}]


def test_a_takeover_after_15_minutes_of_uptime_wins_and_the_request_it_replaced_writes_nothing(
        client, storage, computer, monkeypatch):
    """A complete hangs for 15 minutes of uptime while it checks the file. The next complete takes the
    verification over with a new token and writes the video. The first request then answers 409
    UPLOAD_FINALIZING and writes nothing: the upload keeps the video of the request that took it over."""
    upload_id = full_upload(client)
    computer.boot()
    probe = main_module.media.probe_video
    tokens, later = [], []

    def taken_over_meanwhile(*arguments):
        # Each request reads the token in the row while it checks the file: the first one, then the next.
        tokens.extend(rows(storage, "SELECT finalize_token FROM uploads"))
        if len(tokens) == 1:
            computer.run(STALE - 1, every=60)
            early = client.post(f"/v1/uploads/{upload_id}/complete")
            assert (early.status_code, early.json()["error"]["code"]) == (409, "UPLOAD_FINALIZING")
            computer.run(1)
            later.append(client.post(f"/v1/uploads/{upload_id}/complete"))
        return probe(*arguments)

    monkeypatch.setattr(main_module.media, "probe_video", taken_over_meanwhile)
    first = client.post(f"/v1/uploads/{upload_id}/complete")
    assert (first.status_code, first.json()["error"]["code"]) == (409, "UPLOAD_FINALIZING")
    assert later[0].status_code == 200, later[0].text
    video_id = later[0].json()["videoId"]
    # Each complete wrote its own token with its mark.
    assert len(tokens) == 2 and len({row["finalize_token"] for row in tokens} - {None}) == 2
    assert rows(storage, "SELECT id FROM videos") == [{"id": video_id}]
    assert rows(storage, "SELECT status, video_id, finalize_token FROM uploads") == [
        {"status": "COMPLETED", "video_id": video_id, "finalize_token": None}]


def test_a_checksum_mismatch_after_a_takeover_leaves_the_file_and_the_row_to_the_request_that_took_over(
        client, storage, computer, monkeypatch):
    """A complete hangs for 15 minutes of uptime while it hashes a file whose bytes differ from the declared
    SHA-256, and the next complete takes the verification over. The first request finds the mismatch while the
    second still checks the file. It no longer owns the verification, so it answers 409 UPLOAD_FINALIZING and
    leaves the file and the row as they are. The request that took over then finds the same mismatch, reopens
    the upload at offset 0 and empties the file."""
    upload_id = start_of(client, create_match(client), sha=hashlib.sha256(b"abc").hexdigest(), size=3).json()[
        "uploadId"]
    assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abX").status_code == 200
    computer.boot()
    part = storage / "media" / f"{upload_id}.bin"
    real_sha256 = hashlib.sha256
    second_marked, release_second, hashers, takeovers, answers = (
        threading.Event(), threading.Event(), [], [], [])
    taken_at = []

    class Hasher:
        """The first hasher belongs to the first request, the second to the request that takes over."""

        def __init__(self):
            self.inner, self.order = real_sha256(), len(hashers)
            hashers.append(self)
            if self.order == 1:
                # The second request has written its mark and checks the file until the test lets it go on.
                second_marked.set()
                assert release_second.wait(10)

        def update(self, block):
            self.inner.update(block)

        def hexdigest(self):
            if self.order == 0:
                computer.run(STALE, every=60)
                taken_at.append(computer.uptime())
                takeovers.append(threading.Thread(
                    target=lambda: answers.append(client.post(f"/v1/uploads/{upload_id}/complete"))))
                takeovers[0].start()
                assert second_marked.wait(10)
            return self.inner.hexdigest()

    monkeypatch.setattr(main_module, "hashlib", SimpleNamespace(sha256=Hasher))
    try:
        first = client.post(f"/v1/uploads/{upload_id}/complete")
        assert (first.status_code, first.json()["error"]["code"]) == (409, "UPLOAD_FINALIZING")
        assert part.read_bytes() == b"abX"
        assert rows(storage, "SELECT status, next_offset, finalize_started_uptime FROM uploads") == [
            {"status": "FINALIZING", "next_offset": 3, "finalize_started_uptime": taken_at[0]}]
    finally:
        release_second.set()
        for takeover in takeovers:
            takeover.join(10)
    second, = answers
    assert (second.status_code, second.json()["error"]["code"]) == (422, "UPLOAD_CHECKSUM_MISMATCH")
    assert part.read_bytes() == b""
    assert rows(storage, "SELECT status, next_offset, finalize_started_uptime, finalize_token FROM uploads") == [
        {"status": "UPLOADING", "next_offset": 0, "finalize_started_uptime": None, "finalize_token": None}]


def test_a_pass_writes_the_uptime_it_reads_once_it_holds_the_write_lock_and_moves_no_touch(
        client, storage, computer, monkeypatch):
    """A chunk commits 30 seconds of uptime after the last pass while the next pass waits for the database.
    The pass reads its uptime once it holds the write lock, so it writes the moment of the chunk or a later
    one, and it leaves the chunk's touch as the route wrote it."""
    upload_id = new_upload(client)
    computer.boot()
    connect, written = computer.swept._connect, []

    @contextmanager
    def chunk_first(write=False):
        if write and not written:
            computer.go(30)
            chunk(client, upload_id)
            written.append(rows(storage, "SELECT touched_uptime FROM uploads WHERE id=?", upload_id)[0])
        with connect(write) as db:
            yield db

    monkeypatch.setattr(computer.swept, "_connect", chunk_first)
    assert computer.sweep_pass() == 0
    assert written == [{"touched_uptime": 30.0}]
    assert stored_uptime(storage) == 30.0
    assert rows(storage, "SELECT touched_uptime FROM uploads WHERE id=?", upload_id) == written


def test_a_pass_that_can_not_write_its_uptime_closes_nothing(client, storage, computer, monkeypatch, caplog):
    """The upload had a day of uptime but for a minute when a pass can not write the uptime (the database is
    locked). That pass closes nothing, and the next one, two minutes after the last write, counts both minutes
    and closes the upload."""
    upload_id = new_upload(client)
    computer.boot()
    chunk(client, upload_id)
    touched = computer.ran
    assert computer.run_to(touched + DAY - 60) == 0
    mark_alive = sweep.Sweeper.mark_alive

    def locked(self):
        raise sqlite3.OperationalError("database is locked")

    monkeypatch.setattr(sweep.Sweeper, "mark_alive", locked)
    computer.go(60)
    with caplog.at_level(logging.ERROR, logger="app.sweep"):
        assert computer.swept.run_once() == {}
    assert upload_status(storage, upload_id) == "UPLOADING"
    assert any("uptime" in record.getMessage() for record in caplog.records)
    monkeypatch.setattr(sweep.Sweeper, "mark_alive", mark_alive)
    computer.go(60)
    assert computer.sweep_pass() == 1
    assert stored_uptime(storage) == DAY + 60


def test_without_a_running_sweep_the_routes_write_the_stored_uptime(client, storage, monkeypatch):
    """A server that never wrote its uptime starts at 0. Without a sweep that read the stored uptime (a test
    client without threads, an operator command) the routes write the stored uptime with a touch and with the
    mark of a verification."""
    upload_id = create_upload(client, create_match(client), size=3).json()["uploadId"]
    assert rows(storage, "SELECT touched_uptime FROM uploads") == [{"touched_uptime": 0.0}]
    write_uptime(storage, 5 * DAY)
    assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abc").status_code == 200
    assert rows(storage, "SELECT touched_uptime FROM uploads") == [{"touched_uptime": 5 * DAY}]
    probe, marked = main_module.media.probe_video, []

    def read_the_mark(*arguments):
        marked.extend(rows(storage, "SELECT finalize_started_uptime FROM uploads"))
        return probe(*arguments)

    monkeypatch.setattr(main_module.media, "probe_video", read_the_mark)
    assert client.post(f"/v1/uploads/{upload_id}/complete").status_code == 200
    assert marked == [{"finalize_started_uptime": 5 * DAY}]


def test_the_first_pass_after_a_start_leaves_an_upload_touched_before_the_outage(storage):
    """The wall clock says the upload was touched 27 hours ago, but the server ran no time since: the first
    pass of the next start leaves it. An upload stored before server 0.9 has no touch at all and goes at the
    first pass (its match is LEGACY): here it shows that the pass ran."""
    with serve(storage, None) as client:
        match_id = create_match(client)
        kept, stored_before_accounts = (create_upload(client, match_id, size=10).json()["uploadId"] for _ in range(2))
        assert client.put(f"/v1/uploads/{kept}/chunks?offset=0", content=b"abc").status_code == 200
    set_upload(storage, kept, touched_at=time.time() - 27 * 3600)
    set_upload(storage, stored_before_accounts, touched_at=None, touched_uptime=None)
    with serve(storage, None, start_worker=True):
        wait_for(lambda: upload_status(storage, stored_before_accounts) == "ABORTED")
        assert upload_status(storage, kept) == "UPLOADING"
        assert (storage / "media" / f"{kept}.bin").read_bytes() == b"abc"


def test_the_first_pass_of_a_started_server_counts_the_uptime_before_its_stop(storage):
    """The server wrote 100 hours of uptime when it stopped. One upload was idle for 2 hours of uptime before
    the stop and stays; the other was idle for a day of uptime and a minute and goes at the first pass."""
    with serve(storage, None) as client:
        match_id = create_match(client)
        kept, idle = (create_upload(client, match_id, size=10).json()["uploadId"] for _ in range(2))
    write_uptime(storage, 100 * HOUR)
    set_upload(storage, kept, touched_uptime=98 * HOUR)
    set_upload(storage, idle, touched_uptime=100 * HOUR - DAY - 60)
    with serve(storage, None, start_worker=True):
        wait_for(lambda: upload_status(storage, idle) == "ABORTED")
        assert upload_status(storage, kept) == "UPLOADING"
        assert media_files(storage) == [f"{kept}.bin"]


def test_a_stop_writes_the_uptime_the_server_ran(storage):
    """The stored uptime grows by the time the server ran, the time after its last pass included."""
    with serve(storage, None) as client:
        marker = create_upload(client, create_match(client), size=10).json()["uploadId"]
    set_upload(storage, marker, touched_uptime=None, touched_at=None)
    write_uptime(storage, 7 * HOUR)
    with serve(storage, None, start_worker=True):
        # The first pass closes the upload without a touch: the start and the first pass are done.
        wait_for(lambda: upload_status(storage, marker) == "ABORTED")
        first_pass = time.monotonic()
        time.sleep(0.3)
        before_stop = time.monotonic()
    assert 7 * HOUR + (before_stop - first_pass) <= stored_uptime(storage) < 7 * HOUR + 60


def test_the_sweep_waits_for_its_uptime_before_its_first_pass(storage, monkeypatch, caplog):
    """A sweep that has not read the stored uptime can not count a day of it. So the thread tries again a
    minute later and sweeps only after the start read it."""
    events = []
    resume = sweep.Sweeper.resume

    def flaky_resume(self):
        events.append("resume")
        if events.count("resume") == 1:
            raise sqlite3.OperationalError("database is locked")
        return resume(self)

    def step(self):
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
    assert any("uptime" in record.getMessage() for record in caplog.records)


def test_a_sweeper_that_never_read_its_uptime_leaves_it_as_it_was_at_the_stop(storage, monkeypatch):
    """A process whose start could not read the stored uptime never wrote it, and its stop does not write it
    either: the stored uptime stays as the last process that swept left it."""
    with serve(storage, None):
        pass
    write_uptime(storage, 8 * HOUR)
    tries = []

    def locked(self):
        tries.append(self)
        raise sqlite3.OperationalError("database is locked")

    monkeypatch.setattr(sweep, "SWEEP_SECONDS", 0.01)
    monkeypatch.setattr(sweep.Sweeper, "resume", locked)
    with TestClient(create_app(storage, analyzer="none")):
        wait_for(lambda: len(tries) >= 2)
    assert stored_uptime(storage) == 8 * HOUR


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

    def flaky(self):
        calls.append(self)
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


# --- one server per storage folder ---------------------------------------------------------------------

def test_a_second_server_on_the_same_storage_folder_refuses_to_start(storage, tmp_path):
    """Two servers on one storage folder would both count uptime, sweep and write media files. The second one
    refuses before its threads start. The operator commands still run beside the server, a server on another
    folder starts, and once the first server stops a new one starts on its folder."""
    with TestClient(create_app(storage, analyzer="none")):
        with pytest.raises(storagelock.StorageInUse, match="already uses the storage folder"):
            with TestClient(create_app(storage, analyzer="none")):
                pass
        assert len(sweeper_threads()) == 1
        assert admin.main(["--storage", str(storage), "legacy", "--list"]) == 0
        with TestClient(create_app(tmp_path / "baska-klasor", analyzer="none")):
            assert len(sweeper_threads()) == 2
    with TestClient(create_app(storage, analyzer="none")):
        assert len(sweeper_threads()) == 1


def server_command(storage, *arguments, **options):
    """`python -m app` in another process, on `storage`. The server binds no port before it holds the folder."""
    environment = {**os.environ, "VOLLEYSTAT_STORAGE": str(storage), "VOLLEYSTAT_DISK_FLOOR_GIB": "0"}
    environment.pop("VOLLEYSTAT_ANALYZER", None)
    return subprocess.run([sys.executable, "-m", "app", "--port", "0", *arguments], cwd=BACKEND, env=environment,
                          capture_output=True, text=True, timeout=60, **options)


def test_python_m_app_refuses_a_storage_folder_that_another_server_uses(storage):
    with TestClient(create_app(storage, analyzer="none")):
        refused = server_command(storage)
    assert refused.returncode == 1, refused.stderr
    assert f"Another VolleyStat server already uses the storage folder {storage.resolve()}" in refused.stderr
    assert "Uvicorn running" not in refused.stderr


def test_a_server_that_died_leaves_its_storage_folder_free(storage):
    """The lock belongs to the process: the operating system lets it go when the process ends, however it
    ends. A process that holds the folder and is killed leaves no stale lock behind."""
    holder = subprocess.Popen(
        [sys.executable, "-c", "import sys, time; from pathlib import Path; from app import storagelock;"
                               " storagelock.StorageLock(Path(sys.argv[1])).acquire(); print('held', flush=True);"
                               " time.sleep(60)", str(storage)],
        cwd=BACKEND, stdout=subprocess.PIPE, text=True)
    try:
        assert holder.stdout.readline().strip() == "held"
        with pytest.raises(storagelock.StorageInUse):
            with TestClient(create_app(storage, analyzer="none")):
                pass
    finally:
        holder.kill()
        holder.wait(10)
    with TestClient(create_app(storage, analyzer="none")):
        assert len(sweeper_threads()) == 1
