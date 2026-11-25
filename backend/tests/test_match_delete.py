"""DELETE /v1/matches/{id}: the server keeps nothing of a match the user removed."""
import sqlite3
import time
from contextlib import contextmanager

import pytest

from app import accounts, jobs
from tests.helpers import (
    BlockingAnalyzer, ScriptedAnalyzer, as_account, card_of, decide, ready_video, request_job, reviewed_job, rows,
    run_pending, serve, session_for, user_id_of, wait_for_state,
)
from tests.test_api import create_match, create_upload, set_upload, uptime

TABLES_BY_MATCH = ("matches", "uploads", "videos", "analysis_jobs")


def media_files(storage):
    folder = storage / "media"
    return sorted(path.name for path in folder.iterdir()) if folder.exists() else []


def test_deleting_a_match_removes_everything_the_server_holds_for_it(storage, make_clip):
    with serve(storage, ScriptedAnalyzer()) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"], **{"Idempotency-Key": "first-tap"})
        assert run_pending(client) == 1
        decide(client, card_of(client, job["id"], 1), "HOME")
        assert len(rows(storage, "SELECT 1 FROM analysis_request_keys")) == 1
        kept_video, kept_job = reviewed_job(client, make_clip)
        decide(client, card_of(client, kept_job["id"], 1), "AWAY")
        match_id, kept_match = video["matchId"], kept_video["matchId"]
        assert len(media_files(storage)) == 2

        assert client.delete(f"/v1/matches/{match_id}").status_code == 204

        gone = client.get(f"/v1/matches/{match_id}")
        assert gone.status_code == 404 and gone.json()["error"]["code"] == "MATCH_NOT_FOUND"
        assert client.get(f"/v1/videos/{video['id']}").json()["error"]["code"] == "VIDEO_NOT_FOUND"
        assert client.get(f"/v1/videos/{video['id']}/content").status_code == 404
        assert client.get(f"/v1/analyses/{job['id']}").json()["error"]["code"] == "ANALYSIS_NOT_FOUND"
        assert client.get(f"/v1/matches/{match_id}/report").json()["error"]["code"] == "MATCH_NOT_FOUND"

        # Nothing of the match is left in any table, and its bytes are gone.
        assert [row["id"] for row in rows(storage, "SELECT id FROM matches")] == [kept_match]
        assert {row["match_id"] for row in rows(storage, "SELECT match_id FROM uploads")} == {kept_match}
        assert {row["match_id"] for row in rows(storage, "SELECT match_id FROM videos")} == {kept_match}
        assert [row["id"] for row in rows(storage, "SELECT id FROM analysis_jobs")] == [kept_job["id"]]
        for table in ("rallies", "review_cards", "review_decisions"):
            assert {row["job_id"] for row in rows(storage, f"SELECT job_id FROM {table}")} == {kept_job["id"]}, table
        assert rows(storage, "SELECT 1 FROM analysis_request_keys WHERE video_id=?", video["id"]) == []
        assert media_files(storage) == [f"{rows(storage, 'SELECT upload_id FROM videos')[0]['upload_id']}.bin"]

        # The other match is untouched and still reports.
        assert client.get(f"/v1/matches/{kept_match}").status_code == 200
        assert client.get(f"/v1/matches/{kept_match}/report").status_code == 200


def test_deleting_a_match_removes_an_unfinished_upload_and_its_bytes(client, storage):
    match_id = create_match(client)
    upload_id = create_upload(client, match_id, size=5).json()["uploadId"]
    assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abc").status_code == 200
    assert media_files(storage) == [f"{upload_id}.bin"]

    assert client.delete(f"/v1/matches/{match_id}").status_code == 204

    assert media_files(storage) == []
    assert client.get(f"/v1/uploads/{upload_id}").status_code == 404
    assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=3", content=b"de").status_code == 404
    for table in TABLES_BY_MATCH:
        assert rows(storage, f"SELECT 1 FROM {table}") == [], table


def test_a_match_without_any_upload_can_be_deleted(client, storage):
    match_id = create_match(client)
    assert client.delete(f"/v1/matches/{match_id}").status_code == 204
    assert rows(storage, "SELECT 1 FROM matches") == []


def test_deleting_an_unknown_or_already_deleted_match_answers_404(client):
    unknown = client.delete("/v1/matches/no-such-match")
    assert unknown.status_code == 404 and unknown.json()["error"]["code"] == "MATCH_NOT_FOUND"
    match_id = create_match(client)
    assert client.delete(f"/v1/matches/{match_id}").status_code == 204
    again = client.delete(f"/v1/matches/{match_id}")
    assert again.status_code == 404 and again.json()["error"]["code"] == "MATCH_NOT_FOUND"


def test_a_match_whose_upload_is_being_verified_is_not_deleted_midway(client, storage):
    match_id = create_match(client)
    upload_id = create_upload(client, match_id, size=5).json()["uploadId"]
    client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abcde")
    # A request of this server checks the file: the row holds its token.
    set_upload(storage, upload_id, status="FINALIZING", finalize_started_uptime=uptime(client), finalize_token="running")

    refused = client.delete(f"/v1/matches/{match_id}")
    assert refused.status_code == 409 and refused.json()["error"]["code"] == "UPLOAD_FINALIZING"
    # Nothing was removed: the server refuses before it touches a row or a file.
    assert client.get(f"/v1/matches/{match_id}").status_code == 200
    assert media_files(storage) == [f"{upload_id}.bin"]

    # A verification that died an hour of uptime ago does not hold the match forever. The wall clock is not read.
    set_upload(storage, upload_id, finalize_started_uptime=uptime(client) - 3600, finalize_started_at=time.time())
    assert client.delete(f"/v1/matches/{match_id}").status_code == 204
    assert media_files(storage) == []


def test_deleting_a_match_stops_its_running_job(storage, make_clip, monkeypatch):
    # Without these two, the run would also notice the missing job row by itself within half a second.
    # With them only the release that the route sends can stop the analyzer inside the wait below.
    monkeypatch.setattr(jobs, "PROGRESS_WRITE_SECONDS", 3600.0)
    monkeypatch.setattr(jobs, "HEARTBEAT_INTERVAL_SECONDS", 3600.0)
    analyzer = BlockingAnalyzer()
    with serve(storage, analyzer, start_worker=True) as client:
        video = ready_video(client, make_clip)
        other_video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        assert analyzer.started.wait(10)

        assert client.delete(f"/v1/matches/{video['matchId']}").status_code == 204

        assert analyzer.interrupted.wait(10)
        assert client.get(f"/v1/analyses/{job['id']}").status_code == 404
        # The worker survived the loss of its job.
        analyzer.release.set()
        follower = request_job(client, other_video["id"])
        wait_for_state(client, follower["id"], "NEEDS_REVIEW")
        assert [row["id"] for row in rows(storage, "SELECT id FROM analysis_jobs")] == [follower["id"]]


def database_bytes(storage):
    """Every byte of the database, its write-ahead log included."""
    return b"".join(path.read_bytes() for path in sorted(storage.glob("volleystat.sqlite3*")))


def test_a_deleted_match_can_not_be_read_from_the_database_files(client, storage):
    # A second connection that stays open keeps SQLite from clearing its write-ahead log by itself
    # when a request ends, as on a busy server. The log then still holds the rows as first written.
    bystander = sqlite3.connect(storage / "volleystat.sqlite3")
    try:
        assert bystander.execute("SELECT COUNT(*) FROM matches").fetchone() == (0,)  # the connection is really open now
        deleted_match_leaves_no_bytes(client, storage)
    finally:
        bystander.close()


def deleted_match_leaves_no_bytes(client, storage):
    marks = {"homeTeamName": "ZebraKulubuXQ7", "awayTeamName": "YakutSporQW8", "name": "GizliKupaMM9", "venue": "GizliSalonVV3"}
    match_id = client.post("/v1/matches", json=marks).json()["id"]
    kept = client.post("/v1/matches", json={"homeTeamName": "KalanTakimAA1", "awayTeamName": "KalanTakimBB2"}).json()["id"]
    upload_id = create_upload(client, match_id, size=5, fileName="GizliDosyaKK5.mp4").json()["uploadId"]
    assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abc").status_code == 200
    before = database_bytes(storage)
    assert all(mark.encode() in before for mark in (*marks.values(), "GizliDosyaKK5.mp4")), "the test can not see what it looks for"

    assert client.delete(f"/v1/matches/{match_id}").status_code == 204

    after = database_bytes(storage)
    for mark in (*marks.values(), "GizliDosyaKK5.mp4", match_id, upload_id):
        assert mark.encode() not in after, mark
    # The other match is still there, so the check did not pass on an emptied file.
    assert b"KalanTakimAA1" in after and client.get(f"/v1/matches/{kept}").status_code == 200


def test_deleting_a_match_removes_bytes_stored_in_the_layout_of_the_0_2_preview(storage, make_clip):
    with serve(storage, ScriptedAnalyzer()) as client:
        video = ready_video(client, make_clip)
        partial = create_upload(client, video["matchId"], size=5).json()["uploadId"]
        # The 0.2 preview kept a finished video under videos/ and an unfinished upload under uploads/.
        old_video = storage / "videos" / f"{video['id']}.bin"
        old_part = storage / "uploads" / f"{partial}.part"
        for path in (old_video, old_part):
            path.parent.mkdir(exist_ok=True)
            path.write_bytes(b"old bytes")
        with sqlite3.connect(storage / "volleystat.sqlite3") as db:
            db.execute("UPDATE videos SET storage_key=NULL WHERE id=?", (video["id"],))

        assert client.delete(f"/v1/matches/{video['matchId']}").status_code == 204

        assert not old_video.exists() and not old_part.exists()
        assert media_files(storage) == []


# --- account deletion: the account's personal matches go with it (design 3.7, steps 4-6) -----------

PASSWORD = "uzun-ve-tuhaf-bir-sifre"
DELETED_USER, DELETED_NAME = "silinecek.kisi", "SilinecekKisiQX1"


@pytest.fixture
def cheap_hashing(monkeypatch):
    monkeypatch.setattr(accounts, "SCRYPT_N", 2 ** 10)


@contextmanager
def deleting_account(storage, analyzer=None, start_worker=False):
    """A client signed in as an account with a real password, and the account's id."""
    session_for(storage, DELETED_USER, display_name=DELETED_NAME, password=PASSWORD)
    with serve(storage, analyzer, start_worker=start_worker, user=DELETED_USER) as client:
        yield client, user_id_of(storage, DELETED_USER)


def delete_account(client, password=PASSWORD):
    return client.post("/v1/me/delete", json={"password": password})


def two_matches_of_the_account(client, make_clip):
    """A match with a reviewed video and a match with an unfinished upload, with marks to search for."""
    video = ready_video(client, make_clip)
    job = request_job(client, video["id"])
    assert run_pending(client) == 1
    decide(client, card_of(client, job["id"], 1), "HOME", idempotencyKey="hesap-karari")
    reviewed = video["matchId"]
    client.put(f"/v1/matches/{reviewed}/rules", json={"setsToWin": 2, "setTarget": 21, "decidingSetTarget": 15,
                                                      "expectedRules": {"setsToWin": 3, "setTarget": 25, "decidingSetTarget": 15}})
    unfinished = create_match(client, homeTeamName="HesapEvTakimiQX2", awayTeamName="HesapKonukTakimiQX3")
    upload_id = create_upload(client, unfinished, size=5, fileName="HesapDosyasiQX4.mp4").json()["uploadId"]
    assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abc").status_code == 200
    return reviewed, unfinished


def account_leaves_no_trace(client, storage, user_id, make_clip):
    reviewed, unfinished = two_matches_of_the_account(client, make_clip)
    with sqlite3.connect(storage / "volleystat.sqlite3") as db:
        db.execute("UPDATE matches SET home_team='HesapIlkEvQX5', away_team='HesapIlkKonukQX6' WHERE id=?", (reviewed,))
    marks = (DELETED_USER, DELETED_NAME, user_id, "HesapIlkEvQX5", "HesapIlkKonukQX6", "HesapEvTakimiQX2",
             "HesapKonukTakimiQX3", "HesapDosyasiQX4.mp4", reviewed, unfinished)
    before = database_bytes(storage)
    assert all(mark.encode() in before for mark in marks), "the test can not see what it looks for"
    assert len(media_files(storage)) == 2

    assert delete_account(client).status_code == 204

    for table in ("users", "sessions", "matches", "uploads", "videos", "analysis_jobs", "rallies", "review_cards",
                  "review_decisions", "rally_proposals", "analysis_request_keys"):
        assert rows(storage, f"SELECT 1 FROM {table}") == [], table
    assert media_files(storage) == []
    after = database_bytes(storage)
    for mark in marks:
        assert mark.encode() not in after, mark


def test_deleting_the_account_deletes_its_matches_and_leaves_no_trace(storage, make_clip, cheap_hashing):
    with deleting_account(storage, ScriptedAnalyzer()) as (client, user_id):
        account_leaves_no_trace(client, storage, user_id, make_clip)
        assert client.get("/v1/me").status_code == 401


def test_deleting_the_account_leaves_no_trace_while_another_connection_holds_the_log_open(storage, make_clip, cheap_hashing):
    with deleting_account(storage, ScriptedAnalyzer()) as (client, user_id):
        bystander = sqlite3.connect(storage / "volleystat.sqlite3")
        try:
            assert bystander.execute("SELECT COUNT(*) FROM users").fetchone() == (1,)
            account_leaves_no_trace(client, storage, user_id, make_clip)
        finally:
            bystander.close()


def test_deleting_the_account_keeps_another_accounts_matches_and_legacy_matches(storage, cheap_hashing):
    with deleting_account(storage) as (client, user_id):
        own = create_match(client)
        once_own = create_match(client)
        other = as_account(client, storage, "test.kalan")
        kept = create_match(other)
        kept_upload = create_upload(other, kept, size=5).json()["uploadId"]
        # A match stored before accounts belongs to nobody, also when this account uploaded it.
        with sqlite3.connect(storage / "volleystat.sqlite3") as db:
            db.execute("UPDATE matches SET status='LEGACY', owner_user_id=NULL WHERE id=?", (once_own,))
            db.execute("UPDATE uploads SET started_by=? WHERE id=?", (user_id, kept_upload))
        assert delete_account(client).status_code == 204
        assert {row["id"] for row in rows(storage, "SELECT id FROM matches")} == {kept, once_own}
        assert own not in {row["id"] for row in rows(storage, "SELECT id FROM matches")}
        assert other.get(f"/v1/matches/{kept}").status_code == 200
        # No row names the deleted account any more.
        assert rows(storage, "SELECT started_by FROM uploads WHERE id=?", kept_upload) == [{"started_by": None}]
        assert media_files(storage) == [f"{kept_upload}.bin"]


def test_deleting_the_account_while_an_upload_is_being_verified_answers_409_and_deletes_nothing(storage, cheap_hashing):
    with deleting_account(storage) as (client, user_id):
        bare = create_match(client)
        match_id = create_match(client)
        upload_id = create_upload(client, match_id, size=5).json()["uploadId"]
        client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abcde")
        set_upload(storage, upload_id, status="FINALIZING", finalize_started_uptime=uptime(client),
                   finalize_token="running")
        before = database_bytes(storage)
        refused = delete_account(client)
        assert (refused.status_code, refused.json()["error"]["code"]) == (409, "UPLOAD_FINALIZING")
        assert {row["id"] for row in rows(storage, "SELECT id FROM matches")} == {bare, match_id}
        assert rows(storage, "SELECT COUNT(*) AS n FROM users") == [{"n": 1}]
        assert media_files(storage) == [f"{upload_id}.bin"]
        assert client.get("/v1/me").status_code == 200
        # A verification that died an hour of uptime ago does not hold the account forever.
        set_upload(storage, upload_id, finalize_started_uptime=uptime(client) - 3600, finalize_started_at=time.time())
        assert delete_account(client).status_code == 204
        assert media_files(storage) == [] and rows(storage, "SELECT 1 FROM matches") == []


def test_deleting_the_account_right_after_a_restart_deletes_an_upload_whose_verification_the_stop_cut(
        storage, cheap_hashing):
    """The server stopped while it verified an upload of the account. When the server starts again no other process
    can run a verification in its storage folder, so the start frees that verification, and the account deletion
    right after the start deletes everything."""
    with deleting_account(storage) as (client, user_id):
        match_id = create_match(client)
        upload_id = create_upload(client, match_id, size=5).json()["uploadId"]
        client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abcde")
        set_upload(storage, upload_id, status="FINALIZING", finalize_started_uptime=uptime(client),
                   finalize_token="cut")
        refused = delete_account(client)
        assert (refused.status_code, refused.json()["error"]["code"]) == (409, "UPLOAD_FINALIZING")
    with deleting_account(storage, start_worker=True) as (client, user_id):
        assert delete_account(client).status_code == 204
        assert media_files(storage) == [] and rows(storage, "SELECT 1 FROM matches") == []
        assert rows(storage, "SELECT COUNT(*) AS n FROM users") == [{"n": 0}]


def test_a_changed_password_during_the_deletion_deletes_no_byte(storage, make_clip, cheap_hashing, monkeypatch):
    with deleting_account(storage, ScriptedAnalyzer()) as (client, user_id):
        two_matches_of_the_account(client, make_clip)
        tables = {table: rows(storage, f"SELECT * FROM {table}") for table in ("matches", "uploads", "videos", "users")}
        files = media_files(storage)
        original = accounts.verify_password
        done = []

        def change_password_in_between(*arguments):
            result = original(*arguments)
            if not done:
                done.append(True)
                with sqlite3.connect(storage / "volleystat.sqlite3") as db:
                    db.execute("UPDATE users SET password_hash=?", (accounts.hash_password("baska-bir-sifre-9"),))
            return result

        monkeypatch.setattr(accounts, "verify_password", change_password_in_between)
        late = delete_account(client)
        assert (late.status_code, late.json()["error"]["code"]) == (403, "PASSWORD_WRONG")
        assert media_files(storage) == files
        for table, before in tables.items():
            if table != "users":
                assert rows(storage, f"SELECT * FROM {table}") == before, table
        assert len(rows(storage, "SELECT 1 FROM users")) == 1


def test_a_wrong_password_deletes_nothing(storage, cheap_hashing):
    with deleting_account(storage) as (client, user_id):
        match_id = create_match(client)
        refused = delete_account(client, password="baska-uzun-bir-sifre-42")
        assert (refused.status_code, refused.json()["error"]["code"]) == (403, "PASSWORD_WRONG")
        assert client.get(f"/v1/matches/{match_id}").status_code == 200


def test_deleting_the_account_stops_its_running_analysis(storage, make_clip, cheap_hashing, monkeypatch):
    monkeypatch.setattr(jobs, "PROGRESS_WRITE_SECONDS", 3600.0)
    monkeypatch.setattr(jobs, "HEARTBEAT_INTERVAL_SECONDS", 3600.0)
    analyzer = BlockingAnalyzer()
    with deleting_account(storage, analyzer, start_worker=True) as (client, user_id):
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        assert analyzer.started.wait(10)

        assert delete_account(client).status_code == 204

        assert analyzer.interrupted.wait(10)
        assert rows(storage, "SELECT 1 FROM analysis_jobs") == []
        analyzer.release.set()
