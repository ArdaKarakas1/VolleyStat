"""DELETE /v1/matches/{id}: the server keeps nothing of a match the user removed."""
import sqlite3
import time

from app import jobs
from tests.helpers import (
    BlockingAnalyzer, ScriptedAnalyzer, card_of, decide, ready_video, request_job, reviewed_job, rows, run_pending, serve,
    wait_for_state,
)
from tests.test_api import create_match, create_upload, set_upload

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
    set_upload(storage, upload_id, status="FINALIZING", finalize_started_at=time.time())

    refused = client.delete(f"/v1/matches/{match_id}")
    assert refused.status_code == 409 and refused.json()["error"]["code"] == "UPLOAD_FINALIZING"
    # Nothing was removed: the server refuses before it touches a row or a file.
    assert client.get(f"/v1/matches/{match_id}").status_code == 200
    assert media_files(storage) == [f"{upload_id}.bin"]

    # A verification that died long ago does not hold the match forever.
    set_upload(storage, upload_id, finalize_started_at=time.time() - 3600)
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
