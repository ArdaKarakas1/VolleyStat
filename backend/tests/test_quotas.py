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
from contextlib import closing

import pytest
from fastapi.testclient import TestClient

from app import quotas, sweep
from app.main import create_app
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


def test_a_link_account_has_2_gib_and_one_unfinished_upload(client, storage, link_account):
    invited = link_account()
    match_id = create_match(invited)
    refused = refusal(create_upload(invited, match_id, size=2 * GIB + 1))
    assert refused == {"scope": "PERSONAL", "usedBytes": 0, "limitBytes": 2 * GIB}
    first = create_upload(invited, match_id, size=GIB)
    assert first.status_code == 201
    second = refusal(create_upload(invited, match_id, size=1))
    assert second == {"scope": "PERSONAL", "reason": "UNFINISHED_UPLOAD", "usedBytes": GIB, "limitBytes": 2 * GIB}
    # Once the first upload is a video, the account may start another one within its 2 GiB.
    set_upload(storage, first.json()["uploadId"], status="COMPLETED")
    assert create_upload(invited, match_id, size=GIB).status_code == 201
    set_upload(storage, upload_ids(storage)[-1], status="COMPLETED")
    assert refusal(create_upload(invited, match_id, size=1)) == {"scope": "PERSONAL", "usedBytes": 2 * GIB,
                                                                 "limitBytes": 2 * GIB}


def test_an_upload_being_verified_is_the_link_accounts_unfinished_upload(storage, link_account):
    invited = link_account()
    match_id = create_match(invited)
    upload_id = create_upload(invited, match_id, size=GIB).json()["uploadId"]
    set_upload(storage, upload_id, status="FINALIZING", finalize_started_at=time.time())
    assert refusal(create_upload(invited, match_id, size=1))["reason"] == "UNFINISHED_UPLOAD"
    set_upload(storage, upload_id, status="COMPLETED", finalize_started_at=None)
    assert create_upload(invited, match_id, size=1).status_code == 201


@pytest.mark.parametrize("signup_via", ["OPEN_MATCH", "INVITE", "SOMETHING_NEW", ""])
def test_an_unknown_signup_via_gets_the_link_quota(client, storage, signup_via):
    other = as_account(client, storage, "test.baska", signup_via)
    match_id = create_match(other)
    assert refusal(create_upload(other, match_id, size=2 * GIB + 1))["limitBytes"] == 2 * GIB
    assert create_upload(other, match_id, size=1).status_code == 201
    assert refusal(create_upload(other, match_id, size=1))["reason"] == "UNFINISHED_UPLOAD"


@pytest.mark.parametrize("signup_via", ["SIGNUP_CODE", "CAPTAIN_CODE", "OPEN"])
def test_an_account_opened_with_a_code_has_8_gib_and_no_limit_on_unfinished_uploads(client, storage, signup_via):
    other = as_account(client, storage, "test.kodlu", signup_via)
    match_id = create_match(other)
    for size in (GIB, GIB, 2 * GIB, 4 * GIB):
        assert create_upload(other, match_id, size=size).status_code == 201
    assert refusal(create_upload(other, match_id, size=1))["limitBytes"] == 8 * GIB


# --- the server-wide pool -------------------------------------------------------------------------

def test_twenty_link_accounts_fill_the_personal_pool_and_the_next_upload_answers_server(client, storage):
    for number in range(20):
        invited = as_account(client, storage, f"test.davetli{number}", "INVITE")
        assert create_upload(invited, create_match(invited), size=2 * GIB).status_code == 201
    details = refusal(create_upload(client, create_match(client), size=1))
    assert details == {"scope": "SERVER", "pool": "PERSONAL", "usedBytes": 40 * GIB, "limitBytes": 40 * GIB}
    assert len(upload_ids(storage)) == 20


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
        unfinished = create_upload(invited, match_id, size=2 * GIB).json()["uploadId"]
        personal_match(storage, "dolgu-hesabi", 40 * GIB)               # the pool is full without it
        disk.free = 20 * GIB                                              # and the disk has nothing to spare
        # Every limit is reached at once: the first check answers, then each next one as the one before it clears.
        assert refusal(create_upload(invited, match_id, size=1))["reason"] == "UNFINISHED_UPLOAD"
        set_upload(storage, unfinished, status="COMPLETED")
        details = refusal(create_upload(invited, match_id, size=1))
        assert details == {"scope": "PERSONAL", "usedBytes": 2 * GIB, "limitBytes": 2 * GIB}
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

def sweeper(client):
    return client.app.state.sweeper


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
